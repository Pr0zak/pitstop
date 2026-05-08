#!/usr/bin/env bash
# Best-effort pihole DHCP reservation. Skips silently if pihole admin auth isn't
# configured. Never blocks the deploy.
set -euo pipefail

CT_ID="${PITSTOP_CT_ID:-231}"
PVE_HOST="${PITSTOP_PVE:-pve5}"
CT_IP="${1:?CT_IP required}"
PIHOLE_HOST="${PITSTOP_PIHOLE:-pihole.local}"
PIHOLE_PASSWORD="${PITSTOP_PIHOLE_PASSWORD:-}"

bold() { printf '\e[1m%s\e[0m\n' "$*"; }
warn() { printf '  \e[33m!\e[0m %s\n' "$*"; }
ok()   { printf '  \e[32m✓\e[0m %s\n' "$*"; }

bold "[pihole] DHCP reservation for CT ${CT_ID} → ${CT_IP}"

# Capture eth0 MAC
mac="$(ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- ip -o link show eth0 | awk '{print \$17}'" || true)"
if [[ -z "$mac" ]]; then
  warn "could not read MAC from CT — skipping reservation"
  exit 0
fi
ok "MAC: ${mac}"

# Check pihole reachable
if ! curl -fsS --max-time 3 "http://${PIHOLE_HOST}/admin/" >/dev/null 2>&1; then
  warn "${PIHOLE_HOST} unreachable — skipping (DHCP usually re-assigns same IP anyway)"
  exit 0
fi

if [[ -z "${PIHOLE_PASSWORD}" ]]; then
  warn "PITSTOP_PIHOLE_PASSWORD not set — skipping (set env var to enable)"
  exit 0
fi

# Pi-hole v6 auth flow: POST /api/auth → SID
sid="$(curl -fsS --max-time 5 -H 'Content-Type: application/json' \
  -d "{\"password\":\"${PIHOLE_PASSWORD}\"}" \
  "http://${PIHOLE_HOST}/api/auth" 2>/dev/null \
  | jq -r '.session.sid // empty' || true)"

if [[ -z "$sid" ]]; then
  warn "pihole auth failed — skipping"
  exit 0
fi

# Add static lease via dnsmasq config (Pi-hole v6 / DHCP enabled). API path:
# POST /api/config/dhcp/static body: {"hosts":["mac,ip,hostname"]}
result="$(curl -fsS --max-time 5 -X PATCH \
  -H "X-FTL-SID: ${sid}" -H 'Content-Type: application/json' \
  -d "{\"config\":{\"dhcp\":{\"hosts\":[\"${mac},${CT_IP},pitstop\"]}}}" \
  "http://${PIHOLE_HOST}/api/config" 2>&1 || true)"

if echo "$result" | grep -q '"error"'; then
  warn "reservation failed: $(echo "$result" | head -1)"
else
  ok "reservation submitted for ${mac} → ${CT_IP} (pitstop)"
fi
