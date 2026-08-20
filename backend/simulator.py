"""Fake accumulator, so the whole UI can be built and demoed with no hardware.

Topology, capacity and thresholds all come from the selected CarProfile, so
picking TEK-26e vs T-28 really does change the pack you see.
"""

from __future__ import annotations

import math
import random
import time

from .cars import CarProfile
from .state import (
    Cell,
    Charger,
    Fault,
    Pack,
    Safety,
    Segment,
    Severity,
    Thermistor,
    BmsState,
    classify_cell,
    worst,
)


# Carregador EV Europe OEM3 650-6 do handcart: 600 V a 6 A.
CHARGER_MAX_A = 6.0
CHARGER_MAX_V = 600.0

# Ciclo do demo: conduzir e depois carregar, para se ver o programa todo sem
# hardware nenhum. Curto o suficiente para nao ser preciso esperar.
DRIVE_S = 50.0
CHARGE_S = 30.0
CYCLE_S = DRIVE_S + CHARGE_S


class Simulator:
    """Stateful pack model. Call `step()` at whatever rate you like."""

    def __init__(self, car: CarProfile, seed: int | None = None) -> None:
        self.car = car
        self.rng = random.Random(seed)
        self.t0 = time.time()
        self.soc = 92.0
        self.energy_used = 0.0
        # Contador da sessao de carga, reposto a cada nova fase de carregamento.
        self.chg_energy_wh = 0.0
        self.chg_started: float | None = None
        self._was_charging = False
        self._last_step = self.t0
        self._faults: list[Fault] = []

        # Per-cell fixed personality: tiny manufacturing spread + one weak cell
        # so the UI always has something interesting to highlight.
        self._v_offset = [self.rng.gauss(0, 0.012) for _ in range(self.n_cells)]
        self._t_offset = [self.rng.gauss(0, 1.2) for _ in range(self.n_cells)]
        self._weak = self.rng.randrange(self.n_cells)
        self._v_offset[self._weak] -= 0.09
        self._t_offset[self._weak] += 6.0

        # Thermistors are their own population: 6 per slave, not one per group.
        self._th_offset = [self.rng.gauss(0, 1.6) for _ in range(self.n_thermistors)]
        if self._th_offset:
            self._th_offset[self.rng.randrange(self.n_thermistors)] += 7.0

    @property
    def n_cells(self) -> int:
        """Measured positions, i.e. series groups -- not physical cells."""
        return self.car.series_count

    @property
    def n_thermistors(self) -> int:
        return self.car.slave_count * self.car.temps_per_slave

    # -- current profile -----------------------------------------------------

    def charging_phase(self, t: float) -> bool:
        """Demo alternates between the two situations the pack really lives in.

        Everything is reachable in one session -- which is the point of demo --
        without ever showing an incoherent mixture, like traction current with
        a charger delivering into the pack at the same time.
        """
        return (t % CYCLE_S) >= DRIVE_S

    def _current(self, t: float) -> float:
        """Driving, or charging, depending on the phase.

        Driving: a synthetic lap with accel bursts, coast and regen braking.
        Charging: negative and almost flat, tapering as the pack fills, which
        is what an EV Europe OEM3 at 600 V / 6 A actually does.
        """
        if self.charging_phase(t):
            taper = max(0.15, min(1.0, (100.0 - self.soc) / 15.0))
            return -CHARGER_MAX_A * taper + self.rng.gauss(0, 0.05)

        lap = t % 45.0
        base = 60.0 * math.sin(lap / 45.0 * 2 * math.pi)
        burst = 90.0 * max(0.0, math.sin(lap / 7.0 * 2 * math.pi)) ** 3
        regen = -70.0 if 30.0 < lap < 33.0 else 0.0
        return base + burst + regen + self.rng.gauss(0, 3.0)

    # -- main step -----------------------------------------------------------

    def step(self) -> BmsState:
        car = self.car
        limits = car.limits
        seg_count = car.n_segments
        per_seg = car.cells_per_segment

        now = time.time()
        dt = max(1e-3, now - self._last_step)
        self._last_step = now
        t = now - self.t0

        current = self._current(t)
        pack_nominal_v = car.nominal_pack_v

        # Coulomb counting against the car's capacity, then energy integration.
        self.soc = max(4.0, min(100.0, self.soc - (current * dt) / (car.pack_capacity_ah * 3600.0) * 100.0))
        self.energy_used += max(0.0, current) * pack_nominal_v * dt / 3600.0

        # SOC -> OCV curve (flat middle, steep knees), minus IR drop.
        soc_frac = self.soc / 100.0
        ocv = 3.30 + 0.62 * soc_frac + 0.28 * (soc_frac ** 3)
        ir_drop = current * 0.0009

        # Thermals ramp with current and never quite cool down within a lap.
        heat = 24.0 + 22.0 * (abs(current) / 150.0) + 8.0 * math.sin(t / 60.0)
        ambient = 26.0 + 10.0 * (t / 300.0 if t < 300 else 1.0)

        per_slave = car.cells_per_slave or per_seg
        slaves_per_seg = car.slaves_per_segment or 1

        cells: list[Cell] = []
        for i in range(self.n_cells):
            seg = i // per_seg
            idx = i % per_seg
            # slave+channel from plain arithmetic, same mapping the CAN decoder
            # will use: slave 1 covers groups 1-12 of segment 1, slave 2 the
            # next 12, slave 3 opens segment 2.
            slave = seg * slaves_per_seg + idx // per_slave + 1
            channel = idx % per_slave + 1
            v = ocv - ir_drop + self._v_offset[i] + self.rng.gauss(0, 0.0015)
            v = min(v, limits.v_max + 0.02)
            temp = ambient + heat * 0.35 + self._t_offset[i] + self.rng.gauss(0, 0.25)
            balancing = v > (ocv + 0.02) and abs(current) < 10.0
            cells.append(
                Cell(
                    id=f"cell_{seg + 1:02d}_{idx + 1:02d}",
                    segment=seg + 1,
                    index=idx + 1,
                    slave=slave,
                    slave_channel=channel,
                    voltage=round(v, 4),
                    temperature=round(temp, 2),
                    balancing=balancing,
                    parallel=car.parallel_strings,
                    status=classify_cell(v, temp, limits),
                )
            )

        thermistors: list[Thermistor] = []
        for si in range(car.slave_count):
            for ti in range(car.temps_per_slave):
                k = si * car.temps_per_slave + ti
                t_val = ambient + heat * 0.35 + self._th_offset[k] + self.rng.gauss(0, 0.3)
                thermistors.append(
                    Thermistor(
                        slave=si + 1,
                        index=ti + 1,
                        segment=si // slaves_per_seg + 1,
                        temperature=round(t_val, 2),
                        status=(
                            Severity.FAULT if t_val >= limits.temp_fault
                            else Severity.WARN if t_val >= limits.temp_warn
                            else Severity.OK
                        ),
                    )
                )

        segments: list[Segment] = []
        for s in range(seg_count):
            group = cells[s * per_seg:(s + 1) * per_seg]
            temps = [c.temperature for c in group if c.temperature is not None]
            volts = [c.voltage for c in group]
            segments.append(
                Segment(
                    id=s + 1,
                    name=f"Segmento {s + 1}",
                    voltage=round(sum(volts), 3),
                    temp_max=round(max(temps), 2),
                    temp_avg=round(sum(temps) / len(temps), 2),
                    cell_v_min=round(min(volts), 4),
                    cell_v_max=round(max(volts), 4),
                    balancing_active=any(c.balancing for c in group),
                    status=worst([c.status for c in group]),
                    hotspot=f"hotspot-seg-{s + 1}",
                )
            )

        all_v = [c.voltage for c in cells]
        all_t = [c.temperature for c in cells if c.temperature is not None]
        pack_v = sum(all_v)

        pack = Pack(
            voltage=round(pack_v, 2),
            current=round(current, 2),
            power=round(pack_v * current, 1),
            soc=round(self.soc, 2),
            soh=98.4,
            energy_used=round(self.energy_used, 1),
            cell_v_min=round(min(all_v), 4),
            cell_v_max=round(max(all_v), 4),
            cell_v_delta=round(max(all_v) - min(all_v), 4),
            temp_max=round(max(all_t), 2),
            temp_min=round(min(all_t), 2),
            status=worst([s.status for s in segments]),
        )

        self._update_faults(pack)

        # Em demo o carregador está sempre presente, para o separador de
        # Carregamento estar sempre lá. Só entrega corrente na fase de carga --
        # tal como um carregador ligado mas em repouso.
        on_charge = self.charging_phase(t)
        # Cada fase de carga e uma sessao nova: o contador arranca do zero,
        # como acontece quando se liga o handcart.
        if on_charge and not self._was_charging:
            self.chg_energy_wh = 0.0
            self.chg_started = now
        self._was_charging = on_charge
        if on_charge:
            self.chg_energy_wh += (pack_v + 1.2) * abs(current) * dt / 3600.0

        charger = Charger(
            present=True,
            output_voltage=round(pack_v + 1.2, 1) if on_charge else 0.0,
            output_current=round(abs(current), 2) if on_charge else 0.0,
            requested_voltage=CHARGER_MAX_V,
            requested_current=CHARGER_MAX_A,
            enabled=on_charge and self.soc < 99.5,
            temperature=round(38.0 + 6.0 * math.sin(t / 90.0), 1),
            faults=[],
            energy_wh=round(self.chg_energy_wh, 1),
            session_s=round(now - self.chg_started, 0) if self.chg_started else 0.0,
        )

        return BmsState(
            ts=now,
            stale=False,
            pack=pack,
            mode="charger" if on_charge else "car",
            charging=on_charge and current < -0.5,
            charger=charger,
            safety=Safety(
                # A mesma lista que o descodificador real declara: o demo nao
                # deve mostrar luzes que o hardware nao tem.
                available=["ams_ok", "sdc_closed", "air_positive",
                           "air_negative", "precharge_done"],
                ams_ok=pack.status is not Severity.FAULT,
                sdc_closed=pack.status is not Severity.FAULT,
                air_positive=True,
                air_negative=True,
                precharge_done=True,
                master_state="CHARGING" if on_charge else "ONMISSION",
                precharge_state="HV_ON",
            ),
            segments=segments,
            cells=cells,
            thermistors=thermistors,
            faults=list(self._faults),
            n_segments=seg_count,
            cells_per_segment=per_seg,
            parallel_strings=car.parallel_strings,
            cell_model=car.cell_model,
            topology=car.topology,
            slave_count=car.slave_count,
            cells_per_slave=car.cells_per_slave,
            temps_per_slave=car.temps_per_slave,
        )

    def _update_faults(self, pack: Pack) -> None:
        """Latch faults so they stay on screen after the condition clears."""
        limits = self.car.limits
        active: list[tuple[str, Severity, str]] = []
        if pack.temp_max >= limits.temp_fault:
            active.append(("TEMP_HIGH", Severity.FAULT, f"Temperatura {pack.temp_max:.1f} C acima do limite"))
        elif pack.temp_max >= limits.temp_warn:
            active.append(("TEMP_WARN", Severity.WARN, f"Temperatura {pack.temp_max:.1f} C elevada"))
        if pack.cell_v_delta >= 0.10:
            active.append(("V_IMBALANCE", Severity.WARN, f"Desequilibrio {pack.cell_v_delta * 1000:.0f} mV"))
        if pack.cell_v_min <= limits.v_min:
            active.append(("UV", Severity.FAULT, f"Celula em subtensao ({pack.cell_v_min:.3f} V)"))
        if pack.soc <= 10.0:
            active.append(("SOC_LOW", Severity.WARN, f"SOC {pack.soc:.0f}%"))

        known = {f.code for f in self._faults}
        for code, sev, msg in active:
            if code not in known:
                self._faults.insert(0, Fault(code=code, severity=sev, message=msg, latched=True))
        del self._faults[12:]
