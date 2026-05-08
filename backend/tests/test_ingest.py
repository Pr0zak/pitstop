"""Unit tests for the MQTT ingest worker.

Covers the topic parser, payload classification, expression evaluator, and
the slug→UUID cache. The aiomqtt loopback case is intentionally skipped —
the docstring of MqttIngest explains the wiring; the integration is checked
in tier-c E2E tests with a real broker.
"""

from __future__ import annotations

import math
from datetime import UTC
from uuid import uuid4

import pytest

from pitstop.workers.bus import EventBus, TelemetryEvent
from pitstop.workers.ingest import (
    VehicleCache,
    evaluate_expression,
    is_hex_payload,
    parse_topic,
)

# ---------------------------------------------------------------------------
# Topic parsing
# ---------------------------------------------------------------------------


class TestParseTopic:
    def test_wican_three_parts(self) -> None:
        p = parse_topic("wican/pilot19/engine_rpm")
        assert p is not None
        assert p.source == "wican"
        assert p.vehicle_slug == "pilot19"
        assert p.metric == "engine_rpm"

    def test_bridge_three_parts(self) -> None:
        p = parse_topic("bridge/pilot19/gps_lat")
        assert p is not None
        assert p.source == "bridge"

    def test_bad_source_rejected(self) -> None:
        assert parse_topic("homeassistant/pilot19/engine_rpm") is None

    def test_too_few_parts_rejected(self) -> None:
        assert parse_topic("wican/pilot19") is None

    def test_too_many_parts_rejected(self) -> None:
        assert parse_topic("wican/pilot19/engine/rpm") is None

    def test_empty_slug_rejected(self) -> None:
        assert parse_topic("wican//engine_rpm") is None

    def test_empty_metric_rejected(self) -> None:
        assert parse_topic("wican/pilot19/") is None

    def test_empty_string_rejected(self) -> None:
        assert parse_topic("") is None


# ---------------------------------------------------------------------------
# Hex payload detection
# ---------------------------------------------------------------------------


class TestIsHexPayload:
    def test_short_hex_rejected(self) -> None:
        # "1A" looks hex but len <= 2 — could just as easily be a small int
        assert is_hex_payload("1A") is False

    def test_proper_hex_accepted(self) -> None:
        assert is_hex_payload("1A2B") is True

    def test_odd_length_rejected(self) -> None:
        assert is_hex_payload("1A2") is False

    def test_non_hex_rejected(self) -> None:
        assert is_hex_payload("hello") is False

    def test_lowercase_accepted(self) -> None:
        assert is_hex_payload("1a2b3c") is True


# ---------------------------------------------------------------------------
# Expression evaluator (against the real Honda Pilot profile formulas)
# ---------------------------------------------------------------------------


class TestEvaluateExpression:
    def test_engine_rpm_formula(self) -> None:
        # ((A*256)+B)/4 — payload bytes 0x0F 0xA0 → ((15*256)+160)/4 = 1000
        result = evaluate_expression("((A*256)+B)/4", "0FA0")
        assert math.isclose(result, 1000.0)

    def test_vehicle_speed_formula(self) -> None:
        # A — payload byte 0x42 = 66
        result = evaluate_expression("A", "42")
        assert math.isclose(result, 66.0)

    def test_throttle_position_formula(self) -> None:
        # (A*100)/255 — 0xFF = 100%
        result = evaluate_expression("(A*100)/255", "FF")
        assert math.isclose(result, 100.0)

    def test_coolant_temp_formula(self) -> None:
        # A-40 — 0x5A = 90, 90-40 = 50
        result = evaluate_expression("A-40", "5A")
        assert math.isclose(result, 50.0)

    def test_atf_temp_formula_uses_aa(self) -> None:
        # (AA*9/5)-40 — AA = (A<<8)|B = 0x0050 = 80; (80*9/5)-40 = 104
        result = evaluate_expression("(AA*9/5)-40", "0050")
        assert math.isclose(result, 104.0)

    def test_unknown_name_raises(self) -> None:
        # 'Z' is in env (26th letter) — but a single byte payload only has A.
        with pytest.raises(ValueError):
            evaluate_expression("Z", "42")

    def test_disallowed_function_call_rejected(self) -> None:
        # No call expressions allowed in the language.
        with pytest.raises(ValueError):
            evaluate_expression("__import__('os').system('echo')", "42")


# ---------------------------------------------------------------------------
# VehicleCache (mocked asyncpg pool)
# ---------------------------------------------------------------------------


class _FakeRow(dict):
    def __getitem__(self, key: str):
        return super().__getitem__(key)


class _FakePool:
    """Minimal stand-in exposing the one method VehicleCache uses."""

    def __init__(self, rows: dict[str, dict | None]) -> None:
        self._rows = rows
        self.calls = 0

    async def fetchrow(self, _sql: str, slug: str):
        self.calls += 1
        row = self._rows.get(slug)
        return None if row is None else _FakeRow(row)


@pytest.mark.asyncio
async def test_vehicle_cache_hit_then_miss() -> None:
    vid = uuid4()
    pool = _FakePool(
        {
            "pilot19": {
                "id": vid,
                "profile": {"pids": [{"name": "engine_rpm", "expression": "A"}]},
            },
            "missing": None,
        }
    )
    cache = VehicleCache(ttl_s=300)

    # Cold: hits the pool.
    res = await cache.get(pool, "pilot19")
    assert res is not None
    assert res[0] == vid
    assert "engine_rpm" in res[1]
    assert pool.calls == 1

    # Warm: cached.
    res2 = await cache.get(pool, "pilot19")
    assert res2 is not None
    assert pool.calls == 1

    # Unknown slug: returns None, warns once (no duplicate calls in cache,
    # but each get hits the DB until first miss; subsequent misses hit DB
    # again because we do not cache misses — verify the contract.)
    miss1 = await cache.get(pool, "missing")
    assert miss1 is None
    miss2 = await cache.get(pool, "missing")
    assert miss2 is None
    assert pool.calls >= 2


@pytest.mark.asyncio
async def test_vehicle_cache_invalidate() -> None:
    vid = uuid4()
    pool = _FakePool(
        {"pilot19": {"id": vid, "profile": None}}
    )
    cache = VehicleCache(ttl_s=300)
    await cache.get(pool, "pilot19")
    assert pool.calls == 1
    cache.invalidate("pilot19")
    await cache.get(pool, "pilot19")
    assert pool.calls == 2


# ---------------------------------------------------------------------------
# EventBus
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_event_bus_fan_out() -> None:
    b = EventBus(queue_maxsize=10)
    q1 = b.subscribe()
    q2 = b.subscribe()
    from datetime import datetime

    evt = TelemetryEvent(
        vehicle_id=uuid4(),
        time=datetime.now(UTC),
        metric="engine_rpm",
        value_num=1500.0,
        value_text=None,
        source="wican",
    )
    await b.publish(evt)
    assert q1.get_nowait() is evt
    assert q2.get_nowait() is evt


@pytest.mark.asyncio
async def test_event_bus_drops_oldest_on_full() -> None:
    b = EventBus(queue_maxsize=2)
    q = b.subscribe()
    from datetime import datetime

    def make(metric: str) -> TelemetryEvent:
        return TelemetryEvent(
            vehicle_id=uuid4(),
            time=datetime.now(UTC),
            metric=metric,
            value_num=0.0,
            value_text=None,
            source="wican",
        )

    a, b_, c = make("a"), make("b"), make("c")
    await b.publish(a)
    await b.publish(b_)
    await b.publish(c)  # drops 'a'
    drained = [q.get_nowait().metric for _ in range(q.qsize())]
    assert drained == ["b", "c"]
