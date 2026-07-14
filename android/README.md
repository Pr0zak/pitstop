# Pitstop — Android app

Kotlin / Jetpack Compose / Material 3 phone app that:

1. Bridges the WiCAN OBD-II device over BLE while driving and republishes parsed PIDs to
   the pitstop Mosquitto broker on `bridge/<vehicle_slug>/<metric>` (matches the topic
   convention in `docs/architecture.md`).
2. Shows live gauges fed directly from the in-process flow (no broker round-trip).
3. Lets you record a fuel fillup with auto-GPS that POSTs to the pitstop API.

The broker IP is reachable from cellular through the user's existing Tailscale subnet
router (CT 444). The phone needs Tailscale installed with "Use Tailscale subnets"
enabled — the app surfaces this on the Config screen.

## Build

Requires:
- JDK 17 on `PATH`
- Android SDK with platform 35 / build-tools 35.0.0
- `ANDROID_HOME` (or `local.properties` with `sdk.dir=…`)

```
cd android
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

If your shell does not have JDK 17 picked up automatically, prefix the command:

```
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

## Layout

```
app/src/main/kotlin/com/pitstop/
├── MainActivity.kt           single-activity NavHost
├── PitstopApp.kt             Hilt @HiltAndroidApp
├── data/                     Settings (DataStore) + EncryptedSharedPreferences secrets
├── di/                       Hilt @Module — Retrofit, OkHttp, JSON
├── http/                     Retrofit interface + auth interceptor
├── ble/                      BleScanner + WiCanBleManager (Nordic library)
├── mqtt/                     HiveMQ MQTT v3 publisher
├── obd/                      Pid table + ELM/raw response parser
├── log/                      LogBuffer (ring) + LogShipper (drain to /api/logs) + masking
├── service/                  PitstopBridgeService + BridgeStateBus
└── ui/
    ├── theme/                PitstopTheme (M3 dark/light)
    ├── config/               first-run config screen
    ├── status/               service status + deep-link to web Live view
    ├── live/                 on-screen gauges (30 fps interpolation)
    └── fuel/                 fuel quick-add with auto-GPS + station autocomplete
```

## Permissions

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` — Android 12+ runtime.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — for GPS attached to publishes
  AND for legacy BLE scan paths on Android 11 and below.
- `POST_NOTIFICATIONS` — Android 13+ runtime, for the foreground service notification.
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` + `FOREGROUND_SERVICE_LOCATION` — manifest
  declarations for the bridge service.
- `REQUEST_COMPANION_RUN_IN_BACKGROUND` +
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` — install-time
  NORMAL permissions (no runtime prompt). Grant the CompanionDeviceManager
  background-FGS-start exemption that makes auto-start reliable. The association
  dialog itself is the user's consent. Harmless below API 33. See "Reliable
  background auto-start" below.

## Privacy

- Don't bake real broker URLs, real INGEST tokens, or your vehicle slug into source.
  All values are entered by the user at runtime and persisted in DataStore (slug, broker
  URL, BLE MAC) or `EncryptedSharedPreferences` (MQTT password, INGEST token).
- The keystore (`*.jks`, `keystore.properties`) is gitignored at the repo root.
- Network security config allows cleartext HTTP because the path runs over Tailscale
  (WireGuard) end-to-end. ADR-011 explains why we don't bother with TLS on the LAN
  broker.

## Backend contract assumptions (FLAG to backend agent)

The fuel quick-add screen POSTs:

```
POST /api/fillups
Authorization: Bearer <INGEST_TOKEN>
Content-Type: application/json

{
  "vehicle_slug": "my-car",
  "ts":           "2026-05-07T22:30:00Z",
  "gallons":      14.32,
  "total_price":  48.91,
  "odometer_mi":  76234.0,
  "partial":      false,
  "lat":          39.01,
  "lon":          -94.65,
  "station_name": "Example Station",
  "notes":        null
}
```

Response: `{ "id": "<uuid>" }`.

If the backend wants `vehicle_id` (UUID) instead of the slug, the slug→UUID resolver
should accept the slug in the body and respond with the canonical id. Slug-based
addressing matches the MQTT topic convention (`bridge/<vehicle_slug>/<metric>`),
which is preferable.

## Structured logging (`com.pitstop.log`)

The app ships a structured-log feed to the backend's `/api/logs` depot:

- **`LogBuffer`** — process-wide ring buffer (default 1000 entries). API:
  `debug/info/warn/error(message, context: Map<String, Any?>)`. `debug()` is gated by
  `Settings.verboseLogging` (toggle in the Config screen) — when off, `debug()` calls are
  no-ops at zero allocation. Drop policy is drop-oldest with logcat notice.
- **`LogShipper`** — coroutine drain owned by the foreground service. Flushes every 60 s
  (or 120 s in `batterySaving` mode while the BLE/MQTT layer is in long-disconnect
  backoff), or eagerly when the buffer reaches 50 entries, or via explicit `flushNow()`
  from the Config screen's "Send logs now" button. On POST failure entries are returned
  to the buffer head and exponential backoff (2 s → 60 s) gates the next attempt.
- **`device_id`** — seeded once from `Settings.Secure.ANDROID_ID` (or a random UUID if the
  device returns a blank id), persisted in DataStore. Stable across reinstall on most
  devices, but resets on factory reset.
- **Masking helpers** in `LogMasking.kt`:
  - `maskMac("AA:BB:CC:DD:1A:B2") -> "xx:xx:xx:xx:1A:B2"` — keep last 4 octets only.
  - `loggableUrl(...)` — drops userinfo + query params before logging the URL.
  - `roundCoord(value, decimals)` — round GPS coords for log output (info+ uses 2 decimals,
    debug may use 5).

`ObdResponseParser` is intentionally pure — parse-failure warnings live at the call-site
in `PitstopBridgeService.onUartFrame()` where the surrounding context (which PID, which
frame text) is available.

`PitstopAuthInterceptor` takes a `Lazy<LogBuffer>` to avoid a Hilt cycle: the LogShipper
needs `PitstopApi`, which is built on the same OkHttp client as the interceptor. Logging
in the interceptor is **error-only** and never includes the bearer token bytes; the
`/api/logs` endpoint itself is excluded from interceptor logging to prevent feedback
loops.

## OEM-specific BLE quirks

- **WiCAN Pro UART profile.** First we try the Nordic UART Service (NUS):
  `6E400001…` service / `6E400002…` RX / `6E400003…` TX. Some older firmwares expose
  a 0xFFE0/0xFFE1 SPP-style profile; we fall back to that. If neither is present we
  surface "device not supported" and need the user to upgrade firmware.
- **MTU.** We request 247 best-effort. ELM-style responses fit in MTU=23 anyway, so
  we don't fail when the negotiation downgrades.
- **Honda Pilot 2019 quirks.**
  - VIN PID (Mode 09) returns garbage on this trim — we don't use it for identification
    (ADR-003 — `fuelio_guid` is the sync key).
  - PID 0x0D (vehicle speed) is reported in km/h on this trim — converted to mph for the
    Live view; the raw km/h value is what we publish to MQTT to match the WiCAN profile.
- **Bonding.** WiCAN does not require pairing. If the firmware ever flips bonding on, we
  will need to call `device.createBond()` before `connectGatt`; the Nordic BleManager
  handles this automatically once we wire `shouldAutoConnect()` and bonding callbacks.
- **Connect failures are TIMEOUTs, not auth failures.** The WiCAN link flaps hard — in one
  7-day window only 8% of connect attempts reached the poll loop; the other 626/685 carried
  `status = -5`. That `-5` is Nordic's `FailCallback.REASON_TIMEOUT` (the dongle simply wasn't
  advertising / in range), **not** GATT status 5 (HCI Authentication Failure). The `.fail{}`
  handler on a `connect()` request only ever delivers the *negative* `FailCallback.REASON_*`
  constants, never a raw HCI status — so a `status == 5` comparison there is dead code and would
  never fire. Do NOT `removeBond()` on a timeout: it forces a needless re-pair every time the car
  is asleep. A genuine bond-key mismatch surfaces instead through
  `ConnectionObserver.onDeviceFailedToConnect` as `REASON_UNKNOWN` while the device is still
  BONDED on our side — that (and only that) is where `WiCanBleManager` calls `removeBond()`.
- **One connect() = one attempt.** We dropped the inner `.retry(3, 250)` on `connect()` so a single
  logical failure is a single log line and the service-level adaptive backoff owns retry cadence;
  the old inner retry silently burned three 15 s timeouts (~45 s) per scheduled attempt behind one
  opaque line. The first attempt of a session uses `useAutoConnect(false)` (direct connect → fast
  first frame on engine start); every retry after that uses `useAutoConnect(true)` so the OS
  reattaches opportunistically when the WiCAN next advertises (cheap on battery, dodges the
  active-scan radio timeout). In-car backoff also decays now (5 s for the first ~3 attempts, then
  climbs to 30 s) so a parked phone next to a sleeping WiCAN stops retrying every 5 s forever.
- **Adaptive PID suppression on BLE.** Some PIDs answer only on the WiCAN WiFi/AutoPID path and
  return `NO DATA` on every BLE poll (`maf_air_flow` = 4,061 NO DATA / 7d). The bridge counts
  *consecutive* strict-`NO DATA` per PID (best-effort via `lastSentPid`) and drops a PID from the
  live poll set after 5 in a row, re-probing from scratch each bridge session. `STOPPED` /
  `UNABLE TO CONNECT` / `BUS*` are whole-bus signals (engine off / ECU unreachable) and are
  deliberately excluded from this count.

## Reliable background auto-start (CompanionDeviceManager)

The in-car detector ([`presence/InCarDetector`](app/src/main/kotlin/com/pitstop/presence/InCarDetector.kt))
*detects* "you're in the car" fine, but on modern Android the actual
`startForegroundService()` it then calls is denied with
`ForegroundServiceStartNotAllowedException` ("mAllowStartForeground false")
when the app is in the background with no exemption. Confirmed in production
`client_logs`: an `auto-start bridge` line immediately followed by
`auto-start failed: mAllowStartForeground false`.

The fix is CompanionDeviceManager (CDM), in [`companion/`](app/src/main/kotlin/com/pitstop/companion/):

- **[`WicanCompanionManager`](app/src/main/kotlin/com/pitstop/companion/WicanCompanionManager.kt)**
  owns `associate()` / `disassociate()` / `startObservingDevicePresence()`.
  Associating the WiCAN as a companion device grants the standing
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` exemption, so the
  bridge FGS-start succeeds from the background.
- **[`WicanCompanionService`](app/src/main/kotlin/com/pitstop/companion/WicanCompanionService.kt)**
  (a `CompanionDeviceService`) is bound by the OS while we observe presence. When
  the WiCAN advertises in BLE range the system WAKES our process and calls
  `onDeviceAppeared`, where we start the bridge (respecting the
  `bridgeAutoTrigger` toggle). `onDeviceDisappeared` stops it after a 120 s grace
  window. CDM is the robust PRIMARY trigger; InCarDetector's AA/WiFi/AR paths
  remain best-effort fallbacks. The bridge's `onStartCommand` is idempotent, so a
  CDM start racing an InCarDetector start is harmless.

Quirks / gotchas discovered:

- **Association needs an Activity.** `associate()` hands back an `IntentSender`
  that must be launched from an Activity (`StartIntentSenderForResult`), not the
  manager. The flow is: VM → `associate()` → IntentSender → Settings screen
  launcher → consent dialog → result → VM `onCompanionConfirmed()`.
- **`onDeviceAppeared` signature differs by API.** API 33+ gets
  `onDeviceAppeared(AssociationInfo)`; API 31–32 gets the deprecated
  `onDeviceAppeared(String address)`. Both are implemented and dispatched by
  `SDK_INT`; the String variant early-returns on 33+ to avoid a double start.
- **`AssociationInfo.getId()` returns `int`** (API 31+). The API 31–32 consent
  *result* yields a `BluetoothDevice` (MAC) with no integer id, so the manager
  resolves the real id from `myAssociations` by MAC before observing (sentinel
  `-1` triggers that lookup).
- **`ObservingDevicePresenceRequest` (setAssociationId) is API 35+** and is NOT
  in `compileSdk 35`, so we use the MAC-based `startObservingDevicePresence(String)`
  overload uniformly for API 31+ (deprecated on 35+ but functional for a
  MAC-addressable BLE device). Switch to the request builder behind a
  `SDK_INT >= 35` gate when the project bumps `compileSdk`.
- **API 26–30**: `associate()` works (legacy `associate(req, cb, handler)`), so
  pairing is possible, but presence-observe + the FGS-from-background exemption
  don't exist. The pairing card is hidden below API 31
  (`companionPresenceSupported`); on those versions the InCarDetector triggers are
  the only auto-start path.
- **Emulator can't exercise presence.** No real BLE peripheral means
  `onDeviceAppeared` never fires on an emulator. The Pair button + association
  list + status card render and don't crash without a device; real on-device
  presence-wake needs a powered WiCAN advertising in range.

The association id is persisted in DataStore (`companionAssociationId`) via a
focused setter that does NOT go through `Settings.update()` (which rebuilds
`Settings` from the form and would clobber it). The id is an opaque OS-issued
integer, not vehicle-identifying, so storing it unencrypted is fine.

## Future work

- Optional photo attachment in fuel quick-add. The CSV pipeline references JPEG
  filenames but does not include bytes; the Android side would post the URI through a
  multipart endpoint when the backend grows one (deferred per `docs/decisions.md`).
- Replace the BridgeStateBus singleton with a `bound service` connection so the UI sees
  exact state if the service is restarted by the OS while the user is in another app.
- ktlint and detekt CI gates. Today the editorconfig is the only formatting source.
