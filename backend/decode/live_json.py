"""`live_debug` -> UniversalBmsState.

O AMS master manda por BLE uma linha JSON por segundo com tudo o que mede,
gerada por `LiveDebug_SendJson()`. O formato esta em `LIVE_DEBUG_JSON.md`, no
repositorio do firmware: chaves longas, valores inteiros, unidade no sufixo.

`from_firmware()` passa essa linha para um dicionario interno de chaves curtas,
que e o que os `_build_*` leem. O WiFi gera o mesmo JSON a partir da struct
(`live_struct.to_firmware`), portanto os dois caminhos acabam aqui.

Este ficheiro e a traducao. A interface nao muda nada: recebe o mesmo
`BmsState` que recebia do CAN, com os mesmos campos nas mesmas unidades SI.

Duas diferencas em relacao ao descodificador de CAN valem a pena saber:

*   **Vem tudo de uma vez.** No CAN cada mensagem chega quando chega e o estado
    e montado a partir de sinais com validade propria; aqui cada linha e uma
    fotografia coerente do pack no mesmo instante. Nao ha TTL por sinal.

*   **Uma linha por segundo, nao dez.** O `live()` tolera tres segundos sem
    dados. Menos do que isso e a interface apagava-se entre amostras.

## O que este formato traz e o CAN nao

Bitmasks por celula, que no CAN nao existem:

    ow_cell_mask[slave]    bit c = celula c+1 com fio de medicao solto
    balancing.mask[slave]  bit c = celula c+1 a sangrar neste momento

Ate agora o fio aberto era deduzido por tensao abaixo de um limite e o
balanceamento era um unico sinalizador para o pack todo. Com estas duas
mascaras passa a ser por celula, e a deducao por tensao deixa de ser precisa.

## A ressalva que manda no desenho

**As tensoes em `cell_mV` nao tem guarda de PEC.** O firmware publica o que
estiver no registo do ADBMS, mesmo que a leitura tenha falhado o CRC -- e o que
sai sao numeros plausiveis. Sem cruzar com `pec_flags` nao ha forma de saber que
uma linha inteira de doze celulas e lixo.

Por isso, aqui, um slave com o bit de celula em `pec_flags` reporta as celulas
como DESCONHECIDAS em vez de reportar os numeros. O mesmo para os NTC com os
bits aux/raux. Mostrar 4,1 V numa
celula que ninguem conseguiu ler e pior do que nao mostrar nada.
"""

from __future__ import annotations

import json
import time
from typing import Any

from ..cars import CarProfile
from ..logbuffer import log
from ..state import (
    Ams, BmsState, Cell, Charger, Fault, LinkMeta, Pack, Safety, Segment,
    Severity, Thermistor, classify_cell, worst,
)
from ..transports.base import RawFrame

# Uma amostra por segundo. Tres segundos sao duas amostras perdidas seguidas --
# a partir dai e razoavel dizer que o AMS calou.
AMS_TIMEOUT_S = 3.0

# `brain.st` / `brain.pst`
AMS_STATES = [
    "BALANCING", "CHARGING", "IDLE", "ONMISSION",
    "STARTUP", "DISCHARGE_TEST", "RESET_ISA", "FAULT",
]

# `ct.ps`. O 16 e o que interessa a interface: contactores fechados.
PRECHARGE_STATES = [
    "START", "OPEN_ALL", "SWITCH_HVNEG", "WAIT_FOR_AIR_NEG_TO_CLOSE",
    "CHECKING_AIR_NEG_IS_CLOSED", "SWITCH_PRECHARGE", "WAIT_FOR_PRECHARGE_TO_CLOSE",
    "CHECKING_PRECHARGE_IS_CLOSED", "VERIFY_CURRENT", "VERIFY_BUS_VOLT",
    "SWITCH_HVPOS", "WAIT_FOR_AIR_POS_TO_CLOSE", "CHECKING_AIR_POS_IS_CLOSED",
    "TURN_OFF_PRECHARGE", "WAIT_FOR_PRECHARGE_TO_OPEN", "CHECKING_PRECHARGE_IS_OPEN",
    "HV_ON", "WRONG", "KILL", "RX_CAN",
]
HV_ON = "HV_ON"

# `chg.st`
CHARGER_STATES = ["WAIT_HV", "PRESTART_STOP", "CHARGING", "STOPPING", "DONE"]

# Escalares de `diag` que sao bitmask por slave, e o que dizem quando marcados.
SLAVE_FLAGS = {
    "pec_c": ("PEC nas celulas", Severity.FAULT),
    "pec_ac": ("PEC nos registos averaged", Severity.WARN),
    "pec_rx": ("PEC nos NTC", Severity.FAULT),
    "pec_st": ("PEC no status", Severity.WARN),
    "thsd": ("thermal shutdown do IC", Severity.FAULT),
    "sup": ("erro de alimentacao do IC", Severity.FAULT),
    "osc": ("oscilador fora de tolerancia", Severity.FAULT),
    "fuse": ("fusivel do IC", Severity.FAULT),
}


def _name(table: list[str], value: Any) -> str:
    """Nome do enum, ou o numero cru se estiver fora da tabela.

    Devolver o numero e de proposito: um firmware novo com um estado a mais
    mostra `21` em vez de mentir com o ultimo nome conhecido.
    """
    if isinstance(value, str):
        return value              # o firmware ja manda o nome
    if not isinstance(value, int):
        return ""
    return table[value] if 0 <= value < len(table) else str(value)


def _bit(mask: Any, n: int) -> bool:
    return isinstance(mask, int) and bool(mask & (1 << n))


def _f(d: dict[str, Any], key: str) -> float | None:
    v = d.get(key)
    return float(v) if isinstance(v, (int, float)) else None


# Uma ISA sem trama ha mais do que isto esta calada (LIVE_DEBUG_JSON.md, sec. 4).
ISA_SILENT_MS = 1000

# Bits de `pec_flags`, por slave, e a mascara de `diag` que cada um alimenta.
PEC_BITS = {"pec_c": 1, "pec_ac": 2, "pec_rx": 4 | 8, "pec_st": 16}

CHARGER_FLAGS = {
    "hw_failure": "Carregador: falha de hardware",
    "temp_otp": "Carregador: sobretemperatura",
    "input_voltage_fault": "Carregador: tensao de entrada",
    "starting_state_fault": "Carregador: falha no arranque",
    "comm_timeout": "Carregador: sem comunicacao",
}


def _isa(raw: dict[str, Any]) -> dict[str, Any]:
    """ISA so se estiver a falar; calada, os ultimos valores ja nao valem."""
    age = raw.get("rx_age_ms")
    if not isinstance(age, int) or age > ISA_SILENT_MS:
        return {}
    return {"i": raw.get("current_mA"), "u": raw.get("voltage_mV"), "p": raw.get("power_W")}


def from_firmware(raw: dict[str, Any]) -> dict[str, Any]:
    """Linha do `LiveDebug_SendJson()` -> o dicionario interno.

    O formato do firmware (LIVE_DEBUG_JSON.md) tem chaves longas e unidades no
    sufixo. Por dentro fica-se com o dicionario curto que o WiFi tambem produz
    (`live_struct.to_block`), para que celulas, pack e falhas sejam o mesmo
    codigo nos dois caminhos.
    """
    bms = raw.get("bms") or {}
    ae = raw.get("ams_error") or {}
    pre = raw.get("precharge") or {}
    pack = raw.get("pack") or {}
    bal = raw.get("balancing") or {}
    board = raw.get("board") or {}
    soc = raw.get("soc") or {}
    chg = raw.get("charger") or {}
    pec = raw.get("pec_flags") if isinstance(raw.get("pec_flags"), list) else []

    def mask(bits: int) -> int:
        return sum(1 << si for si, f in enumerate(pec) if isinstance(f, int) and f & bits)

    def num(v: Any, scale: float) -> float | None:
        return v / scale if isinstance(v, (int, float)) else None

    mcu = num(board.get("mcu_temp_dC"), 10)
    blk: dict[str, Any] = {
        "brain": {"st": bms.get("state"), "pst": bms.get("prev_state"),
                  "rt": bms.get("runtime_s"), "nf": bms.get("active_faults")},
        "ae": {"a": ae.get("active"), "p": ae.get("permanent"), "nf": ae.get("fault_count"),
               "ml": ae.get("fault_mask_lo"), "mh": ae.get("fault_mask_hi"),
               "f": ae.get("faults") or []},
        "adbms": {"n": pack.get("slaves_found"), "pe": pack.get("any_pec_error"),
                  "vmx": pack.get("cell_max_mV"), "vmn": pack.get("cell_min_mV"),
                  "tmx": pack.get("temp_max_cC"), "tmn": pack.get("temp_min_cC"),
                  "vsum": pack.get("voltage_sum_mV")},
        "i1": _isa(raw.get("isa_pack") or {}),
        "i2": _isa(raw.get("isa_handcart") or {}),
        "ct": {"ps": pre.get("state"), "ap": pre.get("air_pos"), "an": pre.get("air_neg"),
               "pc": pre.get("precharge_relay"), "dc": pre.get("discharge"),
               "sdc": pre.get("sdc_closed")},
        "bd": {"fan": board.get("fan_pwm"), "mt": mcu},
        "can": {f"{k}{bus}": (raw.get(f"can{bus}") or {}).get(src)
                for bus in (1, 2) for k, src in (("bo", "bus_off"), ("tec", "tx_errors"))},
        "bal": {"dcc": bal.get("mask") or []},
        # Negativo = registo nunca escrito (0x8000). Sem leitura, nao 0 V.
        "c": [[v if isinstance(v, int) and v > 0 else 0 for v in row]
              for row in raw.get("cell_mV") or [] if isinstance(row, list)],
        "t": [[num(v, 10) for v in row]
              for row in raw.get("ntc_dC") or [] if isinstance(row, list)],
        "diag": {"ow_c": raw.get("ow_cell_mask") or [], "ow_a": raw.get("ow_ntc_mask") or [],
                 **{key: mask(bits) for key, bits in PEC_BITS.items()}},
        "chg": {"st": chg.get("state"), "req": chg.get("handcart_switch"),
                "sts": chg.get("status_ok"), "raw": chg.get("requested_current_raw"),
                # As flags do carregador so valem com status recente.
                "flags": [msg for k, msg in CHARGER_FLAGS.items()
                          if chg.get("status_ok") and chg.get(k)]},
        # `sent` conta as linhas anteriores: avanca um por linha, serve de seq.
        "seq": (raw.get("json") or {}).get("sent"),
    }
    if soc.get("ready"):
        blk["soc"] = {"p": num(soc.get("percent_x100"), 100), "used": soc.get("used_charge_As")}
    return blk


class LiveJsonDecoder:
    """Mesmo contrato do T26Decoder: feed / live / snapshot."""

    def __init__(self, car: CarProfile, db: Any = None) -> None:
        self.car = car
        self.db = db            # nao usado: este formato nao precisa de DBC

        self.blk: dict[str, Any] = {}      # ultima linha valida
        self.last_ams_ts: float = 0.0
        self.decoded = 0
        self.rejected = 0                  # linhas que nao eram JSON valido

        self._faults: list[Fault] = []
        # `seq` do firmware. Saltos = amostras perdidas no radio, e e a unica
        # medida de qualidade da ligacao que existe.
        self._seq: int | None = None
        self.lost = 0
        self._dropped: int | None = None
        # Command counter de cada slave na amostra anterior. Se nao incrementa,
        # aquele IC parou de responder mesmo que continue a publicar tensoes.
        self._cc: list[int] | None = None
        self._cc_seq: Any = None
        self._stalled: set[int] = set()
        # Preenchido pelo _build_cells, lido pelo _build_faults.
        self._ow_confirmed: set[tuple[int, int]] = set()

        # Energia desta sessao de carga. Integrada aqui e nao lida do `soc.used`
        # porque esse conta desde um baseline que pode ser de ha dias; quem esta
        # ao lado do handcart pergunta quanto entrou AGORA.
        self._chg_energy_wh = 0.0
        self._chg_started: float | None = None
        self._chg_last: float | None = None

    # -- ingest --------------------------------------------------------------

    def feed(self, frames: list[RawFrame]) -> int:
        """Cada trama e uma linha. Fica a ultima que parsear.

        Processar todas e ficar com a ultima, em vez de saltar as anteriores:
        os saltos de `seq` e o command counter tem de ver a sequencia inteira,
        senao uma amostra perdida passa por perdida duas vezes.
        """
        used = 0
        for frame in frames:
            if not frame.payload.startswith(b"{"):
                # "AMS_ERROR line -> ..." -- as unicas linhas de texto que o
                # firmware manda alem do JSON. Vale a pena ver, nao e perda.
                if frame.payload.startswith(b"AMS_ERROR"):
                    log.warning("AMS: %s", frame.payload.decode("ascii", "replace"))
                else:
                    self.rejected += 1
                continue
            try:
                raw = json.loads(frame.payload)
            except Exception:  # noqa: BLE001 - linha cortada no radio, e normal
                self.rejected += 1
                continue
            if self._accept(raw, frame.ts):
                used += 1
            else:
                self.rejected += 1
        return used

    # BLE: `json.sent` a saltar = linhas perdidas no radio. O WiFi le a struct
    # ao seu ritmo e salta contagens sem perder nada, por isso desliga isto.
    count_lost = True

    def _accept(self, raw: Any, ts: float) -> bool:
        """Uma fotografia no formato do firmware. Comum ao BLE e ao WiFi."""
        if not isinstance(raw, dict) or not isinstance(raw.get("cell_mV"), list):
            return False

        blk = from_firmware(raw)
        # `dropped` e cumulativo no firmware; o que interessa e se subiu agora.
        dropped = (raw.get("json") or {}).get("dropped")
        if isinstance(dropped, int):
            prev = self._dropped if self._dropped is not None else dropped
            blk["drop"] = max(0, dropped - prev)
            self._dropped = dropped

        seq = blk.get("seq")
        if isinstance(seq, int) and self.count_lost:
            if self._seq is not None and seq > self._seq + 1:
                gap = seq - self._seq - 1
                self.lost += gap
                log.warning("%d amostra(s) perdida(s) (seq %d -> %d)", gap, self._seq, seq)
            self._seq = seq

        self._track_stalled(blk, seq)
        self.blk = blk
        self.last_ams_ts = ts
        self.decoded += 1
        return True

    def _track_stalled(self, blk: dict[str, Any], seq: Any) -> None:
        """Slaves cujo command counter nao mexeu de uma amostra para a seguinte.

        E o unico sinal que distingue um IC que parou de responder de um IC
        saudavel: as tensoes continuam a sair do registo, com o ultimo valor
        bom, e nada mais no bloco denuncia isso.

        So compara entre amostras DIFERENTES. A mesma linha entregue duas vezes
        tem, por definicao, o mesmo contador -- compara-la consigo mesma
        marcava os doze slaves como mudos de uma so vez.
        """
        cc = (blk.get("diag") or {}).get("cc")
        if not isinstance(cc, list):
            return
        repeated = seq is not None and seq == self._cc_seq
        if self._cc is not None and len(self._cc) == len(cc) and not repeated:
            n = min(len(cc), self.car.slave_count)
            self._stalled = {
                i for i in range(n)
                if isinstance(cc[i], int) and cc[i] == self._cc[i] and cc[i] != 0
            }
        if not repeated:
            self._cc = list(cc)
            self._cc_seq = seq

    def live(self, now: float | None = None) -> bool:
        now = now or time.time()
        return self.last_ams_ts > 0 and (now - self.last_ams_ts) <= AMS_TIMEOUT_S

    # -- construcao ----------------------------------------------------------

    def _slave_position(self, si: int) -> tuple[int, int]:
        """Indice de slave (base 0) -> (segmento, primeiro paralelo nesse segmento)."""
        per_seg = self.car.slaves_per_segment or 1
        segment = si // per_seg + 1
        first = (si % per_seg) * self.car.cells_per_slave
        return segment, first

    def _bad(self, key: str, si: int) -> bool:
        """Este slave falhou este teste de `diag` nesta amostra?"""
        return _bit((self.blk.get("diag") or {}).get(key), si)

    def _build_cells(self) -> list[Cell]:
        car = self.car
        rows = self.blk.get("c") or []
        diag = self.blk.get("diag") or {}
        bal = self.blk.get("bal") or {}
        dcc = bal.get("dcc") if isinstance(bal.get("dcc"), list) else []
        ow_c = diag.get("ow_c") if isinstance(diag.get("ow_c"), list) else []
        ow_cr = diag.get("ow_cr") if isinstance(diag.get("ow_cr"), list) else []
        detected = self.blk.get("adbms", {}).get("n")

        out: list[Cell] = []
        for si in range(car.slave_count):
            segment, first = self._slave_position(si)
            row = rows[si] if si < len(rows) and isinstance(rows[si], list) else []
            # Um slave com PEC mau publica numeros plausiveis e errados; um
            # slave acima do numero detetado publica zeros; um slave cujo
            # command counter parou publica o ultimo valor bom para sempre.
            # Nos tres casos a leitura nao vale, e dizer isso e o unico
            # comportamento honesto.
            trust = not (
                self._bad("pec_c", si)
                or si in self._stalled
                or (isinstance(detected, int) and si >= detected)
            )

            for ch in range(car.cells_per_slave):
                mv = row[ch] if ch < len(row) else None
                v = mv / 1000.0 if isinstance(mv, (int, float)) and mv else None
                open_wire = _bit(ow_c[si] if si < len(ow_c) else 0, ch)
                # A medicao redundante e o que separa fio solto de erro de
                # leitura. Guardado para a falha o poder dizer.
                confirmed = open_wire and _bit(ow_cr[si] if si < len(ow_cr) else 0, ch)
                balancing = _bit(dcc[si] if si < len(dcc) else 0, ch)

                if v is None or not trust:
                    status = Severity.UNKNOWN
                elif open_wire:
                    status = Severity.FAULT
                else:
                    status = classify_cell(v, None, car.limits)

                idx = first + ch + 1
                out.append(
                    Cell(
                        id=f"cell_{segment:02d}_{idx:02d}",
                        segment=segment,
                        index=idx,
                        slave=si + 1,
                        slave_channel=ch + 1,
                        parallel=car.parallel_strings,
                        voltage=round(v, 4) if v is not None else 0.0,
                        temperature=None,
                        balancing=balancing,
                        open_wire=open_wire,
                        status=status,
                    )
                )
                if confirmed:
                    self._ow_confirmed.add((si + 1, ch + 1))
        return out

    def _build_thermistors(self) -> list[Thermistor]:
        car = self.car
        rows = self.blk.get("t") or []
        diag = self.blk.get("diag") or {}
        ow_a = diag.get("ow_a") if isinstance(diag.get("ow_a"), list) else []
        limits = car.limits
        detected = self.blk.get("adbms", {}).get("n")

        out: list[Thermistor] = []
        for si in range(car.slave_count):
            segment, _ = self._slave_position(si)
            row = rows[si] if si < len(rows) and isinstance(rows[si], list) else []
            trust = not (
                self._bad("pec_rx", si)
                or si in self._stalled
                or (isinstance(detected, int) and si >= detected)
            )
            for ti in range(car.temps_per_slave):
                t = row[ti] if ti < len(row) else None
                t = float(t) if isinstance(t, (int, float)) else None
                # NTC com bypass herda o valor da anterior no firmware
                # (`NTC_ResolveSource`), e no JSON isso e indistinguivel de duas
                # a mesma temperatura. Reportar sem leitura, como no CAN.
                if f"{si + 1}:{ti + 1}" in car.broken_thermistors:
                    t = None
                if _bit(ow_a[si] if si < len(ow_a) else 0, ti):
                    t = None
                if not trust:
                    t = None

                out.append(
                    Thermistor(
                        slave=si + 1,
                        index=ti + 1,
                        segment=segment,
                        temperature=round(t, 2) if t is not None else None,
                        status=(
                            Severity.UNKNOWN if t is None
                            else Severity.FAULT if t >= limits.temp_fault
                            else Severity.WARN if t >= limits.temp_warn
                            else Severity.OK
                        ),
                    )
                )
        return out

    def _build_segments(self, cells: list[Cell], thermistors: list[Thermistor]) -> list[Segment]:
        bal = self.blk.get("bal") or {}
        out: list[Segment] = []
        for s in range(1, self.car.n_segments + 1):
            group = [c for c in cells if c.segment == s]
            volts = [c.voltage for c in group if c.status is not Severity.UNKNOWN]
            temps = [t.temperature for t in thermistors
                     if t.segment == s and t.temperature is not None]
            out.append(
                Segment(
                    id=s,
                    name=f"Segmento {s}",
                    voltage=round(sum(volts), 3) if volts else 0.0,
                    temp_max=round(max(temps), 2) if temps else 0.0,
                    temp_avg=round(sum(temps) / len(temps), 2) if temps else 0.0,
                    cell_v_min=round(min(volts), 4) if volts else 0.0,
                    cell_v_max=round(max(volts), 4) if volts else 0.0,
                    # Por segmento e nao pelo pack: agora sabe-se celula a
                    # celula quem esta a sangrar.
                    balancing_active=any(c.balancing for c in group),
                    status=worst([c.status for c in group]
                                 + [t.status for t in thermistors if t.segment == s]),
                    hotspot=f"hotspot-seg-{s}",
                )
            )
        return out

    def _build_pack(self, cells: list[Cell], thermistors: list[Thermistor]) -> Pack:
        i1 = self.blk.get("i1") or {}
        adbms = self.blk.get("adbms") or {}
        soc = self.blk.get("soc") or {}

        # A ISA do pack, no CAN1. NUNCA a do handcart (`i2`): usam os mesmos IDs
        # em barramentos diferentes e trocá-las poe a corrente de carga onde
        # devia estar a de tracao.
        # Soma das celulas primeiro: e a tensao do pack com ou sem contactores.
        # A ISA mede do lado do barramento, e com HV desligada le ~0 V.
        vsum = _f(adbms, "vsum")
        voltage = vsum / 1000.0 if vsum else None
        if not voltage:
            mv = _f(i1, "u")
            voltage = mv / 1000.0 if mv else None

        ma = _f(i1, "i")
        current = ma / 1000.0 if ma is not None else None
        power = _f(i1, "p")

        volts = [c.voltage for c in cells if c.status is not Severity.UNKNOWN]
        temps = [t.temperature for t in thermistors if t.temperature is not None]

        vmx, vmn = _f(adbms, "vmx"), _f(adbms, "vmn")
        cmax = vmx / 1000.0 if vmx else (max(volts) if volts else 0.0)
        cmin = vmn / 1000.0 if vmn else (min(volts) if volts else 0.0)
        # 0,01 graus no `adbms`, ao contrario do array `t` que ja vem em graus.
        tmx, tmn = _f(adbms, "tmx"), _f(adbms, "tmn")

        p = _f(soc, "p")
        # `used` sao coulombs retirados desde o baseline, e fica NEGATIVO quando
        # entrou carga. So a parte positiva e energia consumida -- usar o valor
        # absoluto punha "695 Wh gastos" logo depois de carregar.
        used_as = _f(soc, "used") or 0.0
        used_wh = max(0.0, used_as) * (voltage or 0.0) / 3600.0

        return Pack(
            voltage=round(voltage, 2) if voltage else 0.0,
            current=round(current, 2) if current is not None else 0.0,
            power=round(power, 1) if power is not None else 0.0,
            soc=round(p, 1) if p is not None else 0.0,
            energy_used=round(used_wh, 1),
            cell_v_min=round(cmin, 4),
            cell_v_max=round(cmax, 4),
            cell_v_delta=round(cmax - cmin, 4),
            temp_max=round(tmx / 100.0, 1) if tmx else (round(max(temps), 1) if temps else 0.0),
            temp_min=round(tmn / 100.0, 1) if tmn else (round(min(temps), 1) if temps else 0.0),
            status=worst([c.status for c in cells] + [t.status for t in thermistors]),
        )

    def _build_safety(self) -> Safety:
        brain = self.blk.get("brain") or {}
        ae = self.blk.get("ae") or {}
        ct = self.blk.get("ct") or {}

        ps = _name(PRECHARGE_STATES, ct.get("ps"))
        # Só o que este firmware mede de facto. O IMD nao esta ligado ao AMS:
        # uma luz verde a dizer "isolamento bom" com base em nada e pior do que
        # nao ter luz. O SDC so entra quando a linha o traz.
        available = ["ams_ok", "air_positive", "air_negative", "precharge_done"]
        if ct.get("sdc") is not None:
            available.append("sdc_closed")
        return Safety(
            available=available,
            sdc_closed=bool(ct.get("sdc")),
            ams_ok=not bool(ae.get("a")),
            air_positive=bool(ct.get("ap")),
            air_negative=bool(ct.get("an")),
            precharge_done=ps == HV_ON,
            master_state=_name(AMS_STATES, brain.get("st")),
            precharge_state=ps,
        )

    def _build_ams(self) -> Ams:
        brain = self.blk.get("brain") or {}
        bd = self.blk.get("bd") or {}
        adbms = self.blk.get("adbms") or {}

        # `bd.fan` e 0-255, o valor do registo de PWM. A interface fala em
        # percentagem em todos os outros sitios.
        fan = _f(bd, "fan")
        return Ams(
            fan_pwm=round(fan / 255.0 * 100.0, 1) if fan is not None else None,
            mcu_temperature=_f(bd, "mt"),
            pec_error=bool(adbms.get("pe")),
            fault_counter=int(brain.get("nf") or 0),
            runtime_s=_f(brain, "rt"),
            slaves_detected=adbms.get("n") if isinstance(adbms.get("n"), int) else None,
        )

    def _build_charger(self, now: float) -> Charger:
        chg = self.blk.get("chg") or {}
        i2 = self.blk.get("i2") or {}

        # A ISA do handcart, no CAN2. `sts` diz que ja chegou status do
        # carregador -- e o que distingue "no carro" de "no carregador", e nao
        # uma corrente negativa, que tambem acontece em regeneracao.
        present = bool(chg.get("sts")) or bool(chg.get("req"))
        raw = _f(chg, "raw")
        ma, mv = _f(i2, "i"), _f(i2, "u")

        charging = _name(CHARGER_STATES, chg.get("st")) == "CHARGING"
        watts = abs(ma or 0.0) / 1000.0 * (mv or 0.0) / 1000.0
        if charging:
            if self._chg_started is None:
                self._chg_started = now
                self._chg_energy_wh = 0.0
            # Guarda de intervalo: se passaram mais de 5 s desde a ultima
            # amostra houve perda de radio, e integrar esse buraco a corrente
            # atual inventava energia que ninguem mediu.
            if self._chg_last is not None and (gap := now - self._chg_last) <= 5.0:
                self._chg_energy_wh += watts * gap / 3600.0
            self._chg_last = now
        else:
            self._chg_started = None
            self._chg_last = None

        return Charger(
            present=present,
            output_voltage=round(mv / 1000.0, 1) if mv else 0.0,
            # Positiva a entrar: a ISA reporta negativo em carga, e no ecra do
            # carregador o que se quer ver e quanto esta a entrar.
            output_current=round(abs(ma) / 1000.0, 2) if ma is not None else 0.0,
            requested_current=round(raw / 10.0, 1) if raw is not None else 0.0,
            enabled=bool(chg.get("req")),
            faults=list(chg.get("flags") or [])
            + ([] if chg.get("iok", 1) else ["Corrente pedida fora dos limites"]),
            energy_wh=round(self._chg_energy_wh, 1),
            session_s=round(now - self._chg_started, 0) if self._chg_started else 0.0,
        )

    # -- falhas --------------------------------------------------------------

    def _build_faults(self, now: float) -> list[Fault]:
        """Falhas desta amostra.

        Reconstruidas de cada vez, ao contrario do CAN, onde ficam pousadas com
        a hora a que apareceram: aqui cada linha e uma fotografia completa, e um
        fault que desapareceu do bloco desapareceu de facto.

        A hora de quando surgiu e mantida para as que continuam ativas, senao a
        lista mostrava todas como se tivessem acabado de aparecer.
        """
        was = {f.code: f for f in self._faults}
        out: list[Fault] = []
        seen: set[str] = set()

        def add(code: str, sev: Severity, msg: str) -> None:
            if code in seen:
                return
            seen.add(code)
            old = was.get(code)
            out.append(Fault(code=code, severity=sev, message=msg,
                             ts=old.ts if old else now))

        ae = self.blk.get("ae") or {}
        names = ae.get("f")
        if isinstance(names, list):
            for n in names:
                if isinstance(n, str) and n:
                    add(n, Severity.FAULT, n.replace("_", " ").capitalize())

        # A lista vem truncada nos 8 primeiros. As mascaras estao completas, mas
        # sem a tabela FaultCode_t nao ha como lhes dar nome -- dizer quantos
        # faltam e melhor do que deixar acreditar que a lista e toda.
        nf = ae.get("nf")
        shown = len(names) if isinstance(names, list) else 0
        if isinstance(nf, int) and nf > shown:
            add("FAULTS_TRUNCADOS", Severity.WARN,
                f"Mais {nf - shown} falha(s) ativa(s) nao listadas "
                f"(mascaras 0x{ae.get('mh', 0):X}{ae.get('ml', 0):08X})")

        # Diagnostico por slave.
        for key, (what, sev) in SLAVE_FLAGS.items():
            for si in range(self.car.slave_count):
                if self._bad(key, si):
                    add(f"{key.upper()}_S{si + 1}", sev, f"Slave {si + 1}: {what}")

        for si in sorted(self._stalled):
            add(f"SLAVE_{si + 1}_MUTE", Severity.FAULT,
                f"Slave {si + 1} deixou de responder (command counter parado)")

        for slave, ch in sorted(self._ow_confirmed):
            add(f"OPEN_WIRE_{slave}_{ch}", Severity.FAULT,
                f"Openwire confirmado: slave {slave}, celula {ch}")

        adbms = self.blk.get("adbms") or {}
        n = adbms.get("n")
        if isinstance(n, int) and n < self.car.slave_count:
            add("SLAVES_MISSING", Severity.FAULT,
                f"So {n} de {self.car.slave_count} slaves detetados")

        # Barramentos CAN do proprio AMS. Nao afetam o que se ve aqui -- estes
        # dados vieram por BLE -- mas um bus-off no CAN1 quer dizer que o carro
        # deixou de receber o AMS, e quem esta a olhar para isto quer saber.
        can = self.blk.get("can") or {}
        for bus, label in ((1, "powertrain"), (2, "carregador")):
            if can.get(f"bo{bus}"):
                add(f"CAN{bus}_BUS_OFF", Severity.FAULT,
                    f"CAN{bus} ({label}) em bus-off")
            tec = can.get(f"tec{bus}")
            if isinstance(tec, int) and tec >= 96:
                add(f"CAN{bus}_TEC", Severity.WARN,
                    f"CAN{bus} ({label}): contador de erros de envio em {tec}")

        drop = self.blk.get("drop")
        if isinstance(drop, int) and drop > 0:
            add("LIVE_DEBUG_DROP", Severity.WARN,
                f"{drop} bloco(s) descartados no AMS por buffer cheio")

        self._faults = out
        return out

    # -- snapshot ------------------------------------------------------------

    def snapshot(self, link: LinkMeta) -> BmsState:
        car = self.car
        now = time.time()

        # Preenchido pelo _build_cells e lido pelo _build_faults, portanto tem
        # de ser limpo antes e nao depois.
        self._ow_confirmed: set[tuple[int, int]] = set()

        cells = self._build_cells()
        thermistors = self._build_thermistors()
        segments = self._build_segments(cells, thermistors)
        pack = self._build_pack(cells, thermistors)
        safety = self._build_safety()
        ams = self._build_ams()
        charger = self._build_charger(now)
        faults = self._build_faults(now)

        chg = self.blk.get("chg") or {}
        charging = _name(CHARGER_STATES, chg.get("st")) == "CHARGING"

        return BmsState(
            ts=now,
            stale=not self.live(now),
            link=link,
            pack=pack,
            safety=safety,
            ams=ams,
            mode="charger" if charger.present else "car",
            charging=charging,
            charger=charger,
            segments=segments,
            cells=cells,
            thermistors=thermistors,
            faults=faults,
            n_segments=car.n_segments,
            cells_per_segment=car.cells_per_segment,
            parallel_strings=car.parallel_strings,
            cell_model=car.cell_model,
            topology=car.topology,
            slave_count=car.slave_count,
            cells_per_slave=car.cells_per_slave,
            temps_per_slave=car.temps_per_slave,
        )
