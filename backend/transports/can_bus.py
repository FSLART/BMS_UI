"""python-can transport.

The AMS fills the bus: 12 slaves x 7 messages plus the master block and the IVT
sensor is well over a thousand frames a second, while the UI repaints ten times
a second. Handing every frame to asyncio individually would burn most of the
loop on scheduling for numbers nobody sees.

So the bus is read by a plain thread into a bounded deque, and the caller drains
the whole batch once per UI tick. `frames()` is still provided for the Transport
contract, but `drain()` is the method the manager actually uses.
"""

from __future__ import annotations

import asyncio
import threading
import time
from collections import deque
from typing import Any, AsyncIterator

from ..logbuffer import log
from ..state import LinkType
from .base import RawFrame, Transport, TransportError

# Roughly two seconds of a busy bus. Beyond that the reader is so far ahead of
# the UI that the oldest frames describe a pack state nobody cares about any
# more, so they are dropped rather than queued forever.
QUEUE_MAX = 4096


class CanTransport(Transport):
    link_type = LinkType.CAN

    def __init__(self, config: dict[str, Any]) -> None:
        super().__init__(config)
        self.channel: str = str(config.get("channel") or "")
        self.backend: str = str(config.get("backend") or "slcan")
        self.bitrate: int = int(config.get("bitrate") or 500_000)

        self._bus: Any = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._q: deque[RawFrame] = deque(maxlen=QUEUE_MAX)
        self._error: str | None = None
        self.dropped = 0
        self.received = 0

    @property
    def describe(self) -> str:
        return f"{self.backend}:{self.channel} @ {self.bitrate // 1000} kbit/s"

    # -- lifecycle -----------------------------------------------------------

    async def open(self) -> None:
        try:
            import can
        except ImportError as exc:
            raise TransportError("python-can nao instalado (pip install python-can)") from exc

        if not self.channel:
            raise TransportError("Nenhum canal CAN selecionado")

        kwargs: dict[str, Any] = {"channel": self.channel, "interface": self.backend}
        # socketcan carries the bitrate on the link itself (`ip link set can0
        # type can bitrate ...`); passing it here is an error there and required
        # everywhere else.
        if self.backend != "socketcan":
            kwargs["bitrate"] = self.bitrate

        def _open() -> Any:
            return can.interface.Bus(**kwargs)

        try:
            self._bus = await asyncio.to_thread(_open)
        except Exception as exc:  # noqa: BLE001 - vendor drivers raise anything
            raise TransportError(f"Nao foi possivel abrir {self.describe}: {exc}") from exc

        self._opened_at = time.time()
        self._stop.clear()
        self._thread = threading.Thread(target=self._reader, name="can-rx", daemon=True)
        self._thread.start()
        log.info("Barramento CAN aberto: %s", self.describe)

    async def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            await asyncio.to_thread(self._thread.join, 1.5)
            self._thread = None
        if self._bus is not None:
            bus, self._bus = self._bus, None
            try:
                await asyncio.to_thread(bus.shutdown)
            except Exception as exc:  # noqa: BLE001 - closing must never raise
                log.debug("Erro ao fechar o barramento CAN: %s", exc)
            log.info("Barramento CAN fechado")

    # -- reader thread -------------------------------------------------------

    def _reader(self) -> None:
        """Blocking recv loop. The only place python-can is touched directly."""
        import can

        while not self._stop.is_set():
            try:
                msg = self._bus.recv(timeout=0.2)
            except Exception as exc:  # noqa: BLE001 - adapter unplugged, bus off...
                if self._stop.is_set():
                    return
                self._error = str(exc)
                log.error("Erro de leitura do CAN: %s", exc)
                return
            if msg is None:
                continue
            if len(self._q) == QUEUE_MAX:
                self.dropped += 1
            self.received += 1
            self._q.append(
                RawFrame(
                    ts=msg.timestamp or time.time(),
                    source=LinkType.CAN,
                    id=msg.arbitration_id,
                    payload=bytes(msg.data),
                    extra={"dlc": msg.dlc, "extended": msg.is_extended_id},
                )
            )

    # -- consumption ---------------------------------------------------------

    @property
    def error(self) -> str | None:
        """Set when the reader thread died. The manager surfaces it and drops
        the link rather than sitting on a bus that stopped delivering."""
        return self._error

    @property
    def alive(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def drain(self, limit: int = QUEUE_MAX) -> list[RawFrame]:
        """Everything received since the last call, oldest first."""
        out: list[RawFrame] = []
        q = self._q
        while q and len(out) < limit:
            out.append(q.popleft())
        return out

    async def frames(self) -> AsyncIterator[RawFrame]:
        """Transport contract. Prefer drain() on a busy bus."""
        while self.alive or self._q:
            batch = self.drain()
            if not batch:
                await asyncio.sleep(0.01)
                continue
            for frame in batch:
                yield frame
