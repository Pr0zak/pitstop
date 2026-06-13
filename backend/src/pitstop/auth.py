import logging
import secrets

from fastapi import Header, HTTPException, status

from .config import settings

log = logging.getLogger(__name__)

# Fail-open is intentional but dangerous: a blank token disables auth for
# that scope entirely. Warn loudly at import time so a misconfigured deploy
# is obvious in the logs rather than silently unauthenticated.
if not settings.ingest_token:
    log.warning(
        "INGEST_TOKEN is blank — ingest endpoints are UNAUTHENTICATED "
        "(fail-open). Set INGEST_TOKEN in .env to require a bearer token."
    )
if not settings.query_token:
    log.warning(
        "QUERY_TOKEN is blank — query endpoints are UNAUTHENTICATED "
        "(fail-open). Set QUERY_TOKEN in .env to require a bearer token."
    )


def _token_ok(authorization: str | None, expected: str) -> bool:
    """Constant-time bearer-token check (timing-attack resistant)."""
    if not authorization:
        return False
    return secrets.compare_digest(authorization, f"Bearer {expected}")


def require_ingest_token(authorization: str | None = Header(default=None)) -> None:
    if not settings.ingest_token:
        return
    if not _token_ok(authorization, settings.ingest_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid ingest token"
        )


def require_query_token(authorization: str | None = Header(default=None)) -> None:
    if not settings.query_token:
        return
    if not _token_ok(authorization, settings.query_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid query token"
        )
