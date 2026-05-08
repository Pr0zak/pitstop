import shutil
from datetime import UTC, datetime
from typing import Any

import asyncpg
from fastapi import APIRouter, Depends
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
async def health_ingest(pool: asyncpg.Pool = Depends(get_pool)) -> dict[str, Any]:
    """Most recent pid_readings row across all vehicles, with lag."""
    async with pool.acquire() as conn:
        last = await conn.fetchval(
            "SELECT max(time) FROM pid_readings"
        )
    if last is None:
        return {"ok": False, "last_message_at": None, "lag_s": None}
    now = datetime.now(UTC)
    lag = max((now - last).total_seconds(), 0.0)
    # Considered healthy if we've heard within the last 10 minutes; tunable.
    return {"ok": lag < 600.0, "last_message_at": last.isoformat(), "lag_s": lag}


@router.get("/health/trips")
async def health_trips(pool: asyncpg.Pool = Depends(get_pool)) -> dict[str, int]:
    async with pool.acquire() as conn:
        n = await conn.fetchval(
            "SELECT count(*) FROM trips WHERE ended_at IS NULL"
        )
    return {"open_trips": int(n or 0)}
