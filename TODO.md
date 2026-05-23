# pitstop — Pending Work

Generated 2026-05-22 by session-close after shipping **v0.1.171** (phone now polls Mode 01 PID 0xA6 odometer at 30s cadence for trip start/end).

Numeric task IDs in the harness are session-scoped. Use the **mnemonics** below as durable identifiers. To resume, open Claude Code in `/home/spider/pitstop` and say `rehydrate from TODO.md`.

The original Phase A/B/C build plan (tasks #1–#29, repo scaffold through HA plumbing) all shipped between v0.1.0 and v0.1.123 and is no longer carried here. See `git log` + [`docs/decisions.md`](./docs/decisions.md) for the running history and the ADRs.

---

## #VERIFY — phone install + UX verification on real device (1 task · user-side)

The whole v0.1.135 → v0.1.171 stack is in one APK. Phone is currently on v0.1.171 per the live logs.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VERIFY-1 | After a clean BLE link (see BLE-2), confirm on a real drive: (a) trip detail shows **Odo start / Odo end / Distance (odo Δ)** rows; (b) **Fuel level start → end** rows populate even when both sit near the calibration peak; (c) **Gas used (est.)** appears once tank drops below ~85 % raw; (d) Fuel hero gauge stays calm (p75 smoothing) through slosh dips; (e) Sync-now confirm dialog fires on cellular; (f) cache-on-5xx fallback shows stale data instead of error toast when backend hiccups. | BLE-2 |

---

## #BLE — Phone BLE link stability (1 task · in-progress, user-side first)

Throughout today the phone's BLE GATT link to the WiCAN dongle went into a tight error loop (`status -5` / `0x85 GATT ERROR` / `0x8 GATT CONN TIMEOUT`), the same flap pattern that hit on 2026-05-20 (resolved then by Bluetooth forget+re-pair). Until BLE works, the new v0.1.171 odometer poll never fires and trips upload as wican-only.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| BLE-2 | User-side: Bluetooth → Forget WiCAN → re-pair from pitstop Settings → Bridge. THEN take a 3+ min drive. THEN inspect new trip's odo_start_km / odo_end_km via the API. If the flap returns within a week, consider an automated "GATT error → forget + retry pair" recovery flow in the phone bridge service. | — |

---

## #WICAN — WiCAN-side PID config (1 task · user-side)

WiCAN's AutoPID list publishes ~30 metrics per drive (engine_load, ltft, rpm, etc.) but **doesn't include odometer** at trip cadence — its A6-Odometer publish fires roughly once a day. The phone's new PID 0xA6 poll (v0.1.171) covers this when BLE is healthy; add WiCAN as a redundant path so trips have odo even on phone-BLE outages.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| WICAN-1 | On the WiCAN web UI (driveway-only LAN access), add Mode 01 PID 0xA6 to the AutoPID poll list, name `odometer`, cadence 30s. Verify by tailing `wican/pilot19/pid` MQTT — should now publish `odometer` alongside the other metrics during a drive. | — |

---

## #OBD — MPG via MAF (1 task · planning)

OBD-5's fuel-level-delta MPG fallback shipped in v0.1.163, but Honda Pilot's 0x2F sensor is too noisy/non-linear at the top of the range — short trips on a near-full tank can't move it enough to register. Proper MPG needs `maf_air_flow` (PID 0x10), which Honda V6 PCM **doesn't** answer in the standard Mode 01 form (returns NO DATA — that's why we dropped it in OBD-1). WiCAN's `66-MAFSensorA/B` (custom Honda PIDs) do work; need to plumb those.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| OBD-6 | Backend: teach `compute_trip_stats` to ALSO integrate `66-MAFSensorA` (or whatever canonical alias) as a MAF source, not just `maf_air_flow`. Map the WiCAN-published Honda-extended MAF PIDs to the same fuel-used integration that already exists. Should unblock per-trip MPG without depending on the broken std-PID path. | — |

---

## Recently closed (this session, v0.1.156 → v0.1.171)

- **v0.1.171 — Phone polls PID 0xA6 odometer (30s).** Adds `Odometer` to the DEFAULT poll list with 4-byte parser (J1979 spec, 0.1 km units). Trip start/end will populate via phone bridge once BLE is healthy (BLE-2). Honda confirmed to answer the PID — WiCAN reads it correctly.
- **v0.1.170 — Trip detail 500 fix.** Unused `$2` SQL placeholder in the fuel_level_end query made asyncpg throw `IndeterminateDatatypeError` on every GET /trips/{id}. Rebound to $2=ended, dropped the unused param.
- **v0.1.169 — Fuel smoothing via 75th percentile.** Three iterations: median → median+widow → slew-rate-limited replay → final p75-of-recent. The p75 approach is simplest and most accurate for this sensor: high end of recent samples = closest to truth (fuel only goes down), robust to slosh dips below + spikes above. 60-min window primary, fallback to last 50 samples regardless of age when parked.
- **v0.1.168 — Median-smooth fuel_level (initial) + raw debug.** Then superseded by v0.1.169's p75.
- **v0.1.167 — Trip detail: odo start/end + fuel level start/end + gas used.** Added per-vehicle calibration-normalized fuel_level boundaries, surfaced fuel_used_l as gal. Phone + web both render rows when data exists.
- **v0.1.166 — Phone cache fallback on HTTP 5xx.** `OfflineCacheInterceptor` only caught IOException; 5xx propagated as HttpException to ViewModels. Now both paths route to FORCE_CACHE so stale-cached data shows instead of error toast.
- **v0.1.165 — Don't double-encode JSONB in drive_ingest.** v0.1.163's `_refresh_vehicle_state_from_drive` called json.dumps() on a dict before passing to asyncpg, which has a JSONB codec that also calls json.dumps(). Double-encoding → Postgres stored a JSON string → `||` concat against an existing object wrapped both as arrays → /vehicles 500. CASE on jsonb_typeof = 'object' added so any pre-existing array `latest` self-heals on next drive upload.
- **v0.1.164 — Cellular-data confirm dialog.** New NetworkMonitor classifies via NET_CAPABILITY_NOT_METERED. Sync-now on cellular opens an AlertDialog with the pending count; Wait for WiFi / Sync over cellular. Offline shows informational variant.
- **v0.1.163 — OBD-5 + post-drive vehicle_state refresh.** MPG fallback from fuel_level delta when MAF integration yields zero. /drives POST now also upserts vehicle_state.latest so the hero card reflects post-drive readings (manual-sync mode users no longer see frozen values).
- **v0.1.162 — Compose binds use absolute host paths.** Recurring mosquitto crash-loop fixed: the in-app upgrade sidecar's `docker compose up` from /work resolved `./mosquitto/config` to `/work/mosquitto/config` (host path doesn't exist), triggering recreate with a broken bind. Pinned both bind sources to `${PITSTOP_HOST_DIR:-.}/...` so they're stable regardless of caller's cwd.
- **v0.1.161 — Trip-detail 3×2 hero grid + heatmap gas-station overlay.** Hero stats card now has even column widths (Duration / Distance / MPG; Max speed / Max RPM / Avg speed). Heatmap gets a Stations FilterChip — overlays a cyan halo+dot at every fillup's GPS pair.
- **v0.1.160 — Surface fuel_level_calibration_pct on VehicleOut.** Pydantic was stripping the new column from the response; phone hero card local-override needed it.
- **v0.1.159 — Live-OBD odo prefill + per-vehicle fuel calibration.** Add Fillup form prefills `odo` from `vehicle.latest_odo_km` (web + phone). alembic 0016 adds `fuel_level_calibration_pct REAL DEFAULT 100`. POST /fillups with is_full=true captures highest raw fuel_level reading ±30 min → vehicle's calibration ceiling. /vehicles normalizes display so 100% = full tank.
- **v0.1.158 — Don't seal implausible drives; drop 4xx-rejected ones from queue.** v0.1.157's STOPPED handler caused a 3-frame 0-duration drive to seal and jam the upload queue. Added refuse-implausible-drives guard + 4xx auto-eviction.
- **v0.1.157 — OBD-4: recognize STOPPED/BUS ERROR/CAN ERROR as engine-off.** ELM327 echoes the request before the response (`010B\rSTOPPED`); the original startsWith check on the trimmed whole frame always failed. Now splits per line and matches against the ELM error-response table.

---

## Infrastructure note

The in-app upgrade flow (UPDATE-2 series) was used 8+ times this session — each ship was deployed via `/admin/upgrade?target=vX.Y.Z` rather than the laptop-side `pitstop-deploy` skill. v0.1.162's absolute-path fix was the load-bearing change; without it every sidecar invocation broke mosquitto. The flow is now stable.

---

## See also

- ADRs: [`docs/decisions.md`](./docs/decisions.md) — pending after this session: ADR for the slosh-robust fuel-level smoothing (p75 of recent samples vs OEM-style filter — explain the iteration path).
- Memory: `~/.claude/projects/-home-spider-pitstop/memory/` — still no `project_pitstop_state.md`. Session-close recommends one but defers to user.
