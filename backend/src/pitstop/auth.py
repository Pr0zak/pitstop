from fastapi import Header, HTTPException, status

from .config import settings


def require_ingest_token(authorization: str | None = Header(default=None)) -> None:
    if not settings.ingest_token:
        return
    if authorization != f"Bearer {settings.ingest_token}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid ingest token")


def require_query_token(authorization: str | None = Header(default=None)) -> None:
    if not settings.query_token:
        return
    if authorization != f"Bearer {settings.query_token}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid query token")
