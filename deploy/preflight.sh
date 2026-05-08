#!/usr/bin/env bash
# Pre-flight validation. No side effects on pve5 or anywhere else — just checks.
# Exits non-zero with explicit reason on failure.
set -euo pipefail

CT_ID="${PITSTOP_CT_ID:-231}"
PVE_HOST="${PITSTOP_PVE:-pve5}"
STORAGE="${PITSTOP_STORAGE:-local-lvm-2t}"
MIN_FREE_GB="${PITSTOP_MIN_FREE_GB:-50}"
PIHOLE_HOST="${PITSTOP_PIHOLE:-pihole.local}"
REPO_DIR="${PITSTOP_REPO_DIR:-/home/spider/pitstop}"

bold() { printf '\e[1m%s\e[0m\n' "$*"; }
ok()   { printf '  \e[32m✓\e[0m %s\n' "$*"; }
fail() { printf '  \e[31m✗\e[0m %s\n' "$*"; exit 1; }
warn() { printf '  \e[33m!\e[0m %s\n' "$*"; }

bold "[preflight] target: CT ${CT_ID} on ${PVE_HOST}"

# 1. Local tools
bold "[preflight] local tools"
for t in ssh rsync openssl curl jq mosquitto_pub mosquitto_sub git; do
  if command -v "$t" >/dev/null 2>&1; then
    ok "$t"
  else
    fail "$t not installed locally"
  fi
done

# 2. SSH to pve host
bold "[preflight] SSH to ${PVE_HOST}"
if ssh -o ConnectTimeout=5 -o BatchMode=yes "root@${PVE_HOST}" "true" >/dev/null 2>&1; then
  ok "ssh root@${PVE_HOST} OK"
else
  fail "cannot ssh root@${PVE_HOST} (key auth required)"
fi

# 3. CT must not exist
bold "[preflight] CT ${CT_ID} availability"
status="$(ssh "root@${PVE_HOST}" "pct status ${CT_ID} 2>&1 || true")"
if [[ "$status" == *"does not exist"* ]]; then
  ok "CT ${CT_ID} does not yet exist"
else
  fail "CT ${CT_ID} already exists (status: ${status}). Destroy first or override PITSTOP_CT_ID."
fi

# 4. Storage free space
bold "[preflight] storage ${STORAGE} free space"
free_gb="$(ssh "root@${PVE_HOST}" "pvesm status -storage ${STORAGE} 2>/dev/null" \
  | awk -v s="$STORAGE" '$1==s {avail_kb=$5} END {print int(avail_kb/1024/1024)}')"
if [[ -z "$free_gb" || "$free_gb" -lt "$MIN_FREE_GB" ]]; then
  fail "storage ${STORAGE} free=${free_gb}GiB < required ${MIN_FREE_GB}GiB"
fi
ok "storage ${STORAGE} has ${free_gb} GiB free (≥ ${MIN_FREE_GB})"

# 5. vmbr0 up
bold "[preflight] vmbr0 link"
if ssh "root@${PVE_HOST}" "ip link show vmbr0 | grep -q 'state UP'"; then
  ok "vmbr0 up on ${PVE_HOST}"
else
  fail "vmbr0 not UP on ${PVE_HOST}"
fi

# 6. Repo present and shaped right
bold "[preflight] repo at ${REPO_DIR}"
[[ -d "${REPO_DIR}" ]] || fail "${REPO_DIR} missing"
[[ -f "${REPO_DIR}/docker-compose.yml" ]] || fail "${REPO_DIR}/docker-compose.yml missing"
[[ -f "${REPO_DIR}/.env.example" ]] || fail "${REPO_DIR}/.env.example missing"
[[ -d "${REPO_DIR}/deploy" ]] || fail "${REPO_DIR}/deploy missing"
[[ -d "${REPO_DIR}/backend" && -d "${REPO_DIR}/frontend" ]] || fail "backend/ or frontend/ missing"
[[ -d "${REPO_DIR}/pid_profiles" ]] || fail "pid_profiles/ missing"
[[ -f "${REPO_DIR}/pid_profiles/honda-pilot-2019.json" ]] || fail "honda-pilot-2019.json missing"
ok "repo shape OK"

# 7. Git uncommitted changes (warn-only)
bold "[preflight] git working tree"
if [[ -d "${REPO_DIR}/.git" ]]; then
  cd "${REPO_DIR}"
  if [[ -z "$(git status --porcelain)" ]]; then
    ok "no uncommitted changes"
  else
    warn "uncommitted changes present (continuing — not blocking)"
    git status --porcelain | head -10
  fi
else
  warn "${REPO_DIR} is not a git repo (continuing)"
fi

# 8. Pihole reachable (warn-only)
bold "[preflight] pihole reachable (best-effort)"
if curl -fsS --max-time 3 "http://${PIHOLE_HOST}/admin/" >/dev/null 2>&1; then
  ok "pihole at ${PIHOLE_HOST} reachable"
else
  warn "pihole at ${PIHOLE_HOST} not reachable — DHCP reservation will be skipped"
fi

bold "[preflight] all required checks passed"
