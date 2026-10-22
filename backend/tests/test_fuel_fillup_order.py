"""A trip that ended before a full fillup must not be debited after it.

The fillup resets the estimate to a full tank; the drive to the pump is
usually derived and settled minutes later, so the decrement pass sees it
after the reset. Its burn is already inside the "full" level.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from pitstop.workers.fuel_state_worker import trip_predates_full_fillup

FILL = datetime(2026, 10, 22, 17, 30, tzinfo=UTC)


def test_drive_to_the_pump_is_skipped():
    assert trip_predates_full_fillup(FILL - timedelta(minutes=3), FILL)


def test_trip_ending_at_the_fillup_instant_is_skipped():
    assert trip_predates_full_fillup(FILL, FILL)


def test_drive_after_the_fillup_is_debited():
    assert not trip_predates_full_fillup(FILL + timedelta(minutes=10), FILL)


def test_no_full_fillup_on_record_debits_everything():
    assert not trip_predates_full_fillup(FILL, None)
