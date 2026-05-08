"""PID profile CRUD endpoints."""

from __future__ import annotations

import json
import logging
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException
from fastapi import Path as FastAPIPath

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import PidProfileBrief, PidProfileCreate, PidProfileOut, PidProfileUpdate

log = logging.getLogger(__name__)

router = APIRouter(prefix="/profiles", tags=["profiles"])


def _profile_jsonb(row: asyncpg.Record) -> dict[str, Any]:
    """asyncpg returns JSONB as either dict or str depending on codec setup."""
    raw = row["profile"]
    if isinstance(raw, str):
        return json.loads(raw)
    return raw


@router.get(
    "",
    response_model=list[PidProfileBrief],
    dependencies=[Depends(require_query_token)],
)
async def list_profiles(pool: asyncpg.Pool = Depends(get_pool)) -> list[dict[str, Any]]:
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, name, description FROM pid_profiles ORDER BY name ASC"
        )
    return [
        {"id": r["id"], "name": r["name"], "description": r["description"]}
        for r in rows
    ]


@router.get(
    "/{profile_id}",
    response_model=PidProfileOut,
    dependencies=[Depends(require_query_token)],
)
async def get_profile(
    profile_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT id, name, description, profile, created_at, updated_at "
            "FROM pid_profiles WHERE id = $1",
            profile_id,
        )
    if row is None:
        raise HTTPException(status_code=404, detail="profile not found")
    return {
        "id": row["id"],
        "name": row["name"],
        "description": row["description"],
        "profile": _profile_jsonb(row),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


@router.post(
    "",
    response_model=PidProfileOut,
    status_code=201,
    dependencies=[Depends(require_ingest_token)],
)
async def create_profile(
    body: PidProfileCreate,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        try:
            row = await conn.fetchrow(
                """
                INSERT INTO pid_profiles (name, description, profile)
                VALUES ($1, $2, $3::jsonb)
                RETURNING id, name, description, profile, created_at, updated_at
                """,
                body.name,
                body.description,
                json.dumps(body.profile),
            )
        except asyncpg.UniqueViolationError as exc:
            raise HTTPException(
                status_code=409, detail="profile name already exists"
            ) from exc
    assert row is not None
    return {
        "id": row["id"],
        "name": row["name"],
        "description": row["description"],
        "profile": _profile_jsonb(row),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


@router.put(
    "/{profile_id}",
    response_model=PidProfileOut,
    dependencies=[Depends(require_ingest_token)],
)
async def replace_profile(
    body: PidProfileUpdate,
    profile_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            UPDATE pid_profiles
               SET description = COALESCE($2, description),
                   profile = $3::jsonb,
                   updated_at = now()
             WHERE id = $1
             RETURNING id, name, description, profile, created_at, updated_at
            """,
            profile_id,
            body.description,
            json.dumps(body.profile),
        )
    if row is None:
        raise HTTPException(status_code=404, detail="profile not found")
    return {
        "id": row["id"],
        "name": row["name"],
        "description": row["description"],
        "profile": _profile_jsonb(row),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


@router.delete(
    "/{profile_id}",
    status_code=204,
    dependencies=[Depends(require_ingest_token)],
)
async def delete_profile(
    profile_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> None:
    async with pool.acquire() as conn:
        result = await conn.execute(
            "DELETE FROM pid_profiles WHERE id = $1", profile_id
        )
    if result.endswith(" 0"):
        raise HTTPException(status_code=404, detail="profile not found")
