"""Per-car profiles.

The car is picked before anything else because it decides where the CAN DBC
comes from, how the pack is laid out, and which 3D models to load. Everything
downstream (decoders, simulator, thresholds, viewer) reads from the active
profile instead of hardcoding values.

NOTE: the repo URLs, pack topologies and limits below are placeholders and must
be filled in with the real values per car. Only the shape is settled.
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

    model_closed: str = ""
    model_open: str = ""      # tampa aberta, interiores a vista
    view_closed: CameraView = Field(default_factory=CameraView)
    view_open: CameraView = Field(default_factory=CameraView)
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


CARS: list[CarProfile] = [
    CarProfile(
        id="tek26e",
        name="TEK-26e",
        subtitle="Elétrico",
        year="2026",
        image="/pics/t26.png",
        dbc=DbcSource(
            # Repositorio publico da equipa - o mesmo que a bridge do Foxglove
            # usa. E o unico que responde sem credenciais, o que interessa
            # porque a atualizacao e feita sem pedir login a ninguem.
            repo="https://github.com/FSLART/T26_DBC_Aquisition_Boards.git",
            ref="main",
            # Ordem = prioridade. A powertrain define o AMS e o IVT; a handcart
            # traz o carregador e o handcart, e repete os IDs do IVT com menos
            # detalhe - por isso vem depois e esses IDs nao a apanham.
            files=["powertrain_t26.dbc", "handcart_t26.dbc"],
            local_dir="C:/Users/jpser/Documents/GitHub/T26_DBC",
        ),
        # NTC 3 e 4 do slave 3 avariados: leem lixo, nao o vizinho.
        broken_thermistors=["3:3", "3:4"],
        # 144s3p: 6 segmentos x 24 grupos serie x 3 celulas = 432 Molicel P45B
        # 12 slaves AMS, 2 por segmento, 12 grupos cada (DBC: Slave_01..Slave_12)
        n_segments=6,
        cells_per_segment=24,
        parallel_strings=3,
        cell_model="Molicel P45B",
        nominal_cell_v=3.60,
        cell_capacity_ah=4.5,
        slaves_per_segment=2,
        cells_per_slave=12,
        temps_per_slave=6,
        limits=CellLimits(v_min=2.50, v_max=4.20, v_warn_low=2.80, v_warn_high=4.15),
        model_closed="/models/tek26e_closed.glb",
        # Export do SolidWorks passado por weld + quantize: 223 MB -> 26 MB com
        # os 5,8M triangulos e a bounding box intactos, por isso as ancoras dos
        # hotspots aqui em baixo continuam validas. Ver models/README.md.
        model_open="/models/tek26e_open_light.glb",
        # De frente, quase ao nivel: mostra o lado das ventoinhas e conectores.
        view_closed=CameraView(orbit="180deg 80deg 60%", fov="30deg"),
        # De topo: os segmentos e a placa master leem-se como planta.
        view_open=CameraView(orbit="0deg 18deg 66%", fov="32deg"),
        hotspots=[
            # Conector RTS718N32S03 na tampa. Centro do node no GLB.
            Hotspot(
                id="rts",
                view="closed",
                position="-0.128m 0.327m 1.755m",
                normal="0m 1m 0m",
                label="Offline",
                binds="link",
                # O painel de ligacao ocupa o centro do palco: o rotulo vai
                # para o canto oposto ao do modo demo, ligado por uma linha.
                standoff="top-right",
            ),
            # --- modelo aberto ---------------------------------------------
            # Posicoes calculadas dos limites dos nodes do GLB, nao clicadas
            # num editor (scratchpad/open_anchors.py). Os segmentos vem das 6
            # placas BMS_Slave_2.0, uma por segmento, igualmente espacadas ao
            # longo do eixo x.
            Hotspot(id="ams", view="open", label="AMS",
                    position="-0.187m 0.276m 1.770m", binds="ams"),
            Hotspot(id="imd", view="open", label="IMD",
                    position="-0.054m 0.261m 1.774m", binds="imd"),
            Hotspot(id="fuse", view="open", label="Fusivel",
                    position="0.051m 0.270m 1.765m", binds="fuse"),
            # AIR+, AIR- e pre-carga partilham o node Pre_Charge_AIR.step e nao
            # se distinguem por nome. Estas posicoes foram apanhadas com o modo
            # ancora da consola, clicando em cada contactor.
            Hotspot(id="air-pos", view="open", label="AIR+",
                    position="0.100m 0.274m 1.797m", normal="0m 1m 0m",
                    binds="air_positive"),
            Hotspot(id="air-neg", view="open", label="AIR-",
                    position="0.170m 0.280m 1.789m", normal="0m 1m 0m",
                    binds="air_negative"),
            Hotspot(id="precharge", view="open", label="Pre-carga",
                    position="0.131m 0.241m 1.745m", normal="0.697m 0m -0.717m",
                    binds="precharge_done"),
            Hotspot(id="ivt", view="open", label="IVT",
                    position="0.210m 0.310m 1.768m", normal="0m 1m 0m", binds="ivt"),
            Hotspot(id="fans", view="open", label="Ventoinhas",
                    position="-0.213m 0.152m 1.907m", normal="0m 1m 0m", binds="fans"),
            Hotspot(id="seg-1", view="open", label="S1",
                    position="-0.212m 0.219m 1.650m", binds="segment:1"),
            Hotspot(id="seg-2", view="open", label="S2",
                    position="-0.127m 0.219m 1.650m", binds="segment:2"),
            Hotspot(id="seg-3", view="open", label="S3",
                    position="-0.042m 0.219m 1.650m", binds="segment:3"),
            Hotspot(id="seg-4", view="open", label="S4",
                    position="0.042m 0.219m 1.650m", binds="segment:4"),
            Hotspot(id="seg-5", view="open", label="S5",
                    position="0.127m 0.219m 1.650m", binds="segment:5"),
            Hotspot(id="seg-6", view="open", label="S6",
                    position="0.212m 0.219m 1.650m", binds="segment:6"),
        ],
    ),
    CarProfile(
        id="tek26e_evo",
        name="TEK-26e EVO",
        subtitle="Elétrico · evolução",
        year="2026",
        image="/pics/mystery_car.png",
        image_is_silhouette=True,
        available=False,
        dbc=DbcSource(repo="", ref="main", files=["dbc/tek26e_evo_bms.dbc"]),
        # PLACEHOLDER: topologia por confirmar
        n_segments=6,
        cells_per_segment=24,
        parallel_strings=3,
        cell_model="Molicel P45B",
        nominal_cell_v=3.60,
        cell_capacity_ah=4.5,
        model_closed="/models/tek26e_evo_closed.glb",
        model_open="/models/tek26e_evo_open.glb",
    ),
    CarProfile(
        id="t28",
        name="T-28",
        subtitle="Nova plataforma",
        year="2028",
        image="/pics/mystery_car.png",
        image_is_silhouette=True,
        available=False,
        dbc=DbcSource(repo="", ref="main", files=["dbc/t28_bms.dbc"]),
        # PLACEHOLDER: topologia por confirmar
        n_segments=8,
        cells_per_segment=18,
        parallel_strings=3,
        nominal_cell_v=3.60,
        cell_capacity_ah=4.5,
        model_closed="/models/t28_closed.glb",
        model_open="/models/t28_open.glb",
    ),
]

CARS_BY_ID = {c.id: c for c in CARS}


def get_car(car_id: str) -> CarProfile | None:
    return CARS_BY_ID.get(car_id)


# Fail loudly at import if a profile contradicts itself.
_problems = [msg for car in CARS for msg in car.check()]
if _problems:
    raise ValueError("Topologia inconsistente:\n  " + "\n  ".join(_problems))
