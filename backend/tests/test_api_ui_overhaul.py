"""API extensions for the web + Android UI overhaul.

- GET /trips ``sort`` / ``source`` / ``towing`` params
- GET /dtcs/timeline per-event ``trip_id`` / ``trip_distance_km``
- GET /analytics/cost-of-ownership ``fuel_volume_total``
- vehicles.redline_rpm (0023 migration) on GET / POST / PATCH

DB-backed; gated on POSTGRES_HOST/PORT/... via the ``test_app`` fixture.
All data is synthetic.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

pytestmark = pytest.mark.usefixtures("test_app")


@pytest.fixture
def temp_vehicle(client, ingest_token, cleanup_test_vehicles) -> dict[str, Any]:
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"slug": "apitest-uiov", "name": "UI Overhaul Test"},
    )
    assert r.status_code == 201, r.text
    return r.json()


# ---------------------------------------------------------------------------
# GET /trips sort / source / towing
# ---------------------------------------------------------------------------

# (hours_ago, distance_km, duration_s, max_speed_kph, max_rpm, fuel_used_l,
#  source, is_towing)
_TRIPS = {
    "a": (1, 10.0, 600, 90.0, 3000.0, 1.0, "deriver", False),
    "b": (2, 50.0, 300, 130.0, 2500.0, None, "phone_batch", True),
    "c": (3, None, 1800, None, 4500.0, 4.0, "manual_merge", False),
    "d": (4, 30.0, None, 110.0, None, 2.0, "phone_batch", False),
}


async def _seed_trips(pg_pool, vid: str) -> dict[str, str]:
    now = datetime.now(UTC)
    ids: dict[str, str] = {}
    async with pg_pool.acquire() as conn:
        for key, (h, dist, dur, spd, rpm, fuel, src, tow) in _TRIPS.items():
            start = now - timedelta(hours=h)
            ids[key] = str(
                await conn.fetchval(
                    "INSERT INTO trips (vehicle_id, started_at, ended_at, "
                    "duration_s, distance_km, max_speed_kph, max_rpm, "
                    "fuel_used_l, source, is_towing) "
                    "VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, $9, $10) "
                    "RETURNING id",
                    vid, start, start + timedelta(minutes=10),
                    dur, dist, spd, rpm, fuel, src, tow,
                )
            )
    return ids


def _order(client, query_token: str, vid: str, qs: str, ids: dict[str, str]) -> list[str]:
    r = client.get(
        f"/trips?vehicle_id={vid}{qs}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200, r.text
    back = {v: k for k, v in ids.items()}
    return [back[t["id"]] for t in r.json()]


@pytest.mark.asyncio
async def test_trips_sort_orders_desc_nulls_last(
    client, query_token, temp_vehicle, pg_pool
) -> None:
    vid = temp_vehicle["id"]
    ids = await _seed_trips(pg_pool, vid)
    q = query_token
    assert _order(client, q, vid, "", ids) == ["a", "b", "c", "d"]
    assert _order(client, q, vid, "&sort=recent", ids) == ["a", "b", "c", "d"]
    assert _order(client, q, vid, "&sort=distance", ids) == ["b", "d", "a", "c"]
    assert _order(client, q, vid, "&sort=duration", ids) == ["c", "a", "b", "d"]
    assert _order(client, q, vid, "&sort=top_speed", ids) == ["b", "d", "a", "c"]
    assert _order(client, q, vid, "&sort=max_rpm", ids) == ["c", "a", "b", "d"]
    assert _order(client, q, vid, "&sort=fuel", ids) == ["c", "d", "a", "b"]


@pytest.mark.asyncio
async def test_trips_sort_paginates_consistently(
    client, query_token, temp_vehicle, pg_pool
) -> None:
    vid = temp_vehicle["id"]
    ids = await _seed_trips(pg_pool, vid)
    first = _order(client, query_token, vid, "&sort=distance&limit=2", ids)
    second = _order(client, query_token, vid, "&sort=distance&limit=2&offset=2", ids)
    assert first + second == ["b", "d", "a", "c"]


def test_trips_sort_rejects_unknown(client, query_token) -> None:
    r = client.get(
        "/trips?sort=bogus", headers={"Authorization": f"Bearer {query_token}"}
    )
    assert r.status_code == 422


def test_trips_source_rejects_unknown(client, query_token) -> None:
    r = client.get(
        "/trips?source=bogus", headers={"Authorization": f"Bearer {query_token}"}
    )
    assert r.status_code == 422


@pytest.mark.asyncio
async def test_trips_source_filter_matches_web_chips(
    client, query_token, temp_vehicle, pg_pool
) -> None:
    vid = temp_vehicle["id"]
    ids = await _seed_trips(pg_pool, vid)
    q = query_token
    assert _order(client, q, vid, "&source=all", ids) == ["a", "b", "c", "d"]
    assert _order(client, q, vid, "&source=phone_batch", ids) == ["b", "d"]
    assert _order(client, q, vid, "&source=phone", ids) == ["b", "d"]
    assert _order(client, q, vid, "&source=manual_merge", ids) == ["c"]
    assert _order(client, q, vid, "&source=merged", ids) == ["c"]
    assert _order(client, q, vid, "&source=other", ids) == ["a"]


@pytest.mark.asyncio
async def test_trips_towing_filter_and_total_count(
    client, query_token, temp_vehicle, pg_pool
) -> None:
    vid = temp_vehicle["id"]
    ids = await _seed_trips(pg_pool, vid)
    assert _order(client, query_token, vid, "&towing=true", ids) == ["b"]
    # towing=false is "don't filter", not "exclude towing".
    assert _order(client, query_token, vid, "&towing=false", ids) == [
        "a", "b", "c", "d",
    ]
    r = client.get(
        f"/trips?vehicle_id={vid}&source=phone_batch&towing=true&sort=distance",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    assert r.headers.get("X-Total-Count") == "1"


# ---------------------------------------------------------------------------
# GET /dtcs/timeline trip attribution
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_dtc_timeline_events_carry_trip(
    client, query_token, temp_vehicle, pg_pool
) -> None:
    vid = temp_vehicle["id"]
    now = datetime.now(UTC).replace(microsecond=0)
    t_start = now - timedelta(hours=5)
    t_end = t_start + timedelta(minutes=30)
    async with pg_pool.acquire() as conn:
        trip_id = await conn.fetchval(
            "INSERT INTO trips (vehicle_id, started_at, ended_at, distance_km) "
            "VALUES ($1::uuid, $2, $3, 42.5) RETURNING id",
            vid, t_start, t_end,
        )
        inside = await conn.fetchval(
            "INSERT INTO dtc_events (vehicle_id, code, seen_at) "
            "VALUES ($1::uuid, 'P0420', $2) RETURNING id",
            vid, t_start + timedelta(minutes=10),
        )
        on_edge = await conn.fetchval(
            "INSERT INTO dtc_events (vehicle_id, code, seen_at) "
            "VALUES ($1::uuid, 'P0420', $2) RETURNING id",
            vid, t_end,
        )
        outside = await conn.fetchval(
            "INSERT INTO dtc_events (vehicle_id, code, seen_at) "
            "VALUES ($1::uuid, 'P0420', $2) RETURNING id",
            vid, t_end + timedelta(hours=1),
        )

    r = client.get(
        f"/dtcs/timeline?vehicle_id={vid}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200, r.text
    codes = r.json()["codes"]
    assert len(codes) == 1 and codes[0]["count"] == 3
    events = {e["id"]: e for e in codes[0]["events"]}
    assert set(events) == {str(inside), str(on_edge), str(outside)}
    for eid in (inside, on_edge):
        assert events[str(eid)]["trip_id"] == str(trip_id)
        assert events[str(eid)]["trip_distance_km"] == 42.5
    assert events[str(outside)]["trip_id"] is None
    assert events[str(outside)]["trip_distance_km"] is None


@pytest.mark.asyncio
async def test_dtc_timeline_ignores_other_vehicles_trips(
    client, ingest_token, query_token, temp_vehicle, pg_pool
) -> None:
    vid = temp_vehicle["id"]
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"slug": "apitest-uiov-other", "name": "Other"},
    )
    assert r.status_code == 201, r.text
    other = r.json()["id"]
    now = datetime.now(UTC)
    async with pg_pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO trips (vehicle_id, started_at, ended_at, distance_km) "
            "VALUES ($1::uuid, $2, $3, 5.0)",
            other, now - timedelta(hours=2), now - timedelta(hours=1),
        )
        await conn.execute(
            "INSERT INTO dtc_events (vehicle_id, code, seen_at) "
            "VALUES ($1::uuid, 'P0171', $2)",
            vid, now - timedelta(minutes=90),
        )
    r = client.get(
        f"/dtcs/timeline?vehicle_id={vid}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200, r.text
    (ev,) = r.json()["codes"][0]["events"]
    assert ev["trip_id"] is None
    assert ev["trip_distance_km"] is None


# ---------------------------------------------------------------------------
# GET /analytics/cost-of-ownership fuel_volume_total
# ---------------------------------------------------------------------------


def test_cost_of_ownership_fuel_volume_total(
    client, ingest_token, query_token, temp_vehicle
) -> None:
    vid = temp_vehicle["id"]
    r = client.get(
        f"/analytics/cost-of-ownership?vehicle_id={vid}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200, r.text
    assert r.json()["fuel_volume_total"] == 0

    for d, odo, vol, price in [
        ("2024-01-01", 10000, 12.5, "40.00"),
        ("2024-01-20", 10300, 10.25, "35.50"),
        # Unpriced fillup: its volume must not count, or fuel_total /
        # fuel_volume_total understates lifetime $/volume.
        ("2024-02-05", 10600, 8.0, None),
    ]:
        r = client.post(
            "/fillups",
            headers={"Authorization": f"Bearer {ingest_token}"},
            json={
                "vehicle_id": vid,
                "fillup_date": f"{d}T12:00:00Z",
                "odo": odo,
                "fuel_volume": vol,
                "is_full": True,
                "price_total": price,
            },
        )
        assert r.status_code == 201, r.text

    r = client.get(
        f"/analytics/cost-of-ownership?vehicle_id={vid}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["fuel_volume_total"] == pytest.approx(22.75)
    assert body["fuel_total"] == pytest.approx(75.50)


# ---------------------------------------------------------------------------
# vehicles.redline_rpm
# ---------------------------------------------------------------------------


def test_vehicle_redline_rpm_roundtrip(
    client, ingest_token, query_token, cleanup_test_vehicles
) -> None:
    ing = {"Authorization": f"Bearer {ingest_token}"}
    qry = {"Authorization": f"Bearer {query_token}"}

    r = client.post(
        "/vehicles",
        headers=ing,
        json={"slug": "apitest-redline", "name": "Redline", "redline_rpm": 6800},
    )
    assert r.status_code == 201, r.text
    vid = r.json()["id"]
    assert r.json()["redline_rpm"] == 6800

    r = client.get(f"/vehicles/{vid}", headers=qry)
    assert r.json()["redline_rpm"] == 6800
    listed = {v["id"]: v for v in client.get("/vehicles", headers=qry).json()}
    assert listed[vid]["redline_rpm"] == 6800

    # PATCH of an unrelated field leaves it alone.
    r = client.patch(f"/vehicles/{vid}", headers=ing, json={"name": "Renamed"})
    assert r.status_code == 200, r.text
    assert r.json()["redline_rpm"] == 6800

    r = client.patch(f"/vehicles/{vid}", headers=ing, json={"redline_rpm": 7200})
    assert r.status_code == 200, r.text
    assert r.json()["redline_rpm"] == 7200

    # Explicit null clears it.
    r = client.patch(f"/vehicles/{vid}", headers=ing, json={"redline_rpm": None})
    assert r.status_code == 200, r.text
    assert r.json()["redline_rpm"] is None


def test_vehicle_redline_rpm_defaults_null_and_validates(
    client, ingest_token, cleanup_test_vehicles
) -> None:
    ing = {"Authorization": f"Bearer {ingest_token}"}
    r = client.post(
        "/vehicles", headers=ing, json={"slug": "apitest-redline2", "name": "R2"}
    )
    assert r.status_code == 201, r.text
    assert r.json()["redline_rpm"] is None
    vid = r.json()["id"]

    for bad in (500, 25000):
        r = client.patch(f"/vehicles/{vid}", headers=ing, json={"redline_rpm": bad})
        assert r.status_code == 422, r.text
    r = client.post(
        "/vehicles",
        headers=ing,
        json={"slug": "apitest-redline3", "name": "R3", "redline_rpm": 99},
    )
    assert r.status_code == 422
