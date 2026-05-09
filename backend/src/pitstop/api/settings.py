"""Settings endpoint — singleton row at id=1.

The HA mirror config is persisted but the worker stays dormant in this task.
The token is never returned in plaintext: callers see ``token_set: bool``
only.
"""

from __future__ import annotations

import json
import logging
from typing import Any

import asyncpg
import httpx
from fastapi import APIRouter, Depends, HTTPException

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import SettingsOut, SettingsUpdate

log = logging.getLogger(__name__)

router = APIRouter(prefix="/settings", tags=["settings"])


def _row_to_settings(row: asyncpg.Record) -> dict[str, Any]:
    toggles = row["ha_per_pid_toggles"]
    if isinstance(toggles, str):
        toggles = json.loads(toggles)
    return {
        "ha": {
            "enabled": row["ha_enabled"],
            "url": row["ha_url"],
            "token_set": bool(row["ha_token"]),
            "discovery_prefix": row["ha_discovery_prefix"],
            "per_pid_toggles": toggles or {},
        },
        "home": {"lat": row["home_lat"], "lon": row["home_lon"]},
        "disk_alert_pct": row["disk_alert_pct"],
        "retention_readings_days": row["retention_readings_days"],
        "retention_logs_days": row["retention_logs_days"],
    }


async def _fetch_settings(conn: asyncpg.Connection) -> dict[str, Any]:
    row = await conn.fetchrow(
        "SELECT ha_enabled, ha_url, ha_token, ha_discovery_prefix, "
        "       ha_per_pid_toggles, home_lat, home_lon, disk_alert_pct, "
        "       retention_readings_days, retention_logs_days "
        "FROM settings WHERE id = 1"
    )
    if row is None:
        # Initial migration seeds id=1, but be defensive on a stale DB.
        await conn.execute("INSERT INTO settings (id) VALUES (1)")
        row = await conn.fetchrow(
            "SELECT ha_enabled, ha_url, ha_token, ha_discovery_prefix, "
            "       ha_per_pid_toggles, home_lat, home_lon, disk_alert_pct "
            "FROM settings WHERE id = 1"
        )
    assert row is not None
    return _row_to_settings(row)


@router.get(
    "",
    response_model=SettingsOut,
    dependencies=[Depends(require_query_token)],
)
async def get_settings(pool: asyncpg.Pool = Depends(get_pool)) -> dict[str, Any]:
    async with pool.acquire() as conn:
        return await _fetch_settings(conn)


@router.patch(
    "",
    response_model=SettingsOut,
    dependencies=[Depends(require_ingest_token)],
)
async def patch_settings(
    body: SettingsUpdate,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    set_parts: list[str] = []
    args: list[Any] = []

    if body.ha is not None:
        ha = body.ha
        ha_fields = ha.model_dump(exclude_unset=True)
        if "enabled" in ha_fields:
            args.append(ha_fields["enabled"])
            set_parts.append(f"ha_enabled = ${len(args)}")
        if "url" in ha_fields:
            args.append(ha_fields["url"])
            set_parts.append(f"ha_url = ${len(args)}")
        if "token" in ha_fields:
            tok = ha_fields["token"]
            # "" or None clears the token.
            args.append(tok if tok else None)
            set_parts.append(f"ha_token = ${len(args)}")
        if "discovery_prefix" in ha_fields:
            args.append(ha_fields["discovery_prefix"])
            set_parts.append(f"ha_discovery_prefix = ${len(args)}")
        if "per_pid_toggles" in ha_fields:
            args.append(json.dumps(ha_fields["per_pid_toggles"]))
            set_parts.append(f"ha_per_pid_toggles = ${len(args)}::jsonb")

    if body.home is not None:
        home_fields = body.home.model_dump(exclude_unset=True)
        if "lat" in home_fields:
            args.append(home_fields["lat"])
            set_parts.append(f"home_lat = ${len(args)}")
        if "lon" in home_fields:
            args.append(home_fields["lon"])
            set_parts.append(f"home_lon = ${len(args)}")

    if body.disk_alert_pct is not None:
        args.append(body.disk_alert_pct)
        set_parts.append(f"disk_alert_pct = ${len(args)}")

    # Retention thresholds. NULL clears the auto-purge for that stream;
    # 0 also clears (worker treats > 0 as "active").
    update_fields = body.model_dump(exclude_unset=True)
    if "retention_readings_days" in update_fields:
        v = update_fields["retention_readings_days"]
        args.append(v)
        set_parts.append(f"retention_readings_days = ${len(args)}")
    if "retention_logs_days" in update_fields:
        v = update_fields["retention_logs_days"]
        args.append(v)
        set_parts.append(f"retention_logs_days = ${len(args)}")

    async with pool.acquire() as conn:
        if set_parts:
            sql = (
                "UPDATE settings SET "
                + ", ".join(set_parts)
                + ", updated_at = now() WHERE id = 1"
            )
            await conn.execute(sql, *args)
        return await _fetch_settings(conn)


@router.post(
    "/ha/test",
    dependencies=[Depends(require_ingest_token)],
)
async def test_ha_connection(
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT ha_enabled, ha_url, ha_token FROM settings WHERE id = 1"
        )
    if row is None or not row["ha_enabled"]:
        raise HTTPException(status_code=400, detail="ha not enabled")
    if not row["ha_url"] or not row["ha_token"]:
        raise HTTPException(status_code=400, detail="ha url/token missing")

    headers = {
        "Authorization": f"Bearer {row['ha_token']}",
        "Content-Type": "application/json",
    }
    target = row["ha_url"].rstrip("/") + "/api/"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(target, headers=headers)
        return {"ok": resp.status_code < 400, "status_code": resp.status_code}
    except httpx.HTTPError as exc:
        log.warning("HA test failed: %s", exc)
        return {"ok": False, "status_code": None, "error": str(exc)}
