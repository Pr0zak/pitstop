"""Import endpoints. Currently: Fuelio CSV multipart upload."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, UploadFile
from fastapi import File as FastAPIFile

from ..auth import require_ingest_token
from ..services.fuelio_import import run_import

log = logging.getLogger(__name__)

router = APIRouter(prefix="/import", tags=["import"])

# Module-level singletons so ruff's B008 isn't flagged on the route signature.
_FILES = FastAPIFile(...)
_DRY_RUN = Query(default=False)
_ATTACH_VEHICLE_SLUG = Query(
    default=None,
    description=(
        "If set, attach all imported fillups/costs to the existing vehicle "
        "with this slug instead of upserting from a Vehicle section. Use this "
        "for Fuelio per-vehicle sync exports (vehicle-N-sync.csv) which don't "
        "include a Vehicle row."
    ),
)


@router.post("/fuelio", dependencies=[Depends(require_ingest_token)])
async def fuelio_import(
    request: Request,
    files: list[UploadFile] = _FILES,
    dry_run: bool = _DRY_RUN,
    attach_vehicle_slug: str | None = _ATTACH_VEHICLE_SLUG,
) -> dict[str, Any]:
    """Import one or more Fuelio CSV/zip exports.

    Accepts ``.zip`` (one CSV inside) or raw ``.csv``. Multiple files in one
    request are imported in a single transaction (or rolled back as one for
    dry-run).

    ``attach_vehicle_slug`` lets the user route all rows to an existing
    vehicle (matched by slug) — this is the path for Fuelio's per-vehicle
    sync export, which omits the Vehicle section.
    """
    pool = getattr(request.app.state, "pg_pool", None)
    if pool is None:
        raise HTTPException(status_code=503, detail="db pool not ready")

    blobs: list[tuple[str, bytes]] = []
    for upload in files:
        data = await upload.read()
        blobs.append((upload.filename or "upload.csv", data))

    async with pool.acquire() as conn:
        result = await run_import(
            conn,
            blobs,
            dry_run=dry_run,
            attach_vehicle_slug=attach_vehicle_slug,
        )

    return result.to_dict()
