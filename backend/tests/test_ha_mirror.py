"""Tests for the HA mirror worker.

Two tiers:

- Pure-logic tests for the discovery/state payload helpers.
- Toggle-driven test that runs the worker against a fake MQTT publisher
  + EventBus, flipping ``ha_enabled`` on, then off, and verifies the
  published topics + retain flags.
"""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime
from typing import Any
from uuid import UUID, uuid4

import pytest

from pitstop.workers import ha_mirror as ha
from pitstop.workers.bus import EventBus, TelemetryEvent

# ---------------------------------------------------------------------------
# Pure helpers
# ---------------------------------------------------------------------------


class TestSafeToken:
    def test_keeps_alphanum_underscore(self) -> None:
        assert ha._safe_token("pilot_19") == "pilot_19"

    def test_lowercases(self) -> None:
        assert ha._safe_token("Pilot19") == "pilot19"

    def test_replaces_dashes_and_punctuation(self) -> None:
        assert ha._safe_token("test-pilot.1") == "test_pilot_1"

    def test_blank_falls_back(self) -> None:
        assert ha._safe_token("---") == "unknown"


class TestDiscoveryTopic:
    def test_basic_shape(self) -> None:
        topic = ha.discovery_topic("homeassistant", "pilot19", "engine_rpm")
        assert topic == "homeassistant/sensor/pitstop_pilot19_engine_rpm/config"

    def test_state_matches_discovery(self) -> None:
        d = ha.discovery_topic("homeassistant", "pilot19", "engine_rpm")
        s = ha.state_topic("homeassistant", "pilot19", "engine_rpm")
        # The object id segment must match between the two.
        assert d.replace("/config", "/state") == s

    def test_prefix_trailing_slash_stripped(self) -> None:
        topic = ha.discovery_topic("homeassistant/", "pilot19", "engine_rpm")
        assert topic == "homeassistant/sensor/pitstop_pilot19_engine_rpm/config"


class TestBuildDiscoveryPayload:
    def _vehicle(self) -> dict[str, Any]:
        return {
            "id": uuid4(),
            "slug": "pilot19",
            "name": "Honda Pilot",
            "make": "Honda",
            "model": "Pilot",
            "year": 2019,
        }

    def test_minimal_no_profile(self) -> None:
        payload = ha.build_discovery_payload(
            prefix="homeassistant",
            vehicle=self._vehicle(),
            metric="engine_rpm",
            profile_pid=None,
        )
        assert payload["unique_id"] == "pitstop_pilot19_engine_rpm"
        assert payload["name"] == "Engine Rpm"
        assert payload["state_topic"].endswith(
            "/sensor/pitstop_pilot19_engine_rpm/state"
        )
        assert payload["device"]["identifiers"] == ["pitstop_pilot19"]
        assert payload["device"]["model"] == "2019 Honda Pilot"
        assert payload["device"]["manufacturer"] == "pitstop"
        # No profile = no unit_of_measurement / device_class.
        assert "unit_of_measurement" not in payload
        assert "device_class" not in payload

    def test_unit_celsius_maps_to_temperature(self) -> None:
        payload = ha.build_discovery_payload(
            prefix="homeassistant",
            vehicle=self._vehicle(),
            metric="coolant_temp",
            profile_pid={"unit": "celsius"},
        )
        assert payload["unit_of_measurement"] == "°C"
        assert payload["device_class"] == "temperature"

    def test_unit_volt_maps_to_voltage(self) -> None:
        payload = ha.build_discovery_payload(
            prefix="homeassistant",
            vehicle=self._vehicle(),
            metric="control_module_voltage",
            profile_pid={"unit": "volt"},
        )
        assert payload["unit_of_measurement"] == "V"
        assert payload["device_class"] == "voltage"

    def test_unit_kph_maps_to_speed(self) -> None:
        payload = ha.build_discovery_payload(
            prefix="homeassistant",
            vehicle=self._vehicle(),
            metric="vehicle_speed",
            profile_pid={"unit": "kph"},
        )
        assert payload["unit_of_measurement"] == "km/h"
        assert payload["device_class"] == "speed"

    def test_unit_passthrough_when_unknown(self) -> None:
        payload = ha.build_discovery_payload(
            prefix="homeassistant",
            vehicle=self._vehicle(),
            metric="custom_metric",
            profile_pid={"unit": "tomatoes"},
        )
        assert payload["unit_of_measurement"] == "tomatoes"
        assert "device_class" not in payload

    def test_bitfield_unit_omits_uom(self) -> None:
        payload = ha.build_discovery_payload(
            prefix="homeassistant",
            vehicle=self._vehicle(),
            metric="fuel_status",
            profile_pid={"unit": "bitfield"},
        )
        assert "unit_of_measurement" not in payload
        assert "device_class" not in payload

    def test_includes_sw_version(self) -> None:
        from pitstop.version import VERSION

        payload = ha.build_discovery_payload(
            prefix="homeassistant",
            vehicle=self._vehicle(),
            metric="engine_rpm",
            profile_pid=None,
        )
        assert payload["device"]["sw_version"] == VERSION

    def test_no_year_make_model_omits_model(self) -> None:
        v = self._vehicle()
        del v["year"]
        del v["make"]
        del v["model"]
        payload = ha.build_discovery_payload(
            prefix="homeassistant",
            vehicle=v,
            metric="engine_rpm",
            profile_pid=None,
        )
        assert "model" not in payload["device"]


class TestStatePayload:
    def test_int_renders_as_int(self) -> None:
        assert ha.state_payload(1500.0, None) == "1500"

    def test_float_keeps_precision(self) -> None:
        assert ha.state_payload(13.456, None) == "13.456"

    def test_text_fallback(self) -> None:
        assert ha.state_payload(None, "P0301") == "P0301"

    def test_blank_when_neither(self) -> None:
        assert ha.state_payload(None, None) == ""


class TestSlugifyVehicle:
    def test_uses_slug(self) -> None:
        assert ha.slugify_vehicle({"slug": "pilot19", "id": uuid4()}) == "pilot19"

    def test_dashes_become_underscores(self) -> None:
        assert ha.slugify_vehicle({"slug": "test-pilot", "id": uuid4()}) == "test_pilot"

    def test_falls_back_to_uuid_when_slug_missing(self) -> None:
        vid = UUID("12345678-1234-1234-1234-123456789012")
        out = ha.slugify_vehicle({"slug": None, "id": vid})
        assert out.startswith("v_")


class TestConfigChange:
    def test_enabled_change_is_significant(self) -> None:
        a = ha._empty_config()
        b = ha._HaConfig(
            enabled=True, url=None, token=None,
            discovery_prefix="homeassistant", per_pid_toggles={},
        )
        assert ha._config_changed_significantly(a, b)

    def test_per_pid_toggle_change_alone_is_not_significant(self) -> None:
        a = ha._HaConfig(
            enabled=True, url="x", token="t",
            discovery_prefix="homeassistant", per_pid_toggles={},
        )
        b = ha._HaConfig(
            enabled=True, url="x", token="t",
            discovery_prefix="homeassistant", per_pid_toggles={"engine_rpm": False},
        )
        assert not ha._config_changed_significantly(a, b)

    def test_url_change_is_significant(self) -> None:
        a = ha._HaConfig(
            enabled=True, url="x", token="t",
            discovery_prefix="homeassistant", per_pid_toggles={},
        )
        b = ha._HaConfig(
            enabled=True, url="y", token="t",
            discovery_prefix="homeassistant", per_pid_toggles={},
        )
        assert ha._config_changed_significantly(a, b)


# ---------------------------------------------------------------------------
# Toggle-driven worker behaviour with fakes
# ---------------------------------------------------------------------------


class _FakePublisher:
    def __init__(self) -> None:
        self.published: list[tuple[str, str, bool]] = []  # (topic, payload, retain)
        self.connected = False
        self.connect_calls = 0
        self.disconnect_calls = 0

    async def connect(self) -> None:
        self.connect_calls += 1
        self.connected = True

    async def disconnect(self) -> None:
        self.disconnect_calls += 1
        self.connected = False

    async def publish(
        self, topic: str, payload: str, *, retain: bool = False, qos: int = 0
    ) -> None:
        if not self.connected:
            raise RuntimeError("not connected")
        self.published.append((topic, payload, retain))


class _FakeConfigStore:
    """Mutable settings store the worker polls."""

    def __init__(self) -> None:
        self._cfg = ha._empty_config()

    def set(self, **fields: Any) -> None:
        self._cfg = ha._HaConfig(
            enabled=fields.get("enabled", self._cfg.enabled),
            url=fields.get("url", self._cfg.url),
            token=fields.get("token", self._cfg.token),
            discovery_prefix=fields.get(
                "discovery_prefix", self._cfg.discovery_prefix
            ),
            per_pid_toggles=fields.get(
                "per_pid_toggles", self._cfg.per_pid_toggles
            ),
        )

    async def loader(self, _pool: Any) -> ha._HaConfig:
        return self._cfg


class _Cfg:
    """Stand-in for pitstop.config.Settings."""

    mqtt_host = "localhost"
    mqtt_port = 1883
    mqtt_user = ""
    mqtt_password = ""


def _vehicle_ctx() -> ha._VehicleCtx:
    return ha._VehicleCtx(
        vehicle={
            "id": uuid4(),
            "slug": "test-pilot",
            "name": "Test Pilot",
            "make": "Test",
            "model": "Pilot",
            "year": 2019,
        },
        profile_pids={
            "engine_rpm": {"name": "engine_rpm", "unit": "rpm"},
            "vehicle_speed": {"name": "vehicle_speed", "unit": "kph"},
        },
    )


@pytest.mark.asyncio
async def test_dormant_until_enabled(monkeypatch: pytest.MonkeyPatch) -> None:
    """When enabled=False, the worker must not connect or publish."""
    # Tighten the poll loop so the test stays fast.
    monkeypatch.setattr(ha, "SETTINGS_POLL_INTERVAL_S", 0.05)

    bus = EventBus(queue_maxsize=64)
    store = _FakeConfigStore()  # starts disabled
    pub = _FakePublisher()
    ctx = _vehicle_ctx()
    vid = ctx.vehicle["id"]

    async def _load_ctx(_pool: Any, _vid: UUID) -> ha._VehicleCtx:
        return ctx

    mirror = ha.HaMirror(
        pool=None,  # not used by fakes
        bus_=bus,
        config=_Cfg(),
        publisher_factory=lambda _c: pub,
        load_config=store.loader,
        load_vehicle_ctx=_load_ctx,
    )
    task = asyncio.create_task(mirror.run())
    try:
        # Push a few events while disabled.
        for i in range(3):
            await bus.publish(
                TelemetryEvent(
                    vehicle_id=vid,
                    time=datetime.now(UTC),
                    metric="engine_rpm",
                    value_num=1000.0 + i,
                    value_text=None,
                    source="wican",
                )
            )
        await asyncio.sleep(0.2)
        assert pub.connect_calls == 0
        assert pub.published == []
    finally:
        mirror.stop()
        await task


@pytest.mark.asyncio
async def test_enable_publishes_discovery_then_throttled_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """On enable, discovery once + state at <=1Hz; on disable, silent."""
    monkeypatch.setattr(ha, "SETTINGS_POLL_INTERVAL_S", 0.05)
    monkeypatch.setattr(ha, "EVENT_QUEUE_TIMEOUT_S", 0.02)
    monkeypatch.setattr(ha, "STATE_THROTTLE_S", 0.5)

    bus = EventBus(queue_maxsize=64)
    store = _FakeConfigStore()
    pub = _FakePublisher()
    ctx = _vehicle_ctx()
    vid = ctx.vehicle["id"]

    async def _load_ctx(_pool: Any, _vid: UUID) -> ha._VehicleCtx:
        return ctx

    mirror = ha.HaMirror(
        pool=None,
        bus_=bus,
        config=_Cfg(),
        publisher_factory=lambda _c: pub,
        load_config=store.loader,
        load_vehicle_ctx=_load_ctx,
    )
    task = asyncio.create_task(mirror.run())
    try:
        # Flip to enabled and wait one settings tick so reconcile runs.
        store.set(enabled=True, url="http://ha.local", token="abc")
        await asyncio.sleep(0.2)
        assert pub.connect_calls == 1, "should have connected once"

        # Burst three readings on the same metric within the throttle window.
        t0 = datetime.now(UTC)
        for i in range(3):
            await bus.publish(
                TelemetryEvent(
                    vehicle_id=vid,
                    time=t0,
                    metric="engine_rpm",
                    value_num=1000.0 + i,
                    value_text=None,
                    source="wican",
                )
            )
        await asyncio.sleep(0.2)

        # Topics by kind.
        topics = [t for t, _, _ in pub.published]
        retains = [r for _, _, r in pub.published]
        discoveries = [t for t in topics if t.endswith("/config")]
        states = [t for t in topics if t.endswith("/state")]

        assert len(discoveries) == 1, (
            f"discovery should publish exactly once, got {discoveries}"
        )
        # First publish must be the retained discovery.
        assert pub.published[0][0].endswith("/config")
        assert retains[0] is True
        # Throttle: only one state publish in the burst.
        assert len(states) == 1, f"expected throttled to 1 state, got {states}"
        # Discovery payload contents.
        cfg_payload = json.loads(pub.published[0][1])
        assert cfg_payload["unique_id"] == "pitstop_test_pilot_engine_rpm"
        assert cfg_payload["state_topic"].endswith(
            "/sensor/pitstop_test_pilot_engine_rpm/state"
        )
        # State retain must be False.
        state_retain = next(r for t, _, r in pub.published if t.endswith("/state"))
        assert state_retain is False

        # A different metric on the same vehicle gets its own discovery.
        await bus.publish(
            TelemetryEvent(
                vehicle_id=vid,
                time=t0,
                metric="vehicle_speed",
                value_num=42.0,
                value_text=None,
                source="wican",
            )
        )
        await asyncio.sleep(0.2)
        topics = [t for t, _, _ in pub.published]
        speed_cfgs = [
            t for t in topics
            if t.endswith("pitstop_test_pilot_vehicle_speed/config")
        ]
        assert len(speed_cfgs) == 1

        # Disable and verify no further publishes.
        before = len(pub.published)
        store.set(enabled=False)
        await asyncio.sleep(0.2)
        assert pub.disconnect_calls >= 1
        await bus.publish(
            TelemetryEvent(
                vehicle_id=vid,
                time=datetime.now(UTC),
                metric="engine_rpm",
                value_num=2000.0,
                value_text=None,
                source="wican",
            )
        )
        await asyncio.sleep(0.2)
        assert len(pub.published) == before, (
            "no publishes should happen after disable"
        )
    finally:
        mirror.stop()
        await task


@pytest.mark.asyncio
async def test_per_pid_toggle_skips_metric(monkeypatch: pytest.MonkeyPatch) -> None:
    """A per-PID toggle set to False suppresses both discovery and state."""
    monkeypatch.setattr(ha, "SETTINGS_POLL_INTERVAL_S", 0.05)
    monkeypatch.setattr(ha, "EVENT_QUEUE_TIMEOUT_S", 0.02)
    monkeypatch.setattr(ha, "STATE_THROTTLE_S", 0.0)

    bus = EventBus(queue_maxsize=64)
    store = _FakeConfigStore()
    pub = _FakePublisher()
    ctx = _vehicle_ctx()
    vid = ctx.vehicle["id"]

    async def _load_ctx(_pool: Any, _vid: UUID) -> ha._VehicleCtx:
        return ctx

    mirror = ha.HaMirror(
        pool=None,
        bus_=bus,
        config=_Cfg(),
        publisher_factory=lambda _c: pub,
        load_config=store.loader,
        load_vehicle_ctx=_load_ctx,
    )
    task = asyncio.create_task(mirror.run())
    try:
        store.set(
            enabled=True,
            url="http://ha.local",
            token="abc",
            per_pid_toggles={"engine_rpm": False, "vehicle_speed": True},
        )
        await asyncio.sleep(0.2)
        for metric in ("engine_rpm", "vehicle_speed"):
            await bus.publish(
                TelemetryEvent(
                    vehicle_id=vid,
                    time=datetime.now(UTC),
                    metric=metric,
                    value_num=1000.0,
                    value_text=None,
                    source="wican",
                )
            )
        await asyncio.sleep(0.2)
        topics = [t for t, _, _ in pub.published]
        # engine_rpm muted; vehicle_speed allowed.
        assert not any("engine_rpm" in t for t in topics)
        assert any("vehicle_speed" in t for t in topics)
    finally:
        mirror.stop()
        await task
