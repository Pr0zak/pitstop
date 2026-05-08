"""End-to-end API tests for Task #16 — fillups, expenses, analytics, and
maintenance reminders.

DB-backed; gated on POSTGRES_HOST/PORT/... via the ``test_app`` fixture.
"""

from __future__ import annotations

import pytest

pytestmark = pytest.mark.usefixtures("test_app")


@pytest.fixture
def temp_vehicle(client, ingest_token, cleanup_test_vehicles):
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"slug": "apitest-fuel", "name": "Fuel Test"},
    )
    assert r.status_code == 201, r.text
    return r.json()


# ---------------------------------------------------------------------------
# Fillups
# ---------------------------------------------------------------------------


def test_fillups_create_list_recompute_mpg(
    client, ingest_token, query_token, temp_vehicle
) -> None:
    vid = temp_vehicle["id"]
    # F, P, F sequence: 12 gal then 5 gal partial then 7 gal full
    fills = [
        ("2024-01-01", 10000, 12.0, True),
        ("2024-01-15", 10300, 5.0, False),
        ("2024-01-25", 10600, 7.0, True),
    ]
    ids = []
    for d, odo, vol, full in fills:
        r = client.post(
            "/fillups",
            headers={"Authorization": f"Bearer {ingest_token}"},
            json={
                "vehicle_id": vid,
                "fillup_date": f"{d}T12:00:00Z",
                "odo": odo,
                "fuel_volume": vol,
                "is_full": full,
                "price_total": "30.00",
            },
        )
        assert r.status_code == 201, r.text
        ids.append(r.json()["id"])

    r = client.get(
        f"/fillups?vehicle_id={vid}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    assert r.headers.get("X-Total-Count") == "3"
    listed = {f["id"]: f for f in r.json()}

    assert listed[ids[0]]["mpg"] is None  # first full has no anchor
    assert listed[ids[1]]["mpg"] is None  # partial
    # Second full: distance 600 / volume 5+7 = 12 → 50 MPG.
    assert listed[ids[2]]["mpg"] == 50.0


# ---------------------------------------------------------------------------
# Analytics
# ---------------------------------------------------------------------------


def test_analytics_mpg_window(
    client, ingest_token, query_token, temp_vehicle
) -> None:
    vid = temp_vehicle["id"]
    for d, odo, vol in [("2024-01-01", 10000, 10.0), ("2024-02-01", 10300, 10.0)]:
        r = client.post(
            "/fillups",
            headers={"Authorization": f"Bearer {ingest_token}"},
            json={
                "vehicle_id": vid,
                "fillup_date": f"{d}T12:00:00Z",
                "odo": odo,
                "fuel_volume": vol,
                "is_full": True,
            },
        )
        assert r.status_code == 201

    r = client.get(
        f"/analytics/mpg?vehicle_id={vid}&window=month",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    points = {p["period"]: p for p in r.json()["points"]}
    # 2024-02 full → distance 300 / vol 10 = 30 MPG.
    assert "2024-02" in points
    assert points["2024-02"]["mpg"] == 30.0


def test_analytics_stations_clusters_by_round(
    client, ingest_token, query_token, temp_vehicle
) -> None:
    vid = temp_vehicle["id"]
    # Two fills at the same lat/lon (rounded to 3 decimals) — same cluster.
    # Synthetic coords near (0, 0) — far from any real address.
    for d, lat, lon in [
        ("2024-01-01", 0.012345, 0.054321),
        ("2024-01-10", 0.0124, 0.0541),
    ]:
        r = client.post(
            "/fillups",
            headers={"Authorization": f"Bearer {ingest_token}"},
            json={
                "vehicle_id": vid,
                "fillup_date": f"{d}T12:00:00Z",
                "odo": 10000.0,
                "fuel_volume": 10.0,
                "is_full": True,
                "lat": lat,
                "lon": lon,
            },
        )
        assert r.status_code == 201
    # Far-away fill — separate cluster.
    r = client.post(
        "/fillups",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "vehicle_id": vid,
            "fillup_date": "2024-01-15T12:00:00Z",
            "odo": 10100.0,
            "fuel_volume": 10.0,
            "is_full": True,
            "lat": 1.0,
            "lon": 1.0,
        },
    )
    assert r.status_code == 201

    r = client.get(
        f"/analytics/stations?vehicle_id={vid}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    clusters = r.json()
    assert len(clusters) == 2
    # Largest first.
    assert clusters[0]["fillup_count"] == 2


# ---------------------------------------------------------------------------
# Maintenance reminders
# ---------------------------------------------------------------------------


def test_reminders_overdue_and_upcoming(
    client, ingest_token, query_token, temp_vehicle
) -> None:
    vid = temp_vehicle["id"]
    # Anchor odo at 10,000.
    r = client.post(
        "/fillups",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "vehicle_id": vid,
            "fillup_date": "2024-12-01T12:00:00Z",
            "odo": 10000.0,
            "fuel_volume": 10.0,
            "is_full": True,
        },
    )
    assert r.status_code == 201

    # OVERDUE.
    r = client.post(
        "/expenses",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "vehicle_id": vid,
            "expense_date": "2024-01-01",
            "title": "Old service",
            "cost": "50.00",
            "remind_odo": 9000.0,
            "cost_type_id": 1,
        },
    )
    assert r.status_code == 201

    # UPCOMING (300 mi out, threshold 500).
    r = client.post(
        "/expenses",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "vehicle_id": vid,
            "expense_date": "2024-01-01",
            "title": "Soon service",
            "cost": "0.00",
            "remind_odo": 10300.0,
            "cost_type_id": 1,
        },
    )
    assert r.status_code == 201

    # NEITHER.
    r = client.post(
        "/expenses",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "vehicle_id": vid,
            "expense_date": "2024-01-01",
            "title": "Distant service",
            "cost": "0.00",
            "remind_odo": 15000.0,
            "cost_type_id": 1,
        },
    )
    assert r.status_code == 201

    r = client.get(
        f"/maintenance/reminders?vehicle_id={vid}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200
    body = r.json()
    overdue_titles = [o["title"] for o in body["overdue"]]
    upcoming_titles = [u["title"] for u in body["upcoming"]]
    assert "Old service" in overdue_titles
    assert "Soon service" in upcoming_titles
    assert "Distant service" not in overdue_titles
    assert "Distant service" not in upcoming_titles


def test_reminder_mark_done_advances_repeat(
    client, ingest_token, query_token, temp_vehicle
) -> None:
    vid = temp_vehicle["id"]
    client.post(
        "/fillups",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "vehicle_id": vid,
            "fillup_date": "2024-12-01T12:00:00Z",
            "odo": 10000.0,
            "fuel_volume": 10.0,
            "is_full": True,
        },
    )
    r = client.post(
        "/expenses",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={
            "vehicle_id": vid,
            "expense_date": "2024-01-01",
            "title": "Oil",
            "cost": "0.00",
            "cost_type_id": 1,
            "remind_odo": 9000.0,
            "repeat_odo": 5000.0,
        },
    )
    assert r.status_code == 201
    expense_id = r.json()["id"]

    r = client.post(
        f"/maintenance/reminders/{expense_id}/done",
        headers={"Authorization": f"Bearer {ingest_token}"},
    )
    assert r.status_code == 200
    assert r.json()["reminder_id"] == expense_id
    new_id = r.json()["completed_expense_id"]
    assert new_id != expense_id

    r = client.get(
        f"/expenses?vehicle_id={vid}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    rows = r.json()
    original = next(e for e in rows if e["id"] == expense_id)
    # current_odo (10000) + repeat_odo (5000) = 15000.
    assert original["remind_odo"] == 15000.0
