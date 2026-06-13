# pitstop — Pending Work

Generated 2026-06-13 by session-close after shipping **v0.1.185** (full-stack review pass: privacy, P0/P1 correctness, modernization, UI parity across backend/web/android). The 2026-06-13 session ran a 3-agent code review, fixed the findings, and validated the Android build on the emulator.

Numeric task IDs are session-scoped. Use **mnemonics** below as the durable identifiers. To rehydrate in a new session: open Claude Code in the pitstop repo and say `rehydrate from TODO.md + memory`.

The original Phase A/B/C build plan (tasks #1–#29) all shipped between v0.1.0 and v0.1.123 and is no longer carried here. See `git log` + [`docs/decisions.md`](./docs/decisions.md) for the running history.

---

## Architecture — current state

**Hybrid capture (ADR-019).** WiCAN runs `protocol: auto_pid` + WiFi-station to mosquitto AND phone bridges OBD over BLE to drive payload, concurrently on Beta-06 firmware. Backend dedupes via `pid_readings` PK.

**Hybrid fuel-level estimator (v0.1.179 → v0.1.183).** Per-vehicle estimate in liters, mutated by three operations: fillup-reset (HIGH), trip-decrement (MEDIUM), engine-quiet sensor-snap (HIGH). Snap is quarantined until a `trips.distance_km > 1.0` exists since the latest fillup (v0.1.183) and capped at 25% tank-drop per tick. Web + phone + widget all read `fuel_level_estimate_l`.

---

## #FUEL — Hybrid estimator follow-ups (3 tasks · phase 3)

Phase 1+2 shipped in v0.1.179/180; v0.1.181/182/183 hardened it against TZ, unit-conversion, and post-fillup-sensor-lag edge cases.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| FUEL-DECREMENT-NULL | **Investigate why `trips.fuel_used_l` is NULL on recent trips.** Observed 2026-06-05: 4 of 5 trips since the 06-03 fillup have NULL fuel_used_l, 1 has 0. Decrement_pass silently skips, estimate hasn't moved. Look at `compute_trip_stats` in `trip_detector.py` — MAF integration may be receiving sparse data (MQTT-2 ~90s subscription drops?), or the integration may be bailing out on a precondition (no MAF readings? speed always 0?). Consider also marking NULL trips as applied so they don't re-evaluate forever. | — |
| TANK-CAP-UI | UI to edit `tank_capacity_l` per vehicle in Settings (currently defaults to 80 L via migration 0017). Pilot's real capacity is 81 L so default is fine; matters when other vehicles get added. | — |
| FUEL-CONFIDENCE-UI | Surface estimate "freshness" in UI: if `fuel_level_estimate_updated_at` is > 7 days old, show a "stale" badge. Today the estimate just shows as a value with no confidence indicator. | — |

---

## #HEALTH — `/health/ingest` misleading (1 task · backend)

Endpoint advances `last_message_at` on every backend reconnect via retained-message redelivery — looks healthy even when no new data is flowing. Reduced impact after v0.1.179's retain-skip fix (no more spurious row writes), but the endpoint itself still lies.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| HEALTH-1 | Change `/health/ingest` to source from `SELECT max(time) FROM pid_readings WHERE time > now() - interval '5 min'` (or surface both: `last_received_at` MQTT + `last_new_row_at` DB, with `lag_s` derived from the latter). | — |

---

## #MQTT — Backend MQTT subscription instability (1 task · backend)

MQTT-1 (keepalive=60) + LWT-1 (flap debounce) shipped in v0.1.178. Retain-skip in v0.1.179 stopped the worst symptom (fake row inserts). The underlying ~90s subscription drop pattern is still happening (warnings in backend log) but no longer poisoning the time series. May be contributing to FUEL-DECREMENT-NULL by causing MAF data gaps.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| MQTT-2 | Investigate why backend MQTT subscription drops every ~90s even on local docker-bridge LAN to mosquitto. Look at aiomqtt 2.x internals, mosquitto broker-side timeout, slow DB writes blocking the ping coroutine. | — |

---

## #BLE — Phone BLE link stability (2 tasks · permanent priority per ADR-019)

BLE-OBD is permanent part of the architecture, not a transitional fallback.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| BLE-2 | **Automated GATT-error recovery** in the phone bridge service: detect `status -5` / `0x85`, attempt programmatic forget+re-pair via BluetoothAdapter APIs, fall back to a sticky notification after N retries. | — |
| BLE-3 | **BLE-OBD path health watchdog.** Surface time-since-last-OBD-frame in phone UI + publish to a `phone_health` MQTT topic so backend can detect "phone says it's bridging but no frames" silently. | — |

---

## #FW — WiCAN firmware bump smoke test (1 task)

| Mnemonic | Subject | Blocked by |
|---|---|---|
| FW-1 | Hybrid architecture depends on Beta-06's BLE+WiFi coexistence behavior. Add a quick post-update test: `curl /check_status`; if both `ble_status: enable` AND `sta_status: Connected`, open phone app → confirm BLE-OBD frames flow AND WiCAN MQTT keeps publishing. ~5 min checklist; document in CLAUDE.md or `docs/wican-config.md`. | — |

---

## #VPN-VERIFY — Cellular tunnel isolation test (1 task · user-side)

Path B (tunnel) end-to-end test, no phone in the picture.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VPN-VERIFY-1 | Real drive with phone Bluetooth OFF / app killed. Verify: (a) WiCAN joins the car hotspot, (b) tunnel up + MQTT publishes flow throughout drive, (c) trip seals from WiCAN-only data (no GPS expected — backup path). | VPN-FOLLOW-RESWAP |

---

## #VPN-FOLLOW — Cellular VPN follow-ups (3 tasks)

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VPN-FOLLOW-RESWAP | WiCAN's primary SSID was swapped to home WiFi for at-home testing on 2026-05-24. Before VPN-VERIFY-1, swap back: primary = car hotspot, fallback[0] = home WiFi. | — |
| VPN-2 | **UniFi firewall: LAN → VPN reverse routing.** Add a UCG rule allowing LAN clients to reach VPN-server subnet so WiCAN's web UI is reachable through the tunnel from anywhere on LAN. | — |
| VPN-4 | **Key rotation.** Secrets that ended up in conversation context across the 2026-05-23/24 sessions: UCG WG server private key, WiCAN client WG private key, hotspot password, **MQTT broker password** (`pitstop` user), **INGEST_TOKEN**. Rotate all five. | — |

---

## #VERIFY — Primary-path drive verification (1 task · user-side)

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VERIFY-1 | Spot-check on a normal drive with phone BLE ON (primary hybrid path): (a) bridge auto-starts via InCarDetector, (b) GPS captures + uploads via drive payload, (c) MAF lands via BLE-OBD, (d) trip seals as one continuous drive, (e) hero card on web + phone agree on fuel level. | — |

---

## #WICAN — WiCAN-side AutoPID polish (1 task · backup-path only)

| Mnemonic | Subject | Blocked by |
|---|---|---|
| WICAN-1 | Reduce WiCAN's `A6-Odometer` period from 5000ms → ≤30000ms via `/wican-config --period A6-Odometer=30000 --apply --reboot`. Backup-path only (phone handles odometer in primary path). | — |

---

## Recently closed (2026-06-13 session, v0.1.185)

**Full-stack review pass (3-agent review → fix → emulator-validated). Highlights:**
- **PRIVACY** — removed the hardcoded `MobileChicken` hotspot SSID default from the Android app (was shipping in the public repo; defaults now empty). Working tree is grep-clean; **git-history scrub still pending** (deferred by choice).
- **MQTT-2 closed** — the ~95s "subscription drop" was pitstop's own silence watchdog; replaced with an active `pitstop/_probe` loopback heartbeat. Log spam gone.
- **FUEL-DECREMENT-NULL closed** — P75-windowed de-slosh of the fuel-level fallback + EPA-distance decrement fallback (LOW confidence) so the estimate keeps moving without MAF; stale NULL trips terminal-stamped. New P1 found+fixed: `decrement_pass` lost-update now uses a real `SELECT … FOR UPDATE`.
- **HEALTH-1 closed** — `last_new_row_at` + `last_received_at` surfaced; retain-skip covers all sources.
- **Android P0s** — FGS location-type crash guard; ADR-017 watchdogs repointed to a dedicated `lastObdFrameAtMs` (GPS/WiCAN frames no longer defeat them).
- **BLE-3 (partial)** — `bridge/<slug>/phone_health` beacon (phone) + subscriber (backend) + OBD-freshness pill/row in the app. **BLE-2 still open.**
- **Web P1s** — FillupModal TZ corruption, dashboard distance regression, spend KPI window; plus canvas color, chart-zoom, date-filter TZ, temp C/F, numeric price sort.
- **TANK-CAP-UI (web) + FUEL-CONFIDENCE-UI (web+phone) closed.** TANK-CAP phone parity deferred (phone has no vehicle-editor screen / no `PATCH /vehicles/{id}` yet).
- **Modernization** — targetSdk 35, force-dark theme (fixed broken light stub), R8 minify (release 70→47MB), retired `TripDetector` deleted, `uv.lock` pin, constant-time auth, eslint+prettier+vitest.

**Still needing a real drive / hardware:** PID 0x10 (MAF) added to the phone poll list in code but unconfirmed on the Pilot PCM (EPA fallback covers either way) → folds into FUEL-DECREMENT-NULL verification + VERIFY-1. BLE-2, VPN-VERIFY-1, VPN-4 key rotation, WICAN-1 unchanged.

## Recently closed (2026-06-07 session, v0.1.184)

**Web + Phone parity**
- **v0.1.184 — Trip merge in web + delete on phone & web.** Backend's `DELETE /trips/{id}` and `POST /trips/{id}/merge` are now reachable from both clients. Web: "Select" toolbar button → tap-to-select → action bar with Merge (exactly 2) / Delete (any) / Cancel + confirm modal. Phone: long-press → `ModalBottomSheet` (Select for merge / Delete) → confirm dialog. APIs added: `PitstopApi.deleteTrip()`, `endpoints.ts.mergeTrips()`.

## Recently closed (2026-06-05 session, v0.1.181 → v0.1.183)

**Backend / web**
- **v0.1.182** — Fillup partial-fill unit conversion (`pumped_l = raw * 3.78541` when fuel_unit=1) + snap_pass post-fillup sample gate + odometer `_refresh_latest_odo` sanity filter (was `max()` getting poisoned by a 10.4M km bad CAN frame; switched to ORDER BY time DESC + < 1M km filter; deleted 262 corrupted rows).
- **v0.1.183** — snap_pass redesign: **trip-since-fillup quarantine** (replaced 6h time quarantine; Honda's PID 0x2F stays stuck post-fillup until driven) + `MAX_SNAP_DROP_FRACTION = 0.25` safety cap to prevent sensor-lag from cratering the estimate in one tick.

**Phone**
- **v0.1.181** — `HistoryViewModel.bucketFor` TZ fix: `OffsetDateTime.parse().atZoneSameInstant(zone).toLocalDate()` (was rendering UTC date via `toInstant().toString().take(10)`, made a June 2 evening drive show in "Today" bucket).

**Prior arc (carried from previous session-close)**
- v0.1.172/173/174/175/176/177/178/179/180 — see git log + previous TODO.md revisions. Highlights: retain-skip MQTT fix, hybrid fuel-level estimator phase 1+2, snap_pass decoupled from engine_events, phone hero + widget parity.

---

## Discoveries persisted to memory

- **`project_pitstop_state.md`** — updated to v0.1.184. Includes hybrid estimator gotchas (quarantine, safety cap, parked-quiet threshold, partial-fillup unit conversion) and the v0.1.184 trip merge/delete parity surfaces.
- **`project_wican_ble_wifi_coexist.md`** — Beta-06 supports concurrent radios.
- **`feedback_health_ingest_misleading.md`** — `/health/ingest` masks dead pipes via retained-message redelivery.
- **`reference_wican_api.md`** — WiCAN HTTP API.
- **`project_pilot_dead_pids.md`** — PIDs the Pilot answers with constant 0.

## See also

- ADRs: [`docs/decisions.md`](./docs/decisions.md) — ADR-019 is the latest.
- Memory: project's `~/.claude/projects/.../memory/` directory — see `MEMORY.md` for the index.
