"""Transport abstraction.

A transport does exactly one job: turn a physical link into an async stream of
RawFrame. It knows nothing about BMS semantics -- that is the decoder's job.
"""

from __future__ import annotations

import abc
import time
from dataclasses import dataclass, field
from typing import Any, AsyncIterator

from ..state import LinkType


@dataclass(slots=True)
class RawFrame:
    ts: float
    source: LinkType
    id: int | str | None
    payload: bytes
    extra: dict[str, Any] = field(default_factory=dict)


class TransportError(RuntimeError):
    pass


class Transport(abc.ABC):
    """Base class for every link type."""

    link_type: LinkType = LinkType.NONE

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self._opened_at: float | None = None

    @property
    def describe(self) -> str:
        return f"{self.link_type.value}"

    @abc.abstractmethod
    async def open(self) -> None:
        """Bring the link up. Raise TransportError on failure."""

    @abc.abstractmethod
    async def close(self) -> None:
        """Tear the link down. Must be safe to call twice."""

    @abc.abstractmethod
    def frames(self) -> AsyncIterator[RawFrame]:
        """Yield frames until the link is closed."""

    def _stamp(self, frame_id: int | str | None, payload: bytes, **extra: Any) -> RawFrame:
        return RawFrame(
            ts=time.time(),
            source=self.link_type,
            id=frame_id,
            payload=payload,
            extra=extra,
        )
