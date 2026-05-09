"""EIA weekly retail-gasoline price worker.

Pulls the U.S. Energy Information Administration's free weekly
retail-gasoline CSV and stores per-region per-grade snapshots in
fuel_market_weekly. Runs once per day on a 24-hour cycle; EIA
publishes the report on Monday so a daily check is plenty to catch
the new week without polling tightly.

CSV source — public, no auth, no rate limits:
    https://www.eia.gov/petroleum/gasdiesel/csv/pswrgvwall.csv

Format (real headers):
    Date, U.S., East Coast, New England, Central Atlantic, Lower
    Atlantic, Midwest, Gulf Coast, Rocky Mountain, West Coast,
    West Coast (PADD 5) Except California, California, ...

Each column is dollars per gallon, regular grade, weekly average.

For now we ingest the U.S. + 5 PADD regions + California for the
"regular" grade only — premium / midgrade live on separate CSVs
(pswrgvwallm.csv etc.) and can be wired in a follow-up.

Worker errors (HTTP fail, parse fail, network down) are caught at the
outer loop and logged; we sleep and retry on the next cycle so a bad
day doesn't kill the worker.
"""

from __future__ import annotations

import asyncio
import csv
import io
import logging
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import asyncpg
import httpx

log = logging.getLogger(__name__)

# Once per day — EIA publishes Monday but the timestamps in the file
# update once. Daily cadence catches the update without polling tight.
CYCLE_INTERVAL_SECONDS = 24 * 3_600

EIA_CSV_URL = "https://www.eia.gov/petroleum/gasdiesel/csv/pswrgvwall.csv"

# Map EIA's column header → canonical region code we store. We accept
# slight header variations (newlines, extra whitespace) by .strip()ing
# and case-insensitive matching.
_REGION_MAP: dict[str, str] = {
    "u.s.": "us",
    "east coast": "east_coast",
    "new england": "new_england",
    "central atlantic": "central_atlantic",
    "lower atlantic": "lower_atlantic",
    "midwest": "midwest",
    "gulf coast": "gulf_coast",
    "rocky mountain": "rocky_mtn",
    "west coast": "west_coast",
    "california": "california",
}


def _parse_csv(text: str) -> list[tuple[str, str, Decimal]]:
    """Return list of (region_code, week_iso, price) for the most recent
    52 rows. Skips header + any leading commentary lines."""
    reader = csv.reader(io.StringIO(text))
    rows = [r for r in reader if r and r[0]]  # drop blank lines
    if not rows:
        return []

    # Find the header — first row that starts with "Date" (case-insensitive).
    header_idx = next(
        (i for i, r in enumerate(rows) if r and r[0].strip().lower() == "date"),
        None,
    )
    if header_idx is None:
        log.warning("eia: CSV header not found")
        return []
    header = [c.strip() for c in rows[header_idx]]
    data_rows = rows[header_idx + 1 :]

    # Build column index → region_code lookup.
    col_to_region: dict[int, str] = {}
    for i, col in enumerate(header[1:], start=1):  # skip Date column
        key = col.strip().lower()
        if key in _REGION_MAP:
            col_to_region[i] = _REGION_MAP[key]

    if not col_to_region:
        log.warning("eia: no recognised region columns in header")
        return []

    out: list[tuple[str, str, Decimal]] = []
    cutoff = datetime.now(timezone.utc) - timedelta(weeks=104)  # last 2 years
    for row in data_rows:
        if not row or not row[0]:
            continue
        # EIA dates look like "11/04/2024" or "2024-11-04" depending on
        # the export. Try a couple of formats.
        date_str = row[0].strip()
        try:
            dt = datetime.strptime(date_str, "%m/%d/%Y")
        except ValueError:
            try:
                dt = datetime.strptime(date_str, "%Y-%m-%d")
            except ValueError:
                continue
        if dt.replace(tzinfo=timezone.utc) < cutoff:
            continue
        for col_idx, region in col_to_region.items():
            if col_idx >= len(row):
                continue
            cell = row[col_idx].strip()
            if not cell or cell == "-":
                continue
            try:
                price = Decimal(cell).quantize(Decimal("0.0001"))
            except Exception:  # noqa: BLE001
                continue
            out.append((region, dt.strftime("%Y-%m-%d"), price))
    return out


async def _fetch_and_store(pool: asyncpg.Pool) -> int:
    async with httpx.AsyncClient(timeout=30.0) as client:
        resp = await client.get(EIA_CSV_URL)
        resp.raise_for_status()
        rows = _parse_csv(resp.text)
    if not rows:
        log.warning("eia: parsed zero rows from CSV; skipping")
        return 0
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.executemany(
                """
                INSERT INTO fuel_market_weekly
                    (region, week_of, grade, price_usd_per_gal)
                VALUES ($1, $2::date, 'regular', $3)
                ON CONFLICT (region, week_of, grade) DO UPDATE
                    SET price_usd_per_gal = EXCLUDED.price_usd_per_gal,
                        fetched_at = now()
                """,
                rows,
            )
    return len(rows)


async def run(pool: asyncpg.Pool) -> None:
    """Worker entry point. Runs forever; cancelled at app shutdown."""
    log.info("eia worker started (cycle every %s s)", CYCLE_INTERVAL_SECONDS)
    while True:
        try:
            n = await _fetch_and_store(pool)
            if n:
                log.info("eia: stored %s region-week-grade rows", n)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.error("eia cycle failed: %s", exc)
        await asyncio.sleep(CYCLE_INTERVAL_SECONDS)
