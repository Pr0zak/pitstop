"""Open-Meteo client + LRU cache for per-record weather observations.

Used by the trip deriver (at trip-close) and the fillup save path to
populate weather columns on `trips` and `fillups`. The
`workers.weather_backfiller` module reuses this same client to fill
in historical records on an hourly cycle.

Why Open-Meteo (Task #78 research): free for non-commercial use, no
API key, ERA5 historical archive back to 1940, generous quota
(<10k/day, 5k/hour). NWS has no historical lookup; OpenWeatherMap
needs a key + 1k/day cap. Open-Meteo wins decisively.

ERA5 has a ~5-day publication lag. We use:
- `/v1/forecast?past_days=1` for the realtime path (covers
  trip-close moments; current-day weather lives here).
- `/v1/archive` for backfill (anything older than ~7 days).
The fetcher picks based on the timestamp distance from "now".
"""

from __future__ import annotations

import logging
from collections import OrderedDict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import httpx

log = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class WeatherObs:
    temp_c: float | None
    humidity_pct: int | None
    precip_mm: float | None
    wind_kph: float | None
    weather_code: int | None


# Empty / unset observation — used as a "we tried but no data" marker.
EMPTY_OBS = WeatherObs(None, None, None, None, None)


_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
_ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
# ERA5 publication lag — anything within this window goes to the
# forecast endpoint instead.
_ARCHIVE_LAG = timedelta(days=7)
_HOURLY_FIELDS = (
    "temperature_2m,"
    "relativehumidity_2m,"
    "precipitation,"
    "windspeed_10m,"
    "weathercode"
)


class _LruCache:
    """Plain dict-based LRU keyed on `(lat_2dp, lon_2dp, hour_utc_iso)`.

    ~2.2 km grid at lat 45° + 1-hour resolution collapses adjacent
    fillups and same-trip duplicate calls. Capacity is generous —
    a typical day yields a few hundred unique slots.
    """

    def __init__(self, capacity: int = 4096) -> None:
        self._capacity = capacity
        self._data: OrderedDict[str, WeatherObs] = OrderedDict()

    def _key(self, lat: float, lon: float, when: datetime) -> str:
        whole = when.astimezone(timezone.utc).replace(minute=0, second=0, microsecond=0)
        return f"{round(lat, 2):.2f},{round(lon, 2):.2f},{whole.isoformat()}"

    def get(self, lat: float, lon: float, when: datetime) -> WeatherObs | None:
        k = self._key(lat, lon, when)
        v = self._data.get(k)
        if v is not None:
            self._data.move_to_end(k)
        return v

    def put(self, lat: float, lon: float, when: datetime, obs: WeatherObs) -> None:
        k = self._key(lat, lon, when)
        self._data[k] = obs
        self._data.move_to_end(k)
        while len(self._data) > self._capacity:
            self._data.popitem(last=False)


_cache = _LruCache()


def _pick_endpoint(when: datetime) -> str:
    age = datetime.now(timezone.utc) - when.astimezone(timezone.utc)
    return _ARCHIVE_URL if age > _ARCHIVE_LAG else _FORECAST_URL


def _params(lat: float, lon: float, when: datetime, archive: bool) -> dict[str, str]:
    when_utc = when.astimezone(timezone.utc)
    day = when_utc.date().isoformat()
    base: dict[str, str] = {
        "latitude": f"{lat:.4f}",
        "longitude": f"{lon:.4f}",
        "hourly": _HOURLY_FIELDS,
        "timezone": "UTC",
    }
    if archive:
        base["start_date"] = day
        base["end_date"] = day
    else:
        # forecast endpoint: anchor at today, allow up to 7 days back.
        days_back = max(0, (datetime.now(timezone.utc).date() - when_utc.date()).days)
        base["past_days"] = str(min(days_back, 7))
        base["forecast_days"] = "1"
    return base


def _hour_index(when: datetime, hourly_times: list[str]) -> int | None:
    """Return the index of the hourly slot closest to `when`. The API
    returns one entry per hour; we pick the matching ISO hour string.
    """
    if not hourly_times:
        return None
    target = (
        when.astimezone(timezone.utc)
        .replace(minute=0, second=0, microsecond=0)
        .strftime("%Y-%m-%dT%H:%M")
    )
    for i, t in enumerate(hourly_times):
        if t == target:
            return i
    # Fallback — caller tolerates imprecise match (closest hour).
    return 0


async def fetch(lat: float, lon: float, when: datetime) -> WeatherObs:
    """Fetch the weather observation closest to `when` at `(lat, lon)`.

    Returns [`EMPTY_OBS`] on any error / missing data so callers can
    cleanly INSERT NULLs without a try/except in every site.
    """
    cached = _cache.get(lat, lon, when)
    if cached is not None:
        return cached
    archive = _pick_endpoint(when) == _ARCHIVE_URL
    url = _ARCHIVE_URL if archive else _FORECAST_URL
    params = _params(lat, lon, when, archive)
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            body = resp.json()
    except (httpx.HTTPError, ValueError) as exc:
        log.warning("weather fetch failed: %s", exc)
        _cache.put(lat, lon, when, EMPTY_OBS)
        return EMPTY_OBS
    hourly = body.get("hourly") or {}
    times = hourly.get("time") or []
    idx = _hour_index(when, times)
    if idx is None:
        _cache.put(lat, lon, when, EMPTY_OBS)
        return EMPTY_OBS

    def _at(key: str) -> float | None:
        arr = hourly.get(key)
        if not arr or idx >= len(arr):
            return None
        v = arr[idx]
        if v is None:
            return None
        try:
            return float(v)
        except (TypeError, ValueError):
            return None

    temp = _at("temperature_2m")
    hum = _at("relativehumidity_2m")
    precip = _at("precipitation")
    wind = _at("windspeed_10m")
    code = _at("weathercode")
    obs = WeatherObs(
        temp_c=temp,
        humidity_pct=int(hum) if hum is not None else None,
        precip_mm=precip,
        wind_kph=wind,
        weather_code=int(code) if code is not None else None,
    )
    _cache.put(lat, lon, when, obs)
    return obs
