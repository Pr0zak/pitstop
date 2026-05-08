"""In-process pub/sub for telemetry events.

The MQTT ingest worker fans out parsed readings to in-process subscribers
(currently the trip detector). Bounded fan-out avoids unbounded memory
growth if a consumer stalls — slow consumers get their oldest events
dropped with a warn log.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

log = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class TelemetryEvent:
    """One PID reading after parsing.

    `value_num` is None for unparseable hex / pure-text payloads — those still
    flow through the bus so the trip detector counts them as "activity".
    """

    vehicle_id: UUID
    time: datetime
    metric: str
    value_num: float | None
    value_text: str | None
    source: str  # "wican" | "bridge"


class EventBus:
    """Async fan-out queue. Subscribers each get their own bounded queue."""

    def __init__(self, queue_maxsize: int = 1000) -> None:
        self._queue_maxsize = queue_maxsize
        self._subscribers: list[asyncio.Queue[TelemetryEvent]] = []
        self._lock = asyncio.Lock()

    def subscribe(self) -> asyncio.Queue[TelemetryEvent]:
        """Register a new subscriber. Returns a queue the caller drains."""
        q: asyncio.Queue[TelemetryEvent] = asyncio.Queue(maxsize=self._queue_maxsize)
        self._subscribers.append(q)
        return q

    def unsubscribe(self, queue: asyncio.Queue[TelemetryEvent]) -> None:
        with contextlib.suppress(ValueError):
            self._subscribers.remove(queue)

    async def publish(self, event: TelemetryEvent) -> None:
        """Push event to every subscriber. Drops oldest on a full queue."""
        for q in self._subscribers:
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                # Drop the oldest event to make room — favors recency.
                try:
                    _ = q.get_nowait()
                    q.task_done()
                except asyncio.QueueEmpty:
                    pass
                try:
                    q.put_nowait(event)
                except asyncio.QueueFull:
                    log.warning(
                        "EventBus subscriber queue full; dropping event "
                        "vehicle=%s metric=%s",
                        event.vehicle_id,
                        event.metric,
                    )


# Single process-wide bus instance. Workers import this directly.
bus = EventBus()
