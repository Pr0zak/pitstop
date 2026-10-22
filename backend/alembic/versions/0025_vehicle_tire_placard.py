"""vehicles.tire_placard_front_kpa / tire_placard_rear_kpa — door-placard pressure

The manufacturer's recommended COLD tire pressure (door-jamb placard), front
and rear axle, in kPa. Gives the TPMS views an absolute baseline beside the
relative 2-psi spread rule: "35 psi placard, this corner reads 31".

User-set, nullable, no backfill: NULL means "not configured" and every
placard visual / verdict is hidden. Values are entered from the Vehicles
page (in psi) after deploy — a data step would have to name a real vehicle
in a public repo.

The CHECK bounds the value to something a road tire could carry
(~7-145 psi), so a typo is refused at the DB; the API validates first.

Revision ID: 0025_vehicle_tire_placard
Revises: 0024_places_fuel_prices
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision: str = "0025_vehicle_tire_placard"
down_revision: str | None = "0024_places_fuel_prices"
branch_labels: str | None = None
depends_on: str | None = None

_AXLES = ("front", "rear")


def upgrade() -> None:
    for axle in _AXLES:
        col = f"tire_placard_{axle}_kpa"
        op.add_column("vehicles", sa.Column(col, sa.Float(), nullable=True))
        op.create_check_constraint(
            f"ck_vehicles_{col}_range",
            "vehicles",
            f"{col} IS NULL OR ({col} >= 50 AND {col} <= 1000)",
        )


def downgrade() -> None:
    for axle in _AXLES:
        col = f"tire_placard_{axle}_kpa"
        op.drop_constraint(f"ck_vehicles_{col}_range", "vehicles", type_="check")
        op.drop_column("vehicles", col)
