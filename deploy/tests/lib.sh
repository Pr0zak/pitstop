# Shared helpers for tier-{a,b,c,d}.sh
# shellcheck shell=bash

SECRETS_FILE="${PITSTOP_SECRETS_FILE:-${HOME}/.pitstop-deploy-secrets.txt}"

if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "[lib] missing $SECRETS_FILE — run provision first" >&2
  exit 2
fi
# shellcheck disable=SC1090
source <(grep -E '^(CT_IP|CT_ID|PVE_HOST|INGEST_TOKEN|QUERY_TOKEN|MQTT_USER|MQTT_PASSWORD|POSTGRES_USER|POSTGRES_DB)=' "$SECRETS_FILE")

: "${CT_IP:?CT_IP missing in $SECRETS_FILE}"
: "${CT_ID:?CT_ID missing in $SECRETS_FILE}"
: "${PVE_HOST:?PVE_HOST missing in $SECRETS_FILE}"
: "${INGEST_TOKEN:?INGEST_TOKEN missing in $SECRETS_FILE}"
: "${QUERY_TOKEN:?QUERY_TOKEN missing in $SECRETS_FILE}"
: "${MQTT_USER:?MQTT_USER missing in $SECRETS_FILE}"
: "${MQTT_PASSWORD:?MQTT_PASSWORD missing in $SECRETS_FILE}"

API="http://${CT_IP}:8000"
WEB="http://${CT_IP}:8080"
MQTT_HOST="${CT_IP}"
MQTT_PORT="${MQTT_PORT:-1883}"

bold() { printf '\e[1m%s\e[0m\n' "$*"; }
ok()   { printf '  \e[32m✓\e[0m %s\n' "$*"; }
fail() { printf '  \e[31m✗\e[0m %s\n' "$*"; exit 1; }
warn() { printf '  \e[33m!\e[0m %s\n' "$*"; }

# Run psql inside the db container of the CT's compose stack.
ct_psql() {
  local sql="$1"
  ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c \"cd /opt/pitstop && docker compose exec -T -e PGPASSWORD=\\\$(grep ^POSTGRES_PASSWORD .env | cut -d= -f2) db psql -U ${POSTGRES_USER:-pitstop} -d ${POSTGRES_DB:-pitstop} -tAc \\\"${sql//\"/\\\\\\\"}\\\"\""
}

# Run any docker-compose command in the CT.
ct_compose() {
  ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c 'cd /opt/pitstop && docker compose $*'"
}

# Curl with QUERY auth.
api_get() {
  curl -fsS --max-time 10 -H "Authorization: Bearer ${QUERY_TOKEN}" "${API}$1"
}

# Curl with INGEST auth.
api_post_json() {
  local path="$1" body="$2"
  curl -fsS --max-time 10 -H "Authorization: Bearer ${INGEST_TOKEN}" \
    -H "Content-Type: application/json" -d "$body" "${API}${path}"
}
