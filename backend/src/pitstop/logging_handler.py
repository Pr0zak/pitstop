"""Logging handler that mirrors backend WARN/ERROR records into the
``client_logs`` depot (source='backend').

Design constraints:

- Must not block the event loop. The stdlib ``logging`` API is sync; we
  drop records onto an ``asyncio.Queue`` and let a background task drain
  them.
- Must not recurse on its own errors. If a DB write fails we increment a
  drop counter and move on — never call ``log.error()`` from inside the
  handler.
- Batches up to 100 rows or 5 s, whichever comes first.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import threading
from datetime import UTC, datetime
from typing import Any

import asyncpg


class DbLogHandler(logging.Handler):
    """Sync ``logging.Handler`` that hands records off to an async drainer.

    Only records at WARNING and above are queued. Lower-severity log
    spam stays in stdout so we don't blow up the depot table.
    """

    BATCH_MAX = 100
    BATCH_FLUSH_S = 5.0
    QUEUE_MAXSIZE = 1000

    def __init__(self, level: int = logging.WARNING) -> None:
        super().__init__(level=level)
        # Created lazily — the asyncio loop may not exist yet at handler
        # install time.
        self._queue: asyncio.Queue[dict[str, Any]] | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._lock = threading.Lock()
        self.dropped = 0  # records lost because the queue was full
        self.write_errors = 0  # rows that hit a DB error during flush

    def attach(self, loop: asyncio.AbstractEventLoop) -> None:
        """Bind the handler to the running asyncio loop. Call once at
        startup, after the loop exists."""
        with self._lock:
            self._loop = loop
            self._queue = asyncio.Queue(maxsize=self.QUEUE_MAXSIZE)

    def emit(self, record: logging.LogRecord) -> None:  # noqa: D401
        """Convert a LogRecord into a queued payload. Never raises."""
        try:
            queue = self._queue
            loop = self._loop
            if queue is None or loop is None:
                return
            level = _py_level_to_depot(record.levelno)
            if level is None:
                return
            try:
                msg = record.getMessage()
            except (TypeError, ValueError):
                msg = str(record.msg)
            ctx: dict[str, Any] = {
                "logger": record.name,
                "module": record.module,
                "func": record.funcName,
                "line": record.lineno,
            }
            if record.exc_info:
                ctx["exc"] = logging.Formatter().formatException(record.exc_info)
            payload = {
                "ts": datetime.fromtimestamp(record.created, tz=UTC),
                "level": level,
                "message": msg[:4000],
                "context": ctx,
            }
            try:
                loop.call_soon_threadsafe(self._enqueue_nowait, payload)
            except RuntimeError:
                # Loop is closing or closed.
                self.dropped += 1
        except Exception:  # noqa: BLE001 - never recurse via log.* here
            # Bare-broad catch is intentional: a logging handler must
            # never raise (would corrupt other log sites).
            self.dropped += 1

    def _enqueue_nowait(self, payload: dict[str, Any]) -> None:
        if self._queue is None:
            return
        try:
            self._queue.put_nowait(payload)
        except asyncio.QueueFull:
            self.dropped += 1


def _py_level_to_depot(level: int) -> str | None:
    """Map Python logging levels to client_logs.level values.

    Below WARNING we drop on the floor — we don't want the depot drowned
    in DEBUG/INFO. (Clients with their own ingest path can still POST
    info/debug rows; that's their call.)
    """
    if level >= logging.ERROR:
        return "error"
    if level >= logging.WARNING:
        return "warn"
    return None


async def run_db_log_drainer(
    handler: DbLogHandler,
    pool: asyncpg.Pool,
) -> None:
    """Drain the handler's queue into ``client_logs``.

    Runs until cancelled. Catches and counts errors; never raises out of
    the task body so a hiccup doesn't kill the worker.
    """
    if handler._queue is None:  # noqa: SLF001 - intentional internal access
        return
    queue = handler._queue
    pending: list[dict[str, Any]] = []
    while True:
        try:
            # Wait for at least one record, then opportunistically batch.
            try:
                first = await asyncio.wait_for(
                    queue.get(), timeout=handler.BATCH_FLUSH_S
                )
                pending.append(first)
            except TimeoutError:
                pass

            # Drain whatever's already queued, up to BATCH_MAX.
            while pending and len(pending) < handler.BATCH_MAX:
                try:
                    pending.append(queue.get_nowait())
                except asyncio.QueueEmpty:
                    break

            if pending:
                await _flush_batch(pool, pending, handler)
                pending = []
        except asyncio.CancelledError:
            # On shutdown, attempt one last drain so we don't lose the
            # final WARN/ERROR lines (e.g. shutdown error).
            with contextlib.suppress(Exception):
                while True:
                    try:
                        pending.append(queue.get_nowait())
                    except asyncio.QueueEmpty:
                        break
                if pending:
                    await _flush_batch(pool, pending, handler)
            raise
        except Exception:  # noqa: BLE001
            handler.write_errors += 1
            pending = []
            # Brief back-off so we don't spin on persistent errors.
            await asyncio.sleep(1.0)


async def _flush_batch(
    pool: asyncpg.Pool,
    batch: list[dict[str, Any]],
    handler: DbLogHandler,
) -> None:
    rows = [
        (
            "backend",
            p["level"],
            p["message"],
            None,  # vehicle_id — not knowable from a LogRecord
            None,  # device_id
            json.dumps(p["context"]),
            p["ts"],
        )
        for p in batch
    ]
    try:
        async with pool.acquire() as conn:
            await conn.executemany(
                """
                INSERT INTO client_logs
                    (source, level, message, vehicle_id, device_id,
                     context, client_ts)
                VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7)
                """,
                rows,
            )
    except (asyncpg.PostgresError, OSError):
        # Don't recurse via logging — just count and drop.
        handler.write_errors += len(rows)
