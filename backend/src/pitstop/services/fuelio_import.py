"""Fuelio CSV importer.

The Fuelio (Sygic) CSV format is multi-section: ``## Vehicle``, ``## Log``,
``## CostCategories``, ``## Costs``, ``## Pictures``, ``## Category``. Each
section starts with a header row and zero or more data rows. All fields are
double-quoted.

Idempotency: each row carries a ``guid`` and ``lastupdated`` (epoch ms). We
upsert keyed on ``fuelio_guid`` with last-write-wins by lastupdated_ms.

This module is purely about parsing + DB writes. The HTTP shell lives in
``api/imports.py``.
"""

from __future__ import annotations

import csv
import io
import logging
import re
import zipfile
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any
from uuid import UUID

import asyncpg

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Section parser
# ---------------------------------------------------------------------------


_SECTION_RE = re.compile(r"^##\s*(.+)$")


@dataclass
class Section:
    name: str
    header: list[str]
    rows: list[dict[str, str]]


def parse_csv_sections(text: str) -> dict[str, Section]:
    """Split a Fuelio export into named sections.

    Section markers like ``"## Vehicle"`` are quoted in real exports; the
    csv module strips quotes and we then strip whitespace + ``##``.
    """
    sections: dict[str, Section] = {}
    current: Section | None = None

    reader = csv.reader(io.StringIO(text), quotechar='"')
    pending_header_for: Section | None = None
    for row in reader:
        if not row:
            # Blank line — sections often separated by blank lines.
            continue
        # Section heading row: a single cell starting with ##.
        first = row[0].strip()
        m = _SECTION_RE.match(first)
        if m and len(row) == 1:
            name = m.group(1).strip()
            current = Section(name=name, header=[], rows=[])
            sections[name] = current
            pending_header_for = current
            continue
        if current is None:
            # Stray data before any section header — skip.
            continue
        if pending_header_for is not None:
            current.header = [c.strip() for c in row]
            pending_header_for = None
            continue
        # Data row.
        if len(row) < len(current.header):
            row = row + [""] * (len(current.header) - len(row))
        record = dict(zip(current.header, row[: len(current.header)], strict=False))
        current.rows.append(record)
    return sections


# ---------------------------------------------------------------------------
# Field coercion helpers
# ---------------------------------------------------------------------------


def _val(d: dict[str, str], *keys: str) -> str | None:
    """Return first non-empty value for any of the keys (case-sensitive first)."""
    for k in keys:
        v = d.get(k)
        if v is not None and v != "":
            return v
    return None


def _maybe_float(s: str | None) -> float | None:
    if s is None or s == "":
        return None
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def _none_if_null_island(value: float | None, other: float | None) -> float | None:
    """Return None when (value, other) is at "Null Island" (lat=0, lon=0).
    Fuelio exports the sentinel for fillups with no recorded GPS; we'd
    otherwise plot it at the equator/prime-meridian intersection off
    the African coast on the Stations map.
    """
    if value is None or other is None:
        return value
    if abs(value) < 0.01 and abs(other) < 0.01:
        return None
    return value


def _maybe_int(s: str | None) -> int | None:
    if s is None or s == "":
        return None
    try:
        return int(s)
    except (TypeError, ValueError):
        try:
            return int(float(s))
        except (TypeError, ValueError):
            return None


def _maybe_decimal(s: str | None) -> Decimal | None:
    if s is None or s == "":
        return None
    try:
        return Decimal(s)
    except (TypeError, InvalidOperation):
        return None


def _bool01(s: str | None) -> bool:
    return s == "1"


_DATE_FORMATS = (
    "%Y-%m-%d %H:%M",
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%d",
)


def _parse_datetime(s: str | None) -> datetime | None:
    if s is None or s == "":
        return None
    for fmt in _DATE_FORMATS:
        try:
            dt = datetime.strptime(s, fmt)
            return dt.replace(tzinfo=UTC)
        except ValueError:
            continue
    return None


def _parse_date(s: str | None) -> date | None:
    dt = _parse_datetime(s)
    if dt is None:
        return None
    return dt.date()


# ---------------------------------------------------------------------------
# Slugify
# ---------------------------------------------------------------------------


_SLUG_RE = re.compile(r"[^a-z0-9]+")


def slugify(name: str) -> str:
    s = _SLUG_RE.sub("-", name.lower()).strip("-")
    return s or "vehicle"


async def resolve_unique_slug(conn: asyncpg.Connection, base: str, fuelio_guid: str | None) -> str:
    """Pick a unique slug, allowing the same vehicle to keep its existing slug.

    If a vehicle already exists with this fuelio_guid, return its current slug
    unchanged. Otherwise, append `-2`, `-3`, ... until unused.
    """
    if fuelio_guid:
        existing = await conn.fetchval(
            "SELECT slug FROM vehicles WHERE fuelio_guid = $1", fuelio_guid
        )
        if existing:
            return existing
    candidate = base
    n = 1
    while True:
        clash = await conn.fetchval(
            "SELECT 1 FROM vehicles WHERE slug = $1", candidate
        )
        if clash is None:
            return candidate
        n += 1
        candidate = f"{base}-{n}"


# ---------------------------------------------------------------------------
# Per-section rowcounts for the response
# ---------------------------------------------------------------------------


@dataclass
class CountSet:
    added: int = 0
    updated: int = 0
    skipped: int = 0


@dataclass
class FilePreview:
    name: str
    vehicle: str | None = None


@dataclass
class ImportResult:
    dry_run: bool
    files: list[FilePreview] = field(default_factory=list)
    vehicles: CountSet = field(default_factory=CountSet)
    fillups: CountSet = field(default_factory=CountSet)
    expenses: CountSet = field(default_factory=CountSet)
    categories_linked: int = 0
    pictures: CountSet = field(default_factory=CountSet)
    trip_categories: CountSet = field(default_factory=CountSet)
    errors: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": self.dry_run,
            "files": [{"name": f.name, "vehicle": f.vehicle} for f in self.files],
            "preview": {
                "vehicles": self.vehicles.__dict__,
                "fillups": self.fillups.__dict__,
                "expenses": self.expenses.__dict__,
                "categories_linked": self.categories_linked,
                "pictures": self.pictures.__dict__,
                "trip_categories": self.trip_categories.__dict__,
            },
            "errors": self.errors,
        }


# ---------------------------------------------------------------------------
# Unit detection from headers
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class LogUnits:
    dist_unit: int  # 1 = miles, 0 = km (matches Fuelio Vehicle.DistUnit code)
    fuel_unit: int  # 1 = US gal, 0 = litres


def detect_log_units(header: list[str]) -> LogUnits:
    """Look at ``## Log``'s header to figure out units.

    Fuelio writes ``Odo (mi)`` vs ``Odo (km)`` and ``Fuel (us gallons)`` vs
    ``Fuel (litres)``. The vehicle row's DistUnit/FuelUnit can disagree on
    older exports — header is authoritative.
    """
    odo_h = next((h for h in header if h.lower().startswith("odo")), "")
    fuel_h = next((h for h in header if h.lower().startswith("fuel ")), "")
    dist_unit = 1 if "mi" in odo_h.lower() else 0
    fuel_unit = 1 if ("gallon" in fuel_h.lower() or "us" in fuel_h.lower()) else 0
    return LogUnits(dist_unit=dist_unit, fuel_unit=fuel_unit)


# ---------------------------------------------------------------------------
# The importer itself
# ---------------------------------------------------------------------------


class FuelioImporter:
    """Stateful importer for one or more uploaded files (one transaction)."""

    def __init__(self, conn: asyncpg.Connection, *, force: bool = False) -> None:
        self.conn = conn
        # When force=True, bypass the lastupdated_ms gate so existing rows
        # get overwritten with whatever the CSV says — used to backfill
        # columns that older importer code missed (GPS, StationID, Notes).
        self.force = force

    async def import_files(
        self,
        files: list[tuple[str, bytes]],
        *,
        dry_run: bool,
        attach_vehicle_slug: str | None = None,
    ) -> ImportResult:
        result = ImportResult(dry_run=dry_run)

        # Resolve the attach target up front so per-file Vehicle sections
        # become optional. The caller (UI) supplied this, so we trust it
        # over whatever the file says about itself.
        attach_vehicle_id: UUID | None = None
        if attach_vehicle_slug:
            row = await self.conn.fetchrow(
                "SELECT id FROM vehicles WHERE slug = $1",
                attach_vehicle_slug,
            )
            if row is None:
                result.errors.append(
                    f"attach_vehicle_slug '{attach_vehicle_slug}' not found"
                )
                return result
            attach_vehicle_id = row["id"]

        async with self.conn.transaction():
            for name, blob in files:
                try:
                    csv_text = self._unwrap(name, blob)
                except (zipfile.BadZipFile, ValueError, UnicodeDecodeError) as exc:
                    msg = f"{name}: failed to read: {exc}"
                    log.warning(msg)
                    result.errors.append(msg)
                    result.files.append(FilePreview(name=name, vehicle=None))
                    continue
                sections = parse_csv_sections(csv_text)
                vehicle_section = sections.get("Vehicle")

                if attach_vehicle_id is not None:
                    # Per-vehicle sync export: ignore any Vehicle section in the
                    # file (Fuelio still ships one for full exports — we'd
                    # otherwise overwrite the user's edits). Use the attach
                    # target for fillups/costs.
                    vehicle_id = attach_vehicle_id
                    result.files.append(
                        FilePreview(name=name, vehicle=attach_vehicle_slug)
                    )
                else:
                    if vehicle_section is None or not vehicle_section.rows:
                        msg = (
                            f"{name}: no Vehicle section. Re-upload with "
                            f"attach_vehicle_slug=… to import into an "
                            f"existing vehicle."
                        )
                        result.errors.append(msg)
                        result.files.append(FilePreview(name=name, vehicle=None))
                        continue
                    vehicle_row = vehicle_section.rows[0]
                    vehicle_name = vehicle_row.get("Name") or "(unnamed)"
                    result.files.append(
                        FilePreview(name=name, vehicle=vehicle_name)
                    )

                    vehicle_id = await self._upsert_vehicle(vehicle_row, result)
                    if vehicle_id is None:
                        continue

                # CostCategories first — needed to map expense cost types.
                cost_type_map: dict[str, int] = {}
                cc_section = sections.get("CostCategories")
                if cc_section is not None:
                    cost_type_map = await self._import_cost_categories(
                        cc_section, result
                    )

                log_section = sections.get("Log")
                if log_section is not None:
                    await self._import_fillups(
                        log_section, vehicle_id, result
                    )

                costs_section = sections.get("Costs")
                if costs_section is not None:
                    await self._import_costs(
                        costs_section, vehicle_id, cost_type_map, result
                    )

                pictures_section = sections.get("Pictures")
                if pictures_section is not None:
                    await self._import_pictures(
                        pictures_section, vehicle_id, result
                    )

                category_section = sections.get("Category")
                if category_section is not None:
                    await self._import_trip_categories(
                        category_section, result
                    )

            if dry_run:
                # Force rollback by raising — caller catches and unwraps.
                raise _DryRunRollbackError(result)
        return result

    # ----- raw blob → CSV text -----------------------------------------

    def _unwrap(self, name: str, blob: bytes) -> str:
        lower = name.lower()
        if lower.endswith(".zip"):
            with zipfile.ZipFile(io.BytesIO(blob)) as zf:
                csv_names = [n for n in zf.namelist() if n.lower().endswith(".csv")]
                if not csv_names:
                    raise ValueError("zip contains no .csv file")
                with zf.open(csv_names[0]) as f:
                    return f.read().decode("utf-8-sig")
        return blob.decode("utf-8-sig")

    # ----- Vehicle -----------------------------------------------------

    async def _upsert_vehicle(
        self, row: dict[str, str], result: ImportResult
    ) -> UUID | None:
        guid = (row.get("guid") or "").strip() or None
        if guid is None:
            result.errors.append("vehicle row missing guid")
            return None
        name = row.get("Name") or "Vehicle"
        base_slug = slugify(name)
        slug = await resolve_unique_slug(self.conn, base_slug, guid)
        lastupdated = _maybe_int(row.get("lastupdated")) or 0
        existing = await self.conn.fetchrow(
            "SELECT id, lastupdated_ms FROM vehicles WHERE fuelio_guid = $1",
            guid,
        )
        params = {
            "slug": slug,
            "name": name,
            "description": row.get("Description") or None,
            "make": row.get("Make") or None,
            "model": row.get("Model") or None,
            "year": _maybe_int(row.get("Year")),
            "vin": row.get("VIN") or None,
            "plate": row.get("Plate") or None,
            "fuelio_guid": guid,
            "dist_unit": _maybe_int(row.get("DistUnit")) or 1,
            "fuel_unit": _maybe_int(row.get("FuelUnit")) or 1,
            "consumption_unit": _maybe_int(row.get("ConsumptionUnit")) or 1,
            "tank_count": _maybe_int(row.get("TankCount")) or 1,
            "tank1_type": _maybe_int(row.get("Tank1Type")),
            "tank2_type": _maybe_int(row.get("Tank2Type")),
            "tank1_capacity": _maybe_float(row.get("Tank1Capacity")),
            "tank2_capacity": _maybe_float(row.get("Tank2Capacity")),
            "active": _maybe_int(row.get("Active")) != 0,
            "lastupdated_ms": lastupdated,
        }

        if existing is None:
            vid = await self.conn.fetchval(
                """
                INSERT INTO vehicles (
                    slug, name, description, make, model, year,
                    vin, plate, fuelio_guid,
                    dist_unit, fuel_unit, consumption_unit,
                    tank_count, tank1_type, tank2_type,
                    tank1_capacity, tank2_capacity,
                    active, lastupdated_ms
                ) VALUES (
                    $1, $2, $3, $4, $5, $6,
                    $7, $8, $9,
                    $10, $11, $12,
                    $13, $14, $15,
                    $16, $17,
                    $18, $19
                )
                RETURNING id
                """,
                params["slug"], params["name"], params["description"],
                params["make"], params["model"], params["year"],
                params["vin"], params["plate"], params["fuelio_guid"],
                params["dist_unit"], params["fuel_unit"], params["consumption_unit"],
                params["tank_count"], params["tank1_type"], params["tank2_type"],
                params["tank1_capacity"], params["tank2_capacity"],
                params["active"], params["lastupdated_ms"],
            )
            result.vehicles.added += 1
            return vid
        if (existing["lastupdated_ms"] or 0) >= lastupdated:
            result.vehicles.skipped += 1
            return existing["id"]
        await self.conn.execute(
            """
            UPDATE vehicles
               SET slug = $2, name = $3, description = $4, make = $5,
                   model = $6, year = $7, vin = $8, plate = $9,
                   dist_unit = $10, fuel_unit = $11, consumption_unit = $12,
                   tank_count = $13, tank1_type = $14, tank2_type = $15,
                   tank1_capacity = $16, tank2_capacity = $17,
                   active = $18, lastupdated_ms = $19
             WHERE id = $1
            """,
            existing["id"],
            params["slug"], params["name"], params["description"],
            params["make"], params["model"], params["year"],
            params["vin"], params["plate"],
            params["dist_unit"], params["fuel_unit"], params["consumption_unit"],
            params["tank_count"], params["tank1_type"], params["tank2_type"],
            params["tank1_capacity"], params["tank2_capacity"],
            params["active"], params["lastupdated_ms"],
        )
        result.vehicles.updated += 1
        return existing["id"]

    # ----- CostCategories ---------------------------------------------

    async def _import_cost_categories(
        self, section: Section, result: ImportResult
    ) -> dict[str, int]:
        """Map Fuelio's per-export CostTypeID → our stable expense_categories.id.

        We dedupe globally by (name, priority): the 9 defaults are seeded into
        expense_categories at migration time. If the row matches by name we
        link the fuelio_guid; we never create duplicate rows.
        """
        mapping: dict[str, int] = {}
        for row in section.rows:
            name = row.get("Name")
            if not name:
                continue
            fuelio_cost_type_id = row.get("CostTypeID") or ""
            guid = (row.get("guid") or "").strip() or None
            priority = _maybe_int(row.get("priority")) or 0
            color = row.get("color") or None
            lastupdated = _maybe_int(row.get("lastupdated")) or 0

            existing = await self.conn.fetchrow(
                "SELECT id, fuelio_guid, lastupdated_ms FROM expense_categories "
                "WHERE name = $1",
                name,
            )
            if existing is not None:
                # Link this Fuelio cost type id back to our stable id.
                if fuelio_cost_type_id:
                    mapping[fuelio_cost_type_id] = existing["id"]
                # Update guid + lastupdated if new.
                if guid and (
                    existing["fuelio_guid"] is None
                    or (existing["lastupdated_ms"] or 0) < lastupdated
                ):
                    await self.conn.execute(
                        "UPDATE expense_categories "
                        "SET fuelio_guid = $2, lastupdated_ms = $3, "
                        "    color = COALESCE($4, color), priority = $5 "
                        "WHERE id = $1",
                        existing["id"], guid, lastupdated, color, priority,
                    )
                    result.categories_linked += 1
                continue
            # Brand-new category (rare — Fuelio's defaults are universal).
            new_id = await self.conn.fetchval(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM expense_categories"
            )
            await self.conn.execute(
                "INSERT INTO expense_categories "
                "(id, name, priority, color, fuelio_guid, lastupdated_ms) "
                "VALUES ($1, $2, $3, $4, $5, $6)",
                new_id, name, priority, color, guid, lastupdated,
            )
            if fuelio_cost_type_id:
                mapping[fuelio_cost_type_id] = new_id
            result.categories_linked += 1
        return mapping

    # ----- Log → fillups -----------------------------------------------

    async def _import_fillups(
        self,
        section: Section,
        vehicle_id: UUID,
        result: ImportResult,
    ) -> None:
        for row in section.rows:
            guid = (row.get("guid") or "").strip() or None
            if guid is None:
                continue
            lastupdated = _maybe_int(row.get("lastupdated")) or 0
            fillup_date = _parse_datetime(row.get("Data"))
            if fillup_date is None:
                result.errors.append(f"fillup {guid}: bad date {row.get('Data')!r}")
                continue
            # Header tells us the units; we still pull the raw value out via
            # whichever Odo/Fuel column exists.
            odo = _maybe_float(_val(row, "Odo (mi)", "Odo (km)", "Odo"))
            fuel_volume = _maybe_float(
                _val(row, "Fuel (us gallons)", "Fuel (litres)", "Fuel")
            )
            if odo is None or fuel_volume is None:
                result.errors.append(
                    f"fillup {guid}: missing odo or fuel "
                    f"(odo={row.get('Odo (mi)') or row.get('Odo (km)')}, "
                    f"fuel={row.get('Fuel (us gallons)') or row.get('Fuel (litres)')})"
                )
                continue
            # Fuelio Log column headers come with " (optional)" suffix in
            # current exports (e.g. "latitude (optional)"). Older builds
            # dropped the suffix. Use _val() to pick whichever exists.
            station_id = _maybe_int(
                _val(row, "StationID (optional)", "StationID")
            )
            if station_id == 0:
                station_id = None
            params = {
                "vehicle_id": vehicle_id,
                "fuelio_guid": guid,
                "fillup_date": fillup_date,
                "odo": odo,
                "fuel_volume": fuel_volume,
                "is_full": _bool01(row.get("Full")),
                "is_missed": _bool01(row.get("Missed")),
                "price_total": _maybe_decimal(
                    _val(row, "Price (optional)", "Price")
                ),
                "price_per_unit": _maybe_decimal(row.get("VolumePrice")),
                # Fuelio's exports vary on the column name for the
                # user-reported MPG. Real exports (vehicle-N-sync.csv
                # in 2025+) use the literal header "mpg (optional)"
                # with the suffix included; older builds dropped the
                # suffix; locale-adjusted exports use Consumption /
                # Economy / L/100km. We try each variant in order and
                # take the first that yields a value.
                "mpg_reported": _maybe_float(
                    _val(
                        row,
                        "mpg (optional)",
                        "mpg",
                        "MPG",
                        "Consumption (optional)",
                        "Consumption",
                        "Economy",
                        "L/100km (optional)",
                        "L/100km",
                    )
                ),
                # NULL out the "Null Island" sentinel — Fuelio exports
                # (0, 0) when the user didn't record a location, which
                # would otherwise plot off the African coast on the
                # Stations map. Real driving doesn't put a station within
                # 1 km of the equator/prime-meridian intersection.
                "lat": _none_if_null_island(
                    _maybe_float(_val(row, "latitude (optional)", "latitude")),
                    _maybe_float(_val(row, "longitude (optional)", "longitude")),
                ),
                "lon": _none_if_null_island(
                    _maybe_float(_val(row, "longitude (optional)", "longitude")),
                    _maybe_float(_val(row, "latitude (optional)", "latitude")),
                ),
                "city": _val(row, "City (optional)", "City") or None,
                "station_id": station_id,
                "tank_number": _maybe_int(row.get("TankNumber")) or 1,
                "fuel_type": _maybe_int(row.get("FuelType")),
                "weather": row.get("Weather") or None,
                "tank_calc": _maybe_float(row.get("TankCalc")),
                "exclude_distance": _bool01(row.get("ExcludeDistance")),
                "notes": _val(row, "Notes (optional)", "Notes") or None,
                "lastupdated_ms": lastupdated,
            }
            existing = await self.conn.fetchrow(
                "SELECT id, lastupdated_ms FROM fillups WHERE fuelio_guid = $1",
                guid,
            )
            if existing is None:
                await self.conn.execute(
                    """
                    INSERT INTO fillups (
                        vehicle_id, fuelio_guid, fillup_date, odo, fuel_volume,
                        is_full, is_missed, price_total, price_per_unit,
                        mpg_reported, lat, lon, city, station_id, tank_number,
                        fuel_type, weather, tank_calc, exclude_distance,
                        notes, lastupdated_ms
                    ) VALUES (
                        $1, $2, $3, $4, $5,
                        $6, $7, $8, $9,
                        $10, $11, $12, $13, $14, $15,
                        $16, $17, $18, $19,
                        $20, $21
                    )
                    """,
                    *params.values(),
                )
                result.fillups.added += 1
                continue
            if not self.force and (existing["lastupdated_ms"] or 0) >= lastupdated:
                result.fillups.skipped += 1
                continue
            await self.conn.execute(
                """
                UPDATE fillups SET
                    vehicle_id = $2, fillup_date = $3, odo = $4, fuel_volume = $5,
                    is_full = $6, is_missed = $7, price_total = $8,
                    price_per_unit = $9, mpg_reported = $10, lat = $11,
                    lon = $12, city = $13, station_id = $14, tank_number = $15,
                    fuel_type = $16, weather = $17, tank_calc = $18,
                    exclude_distance = $19, notes = $20, lastupdated_ms = $21
                WHERE id = $1
                """,
                existing["id"],
                params["vehicle_id"], params["fillup_date"], params["odo"],
                params["fuel_volume"], params["is_full"], params["is_missed"],
                params["price_total"], params["price_per_unit"],
                params["mpg_reported"], params["lat"], params["lon"],
                params["city"], params["station_id"], params["tank_number"],
                params["fuel_type"], params["weather"], params["tank_calc"],
                params["exclude_distance"], params["notes"],
                params["lastupdated_ms"],
            )
            result.fillups.updated += 1

    # ----- Costs → expenses --------------------------------------------

    async def _import_costs(
        self,
        section: Section,
        vehicle_id: UUID,
        cost_type_map: dict[str, int],
        result: ImportResult,
    ) -> None:
        for row in section.rows:
            guid = (row.get("guid") or "").strip() or None
            if guid is None:
                continue
            lastupdated = _maybe_int(row.get("lastupdated")) or 0
            expense_date = _parse_date(row.get("Date"))
            if expense_date is None:
                result.errors.append(f"expense {guid}: bad date {row.get('Date')!r}")
                continue
            cost = _maybe_decimal(row.get("Cost"))
            if cost is None:
                result.errors.append(f"expense {guid}: missing cost")
                continue
            fuelio_cost_type_id = row.get("CostTypeID") or ""
            cost_type_id = cost_type_map.get(fuelio_cost_type_id)
            params = {
                "vehicle_id": vehicle_id,
                "fuelio_guid": guid,
                "expense_date": expense_date,
                "odo": _maybe_float(row.get("Odo")),
                "cost_type_id": cost_type_id,
                "title": row.get("CostTitle") or None,
                "notes": row.get("Notes") or None,
                "cost": cost,
                "is_income": _bool01(row.get("isIncome")),
                "is_template": _bool01(row.get("isTemplate")),
                "remind_odo": _maybe_float(row.get("RemindOdo")),
                "remind_date": _parse_date(row.get("RemindDate")),
                "repeat_odo": _maybe_float(row.get("RepeatOdo")),
                "repeat_months": _maybe_float(row.get("RepeatMonths")),
                "flag": _maybe_int(row.get("flag")) or 0,
                "lastupdated_ms": lastupdated,
            }
            existing = await self.conn.fetchrow(
                "SELECT id, lastupdated_ms FROM expenses WHERE fuelio_guid = $1",
                guid,
            )
            if existing is None:
                await self.conn.execute(
                    """
                    INSERT INTO expenses (
                        vehicle_id, fuelio_guid, expense_date, odo,
                        cost_type_id, title, notes, cost,
                        is_income, is_template, remind_odo, remind_date,
                        repeat_odo, repeat_months, flag, lastupdated_ms
                    ) VALUES (
                        $1, $2, $3, $4,
                        $5, $6, $7, $8,
                        $9, $10, $11, $12,
                        $13, $14, $15, $16
                    )
                    """,
                    *params.values(),
                )
                result.expenses.added += 1
                continue
            if (existing["lastupdated_ms"] or 0) >= lastupdated:
                result.expenses.skipped += 1
                continue
            await self.conn.execute(
                """
                UPDATE expenses SET
                    vehicle_id = $2, expense_date = $3, odo = $4,
                    cost_type_id = $5, title = $6, notes = $7, cost = $8,
                    is_income = $9, is_template = $10, remind_odo = $11,
                    remind_date = $12, repeat_odo = $13, repeat_months = $14,
                    flag = $15, lastupdated_ms = $16
                WHERE id = $1
                """,
                existing["id"],
                params["vehicle_id"], params["expense_date"], params["odo"],
                params["cost_type_id"], params["title"], params["notes"],
                params["cost"], params["is_income"], params["is_template"],
                params["remind_odo"], params["remind_date"],
                params["repeat_odo"], params["repeat_months"],
                params["flag"], params["lastupdated_ms"],
            )
            result.expenses.updated += 1

    # ----- Pictures ----------------------------------------------------

    async def _import_pictures(
        self,
        section: Section,
        vehicle_id: UUID,
        result: ImportResult,
    ) -> None:
        for row in section.rows:
            guid = (row.get("guid") or "").strip() or None
            filename = row.get("Filename")
            if guid is None or not filename:
                continue
            lastupdated = _maybe_int(row.get("lastupdated")) or 0
            params = (
                vehicle_id,
                guid,
                filename,
                row.get("Note") or None,
                _maybe_int(row.get("Type")),
                row.get("target_id") or None,
                lastupdated,
            )
            existing = await self.conn.fetchrow(
                "SELECT id, lastupdated_ms FROM pictures WHERE fuelio_guid = $1",
                guid,
            )
            if existing is None:
                await self.conn.execute(
                    """
                    INSERT INTO pictures (
                        vehicle_id, fuelio_guid, filename, note,
                        kind, target_id, lastupdated_ms
                    ) VALUES ($1, $2, $3, $4, $5, $6, $7)
                    """,
                    *params,
                )
                result.pictures.added += 1
            else:
                # Last-write-wins.
                if (existing["lastupdated_ms"] or 0) >= lastupdated:
                    result.pictures.skipped += 1
                else:
                    await self.conn.execute(
                        """
                        UPDATE pictures SET
                            vehicle_id = $2, filename = $3, note = $4,
                            kind = $5, target_id = $6, lastupdated_ms = $7
                        WHERE id = $1
                        """,
                        existing["id"], *params[:1], *params[2:],
                    )
                    result.pictures.skipped += 1  # treat as skipped for preview

    # ----- Trip categories --------------------------------------------

    async def _import_trip_categories(
        self,
        section: Section,
        result: ImportResult,
    ) -> None:
        for row in section.rows:
            name = row.get("Name")
            guid = (row.get("guid") or "").strip() or None
            if not name:
                continue
            lastupdated = _maybe_int(row.get("lastupdated")) or 0

            existing = await self.conn.fetchrow(
                "SELECT id, fuelio_guid, lastupdated_ms FROM trip_categories "
                "WHERE name = $1",
                name,
            )
            if existing is not None:
                if guid and (
                    existing["fuelio_guid"] is None
                    or (existing["lastupdated_ms"] or 0) < lastupdated
                ):
                    await self.conn.execute(
                        "UPDATE trip_categories SET fuelio_guid = $2, "
                        "lastupdated_ms = $3 WHERE id = $1",
                        existing["id"], guid, lastupdated,
                    )
                    result.trip_categories.updated += 1
                else:
                    result.trip_categories.skipped += 1
                continue
            new_id = await self.conn.fetchval(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM trip_categories"
            )
            await self.conn.execute(
                "INSERT INTO trip_categories (id, name, fuelio_guid, "
                "lastupdated_ms) VALUES ($1, $2, $3, $4)",
                new_id, name, guid, lastupdated,
            )
            result.trip_categories.added += 1


# ---------------------------------------------------------------------------
# Dry-run handling
# ---------------------------------------------------------------------------


class _DryRunRollbackError(Exception):
    """Raised at end of import to force a transaction rollback."""

    def __init__(self, result: ImportResult) -> None:
        super().__init__("dry-run rollback")
        self.result = result


async def run_import(
    conn: asyncpg.Connection,
    files: list[tuple[str, bytes]],
    *,
    dry_run: bool,
    attach_vehicle_slug: str | None = None,
    force: bool = False,
) -> ImportResult:
    """Top-level wrapper: handles the dry-run rollback dance."""
    importer = FuelioImporter(conn, force=force)
    if not dry_run:
        return await importer.import_files(
            files, dry_run=False, attach_vehicle_slug=attach_vehicle_slug
        )
    try:
        return await importer.import_files(
            files, dry_run=True, attach_vehicle_slug=attach_vehicle_slug
        )
    except _DryRunRollbackError as ex:
        return ex.result
