"""Timescale compression on pid_readings + gps_points + engine_events
(Task DISK-2).

Goal is multi-year vehicle history without busting the CT disk. Raw
shape (timestamp + metric + scalar value) compresses ~8–10x with
Timescale's columnar compression; the most recent week stays
uncompressed for fast trip-detail queries, anything older flips to
read-only compressed chunks.

No retention/drop policy — we keep all rows forever. Sizing math
(after IMU is dropped in DISK-3):

    ~40 MB/day raw → ~5 MB/day after compression
      1 year ≈ 1.5–2 GB
      5 years ≈ 8–10 GB
     10 years ≈ 15–20 GB

That comfortably fits the current 30 GB CT for the life of any one
vehicle. Headroom: `pct resize 231 rootfs +10G` on pve5 is a
one-liner when needed.

Backfill: existing chunks older than 7 days are compressed in the
upgrade() step so the immediate disk win lands without waiting for
the policy job to fire.

Revision ID: 0015_timescale_compression
Revises: 0014_trips_idle_s
Create Date: 2026-05-15
"""

from __future__ import annotations

import logging
from collections.abc import Sequence

from alembic import op

log = logging.getLogger(__name__)

revision: str = "0015_timescale_compression"
down_revision: str | None = "0014_trips_idle_s"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# (hypertable, segmentby clause) — orderby is always `time DESC` for these
# append-only time-series. segmentby is chosen so a typical query
# (`vehicle_id = X AND metric = Y AND time BETWEEN …`) hits the right
# compressed batches without decompressing the whole chunk.
_HYPERTABLES: list[tuple[str, str]] = [
    ("pid_readings", "vehicle_id, metric"),
    ("gps_points", "vehicle_id"),
    ("engine_events", "vehicle_id"),
]


def upgrade() -> None:
    conn = op.get_bind()
    for table, segmentby in _HYPERTABLES:
        # Enable compression. Re-running on a table that's already
        # configured is harmless — Timescale no-ops the SET options
        # when they match.
        conn.exec_driver_sql(
            f"""
            ALTER TABLE {table} SET (
                timescaledb.compress,
                timescaledb.compress_orderby = 'time DESC',
                timescaledb.compress_segmentby = '{segmentby}'
            )
            """
        )
        # Background policy that compresses chunks once they're older
        # than a week. if_not_exists so a re-run doesn't error.
        conn.exec_driver_sql(
            f"""
            SELECT add_compression_policy(
                '{table}', INTERVAL '7 days', if_not_exists => true
            )
            """
        )
        # Immediate backfill: walk existing chunks older than 7 days
        # and compress them now so we don't wait on the next policy
        # tick. Wrapped in a DO block + EXCEPTION handler so a
        # partially-compressed chunk from a prior failed run doesn't
        # break the whole migration.
        conn.exec_driver_sql(
            f"""
            DO $$
            DECLARE
                c regclass;
            BEGIN
                FOR c IN
                    SELECT show_chunks('{table}', older_than => INTERVAL '7 days')
                LOOP
                    BEGIN
                        PERFORM compress_chunk(c);
                    EXCEPTION WHEN OTHERS THEN
                        RAISE NOTICE 'compress_chunk(%) skipped: %', c, SQLERRM;
                    END;
                END LOOP;
            END$$;
            """
        )


def downgrade() -> None:
    conn = op.get_bind()
    for table, _segmentby in _HYPERTABLES:
        # Best-effort: remove the policy, then decompress every
        # compressed chunk so the table goes back to plain Timescale
        # storage. The policy's own removal is also best-effort
        # because it may not exist on a partial run.
        conn.exec_driver_sql(
            f"""
            SELECT remove_compression_policy(
                '{table}', if_exists => true
            )
            """
        )
        conn.exec_driver_sql(
            f"""
            DO $$
            DECLARE
                c regclass;
            BEGIN
                FOR c IN
                    SELECT format('%I.%I', chunk_schema, chunk_name)::regclass
                      FROM timescaledb_information.chunks
                     WHERE hypertable_name = '{table}'
                       AND is_compressed = true
                LOOP
                    BEGIN
                        PERFORM decompress_chunk(c);
                    EXCEPTION WHEN OTHERS THEN
                        RAISE NOTICE 'decompress_chunk(%) skipped: %', c, SQLERRM;
                    END;
                END LOOP;
            END$$;
            """
        )
        conn.exec_driver_sql(
            f"""
            ALTER TABLE {table} SET (
                timescaledb.compress = false
            )
            """
        )
