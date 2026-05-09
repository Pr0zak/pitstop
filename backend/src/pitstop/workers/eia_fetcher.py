"""EIA weekly retail-gasoline price worker.

Pulls the U.S. Energy Information Administration's free weekly
retail-gasoline workbook (XLS) and stores per-region per-grade
snapshots in fuel_market_weekly. Runs once per day; EIA publishes
the report on Monday so a daily check is enough to catch the new
week without polling tightly.

Source — public, no API key, no rate limits:
    https://www.eia.gov/dnav/pet/xls/PET_PRI_GND_DCUS_NUS_W.xls

The workbook has multiple sheets ("Data 1" through "Data 11" or so)
covering the U.S. average + each PADD region. Sheet 1 carries the
U.S. All Grades + Regular + Midgrade + Premium series; we ingest
that sheet for the U.S. region and the city-level + PADD sheets
for the regional splits.

Worker errors (HTTP fail, parse fail, network down) are caught at
the outer loop and logged; we sleep and retry on the next cycle so
a bad day doesn't kill the worker.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import asyncpg
import httpx
import xlrd

log = logging.getLogger(__name__)

# Once per day — EIA publishes Monday but the file timestamp updates
# once. Daily cadence catches the update without burning bandwidth.
CYCLE_INTERVAL_SECONDS = 24 * 3_600

EIA_XLS_URL = "https://www.eia.gov/dnav/pet/xls/PET_PRI_GND_DCUS_NUS_W.xls"


def _parse_workbook(blob: bytes) -> list[tuple[str, "datetime.date", Decimal]]:
    """Return list of (region_code, week_iso, price) tuples for the
    U.S. Regular All Formulations weekly series.

    The workbook's "Data 1" sheet is structured as:
        Row 1: title
        Row 2: header — Date, then a column per series (varies)
        Row 3+: data — date in col A, price values in subsequent cols

    We grab the first numeric series that mentions "Regular" or "All
    Grades" in the header and treat the U.S. column. Regional splits
    live in Data 2 / 3 / etc; future iteration can ingest those.
    """
    out: list[tuple[str, "datetime.date", Decimal]] = []
    cutoff = datetime.now(timezone.utc) - timedelta(weeks=104)
    try:
        book = xlrd.open_workbook(file_contents=blob)
    except Exception as exc:  # noqa: BLE001
        log.warning("eia: workbook load failed: %s", exc)
        return out

    # Sheet "Data 1" carries U.S. All Grades / Regular / Midgrade /
    # Premium weekly series. Find the row whose header mentions
    # "Regular" (case-insensitive) and capture that column.
    sheet_names = book.sheet_names()
    if "Data 1" not in sheet_names:
        log.warning("eia: workbook missing 'Data 1' sheet — got %s", sheet_names)
        return out
    sheet = book.sheet_by_name("Data 1")

    header_row_idx = -1
    regular_col: int | None = None
    for r in range(min(7, sheet.nrows)):
        row_values = sheet.row_values(r)
        for i, c in enumerate(row_values):
            if c is None:
                continue
            label = str(c).strip().lower()
            if "regular" in label and "u.s." in label:
                header_row_idx = r
                regular_col = i
                break
        if header_row_idx != -1:
            break
    if regular_col is None:
        # Fallback: take the first column whose header just contains
        # "regular" (some workbook variants drop the U.S. prefix).
        for r in range(min(7, sheet.nrows)):
            row_values = sheet.row_values(r)
            for i, c in enumerate(row_values):
                if c is None:
                    continue
                if "regular" in str(c).strip().lower():
                    header_row_idx = r
                    regular_col = i
                    break
            if regular_col is not None:
                break
    if regular_col is None:
        log.warning("eia: 'Regular' column not found")
        return out

    for r in range(header_row_idx + 1, sheet.nrows):
        row_values = sheet.row_values(r)
        if not row_values or row_values[0] in (None, ""):
            continue
        # Date cells in xlrd come back as floats (Excel serial date) or
        # strings depending on the original cell format. xlrd_datetime
        # parses serial dates back into a Python datetime.
        date_raw = row_values[0]
        try:
            if isinstance(date_raw, (int, float)):
                tup = xlrd.xldate_as_tuple(date_raw, book.datemode)
                dt = datetime(*tup)
            else:
                dt = datetime.strptime(str(date_raw).strip(), "%Y-%m-%d")
        except Exception:  # noqa: BLE001
            continue
        if dt.replace(tzinfo=timezone.utc) < cutoff:
            continue
        if regular_col >= len(row_values):
            continue
        cell = row_values[regular_col]
        if cell is None or cell == "":
            continue
        try:
            price = Decimal(str(cell)).quantize(Decimal("0.0001"))
        except Exception:  # noqa: BLE001
            continue
        out.append(("us", dt.date(), price))

    return out


async def _fetch_and_store(pool: asyncpg.Pool) -> int:
    async with httpx.AsyncClient(timeout=30.0, follow_redirects=True) as client:
        resp = await client.get(EIA_XLS_URL)
        resp.raise_for_status()
        blob = resp.content
    rows = _parse_workbook(blob)
    if not rows:
        log.warning("eia: parsed zero rows from workbook; skipping")
        return 0
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.executemany(
                """
                INSERT INTO fuel_market_weekly
                    (region, week_of, grade, price_usd_per_gal)
                VALUES ($1, $2, 'regular', $3)
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
                log.info("eia: stored %s region-week rows", n)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.error("eia cycle failed: %s", exc)
        await asyncio.sleep(CYCLE_INTERVAL_SECONDS)
