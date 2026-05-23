"""Trip detector worker.

Silence-based trip semantics (per PLAN.md / TODO.md):

- Trip OPENS on the first telemetry message after >TRIP_SILENCE_OPEN_S
  seconds of silence (default 120 s).
- Trip CLOSES on TRIP_SILENCE_CLOSE_S seconds of silence (default 60 s) OR
  on `control_module_voltage` < TRIP_LOW_VOLTAGE_THRESHOLD V for
  >= TRIP_LOW_VOLTAGE_CONSECUTIVE consecutive readings (engine-off proxy).

The detector subscribes to the in-process EventBus from the ingest worker.
A periodic sweeper (every 10 s) closes any trips that crossed the silence
threshold even when no new traffic arrives.

Stats on close are computed by querying the pid_readings hypertable for
the closed window.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import TYPE_CHECKING
from uuid import UUID

import asyncpg

if TYPE_CHECKING:
    from ..config import Settings
    from .bus import EventBus, TelemetryEvent

log = logging.getLogger(__name__)


SWEEP_INTERVAL_S = 10.0


@dataclass
class _VehicleState:
    """Per-vehicle in-memory state for the detector."""

    last_seen_at: datetime | None = None
    last_metric: str | None = None
    current_trip_id: UUID | None = None
    current_trip_started: datetime | None = None
    low_voltage_count: int = 0
    # Cached during open window so close has accurate fallback bounds.
    last_event_in_trip_at: datetime | None = None
    # Ordered metrics seen in the trip (for fast stat queries — optional).
    metrics_seen: set[str] = field(default_factory=set)


# ---------------------------------------------------------------------------
# Pure state-machine logic — testable without a DB
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class TripDecision:
    """What the state machine wants the worker to do."""

    open_trip: bool = False
    close_trip: bool = False
    close_reason: str | None = None  # "silence" | "low_voltage"


def decide_on_event(
    state: _VehicleState,
    event_time: datetime,
    metric: str,
    value_num: float | None,
    *,
    silence_open_s: int,
    silence_close_s: int,
    low_voltage_threshold: float,
    low_voltage_consecutive: int,
) -> TripDecision:
    """Return the action the worker should take given a new telemetry event.

    The state object is mutated in place — we update last_seen, last_metric,
    voltage counter, and (caller does the open/close DB work and updates
    current_trip_id/started + last_event_in_trip_at).
    """
    decision = TripDecision()

    # Determine if this event should OPEN a trip (silence gap or no trip yet).
    silence_gap_open = state.last_seen_at is None or (
        event_time - state.last_seen_at
    ) >= timedelta(seconds=silence_open_s)
    if state.current_trip_id is None and silence_gap_open:
        decision = TripDecision(open_trip=True)
    elif state.current_trip_id is None:
        # Active stream but we have no trip on record (e.g. process restart
        # mid-stream): open one anyway so we don't lose this drive.
        decision = TripDecision(open_trip=True)

    # Update tracking before evaluating close conditions so a single voltage
    # event with no preceding state still counts.
    state.last_seen_at = event_time
    state.last_metric = metric

    # Voltage-based close: track consecutive low readings.
    if metric == "control_module_voltage" and value_num is not None:
        if value_num < low_voltage_threshold:
            state.low_voltage_count += 1
        else:
            state.low_voltage_count = 0
        if (
            state.current_trip_id is not None or decision.open_trip
        ) and state.low_voltage_count >= low_voltage_consecutive:
            return TripDecision(
                open_trip=decision.open_trip,
                close_trip=True,
                close_reason="low_voltage",
            )

    return decision


def decide_on_sweep(
    state: _VehicleState,
    now: datetime,
    *,
    silence_close_s: int,
) -> TripDecision:
    """Return whether to close an open trip due to silence."""
    if state.current_trip_id is None:
        return TripDecision()
    last = state.last_event_in_trip_at or state.last_seen_at
    if last is None:
        return TripDecision()
    if (now - last) >= timedelta(seconds=silence_close_s):
        return TripDecision(close_trip=True, close_reason="silence")
    return TripDecision()


# ---------------------------------------------------------------------------
# Stats computation — pure SQL, takes a connection
# ---------------------------------------------------------------------------


async def compute_trip_stats(
    conn: asyncpg.Connection,
    trip_id: UUID,
    vehicle_id: UUID,
    started_at: datetime,
    ended_at: datetime,
) -> dict[str, float | int | None]:
    """Compute close-time stats from pid_readings + dtc_events."""
    duration_s = max(int((ended_at - started_at).total_seconds()), 0)

    # Aggregate the well-known metrics in one pass.
    agg = await conn.fetchrow(
        """
        SELECT
            max(CASE WHEN metric = 'engine_rpm'      THEN value_num END) AS max_rpm,
            max(CASE WHEN metric = 'vehicle_speed'   THEN value_num END) AS max_speed_kph,
            avg(CASE WHEN metric = 'vehicle_speed'   THEN value_num END) AS avg_speed_kph,
            avg(CASE WHEN metric = 'coolant_temp'    THEN value_num END) AS avg_coolant_c
        FROM pid_readings
        WHERE vehicle_id = $1 AND time >= $2 AND time <= $3
        """,
        vehicle_id,
        started_at,
        ended_at,
    )

    # Distance preference order:
    #   1) GPS haversine sum from gps_points (accurate, immune to OBD speed
    #      noise / dropped frames). PostGIS would be cleaner but we don't
    #      ship the extension; the formula below is the standard 6371-km
    #      great-circle approximation, plenty for trip totals.
    #   2) Vehicle-speed integration (kph * dt) as a fallback when no GPS.
    distance_km = await conn.fetchval(
        """
        WITH pairs AS (
            SELECT
                lat, lon,
                LAG(lat) OVER (ORDER BY time) AS lag_lat,
                LAG(lon) OVER (ORDER BY time) AS lag_lon,
                EXTRACT(EPOCH FROM (time - LAG(time) OVER (ORDER BY time)))
                    AS dt
            FROM gps_points
            WHERE vehicle_id = $1
              AND time >= $2 AND time <= $3
        ),
        steps AS (
            SELECT
                dt,
                2 * 6371.0 * asin(sqrt(
                    pow(sin(radians(lat - lag_lat) / 2), 2) +
                    cos(radians(lag_lat)) * cos(radians(lat)) *
                    pow(sin(radians(lon - lag_lon) / 2), 2)
                )) AS step_km
            FROM pairs
            WHERE lag_lat IS NOT NULL AND dt IS NOT NULL AND dt < 60
        )
        -- Drop pairs that imply > 250 km/h apparent velocity. Those are
        -- network-provider noise spikes (alternating real / stale
        -- coords) that exploded historical trip distances. Real
        -- driving never produces a single step over the threshold.
        SELECT COALESCE(SUM(step_km), 0)
        FROM steps
        WHERE step_km / NULLIF(dt, 0) * 3600 < 250
        """,
        vehicle_id,
        started_at,
        ended_at,
    )
    # Fallback: speed integration if GPS gave us nothing. Older trips that
    # predate gps_points (or vehicles without GPS plumbed through the
    # bridge) take this path.
    if not distance_km:
        distance_km = await conn.fetchval(
            """
            SELECT COALESCE(SUM(value_num * dt) / 3600.0, 0)
            FROM (
                SELECT
                    value_num,
                    EXTRACT(EPOCH FROM (time - LAG(time) OVER (ORDER BY time)))
                        AS dt
                FROM pid_readings
                WHERE vehicle_id = $1
                  AND metric = 'vehicle_speed'
                  AND time >= $2 AND time <= $3
            ) s
            WHERE dt IS NOT NULL AND dt < 60
            """,
            vehicle_id,
            started_at,
            ended_at,
        )

    # Fuel used: integrate maf_air_flow (g/s) / 14.7 (stoich) / 749.9 (g/L)
    # across the trip duration. ~14.7 g air per 1 g fuel; gasoline density
    # ~0.7499 kg/L, so g_fuel / 749.9 = L_fuel.
    fuel_used_l = await conn.fetchval(
        """
        SELECT COALESCE(SUM(value_num * dt) / 14.7 / 749.9, 0)
        FROM (
            SELECT
                value_num,
                EXTRACT(EPOCH FROM (time - LAG(time) OVER (ORDER BY time)))
                    AS dt
            FROM pid_readings
            WHERE vehicle_id = $1
              AND metric = 'maf_air_flow'
              AND time >= $2 AND time <= $3
        ) s
        WHERE dt IS NOT NULL AND dt < 60
        """,
        vehicle_id,
        started_at,
        ended_at,
    )

    # Fallback: derive fuel_used from the fuel_level delta when MAF isn't
    # available. Honda Pilot's poll list doesn't include PID 0x10 (MAF)
    # reliably, so MAF-integration produces zero and MPG was always
    # blank for these trips. Sensor is granular (~0.5 % steps) but for
    # any drive that uses > 1 % of tank, the delta path beats nothing.
    # raw_pct is normalized against the per-vehicle calibration ceiling
    # (Honda 0x2F caps below 100 on a full tank) so the delta maps to
    # physical gallons consistently with the gauge UI.
    if (fuel_used_l is None) or (float(fuel_used_l) < 0.05):
        fuel_used_l = await conn.fetchval(
            """
            WITH cfg AS (
                SELECT
                    COALESCE(tank1_capacity, 0)::float          AS cap_gal,
                    NULLIF(fuel_level_calibration_pct, 0)::float AS cal_pct
                  FROM vehicles WHERE id = $1
            ),
            first_pt AS (
                SELECT value_num FROM pid_readings
                 WHERE vehicle_id = $1
                   AND metric = 'fuel_level'
                   AND time BETWEEN $2 AND $3
                 ORDER BY time ASC
                 LIMIT 1
            ),
            last_pt AS (
                SELECT value_num FROM pid_readings
                 WHERE vehicle_id = $1
                   AND metric = 'fuel_level'
                   AND time BETWEEN $2 AND $3
                 ORDER BY time DESC
                 LIMIT 1
            )
            SELECT CASE
                WHEN cfg.cap_gal > 0
                 AND cfg.cal_pct IS NOT NULL
                 AND first_pt.value_num IS NOT NULL
                 AND last_pt.value_num IS NOT NULL
                 AND (first_pt.value_num - last_pt.value_num) > 0.5
                THEN ((first_pt.value_num - last_pt.value_num)
                       / cfg.cal_pct * cfg.cap_gal * 3.78541)
                ELSE 0
            END
            FROM cfg, first_pt, last_pt
            """,
            vehicle_id,
            started_at,
            ended_at,
        )

    # Sanity cap: raw fuel_level can slosh 20+ pct on a short trip,
    # producing fallback estimates that imply sub-1-MPG consumption.
    # Discard rather than poison /analytics/* with the bogus value;
    # NULL distinguishes "unknown" from a genuine zero. Threshold:
    # 0.40 L/km ≈ 5.9 MPG, looser than any plausible real-world figure
    # on a moving trip (worst-case towing-uphill gasoline still beats
    # ~6 MPG).
    if (
        fuel_used_l is not None
        and distance_km is not None
        and float(distance_km) > 0.5
        and float(fuel_used_l) / float(distance_km) > 0.40
    ):
        fuel_used_l = None

    dtc_count = await conn.fetchval(
        """
        SELECT count(*)
          FROM dtc_events
         WHERE vehicle_id = $1
           AND seen_at >= $2 AND seen_at <= $3
        """,
        vehicle_id,
        started_at,
        ended_at,
    )

    # Idle seconds (Task #91). Count distinct seconds where
    # vehicle_speed < 1 m/s (≈ 3.6 kph). The bridge only publishes
    # vehicle_speed while the engine is responding to OBD requests,
    # so any low-speed sample inside the trip window is engine-on +
    # vehicle-stopped — exactly what we want.
    idle_s = await conn.fetchval(
        """
        SELECT count(DISTINCT date_trunc('second', time))
          FROM pid_readings
         WHERE vehicle_id = $1
           AND metric = 'vehicle_speed'
           AND value_num IS NOT NULL
           AND value_num < 1
           AND time >= $2 AND time <= $3
        """,
        vehicle_id,
        started_at,
        ended_at,
    )

    def _f(v: object) -> float | None:
        if v is None:
            return None
        return float(v)

    return {
        "duration_s": duration_s,
        "max_rpm": _f(agg["max_rpm"]) if agg else None,
        "max_speed_kph": _f(agg["max_speed_kph"]) if agg else None,
        "avg_speed_kph": _f(agg["avg_speed_kph"]) if agg else None,
        "avg_coolant_c": _f(agg["avg_coolant_c"]) if agg else None,
        "distance_km": _f(distance_km),
        "fuel_used_l": _f(fuel_used_l),
        "dtc_count": int(dtc_count or 0),
        "idle_s": int(idle_s or 0) if idle_s is not None else None,
    }


# ---------------------------------------------------------------------------
# TripDetector worker
# ---------------------------------------------------------------------------


class TripDetector:
    def __init__(
        self,
        *,
        pool: asyncpg.Pool,
        bus_: EventBus,
        config: Settings,
    ) -> None:
        self._pool = pool
        self._bus = bus_
        self._cfg = config
        self._stop = asyncio.Event()
        self._states: dict[UUID, _VehicleState] = {}
        self._states_lock = asyncio.Lock()

    # -- lifecycle ------------------------------------------------------

    async def run(self) -> None:
        # Re-claim any trips that were left open at process restart so we
        # don't violate uq_trips_open_per_vehicle on the next event.
        await self._reclaim_open_trips()
        queue = self._bus.subscribe()
        sweep_task = asyncio.create_task(self._sweep_loop())
        try:
            while not self._stop.is_set():
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=1.0)
                except TimeoutError:
                    continue
                try:
                    await self._on_event(event)
                except Exception:
                    log.exception("trip detector failure on event %r", event)
        finally:
            sweep_task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await sweep_task
            self._bus.unsubscribe(queue)

    def stop(self) -> None:
        self._stop.set()

    # -- event handling -------------------------------------------------

    async def _on_event(self, event: TelemetryEvent) -> None:
        async with self._states_lock:
            state = self._states.setdefault(event.vehicle_id, _VehicleState())

            # Authoritative engine on/off transitions short-circuit the
            # silence-based heuristic. Two flavours of source:
            #   - bridge: phone OBD-frame parser, accurate to seconds
            #   - wican_lwt: WiCAN's MQTT LWT, fires on broker keepalive
            #     timeout — can blip false-off during a brief WiFi
            #     interruption. Only treat its "off" as authoritative if
            #     the trip has been open >5 min (long enough that a
            #     keepalive blip is the unlikely explanation). Bridge
            #     "off" is always trusted because it sees STOPPED frames
            #     directly from the OBD bus.
            if event.metric == "_engine_state":
                state.last_seen_at = event.time
                if event.value_text == "on":
                    if state.current_trip_id is None:
                        await self._open_trip(state, event.vehicle_id, event.time)
                    state.last_event_in_trip_at = event.time
                elif event.value_text == "off":
                    if state.current_trip_id is None:
                        return
                    if event.source == "wican_lwt" and state.current_trip_started:
                        elapsed = (
                            event.time - state.current_trip_started
                        ).total_seconds()
                        if elapsed < 300:
                            log.info(
                                "ignoring wican_lwt off — trip only %.0fs old "
                                "(likely WiFi blip)",
                                elapsed,
                            )
                            return
                    await self._close_trip(
                        state, event.vehicle_id, event.time, "engine_off"
                    )
                return

            decision = decide_on_event(
                state,
                event.time,
                event.metric,
                event.value_num,
                silence_open_s=self._cfg.trip_silence_open_s,
                silence_close_s=self._cfg.trip_silence_close_s,
                low_voltage_threshold=self._cfg.trip_low_voltage_threshold,
                low_voltage_consecutive=self._cfg.trip_low_voltage_consecutive,
            )

            if decision.open_trip:
                await self._open_trip(state, event.vehicle_id, event.time)

            # Track per-trip activity time (sweep uses this).
            if state.current_trip_id is not None:
                state.last_event_in_trip_at = event.time
                state.metrics_seen.add(event.metric)

            if decision.close_trip and state.current_trip_id is not None:
                await self._close_trip(
                    state, event.vehicle_id, event.time, decision.close_reason
                )

    async def _sweep_loop(self) -> None:
        while not self._stop.is_set():
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=SWEEP_INTERVAL_S)
                break
            except TimeoutError:
                pass
            await self._sweep_silence()

    async def _sweep_silence(self) -> None:
        # Use the latest seen time across all vehicles as "now"; falling
        # back to wall-clock when there's no traffic at all.
        from datetime import UTC

        now = datetime.now(UTC)
        async with self._states_lock:
            stale: list[tuple[UUID, _VehicleState]] = []
            for vid, state in self._states.items():
                d = decide_on_sweep(
                    state, now, silence_close_s=self._cfg.trip_silence_close_s
                )
                if d.close_trip:
                    stale.append((vid, state))
            for vid, state in stale:
                if state.current_trip_id is None:
                    continue
                end_time = state.last_event_in_trip_at or now
                await self._close_trip(state, vid, end_time, "silence")

    # -- DB ops ---------------------------------------------------------

    async def _reclaim_open_trips(self) -> None:
        """On startup, sync in-memory state with any open trip rows."""
        try:
            async with self._pool.acquire() as conn:
                rows = await conn.fetch(
                    "SELECT id, vehicle_id, started_at FROM trips "
                    "WHERE ended_at IS NULL"
                )
        except (asyncpg.PostgresError, OSError) as exc:
            log.warning("reclaim_open_trips failed: %s", exc)
            return
        for row in rows:
            state = self._states.setdefault(row["vehicle_id"], _VehicleState())
            state.current_trip_id = row["id"]
            state.current_trip_started = row["started_at"]
            state.last_event_in_trip_at = row["started_at"]

    async def _open_trip(
        self, state: _VehicleState, vehicle_id: UUID, started_at: datetime
    ) -> None:
        try:
            async with self._pool.acquire() as conn:
                # Check for an existing open trip — if present, claim it.
                row = await conn.fetchrow(
                    "SELECT id, started_at FROM trips "
                    "WHERE vehicle_id = $1 AND ended_at IS NULL",
                    vehicle_id,
                )
                if row is not None:
                    state.current_trip_id = row["id"]
                    state.current_trip_started = row["started_at"]
                    log.info("claimed existing open trip %s", row["id"])
                    return
                trip_id = await conn.fetchval(
                    "INSERT INTO trips (vehicle_id, started_at) "
                    "VALUES ($1, $2) RETURNING id",
                    vehicle_id,
                    started_at,
                )
        except (asyncpg.PostgresError, OSError) as exc:
            log.error("trip open failed for vehicle %s: %s", vehicle_id, exc)
            return
        state.current_trip_id = trip_id
        state.current_trip_started = started_at
        state.metrics_seen.clear()
        state.low_voltage_count = 0
        log.info("opened trip %s for vehicle %s", trip_id, vehicle_id)

    async def _close_trip(
        self,
        state: _VehicleState,
        vehicle_id: UUID,
        ended_at: datetime,
        reason: str | None,
    ) -> None:
        trip_id = state.current_trip_id
        started_at = state.current_trip_started
        if trip_id is None or started_at is None:
            return
        try:
            async with self._pool.acquire() as conn:
                stats = await compute_trip_stats(
                    conn, trip_id, vehicle_id, started_at, ended_at
                )
                # Discard sliver-trips. A parked car publishing battery /
                # temp readings every minute would otherwise auto-open +
                # auto-close a 0-km "trip" every silence window. Below
                # the threshold we delete the row outright so it never
                # appears in /trips.
                if (
                    stats["distance_km"] is None
                    or float(stats["distance_km"]) < self._cfg.trip_min_distance_km
                ):
                    await conn.execute(
                        "DELETE FROM trips WHERE id = $1",
                        trip_id,
                    )
                    log.info(
                        "discarded sliver trip %s vehicle=%s reason=%s "
                        "distance_km=%s threshold_km=%s",
                        trip_id,
                        vehicle_id,
                        reason,
                        stats["distance_km"],
                        self._cfg.trip_min_distance_km,
                    )
                    state.current_trip_id = None
                    state.current_trip_started = None
                    state.last_event_in_trip_at = None
                    state.metrics_seen.clear()
                    state.low_voltage_count = 0
                    return
                await conn.execute(
                    """
                    UPDATE trips
                       SET ended_at = $2,
                           duration_s = $3,
                           distance_km = $4,
                           max_rpm = $5,
                           max_speed_kph = $6,
                           avg_speed_kph = $7,
                           avg_coolant_c = $8,
                           fuel_used_l = $9,
                           dtc_count = $10
                     WHERE id = $1
                    """,
                    trip_id,
                    ended_at,
                    stats["duration_s"],
                    stats["distance_km"],
                    stats["max_rpm"],
                    stats["max_speed_kph"],
                    stats["avg_speed_kph"],
                    stats["avg_coolant_c"],
                    stats["fuel_used_l"],
                    stats["dtc_count"],
                )
        except (asyncpg.PostgresError, OSError) as exc:
            log.error("trip close failed trip=%s: %s", trip_id, exc)
            return
        log.info(
            "closed trip %s vehicle=%s reason=%s duration_s=%s distance_km=%s",
            trip_id,
            vehicle_id,
            reason,
            stats["duration_s"],
            stats["distance_km"],
        )
        state.current_trip_id = None
        state.current_trip_started = None
        state.last_event_in_trip_at = None
        state.metrics_seen.clear()
        state.low_voltage_count = 0
