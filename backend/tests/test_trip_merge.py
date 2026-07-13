"""Tests for the N-way trip merge endpoint (POST /trips/{id}/merge).

DB-backed: gated on a temp Postgres exposed via env vars
POSTGRES_HOST/PORT/USER/PASSWORD/DB. Skipped when unset.

Covers the MERGE-N contract: folding 3+ fragmented legs into one trip
(a long drive the phone split across BLE dropouts), the legacy single
``other_trip_id`` field still working, and the validation guards.
"""

from __future__ import annotations

import asyncio
import os
import uuid
from datetime import UTC, datetime, timedelta

import asyncpg
import pytest

pytestmark = pytest.mark.skipif(
    not (os.environ.get("POSTGRES_HOST") and os.environ.get("POSTGRES_PORT")),
    reason="no test Postgres configured",
)


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


async def _mk_vehicle(pool: asyncpg.Pool, slug: str) -> uuid.UUID:
    async with pool.acquire() as conn:
        return await conn.fetchval(
            """
            INSERT INTO vehicles (slug, name) VALUES ($1, $2)
            ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name
            RETURNING id
            """,
            slug,
            f"Merge Test {slug}",
        )


async def _mk_trip(
    pool: asyncpg.Pool,
    vehicle_id: uuid.UUID,
    started: datetime,
    duration_s: int,
    *,
    distance_km: float,
    max_rpm: float,
    max_speed_kph: float,
    avg_speed_kph: float,
    avg_coolant_c: float,
    fuel_used_l: float,
    dtc_count: int,
    idle_s: int,
    source: str = "phone_batch",
) -> uuid.UUID:
    tid = uuid.uuid4()
    ended = started + timedelta(seconds=duration_s)
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO trips (
                id, vehicle_id, started_at, ended_at, duration_s,
                distance_km, max_rpm, max_speed_kph, avg_speed_kph,
                avg_coolant_c, fuel_used_l, dtc_count, idle_s, source
            ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
            """,
            tid, vehicle_id, started, ended, duration_s,
            distance_km, max_rpm, max_speed_kph, avg_speed_kph,
            avg_coolant_c, fuel_used_l, dtc_count, idle_s, source,
        )
    return tid


async def _count_trips(pool: asyncpg.Pool, ids: list[uuid.UUID]) -> int:
    async with pool.acquire() as conn:
        return await conn.fetchval(
            "SELECT count(*) FROM trips WHERE id = ANY($1::uuid[])", ids
        )


def test_merge_three_legs_folds_stats(
    client, ingest_token, pg_pool, cleanup_test_vehicles
) -> None:
    slug = f"apitest-merge-{uuid.uuid4().hex[:8]}"
    vid = _run(_mk_vehicle(pg_pool, slug))
    t0 = datetime(2026, 7, 12, 12, 0, tzinfo=UTC)

    # Three fragmented legs with a gap between each — like the phone
    # sealing a long drive across BLE dropouts.
    a = _run(_mk_trip(
        pg_pool, vid, t0, 600,
        distance_km=10.0, max_rpm=3000, max_speed_kph=100,
        avg_speed_kph=30, avg_coolant_c=60, fuel_used_l=1.0,
        dtc_count=1, idle_s=60,
    ))
    b = _run(_mk_trip(
        pg_pool, vid, t0 + timedelta(minutes=30), 1200,
        distance_km=20.0, max_rpm=5000, max_speed_kph=130,
        avg_speed_kph=60, avg_coolant_c=90, fuel_used_l=2.0,
        dtc_count=0, idle_s=120,
    ))
    c = _run(_mk_trip(
        pg_pool, vid, t0 + timedelta(minutes=60), 300,
        distance_km=5.0, max_rpm=2000, max_speed_kph=80,
        avg_speed_kph=90, avg_coolant_c=100, fuel_used_l=0.5,
        dtc_count=2, idle_s=0,
    ))

    # Anchor the path on the LATEST leg to prove the server still keeps
    # the earliest (a) as the survivor.
    r = client.post(
        f"/trips/{c}/merge",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"other_trip_ids": [str(a), str(b)]},
    )
    assert r.status_code == 200, r.text
    body = r.json()

    assert body["id"] == str(a)  # earliest wins regardless of path anchor
    assert body["source"] == "manual_merge"
    assert body["started_at"].startswith("2026-07-12T12:00")
    # ended = last leg end = 13:05; duration = 65 min = 3900 s (gaps in).
    assert body["ended_at"].startswith("2026-07-12T13:05")
    assert body["duration_s"] == 3900
    # Sums.
    assert body["distance_km"] == pytest.approx(35.0)
    assert body["fuel_used_l"] == pytest.approx(3.5)
    assert body["dtc_count"] == 3
    assert body["idle_s"] == 180
    # Maxes.
    assert body["max_rpm"] == pytest.approx(5000)
    assert body["max_speed_kph"] == pytest.approx(130)
    # Duration-weighted averages: weights 600/1200/300 (sum 2100).
    assert body["avg_speed_kph"] == pytest.approx(
        (30 * 600 + 60 * 1200 + 90 * 300) / 2100
    )
    assert body["avg_coolant_c"] == pytest.approx(
        (60 * 600 + 90 * 1200 + 100 * 300) / 2100
    )

    # b and c are gone; only a remains.
    assert _run(_count_trips(pg_pool, [a, b, c])) == 1


def test_merge_legacy_single_field(
    client, ingest_token, pg_pool, cleanup_test_vehicles
) -> None:
    slug = f"apitest-merge-{uuid.uuid4().hex[:8]}"
    vid = _run(_mk_vehicle(pg_pool, slug))
    t0 = datetime(2026, 7, 1, 8, 0, tzinfo=UTC)
    a = _run(_mk_trip(
        pg_pool, vid, t0, 600, distance_km=5.0, max_rpm=3000,
        max_speed_kph=90, avg_speed_kph=40, avg_coolant_c=80,
        fuel_used_l=0.6, dtc_count=0, idle_s=30,
    ))
    b = _run(_mk_trip(
        pg_pool, vid, t0 + timedelta(minutes=20), 600, distance_km=7.0,
        max_rpm=3500, max_speed_kph=95, avg_speed_kph=50, avg_coolant_c=85,
        fuel_used_l=0.8, dtc_count=0, idle_s=10,
    ))
    # Old clients send the singular field only.
    r = client.post(
        f"/trips/{a}/merge",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"other_trip_id": str(b)},
    )
    assert r.status_code == 200, r.text
    assert r.json()["distance_km"] == pytest.approx(12.0)
    assert _run(_count_trips(pg_pool, [a, b])) == 1


def test_merge_needs_two_distinct(
    client, ingest_token, pg_pool, cleanup_test_vehicles
) -> None:
    slug = f"apitest-merge-{uuid.uuid4().hex[:8]}"
    vid = _run(_mk_vehicle(pg_pool, slug))
    t0 = datetime(2026, 6, 1, 8, 0, tzinfo=UTC)
    a = _run(_mk_trip(
        pg_pool, vid, t0, 600, distance_km=5.0, max_rpm=3000,
        max_speed_kph=90, avg_speed_kph=40, avg_coolant_c=80,
        fuel_used_l=0.6, dtc_count=0, idle_s=30,
    ))
    # Empty others.
    r = client.post(
        f"/trips/{a}/merge",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"other_trip_ids": []},
    )
    assert r.status_code == 400, r.text
    # Only self referenced.
    r = client.post(
        f"/trips/{a}/merge",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"other_trip_ids": [str(a)]},
    )
    assert r.status_code == 400, r.text


def test_merge_cross_vehicle_rejected(
    client, ingest_token, pg_pool, cleanup_test_vehicles
) -> None:
    v1 = _run(_mk_vehicle(pg_pool, f"apitest-merge-{uuid.uuid4().hex[:8]}"))
    v2 = _run(_mk_vehicle(pg_pool, f"apitest-merge-{uuid.uuid4().hex[:8]}"))
    t0 = datetime(2026, 5, 1, 8, 0, tzinfo=UTC)
    a = _run(_mk_trip(
        pg_pool, v1, t0, 600, distance_km=5.0, max_rpm=3000,
        max_speed_kph=90, avg_speed_kph=40, avg_coolant_c=80,
        fuel_used_l=0.6, dtc_count=0, idle_s=30,
    ))
    b = _run(_mk_trip(
        pg_pool, v2, t0 + timedelta(minutes=20), 600, distance_km=7.0,
        max_rpm=3500, max_speed_kph=95, avg_speed_kph=50, avg_coolant_c=85,
        fuel_used_l=0.8, dtc_count=0, idle_s=10,
    ))
    r = client.post(
        f"/trips/{a}/merge",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"other_trip_ids": [str(b)]},
    )
    assert r.status_code == 400, r.text
    # Nothing deleted.
    assert _run(_count_trips(pg_pool, [a, b])) == 2


def test_merge_missing_trip_404(
    client, ingest_token, pg_pool, cleanup_test_vehicles
) -> None:
    slug = f"apitest-merge-{uuid.uuid4().hex[:8]}"
    vid = _run(_mk_vehicle(pg_pool, slug))
    t0 = datetime(2026, 4, 1, 8, 0, tzinfo=UTC)
    a = _run(_mk_trip(
        pg_pool, vid, t0, 600, distance_km=5.0, max_rpm=3000,
        max_speed_kph=90, avg_speed_kph=40, avg_coolant_c=80,
        fuel_used_l=0.6, dtc_count=0, idle_s=30,
    ))
    ghost = uuid.uuid4()
    r = client.post(
        f"/trips/{a}/merge",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"other_trip_ids": [str(ghost)]},
    )
    assert r.status_code == 404, r.text
    assert _run(_count_trips(pg_pool, [a])) == 1
