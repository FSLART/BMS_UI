"""In-memory log ring buffer, so the UI can show what the console shows.

python-can probes every vendor driver it knows at import time and logs a line
for each one it cannot load. That noise is genuinely useful when a CAN adapter
is not being detected, but it only ever reached the terminal. This captures it
-- plus our own connection events -- and serves it to the in-app console.
"""

from __future__ import annotations

import logging
import threading
from collections import deque
from typing import Any

CAPACITY = 800

# Loggers that would otherwise flood the buffer with per-frame chatter.
# cantools warns once per duplicated frame id while parsing; the handcart DBC
# defines the IVT ids twice on purpose, and the merge in dbcstore reports what
# it actually kept, which is the line worth reading.
MUTED = ("uvicorn.access", "websockets", "asyncio", "watchfiles",
         "cantools.database")


class RingBufferHandler(logging.Handler):
    def __init__(self, capacity: int = CAPACITY) -> None:
        super().__init__()
        self.records: deque[dict[str, Any]] = deque(maxlen=capacity)
        self._seq = 0
        self._lock = threading.Lock()

    def emit(self, record: logging.LogRecord) -> None:
        if record.name.startswith(MUTED):
            return
        try:
            message = record.getMessage()
        except Exception:  # noqa: BLE001 - a broken log line must not crash us
            message = str(record.msg)
        if record.exc_info:
            message = f"{message}\n{logging.Formatter().formatException(record.exc_info)}"

        with self._lock:
            self._seq += 1
            self.records.append({
                "seq": self._seq,
                "ts": record.created,
                "level": record.levelname,
                "logger": record.name,
                "message": message,
            })

    def since(self, seq: int) -> dict[str, Any]:
        with self._lock:
            items = [r for r in self.records if r["seq"] > seq]
            return {"entries": items, "seq": self._seq, "dropped": self._seq - len(self.records) > seq}

    def clear(self) -> None:
        with self._lock:
            self.records.clear()


buffer = RingBufferHandler()


def install() -> None:
    """Attach to the root logger. Call before anything imports `can`."""
    root = logging.getLogger()
    if buffer not in root.handlers:
        root.addHandler(buffer)
    if root.level > logging.INFO or root.level == logging.NOTSET:
        root.setLevel(logging.INFO)
    logging.captureWarnings(True)          # warnings.warn() -> py.warnings logger


log = logging.getLogger("bms")
