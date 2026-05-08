# CLAUDE.md — pitstop

## What this is

Self-hosted vehicle telemetry + fuel tracker. WiCAN Pro publishes OBD-II PIDs over MQTT; backend persists to TimescaleDB; Vue 3 frontend renders live + historical analytics. Fuelio CSV import for legacy fuel data, service-reminder tracking from Fuelio's Costs section.

Read [PLAN.md](./PLAN.md) and [docs/decisions.md](./docs/decisions.md) before making structural changes.

## Stack

- Backend: FastAPI (async), aiomqtt, asyncpg, alembic
- Frontend: Vue 3, Vite, Pinia, Vue Router, uPlot, MapLibre GL
- DB: TimescaleDB 2.17 / Postgres 16
- Broker: Mosquitto (LAN-only, port 1883)
- Deployment: single Docker Compose stack in a Proxmox LXC on pve5

## Conventions (mirror myvitals)

- Token auth: `INGEST_TOKEN` + `QUERY_TOKEN` in `.env`
- Multi-arch Docker images on `ghcr.io/pr0zak/pitstop-{backend,frontend}`
- `deploy/ct-bootstrap.sh` provisions a fresh CT (with the Docker-in-LXC `runc` swap)
- `deploy/upgrade.sh` redeploys (or use the `pitstop-deploy` skill)

## Key design decisions

- **`fuelio_guid`** is the sync key for vehicles/fillups/expenses (VIN is sparsely populated in real Fuelio exports).
- **No Tailscale on the pitstop CT** — relies on existing subnet router (CT 444) for off-net access from the phone bridge.
- **Mosquitto LAN-only** on 1883 — WiCAN reaches it directly in the driveway; phone reaches it via the subnet router from cellular.
- **HA plumbing built but disabled** by default. Toggle in Settings → Home Assistant.
- **Profile JSONs seed the DB**, then DB is the source of truth. Files in `pid_profiles/` are the seed only.
- **Multi-vehicle from day one** — your garage already has Pilot + Truck + SeaDoo per the Fuelio export.
- **Web/phone parity, server is source of truth** (ADR-013). Vue frontend and Android app must keep feature parity for shared workflows. The Android app is collect+ship+cache; never persists authoritative state. Both clients consume the same backend API contract — design endpoints first.

## UI design workflow

Use [claude.ai/design](https://claude.ai/design) for UI mockups, layout planning, and component variant exploration before implementing.

## Privacy — DO NOT upload personal/private data to GitHub

`Pr0zak/pitstop` is public. Never commit:
- Fuelio CSV exports or fillup data with real GPS / station / cost
- Real PID profiles containing VIN / plate / owner info (ship the generic profile only)
- `.env`, tokens, keystores, DB dumps, photos
- Internal infra (CT IDs, LAN IPs, hostnames `pveN`, the user's name/email)

The `.gitignore` blocks `data/`, `*.csv`, `*.zip`, `.env*`, `*.jks`, `keystore.properties`, `**/private/`, `pid_profiles/private-*.json`. The `pitstop-release` skill runs a grep audit before tagging — pause if it finds anything.

When demonstrating with sample data, write it to `data/` (gitignored) or `/tmp/`. When pasting examples into docs, redact (lat/lon to 2 decimals, no real station names, no VIN).

## Build phase

Phase A + B + C complete; deployed at CT 231 / `10.0.0.83`. Future work follows [ADR-013](./docs/decisions.md#adr-013--webphone-parity-server-is-source-of-truth) — backend contract first, then web + Android consume it.

## Repo

GitHub: [github.com/Pr0zak/pitstop](https://github.com/Pr0zak/pitstop) (public, created in Task #1). GHCR images public (no PAT needed for CT pull).
