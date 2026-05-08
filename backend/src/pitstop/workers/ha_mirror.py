"""Home Assistant MQTT discovery + state mirror.

The worker is **dormant by default** (ADR-006). It polls the
``settings`` row every :data:`SETTINGS_POLL_INTERVAL_S` seconds; while
``settings.ha_enabled`` is ``False`` it touches neither the broker nor the
in-process bus, so Tier-D verifies ``homeassistant/#`` is silent.

When the toggle flips to ``True`` the worker:

1. Connects to Mosquitto with a separate ``pitstop-ha-mirror`` client id.
2. Subscribes to the in-process :class:`EventBus` (the same one the trip
   detector uses) and consumes :class:`TelemetryEvent` records.
3. On the *first* sighting of a ``(vehicle_id, metric)`` pair, publishes a
   retained Home Assistant MQTT-discovery sensor config to
   ``<discovery_prefix>/sensor/pitstop_<slug>_<metric>/config``.
4. On every subsequent reading, publishes the value (numeric or text) to
   the matching ``state_topic`` with retain=False.
5. Throttles state publishes to one every :data:`STATE_THROTTLE_S` seconds
   per ``(vehicle, metric)`` pair so a 10 Hz PID stream cannot drown HA.

When the toggle flips back to ``False`` the publisher disconnects cleanly
and the discovery cache is cleared so a future enable re-announces the
sensors. URL/token changes while running force a reconnect.

A per-PID toggle map (``settings.ha_per_pid_toggles``: ``metric -> bool``)
optionally suppresses individual metrics; missing entries default to
"emit".

On any MQTT/transient error the worker backs off 1s → 30s and retries; it
never crashes the parent task. Inserts are best-effort: a failed publish
is logged at WARNING and the worker keeps running.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import re
import time
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any
from uuid import UUID

import aiomqtt
import asyncpg

from ..version import VERSION

if TYPE_CHECKING:
    from ..config import Settings
    from .bus import EventBus, TelemetryEvent

log = logging.getLogger(__name__)


# Tunables (constants — these aren't user-facing toggles).
SETTINGS_POLL_INTERVAL_S: float = 5.0
STATE_THROTTLE_S: float = 1.0
RECONNECT_BACKOFF_INITIAL_S: float = 1.0
RECONNECT_BACKOFF_MAX_S: float = 30.0
HA_CLIENT_ID: str = "pitstop-ha-mirror"
EVENT_QUEUE_TIMEOUT_S: float = 1.0


# ---------------------------------------------------------------------------
# Pure helpers — testable without MQTT or DB
# ---------------------------------------------------------------------------


_SAFE_RE = re.compile(r"[^a-z0-9_]+")


def _safe_token(value: str) -> str:
    """Lowercase + replace non-``[a-z0-9_]`` so it's safe in an MQTT topic.

    HA topic chars are restrictive: allow word chars only. Empty result
    falls back to ``unknown``.
    """
    cleaned = _SAFE_RE.sub("_", value.lower()).strip("_")
    return cleaned or "unknown"


# Map common WiCAN-profile units to HA device classes / units.
# Anything not in this map falls through with no device_class and the unit
# passed through verbatim.
_UNIT_DEVICE_CLASS: dict[str, tuple[str | None, str | None]] = {
    # unit_of_measurement, device_class
    "rpm":         ("rpm",  None),
    "kph":         ("km/h", "speed"),
    "mph":         ("mph",  "speed"),
    "percent":     ("%",    None),
    "celsius":     ("°C",   "temperature"),
    "fahrenheit":  ("°F",   "temperature"),
    "volt":        ("V",    "voltage"),
    "g/s":         ("g/s",  None),
    "l/h":         ("L/h",  "volume_flow_rate"),
    "bitfield":    (None,   None),
}


def slugify_vehicle(vehicle: dict[str, Any]) -> str:
    """Stable HA-safe identifier for a vehicle row.

    Prefers the existing ``slug`` since the rest of the codebase already
    constrains it to ``[a-z0-9_-]+``; falls back to a UUID-derived token.
    """
    slug = vehicle.get("slug")
    if isinstance(slug, str) and slug:
        return _safe_token(slug.replace("-", "_"))
    vid = vehicle.get("id")
    if vid is not None:
        return f"v_{_safe_token(str(vid))}"
    return "unknown"


def discovery_topic(prefix: str, vehicle_slug: str, metric: str) -> str:
    """``<prefix>/sensor/pitstop_<vehicle>_<metric>/config``."""
    obj_id = f"pitstop_{_safe_token(vehicle_slug)}_{_safe_token(metric)}"
    return f"{prefix.rstrip('/')}/sensor/{obj_id}/config"


def state_topic(prefix: str, vehicle_slug: str, metric: str) -> str:
    """``<prefix>/sensor/pitstop_<vehicle>_<metric>/state``."""
    obj_id = f"pitstop_{_safe_token(vehicle_slug)}_{_safe_token(metric)}"
    return f"{prefix.rstrip('/')}/sensor/{obj_id}/state"


def build_discovery_payload(
    *,
    prefix: str,
    vehicle: dict[str, Any],
    metric: str,
    profile_pid: dict[str, Any] | None,
) -> dict[str, Any]:
    """Compose an MQTT-discovery sensor config matching HA's schema.

    ``profile_pid`` is the entry from ``pid_profiles.profile.pids`` keyed by
    the same metric name (passed through ``unit`` if present).
    """
    vslug = slugify_vehicle(vehicle)
    metric_safe = _safe_token(metric)
    obj_id = f"pitstop_{vslug}_{metric_safe}"

    unit_of_measurement: str | None = None
    device_class: str | None = None
    if profile_pid is not None:
        raw_unit = profile_pid.get("unit")
        if isinstance(raw_unit, str):
            mapped = _UNIT_DEVICE_CLASS.get(raw_unit.lower())
            if mapped is not None:
                unit_of_measurement, device_class = mapped
            else:
                # Unknown unit: pass through the raw unit string.
                unit_of_measurement = raw_unit

    # Friendly name: human metric (with the underscores). HA prepends the
    # device name ("Honda Pilot 2019 Engine RPM") when both are set.
    friendly_metric = metric.replace("_", " ").strip().title() or metric
    vehicle_name = vehicle.get("name") or vehicle.get("slug") or "Vehicle"

    device: dict[str, Any] = {
        "identifiers": [f"pitstop_{vslug}"],
        "name": str(vehicle_name),
        "manufacturer": "pitstop",
        "sw_version": VERSION,
    }
    # Vehicle-derived model: "<year> <make> <model>" if present, else fall
    # back to the slug.
    parts = [
        str(vehicle.get(k))
        for k in ("year", "make", "model")
        if vehicle.get(k)
    ]
    if parts:
        device["model"] = " ".join(parts)

    payload: dict[str, Any] = {
        "name": friendly_metric,
        "unique_id": obj_id,
        "object_id": obj_id,
        "state_topic": state_topic(prefix, vslug, metric),
        "device": device,
    }
    if unit_of_measurement is not None:
        payload["unit_of_measurement"] = unit_of_measurement
    if device_class is not None:
        payload["device_class"] = device_class
    return payload


def state_payload(value_num: float | None, value_text: str | None) -> str:
    """What we publish to the state topic. Numeric wins."""
    if value_num is not None:
        # HA tolerates floats with trailing zeros; we keep precision tight.
        # Avoid scientific notation for most real values.
        if value_num == int(value_num) and abs(value_num) < 1e16:
            return str(int(value_num))
        return f"{value_num:g}"
    if value_text is not None:
        return value_text
    return ""


# ---------------------------------------------------------------------------
# Settings + vehicle/profile lookups (DB-backed, cached)
# ---------------------------------------------------------------------------


@dataclass
class _HaConfig:
    enabled: bool
    url: str | None
    token: str | None  # never logged
    discovery_prefix: str
    per_pid_toggles: dict[str, bool]


def _empty_config() -> _HaConfig:
    return _HaConfig(
        enabled=False,
        url=None,
        token=None,
        discovery_prefix="homeassistant",
        per_pid_toggles={},
    )


def _config_changed_significantly(old: _HaConfig, new: _HaConfig) -> bool:
    """Anything that requires a reconnect."""
    return (
        old.enabled != new.enabled
        or old.url != new.url
        or old.token != new.token
        or old.discovery_prefix != new.discovery_prefix
    )


async def _load_ha_config(pool: asyncpg.Pool) -> _HaConfig:
    """Fetch the singleton settings row, defaulting to disabled on error."""
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT ha_enabled, ha_url, ha_token, ha_discovery_prefix, "
                "       ha_per_pid_toggles "
                "FROM settings WHERE id = 1"
            )
    except (asyncpg.PostgresError, OSError) as exc:
        log.warning("ha_mirror: settings fetch failed: %s", exc)
        return _empty_config()
    if row is None:
        return _empty_config()
    toggles_raw = row["ha_per_pid_toggles"]
    if isinstance(toggles_raw, str):
        try:
            toggles_raw = json.loads(toggles_raw)
        except json.JSONDecodeError:
            toggles_raw = {}
    toggles: dict[str, bool] = {}
    if isinstance(toggles_raw, dict):
        for k, v in toggles_raw.items():
            toggles[str(k)] = bool(v)
    return _HaConfig(
        enabled=bool(row["ha_enabled"]),
        url=row["ha_url"],
        token=row["ha_token"],
        discovery_prefix=row["ha_discovery_prefix"] or "homeassistant",
        per_pid_toggles=toggles,
    )


@dataclass
class _VehicleCtx:
    vehicle: dict[str, Any]
    profile_pids: dict[str, dict[str, Any]]


async def _load_vehicle_ctx(
    pool: asyncpg.Pool, vehicle_id: UUID
) -> _VehicleCtx | None:
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT v.id, v.slug, v.name, v.make, v.model, v.year,
                       p.profile
                  FROM vehicles v
             LEFT JOIN pid_profiles p ON p.id = v.pid_profile_id
                 WHERE v.id = $1
                """,
                vehicle_id,
            )
    except (asyncpg.PostgresError, OSError) as exc:
        log.warning("ha_mirror: vehicle fetch failed for %s: %s", vehicle_id, exc)
        return None
    if row is None:
        return None
    vehicle = {
        "id": row["id"],
        "slug": row["slug"],
        "name": row["name"],
        "make": row["make"],
        "model": row["model"],
        "year": row["year"],
    }
    profile_json = row["profile"]
    profile_pids: dict[str, dict[str, Any]] = {}
    if profile_json:
        for pid in profile_json.get("pids") or []:
            name = pid.get("name")
            if name:
                profile_pids[name] = pid
    return _VehicleCtx(vehicle=vehicle, profile_pids=profile_pids)


# ---------------------------------------------------------------------------
# MqttPublisher protocol — kept thin so tests can swap it
# ---------------------------------------------------------------------------


class _MqttPublisher:
    """Owns the live aiomqtt.Client. Re-creates it on connect()."""

    def __init__(self, *, host: str, port: int, user: str | None, password: str | None) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._stack: contextlib.AsyncExitStack | None = None
        self._client: aiomqtt.Client | None = None

    @property
    def connected(self) -> bool:
        return self._client is not None

    async def connect(self) -> None:
        if self._client is not None:
            return
        stack = contextlib.AsyncExitStack()
        try:
            client = await stack.enter_async_context(
                aiomqtt.Client(
                    hostname=self._host,
                    port=self._port,
                    username=self._user or None,
                    password=self._password or None,
                    identifier=HA_CLIENT_ID,
                )
            )
        except Exception:
            await stack.aclose()
            raise
        self._stack = stack
        self._client = client

    async def disconnect(self) -> None:
        stack = self._stack
        self._stack = None
        self._client = None
        if stack is not None:
            with contextlib.suppress(aiomqtt.MqttError, OSError, Exception):
                await stack.aclose()

    async def publish(
        self, topic: str, payload: str, *, retain: bool = False, qos: int = 0
    ) -> None:
        if self._client is None:
            raise aiomqtt.MqttError("publisher not connected")
        await self._client.publish(topic, payload=payload, retain=retain, qos=qos)


# ---------------------------------------------------------------------------
# HaMirror worker
# ---------------------------------------------------------------------------


class HaMirror:
    """Worker that mirrors EventBus telemetry into HA MQTT discovery topics.

    The worker accepts a *publisher factory* so tests can substitute a fake
    MQTT client without touching the real broker.
    """

    def __init__(
        self,
        *,
        pool: asyncpg.Pool,
        bus_: EventBus,
        config: Settings,
        publisher_factory: Any | None = None,
        load_config: Any | None = None,
        load_vehicle_ctx: Any | None = None,
    ) -> None:
        self._pool = pool
        self._bus = bus_
        self._cfg = config
        self._stop = asyncio.Event()
        self._publisher_factory = publisher_factory or self._default_publisher_factory
        self._load_config = load_config or _load_ha_config
        self._load_vehicle_ctx = load_vehicle_ctx or _load_vehicle_ctx

        # Live state — only populated while ha_enabled == True.
        self._publisher: _MqttPublisher | None = None
        self._current_config: _HaConfig = _empty_config()
        self._discovery_published: set[tuple[UUID, str]] = set()
        self._last_state_at: dict[tuple[UUID, str], float] = {}
        self._vehicle_ctx_cache: dict[UUID, _VehicleCtx | None] = {}
        self._queue: asyncio.Queue[TelemetryEvent] | None = None
        self._reconnect_backoff: float = RECONNECT_BACKOFF_INITIAL_S

    # -- public lifecycle ----------------------------------------------------

    async def run(self) -> None:
        log.info("ha_mirror: started (dormant until settings.ha_enabled=true)")
        try:
            while not self._stop.is_set():
                # Refresh config on every iteration. The function is
                # tolerant of DB hiccups (returns a disabled config).
                new_cfg = await self._load_config(self._pool)
                if _config_changed_significantly(self._current_config, new_cfg):
                    log.info(
                        "ha_mirror: config change (enabled=%s url=%s prefix=%s)",
                        new_cfg.enabled,
                        new_cfg.url,
                        new_cfg.discovery_prefix,
                    )
                    await self._reconcile_connection(new_cfg)
                self._current_config = new_cfg

                if not self._current_config.enabled:
                    # Sleep — broker untouched, bus untouched.
                    if await self._wait_or_stop(SETTINGS_POLL_INTERVAL_S):
                        return
                    continue

                # Enabled path: drain bus events for SETTINGS_POLL_INTERVAL_S,
                # then loop to re-check config.
                await self._drain_bus_for(SETTINGS_POLL_INTERVAL_S)
        finally:
            await self._teardown()
            log.info("ha_mirror: stopped")

    def stop(self) -> None:
        self._stop.set()

    # -- internals -----------------------------------------------------------

    def _default_publisher_factory(self, _cfg: _HaConfig) -> _MqttPublisher:
        return _MqttPublisher(
            host=self._cfg.mqtt_host,
            port=self._cfg.mqtt_port,
            user=self._cfg.mqtt_user or None,
            password=self._cfg.mqtt_password or None,
        )

    async def _wait_or_stop(self, seconds: float) -> bool:
        """Sleep ``seconds`` or until stop signal. Returns True if stopping."""
        try:
            await asyncio.wait_for(self._stop.wait(), timeout=seconds)
            return True
        except TimeoutError:
            return False

    async def _reconcile_connection(self, new_cfg: _HaConfig) -> None:
        """Bring the publisher in line with ``new_cfg.enabled``.

        On disable: disconnect, drop the bus queue, clear caches.
        On enable: subscribe to the bus and connect (with backoff).
        On URL/token change: tear down + reconnect.
        """
        was_enabled = self._current_config.enabled
        is_enabled = new_cfg.enabled

        # Either way, on a "significant change" tear down what we have so the
        # subsequent enable connects fresh.
        if was_enabled or self._publisher is not None:
            await self._teardown()

        if is_enabled:
            self._queue = self._bus.subscribe()
            self._discovery_published.clear()
            self._last_state_at.clear()
            self._vehicle_ctx_cache.clear()
            self._reconnect_backoff = RECONNECT_BACKOFF_INITIAL_S
            await self._ensure_connected(new_cfg)

    async def _teardown(self) -> None:
        if self._queue is not None:
            self._bus.unsubscribe(self._queue)
            self._queue = None
        if self._publisher is not None:
            await self._publisher.disconnect()
            self._publisher = None
        self._discovery_published.clear()
        self._last_state_at.clear()
        self._vehicle_ctx_cache.clear()

    async def _ensure_connected(self, cfg: _HaConfig) -> bool:
        """Connect if not already. Returns True if connected after the call."""
        if self._publisher is not None and self._publisher.connected:
            return True
        publisher = self._publisher_factory(cfg)
        try:
            await publisher.connect()
        except (aiomqtt.MqttError, OSError) as exc:
            log.warning(
                "ha_mirror: broker connect failed (backoff=%.1fs): %s",
                self._reconnect_backoff,
                exc,
            )
            await self._wait_or_stop(self._reconnect_backoff)
            self._reconnect_backoff = min(
                self._reconnect_backoff * 2.0, RECONNECT_BACKOFF_MAX_S
            )
            return False
        self._publisher = publisher
        self._reconnect_backoff = RECONNECT_BACKOFF_INITIAL_S
        log.info("ha_mirror: broker connected")
        return True

    async def _drain_bus_for(self, max_seconds: float) -> None:
        """Read events for up to ``max_seconds`` then return to the outer loop.

        We use a deadline rather than a single wait_for because each
        individual ``queue.get`` may block for less than the budget.
        """
        if self._queue is None:
            # Defensive: shouldn't happen because reconcile subscribes on
            # enable, but if it did, get back to the top of the loop.
            await self._wait_or_stop(max_seconds)
            return
        deadline = time.monotonic() + max_seconds
        while not self._stop.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            try:
                event = await asyncio.wait_for(
                    self._queue.get(), timeout=min(remaining, EVENT_QUEUE_TIMEOUT_S)
                )
            except TimeoutError:
                continue
            try:
                await self._handle_event(event)
            except Exception:
                # Never crash the worker on a bad event — log and continue.
                log.exception("ha_mirror: unhandled error processing event")

    async def _handle_event(self, event: TelemetryEvent) -> None:
        cfg = self._current_config
        if not cfg.enabled:
            return

        # Per-PID toggle: missing key = emit; explicit False = skip.
        if cfg.per_pid_toggles.get(event.metric, True) is False:
            return

        # Need a connected publisher to do anything.
        if (
            self._publisher is None or not self._publisher.connected
        ) and not await self._ensure_connected(cfg):
            return

        ctx = await self._get_vehicle_ctx(event.vehicle_id)
        if ctx is None:
            return  # vehicle row missing — drop silently
        vslug = slugify_vehicle(ctx.vehicle)

        key = (event.vehicle_id, event.metric)

        # Discovery: once per (vehicle, metric) per enable cycle.
        if key not in self._discovery_published:
            payload = build_discovery_payload(
                prefix=cfg.discovery_prefix,
                vehicle=ctx.vehicle,
                metric=event.metric,
                profile_pid=ctx.profile_pids.get(event.metric),
            )
            topic = discovery_topic(cfg.discovery_prefix, vslug, event.metric)
            try:
                await self._publisher.publish(
                    topic, json.dumps(payload), retain=True, qos=0
                )
            except (aiomqtt.MqttError, OSError) as exc:
                log.warning("ha_mirror: discovery publish failed: %s", exc)
                await self._handle_publish_failure()
                return
            self._discovery_published.add(key)
            log.info("ha_mirror: discovery for %s/%s", vslug, event.metric)

        # State throttle.
        now_mono = time.monotonic()
        last = self._last_state_at.get(key, 0.0)
        if (now_mono - last) < STATE_THROTTLE_S:
            return
        topic = state_topic(cfg.discovery_prefix, vslug, event.metric)
        try:
            await self._publisher.publish(
                topic,
                state_payload(event.value_num, event.value_text),
                retain=False,
                qos=0,
            )
        except (aiomqtt.MqttError, OSError) as exc:
            log.warning("ha_mirror: state publish failed: %s", exc)
            await self._handle_publish_failure()
            return
        self._last_state_at[key] = now_mono

    async def _handle_publish_failure(self) -> None:
        """Drop the publisher so the next event re-establishes it with backoff."""
        if self._publisher is not None:
            await self._publisher.disconnect()
        self._publisher = None
        # Discovery state must be re-announced after reconnect (the broker
        # already has retained discovery payloads for old session, but a new
        # session is the cleanest way to handle URL changes anyway).
        self._discovery_published.clear()

    async def _get_vehicle_ctx(self, vehicle_id: UUID) -> _VehicleCtx | None:
        if vehicle_id in self._vehicle_ctx_cache:
            return self._vehicle_ctx_cache[vehicle_id]
        ctx = await self._load_vehicle_ctx(self._pool, vehicle_id)
        self._vehicle_ctx_cache[vehicle_id] = ctx
        return ctx
