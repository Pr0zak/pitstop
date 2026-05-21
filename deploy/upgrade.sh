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
cd /work

echo "[1/4] Updating .env BACKEND_TAG / FRONTEND_TAG to $TARGET"
if grep -q '^BACKEND_TAG=' .env; then
  sed -i "s|^BACKEND_TAG=.*|BACKEND_TAG=$TARGET|" .env
else
  echo "BACKEND_TAG=$TARGET" >> .env
fi
if grep -q '^FRONTEND_TAG=' .env; then
  sed -i "s|^FRONTEND_TAG=.*|FRONTEND_TAG=$TARGET|" .env
else
  echo "FRONTEND_TAG=$TARGET" >> .env
fi

echo "[2/4] docker compose pull backend frontend"
docker compose pull backend frontend

echo "[3/4] docker compose up -d backend frontend"
docker compose up -d backend frontend

echo "[4/4] Done — new backend will report $TARGET at /version once it boots"
