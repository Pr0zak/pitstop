#!/usr/bin/env bash
# Mint secrets, write .env, create CT, capture DHCP IP.
# Output: prints the captured IP on the last line of stdout.
set -euo pipefail

CT_ID="${PITSTOP_CT_ID:-231}"
PVE_HOST="${PITSTOP_PVE:?set PITSTOP_PVE=<your-pve-host>}"
HOSTNAME="${PITSTOP_HOSTNAME:-pitstop}"
STORAGE="${PITSTOP_STORAGE:-local-lvm-2t}"
DISK_GB="${PITSTOP_DISK_GB:-30}"
CORES="${PITSTOP_CORES:-2}"
RAM_MB="${PITSTOP_RAM_MB:-6144}"
SWAP_MB="${PITSTOP_SWAP_MB:-2048}"
TZ_VAL="${PITSTOP_TZ:-America/Chicago}"
REPO_DIR="${PITSTOP_REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SECRETS_FILE="${PITSTOP_SECRETS_FILE:-${HOME}/.pitstop-deploy-secrets.txt}"

bold() { printf '\e[1m%s\e[0m\n' "$*"; }
ok()   { printf '  \e[32m✓\e[0m %s\n' "$*"; }
err()  { printf '  \e[31m✗\e[0m %s\n' "$*"; exit 1; }

bold "[provision] minting secrets"
INGEST_TOKEN="$(openssl rand -hex 32)"
QUERY_TOKEN="$(openssl rand -hex 32)"
POSTGRES_PASSWORD="$(openssl rand -hex 24)"
MQTT_PASSWORD="$(openssl rand -hex 24)"
MQTT_USER="${MQTT_USER:-pitstop}"
POSTGRES_USER="${POSTGRES_USER:-pitstop}"
POSTGRES_DB="${POSTGRES_DB:-pitstop}"

bold "[provision] writing ${SECRETS_FILE} (mode 0600)"
umask 077
cat > "${SECRETS_FILE}" <<EOF
# pitstop deploy secrets — generated $(date -Iseconds)
# CT ${CT_ID} on ${PVE_HOST}

POSTGRES_USER=${POSTGRES_USER}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
POSTGRES_DB=${POSTGRES_DB}

INGEST_TOKEN=${INGEST_TOKEN}
QUERY_TOKEN=${QUERY_TOKEN}

MQTT_USER=${MQTT_USER}
MQTT_PASSWORD=${MQTT_PASSWORD}
EOF
chmod 600 "${SECRETS_FILE}"
ok "secrets at ${SECRETS_FILE}"

bold "[provision] rendering .env from .env.example"
ENV_TMP="$(mktemp)"
cp "${REPO_DIR}/.env.example" "${ENV_TMP}"
sed -i "s|^POSTGRES_USER=.*|POSTGRES_USER=${POSTGRES_USER}|" "${ENV_TMP}"
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${POSTGRES_PASSWORD}|" "${ENV_TMP}"
sed -i "s|^POSTGRES_DB=.*|POSTGRES_DB=${POSTGRES_DB}|" "${ENV_TMP}"
sed -i "s|^INGEST_TOKEN=.*|INGEST_TOKEN=${INGEST_TOKEN}|" "${ENV_TMP}"
sed -i "s|^QUERY_TOKEN=.*|QUERY_TOKEN=${QUERY_TOKEN}|" "${ENV_TMP}"
sed -i "s|^MQTT_USER=.*|MQTT_USER=${MQTT_USER}|" "${ENV_TMP}"
sed -i "s|^MQTT_PASSWORD=.*|MQTT_PASSWORD=${MQTT_PASSWORD}|" "${ENV_TMP}"
sed -i "s|^TZ=.*|TZ=${TZ_VAL}|" "${ENV_TMP}"
chmod 600 "${ENV_TMP}"
echo "${ENV_TMP}" > "${SECRETS_FILE}.envpath"
ok "env rendered to ${ENV_TMP}"

bold "[provision] locating debian-12 template on ${PVE_HOST}"
TEMPLATE="$(ssh "root@${PVE_HOST}" "ls /var/lib/vz/template/cache/debian-12-standard*.tar.zst 2>/dev/null | head -n1 || true")"
if [[ -z "${TEMPLATE}" ]]; then
  bold "[provision] downloading debian-12 template"
  ssh "root@${PVE_HOST}" "pveam update >/dev/null && pveam download local debian-12-standard"
  TEMPLATE="$(ssh "root@${PVE_HOST}" "ls /var/lib/vz/template/cache/debian-12-standard*.tar.zst 2>/dev/null | head -n1")"
fi
[[ -n "${TEMPLATE}" ]] || err "no debian-12 template available"
ok "template ${TEMPLATE}"

bold "[provision] creating CT ${CT_ID}"
# Use SSH heredoc to avoid local-shell quoting nightmare.
# Note: SSH key from /root/.ssh/authorized_keys already in place;
# for the LXC we do not need a new key — pct exec is the access path.
ssh "root@${PVE_HOST}" bash -s <<EOSSH
set -e
pct create ${CT_ID} ${TEMPLATE} \
  --hostname ${HOSTNAME} \
  --cores ${CORES} \
  --memory ${RAM_MB} \
  --swap ${SWAP_MB} \
  --rootfs ${STORAGE}:${DISK_GB} \
  --net0 name=eth0,bridge=vmbr0,ip=dhcp \
  --features keyctl=1,nesting=1 \
  --unprivileged 1 \
  --onboot 1
pct start ${CT_ID}
EOSSH
ok "CT ${CT_ID} created and started"

bold "[provision] waiting for systemd ready"
for i in $(seq 1 60); do
  state="$(ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- systemctl is-system-running 2>/dev/null || true")"
  case "$state" in
    running|degraded)
      ok "systemd ready (state: ${state})"
      break
      ;;
  esac
  sleep 2
  if [[ "$i" == 60 ]]; then
    err "systemd not ready after 120s"
  fi
done

bold "[provision] capturing CT IP"
CT_IP=""
for i in $(seq 1 30); do
  CT_IP="$(ssh "root@${PVE_HOST}" "pct exec ${CT_ID} -- ip -4 -o addr show eth0 2>/dev/null" \
    | awk '{print $4}' | cut -d/ -f1 | head -n1 || true)"
  if [[ -n "${CT_IP}" ]]; then break; fi
  sleep 2
done
[[ -n "${CT_IP}" ]] || err "no eth0 IPv4 after 60s"
ok "CT ${CT_ID} IP: ${CT_IP}"

# Save CT IP into the secrets file for downstream scripts.
echo "CT_IP=${CT_IP}" >> "${SECRETS_FILE}"
echo "CT_ID=${CT_ID}" >> "${SECRETS_FILE}"
echo "PVE_HOST=${PVE_HOST}" >> "${SECRETS_FILE}"
echo "MOSQUITTO_BIND=${CT_IP}" >> "${SECRETS_FILE}"

# Pin the env file's MOSQUITTO_BIND to the LAN IP so 1883 is LAN-only.
sed -i "s|^MOSQUITTO_BIND=.*|MOSQUITTO_BIND=${CT_IP}|" "${ENV_TMP}"

# Last line is the IP — caller can $(provision.sh | tail -n1).
echo "${CT_IP}"
