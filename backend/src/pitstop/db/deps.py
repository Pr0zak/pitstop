"""FastAPI dependency helpers for the asyncpg pool living on app.state."""

from __future__ import annotations

import asyncpg
from fastapi import HTTPException, Request


def get_pool(request: Request) -> asyncpg.Pool:
    """Return the asyncpg pool stashed on app.state at startup.

    Raises 503 if the lifespan hasn't initialised it yet (e.g. during a hot
    restart — shouldn't happen in normal operation).
    """
    pool = getattr(request.app.state, "pg_pool", None)
    if pool is None:
        raise HTTPException(status_code=503, detail="db pool not ready")
    return pool
