#!/usr/bin/env bash
# Inside-CT bootstrap. Idempotent. Run as root inside CT 231 (or whatever CT_ID).
# See docs/deployment.md Phase 3.
set -euo pipefail

TZ_VAL="${TZ:-America/Chicago}"

echo "[ct-bootstrap] apt update + base packages"
apt-get update -y
apt-get install -y curl ca-certificates gnupg lsb-release rsync git

echo "[ct-bootstrap] installing Docker CE"
install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
  curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
fi
codename="$(. /etc/os-release && echo "$VERSION_CODENAME")"
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $codename stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

echo "[ct-bootstrap] runc 1.1.x swap (Debian's runc, mandatory for unprivileged LXC + Docker)"
apt-get install -y runc
cp /usr/sbin/runc /usr/bin/runc.bak.$(date +%s) 2>/dev/null || true
cp /usr/sbin/runc /usr/bin/runc
systemctl restart docker

echo "[ct-bootstrap] /opt/pitstop layout"
mkdir -p /opt/pitstop /opt/pitstop/data/db /opt/pitstop/data/mosquitto /opt/pitstop/data/photos /opt/pitstop/data/backups

echo "[ct-bootstrap] disk monitoring cron"
if [ -f /opt/pitstop/deploy/disk-cron.sh ]; then
  install -m 0755 /opt/pitstop/deploy/disk-cron.sh /usr/local/bin/pitstop-disk-cron
  cat > /etc/cron.daily/pitstop-disk <<'EOF'
#!/bin/sh
exec /usr/local/bin/pitstop-disk-cron
EOF
  chmod 0755 /etc/cron.daily/pitstop-disk
  install -d -m 0755 /var/log
  touch /var/log/pitstop-disk.log
fi

echo "[ct-bootstrap] timezone $TZ_VAL"
ln -sf "/usr/share/zoneinfo/$TZ_VAL" /etc/localtime || true

echo "[ct-bootstrap] journald cap"
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/99-pitstop.conf <<'EOF'
[Journal]
SystemMaxUse=500M
EOF
systemctl restart systemd-journald

echo "[ct-bootstrap] complete"
