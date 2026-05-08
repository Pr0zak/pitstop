# pitstop

Self-hosted vehicle telemetry and fuel-tracking app.

- Receives OBD-II PIDs from a [WiCAN Pro](https://meatpihq.github.io/wican-fw/) over MQTT (driveway WiFi or phone BT bridge)
- Stores trips, readings, fillups, expenses, and service reminders in TimescaleDB
- Vue 3 dashboard: live gauges, trip history with maps, fuel/MPG analytics, maintenance reminders
- Imports historical fuel data from Fuelio (Sygic) CSV exports
- Companion Android app (Kotlin) that bridges OBD over BLE → MQTT and offers a mobile-native UI

Status: **planning** — see [PLAN.md](./PLAN.md). Repo is doc-only until Task #1 scaffolds the Compose stack. Public repo: [github.com/Pr0zak/pitstop](https://github.com/Pr0zak/pitstop).

## Stack

- Backend: FastAPI (async) · aiomqtt · asyncpg · alembic
- Frontend: Vue 3 · Vite · Pinia · uPlot · MapLibre GL
- DB: TimescaleDB 2.17 / Postgres 16
- Broker: Mosquitto
- Deployment: single Docker Compose stack in a Proxmox LXC on pve5

## Layout (planned)

```
pitstop/
├── backend/                  # FastAPI service
├── frontend/                 # Vue 3 SPA
├── deploy/                   # ct-bootstrap.sh, upgrade.sh
├── pid_profiles/             # WiCAN-format PID profile JSONs (seed)
├── docs/                     # design notes & research
└── docker-compose.yml
```
