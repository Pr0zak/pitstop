"""Pure state-machine helpers for the hybrid fuel-level estimator.

Three operations mutate the per-vehicle estimate stored in
``vehicles.fuel_level_estimate_l``:

1. ``reset_on_fillup`` — called from ``POST /fillups``. is_full sets the
   estimate to tank capacity; partial fillups add the pumped liters.
2. ``decrement_on_trip`` — called from the fuel_state_worker after a
   trip's ``fuel_used_l`` is computed (MAF integration or de-sloshed
   fuel-level delta). Subtracts; floors at 0.
3. ``snap_to_sensor`` — called from the fuel_state_worker once the phone
   has been quiet long enough that the vehicle is parked, with the target
   ``summarize_snap_window`` extracted from the last minutes of driving.

Each function returns the new estimate + a short ``reason`` string for the
audit trail in logs. None of these touch the DB; the caller is responsible
for persisting via the small ``persist_estimate`` helper.

All math is in liters. The sensor reads percentage (0-100); convert via
``sensor_pct_to_liters`` which accounts for the per-vehicle calibration
ceiling (Honda's PID 0x2F caps below 100% on a physically full tank — see
ADR-014 / migration 0016).
"""

from __future__ import annotations

import math
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime
from typing import Literal
from uuid import UUID

Confidence = Literal["HIGH", "MEDIUM", "LOW"]

# Snap dead-band, as a fraction of tank_capacity_l. 2 % is ~1.5 L on the
# Pilot's 73.8 L tank — tight enough that real drift gets corrected on the
# next park, wide enough not to chase the float sensor's quantisation.
SNAP_THRESHOLD_TANK_FRACTION = 0.02
# ...but never trigger on less than this, so a small tank doesn't end up
# with a hair-trigger.
SNAP_THRESHOLD_MIN_L = 1.0


# ── Snap window ───────────────────────────────────────────────────────
#
# Which rows may feed a snap, and how many of them it takes. Added after
# 2026-09-02, when the snap read one replayed WiCAN frame and lifted the
# estimate 23.4 L; see ADR-025.

# Only rows that carry the ECU's own answer time may feed a snap. The
# phone paths do (bridge v2 rows are stamped with the envelope's capture
# time, phone_batch rows with the recorded one) and they only write a row
# when the ECU actually answered. WiCAN AutoPID rows do neither: ingest
# stamps them at receipt, and the firmware republishes its cached frame
# after the ECU goes quiet — every post-park ``source='wican'`` session in
# the 30 days before 2026-09-05 was a single frozen value per metric.
SNAP_SOURCES: tuple[str, ...] = ("bridge", "phone_batch")
# The snap target is taken from this much driving before the last sample.
SNAP_WINDOW_S = 900.0
# 75th percentile, not the median. The float arm dips into slosh far more
# often than it spikes, so the upper quartile sits closest to the settled
# level — the same statistic ``api/vehicles._smooth_fuel_levels`` uses.
# Measured per driving day against trip fuel accounting on the Pilot:
# P75 mean |err| 1.9 L (max 3.0), median 3.2 L (max 8.4, low 9 days in 10).
SNAP_PERCENTILE = 0.75
# Below this many distinct readings the window is a short errand, not a
# drive; leave the estimate to the trip decrements. ~5 min at the phone's
# 30 s fuel_level cadence.
SNAP_MIN_READINGS = 10
# PID 0x2F is quantised at 100/255 = 0.39 raw and sloshes ±15 % while
# moving, so ten live readings always carry at least three distinct
# values (observed 9-22). A window with fewer is a replayed or stuck
# frame, whatever its source.
SNAP_MIN_DISTINCT_VALUES = 3
# Two samples closer together than this are one physical reading delivered
# twice (bridge live + phone_batch upload, 1-2 ms apart). Merged by
# proximity rather than by truncating to the wall-clock second, so a pair
# that straddles a second boundary still counts once.
SNAP_DEDUPE_S = 0.5
# A snap that moves the estimate by more than this fraction of the tank is
# logged loudly. It is still applied: a refusal never converges when the
# sensor is right, and on 2026-09-04 the old 25 % drop cap refused the
# same correction 565 times in a row while the gauge sat 20 L high.
LARGE_SNAP_FRACTION = 0.25


@dataclass(frozen=True)
class SnapWindow:
    """What the trailing drive window says, before any state is touched."""

    target_pct: float
    n_readings: int
    n_values: int
    anchor: datetime
    rejection: Literal["thin", "frozen"] | None


def summarize_snap_window(
    samples: Iterable[tuple[datetime, float]],
) -> SnapWindow | None:
    """Reduce a trailing window of raw fuel_level samples to one target.

    The caller has already restricted ``samples`` to ``SNAP_SOURCES`` and
    to the ``SNAP_WINDOW_S`` before the newest one. This function:

    * merges samples within ``SNAP_DEDUPE_S`` of each other — bridge
      (live MQTT) and phone_batch (drive upload) carry the same physical
      reading 1-2 ms apart, so counting rows would count every reading
      twice;
    * takes the discrete ``SNAP_PERCENTILE`` (a value the sender actually
      produced, ``percentile_disc`` semantics);
    * flags a window that is too thin to trust or has too few distinct
      values to be a live sensor.

    Returns None for an empty input. A rejected window still reports its
    numbers so the worker can log them.
    """
    ordered = sorted(samples, key=lambda s: s[0])
    if not ordered:
        return None
    readings: list[float] = []
    bucket_at: datetime | None = None
    for when, value in ordered:
        if (
            bucket_at is not None
            and (when - bucket_at).total_seconds() <= SNAP_DEDUPE_S
        ):
            readings[-1] = max(readings[-1], value)
        else:
            readings.append(value)
            bucket_at = when
    anchor = ordered[-1][0]
    values = sorted(readings)
    n = len(values)
    idx = min(max(math.ceil(SNAP_PERCENTILE * n) - 1, 0), n - 1)
    n_values = len({round(v, 1) for v in values})
    rejection: Literal["thin", "frozen"] | None = None
    if n < SNAP_MIN_READINGS:
        rejection = "thin"
    elif n_values < SNAP_MIN_DISTINCT_VALUES:
        rejection = "frozen"
    return SnapWindow(
        target_pct=values[idx],
        n_readings=n,
        n_values=n_values,
        anchor=anchor,
        rejection=rejection,
    )


@dataclass(frozen=True)
class EstimateUpdate:
    """A single state mutation, returned by the operations below."""

    liters: float
    confidence: Confidence
    reason: str
    when: datetime


def sensor_pct_to_liters(
    sensor_pct: float,
    tank_capacity_l: float,
    calibration_pct: float = 100.0,
    empty_pct: float | None = None,
) -> float:
    """Convert raw fuel-level percentage to liters using per-vehicle calibration.

    TWO-POINT map (migration 0019). The sender curve is anchored at both
    ends:

      * ``calibration_pct`` (0016) — raw reading on a physically FULL
        tank. Honda's PID 0x2F stops below 100 because the float arm
        bottoms out above the fill line.
      * ``empty_pct`` (0019) — raw reading on an EMPTY tank. The same
        float arm also stops ABOVE the bottom, so the sender never
        reaches 0.

    Assuming raw 0 == empty (the old one-point behaviour) over-reports
    remaining fuel, and the error is worst at the bottom of the tank
    where it matters most: measured on the Pilot, a raw 14.902% mapped to
    3.18 gal when the tank actually held 2.10 gal — 51% high, and the
    driver was on the low-fuel light.

    ``empty_pct=None`` restores the one-point behaviour, so a vehicle
    that has never calibrated its low end renders exactly as before.
    """
    if calibration_pct <= 0:
        calibration_pct = 100.0
    # A bogus empty point (negative, or at/above the full point) would
    # invert or explode the slope — fall back to one-point rather than
    # emit nonsense.
    low = 0.0
    if empty_pct is not None and 0.0 <= empty_pct < calibration_pct:
        low = float(empty_pct)
    span = calibration_pct - low
    if span <= 0:
        return 0.0
    pct_real = ((sensor_pct - low) / span) * 100.0
    pct_real = max(0.0, min(100.0, pct_real))
    return tank_capacity_l * pct_real / 100.0


def derive_empty_pct(
    *,
    sensor_pct_before: float,
    liters_remaining_before: float,
    tank_capacity_l: float,
    calibration_pct: float,
) -> float | None:
    """Solve for the sender's EMPTY reading from one low-tank observation.

    A fill-to-full tells us exactly how much fuel was in the tank
    beforehand: ``tank_capacity - pumped``. Pair that with the raw sender
    reading taken just before the pump and we have a second point on the
    curve; the full-tank calibration is the first. Two points define the
    line, and its x-intercept is the reading the sender shows when dry.

        real% = (sensor% - empty%) / (calibration% - empty%) * 100

    Returns None when the observation can't constrain the low end —
    caller should leave the existing calibration alone.
    """
    if tank_capacity_l <= 0 or calibration_pct <= 0:
        return None
    if not (0.0 <= sensor_pct_before < calibration_pct):
        return None
    real_pct = liters_remaining_before / tank_capacity_l * 100.0
    if not (0.0 <= real_pct < 100.0):
        return None
    # Slope in real-% per sensor-%. Guard the degenerate case where the
    # observation sits on top of the full point.
    denom = calibration_pct - sensor_pct_before
    if denom <= 0:
        return None
    slope = (100.0 - real_pct) / denom
    if slope <= 0:
        return None
    empty = sensor_pct_before - real_pct / slope
    # A sender that reads negative-when-dry is physically meaningless,
    # and one that reads a third of scale when dry is a bad observation
    # rather than a real calibration.
    if not (0.0 <= empty <= min(30.0, calibration_pct * 0.5)):
        return None
    return empty


def reset_on_fillup(
    *,
    is_full: bool,
    pumped_liters: float | None,
    current_estimate_l: float | None,
    tank_capacity_l: float,
    when: datetime,
) -> EstimateUpdate:
    """Set state from a logged fillup. is_full overrides; else additive.

    is_full=true is the gold-standard reset: the user pumped to overflow,
    so we know exact tank contents = tank_capacity_l.

    Partial fillup adds pumped_liters to the previous estimate. If no
    previous estimate exists, we can't compute (return previous estimate
    as None and HIGH confidence ``unknown`` — caller may keep the existing
    value or fall back to sensor).
    """
    if is_full:
        return EstimateUpdate(
            liters=tank_capacity_l,
            confidence="HIGH",
            reason="fillup_is_full",
            when=when,
        )
    if pumped_liters is None or pumped_liters <= 0:
        # Defensive: a fillup with no pumped value is meaningless; keep state.
        return EstimateUpdate(
            liters=current_estimate_l if current_estimate_l is not None else 0.0,
            confidence="LOW",
            reason="fillup_skipped_no_liters",
            when=when,
        )
    if current_estimate_l is None:
        # Partial fillup with no prior state can't reconstruct contents.
        # Caller should consult sensor; we return a sentinel result.
        return EstimateUpdate(
            liters=pumped_liters,
            confidence="LOW",
            reason="fillup_partial_no_prior",
            when=when,
        )
    new = min(current_estimate_l + pumped_liters, tank_capacity_l)
    return EstimateUpdate(
        liters=new,
        confidence="HIGH" if new < tank_capacity_l else "HIGH",
        reason="fillup_partial",
        when=when,
    )


def decrement_on_trip(
    *,
    fuel_used_l: float,
    current_estimate_l: float | None,
    when: datetime,
) -> EstimateUpdate | None:
    """Subtract trip MAF-integrated consumption from the estimate.

    Returns None if the trip has no usable fuel value or there's no prior
    estimate — caller should skip the persist step.
    """
    if fuel_used_l is None or fuel_used_l <= 0:
        return None
    if current_estimate_l is None:
        return None
    new = max(0.0, current_estimate_l - fuel_used_l)
    return EstimateUpdate(
        liters=new,
        confidence="MEDIUM",
        reason=f"trip_decrement_{fuel_used_l:.2f}L",
        when=when,
    )


def snap_threshold_for_tank(
    tank_capacity_l: float,
    *,
    override_l: float | None = None,
) -> float:
    """Dead-band, in liters, below which a snap is not worth making.

    Proportional to the tank, not a flat number. The flat 5.0 L this
    replaces was 6.8 % of the Pilot's 73.8 L tank — 1.3 gallons of error
    the estimator was designed to ignore, so any drift smaller than that
    could never self-correct no matter how long the car sat parked.
    Observed 2026-08-20: the estimate sat 4.89 L below a clean, settled
    sensor reading for hours, missing the snap by 0.11 L.

    The floor keeps a small tank from getting a hair-trigger that chases
    sensor quantisation, and ``override_l`` preserves the explicit knob
    for callers that want to pin an exact value.
    """
    if override_l is not None:
        return override_l
    return max(tank_capacity_l * SNAP_THRESHOLD_TANK_FRACTION, SNAP_THRESHOLD_MIN_L)


def snap_absorbs_trip(
    *,
    trip_ended_at: datetime,
    sensor_sample_at: datetime,
) -> bool:
    """True when a snap to ``sensor_sample_at`` already reflects this trip.

    The tank sensor moves the moment fuel is burned, but a trip's
    ``fuel_used_l`` decrement can arrive an hour later — the phone has to
    upload the drive and ``trip_deriver`` has to build the row. If a snap
    lands in that gap, the same fuel is charged twice: once because the
    sensor had already fallen, and again when the decrement finally runs.

    That is exactly what happened on 2026-08-20. Trip 94eb726b ended at
    20:55:34; ``snap_pass`` read the post-trip sensor at 21:05:12 and set
    15.38 L; the trip's own 3.13 L decrement then ran at 22:02:13, taking
    the estimate to 12.25 L when 15.38 L was already correct.

    ``snap_pass`` uses this rule to stamp ``fuel_applied_at`` on the trips
    a snap has absorbed, so their decrements can never run afterwards.
    The boundary is inclusive: a sample taken at the same instant a trip
    ended reflects that trip. The worker's SQL predicate
    (``ended_at <= sensor_time``) must mirror this.
    """
    return trip_ended_at <= sensor_sample_at


def snap_to_sensor(
    *,
    sensor_pct: float,
    tank_capacity_l: float,
    calibration_pct: float,
    current_estimate_l: float | None,
    when: datetime,
    snap_threshold_l: float | None = None,
    empty_pct: float | None = None,
    later_burn_l: float = 0.0,
) -> EstimateUpdate | None:
    """Snap the estimate to the sensor reading once the vehicle is parked.

    Called by the fuel_state_worker after the phone has been quiet for
    ``PARKED_QUIET_S`` and ``summarize_snap_window`` has accepted the
    trailing drive window; ``sensor_pct`` is that window's target. There
    is deliberately no "stable readings" gate here: a replayed frame has
    a standard deviation of exactly zero, so stability would select the
    one kind of sample that must never win.

    ``later_burn_l`` is the fuel already charged for trips that ended
    AFTER ``when`` (a drive with no phone samples, debited by the EPA
    fallback). The reading predates those trips, so their burn comes off
    the target. This is what makes a snap idempotent: the same window
    re-evaluated after such a trip lands exactly where the decrement left
    the estimate, inside the dead-band, instead of snapping back over it.

    Returns None when no update is needed (estimate is already within the
    dead-band of the target — see ``snap_threshold_for_tank``). Moves in
    either direction are applied in full; the worker logs any move larger
    than ``LARGE_SNAP_FRACTION`` of the tank.
    """
    sensor_l = max(
        0.0,
        sensor_pct_to_liters(sensor_pct, tank_capacity_l, calibration_pct, empty_pct)
        - max(0.0, later_burn_l),
    )
    if current_estimate_l is None:
        return EstimateUpdate(
            liters=sensor_l,
            confidence="HIGH",
            reason="snap_initial_from_sensor",
            when=when,
        )
    threshold_l = snap_threshold_for_tank(
        tank_capacity_l, override_l=snap_threshold_l
    )
    delta = abs(current_estimate_l - sensor_l)
    if delta < threshold_l:
        return None
    later = f"_less_{later_burn_l:.2f}L_later" if later_burn_l > 0 else ""
    return EstimateUpdate(
        liters=sensor_l,
        confidence="HIGH",
        reason=f"snap_delta_{delta:.1f}L_sensor_{sensor_pct:.1f}pct{later}",
        when=when,
    )


async def persist_estimate(
    conn, vehicle_id: UUID, update: EstimateUpdate
) -> None:
    """Write the new estimate + timestamp to vehicles.

    Caller-supplied connection (so this composes with existing
    transactions in the api / worker code).
    """
    await conn.execute(
        """
        UPDATE vehicles
           SET fuel_level_estimate_l = $2,
               fuel_level_estimate_updated_at = $3
         WHERE id = $1
        """,
        vehicle_id,
        update.liters,
        update.when,
    )
