# pitstop — Pending Work

Generated 2026-05-24 after shipping **v0.1.178** + writing **ADR-019** (radio coexistence + hybrid architecture). The morning's "Path A primary" pivot is overturned — empirical test proved Beta-06 supports concurrent BLE + WiFi-station. See [`docs/decisions.md#adr-019`](./docs/decisions.md).

Numeric task IDs are session-scoped. Use **mnemonics** below as the durable identifiers. To rehydrate in a new session: open Claude Code in the pitstop repo and say `rehydrate from TODO.md + memory`.

The original Phase A/B/C build plan (tasks #1–#29) all shipped between v0.1.0 and v0.1.123 and is no longer carried here. See `git log` + [`docs/decisions.md`](./docs/decisions.md) for the running history.

---

## Architecture — current state

**Hybrid (ADR-019).** Two capture paths run simultaneously on WiCAN firmware ≥ v4.49p_beta-06:

- **Phone bridge** — Kotlin app polls OBD over BLE, ships via MQTT and caches every frame to `PendingDrive` payload (ADR-016). Adds GPS track. Offline-safe primary.
- **WiCAN tunnel** — `protocol: auto_pid` + WiFi-station to `<car-hotspot-ssid>` → WireGuard → mosquitto. Phone-free fallback for passenger drives / dead phone / BLE flaps.

Backend dedupes via `pid_readings` PK `(vehicle_id, metric, time)`. No application-level dedup logic needed; the schema handles it.

Confirmed working in the driveway 2026-05-24 — phone connected via BLE + recorded a drive WHILE WiCAN published at 1 Hz over WiFi to the broker, simultaneously.

---

## #HEALTH — `/health/ingest` masks dead pipes (1 task · backend, NEW)

Discovered during ARCH-2 today. The endpoint reports `last_message_at` from MQTT receipt time, which advances on every backend reconnect because retained messages re-deliver to the fresh subscriber. During WiCAN silence we saw `lag_s: 7s` while no new `pid_readings` rows had been inserted for 5 minutes. The pipe looked healthy when it wasn't.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| HEALTH-1 | Change `/health/ingest` to source from `SELECT max(time) FROM pid_readings WHERE time > now() - interval '5 min'` (or equivalent) so the timestamp reflects *new data*, not MQTT receipt. Optionally surface both: `last_received_at` (MQTT) + `last_new_row_at` (DB), with `lag_s` derived from the latter. | — |

---

## #BLE — Phone BLE link stability (2 tasks · permanent priority per ADR-019)

BLE-OBD is part of the long-term architecture, not a transitional fallback. GATT flap pattern is a chronic source of pain.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| BLE-2 | **Automated GATT-error recovery** in the phone bridge service: detect `status -5` / `0x85` patterns, attempt programmatic forget+re-pair via BluetoothAdapter APIs, fall back to a sticky notification telling the user to re-pair manually only after N retries fail. | — |
| BLE-3 | **BLE-OBD path health watchdog.** Surface time-since-last-OBD-frame in phone UI + publish to a `phone_health` MQTT topic so the backend can detect "phone says it's bridging but no frames" silently. | — |

---

## #FW — WiCAN firmware version smoke test (1 task · low-effort safety net)

| Mnemonic | Subject | Blocked by |
|---|---|---|
| FW-1 | Hybrid architecture depends on Beta-06's BLE+WiFi coexistence behavior. A regression in a future firmware bump would silently break Path A (BLE) while Path B (tunnel) keeps working. Add a quick post-update test: `curl /check_status`; if `ble_status: enable` AND `sta_status: Connected`, open phone app → confirm BLE-OBD frames flow → confirm WiCAN MQTT keeps publishing. ~5 min checklist documented somewhere stable (CLAUDE.md or `docs/wican-config.md`). | — |

---

## #ARCH — Architecture decisions (closed today)

| Mnemonic | Subject | Status |
|---|---|---|
| ARCH-1 | Write ADR-019 ("BLE-bridge primary, cellular tunnel as backup") | **REPLACED** — ADR-019 written but as a *hybrid* decision after empirical test reversed the original framing. See `docs/decisions.md#adr-019`. |
| ARCH-2 | Verify WiCAN's BLE-disconnect → WiFi auto-switch actually works | **RESOLVED differently** — empirical test showed BLE + WiFi are CONCURRENT in Beta-06, not just auto-switching. Hybrid coexistence is the actual finding. |

---

## #MQTT — Backend MQTT subscription stability (1 task · backend, lower-priority)

MQTT-1 shipped in v0.1.178. MQTT-2 remains; once HEALTH-1 lands we'll have a clearer signal of whether MQTT-2 is also fixed by v0.1.178's keepalive=60 + LWT debounce.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| MQTT-2 | Investigate why backend MQTT subscription drops every ~30s on local docker-bridge LAN to mosquitto. Look at aiomqtt 2.x internals around ping, mosquitto broker-side timeout, whether message handler blocks the ping coroutine (slow DB write?). Enable DEBUG logs on both ends. | HEALTH-1 |

---

## #VPN-VERIFY — Cellular tunnel isolation test (1 task · user-side)

Reframed once more: Path B is a real path in the architecture; this test verifies it works end-to-end without the phone in the picture.

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VPN-VERIFY-1 | Real drive with phone Bluetooth OFF / app killed. Verify: (a) WiCAN joins `<car-hotspot-ssid>` (primary back to `<car-hotspot-ssid>` first — see VPN-FOLLOW-RESWAP), (b) tunnel comes up + MQTT publishes flow throughout drive, (c) trip seals from WiCAN-only data (no GPS expected — that's fine for backup-path semantics). | VPN-FOLLOW-RESWAP |

---

## #VPN-FOLLOW — Cellular VPN follow-ups (4 tasks)

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VPN-FOLLOW-RESWAP | Today we swapped WiCAN's primary SSID back to **`<home-wifi-ssid>`** (home WiFi) for at-home reachability during ARCH-2 testing. Before VPN-VERIFY-1, swap back: primary = `<car-hotspot-ssid>`, fallback[0] = `<home-wifi-ssid>`. | — |
| VPN-2 | **UniFi firewall: LAN → VPN reverse routing.** Add a UCG rule allowing LAN (`10.0.0.0/24`) clients to reach VPN-server subnet (`192.168.5.0/24`) so WiCAN's web UI is reachable through the tunnel from anywhere on LAN. | — |
| VPN-4 | **Key rotation.** UCG WG server private key, WiCAN client WG private key, phone hotspot password — all in conversation context. Also the MQTT broker password (today). Rotate all four. | — |
| ~~VPN-3~~ | ~~Phase-out plan for phone BLE-OBD bridge.~~ **STAYS CANCELLED** per ADR-019 — BLE is permanent. | — |

---

## #VERIFY — End-to-end primary-path drive (1 task · user-side, carried)

After installing v0.1.177+178 phone APK:

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VERIFY-1 | Real drive with phone BLE ON (the normal hybrid path): (a) bridge auto-starts via InCarDetector signal, (b) GPS captures + uploads via drive payload, (c) MAF lands via BLE-OBD bridge, (d) trip seals as one continuous drive (LWT debounce v0.1.178), (e) fuel hero gauge renders cleanly (v0.1.177 fix), (f) in-app upgrade dialog Download button is no longer covered by snackbar (v0.1.174). | — |

---

## #WICAN — WiCAN-side AutoPID polish (1 task · low-priority)

| Mnemonic | Subject | Blocked by |
|---|---|---|
| WICAN-1 | Reduce WiCAN's `A6-Odometer` period from 5000ms → ≤30000ms via `/wican-config --period A6-Odometer=30000 --apply --reboot`. Mostly matters for the tunnel-only/phone-free path (phone's own 0xA6 poll handles odometer in the hybrid case). | — |

---

## Recently closed (this session, v0.1.172 → v0.1.178 + ADR-019)

**Backend / web**
- **v0.1.172** — Fuel-level fallback sanity cap. Implied L/km > 0.40 → NULL.
- **v0.1.173** — Backend MQTT subscriber watchdog (`asyncio.wait_for(timeout=90s)`).
- **v0.1.177** — Web fuel-gauge SVG arc fix. Hard-coded `large-arc-flag=0`.
- **v0.1.178** — MQTT keepalive=60 + wican_lwt flap debounce (MQTT-1 + LWT-1). Reverted v0.1.173's aggressive keepalive=30; added per-vehicle LWT state tracking to suppress duplicate-state events and < 30s state-flip flaps.

**Phone**
- **v0.1.173** — BridgeService split into independent BLE/GPS toggles, runtime-switchable.
- **v0.1.174** — In-app upgrade dialog snackbar overlap fix.
- **v0.1.175** — InCarDetector + multi-signal auto-trigger (SSID + AA + paired-car BT).
- **v0.1.176** — Activity Recognition as 4th in-car signal (opt-in).
- **v0.1.177** — GPS-only mode capture fix; engine-state merge across BLE + InCarDetector hint.

**WiCAN device-side (not in repo)**
- WireGuard VPN config installed → UCG One-Click VPN server (proven 2026-05-23, re-proven 2026-05-24 with `vpn_status: connected` on `<home-wifi-ssid>` too).
- Primary SSID currently **`<home-wifi-ssid>`** (was `<car-hotspot-ssid>`; swapped 2026-05-24 for at-home reachability during ARCH-2). VPN-FOLLOW-RESWAP must swap back before cellular testing.
- BLE re-enabled 2026-05-24 (`ble_status: enable`) — confirmed coexists with WiFi station in Beta-06.

**Docs / architecture**
- **ADR-019** written 2026-05-24 — Hybrid capture architecture (BLE + WiFi concurrent, backend dedupes).
- `/wican-config` skill (from prior session) auto-backups + dry-runs std_pids changes.

---

## Discoveries persisted to memory

- **`project_pitstop_state.md`** — needs update for v0.1.178 + ADR-019 (hybrid architecture, BLE+WiFi coexistence in Beta-06).
- **`reference_wican_api.md`** — WiCAN HTTP API surface (correct password field is `sta_pass`, not `sta_password` — `/load_config` returns real plaintext passwords, not masked).
- **`project_pilot_dead_pids.md`** — PIDs the Pilot answers with constant 0.

## See also

- ADRs: [`docs/decisions.md`](./docs/decisions.md) — ADR-019 is the latest.
- Memory: project's `~/.claude/projects/.../memory/` directory — see `MEMORY.md` for the index.
