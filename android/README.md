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

## Future work

- Optional photo attachment in fuel quick-add. The CSV pipeline references JPEG
  filenames but does not include bytes; the Android side would post the URI through a
  multipart endpoint when the backend grows one (deferred per `docs/decisions.md`).
- Replace the BridgeStateBus singleton with a `bound service` connection so the UI sees
  exact state if the service is restarted by the OS while the user is in another app.
- ktlint and detekt CI gates. Today the editorconfig is the only formatting source.
