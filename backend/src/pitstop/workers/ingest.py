"""MQTT ingest worker.

Subscribes to ``wican/+/+`` and ``bridge/+/+``, resolves the vehicle slug to
its UUID, parses the payload (numeric → float; hex → formula via the
vehicle's PID profile; otherwise raw text), batches inserts into the
``pid_readings`` hypertable, and best-effort updates ``vehicle_state`` with
the latest value.

Each parsed event is also published to the in-process :mod:`EventBus` so the
trip detector worker can react to it.
"""

from __future__ import annotations

import ast
import asyncio
import contextlib
import json
import logging
import operator
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import aiomqtt
import asyncpg

from ..config import Settings
from ..config import settings as default_settings
from .bus import EventBus, TelemetryEvent
from .bus import bus as default_bus
from .wican_aliases import normalise

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Topic parsing
# ---------------------------------------------------------------------------


VALID_SOURCES = {"wican", "bridge"}


@dataclass(frozen=True, slots=True)
class ParsedTopic:
    source: str  # "wican" | "bridge"
    vehicle_slug: str
    metric: str


def parse_topic(topic: str) -> ParsedTopic | None:
    """Parse ``<source>/<vehicle_slug>/<metric>``. Returns None on bad shape."""
    if not topic:
        return None
    parts = topic.split("/")
    if len(parts) != 3:
        return None
    source, slug, metric = parts
    if source not in VALID_SOURCES:
        return None
    if not slug or not metric:
        return None
    return ParsedTopic(source=source, vehicle_slug=slug, metric=metric)


# ---------------------------------------------------------------------------
# Hex payload + WiCAN expression evaluator
# ---------------------------------------------------------------------------


_HEX_CHARS = set("0123456789abcdefABCDEF")


def is_hex_payload(payload: str) -> bool:
    """True for an even-length, len>2 hex blob (multi-byte response)."""
    if len(payload) <= 2:
        return False
    if len(payload) % 2:
        return False
    return all(c in _HEX_CHARS for c in payload)


# Allowed AST nodes for the WiCAN expression language. Profiles use formulas
# like ``((A*256)+B)/4`` and ``(A*100)/255`` over per-byte names. We never
# eval untrusted Python — only this restricted grammar.
_BIN_OPS: dict[type[ast.AST], Any] = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
    ast.LShift: operator.lshift,
    ast.RShift: operator.rshift,
    ast.BitOr: operator.or_,
    ast.BitXor: operator.xor,
    ast.BitAnd: operator.and_,
}
_UNARY_OPS: dict[type[ast.AST], Any] = {
    ast.UAdd: operator.pos,
    ast.USub: operator.neg,
    ast.Invert: operator.invert,
}


def _eval_expr(node: ast.AST, env: dict[str, int]) -> float:
    if isinstance(node, ast.Expression):
        return _eval_expr(node.body, env)
    if isinstance(node, ast.Constant):
        if isinstance(node.value, (int, float)):
            return float(node.value)
        raise ValueError(f"unsupported constant: {node.value!r}")
    if isinstance(node, ast.Name):
        if node.id not in env:
            raise ValueError(f"unknown name {node.id!r}")
        return float(env[node.id])
    if isinstance(node, ast.BinOp):
        op_fn = _BIN_OPS.get(type(node.op))
        if op_fn is None:
            raise ValueError(f"unsupported binop {type(node.op).__name__}")
        return op_fn(_eval_expr(node.left, env), _eval_expr(node.right, env))
    if isinstance(node, ast.UnaryOp):
        op_fn = _UNARY_OPS.get(type(node.op))
        if op_fn is None:
            raise ValueError(f"unsupported unaryop {type(node.op).__name__}")
        return op_fn(_eval_expr(node.operand, env))
    raise ValueError(f"unsupported node {type(node).__name__}")


def _byte_env(payload_hex: str) -> dict[str, int]:
    """Map ``A``..``Z``, ``AA``..``ZZ`` to byte values from the hex payload.

    WiCAN convention: A is the first byte, B the second, etc. Some custom
    PIDs use AA which means "two bytes A and A interpreted as a 16-bit int."
    Honda's ATF formula uses ``AA`` (the docs read it as a single 16-bit
    word starting at A). We expose both: per-byte single-letter names AND
    the two-letter form ``AA`` = (A<<8)|B, ``BB`` = (B<<8)|C, etc.
    """
    raw = bytes.fromhex(payload_hex)
    env: dict[str, int] = {}
    for i, b in enumerate(raw):
        if i < 26:
            env[chr(ord("A") + i)] = b
    # Two-letter forms = consecutive 16-bit big-endian word starting at index i.
    for i in range(len(raw) - 1):
        if i < 26:
            name = chr(ord("A") + i) * 2
            env[name] = (raw[i] << 8) | raw[i + 1]
    return env


def evaluate_expression(expression: str, payload_hex: str) -> float:
    """Apply a WiCAN profile expression to a hex byte payload."""
    tree = ast.parse(expression, mode="eval")
    env = _byte_env(payload_hex)
    return _eval_expr(tree, env)


# ---------------------------------------------------------------------------
# Vehicle slug → UUID + profile cache
# ---------------------------------------------------------------------------


@dataclass
class _CacheEntry:
    vehicle_id: UUID
    profile_pids: dict[str, dict]  # metric name -> pid def
    expires_at: float


class VehicleCache:
    """Slug → (vehicle_id, profile-by-metric) with TTL."""

    def __init__(self, ttl_s: int = 300) -> None:
        self._ttl = ttl_s
        self._cache: dict[str, _CacheEntry] = {}
        self._misses: set[str] = set()  # remember slugs we've already warned for
        self._lock = asyncio.Lock()

    async def get(
        self, pool: asyncpg.Pool, slug: str
    ) -> tuple[UUID, dict[str, dict]] | None:
        now = time.monotonic()
        entry = self._cache.get(slug)
        if entry and entry.expires_at > now:
            return entry.vehicle_id, entry.profile_pids
        async with self._lock:
            # double-check after acquiring lock
            entry = self._cache.get(slug)
            if entry and entry.expires_at > now:
                return entry.vehicle_id, entry.profile_pids
            # First try the direct slug match (covers the common case where
            # the WiCAN MQTT prefix was set to a real vehicle slug like
            # "pilot19" at config time).
            row = await pool.fetchrow(
                """
                SELECT v.id, p.profile
                  FROM vehicles v
                  LEFT JOIN pid_profiles p ON p.id = v.pid_profile_id
                 WHERE v.slug = $1
                """,
                slug,
            )
            if row is None:
                # Fall back to device_vehicle_map — handles the WiCAN-using-
                # MAC-as-topic case ("wican/94a9901b16d1/...") so the user
                # doesn't have to reconfigure the device.
                row = await pool.fetchrow(
                    """
                    SELECT v.id, p.profile
                      FROM device_vehicle_map d
                      JOIN vehicles v ON v.id = d.vehicle_id
                      LEFT JOIN pid_profiles p ON p.id = v.pid_profile_id
                     WHERE d.device_id = $1
                    """,
                    slug,
                )
                if row is not None:
                    # Touch last_seen_at on the mapping so the admin UI
                    # can sort active devices first. Fire-and-forget so
                    # ingest doesn't block on the write.
                    await pool.execute(
                        "UPDATE device_vehicle_map SET last_seen_at = now() "
                        "WHERE device_id = $1",
                        slug,
                    )
            if row is None:
                if slug not in self._misses:
                    log.warning(
                        "dropping message for unknown device/slug %r — "
                        "map it via POST /admin/devices/%s/map "
                        "or set the device's MQTT topic to a vehicle slug",
                        slug, slug,
                    )
                    self._misses.add(slug)
                return None
            profile_pids: dict[str, dict] = {}
            profile_json = row["profile"]
            if profile_json:
                pids = profile_json.get("pids") or []
                for pid in pids:
                    name = pid.get("name")
                    if name:
                        profile_pids[name] = pid
            self._cache[slug] = _CacheEntry(
                vehicle_id=row["id"],
                profile_pids=profile_pids,
                expires_at=now + self._ttl,
            )
            self._misses.discard(slug)
            return row["id"], profile_pids

    def invalidate(self, slug: str | None = None) -> None:
        if slug is None:
            self._cache.clear()
            self._misses.clear()
        else:
            self._cache.pop(slug, None)
            self._misses.discard(slug)


# ---------------------------------------------------------------------------
# Reading shape passed to the batch inserter
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class _PendingReading:
    vehicle_id: UUID
    time: datetime
    metric: str
    value_num: float | None
    value_text: str | None
    source: str


# ---------------------------------------------------------------------------
# MqttIngest
# ---------------------------------------------------------------------------


class MqttIngest:
    """aiomqtt-driven ingest pipeline."""

    def __init__(
        self,
        *,
        pool: asyncpg.Pool,
        bus_: EventBus | None = None,
        config: Settings | None = None,
    ) -> None:
        self._pool = pool
        self._bus = bus_ or default_bus
        self._cfg = config or default_settings
        self._cache = VehicleCache(ttl_s=self._cfg.vehicle_cache_ttl_s)
        self._batch: list[_PendingReading] = []
        self._batch_lock = asyncio.Lock()
        self._stop = asyncio.Event()
        self._unparsed_warns: set[tuple[UUID, str]] = set()

    # -- helpers ---------------------------------------------------------

    def _parse_payload(
        self,
        metric: str,
        payload: str,
        profile_pids: dict[str, dict],
        vehicle_id: UUID,
    ) -> tuple[float | None, str | None]:
        s = payload.strip()
        if not s:
            return None, None
        # Numeric first.
        try:
            return float(s), None
        except ValueError:
            pass
        # Hex with profile lookup.
        if is_hex_payload(s):
            pid_def = profile_pids.get(metric)
            expression = pid_def.get("expression") if pid_def else None
            if expression:
                try:
                    return float(evaluate_expression(expression, s)), None
                except (ValueError, ZeroDivisionError, SyntaxError) as exc:
                    log.warning(
                        "expression eval failed vehicle=%s metric=%s expr=%r err=%s",
                        vehicle_id,
                        metric,
                        expression,
                        exc,
                    )
                    return None, s
            # No formula known — store hex as text, warn once.
            key = (vehicle_id, metric)
            if key not in self._unparsed_warns:
                log.warning(
                    "no profile formula for hex payload vehicle=%s metric=%s",
                    vehicle_id,
                    metric,
                )
                self._unparsed_warns.add(key)
            return None, s
        # Otherwise plain text.
        return None, s

    @staticmethod
    def _parse_v2_scalar(payload: str, default_when: datetime) -> tuple[float, datetime] | None:
        """Recognise the bridge payload v2 shape: ``{"v": <number>, "t": <ms>}``.

        Phone bridge wraps every per-metric value in this envelope so the
        original capture timestamp survives an offline-buffer drain. ``t``
        is unix milliseconds (System.currentTimeMillis on the phone).

        Returns ``None`` if the payload isn't this shape (caller falls back
        to the legacy scalar/AutoPID paths). Returns ``(value, when)`` on
        match — when ``t`` is missing we use ``default_when`` so partial v2
        payloads (e.g. live-stream paths that haven't been migrated yet)
        still work.
        """
        s = payload.strip()
        if not (s.startswith("{") and s.endswith("}")):
            return None
        try:
            obj = json.loads(s)
        except json.JSONDecodeError:
            return None
        if not isinstance(obj, dict):
            return None
        # v2 has at most {"v","t"}. WiCAN's AutoPID payloads have many keys.
        if "v" not in obj:
            return None
        if not set(obj.keys()).issubset({"v", "t"}):
            return None
        v = obj["v"]
        if not isinstance(v, (int, float, bool)):
            return None
        when = default_when
        t_ms = obj.get("t")
        if isinstance(t_ms, (int, float)):
            try:
                when = datetime.fromtimestamp(float(t_ms) / 1000.0, tz=UTC)
            except (OverflowError, OSError, ValueError):
                pass  # fall back to default_when
        return float(v), when

    def _fanout_json_payload(
        self,
        payload: str,
        profile_pids: dict[str, dict] | None,
        vehicle_id: UUID,
        source: str,
        when: datetime,
    ) -> list[_PendingReading] | None:
        """Recognise a WiCAN AutoPID consolidated-JSON payload and fan it out.

        WiCAN publishes one JSON object per polling cycle to a single topic,
        with per-PID name→value pairs at the top level (e.g.
        ``{"engine_rpm": 1600, "vehicle_speed": 42}``). For pitstop's table
        we want one reading per (vehicle, metric, time), so we expand the
        object here into a list of _PendingReading rows that share the
        same timestamp.

        Returns ``None`` when the payload isn't a JSON object — caller falls
        back to the scalar path. Returns ``[]`` when the payload is a JSON
        object but every key is unparseable, so we silently drop and don't
        re-treat the whole string as a scalar.
        """
        s = payload.strip()
        if not (s.startswith("{") and s.endswith("}")):
            return None
        try:
            obj = json.loads(s)
        except json.JSONDecodeError:
            return None
        if not isinstance(obj, dict):
            return None
        # Bridge v2 envelope ({"v":..,"t":..}) is handled by _parse_v2_scalar.
        # If we see a v2 shape here, decline and let the caller route it.
        if "v" in obj and set(obj.keys()).issubset({"v", "t"}):
            return None
        out: list[_PendingReading] = []
        for k, v in obj.items():
            if not isinstance(k, str):
                continue
            metric = k.strip()
            if not metric:
                continue
            # Translate WiCAN auto-names ("0C-EngineRPM") to pitstop canonical
            # names ("engine_rpm") so the frontend gauges + analytics work
            # without renaming every PID in the WiCAN UI.
            canonical = normalise(metric)
            if canonical is None:
                continue  # explicitly-dropped key (e.g. WiCAN's synthetic timestamp)
            metric = canonical
            value_num: float | None = None
            value_text: str | None = None
            if isinstance(v, bool):
                value_num = float(v)
            elif isinstance(v, (int, float)):
                value_num = float(v)
            elif v is None:
                continue  # drop nulls; nothing to record
            elif isinstance(v, str):
                # If the value is itself a hex/string blob, run it through
                # the same per-metric path so profile formulas still apply.
                vn, vt = self._parse_payload(metric, v, profile_pids, vehicle_id)
                value_num, value_text = vn, vt
            else:
                value_text = json.dumps(v)
            if value_num is None and value_text is None:
                continue
            out.append(
                _PendingReading(
                    vehicle_id=vehicle_id,
                    time=when,
                    metric=metric,
                    value_num=value_num,
                    value_text=value_text,
                    source=source,
                )
            )
        return out

    async def _handle_message(self, msg: aiomqtt.Message) -> None:
        topic = str(msg.topic)
        parsed = parse_topic(topic)
        if parsed is None:
            log.debug("rejecting bad topic %r", topic)
            return
        try:
            payload = msg.payload.decode("utf-8") if msg.payload else ""
        except UnicodeDecodeError:
            log.warning("non-utf8 payload on topic %r; dropping", topic)
            return
        resolved = await self._cache.get(self._pool, parsed.vehicle_slug)
        if resolved is None:
            return
        vehicle_id, profile_pids = resolved
        now = datetime.now(tz=UTC)

        # Bridge v2 dedicated topics — these don't land in pid_readings;
        # they have their own tables so /trips can render maps and trip
        # boundaries cleanly.
        if parsed.source == "bridge" and parsed.metric == "location":
            await self._handle_location(payload, vehicle_id, now)
            return
        if parsed.source == "bridge" and parsed.metric == "engine_state":
            await self._handle_engine_state(payload, vehicle_id, now)
            return

        # Bridge v2 envelope: {"v": <num>, "t": <unix_ms>}. Honors the
        # original capture time so an offline-buffer drain doesn't collapse
        # an hour of driving into a 3-minute ingest window.
        v2 = self._parse_v2_scalar(payload, now)
        if v2 is not None:
            value_num, when = v2
            readings = [
                _PendingReading(
                    vehicle_id=vehicle_id,
                    time=when,
                    metric=parsed.metric,
                    value_num=value_num,
                    value_text=None,
                    source=parsed.source,
                )
            ]
        else:
            # Legacy AutoPID consolidated-JSON path (WiCAN) or scalar
            # payload (bridge/<slug>/<metric> bare value, pre-v2 phone
            # builds).
            readings = self._fanout_json_payload(
                payload, profile_pids, vehicle_id, parsed.source, now
            )
            if readings is None:
                value_num, value_text = self._parse_payload(
                    parsed.metric, payload, profile_pids, vehicle_id
                )
                if value_num is None and value_text is None:
                    return  # blank payload
                readings = [
                    _PendingReading(
                        vehicle_id=vehicle_id,
                        time=now,
                        metric=parsed.metric,
                        value_num=value_num,
                        value_text=value_text,
                        source=parsed.source,
                    )
                ]
        if not readings:
            return

        async with self._batch_lock:
            self._batch.extend(readings)
            full = len(self._batch) >= self._cfg.ingest_batch_max_rows
        if full:
            await self._flush()
        for reading in readings:
            await self._bus.publish(
                TelemetryEvent(
                    vehicle_id=reading.vehicle_id,
                    time=reading.time,
                    metric=reading.metric,
                    value_num=reading.value_num,
                    value_text=reading.value_text,
                    source=reading.source,
                )
            )

    async def _handle_location(
        self, payload: str, vehicle_id: UUID, default_when: datetime
    ) -> None:
        """Parse {"t","lat","lon","alt","speed","heading","acc"} and insert
        into gps_points. Required keys: lat, lon. All other fields optional.
        """
        s = payload.strip()
        if not (s.startswith("{") and s.endswith("}")):
            return
        try:
            obj = json.loads(s)
        except json.JSONDecodeError:
            return
        if not isinstance(obj, dict):
            return
        try:
            lat = float(obj["lat"])
            lon = float(obj["lon"])
        except (KeyError, TypeError, ValueError):
            return
        when = default_when
        t_ms = obj.get("t")
        if isinstance(t_ms, (int, float)):
            try:
                when = datetime.fromtimestamp(float(t_ms) / 1000.0, tz=UTC)
            except (OverflowError, OSError, ValueError):
                pass

        def _opt_float(key: str) -> float | None:
            v = obj.get(key)
            if isinstance(v, (int, float)):
                return float(v)
            return None

        try:
            async with self._pool.acquire() as conn:
                await conn.execute(
                    """
                    INSERT INTO gps_points
                        (time, vehicle_id, lat, lon, alt_m,
                         speed_mps, heading_deg, accuracy_m, source)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 'bridge')
                    """,
                    when,
                    vehicle_id,
                    lat,
                    lon,
                    _opt_float("alt"),
                    _opt_float("speed"),
                    _opt_float("heading"),
                    _opt_float("acc"),
                )
        except (asyncpg.PostgresError, OSError) as exc:
            log.warning("gps_points insert failed: %s", exc)

    async def _handle_engine_state(
        self, payload: str, vehicle_id: UUID, default_when: datetime
    ) -> None:
        """Parse {"t","state":"on"|"off"} and insert into engine_events.

        Trip detector (Pass 3) uses these as authoritative trip boundaries
        when present; legacy silence-based detection still runs for
        vehicles that don't publish the new event.
        """
        s = payload.strip()
        if not (s.startswith("{") and s.endswith("}")):
            return
        try:
            obj = json.loads(s)
        except json.JSONDecodeError:
            return
        if not isinstance(obj, dict):
            return
        state = obj.get("state")
        if state not in ("on", "off"):
            return
        when = default_when
        t_ms = obj.get("t")
        if isinstance(t_ms, (int, float)):
            try:
                when = datetime.fromtimestamp(float(t_ms) / 1000.0, tz=UTC)
            except (OverflowError, OSError, ValueError):
                pass
        try:
            async with self._pool.acquire() as conn:
                await conn.execute(
                    """
                    INSERT INTO engine_events (time, vehicle_id, state, source)
                    VALUES ($1, $2, $3, 'bridge')
                    """,
                    when,
                    vehicle_id,
                    state,
                )
        except (asyncpg.PostgresError, OSError) as exc:
            log.warning("engine_events insert failed: %s", exc)
        # Fan out to the in-process bus so the trip detector reacts in
        # real time. Synthetic metric name `_engine_state` is recognised
        # there as authoritative trip-boundary signal; value_text carries
        # the new state.
        await self._bus.publish(
            TelemetryEvent(
                vehicle_id=vehicle_id,
                time=when,
                metric="_engine_state",
                value_num=None,
                value_text=state,
                source="bridge",
            )
        )

    async def _flush(self) -> None:
        async with self._batch_lock:
            if not self._batch:
                return
            batch = self._batch
            self._batch = []
        rows = [
            (r.time, r.vehicle_id, r.metric, r.value_num, r.value_text, r.source)
            for r in batch
        ]
        try:
            async with self._pool.acquire() as conn:
                await conn.executemany(
                    """
                    INSERT INTO pid_readings
                        (time, vehicle_id, metric, value_num, value_text, source)
                    VALUES ($1, $2, $3, $4, $5, $6)
                    ON CONFLICT (vehicle_id, metric, time) DO NOTHING
                    """,
                    rows,
                )
        except (asyncpg.PostgresError, OSError) as exc:
            log.error("pid_readings batch insert failed (%d rows): %s", len(rows), exc)
            return
        # Best-effort vehicle_state update — separate connection so a failure
        # here can't block readings inserts. Keep the latest reading per
        # (vehicle, metric) within the batch so a fast burst of multiple
        # metrics all land in the snapshot, not just the last topic.
        latest_per_vm: dict[tuple[UUID, str], _PendingReading] = {}
        for r in batch:
            key = (r.vehicle_id, r.metric)
            cur = latest_per_vm.get(key)
            if cur is None or r.time >= cur.time:
                latest_per_vm[key] = r
        for r in latest_per_vm.values():
            try:
                async with self._pool.acquire() as conn:
                    await conn.execute(
                        """
                        INSERT INTO vehicle_state
                            (vehicle_id, last_seen_at, last_metric, latest, updated_at)
                        VALUES (
                            $1::uuid, $2::timestamptz, $3::text,
                            jsonb_build_object($3::text, jsonb_build_object(
                                'value_num', $4::float8,
                                'value_text', $5::text,
                                'time', to_char($2::timestamptz AT TIME ZONE 'UTC',
                                    'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
                                'source', $6::text
                            )),
                            now()
                        )
                        ON CONFLICT (vehicle_id) DO UPDATE
                            SET last_seen_at = EXCLUDED.last_seen_at,
                                last_metric = EXCLUDED.last_metric,
                                latest = vehicle_state.latest || EXCLUDED.latest,
                                updated_at = now()
                        """,
                        r.vehicle_id,
                        r.time,
                        r.metric,
                        r.value_num,
                        r.value_text,
                        r.source,
                    )
            except (asyncpg.PostgresError, OSError) as exc:
                log.warning(
                    "vehicle_state update failed vehicle=%s: %s", r.vehicle_id, exc
                )

    async def _flush_loop(self) -> None:
        """Time-based flush. Triggers every ``ingest_batch_max_ms`` ms."""
        interval = max(self._cfg.ingest_batch_max_ms, 1) / 1000
        while not self._stop.is_set():
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=interval)
                # stop signaled
                break
            except TimeoutError:
                pass
            await self._flush()

    # -- public lifecycle ------------------------------------------------

    async def run(self) -> None:
        """Main loop. Reconnects forever on transient broker failures."""
        flush_task = asyncio.create_task(self._flush_loop())
        try:
            while not self._stop.is_set():
                try:
                    async with aiomqtt.Client(
                        hostname=self._cfg.mqtt_host,
                        port=self._cfg.mqtt_port,
                        username=self._cfg.mqtt_user or None,
                        password=self._cfg.mqtt_password or None,
                    ) as client:
                        log.info(
                            "MQTT connected host=%s port=%d",
                            self._cfg.mqtt_host,
                            self._cfg.mqtt_port,
                        )
                        await client.subscribe("wican/+/+")
                        await client.subscribe("bridge/+/+")
                        async for msg in client.messages:
                            if self._stop.is_set():
                                break
                            await self._handle_message(msg)
                except aiomqtt.MqttError as exc:
                    log.warning("MQTT error, reconnecting in 5s: %s", exc)
                    try:
                        await asyncio.wait_for(self._stop.wait(), timeout=5.0)
                        break
                    except TimeoutError:
                        continue
                except asyncio.CancelledError:
                    raise
                except Exception:
                    log.exception("unexpected MQTT loop error; backing off 5s")
                    try:
                        await asyncio.wait_for(self._stop.wait(), timeout=5.0)
                        break
                    except TimeoutError:
                        continue
        finally:
            self._stop.set()
            flush_task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await flush_task
            await self._flush()

    def stop(self) -> None:
        self._stop.set()
