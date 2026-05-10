<p align="left">
  <img src="docs/logo.svg" alt="pitstop logo" width="96" height="96" />
</p>

# pitstop

Self-hosted vehicle telemetry and fuel-tracking app.

- Ingests OBD-II PIDs from a [WiCAN Pro](https://meatpihq.github.io/wican-fw/) over MQTT — either directly over Wi-Fi (AutoPID at home / driveway) or via a phone BLE bridge while driving.
- Stores trips, readings, fillups, expenses, GPS tracks, and engine on/off events in TimescaleDB. Trip detection is post-processed every 5 minutes from the raw stream so noisy real-time signals (LWT blips, late buffer drains, dual-provider GPS noise) don't poison the trip table.
- Vue 3 dashboard: live gauges, trip history with map polylines, fuel/MPG analytics, station map, maintenance reminders, hero cards for cost-per-mile / best-MPG / etc.
- Imports historical fuel data from Fuelio (Sygic) CSV exports — including the `(optional)`-suffixed columns for GPS, station ID, notes, and price that older tooling missed.
- Companion Android app (Kotlin + Jetpack Compose): bridges OBD over BLE → MQTT during driving, surfaces live gauges, picks up WiCAN AutoPID metrics from the broker, supports adaptive BLE backoff via Android Auto + paired-car-Bluetooth presence detection, and offers an in-app self-update flow.

## Stack

- **Backend:** FastAPI (async), aiomqtt, asyncpg, alembic
- **Frontend:** Vue 3, Vite, Pinia, uPlot, MapLibre GL
- **Phone:** Kotlin, Jetpack Compose, Hilt, HiveMQ MQTT3 client, Nordic BLE library, Tink (encrypted secrets)
- **DB:** TimescaleDB 2.17 / Postgres 16 — hypertables for `pid_readings`, `gps_points`, `engine_events`
- **Broker:** Mosquitto on the LAN
- **Deployment:** single Docker Compose stack inside a Proxmox LXC

## Layout

```
pitstop/
├── backend/                  # FastAPI service + alembic migrations
│   ├── alembic/versions/
│   ├── src/pitstop/
│   │   ├── api/              # routers: trips, fillups, analytics, settings, admin, ...
│   │   ├── services/         # fuelio_import, etc.
│   │   ├── workers/          # ingest, trip_deriver, retention, ha_mirror, eia_fetcher
│   │   └── ...
├── frontend/                 # Vue 3 SPA (Caddy-served)
├── android/                  # Kotlin + Compose phone app
├── deploy/                   # ct-bootstrap.sh, upgrade.sh, fixtures, mosquitto config
├── pid_profiles/             # WiCAN-format PID profile JSONs (DB seed)
├── docs/                     # decisions (ADRs), architecture, deployment, ui-workflow, wican-config
├── PLAN.md                   # roadmap + decisions log
└── docker-compose.yml
```

## Trip detection

Trips are derived by a 5-minute batch worker (`workers/trip_deriver.py`), not in real time. Per cycle it pulls every `vehicle_speed > 0` reading, every moving GPS point, and every `engine_events` row in the lookback window for each vehicle, then walks them as a single sample stream:

- **Hard close** on bridge-source `engine_state=off` (phone OBD STOPPED-frame detection).
- **Soft signal** for everything else (vehicle_speed, GPS movement, WiCAN LWT off — the LWT can blip false on Wi-Fi keepalive timeouts so it's coerced to "activity" subject to the 5-minute merge gap).
- **Merge** intervals separated by ≤ 5 min into one trip (covers stoplights, brief stops).
- **Distance** by haversine sum on `gps_points`, with a > 250 km/h velocity sanity filter that drops phantom jumps from stale Wi-Fi-derived locations; falls back to vehicle-speed integration when no GPS.
- **Idempotent** UUID-v5 trip ids keyed on `(vehicle_id, started_at)` — re-running doesn't dup. User-set `category` and `notes` are preserved on re-derive.

Manual rerun is `POST /admin/trips/reprocess?older_than_hours=N`.

## Engine state

Three layers, each covering the others' blind spots:

| Layer | Source | Speed | Blind spot |
|---|---|---|---|
| Phone bridge `engine_state` | OBD STOPPED-frame parsing over BLE | seconds | needs phone in BT range |
| WiCAN MQTT LWT | `wican/<id>/can/status` from the broker | ~5–15 s on engine-on, 3–5 min on engine-off (broker keepalive) | needs WiCAN's Wi-Fi reachable |
| `vehicle_speed > 0` activity | direct from PID readings | per-sample | obvious — no signal until OBD is flowing |

The trip deriver consumes all three. The phone uses `engine_state` as the gate for IMU + GPS broker publishes (no flooding the DB while parked).

## Development

Backend + frontend run via Docker Compose; the phone app builds with Gradle. The `deploy/` scripts provision a Proxmox LXC end-to-end (including the Docker-in-LXC `runc` swap for unprivileged containers). See [docs/deployment.md](./docs/deployment.md) and [PLAN.md](./PLAN.md).
