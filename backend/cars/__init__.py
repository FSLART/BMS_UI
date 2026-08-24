"""Perfis de carro.

Um carro decide de onde vem a DBC, como o pack esta feito, e que modelos 3D
carregar. Tudo o que esta a jusante -- descodificadores, simulador, limites,
viewer -- le do perfil ativo em vez de ter valores escritos por dentro.

    models.py    as formas. Nenhum valor de nenhum carro.
    common.py    o que os tres partilham: barramentos, comandos, descodificador.
    tek26e.py    um ficheiro por carro.
    tek26e_evo.py
    t28.py

Para acrescentar um carro: copiar o ficheiro de um existente, trocar a DBC e os
modelos 3D, e juntar aqui a CARS. O frontend nao muda -- e o mesmo para todos.

A validacao no fim corre no import: uma topologia que se contradiz rebenta ao
arrancar, e nao a meio de uma sessao com o carro ligado.
"""

from __future__ import annotations

from .models import (
    CameraView,
    CanBus,
    CanCommand,
    CarProfile,
    CellLimits,
    DbcSource,
    Hotspot,
)
from .t28 import CAR as T28
from .tek26e import CAR as TEK26E
from .tek26e_evo import CAR as TEK26E_EVO

__all__ = [
    "CameraView", "CanBus", "CanCommand", "CarProfile", "CellLimits",
    "DbcSource", "Hotspot", "CARS", "CARS_BY_ID", "get_car",
]

# Ordem de apresentacao no ecra de escolha.
CARS: list[CarProfile] = [TEK26E, TEK26E_EVO, T28]

CARS_BY_ID = {c.id: c for c in CARS}


def get_car(car_id: str) -> CarProfile | None:
    return CARS_BY_ID.get(car_id)


_problems = [msg for car in CARS for msg in car.check()]
if _problems:
    raise ValueError("Topologia inconsistente:\n  " + "\n  ".join(_problems))
