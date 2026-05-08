import shutil

from fastapi import APIRouter
from sqlalchemy import text

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
