"""T-28 — ainda por definir.

Topologia e limites sao PLACEHOLDER, como o EVO.
"""

from __future__ import annotations

from .common import BUSES, COMMANDS, DECODER
from .models import CameraView, CanBus, CanCommand, CarProfile, CellLimits, DbcSource, Hotspot

CAR = CarProfile(
    id="t28",
    name="T-28",
    subtitle="Nova plataforma",
    year="2028",
    image="/pics/mystery_car.png",
    image_is_silhouette=True,
    available=False,
    # Eletronica igual a do TEK-26e: mesmos barramentos, mesmos comandos, mesmo
    # descodificador. So a DBC e os modelos 3D e que sao deste carro.
    buses=BUSES,
    commands=COMMANDS,
    decoder=DECODER,
    dbc=DbcSource(repo="", ref="main", files=["dbc/t28_bms.dbc"]),
    # PLACEHOLDER: topologia por confirmar
    n_segments=8,
    cells_per_segment=18,
    parallel_strings=3,
    nominal_cell_v=3.60,
    cell_capacity_ah=4.5,
    model_closed="/models/t28_closed.glb",
    model_open="/models/t28_open.glb",
)
