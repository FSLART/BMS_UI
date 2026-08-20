"""Where the data files are, in dev and inside a PyInstaller build.

A frozen `--onefile` executable unpacks itself into a temporary directory and
points `sys._MEIPASS` at it. Anything computed from `__file__` lands inside the
bundled library instead, so the frontend, the vendored DBCs and the window icon
all have to be looked up through here.

Paths are given relative to the repository root, and `compile.py` bundles them
at the same relative paths so both cases resolve identically.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Repository root when running from source: backend/resources.py -> up two.
_SOURCE_ROOT = Path(__file__).resolve().parent.parent


def root() -> Path:
    return Path(getattr(sys, "_MEIPASS", _SOURCE_ROOT))


def resource_path(*parts: str) -> Path:
    """Absolute path to a bundled file, e.g. resource_path("frontend", "pics")."""
    return root().joinpath(*parts)


def frozen() -> bool:
    return getattr(sys, "frozen", False)
