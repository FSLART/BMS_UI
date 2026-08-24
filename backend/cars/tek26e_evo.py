"""TEK-26e EVO — ainda por definir.

Topologia e limites sao PLACEHOLDER. `available=False` mantem-no fora da
escolha ate os valores reais existirem: melhor nao aparecer do que
aparecer errado.
"""

from __future__ import annotations

from .common import BUSES, COMMANDS, DECODER
from .models import CameraView, CanBus, CanCommand, CarProfile, CellLimits, DbcSource, Hotspot

CAR = CarProfile(
    id="tek26e_evo",
    name="TEK-26e EVO",
    subtitle="Elétrico · evolução",
    year="2026",
    image="/pics/mystery_car.png",
    image_is_silhouette=True,
    available=False,
    # Eletronica igual a do TEK-26e: mesmos barramentos, mesmos comandos, mesmo
    # descodificador. So a DBC e os modelos 3D e que sao deste carro.
    buses=BUSES,
    commands=COMMANDS,
    decoder=DECODER,
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
)
