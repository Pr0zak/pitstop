"""Expenses CRUD + categories listing."""

from __future__ import annotations

import logging
from datetime import date
from decimal import Decimal
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi import Path as FastAPIPath

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import ExpenseCategoryOut, ExpenseCreate, ExpenseOut, ExpenseUpdate

log = logging.getLogger(__name__)

router = APIRouter(tags=["expenses"])


_EXPENSE_COLS = (
    "id, vehicle_id, fuelio_guid, expense_date, odo, cost_type_id, "
    "title, notes, cost, is_income, is_template, "
    "remind_odo, remind_date, repeat_odo, repeat_months, flag"
)


def _row_to_expense(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": row["id"],
        "vehicle_id": row["vehicle_id"],
        "fuelio_guid": row["fuelio_guid"],
        "expense_date": row["expense_date"],
        "odo": row["odo"],
        "cost_type_id": row["cost_type_id"],
        "title": row["title"],
        "notes": row["notes"],
        "cost": row["cost"],
        "is_income": row["is_income"],
        "is_template": row["is_template"],
        "remind_odo": row["remind_odo"],
        "remind_date": row["remind_date"],
        "repeat_odo": row["repeat_odo"],
        "repeat_months": row["repeat_months"],
        "flag": row["flag"],
    }


@router.get(
    "/expenses",
    response_model=list[ExpenseOut],
    dependencies=[Depends(require_query_token)],
)
async def list_expenses(
    vehicle_id: UUID | None = Query(default=None),
    category_id: int | None = Query(default=None),
    from_: date | None = Query(default=None, alias="from"),
    to: date | None = Query(default=None),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    where: list[str] = []
    args: list[Any] = []
    if vehicle_id is not None:
        args.append(vehicle_id)
        where.append(f"vehicle_id = ${len(args)}")
    if category_id is not None:
        args.append(category_id)
        where.append(f"cost_type_id = ${len(args)}")
    if from_ is not None:
        args.append(from_)
        where.append(f"expense_date >= ${len(args)}")
    if to is not None:
        args.append(to)
        where.append(f"expense_date <= ${len(args)}")
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"SELECT {_EXPENSE_COLS} FROM expenses{where_sql} "
            f"ORDER BY expense_date DESC LIMIT 1000",
            *args,
        )
    return [_row_to_expense(r) for r in rows]


@router.post(
    "/expenses",
    response_model=ExpenseOut,
    status_code=201,
    dependencies=[Depends(require_ingest_token)],
)
async def create_expense(
    body: ExpenseCreate,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        try:
            row = await conn.fetchrow(
                f"""
                INSERT INTO expenses (
                    vehicle_id, expense_date, odo, cost_type_id,
                    title, notes, cost, is_income, is_template,
                    remind_odo, remind_date, repeat_odo, repeat_months, flag
                ) VALUES (
                    $1, $2, $3, $4,
                    $5, $6, $7, $8, $9,
                    $10, $11, $12, $13, $14
                )
                RETURNING {_EXPENSE_COLS}
                """,
                body.vehicle_id, body.expense_date, body.odo, body.cost_type_id,
                body.title, body.notes, _dec(body.cost), body.is_income,
                body.is_template, body.remind_odo, body.remind_date,
                body.repeat_odo, body.repeat_months, body.flag,
            )
        except asyncpg.ForeignKeyViolationError as exc:
            raise HTTPException(
                status_code=400, detail=str(exc)
            ) from exc
    assert row is not None
    return _row_to_expense(row)


@router.patch(
    "/expenses/{expense_id}",
    response_model=ExpenseOut,
    dependencies=[Depends(require_ingest_token)],
)
async def update_expense(
    body: ExpenseUpdate,
    expense_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    fields = body.model_dump(exclude_unset=True)
    if not fields:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                f"SELECT {_EXPENSE_COLS} FROM expenses WHERE id = $1", expense_id
            )
        if row is None:
            raise HTTPException(status_code=404, detail="expense not found")
        return _row_to_expense(row)
    set_parts: list[str] = []
    values: list[Any] = []
    for i, (k, v) in enumerate(fields.items(), start=2):
        set_parts.append(f"{k} = ${i}")
        if k == "cost" and isinstance(v, (int, float)):
            v = Decimal(str(v))
        values.append(v)
    sql = (
        f"UPDATE expenses SET {', '.join(set_parts)} WHERE id = $1 "
        f"RETURNING {_EXPENSE_COLS}"
    )
    async with pool.acquire() as conn:
        row = await conn.fetchrow(sql, expense_id, *values)
    if row is None:
        raise HTTPException(status_code=404, detail="expense not found")
    return _row_to_expense(row)


@router.delete(
    "/expenses/{expense_id}",
    status_code=204,
    dependencies=[Depends(require_ingest_token)],
)
async def delete_expense(
    expense_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> None:
    async with pool.acquire() as conn:
        result = await conn.execute(
            "DELETE FROM expenses WHERE id = $1", expense_id
        )
    if result.endswith(" 0"):
        raise HTTPException(status_code=404, detail="expense not found")


@router.get(
    "/expense-categories",
    response_model=list[ExpenseCategoryOut],
    dependencies=[Depends(require_query_token)],
)
async def list_categories(
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, name, priority, color FROM expense_categories "
            "ORDER BY priority, id"
        )
    return [
        {
            "id": r["id"],
            "name": r["name"],
            "priority": r["priority"],
            "color": r["color"],
        }
        for r in rows
    ]


def _dec(v: Decimal | int | float) -> Decimal:
    if isinstance(v, Decimal):
        return v
    return Decimal(str(v))
