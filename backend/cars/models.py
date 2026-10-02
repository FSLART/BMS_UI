"""Formas que um perfil de carro pode ter.

So estruturas: nenhum valor de nenhum carro vive aqui. Os carros estao um por
ficheiro nesta pasta, e o que todos partilham esta em `common.py`.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class CellLimits(BaseModel):
    """Chemistry-dependent thresholds. One source of truth per car.

    Voltages are per series group, which is what the BMS actually measures:
    cells in parallel share a node and read as one voltage.
    """

    v_min: float = 2.80
    v_max: float = 4.20
    v_warn_low: float = 3.00
    v_warn_high: float = 4.15
    temp_warn: float = 50.0
    temp_fault: float = 60.0
    # Abaixo disto o firmware nao le uma celula fraca, le um fio de medicao
    # solto (FSLART/lart_bms, beta-v2.1). Sao problemas diferentes e mandam
    # procurar em sitios diferentes, por isso nao se mostram como o mesmo.
    v_open_wire: float = 2.30


class CameraView(BaseModel):
    """Starting camera for one model, and what "reset view" returns to.

    Lives here rather than in the viewer because it is a property of the car's
    geometry: where the connectors face, how tall the case is, which side is
    worth showing. Leave `orbit` empty to let model-viewer frame the model
    itself.

    `orbit` is model-viewer's "azimuth polar radius", polar measured from +Y
    (small = looking down). Radius takes % of the framed distance or metres.
    """

    orbit: str = ""
    target: str = "auto auto auto"
    fov: str = ""
    # Roda o MODELO, nao a camara: o <model-viewer> nao tem roll, portanto
    # nenhuma orbita poe de pe uma peca exportada deitada. "roll pitch yaw".
    orientation: str = ""


class Hotspot(BaseModel):
    """A marker anchored to a point on one of the car's 3D models.

    `position` and `normal` are model-space, in metres, exactly as
    <model-viewer> wants them. Computed from the GLB node bounds rather than
    clicked in an editor, so they survive a re-export as long as the part keeps
    its name (see scratchpad/anchors.py).

    `binds` says what drives the label:
      link        -> connection state
      segment:<n> -> that segment's status and readings

    `standoff` parks the label in a fixed corner of the viewport instead of
    beside the anchor, joined to it by a leader line. Use it when something
    else covers the middle of the stage -- the connection panel sits over the
    centre of the closed model, and a label riding next to the RTS connector
    disappears underneath it at most camera angles.
    """

    id: str
    view: str = "closed"          # closed | open
    position: str
    normal: str = "0m 1m 0m"
    label: str = ""
    binds: str = ""
    # "" | top-left | top-right | bottom-left | bottom-right
    standoff: str = ""


class DbcSource(BaseModel):
    """Where to fetch the CAN databases for this car.

    Several files can describe the same bus. The TEK-26e needs two: the
    powertrain database for the AMS itself, and the handcart database for the
    charger — while the accumulator is on charge, the charger and its handcart
    publish messages that explain what the negative current on the ISA IVT
    actually means.

    `files` is in priority order. Where two databases define the same frame id
    the FIRST one wins, so the primary database goes first and the others only
    fill in what it does not cover.
    """

    repo: str = ""            # git remote, e.g. git@github.com:org/car-can.git
    ref: str = "main"
    files: list[str] = []     # paths inside the repo, merged first-wins
    local_dir: str = ""       # optional checkout on this machine, wins over repo


class CanBus(BaseModel):
    """Um dos barramentos CAN do carro.

    O firmware (FSLART/lart_bms, beta-v2.1) separa-os por situacao, nao por
    tipo de dado: o da powertrain e o que existe com a bateria montada no
    carro, o de carregamento so vive enquanto o acumulador esta no handcart.
    Sao fisicamente distintos e correm a velocidades diferentes, por isso
    escolher mal nao da dados errados -- nao da dados nenhuns.
    """

    id: str
    name: str
    bitrate: int
    detail: str = ""


class CanCommand(BaseModel):
    """Uma trama que a interface pode enviar para o BMS.

    Declarada aqui, e nao no frontend, porque e especifica da DBC deste carro.
    Cada uma e um unico bit ligado/desligado.

    `danger=True` marca as que mexem em alta tensao. Nao muda o que e enviado --
    muda o que e preciso fazer para o enviar.
    """

    id: str
    label: str
    message: str              # nome da mensagem na DBC
    signal: str
    on: int = 1
    off: int = 0
    danger: bool = False
    detail: str = ""


class CarProfile(BaseModel):
    id: str
    name: str
    subtitle: str = ""
    image: str = ""           # served from frontend/pics/
    # True for the "?" silhouette stand-in: it is an opaque black shape on
    # transparent, so the UI inverts it instead of losing it against the
    # dark background.
    image_is_silhouette: bool = False
    year: str = ""
    # False = shown on the selection screen but not selectable. Enforced
    # here, not just in the UI, so nothing downstream can pick it.
    available: bool = True

    dbc: DbcSource = Field(default_factory=DbcSource)
    buses: list[CanBus] = Field(default_factory=list)
    commands: list[CanCommand] = Field(default_factory=list)
    limits: CellLimits = Field(default_factory=CellLimits)

    # --- pack topology -----------------------------------------------------
    # `cells_per_segment` counts SERIES GROUPS, not physical cells: parallel
    # cells share a node and the BMS reads them as a single voltage. So a
    # 144s3p pack is 6 segments x 24 groups x 3 cells = 432 cells, but only
    # 144 measured positions.
    n_segments: int = 6
    cells_per_segment: int = 24
    parallel_strings: int = 1
    cell_model: str = ""
    nominal_cell_v: float = 3.60
    cell_capacity_ah: float = 4.5

    # AMS slave boards. Each reads a fixed number of series groups, so the
    # decoder maps slave+channel -> segment+position with plain arithmetic.
    slaves_per_segment: int = 0
    cells_per_slave: int = 0
    temps_per_slave: int = 0

    # NTCs known to be dead in the hardware, as "slave:index". Reported with no
    # reading instead of the neighbouring channel's value, so nobody acts on a
    # temperature the sensor never measured.
    broken_thermistors: list[str] = Field(default_factory=list)

    # Which decoder understands this car's CAN dialect (backend/decode/).
    decoder: str = "t26"

    # MAC do RN4871 do AMS. Hardware fixo: sem outro dispositivo escolhido, a
    # ligacao BLE vai para aqui e insiste ate o encontrar.
    ble_address: str = ""

    model_closed: str = ""
    model_open: str = ""      # tampa aberta, interiores a vista
    # Acumulador montado no handcart. Ainda nao existe: enquanto o ficheiro nao
    # estiver la, o viewer mostra o placeholder dele e a pagina de carregamento
    # funciona na mesma. As ancoras ja estao declaradas, a espera do GLB.
    model_charger: str = ""
    # Um segmento sozinho. Generico: os 6 sao identicos, so muda a informacao
    # que se pendura nas ancoras.
    model_segment: str = ""
    view_closed: CameraView = Field(default_factory=CameraView)
    view_open: CameraView = Field(default_factory=CameraView)
    view_charger: CameraView = Field(default_factory=CameraView)
    view_segment: CameraView = Field(default_factory=CameraView)
    hotspots: list[Hotspot] = Field(default_factory=list)

    @property
    def series_count(self) -> int:
        return self.n_segments * self.cells_per_segment

    @property
    def total_cells(self) -> int:
        return self.series_count * self.parallel_strings

    @property
    def pack_capacity_ah(self) -> float:
        """Parallel strings add capacity; series adds voltage."""
        return self.cell_capacity_ah * self.parallel_strings

    @property
    def nominal_pack_v(self) -> float:
        return self.series_count * self.nominal_cell_v

    @property
    def topology(self) -> str:
        return f"{self.series_count}s{self.parallel_strings}p"

    @property
    def slave_count(self) -> int:
        return self.n_segments * self.slaves_per_segment

    def check(self) -> list[str]:
        """Topology contradictions worth catching at import, not at runtime."""
        problems = []
        if self.slaves_per_segment and self.cells_per_slave:
            covered = self.slaves_per_segment * self.cells_per_slave
            if covered != self.cells_per_segment:
                problems.append(
                    f"{self.id}: {self.slaves_per_segment} slaves x "
                    f"{self.cells_per_slave} celulas = {covered}, "
                    f"mas cells_per_segment = {self.cells_per_segment}"
                )
        return problems

    # Transports that make sense for this car, in the order they should be
    # offered. Empty = offer all of them.
    transports: list[str] = Field(default_factory=list)
