"""Tabela de simbolos de um ELF, sem dependencias.

Para ler variaveis do firmware pelo servidor GDB e preciso saber em que
endereco elas vivem. O servidor nao sabe: o Black Magic so fala em enderecos,
nunca em nomes. Quem sabe e o `.elf` que foi gravado no STM32.

Le-se so a `.symtab`, que e uma tabela fixa de 16 bytes por entrada. A
informacao de tipos esta na DWARF, que e outra ordem de grandeza de trabalho --
e nao e precisa aqui, porque a disposicao da struct esta escrita a mao em
`decode/live_struct.py` e validada contra o `st_size` que vem daqui.

Restringido a ELF32 little-endian ARM, que e o unico que este projeto ve.
"""

from __future__ import annotations

import re
import struct
from dataclasses import dataclass
from pathlib import Path

ELF_MAGIC = b"\x7fELF"
ELFCLASS32 = 1
ELFDATA2LSB = 1
EM_ARM = 40

SHT_SYMTAB = 2
STT_OBJECT = 1

# Um .elf do STM32F412 com DWARF completa anda pelos 3 MB. 64 MB e folga a mais
# do que suficiente, e evita ler para memoria um ficheiro que nao e um ELF.
MAX_ELF_BYTES = 64 * 1024 * 1024


class ElfError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class Symbol:
    name: str
    addr: int
    size: int


def _cstr(data: bytes, base: int, offset: int) -> str:
    start = base + offset
    end = data.find(b"\0", start)
    if end < 0:
        return ""
    return data[start:end].decode("utf-8", "replace")


def read_symbols(path: str | Path) -> dict[str, Symbol]:
    """Variaveis globais e estaticas do ELF, por nome.

    So objetos (`STT_OBJECT`): funcoes e etiquetas de seccao nao interessam a
    quem quer ler memoria, e deixa-las de fora evita que um nome de funcao
    tape uma variavel com o mesmo nome.
    """
    p = Path(path)
    size = p.stat().st_size
    if size > MAX_ELF_BYTES:
        raise ElfError(f"ficheiro demasiado grande para um .elf ({size // (1024 * 1024)} MB)")
    data = p.read_bytes()

    if len(data) < 52 or data[:4] != ELF_MAGIC:
        raise ElfError("nao e um ficheiro ELF")
    if data[4] != ELFCLASS32 or data[5] != ELFDATA2LSB:
        raise ElfError("so ELF32 little-endian e suportado")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != EM_ARM:
        raise ElfError(f"ELF nao e ARM (e_machine={machine})")

    e_shoff = struct.unpack_from("<I", data, 0x20)[0]
    e_shentsize, e_shnum = struct.unpack_from("<HH", data, 0x2E)
    if not e_shoff or not e_shnum:
        raise ElfError("ELF sem tabela de seccoes")

    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if off + 40 > len(data):
            raise ElfError("tabela de seccoes truncada")
        name, sh_type, _flags, _addr, sh_off, sh_size, link, _info, _align, entsize = \
            struct.unpack_from("<10I", data, off)
        sections.append((name, sh_type, sh_off, sh_size, link, entsize))

    symtab = next((s for s in sections if s[1] == SHT_SYMTAB), None)
    if symtab is None:
        raise ElfError("ELF sem .symtab (foi compilado sem simbolos?)")

    _n, _t, sym_off, sym_size, strtab_idx, entsize = symtab
    if entsize != 16 or strtab_idx >= len(sections):
        raise ElfError(".symtab com formato inesperado")
    str_off = sections[strtab_idx][2]

    out: dict[str, Symbol] = {}
    for i in range(sym_size // entsize):
        off = sym_off + i * entsize
        st_name, st_value, st_size, st_info, _other, _shndx = \
            struct.unpack_from("<IIIBBH", data, off)
        if (st_info & 0xF) != STT_OBJECT or not st_name:
            continue
        name = _cstr(data, str_off, st_name)
        if not name:
            continue
        # Globais ganham a locais com o mesmo nome: um `static` de outra unidade
        # de compilacao nao deve tapar a variavel que o resto do firmware ve.
        is_global = (st_info >> 4) != 0
        if name in out and not is_global:
            continue
        out[name] = Symbol(name=name, addr=st_value, size=st_size)
    return out


# ---------------------------------------------------------------------------
# Ficheiros escolhidos pelo utilizador
# ---------------------------------------------------------------------------

def store_dir() -> Path:
    """Onde ficam os `.elf` que alguem escolheu na interface.

    A pasta por utilizador e a mesma que guarda as DBCs; e de la que vem o
    caminho base, para nao haver duas definicoes de "onde e que esta app guarda
    coisas" a divergir com o tempo.
    """
    from .dbcstore import cache_dir

    d = cache_dir().parent / "elf"
    d.mkdir(parents=True, exist_ok=True)
    return d


def accept_upload(name: str, data: bytes) -> dict:
    """Guarda um `.elf` do utilizador, mas so se servir para o que e preciso.

    Rejeitar aqui, com o ficheiro a frente, e melhor do que rejeitar na
    ligacao: quem escolheu o ficheiro errado fica a saber logo, em vez de ver
    uma ligacao a falhar sem perceber porque.

    O nome e reduzido ao ultimo componente e despido de tudo o que nao seja um
    caractere de nome de ficheiro, para que um nome preparado nao consiga
    escrever fora de `store_dir()`.
    """
    from .decode import live_struct

    safe = re.sub(r"[^A-Za-z0-9._-]", "_", Path(name).name) or "firmware.elf"
    if not safe.lower().endswith(".elf"):
        safe += ".elf"
    dest = store_dir() / safe

    tmp = dest.with_suffix(dest.suffix + ".part")
    tmp.write_bytes(data)
    try:
        syms = read_symbols(tmp)
    except (OSError, ElfError) as exc:
        tmp.unlink(missing_ok=True)
        return {"ok": False, "error": f"Nao e um .elf utilizavel: {exc}"}

    live = syms.get(live_struct.SYMBOL)
    if live is None:
        tmp.unlink(missing_ok=True)
        return {"ok": False, "error": f"Este .elf nao tem a variavel '{live_struct.SYMBOL}'"}
    if live.size != live_struct.STRUCT_SIZE:
        tmp.unlink(missing_ok=True)
        return {
            "ok": False,
            "error": (
                f"'{live_struct.SYMBOL}' tem {live.size} bytes neste firmware e este "
                f"programa espera {live_struct.STRUCT_SIZE}: a struct mudou"
            ),
        }

    tmp.replace(dest)
    return {
        "ok": True,
        "path": str(dest),
        "name": safe,
        "symbol": live_struct.SYMBOL,
        "address": f"0x{live.addr:08x}",
        "size": live.size,
    }
