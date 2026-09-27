"""Live nearby fuel prices — Places parsing, station shaping, cost guard.

The pure tests need no DB. The endpoint tests are gated on the
``test_app`` fixture (POSTGRES_* env) and never reach Google: the HTTP
call is monkeypatched. All data is synthetic.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from pitstop.api.fuel_prices import build_station_rows
from pitstop.services import fuel_prices as fp

ORIGIN = (40.0, -90.0)


def _place(pid: str, lat: float, lon: float, prices: dict[str, str] | None) -> dict:
    p: dict[str, Any] = {
        "id": pid,
        "displayName": {"text": f"Station {pid}", "languageCode": "en"},
        "shortFormattedAddress": f"{pid} Main St",
        "location": {"latitude": lat, "longitude": lon},
    }
    if prices is not None:
        p["fuelOptions"] = {
            "fuelPrices": [
                {
                    "type": grade,
                    "price": {
                        "currencyCode": "USD",
                        "units": amount.split(".")[0],
                        "nanos": int(amount.split(".")[1].ljust(9, "0")),
                    },
                    "updateTime": "2026-09-26T14:05:00Z",
                }
                for grade, amount in prices.items()
            ]
        }
    return p


# --- parsing ---------------------------------------------------------------


def test_parse_money_and_grades() -> None:
    body = {"places": [_place("a", 40.001, -90.0, {"REGULAR_UNLEADED": "3.19", "PREMIUM": "3.89"})]}
    [s] = fp.parse_places(body)
    assert s.name == "Station a"
    assert s.address == "a Main St"
    reg = s.price_for("REGULAR_UNLEADED")
    assert reg is not None
    assert reg.price == 3.19
    assert reg.currency == "USD"
    assert reg.updated_at == datetime(2026, 9, 26, 14, 5, tzinfo=UTC)
    assert s.price_for("DIESEL") is None


def test_parse_station_without_fuel_options_has_no_prices() -> None:
    [s] = fp.parse_places({"places": [_place("b", 40.0, -90.0, None)]})
    assert s.prices == ()
    assert s.price_for("REGULAR_UNLEADED") is None


def test_parse_drops_zero_price_and_missing_location() -> None:
    zero = _place("z", 40.0, -90.0, None)
    zero["fuelOptions"] = {
        "fuelPrices": [{"type": "REGULAR_UNLEADED", "price": {"currencyCode": "USD"}}]
    }
    nowhere = _place("n", 40.0, -90.0, {"REGULAR_UNLEADED": "3.00"})
    del nowhere["location"]
    stations = fp.parse_places({"places": [zero, nowhere]})
    assert [s.place_id for s in stations] == ["z"]
    assert stations[0].prices == ()  # $0.00 is "no price", not free gas


def test_parse_empty_body() -> None:
    assert fp.parse_places({}) == []


# --- shaping ---------------------------------------------------------------


def _stations() -> list[fp.PlaceStation]:
    return fp.parse_places(
        {
            "places": [
                _place("far_cheap", 40.030, -90.0, {"REGULAR_UNLEADED": "2.99"}),
                _place("near_dear", 40.002, -90.0, {"REGULAR_UNLEADED": "3.49"}),
                _place("no_price", 40.001, -90.0, None),
                _place("diesel_only", 40.003, -90.0, {"DIESEL": "3.79"}),
                _place("outside", 40.200, -90.0, {"REGULAR_UNLEADED": "2.50"}),
            ]
        }
    )


def test_rows_sorted_cheapest_first_then_unpriced_nearest() -> None:
    rows = build_station_rows(_stations(), *ORIGIN, 5_000, "REGULAR_UNLEADED", [])
    assert [r["place_id"] for r in rows] == [
        "far_cheap",
        "near_dear",
        "no_price",  # ~111 m
        "diesel_only",  # ~334 m — unpriced for this grade
    ]
    # "outside" is ~22 km away — beyond the 5 km radius.
    assert rows[2]["price"] is None
    assert rows[3]["other_prices"][0]["grade"] == "DIESEL"
    assert rows[0]["maps_url"].startswith("https://www.google.com/maps/search/?api=1")


def test_rows_join_own_history_within_150m() -> None:
    history = [
        # newest first, like the endpoint's query
        {"lat": 40.0021, "lon": -90.0, "price_per_unit": 3.29,
         "fillup_date": datetime(2026, 9, 12, tzinfo=UTC)},
        {"lat": 40.0020, "lon": -90.0001, "price_per_unit": 3.05,
         "fillup_date": datetime(2026, 8, 1, tzinfo=UTC)},
        # ~555 m from near_dear — a different forecourt
        {"lat": 40.007, "lon": -90.0, "price_per_unit": 2.00,
         "fillup_date": datetime(2026, 9, 20, tzinfo=UTC)},
    ]
    rows = build_station_rows(_stations(), *ORIGIN, 5_000, "REGULAR_UNLEADED", history)
    near = next(r for r in rows if r["place_id"] == "near_dear")
    assert near["my_last_price"] == 3.29
    assert near["my_last_date"].startswith("2026-09-12")
    assert near["my_fillup_count"] == 2
    far = next(r for r in rows if r["place_id"] == "far_cheap")
    assert far["my_last_price"] is None
    assert far["my_fillup_count"] == 0


def test_a_fillup_counts_for_its_nearest_station_only() -> None:
    # 40.0021 is within 150 m of no_price (~122 m), near_dear (~11 m) and
    # diesel_only (~100 m); only near_dear, the closest, may claim it.
    history = [
        {"lat": 40.0021, "lon": -90.0, "price_per_unit": 3.29,
         "fillup_date": datetime(2026, 9, 12, tzinfo=UTC)},
    ]
    rows = build_station_rows(_stations(), *ORIGIN, 5_000, "REGULAR_UNLEADED", history)
    claimed = {r["place_id"]: r["my_fillup_count"] for r in rows}
    assert claimed == {"far_cheap": 0, "near_dear": 1, "no_price": 0, "diesel_only": 0}


# --- cache -----------------------------------------------------------------


def test_cache_shares_a_cell_and_expires(monkeypatch) -> None:
    c = fp._TtlCache(ttl=60)
    clock = [1_000.0]
    monkeypatch.setattr(fp.time, "monotonic", lambda: clock[0])
    stations = _stations()
    c.put(40.0012, -90.0031, 5_000, stations)
    # Same 2-dp cell, same radius → hit.
    hit = c.get(40.0049, -90.0049, 5_000)
    assert hit is not None and hit[1] is stations
    # Different radius or cell → miss.
    assert c.get(40.0012, -90.0031, 10_000) is None
    assert c.get(40.0151, -90.0031, 5_000) is None
    clock[0] += 61
    assert c.get(40.0012, -90.0031, 5_000) is None


# --- endpoint (DB) -----------------------------------------------------------


def _auth(tok: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {tok}"}


@pytest.fixture
def fake_google(monkeypatch):
    """Replace the outbound call; record how often it would have fired."""
    calls: list[tuple[float, float, int]] = []

    async def _search(api_key: str, lat: float, lon: float, radius_m: int):
        calls.append((lat, lon, radius_m))
        return _stations()

    monkeypatch.setattr(fp, "search_nearby", _search)
    fp.cache.clear()
    yield calls
    fp.cache.clear()


async def _reset(pg_pool) -> None:
    async with pg_pool.acquire() as conn:
        await conn.execute("DELETE FROM places_api_usage")
        await conn.execute(
            "UPDATE settings SET places_api_key = NULL, home_lat = NULL, "
            "home_lon = NULL WHERE id = 1"
        )


@pytest.mark.asyncio
async def test_endpoint_statuses_and_cache(
    client, pg_pool, ingest_token, query_token, fake_google
) -> None:
    await _reset(pg_pool)
    q = _auth(query_token)

    r = client.get("/fuel-prices/nearby", params={"lat": 40.0, "lon": -90.0}, headers=q)
    assert r.status_code == 200
    assert r.json()["status"] == "no_key"

    # Blank never overwrites; a real key sets; settings never echo it.
    r = client.patch(
        "/settings", headers=_auth(ingest_token), json={"places": {"api_key": "k-123"}}
    )
    assert r.json()["places"]["key_set"] is True
    assert "k-123" not in r.text
    r = client.patch("/settings", headers=_auth(ingest_token), json={"places": {"api_key": ""}})
    assert r.json()["places"]["key_set"] is True

    r = client.get("/fuel-prices/nearby", headers=q)
    assert r.json()["status"] == "no_location"

    r = client.get("/fuel-prices/nearby", params={"lat": 40.0, "lon": -90.0}, headers=q)
    body = r.json()
    assert body["status"] == "ok"
    assert body["cached"] is False
    assert body["origin"]["source"] == "device"
    assert body["stations"][0]["place_id"] == "far_cheap"
    assert body["usage"]["month_calls"] == 1
    # Searched at the cell centre with the radius padded.
    assert fake_google == [(40.0, -90.0, 6_000)]

    # Same cell again → served from cache, no new call, no count.
    r = client.get("/fuel-prices/nearby", params={"lat": 40.004, "lon": -90.003}, headers=q)
    body = r.json()
    assert body["cached"] is True
    assert body["usage"]["month_calls"] == 1
    assert len(fake_google) == 1

    r = client.get("/fuel-prices/nearby", params={"lat": 40.0}, headers=q)
    assert r.status_code == 422

    r = client.patch("/settings", headers=_auth(ingest_token), json={"places": {"api_key": None}})
    assert r.json()["places"]["key_set"] is False


@pytest.mark.asyncio
async def test_monthly_cap_blocks_the_call(
    client, pg_pool, ingest_token, query_token, fake_google
) -> None:
    await _reset(pg_pool)
    client.patch("/settings", headers=_auth(ingest_token), json={"places": {"api_key": "k"}})
    async with pg_pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO places_api_usage (month, calls) VALUES ($1, $2)",
            datetime.now(UTC).date().replace(day=1),
            fp.MONTHLY_CAP,
        )
    r = client.get(
        "/fuel-prices/nearby", params={"lat": 41.0, "lon": -91.0}, headers=_auth(query_token)
    )
    body = r.json()
    assert body["status"] == "quota_reached"
    assert body["usage"]["month_calls"] == fp.MONTHLY_CAP
    assert fake_google == []
    await _reset(pg_pool)


@pytest.mark.asyncio
async def test_falls_back_to_vehicle_gps(
    client, pg_pool, ingest_token, query_token, fake_google, cleanup_test_vehicles
) -> None:
    await _reset(pg_pool)
    client.patch("/settings", headers=_auth(ingest_token), json={"places": {"api_key": "k"}})
    r = client.post(
        "/vehicles",
        headers=_auth(ingest_token),
        json={"slug": "apitest-fp-gps", "name": "apitest-fp-gps"},
    )
    vid = r.json()["id"]
    parked = datetime.now(UTC) - timedelta(hours=3)
    async with pg_pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO gps_points (time, vehicle_id, lat, lon) VALUES ($1, $2::uuid, $3, $4)",
            parked, vid, 40.0, -90.0,
        )
    r = client.get(
        "/fuel-prices/nearby", params={"vehicle_id": vid}, headers=_auth(query_token)
    )
    body = r.json()
    assert body["status"] == "ok"
    assert body["origin"]["source"] == "vehicle"
    assert body["origin"]["as_of"] is not None
    await _reset(pg_pool)
