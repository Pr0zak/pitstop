#!/usr/bin/env bash
# Tier D — HA mirror is OFF.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

bold "[Tier D] HA mirror dormant checks"

# 1. /settings reports ha.enabled=false
settings="$(api_get "/settings")"
enabled="$(echo "$settings" | jq -r '.ha.enabled')"
[[ "$enabled" == "false" ]] || fail "settings.ha.enabled = ${enabled} (expected false)"
ok "settings.ha.enabled = false"

# 2. mosquitto homeassistant/# silent for 5s
out="$(mosquitto_sub -h "${MQTT_HOST}" -p "${MQTT_PORT}" \
  -u "${MQTT_USER}" -P "${MQTT_PASSWORD}" \
  -t 'homeassistant/#' -W 5 2>/dev/null || true)"
if [[ -n "$out" ]]; then
  fail "homeassistant/# returned messages while HA disabled: $(echo "$out" | head -3)"
fi
ok "homeassistant/# silent for 5s"

bold "[Tier D] PASS"
