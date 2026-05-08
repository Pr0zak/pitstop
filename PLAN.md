# pitstop — build plan

## Goal

Self-hosted Pixel-Watch-class telemetry app for personal vehicles. Replace dependence on Fuelio (Sygic) for fuel logging, add OBD-II live data and trip analytics from a WiCAN Pro device, fold in service/maintenance reminders, and run all of it on infrastructure I own.

## Vehicles in scope at launch

| Vehicle | State | Source |
|---|---|---|
| **2019 Honda Pilot Elite** | active — primary OBD target (WiCAN plugged in) | live MQTT + Fuelio history (238 fillups, 9 services, ~75,984 mi, 19.5 gal tank, ~16 MPG) |
| **Truck** | retired (last fillup 2019) | Fuelio history only (254 fillups, ~12 MPG, 25 gal) |
| **SeaDoo 170 STI jet ski** | occasional | Fuelio history only (1 fillup, 15.9 gal, "odometer" is engine hours) |

Multi-vehicle from day one. One Fuelio import populates everything.

## Architecture

```
┌─ Honda Pilot ──────────────────────────┐
│ WiCAN Pro (OBD-II)                     │
│   ├─ WiFi mode (driveway):  MQTT push  │
│   └─ BLE mode (driving):    phone app  │
└──────────┬──────────────────────┬──────┘
           │                      │
           ▼                      ▼
   home WiFi (driveway)    phone BT bridge (cellular)
           │                      │
           │            ┌─────────┴─── reaches LAN via existing
           │            │              subnet router CT 444 (Tailscale)
           ▼            ▼
        ┌───────────────────────────────┐
        │ pitstop CT on pve5            │
        │ ┌───────────────────────────┐ │
        │ │ mosquitto :1883 (LAN-only)│ │
        │ └─────────────┬─────────────┘ │
        │               ▼               │
        │ ┌───────────────────────────┐ │
        │ │ backend (FastAPI) :8000   │ │
        │ │  • aiomqtt subscriber     │ │
        │ │  • trip detector worker   │ │
        │ │  • REST + WebSocket API   │ │
        │ │  • Fuelio CSV importer    │ │
        │ │  • HA mirror (disabled)   │ │
        │ └─────────────┬─────────────┘ │
        │               ▼               │
        │ ┌───────────────────────────┐ │
        │ │ TimescaleDB :5432         │ │
        │ │  • pid_readings (hyper)   │ │
        │ │  • trips, dtc_events      │ │
        │ │  • vehicles, profiles     │ │
        │ │  • fillups, expenses      │ │
        │ └───────────────────────────┘ │
        │ ┌───────────────────────────┐ │
        │ │ frontend (Vue 3) :8080    │ │
        │ └───────────────────────────┘ │
        └───────────────────────────────┘
```

## Locked tech choices

| Decision | Choice | Why |
|---|---|---|
| TSDB | **TimescaleDB** (Postgres 16 + hypertable) | One DB for everything, no Influx/Grafana |
| Frontend | **Vue 3 + Vite + Pinia** | matches myvitals; mature ecosystem |
| Charts | **uPlot** | fast for 100k-point time series |
| Maps | **MapLibre GL** + OSM tiles | open, no API key |
| Broker | **Mosquitto** | in-stack, LAN-only |
| Auth | **shared INGEST/QUERY tokens** | matches myvitals |
| Profile JSON | **WiCAN AutoPID format 1:1** | one file works as device upload + backend seed |
| Sync key | **`fuelio_guid`** (not VIN) | VIN sparsely populated in real Fuelio data |
| Tailscale | **none on pitstop CT** | existing subnet router (CT 444) advertises LAN |
| HA mirror | **built, default off** | plumbing ready; flip when desired |
| Phone bridge | **native Kotlin** | BLE behaves better with platform APIs; matches zonik-app |
| Deploy target | **new CT on pve5** | next to existing media/monitoring stack |
| Repo | **[github.com/Pr0zak/pitstop](https://github.com/Pr0zak/pitstop)** (public) + public GHCR images | mirrors myvitals |
| Units (default) | **USD / miles / US gallons / MPG** | per user preference |

## Phases

### Phase A — Data plane (Tasks 1, 2, 14, 12, 3, 4, 15)

By the end:
- Compose stack scaffolded
- Schema in place (vehicles, profiles, readings, trips, DTCs, fillups, expenses, settings)
- Honda Pilot 2019 PID profile seeded
- MQTT subscriber receiving from WiCAN
- Trip detector closing trips on idle
- Fuelio importer ingests all 3 vehicles' historical data

Verify via `psql` — no UI yet.

### Phase B — UI + native app (Tasks 5, 16, 6, 7, 8, 9, 17, 18, 10, 28, 29)

By the end:
- REST + WebSocket API live
- Vue shell with sidebar, vehicle picker, dark theme
- Live gauges (real-time)
- Trips list/detail with uPlot timeline + MapLibre route map
- Engine analytics (fuel economy, driving habits, health, DTCs)
- Fuel UI (fillups list, add form, stations map, stats, import)
- Service & maintenance reminders dashboard
- **Android app (Kotlin):** BLE bridge service + native config + status + live view + fuel quick-add

Use [claude.ai/design](https://claude.ai/design) for visual mockup help.

### Phase C — Deploy + HA (Tasks 11, 19–27, 13)

By the end:
- Production CT provisioned on pve5 (30 GB rootfs, monitored)
- All four services healthy, smoke + E2E tests green
- HA plumbing wired but toggle defaults off
- Phone app pointed at deployed broker

## Task list — see [TODO.md](./TODO.md)

## Day-one outcome

After Phase A + the import: DB holds **493 fillups, 11 services, 3 vehicles**, plus live OBD telemetry from the Pilot. Phase B turns it into the polished UI. Phase C deploys + mobilizes.

## Privacy — never to GitHub

The `Pr0zak/pitstop` repo is public-by-default. **Never commit:**

- Fuelio CSV exports or anything derived from them (fillup history, GPS coordinates, station names, expense totals)
- Real PID profile JSONs containing VIN, plate, or owner-identifying fields — ship a generic `honda-pilot-2019.json` profile only
- `.env` files, tokens (`INGEST_TOKEN`, `QUERY_TOKEN`, `MQTT_PASSWORD`), keystore files, signing keys
- DB dumps, backups, photo blobs
- Internal infra details: CT IDs (104, 444, etc.), LAN IPs (`10.0.0.x`, `192.168.x.x`), real hostnames (`pve3`, `pve5`), the user's email/name
- WiCAN device serial numbers or MAC addresses

The `.gitignore` excludes `data/`, `*.csv`, `*.zip`, `.env*`, `*.jks`, `keystore.properties`, `**/private/`, `pid_profiles/private-*.json`. The release skill runs a privacy grep before tagging.

Sample data lives **only** at:
- `/home/spider/pitstop/data/` (gitignored, on dev machine)
- `/opt/pitstop/data/` (CT, never committed)
- `/tmp/fuelio-inspect/` (ephemeral inspection — clean up after use)

## Open / deferred

- Photos: Fuelio CSV references JPEG filenames but doesn't include the bytes. Future: watched-folder pickup if user copies JPEGs into `/data/photos/`.
- Auto-import scheduling: web upload only at launch. Watched-dir or Drive sync deferred.
- Multi-user / per-user auth: deferred. Shared tokens at launch.
- iOS phone bridge: deferred. Kotlin first; only port to Flutter/iOS if needed.
- Trip categories (Private/Work from Fuelio's `Category` section): imported but not surfaced at launch.
- Trip-categorized fuel cost (private vs business): future analytics view.
