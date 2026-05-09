"""Admin endpoints — storage stats and on-demand retention purges.

Read endpoints (storage stats) use require_query_token. Mutating endpoints
(purges) require require_ingest_token AND a deliberate ``confirm=true``
query param so a misclicked button can't drop a year of readings.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool

log = logging.getLogger(__name__)

router = APIRouter(prefix="/admin", tags=["admin"])


# Tables we surface stats for. Hypertables (pid_readings, dtc_events) are
# rolled up by aggregating their chunks back to the parent name so the UI
# reports one row per logical table. The rest of the schema (alembic_version,
# bgw_job, etc.) is omitted — not actionable from the user's perspective.
_USER_TABLES = (
    "pid_readings",
    "fillups",
    "trips",
    "dtc_events",
    "expenses",
    "client_logs",
    "vehicles",
    "vehicle_state",
    "expense_categories",
    "pid_profiles",
)


@router.get(
    "/storage",
    dependencies=[Depends(require_query_token)],
)
async def storage_stats(
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Per-table size + row counts.

    For Timescale hypertables (pid_readings, dtc_events), rolls up all
    chunks under the logical name so the user sees a single number per
    feature, not one row per Timescale chunk.
    """
    async with pool.acquire() as conn:
        # Total DB size
        total_size_bytes = await conn.fetchval(
            "SELECT pg_database_size(current_database())"
        )

        # Per-logical-table stats. The hypertable rollup uses
        # _timescaledb_internal.show_chunks → not always available;
        # cleaner to use the timescaledb catalog directly.
        rows = []
        for table in _USER_TABLES:
            exists = await conn.fetchval(
                "SELECT to_regclass($1)::text IS NOT NULL", f"public.{table}"
            )
            if not exists:
                continue

            # Row count — for hypertables the parent table reports 0,
            # so we count() against it which Timescale routes to chunks.
            try:
                row_count = await conn.fetchval(f"SELECT count(*) FROM {table}")
            except asyncpg.PostgresError:
                row_count = None

            # Size — for plain tables pg_total_relation_size; for
            # hypertables we sum chunk sizes via the timescaledb catalog.
            is_hyper = await conn.fetchval(
                """
                SELECT EXISTS (
                    SELECT 1 FROM timescaledb_information.hypertables
                    WHERE hypertable_name = $1
                )
                """,
                table,
            )
            if is_hyper:
                # Timescale's hypertable_size() returns the total disk
                # bytes across all chunks (data + index + toast).
                # Older Timescale releases don't have a size column on
                # timescaledb_information.chunks — the function call is
                # the version-stable way.
                try:
                    size = await conn.fetchval(
                        "SELECT hypertable_size($1::regclass)",
                        f"public.{table}",
                    )
                except asyncpg.PostgresError:
                    # Fall back to summing chunk sizes via pg_total_relation_size.
                    size = await conn.fetchval(
                        """
                        SELECT COALESCE(sum(pg_total_relation_size(
                                   format('%I.%I', chunk_schema, chunk_name)::regclass
                               )), 0)::bigint
                          FROM timescaledb_information.chunks
                         WHERE hypertable_name = $1
                        """,
                        table,
                    )
            else:
                size = await conn.fetchval(
                    "SELECT pg_total_relation_size($1)", f"public.{table}"
                )

            rows.append(
                {
                    "table": table,
                    "rows": int(row_count) if row_count is not None else None,
                    "size_bytes": int(size or 0),
                    "is_hypertable": bool(is_hyper),
                }
            )

        # Oldest reading timestamp — surfaces "your oldest data is X
        # days old" so the retention slider has useful context.
        oldest_reading = await conn.fetchval(
            "SELECT min(time) FROM pid_readings"
        )

    return {
        "total_size_bytes": int(total_size_bytes or 0),
        "tables": sorted(rows, key=lambda r: r["size_bytes"], reverse=True),
        "oldest_reading_at": oldest_reading.isoformat() if oldest_reading else None,
    }


@router.post(
    "/purge/readings",
    dependencies=[Depends(require_ingest_token)],
)
async def purge_readings(
    older_than_days: int | None = Query(default=None, ge=1, le=3650),
    confirm: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Drop OBD readings older than N days.

    Two-step: first call returns the projected row count for review,
    second call with confirm=true actually deletes. The Timescale
    drop_chunks function is much faster than DELETE for hypertable
    purges, so we use it when the cutoff aligns to chunk boundaries
    (Timescale handles partial-chunk deletes via DELETE under the hood).

    Body: { older_than_days: 1..3650, confirm: bool }
    """
    if older_than_days is None:
        raise HTTPException(
            status_code=400, detail="older_than_days is required (1..3650)"
        )
    cutoff = datetime.now(timezone.utc) - timedelta(days=older_than_days)

    async with pool.acquire() as conn:
        affected = await conn.fetchval(
            "SELECT count(*) FROM pid_readings WHERE time < $1",
            cutoff,
        )

        if not confirm:
            return {
                "preview": True,
                "older_than_days": older_than_days,
                "cutoff_iso": cutoff.isoformat(),
                "rows_to_delete": int(affected or 0),
            }

        # Actually purge. Use drop_chunks for the bulk path.
        try:
            await conn.execute(
                "SELECT drop_chunks('pid_readings', $1::timestamptz)",
                cutoff,
            )
        except asyncpg.PostgresError as exc:
            # drop_chunks may fail on partial-chunk cutoffs; fall back to
            # a plain DELETE which is slower but always works.
            log.warning(
                "drop_chunks failed (%s) — falling back to DELETE", exc
            )
            await conn.execute(
                "DELETE FROM pid_readings WHERE time < $1",
                cutoff,
            )

    log.info(
        "admin purge readings older_than_days=%s rows_deleted=%s",
        older_than_days,
        affected,
    )
    return {
        "preview": False,
        "older_than_days": older_than_days,
        "cutoff_iso": cutoff.isoformat(),
        "rows_deleted": int(affected or 0),
    }


@router.post(
    "/cleanup/sliver-trips",
    dependencies=[Depends(require_ingest_token)],
)
async def cleanup_sliver_trips(
    min_km: float = Query(default=0.5, ge=0.0, le=10.0),
    confirm: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """One-shot cleanup of zero-distance / sliver trips.

    The trip detector started rejecting these at v0.1.31 (#49) but
    legacy rows from before that fix linger in the trips table — they
    pollute the trips list and the fleet dashboard's distance averages.
    Same preview/confirm shape as /purge/readings so the user can see
    what's about to disappear before it's gone.
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT id, started_at, distance_km, duration_s
              FROM trips
             WHERE distance_km IS NULL OR distance_km < $1
             ORDER BY started_at ASC
            """,
            min_km,
        )
        if not confirm:
            return {
                "preview": True,
                "min_km": min_km,
                "rows_to_delete": len(rows),
                "samples": [
                    {
                        "id": str(r["id"]),
                        "started_at": r["started_at"].isoformat(),
                        "distance_km": float(r["distance_km"] or 0),
                        "duration_s": int(r["duration_s"] or 0),
                    }
                    for r in rows[:5]
                ],
            }
        deleted = await conn.fetchval(
            """
            WITH d AS (
                DELETE FROM trips
                 WHERE distance_km IS NULL OR distance_km < $1
                 RETURNING 1
            )
            SELECT count(*) FROM d
            """,
            min_km,
        )

    log.info("admin sliver-trip cleanup deleted=%s min_km=%s", deleted, min_km)
    return {"preview": False, "min_km": min_km, "rows_deleted": int(deleted or 0)}


@router.post(
    "/purge/logs",
    dependencies=[Depends(require_ingest_token)],
)
async def purge_logs(
    older_than_days: int | None = Query(default=None, ge=1, le=3650),
    confirm: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Drop client_logs older than N days. Same two-step preview/confirm shape."""
    if older_than_days is None:
        raise HTTPException(status_code=400, detail="older_than_days is required")
    cutoff = datetime.now(timezone.utc) - timedelta(days=older_than_days)

    async with pool.acquire() as conn:
        affected = await conn.fetchval(
            "SELECT count(*) FROM client_logs WHERE ts < $1",
            cutoff,
        )
        if not confirm:
            return {
                "preview": True,
                "older_than_days": older_than_days,
                "cutoff_iso": cutoff.isoformat(),
                "rows_to_delete": int(affected or 0),
            }
        await conn.execute("DELETE FROM client_logs WHERE ts < $1", cutoff)

    return {
        "preview": False,
        "older_than_days": older_than_days,
        "cutoff_iso": cutoff.isoformat(),
        "rows_deleted": int(affected or 0),
    }
