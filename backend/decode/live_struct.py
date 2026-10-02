"""A struct `live_debug` do firmware -> UniversalBmsState.

O firmware mantem uma fotografia de tudo o que mede numa unica variavel global,
`live_debug`, atualizada em `brain.c`. Foi feita para a janela Live Expressions
do STM32CubeIDE; serve igualmente bem para ser lida pelo servidor GDB do Black
Magic por WiFi.

## Um so caminho

O JSON que sai pelo BLE e gerado a partir desta mesma struct
(`LiveDebug_SendJson()`). Por isso estes bytes sao convertidos para exatamente
esse JSON -- mesmas chaves, mesmas unidades -- e entram pela mesma traducao
(`live_json.from_firmware`). Uma correcao num caminho corrige os dois.

## A disposicao esta escrita a mao -- e porque

Os deslocamentos vieram de `arm-none-eabi-gdb -ex "ptype /o live_debug_t"`
sobre o `.elf` do firmware. Ler a DWARF em tempo de execucao seria a
alternativa, mas e outra ordem de grandeza de codigo para um problema que tem
uma guarda barata: a tabela de simbolos diz o tamanho da variavel, e se nao for
exatamente `STRUCT_SIZE` a ligacao recusa em vez de ler campos trocados.
"""

from __future__ import annotations

import math
import struct
from typing import Any

from ..cars import CarProfile
from ..transports.base import RawFrame
from .live_json import AMS_STATES, CHARGER_STATES, PRECHARGE_STATES, LiveJsonDecoder

# Nome da variavel global a ler. A morada vem da tabela de simbolos do .elf.
SYMBOL = "live_debug"

# `sizeof(live_debug_t)` do firmware em que esta tabela foi escrita
# (lart_bms beta-v2.1, 2026-10-01).
STRUCT_SIZE = 960

IC_MAX = 12
CELLS_MAX = 12
NTC_MAX = 6
FAULT_LIST = 8

O_BRAIN = 0
O_AMS_ERROR = 24
O_ADBMS = 76
O_IVT1 = 92
O_IVT2 = 116
O_CONTACTORS = 140
O_SOC = 156
O_CHARGER = 168
O_BOARD = 192
O_CAN = 208
O_BAL = 236
O_MEAS = 300
O_JSON = 948

# Campos `const char *`. Apontam para cadeias em flash, que nunca mudam de
# sitio -- quem as le pode guardar o resultado para sempre.
P_STATE_NAME = O_AMS_ERROR + 4
P_FAULTS = O_AMS_ERROR + 20
P_STAGE_NAME = O_BAL + 4
P_PHASE_NAME = O_BAL + 8

POINTER_OFFSETS: tuple[int, ...] = (
    P_STATE_NAME,
    *(P_FAULTS + 4 * i for i in range(FAULT_LIST)),
    P_STAGE_NAME,
    P_PHASE_NAME,
)

ADBMS_STATES = ["END", "ONGOING", "START"]


def pointer_addresses(blob: bytes) -> list[int]:
    """Enderecos das cadeias apontadas por esta fotografia, sem repeticoes."""
    seen: list[int] = []
    for off in POINTER_OFFSETS:
        (addr,) = struct.unpack_from("<I", blob, off)
        if addr and addr not in seen:
            seen.append(addr)
    return seen


def _name(table: list[str], value: int) -> str:
    return table[value] if 0 <= value < len(table) else str(value)


def _d(value: float, scale: int) -> int | None:
    """Float do firmware -> o inteiro com sufixo que o JSON usaria."""
    return round(value * scale) if math.isfinite(value) else None


def to_firmware(blob: bytes, strings: dict[int, str] | None = None) -> dict[str, Any]:
    """Os 960 bytes no formato de `LiveDebug_SendJson()` (LIVE_DEBUG_JSON.md)."""
    strings = strings or {}

    def s(off: int) -> str:
        (addr,) = struct.unpack_from("<I", blob, off)
        return strings.get(addr, "") if addr else ""

    def u(fmt: str, off: int) -> tuple:
        return struct.unpack_from("<" + fmt, blob, off)

    st, pst, _h0, _h1, runtime_s, runtime_ms, nfaults = u("BBBBIIB", O_BRAIN)
    (wwdg,) = u("B", O_BRAIN + 20)

    ae_active, ae_perm = u("BB", O_AMS_ERROR)
    (ae_count,) = u("B", O_AMS_ERROR + 8)
    ae_lo, ae_hi = u("II", O_AMS_ERROR + 12)

    n_slaves, adbms_state, any_pec = u("BBB", O_ADBMS)
    vmax, vmin, tmax, tmin, vsum = u("HHhhI", O_ADBMS + 4)

    def isa(off: int) -> dict[str, int]:
        i, v, p, q, t, age = u("iiiiiI", off)
        return {"current_mA": i, "voltage_mV": v, "power_W": p, "charge_As": q,
                "temp_dC": t, "rx_age_ms": age}

    ps, vcu_req = u("Bb", O_CONTACTORS)
    (vcu_age,) = u("I", O_CONTACTORS + 4)
    mismatch, sdc, air_pos, air_neg, pre, dis = u("6B", O_CONTACTORS + 8)

    soc_pct, soc_used, soc_ready = u("fiB", O_SOC)

    chg_st, chg_sw = u("BB", O_CHARGER)
    (chg_age,) = u("I", O_CHARGER + 4)
    (chg_ok,) = u("B", O_CHARGER + 8)
    out_v, out_i, hw, otp, inv, start, comm = u("HH5B", O_CHARGER + 10)
    chg_temp, req_raw = u("hH", O_CHARGER + 20)

    (fan,) = u("B", O_BOARD)
    mcu, vdda, imaster = u("3f", O_BOARD + 4)

    started1, started2, q1, q2, cs1, cs2, bo1, bo2 = u("BBHHBBBB", O_CAN)
    hw1, hw2 = u("II", O_CAN + 12)
    tec1, tec2, rec1, rec2, le1, le2 = u("6B", O_CAN + 20)

    (bal_active,) = u("B", O_BAL)
    target, discharging = u("HB", O_BAL + 12)
    mask = list(u("12H", O_BAL + 28))
    bmax, bmin, bdelta, worst_delta, worst_slave, worst_cell = u("HHHHBB", O_BAL + 52)

    cells = u(f"{IC_MAX * CELLS_MAX}h", O_MEAS)
    ntc = u(f"{IC_MAX * NTC_MAX}f", O_MEAS + 288)
    die = list(u("12h", O_MEAS + 576))
    pec = list(u("12B", O_MEAS + 600))
    ow_cell = list(u("12H", O_MEAS + 612))
    ow_ntc = list(u("12B", O_MEAS + 636))

    sent, dropped, last_len = u("IIH", O_JSON)

    return {
        "uptime_ms": runtime_ms,
        "bms": {"state": _name(AMS_STATES, st), "prev_state": _name(AMS_STATES, pst),
                "runtime_s": runtime_s, "wwdg_reset": wwdg, "active_faults": nfaults},
        "ams_error": {"state": s(P_STATE_NAME), "active": ae_active, "permanent": ae_perm,
                      "fault_count": ae_count, "fault_mask_lo": ae_lo, "fault_mask_hi": ae_hi,
                      "faults": [n for i in range(FAULT_LIST) if (n := s(P_FAULTS + 4 * i))]},
        "precharge": {"state": _name(PRECHARGE_STATES, ps), "vcu_request": vcu_req,
                      "vcu_request_age_ms": vcu_age, "hv_on_mismatch_count": mismatch,
                      "sdc_closed": sdc, "air_pos": air_pos, "air_neg": air_neg,
                      "precharge_relay": pre, "discharge": dis},
        "isa_pack": isa(O_IVT1),
        "isa_handcart": isa(O_IVT2),
        "soc": {"percent_x100": _d(soc_pct, 100), "used_charge_As": soc_used, "ready": soc_ready},
        "charger": {"state": _name(CHARGER_STATES, chg_st), "handcart_switch": chg_sw,
                    "handcart_age_ms": chg_age, "status_ok": chg_ok,
                    "output_voltage_dV": out_v, "output_current_dA": out_i,
                    "hw_failure": hw, "temp_otp": otp, "input_voltage_fault": inv,
                    "starting_state_fault": start, "comm_timeout": comm,
                    "temp_C": chg_temp, "requested_current_raw": req_raw},
        "pack": {"slaves_found": n_slaves, "adbms_state": _name(ADBMS_STATES, adbms_state),
                 "any_pec_error": any_pec, "cell_max_mV": vmax, "cell_min_mV": vmin,
                 "temp_max_cC": tmax, "temp_min_cC": tmin, "voltage_sum_mV": vsum},
        "balancing": {"active": bal_active, "stage": s(P_STAGE_NAME), "phase": s(P_PHASE_NAME),
                      "target_mV": target, "cells_discharging": discharging,
                      "cell_max_mV": bmax, "cell_min_mV": bmin, "delta_mV": bdelta,
                      "worst_delta_mV": worst_delta, "worst_slave": worst_slave,
                      "worst_cell": worst_cell, "mask": mask},
        "board": {"fan_pwm": fan, "mcu_temp_dC": _d(mcu, 10), "vdda_mV": _d(vdda, 1000),
                  "master_current_mA": _d(imaster, 1000)},
        **{f"can{n}": {"started": a, "state": c, "bus_off": b, "tx_errors": te, "rx_errors": re,
                       "last_error": le, "hw_error": h, "tx_queue": q}
           for n, a, c, b, te, re, le, h, q in (
               (1, started1, cs1, bo1, tec1, rec1, le1, hw1, q1),
               (2, started2, cs2, bo2, tec2, rec2, le2, hw2, q2))},
        "json": {"sent": sent, "dropped": dropped, "last_len": last_len},
        "cell_mV": [list(cells[ic * CELLS_MAX:(ic + 1) * CELLS_MAX]) for ic in range(IC_MAX)],
        "ntc_dC": [[_d(ntc[ic * NTC_MAX + t], 10) for t in range(NTC_MAX)] for ic in range(IC_MAX)],
        "die_dC": die,
        "pec_flags": pec,
        "ow_cell_mask": ow_cell,
        "ow_ntc_mask": ow_ntc,
    }


class LiveStructDecoder(LiveJsonDecoder):
    """`LiveJsonDecoder` alimentado por bytes em vez de texto.

    Cada `RawFrame` traz uma fotografia completa da struct no `payload` e as
    cadeias ja resolvidas em `extra["strings"]`.
    """

    count_lost = False

    def feed(self, frames: list[RawFrame]) -> int:
        used = 0
        for frame in frames:
            if len(frame.payload) != STRUCT_SIZE:
                self.rejected += 1
                continue
            raw = to_firmware(frame.payload, frame.extra.get("strings"))
            if self._accept(raw, frame.ts):
                used += 1
            else:
                self.rejected += 1
        return used


def for_struct(car: CarProfile) -> LiveStructDecoder | None:
    """So o dialeto do T26 tem esta struct. Outro carro, outro firmware."""
    if car.decoder != "t26":
        return None
    return LiveStructDecoder(car)
