"""Misc one-off endpoints. Currently: short-link redirect resolver for
the Settings → Home location flow.

Browsers can't follow a cross-origin redirect and read the final URL,
so we expose a tiny server-side helper that GETs an allowlisted shortener
and returns the long URL. The frontend then re-runs its lat/lon parser
on the result.

Allowlist is tight on purpose — this is not a generic URL fetcher.
"""
from __future__ import annotations

import re
from typing import Annotated

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query

from ..auth import require_query_token

router = APIRouter()

# Tight allowlist of shorteners we resolve. Add carefully.
_ALLOWED_HOSTS = (
    "maps.app.goo.gl",
    "goo.gl",
    "g.co",
    "g.page",
    "apple.co",
    "osm.org",
)
_HTTP_URL = re.compile(r"^https://[A-Za-z0-9.\-]+(/[^\s]*)?$")
_MAX_HOPS = 5
_TIMEOUT_S = 5.0


def _host_allowed(url: str) -> bool:
    m = re.match(r"^https://([A-Za-z0-9.\-]+)", url)
    if not m:
        return False
    host = m.group(1).lower()
    return host in _ALLOWED_HOSTS or any(host.endswith("." + h) for h in _ALLOWED_HOSTS)


@router.get("/utils/resolve-url", dependencies=[Depends(require_query_token)])
async def resolve_url(
    url: Annotated[str, Query(min_length=10, max_length=500)],
) -> dict[str, str | int]:
    if not _HTTP_URL.match(url):
        raise HTTPException(status_code=400, detail="url must be https://...")
    if not _host_allowed(url):
        raise HTTPException(
            status_code=400,
            detail=(
                "host not in resolver allowlist "
                f"({', '.join(_ALLOWED_HOSTS)})"
            ),
        )

    current = url
    hops = 0
    async with httpx.AsyncClient(
        follow_redirects=False, timeout=_TIMEOUT_S
    ) as client:
        while hops < _MAX_HOPS:
            try:
                resp = await client.head(current)
            except httpx.HTTPError as e:
                raise HTTPException(
                    status_code=502, detail=f"upstream error: {e}"
                ) from e
            if resp.status_code in (301, 302, 303, 307, 308):
                loc = resp.headers.get("location")
                if not loc:
                    break
                # Resolve relative location
                if loc.startswith("/"):
                    m = re.match(r"^(https://[^/]+)", current)
                    loc = (m.group(1) if m else "") + loc
                current = loc
                hops += 1
                continue
            break

    return {"resolved": current, "hops": hops}
