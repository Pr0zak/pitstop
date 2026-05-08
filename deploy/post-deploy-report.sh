#!/usr/bin/env bash
# Print and persist the post-deploy report.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${PITSTOP_SECRETS_FILE:-${HOME}/.pitstop-deploy-secrets.txt}"
# shellcheck disable=SC1090
source <(grep -E '^(CT_IP|CT_ID|PVE_HOST|MQTT_USER|MQTT_PASSWORD)=' "$SECRETS_FILE")

cat <<EOF

════════════════════════════════════════════════════════════════
  ✅ pitstop deployed to CT ${CT_ID} on ${PVE_HOST}
════════════════════════════════════════════════════════════════

  IP:        ${CT_IP}
  API:       http://${CT_IP}:8000
  Frontend:  http://${CT_IP}:8080
  Mosquitto: ${CT_IP}:1883  (user "${MQTT_USER}")
  DB:        timescaledb on internal port 5432 (not exposed)
  Secrets:   ${SECRETS_FILE}  (mode 0600)

────────────────────────────────────────────────────────────────
  Manual steps (one-time per device):

  1. Open the WiCAN web UI → MQTT Settings:
       Broker:         ${CT_IP}:1883
       Username:       ${MQTT_USER}
       Password:       (paste from ${SECRETS_FILE})
       Topic prefix:   wican/pilot19/

  2. WiCAN AutoPID → Upload pid_profiles/honda-pilot-2019.json

  3. Drive once. Then run the pitstop-status skill (or
     curl ${CT_IP}:8000/health/ingest) to confirm data flowed.

  See docs/wican-config.md for full instructions + troubleshooting.

  4. Optional: install the Android app
     (android/app/build/outputs/apk/debug/app-debug.apk) for the
     cellular bridge + fuel quick-add. Configure broker IP
     and INGEST_TOKEN inside the app's Config screen.

────────────────────────────────────────────────────────────────
  Disk: pct resize ${CT_ID} rootfs +20G + resize2fs (no downtime)
  Rollback (destroys data): pct stop ${CT_ID} && pct destroy ${CT_ID} --purge
════════════════════════════════════════════════════════════════
EOF
