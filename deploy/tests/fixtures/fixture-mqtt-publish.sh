#!/usr/bin/env bash
# Publish 60s of synthetic OBD-II values for the `test-pilot` vehicle.
# Used by Tier C E2E (deploy/tests/tier-c-e2e.sh).
#
# Usage:
#   MQTT_HOST=... MQTT_USER=... MQTT_PASSWORD=... ./fixture-mqtt-publish.sh [duration_sec]
#
# Defaults to 60s. Publishes engine_rpm + vehicle_speed + coolant_temp +
# control_module_voltage at 1 Hz on topic wican/test-pilot/<metric>.
set -euo pipefail

HOST="${MQTT_HOST:-localhost}"
PORT="${MQTT_PORT:-1883}"
USER="${MQTT_USER:-pitstop}"
PASS="${MQTT_PASSWORD:?MQTT_PASSWORD is required}"
SLUG="${MQTT_VEHICLE_SLUG:-test-pilot}"
DURATION="${1:-60}"

topic_base="wican/${SLUG}"

publish() {
  local metric="$1" value="$2"
  mosquitto_pub -h "$HOST" -p "$PORT" -u "$USER" -P "$PASS" \
    -t "${topic_base}/${metric}" -m "$value" -q 0
}

echo "publishing ${DURATION}s of synthetic OBD data to ${HOST}:${PORT} as ${USER} for slug=${SLUG}"

for i in $(seq 1 "$DURATION"); do
  # idle then ramp
  if [ "$i" -lt 5 ]; then
    rpm=800
    speed=0
  else
    # sin-ish ramp up to ~3000 rpm, ~75 kph
    rpm=$(( 800 + (i * 35) % 2200 ))
    speed=$(( (i * 2) % 80 ))
  fi
  coolant=$(( 70 + (i % 25) ))           # 70..95 °C
  voltage_milli=$(( 13800 + (i % 400) )) # 13.8..14.2 V

  publish engine_rpm "$rpm"
  publish vehicle_speed "$speed"
  publish coolant_temp "$coolant"
  publish control_module_voltage "$(awk "BEGIN{printf \"%.3f\", $voltage_milli/1000}")"

  sleep 1
done

echo "publish run complete"
