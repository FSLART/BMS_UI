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
from .decode import for_car, for_lines, for_struct
from .logbuffer import log
from .simulator import Simulator
from .transports.base import TransportError
from .transports.ble import BleTransport
from .transports.can_bus import CanTransport
from .transports.wifi import WifiGdbTransport
from .state import (
    STALE_AFTER_S,
    BmsState,
    CameraView,
    CanBus,
    CanCommand,
    CarMeta,
    Hotspot,
    LinkMeta,
    LinkStatus,
    LinkType,
)

UPDATE_HZ = 10.0

# Intervalo entre tentativas ao RN4871 fixo do carro (CarProfile.ble_address).
BLE_RETRY_S = 3

# Ligado mas sem amostras ha este tempo = ligacao meio-aberta (o RN4871 deixou
# de encaminhar, ou o Windows ainda nao deu pela queda). O firmware manda uma
# linha por segundo, portanto 10 s sem nada nao e um atraso: e fechar e religar.
BLE_SILENCE_S = 10.0

# Link types the UI may offer at all. The rest are shown greyed out until their
# decoders land, so nobody wires up a car expecting a link that cannot work.
# SIM is deliberately absent: "run the simulator" is the demo switch, not a
# transport you connect to.
SELECTABLE: set[LinkType] = {LinkType.CAN, LinkType.BLE, LinkType.WIFI}

# Of those, the ones with a working end-to-end decoder today. Anything else is
# reported honestly as not implemented instead of faking a connection.
IMPLEMENTED: set[LinkType] = {LinkType.SIM, LinkType.CAN, LinkType.BLE, LinkType.WIFI}


class ConnectionManager:
    def __init__(self) -> None:
        self.state = BmsState()
        self.car: CarProfile | None = None
        self._subscribers: set[asyncio.Queue[str]] = set()
        self._task: asyncio.Task[None] | None = None
        self._sim: Simulator | None = None
        self._demo_fallback = False
        # Only set while a real CAN link is up. Commands refuse without them.
        self._tx: CanTransport | None = None
        self._db = None

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
            model_charger=self.car.model_charger,
            model_segment=self.car.model_segment,
            view_closed=CameraView(**self.car.view_closed.model_dump()),
            view_open=CameraView(**self.car.view_open.model_dump()),
            view_charger=CameraView(**self.car.view_charger.model_dump()),
            view_segment=CameraView(**self.car.view_segment.model_dump()),
            hotspots=[Hotspot(**h.model_dump()) for h in self.car.hotspots],
            buses=[CanBus(**b.model_dump()) for b in self.car.buses],
            commands=[
                CanCommand(id=c.id, label=c.label, danger=c.danger, detail=c.detail)
                for c in self.car.commands
            ],
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
            "Carro selecionado: %s - %s: %d segmentos x %d paralelos x %dp "
            "= %d celulas%s | %d slaves (%d paralelos cada) | %.1f Ah | %.1f V nominal",
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
        self._tx = None
        self._db = None
        self.state = BmsState(car=self.car_meta())
        self._broadcast()
        return {"ok": True}

    # -- the run loop --------------------------------------------------------

    async def _run(self, lt: LinkType, config: dict[str, Any]) -> None:
        try:
            if lt is LinkType.CAN:
                await self._run_can(config)
            elif lt is LinkType.BLE:
                await self._run_ble(config)
            elif lt is LinkType.WIFI:
                await self._run_wifi(config)
            else:
                await self._run_sim(lt, config)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - surface any driver failure in the UI
            log.exception("Falha na ligacao %s", lt.value.upper())
            self._set_link(status=LinkStatus.ERROR, error=str(exc))

    # -- BLE -----------------------------------------------------------------

    async def _run_ble(self, config: dict[str, Any]) -> None:
        """Ouvir o dump `live_debug` do AMS pelo RN4871.

        Sem DBC: o que chega ja vem descodificado pelo firmware, uma linha JSON
        por segundo. O ciclo continua a correr aos 10 Hz da interface -- entre
        amostras redesenha o mesmo estado, o que mantem o contador de
        obsolescencia e o resto do ecra a mexer como em qualquer outro
        transporte.

        Nao ha `self._tx`: por BLE nao se envia nada. A configuracao do BMS por
        radio nao esta no plano, e sem isso um caminho de escrita seria uma
        porta aberta sem ninguem do outro lado.
        """
        assert self.car is not None
        car = self.car
        # O RN4871 do proprio carro insiste: sem ligacao tenta de 3 em 3 s, e
        # se cair volta a procurar. Outro dispositivo escolhido a mao tenta uma
        # vez e diz o que correu mal.
        address = str(config.get("address") or "").strip().upper()
        fixed = bool(car.ble_address) and address == car.ble_address.upper()

        # Um descodificador para a sessao toda, nao um por religacao: assim o
        # ultimo estado, o seq e a energia da carga sobrevivem a um reinicio
        # do RN4871 em vez de recomecarem do zero.
        decoder = for_lines(car)
        if decoder is None:
            msg = f"Sem descodificador de linha para o dialeto '{car.decoder}'"
            log.error("%s", msg)
            self._set_link(status=LinkStatus.ERROR, error=msg)
            return

        warned = False
        while True:
            transport = BleTransport(config)
            detail = transport.describe
            try:
                await transport.open()
            except TransportError as exc:
                if self._demo_fallback:
                    log.error("%s", exc)
                    log.warning("A cair para o simulador (modo demo ligado)")
                    return await self._run_sim(LinkType.BLE, config)
                if not fixed:
                    log.error("%s", exc)
                    self._set_link(status=LinkStatus.ERROR, error=str(exc))
                    return
                # Uma vez no log, nao a cada tentativa: a procurar pode ficar
                # minutos e a consola enchia-se de repeticoes.
                if not warned:
                    warned = True
                    log.warning("%s - a tentar de %d em %d s", exc, BLE_RETRY_S, BLE_RETRY_S)
                self._set_link(status=LinkStatus.CONNECTING, detail=f"A procurar {detail}")
                await asyncio.sleep(BLE_RETRY_S)
                continue

            warned = False
            log.info("A aguardar dump JSON do AMS em %s", detail)
            try:
                await self._pump_snapshots(
                    LinkType.BLE, transport, decoder, detail,
                    # Linha cortada a meio no radio. O RN4871 nao tem controlo
                    # de fluxo, portanto isto acontece e nao e erro -- so vale
                    # a pena contar.
                    reject_msg="%d linha(s) invalidas (perda no radio)",
                    max_silence=BLE_SILENCE_S,
                )
            except Exception:  # noqa: BLE001 - o RN4871 fixo nunca desiste
                if not fixed:
                    raise
                log.exception("Erro inesperado na ligacao BLE - a religar")
            if not fixed:
                return
            # Pausa curta e nao os 3 s: o RN4871 esta a reiniciar e a ligacao
            # seguinte ja fica a espera dele. So evita martelar o Bluetooth do
            # Windows se a ligacao cair logo a seguir a abrir.
            log.warning("Ligacao a %s perdida - a voltar a procurar", detail)
            self._set_link(status=LinkStatus.CONNECTING, detail=f"A procurar {detail}")
            await asyncio.sleep(0.5)

    # -- WiFi ----------------------------------------------------------------

    async def _run_wifi(self, config: dict[str, Any]) -> None:
        """Ler a struct `live_debug` do STM32 pelo servidor GDB do Black Magic.

        Tal como no BLE nao ha DBC nem `self._tx`: o que chega ja vem medido
        pelo firmware, e por aqui nao se envia nada para o barramento.

        A diferenca esta na origem. Aqui ninguem manda nada -- vai-se buscar a
        RAM do STM32, 816 bytes de cada vez, pelo AHB-AP. O preco e ter de
        agarrar o alvo uma vez, o que para o CPU por instantes; ver o cabecalho
        de `transports/wifi.py`.
        """
        assert self.car is not None
        car = self.car
        transport = WifiGdbTransport(config)
        detail = transport.describe

        decoder = for_struct(car)
        if decoder is None:
            msg = f"Sem descodificador de memoria para o dialeto '{car.decoder}'"
            log.error("%s", msg)
            self._set_link(status=LinkStatus.ERROR, error=msg)
            return

        try:
            await transport.open()
        except TransportError as exc:
            log.error("%s", exc)
            if self._demo_fallback:
                log.warning("A cair para o simulador (modo demo ligado)")
                return await self._run_sim(LinkType.WIFI, config)
            self._set_link(status=LinkStatus.ERROR, error=str(exc))
            return

        await self._pump_snapshots(
            LinkType.WIFI, transport, decoder, detail,
            reject_msg="%d fotografia(s) com tamanho errado",
        )

    # -- ciclo comum aos transportes de fotografia ----------------------------

    async def _pump_snapshots(self, lt: LinkType, transport, decoder, detail: str,
                              reject_msg: str, max_silence: float | None = None) -> None:
        """Roda a interface a partir de um transporte que entrega fotografias.

        BLE e WiFi trazem, cada um a sua maneira, o estado completo do pack de
        uma so vez. O que muda e o meio; o ciclo e o mesmo, e continua aos
        10 Hz da interface mesmo quando as amostras chegam a 1 ou 4 Hz -- entre
        elas redesenha o mesmo estado, o que mantem o contador de obsolescencia
        e o resto do ecra a mexer como em qualquer outro transporte.
        """
        self._set_link(status=LinkStatus.HANDSHAKING, detail=detail)

        period = 1.0 / UPDATE_HZ
        was_live = False
        rx_window = started = time.time()
        rx_count = 0
        rejected = 0
        silence_warned = False

        try:
            while True:
                loop_start = time.time()

                if (err := transport.error) is not None:
                    self._set_link(status=LinkStatus.ERROR, error=err)
                    return

                rx_count += decoder.feed(transport.drain())

                # Ligacao aberta mas muda ha demasiado tempo: sair, para quem
                # chamou fechar e voltar a abrir.
                if max_silence and loop_start - max(started, decoder.last_ams_ts) > max_silence:
                    err = f"sem amostras ha {max_silence:.0f} s com a ligacao aberta"
                    log.warning("%s: %s", detail, err)
                    self._set_link(status=LinkStatus.ERROR, error=err)
                    return

                elapsed = loop_start - rx_window
                if elapsed >= 1.0:
                    # Amostras por segundo, e nao tramas: a unidade certa aqui
                    # e "quantas fotografias do pack chegaram".
                    self.state.link.rx_rate = round(rx_count / elapsed, 1)
                    if decoder.rejected > rejected:
                        log.warning(reject_msg, decoder.rejected - rejected)
                        rejected = decoder.rejected
                    rx_count = 0
                    rx_window = loop_start

                live = decoder.live(loop_start)
                if live != was_live:
                    was_live = live
                    log.info("AMS %s", "online" if live else "sem resposta")

                # Ligado mas mudo: sem isto a interface fica em "a espera" para
                # sempre e ninguem sabe se o problema e o radio ou o firmware.
                if not silence_warned and not decoder.decoded and loop_start - started >= 5.0:
                    silence_warned = True
                    got = getattr(transport, "bytes_in", None)
                    if got == 0:
                        log.warning("Ligado ha 5 s e nao chegou nenhum byte: o AMS nao esta "
                                    "a mandar nada (firmware sem dump JSON no UART2?)")
                    else:
                        log.warning("Ligado ha 5 s sem nenhuma amostra valida%s",
                                    f" ({got} bytes recebidos)" if got else "")

                link = LinkMeta(
                    type=lt,
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

        # A DBC the operator picked in the connection screen, if any.
        picked = [p for p in [str(config.get("dbc") or "").strip()] if p]

        try:
            db, files = await asyncio.to_thread(dbcstore.load, car.dbc, True, picked)
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

        # From here the app can also talk. Kept on the manager rather than
        # passed around, so send_command has one place to check.
        self._tx, self._db = transport, db

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
            self._tx = None
            self._db = None
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
                    # Everything reaching this branch is simulated, whether it
                    # got here by choice or by falling back off a dead bus.
                    demo=True,
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

    # -- commands ------------------------------------------------------------

    async def send_command(self, cmd_id: str, on: bool) -> dict[str, Any]:
        """Transmit one of the car's declared commands.

        The guards are the point of this method. This is a monitoring tool that
        happens to be able to talk, and everything below refuses rather than
        guesses:

        * demo never transmits -- pretending to command a pack that is not
          there is the worst possible outcome
        * only while the link is LIVE, so nothing goes out at a moment when we
          cannot see what it did
        * the command must be declared in the car profile; no arbitrary frames
        """
        if self.car is None:
            return {"ok": False, "error": "Nenhum carro selecionado"}

        cmd = next((c for c in self.car.commands if c.id == cmd_id), None)
        if cmd is None:
            log.warning("Comando desconhecido: %s", cmd_id)
            return {"ok": False, "error": f"Comando desconhecido: {cmd_id}"}

        if self._demo_fallback and self._sim is not None:
            msg = "Modo demo: nada e enviado para o barramento."
            log.warning("%s (%s)", msg, cmd.label)
            return {"ok": False, "error": msg}

        link = self.state.link
        if link.status is LinkStatus.LIVE and link.type is not LinkType.CAN:
            msg = f"Comandos so seguem por CAN; a ligacao atual e {link.type.value.upper()}."
            log.warning("%s Comando '%s' nao enviado.", msg, cmd.label)
            return {"ok": False, "error": msg}

        transport, db = self._tx, self._db
        if transport is None or db is None or link.status is not LinkStatus.LIVE:
            msg = "Sem ligacao ativa ao BMS."
            log.warning("%s Comando '%s' nao enviado.", msg, cmd.label)
            return {"ok": False, "error": msg}

        try:
            message = db.get_message_by_name(cmd.message)
            payload = message.encode({cmd.signal: cmd.on if on else cmd.off})
            await transport.send(message.frame_id, payload, message.is_extended_frame)
        except Exception as exc:  # noqa: BLE001
            log.error("Falha a enviar '%s': %s", cmd.label, exc)
            return {"ok": False, "error": str(exc)}

        # Loud on purpose: every transmission onto the vehicle is in the log,
        # with what it was and who asked for it.
        log.warning("COMANDO ENVIADO -> %s = %s  (%s, 0x%X)",
                    cmd.label, "ON" if on else "OFF", cmd.message, message.frame_id)
        return {"ok": True, "sent": cmd.label, "on": on}

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
