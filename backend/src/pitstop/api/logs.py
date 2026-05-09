"""Centralized client-log depot endpoints.

Three endpoints:

- ``POST /logs`` (INGEST) — batch-ingest log entries from phone, web,
  WiCAN scripts, etc. Up to 500 per request.
- ``GET  /logs/recent`` (QUERY) — tail/filter view of recent entries.
- ``DELETE /logs/older-than`` (INGEST) — retention sweep, capped at 365
  days.

The backend itself also writes to ``client_logs`` via the
``DbLogHandler`` installed in ``pitstop.logging_handler`` — those rows are
tagged ``source='backend'``.
"""

from __future__ import annotations

import json
import logging
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import ValidationError

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import (
    ClientLogDeleteResult,
    ClientLogIngest,
    ClientLogIngestResult,
    ClientLogOut,
)

log = logging.getLogger(__name__)

router = APIRouter(prefix="/logs", tags=["logs"])


_LOG_COLS = "id, ts, source, level, message, vehicle_id, device_id, context, client_ts"

_MAX_BATCH = 500
_RECENT_DEFAULT_LIMIT = 200
_RECENT_MAX_LIMIT = 1000
_RETENTION_MIN_DAYS = 1
_RETENTION_MAX_DAYS = 365


def _row_to_log(row: asyncpg.Record) -> dict[str, Any]:
    raw_ctx = row["context"]
    if isinstance(raw_ctx, str):
        try:
            ctx = json.loads(raw_ctx)
        except (json.JSONDecodeError, TypeError):
            ctx = {}
    elif isinstance(raw_ctx, dict):
        ctx = raw_ctx
    else:
        ctx = {}
    return {
        "id": row["id"],
        "ts": row["ts"],
        "source": row["source"],
        "level": row["level"],
        "message": row["message"],
        "vehicle_id": row["vehicle_id"],
        "device_id": row["device_id"],
        "context": ctx,
        "client_ts": row["client_ts"],
    }


@router.post(
    "",
    response_model=ClientLogIngestResult,
    dependencies=[Depends(require_ingest_token)],
)
async def ingest_logs(
    request: Request,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, int]:
    """Insert a batch of log entries.

    Server stamps ``ts = now()`` so the tail ordering reflects arrival
    order; the client's own timestamp is preserved verbatim in
    ``client_ts``. Up to 500 entries; >500 → 413.

    Body is parsed manually rather than via FastAPI's automatic
    Pydantic binding so a malformed batch from a buggy client surfaces
    a 200 + warning in the depot (so we can see it via /debug)
    rather than a black-box 422 the client can't show us. Strict
    validation moved to the model_validate call below.
    """
    try:
        raw = await request.json()
    except (json.JSONDecodeError, ValueError) as exc:
        log.warning("logs ingest non-JSON body: %s", exc)
        return {"accepted": 0}

    try:
        body = ClientLogIngest.model_validate(raw)
    except ValidationError as exc:
        # Log the raw body shape so we can see what the client really sent.
        # Truncate so we don't write a megabyte to the depot for a runaway
        # bad-batch.
        body_repr = json.dumps(raw)[:2000] if raw is not None else "(none)"
        errs = [
            {"loc": list(e.get("loc") or []), "msg": e.get("msg"), "type": e.get("type")}
            for e in exc.errors()[:10]
        ]
        log.warning(
            "logs ingest validation failed (%d errors); errs=%s body=%s",
            len(exc.errors()),
            errs,
            body_repr,
        )
        return {"accepted": 0}

    if len(body.entries) > _MAX_BATCH:
        raise HTTPException(
            status_code=413,
            detail=f"too many entries (max {_MAX_BATCH})",
        )

    rows: list[tuple] = []
    for entry in body.entries:
        rows.append(
            (
                entry.source,
                entry.level,
                entry.message,
                entry.vehicle_id,
                entry.device_id,
                json.dumps(entry.context or {}),
                entry.ts,
            )
        )

    async with pool.acquire() as conn:
        try:
            await conn.executemany(
                """
                INSERT INTO client_logs
                    (source, level, message, vehicle_id, device_id, context, client_ts)
                VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7)
                """,
                rows,
            )
        except asyncpg.CheckViolationError as exc:
            # Surface check-constraint failures as 422 Unprocessable Entity —
            # the body parsed but a value (source/level) wasn't accepted.
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except asyncpg.ForeignKeyViolationError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {"accepted": len(rows)}


def _split_csv(raw: str | None) -> list[str]:
    if not raw:
        return []
    return [p.strip() for p in raw.split(",") if p.strip()]


@router.get(
    "/recent",
    response_model=list[ClientLogOut],
    dependencies=[Depends(require_query_token)],
)
async def recent_logs(
    source: str | None = Query(default=None),
    level: str | None = Query(default=None),
    vehicle_id: UUID | None = Query(default=None),
    from_: datetime | None = Query(default=None, alias="from"),
    to: datetime | None = Query(default=None),
    q: str | None = Query(default=None, min_length=1, max_length=200),
    limit: int = Query(
        default=_RECENT_DEFAULT_LIMIT, ge=1, le=_RECENT_MAX_LIMIT
    ),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    """Tail/filter view of recent log entries, ordered by server ``ts``
    descending."""
    if from_ is None and to is None:
        from_ = datetime.now(UTC) - timedelta(hours=1)

    where: list[str] = []
    args: list[Any] = []

    sources = _split_csv(source)
    if sources:
        args.append(sources)
        where.append(f"source = ANY(${len(args)}::text[])")

    levels = _split_csv(level)
    if levels:
        args.append(levels)
        where.append(f"level = ANY(${len(args)}::text[])")

    if vehicle_id is not None:
        args.append(vehicle_id)
        where.append(f"vehicle_id = ${len(args)}")

    if from_ is not None:
        args.append(from_)
        where.append(f"ts >= ${len(args)}")

    if to is not None:
        args.append(to)
        where.append(f"ts <= ${len(args)}")

    if q:
        args.append(f"%{q}%")
        where.append(f"message ILIKE ${len(args)}")

    sql = f"SELECT {_LOG_COLS} FROM client_logs"
    if where:
        sql += " WHERE " + " AND ".join(where)
    args.append(limit)
    sql += f" ORDER BY ts DESC LIMIT ${len(args)}"

    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, *args)
    return [_row_to_log(r) for r in rows]


@router.delete(
    "/older-than",
    response_model=ClientLogDeleteResult,
    dependencies=[Depends(require_ingest_token)],
)
async def delete_older_than(
    days: int = Query(..., ge=_RETENTION_MIN_DAYS, le=_RETENTION_MAX_DAYS),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, int]:
    """Delete log entries older than ``days``. Capped at [1, 365]."""
    cutoff = datetime.now(UTC) - timedelta(days=days)
    async with pool.acquire() as conn:
        result = await conn.execute(
            "DELETE FROM client_logs WHERE ts < $1", cutoff
        )
    # asyncpg's execute returns "DELETE <n>".
    deleted = 0
    parts = result.split()
    if len(parts) >= 2 and parts[-1].isdigit():
        deleted = int(parts[-1])
    return {"deleted": deleted}
