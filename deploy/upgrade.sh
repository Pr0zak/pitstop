#!/bin/sh
# pitstop in-app upgrade script — runs inside an ephemeral docker:27-cli
# container spawned by /admin/upgrade. /opt/pitstop on the CT is
# bind-mounted at /work; /var/run/docker.sock is passed through.
#
# Steps print [N/5] markers so a future progress-streaming version can
# parse them; today the frontend just polls /version until it flips.
#
# This script must NOT depend on the backend being alive — `docker
# compose up -d backend` will tear it down halfway through. The
# upgrader container owns its own lifecycle.
#
# For laptop-side deploys (rsync + rebuild from local source) use the
# pitstop-deploy skill instead — this script is purely the in-app path.

set -e

TARGET="${TARGET_TAG:?TARGET_TAG env var required (e.g. v0.1.152)}"
# CI publishes GHCR images via docker/metadata-action's {{version}}
# pattern, which strips the leading 'v' — so the image tag is 0.1.154
# not v0.1.154. Normalize to the bare semver to match.
NORMALIZED="${TARGET#v}"

# docker compose names its project after the working directory by
# default. We bind-mount the CT's /opt/pitstop at /work, so compose
# would create a parallel `work-backend-1` etc. instead of touching the
# existing `pitstop-*` containers — and then fail with port-already-bound
# at the up step. Pin the project name explicitly to match the CT host's
# default (basename of /opt/pitstop).
export COMPOSE_PROJECT_NAME=pitstop

cd /work

# ---------------------------------------------------------------------
# [1/5] Schema-compatibility preflight.
#
# The upgrader points an EXISTING database at a DIFFERENT image. If that
# image's migration set predates the revision the DB is stamped at,
# alembic aborts at boot with "Can't locate revision identified by ..."
# and the container crash-loops forever — no rollback, and because the
# frontend keeps serving from Caddy the outage looks like nothing is
# wrong. This check runs BEFORE .env is touched so a refusal leaves the
# running stack completely untouched.
#
# Real incident (2026-08-10): an unreleased 0022 migration was applied by
# hand, then an in-app update pulled v0.1.233, whose image contains only
# 0018-0021. Backend down 33 hours, silently.
#
# An inconclusive probe does not block — only a definite "the image does
# not contain it" aborts.
# ---------------------------------------------------------------------
echo "[1/5] Preflight: does $NORMALIZED contain the DB's current migration?"
IMAGE="ghcr.io/${GHCR_OWNER:-pr0zak}/pitstop-backend:$NORMALIZED"

PGUSER=$(grep -E '^POSTGRES_USER=' .env 2>/dev/null | cut -d= -f2- || true)
PGDB=$(grep -E '^POSTGRES_DB=' .env 2>/dev/null | cut -d= -f2- || true)
PGUSER="${PGUSER:-pitstop}"
PGDB="${PGDB:-pitstop}"

HEAD=$(docker compose exec -T db psql -U "$PGUSER" -d "$PGDB" -t -A \
  -c "SELECT version_num FROM alembic_version" 2>/dev/null | tr -d '\r' | head -1 || true)

if [ -z "$HEAD" ]; then
  echo "      Could not read alembic_version — skipping check"
else
  echo "      DB is stamped at '$HEAD'; pulling $IMAGE to inspect"
  docker pull "$IMAGE"
  LISTING=$(docker run --rm --entrypoint sh "$IMAGE" -c "ls /app/alembic/versions/" 2>/dev/null || true)
  if [ -z "$LISTING" ]; then
    echo "      Could not list migrations inside the image — skipping check"
  elif echo "$LISTING" | grep -q "^${HEAD}\.py$"; then
    echo "      OK — image contains $HEAD"
  else
    echo "ABORT: $IMAGE does not contain migration '$HEAD'." >&2
    echo "       This database has already been migrated past what that" >&2
    echo "       release ships, so its backend could not boot:" >&2
    echo "         alembic: Can't locate revision identified by '$HEAD'" >&2
    echo "       Nothing was changed — the current stack is still running." >&2
    exit 1
  fi
fi

echo "[2/5] Updating .env BACKEND_TAG / FRONTEND_TAG to $NORMALIZED"
if grep -q '^BACKEND_TAG=' .env; then
  sed -i "s|^BACKEND_TAG=.*|BACKEND_TAG=$NORMALIZED|" .env
else
  echo "BACKEND_TAG=$NORMALIZED" >> .env
fi
if grep -q '^FRONTEND_TAG=' .env; then
  sed -i "s|^FRONTEND_TAG=.*|FRONTEND_TAG=$NORMALIZED|" .env
else
  echo "FRONTEND_TAG=$NORMALIZED" >> .env
fi

echo "[3/5] docker compose pull backend frontend"
docker compose pull backend frontend

echo "[4/5] docker compose up -d backend frontend"
docker compose up -d backend frontend

echo "[5/5] Done — new backend will report $TARGET at /version once it boots"
