"""Tests for compute_trip_stats (workers/trip_stats.py).

DB-backed: gated on a temp Postgres exposed via env vars
POSTGRES_HOST/PORT/USER/PASSWORD/DB. Skipped when unset.

The pure state-machine tests that used to live alongside this
(``decide_on_event`` / ``decide_on_sweep``) were dropped when the streaming
TripDetector was retired in favour of the periodic trip_deriver.
"""

from __future__ import annotations

import os
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from pitstop.workers.trip_stats import compute_trip_stats


def _has_pg_env() -> bool:
    return bool(os.environ.get("POSTGRES_HOST")) and bool(
        os.environ.get("POSTGRES_PORT")
    )


@pytest.mark.skipif(not _has_pg_env(), reason="no test Postgres configured")
@pytest.mark.asyncio
async def test_compute_trip_stats_basic() -> None:
    import asyncpg

    dsn = (
        f"postgresql://{os.environ['POSTGRES_USER']}:"
        f"{os.environ['POSTGRES_PASSWORD']}@"
        f"{os.environ['POSTGRES_HOST']}:{os.environ['POSTGRES_PORT']}/"
        f"{os.environ['POSTGRES_DB']}"
    )
    conn = await asyncpg.connect(dsn=dsn)
    vehicle_id = None
    try:
        # Reset and seed: a fresh vehicle.
        vehicle_id = await conn.fetchval(
            """
            INSERT INTO vehicles (slug, name) VALUES ($1, $2)
            ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name
            RETURNING id
            """,
            "test-trip-stats",
            "Test Trip Stats",
        )
        # Clean any prior readings for this vehicle.
        await conn.execute(
            "DELETE FROM pid_readings WHERE vehicle_id = $1", vehicle_id
        )
        # Seed 60 vehicle_speed readings at 1s intervals at 60 kph.
        # Distance = 60 kph * 60s / 3600 = 1.0 km.
        t0 = datetime.now(UTC) - timedelta(minutes=5)
        rows = []
        for i in range(60):
            t = t0 + timedelta(seconds=i)
            rows.append((t, vehicle_id, "vehicle_speed", 60.0, None, "wican"))
            rows.append((t, vehicle_id, "engine_rpm", 1500.0 + i, None, "wican"))
            rows.append((t, vehicle_id, "coolant_temp", 90.0, None, "wican"))
        await conn.executemany(
            "INSERT INTO pid_readings "
            "(time, vehicle_id, metric, value_num, value_text, source) "
            "VALUES ($1, $2, $3, $4, $5, $6)",
            rows,
        )

        # Synthetic trip id (we don't insert into trips here — stats only).
        trip_id = uuid4()
        started = t0
        ended = t0 + timedelta(seconds=60)
        stats = await compute_trip_stats(
            conn, trip_id, vehicle_id, started, ended
        )

        assert stats["duration_s"] == 60
        # Distance: 60 readings * 60 kph * 1 sec each, integrated.
        # 59 dt's of 1s; 59 * 60 / 3600 = 0.9833 km.
        assert stats["distance_km"] is not None
        assert 0.9 < stats["distance_km"] < 1.05
        assert stats["max_speed_kph"] == 60.0
        assert stats["avg_speed_kph"] == 60.0
        assert stats["max_rpm"] is not None
        assert stats["max_rpm"] >= 1559.0
        assert stats["avg_coolant_c"] == 90.0
        assert stats["dtc_count"] == 0

    finally:
        if vehicle_id is not None:
            await conn.execute(
                "DELETE FROM pid_readings WHERE vehicle_id = $1", vehicle_id
            )
            await conn.execute("DELETE FROM vehicles WHERE id = $1", vehicle_id)
        await conn.close()


@pytest.mark.skipif(not _has_pg_env(), reason="no test Postgres configured")
@pytest.mark.asyncio
async def test_sparse_gps_falls_back_to_speed_integration() -> None:
    """Sparse GPS must NOT under-report a real drive (the 2026-07-13 bug).

    A cold-start drive gets only a handful of GPS fixes with >60 s gaps, so
    the haversine sum drops almost every pair and collapses a real ~1 km
    drive to a tiny non-zero number. Preferring GPS whenever it was non-zero
    then silently under-reported. compute_trip_stats now takes the max of
    the GPS-haversine and speed-integration distances, so dense OBD speed
    wins here.
    """
    import asyncpg

    dsn = (
        f"postgresql://{os.environ['POSTGRES_USER']}:"
        f"{os.environ['POSTGRES_PASSWORD']}@"
        f"{os.environ['POSTGRES_HOST']}:{os.environ['POSTGRES_PORT']}/"
        f"{os.environ['POSTGRES_DB']}"
    )
    conn = await asyncpg.connect(dsn=dsn)
    vehicle_id = None
    try:
        vehicle_id = await conn.fetchval(
            """
            INSERT INTO vehicles (slug, name) VALUES ($1, $2)
            ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name
            RETURNING id
            """,
            "test-sparse-gps",
            "Test Sparse GPS",
        )
        await conn.execute("DELETE FROM pid_readings WHERE vehicle_id = $1", vehicle_id)
        await conn.execute("DELETE FROM gps_points WHERE vehicle_id = $1", vehicle_id)

        t0 = datetime.now(UTC) - timedelta(minutes=5)
        # Dense OBD speed: 60 samples at 60 kph over 60 s ≈ 0.98 km.
        speed_rows = [
            (t0 + timedelta(seconds=i), vehicle_id, "vehicle_speed", 60.0, None, "wican")
            for i in range(60)
        ]
        await conn.executemany(
            "INSERT INTO pid_readings "
            "(time, vehicle_id, metric, value_num, value_text, source) "
            "VALUES ($1, $2, $3, $4, $5, $6)",
            speed_rows,
        )
        # Sparse GPS: only two fixes 10 s apart covering ~0.11 km — the rest
        # of the drive has no fix. Haversine alone would report ~0.11 km.
        await conn.executemany(
            "INSERT INTO gps_points (time, vehicle_id, lat, lon, source) "
            "VALUES ($1, $2, $3, $4, 'bridge')",
            # Synthetic mid-ocean coords (not near any real location) —
            # 0.001° lat ≈ 0.111 km, so the single kept pair sums ~0.11 km.
            [
                (t0 + timedelta(seconds=5), vehicle_id, 10.0000, 20.0000),
                (t0 + timedelta(seconds=15), vehicle_id, 10.0010, 20.0000),
            ],
        )

        stats = await compute_trip_stats(
            conn, uuid4(), vehicle_id, t0, t0 + timedelta(seconds=60)
        )
        # Speed integration (~0.98 km) must win over the sparse-GPS 0.11 km.
        assert stats["distance_km"] is not None
        assert stats["distance_km"] > 0.9, stats["distance_km"]
    finally:
        if vehicle_id is not None:
            await conn.execute("DELETE FROM pid_readings WHERE vehicle_id = $1", vehicle_id)
            await conn.execute("DELETE FROM gps_points WHERE vehicle_id = $1", vehicle_id)
            await conn.execute("DELETE FROM vehicles WHERE id = $1", vehicle_id)
        await conn.close()
