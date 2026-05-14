# pitstop — Pending Work

Live pending-work index. Last refreshed 2026-05-14 after shipping **v0.1.126**.

Numeric task IDs in the harness are session-scoped. Use the **mnemonics** below as durable identifiers. To resume, open Claude Code in `/home/spider/pitstop` and say `rehydrate from TODO.md` — it'll re-instantiate via `TaskCreate` with the right `blockedBy` edges.

The original Phase A/B/C build plan (tasks #1–#29, repo scaffold through HA plumbing) all shipped between v0.1.0 and v0.1.123 and is no longer carried in this file. See `git log` + [`docs/decisions.md`](./docs/decisions.md) for the running history and the ADRs that document the build.

---

## #CI — GitHub Actions Node version refresh (1 task · low-priority, deadline-driven)

CI workflows still use Node 20-based actions. GitHub forces Node 24 on June 2nd, 2026 and removes Node 20 entirely on September 16th, 2026. The v0.1.126 build emitted deprecation warnings for `actions/checkout@v4`, `actions/setup-java@v4`, `android-actions/setup-android@v3`, `gradle/actions/setup-gradle@v4`, `softprops/action-gh-release@v2`, plus the Docker action family (`docker/build-push-action@v6`, `docker/login-action@v3`, `docker/metadata-action@v5`, `docker/setup-buildx-action@v3`).

| Mnemonic | Subject | Blocked by |
|---|---|---|
| CI-1 | Bump GitHub Actions to Node 24-compatible versions in `.github/workflows/*.yml` | — |

Open the workflow files, check each action's latest version on its README, bump. No code change required.

---

## #VERIFY — v0.1.126 phone install + new UI verification (1 task · user-side)

v0.1.126 ships two phone-only behaviour changes that need a real install + tap to validate end-to-end:

| Mnemonic | Subject | Blocked by |
|---|---|---|
| VERIFY-1 | Install v0.1.126 APK via in-app self-update; confirm (a) Settings → manual-sync toggle persists the moment it flips (a `manual_sync_only=true` line shows up in the logs without a Save tap), and (b) History → Sync now chip flips to "Syncing…" with a spinner during the drain, then "Synced N drive(s)" briefly before returning to Idle | — |

Code-side gates were exercised on debug build at `compile-clean` only — the only way to confirm the UX feels right is a real device install.

---

## Recently closed (last session)

- **VERIFY-1/v0.1.123** — phone APK installed; the phone is currently running v0.1.125 build 227 per uploaded `client_logs`, so this task is implicitly superseded.
- **VERIFY-2/v0.1.123** — `intake_air_temp` shows plausible values via the WiCAN custom-PID workaround; broker tail on 2026-05-14 captured a `wican/pilot19/pid` retained message with `intake_air_temp: 33` (~91 °F idle/cooled), well clear of the broken-bitmap –39 °C value.
- **BLE auth flap (2026-05-14)** — phone hit GATT status 5 (`INSUFFICIENT_AUTHENTICATION`) on every connect attempt to the WiCAN; resolved by Phone Settings → Bluetooth → forget the device + re-pair. Not a code bug; the bond on one side had been cleared. No follow-up task — the BLE-lost watchdog (ADR-017) already covers the trip-splitting side of this.
- **Manual-sync toggle bug (2026-05-14)** — user flipped the Settings switch but didn't tap Save, so DataStore stayed `false` and drives kept auto-uploading after each seal. Shipped v0.1.126: toggle now auto-persists via a focused `SettingsRepository.setManualSyncOnly(value)` setter wired through `ConfigViewModel.setManualSyncOnly`.
- **Sync-now silent UX (2026-05-14)** — History `Sync now` chip used to call `DriveSealer.kickWorker(force=true)` on a detached scope with zero UI feedback. Shipped v0.1.126: `HistoryViewModel.syncNow` now runs `DriveUploader.drain` on `viewModelScope`, exposing a `SyncState` flow (Idle / InProgress / Done(uploaded, remaining) / Failed) that the chip + status line consume.

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
