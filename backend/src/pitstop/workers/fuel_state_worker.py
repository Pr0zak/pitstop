"""Hybrid fuel-level estimator — background worker (phase 2).

Runs alongside trip_deriver / ingest. Two passes per cycle:

1. **decrement_pass** — for each settled trip (ended_at older than the
   settle delay) where fuel_used_l is non-null and fuel_applied_at is
   null, subtract fuel_used_l from the vehicle's running estimate and
   stamp fuel_applied_at. Idempotent via the column flag.

2. **snap_pass** — for each vehicle, once the phone has been quiet for
   PARKED_QUIET_S (no fuel_level rows from a phone source), take the
   last SNAP_WINDOW_S of phone-captured fuel_level readings, reduce them
   with ``services.fuel_state.summarize_snap_window`` (de-duplicated per
   second, 75th percentile, thin/frozen gates) and snap the running
   estimate to that target. This absorbs accumulated drift between
   fillups. It also stamps ``fuel_applied_at`` on every trip that had
   already ended when the window closed, because the reading it just
   snapped to already includes their fuel; without that, a decrement
   arriving later charges the same drive twice.

   Only ``SNAP_SOURCES`` rows feed the window. The WiCAN's own WiFi feed
   is excluded: the dongle republishes its cached AutoPID frame after the
   ECU goes quiet, and ingest stamps those rows at receipt time, so a
   post-park ``source='wican'`` burst is one stale value that looks
   fresh. On 2026-09-02 the trailing-five-rows window was exactly such a
   burst (raw 76.47 %, every other metric constant) and the snap lifted
   the estimate 23.4 L; the 25 %-of-tank drop cap that used to live here
   then refused the correction back for two days. See ADR-025.

The state-machine math lives in services/fuel_state.py as pure functions;
this worker is the DB driver around it.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from uuid import UUID

import asyncpg

from ..services.fuel_state import (
    LARGE_SNAP_FRACTION,
    SNAP_SOURCES,
    SNAP_WINDOW_S,
    EstimateUpdate,
    decrement_on_trip,
    persist_estimate,
    snap_to_sensor,
    summarize_snap_window,
)

if TYPE_CHECKING:
    from ..config import Settings

log = logging.getLogger(__name__)


CYCLE_INTERVAL_S = 60.0
TRIP_SETTLE_S = 120.0  # let trip_deriver finish UPSERTing before we apply
# After this many seconds with no new phone-sourced fuel_level sample we
# treat the vehicle as parked. The phone only publishes while its bridge
# service is polling the ECU, so a sustained silence is a strong "parked"
# signal — and unlike the dongle's feed it cannot be a replay.
PARKED_QUIET_S = 600.0

# Windows rejected as frozen are logged once per anchor, not once per
# 60 s cycle — the old drop cap warned 565 times for three episodes and
# buried the signal. Process-local; a restart re-warns once, which is fine.
_last_rejected_anchor: dict[UUID, datetime] = {}


async def decrement_pass(pool: asyncpg.Pool) -> int:
    """Process all unapplied settled trips. Returns count of trips applied.

    For each trip we (re-)read the vehicle's running estimate inside a
    per-trip transaction that takes ``SELECT ... FOR UPDATE`` on the
    vehicle row. This is what actually prevents:

    1. **Lost updates across multiple trips for one vehicle in a single
       cycle.** The estimate is re-read per trip rather than snapshotted
       once, so two unapplied trips both debit a running total instead of
       the second clobbering the first.
    2. **Clobbering a concurrent ``POST /fillups`` reset.** The row lock
       serialises against the api's reset path.

    Fuel debit preference order:
      - ``fuel_used_l`` (MAF or de-sloshed fuel-level delta) when present.
      - **EPA fallback** (#3): when ``fuel_used_l`` is NULL/0 but
        ``distance_km`` is known and the vehicle has ``epa_mpg_combined``,
        decrement by an EPA-based estimate (LOW confidence) and stamp
        ``fuel_applied_at`` so the gauge keeps moving between snaps.
      - Otherwise terminal-stamp stale NULL trips (``ended_at`` older than
        24 h) so "applied" semantics stay clean and we stop re-scanning
        them forever.
    """
    processed = 0
    async with pool.acquire() as conn:
        # Candidate trips: settled + unapplied. Includes trips with NULL
        # fuel_used_l so the EPA fallback / terminal-stamp paths can run.
        trips = await conn.fetch(
            """
            SELECT t.id, t.vehicle_id, t.fuel_used_l, t.distance_km,
                   t.ended_at
              FROM trips t
             WHERE t.fuel_applied_at IS NULL
               AND t.ended_at IS NOT NULL
               AND t.ended_at < now() - make_interval(secs => $1)
             ORDER BY t.vehicle_id, t.ended_at
            """,
            TRIP_SETTLE_S,
        )
        for row in trips:
            stale = row["ended_at"] < datetime.now(UTC) - timedelta(hours=24)
            async with conn.transaction():
                # Lock the vehicle row + read the freshest estimate inside
                # the txn — this is the real lost-update / fillup-race fix.
                veh = await conn.fetchrow(
                    """
                    SELECT fuel_level_estimate_l, tank_capacity_l,
                           epa_mpg_combined
                      FROM vehicles WHERE id = $1
                      FOR UPDATE
                    """,
                    row["vehicle_id"],
                )
                if veh is None:
                    continue
                current_l = (
                    float(veh["fuel_level_estimate_l"])
                    if veh["fuel_level_estimate_l"] is not None
                    else None
                )

                fuel_used = (
                    float(row["fuel_used_l"])
                    if row["fuel_used_l"] is not None
                    else None
                )
                update = None
                if fuel_used is not None and fuel_used > 0:
                    update = decrement_on_trip(
                        fuel_used_l=fuel_used,
                        current_estimate_l=current_l,
                        when=row["ended_at"],
                    )
                elif (
                    row["distance_km"] is not None
                    and float(row["distance_km"]) > 0
                    and veh["epa_mpg_combined"] is not None
                    and float(veh["epa_mpg_combined"]) > 0
                    and current_l is not None
                ):
                    # EPA fallback (#3): liters = km / 1.609 / mpg * 3.785.
                    epa_l = (
                        float(row["distance_km"])
                        / 1.609
                        / float(veh["epa_mpg_combined"])
                        * 3.785
                    )
                    update = decrement_on_trip(
                        fuel_used_l=epa_l,
                        current_estimate_l=current_l,
                        when=row["ended_at"],
                    )
                    if update is not None:
                        # Re-tag as LOW confidence — it's a model estimate,
                        # not a measured burn.
                        update = EstimateUpdate(
                            liters=update.liters,
                            confidence="LOW",
                            reason=f"trip_epa_decrement_{epa_l:.2f}L",
                            when=update.when,
                        )

                if update is None:
                    # No usable debit. Stamp so we stop re-trying, but only
                    # terminal-stamp NULL-fuel trips once they're stale —
                    # a recent trip might still get a real fuel_used_l from
                    # a later deriver pass, or a fillup may seed the
                    # estimate so a future cycle can apply it.
                    if fuel_used is not None or stale or current_l is not None:
                        await conn.execute(
                            "UPDATE trips SET fuel_applied_at = now() "
                            "WHERE id = $1",
                            row["id"],
                        )
                    continue

                await persist_estimate(conn, row["vehicle_id"], update)
                await conn.execute(
                    "UPDATE trips SET fuel_applied_at = now() WHERE id = $1",
                    row["id"],
                )
            processed += 1
            log.info(
                "fuel-estimate decrement trip=%s vehicle=%s new=%.2fL reason=%s",
                row["id"], row["vehicle_id"], update.liters, update.reason,
            )
    return processed


async def snap_pass(pool: asyncpg.Pool) -> int:
    """For each vehicle whose phone feed has been quiet for PARKED_QUIET_S,
    snap the estimate to the trailing drive window's target. Returns the
    number of vehicles snapped.

    Doesn't depend on engine_events — those can be missing (short drives
    that didn't generate LWT, phone-only captures with no wican_lwt), and
    the dongle's own ``status`` online is MQTT-connect-driven, so it
    records a false engine-on ~30 s after every real key-off.

    Per vehicle, in order:

    1. Fillup quarantine — no snap until a real trip has happened since
       the latest fillup, and no sample from before it is considered.
    2. The anchor is the newest ``SNAP_SOURCES`` fuel_level sample after
       that fillup; it is the quiet-gate clock. The window is every such
       sample in the ``SNAP_WINDOW_S`` before it, bounded below by the
       fillup too. Two statements on purpose: with the anchor bound as a
       parameter the range is an index condition and TimescaleDB excludes
       every other chunk; as a CTE join the planner walked the vehicle's
       whole fuel_level history every cycle.
    3. ``summarize_snap_window`` de-duplicates, takes the P75, and rejects
       thin or frozen windows.
    4. ``when`` = the anchor, or the end of the trip that contains it if
       that is later — the ADR-017 BLE-lost watchdog can seal a trip
       minutes after its last frame, and the clients show ``when`` as the
       gauge's "as of" age.
    5. Trips that ended AFTER ``when`` and have already been charged (a
       phone-less drive debited by the EPA fallback, a sub-kilometre hop)
       have their burn subtracted from the target rather than blocking
       the snap. The reading predates them, so the arithmetic is exact,
       and it makes the snap idempotent: re-evaluating the same window
       after such a trip lands inside the dead-band instead of snapping
       back over the decrement. A timestamp guard was tried first and
       would have refused the 2026-09-04 heal itself had the 0.05 km stub
       that followed the drive been 20 s shorter.
    6. Inside one transaction, re-read the vehicle row ``FOR UPDATE`` (the
       same lock ``decrement_pass`` and the fillup reset take) and bail if
       a fillup landed since the pass began, then apply. A move larger
       than ``LARGE_SNAP_FRACTION`` of the tank is logged as a WARNING but
       still applied — see ADR-025 for why a refusal is worse than a loud
       correction.
    """
    snapped = 0
    async with pool.acquire() as conn:
        vehicles = await conn.fetch(
            """
            SELECT id, tank_capacity_l,
                   COALESCE(fuel_level_calibration_pct, 100.0) AS cal_pct,
                   fuel_level_empty_pct,
                   epa_mpg_combined
              FROM vehicles
             WHERE tank_capacity_l IS NOT NULL
            """
        )
        for v in vehicles:
            # Latest fillup time — snaps must only consider samples taken
            # AFTER any recent fillup. A fillup is authoritative (user
            # observed the tank state); pre-fillup sensor samples are
            # stale and would silently undo the fillup-reset. Discovered
            # 2026-06-03: a 18:17 is_full=true fillup reset estimate to
            # 80 L; snap_pass at 23:14 read pre-fillup near-empty samples
            # and clobbered the estimate down to 3.94 L.
            latest_fillup = await conn.fetchval(
                """
                SELECT max(fillup_date) FROM fillups WHERE vehicle_id = $1
                """,
                v["id"],
            )
            # Quarantine snap until at least one COMPLETED TRIP has
            # happened since the latest fillup. Time-based quarantine
            # (the prior 6h fixed window) isn't enough: Honda's PID 0x2F
            # stays stuck on pre-fillup readings until the car has been
            # driven, and if WiCAN sleeps right after the pump there are
            # no fresh samples to disambiguate. A trip-since-fillup is
            # physical proof the sensor has had a chance to update.
            if latest_fillup is not None:
                trip_since = await conn.fetchval(
                    """
                    SELECT 1 FROM trips
                     WHERE vehicle_id = $1
                       AND ended_at IS NOT NULL
                       AND ended_at > $2
                       AND distance_km > 1.0
                     LIMIT 1
                    """,
                    v["id"], latest_fillup,
                )
                if trip_since is None:
                    continue
            # The anchor: newest phone-captured sample since the fillup.
            anchor = await conn.fetchval(
                """
                SELECT max(time)
                  FROM pid_readings
                 WHERE vehicle_id = $1
                   AND metric = 'fuel_level'
                   AND value_num IS NOT NULL
                   AND source = ANY($2::text[])
                   AND ($3::timestamptz IS NULL OR time > $3)
                """,
                v["id"], list(SNAP_SOURCES), latest_fillup,
            )
            if anchor is None:
                continue
            quiet_for_s = (datetime.now(UTC) - anchor).total_seconds()
            if quiet_for_s < PARKED_QUIET_S:
                continue  # phone still polling, vehicle likely active
            # The window: the drive's last SNAP_WINDOW_S before the anchor,
            # never reaching back past the fillup.
            rows = await conn.fetch(
                """
                SELECT time, value_num
                  FROM pid_readings
                 WHERE vehicle_id = $1
                   AND metric = 'fuel_level'
                   AND value_num IS NOT NULL
                   AND source = ANY($2::text[])
                   AND time >  $3::timestamptz - make_interval(secs => $4)
                   AND time <= $3::timestamptz
                   AND ($5::timestamptz IS NULL OR time > $5)
                 ORDER BY time
                """,
                v["id"], list(SNAP_SOURCES), anchor, SNAP_WINDOW_S, latest_fillup,
            )
            window = summarize_snap_window(
                (r["time"], float(r["value_num"])) for r in rows
            )
            if window is None:
                continue
            if window.rejection is not None:
                if window.rejection == "frozen":
                    if _last_rejected_anchor.get(v["id"]) != anchor:
                        _last_rejected_anchor[v["id"]] = anchor
                        log.warning(
                            "fuel-estimate snap REJECTED vehicle=%s window=%d "
                            "readings / %d distinct value(s) ending %s — "
                            "replayed or stuck frame, not a live sensor",
                            v["id"], window.n_readings, window.n_values,
                            anchor.isoformat(timespec="seconds"),
                        )
                else:
                    log.debug(
                        "fuel-estimate snap skipped vehicle=%s window too thin "
                        "(%d readings ending %s)",
                        v["id"], window.n_readings,
                        anchor.isoformat(timespec="seconds"),
                    )
                continue
            # The instant this reading stands for. A trip that contains the
            # anchor and ended later (watchdog-sealed, or a few seconds of
            # coasting after the last poll) is still reflected in it.
            trip_end = await conn.fetchval(
                """
                SELECT max(ended_at) FROM trips
                 WHERE vehicle_id = $1
                   AND started_at <= $2
                   AND ended_at >= $2
                """,
                v["id"], anchor,
            )
            when = max(anchor, trip_end) if trip_end is not None else anchor
            # Fuel already charged for trips the reading cannot know about.
            # Mirrors decrement_pass: measured burn when present, else the
            # EPA fallback from distance.
            later_burn_l = float(await conn.fetchval(
                """
                SELECT COALESCE(sum(
                         CASE
                           WHEN fuel_used_l IS NOT NULL AND fuel_used_l > 0
                             THEN fuel_used_l
                           WHEN distance_km IS NOT NULL AND distance_km > 0
                                AND $3::float8 IS NOT NULL AND $3::float8 > 0
                             THEN distance_km / 1.609 / $3::float8 * 3.785
                           ELSE 0
                         END), 0)
                  FROM trips
                 WHERE vehicle_id = $1
                   AND ended_at > $2
                   AND fuel_applied_at IS NOT NULL
                """,
                v["id"], when,
                float(v["epa_mpg_combined"]) if v["epa_mpg_combined"] is not None else None,
            ) or 0.0)
            tank_l = float(v["tank_capacity_l"])
            # Persist the snap and retire the trips it absorbed together.
            # The reading already reflects every trip that had ENDED by
            # ``when``, but those trips' own fuel_used_l decrements may
            # still be pending — the phone uploads a drive minutes to
            # hours after it ends, and trip_deriver only then builds the
            # row. Left unstamped, each one gets charged a second time on
            # a later cycle (2026-08-20: 15.38 L snapped, then a 3.13 L
            # decrement for a trip the reading already covered).
            #
            # One transaction so a crash can't do half of it, and the
            # vehicle row is locked so a fillup reset or a decrement
            # landing mid-pass is seen rather than overwritten. See
            # fuel_state.snap_absorbs_trip for the rule this mirrors —
            # the boundary is inclusive.
            async with conn.transaction():
                fresh = await conn.fetchrow(
                    """
                    SELECT fuel_level_estimate_l,
                           (SELECT max(fillup_date) FROM fillups
                             WHERE vehicle_id = $1) AS latest_fillup
                      FROM vehicles WHERE id = $1
                      FOR UPDATE
                    """,
                    v["id"],
                )
                if fresh is None or fresh["latest_fillup"] != latest_fillup:
                    continue  # a fillup landed mid-pass; next cycle re-evaluates
                current_l = (
                    float(fresh["fuel_level_estimate_l"])
                    if fresh["fuel_level_estimate_l"] is not None
                    else None
                )
                update = snap_to_sensor(
                    sensor_pct=window.target_pct,
                    tank_capacity_l=tank_l,
                    calibration_pct=float(v["cal_pct"]),
                    empty_pct=(
                        float(v["fuel_level_empty_pct"])
                        if v["fuel_level_empty_pct"] is not None
                        else None
                    ),
                    current_estimate_l=current_l,
                    when=when,
                    later_burn_l=later_burn_l,
                )
                if update is None:
                    continue
                if current_l is not None:
                    delta_l = update.liters - current_l
                    if abs(delta_l) > tank_l * LARGE_SNAP_FRACTION:
                        log.warning(
                            "fuel-estimate snap LARGE vehicle=%s %+.2fL "
                            "(%.2fL -> %.2fL, target %.1f%% from %d readings / "
                            "%d distinct) — applying%s",
                            v["id"], delta_l, current_l, update.liters,
                            window.target_pct, window.n_readings, window.n_values,
                            "; unlogged fillup?" if delta_l > 0 else "",
                        )
                await persist_estimate(conn, v["id"], update)
                absorbed = await conn.fetchval(
                    """
                    WITH stamped AS (
                        UPDATE trips
                           SET fuel_applied_at = now()
                         WHERE vehicle_id = $1
                           AND fuel_applied_at IS NULL
                           AND ended_at IS NOT NULL
                           AND ended_at <= $2
                        RETURNING 1
                    )
                    SELECT count(*) FROM stamped
                    """,
                    v["id"], update.when,
                )
            snapped += 1
            log.info(
                "fuel-estimate snap vehicle=%s target=%.1f%% liters=%.2fL %s "
                "window=%d readings / %d distinct ending %s "
                "absorbed_pending_trips=%d",
                v["id"], window.target_pct, update.liters, update.reason,
                window.n_readings, window.n_values,
                anchor.isoformat(timespec="seconds"), absorbed or 0,
            )
    return snapped


async def run_cycle(pool: asyncpg.Pool) -> tuple[int, int]:
    """One pass of the worker: ``(decremented, snapped)``.

    **Snap FIRST, decrement second.** The sensor reading is the authority
    for everything that had already happened when it was taken, so letting
    it land first means the trips it covers get stamped applied by
    ``snap_pass`` and never decrement at all. Trips that ended AFTER the
    sample are untouched by the snap and still decrement, in this same
    pass.

    The old order was decrement-then-snap and it lost real consumption:
    on 2026-08-22 seven decrements totalling 3.6 L were applied at
    23:47:54 and a snap in that same second overwrote the estimate,
    discarding every one of them. It also made the stamping in
    ``snap_pass`` dead code — by the time the snap looked, the decrement
    pass had already claimed the trips, which is why
    ``absorbed_pending_trips`` was always 0.

    Extracted from ``run`` so the ordering is reachable from a test; the
    order here IS the fix, and a caller that reverses it reintroduces the
    bug silently.
    """
    snapped = await snap_pass(pool)
    decremented = await decrement_pass(pool)
    return decremented, snapped


async def run(pool: asyncpg.Pool, cfg: "Settings") -> None:
    """Forever loop. Mirrors the trip_deriver/retention pattern."""
    log.info("fuel-state worker started (cycle every %s s)", CYCLE_INTERVAL_S)
    while True:
        try:
            d, snapped_n = await run_cycle(pool)
            if d or snapped_n:
                log.info(
                    "fuel-state cycle: decremented=%d snapped=%d", d, snapped_n
                )
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("fuel-state cycle failed")
        await asyncio.sleep(CYCLE_INTERVAL_S)
