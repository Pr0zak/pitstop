# pitstop — task list

18 tasks, three phases. See [PLAN.md](./PLAN.md) for context.

Mirror in the harness task system (`TaskList`) so they stay in sync.

## Phase A — Data plane

- [ ] **#1** Scaffold repo: `/home/spider/pitstop/` + Compose + git + `Pr0zak/pitstop` GitHub + GHCR
- [ ] **#2** Core schema: vehicles (UUID PK, `fuelio_guid` UNIQUE, nullable VIN), pid_profiles (JSONB matching WiCAN format), pid_readings hypertable, trips, dtc_events, vehicle_state, settings, lookup tables (units, fuel types, tank types). Continuous aggregates per-vehicle.
- [ ] **#14** Fuel/expense schema: fillups (with weather, partial flag, multi-tank, station nullable), expenses (with reminder fields), expense_categories, pictures (orphan rows), trip_categories.
- [ ] **#12** Curate `pid_profiles/honda-pilot-2019.json` — standard OBD-II PIDs + Honda Mode 22 (ATF temp `2201` formula `AA*9/5-40`, gear, TPMS). WiCAN-uploadable + DB-seedable.
- [ ] **#3** MQTT subscriber: `wican/+/+` (driveway) + `bridge/+/+` (phone). Profile-driven parsing. Source tag per reading.
- [ ] **#4** Trip detector worker: opens on first message after >2min silence, closes on 60s silence (or voltage drop). Per-trip stats.
- [ ] **#15** Fuelio importer: zip-aware, multi-vehicle, multi-section CSV parser. Idempotent on `fuelio_guid` with `lastupdated` last-write-wins. Dry-run preview.

## Phase B — UI + native app

- [ ] **#5** REST + WebSocket API: vehicles CRUD, trips, live WS, dtcs, profiles, settings (incl. dormant HA block).
- [ ] **#16** Fuel API + analytics: fillups CRUD, recomputed MPG, $/mi, monthly spend, station clustering, trip-correlated MPG, reminders endpoint.
- [ ] **#6** Frontend shell: Vite+Vue3+Pinia. Sidebar nav (Overview, Live, Trips, Analytics, Fuel, Maintenance, DTCs, Vehicles, Profiles, Settings). Vehicle picker in header. Dark theme matching myvitals.
- [ ] **#7** Live view with real-time gauges (WS-driven).
- [ ] **#8** Trips list + detail: uPlot timeline (speed/RPM/coolant overlays), MapLibre route map when GPS available.
- [ ] **#9** Engine analytics: fuel economy trends, driving-habit histograms, engine health, DTC history.
- [ ] **#17** Fuel UI: fillups list + add form (use-current-location, partial flag), stations map, stats panel (MPG, $/mi, monthly spend, OBD-MPG vs fillup-MPG overlay), import page with multi-zip upload + dry-run preview.
- [ ] **#18** Maintenance reminders: surface Fuelio's `RemindOdo`/`RepeatOdo`/`RepeatMonths`. Sidebar entry with overdue/upcoming list. Mark-done flips reminder forward.
- [ ] **#10** Phone app: BLE bridge service + config UI (Kotlin/Compose, foreground service, MQTT publish to bridge/* over LAN via subnet router).
- [ ] **#28** Phone app: status + live data view (service health, live gauges, deep-link to web UI).
- [ ] **#29** Phone app: fuel quick-add with auto-GPS (mobile-native fillup entry).

## Phase C — Deploy + HA

- [ ] **#11** Deploy: orchestrate (runs sub-tasks #19–#27 per docs/deployment.md).
- [ ] **#19** Deploy: pre-flight host validator (deploy/preflight.sh).
- [ ] **#20** Deploy: ct-bootstrap.sh (inside-CT setup).
- [ ] **#21** Deploy: provision + secrets script (deploy/provision.sh).
- [ ] **#22** Deploy: docker-compose healthchecks + LAN-only Mosquitto bind.
- [ ] **#23** Deploy: smoke + E2E test scripts (4 tiers).
- [ ] **#24** Deploy: test fixtures (synthetic CSV + MQTT publisher).
- [ ] **#25** Deploy: disk monitoring (`/health/disk`, daily cron, 70% alert webhook, easy resize).
- [ ] **#26** Deploy: pihole DHCP reservation.
- [ ] **#27** Deploy: post-deploy report + WiCAN config doc.
- [ ] **#13** HA plumbing: settings UI + worker, gated on `ha_enabled` flag (default off). No HA traffic until flipped.
