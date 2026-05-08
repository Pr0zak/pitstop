"""End-to-end API tests for Task #5 — auth, vehicles, profiles, settings,
trips, websocket.

DB-backed; gated on POSTGRES_HOST/PORT/... env vars via the ``test_app``
fixture. Uses a slim FastAPI app harness that runs the same routers as
production but skips the MQTT/trip-detector workers.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

pytestmark = pytest.mark.usefixtures("test_app")


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------


def test_query_endpoint_rejects_missing_token(client) -> None:
    r = client.get("/vehicles")
    assert r.status_code == 401


def test_query_endpoint_rejects_wrong_token(client) -> None:
    r = client.get("/vehicles", headers={"Authorization": "Bearer wrong"})
    assert r.status_code == 401


def test_ingest_endpoint_rejects_query_token(client, query_token: str) -> None:
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {query_token}"},
        json={"slug": "apitest-x", "name": "X"},
    )
    assert r.status_code == 401


def test_health_no_token_required(client) -> None:
    r = client.get("/health")
    assert r.status_code == 200


# ---------------------------------------------------------------------------
# Vehicles CRUD
# ---------------------------------------------------------------------------


def test_vehicle_crud_full_cycle(
    client, ingest_token, query_token, cleanup_test_vehicles
) -> None:
    body = {
        "slug": "apitest-pilot",
        "name": "API Test Pilot",
        "make": "Honda",
        "model": "Pilot",
        "year": 2019,
    }
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json=body,
    )
    assert r.status_code == 201, r.text
    veh = r.json()
    vid = veh["id"]
    assert veh["slug"] == "apitest-pilot"
    assert veh["latest"] == {}
    assert veh["pid_profile"] is None

    r = client.get(
        f"/vehicles/{vid}", headers={"Authorization": f"Bearer {query_token}"}
    )
    assert r.status_code == 200
    assert r.json()["name"] == "API Test Pilot"

    r = client.get(
        "/vehicles", headers={"Authorization": f"Bearer {query_token}"}
    )
    assert r.status_code == 200
    assert any(v["id"] == vid for v in r.json())

    r = client.patch(
        f"/vehicles/{vid}",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"name": "Renamed Pilot"},
    )
    assert r.status_code == 200
    assert r.json()["name"] == "Renamed Pilot"

    r = client.delete(
        f"/vehicles/{vid}",
        headers={"Authorization": f"Bearer {ingest_token}"},
    )
    assert r.status_code == 204
    r = client.get(
        f"/vehicles/{vid}", headers={"Authorization": f"Bearer {query_token}"}
    )
    assert r.status_code == 404


def test_vehicle_create_rejects_bad_slug(
    client, ingest_token, cleanup_test_vehicles
) -> None:
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"slug": "Has Spaces", "name": "X"},
    )
    assert r.status_code == 422


# ---------------------------------------------------------------------------
# PID profiles
# ---------------------------------------------------------------------------


def test_profiles_list_includes_seeded_or_empty(client, query_token) -> None:
    r = client.get(
        "/profiles", headers={"Authorization": f"Bearer {query_token}"}
    )
    assert r.status_code == 200
    assert isinstance(r.json(), list)


def test_profile_crud(client, ingest_token, query_token, pg_pool) -> None:
    name = f"apitest-profile-{uuid.uuid4().hex[:8]}"
    body = {
        "name": name,
        "description": "test",
        "profile": {"name": name, "pids": []},
    }
    try:
        r = client.post(
            "/profiles",
            headers={"Authorization": f"Bearer {ingest_token}"},
            json=body,
        )
        assert r.status_code == 201, r.text
        pid = r.json()["id"]

        r = client.get(
            f"/profiles/{pid}",
            headers={"Authorization": f"Bearer {query_token}"},
        )
        assert r.status_code == 200
        assert r.json()["profile"]["pids"] == []

        r = client.put(
            f"/profiles/{pid}",
            headers={"Authorization": f"Bearer {ingest_token}"},
            json={
                "description": "updated",
                "profile": {"name": name, "pids": [{"name": "x", "expression": "A"}]},
            },
        )
        assert r.status_code == 200
        assert len(r.json()["profile"]["pids"]) == 1

        r = client.delete(
            f"/profiles/{pid}",
            headers={"Authorization": f"Bearer {ingest_token}"},
        )
        assert r.status_code == 204
    finally:
        # Belt & braces — make sure stray test rows don't accumulate.
        async def _purge():
            async with pg_pool.acquire() as conn:
                await conn.execute(
                    "DELETE FROM pid_profiles WHERE name = $1", name
                )

        import asyncio

        asyncio.get_event_loop().run_until_complete(_purge())


# ---------------------------------------------------------------------------
# Settings — token redaction
# ---------------------------------------------------------------------------


def test_settings_token_redacted(client, ingest_token, query_token) -> None:
    r = client.patch(
        "/settings",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"ha": {"token": "supersecret123", "url": "http://ha.local"}},
    )
    assert r.status_code == 200
    assert "token" not in r.json()["ha"]
    assert r.json()["ha"]["token_set"] is True
    assert "supersecret123" not in r.text

    r = client.get(
        "/settings", headers={"Authorization": f"Bearer {query_token}"}
    )
    assert r.status_code == 200
    assert r.json()["ha"]["token_set"] is True
    assert "token" not in r.json()["ha"]
    assert "supersecret123" not in r.text

    r = client.patch(
        "/settings",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"ha": {"token": ""}},
    )
    assert r.status_code == 200
    assert r.json()["ha"]["token_set"] is False


def test_settings_ha_test_only_when_enabled(client, ingest_token) -> None:
    client.patch(
        "/settings",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"ha": {"enabled": False}},
    )
    r = client.post(
        "/settings/ha/test",
        headers={"Authorization": f"Bearer {ingest_token}"},
    )
    assert r.status_code == 400


# ---------------------------------------------------------------------------
# Trips
# ---------------------------------------------------------------------------


@pytest.fixture
def temp_vehicle(client, ingest_token, cleanup_test_vehicles):
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"slug": "apitest-tripcore", "name": "Trip Core"},
    )
    assert r.status_code == 201, r.text
    return r.json()


@pytest.mark.asyncio
async def test_trip_list_pagination_header(
    client, query_token, temp_vehicle, pg_pool
) -> None:
    vid = temp_vehicle["id"]
    async with pg_pool.acquire() as conn:
        for i in range(2):
            await conn.execute(
                "INSERT INTO trips (vehicle_id, started_at, ended_at, "
                "duration_s, distance_km, dtc_count) "
                "VALUES ($1::uuid, $2, $3, 60, 1.0, 0)",
                vid,
                datetime.now(UTC) - timedelta(hours=i + 1),
                datetime.now(UTC) - timedelta(hours=i + 1) + timedelta(minutes=1),
            )

    r = client.get(
        f"/trips?vehicle_id={vid}&limit=1",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    assert r.headers.get("X-Total-Count") == "2"
    assert len(r.json()) == 1


# ---------------------------------------------------------------------------
# WebSocket
# ---------------------------------------------------------------------------


def test_ws_live_rejects_missing_token(client, temp_vehicle) -> None:
    vid = temp_vehicle["id"]
    from starlette.websockets import WebSocketDisconnect

    with (
        pytest.raises(WebSocketDisconnect),
        client.websocket_connect(f"/ws/live?vehicle_id={vid}") as ws,
    ):
        ws.receive_text()


def test_ws_live_publishes_filtered_events(
    client, query_token, temp_vehicle
) -> None:
    from pitstop.workers.bus import TelemetryEvent

    vid = uuid.UUID(temp_vehicle["id"])
    other_vid = uuid4()
    url = f"/ws/live?vehicle_id={vid}&token={query_token}"

    with client.websocket_connect(url) as ws:
        bus = client.app.state.bus
        import asyncio as _asyncio

        async def _pub(events):
            for ev in events:
                await bus.publish(ev)

        events = [
            TelemetryEvent(
                vehicle_id=other_vid,
                time=datetime.now(UTC),
                metric="engine_rpm",
                value_num=999.0,
                value_text=None,
                source="wican",
            ),
            TelemetryEvent(
                vehicle_id=vid,
                time=datetime.now(UTC),
                metric="engine_rpm",
                value_num=1234.0,
                value_text=None,
                source="wican",
            ),
        ]
        loop = _asyncio.new_event_loop()
        try:
            loop.run_until_complete(_pub(events))
        finally:
            loop.close()

        msg = ws.receive_json()
        assert msg["metric"] == "engine_rpm"
        assert msg["value_num"] == 1234.0
        assert msg["vehicle_id"] == str(vid)
