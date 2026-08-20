"""TEK-26e CAN decoder.

Turns `powertrain_t26.dbc` traffic into the UniversalBmsState the UI consumes.
Signal names and scaling come from cantools reading the DBC — never from byte
offsets written out by hand here, because the DBC is the thing that changes.

Shape of the bus (see transports/DBC_T26.md for the full map):

  0x600 + (n-1)*7, n = 1..12     one block of 7 messages per slave
    +0..+2  cell_voltage_1..12       0.001 V
    +3..+4  temperature_value_1..6   0.01 degC, plus max and delta
    +5..+6  module summary, IC voltage/temperature, OV/UV flags
  0x701..0x706, 0x709             master: current, precharge, faults, SOC
  0x521..0x528                    ISA IVT current/voltage/power/energy

Two decisions worth knowing about:

* **Current and pack voltage come from the IVT, and only from the IVT.**
  `ams_current_draw` sounds like the pack current and is not: the firmware
  fills it from an MCS1802 hall sensor measuring what the AMS master board
  itself draws. `IVT_Result_I` is the signed milliamp reading from the ISA
  shunt. With the shunt silent the current is unknown, and says so.

* **Cell temperature stays empty.** There are 6 NTCs per slave against 12 cell
  groups, and which group each NTC sits on is a hardware fact this code does
  not have. Temperatures are reported per thermistor, where they are true,
  rather than smeared over cell groups where they would be invented.

The handcart database is merged in on top, so when the accumulator is on the
charger its messages land here too. That is what tells negative shunt current
apart from regen: a negative reading with `Charger_Status` live on the bus is a
charging session, the same reading without it is the car braking.
"""

from __future__ import annotations

import re
import time
from typing import Any

from ..cars import CarProfile
from ..logbuffer import log
from ..state import (
    BmsState,
    Cell,
    Charger,
    Fault,
    LinkMeta,
    Pack,
    Safety,
    Segment,
    Severity,
    Thermistor,
    classify_cell,
    worst,
)
from ..transports.base import RawFrame

# A signal older than this is not used. Long enough to ride out a missed frame
# on a 100 ms message, short enough that a dead node stops feeding the UI.
SIGNAL_TTL_S = 1.0

# No AMS frame for this long and the pack is considered offline. Matches the
# value the Foxglove bridge has been running with.
AMS_TIMEOUT_S = 1.5

SLAVE_RE = re.compile(r"^Slave_(\d{2})_(Voltage|Temperature|MSC)_ID_(\d)$")

# precharge_state == HV_ON: the AIRs are closed and the precharge relay is back
# open. Any other state means the sequence has not finished.
PRECHARGE_DONE_STATE = "HV_ON"

# The handcart carries three charger variants at different frame ids, all with
# the same signal names. Whichever one is on the bus wins; they are never
# present at the same time.
CHARGER_PREFIXES = ("Charger_Status", "BMS_ChargingRequest")

# A charger message this recent means a session is in progress. Longer than
# SIGNAL_TTL_S because the charger only reports at 1 Hz.
CHARGER_TTL_S = 3.0

CHARGER_FAULT_LABELS = {
    "HW_Failure": "Avaria de hardware do carregador",
    "Temp_OTP": "Carregador em protecao termica",
    "Input_Voltage_Fault": "Tensao de entrada fora de gama",
    "Starting_State_Fault": "Falha no arranque do carregador",
    "Comm_Timeout": "Carregador sem comunicacao com o BMS",
}


def _num(value: Any) -> float | None:
    """cantools hands back NamedSignalValue where the DBC has a VAL_ table."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    raw = getattr(value, "value", None)
    return float(raw) if isinstance(raw, (int, float)) else None


def _name(value: Any) -> str:
    return str(value) if value is not None else ""


class _Timed:
    """Last value of a signal, with the moment it arrived."""

    __slots__ = ("value", "ts")

    def __init__(self) -> None:
        self.value: Any = None
        self.ts: float = 0.0

    def set(self, value: Any, ts: float) -> None:
        self.value = value
        self.ts = ts

    def get(self, now: float, ttl: float = SIGNAL_TTL_S) -> Any:
        if self.value is None or (now - self.ts) > ttl:
            return None
        return self.value

    def num(self, now: float, ttl: float = SIGNAL_TTL_S) -> float | None:
        return _num(self.get(now, ttl))


class T26Decoder:
    def __init__(self, car: CarProfile, db: Any) -> None:
        self.car = car
        self.db = db

        n_slaves = car.slave_count
        self.cell_v: list[list[float | None]] = [
            [None] * car.cells_per_slave for _ in range(n_slaves)
        ]
        self.temp: list[list[float | None]] = [
            [None] * car.temps_per_slave for _ in range(n_slaves)
        ]
        self.module: list[dict[str, Any]] = [{} for _ in range(n_slaves)]
        self.slave_ts: list[float] = [0.0] * n_slaves

        self.sig: dict[str, _Timed] = {}
        self.last_ams_ts: float = 0.0
        self.decoded = 0
        self.undecodable: set[int] = set()
        self._faults: list[Fault] = []
        self._balancing = False

        # Charging session accumulator. Integrated here rather than read from
        # the IVT's Wh register because that register counts for the life of
        # the sensor; what matters on the handcart is this session.
        self._chg_energy_wh = 0.0
        self._chg_started: float | None = None
        self._chg_last: float | None = None

        # frame id -> (kind, slave index, block number). Built once from the
        # database so a renumbered DBC needs no change here.
        self.slave_frames: dict[int, tuple[str, int, int]] = {}
        self.ams_frames: set[int] = set()
        self.known: dict[int, str] = {}
        for msg in db.messages:
            self.known[msg.frame_id] = msg.name
            if "AMS" in getattr(msg, "senders", []):
                self.ams_frames.add(msg.frame_id)
            m = SLAVE_RE.match(msg.name)
            if m:
                slave = int(m.group(1))
                if 1 <= slave <= n_slaves:
                    self.slave_frames[msg.frame_id] = (m.group(2), slave - 1, int(m.group(3)))

        missing = n_slaves * 7 - len(self.slave_frames)
        log.info(
            "Descodificador T26 pronto: %d mensagens de slave, %d frames do AMS%s",
            len(self.slave_frames), len(self.ams_frames),
            f" ({missing} em falta na DBC)" if missing else "",
        )
        self._audit(db, n_slaves)

    @staticmethod
    def _audit(db: Any, n_slaves: int) -> None:
        """Warn about slave blocks that do not match their siblings.

        The 12 slave blocks run identical firmware and must be described
        identically. Where the DBC disagrees with itself the numbers decode
        silently wrong, which is far worse than not decoding at all — so it is
        said out loud, in the console, once per connection.
        """
        kinds = ("Voltage_ID_1", "Voltage_ID_2", "Voltage_ID_3",
                 "Temperature_ID_1", "Temperature_ID_2", "MSC_ID_1", "MSC_ID_2")
        for kind in kinds:
            layouts: dict[tuple, list[int]] = {}
            for n in range(1, n_slaves + 1):
                try:
                    msg = db.get_message_by_name(f"Slave_{n:02d}_{kind}")
                except Exception:  # noqa: BLE001 - absent is reported separately
                    continue
                key = tuple(sorted((s.name, s.start, s.length, s.scale) for s in msg.signals))
                layouts.setdefault(key, []).append(n)
            if len(layouts) < 2:
                continue
            # The layout most slaves share is the reference; the rest are odd.
            ordered = sorted(layouts.items(), key=lambda kv: len(kv[1]), reverse=True)
            odd = [n for _, slaves in ordered[1:] for n in slaves]
            log.warning(
                "DBC inconsistente em %s: slaves %s diferem dos restantes - "
                "esses valores vao descodificar mal",
                kind, ", ".join(str(n) for n in odd),
            )

    # -- ingest --------------------------------------------------------------

    def feed(self, frames: list[RawFrame]) -> int:
        """Fold a batch of raw frames into the running picture."""
        used = 0
        for frame in frames:
            fid = frame.id
            if not isinstance(fid, int) or fid not in self.known:
                continue
            try:
                decoded = self.db.decode_message(fid, frame.payload)
            except Exception as exc:  # noqa: BLE001 - short DLC, bad frame
                if fid not in self.undecodable:
                    self.undecodable.add(fid)
                    log.warning("Frame 0x%X nao descodificavel: %s", fid, exc)
                continue

            used += 1
            ts = frame.ts
            if fid in self.ams_frames:
                self.last_ams_ts = ts

            slot = self.slave_frames.get(fid)
            if slot is not None:
                self._feed_slave(slot, decoded, ts)
                continue

            name = self.known[fid]
            if name.startswith(("Master_", "AMS_", "IVT_Msg_Result")):
                for key, value in decoded.items():
                    self.sig.setdefault(key, _Timed()).set(value, ts)
            elif name.startswith(CHARGER_PREFIXES):
                # The three charger variants share signal names, so they are
                # namespaced by role rather than by frame: whichever charger is
                # plugged in fills the same slots.
                role = "chg" if name.startswith("Charger_Status") else "req"
                for key, value in decoded.items():
                    self.sig.setdefault(f"{role}.{key}", _Timed()).set(value, ts)

        self.decoded += used
        return used

    def _feed_slave(self, slot: tuple[str, int, int], decoded: dict[str, Any], ts: float) -> None:
        kind, si, block = slot
        self.slave_ts[si] = ts

        if kind == "Voltage":
            # Voltage_ID_1 carries cells 1-4, ID_2 5-8, ID_3 9-12.
            base = (block - 1) * 4
            for off in range(4):
                v = _num(decoded.get(f"cell_voltage_{base + off + 1}"))
                if v is not None and base + off < len(self.cell_v[si]):
                    self.cell_v[si][base + off] = v
            return

        if kind == "Temperature":
            # ID_1 carries NTC 1-4, ID_2 carries 5-6 plus max/delta.
            span = range(1, 5) if block == 1 else range(5, 7)
            for n in span:
                t = _num(decoded.get(f"temperature_value_{n}"))
                if t is not None and n - 1 < len(self.temp[si]):
                    self.temp[si][n - 1] = t
            if block == 2:
                self.module[si]["temp_max"] = _num(decoded.get("temperature_maximum"))
                self.module[si]["temp_delta"] = _num(decoded.get("temperature_delta"))
            return

        # MSC_ID_1 / MSC_ID_2: per-module summary and status flags.
        for key, value in decoded.items():
            self.module[si][key] = _num(value)

    # -- liveness ------------------------------------------------------------

    def live(self, now: float | None = None) -> bool:
        now = now or time.time()
        return self.last_ams_ts > 0 and (now - self.last_ams_ts) <= AMS_TIMEOUT_S

    # -- snapshot ------------------------------------------------------------

    def snapshot(self, link: LinkMeta) -> BmsState:
        car = self.car
        limits = car.limits
        now = time.time()

        # Before the cells: they carry the balancing flag, and reading it after
        # _build_safety had set it left every frame one behind.
        state_name = _name(self.sig["master_state"].get(now)) if "master_state" in self.sig else ""
        self._balancing = state_name == "BALANCING"

        cells = self._build_cells(now)
        thermistors = self._build_thermistors(now)
        segments = self._build_segments(cells, thermistors)
        pack = self._build_pack(cells, thermistors, now)
        safety = self._build_safety(now)
        charger = self._build_charger(now)
        self._update_faults(now, pack, charger, cells)

        return BmsState(
            ts=now,
            stale=not self.live(now),
            link=link,
            pack=pack,
            safety=safety,
            charger=charger,
            # Hearing the charger is what says which bus this is. Nothing is
            # configured; plug in, and the app works out where it landed.
            mode="charger" if charger.present else "car",
            # Negative current alone is ambiguous: it is regen on track and a
            # charge on the handcart. The charger being on the bus is what
            # separates them.
            charging=charger.present and pack.current < -0.5,
            segments=segments,
            cells=cells,
            thermistors=thermistors,
            faults=list(self._faults),
            n_segments=car.n_segments,
            cells_per_segment=car.cells_per_segment,
            parallel_strings=car.parallel_strings,
            cell_model=car.cell_model,
            topology=car.topology,
            slave_count=car.slave_count,
            cells_per_slave=car.cells_per_slave,
            temps_per_slave=car.temps_per_slave,
        )

    def _slave_position(self, si: int) -> tuple[int, int]:
        """Slave index (0-based) -> (segment, first group index in it)."""
        per_seg = self.car.slaves_per_segment or 1
        segment = si // per_seg + 1
        first = (si % per_seg) * self.car.cells_per_slave
        return segment, first

    def _build_cells(self, now: float) -> list[Cell]:
        car = self.car
        cells: list[Cell] = []
        for si in range(car.slave_count):
            segment, first = self._slave_position(si)
            fresh = (now - self.slave_ts[si]) <= SIGNAL_TTL_S if self.slave_ts[si] else False
            for ch in range(car.cells_per_slave):
                v = self.cell_v[si][ch] if fresh else None
                index = first + ch + 1
                open_wire = v is not None and v < car.limits.v_open_wire
                cells.append(
                    Cell(
                        id=f"cell_{segment:02d}_{index:02d}",
                        segment=segment,
                        index=index,
                        slave=si + 1,
                        slave_channel=ch + 1,
                        parallel=car.parallel_strings,
                        voltage=round(v, 4) if v is not None else 0.0,
                        # NTC -> group mapping is unknown; see module docstring.
                        temperature=None,
                        balancing=self._balancing,
                        open_wire=open_wire,
                        status=(
                            Severity.UNKNOWN if v is None
                            # An open wire is a wiring fault, not a flat cell.
                            # Reported as a fault either way, but never as a
                            # voltage anyone should act on.
                            else Severity.FAULT if open_wire
                            else classify_cell(v, None, car.limits)
                        ),
                    )
                )
        return cells

    def _build_thermistors(self, now: float) -> list[Thermistor]:
        car = self.car
        limits = car.limits
        out: list[Thermistor] = []
        for si in range(car.slave_count):
            segment, _ = self._slave_position(si)
            fresh = (now - self.slave_ts[si]) <= SIGNAL_TTL_S if self.slave_ts[si] else False
            for ti in range(car.temps_per_slave):
                t = self.temp[si][ti] if fresh else None
                if f"{si + 1}:{ti + 1}" in car.broken_thermistors:
                    # Known-dead NTC. Reported as no reading rather than as the
                    # neighbouring channel's value, so nobody trusts a number
                    # the hardware never measured.
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
        segments: list[Segment] = []
        for s in range(1, self.car.n_segments + 1):
            group = [c for c in cells if c.segment == s]
            volts = [c.voltage for c in group if c.status is not Severity.UNKNOWN]
            temps = [t.temperature for t in thermistors
                     if t.segment == s and t.temperature is not None]
            segments.append(
                Segment(
                    id=s,
                    name=f"Segmento {s}",
                    voltage=round(sum(volts), 3) if volts else 0.0,
                    temp_max=round(max(temps), 2) if temps else 0.0,
                    temp_avg=round(sum(temps) / len(temps), 2) if temps else 0.0,
                    cell_v_min=round(min(volts), 4) if volts else 0.0,
                    cell_v_max=round(max(volts), 4) if volts else 0.0,
                    balancing_active=self._balancing,
                    status=worst([c.status for c in group] + [t.status for t in thermistors
                                                              if t.segment == s]),
                    hotspot=f"hotspot-seg-{s}",
                )
            )
        return segments

    def _build_pack(self, cells: list[Cell], thermistors: list[Thermistor], now: float) -> Pack:
        sig = self.sig

        def s(name: str) -> float | None:
            t = sig.get(name)
            return t.num(now) if t else None

        # --- current: the IVT, and nothing else -------------------------------
        # `ams_current_draw` is NOT the pack current, despite the name. The
        # firmware fills it from `analog_readings->ams_master_current`, an
        # MCS1802 hall sensor at 0.264 V/A reading what the AMS master board
        # itself consumes -- a few amps, unrelated to traction. Using it as a
        # fallback put a plausible-looking half-amp where the pack current goes.
        # If the ISA shunt is quiet, the current is unknown and says so.
        current = None
        ivt_ma = s("IVT_Result_I")
        if ivt_ma is not None:
            current = ivt_ma / 1000.0

        # --- pack voltage: IVT, else the cell voltages added up --------------
        # Not the sum of `module_voltage_sum`, even though that looks like the
        # obvious shortcut: Slave_10_MSC_ID_1 declares its four fields as 8-bit
        # instead of 16-bit in the DBC, so slave 10's module total decodes to at
        # most 0.255 V and the sum comes out ~46 V short. The per-cell voltages
        # are unaffected and add up to the same number.
        voltage = None
        ivt_mv = s("IVT_Result_U1")
        if ivt_mv is not None:
            voltage = ivt_mv / 1000.0
        else:
            measured = [c.voltage for c in cells if c.status is not Severity.UNKNOWN]
            # Only trustworthy with every group reporting: a partial sum reads
            # as a pack that lost half its voltage.
            if len(measured) == len(cells) and cells:
                voltage = sum(measured)

        power = s("IVT_Result_W")
        if power is None and voltage is not None and current is not None:
            power = voltage * current

        energy_wh = s("IVT_Result_Wh")

        soc_i = s("SOC_Integer")
        soc_f = s("SOC_Float")
        soc = None
        if soc_i is not None:
            soc = soc_i + (soc_f or 0.0)
        elif soc_f is not None:
            soc = soc_f

        # --- extremes: the master computes them across the whole pack --------
        v_max = s("overall_maximum_voltage")
        v_min = s("overall_minimum_voltage")
        t_max = s("overall_maximum_temperature")
        t_min = s("overall_minimum_temperature")

        volts = [c.voltage for c in cells if c.status is not Severity.UNKNOWN]
        if v_max is None and volts:
            v_max = max(volts)
        if v_min is None and volts:
            v_min = min(volts)
        temps = [t.temperature for t in thermistors if t.temperature is not None]
        if t_max is None and temps:
            t_max = max(temps)
        if t_min is None and temps:
            t_min = min(temps)

        return Pack(
            voltage=round(voltage, 2) if voltage is not None else 0.0,
            current=round(current, 2) if current is not None else 0.0,
            power=round(power, 1) if power is not None else 0.0,
            soc=round(soc, 2) if soc is not None else 0.0,
            # No SOH signal on this bus. Left at the model default rather than
            # invented from cycle counts the AMS does not send.
            soh=100.0,
            energy_used=round(abs(energy_wh), 1) if energy_wh is not None else 0.0,
            cell_v_min=round(v_min, 4) if v_min is not None else 0.0,
            cell_v_max=round(v_max, 4) if v_max is not None else 0.0,
            cell_v_delta=round(v_max - v_min, 4) if (v_max is not None and v_min is not None) else 0.0,
            temp_max=round(t_max, 2) if t_max is not None else 0.0,
            temp_min=round(t_min, 2) if t_min is not None else 0.0,
            status=worst([c.status for c in cells] + [t.status for t in thermistors]),
        )

    def _build_safety(self, now: float) -> Safety:
        sig = self.sig

        def flag(name: str) -> bool:
            t = sig.get(name)
            v = t.num(now) if t else None
            return bool(v)

        master_state = _name(sig["master_state"].get(now)) if "master_state" in sig else ""
        precharge = _name(sig["precharge_state"].get(now)) if "precharge_state" in sig else ""

        # What this bus genuinely reports. IMD, BSPD and insulation resistance
        # are absent from the database: the AMS does not read them, so they are
        # not claimed. Feeding an IMD lamp from the SDC would turn "the loop is
        # closed" into "insulation is fine", which is not the same statement.
        return Safety(
            available=["ams_ok", "sdc_closed", "air_positive",
                       "air_negative", "precharge_done"],
            ams_ok=self.live(now) and master_state not in ("FAULT", "KILL"),
            sdc_closed=flag("SDC_State"),
            air_positive=flag("precharge_ctc_air_pos_state"),
            air_negative=flag("precharge_ctc_air_min_state"),
            precharge_done=precharge == PRECHARGE_DONE_STATE,
            master_state=master_state,
            precharge_state=precharge,
        )

    def _build_charger(self, now: float) -> Charger:
        sig = self.sig

        def s(name: str) -> float | None:
            t = sig.get(name)
            return t.num(now, ttl=CHARGER_TTL_S) if t else None

        out_v = s("chg.Output_Voltage")
        present = out_v is not None
        faults = [label for key, label in CHARGER_FAULT_LABELS.items()
                  if s(f"chg.{key}")]

        temp = s("chg.Charger_Temperature")
        req_ctrl = s("req.Control")
        out_i = s("chg.Output_Current") or 0.0

        # Session bookkeeping. A session starts when the charger appears and
        # ends when it goes quiet: unplugging resets the count rather than
        # carrying yesterday's total into tonight.
        if not present:
            self._chg_energy_wh = 0.0
            self._chg_started = None
            self._chg_last = None
        else:
            if self._chg_started is None:
                self._chg_started = now
            if self._chg_last is not None:
                dt = now - self._chg_last
                # Guard the gap: a paused UI or a stalled bus must not book
                # hours of energy that never flowed.
                if 0.0 < dt <= 5.0:
                    self._chg_energy_wh += (out_v or 0.0) * out_i * dt / 3600.0
            self._chg_last = now

        return Charger(
            present=present,
            output_voltage=round(out_v, 1) if out_v is not None else 0.0,
            output_current=round(out_i, 2),
            requested_voltage=round(s("req.Max_Charging_Voltage") or 0.0, 1),
            requested_current=round(s("req.Max_Charging_Current") or 0.0, 2),
            # Control is the enable bit the BMS sends: 0 = charge, 1 = stop, per
            # the usual Elcon/TC protocol the handcart charger speaks.
            enabled=req_ctrl is not None and req_ctrl == 0,
            temperature=round(temp, 1) if temp is not None else None,
            faults=faults,
            energy_wh=round(self._chg_energy_wh, 1),
            session_s=round(now - self._chg_started, 0) if self._chg_started else 0.0,
        )

    # -- faults --------------------------------------------------------------

    def _update_faults(self, now: float, pack: Pack, charger: Charger,
                       cells: list[Cell]) -> None:
        """Latch faults so a condition that clears in 100 ms is still readable.

        Sources, in order of authority: the two fault slots the master
        publishes, then conditions this decoder can see for itself (a slave
        that went quiet, a module flagging over/undervoltage).
        """
        active: list[tuple[str, Severity, str]] = []
        sig = self.sig

        for slot in ("fault1", "fault2"):
            code = sig.get(f"{slot}_code")
            raw = code.get(now, ttl=5.0) if code else None
            label = _name(raw)
            if not label or label == "EMPTY" or _num(raw) == 255:
                continue
            idx_t = sig.get(f"{slot}_index_type")
            idx_v = sig.get(f"{slot}_index_value")
            where = ""
            if idx_t and idx_v and idx_t.get(now, ttl=5.0) is not None:
                where = f" [{_name(idx_t.get(now, ttl=5.0))} {_num(idx_v.get(now, ttl=5.0)):.0f}]"
            active.append((label, Severity.FAULT, f"{label.replace('FAULT_', '')}{where}"))

        for c in cells:
            if c.open_wire:
                active.append((
                    f"OPEN_WIRE_{c.slave}_{c.slave_channel}", Severity.FAULT,
                    f"Fio de medicao solto: slave {c.slave}, celula {c.slave_channel}",
                ))

        # Without the shunt there is no current, no power and no coulomb count.
        # Worth a fault: the pack looks calm on a screen that simply cannot see
        # what it is doing.
        if self.live(now) and sig.get("IVT_Result_I") is None:
            active.append(("IVT_MUTE", Severity.WARN,
                           "Sensor ISA IVT sem transmitir - corrente desconhecida"))

        detected = sig.get("slaves_detected")
        n = detected.num(now, ttl=5.0) if detected else None
        if n is not None and int(n) < self.car.slave_count:
            active.append((
                "SLAVES_MISSING", Severity.FAULT,
                f"So {int(n)} de {self.car.slave_count} slaves detetados",
            ))

        for si in range(self.car.slave_count):
            if self.slave_ts[si] and (now - self.slave_ts[si]) > AMS_TIMEOUT_S:
                active.append((f"SLAVE_{si + 1}_MUTE", Severity.FAULT,
                               f"Slave {si + 1} sem transmitir"))
            if self.module[si].get("module_overvoltage"):
                active.append((f"SLAVE_{si + 1}_OV", Severity.FAULT,
                               f"Sobretensao no slave {si + 1}"))
            if self.module[si].get("module_undervoltage"):
                active.append((f"SLAVE_{si + 1}_UV", Severity.FAULT,
                               f"Subtensao no slave {si + 1}"))

        for label in charger.faults:
            active.append((f"CHARGER:{label}", Severity.FAULT, label))

        known = {f.code for f in self._faults}
        for code, sev, msg in active:
            if code not in known:
                self._faults.insert(0, Fault(code=code, severity=sev, message=msg, latched=True))
        del self._faults[16:]
