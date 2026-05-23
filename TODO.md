# pitstop — Pending Work

Generated 2026-05-23 by session-close after shipping **v0.1.172** (sanity-cap fuel-level fallback to discard impossible MPG; paired with device-side WiCAN poll-list fix enabling Mode 01 PID 0x10 MAF) **and** the post-release cellular-VPN architecture work (WiCAN now reaches mosquitto via WireGuard tunnel through the UCG-Ultra when on car WiFi — phone bridge no longer required for OBD capture).

Numeric task IDs in the harness are session-scoped. Use **mnemonics** below as the durable identifiers. To resume, open Claude Code in `/home/spider/pitstop` and say `rehydrate from TODO.md + memory`.

The original Phase A/B/C build plan (tasks #1–#29, repo scaffold through HA plumbing) all shipped between v0.1.0 and v0.1.123 and is no longer carried here. See `git log` + [`docs/decisions.md`](./docs/decisions.md) for the running history and ADRs.

---

## #VPN — Cellular tunnel architecture (4 tasks · live, partially proven)

**Status:** Architecture works end-to-end via WiCAN's `sta_ssid=MobileChicken` → cellular → DDNS `zak.port0.org:51820` → UCG-Ultra WireGuard server → tunnel `192.168.5.3 ↔ 10.0.0.83` → mosquitto. Verified by broker-side subscribe catching live publishes after WiCAN connected to phone hotspot in driveway.

**Key gotchas discovered today:**
- **SSID is case-sensitive.** WiCAN configured with `mobilechicken` (lowercase) never matched the actual `MobileChicken` SSID. Fixed.
- **`sta_fallbacks` triggers at boot/scan time, not mid-connection.** When WiCAN's home WiFi drops mid-drive, it stays in reconnect-loop on the same SSID. Switching networks requires a sleep/wake cycle (engine off > 5 min for voltage-triggered sleep).
- **`SmartConnect` wifi_mode in firmware 4.49 is buggy / unsupported despite being in the API.** UI hides the toggle. Pushing it via API bricked the device into AP-mode fallback. Avoid; stick to `Station` mode with primary + fallback.
- **`/store_auto_data` + `/store_config` race** — POSTing both then triggering one reboot via `/store_config` loses the auto_pid changes (the flash flush hadn't completed when reboot fired). Push auto_pid first with its own `/system_reboot`, then push general config separately.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VPN-1 | **Real drive test of cellular tunnel.** With hotspot ON before engine start, take a 5+ min drive away from home WiFi. Verify: (a) WiCAN's destination success_count ticks throughout drive, (b) pid_readings land with no >60s gaps, (c) trip seals correctly with sensible MAF coverage. | — |
| VPN-2 | **UniFi firewall rule: LAN → VPN reverse routing.** Today we couldn't reach the WiCAN at `192.168.5.3` from the LAN (`10.0.0.0/24`). Add a UCG firewall rule allowing LAN clients to reach VPN-server subnet `192.168.5.0/24` so we can curl the WiCAN's web UI through the tunnel for live diagnostics during a drive. | — |
| VPN-3 | **Phase-out plan for phone BLE-OBD bridge.** Once VPN-1 + #13 (GPS-only mode) both land, the phone bridge becomes redundant for OBD. Document the deprecation, add a feature flag in Settings to disable BLE-OBD by default for new installs, ADR for the architecture. | VPN-1, #13 |
| VPN-4 | **Key rotation.** During API discovery + recovery the UCG WG server private key, the WiCAN client WG private key, and the phone hotspot password all ended up in conversation context. Rotate via UniFi UI → regenerate One-Click VPN keys → re-push new client config to WiCAN. | — |

---

## #MAF — verify WiCAN MAF coverage survives a sleep/wake cycle (2 tasks · user-side first, then verify)

Today's fix added `10-MAFAirFlowRate` (Mode 01 PID 0x10) to the WiCAN's std_pids and disabled four always-zero PIDs (`9D-EngineFuelRate`, `51-FuelType`, `9E-EngineExhaustFlowRate`, `9F-FuelSystemPercentageUse`). MAF landed cleanly at 1 Hz from 09:04 → 09:24 (1,828 samples). Then the WiCAN went to sleep (engine-off > 5 min, voltage < 13.1 V), and two subsequent trips (09:37, 09:50) had **zero** MAF samples — even though RPM, speed, throttle, manifold pressure, etc. all landed fine.

Open question: does the WiCAN re-apply the new PID config on wake-from-sleep, or does it revert to a cached set that doesn't include `10-MAFAirFlowRate`?

| Mnemonic | Subject | Blocked by |
|---|---|---|
| MAF-1 | Drive the car for 2–3 min. Then immediately: `curl http://10.0.0.181/load_auto_pid` and confirm `10-MAFAirFlowRate` is still in std_pids with `enabled: true`. Then query the DB: any `maf_air_flow` samples in the trip's time window? | — |
| MAF-2 | **If MAF-1 shows the PID is gone or disabled**, two options: (a) disable WiCAN sleep entirely via `/store_config` with `sleep_status: disable` (costs a few mA standby drain), or (b) investigate the wake-from-sleep PID-restore path in WiCAN firmware. (a) is the pragmatic fix. **If MAF-1 shows MAF is back and integrating cleanly**, close this family. | MAF-1 |

Skills: `/wican-config --show` to inspect the live config; `/pitstop-status` for ingest health.

---

## #VERIFY — phone install + UX verification on real device (1 task · user-side, carried forward)

The whole v0.1.135 → v0.1.172 stack is in one APK. Phone was on v0.1.171 entering this session; the v0.1.172 backend release didn't change phone code, but the new APK is available.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VERIFY-1 | After a clean BLE link (see BLE-2), confirm on a real drive: (a) trip detail shows **Odo start / Odo end / Distance (odo Δ)** rows; (b) **Fuel level start → end** rows populate even when both sit near the calibration peak; (c) **Gas used (est.)** appears once tank drops below ~85 % raw (and now reads from real MAF integration, not slosh fallback); (d) Fuel hero gauge stays calm (p75 smoothing) through slosh dips; (e) Sync-now confirm dialog fires on cellular; (f) cache-on-5xx fallback shows stale data instead of error toast when backend hiccups. | BLE-2, MAF-1 |

---

## #BLE — Phone BLE link stability (1 task · user-side first, carried forward)

The phone's BLE GATT link to the WiCAN dongle has been flapping (`status -5` / `0x85 GATT ERROR` / `0x8 GATT CONN TIMEOUT`), the same flap pattern that hit on 2026-05-20 (resolved then by Bluetooth forget+re-pair). Until BLE works, the new v0.1.171 odometer poll never fires and trips upload as wican-only.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| BLE-2 | User-side: Bluetooth → Forget WiCAN → re-pair from pitstop Settings → Bridge. THEN take a 3+ min drive. THEN inspect new trip's odo_start_km / odo_end_km via the API. If the flap returns within a week, consider an automated "GATT error → forget + retry pair" recovery flow in the phone bridge service. | — |

---

## #WICAN — WiCAN-side AutoPID additions (1 task · user-side, carried forward)

The phone's PID 0xA6 poll (v0.1.171) covers odometer at trip cadence when BLE is healthy; the WiCAN's `A6-Odometer` entry is enabled but at 5000 ms (its own slow default). Adding it as a faster redundant path on the WiCAN would close the gap on phone-BLE outages.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| WICAN-1 | On the WiCAN web UI (or via `/wican-config --period A6-Odometer=30000 --apply --reboot`), reduce `A6-Odometer` to ≤30 s. Verify by tailing `wican/pilot19/pid` MQTT — should now publish `odometer` at the faster cadence. Low priority since phone already covers this, but cheap defense-in-depth. | — |

---

## #SD — Evaluate WiCAN SD buffer-and-sync as phone-bridge replacement (4 tasks · investigation)

**Idea:** WiCAN logs every drive to its onboard SD card (`logger_status` is currently `disable`, `log_storage: sdcard`, `log_filesystem: fatfs`). If the firmware supports buffer-on-publish-fail + replay-on-reconnect, we could remove the phone from the data-capture loop entirely — cellular trips would just buffer locally on the WiCAN and drain to MQTT when the car returns to home WiFi. Phone becomes pure UI + server polling. Eliminates the BLE flap class of bugs ([[BLE-2]]) and the phone-battery drain of an always-on BLE service.

**Known catches** before starting work:
- WiCAN-Pro has **no GPS** — phone provides GPS via the bridge today. Removing phone = no GPS = no route maps / GPS-haversine distance / station overlays on cellular trips. Either accept the regression or find a separate GPS source.
- **No live data during cellular drives** — drives only appear server-side after the car gets home. Trade real-time visibility for architectural simplicity.
- The firmware's SD logger may be designed for *offline extraction* (pull the card, parse CSV) rather than store-and-forward to MQTT. Unverified.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| SD-1 | Empirical test. Toggle `logger_status: enable` (probably via `/store_config`; verify the right endpoint — the `/wican-config` skill currently only edits `/store_auto_data`). Drive a short cellular trip with the phone bridge OFF (manual-sync mode is fine). Return to home WiFi, wait 2 min. Inspect: (a) `/wican/pilot19/pid` MQTT for any backfilled messages with old timestamps; (b) pull the SD card and read whatever files landed — note filename format, log shape, and whether timestamps are preserved. | — |
| SD-2 | Read `meatpiHQ/wican-fw` source for the publish-on-MQTT-failure path. Confirm or refute that the firmware buffers failed publishes and replays them on reconnect. Look for: queueing layer between AutoPID and the MQTT client, persistence of that queue across sleep/wake, and timestamp-replay semantics. | — |
| SD-3 | Design the integration. Two paths: (a) firmware handles replay natively → no pitstop backend changes, just enable logger + clean up phone bridge code. (b) Firmware doesn't replay → write a small driveway-sync helper (could run on the pitstop CT, polling the WiCAN's HTTP API for SD contents when the device shows up on LAN), and a backend endpoint to ingest the dumped log. Include the GPS-gap decision: accept loss, or carve out a phone-GPS-only path. | SD-1, SD-2 |
| SD-4 | Go/no-go on phasing out the phone bridge as primary capture path. Write an ADR documenting the architecture choice, the GPS tradeoff, and the migration plan (likely: parallel run for a few weeks, then deprecate). | SD-3 |

Skills: `/wican-config` for current PID config. The `/store_config` endpoint for the logger toggle isn't yet wrapped in a skill — SD-1 may surface a useful extension to `/wican-config`.

---

## Recently closed (this session, v0.1.171 → v0.1.172)

- **v0.1.172 — Fuel-level fallback sanity cap.** When MAF samples are missing inside a trip window, `compute_trip_stats` falls back to a `(start − end)` fuel_level delta. The fallback reads raw `value_num` (the recent smoothing only runs at API read time), so the Pilot's noisy 0.5 %-step sensor produced fuel_used_l of 4–25 L on 1–6 km drives — 0.3 to 3.6 MPG, poisoning every `/analytics/*` endpoint. New rule: if computed L/km > 0.40 (worse than ~5.9 MPG, looser than any plausible moving trip), discard to NULL. Existing 4 affected rows nulled manually via DB UPDATE.
- **WiCAN poll list cleanup (device-side, not in the repo).** Added `10-MAFAirFlowRate` (Mode 01 PID 0x10 MAF — the universal standard, not Mode 22 0x66 which intermittently gateway-blocks on Honda). Disabled `9D-EngineFuelRate` (constant 0 on Pilot), `51-FuelType` (constant 0), `9E-EngineExhaustFlowRate` (constant 0), `9F-FuelSystemPercentageUse` (constant 0). std_pids enabled count: 61 → 58.
- **New skill `/wican-config`.** Pull/edit/apply WiCAN auto_pid config over its unauthenticated HTTP API at `http://10.0.0.181`. Supports `--show`, `--enable`, `--disable`, `--add`, `--period`, `--apply`, `--reboot`. Backups to `~/.wican-backups/` before every write. See `~/.claude/skills/wican-config/SKILL.md`.
- **Skill update: `/stitch-design`.** Documented (a) the two flavors of `generate_screen_from_text` timeout (with-body vs pure), (b) `list_screens` eventual-consistency lag of 30–90s, (c) `/tmp/` is unsafe on WSL2 (use a project-local gitignored dir), (d) parallel-generates work but each can time out independently.
- **Stitch UI explorations (not in repo, exploratory).** Two Stitch projects: 14 mockups across Refined + Bold A/B for Home / Trip detail / Fillup / Analytics on both web and phone. Then a Driver's Seat round (amber-phosphor instrument cluster aesthetic, 2 screens). User found the first round flat, second round closer but no Vue port was made — purely design exploration. Mockups at `/home/spider/pitstop/.stitch-mockups/` (gitignored).

---

## Discoveries worth remembering

These are persisted to memory; this list exists for the next session to know what's there without re-deriving:

- **`reference_wican_api.md`** — full HTTP API surface for the WiCAN-Pro at 10.0.0.181 (load_auto_pid, store_auto_data, system_reboot, etc.).
- **`project_pilot_dead_pids.md`** — Pilot Elite V6's set of PIDs that respond but always return 0 (9D, 9E, 9F, 51) — so we don't re-add them.
- **`project_pitstop_state.md`** — current pitstop deploy state as of v0.1.172 (created this session per the recommendation in last session's TODO).

---

## See also

- ADRs: [`docs/decisions.md`](./docs/decisions.md). Pending: ADR for the fuel-level slosh-robust smoothing (p75 of recent samples) and a sibling ADR for the fuel_used_l sanity cap rationale.
- Memory: `~/.claude/projects/-home-spider-pitstop/memory/` — see `MEMORY.md` for the index.
