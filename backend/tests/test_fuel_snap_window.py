"""Regression cover for the 2026-09-02 frozen-frame snap and the drop cap
that then pinned the gauge at 79 % for two days (ADR-025).

Two faults that combined:

*   ``snap_pass`` took the trailing five ``fuel_level`` rows of any
    source. After a park at home the WiCAN joins WiFi and republishes its
    cached AutoPID frame at 1 Hz — the same value every second, every
    metric constant — and ingest stamps those rows at receipt, so the
    trailing five rows WERE that frame. Raw 76.47 % went in, 60.97 L came
    out, +23.4 L over the phone's reading from minutes earlier.

*   A 25 %-of-tank cap on downward snaps refused the 31.8 L correction
    back and applied nothing at all, so the estimate could not converge
    however long the car sat: 565 CAPPED warnings across three episodes,
    zero corrections.

The fix: the window is phone-sourced only, de-duplicated by proximity,
targets the 75th percentile of the last 15 minutes of driving after the
latest fillup, rejects thin or single-valued windows, subtracts the
applied burn of any trip that ended after the window, and applies large
moves loudly instead of refusing them. The numbers below are the real
ones from those two evenings.
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
    SNAP_MIN_DISTINCT_VALUES,
    SNAP_MIN_READINGS,
    SNAP_SOURCES,
    sensor_pct_to_liters,
    summarize_snap_window,
)

from .conftest import _dsn, _has_pg_env  # noqa: E402

# The Pilot, as configured in production.
TANK_L = 73.8
CAL_PCT = 91.373
EMPTY_PCT = 5.671
EPA_MPG = 22.0

# The last 15 minutes of phone readings before the 2026-09-04 23:05:45Z
# park, de-duplicated: 28 readings, 30 s apart except the last (90 s).
# P75 is raw 50.196 -> 38.34 L. The trailing five ROWS of the same data
# ({25.88 x2, 36.47, 40.39 x2}) gave the old code a 26.52 L target.
SEPT4_TAIL_RAW = (
    50.1961, 50.1961, 50.1961, 50.1961, 50.1961, 46.2750, 47.0588, 49.0200,
    49.0196, 44.7059, 44.7060, 44.7059, 49.0200, 50.1961, 48.2353, 52.9412,
    58.8235, 25.8824, 63.5294, 49.0196, 45.4902, 47.0588, 47.0588, 47.0590,
    43.1373, 25.8824, 36.4706, 40.3922,
)
SEPT4_TAIL_P75 = 50.1961
SEPT4_TAIL_L = 38.34
STUCK_ESTIMATE_L = 58.2953   # what the gauge showed as 79 %

# The 2026-09-02 19:15 drive as the phone saw it (median ~51.4), and the
# frame the dongle replayed 148 times after the park. Sorted, the 21st of
# 28 values is 53.7 -> 41.36 L; the first twelve alone give 54.1 -> 41.70 L.
SEPT2_DRIVE_RAW = (
    54.1, 50.2, 44.7, 62.0, 57.3, 46.3, 51.4, 53.7, 51.4, 43.1, 64.7, 52.9,
    49.0, 55.3, 47.1, 51.8, 48.2, 56.5, 50.2, 45.5, 53.3, 49.8, 58.8, 47.8,
    52.5, 50.6, 44.3, 51.0,
)
SEPT2_P75 = 53.7
SEPT2_L = 41.36
SEPT2_FIRST12_L = 41.70
FROZEN_WICAN_RAW = 76.47
FROZEN_WICAN_L = 60.97       # what the snap wrote from it
PRE_SNAP_ESTIMATE_L = 37.61  # what the estimate was, and roughly the truth


def _liters(raw: float) -> float:
    return sensor_pct_to_liters(raw, TANK_L, CAL_PCT, EMPTY_PCT)


def _times(anchor: datetime, n: int, step_s: float = 30.0) -> list[datetime]:
    return [anchor - timedelta(seconds=step_s * (n - 1 - i)) for i in range(n)]


# ── The pure window helper ──────────────────────────────────────────

NOW = datetime(2026, 9, 4, 23, 5, 45, 36000, tzinfo=UTC)


def test_window_dedupes_the_bridge_and_phone_batch_pair():
    """The two phone paths carry the same physical reading 1-2 ms apart.
    Five readings arriving as ten rows are five readings, and the higher
    precision rendering wins."""
    samples = []
    for at, raw in zip(_times(NOW, 5), (40.0, 42.0, 44.0, 46.0, 48.0), strict=True):
        samples.append((at, raw - 0.0002))
        samples.append((at + timedelta(milliseconds=2), raw))
    w = summarize_snap_window(samples)
    assert w is not None
    assert w.n_readings == 5
    assert w.target_pct == 46.0
    # The anchor is the newest row, whichever path delivered it.
    assert w.anchor == max(at for at, _ in samples)


def test_window_dedupes_a_pair_that_straddles_a_second():
    """Truncating to the wall-clock second would split .999 / .001."""
    late = datetime(2026, 9, 4, 23, 5, 45, 999000, tzinfo=UTC)
    samples = [(late, 50.196), (late + timedelta(milliseconds=2), 50.1961)]
    w = summarize_snap_window(samples)
    assert w is not None
    assert w.n_readings == 1


def test_window_rejects_a_replayed_frame():
    """148 rows of one value at 1 Hz is the WiCAN's post-park replay."""
    w = summarize_snap_window(
        (at, FROZEN_WICAN_RAW) for at in _times(NOW, 148, step_s=1.0)
    )
    assert w is not None
    assert w.rejection == "frozen"
    assert w.n_values == 1


def test_window_treats_precision_variants_as_one_value():
    """bridge rounds to 3 decimals, phone_batch does not; a stuck sender
    delivered both ways is still one value, still frozen."""
    variants = (50.1961, 50.196, 50.20)
    w = summarize_snap_window(
        (at, variants[i % 3]) for i, at in enumerate(_times(NOW, 12))
    )
    assert w is not None
    assert w.n_values == 1
    assert w.rejection == "frozen"


def test_window_rejects_a_short_errand():
    w = summarize_snap_window(
        zip(_times(NOW, 6), (50.2, 48.6, 51.4, 47.1, 49.8, 50.6), strict=True)
    )
    assert w is not None
    assert w.rejection == "thin"


def test_window_gate_boundaries():
    live = [40.0 + (i % SNAP_MIN_DISTINCT_VALUES) for i in range(SNAP_MIN_READINGS)]
    ok = summarize_snap_window(zip(_times(NOW, SNAP_MIN_READINGS), live, strict=True))
    assert ok is not None and ok.rejection is None
    one_short = summarize_snap_window(
        zip(_times(NOW, SNAP_MIN_READINGS - 1), live[:-1], strict=True)
    )
    assert one_short is not None and one_short.rejection == "thin"
    two_valued = [40.0 + (i % 2) for i in range(SNAP_MIN_READINGS)]
    stuck = summarize_snap_window(
        zip(_times(NOW, SNAP_MIN_READINGS), two_valued, strict=True)
    )
    assert stuck is not None and stuck.rejection == "frozen"


def test_window_empty_is_none():
    assert summarize_snap_window([]) is None


def test_window_target_is_the_p75_of_the_real_sept_4_drive():
    """The window that was CAPPED 143 times: its P75 lands 3 L from the
    trip fuel accounting, where the trailing-five median sat 15 L low."""
    times = _times(NOW - timedelta(seconds=90), 27) + [NOW]
    samples = list(zip(times, SEPT4_TAIL_RAW, strict=True))
    # Bridge duplicates on every other reading, 2 ms later, 3-decimal.
    samples += [
        (at + timedelta(milliseconds=2), round(raw, 3))
        for at, raw in samples[::2]
    ]
    w = summarize_snap_window(samples)
    assert w is not None
    assert w.rejection is None
    assert w.n_readings == 28
    assert w.target_pct == pytest.approx(SEPT4_TAIL_P75, abs=1e-5)
    assert _liters(w.target_pct) == pytest.approx(SEPT4_TAIL_L, abs=0.02)
    assert _liters(w.target_pct) > 35.0, "the old target was 26.52 L"


def test_window_percentile_is_the_upper_quartile():
    """Pins the 0.75, not just 'above the median': the 21st of 28."""
    w = summarize_snap_window(zip(_times(NOW, 28), SEPT2_DRIVE_RAW, strict=True))
    assert w is not None
    assert w.target_pct == SEPT2_P75
    assert w.target_pct == sorted(SEPT2_DRIVE_RAW)[20]
    assert _liters(w.target_pct) == pytest.approx(SEPT2_L, abs=0.01)


def test_snap_sources_are_the_phone_paths():
    """Widening this to the dongle's feed re-opens the replay bug unless
    the frozen gate is applied per source run — see ADR-025."""
    assert set(SNAP_SOURCES) == {"bridge", "phone_batch"}
    assert "wican" not in SNAP_SOURCES


# ── End-to-end through the real worker ──────────────────────────────

pytestmark_pg = pytest.mark.skipif(
    not _has_pg_env(), reason="no test Postgres configured"
)


async def _seed(conn, vehicle_id, times, values, *, source="phone_batch",
                dupes=True):
    """Insert readings at ``times``; with ``dupes`` every other one is also
    written 2 ms later by the other phone path, as in production."""
    for i, (at, raw) in enumerate(zip(times, values, strict=True)):
        await conn.execute(
            """
            INSERT INTO pid_readings (time, vehicle_id, metric, value_num, source)
            VALUES ($1, $2, 'fuel_level', $3, $4)
            """,
            at, vehicle_id, raw, source,
        )
        if dupes and i % 2 == 0:
            other = "bridge" if source == "phone_batch" else "phone_batch"
            await conn.execute(
                """
                INSERT INTO pid_readings (time, vehicle_id, metric, value_num, source)
                VALUES ($1, $2, 'fuel_level', $3, $4)
                """,
                at + timedelta(milliseconds=2), vehicle_id, round(raw, 3), other,
            )


async def _trip(conn, vehicle_id, started_at, ended_at, *, distance_km,
                fuel_used_l, gps_only=False, applied=False):
    return await conn.fetchval(
        """
        INSERT INTO trips (vehicle_id, started_at, ended_at, distance_km,
                           fuel_used_l, source, gps_only, fuel_applied_at)
        VALUES ($1, $2, $3, $4, $5, 'phone_batch', $6,
                CASE WHEN $7 THEN now() ELSE NULL END)
        RETURNING id
        """,
        vehicle_id, started_at, ended_at, distance_km, fuel_used_l,
        gps_only, applied,
    )


async def _fillup(conn, vehicle_id, at, *, liters):
    await conn.execute(
        """
        INSERT INTO fillups (vehicle_id, fillup_date, odo, fuel_volume, is_full)
        VALUES ($1, $2, 79326, $3, true)
        """,
        vehicle_id, at, liters / 3.78541,
    )


async def _estimate(conn, vehicle_id):
    row = await conn.fetchrow(
        "SELECT fuel_level_estimate_l AS l, fuel_level_estimate_updated_at AS at "
        "FROM vehicles WHERE id = $1",
        vehicle_id,
    )
    return (float(row["l"]) if row["l"] is not None else None), row["at"]


class _Fixture:
    """One asyncpg pool per test and every vehicle it creates deleted on
    exit, whatever happened in between."""

    def __init__(self, tag: str):
        self.prefix = f"apitest-snapwin-{tag}-{uuid4().hex[:8]}"
        self.slugs: list[str] = []
        self.pool = None

    async def __aenter__(self):
        import asyncpg

        self.pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
        return self

    async def __aexit__(self, *exc):
        try:
            async with self.pool.acquire() as conn:
                await conn.execute(
                    "DELETE FROM vehicles WHERE slug = ANY($1::text[])", self.slugs
                )
        finally:
            await self.pool.close()

    async def vehicle(self, conn, suffix="", *, estimate_l, updated_at=None,
                      epa=None):
        slug = self.prefix + suffix
        self.slugs.append(slug)
        return await conn.fetchval(
            """
            INSERT INTO vehicles (slug, name, tank_capacity_l,
                                  fuel_level_calibration_pct, fuel_level_empty_pct,
                                  fuel_level_estimate_l,
                                  fuel_level_estimate_updated_at, epa_mpg_combined)
            VALUES ($1, 'snap-window fixture', $2, $3, $4, $5, $6, $7)
            RETURNING id
            """,
            slug, TANK_L, CAL_PCT, EMPTY_PCT, estimate_l, updated_at, epa,
        )


def _worker_logs(caplog):
    caplog.set_level(logging.INFO, logger="pitstop.workers.fuel_state_worker")


@pytestmark_pg
async def test_frozen_wican_replay_after_park_is_ignored(caplog):
    """The 2026-09-02 evening, end to end. The phone's drive window wins;
    the dongle's 148-row replay after the park never enters it."""
    from pitstop.workers.fuel_state_worker import snap_pass

    _worker_logs(caplog)
    async with _Fixture("replay") as fx:
        started = datetime.now(UTC) - timedelta(minutes=40)
        ended = started + timedelta(minutes=14)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=PRE_SNAP_ESTIMATE_L)
            times = _times(ended - timedelta(seconds=30), len(SEPT2_DRIVE_RAW))
            await _seed(conn, vid, times, SEPT2_DRIVE_RAW)
            await _trip(conn, vid, started, ended, distance_km=5.28,
                        fuel_used_l=1.07, applied=True)
            # The dongle comes home on WiFi 35 s after key-off and replays
            # its cached frame at 1 Hz for two and a half minutes.
            burst = [ended + timedelta(seconds=35 + i) for i in range(148)]
            await _seed(conn, vid, burst, [FROZEN_WICAN_RAW] * 148,
                        source="wican", dupes=False)

        assert await snap_pass(fx.pool) >= 1

        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(SEPT2_L, abs=0.05)
        assert liters != pytest.approx(FROZEN_WICAN_L, abs=0.5), (
            "snapped to the dongle's replayed frame"
        )
        # The reading stands for the end of the drive that produced it.
        assert at == ended
        assert f"snap LARGE vehicle={vid}" not in caplog.text


@pytestmark_pg
async def test_stuck_estimate_self_heals_on_the_first_cycle(caplog):
    """Production as it stood on 2026-09-04: 58.30 L, updated by the stub
    trip's EPA decrement, the phone window CAPPED every minute. One cycle
    of the fixed worker lands it at 38.34 L, loudly; the next is quiet."""
    from pitstop.workers.fuel_state_worker import snap_pass

    _worker_logs(caplog)
    async with _Fixture("stuck") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=30)
        stub_started = anchor - timedelta(seconds=19)
        stub_ended = anchor + timedelta(seconds=44)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=STUCK_ESTIMATE_L,
                                   updated_at=stub_ended, epa=EPA_MPG)
            times = _times(anchor - timedelta(seconds=90), 27) + [anchor]
            await _seed(conn, vid, times, SEPT4_TAIL_RAW)
            await _trip(conn, vid, stub_started, stub_ended, distance_km=0.053,
                        fuel_used_l=0.0, applied=True)

        assert await snap_pass(fx.pool) >= 1
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(SEPT4_TAIL_L, abs=0.05)
        assert at == stub_ended
        assert f"snap LARGE vehicle={vid} -19.9" in caplog.text, (
            "a 20 L move must be logged, with its size"
        )

        # Same window, nothing new: the dead-band keeps it still.
        caplog.clear()
        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            again, _ = await _estimate(conn, vid)
        assert again == pytest.approx(liters, abs=1e-6)
        assert f"snap vehicle={vid}" not in caplog.text


@pytestmark_pg
async def test_a_stub_trip_after_the_window_does_not_block_the_heal():
    """The same evening had the 0.05 km stub STARTED after the last phone
    reading: it holds no readings, so it is not the containing trip, and
    its 0.006 L EPA decrement wrote the estimate later than the window's
    instant. A timestamp guard would have refused the 20 L correction to
    protect that 0.006 L. Subtracting it instead lands exactly right."""
    from pitstop.workers.fuel_state_worker import snap_pass

    async with _Fixture("stub") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=30)
        stub_started = anchor + timedelta(seconds=30)
        stub_ended = anchor + timedelta(seconds=60)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=STUCK_ESTIMATE_L,
                                   updated_at=stub_ended, epa=EPA_MPG)
            times = _times(anchor - timedelta(seconds=90), 27) + [anchor]
            await _seed(conn, vid, times, SEPT4_TAIL_RAW)
            await _trip(conn, vid, stub_started, stub_ended, distance_km=0.053,
                        fuel_used_l=0.0, applied=True)

        assert await snap_pass(fx.pool) >= 1
        epa_l = 0.053 / 1.609 / EPA_MPG * 3.785
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(SEPT4_TAIL_L - epa_l, abs=0.05)
        assert at == anchor


@pytestmark_pg
async def test_single_value_phone_window_is_rejected_once_per_window(caplog):
    """A stuck sender on the phone path is refused too. The warning fires
    once per window, not once per cycle — and again for the next window,
    not once per process."""
    from pitstop.workers.fuel_state_worker import snap_pass

    _worker_logs(caplog)
    async with _Fixture("stuck-sender") as fx:
        first = datetime.now(UTC) - timedelta(minutes=30)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=PRE_SNAP_ESTIMATE_L)
            await _seed(conn, vid, _times(first, 20), [51.76] * 20,
                        source="bridge", dupes=False)

        await snap_pass(fx.pool)
        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(PRE_SNAP_ESTIMATE_L)
        assert at is None
        rejected = f"snap REJECTED vehicle={vid}"
        assert caplog.text.count(rejected) == 1

        # A later drive, stuck again: a fresh window, a fresh warning.
        second = datetime.now(UTC) - timedelta(minutes=12)
        async with fx.pool.acquire() as conn:
            await _seed(conn, vid, _times(second, 20), [47.06] * 20,
                        source="bridge", dupes=False)
        await snap_pass(fx.pool)
        await snap_pass(fx.pool)
        assert caplog.text.count(rejected) == 2


@pytestmark_pg
async def test_thin_window_waits_for_a_longer_drive(caplog):
    from pitstop.workers.fuel_state_worker import snap_pass

    _worker_logs(caplog)
    async with _Fixture("thin") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=30)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=PRE_SNAP_ESTIMATE_L)
            await _seed(conn, vid, _times(anchor, 6), SEPT2_DRIVE_RAW[:6])

        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(PRE_SNAP_ESTIMATE_L)
        assert at is None
        warnings = [
            r.getMessage() for r in caplog.records if r.levelno >= logging.WARNING
        ]
        assert not any(f"vehicle={vid}" in m for m in warnings)


@pytestmark_pg
async def test_duplicate_rows_across_phone_paths_count_once():
    """Six readings delivered twice are six readings (thin); twelve
    delivered twice are twelve (a drive)."""
    from pitstop.workers.fuel_state_worker import snap_pass

    async with _Fixture("dupes") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=30)
        async with fx.pool.acquire() as conn:
            six = await fx.vehicle(conn, "-six", estimate_l=PRE_SNAP_ESTIMATE_L)
            twelve = await fx.vehicle(conn, "-twelve", estimate_l=PRE_SNAP_ESTIMATE_L)
            for vid, n in ((six, 6), (twelve, 12)):
                times = _times(anchor, n)
                await _seed(conn, vid, times, SEPT2_DRIVE_RAW[:n], dupes=False)
                await _seed(conn, vid, [t + timedelta(milliseconds=2) for t in times],
                            SEPT2_DRIVE_RAW[:n], source="bridge", dupes=False)

        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            six_l, _ = await _estimate(conn, six)
            twelve_l, _ = await _estimate(conn, twelve)
        assert six_l == pytest.approx(PRE_SNAP_ESTIMATE_L)
        assert twelve_l == pytest.approx(SEPT2_FIRST12_L, abs=0.05)


@pytestmark_pg
async def test_a_later_phone_less_drive_is_subtracted_not_overwritten():
    """A GPS-only drive after the park is charged by the EPA fallback. The
    window must neither snap the estimate back up over it nor be blocked
    by it: re-evaluated, it lands where the decrement left things."""
    from pitstop.workers.fuel_state_worker import run_cycle

    async with _Fixture("later") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=60)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=30.0, epa=EPA_MPG)
            await _seed(conn, vid, _times(anchor, len(SEPT2_DRIVE_RAW)),
                        SEPT2_DRIVE_RAW)
            # A later drive with GPS only — no samples, no fuel figure.
            await _trip(conn, vid, anchor + timedelta(minutes=10),
                        anchor + timedelta(minutes=40), distance_km=20.0,
                        fuel_used_l=None, gps_only=True)

        await run_cycle(fx.pool)
        epa_l = 20.0 / 1.609 / EPA_MPG * 3.785
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(SEPT2_L - epa_l, abs=0.05)
        assert at == anchor + timedelta(minutes=40)

        await run_cycle(fx.pool)
        async with fx.pool.acquire() as conn:
            again, at_again = await _estimate(conn, vid)
        assert again == pytest.approx(liters, abs=1e-6), (
            "the stale window re-snapped over the EPA decrement"
        )
        assert at_again == at


@pytestmark_pg
async def test_a_watchdog_sealed_trip_is_absorbed_by_its_own_window():
    """ADR-017's BLE-lost watchdog can stamp ended_at minutes after the
    last frame. The window still stands for that trip: it is stamped
    applied and the gauge's "as of" is the end of the drive."""
    from pitstop.workers.fuel_state_worker import decrement_pass, snap_pass

    async with _Fixture("watchdog") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=30)
        started = anchor - timedelta(minutes=14)
        ended = anchor + timedelta(minutes=3)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=30.0)
            await _seed(conn, vid, _times(anchor, len(SEPT2_DRIVE_RAW)),
                        SEPT2_DRIVE_RAW)
            trip = await _trip(conn, vid, started, ended, distance_km=5.3,
                               fuel_used_l=2.0)

        assert await snap_pass(fx.pool) >= 1
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
            applied = await conn.fetchval(
                "SELECT fuel_applied_at FROM trips WHERE id = $1", trip
            )
        assert liters == pytest.approx(SEPT2_L, abs=0.05)
        assert at == ended
        assert applied is not None

        await decrement_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            after, _ = await _estimate(conn, vid)
        assert after == pytest.approx(liters, abs=1e-6), (
            "the absorbed trip was charged a second time"
        )


@pytestmark_pg
async def test_wican_rows_inside_a_phone_drive_never_enter_the_window():
    """On 2026-09-01 the dongle replayed 179 rows of one value while the
    phone was still driving. They land in pid_readings; they do not move
    the target."""
    from pitstop.workers.fuel_state_worker import snap_pass

    async with _Fixture("interleaved") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=30)
        async with fx.pool.acquire() as conn:
            clean = await fx.vehicle(conn, "-clean", estimate_l=30.0)
            noisy = await fx.vehicle(conn, "-noisy", estimate_l=30.0)
            for vid in (clean, noisy):
                await _seed(conn, vid, _times(anchor, len(SEPT2_DRIVE_RAW)),
                            SEPT2_DRIVE_RAW)
            # 1 Hz, offset half a second so no row shares a primary key
            # (vehicle, metric, time) with a phone reading.
            burst = [
                anchor - timedelta(seconds=400 - i, milliseconds=500)
                for i in range(179)
            ]
            await _seed(conn, noisy, burst, [51.76] * 179, source="wican",
                        dupes=False)

        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            clean_l, _ = await _estimate(conn, clean)
            noisy_l, _ = await _estimate(conn, noisy)
        assert clean_l == pytest.approx(SEPT2_L, abs=0.05)
        assert noisy_l == pytest.approx(clean_l, abs=1e-6)


@pytestmark_pg
async def test_window_is_the_last_fifteen_minutes_only():
    """A long drive's earlier, fuller readings must not pull the target
    up. 49 readings at raw 80 end 16 minutes before the anchor; the last
    14 minutes are the Sept 2 drive."""
    from pitstop.workers.fuel_state_worker import snap_pass

    async with _Fixture("bound") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=30)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=30.0)
            early = _times(anchor - timedelta(minutes=16), 49)
            await _seed(conn, vid, early, [80.0] * 49, dupes=False)
            await _seed(conn, vid, _times(anchor, len(SEPT2_DRIVE_RAW)),
                        SEPT2_DRIVE_RAW)

        assert await snap_pass(fx.pool) >= 1
        async with fx.pool.acquire() as conn:
            liters, _ = await _estimate(conn, vid)
        assert liters == pytest.approx(SEPT2_L, abs=0.05)


@pytestmark_pg
async def test_still_driving_does_not_snap(monkeypatch):
    """The quiet gate: readings five minutes old mean the phone is still
    polling. Shrink the gate and the same window snaps."""
    from pitstop.workers import fuel_state_worker

    async with _Fixture("driving") as fx:
        anchor = datetime.now(UTC) - timedelta(minutes=5)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=30.0)
            await _seed(conn, vid, _times(anchor, len(SEPT2_DRIVE_RAW)),
                        SEPT2_DRIVE_RAW)
            trip = await _trip(conn, vid, anchor - timedelta(minutes=14),
                               anchor + timedelta(seconds=5), distance_km=5.3,
                               fuel_used_l=2.0)

        await fuel_state_worker.snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
            applied = await conn.fetchval(
                "SELECT fuel_applied_at FROM trips WHERE id = $1", trip
            )
        assert liters == pytest.approx(30.0)
        assert at is None
        assert applied is None, "nothing may be stamped mid-drive"

        monkeypatch.setattr(fuel_state_worker, "PARKED_QUIET_S", 60.0)
        assert await fuel_state_worker.snap_pass(fx.pool) >= 1
        async with fx.pool.acquire() as conn:
            liters, _ = await _estimate(conn, vid)
        assert liters == pytest.approx(SEPT2_L, abs=0.05)


# ── Fillups ─────────────────────────────────────────────────────────


@pytestmark_pg
async def test_no_snap_until_a_real_trip_follows_the_fillup():
    """Honda's PID 0x2F can sit on the pre-fill reading until the car has
    been driven (2026-06-03: a full tank snapped down to 3.94 L)."""
    from pitstop.workers.fuel_state_worker import snap_pass

    async with _Fixture("fill-quarantine") as fx:
        filled = datetime.now(UTC) - timedelta(minutes=45)
        anchor = datetime.now(UTC) - timedelta(minutes=30)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=TANK_L, updated_at=filled)
            await _fillup(conn, vid, filled, liters=60.0)
            # Readings after the fill, but no trip row yet.
            await _seed(conn, vid, _times(anchor, len(SEPT2_DRIVE_RAW)),
                        SEPT2_DRIVE_RAW)

        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(TANK_L)
        assert at == filled


@pytestmark_pg
async def test_readings_from_before_the_fillup_never_anchor_a_window():
    from pitstop.workers.fuel_state_worker import snap_pass

    async with _Fixture("fill-anchor") as fx:
        filled = datetime.now(UTC) - timedelta(minutes=40)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=TANK_L, updated_at=filled)
            await _fillup(conn, vid, filled, liters=60.0)
            # The drive TO the pump, on a near-empty tank.
            await _seed(conn, vid, _times(filled - timedelta(minutes=2), 28),
                        [18.0 + (i % 5) for i in range(28)])
            # A real drive since, but with no readings uploaded.
            await _trip(conn, vid, filled + timedelta(minutes=2),
                        filled + timedelta(minutes=12), distance_km=5.0,
                        fuel_used_l=None)

        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(TANK_L)
        assert at == filled


@pytestmark_pg
async def test_window_does_not_reach_back_past_the_fillup():
    """Three minutes home from the pump: four post-fill readings. The 22
    near-empty readings from the drive TO the pump sit inside the 15-min
    span and must not pad the window past the thin gate — with them, and
    no cap, a full tank would be snapped to ~15 L."""
    from pitstop.workers.fuel_state_worker import snap_pass

    async with _Fixture("fill-window") as fx:
        filled = datetime.now(UTC) - timedelta(minutes=40)
        anchor = filled + timedelta(minutes=5)
        async with fx.pool.acquire() as conn:
            vid = await fx.vehicle(conn, estimate_l=TANK_L, updated_at=filled)
            await _fillup(conn, vid, filled, liters=60.0)
            await _seed(conn, vid, _times(filled - timedelta(minutes=1), 22),
                        [18.0 + (i % 6) for i in range(22)])
            await _seed(conn, vid, _times(anchor, 4), (86.5, 88.2, 87.1, 89.0))
            await _trip(conn, vid, filled + timedelta(minutes=2), anchor,
                        distance_km=1.6, fuel_used_l=0.3, applied=True)

        await snap_pass(fx.pool)
        async with fx.pool.acquire() as conn:
            liters, at = await _estimate(conn, vid)
        assert liters == pytest.approx(TANK_L)
        assert at == filled
