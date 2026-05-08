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
    analytics,
    dtcs,
    expenses,
    fillups,
    health,
    imports,
    live_ws,
    maintenance,
    profiles,
    readings,
)
from .api import logs as logs_api
from .api import settings as settings_api
from .api import trips as trips_api
from .api import utils as utils_api
from .api import vehicles as vehicles_api
from .config import settings
from .logging_handler import DbLogHandler, run_db_log_drainer
from .workers.bus import bus
from .workers.ha_mirror import HaMirror
from .workers.ingest import MqttIngest
from .workers.trip_detector import TripDetector

logging.basicConfig(level=getattr(logging, settings.log_level.upper(), logging.INFO))
log = logging.getLogger(__name__)

# Install the depot handler at import time so any startup-phase warnings get
# captured (the actual asyncio queue is wired up in lifespan once a loop
# exists). Attaching to the root logger means every module's logger feeds it.
db_log_handler = DbLogHandler()
logging.getLogger().addHandler(db_log_handler)


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
    ha_task: asyncio.Task | None = None
    log_drain_task: asyncio.Task | None = None
    ingest: MqttIngest | None = None
    trip_detector: TripDetector | None = None
    ha_mirror: HaMirror | None = None
    try:
        async def _init_conn(conn: asyncpg.Connection) -> None:
            # Register a JSON codec so JSONB columns deserialize to Python
            # dicts/lists automatically (and dicts on the way in serialize
            # to JSON). Without this asyncpg hands JSONB to the app as a
            # raw `str` and every endpoint has to remember to json.loads().
            for typ in ("jsonb", "json"):
                await conn.set_type_codec(
                    typ,
                    encoder=json.dumps,
                    decoder=json.loads,
                    schema="pg_catalog",
                )

        pool = await asyncpg.create_pool(
            dsn=settings.asyncpg_dsn,
            min_size=1,
            max_size=10,
            init=_init_conn,
        )
        app.state.pg_pool = pool
        app.state.bus = bus
        await _seed_pid_profiles(pool)
        # Bind the log handler now that we have a running loop.
        loop = asyncio.get_running_loop()
        db_log_handler.attach(loop)
        log_drain_task = asyncio.create_task(
            run_db_log_drainer(db_log_handler, pool), name="db-log-drainer"
        )
        ingest = MqttIngest(pool=pool, bus_=bus, config=settings)
        trip_detector = TripDetector(pool=pool, bus_=bus, config=settings)
        ha_mirror = HaMirror(pool=pool, bus_=bus, config=settings)
        ingest_task = asyncio.create_task(ingest.run(), name="mqtt-ingest")
        trip_task = asyncio.create_task(trip_detector.run(), name="trip-detector")
        ha_task = asyncio.create_task(ha_mirror.run(), name="ha-mirror")
        yield
    finally:
        log.info("pitstop backend stopping")
        if ingest is not None:
            ingest.stop()
        if trip_detector is not None:
            trip_detector.stop()
        if ha_mirror is not None:
            ha_mirror.stop()
        for task in (ingest_task, trip_task, ha_task, log_drain_task):
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
app.include_router(fillups.router)
app.include_router(expenses.router)
app.include_router(analytics.router)
app.include_router(maintenance.router)
app.include_router(live_ws.router)
app.include_router(utils_api.router)
app.include_router(logs_api.router)
