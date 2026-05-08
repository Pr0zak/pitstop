#!/usr/bin/env bash
# Tier A — Liveness. HTTP + MQTT + container health.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

bold "[Tier A] liveness checks against ${CT_IP}"

# 1. /health
body="$(curl -fsS --max-time 5 "${API}/health")"
[[ "$(echo "$body" | jq -r .status)" == "ok" ]] || fail "/health body: ${body}"
ok "/health → ok"

# 2. /version
body="$(curl -fsS --max-time 5 "${API}/version")"
ver="$(echo "$body" | jq -r .version)"
sha="$(echo "$body" | jq -r .git_sha)"
[[ -n "$ver" && "$ver" != "null" ]] || fail "/version missing version"
[[ -n "$sha" ]] || fail "/version missing git_sha"
ok "/version: ${ver} (sha ${sha:0:8})"

# 3. Frontend
body="$(curl -fsS --max-time 5 "${WEB}/")"
echo "$body" | grep -qi "pitstop" || fail "frontend HTML missing 'pitstop'"
ok "/ (frontend) returns HTML"

# 4. /health/disk
body="$(curl -fsS --max-time 5 "${API}/health/disk")"
used="$(echo "$body" | jq -r .used_pct)"
free="$(echo "$body" | jq -r .free_gb)"
[[ -n "$used" && "$used" != "null" ]] || fail "/health/disk missing used_pct"
ok "/health/disk: used=${used}% free=${free}GB"

# 5. Mosquitto broker version
v="$(mosquitto_sub -h "${MQTT_HOST}" -p "${MQTT_PORT}" \
  -u "${MQTT_USER}" -P "${MQTT_PASSWORD}" \
  -t '$SYS/broker/version' -W 5 -C 1 2>/dev/null || true)"
[[ -n "$v" ]] || fail "mosquitto broker did not respond"
ok "mosquitto broker: ${v}"

# 6. docker compose ps — all four services healthy with no restarts
ps_json="$(ct_compose ps --format json)"
# docker compose ps emits one JSON object per line on newer versions; normalize.
states="$(echo "$ps_json" | jq -r '.Health // .State // ""' 2>/dev/null || echo "")"
fails=0
for svc in db backend frontend mosquitto; do
  state="$(ct_compose ps --format json "$svc" | jq -r '.Health // .State // ""' 2>/dev/null || true)"
  case "$state" in
    healthy|running)
      ok "service $svc: ${state}"
      ;;
    *)
      warn "service $svc: ${state}"
      fails=$((fails+1))
      ;;
  esac
done
[[ "$fails" -eq 0 ]] || fail "${fails} compose service(s) unhealthy"

bold "[Tier A] PASS"
