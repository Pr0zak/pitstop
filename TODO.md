# pitstop — Pending Work

Generated 2026-05-21 by session-close after shipping **v0.1.156** (in-app web upgrade flow, end-to-end validated).

Numeric task IDs in the harness are session-scoped. Use the **mnemonics** below as durable identifiers. To resume, open Claude Code in `/home/spider/pitstop` and say `rehydrate from TODO.md` — it'll re-instantiate via `TaskCreate` with the right `blockedBy` edges.

The original Phase A/B/C build plan (tasks #1–#29, repo scaffold through HA plumbing) all shipped between v0.1.0 and v0.1.123 and is no longer carried in this file. See `git log` + [`docs/decisions.md`](./docs/decisions.md) for the running history and the ADRs that document the build.

---

## #VERIFY — phone install + UX verification on real device (1 task · user-side)

The whole v0.1.135 → v0.1.156 stack is in one APK. Install once and exercise each change in turn. The web side has already been verified via the in-app upgrade endpoint (v0.1.154 → v0.1.155 → v0.1.156 all flipped cleanly through `/admin/upgrade`).

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VERIFY-1 | Install latest APK via in-app self-update; spot-check: (a) **manual-sync beacon** — even with manual-sync ON, fuel widget + Overview hero card update at ~1/min, but no per-frame OBD floods to MQTT; (b) **OTA progress** — in-app update shows a real progress bar pulling the new APK; (c) **fuel widget WorkManager refresh** — value stays current after the launcher has sat idle (Doze) for hours; (d) **NO DATA logging** — Status → DTCs / logs doesn't include endless `NO DATA` spam for Honda-unsupported PIDs; (e) **offline cache** — open Trips with cellular off, recent ones still render; (f) **combined-trips heatmap** — Heatmap tab on phone renders the same polyline overlay style as the web heatmap; (g) **BLE-lost watchdog** — drives without OBD now seal at ~3 min idle instead of 15; (h) **GPS bypasses manual-sync** — drives still get GPS even in manual-sync mode; (i) **pull-to-refresh** — pull down on History / Home / Trips reloads; (j) **trip + fillup grouping** — History tabs show date-bucketed sections + sort/filter chips; (k) **fuel gauge** — Status Home shows the analog arc-gauge fuel card; (l) verify the **fuel gauge** also looks right on the web Overview. | — |

Code-side gates passed compile-clean + the web upgrade flow was validated end-to-end against a running CT. The remaining unknown is real-device behavior.

---

## Recently closed (this session, v0.1.134 → v0.1.156)

- **UPDATE-2/3/4/5 — In-app web upgrade flow (Zonik-style) (2026-05-20/21, v0.1.153–156)** — `GET /admin/updates` compares running PITSTOP_VERSION to GitHub /releases/latest (60s cache). `POST /admin/upgrade` spawns a detached `docker:27-cli` sidecar via mounted /var/run/docker.sock that runs `deploy/upgrade.sh` against the CT host daemon — survives the backend's own restart. Sidebar update-badge becomes a button; new Settings → About card has a "Check for updates" button. UpdateModal polls /version every 3s until target tag appears, then prompts page reload. Two fixes shipped during validation: v0.1.155 strips `v` prefix to match GHCR semver tags + self-heals stale "running" job state; v0.1.156 pins `COMPOSE_PROJECT_NAME=pitstop` so the sidecar updates real `pitstop-*` containers instead of phantom `work-*` ones. CT 231 migrated to image-pull deploy in the process (`BACKEND_TAG=v0.1.X` / `FRONTEND_TAG=v0.1.X` in `.env`).
- **GAUGE-1/2 — Fuel hero card as analog arc gauge (2026-05-20, v0.1.151–152)** — phone + web Overview Fuel level card replaced with a 180° SVG/Canvas arc gauge: tick marks at E/¼/½/¾/F, color-coded fill (red <15%, amber <35%, green ≥35%), border tinted to the same accent, big mono % on the arc baseline. Web LiveView's tile-grid fuel display deliberately left alone.
- **FILLUPS-1/2 — Group fillups by date + sort/filter (2026-05-20, v0.1.150)** — mirrors the trips treatment. Phone gets a chip row (All / Full / Partial) + sort dropdown (Recent / Cost / Volume / MPG / $/gal) above sticky-header date sections; web FuelView splits the single table into per-bucket cards keeping its existing column-header click sort.
- **TRIPS-1/2 — Group trips by date + sort/filter (2026-05-20, v0.1.149)** — both clients group trips into Today / Yesterday / Past 7 / Past 30 / This year / Older with sticky headers; phone adds source filter (Phone / Manual merge / Other) + sort by Recent / Furthest / Fastest / Longest.
- **UX-1/2 — Pull-to-refresh + web update badge (2026-05-20, v0.1.148)** — phone History / Home / Trips lists wrap in `PullToRefreshBox`. Web sidebar shows a `↑ vX.Y.Z` badge when `/version` < latest GitHub release (this was the seed of the bigger in-app upgrade work shipped above).
- **OBD-3 — GPS bypasses manual-sync gate (2026-05-20, v0.1.147)** — `location` added to `MANUAL_MODE_BEACON_METRICS` alongside `fuel_level`. Trips taken in manual-sync mode no longer go GPS-less.
- **OBD-2 — BLE-lost watchdog 15 min → 3 min (2026-05-20, v0.1.146)** — drives that end with a permanent BLE drop are now sealed quickly so they show up in History without the user waiting.
- **MAP-1/2/3 — Combined trips heatmap (2026-05-19/20, v0.1.141–145)** — backend endpoint streams trip route points for a date range; web view renders them via MapLibre using the trip-detail polyline approach (not circles); phone parity port via MapLibre Android. The mode-toggle bug where `setStyle({diff:true})` kept the GeoJSON source but wiped layers was diagnosed and fixed by re-adding layers in the `style.load` callback.
- **DERIVE-1 — Trip derivation from wican-only PID windows (2026-05-19)** — confirmed NOT a bug: wican-only data with vehicle_speed=0 correctly skipped as not-a-trip; the sparse 32 km/h readings the user saw were pass-by glimpses on the property.
- **CACHE-1 — OkHttp offline cache (2026-05-19, v0.1.140)** — phone HTTP client now has a 32 MB disk cache plus a two-interceptor offline-fallback: network interceptor rewrites `Cache-Control: max-age=120, stale-while-revalidate=600`; application interceptor returns `only-if-cached, max-stale=86400` when offline.
- **OBD-1 — Drop Honda-unsupported PIDs + log NO DATA (2026-05-19, v0.1.139)** — `pid_profiles/honda-pilot19.json` no longer asks for 0x14/16/17/18 (O2 sensors) or 0x1F (engine-run-time). `NO DATA` responses now flow to `client_logs` at INFO so future drift is visible.
- **WIDGET-5 — Periodic WorkManager fuel refresh (2026-05-18, v0.1.138)** — a `PeriodicWorkRequest` runs every 30 min via WorkManager (which Android honors even in Doze) and triggers `FuelWidgetProvider.refreshWidgets`, surviving Doze-deferred `updatePeriodMillis`.
- **UPDATE-1 — In-app OTA progress bar (2026-05-18, v0.1.137)** — `OtaUpdater` now streams `downloaded / total bytes` to a `StateFlow`; the settings download card renders a determinate `LinearProgressIndicator` instead of a spinner.
- **WIDGET-4 — Hero fuel card prefers local in-process metric (2026-05-18, v0.1.136)** — when `BridgeStateBus.fuelLevelPct` is fresher than the `/vehicles` `latest.fuel_level.time`, the hero card uses the local sample. Fixes the lag where manual-sync mode would suppress publishes and the hero card showed stale data.
- **WIDGET-3 — Manual-sync fuel beacon (2026-05-18, v0.1.135)** — even when manual-sync is on, the phone publishes `fuel_level` at most once per 60s (and `location` per OBD-3) so the widget + hero card stay current. Allowlist is `MANUAL_MODE_BEACON_METRICS = {"fuel_level", "location"}`.

---

## See also

- ADRs: [`docs/decisions.md`](./docs/decisions.md) — pending after this session: ADR for the in-app upgrade flow (sidecar + bind mount + project name pin).
- Memory: `~/.claude/projects/-home-spider-pitstop/memory/` — no `project_pitstop_state.md` exists; the session-close skill recommends creating one but defers to the user.
