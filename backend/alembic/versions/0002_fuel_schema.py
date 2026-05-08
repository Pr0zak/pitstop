"""Task #14 — fuel + expense schema (fillups, expenses, pictures, categories).

Adds Fuelio-derived tables and seeds the default expense and trip categories.

Revision ID: 0002_fuel_schema
Revises: 0001_core_schema
Create Date: 2026-05-07
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0002_fuel_schema"
down_revision: str | None = "0001_core_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # --- expense_categories -------------------------------------------------
    op.create_table(
        "expense_categories",
        sa.Column("id", sa.SmallInteger(), primary_key=True),
        sa.Column("name", sa.Text(), nullable=False, unique=True),
        sa.Column(
            "priority",
            sa.SmallInteger(),
            nullable=False,
            server_default=sa.text("0"),
        ),
        sa.Column("color", sa.Text(), nullable=True),
        sa.Column("fuelio_guid", sa.Text(), unique=True, nullable=True),
        sa.Column("lastupdated_ms", sa.BigInteger(), nullable=True),
    )
    # Fuelio's nine defaults; synthetic IDs 1..9 (Fuelio's own CostTypeIDs vary
    # by export, so we keep our own stable PK and link by name on import).
    op.execute(
        """
        INSERT INTO expense_categories (id, name, priority) VALUES
            (1, 'Service', 0),
            (2, 'Maintenance', 0),
            (3, 'Repairs', 0),
            (4, 'Insurance', 0),
            (5, 'Tires', 0),
            (6, 'Wash', 0),
            (7, 'Tolls', 0),
            (8, 'Parking', 0),
            (9, 'Other', 0);
        """
    )

    # --- trip_categories ----------------------------------------------------
    op.create_table(
        "trip_categories",
        sa.Column("id", sa.SmallInteger(), primary_key=True),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("fuelio_guid", sa.Text(), unique=True, nullable=True),
        sa.Column("lastupdated_ms", sa.BigInteger(), nullable=True),
    )
    op.execute(
        """
        INSERT INTO trip_categories (id, name) VALUES
            (1, 'Private'),
            (2, 'Work');
        """
    )

    # --- fillups ------------------------------------------------------------
    op.create_table(
        "fillups",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "vehicle_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("fuelio_guid", sa.Text(), unique=True, nullable=True),
        sa.Column("fillup_date", sa.DateTime(timezone=True), nullable=False),
        sa.Column("odo", sa.Float(), nullable=False),
        sa.Column("fuel_volume", sa.Float(), nullable=False),
        sa.Column(
            "is_full", sa.Boolean(), nullable=False, server_default=sa.text("true")
        ),
        sa.Column(
            "is_missed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
        sa.Column("price_total", sa.Numeric(10, 2), nullable=True),
        sa.Column("price_per_unit", sa.Numeric(10, 4), nullable=True),
        sa.Column("mpg_reported", sa.Float(), nullable=True),
        sa.Column("lat", sa.Float(), nullable=True),
        sa.Column("lon", sa.Float(), nullable=True),
        sa.Column("city", sa.Text(), nullable=True),
        sa.Column("station_id", sa.Integer(), nullable=True),
        sa.Column(
            "tank_number",
            sa.SmallInteger(),
            nullable=False,
            server_default=sa.text("1"),
        ),
        sa.Column("fuel_type", sa.SmallInteger(), nullable=True),
        sa.Column("weather", sa.Text(), nullable=True),
        sa.Column("tank_calc", sa.Float(), nullable=True),
        sa.Column(
            "exclude_distance",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("lastupdated_ms", sa.BigInteger(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "ix_fillups_vehicle_date",
        "fillups",
        ["vehicle_id", sa.text("fillup_date DESC")],
    )

    # --- expenses -----------------------------------------------------------
    op.create_table(
        "expenses",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "vehicle_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("fuelio_guid", sa.Text(), unique=True, nullable=True),
        sa.Column("expense_date", sa.Date(), nullable=False),
        sa.Column("odo", sa.Float(), nullable=True),
        sa.Column(
            "cost_type_id",
            sa.SmallInteger(),
            sa.ForeignKey("expense_categories.id"),
            nullable=True,
        ),
        sa.Column("title", sa.Text(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("cost", sa.Numeric(10, 2), nullable=False),
        sa.Column(
            "is_income",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
        sa.Column(
            "is_template",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
        sa.Column("remind_odo", sa.Float(), nullable=True),
        sa.Column("remind_date", sa.Date(), nullable=True),
        sa.Column("repeat_odo", sa.Float(), nullable=True),
        sa.Column("repeat_months", sa.Float(), nullable=True),
        sa.Column(
            "flag", sa.SmallInteger(), nullable=False, server_default=sa.text("0")
        ),
        sa.Column("lastupdated_ms", sa.BigInteger(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "ix_expenses_vehicle_date",
        "expenses",
        ["vehicle_id", sa.text("expense_date DESC")],
    )

    # --- pictures -----------------------------------------------------------
    op.create_table(
        "pictures",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "vehicle_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("fuelio_guid", sa.Text(), unique=True, nullable=True),
        sa.Column("filename", sa.Text(), nullable=False),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("kind", sa.SmallInteger(), nullable=True),
        sa.Column("target_id", sa.Text(), nullable=True),
        sa.Column("lastupdated_ms", sa.BigInteger(), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("pictures")
    op.drop_index("ix_expenses_vehicle_date", table_name="expenses")
    op.drop_table("expenses")
    op.drop_index("ix_fillups_vehicle_date", table_name="fillups")
    op.drop_table("fillups")
    op.drop_table("trip_categories")
    op.drop_table("expense_categories")
