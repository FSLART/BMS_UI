"""Universal BMS state model.

Every transport (CAN / WiFi / BLE / UART) decodes into these structures, so the
UI never knows or cares where the numbers came from. SI units everywhere:
volts, amperes, degrees celsius, ohms, seconds.
"""

from __future__ import annotations

import time
from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class Severity(str, Enum):
    OK = "ok"
    WARN = "warn"
    FAULT = "fault"
    UNKNOWN = "unknown"


class LinkType(str, Enum):
    NONE = "none"
    CAN = "can"
    WIFI = "wifi"
    BLE = "ble"
    SERIAL = "serial"
    SIM = "sim"


class LinkStatus(str, Enum):
    DISCONNECTED = "disconnected"
    CONNECTING = "connecting"
    HANDSHAKING = "handshaking"   # link is up, waiting for a valid BMS frame
    LIVE = "live"                 # BMS confirmed alive
    ERROR = "error"


class Cell(BaseModel):
    """One SERIES GROUP, i.e. one measured position.

    In a 144s3p pack the three parallel cells at a position share a node, so the
    BMS reports one voltage for the group. `parallel` records how many physical
    cells sit behind that single reading.
    """

    id: str                       # matches the node name in the CAD/GLB tree
    segment: int
    index: int                    # 1..cells_per_segment, within the segment
    slave: int = 0                # 1..slave_count, which AMS board reads it
    slave_channel: int = 0        # 1..cells_per_slave, channel on that board
    parallel: int = 1
    voltage: float = 0.0
    temperature: float | None = None
    balancing: bool = False
    # Reading below the open-wire threshold: the sense lead is loose, not the
    # cell flat. Kept apart from status so the UI can say which.
    open_wire: bool = False
    status: Severity = Severity.UNKNOWN


class Thermistor(BaseModel):
    """One temperature sensor.

    Sensors do not map one-to-one onto cell groups: the AMS reads 6 per slave
    (72 in the pack) against 144 groups, so temperature is reported per sensor
    and never invented per group.
    """

    slave: int                    # 1..slave_count
    index: int                    # 1..temps_per_slave
    segment: int = 0
    temperature: float | None = None
    status: Severity = Severity.UNKNOWN


class Segment(BaseModel):
    id: int
    name: str
    voltage: float = 0.0
    temp_max: float = 0.0
    temp_avg: float = 0.0
    cell_v_min: float = 0.0
    cell_v_max: float = 0.0
    balancing_active: bool = False
    status: Severity = Severity.UNKNOWN
    # hotspot anchor in the GLB, filled from the model-viewer editor
    hotspot: str | None = None


class Pack(BaseModel):
    voltage: float = 0.0
    current: float = 0.0          # positive = discharge
    power: float = 0.0
    soc: float = 0.0              # 0..100 %
    soh: float = 100.0
    energy_used: float = 0.0      # Wh
    cell_v_min: float = 0.0
    cell_v_max: float = 0.0
    cell_v_delta: float = 0.0
    temp_max: float = 0.0
    temp_min: float = 0.0
    status: Severity = Severity.UNKNOWN


class Safety(BaseModel):
    """Formula Student shutdown-circuit relevant signals.

    The fields exist for any car; `available` says which of them THIS car's
    decoder actually measures. Everything not listed is never shown, rather
    than shown as a green light standing for a signal nobody is reading -- an
    IMD lamp fed from the SDC says "insulation fine" when all it knows is that
    the loop is closed.
    """

    available: list[str] = Field(default_factory=list)

    ams_ok: bool = False
    imd_ok: bool = False
    bspd_ok: bool = False
    sdc_closed: bool = False
    air_positive: bool = False
    air_negative: bool = False
    precharge_done: bool = False
    insulation_resistance: float | None = None   # ohms

    # Nomes das maquinas de estado do AMS, das tabelas VAL_ da DBC.
    master_state: str = ""
    precharge_state: str = ""


class Ams(BaseModel):
    """Saúde da própria placa master, não do pack.

    Vem quase toda de `Master_MSC_ID_1`, a mesma mensagem que traz o
    `master_state`. São valores sobre o computador que faz a medição, e não
    sobre as células — daí ficarem à parte do `Pack`.
    """

    fan_pwm: float | None = None        # 0..100 %, comando das ventoinhas
    mcu_temperature: float | None = None
    firmware: int | None = None
    pec_error: bool = False             # erro de CRC no barramento dos ADBMS
    fault_counter: int = 0
    runtime_s: float | None = None
    slaves_detected: int | None = None


class Charger(BaseModel):
    """Charging session, when the pack is on the handcart.

    Separate from Pack because a negative current on the shunt on its own does
    not say whether the pack is charging or regenerating — the charger's own
    messages are what distinguish the two.
    """

    present: bool = False           # charger seen on the bus recently
    output_voltage: float = 0.0
    output_current: float = 0.0
    requested_voltage: float = 0.0  # what the BMS is asking the charger for
    requested_current: float = 0.0
    enabled: bool = False           # BMS is commanding the charger to deliver
    temperature: float | None = None
    faults: list[str] = Field(default_factory=list)

    # Energy put into the pack since this session started, and how long it has
    # been running. Session-relative on purpose: the IVT's own Wh register is a
    # lifetime counter, and "how much went in tonight" is the question anyone
    # standing next to the handcart is actually asking.
    energy_wh: float = 0.0
    session_s: float = 0.0


class Fault(BaseModel):
    code: str
    severity: Severity
    message: str
    ts: float = Field(default_factory=time.time)
    latched: bool = False


class LinkMeta(BaseModel):
    type: LinkType = LinkType.NONE
    status: LinkStatus = LinkStatus.DISCONNECTED
    detail: str = ""
    # Numbers are coming from the simulator, not from hardware. Explicit rather
    # than inferred from `detail`: the UI has to be able to refuse to send
    # commands, and guessing from a display string is not a basis for that.
    demo: bool = False
    rx_rate: float = 0.0          # frames/s
    latency_ms: float | None = None
    last_frame_ts: float | None = None
    error: str | None = None


class CameraView(BaseModel):
    orbit: str = ""
    target: str = "auto auto auto"
    fov: str = ""
    # Roda o MODELO, nao a camara: o <model-viewer> nao tem roll, portanto
    # nenhuma orbita poe de pe uma peca exportada deitada. "roll pitch yaw".
    orientation: str = ""


class Hotspot(BaseModel):
    id: str
    view: str = "closed"          # closed | open | charger
    position: str = ""
    normal: str = "0m 1m 0m"
    label: str = ""
    binds: str = ""
    # Mirrors cars.Hotspot. Missing here, pydantic dropped it silently on the
    # way into CarMeta -- harmless today because the frontend reads the raw
    # profile from /api/cars, but a trap for whoever uses CarMeta next.
    standoff: str = ""


class CanBus(BaseModel):
    id: str = ""
    name: str = ""
    bitrate: int = 0
    detail: str = ""


class CanCommand(BaseModel):
    id: str = ""
    label: str = ""
    danger: bool = False
    detail: str = ""


class CarMeta(BaseModel):
    id: str = ""
    name: str = ""
    subtitle: str = ""
    buses: list[CanBus] = Field(default_factory=list)
    commands: list[CanCommand] = Field(default_factory=list)
    model_closed: str = ""
    model_open: str = ""
    model_charger: str = ""
    model_segment: str = ""
    view_closed: CameraView = Field(default_factory=CameraView)
    view_open: CameraView = Field(default_factory=CameraView)
    view_charger: CameraView = Field(default_factory=CameraView)
    view_segment: CameraView = Field(default_factory=CameraView)
    hotspots: list[Hotspot] = Field(default_factory=list)


class BmsState(BaseModel):
    ts: float = Field(default_factory=time.time)
    stale: bool = True
    car: CarMeta = Field(default_factory=CarMeta)
    link: LinkMeta = Field(default_factory=LinkMeta)
    pack: Pack = Field(default_factory=Pack)
    safety: Safety = Field(default_factory=Safety)
    ams: Ams = Field(default_factory=Ams)
    # Which bus we turned out to be on, worked out from the traffic rather than
    # asked: charger frames present means the pack is on the handcart, their
    # absence means it is in the car. Never a setting.
    mode: Literal["car", "charger"] = "car"
    charging: bool = False
    charger: Charger = Field(default_factory=Charger)
    segments: list[Segment] = Field(default_factory=list)
    cells: list[Cell] = Field(default_factory=list)
    thermistors: list[Thermistor] = Field(default_factory=list)
    faults: list[Fault] = Field(default_factory=list)

    # ---- pack topology, declared once so the UI can lay out before data ----
    n_segments: int = 0
    cells_per_segment: int = 0      # series groups per segment, not cells
    parallel_strings: int = 1
    cell_model: str = ""
    topology: str = ""              # e.g. "144s3p"
    slave_count: int = 0
    cells_per_slave: int = 0
    temps_per_slave: int = 0


# How long without a frame before the UI greys everything out instead of
# showing numbers that are quietly lying.
STALE_AFTER_S = 2.0


# ---------------------------------------------------------------------------
# Thresholds. Single place where "is this value bad" is decided, so the 3D
# hotspots, the cell grid and the fault list can never disagree. The actual
# numbers come from the active car profile (backend/cars/ -> CellLimits).
# ---------------------------------------------------------------------------

def classify_cell(voltage: float, temperature: float | None, limits) -> Severity:
    if voltage <= limits.v_min or voltage >= limits.v_max:
        return Severity.FAULT
    if temperature is not None and temperature >= limits.temp_fault:
        return Severity.FAULT
    if voltage <= limits.v_warn_low or voltage >= limits.v_warn_high:
        return Severity.WARN
    if temperature is not None and temperature >= limits.temp_warn:
        return Severity.WARN
    return Severity.OK


def worst(severities: list[Severity]) -> Severity:
    order: list[Severity] = [Severity.FAULT, Severity.WARN, Severity.OK, Severity.UNKNOWN]
    for level in order:
        if level in severities:
            return level
    return Severity.UNKNOWN
