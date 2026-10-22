"""Door-placard tire pressure on the vehicle API (0025). DB-backed."""

from __future__ import annotations

import pytest

pytestmark = pytest.mark.usefixtures("test_app")


def test_placard_patch_roundtrip_and_range(
    client, ingest_token, query_token, cleanup_test_vehicles
) -> None:
    w = {"Authorization": f"Bearer {ingest_token}"}
    r = client.post("/vehicles", headers=w,
                    json={"slug": "apitest-placard", "name": "Placard"})
    assert r.status_code == 201, r.text
    vid = r.json()["id"]
    assert r.json()["tire_placard_front_kpa"] is None
    assert r.json()["tire_placard_rear_kpa"] is None

    r = client.patch(f"/vehicles/{vid}", headers=w,
                     json={"tire_placard_front_kpa": 241.3,
                           "tire_placard_rear_kpa": 241.3})
    assert r.status_code == 200, r.text
    got = client.get(f"/vehicles/{vid}",
                     headers={"Authorization": f"Bearer {query_token}"}).json()
    assert got["tire_placard_front_kpa"] == pytest.approx(241.3)
    assert got["tire_placard_rear_kpa"] == pytest.approx(241.3)

    # Typo-sized values are refused; null clears.
    r = client.patch(f"/vehicles/{vid}", headers=w,
                     json={"tire_placard_front_kpa": 35})
    assert r.status_code == 422
    r = client.patch(f"/vehicles/{vid}", headers=w,
                     json={"tire_placard_front_kpa": None})
    assert r.status_code == 200
    assert r.json()["tire_placard_front_kpa"] is None


def test_placard_on_create(client, ingest_token, cleanup_test_vehicles) -> None:
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"slug": "apitest-placard2", "name": "Placard 2",
              "tire_placard_front_kpa": 241.3, "tire_placard_rear_kpa": 248.2},
    )
    assert r.status_code == 201, r.text
    assert r.json()["tire_placard_rear_kpa"] == pytest.approx(248.2)
