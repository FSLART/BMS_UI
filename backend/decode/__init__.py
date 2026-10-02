"""Decoders: raw frames in, UniversalBmsState out.

One decoder per CAN database dialect, chosen by `CarProfile.decoder`. The rest
of the app only ever sees BmsState, so a second car with a different DBC needs a
new decoder here and nothing else.
"""

from __future__ import annotations

from ..cars import CarProfile
from .live_json import LiveJsonDecoder
from .live_struct import LiveStructDecoder
from .t26 import T26Decoder

DECODERS = {
    "t26": T26Decoder,
}

# Descodificadores de linha, para os transportes que entregam texto em vez de
# tramas: hoje o BLE, que traz o dump `live_debug` do firmware. A chave e a
# mesma do CAN porque e o mesmo firmware a falar -- muda o meio, nao o dialeto.
LINE_DECODERS = {
    "t26": LiveJsonDecoder,
}

# Descodificadores de memoria, para quem le a struct `live_debug` diretamente
# da RAM do STM32 em vez de receber o dump ja feito: hoje o WiFi, pelo servidor
# GDB do Black Magic. Mesmo firmware outra vez, mesma chave.
STRUCT_DECODERS = {
    "t26": LiveStructDecoder,
}


def for_car(car: CarProfile, db):
    """Decoder instance for this car, or None if its dialect has none yet."""
    cls = DECODERS.get(car.decoder)
    if cls is None:
        return None
    return cls(car, db)


def for_lines(car: CarProfile):
    """Line decoder for this car, or None. No CAN database involved."""
    cls = LINE_DECODERS.get(car.decoder)
    if cls is None:
        return None
    return cls(car)


def for_struct(car: CarProfile):
    """Descodificador de fotografias da struct `live_debug`, ou None."""
    cls = STRUCT_DECODERS.get(car.decoder)
    if cls is None:
        return None
    return cls(car)
