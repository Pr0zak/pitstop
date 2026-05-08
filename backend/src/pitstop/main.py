from __future__ import annotations

import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager

import asyncpg
from fastapi import FastAPI

from .api import health
from .config import settings
from .workers.bus import bus
from .workers.ingest import MqttIngest
from .workers.trip_detector import TripDetector

logging.basicConfig(level=getattr(logging, settings.log_level.upper(), logging.INFO))
log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("pitstop backend starting")
    pool: asyncpg.Pool | None = None
    ingest_task: asyncio.Task | None = None
    trip_task: asyncio.Task | None = None
    ingest: MqttIngest | None = None
    trip_detector: TripDetector | None = None
    try:
        pool = await asyncpg.create_pool(
            dsn=settings.asyncpg_dsn, min_size=1, max_size=10
        )
        app.state.pg_pool = pool
        app.state.bus = bus
        ingest = MqttIngest(pool=pool, bus_=bus, config=settings)
        trip_detector = TripDetector(pool=pool, bus_=bus, config=settings)
        ingest_task = asyncio.create_task(ingest.run(), name="mqtt-ingest")
        trip_task = asyncio.create_task(trip_detector.run(), name="trip-detector")
        yield
    finally:
        log.info("pitstop backend stopping")
        if ingest is not None:
            ingest.stop()
        if trip_detector is not None:
            trip_detector.stop()
        for task in (ingest_task, trip_task):
            if task is None:
                continue
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        if pool is not None:
            await pool.close()


app = FastAPI(title="pitstop", lifespan=lifespan)
app.include_router(health.router)
