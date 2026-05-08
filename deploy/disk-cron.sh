#!/usr/bin/env bash
# Daily inside-CT disk-usage logger + alert webhook poster.
# Installed by ct-bootstrap as /usr/local/bin/pitstop-disk-cron and called from /etc/cron.daily.
set -euo pipefail

LOG="/var/log/pitstop-disk.log"
THRESHOLD="${PITSTOP_DISK_ALERT_PCT:-70}"

# Read PITSTOP_DISK_ALERT_WEBHOOK from /opt/pitstop/.env if present.
WEBHOOK=""
if [[ -f /opt/pitstop/.env ]]; then
  WEBHOOK="$(grep -E '^PITSTOP_DISK_ALERT_WEBHOOK=' /opt/pitstop/.env | cut -d= -f2- || true)"
fi

usage_pct="$(df -P / | awk 'NR==2 {gsub("%","",$5); print $5}')"
free_gb="$(df -P -BG / | awk 'NR==2 {gsub("G","",$4); print $4}')"
total_gb="$(df -P -BG / | awk 'NR==2 {gsub("G","",$2); print $2}')"

ts="$(date -Iseconds)"
echo "${ts} usage=${usage_pct}% free=${free_gb}G total=${total_gb}G" >> "$LOG"

if [[ "${usage_pct}" -ge "${THRESHOLD}" && -n "${WEBHOOK}" ]]; then
  curl -fsS --max-time 5 -X POST \
    -H 'Content-Type: application/json' \
    -d "{\"host\":\"$(hostname)\",\"ts\":\"${ts}\",\"used_pct\":${usage_pct},\"free_gb\":${free_gb},\"total_gb\":${total_gb}}" \
    "${WEBHOOK}" || true
fi
