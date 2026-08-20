"""Decoders: raw frames in, UniversalBmsState out.

One decoder per CAN database dialect, chosen by `CarProfile.decoder`. The rest
of the app only ever sees BmsState, so a second car with a different DBC needs a
new decoder here and nothing else.
"""

from __future__ import annotations

from ..cars import CarProfile
from .t26 import T26Decoder

DECODERS = {
    "t26": T26Decoder,
}


def for_car(car: CarProfile, db):
    """Decoder instance for this car, or None if its dialect has none yet."""
    cls = DECODERS.get(car.decoder)
    if cls is None:
        return None
    return cls(car, db)
