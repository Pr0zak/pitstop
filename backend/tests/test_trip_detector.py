"""Tests for the trip detector.

Two tiers:
- Pure state-machine tests using ``decide_on_event`` / ``decide_on_sweep``.
- DB-backed stats test (``compute_trip_stats``) gated on a temp Postgres
  exposed via env vars: POSTGRES_HOST/PORT/USER/PASSWORD/DB. Skipped when
  unset.
"""

from __future__ import annotations

import os
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from pitstop.workers.trip_detector import (
    _VehicleState,
    compute_trip_stats,
    decide_on_event,
    decide_on_sweep,
)

SILENCE_OPEN = 120
SILENCE_CLOSE = 60
LOW_V = 12.0
LOW_V_N = 3


def _decide(state: _VehicleState, t: datetime, metric: str, v: float | None):
    return decide_on_event(
        state,
        t,
        metric,
        v,
        silence_open_s=SILENCE_OPEN,
        silence_close_s=SILENCE_CLOSE,
        low_voltage_threshold=LOW_V,
        low_voltage_consecutive=LOW_V_N,
    )


# ---------------------------------------------------------------------------
# State machine
# ---------------------------------------------------------------------------


class TestStateMachineOpen:
    def test_first_event_opens_trip(self) -> None:
        state = _VehicleState()
        t0 = datetime.now(UTC)
        d = _decide(state, t0, "engine_rpm", 800.0)
        assert d.open_trip is True
        assert d.close_trip is False

    def test_continuation_within_open_window_does_not_reopen(self) -> None:
        state = _VehicleState()
        t0 = datetime.now(UTC)
        _decide(state, t0, "engine_rpm", 800.0)
        # Caller would normally set current_trip_id after _open_trip succeeds;
        # simulate that.
        state.current_trip_id = uuid4()
        state.current_trip_started = t0

        t1 = t0 + timedelta(seconds=10)
        d = _decide(state, t1, "engine_rpm", 1500.0)
        assert d.open_trip is False
        assert d.close_trip is False

    def test_opens_after_long_silence_when_no_open_trip(self) -> None:
        state = _VehicleState()
        t0 = datetime.now(UTC)
        state.last_seen_at = t0
        # No trip currently open.
        t1 = t0 + timedelta(seconds=SILENCE_OPEN + 1)
        d = _decide(state, t1, "engine_rpm", 800.0)
        assert d.open_trip is True


class TestStateMachineLowVoltageClose:
    def test_three_consecutive_low_volts_close(self) -> None:
        state = _VehicleState()
        t0 = datetime.now(UTC)
        # Open a trip.
        _decide(state, t0, "engine_rpm", 800.0)
        state.current_trip_id = uuid4()
        state.current_trip_started = t0

        for i in range(LOW_V_N):
            t = t0 + timedelta(seconds=i + 1)
            d = _decide(state, t, "control_module_voltage", 11.5)
            if i < LOW_V_N - 1:
                assert d.close_trip is False
            else:
                assert d.close_trip is True
                assert d.close_reason == "low_voltage"

    def test_normal_voltage_resets_counter(self) -> None:
        state = _VehicleState()
        t0 = datetime.now(UTC)
        _decide(state, t0, "engine_rpm", 800.0)
        state.current_trip_id = uuid4()
        state.current_trip_started = t0

        # Two low readings.
        _decide(state, t0 + timedelta(seconds=1), "control_module_voltage", 11.5)
        _decide(state, t0 + timedelta(seconds=2), "control_module_voltage", 11.5)
        # One healthy reading resets.
        _decide(state, t0 + timedelta(seconds=3), "control_module_voltage", 13.5)
        # Now another two lows must not yet close.
        _decide(state, t0 + timedelta(seconds=4), "control_module_voltage", 11.5)
        d = _decide(state, t0 + timedelta(seconds=5), "control_module_voltage", 11.5)
        assert d.close_trip is False


class TestSweep:
    def test_sweep_closes_after_silence(self) -> None:
        state = _VehicleState()
        t0 = datetime.now(UTC)
        state.current_trip_id = uuid4()
        state.current_trip_started = t0
        state.last_event_in_trip_at = t0
        # 61s later → past close threshold.
        d = decide_on_sweep(
            state, t0 + timedelta(seconds=SILENCE_CLOSE + 1),
            silence_close_s=SILENCE_CLOSE,
        )
        assert d.close_trip is True
        assert d.close_reason == "silence"

    def test_sweep_does_nothing_within_window(self) -> None:
        state = _VehicleState()
        t0 = datetime.now(UTC)
        state.current_trip_id = uuid4()
        state.current_trip_started = t0
        state.last_event_in_trip_at = t0
        d = decide_on_sweep(
            state, t0 + timedelta(seconds=SILENCE_CLOSE - 1),
            silence_close_s=SILENCE_CLOSE,
        )
        assert d.close_trip is False

    def test_sweep_no_open_trip(self) -> None:
        state = _VehicleState()
        d = decide_on_sweep(
            state, datetime.now(UTC), silence_close_s=SILENCE_CLOSE
        )
        assert d.close_trip is False


# ---------------------------------------------------------------------------
# DB-backed stats — skipped if no temp Postgres is available
# ---------------------------------------------------------------------------


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
        # Cleanup readings + vehicle row for repeat runs.
        if vehicle_id is not None:
            await conn.execute(
                "DELETE FROM pid_readings WHERE vehicle_id = $1", vehicle_id
            )
            await conn.execute("DELETE FROM vehicles WHERE id = $1", vehicle_id)
        await conn.close()
