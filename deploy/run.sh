#!/usr/bin/env bash
# Orchestrator: preflight → provision → rsync → bootstrap → up → verify → report.
# Failure handling: on tier failure, capture compose logs to ~/.pitstop-deploy-logs/<ts>/,
# destroy the CT (interactive prompt), and exit non-zero.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${PITSTOP_REPO_DIR:-$(cd "${HERE}/.." && pwd)}"
SECRETS_FILE="${PITSTOP_SECRETS_FILE:-${HOME}/.pitstop-deploy-secrets.txt}"
CT_ID="${PITSTOP_CT_ID:-231}"
PVE_HOST="${PITSTOP_PVE:-pve5}"
LOG_DIR_BASE="${HOME}/.pitstop-deploy-logs"

bold() { printf '\e[1m%s\e[0m\n' "$*"; }
err()  { printf '\e[31m✗ %s\e[0m\n' "$*"; }

ts="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="${LOG_DIR_BASE}/${ts}"
mkdir -p "${LOG_DIR}"

capture_logs() {
  bold "[orchestrator] capturing compose logs to ${LOG_DIR}/"
  ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c 'cd /opt/pitstop && docker compose logs --tail 500 --no-color'" > "${LOG_DIR}/compose.log" 2>&1 || true
  ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c 'cd /opt/pitstop && docker compose ps -a --format json'" > "${LOG_DIR}/compose-ps.json" 2>&1 || true
  ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c 'cd /opt/pitstop && docker compose config'" > "${LOG_DIR}/compose-config.yml" 2>&1 || true
}

destroy_ct_or_pause() {
  err "Tier verification failed. Logs: ${LOG_DIR}"
  if [[ "${PITSTOP_DESTROY_ON_FAIL:-yes}" == "yes" ]]; then
    bold "[orchestrator] destroying CT ${CT_ID} on ${PVE_HOST} (PITSTOP_DESTROY_ON_FAIL=yes)"
    ssh "root@${PVE_HOST}" "pct stop ${CT_ID} 2>/dev/null; pct destroy ${CT_ID} --purge" || true
  else
    err "PITSTOP_DESTROY_ON_FAIL!=yes — leaving CT in place for diagnosis"
  fi
  exit 1
}

# ---- preflight ----
bold "[orchestrator] STAGE 1 — preflight"
"${HERE}/preflight.sh"

# ---- provision (mints secrets, writes .env, creates CT, captures IP) ----
bold "[orchestrator] STAGE 2 — provision"
"${HERE}/provision.sh"
# load secrets just produced
# shellcheck disable=SC1090
source <(grep -E '^(CT_IP|CT_ID|PVE_HOST|INGEST_TOKEN|QUERY_TOKEN|POSTGRES_USER|POSTGRES_PASSWORD|POSTGRES_DB|MQTT_USER|MQTT_PASSWORD|MOSQUITTO_BIND)=' "${SECRETS_FILE}")
ENV_TMP="$(cat "${SECRETS_FILE}.envpath")"
bold "[orchestrator] CT IP captured: ${CT_IP}"

# ---- rsync repo + .env ----
bold "[orchestrator] STAGE 3 — push code to CT"
ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- mkdir -p /opt/pitstop"

# rsync via pve host (no direct CT SSH yet) — use pct push for files / pct exec rsync for trees.
# Easier: rsync over an ssh tunnel through the pve host using a ProxyJump (root@pve has ssh into the CT through pct exec).
# Easiest robust path: tar | ssh | tar.
tar -C "${REPO_DIR}" -cf - \
  --exclude='.git' --exclude='node_modules' --exclude='.venv' \
  --exclude='__pycache__' --exclude='.pytest_cache' \
  --exclude='data' --exclude='backups' --exclude='dist' \
  --exclude='build' --exclude='.gradle' --exclude='*.apk' --exclude='*.aab' \
  --exclude='.env' \
  . | ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c 'cd /opt/pitstop && tar -xf - --no-same-owner'"

# Push .env (mode 0600).
ssh "root@${PVE_HOST}" "pct push ${CT_ID} ${ENV_TMP} /opt/pitstop/.env --perms 0600"

# ---- bootstrap inside CT ----
bold "[orchestrator] STAGE 4 — ct-bootstrap"
ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c 'TZ=America/Chicago bash /opt/pitstop/deploy/ct-bootstrap.sh'"

# ---- mosquitto password file ----
bold "[orchestrator] STAGE 5 — mosquitto passwd"
ssh "root@${PVE_HOST}" bash -s <<EOSSH
set -e
pct exec ${CT_ID} -- bash -c "
  set -e
  cd /opt/pitstop
  docker run --rm -v /opt/pitstop/mosquitto/config:/mosquitto/config eclipse-mosquitto:2.0 \
    mosquitto_passwd -b -c /mosquitto/config/passwd '${MQTT_USER}' '${MQTT_PASSWORD}'
  chmod 0700 /opt/pitstop/mosquitto/config/passwd
"
EOSSH

# ---- bring up the stack ----
bold "[orchestrator] STAGE 6 — docker compose up"
ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c 'cd /opt/pitstop && docker compose build && docker compose up -d'"

bold "[orchestrator] waiting for services to be healthy (max 180s)"
ok_count=0
for i in $(seq 1 36); do
  states="$(ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- bash -c 'cd /opt/pitstop && docker compose ps --format \"{{.Service}}={{.Health}}\" 2>/dev/null'" || true)"
  echo "  [${i}] ${states}" | tr '\n' ' ' && echo
  ok_count=0
  for svc in db backend frontend mosquitto; do
    if echo "$states" | grep -qE "^${svc}=(healthy|running)$"; then
      ok_count=$((ok_count+1))
    fi
  done
  if [[ "$ok_count" -eq 4 ]]; then
    bold "[orchestrator] all 4 services healthy"
    break
  fi
  sleep 5
done

if [[ "$ok_count" -lt 4 ]]; then
  err "services did not become healthy within 180s (got ${ok_count}/4)"
  capture_logs
  destroy_ct_or_pause
fi

# ---- pihole reservation (best-effort) ----
bold "[orchestrator] STAGE 7 — pihole reservation (best-effort)"
"${HERE}/pihole-reserve.sh" "${CT_IP}" || true

# ---- 4-tier verification ----
bold "[orchestrator] STAGE 8 — 4-tier verification"
if ! "${HERE}/tests/run-all.sh"; then
  capture_logs
  destroy_ct_or_pause
fi

# ---- post-deploy report ----
bold "[orchestrator] STAGE 9 — post-deploy report"
"${HERE}/post-deploy-report.sh" | tee -a "${SECRETS_FILE}.report"
echo "report saved to ${SECRETS_FILE}.report"
