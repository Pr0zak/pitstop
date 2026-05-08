"""End-to-end API tests for the centralized client-log depot.

DB-backed via the shared ``test_app`` fixture (auto-skips when no Postgres
env is configured). Each test cleans up after itself by deleting any rows
it created — we filter on ``source='test'`` to keep the radius narrow.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from urllib.parse import quote

import pytest

pytestmark = [pytest.mark.usefixtures("test_app")]


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
async def cleanup_test_logs(pg_pool) -> AsyncIterator[None]:
    """Wipe any source='test' / 'phone' / 'web' rows before and after each
    test. We narrowly target the sources our tests use rather than
    truncating the whole table — backend self-emitted rows shouldn't be
    perturbed by a test run."""

    async def _purge() -> None:
        async with pg_pool.acquire() as conn:
            await conn.execute(
                "DELETE FROM client_logs WHERE source IN ('test','phone','web')"
            )

    await _purge()
    try:
        yield
    finally:
        await _purge()


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------


def test_logs_post_rejects_missing_token(client) -> None:
    r = client.post(
        "/logs",
        json={"entries": [{"source": "test", "level": "info", "message": "x"}]},
    )
    assert r.status_code == 401


def test_logs_post_rejects_query_token(client, query_token: str) -> None:
    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {query_token}"},
        json={"entries": [{"source": "test", "level": "info", "message": "x"}]},
    )
    assert r.status_code == 401


def test_logs_recent_rejects_missing_token(client) -> None:
    r = client.get("/logs/recent")
    assert r.status_code == 401


def test_logs_recent_rejects_ingest_token(client, ingest_token: str) -> None:
    r = client.get(
        "/logs/recent",
        headers={"Authorization": f"Bearer {ingest_token}"},
    )
    assert r.status_code == 401


def test_logs_delete_rejects_missing_token(client) -> None:
    r = client.delete("/logs/older-than?days=7")
    assert r.status_code == 401


# ---------------------------------------------------------------------------
# POST batch
# ---------------------------------------------------------------------------


def test_logs_post_happy_path(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    payload = {
        "entries": [
            {
                "source": "test",
                "level": "info",
                "message": "hello",
                "device_id": "test-device-001",
                "context": {"k": 1},
            },
            {
                "source": "test",
                "level": "warn",
                "message": "uh oh",
                "context": {"reason": "demo"},
            },
        ]
    }
    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json=payload,
    )
    assert r.status_code == 200, r.text
    assert r.json() == {"accepted": 2}

    r = client.get(
        "/logs/recent?source=test&limit=10",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    out = r.json()
    msgs = [row["message"] for row in out]
    assert "hello" in msgs
    assert "uh oh" in msgs
    # Newest first.
    assert out[0]["ts"] >= out[-1]["ts"]


def test_logs_post_preserves_client_ts(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    client_ts = (datetime.now(UTC) - timedelta(hours=2)).isoformat()
    payload = {
        "entries": [
            {
                "source": "test",
                "level": "info",
                "message": "timestamped",
                "ts": client_ts,
            }
        ]
    }
    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json=payload,
    )
    assert r.status_code == 200

    # Look back 24h since client_ts is 2h old but server ts is now.
    r = client.get(
        "/logs/recent?source=test&q=timestamped&limit=5",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    out = r.json()
    assert len(out) == 1
    row = out[0]
    assert row["client_ts"] is not None
    # Server ts is "now" (within ~1 minute), client_ts is ~2h ago.
    server_ts = datetime.fromisoformat(row["ts"])
    cts = datetime.fromisoformat(row["client_ts"])
    assert (server_ts - cts) > timedelta(minutes=30)


def test_logs_post_rejects_unknown_source(
    client, ingest_token, cleanup_test_logs
) -> None:
    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "entries": [
                {
                    "source": "martian",
                    "level": "info",
                    "message": "from outer space",
                }
            ]
        },
    )
    # Pydantic Literal rejection → 422 from FastAPI body validation.
    assert r.status_code == 422


def test_logs_post_rejects_unknown_level(
    client, ingest_token, cleanup_test_logs
) -> None:
    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "entries": [
                {
                    "source": "test",
                    "level": "fatal",
                    "message": "nope",
                }
            ]
        },
    )
    assert r.status_code == 422


def test_logs_post_too_many_entries_413(
    client, ingest_token, cleanup_test_logs
) -> None:
    # Try 501 entries — Pydantic's max_length=500 will catch it as 422,
    # which is still a "rejected" outcome. The route also has a 413 guard
    # for the case where Pydantic constraint is loosened in the future.
    entries = [
        {"source": "test", "level": "info", "message": f"row {i}"}
        for i in range(501)
    ]
    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"entries": entries},
    )
    assert r.status_code in (413, 422)


def test_logs_post_empty_batch_422(
    client, ingest_token, cleanup_test_logs
) -> None:
    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"entries": []},
    )
    assert r.status_code == 422


# ---------------------------------------------------------------------------
# GET filter combinations
# ---------------------------------------------------------------------------


def _seed_filter_logs(client, ingest_token: str) -> None:
    """Insert a small mixed batch we can filter against."""
    entries = [
        {"source": "test", "level": "info", "message": "alpha info"},
        {"source": "test", "level": "warn", "message": "beta warn"},
        {"source": "test", "level": "error", "message": "gamma error"},
        {"source": "phone", "level": "info", "message": "phone alpha"},
        {"source": "web", "level": "warn", "message": "web beta"},
    ]
    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"entries": entries},
    )
    assert r.status_code == 200, r.text


def test_logs_filter_by_source(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    _seed_filter_logs(client, ingest_token)
    r = client.get(
        "/logs/recent?source=phone&limit=50",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    rows = r.json()
    assert all(row["source"] == "phone" for row in rows)
    assert any(row["message"] == "phone alpha" for row in rows)


def test_logs_filter_by_multi_source(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    _seed_filter_logs(client, ingest_token)
    r = client.get(
        "/logs/recent?source=phone,web&limit=50",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    rows = r.json()
    sources = {row["source"] for row in rows}
    assert sources <= {"phone", "web"}
    assert {"phone", "web"} <= sources


def test_logs_filter_by_level(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    _seed_filter_logs(client, ingest_token)
    r = client.get(
        "/logs/recent?source=test&level=warn,error&limit=50",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    rows = r.json()
    levels = {row["level"] for row in rows}
    assert levels <= {"warn", "error"}
    assert "info" not in levels


def test_logs_filter_by_q(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    _seed_filter_logs(client, ingest_token)
    r = client.get(
        "/logs/recent?source=test&q=gamma&limit=50",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    rows = r.json()
    assert len(rows) == 1
    assert rows[0]["message"] == "gamma error"


def test_logs_filter_by_time(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    _seed_filter_logs(client, ingest_token)
    # Window in the deep past should return nothing.
    far_past = quote((datetime.now(UTC) - timedelta(days=10)).isoformat())
    far_past_end = quote((datetime.now(UTC) - timedelta(days=9)).isoformat())
    r = client.get(
        f"/logs/recent?source=test&from={far_past}&to={far_past_end}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    assert r.json() == []


def test_logs_filter_by_vehicle(
    client, ingest_token, query_token, cleanup_test_vehicles, cleanup_test_logs
) -> None:
    # Need a real vehicle for the FK.
    veh = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"slug": "apitest-logveh", "name": "Log Vehicle"},
    ).json()
    vid = veh["id"]

    r = client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "entries": [
                {
                    "source": "test",
                    "level": "info",
                    "message": "linked",
                    "vehicle_id": vid,
                },
                {
                    "source": "test",
                    "level": "info",
                    "message": "unlinked",
                },
            ]
        },
    )
    assert r.status_code == 200

    r = client.get(
        f"/logs/recent?source=test&vehicle_id={vid}&limit=10",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    rows = r.json()
    assert len(rows) == 1
    assert rows[0]["message"] == "linked"
    assert rows[0]["vehicle_id"] == vid


def test_logs_recent_default_window_is_one_hour(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    _seed_filter_logs(client, ingest_token)
    r = client.get(
        "/logs/recent?source=test",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    # All seeded rows landed within the last second, well inside 1h.
    rows = r.json()
    assert len(rows) >= 3


def test_logs_recent_limit_caps_at_1000(
    client, ingest_token, query_token, cleanup_test_logs
) -> None:
    r = client.get(
        "/logs/recent?limit=2000",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 422


# ---------------------------------------------------------------------------
# DELETE older-than
# ---------------------------------------------------------------------------


def test_logs_delete_older_than_zero_rejected(client, ingest_token) -> None:
    r = client.delete(
        "/logs/older-than?days=0",
        headers={"Authorization": f"Bearer {ingest_token}"},
    )
    assert r.status_code == 422


def test_logs_delete_older_than_too_large_rejected(
    client, ingest_token
) -> None:
    r = client.delete(
        "/logs/older-than?days=400",
        headers={"Authorization": f"Bearer {ingest_token}"},
    )
    assert r.status_code == 422


def test_logs_delete_older_than_recent_zero(
    client, ingest_token, cleanup_test_logs
) -> None:
    # Insert a fresh row, then delete-older-than=365: it should NOT be
    # touched because its server ts is now.
    client.post(
        "/logs",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "entries": [
                {"source": "test", "level": "info", "message": "fresh row"}
            ]
        },
    )
    r = client.delete(
        "/logs/older-than?days=365",
        headers={"Authorization": f"Bearer {ingest_token}"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["deleted"] == 0


@pytest.mark.asyncio
async def test_logs_delete_older_than_returns_count(
    client, ingest_token, pg_pool
) -> None:
    # Insert a row directly so we can backdate ts past the threshold.
    async with pg_pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO client_logs (ts, source, level, message)
            VALUES (now() - INTERVAL '60 days', 'test', 'info',
                    'ancient log')
            """
        )

    r = client.delete(
        "/logs/older-than?days=30",
        headers={"Authorization": f"Bearer {ingest_token}"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["deleted"] >= 1
