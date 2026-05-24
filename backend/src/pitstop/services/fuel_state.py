"""Pure state-machine helpers for the hybrid fuel-level estimator.

Three operations mutate the per-vehicle estimate stored in
``vehicles.fuel_level_estimate_l``:

1. ``reset_on_fillup`` — called from ``POST /fillups``. is_full sets the
   estimate to tank capacity; partial fillups add the pumped liters.
2. ``decrement_on_trip`` — called from trip_detector after a trip's
   ``fuel_used_l`` is computed (MAF integration). Subtracts; floors at 0.
3. ``snap_to_sensor`` — called from the truth-up worker when the engine
   has been off long enough that the sensor reading should be stable.

Each function returns the new estimate + a short ``reason`` string for the
audit trail in logs. None of these touch the DB; the caller is responsible
for persisting via the small ``persist_estimate`` helper.

All math is in liters. The sensor reads percentage (0-100); convert via
``sensor_pct_to_liters`` which accounts for the per-vehicle calibration
ceiling (Honda's PID 0x2F caps below 100% on a physically full tank — see
ADR-014 / migration 0016).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Literal
from uuid import UUID

Confidence = Literal["HIGH", "MEDIUM", "LOW"]


@dataclass(frozen=True)
class EstimateUpdate:
    """A single state mutation, returned by the operations below."""

    liters: float
    confidence: Confidence
    reason: str
    when: datetime


def sensor_pct_to_liters(
    sensor_pct: float, tank_capacity_l: float, calibration_pct: float = 100.0
) -> float:
    """Convert raw fuel-level percentage to liters using per-vehicle calibration.

    The calibration ceiling (from migration 0016) maps the sensor's
    physical-max reading to 100% of tank. If a vehicle reads 85% on a
    full tank, calibration_pct=85; a current reading of 42.5 means
    50% real = tank_capacity_l * 0.5 liters.
    """
    if calibration_pct <= 0:
        calibration_pct = 100.0
    pct_real = (sensor_pct / calibration_pct) * 100.0
    pct_real = max(0.0, min(100.0, pct_real))
    return tank_capacity_l * pct_real / 100.0


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


def snap_to_sensor(
    *,
    sensor_pct: float,
    tank_capacity_l: float,
    calibration_pct: float,
    current_estimate_l: float | None,
    when: datetime,
    snap_threshold_l: float = 5.0,
) -> EstimateUpdate | None:
    """Snap the estimate to the sensor reading when the sensor should be stable.

    Called by the truth-up worker after verifying:
      - Engine has been off for ≥10 min
      - Sensor readings stable (stddev < 0.5%) over a recent window

    Returns None when no update is needed (estimate is already within
    snap_threshold of the sensor reading).
    """
    sensor_l = sensor_pct_to_liters(sensor_pct, tank_capacity_l, calibration_pct)
    if current_estimate_l is None:
        return EstimateUpdate(
            liters=sensor_l,
            confidence="HIGH",
            reason="snap_initial_from_sensor",
            when=when,
        )
    delta = abs(current_estimate_l - sensor_l)
    if delta < snap_threshold_l:
        return None
    return EstimateUpdate(
        liters=sensor_l,
        confidence="HIGH",
        reason=f"snap_delta_{delta:.1f}L_sensor_{sensor_pct:.1f}pct",
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
