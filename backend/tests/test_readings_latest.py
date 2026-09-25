"""GET /readings/latest — newest reading per metric, bounded to 30 days.

Seeds the web Live view while the vehicle is parked. DB-backed; gated on
POSTGRES_HOST/PORT/... via the ``test_app`` fixture. All data is synthetic.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

pytestmark = pytest.mark.usefixtures("test_app")


def _mk_vehicle(client, ingest_token: str, slug: str) -> dict[str, Any]:
    r = client.post(
        "/vehicles",
        headers={"Authorization": f"Bearer {ingest_token}"},
        json={"slug": slug, "name": slug},
    )
    assert r.status_code == 201, r.text
    return r.json()


async def _seed(pg_pool, vid: str, rows: list[tuple[str, float, float | None, str | None, str]]):
    """rows: (metric, minutes_ago, value_num, value_text, source)."""
    now = datetime.now(UTC)
    async with pg_pool.acquire() as conn:
        for metric, mins, num, text, src in rows:
            await conn.execute(
                "INSERT INTO pid_readings "
                "(time, vehicle_id, metric, value_num, value_text, source) "
                "VALUES ($1, $2::uuid, $3, $4, $5, $6)",
                now - timedelta(minutes=mins), vid, metric, num, text, src,
            )


@pytest.mark.asyncio
async def test_latest_returns_newest_row_per_metric_within_30_days(
    client, ingest_token, query_token, pg_pool, cleanup_test_vehicles
) -> None:
    v = _mk_vehicle(client, ingest_token, "apitest-latest-a")
    other = _mk_vehicle(client, ingest_token, "apitest-latest-b")
    await _seed(
        pg_pool,
        v["id"],
        [
            ("vehicle_speed", 30, 50.0, None, "wican"),
            ("vehicle_speed", 7, 0.0, None, "bridge"),  # newest speed
            ("coolant_temp", 60, 88.0, None, "wican"),
            ("coolant_temp", 9, 91.5, None, "phone_batch"),  # newest coolant
            ("vin_status", 12, None, "ok", "wican"),  # text-only metric
            # Older than the 30-day window — must not appear at all.
            ("fuel_level", 31 * 24 * 60, 42.0, None, "wican"),
        ],
    )
    # Another vehicle's readings must not leak in.
    await _seed(pg_pool, other["id"], [("engine_rpm", 1, 800.0, None, "wican")])

    r = client.get(
        f"/readings/latest?vehicle_id={v['id']}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200, r.text
    by_metric = {row["metric"]: row for row in r.json()}
    assert set(by_metric) == {"vehicle_speed", "coolant_temp", "vin_status"}
    assert by_metric["vehicle_speed"]["value"] == 0.0
    assert by_metric["vehicle_speed"]["source"] == "bridge"
    assert by_metric["coolant_temp"]["value"] == 91.5
    assert by_metric["coolant_temp"]["source"] == "phone_batch"
    assert by_metric["vin_status"]["value"] == "ok"
    # Newest speed row is ~7 minutes old.
    t = datetime.fromisoformat(by_metric["vehicle_speed"]["time"])
    age = datetime.now(UTC) - t
    assert timedelta(minutes=6) < age < timedelta(minutes=8)


@pytest.mark.asyncio
async def test_latest_empty_for_vehicle_without_recent_readings(
    client, ingest_token, query_token, cleanup_test_vehicles
) -> None:
    v = _mk_vehicle(client, ingest_token, "apitest-latest-empty")
    r = client.get(
        f"/readings/latest?vehicle_id={v['id']}",
        headers={"Authorization": f"Bearer {query_token}"},
    )
    assert r.status_code == 200, r.text
    assert r.json() == []


def test_latest_requires_query_token(client) -> None:
    r = client.get("/readings/latest?vehicle_id=00000000-0000-0000-0000-000000000000")
    assert r.status_code == 401
