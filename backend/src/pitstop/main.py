from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from contextlib import asynccontextmanager
from pathlib import Path

import asyncpg
from fastapi import FastAPI

from .api import (
    dtcs,
    health,
    imports,
    live_ws,
    profiles,
    readings,
)
from .api import settings as settings_api
from .api import trips as trips_api
from .api import vehicles as vehicles_api
from .config import settings
from .workers.bus import bus
from .workers.ingest import MqttIngest
from .workers.trip_detector import TripDetector

logging.basicConfig(level=getattr(logging, settings.log_level.upper(), logging.INFO))
log = logging.getLogger(__name__)


async def _seed_pid_profiles(pool: asyncpg.Pool) -> None:
    """Insert any ``pid_profiles/*.json`` files missing in the DB.

    DB row is authoritative once seeded — UI edits aren't overwritten on
    restart (ADR-008).
    """
    profile_dir = Path(settings.pid_profiles_dir)
    if not profile_dir.is_dir():
        log.info("pid_profiles dir %s missing; skipping seed", profile_dir)
        return
    for path in sorted(profile_dir.glob("*.json")):
        try:
            data = json.loads(path.read_text())
        except (json.JSONDecodeError, OSError) as exc:
            log.warning("skipping malformed profile %s: %s", path, exc)
            continue
        name = data.get("name") or path.stem
        description = data.get("description")
        try:
            async with pool.acquire() as conn:
                await conn.execute(
                    """
                    INSERT INTO pid_profiles (name, description, profile)
                    VALUES ($1, $2, $3::jsonb)
                    ON CONFLICT (name) DO NOTHING
                    """,
                    name,
                    description,
                    json.dumps(data),
                )
        except (asyncpg.PostgresError, OSError) as exc:
            log.warning("seed pid_profile %s failed: %s", name, exc)


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
        await _seed_pid_profiles(pool)
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
app.include_router(imports.router)
app.include_router(vehicles_api.router)
app.include_router(profiles.router)
app.include_router(readings.router)
app.include_router(trips_api.router)
app.include_router(dtcs.router)
app.include_router(settings_api.router)
app.include_router(live_ws.router)
