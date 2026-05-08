"""Tests for the Fuelio CSV importer.

Two tiers:
- Pure parser unit tests — no DB.
- Integration tests against a temp Postgres (env-gated like the trip
  detector). Covers idempotency: re-importing produces all-skipped counts.
"""

from __future__ import annotations

import io
import os
import zipfile
from pathlib import Path

import asyncpg
import pytest

from pitstop.services.fuelio_import import (
    LogUnits,
    detect_log_units,
    parse_csv_sections,
    run_import,
    slugify,
)

FIXTURE_PATH = (
    Path(__file__).resolve().parents[2]
    / "deploy"
    / "tests"
    / "fixtures"
    / "fixture-fuelio.csv"
)


def _has_pg_env() -> bool:
    return bool(os.environ.get("POSTGRES_HOST")) and bool(
        os.environ.get("POSTGRES_PORT")
    )


def _dsn() -> str:
    return (
        f"postgresql://{os.environ['POSTGRES_USER']}:"
        f"{os.environ['POSTGRES_PASSWORD']}@"
        f"{os.environ['POSTGRES_HOST']}:{os.environ['POSTGRES_PORT']}/"
        f"{os.environ['POSTGRES_DB']}"
    )


# ---------------------------------------------------------------------------
# Pure parser tests
# ---------------------------------------------------------------------------


class TestSlugify:
    def test_lowercases_and_replaces_separators(self) -> None:
        assert slugify("Honda Pilot 2019") == "honda-pilot-2019"

    def test_strips_punctuation(self) -> None:
        assert slugify("FixtureCar (test)") == "fixturecar-test"

    def test_returns_default_for_empty(self) -> None:
        assert slugify("***") == "vehicle"


class TestSectionParser:
    def test_parses_fixture_sections(self) -> None:
        text = FIXTURE_PATH.read_text()
        sections = parse_csv_sections(text)
        assert set(sections.keys()) == {
            "Vehicle",
            "Log",
            "CostCategories",
            "Costs",
            "Pictures",
            "Category",
        }
        assert len(sections["Vehicle"].rows) == 1
        assert len(sections["Log"].rows) == 3
        assert len(sections["CostCategories"].rows) == 9
        assert len(sections["Costs"].rows) == 1
        assert len(sections["Pictures"].rows) == 0
        assert len(sections["Category"].rows) == 2

    def test_vehicle_columns_correct(self) -> None:
        text = FIXTURE_PATH.read_text()
        sections = parse_csv_sections(text)
        veh = sections["Vehicle"].rows[0]
        assert veh["Name"] == "FixtureCar"
        assert veh["guid"] == "fixture-vehicle-0001"
        assert veh["DistUnit"] == "1"

    def test_log_units_detected(self) -> None:
        text = FIXTURE_PATH.read_text()
        sections = parse_csv_sections(text)
        units = detect_log_units(sections["Log"].header)
        assert units == LogUnits(dist_unit=1, fuel_unit=1)


# ---------------------------------------------------------------------------
# DB-backed integration
# ---------------------------------------------------------------------------


@pytest.mark.skipif(not _has_pg_env(), reason="no test Postgres configured")
@pytest.mark.asyncio
async def test_import_fixture_end_to_end() -> None:
    csv_bytes = FIXTURE_PATH.read_bytes()
    files = [("fixture-fuelio.csv", csv_bytes)]
    conn = await asyncpg.connect(dsn=_dsn())
    try:
        # Reset just our fixture's data — leave seeded categories alone.
        await _purge_fixture(conn)

        result = await run_import(conn, files, dry_run=False)
        d = result.to_dict()
        assert d["dry_run"] is False
        assert len(d["files"]) == 1
        assert d["files"][0]["vehicle"] == "FixtureCar"
        assert d["preview"]["vehicles"]["added"] == 1
        assert d["preview"]["vehicles"]["updated"] == 0
        assert d["preview"]["vehicles"]["skipped"] == 0
        assert d["preview"]["fillups"]["added"] == 3
        assert d["preview"]["fillups"]["skipped"] == 0
        assert d["preview"]["expenses"]["added"] == 1
        # 9 default categories all linked.
        assert d["preview"]["categories_linked"] == 9
        # 2 categories (Private/Work) — pre-seeded by name; should be linked
        # via fuelio_guid (added=0, updated=2 since seeded but no guid yet).
        assert d["preview"]["trip_categories"]["added"] == 0
        assert d["preview"]["trip_categories"]["updated"] == 2

        # DB sanity check.
        vehicle_id = await conn.fetchval(
            "SELECT id FROM vehicles WHERE fuelio_guid = $1",
            "fixture-vehicle-0001",
        )
        assert vehicle_id is not None
        n_fillups = await conn.fetchval(
            "SELECT count(*) FROM fillups WHERE vehicle_id = $1", vehicle_id
        )
        assert n_fillups == 3

        # Re-import: idempotent → all-skipped counts.
        result2 = await run_import(conn, files, dry_run=False)
        d2 = result2.to_dict()
        assert d2["preview"]["vehicles"]["added"] == 0
        assert d2["preview"]["vehicles"]["skipped"] == 1
        assert d2["preview"]["fillups"]["added"] == 0
        assert d2["preview"]["fillups"]["skipped"] == 3
        assert d2["preview"]["expenses"]["added"] == 0
        assert d2["preview"]["expenses"]["skipped"] == 1
    finally:
        await _purge_fixture(conn)
        await conn.close()


@pytest.mark.skipif(not _has_pg_env(), reason="no test Postgres configured")
@pytest.mark.asyncio
async def test_dry_run_rolls_back() -> None:
    csv_bytes = FIXTURE_PATH.read_bytes()
    files = [("fixture-fuelio.csv", csv_bytes)]
    conn = await asyncpg.connect(dsn=_dsn())
    try:
        await _purge_fixture(conn)
        before = await conn.fetchval("SELECT count(*) FROM vehicles")
        result = await run_import(conn, files, dry_run=True)
        d = result.to_dict()
        assert d["dry_run"] is True
        # Counts in result are non-zero (we previewed) but DB is unchanged.
        assert d["preview"]["vehicles"]["added"] == 1
        after = await conn.fetchval("SELECT count(*) FROM vehicles")
        assert after == before
    finally:
        await _purge_fixture(conn)
        await conn.close()


@pytest.mark.skipif(not _has_pg_env(), reason="no test Postgres configured")
@pytest.mark.asyncio
async def test_zip_upload_unwraps() -> None:
    """Importer should handle a .zip wrapping the CSV."""
    csv_bytes = FIXTURE_PATH.read_bytes()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("fixture-fuelio.csv", csv_bytes)
    zip_bytes = buf.getvalue()
    files = [("fixture-fuelio.csv.zip", zip_bytes)]
    conn = await asyncpg.connect(dsn=_dsn())
    try:
        await _purge_fixture(conn)
        result = await run_import(conn, files, dry_run=False)
        d = result.to_dict()
        assert d["preview"]["vehicles"]["added"] == 1
        assert d["preview"]["fillups"]["added"] == 3
    finally:
        await _purge_fixture(conn)
        await conn.close()


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


async def _purge_fixture(conn: asyncpg.Connection) -> None:
    """Remove any prior fixture rows so the test is repeatable."""
    vid = await conn.fetchval(
        "SELECT id FROM vehicles WHERE fuelio_guid = $1",
        "fixture-vehicle-0001",
    )
    if vid is not None:
        # Cascades will remove fillups/expenses/dtc_events/pid_readings/trips.
        await conn.execute("DELETE FROM vehicles WHERE id = $1", vid)
    # Unlink the seeded expense + trip category guids if a previous run
    # attached them.
    await conn.execute(
        "UPDATE expense_categories SET fuelio_guid = NULL, lastupdated_ms = NULL "
        "WHERE fuelio_guid LIKE 'fixture-cat-%'"
    )
    await conn.execute(
        "UPDATE trip_categories SET fuelio_guid = NULL, lastupdated_ms = NULL "
        "WHERE fuelio_guid LIKE 'fixture-tripcat-%'"
    )
    await conn.execute(
        "DELETE FROM pictures WHERE fuelio_guid LIKE 'fixture-%'"
    )
