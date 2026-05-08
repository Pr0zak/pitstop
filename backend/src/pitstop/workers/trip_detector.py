"""Trip detector worker — placeholder, real implementation in Task #4.

This stub is overwritten when Task #4 lands.
"""

from __future__ import annotations

import asyncio
import logging

import asyncpg

from ..config import Settings
from .bus import EventBus

log = logging.getLogger(__name__)


class TripDetector:
    def __init__(
        self,
        *,
        pool: asyncpg.Pool,
        bus_: EventBus,
        config: Settings,
    ) -> None:
        self._pool = pool
        self._bus = bus_
        self._cfg = config
        self._stop = asyncio.Event()

    async def run(self) -> None:
        log.info("trip detector stub running")
        await self._stop.wait()

    def stop(self) -> None:
        self._stop.set()
