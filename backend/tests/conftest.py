"""Shared test fixtures.

Provides:

- ``test_app``: a FastAPI app with the production routers mounted but a
  lifespan that only opens the asyncpg pool — no MQTT/trip workers spun up.
  Skipped if no test Postgres is configured.
- ``client``: a synchronous FastAPI ``TestClient`` against ``test_app``,
  used inside a ``with`` block so the app's lifespan runs.
- ``ingest_token`` / ``query_token``: the configured tokens at test time
  (auth is on by default in tests).
- ``pg_pool``: direct asyncpg pool for tests that want to seed/clean rows.
"""

from __future__ import annotations

import os
import warnings
from collections.abc import AsyncIterator, Iterator
from contextlib import asynccontextmanager

import asyncpg
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient


def _has_pg_env() -> bool:
    return bool(os.environ.get("POSTGRES_HOST")) and bool(
        os.environ.get("POSTGRES_PORT")
    )


def _dsn() -> str:
    return (
        f"postgresql://{os.environ['POSTGRES_USER']}:"
        f"{os.environ['POSTGRES_PASSWORD']}@"
        f"{os.environ['POSTGRES_HOST']}:{os.environ['POSTGRES_PORT']}/"
        f"{os.environ['POSTGRES_DB']}"
    )


@pytest.fixture(scope="session")
def ingest_token() -> str:
    return "test-ingest-token"


@pytest.fixture(scope="session")
def query_token() -> str:
    return "test-query-token"


@pytest.fixture(autouse=True)
def _set_token_env(monkeypatch, ingest_token: str, query_token: str) -> None:
    """Ensure pitstop.config.settings sees the test tokens."""
    monkeypatch.setenv("INGEST_TOKEN", ingest_token)
    monkeypatch.setenv("QUERY_TOKEN", query_token)
    # Re-instantiate settings to pick up the env override. The auth helpers
    # read the singleton, so we hot-patch its fields.
    from pitstop import auth, config

    fresh = config.Settings()
    config.settings.ingest_token = fresh.ingest_token
    config.settings.query_token = fresh.query_token
    auth.settings.ingest_token = fresh.ingest_token
    auth.settings.query_token = fresh.query_token


@pytest.fixture
def test_app() -> FastAPI:
    """A FastAPI app mirroring production routes but with a slim lifespan
    that only opens an asyncpg pool — no MQTT/trip workers."""
    if not _has_pg_env():
        pytest.skip("no test Postgres configured")

    from pitstop.api import (
        dtcs,
        health,
        imports,
        live_ws,
        profiles,
        readings,
    )
    from pitstop.api import settings as settings_api
    from pitstop.api import trips as trips_api
    from pitstop.api import vehicles as vehicles_api
    from pitstop.workers.bus import EventBus

    @asynccontextmanager
    async def slim_lifespan(app: FastAPI) -> AsyncIterator[None]:
        pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=4)
        app.state.pg_pool = pool
        # Per-test bus so live_ws fixtures don't leak across tests.
        app.state.bus = EventBus(queue_maxsize=100)
        try:
            yield
        finally:
            await pool.close()

    app = FastAPI(title="pitstop-test", lifespan=slim_lifespan)
    app.include_router(health.router)
    app.include_router(imports.router)
    app.include_router(vehicles_api.router)
    app.include_router(profiles.router)
    app.include_router(readings.router)
    app.include_router(trips_api.router)
    app.include_router(dtcs.router)
    app.include_router(settings_api.router)
    app.include_router(live_ws.router)
    return app


@pytest.fixture
def client(test_app: FastAPI) -> Iterator[TestClient]:
    # Suppress the asyncio "Unclosed event loop" deprecation noise when
    # running tests synchronously through TestClient.
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", DeprecationWarning)
        with TestClient(test_app) as c:
            yield c


@pytest.fixture
async def pg_pool() -> AsyncIterator[asyncpg.Pool]:
    if not _has_pg_env():
        pytest.skip("no test Postgres configured")
    pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
    try:
        yield pool
    finally:
        await pool.close()


@pytest.fixture
async def cleanup_test_vehicles(pg_pool: asyncpg.Pool) -> AsyncIterator[None]:
    """Delete any vehicles whose slug starts with 'apitest-' before/after."""
    async def _purge() -> None:
        async with pg_pool.acquire() as conn:
            await conn.execute(
                "DELETE FROM vehicles WHERE slug LIKE 'apitest-%'"
            )

    await _purge()
    try:
        yield
    finally:
        await _purge()


# Helpful re-export
__all__ = ["_has_pg_env", "_dsn"]
