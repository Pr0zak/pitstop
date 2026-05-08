#!/usr/bin/env bash
# Tier C — end-to-end. Two flows that exercise the full pipeline.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

bold "[Tier C] E2E flows"

# ---------- E2E-1: Synthetic OBD trip ----------
bold "[Tier C / E2E-1] Synthetic OBD trip"

# 1. Create test vehicle
slug="e2e-pilot-$(date +%s)"
body=$(api_post_json "/vehicles" "{
  \"slug\": \"${slug}\",
  \"name\": \"E2E Test Pilot\",
  \"make\": \"Synthetic\",
  \"model\": \"E2E\",
  \"year\": 2026,
  \"fuelio_guid\": \"e2e-vehicle-${slug}\"
}")
vehicle_id="$(echo "$body" | jq -r '.id')"
[[ -n "$vehicle_id" && "$vehicle_id" != "null" ]] || fail "vehicle create returned: ${body}"
ok "created vehicle ${slug} → ${vehicle_id}"

cleanup_vehicle() {
  curl -fsS --max-time 10 -X DELETE \
    -H "Authorization: Bearer ${INGEST_TOKEN}" \
    "${API}/vehicles/${vehicle_id}" >/dev/null 2>&1 || true
}
trap cleanup_vehicle EXIT

# 2. Publish 60s synthetic OBD via the fixture
fixture="$(cd "${HERE}/.." && pwd)/tests/fixtures/fixture-mqtt-publish.sh"
[[ -x "$fixture" ]] || fail "fixture-mqtt-publish.sh missing or not executable"

MQTT_HOST="${MQTT_HOST}" MQTT_PORT="${MQTT_PORT}" \
MQTT_USER="${MQTT_USER}" MQTT_PASSWORD="${MQTT_PASSWORD}" \
MQTT_VEHICLE_SLUG="${slug}" \
"$fixture" 30 >/dev/null

ok "published 30s of synthetic OBD on wican/${slug}/*"

# 3. Wait for trip to close (trip_silence_close_s default 60)
bold "[Tier C / E2E-1] waiting 70s for trip to close (silence-detected)"
sleep 70

# 4. Trip exists + has reasonable stats
trips_body="$(api_get "/trips?vehicle_id=${vehicle_id}")"
n="$(echo "$trips_body" | jq -r 'length')"
[[ "$n" -ge 1 ]] || fail "no trip recorded after publish (got: ${trips_body})"
duration="$(echo "$trips_body" | jq -r '.[0].duration_s // 0')"
max_rpm="$(echo "$trips_body" | jq -r '.[0].max_rpm // 0')"
ok "trip recorded: duration=${duration}s, max_rpm=${max_rpm}"
[[ "$duration" -ge 5 && "$duration" -le 120 ]] || fail "trip duration ${duration}s outside [5,120]"

# 5. Readings landed
readings_body="$(api_get "/readings?vehicle_id=${vehicle_id}&metric=engine_rpm&limit=200")"
nr="$(echo "$readings_body" | jq -r 'length')"
[[ "$nr" -ge 20 ]] || fail "engine_rpm readings count=${nr} (expected ≥ 20)"
ok "engine_rpm readings: ${nr}"

# 6. Cleanup explicit; trap also catches errors
curl -fsS --max-time 10 -X DELETE \
  -H "Authorization: Bearer ${INGEST_TOKEN}" \
  "${API}/vehicles/${vehicle_id}" >/dev/null
trap - EXIT
ok "deleted vehicle ${vehicle_id}"

bold "[Tier C / E2E-1] PASS"

# ---------- E2E-2: Synthetic Fuelio import ----------
bold "[Tier C / E2E-2] Synthetic Fuelio import"

fixture_csv="$(cd "${HERE}/.." && pwd)/tests/fixtures/fixture-fuelio.csv"
[[ -f "$fixture_csv" ]] || fail "fixture-fuelio.csv missing"

# 1. Dry-run preview
preview="$(curl -fsS --max-time 30 \
  -H "Authorization: Bearer ${INGEST_TOKEN}" \
  -F "files=@${fixture_csv}" \
  "${API}/import/fuelio?dry_run=true")"
v_added="$(echo "$preview" | jq -r '.preview.vehicles.added // 0')"
f_added="$(echo "$preview" | jq -r '.preview.fillups.added // 0')"
e_added="$(echo "$preview" | jq -r '.preview.expenses.added // 0')"
[[ "$v_added" == 1 && "$f_added" == 3 && "$e_added" == 1 ]] || fail "dry-run preview: vehicles=${v_added} fillups=${f_added} expenses=${e_added} (expected 1/3/1). raw: ${preview}"
ok "dry-run preview: vehicles=1, fillups=3, expenses=1"

# 2. Real import
result="$(curl -fsS --max-time 30 \
  -H "Authorization: Bearer ${INGEST_TOKEN}" \
  -F "files=@${fixture_csv}" \
  "${API}/import/fuelio?dry_run=false")"
ok "real import committed"

# 3. Re-run dry-run → should show 0 added (idempotent on guid)
preview2="$(curl -fsS --max-time 30 \
  -H "Authorization: Bearer ${INGEST_TOKEN}" \
  -F "files=@${fixture_csv}" \
  "${API}/import/fuelio?dry_run=true")"
v_added2="$(echo "$preview2" | jq -r '.preview.vehicles.added // 0')"
f_added2="$(echo "$preview2" | jq -r '.preview.fillups.added // 0')"
[[ "$v_added2" == 0 && "$f_added2" == 0 ]] || fail "second dry-run not idempotent: vehicles_added=${v_added2}, fillups_added=${f_added2}"
ok "re-import idempotent (vehicles_added=0, fillups_added=0)"

# 4. Cleanup the imported test vehicle
fixture_vehicle_id="$(api_get "/vehicles" | jq -r '.[] | select(.fuelio_guid == "fixture-vehicle-0001") | .id' | head -n1)"
if [[ -n "$fixture_vehicle_id" && "$fixture_vehicle_id" != "null" ]]; then
  curl -fsS --max-time 10 -X DELETE \
    -H "Authorization: Bearer ${INGEST_TOKEN}" \
    "${API}/vehicles/${fixture_vehicle_id}" >/dev/null
  ok "deleted fixture vehicle ${fixture_vehicle_id}"
fi

bold "[Tier C / E2E-2] PASS"
bold "[Tier C] PASS"
