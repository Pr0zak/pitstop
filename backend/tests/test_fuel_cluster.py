"""Cluster litres as the preferred fuel source (ADR-025 amendment).

On 2026-10-21 the running estimate (fill-up reset + trip burn + snaps to
the raw % PID) read 53.1 L while the instrument cluster's own fuel
quantity (``fuel_level_l``, Honda DID 7029) read 44.5 L. Over a 7-min
drive the cluster held 44.46-44.94 L while PID 0x2F swung 27-58 %.

The snap now targets the median of phone-captured cluster litres when the
window holds at least ``CLUSTER_MIN_READINGS``, falling back to the % PID;
the vehicles API exposes the cluster reading and one shared display rule;
fill-ups log how each source compared with the pump.
"""

from __future__ import annotations

import logging
import os
import sys
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from pitstop.services.fuel_state import (  # noqa: E402
    CLUSTER_FRESH_S,
    CLUSTER_METRIC,
    CLUSTER_MIN_READINGS,
    fillup_check,
    fuel_display,
    snap_to_cluster,
    summarize_cluster_window,
)

from .conftest import _dsn, _has_pg_env  # noqa: E402

TANK_L = 73.8
CAL_PCT = 91.373
EMPTY_PCT = 5.671
NOW = datetime(2026, 10, 21, 18, 0, tzinfo=UTC)

# The 2026-10-21 drive: the cluster, damped, and the % PID, sloshing.
CLUSTER_DRIVE_L = (
    44.94, 44.90, 44.86, 44.82, 44.78, 44.74, 44.70, 44.66, 44.62, 44.58,
    44.54, 44.50, 44.46, 44.46,
)
CLUSTER_MEDIAN_L = (44.70 + 44.66) / 2
PCT_DRIVE_RAW = (
    58.0, 27.0, 55.1, 49.0, 41.2, 52.9, 33.3, 57.6, 47.1, 50.2, 45.5, 56.5,
    38.0, 54.1,
)
ESTIMATE_L = 53.1


def _times(anchor: datetime, n: int, step_s: float = 30.0) -> list[datetime]:
    return [anchor - timedelta(seconds=step_s * (n - 1 - i)) for i in range(n)]


# ── Pure helpers ─────────────────────────────────────────────────────

def test_cluster_window_is_the_median():
    w = summarize_cluster_window(zip(_times(NOW, 14), CLUSTER_DRIVE_L, strict=True))
    assert w is not None
    assert w.rejection is None
    assert w.n_readings == 14
    assert w.target_l == pytest.approx(CLUSTER_MEDIAN_L)
    assert w.anchor == NOW


def test_cluster_window_odd_count_median_is_a_reading():
    w = summarize_cluster_window(zip(_times(NOW, 3), (44.0, 46.0, 45.0), strict=True))
    assert w is not None and w.target_l == 45.0


def test_cluster_window_dedupes_the_two_phone_paths():
    samples = []
    for at, litres in zip(_times(NOW, 3), (44.5, 44.6, 44.7), strict=True):
        samples += [(at, litres), (at + timedelta(milliseconds=2), litres)]
    w = summarize_cluster_window(samples)
    assert w is not None and w.n_readings == 3 and w.rejection is None


def test_cluster_window_thin_below_three():
    assert CLUSTER_MIN_READINGS == 3
    w = summarize_cluster_window(zip(_times(NOW, 2), (44.5, 44.6), strict=True))
    assert w is not None and w.rejection == "thin"


def test_cluster_window_constant_value_is_not_rejected():
    """The cluster is damped: a steady value is normal, not a replay."""
    w = summarize_cluster_window(zip(_times(NOW, 10), [44.5] * 10, strict=True))
    assert w is not None and w.rejection is None and w.target_l == 44.5


def test_cluster_window_empty_is_none():
    assert summarize_cluster_window([]) is None


def test_snap_to_cluster_moves_the_estimate_in_litres():
    u = snap_to_cluster(
        cluster_l=44.5, tank_capacity_l=TANK_L, current_estimate_l=ESTIMATE_L, when=NOW
    )
    assert u is not None
    assert u.liters == 44.5
    assert "cluster_44.50L" in u.reason


def test_snap_to_cluster_dead_band():
    assert snap_to_cluster(
        cluster_l=44.5, tank_capacity_l=TANK_L, current_estimate_l=45.0, when=NOW
    ) is None


def test_snap_to_cluster_subtracts_later_burn_and_clamps():
    u = snap_to_cluster(
        cluster_l=44.5, tank_capacity_l=TANK_L, current_estimate_l=60.0,
        when=NOW, later_burn_l=2.0,
    )
    assert u is not None and u.liters == pytest.approx(42.5)
    over = snap_to_cluster(
        cluster_l=80.0, tank_capacity_l=TANK_L, current_estimate_l=None, when=NOW
    )
    assert over is not None and over.liters == TANK_L


def test_display_fresh_cluster_wins():
    d = fuel_display(
        cluster_l=44.5, cluster_at=NOW - timedelta(minutes=2),
        estimate_l=53.1, estimate_at=NOW - timedelta(minutes=1), now=NOW,
    )
    assert (d.liters, d.source) == (44.5, "cluster")


def test_display_stale_cluster_loses_to_a_newer_estimate():
    d = fuel_display(
        cluster_l=44.5, cluster_at=NOW - timedelta(seconds=CLUSTER_FRESH_S + 1),
        estimate_l=43.9, estimate_at=NOW - timedelta(minutes=5), now=NOW,
    )
    assert (d.liters, d.source) == (43.9, "estimate")


def test_display_stale_cluster_still_wins_when_nothing_moved_since():
    """Parked overnight, snap inside its dead-band: the estimate's last
    update predates the cluster reading, so the dash figure stands."""
    d = fuel_display(
        cluster_l=44.5, cluster_at=NOW - timedelta(hours=10),
        estimate_l=45.2, estimate_at=NOW - timedelta(hours=11), now=NOW,
    )
    assert d.source == "cluster"
    assert d.at == NOW - timedelta(hours=10)


def test_display_estimate_only_and_nothing():
    d = fuel_display(cluster_l=None, cluster_at=None, estimate_l=53.1,
                     estimate_at=NOW, now=NOW)
    assert d.source == "estimate"
    d = fuel_display(cluster_l=None, cluster_at=None, estimate_l=None,
                     estimate_at=None, now=NOW)
    assert (d.liters, d.source, d.at) == (None, None, None)


def test_fillup_check_signs():
    c = fillup_check(is_full=True, pumped_l=29.0, tank_capacity_l=TANK_L,
                     estimate_before_l=53.1, cluster_before_l=44.5)
    assert c.implied_before_l == pytest.approx(44.8)
    assert c.estimate_err_l == pytest.approx(8.3)   # estimate read high
    assert c.cluster_err_l == pytest.approx(-0.3)
    partial = fillup_check(is_full=False, pumped_l=10.0, tank_capacity_l=TANK_L,
                           estimate_before_l=53.1, cluster_before_l=None)
    assert partial.implied_before_l is None and partial.estimate_err_l is None


# ── Through the worker and the API ──────────────────────────────────

pytestmark_pg = pytest.mark.skipif(
    not _has_pg_env(), reason="no test Postgres configured"
)


class _Fx:
    def __init__(self) -> None:
        self.slug = f"apitest-cluster-{uuid4().hex[:8]}"
        self.pool = None

    async def __aenter__(self):
        import asyncpg

        self.pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
        return self

    async def __aexit__(self, *exc):
        try:
            async with self.pool.acquire() as conn:
                await conn.execute("DELETE FROM vehicles WHERE slug = $1", self.slug)
        finally:
            await self.pool.close()

    async def vehicle(self, conn, *, estimate_l, updated_at=None):
        return await conn.fetchval(
            """
            INSERT INTO vehicles (slug, name, tank_capacity_l,
                                  fuel_level_calibration_pct, fuel_level_empty_pct,
                                  fuel_level_estimate_l, fuel_level_estimate_updated_at,
                                  fuel_unit)
            VALUES ($1, 'cluster fixture', $2, $3, $4, $5, $6, 1)
            RETURNING id
            """,
            self.slug, TANK_L, CAL_PCT, EMPTY_PCT, estimate_l, updated_at,
        )


async def _seed(conn, vid, metric, times, values, source="phone_batch"):
    await conn.executemany(
        "INSERT INTO pid_readings (time, vehicle_id, metric, value_num, source) "
        "VALUES ($1, $2, $3, $4, $5)",
        [(t, vid, metric, v, source) for t, v in zip(times, values, strict=True)],
    )


async def _estimate(conn, vid) -> float:
    return float(await conn.fetchval(
        "SELECT fuel_level_estimate_l FROM vehicles WHERE id = $1", vid))


@pytestmark_pg
async def test_snap_targets_cluster_median_over_the_pct_pid(caplog):
    from pitstop.workers.fuel_state_worker import snap_pass

    caplog.set_level(logging.INFO, logger="pitstop.workers.fuel_state_worker")
    async with _Fx() as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=20)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=ESTIMATE_L)
            await _seed(conn, vid, "fuel_level", _times(anchor, 14), PCT_DRIVE_RAW)
            await _seed(conn, vid, CLUSTER_METRIC, _times(anchor, 14), CLUSTER_DRIVE_L)
        assert await snap_pass(fx.pool) == 1
        async with fx.pool.acquire() as conn:
            assert await _estimate(conn, vid) == pytest.approx(CLUSTER_MEDIAN_L)
        assert f"snap vehicle={vid} source=cluster" in caplog.text


@pytestmark_pg
async def test_snap_falls_back_to_pct_when_cluster_is_thin(caplog):
    from pitstop.services.fuel_state import sensor_pct_to_liters, summarize_snap_window
    from pitstop.workers.fuel_state_worker import snap_pass

    caplog.set_level(logging.INFO, logger="pitstop.workers.fuel_state_worker")
    async with _Fx() as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=20)
        times = _times(anchor, 14)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=10.0)
            await _seed(conn, vid, "fuel_level", times, PCT_DRIVE_RAW)
            await _seed(conn, vid, CLUSTER_METRIC, times[-2:], (44.5, 44.5))
        assert await snap_pass(fx.pool) == 1
        w = summarize_snap_window(zip(times, PCT_DRIVE_RAW, strict=True))
        assert w is not None
        expect = sensor_pct_to_liters(w.target_pct, TANK_L, CAL_PCT, EMPTY_PCT)
        async with fx.pool.acquire() as conn:
            assert await _estimate(conn, vid) == pytest.approx(expect, abs=0.01)
        assert f"snap vehicle={vid} source=pct" in caplog.text


@pytestmark_pg
async def test_dongle_cluster_rows_never_feed_the_snap():
    from pitstop.workers.fuel_state_worker import snap_pass

    async with _Fx() as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=20)
        times = _times(anchor, 14)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=44.6)
            await _seed(conn, vid, CLUSTER_METRIC, times, CLUSTER_DRIVE_L)
            # A replayed dongle snapshot after the park, far off.
            await _seed(conn, vid, CLUSTER_METRIC,
                        [anchor + timedelta(seconds=40 + i) for i in range(5)],
                        [60.0] * 5, source="wican")
        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            assert await _estimate(conn, vid) == pytest.approx(44.6)


@pytestmark_pg
async def test_vehicle_api_exposes_cluster_and_display():
    from pitstop.api.vehicles import _attach_fuel_display

    async with _Fx() as fx:
        now = datetime.now(UTC)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=ESTIMATE_L,
                                   updated_at=now - timedelta(hours=1))
            await _seed(conn, vid, CLUSTER_METRIC,
                        [now - timedelta(minutes=3)], [44.5])
            # Newer, but from the dongle: ignored.
            await _seed(conn, vid, CLUSTER_METRIC,
                        [now - timedelta(minutes=1)], [61.0], source="wican")
            v = {"id": vid, "tank_capacity_l": TANK_L,
                 "fuel_level_estimate_l": ESTIMATE_L,
                 "fuel_level_estimate_updated_at": now - timedelta(hours=1)}
            await _attach_fuel_display(conn, [v])
        assert v["fuel_cluster_l"] == 44.5
        assert v["fuel_display_l"] == 44.5
        assert v["fuel_display_source"] == "cluster"
        assert v["fuel_display_at"] == v["fuel_cluster_at"]


@pytestmark_pg
async def test_vehicle_api_without_cluster_shows_the_estimate():
    from pitstop.api.vehicles import _attach_fuel_display

    async with _Fx() as fx:
        at = datetime.now(UTC) - timedelta(hours=1)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=ESTIMATE_L, updated_at=at)
            v = {"id": vid, "tank_capacity_l": TANK_L,
                 "fuel_level_estimate_l": ESTIMATE_L,
                 "fuel_level_estimate_updated_at": at}
            await _attach_fuel_display(conn, [v])
        assert v["fuel_cluster_l"] is None and v["fuel_cluster_at"] is None
        assert (v["fuel_display_l"], v["fuel_display_source"]) == (ESTIMATE_L, "estimate")


@pytestmark_pg
async def test_fillup_logs_the_source_comparison(caplog):
    from pitstop.api.fillups import _apply_fillup_to_fuel_estimate
    from pitstop.schemas import FillupCreate

    caplog.set_level(logging.INFO, logger="pitstop.api.fillups")
    async with _Fx() as fx:
        fill_at = datetime.now(UTC) - timedelta(minutes=5)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=ESTIMATE_L)
            await _seed(conn, vid, CLUSTER_METRIC,
                        [fill_at - timedelta(minutes=8)], [44.5])
        body = FillupCreate(vehicle_id=vid, fillup_date=fill_at, odo=80000,
                            fuel_volume=29.0 / 3.78541, is_full=True)
        await _apply_fillup_to_fuel_estimate(fx.pool, body)
        line = next(r.getMessage() for r in caplog.records
                    if r.getMessage().startswith("fuel-fillup check"))
        assert "implied_before_l=44.80" in line
        assert "estimate_err_l=8.30" in line
        assert "cluster_before_l=44.50 cluster_err_l=-0.30" in line
        async with fx.pool.acquire() as conn:
            assert await _estimate(conn, vid) == pytest.approx(TANK_L)
