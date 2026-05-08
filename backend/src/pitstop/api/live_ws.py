"""Live telemetry WebSocket — streams ingest events to subscribed UI clients.

Token auth via ``?token=...`` query string (browsers cannot set headers on
WebSocket upgrades). Filtering by ``vehicle_id`` is mandatory; we don't have
a use case for fan-out across all vehicles in one socket today.

Each connected client gets its own bounded asyncio.Queue via ``EventBus``.
On a slow client, the bus drops the oldest event — favors recency.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from datetime import UTC, datetime
from uuid import UUID

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, status

from ..config import settings as app_settings
from ..workers.bus import EventBus, bus

log = logging.getLogger(__name__)

router = APIRouter()


PING_INTERVAL_S = 25
PONG_DEADLINE_S = 60


def _check_query_token(token: str | None) -> bool:
    if not app_settings.query_token:
        return True  # auth disabled
    return token == app_settings.query_token


@router.websocket("/ws/live")
async def live_ws(
    websocket: WebSocket,
    vehicle_id: UUID | None = None,
    token: str | None = None,
) -> None:
    if not _check_query_token(token):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return
    if vehicle_id is None:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    log.info("ws/live connected vehicle=%s", vehicle_id)

    # Use the global bus (process-wide). Tests may swap it via app.state.bus.
    event_bus: EventBus = getattr(websocket.app.state, "bus", bus)
    queue = event_bus.subscribe()
    last_pong_at = datetime.now(UTC)

    async def _drain_queue() -> None:
        """Read TelemetryEvents and forward as JSON; filter by vehicle_id."""
        while True:
            event = await queue.get()
            if event.vehicle_id != vehicle_id:
                continue
            payload = {
                "vehicle_id": str(event.vehicle_id),
                "time": event.time.isoformat(),
                "metric": event.metric,
                "value_num": event.value_num,
                "value_text": event.value_text,
                "source": event.source,
            }
            await websocket.send_text(json.dumps(payload))

    async def _heartbeat() -> None:
        nonlocal last_pong_at
        while True:
            await asyncio.sleep(PING_INTERVAL_S)
            try:
                await websocket.send_text(json.dumps({"type": "ping"}))
            except (WebSocketDisconnect, RuntimeError):
                return
            now = datetime.now(UTC)
            if (now - last_pong_at).total_seconds() > PONG_DEADLINE_S:
                log.info("ws/live pong timeout; closing vehicle=%s", vehicle_id)
                with contextlib.suppress(RuntimeError):
                    await websocket.close(code=status.WS_1011_INTERNAL_ERROR)
                return

    async def _read_pongs() -> None:
        nonlocal last_pong_at
        while True:
            try:
                msg = await websocket.receive_text()
            except WebSocketDisconnect:
                return
            try:
                data = json.loads(msg)
            except (json.JSONDecodeError, TypeError):
                continue
            if isinstance(data, dict) and data.get("type") in {"pong", "ping"}:
                last_pong_at = datetime.now(UTC)

    drain_task = asyncio.create_task(_drain_queue())
    hb_task = asyncio.create_task(_heartbeat())
    rd_task = asyncio.create_task(_read_pongs())

    try:
        done, pending = await asyncio.wait(
            {drain_task, hb_task, rd_task}, return_when=asyncio.FIRST_COMPLETED
        )
        for task in pending:
            task.cancel()
        for task in done:
            with contextlib.suppress(Exception):
                task.result()
    except WebSocketDisconnect:
        pass
    finally:
        for task in (drain_task, hb_task, rd_task):
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        event_bus.unsubscribe(queue)
        log.info("ws/live disconnected vehicle=%s", vehicle_id)
