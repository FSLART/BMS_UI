"""Cliente minimo do GDB Remote Serial Protocol.

Fala com o servidor GDB do Black Magic que corre no ESP32 (porta 2345 por
omissao). So o suficiente para ligar, agarrar o alvo e ler memoria -- nao ha
aqui registos, breakpoints nem gravacao de flash, de proposito: isto e uma
ferramenta de observacao, e o que nao existe nao se engana a escrever.

## O formato

    $<payload>#<cs>      cs = soma dos bytes do payload, modulo 256, em hex

Cada pacote e confirmado com um `+`. O Black Magic anuncia `QStartNoAckMode`, e
vale a pena aceitar: por WiFi cada confirmacao e mais uma ida e volta.

Duas codificacoes aparecem na resposta e tem de ser desfeitas:

*   `}` escapa o caractere seguinte, que vem XOR 0x20. Serve para `# $ } *`.
*   `*` e compressao: repete o caractere anterior mais (n - 29) vezes, onde n
    e o byte a seguir ao `*`. Uma leitura de 800 bytes de RAM quase toda a
    zeros encolhe para muito pouco, e o Black Magic usa isto.

## O pacote `O`

O servidor manda saida de consola em pacotes `O<hex>` a qualquer momento, sem
ser resposta a nada. Quem espera por uma resposta tem de os saltar, ou le a
saida do `monitor` como se fosse o resultado do comando anterior.
"""

from __future__ import annotations

import asyncio

from ..logbuffer import log

# O Black Magic anuncia o seu PacketSize no qSupported. Ate la, o minimo que o
# protocolo garante.
DEFAULT_PACKET_SIZE = 256

# Tecto de seguranca para um pacote recebido. O ESP32 anuncia ~2 kB; isto e
# folga mais do que suficiente e evita crescer um buffer sem fim se a ligacao
# encher de lixo.
MAX_PACKET_BYTES = 64 * 1024


class GdbError(RuntimeError):
    pass


def _checksum(payload: bytes) -> int:
    return sum(payload) & 0xFF


def _decode(payload: bytes) -> bytes:
    """Desfaz o escape `}` e a compressao `*`."""
    out = bytearray()
    i = 0
    n = len(payload)
    while i < n:
        b = payload[i]
        if b == 0x7D and i + 1 < n:          # chaveta: escape
            out.append(payload[i + 1] ^ 0x20)
            i += 2
            continue
        if b == 0x2A and out and i + 1 < n:  # asterisco: repeticao
            repeat = payload[i + 1] - 29
            if repeat > 0:
                out.extend(bytes([out[-1]]) * repeat)
            i += 2
            continue
        out.append(b)
        i += 1
    return bytes(out)


class GdbClient:
    """Uma ligacao TCP a um servidor GDB. Nao e segura para uso concorrente."""

    def __init__(self, host: str, port: int, timeout: float = 5.0) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self.packet_size = DEFAULT_PACKET_SIZE
        self._r: asyncio.StreamReader | None = None
        self._w: asyncio.StreamWriter | None = None
        self._no_ack = False

    # -- ligacao -------------------------------------------------------------

    async def open(self, connect_timeout: float = 8.0) -> None:
        try:
            self._r, self._w = await asyncio.wait_for(
                asyncio.open_connection(self.host, self.port), connect_timeout
            )
        except asyncio.TimeoutError as exc:
            raise GdbError(
                f"{self.host}:{self.port} nao respondeu em {connect_timeout:.0f} s"
            ) from exc
        except OSError as exc:
            raise GdbError(f"nao foi possivel ligar a {self.host}:{self.port}: {exc}") from exc

    async def close(self) -> None:
        w, self._w = self._w, None
        self._r = None
        if w is None:
            return
        try:
            w.close()
            await asyncio.wait_for(w.wait_closed(), 2.0)
        except Exception as exc:  # noqa: BLE001 - fechar nunca pode falhar
            log.debug("Erro a fechar ligacao GDB: %s", exc)

    @property
    def connected(self) -> bool:
        return self._w is not None and not self._w.is_closing()

    # -- pacotes -------------------------------------------------------------

    async def _write(self, data: bytes) -> None:
        if self._w is None:
            raise GdbError("ligacao GDB fechada")
        try:
            self._w.write(data)
            await self._w.drain()
        except OSError as exc:
            raise GdbError(f"ligacao GDB caiu: {exc}") from exc

    async def _send(self, payload: bytes) -> None:
        await self._write(b"$" + payload + b"#%02x" % _checksum(payload))

    async def _read_exactly(self, n: int) -> bytes:
        if self._r is None:
            raise GdbError("ligacao GDB fechada")
        try:
            return await asyncio.wait_for(self._r.readexactly(n), self.timeout)
        except asyncio.IncompleteReadError as exc:
            raise GdbError("o servidor GDB fechou a ligacao") from exc
        except asyncio.TimeoutError as exc:
            raise GdbError(f"sem resposta do servidor GDB em {self.timeout:.0f} s") from exc
        except OSError as exc:
            raise GdbError(f"ligacao GDB caiu: {exc}") from exc

    async def _recv(self) -> bytes:
        """Um pacote, ja descodificado. Confirmacoes soltas sao saltadas."""
        while True:
            b = await self._read_exactly(1)
            if b == b"$":
                break
            # Qualquer outra coisa antes do inicio do pacote e ruido: uma
            # confirmacao solta, ou texto de um servidor a arrancar. Ignorar em
            # silencio e melhor do que rebentar a ligacao por causa disso.

        payload = bytearray()
        while True:
            b = await self._read_exactly(1)
            if b == b"#":
                break
            payload += b
            if len(payload) > MAX_PACKET_BYTES:
                raise GdbError("pacote GDB demasiado grande")

        got = await self._read_exactly(2)
        if not self._no_ack:
            try:
                ok = int(got, 16) == _checksum(bytes(payload))
            except ValueError:
                ok = False
            await self._write(b"+" if ok else b"-")
            if not ok:
                raise GdbError("checksum errado num pacote GDB")
        return _decode(bytes(payload))

    async def cmd(self, payload: str | bytes) -> bytes:
        """Manda um pacote e devolve a resposta, saltando saida de consola."""
        if isinstance(payload, str):
            payload = payload.encode()
        await self._send(payload)
        while True:
            reply = await self._recv()
            # `O<hex>` e a consola do servidor a falar sozinha, nunca a
            # resposta ao que foi perguntado.
            if reply[:1] == b"O" and reply != b"OK":
                continue
            return reply

    async def cmd_many(self, payloads: list[str | bytes]) -> list[bytes]:
        """Varios pacotes de uma vez, respostas pela mesma ordem.

        Existe por uma razao so: agarrar o alvo para o CPU, e o pacote que o
        volta a por a andar nao deve esperar por outra ida e volta do WiFi.
        Mandados os dois de seguida, o servidor processa-os em fila e o CPU
        fica parado o tempo de uma transacao SWD em vez de um RTT.

        So sem confirmacoes. Com elas, o servidor fica a espera de `+` depois
        de cada resposta e le o `$` do pacote seguinte como confirmacao errada:
        reenvia a resposta e o segundo pacote perde-se. Visto no ESP32 real,
        que nao oferece QStartNoAckMode.
        """
        if not self._no_ack:
            return [await self.cmd(p) for p in payloads]
        for payload in payloads:
            await self._send(payload.encode() if isinstance(payload, str) else payload)
        out: list[bytes] = []
        while len(out) < len(payloads):
            reply = await self._recv()
            if reply[:1] == b"O" and reply != b"OK":
                continue
            out.append(reply)
        return out

    # -- arranque ------------------------------------------------------------

    async def handshake(self) -> None:
        """qSupported + modo sem confirmacoes.

        Sem isto ficava-se no PacketSize minimo do protocolo, o que partia uma
        leitura de 816 bytes em dezenas de idas e voltas por WiFi.
        """
        reply = (await self.cmd("qSupported:multiprocess+;swbreak+;hwbreak+")).decode(
            "ascii", "replace"
        )
        for feature in reply.split(";"):
            if feature.startswith("PacketSize="):
                try:
                    self.packet_size = max(DEFAULT_PACKET_SIZE, int(feature[11:], 16))
                except ValueError:
                    pass
        # Pedido sempre, anunciado ou nao: o ESP32 tem o handler mas a build
        # nao o anuncia. Com confirmacoes, o servidor reenvia a resposta se o
        # `+` demorar mais de 2 s (gdb_packet.c) -- por WiFi isso acontece, e
        # uma resposta repetida desalinha todas as seguintes.
        if await self.cmd("QStartNoAckMode") == b"OK":
            self._no_ack = True
        log.info("GDB: PacketSize=%d, confirmacoes %s",
                 self.packet_size, "desligadas" if self._no_ack else "ligadas")

    async def monitor(self, text: str) -> str:
        """Comando `monitor` (qRcmd). A saida vem em pacotes `O<hex>`."""
        await self._send(b"qRcmd," + text.encode().hex().encode())
        out = bytearray()
        while True:
            reply = await self._recv()
            if reply[:1] == b"O" and reply != b"OK":
                try:
                    out += bytes.fromhex(reply[1:].decode("ascii", "replace"))
                except ValueError:
                    pass
                continue
            if reply.startswith(b"E"):
                raise GdbError(f"monitor {text}: {reply.decode('ascii', 'replace')}")
            return out.decode("utf-8", "replace")

    async def attach(self, target: int = 1) -> None:
        """Agarra o alvo. **Isto para o CPU.** Ver `wifi.py`."""
        reply = await self.cmd(f"vAttach;{target:x}")
        if not reply or reply.startswith(b"E") or reply.startswith(b"X"):
            raise GdbError(
                f"nao foi possivel agarrar o alvo {target}: "
                f"{reply.decode('ascii', 'replace') or '(sem resposta)'}"
            )

    async def detach(self) -> None:
        try:
            await self.cmd("D")
        except Exception as exc:  # noqa: BLE001 - largar nunca pode falhar
            log.debug("Erro no detach GDB: %s", exc)

    # -- memoria -------------------------------------------------------------

    def _chunk(self) -> int:
        """Bytes por pacote `m`.

        A resposta vem em hex, dois caracteres por byte, e o Black Magic
        rejeita um pedido maior do que metade do seu buffer. Fica-se abaixo
        disso com folga para o cabecalho.
        """
        return max(64, (self.packet_size - 8) // 2)

    async def read_mem(self, addr: int, length: int) -> bytes:
        out = bytearray()
        chunk = self._chunk()
        while len(out) < length:
            want = min(chunk, length - len(out))
            at = addr + len(out)
            reply = await self.cmd(f"m{at:x},{want:x}")
            if reply.startswith(b"E") or not reply:
                raise GdbError(
                    f"leitura de 0x{at:08x} (+{want}) recusada: "
                    f"{reply.decode('ascii', 'replace') or '(vazio)'}"
                )
            try:
                got = bytes.fromhex(reply.decode("ascii"))
            except (ValueError, UnicodeDecodeError) as exc:
                raise GdbError(f"resposta invalida a ler 0x{at:08x}") from exc
            if not got:
                raise GdbError(f"leitura de 0x{at:08x} devolveu zero bytes")
            out += got
        return bytes(out[:length])

    async def write_mem(self, addr: int, data: bytes) -> None:
        reply = await self.cmd(f"M{addr:x},{len(data):x}:{data.hex()}")
        if reply != b"OK":
            raise GdbError(
                f"escrita em 0x{addr:08x} recusada: "
                f"{reply.decode('ascii', 'replace') or '(vazio)'}"
            )

    async def read_u32(self, addr: int) -> int:
        return int.from_bytes(await self.read_mem(addr, 4), "little")

    async def write_u32(self, addr: int, value: int) -> None:
        await self.write_mem(addr, value.to_bytes(4, "little"))
