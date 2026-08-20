"""DBC resolution, caching and merging.

The car is monitored in a paddock, in a garage, at a track — places where there
is often no internet. So the network is never on the critical path: each DBC is
looked up locally first, and a successful download is only ever a bonus that
gets written to the cache for the next time there is no connection.

Resolution order per file, first hit wins:

  1. `DbcSource.local_dir`  — a checkout on this machine (the dev case)
  2. cache                  — last copy successfully downloaded, in the user's
                              app-data directory
  3. vendored               — the copy committed next to this package, so a
                              fresh clone with no internet still works
  4. download               — GitHub raw, and only if 1-3 all missed

A download is attempted in the background even when a local copy was found, so
the cache keeps up with the repo without ever blocking a connection. If it
fails, nothing happens: the file already in hand is the one in use.

Multiple files are merged into a single database. On a frame id defined by more
than one file the FIRST file in `DbcSource.files` wins — the primary database
keeps its definitions, and the others only add what it does not cover. That
matters for the TEK-26e: the handcart database repeats the ISA IVT ids with a
coarser definition than the powertrain one.
"""

from __future__ import annotations

import re
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.request import urlopen

from .cars import DbcSource
from .logbuffer import log

DOWNLOAD_TIMEOUT_S = 6.0

# Committed with the source. Guarantees a working decoder on a machine that has
# never been online and never had the CAN repo checked out.
VENDORED_DIR = Path(__file__).parent / "dbc"


def cache_dir() -> Path:
    """Per-user, persistent. Deliberately not tempfile.gettempdir(): a temp
    directory is exactly the thing that gets wiped between the download and the
    day at the track with no signal."""
    if sys.platform.startswith("win"):
        base = Path.home() / "AppData" / "Local" / "BMS_UI"
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support" / "BMS_UI"
    else:
        base = Path.home() / ".local" / "share" / "bms_ui"
    d = base / "dbc"
    d.mkdir(parents=True, exist_ok=True)
    return d


def raw_url(src: DbcSource, rel: str) -> str:
    """GitHub raw URL for one file named by a DbcSource.

    Accepts the repo as an https URL, an ssh remote, or plain `owner/name`.
    Returns "" for anything that is not GitHub, which simply disables the
    download step — the local copies still work.
    """
    if not src.repo or not rel:
        return ""
    m = re.search(r"github\.com[:/]+([^/]+)/([^/]+?)(?:\.git)?/?$", src.repo)
    if not m:
        m = re.fullmatch(r"([\w.-]+)/([\w.-]+)", src.repo.strip())
    if not m:
        return ""
    return f"https://raw.githubusercontent.com/{m.group(1)}/{m.group(2)}/{src.ref}/{rel}"


ORIGIN_LABEL = {
    "local": "checkout local",
    "cache": "cache offline",
    "vendored": "copia incluida na app",
    "download": "descarregada do repositorio",
}


@dataclass(slots=True)
class DbcFile:
    rel: str
    path: Path
    origin: str            # local | cache | vendored | download

    @property
    def describe(self) -> str:
        return f"{self.path.name} ({ORIGIN_LABEL[self.origin]})"


def _cached(rel: str) -> Path:
    return cache_dir() / Path(rel).name


def _vendored(rel: str) -> Path:
    return VENDORED_DIR / Path(rel).name


def download(src: DbcSource, rel: str) -> Path | None:
    """Fetch one DBC and write it into the cache. None on any failure.

    Never raises: being offline is the normal case this whole module exists to
    survive, not an error worth propagating.
    """
    url = raw_url(src, rel)
    if not url:
        return None
    dest = _cached(rel)
    try:
        with urlopen(url, timeout=DOWNLOAD_TIMEOUT_S) as response:
            content = response.read()
        if not content:
            raise ValueError("resposta vazia")
        # Write beside the target then move, so an interrupted download can
        # never leave a truncated file where a valid cache used to be.
        tmp = dest.with_suffix(dest.suffix + ".part")
        tmp.write_bytes(content)
        tmp.replace(dest)
        log.info("DBC atualizada da rede: %s (%d bytes)", Path(rel).name, len(content))
        return dest
    except Exception as exc:  # noqa: BLE001 - offline is expected, not exceptional
        log.info("Sem rede para %s (%s) - a usar copia local", Path(rel).name, exc)
        return None


def resolve_one(src: DbcSource, rel: str, refresh: bool = True) -> DbcFile | None:
    """Best copy of one DBC available right now, plus where it came from."""
    if src.local_dir:
        p = Path(src.local_dir) / rel
        if p.is_file():
            # Opportunistic: keep the cache current for the machines that will
            # not have this checkout, but never let it delay the connection.
            if refresh:
                download(src, rel)
            return DbcFile(rel, p, "local")

    cached = _cached(rel)
    vendored = _vendored(rel)

    if refresh and (fresh := download(src, rel)):
        return DbcFile(rel, fresh, "download")
    if cached.is_file():
        return DbcFile(rel, cached, "cache")
    if vendored.is_file():
        # Seed the cache from the vendored copy so later runs have one place to
        # look whichever way they got here.
        try:
            shutil.copy2(vendored, cached)
        except OSError:
            pass
        return DbcFile(rel, vendored, "vendored")

    log.error("Sem DBC disponivel para %s: nem local, nem cache (%s), nem incluida (%s)",
              rel, cached, vendored)
    return None


def _read(path: Path):
    """Parse one file.

    DBCs exported by Vector are usually cp1252, not UTF-8 — the degree sign in
    the IVT temperature unit is enough to break a strict UTF-8 read — so the
    encodings are tried in turn rather than assumed.
    """
    import cantools

    last: Exception | None = None
    for enc in ("utf-8", "cp1252", "latin-1"):
        try:
            with open(path, "r", encoding=enc) as f:
                return cantools.database.load(f, database_format="dbc")
        except UnicodeDecodeError as exc:
            last = exc
    raise RuntimeError(f"Falha a descodificar {path}: {last}")


def load(src: DbcSource, refresh: bool = True):
    """Resolve every file for this car and merge them into one database.

    Returns (database, [DbcFile]). Raises only when nothing at all was found:
    a missing secondary file is logged and skipped, because the charger
    database being unavailable should not stop the pack from being monitored.
    """
    import cantools

    files = src.files or []
    if not files:
        raise FileNotFoundError("Este carro nao tem nenhuma DBC configurada")

    found: list[DbcFile] = []
    for rel in files:
        f = resolve_one(src, rel, refresh=refresh)
        if f is not None:
            found.append(f)

    if not found:
        raise FileNotFoundError(
            "Nenhuma DBC disponivel para este carro. Liga a internet uma vez, "
            "ou aponta CarProfile.dbc.local_dir para um checkout do repositorio."
        )

    db = cantools.database.can.Database()
    seen: set[int] = set()
    for f in found:
        part = _read(f.path)
        added = shadowed = 0
        for msg in part.messages:
            if msg.frame_id in seen:
                shadowed += 1
                continue
            seen.add(msg.frame_id)
            db.messages.append(msg)
            added += 1
        log.info("DBC carregada: %s - %d mensagens%s",
                 f.describe, added,
                 f" ({shadowed} ignoradas, ja definidas por uma DBC anterior)" if shadowed else "")
    db.refresh()

    if len(found) < len(files):
        missing = [r for r in files if r not in {f.rel for f in found}]
        log.warning("DBC em falta, a continuar sem ela: %s", ", ".join(missing))

    return db, found
