"""Auto-purge retention worker.

Runs once per hour. For each enabled retention threshold on the
singleton settings row, deletes rows older than the configured day
count. Mirrors the manual /admin/purge/readings + /admin/purge/logs
endpoints — same drop_chunks fast path for the Timescale hypertable
(pid_readings), plain DELETE for the regular table (client_logs).

The worker logs what it dropped (or skipped) so /debug surfaces the
activity. Errors are caught at the outer loop so a single bad cycle
doesn't kill the worker.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

import asyncpg

log = logging.getLogger(__name__)

CYCLE_INTERVAL_SECONDS = 3_600  # one pass per hour


async def _purge_readings(conn: asyncpg.Connection, days: int) -> int:
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    affected = await conn.fetchval(
        "SELECT count(*) FROM pid_readings WHERE time < $1",
        cutoff,
    )
    if affected == 0:
        return 0
    try:
        await conn.execute(
            "SELECT drop_chunks('pid_readings', $1::timestamptz)",
            cutoff,
        )
    except asyncpg.PostgresError as exc:
        log.warning("retention drop_chunks failed (%s); falling back to DELETE", exc)
        await conn.execute(
            "DELETE FROM pid_readings WHERE time < $1",
            cutoff,
        )
    return int(affected)


async def _purge_logs(conn: asyncpg.Connection, days: int) -> int:
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    affected = await conn.fetchval(
        "SELECT count(*) FROM client_logs WHERE ts < $1",
        cutoff,
    )
    if affected == 0:
        return 0
    await conn.execute("DELETE FROM client_logs WHERE ts < $1", cutoff)
    return int(affected)


async def _cycle(pool: asyncpg.Pool) -> None:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT retention_readings_days, retention_logs_days "
            "FROM settings WHERE id = 1"
        )
        if row is None:
            return
        readings_days = row["retention_readings_days"]
        logs_days = row["retention_logs_days"]

        if readings_days is not None and readings_days > 0:
            n = await _purge_readings(conn, int(readings_days))
            if n:
                log.info("retention: purged %s readings older than %s days", n, readings_days)
        if logs_days is not None and logs_days > 0:
            n = await _purge_logs(conn, int(logs_days))
            if n:
                log.info("retention: purged %s logs older than %s days", n, logs_days)


async def run(pool: asyncpg.Pool) -> None:
    """Worker entry point. Loops forever; cancelled at app shutdown."""
    log.info("retention worker started (cycle every %s s)", CYCLE_INTERVAL_SECONDS)
    while True:
        try:
            await _cycle(pool)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.error("retention cycle failed: %s", exc)
        await asyncio.sleep(CYCLE_INTERVAL_SECONDS)
