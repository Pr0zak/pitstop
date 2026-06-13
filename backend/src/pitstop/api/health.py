import shutil
from datetime import UTC, datetime
from typing import Any

import asyncpg
from fastapi import APIRouter, Depends, Request
from sqlalchemy import text

from ..db.deps import get_pool
from ..db.session import engine
from ..version import BUILD_TIME, GIT_SHA, VERSION

router = APIRouter()


@router.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/version")
async def version() -> dict[str, str]:
    return {"version": VERSION, "git_sha": GIT_SHA, "build_time": BUILD_TIME}


@router.get("/health/disk")
async def health_disk() -> dict[str, float | int]:
    usage = shutil.disk_usage("/")
    pct = usage.used / usage.total * 100
    return {
        "total_gb": round(usage.total / 1024**3, 2),
        "used_gb": round(usage.used / 1024**3, 2),
        "free_gb": round(usage.free / 1024**3, 2),
        "used_pct": round(pct, 2),
    }


@router.get("/health/db")
async def health_db() -> dict[str, str]:
    async with engine.connect() as conn:
        await conn.execute(text("SELECT 1"))
    return {"status": "ok"}


@router.get("/health/ingest")
async def health_ingest(
    request: Request, pool: asyncpg.Pool = Depends(get_pool)
) -> dict[str, Any]:
    """Ingest health (HEALTH-1).

    Two distinct signals, previously conflated under the misleading
    ``last_message_at`` name:

    - ``last_new_row_at`` — ``max(time) FROM pid_readings``, i.e. the
      freshest telemetry actually persisted. ``lag_s`` is derived from
      this; it's what tells you new data is flowing.
    - ``last_received_at`` — an in-process counter on the MQTT ingest
      worker, stamped on every broker receipt (including the loopback
      liveness probe). It proves the broker link is alive even when no
      new telemetry is arriving (parked car). It advances on retained-
      message redelivery, so it must NOT be read as "fresh data".

    ``last_message_at`` is kept as an alias of ``last_new_row_at`` for
    backward compatibility with existing clients.
    """
    async with pool.acquire() as conn:
        last = await conn.fetchval("SELECT max(time) FROM pid_readings")
    ingest = getattr(request.app.state, "ingest", None)
    last_received = getattr(ingest, "last_received_at", None) if ingest else None
    last_received_iso = last_received.isoformat() if last_received else None
    if last is None:
        return {
            "ok": False,
            "last_new_row_at": None,
            "last_message_at": None,
            "last_received_at": last_received_iso,
            "lag_s": None,
        }
    now = datetime.now(UTC)
    lag = max((now - last).total_seconds(), 0.0)
    # Considered healthy if a new row landed within the last 10 minutes.
    return {
        "ok": lag < 600.0,
        "last_new_row_at": last.isoformat(),
        "last_message_at": last.isoformat(),  # backward-compat alias
        "last_received_at": last_received_iso,
        "lag_s": lag,
    }


@router.get("/health/trips")
async def health_trips(pool: asyncpg.Pool = Depends(get_pool)) -> dict[str, int]:
    async with pool.acquire() as conn:
        n = await conn.fetchval(
            "SELECT count(*) FROM trips WHERE ended_at IS NULL"
        )
    return {"open_trips": int(n or 0)}
