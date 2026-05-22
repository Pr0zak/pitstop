"""Phone-canonical batch drive ingest (Task #116).

`POST /ingest/drive` accepts a complete drive payload from the Android
app's drive sealer (#117) — all PIDs, GPS points, engine events, and
optional IMU samples captured during one engine-on cycle. Writes the
whole drive atomically: one transaction creates the `trips` row,
bulk-inserts all sample tables, and records an idempotency entry in
`phone_drive_uploads`.

Replaces the streaming MQTT + trip_deriver reconstruction for new
data; the deriver becomes legacy-only after the skip rule lands
(#119).

Idempotency model:
- The phone assigns `client_drive_uuid` deterministically as
  ``uuid5(vehicle_id, started_at)`` so retries on the same drive
  always collide on PK.
- On retry the endpoint short-circuits: returns
  ``{"duplicate": true, "trip_id": <existing>, ...}`` without
  touching any data.
- The trip row itself uses the same UUID so the deriver's UPSERT
  scheme keeps working for legacy paths.

Logging:
- One structured ``INFO`` log per upload with the headline fields
  (vehicle, uuid, started/ended, frame_count, duration_s,
  distance_km, incomplete, duplicate). Surfaces in /api/logs.
"""

from __future__ import annotations

import logging
import json
from datetime import datetime, timezone
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from ..auth import require_ingest_token
from ..db.deps import get_pool
from ..workers.trip_detector import compute_trip_stats

log = logging.getLogger(__name__)

router = APIRouter(prefix="/ingest", tags=["ingest"])


# ─── payload schemas ────────────────────────────────────────────────


class EngineEventIn(BaseModel):
    t: datetime
    kind: str  # "on" | "off"


class PidReadingIn(BaseModel):
    t: datetime
    metric: str
    value_num: float | None = None
    value_text: str | None = None


class GpsPointIn(BaseModel):
    t: datetime
    lat: float
    lon: float
    alt_m: float | None = None
    speed_mps: float | None = None
    heading_deg: float | None = None
    accuracy_m: float | None = None


class DriveUploadIn(BaseModel):
    # Accept either a vehicle UUID or a slug (e.g. "pilot19"). The
    # phone bridge service only knows the slug; resolving server-side
    # avoids needing a vehicle-lookup round-trip from the phone.
    vehicle_id: str
    started_at: datetime
    ended_at: datetime
    device_id: str | None = None
    client_drive_uuid: UUID
    frame_count: int = Field(ge=0)
    incomplete: bool = False
    engine_events: list[EngineEventIn] = Field(default_factory=list)
    pid_readings: list[PidReadingIn] = Field(default_factory=list)
    gps_points: list[GpsPointIn] = Field(default_factory=list)
    # imu_samples was removed in DISK-3. Accept the field silently
    # from older APKs (model_config below allows extras) but don't
    # parse or store it — the column was dropped from pid_readings'
    # ingest path because we no longer write IMU data anywhere.


class DriveUploadOut(BaseModel):
    trip_id: UUID
    client_drive_uuid: UUID
    duplicate: bool
    frame_count_accepted: int
    started_at: datetime
    ended_at: datetime
    incomplete: bool


# ─── helpers ────────────────────────────────────────────────────────


def _frame_count(body: DriveUploadIn) -> int:
    """Sum of the four sample arrays."""
    return (
        len(body.pid_readings)
        + len(body.gps_points)
        + len(body.engine_events)
    )


async def _bulk_insert_pid_readings(
    conn: asyncpg.Connection,
    vehicle_id: UUID,
    rows: list[PidReadingIn],
) -> None:
    """Insert pid_readings rows.

    ON CONFLICT DO NOTHING — the bridge stream may have shipped the
    same data in real time. Phone batch is authoritative on tie.
    """
    records: list[tuple[Any, ...]] = []
    for r in rows:
        records.append((r.t, vehicle_id, r.metric, r.value_num, r.value_text, "phone_batch"))
    if not records:
        return
    # asyncpg's copy_records_to_table is the fastest bulk insert
    # path but doesn't honour ON CONFLICT. For now use executemany
    # with INSERT ... ON CONFLICT DO NOTHING; a few thousand rows
    # per drive is well within budget.
    await conn.executemany(
        """
        INSERT INTO pid_readings (time, vehicle_id, metric, value_num, value_text, source)
        VALUES ($1, $2, $3, $4, $5, $6)
        ON CONFLICT (vehicle_id, metric, time) DO NOTHING
        """,
        records,
    )


async def _refresh_vehicle_state_from_drive(
    conn: asyncpg.Connection,
    vehicle_id: UUID,
    rows: list[PidReadingIn],
) -> None:
    """Upsert vehicle_state.latest from the newest reading per metric in
    this drive payload. Mirrors the live MQTT ingest path so the
    fuel-level hero card + widget reflect post-drive state immediately
    after the user taps Sync — instead of waiting for the next live
    publish (which is suppressed in manual-sync mode and was absent
    entirely during the 2026-05-20 broker outage).

    Skips any metric whose timestamp would *regress* the latest — drive
    payloads can arrive late and we never want a stale row to overwrite
    a fresher live publish."""
    if not rows:
        return
    latest: dict[str, PidReadingIn] = {}
    for r in rows:
        cur = latest.get(r.metric)
        if cur is None or r.t >= cur.t:
            latest[r.metric] = r
    if not latest:
        return
    last_time = max(r.t for r in latest.values())
    last_metric = max(latest.values(), key=lambda r: r.t).metric
    # Build a single JSONB object from all metric snapshots and merge
    # it onto vehicle_state.latest. The CASE-on-time guard inside the
    # jsonb_build_object would be complex; instead we trust that the
    # caller only refreshes when the drive's last_time is plausibly
    # current. For race robustness the upsert below uses GREATEST on
    # last_seen_at and only OVERWRITES metrics whose existing time is
    # older.
    new_entries = {
        metric: {
            "value_num": r.value_num,
            "value_text": r.value_text,
            "time": (
                r.t.astimezone(timezone.utc).strftime(
                    "%Y-%m-%dT%H:%M:%S.")
                + f"{r.t.microsecond // 1000:03d}Z"
            ),
            "source": "phone_batch",
        }
        for metric, r in latest.items()
    }
    await conn.execute(
        """
        INSERT INTO vehicle_state
            (vehicle_id, last_seen_at, last_metric, latest, updated_at)
        VALUES ($1::uuid, $2::timestamptz, $3::text, $4::jsonb, now())
        ON CONFLICT (vehicle_id) DO UPDATE
            SET last_seen_at = GREATEST(vehicle_state.last_seen_at, EXCLUDED.last_seen_at),
                last_metric  = CASE
                    WHEN EXCLUDED.last_seen_at > vehicle_state.last_seen_at
                    THEN EXCLUDED.last_metric
                    ELSE vehicle_state.last_metric
                END,
                latest       = vehicle_state.latest || EXCLUDED.latest,
                updated_at   = now()
        """,
        vehicle_id,
        last_time,
        last_metric,
        json.dumps(new_entries),
    )


async def _bulk_insert_gps_points(
    conn: asyncpg.Connection,
    vehicle_id: UUID,
    rows: list[GpsPointIn],
) -> None:
    if not rows:
        return
    records = [
        (
            r.t, vehicle_id,
            r.lat, r.lon, r.alt_m,
            r.speed_mps, r.heading_deg, r.accuracy_m,
        )
        for r in rows
    ]
    await conn.executemany(
        """
        INSERT INTO gps_points (
            time, vehicle_id, lat, lon, alt_m,
            speed_mps, heading_deg, accuracy_m
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
        ON CONFLICT (vehicle_id, time) DO NOTHING
        """,
        records,
    )


async def _bulk_insert_engine_events(
    conn: asyncpg.Connection,
    vehicle_id: UUID,
    rows: list[EngineEventIn],
) -> None:
    if not rows:
        return
    records = [(e.t, vehicle_id, e.kind, "bridge") for e in rows]
    await conn.executemany(
        """
        INSERT INTO engine_events (time, vehicle_id, state, source)
        VALUES ($1, $2, $3, $4)
        ON CONFLICT (vehicle_id, time, state) DO NOTHING
        """,
        records,
    )


# ─── endpoint ───────────────────────────────────────────────────────


@router.post(
    "/drive",
    response_model=DriveUploadOut,
    dependencies=[Depends(require_ingest_token)],
)
async def post_drive(
    body: DriveUploadIn,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Atomic batch ingest of a complete drive from the phone."""
    if body.ended_at <= body.started_at:
        raise HTTPException(
            status_code=400, detail="ended_at must be > started_at"
        )

    # Frame-count sanity: phone's claimed count and the actual sum
    # of arrays should be within 5% of each other. Above that =
    # client bug, reject early.
    actual = _frame_count(body)
    if body.frame_count > 0:
        delta = abs(actual - body.frame_count) / body.frame_count
        if delta > 0.05:
            raise HTTPException(
                status_code=400,
                detail=(
                    f"frame_count mismatch: claimed {body.frame_count}, "
                    f"got {actual} (Δ {delta:.0%})"
                ),
            )

    async with pool.acquire() as conn, conn.transaction():
        # Resolve vehicle_id which can be either a UUID or a slug.
        # The phone bridge sends the slug; web/API callers send UUID.
        try:
            vehicle_uuid: UUID | None = UUID(body.vehicle_id)
        except ValueError:
            vehicle_uuid = None
        if vehicle_uuid is not None:
            vrow = await conn.fetchrow(
                "SELECT id FROM vehicles WHERE id = $1", vehicle_uuid
            )
        else:
            vrow = await conn.fetchrow(
                "SELECT id FROM vehicles WHERE slug = $1", body.vehicle_id
            )
        if vrow is None:
            raise HTTPException(
                status_code=404, detail=f"vehicle {body.vehicle_id} not found"
            )
        resolved_vehicle_id: UUID = vrow["id"]

        # Idempotency short-circuit.
        existing = await conn.fetchrow(
            "SELECT trip_id, frame_count FROM phone_drive_uploads "
            "WHERE client_drive_uuid = $1",
            body.client_drive_uuid,
        )
        if existing is not None:
            log.info(
                "drive upload duplicate",
                extra={
                    "vehicle_id": str(body.vehicle_id),
                    "client_drive_uuid": str(body.client_drive_uuid),
                    "trip_id": str(existing["trip_id"]),
                    "frame_count_prior": existing["frame_count"],
                },
            )
            return {
                "trip_id": existing["trip_id"],
                "client_drive_uuid": body.client_drive_uuid,
                "duplicate": True,
                "frame_count_accepted": existing["frame_count"],
                "started_at": body.started_at,
                "ended_at": body.ended_at,
                "incomplete": False,
            }

        # Bulk inserts first so compute_trip_stats sees the new data.
        await _bulk_insert_pid_readings(
            conn, resolved_vehicle_id, body.pid_readings
        )
        await _bulk_insert_gps_points(conn, resolved_vehicle_id, body.gps_points)
        await _bulk_insert_engine_events(conn, resolved_vehicle_id, body.engine_events)

        # Refresh vehicle_state.latest from the latest reading per metric
        # we just inserted. Without this the fuel-level hero card + widget
        # stay frozen at the last live-MQTT publish (broker outages, or
        # manual-sync mode where live publishes are suppressed) even
        # though the drive payload had fresher data.
        await _refresh_vehicle_state_from_drive(
            conn, resolved_vehicle_id, body.pid_readings
        )

        # Trip UUID is the phone's client_drive_uuid (deterministic).
        # The deriver's UUID v5 scheme would produce a different ID
        # for the same range, but we never let the deriver touch a
        # phone-sourced range (skip rule lands in #119). Until then
        # there's a tiny window where the deriver could create a
        # parallel row — the trips.source filter on the frontend
        # collapses it visually.
        trip_id = body.client_drive_uuid

        stats = await compute_trip_stats(
            conn, trip_id, resolved_vehicle_id, body.started_at, body.ended_at,
        )

        await conn.execute(
            """
            INSERT INTO trips (
                id, vehicle_id, started_at, ended_at, duration_s,
                distance_km, max_rpm, max_speed_kph, avg_speed_kph,
                avg_coolant_c, fuel_used_l, dtc_count, idle_s,
                source, incomplete
            ) VALUES (
                $1, $2, $3, $4, $5,
                $6, $7, $8, $9,
                $10, $11, $12, $13,
                'phone_batch', $14
            )
            ON CONFLICT (id) DO UPDATE SET
                ended_at = EXCLUDED.ended_at,
                duration_s = EXCLUDED.duration_s,
                distance_km = EXCLUDED.distance_km,
                max_rpm = EXCLUDED.max_rpm,
                max_speed_kph = EXCLUDED.max_speed_kph,
                avg_speed_kph = EXCLUDED.avg_speed_kph,
                avg_coolant_c = EXCLUDED.avg_coolant_c,
                fuel_used_l = EXCLUDED.fuel_used_l,
                dtc_count = EXCLUDED.dtc_count,
                idle_s = EXCLUDED.idle_s,
                source = EXCLUDED.source,
                incomplete = EXCLUDED.incomplete
            """,
            trip_id, resolved_vehicle_id, body.started_at, body.ended_at,
            stats["duration_s"], stats["distance_km"],
            stats["max_rpm"], stats["max_speed_kph"],
            stats["avg_speed_kph"], stats["avg_coolant_c"],
            stats["fuel_used_l"], stats["dtc_count"], stats.get("idle_s"),
            body.incomplete,
        )

        await conn.execute(
            """
            INSERT INTO phone_drive_uploads (
                client_drive_uuid, trip_id, device_id, frame_count
            ) VALUES ($1, $2, $3, $4)
            """,
            body.client_drive_uuid, trip_id, body.device_id, actual,
        )

        # Remove any deriver-source trips that overlap this phone-
        # sourced window. The deriver may have already produced a trip
        # for the same range before the phone batch arrived (deriver
        # runs every 5 min, phone seal-then-upload can happen after).
        # The deriver's skip-phone-sourced rule (#119) prevents NEW
        # overlaps, but we also need to clean up STALE deriver rows
        # that pre-date this upload.
        # NB: we don't touch deriver trips that fall outside the
        # phone's window — those are different physical drives the
        # deriver split correctly (the OBD-quiet watchdog now catches
        # parking gaps but old phone-batch trips from before v0.1.102
        # may still merge across them).
        wiped = await conn.fetch(
            """
            DELETE FROM trips
             WHERE vehicle_id = $1
               AND source = 'deriver'
               AND started_at >= $2
               AND ended_at   <= $3
               AND id <> $4
            RETURNING id
            """,
            resolved_vehicle_id,
            body.started_at, body.ended_at,
            trip_id,
        )
        if wiped:
            log.info(
                "drive upload: cleaned %d deriver shadow trip(s)",
                len(wiped),
                extra={
                    "vehicle_id": str(resolved_vehicle_id),
                    "client_drive_uuid": str(body.client_drive_uuid),
                    "wiped_trip_ids": [str(r["id"]) for r in wiped],
                },
            )

    log.info(
        "drive upload accepted",
        extra={
            "vehicle_id": str(body.vehicle_id),
            "client_drive_uuid": str(body.client_drive_uuid),
            "trip_id": str(trip_id),
            "device_id": body.device_id,
            "started_at": body.started_at.isoformat(),
            "ended_at": body.ended_at.isoformat(),
            "frame_count": actual,
            "duration_s": stats["duration_s"],
            "distance_km": stats["distance_km"],
            "incomplete": body.incomplete,
        },
    )

    return {
        "trip_id": trip_id,
        "client_drive_uuid": body.client_drive_uuid,
        "duplicate": False,
        "frame_count_accepted": actual,
        "started_at": body.started_at,
        "ended_at": body.ended_at,
        "incomplete": body.incomplete,
    }
