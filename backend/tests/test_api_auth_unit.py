"""Unit tests for the auth helpers — no DB required.

Covers token presence, malformed bearer header, and the case where a token
is empty (auth disabled). The full-stack auth test against real endpoints
lives in test_api_endpoints.py and is gated on a Postgres env.
"""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from pitstop import auth
from pitstop.config import settings


@pytest.fixture
def with_tokens(monkeypatch):
    monkeypatch.setattr(settings, "ingest_token", "ingest-secret")
    monkeypatch.setattr(settings, "query_token", "query-secret")
    yield


def test_ingest_accepts_valid(with_tokens) -> None:
    auth.require_ingest_token(authorization="Bearer ingest-secret")


def test_ingest_rejects_wrong_token(with_tokens) -> None:
    with pytest.raises(HTTPException) as ei:
        auth.require_ingest_token(authorization="Bearer nope")
    assert ei.value.status_code == 401


def test_ingest_rejects_missing_header(with_tokens) -> None:
    with pytest.raises(HTTPException) as ei:
        auth.require_ingest_token(authorization=None)
    assert ei.value.status_code == 401


def test_query_accepts_valid(with_tokens) -> None:
    auth.require_query_token(authorization="Bearer query-secret")


def test_query_rejects_wrong_token(with_tokens) -> None:
    with pytest.raises(HTTPException) as ei:
        auth.require_query_token(authorization="Bearer ingest-secret")
    assert ei.value.status_code == 401


def test_no_token_disables_auth(monkeypatch) -> None:
    monkeypatch.setattr(settings, "ingest_token", "")
    monkeypatch.setattr(settings, "query_token", "")
    # Both helpers no-op when the token is empty.
    auth.require_ingest_token(authorization=None)
    auth.require_query_token(authorization=None)
