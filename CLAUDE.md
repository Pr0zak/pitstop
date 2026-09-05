# CLAUDE.md — pitstop

## What this is

Self-hosted vehicle telemetry + fuel tracker. WiCAN Pro publishes OBD-II PIDs over MQTT; backend persists to TimescaleDB; Vue 3 frontend renders live + historical analytics. Fuelio CSV import for legacy fuel data, service-reminder tracking from Fuelio's Costs section. Companion Kotlin/Compose phone app bridges OBD over BLE → MQTT during driving.

Read [PLAN.md](./PLAN.md) and [docs/decisions.md](./docs/decisions.md) before making structural changes.

## Stack

- Backend: FastAPI (async), aiomqtt, asyncpg, alembic
- Frontend: Vue 3, Vite, Pinia, Vue Router, uPlot, MapLibre GL
- Phone: Kotlin, Jetpack Compose, Hilt, HiveMQ MQTT3 client, Nordic BLE library, Tink
- DB: TimescaleDB 2.17 / Postgres 16 — hypertables: `pid_readings`, `gps_points`, `engine_events`
- Broker: Mosquitto (LAN-only, port 1883)
- Deployment: single Docker Compose stack in a Proxmox LXC

## Conventions

- Token auth: `INGEST_TOKEN` + `QUERY_TOKEN` in `.env`
- Multi-arch Docker images on `ghcr.io/pr0zak/pitstop-{backend,frontend}`
- `deploy/ct-bootstrap.sh` provisions a fresh CT (with the Docker-in-LXC `runc` swap)
- `deploy/upgrade.sh` redeploys (or use the `pitstop-deploy` skill)
- Phone APKs released via GitHub Releases on tag push; phone has in-app self-update flow that downloads + installs

## Architecture cheat sheet

- **Bridge payload v2** (ADR-015): phone publishes per-metric values as `{"v": <num>, "t": <unix_ms>}` to `bridge/<slug>/<metric>` so offline-buffer drains preserve real capture time. GPS goes to `bridge/<slug>/location` as a single object → `gps_points` table. Engine state changes go to `bridge/<slug>/engine_state`.
- **WiCAN-side signals**: backend subscribes to `wican/<id>/can/status` (CAN bus presence) and `wican/<id>/status` (device LWT). Both translate to `engine_events` with `source='wican_lwt'`.
- **Phone presence** (Task #77): `PresenceTracker` watches `CarConnection.type` (Android Auto active) + `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED` (paired car BT). Combined `inCar` StateFlow drives adaptive BLE backoff (5s cap when in-car, 5min cap when parked + engine-off).
- **Trip detection is post-processed** — `workers/trip_deriver.py` runs every 5 min, walks raw activity samples + engine_events, builds intervals, computes haversine distance with > 250 km/h velocity sanity filter, upserts trips with deterministic UUID v5 keyed on `(vehicle_id, started_at)`. Streaming `trip_detector.py` retired.
- **Drive payload to disk, not SQLite** (ADR-016): `PendingDrive` row carries metadata + a file path; the actual JSON body lives at `${filesDir}/pending-drives/${uuid}.json`. Inlining into the SQLite row hit Android's ~2 MB CursorWindow limit on long drives.
- **Auto-upload on WiFi** (ADR-023): opt-in Settings toggle + SSID allowlist. `WifiUploadGate` is the single decision point; `WifiUploadTrigger` (NetworkCallback) covers the app-alive case and an `UNMETERED` WorkManager one-shot armed by `DriveSealer` covers process death. Overrides manual-sync for the drive-upload queue only — MQTT stays suppressed. Empty allowlist = any unmetered WiFi.
- **Phone manual-sync mode** (ADR-015): Settings toggle suppresses all phone-side MQTT publishes + auto-upload. Local capture and Live tiles still work; "Sync now" is the only path that ships data when on. Persistent ongoing notification fires at ≥5 queued drives.
- **BLE phase ≠ engine state** (ADR-017): the phone tracks BLE link state and engine state independently. The OBD-quiet watchdog (60 s) only fires while `phase == Connected`; a separate BLE-lost watchdog (15 min) seals drives that end with a permanent BLE drop. Conflating these caused trip-splits across short BLE flakes (the 2026-05-12 incident).

## Key design decisions

- **`fuelio_guid`** is the sync key for vehicles/fillups/expenses (VIN is sparsely populated in real Fuelio exports).
- **Mosquitto LAN-only** on 1883 — WiCAN reaches it directly in the driveway; phone reaches it via the home network's subnet router from cellular.
- **HA plumbing built but disabled** by default. Toggle in Settings → Home Assistant.
- **Profile JSONs seed the DB**, then DB is the source of truth. Files in `pid_profiles/` are the seed only.
- **Multi-vehicle from day one** — Pilot + Truck per the Fuelio import; SeaDoo to follow.
- **Web/phone parity, server is source of truth** (ADR-013). Vue frontend and Android app must keep feature parity for shared workflows. The Android app is collect+ship+cache; never persists authoritative state. Both clients consume the same backend API contract — design endpoints first.
- **Settings.update() never overwrites a stored secret with blank** (post-v0.1.83) — protects against the form-init race where a Save fired before disk values had loaded would silently wipe MQTT password / tokens. Explicit clears go through `clearSecret()`.
- **Basemap tiles come from OpenFreeMap** (ADR-024) — `tiles.openfreemap.org/styles/{dark,positron}`, no API key. CARTO started watermarking its unauthenticated raster tiles "API KEY REQUIRED" on 2026-09-01. URLs live in `frontend/src/lib/mapStyles.ts` and `android/.../ui/history/MapStyles.kt` only — don't inline a style object in a view. Attribution rides in the TileJSON; adding an `AttributionControl` double-prints it.
- **Speed buckets share a 55-mph highway cutoff** between phone, web, and `/analytics/mpg-by-speed-class`. Stopped <2 mph, City 2–22 mph, Suburban 22–55 mph, Highway ≥55 mph (24.6 m/s).
- **The fuel snap reads phone-captured rows only** (ADR-025) — `SNAP_SOURCES = ('bridge','phone_batch')`, P75 of the last 15 min de-duplicated per second, rejects windows with < 10 readings or < 3 distinct values, no drop cap (large moves are logged `snap LARGE` and applied), and the applied burn of trips that ended after the window is subtracted from the target so a stale window can neither block a heal nor overwrite a newer decrement. The WiCAN replays a frozen AutoPID frame at 1 Hz after key-off / on WiFi reconnect and ingest stamps it at receipt, so a post-park `source='wican'` burst looks fresh and is one stale value per metric (2026-09-02: snapped +23.4 L, then the old 25 % drop cap refused the correction 565 times). Never re-add a stddev "stability" gate — a replay has stddev 0.
- **MPG aggregation must SELECT `is_missed`** (post-v0.1.118) — `compute_recomputed_mpg()` reads `row["is_missed"]` to skip the broken-chain case. Several `/analytics/*` endpoints' SELECT statements omitted the column and silently produced bogus MPG for any month containing a missed fillup.

## UI design workflow

Use [claude.ai/design](https://claude.ai/design) for UI mockups, layout planning, and component variant exploration before implementing.

## Privacy — DO NOT upload personal/private data to GitHub

`Pr0zak/pitstop` is public. Never commit:
- Fuelio CSV exports or fillup data with real GPS / station / cost
- Real PID profiles containing VIN / plate / owner info (ship the generic profile only)
- `.env`, tokens, keystores (except the committed debug keystore — see CI signing notes), DB dumps, photos
- Internal infra (CT IDs, LAN IPs, hostnames, the user's name/email)

The `.gitignore` blocks `data/`, `*.csv` (except the synthetic test fixture), `*.zip`, `.env*`, `*.jks`, `keystore.properties`, `**/private/`, `pid_profiles/private-*.json`. The `pitstop-release` skill runs a grep audit before tagging — pause if it finds anything.

The web **HondaLink connection test** (`/hondalink-test`, backend `POST /hondalink/test`) takes the user's HondaLink email + password, runs one read-only login-and-dashboard probe against Honda's servers, and stores nothing: no DB row, no log of the password, VIN redacted to the last four. It is a diagnostic, not an integration, and uses the unofficial app API. If it ever grows into a real feed, Smartcar is the sanctioned route for a 2019 Pilot.

When demonstrating with sample data, write it to `data/` (gitignored) or `/tmp/`. When pasting examples into docs, redact (lat/lon to 2 decimals, no real station names, no VIN).

## Build phase

Phase A + B + C complete + post-v1 work landed (bridge payload v2, post-processed trip derivation, phone presence detection, WiCAN broker subscription, layered engine-state detection). Tags shipped through v0.1.123. Recent landmarks: Android History detail screens with MapLibre routes (v0.1.113), on-disk drive payloads + queue unstall (v0.1.111), Home dashboard overhaul + active-DTCs panel (v0.1.116), manual-sync mode + sync-reminder notification (v0.1.120), trip-split fix + BLE-lost watchdog (v0.1.121), trip-detail Smooth chip phone+web (v0.1.121), fuel-level hero card + dark-default trip-detail map + WiCAN PID 0x68 firmware-bug workaround for Pilot V6 IAT (v0.1.123 — see ADR-018). See git log for the running history; ADRs in [docs/decisions.md](./docs/decisions.md).

## Repo

GitHub: [github.com/Pr0zak/pitstop](https://github.com/Pr0zak/pitstop) (public). GHCR images public (no PAT needed for CT pull).
