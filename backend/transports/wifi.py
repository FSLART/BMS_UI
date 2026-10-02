"""WiFi, pelo servidor GDB do Black Magic.

O depurador e um ESP32 a correr o firmware Black Magic
(github.com/Ebiroll/esp32_blackmagic). Levanta uma rede propria, fala SWD com o
STM32 do AMS e serve o protocolo do GDB em TCP -- 192.168.4.1:2345 por omissao.
E o mesmo caminho que o STM32CubeIDE usa para as Live Expressions.

O que se le e a variavel global `live_debug`, 816 bytes que o firmware refresca
a cada 500 ms com tudo o que mede. A morada vem da tabela de simbolos do `.elf`
que foi gravado; a traducao para estado da interface esta em
`decode/live_struct.py`.

## O CPU para ao agarrar o alvo

O `vAttach` do Black Magic chama `cortexm_attach`, e a primeira coisa que essa
funcao faz e `target_halt_request`. Nao ha forma de agarrar o alvo sem isso.

Duas consequencias que interessam a quem tem o acumulador ligado:

*   **A paragem e curta mas existe.** O pacote que volta a por o CPU a andar
    segue colado ao `vAttach`, sem esperar pela resposta, portanto a paragem e
    o tempo de uma transacao SWD dentro do ESP32 e nao uma ida e volta do
    WiFi. Ainda assim, ligar isto a um acumulador em HV e uma decisao de quem
    o faz.
*   **O watchdog do STM32 pode nao estar congelado.** O firmware so poe
    `DBG_WWDG_STOP` se o depurador ja estava ligado quando arrancou. Ligar a um
    BMS que arrancou sozinho deixa o WWDG a contar durante a paragem. Este
    modulo escreve esse bit logo a seguir a agarrar o alvo, o que protege
    qualquer paragem posterior -- a do proprio `vAttach` fica de fora, por
    definicao.

## Ler com o CPU a andar

O ciclo do Black Magic nao responde a pacotes `m` enquanto acha que o alvo esta
a correr: em `bmp_poll_loop` so le caracteres a procura de Ctrl-C. Mandar `c`
seria portanto o fim das leituras.

O que se faz e outra coisa: o alvo volta a andar por escrita direta no DHCSR,
o registo de controlo do bloco de depuracao do Cortex-M. O nucleo arranca, e o
Black Magic continua a achar que o tem parado, portanto continua a servir
leituras de memoria. Essas leituras passam pelo AHB-AP, que e independente do
nucleo e nunca precisou dele parado -- e por isso que o RTT tambem funciona com
o alvo a correr.

Nada disto escreve na memoria do firmware. As unicas escritas sao nos dois
registos de depuracao, DHCSR e DBGMCU.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections import deque
from typing import Any, AsyncIterator

from ..elfsyms import ElfError, Symbol, read_symbols
from ..decode import live_struct
from ..logbuffer import log
from ..state import LinkType
from .base import RawFrame, Transport, TransportError
from .gdbrsp import GdbClient, GdbError

# --- registos de depuracao do Cortex-M --------------------------------------
DHCSR = 0xE000EDF0
DHCSR_DBGKEY = 0xA05F0000
DHCSR_C_DEBUGEN = 1 << 0
DHCSR_C_HALT = 1 << 1
DHCSR_S_HALT = 1 << 17

# Correr: chave + depuracao ligada, sem o bit de paragem.
DHCSR_RUN = DHCSR_DBGKEY | DHCSR_C_DEBUGEN

# DBGMCU do STM32F4. APB1_FZ decide que perifericos congelam com o nucleo
# parado; o bit 11 e o watchdog de janela, o unico que este firmware usa.
DBGMCU_APB1_FZ = 0xE0042008
DBG_WWDG_STOP = 1 << 11

# O firmware refresca a struct uma vez por segundo (brain.c). O dobro chega
# para ver cada atualizacao sem esperar um segundo inteiro por ela.
DEFAULT_HZ = 2.0
MIN_HZ = 0.5
MAX_HZ = 20.0

QUEUE_MAX = 8
CONNECT_TIMEOUT_S = 8.0

# Cadeias de um nome de falha nao passam disto. Estao em flash, portanto le-se
# um bloco e corta-se no primeiro zero.
STRING_MAX = 64



class WifiGdbTransport(Transport):
    link_type = LinkType.WIFI

    def __init__(self, config: dict[str, Any]) -> None:
        super().__init__(config)
        self.host: str = str(config.get("host") or "192.168.4.1").strip()
        self.port: int = int(config.get("port") or 2345)
        self.elf: str = str(config.get("elf") or "").strip()
        self.target: int = int(config.get("target") or 1)
        self.ssid: str = str(config.get("ssid") or "").strip()
        try:
            hz = float(config.get("hz") or DEFAULT_HZ)
        except (TypeError, ValueError):
            hz = DEFAULT_HZ
        self.hz = min(MAX_HZ, max(MIN_HZ, hz))

        self._client: GdbClient | None = None
        self._task: asyncio.Task[None] | None = None
        self._q: deque[RawFrame] = deque(maxlen=QUEUE_MAX)
        self._error: str | None = None

        self._live: Symbol | None = None
        # Cadeias em flash nunca mudam de sitio. Uma leitura por endereco, para
        # sempre.
        self._strings: dict[int, str] = {}

        self.received = 0
        self.dropped = 0
        self.rejected = 0
        self.halts_cleared = 0

    @property
    def describe(self) -> str:
        where = f"{self.host}:{self.port}"
        return f"{self.ssid} -> {where}" if self.ssid else where

    # -- simbolos ------------------------------------------------------------

    def _load_symbols(self) -> None:
        """Le o .elf e monta o plano de leitura. Corre fora do event loop."""
        if not self.elf:
            raise TransportError(
                "Sem ficheiro .elf: sem ele nao se sabe em que endereco esta o live_debug"
            )
        try:
            syms = read_symbols(self.elf)
        except (OSError, ElfError) as exc:
            raise TransportError(f"Nao foi possivel ler o .elf: {exc}") from exc

        live = syms.get(live_struct.SYMBOL)
        if live is None:
            raise TransportError(
                f"O .elf nao tem a variavel '{live_struct.SYMBOL}' "
                "(firmware sem live_debug.c?)"
            )
        if live.size != live_struct.STRUCT_SIZE:
            raise TransportError(
                f"'{live_struct.SYMBOL}' tem {live.size} bytes neste firmware e a "
                f"tabela deste programa espera {live_struct.STRUCT_SIZE}. A struct mudou; "
                "ler assim dava campos trocados."
            )
        self._live = live

    # -- ciclo de vida -------------------------------------------------------

    async def open(self) -> None:
        await asyncio.to_thread(self._load_symbols)
        assert self._live is not None

        # 15 s e nao 5: o Windows reautentica a rede do ESP32 com frequencia
        # ("Wireless security stopped" no registo WLAN) e a ligacao fica ~3 s
        # em baixo. O TCP sobrevive a isso se ninguem desistir antes; entretanto
        # a interface ja mostra os dados como obsoletos.
        client = GdbClient(self.host, self.port, timeout=15.0)
        try:
            await client.open(CONNECT_TIMEOUT_S)
            await client.handshake()
            await self._scan(client)
            # Aqui e que o CPU para. So depois do .elf lido e do alvo
            # encontrado: avisar antes disso era avisar de uma paragem que na
            # maior parte das falhas nem chega a acontecer.
            log.warning("A agarrar o alvo em %s - o CPU do AMS para por instantes",
                        self.describe)
            await self._attach_and_run(client)
        except BaseException as exc:
            # Falhar depois do attach nunca pode deixar o BMS parado: tentar
            # sempre por o nucleo a andar e largar o alvo antes de fechar.
            if client.connected:
                with contextlib.suppress(Exception):
                    await client.write_u32(DHCSR, DHCSR_RUN)
                    await client.detach()
            await client.close()
            if isinstance(exc, GdbError):
                raise TransportError(str(exc)) from exc
            raise

        self._client = client
        self._opened_at = time.time()
        log.info("WiFi/GDB ligado: %s | %s em 0x%08x (%d bytes) a %.1f Hz",
                 self.describe, live_struct.SYMBOL, self._live.addr, self._live.size, self.hz)
        self._task = asyncio.create_task(self._poll())

    async def _scan(self, client: GdbClient) -> None:
        """`monitor swd_scan`: e isto que cria o alvo do lado do depurador."""
        out = (await client.monitor("swd_scan")).strip()
        for line in out.splitlines():
            if line.strip():
                log.info("swd_scan: %s", line.strip())
        if not out:
            # Versoes mais antigas chamam-lhe `swdp_scan`. Tentar antes de
            # desistir e melhor do que obrigar alguem a descobrir isto sozinho.
            out = (await client.monitor("swdp_scan")).strip()
            for line in out.splitlines():
                if line.strip():
                    log.info("swdp_scan: %s", line.strip())
        if not out:
            raise GdbError("o swd_scan nao devolveu nada - SWD ligado ao AMS?")

    async def _attach_and_run(self, client: GdbClient) -> None:
        """Agarra o alvo e volta a po-lo a andar no mesmo folego.

        Os dois pacotes vao juntos de proposito. Ver o cabecalho deste ficheiro.
        """
        attach, resume = await client.cmd_many([
            f"vAttach;{self.target:x}",
            f"M{DHCSR:x},4:{DHCSR_RUN.to_bytes(4, 'little').hex()}",
        ])
        if not attach or attach.startswith((b"E", b"X")):
            raise GdbError(
                f"nao foi possivel agarrar o alvo {self.target}: "
                f"{attach.decode('ascii', 'replace') or '(sem resposta)'}"
            )
        if resume != b"OK":
            raise GdbError(
                "o alvo ficou parado: a escrita no DHCSR foi recusada "
                f"({resume.decode('ascii', 'replace') or 'sem resposta'})"
            )

        # Congelar o watchdog de janela enquanto o nucleo estiver parado. O
        # firmware so faz isto se arrancou com o depurador ligado.
        try:
            fz = await client.read_u32(DBGMCU_APB1_FZ)
            if not fz & DBG_WWDG_STOP:
                await client.write_u32(DBGMCU_APB1_FZ, fz | DBG_WWDG_STOP)
                log.info("WiFi/GDB: WWDG congelado em paragens do nucleo (DBGMCU)")
        except GdbError as exc:
            log.warning("Nao foi possivel congelar o WWDG: %s", exc)

        if await self._halted(client):
            raise GdbError("o nucleo continua parado depois do arranque")

    async def _halted(self, client: GdbClient) -> bool:
        return bool(await client.read_u32(DHCSR) & DHCSR_S_HALT)

    async def close(self) -> None:
        task, self._task = self._task, None
        if task is not None:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task

        client, self._client = self._client, None
        if client is None:
            return
        try:
            # Largar o alvo a andar. O detach do Black Magic ja o retoma, mas
            # se o pacote se perder o que fica e um BMS a correr e nao parado.
            # Fechar nunca rebenta: com o WiFi caido isto falha, e esta certo.
            if client.connected:
                with contextlib.suppress(Exception):
                    await client.write_u32(DHCSR, DHCSR_RUN)
                    await client.detach()
        finally:
            await client.close()
        log.info("WiFi/GDB fechado: %s", self.describe)

    # -- leitura -------------------------------------------------------------

    async def _read_strings(self, client: GdbClient, blob: bytes) -> dict[int, str]:
        for addr in live_struct.pointer_addresses(blob):
            if addr in self._strings:
                continue
            try:
                raw = await client.read_mem(addr, STRING_MAX)
            except GdbError as exc:
                # Um ponteiro invalido nao derruba a amostra: fica sem nome.
                log.debug("Cadeia em 0x%08x ilegivel: %s", addr, exc)
                self._strings[addr] = ""
                continue
            self._strings[addr] = raw.split(b"\0", 1)[0].decode("utf-8", "replace")
        return self._strings

    async def _poll(self) -> None:
        assert self._client is not None and self._live is not None
        client, live = self._client, self._live
        period = 1.0 / self.hz

        try:
            while True:
                start = time.time()

                # Primeiro o DHCSR. Se alguma coisa parou o nucleo -- um reset,
                # um vector catch, outro depurador -- o que interessa e po-lo a
                # andar depressa, nao ler a fotografia dele parado.
                if await self._halted(client):
                    self.halts_cleared += 1
                    log.warning("WiFi/GDB: nucleo parado, a retomar")
                    await client.write_u32(DHCSR, DHCSR_RUN)

                blob = await client.read_mem(live.addr, live.size)
                strings = await self._read_strings(client, blob)

                if len(self._q) == QUEUE_MAX:
                    self.dropped += 1
                self.received += 1
                self._q.append(
                    RawFrame(
                        ts=time.time(),
                        source=LinkType.WIFI,
                        id=live_struct.SYMBOL,
                        payload=blob,
                        extra={"strings": strings},
                    )
                )

                await asyncio.sleep(max(0.0, period - (time.time() - start)))
        except asyncio.CancelledError:
            raise
        except GdbError as exc:
            self._error = str(exc)
            log.error("WiFi/GDB: %s", exc)
        except Exception as exc:  # noqa: BLE001 - socket morto, alvo perdido
            self._error = f"leitura falhou: {exc}"
            log.exception("WiFi/GDB: falha no ciclo de leitura")

    # -- contrato do transporte ----------------------------------------------

    @property
    def error(self) -> str | None:
        return self._error

    @property
    def alive(self) -> bool:
        return (
            self._client is not None
            and self._client.connected
            and self._task is not None
            and not self._task.done()
        )

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
