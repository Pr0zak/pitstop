#!/bin/sh
# pitstop in-app upgrade script — runs inside an ephemeral docker:27-cli
# container spawned by /admin/upgrade. /opt/pitstop on the CT is
# bind-mounted at /work; /var/run/docker.sock is passed through.
#
# Steps print [N/4] markers so a future progress-streaming version can
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

echo "[1/4] Updating .env BACKEND_TAG / FRONTEND_TAG to $NORMALIZED"
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

echo "[2/4] docker compose pull backend frontend"
docker compose pull backend frontend

echo "[3/4] docker compose up -d backend frontend"
docker compose up -d backend frontend

echo "[4/4] Done — new backend will report $TARGET at /version once it boots"
