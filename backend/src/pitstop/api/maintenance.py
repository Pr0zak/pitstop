"""Service / maintenance reminders.

Reminders surface from ``expenses`` rows where ``remind_odo > 0`` OR
``remind_date IS NOT NULL``. The current odo for a vehicle is taken from
its most recent fillup (we don't trust live OBD odo here — fillups are
authoritative for odometer).

A reminder is "overdue" when current_odo > remind_odo or today > remind_date.
"Upcoming" is within 500 mi or 30 days.
"""

from __future__ import annotations

import logging
from datetime import date, datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

import asyncpg
from dateutil.relativedelta import relativedelta
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi import Path as FastAPIPath

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import RemindersOut

log = logging.getLogger(__name__)

router = APIRouter(prefix="/maintenance", tags=["maintenance"])


UPCOMING_MILES_THRESHOLD = 500.0
UPCOMING_DAYS_THRESHOLD = 30


async def _current_odo(
    conn: asyncpg.Connection, vehicle_id: UUID
) -> float | None:
    return await conn.fetchval(
        "SELECT odo FROM fillups WHERE vehicle_id = $1 "
        "ORDER BY fillup_date DESC LIMIT 1",
        vehicle_id,
    )


@router.get(
    "/reminders",
    response_model=RemindersOut,
    dependencies=[Depends(require_query_token)],
)
async def reminders(
    vehicle_id: UUID | None = Query(default=None),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    where = ["(remind_odo IS NOT NULL AND remind_odo > 0) OR remind_date IS NOT NULL"]
    args: list[Any] = []
    if vehicle_id is not None:
        args.append(vehicle_id)
        where.insert(0, f"vehicle_id = ${len(args)}")
    sql = (
        "SELECT e.id, e.vehicle_id, e.title, e.notes, e.odo, "
        "       e.remind_odo, e.remind_date, "
        "       c.name AS category "
        "FROM expenses e LEFT JOIN expense_categories c "
        "  ON c.id = e.cost_type_id "
        "WHERE " + " AND ".join(f"({w})" for w in where) + " "
        "  AND e.is_template = false"
    )
    overdue: list[dict[str, Any]] = []
    upcoming: list[dict[str, Any]] = []
    today = datetime.now(tz=None).date()
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, *args)
        # Cache current_odo per vehicle.
        odo_cache: dict[UUID, float | None] = {}
        for r in rows:
            vid = r["vehicle_id"]
            if vid not in odo_cache:
                odo_cache[vid] = await _current_odo(conn, vid)
            current_odo = odo_cache[vid]
            miles_over: float | None = None
            miles_until: float | None = None
            days_over: int | None = None
            days_until: int | None = None

            remind_odo = r["remind_odo"]
            if remind_odo is not None and current_odo is not None:
                diff = float(current_odo) - float(remind_odo)
                if diff > 0:
                    miles_over = round(diff, 1)
                else:
                    miles_until = round(-diff, 1)

            remind_date: date | None = r["remind_date"]
            if remind_date is not None:
                diff_days = (today - remind_date).days
                if diff_days > 0:
                    days_over = diff_days
                else:
                    days_until = -diff_days

            common = {
                "expense_id": r["id"],
                "title": r["title"],
                "category": r["category"],
                "current_odo": float(current_odo) if current_odo is not None else None,
                "remind_odo": float(remind_odo) if remind_odo is not None else None,
                "remind_date": remind_date,
                "notes": r["notes"],
            }
            is_overdue = (miles_over is not None) or (days_over is not None)
            is_upcoming = (
                (miles_until is not None and miles_until <= UPCOMING_MILES_THRESHOLD)
                or (days_until is not None and days_until <= UPCOMING_DAYS_THRESHOLD)
            )

            if is_overdue:
                overdue.append(
                    {
                        **common,
                        "miles_over": miles_over,
                        "days_over": days_over,
                    }
                )
            elif is_upcoming:
                upcoming.append(
                    {
                        **common,
                        "miles_until": miles_until,
                        "days_until": days_until,
                    }
                )
    return {"overdue": overdue, "upcoming": upcoming}


@router.post(
    "/reminders/{expense_id}/done",
    dependencies=[Depends(require_ingest_token)],
)
async def mark_reminder_done(
    expense_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Close out a reminder.

    Strategy:
        1. Fetch the source reminder row.
        2. Insert a new ``expenses`` row of the same category at the
           current odo / today's date with cost=0 (the user can edit cost).
        3. If the original has ``repeat_odo`` and/or ``repeat_months``,
           advance the reminder fields by that amount on the original.
           Otherwise clear ``remind_odo`` and ``remind_date`` to "close"
           the reminder.
    """
    today = datetime.now(tz=None).date()
    async with pool.acquire() as conn, conn.transaction():
        row = await conn.fetchrow(
            "SELECT id, vehicle_id, title, notes, cost_type_id, "
            "       remind_odo, remind_date, repeat_odo, repeat_months, "
            "       is_template "
            "FROM expenses WHERE id = $1",
            expense_id,
        )
        if row is None:
            raise HTTPException(status_code=404, detail="reminder not found")
        current_odo = await _current_odo(conn, row["vehicle_id"])
        new_id = await conn.fetchval(
            """
            INSERT INTO expenses (
                vehicle_id, expense_date, odo, cost_type_id,
                title, notes, cost, is_income, is_template
            ) VALUES (
                $1, $2, $3, $4,
                $5, $6, $7, false, false
            )
            RETURNING id
            """,
            row["vehicle_id"],
            today,
            current_odo,
            row["cost_type_id"],
            row["title"],
            row["notes"],
            Decimal("0"),
        )

        repeat_odo = row["repeat_odo"]
        repeat_months = row["repeat_months"]
        has_repeat = (repeat_odo is not None and repeat_odo > 0) or (
            repeat_months is not None and repeat_months > 0
        )
        if has_repeat:
            new_remind_odo: float | None = None
            new_remind_date: date | None = None
            if repeat_odo is not None and repeat_odo > 0 and current_odo is not None:
                new_remind_odo = float(current_odo) + float(repeat_odo)
            elif row["remind_odo"] is not None and repeat_odo is not None:
                new_remind_odo = float(row["remind_odo"]) + float(repeat_odo)
            if repeat_months is not None and repeat_months > 0:
                base_date = row["remind_date"] or today
                new_remind_date = base_date + relativedelta(
                    months=int(round(float(repeat_months)))
                )
            await conn.execute(
                "UPDATE expenses SET remind_odo = $2, remind_date = $3 "
                "WHERE id = $1",
                expense_id,
                new_remind_odo,
                new_remind_date,
            )
        else:
            await conn.execute(
                "UPDATE expenses SET remind_odo = NULL, remind_date = NULL "
                "WHERE id = $1",
                expense_id,
            )
    return {"completed_expense_id": str(new_id), "reminder_id": str(expense_id)}
