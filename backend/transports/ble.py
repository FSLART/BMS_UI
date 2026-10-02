"""Bluetooth LE, para o RN4871 do AMS master.

O modulo esta ligado ao UART2 do STM32 a 115200 e reencaminha o que sai por la:
uma linha JSON por segundo, ~3,1 kB, terminada em CRLF. Ver
`LIVE_DEBUG_JSON.md` no repositorio do firmware.

Nao ha aqui nenhuma nocao de trama CAN. O que sai deste transporte sao LINHAS:
cada `RawFrame` leva uma linha completa no `payload`, e o `LiveJsonDecoder`
transforma-a em estado. O contrato `Transport` mantem-se, so o conteudo e que e
outro.

Duas coisas dominam o desenho:

*   **O RN4871 nao tem RTS/CTS.** Perde bytes em silencio quando o buffer
    enche, portanto uma linha pode chegar cortada ao meio. Nada aqui assume que
    o que veio e valido -- parte-se por `\\n`, entrega-se a linha, e quem
    descodifica e que decide se presta. O firmware manda o dump inteiro de uma
    vez (`uart2_WriteBlock`), nunca meia linha, logo uma linha truncada
    significa perda no radio e nao no emissor.

*   **As notificacoes chegam em pedacos de ~20-180 bytes.** Uma linha de 3 kB
    vem em dezenas deles. O buffer acumula entre notificacoes e so corta quando
    aparece o fim de linha.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections import deque
from typing import Any, AsyncIterator

from ..logbuffer import log
from ..state import LinkType
from .base import RawFrame, Transport, TransportError
from .discovery import BLE_SEEN

# UART transparente da Microchip, que e o que o RN4871 anuncia de origem.
# Ficam aqui como preferencia e nao como imposicao: o ecra de ligacao deixa
# escrever outros UUID, e sem nenhum escrito procura-se o primeiro que notifique.
RN4871_SERVICE = "49535343-fe7d-4ae5-8fa9-9fafd205e455"
RN4871_NOTIFY = "49535343-1e4d-4bd9-ba61-23c647249616"   # dispositivo -> nos
RN4871_WRITE = "49535343-8841-43f4-a8d4-ecbe34729bb3"    # nos -> dispositivo

# Uma linha ronda os 3,1 kB e o firmware usa um buffer de 5120. Acima deste
# valor nao ha linha nenhuma a caminho: perdeu-se o fim de linha e o buffer
# esta a crescer com restos de varias amostras. Deitar fora e mais seguro do
# que juntar metades de dumps diferentes num objeto que parece valido.
LINE_MAX = 16384

# Poucas linhas: cada uma e uma fotografia completa do pack, e a um dump por
# segundo a mais antiga de dez ja nao interessa a ninguem.
QUEUE_MAX = 16

# Tecto para a ligacao. O bleak tem limite proprio para a descoberta, mas no
# Windows tenta varias vezes e um endereco que nao existe levava 32 s a
# desistir -- tempo suficiente para quem esta a olhar concluir que bloqueou, e
# tempo a mais para o modo demo comecar a servir dados.
CONNECT_TIMEOUT_S = 8.0


class BleTransport(Transport):
    link_type = LinkType.BLE

    def __init__(self, config: dict[str, Any]) -> None:
        super().__init__(config)
        self.address: str = str(config.get("address") or "").strip()
        self.name: str = str(config.get("name") or "").strip()
        self.service: str = str(config.get("service") or "").strip().lower()
        self.characteristic: str = str(config.get("characteristic") or "").strip().lower()

        self._client: Any = None
        self._notify_uuid: str | None = None
        self._buf = bytearray()
        self._q: deque[RawFrame] = deque(maxlen=QUEUE_MAX)
        self._error: str | None = None
        self.dropped = 0
        self.received = 0
        # Bytes deitados fora por a linha nunca fechar. Diagnostico de radio, e
        # a unica pista de que se esta a perder metade das amostras.
        self.discarded_bytes = 0
        self.bytes_in = 0

    @property
    def describe(self) -> str:
        return self.name or self.address or "BLE"

    # -- lifecycle -----------------------------------------------------------

    async def open(self) -> None:
        try:
            from bleak import BleakClient
            from bleak.exc import BleakDeviceNotFoundError
        except ImportError as exc:
            raise TransportError("bleak nao instalado (pip install bleak)") from exc

        if not self.address:
            raise TransportError("Nenhum dispositivo BLE selecionado")

        try:
            client = BleakClient(
                BLE_SEEN.get(self.address.upper(), self.address),
                disconnected_callback=self._on_disconnect,
                timeout=CONNECT_TIMEOUT_S,
            )
        except Exception as exc:  # noqa: BLE001 - endereco mal formado
            raise TransportError(f"Endereco BLE invalido ({self.address}): {exc}") from exc

        try:
            # `wait_for` por cima do timeout do proprio bleak: o do bleak cobre
            # a descoberta, este cobre a ligacao inteira.
            await asyncio.wait_for(client.connect(), CONNECT_TIMEOUT_S + 2.0)
        except asyncio.TimeoutError as exc:
            with contextlib.suppress(Exception):
                await client.disconnect()
            raise TransportError(
                f"{self.describe} nao respondeu em {CONNECT_TIMEOUT_S + 2.0:.0f} s"
            ) from exc
        except BleakDeviceNotFoundError as exc:
            # Quase sempre e isto: o RN4871 so aceita uma ligacao e deixa de
            # anunciar enquanto a tem. A app do telemovel ligada chega.
            raise TransportError(
                f"{self.describe} nao esta a anunciar. O RN4871 so aceita uma ligacao: "
                "se estiver ligado ao telemovel (ou a outro PC), desliga la e tenta outra vez."
            ) from exc
        except Exception as exc:  # noqa: BLE001 - adaptador desligado, fora de alcance
            raise TransportError(f"Nao foi possivel ligar a {self.describe}: {exc}") from exc

        self._client = client
        try:
            self._notify_uuid = self._pick_notify(client)
            await client.start_notify(self._notify_uuid, self._on_data)
        except Exception as exc:  # noqa: BLE001
            await self.close()
            raise TransportError(f"Sem caracteristica de notificacao utilizavel: {exc}") from exc

        self._opened_at = time.time()
        log.info("BLE ligado: %s | notificacoes em %s", self.describe, self._notify_uuid)

    def _pick_notify(self, client: Any) -> str:
        """Qual caracteristica ouvir.

        Por esta ordem: a que foi escrita no ecra de ligacao, a do UART
        transparente do RN4871, e por fim a primeira que saiba notificar. A
        ultima opcao existe para um modulo diferente funcionar sem obrigar
        ninguem a ir procurar UUID a mao.
        """
        chars = [
            c for svc in client.services for c in svc.characteristics
            if ("notify" in c.properties or "indicate" in c.properties)
            and not c.uuid.lower().startswith("00002a05")
        ]
        if not chars:
            raise TransportError("o dispositivo nao expoe nenhuma caracteristica de notificacao")

        if self.characteristic:
            for c in chars:
                if c.uuid.lower() == self.characteristic:
                    return c.uuid
            raise TransportError(f"{self.characteristic} nao notifica ou nao existe")

        for c in chars:
            if c.uuid.lower() == RN4871_NOTIFY:
                return c.uuid

        if self.service:
            for c in chars:
                if str(c.service_uuid).lower() == self.service:
                    log.info("BLE: sem UUID de caracteristica, a usar %s do servico indicado", c.uuid)
                    return c.uuid

        log.warning("BLE: nem UUID indicado nem UART do RN4871 - a tentar %s", chars[0].uuid)
        return chars[0].uuid

    def _on_disconnect(self, _client: Any) -> None:
        # Chamado pelo bleak em qualquer thread. So se marca o erro; quem o
        # mostra e derruba a ligacao e o ciclo do manager.
        if self._error is None:
            self._error = f"{self.describe} desligou-se"
            log.warning("BLE desligado: %s", self.describe)

    async def close(self) -> None:
        client, self._client = self._client, None
        if client is None:
            return
        try:
            if self._notify_uuid and client.is_connected:
                await client.stop_notify(self._notify_uuid)
        except Exception as exc:  # noqa: BLE001 - fechar nunca pode falhar
            log.debug("Erro a parar notificacoes BLE: %s", exc)
        try:
            await client.disconnect()
        except Exception as exc:  # noqa: BLE001
            log.debug("Erro a desligar BLE: %s", exc)
        log.info("BLE fechado: %s", self.describe)

    # -- recepcao ------------------------------------------------------------

    def _on_data(self, _sender: Any, data: bytearray) -> None:
        """Um pedaco de notificacao. Acumula e corta em linhas completas."""
        self.bytes_in += len(data)
        self._buf += data

        while True:
            nl = self._buf.find(b"\n")
            if nl < 0:
                break
            line = bytes(self._buf[:nl]).strip(b"\r\x00 \t")
            del self._buf[: nl + 1]
            if not line:
                continue
            if len(self._q) == QUEUE_MAX:
                self.dropped += 1
            self.received += 1
            self._q.append(
                RawFrame(
                    ts=time.time(),
                    source=LinkType.BLE,
                    id="live_debug",
                    payload=line,
                    extra={"bytes": len(line)},
                )
            )

        if len(self._buf) > LINE_MAX:
            self.discarded_bytes += len(self._buf)
            log.warning("BLE: %d bytes sem fim de linha, deitados fora", len(self._buf))
            self._buf.clear()

    @property
    def error(self) -> str | None:
        return self._error

    @property
    def alive(self) -> bool:
        return self._client is not None and self._client.is_connected

    def drain(self, limit: int = QUEUE_MAX) -> list[RawFrame]:
        out: list[RawFrame] = []
        q = self._q
        while q and len(out) < limit:
            out.append(q.popleft())
        return out

    async def frames(self) -> AsyncIterator[RawFrame]:
        while self.alive or self._q:
            batch = self.drain()
            if not batch:
                await asyncio.sleep(0.05)
                continue
            for frame in batch:
                yield frame
