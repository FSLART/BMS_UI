"""Connection manager.

Owns the one and only BmsState, drives whichever transport is selected, and
fans the state out to every connected WebSocket client.

M2 status: CAN is decoded for real (transports/can_bus.py + decode/t26.py). The
other transports are still offered as not-implemented rather than faked.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from typing import Any

from . import dbcstore
from .cars import CarProfile, get_car
from .decode import for_car
from .logbuffer import log
from .simulator import Simulator
from .transports.base import TransportError
from .transports.can_bus import CanTransport
from .state import (
    STALE_AFTER_S,
    BmsState,
    CameraView,
    CarMeta,
    Hotspot,
    LinkMeta,
    LinkStatus,
    LinkType,
)

UPDATE_HZ = 10.0

# Link types the UI may offer at all. The rest are shown greyed out until their
# decoders land, so nobody wires up a car expecting a link that cannot work.
# SIM is deliberately absent: "run the simulator" is the demo switch, not a
# transport you connect to.
SELECTABLE: set[LinkType] = {LinkType.CAN}

# Of those, the ones with a working end-to-end decoder today. Anything else is
# reported honestly as not implemented instead of faking a connection.
IMPLEMENTED: set[LinkType] = {LinkType.SIM, LinkType.CAN}


class ConnectionManager:
    def __init__(self) -> None:
        self.state = BmsState()
        self.car: CarProfile | None = None
        self._subscribers: set[asyncio.Queue[str]] = set()
        self._task: asyncio.Task[None] | None = None
        self._sim: Simulator | None = None
        self._demo_fallback = False

    # -- car selection -------------------------------------------------------

    def car_meta(self) -> CarMeta:
        if self.car is None:
            return CarMeta()
        return CarMeta(
            id=self.car.id,
            name=self.car.name,
            subtitle=self.car.subtitle,
            model_closed=self.car.model_closed,
            model_open=self.car.model_open,
            view_closed=CameraView(**self.car.view_closed.model_dump()),
            view_open=CameraView(**self.car.view_open.model_dump()),
            hotspots=[Hotspot(**h.model_dump()) for h in self.car.hotspots],
        )

    async def select_car(self, car_id: str) -> dict[str, Any]:
        car = get_car(car_id)
        if car is None:
            log.warning("Carro desconhecido: %s", car_id)
            return {"ok": False, "error": f"Carro desconhecido: {car_id}"}
        if not car.available:
            log.warning("%s ainda nao esta disponivel", car.name)
            return {"ok": False, "error": f"{car.name} ainda nao esta disponivel"}
        # Changing car invalidates topology and thresholds, so drop any link.
        await self.disconnect()
        self.car = car
        log.info(
            "Carro selecionado: %s - %s: %d segmentos x %d grupos serie x %dp "
            "= %d celulas%s | %d slaves (%d grupos cada) | %.1f Ah | %.1f V nominal",
            car.name, car.topology, car.n_segments, car.cells_per_segment,
            car.parallel_strings, car.total_cells,
            f" {car.cell_model}" if car.cell_model else "",
            car.slave_count, car.cells_per_slave,
            car.pack_capacity_ah, car.nominal_pack_v,
        )
        self.state.car = self.car_meta()
        self._broadcast()
        return {"ok": True, "car": car.model_dump()}

    # -- pub/sub -------------------------------------------------------------

    def subscribe(self) -> asyncio.Queue[str]:
        q: asyncio.Queue[str] = asyncio.Queue(maxsize=4)
        self._subscribers.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue[str]) -> None:
        self._subscribers.discard(q)

    def _broadcast(self) -> None:
        payload = self.state.model_dump_json()
        for q in list(self._subscribers):
            if q.full():
                # Slow client: drop the oldest frame rather than block the loop.
                with contextlib.suppress(asyncio.QueueEmpty):
                    q.get_nowait()
            with contextlib.suppress(asyncio.QueueFull):
                q.put_nowait(payload)

    def _set_link(self, **kwargs: Any) -> None:
        self.state.link = self.state.link.model_copy(update=kwargs)
        self.state.ts = time.time()
        self._broadcast()

    # -- lifecycle -----------------------------------------------------------

    async def connect(self, link_type: str, config: dict[str, Any], demo_fallback: bool = False) -> dict[str, Any]:
        if self.car is None:
            log.warning("Tentativa de ligacao sem carro selecionado")
            return {"ok": False, "error": "Nenhum carro selecionado"}

        await self.disconnect()

        try:
            lt = LinkType(link_type)
        except ValueError:
            return {"ok": False, "error": f"Tipo de ligacao desconhecido: {link_type}"}

        if lt not in SELECTABLE:
            log.warning("Ligacao '%s' ainda nao esta disponivel", lt.value)
            return {"ok": False, "error": f"Ligacao '{lt.value}' ainda nao esta disponivel"}

        self._demo_fallback = demo_fallback
        detail = _describe(lt, config)

        log.info("A ligar via %s: %s%s", lt.value.upper(), detail, "  [modo demo]" if demo_fallback else "")
        self.state = BmsState(car=self.car_meta(),
                              link=LinkMeta(type=lt, status=LinkStatus.CONNECTING, detail=detail))
        self._broadcast()

        if lt not in IMPLEMENTED and not demo_fallback:
            msg = (
                f"Transporte '{lt.value}' ainda sem descodificador (M2). "
                "Liga o Modo Demo para percorrer o fluxo com dados simulados."
            )
            log.error("%s", msg)
            self._set_link(status=LinkStatus.ERROR, error=msg)
            return {"ok": False, "error": msg}

        self._task = asyncio.create_task(self._run(lt, config))
        return {"ok": True}

    async def disconnect(self) -> dict[str, Any]:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        self._sim = None
        self.state = BmsState(car=self.car_meta())
        self._broadcast()
        return {"ok": True}

    # -- the run loop --------------------------------------------------------

    async def _run(self, lt: LinkType, config: dict[str, Any]) -> None:
        try:
            if lt is LinkType.CAN:
                await self._run_can(config)
            else:
                await self._run_sim(lt, config)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - surface any driver failure in the UI
            log.exception("Falha na ligacao %s", lt.value.upper())
            self._set_link(status=LinkStatus.ERROR, error=str(exc))

    # -- real CAN ------------------------------------------------------------

    async def _run_can(self, config: dict[str, Any]) -> None:
        """Open the bus, load the DBCs, then decode at the UI rate.

        The reader thread accumulates frames continuously; this loop drains the
        batch ten times a second and rebuilds the state from it. Decoding per
        frame instead would spend the whole loop on messages that are
        overwritten before anyone sees them.
        """
        assert self.car is not None
        car = self.car
        transport = CanTransport(config)
        detail = transport.describe

        try:
            db, files = await asyncio.to_thread(dbcstore.load, car.dbc)
        except Exception as exc:  # noqa: BLE001
            msg = f"DBC indisponivel: {exc}"
            log.error("%s", msg)
            if self._demo_fallback:
                log.warning("A cair para o simulador (modo demo ligado)")
                return await self._run_sim(LinkType.CAN, config)
            self._set_link(status=LinkStatus.ERROR, error=msg)
            return

        decoder = for_car(car, db)
        if decoder is None:
            msg = f"Sem descodificador para o dialeto '{car.decoder}'"
            log.error("%s", msg)
            self._set_link(status=LinkStatus.ERROR, error=msg)
            return

        try:
            await transport.open()
        except TransportError as exc:
            log.error("%s", exc)
            if self._demo_fallback:
                log.warning("A cair para o simulador (modo demo ligado)")
                return await self._run_sim(LinkType.CAN, config)
            self._set_link(status=LinkStatus.ERROR, error=str(exc))
            return

        origins = ", ".join(f.describe for f in files)
        self._set_link(status=LinkStatus.HANDSHAKING, detail=detail)
        log.info("A aguardar tramas do AMS em %s | %s", detail, origins)

        period = 1.0 / UPDATE_HZ
        was_live = False
        rx_window = time.time()
        rx_count = 0

        try:
            while True:
                loop_start = time.time()

                if (err := transport.error) is not None:
                    self._set_link(status=LinkStatus.ERROR, error=err)
                    return

                batch = transport.drain()
                rx_count += decoder.feed(batch)

                elapsed = loop_start - rx_window
                if elapsed >= 1.0:
                    self.state.link.rx_rate = round(rx_count / elapsed, 1)
                    if transport.dropped:
                        log.warning("%d tramas descartadas (fila cheia)", transport.dropped)
                        transport.dropped = 0
                    rx_count = 0
                    rx_window = loop_start

                live = decoder.live(loop_start)
                if live != was_live:
                    was_live = live
                    log.info("AMS %s", "online" if live else "sem resposta")

                link = LinkMeta(
                    type=LinkType.CAN,
                    status=LinkStatus.LIVE if live else LinkStatus.HANDSHAKING,
                    detail=detail,
                    rx_rate=self.state.link.rx_rate,
                    last_frame_ts=decoder.last_ams_ts or None,
                )
                state = decoder.snapshot(link)
                state.car = self.car_meta()
                self.state = state
                self._broadcast()

                await asyncio.sleep(max(0.0, period - (time.time() - loop_start)))
        finally:
            await transport.close()

    # -- simulator -----------------------------------------------------------

    async def _run_sim(self, lt: LinkType, config: dict[str, Any]) -> None:
        detail = _describe(lt, config)
        try:
            # Link is nominally up; we are not LIVE until a valid frame arrives.
            await asyncio.sleep(0.6)
            self._set_link(status=LinkStatus.HANDSHAKING, detail=detail)
            await asyncio.sleep(0.9)

            assert self.car is not None
            log.info("Ligacao %s ativa: %s", lt.value.upper(), detail)
            self._sim = Simulator(self.car)
            period = 1.0 / UPDATE_HZ
            frames = 0
            window_start = time.time()

            while True:
                loop_start = time.time()
                state = self._sim.step()
                state.car = self.car_meta()
                state.link = LinkMeta(
                    type=lt,
                    status=LinkStatus.LIVE,
                    detail=detail if lt is not LinkType.SIM else "Simulador interno",
                    rx_rate=self.state.link.rx_rate,
                    latency_ms=round(2.0 + (loop_start * 37 % 3), 1),
                    last_frame_ts=loop_start,
                )
                self.state = state

                frames += 1
                elapsed = loop_start - window_start
                if elapsed >= 1.0:
                    self.state.link.rx_rate = round(frames / elapsed, 1)
                    frames = 0
                    window_start = loop_start

                self._broadcast()
                await asyncio.sleep(max(0.0, period - (time.time() - loop_start)))

        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - surface any driver failure in the UI
            log.exception("Falha na ligacao %s", lt.value.upper())
            self._set_link(status=LinkStatus.ERROR, error=str(exc))

    # -- staleness watchdog --------------------------------------------------

    async def watchdog(self) -> None:
        """Grey the UI out instead of showing numbers that stopped updating."""
        while True:
            await asyncio.sleep(0.5)
            last = self.state.link.last_frame_ts
            if self.state.link.status is LinkStatus.LIVE and last is not None:
                stale = (time.time() - last) > STALE_AFTER_S
                if stale != self.state.stale:
                    self.state.stale = stale
                    self._broadcast()


def _describe(lt: LinkType, config: dict[str, Any]) -> str:
    if lt is LinkType.SERIAL:
        return f"{config.get('port', '?')} @ {config.get('baud', '?')} baud"
    if lt is LinkType.CAN:
        return f"{config.get('channel', '?')} @ {int(config.get('bitrate', 0)) // 1000} kbit/s"
    if lt is LinkType.BLE:
        return f"{config.get('name') or config.get('address', '?')}"
    if lt is LinkType.WIFI:
        ssid = config.get("ssid")
        target = f"{config.get('host', '?')}:{config.get('port', '?')}"
        return f"{ssid} -> {target}" if ssid else target
    if lt is LinkType.SIM:
        return "Simulador interno"
    return ""


manager = ConnectionManager()
