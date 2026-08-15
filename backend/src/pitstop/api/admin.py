"""Admin endpoints — storage stats and on-demand retention purges.

Read endpoints (storage stats) use require_query_token. Mutating endpoints
(purges) require require_ingest_token AND a deliberate ``confirm=true``
query param so a misclicked button can't drop a year of readings.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import shlex
import subprocess
import time
from datetime import datetime, timedelta, timezone
from typing import Any

import asyncpg
import httpx
from fastapi import APIRouter, Depends, HTTPException, Query

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..version import GIT_SHA, VERSION

log = logging.getLogger(__name__)

router = APIRouter(prefix="/admin", tags=["admin"])


# Tables we surface stats for. Hypertables (pid_readings, dtc_events) are
# rolled up by aggregating their chunks back to the parent name so the UI
# reports one row per logical table. The rest of the schema (alembic_version,
# bgw_job, etc.) is omitted — not actionable from the user's perspective.
_USER_TABLES = (
    "pid_readings",
    "fillups",
    "trips",
    "dtc_events",
    "expenses",
    "client_logs",
    "vehicles",
    "vehicle_state",
    "expense_categories",
    "pid_profiles",
)


@router.get(
    "/storage",
    dependencies=[Depends(require_query_token)],
)
async def storage_stats(
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Per-table size + row counts.

    For Timescale hypertables (pid_readings, dtc_events), rolls up all
    chunks under the logical name so the user sees a single number per
    feature, not one row per Timescale chunk.
    """
    async with pool.acquire() as conn:
        # Total DB size
        total_size_bytes = await conn.fetchval(
            "SELECT pg_database_size(current_database())"
        )

        # Per-logical-table stats. The hypertable rollup uses
        # _timescaledb_internal.show_chunks → not always available;
        # cleaner to use the timescaledb catalog directly.
        rows = []
        for table in _USER_TABLES:
            exists = await conn.fetchval(
                "SELECT to_regclass($1)::text IS NOT NULL", f"public.{table}"
            )
            if not exists:
                continue

            # Row count — for hypertables the parent table reports 0,
            # so we count() against it which Timescale routes to chunks.
            try:
                row_count = await conn.fetchval(f"SELECT count(*) FROM {table}")
            except asyncpg.PostgresError:
                row_count = None

            # Size — for plain tables pg_total_relation_size; for
            # hypertables we sum chunk sizes via the timescaledb catalog.
            is_hyper = await conn.fetchval(
                """
                SELECT EXISTS (
                    SELECT 1 FROM timescaledb_information.hypertables
                    WHERE hypertable_name = $1
                )
                """,
                table,
            )
            if is_hyper:
                # Timescale's hypertable_size() returns the total disk
                # bytes across all chunks (data + index + toast).
                # Older Timescale releases don't have a size column on
                # timescaledb_information.chunks — the function call is
                # the version-stable way.
                try:
                    size = await conn.fetchval(
                        "SELECT hypertable_size($1::regclass)",
                        f"public.{table}",
                    )
                except asyncpg.PostgresError:
                    # Fall back to summing chunk sizes via pg_total_relation_size.
                    size = await conn.fetchval(
                        """
                        SELECT COALESCE(sum(pg_total_relation_size(
                                   format('%I.%I', chunk_schema, chunk_name)::regclass
                               )), 0)::bigint
                          FROM timescaledb_information.chunks
                         WHERE hypertable_name = $1
                        """,
                        table,
                    )
            else:
                size = await conn.fetchval(
                    "SELECT pg_total_relation_size($1)", f"public.{table}"
                )

            rows.append(
                {
                    "table": table,
                    "rows": int(row_count) if row_count is not None else None,
                    "size_bytes": int(size or 0),
                    "is_hypertable": bool(is_hyper),
                }
            )

        # Oldest reading timestamp — surfaces "your oldest data is X
        # days old" so the retention slider has useful context.
        oldest_reading = await conn.fetchval(
            "SELECT min(time) FROM pid_readings"
        )

    return {
        "total_size_bytes": int(total_size_bytes or 0),
        "tables": sorted(rows, key=lambda r: r["size_bytes"], reverse=True),
        "oldest_reading_at": oldest_reading.isoformat() if oldest_reading else None,
    }


@router.post(
    "/purge/readings",
    dependencies=[Depends(require_ingest_token)],
)
async def purge_readings(
    older_than_days: int | None = Query(default=None, ge=1, le=3650),
    confirm: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Drop OBD readings older than N days.

    Two-step: first call returns the projected row count for review,
    second call with confirm=true actually deletes. The Timescale
    drop_chunks function is much faster than DELETE for hypertable
    purges, so we use it when the cutoff aligns to chunk boundaries
    (Timescale handles partial-chunk deletes via DELETE under the hood).

    Body: { older_than_days: 1..3650, confirm: bool }
    """
    if older_than_days is None:
        raise HTTPException(
            status_code=400, detail="older_than_days is required (1..3650)"
        )
    cutoff = datetime.now(timezone.utc) - timedelta(days=older_than_days)

    async with pool.acquire() as conn:
        affected = await conn.fetchval(
            "SELECT count(*) FROM pid_readings WHERE time < $1",
            cutoff,
        )

        if not confirm:
            return {
                "preview": True,
                "older_than_days": older_than_days,
                "cutoff_iso": cutoff.isoformat(),
                "rows_to_delete": int(affected or 0),
            }

        # Actually purge. Use drop_chunks for the bulk path.
        try:
            await conn.execute(
                "SELECT drop_chunks('pid_readings', $1::timestamptz)",
                cutoff,
            )
        except asyncpg.PostgresError as exc:
            # drop_chunks may fail on partial-chunk cutoffs; fall back to
            # a plain DELETE which is slower but always works.
            log.warning(
                "drop_chunks failed (%s) — falling back to DELETE", exc
            )
            await conn.execute(
                "DELETE FROM pid_readings WHERE time < $1",
                cutoff,
            )

    log.info(
        "admin purge readings older_than_days=%s rows_deleted=%s",
        older_than_days,
        affected,
    )
    return {
        "preview": False,
        "older_than_days": older_than_days,
        "cutoff_iso": cutoff.isoformat(),
        "rows_deleted": int(affected or 0),
    }


@router.get(
    "/devices",
    dependencies=[Depends(require_query_token)],
)
async def list_devices(
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    """List every WiCAN / phone device the backend has either:
      - explicitly mapped to a vehicle (device_vehicle_map row)
      - seen recently in the WiCAN log warnings (parsed from client_logs)

    Lets the admin UI render: mapped devices with a name + status, plus
    unmapped MAC-style topics that the ingest worker has been dropping
    so the user can one-tap-assign them.
    """
    async with pool.acquire() as conn:
        mapped = await conn.fetch(
            """
            SELECT d.device_id, d.vehicle_id, d.kind, d.label,
                   d.first_seen_at, d.last_seen_at,
                   v.name AS vehicle_name, v.slug AS vehicle_slug
              FROM device_vehicle_map d
              LEFT JOIN vehicles v ON v.id = d.vehicle_id
             ORDER BY d.last_seen_at DESC NULLS LAST
            """,
        )
        # Unmapped devices observed via backend log warnings in the last
        # 24h. The warning message format is stable
        # ("dropping message for unknown device/slug 'XXX'") so a regex
        # extraction is reliable enough.
        unmapped_rows = await conn.fetch(
            """
            SELECT DISTINCT
                substring(message FROM 'unknown device/slug ''([^'']+)''') AS device_id,
                max(ts) AS last_seen_at,
                count(*) AS warn_count
              FROM client_logs
             WHERE source = 'backend'
               AND message LIKE 'dropping message for unknown device/slug %'
               AND ts > now() - interval '24 hours'
             GROUP BY 1
             HAVING substring(message FROM 'unknown device/slug ''([^'']+)''') IS NOT NULL
             ORDER BY 2 DESC
            """,
        )
        # Filter out unmapped device_ids that already have a row (race).
        mapped_ids = {r["device_id"] for r in mapped}
        unmapped = [
            {
                "device_id": r["device_id"],
                "last_seen_at": r["last_seen_at"].isoformat() if r["last_seen_at"] else None,
                "warn_count": int(r["warn_count"]),
                "mapped": False,
            }
            for r in unmapped_rows
            if r["device_id"] and r["device_id"] not in mapped_ids
        ]
    return [
        {
            "device_id": r["device_id"],
            "kind": r["kind"],
            "label": r["label"],
            "vehicle_id": str(r["vehicle_id"]) if r["vehicle_id"] else None,
            "vehicle_name": r["vehicle_name"],
            "vehicle_slug": r["vehicle_slug"],
            "first_seen_at": r["first_seen_at"].isoformat() if r["first_seen_at"] else None,
            "last_seen_at": r["last_seen_at"].isoformat() if r["last_seen_at"] else None,
            "mapped": True,
        }
        for r in mapped
    ] + unmapped


class _DeviceMapPayload(__import__("pydantic").BaseModel):
    vehicle_id: str
    kind: str = "wican"
    label: str | None = None


@router.post(
    "/devices/{device_id}/map",
    dependencies=[Depends(require_ingest_token)],
)
async def map_device(
    device_id: str,
    body: _DeviceMapPayload,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Create or update a device → vehicle mapping. Idempotent."""
    from uuid import UUID
    try:
        vid = UUID(body.vehicle_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="vehicle_id must be a UUID") from exc
    async with pool.acquire() as conn:
        veh = await conn.fetchrow("SELECT 1 FROM vehicles WHERE id = $1", vid)
        if veh is None:
            raise HTTPException(status_code=404, detail="vehicle not found")
        await conn.execute(
            """
            INSERT INTO device_vehicle_map (device_id, vehicle_id, kind, label)
            VALUES ($1, $2, $3, $4)
            ON CONFLICT (device_id) DO UPDATE
                SET vehicle_id = EXCLUDED.vehicle_id,
                    kind = EXCLUDED.kind,
                    label = EXCLUDED.label
            """,
            device_id,
            vid,
            body.kind,
            body.label,
        )
    log.info("device mapped: device_id=%s → vehicle_id=%s kind=%s",
             device_id, vid, body.kind)
    return {"device_id": device_id, "vehicle_id": str(vid), "mapped": True}


@router.delete(
    "/devices/{device_id}/map",
    dependencies=[Depends(require_ingest_token)],
)
async def unmap_device(
    device_id: str,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        rowcount = await conn.execute(
            "DELETE FROM device_vehicle_map WHERE device_id = $1",
            device_id,
        )
    log.info("device unmapped: device_id=%s (%s)", device_id, rowcount)
    return {"device_id": device_id, "mapped": False}


@router.post(
    "/cleanup/sliver-trips",
    dependencies=[Depends(require_ingest_token)],
)
async def cleanup_sliver_trips(
    min_km: float = Query(default=0.5, ge=0.0, le=10.0),
    confirm: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """One-shot cleanup of zero-distance / sliver trips.

    The trip detector started rejecting these at v0.1.31 (#49) but
    legacy rows from before that fix linger in the trips table — they
    pollute the trips list and the fleet dashboard's distance averages.
    Same preview/confirm shape as /purge/readings so the user can see
    what's about to disappear before it's gone.
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT id, started_at, distance_km, duration_s
              FROM trips
             WHERE distance_km IS NULL OR distance_km < $1
             ORDER BY started_at ASC
            """,
            min_km,
        )
        if not confirm:
            return {
                "preview": True,
                "min_km": min_km,
                "rows_to_delete": len(rows),
                "samples": [
                    {
                        "id": str(r["id"]),
                        "started_at": r["started_at"].isoformat(),
                        "distance_km": float(r["distance_km"] or 0),
                        "duration_s": int(r["duration_s"] or 0),
                    }
                    for r in rows[:5]
                ],
            }
        deleted = await conn.fetchval(
            """
            WITH d AS (
                DELETE FROM trips
                 WHERE distance_km IS NULL OR distance_km < $1
                 RETURNING 1
            )
            SELECT count(*) FROM d
            """,
            min_km,
        )

    log.info("admin sliver-trip cleanup deleted=%s min_km=%s", deleted, min_km)
    return {"preview": False, "min_km": min_km, "rows_deleted": int(deleted or 0)}


@router.post(
    "/purge/logs",
    dependencies=[Depends(require_ingest_token)],
)
async def purge_logs(
    older_than_days: int | None = Query(default=None, ge=0, le=3650),
    older_than_hours: int | None = Query(default=None, ge=0, le=24 * 365),
    level: str | None = Query(default=None, description="optional level filter (debug/info/warn/error)"),
    confirm: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Drop client_logs older than N days *or* N hours, optionally filtered
    by level. Same two-step preview/confirm shape. Hour granularity lets the
    UI clear today's debug spam without waiting a full 24 hours."""
    if older_than_days is None and older_than_hours is None:
        raise HTTPException(
            status_code=400,
            detail="older_than_days or older_than_hours is required",
        )
    if older_than_hours is not None:
        cutoff = datetime.now(timezone.utc) - timedelta(hours=older_than_hours)
    else:
        assert older_than_days is not None
        cutoff = datetime.now(timezone.utc) - timedelta(days=older_than_days)

    where = ["ts < $1"]
    args: list[Any] = [cutoff]
    if level:
        if level not in ("debug", "info", "warn", "error"):
            raise HTTPException(status_code=400, detail="bad level")
        args.append(level)
        where.append(f"level = ${len(args)}")
    clause = " AND ".join(where)

    async with pool.acquire() as conn:
        affected = await conn.fetchval(
            f"SELECT count(*) FROM client_logs WHERE {clause}",
            *args,
        )
        if not confirm:
            return {
                "preview": True,
                "cutoff_iso": cutoff.isoformat(),
                "level": level,
                "rows_to_delete": int(affected or 0),
            }
        await conn.execute(f"DELETE FROM client_logs WHERE {clause}", *args)

    return {
        "preview": False,
        "cutoff_iso": cutoff.isoformat(),
        "level": level,
        "rows_deleted": int(affected or 0),
    }


@router.post(
    "/trips/reprocess",
    dependencies=[Depends(require_ingest_token)],
)
async def reprocess_trips(
    request: Any = None,  # FastAPI populates via Request injection
    older_than_hours: int = Query(default=24, ge=1, le=24 * 365 * 5),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Re-derive trips for the last N hours using the post-processor.

    Idempotent — uses deterministic UUIDs keyed on (vehicle_id,
    started_at), so running this won't create duplicates. User-set
    fields (category, notes) on the trip rows are preserved.
    """
    from ..config import settings as default_settings
    from ..workers import trip_deriver

    since = datetime.now(timezone.utc) - timedelta(hours=older_than_hours)
    summary = await trip_deriver.derive_window(
        pool, default_settings, since=since
    )
    return {
        "since_iso": since.isoformat(),
        "older_than_hours": older_than_hours,
        "trips_touched_per_vehicle": summary,
        "total_trips_touched": sum(summary.values()),
    }


@router.post(
    "/trips/recompute-phone-batch",
    dependencies=[Depends(require_ingest_token)],
)
async def recompute_phone_batch_trips(
    older_than_hours: int = Query(default=24 * 365 * 5, ge=1, le=24 * 365 * 10),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Recompute the derived stat columns of ``phone_batch`` trips.

    The periodic ``trip_deriver`` deliberately never touches phone-owned
    trips (it only owns ``deriver``-source rows and skips phone_batch /
    manual_merge ranges), so a fix to :func:`compute_trip_stats` — e.g. the
    sparse-GPS ``max(gps, speed)`` distance fix or the coolant-garbage filter
    — never reaches existing phone_batch rows. This re-runs
    ``compute_trip_stats`` over each phone_batch trip's fixed
    ``(started_at, ended_at)`` window and UPDATEs ONLY the derived stat
    columns, preserving id / boundaries / source / category / notes. It is a
    pure read over pid_readings + gps_points — deterministic and safe to run
    repeatedly. ``manual_merge`` trips are intentionally left alone (their
    stats are the user's fold result, not a window recompute).
    """
    from ..workers.trip_stats import compute_trip_stats

    since = datetime.now(timezone.utc) - timedelta(hours=older_than_hours)
    updated = 0
    changed: list[dict[str, Any]] = []
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT id, vehicle_id, started_at, ended_at, distance_km
              FROM trips
             WHERE source = 'phone_batch'
               AND started_at >= $1
               AND ended_at IS NOT NULL
             ORDER BY started_at
            """,
            since,
        )
        for r in rows:
            stats = await compute_trip_stats(
                conn, r["id"], r["vehicle_id"], r["started_at"], r["ended_at"]
            )
            old_km = float(r["distance_km"]) if r["distance_km"] is not None else None
            await conn.execute(
                """
                UPDATE trips SET
                    distance_km = $2, fuel_used_l = $3, max_rpm = $4,
                    max_speed_kph = $5, avg_speed_kph = $6,
                    avg_coolant_c = $7, idle_s = $8
                  WHERE id = $1
                """,
                r["id"], stats["distance_km"], stats["fuel_used_l"],
                stats["max_rpm"], stats["max_speed_kph"], stats["avg_speed_kph"],
                stats["avg_coolant_c"], stats.get("idle_s"),
            )
            updated += 1
            new_km = stats["distance_km"]
            if old_km is not None and new_km is not None and abs(float(new_km) - old_km) > 0.1:
                changed.append({
                    "id": str(r["id"]),
                    "old_km": round(old_km, 2),
                    "new_km": round(float(new_km), 2),
                })
    log.info("admin phone_batch recompute updated=%s changed=%s", updated, len(changed))
    return {
        "since_iso": since.isoformat(),
        "trips_recomputed": updated,
        "distance_changed": changed,
    }


_GITHUB_REPO = os.environ.get("PITSTOP_GITHUB_REPO", "Pr0zak/pitstop")
_GITHUB_RELEASES_URL = f"https://api.github.com/repos/{_GITHUB_REPO}/releases/latest"
_release_cache: dict[str, Any] = {"data": None, "ts": 0.0}
_RELEASE_CACHE_TTL_S = 60


def _version_tuple(v: str) -> tuple[int, ...]:
    """Parse "v0.1.152" / "0.1.152" / "0.1.152-3-gabc" into a sortable tuple.

    Strips a leading 'v' and any -gSHA / -rcN / build suffix. Unparseable
    components fall back to 0 so weird tags don't crash the comparator."""
    s = re.sub(r"^v", "", v)
    s = re.split(r"[-+]", s, 1)[0]
    parts = []
    for p in s.split("."):
        try:
            parts.append(int(p))
        except ValueError:
            parts.append(0)
    return tuple(parts)


async def _fetch_latest_release() -> dict[str, Any]:
    """Hit GitHub's /releases/latest, cached for 60 s.

    Returns {tag_name, name, body, html_url, published_at} or {error}.
    The cache keeps us under GitHub's 60 req/h unauthenticated limit when
    the web UI polls aggressively after kicking an upgrade."""
    now = time.time()
    if _release_cache["data"] and (now - _release_cache["ts"]) < _RELEASE_CACHE_TTL_S:
        return _release_cache["data"]
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(
                _GITHUB_RELEASES_URL,
                headers={"Accept": "application/vnd.github.v3+json"},
            )
        if resp.status_code != 200:
            return {"error": f"GitHub returned {resp.status_code}"}
        data = resp.json()
        result = {
            "tag_name": data.get("tag_name"),
            "name": data.get("name"),
            "body": data.get("body"),
            "html_url": data.get("html_url"),
            "published_at": data.get("published_at"),
        }
        _release_cache["data"] = result
        _release_cache["ts"] = now
        return result
    except Exception as exc:
        log.warning("github releases fetch failed: %s", exc)
        return {"error": str(exc)}


@router.get(
    "/updates",
    dependencies=[Depends(require_query_token)],
)
async def check_updates() -> dict[str, Any]:
    """Compare running version to GitHub's latest release tag.

    Mirrors Zonik's /api/updates pattern: returns update_available + the
    release metadata so the UI can render a changelog before the user
    pulls the trigger. Cached for 60 s server-side."""
    release = await _fetch_latest_release()
    current = VERSION
    if "error" in release:
        return {
            "update_available": False,
            "current_version": current,
            "current_sha": GIT_SHA,
            "error": release["error"],
        }
    latest = release.get("tag_name") or ""
    update_available = False
    if latest and current not in ("dev", "0.0.0") and not current.startswith("0.0.0"):
        try:
            update_available = _version_tuple(current) < _version_tuple(latest)
        except Exception:
            update_available = False
    return {
        "update_available": update_available,
        "current_version": current,
        "current_sha": GIT_SHA,
        "latest_version": latest,
        "latest_name": release.get("name"),
        "latest_body": release.get("body"),
        "latest_url": release.get("html_url"),
        "latest_published_at": release.get("published_at"),
    }


# In-memory upgrade job state. Single-slot — only one upgrade can be
# in flight, and the API process is about to be torn down anyway when
# `docker compose up -d` recreates the backend container.
_upgrade_job: dict[str, Any] = {
    "status": "idle",  # idle | running | failed
    "target": None,
    "started_at": None,
    "error": None,
}


def _docker_socket_available() -> bool:
    """Whether the backend container can talk to the host docker daemon.

    True when /var/run/docker.sock is mounted in. The /admin/upgrade
    endpoint refuses to run without this — pre-migration CTs would
    otherwise return a confusing "docker: command not found" 500."""
    return os.path.exists("/var/run/docker.sock") and os.access(
        "/var/run/docker.sock", os.W_OK | os.R_OK
    )


async def _db_alembic_head(pool: asyncpg.Pool) -> str | None:
    """The revision the database is currently stamped at, or None."""
    try:
        async with pool.acquire() as conn:
            return await conn.fetchval("SELECT version_num FROM alembic_version")
    except Exception as exc:  # table absent on a never-migrated DB
        log.warning("could not read alembic_version: %s", exc)
        return None


async def _image_has_revision(image: str, revision: str) -> tuple[bool, str]:
    """Whether `image` ships the alembic revision the DB is stamped at.

    Returns (ok, detail). `ok` is True when the revision file is present
    OR when we could not determine it — an inconclusive probe must not
    block a legitimate upgrade, since the alternative is an unusable
    Update button. Only a definite "the image does not contain it" stops
    the flow.

    Why this exists: the upgrader pulls a published image and restarts
    the backend against the EXISTING database. If that image's migration
    set predates the DB's current head, alembic aborts at boot with
    "Can't locate revision identified by ..." and the container
    crash-loops forever. There is no rollback, and the sidecar is
    detached, so nothing survives to report it — the frontend keeps
    serving from Caddy and the outage is invisible. Checking here, while
    the current backend is still alive, is the only point where the
    failure can still be handed back to the user as an error.

    Real incident (2026-08-10): an unreleased 0022 migration had been
    applied by hand, then an in-app update to the then-latest v0.1.233
    pulled an image containing only 0018-0021. 33 hours of lost ingest.
    """
    cmd = [
        "docker", "run", "--rm", "--entrypoint", "sh", image,
        "-c", "ls /app/alembic/versions/",
    ]
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
        stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=300)
    except (asyncio.TimeoutError, FileNotFoundError, OSError) as exc:
        return True, f"revision probe skipped: {exc}"
    out = (stdout or b"").decode(errors="replace")
    if proc.returncode != 0:
        return True, f"revision probe skipped (exit {proc.returncode})"
    files = {line.strip() for line in out.splitlines() if line.strip()}
    if not files:
        return True, "revision probe skipped (no versions dir)"
    if f"{revision}.py" in files:
        return True, "ok"
    return False, (
        f"target image does not contain migration '{revision}', which this "
        f"database is currently stamped at. Upgrading would leave the backend "
        f"unable to boot. Pick a release that includes it."
    )


@router.post(
    "/upgrade",
    dependencies=[Depends(require_ingest_token)],
)
async def trigger_upgrade(
    target: str | None = Query(default=None, description="release tag, e.g. v0.1.152"),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Kick the upgrade flow.

    If `target` is omitted, we pull the latest GitHub release tag. We
    then spawn a detached `docker:cli` sidecar container that runs
    `deploy/upgrade.sh <tag>`. The sidecar bind-mounts /opt/pitstop and
    the docker socket, so it can `docker compose pull && up -d`. It
    survives our own container's restart — without that decoupling the
    `up -d backend` step would kill the process running it."""
    if not _docker_socket_available():
        raise HTTPException(
            status_code=503,
            detail=(
                "/var/run/docker.sock not mounted in the backend container. "
                "Run the CT migration first (deploy/migrate-image-pinned.sh)."
            ),
        )
    # Self-heal the single-slot job state — the sidecar runs detached so
    # we never directly see it exit; rely on time + version drift instead.
    if _upgrade_job["status"] == "running":
        target_seen = (_upgrade_job.get("target") or "").lstrip("v")
        current_seen = VERSION.lstrip("v")
        started_iso = _upgrade_job.get("started_at")
        age_min = 999.0
        if started_iso:
            try:
                started = datetime.fromisoformat(started_iso)
                age_min = (datetime.now(timezone.utc) - started).total_seconds() / 60
            except ValueError:
                pass
        if target_seen == current_seen or age_min > 10:
            log.info(
                "self-healing stale upgrade job (target=%s current=%s age_min=%.1f)",
                target_seen, current_seen, age_min,
            )
            _upgrade_job["status"] = "idle"
        else:
            raise HTTPException(
                status_code=409, detail="upgrade already in progress"
            )

    if target is None:
        release = await _fetch_latest_release()
        if "error" in release:
            raise HTTPException(
                status_code=502, detail=f"GitHub: {release['error']}"
            )
        target = release.get("tag_name")
        if not target:
            raise HTTPException(status_code=502, detail="no latest release tag found")

    if not re.fullmatch(r"v?\d+\.\d+\.\d+", target):
        raise HTTPException(status_code=400, detail="bad target tag format")

    # Schema-compatibility preflight. Must happen here — synchronously,
    # before the sidecar is spawned — because the sidecar is detached and
    # replaces this very process. Once a bad image is running there is
    # nothing left alive to surface the error. See _image_has_revision.
    head = await _db_alembic_head(pool)
    if head:
        owner = os.environ.get("GHCR_OWNER", "pr0zak")
        image = f"ghcr.io/{owner}/pitstop-backend:{target.lstrip('v')}"
        ok, detail = await _image_has_revision(image, head)
        if not ok:
            log.error("upgrade to %s blocked: %s", target, detail)
            _upgrade_job["status"] = "failed"
            _upgrade_job["target"] = target
            _upgrade_job["error"] = detail
            raise HTTPException(status_code=409, detail=detail)
        log.info("upgrade preflight for %s at head %s: %s", target, head, detail)

    host_dir = os.environ.get("PITSTOP_HOST_DIR", "/opt/pitstop")
    # Spawn the upgrader sidecar. `--rm -d` = detached + auto-clean.
    # We pass the target as an env var so the upgrade.sh inside is short.
    cmd = [
        "docker",
        "run",
        "--rm",
        "-d",
        "--name",
        f"pitstop-upgrader-{int(time.time())}",
        "-v",
        "/var/run/docker.sock:/var/run/docker.sock",
        "-v",
        f"{host_dir}:/work",
        "-w",
        "/work",
        "-e",
        f"TARGET_TAG={target}",
        "docker:27-cli",
        "sh",
        "/work/deploy/upgrade.sh",
    ]
    log.info("kicking upgrader: %s", shlex.join(cmd))
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
        stdout, _ = await proc.communicate()
        out = (stdout or b"").decode(errors="replace").strip()
        if proc.returncode != 0:
            _upgrade_job["status"] = "failed"
            _upgrade_job["error"] = out
            raise HTTPException(
                status_code=500, detail=f"docker run failed: {out[:500]}"
            )
    except FileNotFoundError as exc:
        raise HTTPException(
            status_code=503,
            detail="docker CLI not installed in backend image",
        ) from exc

    _upgrade_job["status"] = "running"
    _upgrade_job["target"] = target
    _upgrade_job["started_at"] = datetime.now(timezone.utc).isoformat()
    _upgrade_job["error"] = None
    log.info("upgrade kicked: target=%s sidecar=%s", target, out)
    return {
        "status": "running",
        "target": target,
        "current": VERSION,
        "sidecar_container": out,
    }


@router.get(
    "/upgrade/status",
    dependencies=[Depends(require_query_token)],
)
async def upgrade_status() -> dict[str, Any]:
    """Cheap status probe. The frontend mostly polls /version directly to
    detect when the new image has taken over — this endpoint just reports
    what _this_ (possibly soon-to-die) backend remembers about the kick."""
    return {
        **_upgrade_job,
        "current_version": VERSION,
        "docker_socket": _docker_socket_available(),
    }
