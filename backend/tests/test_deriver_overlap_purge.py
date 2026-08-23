"""The deriver used to leave overlapping trips behind.

Its self-cleanup only deleted stale trips fully CONTAINED in a new
interval. The commonest stale shape is not contained: a trip that begins
before the new interval and runs into it, left over from a run that
happened before a late phone upload filled a sampling gap. Those rows
survived, so the same driving appeared twice — 22 such pairs over the
2026-08-03 to 08-21 tank, 43.5 double-counted miles against 485 real
odometer miles, inflating every per-mile figure downstream.
"""

from __future__ import annotations

import os
import sys
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from pitstop.workers.trip_deriver import (  # noqa: E402
    _Interval,
    _purge_overlapping_deriver_trips,
)

from .conftest import _dsn, _has_pg_env  # noqa: E402

pytestmark = pytest.mark.skipif(
    not _has_pg_env(), reason="no test Postgres configured"
)

T0 = datetime(2026, 8, 12, 17, 0, tzinfo=UTC)


async def _seed(conn, slug: str) -> str:
    return await conn.fetchval(
        "INSERT INTO vehicles (slug, name) VALUES ($1, 'purge fixture') RETURNING id",
        slug,
    )


async def _add_trip(conn, vehicle_id, start, end, source="deriver") -> str:
    return await conn.fetchval(
        """
        INSERT INTO trips (vehicle_id, started_at, ended_at, distance_km, source)
        VALUES ($1, $2, $3, 10.0, $4) RETURNING id
        """,
        vehicle_id, start, end, source,
    )


async def _surviving(conn, vehicle_id) -> set:
    rows = await conn.fetch(
        "SELECT id FROM trips WHERE vehicle_id = $1", vehicle_id
    )
    return {r["id"] for r in rows}


async def test_purges_a_stale_trip_that_starts_before_the_new_interval():
    """The shape the old containment predicate missed."""
    import asyncpg

    pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
    slug = f"apitest-purge-{uuid4().hex[:8]}"
    try:
        async with pool.acquire() as conn:
            v = await _seed(conn, slug)
            # Stale: begins 10 min before the interval, ends inside it.
            stale = await _add_trip(
                conn, v, T0 - timedelta(minutes=10), T0 + timedelta(minutes=12)
            )
            interval = _Interval(T0, T0 + timedelta(minutes=40))
            purged = await _purge_overlapping_deriver_trips(conn, v, [interval])
            assert purged == 1
            assert stale not in await _surviving(conn, v)
    finally:
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM vehicles WHERE slug = $1", slug)
        await pool.close()


async def test_never_purges_the_row_the_run_is_about_to_write():
    """A trip keyed on an interval's own started_at is this run's output,
    not a leftover. Without the new_starts guard the wider predicate would
    delete the very rows the deriver is producing."""
    import asyncpg

    pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
    slug = f"apitest-purge-{uuid4().hex[:8]}"
    try:
        async with pool.acquire() as conn:
            v = await _seed(conn, slug)
            mine = await _add_trip(conn, v, T0, T0 + timedelta(minutes=40))
            interval = _Interval(T0, T0 + timedelta(minutes=40))
            assert await _purge_overlapping_deriver_trips(conn, v, [interval]) == 0
            assert mine in await _surviving(conn, v)
    finally:
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM vehicles WHERE slug = $1", slug)
        await pool.close()


async def test_leaves_phone_and_merged_trips_alone():
    """Only deriver output is ever reclaimed. A phone_batch trip is the
    authoritative record of a drive and a manual_merge is a user decision."""
    import asyncpg

    pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
    slug = f"apitest-purge-{uuid4().hex[:8]}"
    try:
        async with pool.acquire() as conn:
            v = await _seed(conn, slug)
            phone = await _add_trip(
                conn, v, T0 + timedelta(minutes=5), T0 + timedelta(minutes=20),
                source="phone_batch",
            )
            merged = await _add_trip(
                conn, v, T0 + timedelta(minutes=6), T0 + timedelta(minutes=21),
                source="manual_merge",
            )
            interval = _Interval(T0, T0 + timedelta(minutes=40))
            assert await _purge_overlapping_deriver_trips(conn, v, [interval]) == 0
            alive = await _surviving(conn, v)
            assert phone in alive and merged in alive
    finally:
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM vehicles WHERE slug = $1", slug)
        await pool.close()


async def test_a_trip_entirely_outside_the_interval_is_untouched():
    import asyncpg

    pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
    slug = f"apitest-purge-{uuid4().hex[:8]}"
    try:
        async with pool.acquire() as conn:
            v = await _seed(conn, slug)
            earlier = await _add_trip(
                conn, v, T0 - timedelta(hours=3), T0 - timedelta(hours=2)
            )
            interval = _Interval(T0, T0 + timedelta(minutes=40))
            assert await _purge_overlapping_deriver_trips(conn, v, [interval]) == 0
            assert earlier in await _surviving(conn, v)
    finally:
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM vehicles WHERE slug = $1", slug)
        await pool.close()


async def test_full_containment_still_purges():
    """Regression: the old behaviour is a subset of the new predicate."""
    import asyncpg

    pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
    slug = f"apitest-purge-{uuid4().hex[:8]}"
    try:
        async with pool.acquire() as conn:
            v = await _seed(conn, slug)
            inner = await _add_trip(
                conn, v, T0 + timedelta(minutes=5), T0 + timedelta(minutes=20)
            )
            interval = _Interval(T0, T0 + timedelta(minutes=40))
            assert await _purge_overlapping_deriver_trips(conn, v, [interval]) == 1
            assert inner not in await _surviving(conn, v)
    finally:
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM vehicles WHERE slug = $1", slug)
        await pool.close()
