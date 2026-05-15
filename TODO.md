# pitstop — Pending Work

Generated 2026-05-15 after shipping **v0.1.134** (Timescale compression + IMU drop).

Numeric task IDs in the harness are session-scoped. Use the **mnemonics** below as durable identifiers. To resume, open Claude Code in `/home/spider/pitstop` and say `rehydrate from TODO.md` — it'll re-instantiate via `TaskCreate` with the right `blockedBy` edges.

The original Phase A/B/C build plan (tasks #1–#29, repo scaffold through HA plumbing) all shipped between v0.1.0 and v0.1.123 and is no longer carried in this file. See `git log` + [`docs/decisions.md`](./docs/decisions.md) for the running history and the ADRs that document the build.

---

## #VERIFY — phone install + new UX verification (1 task · user-side)

v0.1.126 → v0.1.133 stack into one APK. Install once on the phone and exercise each change in turn.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VERIFY-1 | Install v0.1.133 APK via in-app self-update; confirm (a) Settings → manual-sync toggle persists the moment it flips (`manual_sync_only=true` line shows up in the logs without a Save tap); (b) History → Sync now chip shows "Syncing…" → "Synced N drive(s)" → Idle; (c) launcher icon + Add-Fillup shortcut sit centered in the Pixel circular mask with no black above the dial; (d) Settings opens with Bridge / OBD / Broker / Connectivity expanded, Pitstop server / Vehicle / Display / Logs collapsed with chevrons, App at the bottom anchor; (e) first Sync-now tap after a real drive clears the queue without 20 s client-side timeouts; (f) BLE reconnects within ~60 s of ignition-on (or instantly when the WiCAN posts `can/status: online` on MQTT); (g) fuel widget updates within a drive instead of every 30 min; (h) History → long-press two trips → Merge collapses them into one. | — |

Code-side gates were exercised on debug build at `compile-clean` only — the only way to confirm the UX feels right is a real device install plus the next drive.

---

## Recently closed (last session)

- **DISK-3 — IMU writes + hard-event code removed (2026-05-15, v0.1.134)** — phone stopped registering linear-accel + gyro sensors and publishing accel_x/y/z + gyro_x/y/z to MQTT; DriveBuffer no longer collects ImuSample; LiveScreen Motion panel gone; TripDetailScreen Hard-events card gone. Backend dropped TripDetail.imu_events, the imu_event_rows CTE in get_trip, and the /analytics/hard-events endpoint. Vue trip-detail map markers + AnalyticsView hard-events card gone. DELETE FROM pid_readings WHERE metric IN ('accel_x'…'gyro_z') removed 496k rows.
- **DISK-2 — Timescale compression (2026-05-15, v0.1.134)** — Alembic 0015 enables compression on pid_readings (segmentby vehicle_id+metric), gps_points and engine_events (segmentby vehicle_id), all with compress_after = 7 days. Backfill compressed existing chunks immediately. **No retention policy** — multi-year history per the vehicle. Realistic sizing: ~25 MB/day after IMU drop → 5 yr ≈ 8–10 GB compressed, comfortably fits the 30 GB CT.
- **DISK-1 — CT docker cache cleanup (2026-05-15)** — `docker builder prune -af` + `docker image prune -af` freed 18 GB (CT root 24 GB → 5.8 GB used). Cleanup tail added to the `pitstop-deploy` skill so the cache doesn't compound across deploys.
- **MERGE-1 — Manual trip merge (2026-05-14, v0.1.133)** — `POST /trips/{trip_id}/merge` on backend merges two trips: earlier absorbs later, distance/fuel/dtc/idle sum, max-rpm/max-speed take the max, avg-speed/avg-coolant re-weight by duration, source flips to `manual_merge`. `trip_deriver.py` now treats `manual_merge` as a protected range (was just `phone_batch`) so re-derives don't split it back apart. Phone History → Trips long-press to enter selection mode; tap a second to enable Merge; selection bar at the top of the list shows status and a Cancel/Merge pair. CT 231 backend redeployed via rsync+rebuild (faster than waiting on GHCR pull).
- **WIDGET-2 — Unstick fuel widget on install (2026-05-14, v0.1.132)** — `MainActivity.onCreate` and `PitstopBridgeService` bridge-start both fire `FuelWidgetProvider.refreshWidgets` so the widget doesn't sit blank between Android's 30-min `updatePeriodMillis` ticks (especially after an APK install where the first onUpdate may fire while Hilt/network isn't ready). Widget now logs `fuel widget onUpdate`, `fuel widget fetch result {pct, sub}`, `fuel widget fetch threw` to `LogBuffer` for future diagnosis.
- **WIDGET-1 — Fuel widget refresh on samples (2026-05-14, v0.1.131)** — new `WidgetRefresher` singleton rate-limited to 1 refresh per 30 s, called from `PitstopBridgeService` BLE poll path and `WiCanSubscriber` MQTT path whenever a `fuel_level` metric is observed. `FuelWidgetProvider.refreshWidgets()` existed but had no callers; now it does.
- **BLE-1 — BLE backoff cap + MQTT wake (2026-05-14, v0.1.130)** — drop engine-off backoff cap 300 s → 60 s. `WiCanSubscriber` also subscribes to `wican/+/can/status`; on `{"status":"online"}` calls `BridgeStateBus.wakeUp()` which fires the new `wakeEvents` SharedFlow. Bridge service backoff sleep now merges `presence.inCar` with `wakeEvents` — whichever fires first breaks the sleep. Re-entering the car triggers a fast BLE retry without the Settings-toggle dance.
- **HTTP timeouts for drive uploads (2026-05-14, v0.1.129)** — `AppModule` read+write timeouts 20 s/10 s → 60 s/60 s. Multi-MB drive payloads (20k–25k frames) were tripping the 20 s read; server completed the upload but client gave up and the user saw "Sync failed" / partial drains. Retry came back as `duplicate=true` instantly, proving the prior attempt had landed.
- **CI-1 — Node 24 actions bump (2026-05-14)** — every workflow action that emitted a deprecation warning on the v0.1.127 build is now pinned to a Node-24 major: `actions/checkout@v6`, `actions/setup-java@v5`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v5` (NOT v6 — caching component split out into a commercial library there), `softprops/action-gh-release@v3`, `docker/setup-buildx-action@v4`, `docker/login-action@v4`, `docker/metadata-action@v6`, `docker/build-push-action@v7`.
- **SETTINGS-1 — Collapsible Settings sections (2026-05-14, v0.1.128)** — `SettingsSection` gained `collapsible` + `initiallyExpanded` params; expanded state in `rememberSaveable(title)`. Top (expanded): Bridge service / OBD device / MQTT broker / Connectivity. Collapsed by default: Pitstop server / Vehicle / Display / Logs. Bottom anchor: App.
- **Icon optical centering (2026-05-14, v0.1.127)** — adaptive-icon foregrounds shifted up: launcher 1.18 → 4.18 dp, Add-Fillup 2.2 → 5.2 dp (`translateY` 13.0→10.0 and 7.0→4.0). Both still fit inside the 36 dp mask radius and the 21–87 dp safe zone.
- **Manual-sync toggle save-race (2026-05-14, v0.1.126)** — focused `SettingsRepository.setManualSyncOnly(value)` setter wired through `ConfigViewModel.setManualSyncOnly`; toggle now auto-persists, no Save tap needed.
- **Sync-now silent UX (2026-05-14, v0.1.126)** — `HistoryViewModel.syncNow` runs `DriveUploader.drain` on `viewModelScope`, exposing a `SyncState` flow (Idle/InProgress/Done(uploaded, remaining)/Failed) that the chip + status line consume.
- **BLE auth flap (2026-05-14)** — phone hit GATT status 5 (`INSUFFICIENT_AUTHENTICATION`) on every connect attempt to the WiCAN; resolved by Phone Settings → Bluetooth → forget + re-pair. Not a code bug; the bond on one side had been cleared.

---

## Out-of-scope, won't-do (documented in memory)

- **Mode 22 PIDs on this 2019 Pilot Elite** (ATF temp, current gear, TPMS, i-VTM4 AWD torque split). Gateway-blocked. See [`project_wican_atf_pilot19.md`](../../.claude/projects/-home-spider-pitstop/memory/project_wican_atf_pilot19.md) — won't be revisited unless Honda publishes a firmware update opening Mode 22 on this trim, or the user buys hardware that bypasses the gateway.
- **Honda V6 IAT decoder bug in WiCAN firmware**. Worked around via custom Mode 01 PID 0168 with `B5-40` (see [`project_wican_iat_pilot19.md`](../../.claude/projects/-home-spider-pitstop/memory/project_wican_iat_pilot19.md)). If the WiCAN vendor ships a firmware fix later, the workaround stays — it doesn't conflict with a future correct std-PID decoder.

---

## Memory files relevant to this project

- `project_wican_atf_pilot19.md` — Mode 22 gateway-blocked, history of the bisect, the "verified 136.4°F" false positive
- `project_wican_iat_pilot19.md` — PID 0x68 firmware bug + custom-PID workaround
- `project_wican_wpa2.md` — `sta_security: "wpa2"` requirement for this user's router
- `feedback_phone_dto_type_check.md` — kotlinx-serialization strictness; curl-then-DTO discipline
- `feedback_phone_web_parity.md` — shared workflows must reach parity across clients
- `feedback_no_personal_data_to_github.md` — repo is public, never commit Fuelio/VIN/.env
- `reference_phone_debug_logs.md` — client_logs query recipe for triage
