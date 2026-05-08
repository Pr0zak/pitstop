"""Pure unit tests for the recomputed-MPG function.

The expectation set in Task #16: F, P, F, P, P, F should produce 3 MPG values
(one per full fillup), with the partial-fill volumes rolled into the next
full's denominator.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from uuid import uuid4

from pitstop.api.fillups import compute_recomputed_mpg


def _row(idx: int, days: int, odo: float, vol: float, full: bool) -> dict:
    return {
        "id": uuid4(),
        "fillup_date": datetime(2024, 1, 1) + timedelta(days=days),
        "odo": odo,
        "fuel_volume": vol,
        "is_full": full,
    }


def test_partial_fills_rolled_forward() -> None:
    # F  (10000 mi, 12 gal)        — first full, no MPG (no prior anchor)
    # P  (10300 mi, 5 gal)          — no MPG; volume rolls forward
    # F  (10600 mi, 7 gal)          — MPG = (10600-10000) / (5+7) = 50.0
    # P  (10900 mi, 6 gal)          — None
    # P  (11200 mi, 6 gal)          — None
    # F  (11500 mi, 13 gal)         — MPG = (11500-10600) / (6+6+13) = 36.0
    rows = [
        _row(1, 0,  10000, 12, full=True),
        _row(2, 5,  10300,  5, full=False),
        _row(3, 10, 10600,  7, full=True),
        _row(4, 15, 10900,  6, full=False),
        _row(5, 20, 11200,  6, full=False),
        _row(6, 25, 11500, 13, full=True),
    ]
    out = compute_recomputed_mpg(rows)

    full_rows_in_order = [r for r in rows if r["is_full"]]
    # First full: None (no anchor before).
    assert out[full_rows_in_order[0]["id"]] is None
    # Second full: distance 600 over rolled-up 12 gal.
    assert out[full_rows_in_order[1]["id"]] == 50.0
    # Third full: distance 900 over rolled-up 25 gal.
    assert out[full_rows_in_order[2]["id"]] == 36.0
    # Partials all None.
    for r in rows:
        if not r["is_full"]:
            assert out[r["id"]] is None


def test_only_partials_no_mpg() -> None:
    rows = [
        _row(1, 0, 10000, 5, full=False),
        _row(2, 5, 10100, 5, full=False),
    ]
    out = compute_recomputed_mpg(rows)
    assert all(v is None for v in out.values())


def test_negative_distance_is_dropped() -> None:
    # If the user enters a backward odo (data entry error), don't produce a
    # bogus value — drop to None.
    rows = [
        _row(1, 0,  10000, 10, full=True),
        _row(2, 5,   9800,  8, full=True),  # odo went down
    ]
    out = compute_recomputed_mpg(rows)
    rows_full = [r for r in rows if r["is_full"]]
    assert out[rows_full[0]["id"]] is None
    assert out[rows_full[1]["id"]] is None
