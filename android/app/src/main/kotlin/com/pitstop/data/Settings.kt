package com.pitstop.data

/**
 * App-level configuration. The password and ingest token are persisted through
 * [SecretStore] (Tink-backed) and are NOT stored in DataStore.
 */
data class Settings(
    val brokerUrl: String = "",
    val mqttUser: String = "",
    val vehicleSlug: String = "",
    val apiBaseUrl: String = "",
    val bleDeviceMac: String? = null,
    val bleDeviceName: String? = null,
    val publishHz: Float = 1f,
    /**
     * When true, `LogBuffer.debug(...)` calls are recorded and shipped to the server log
     * depot. When false (default) debug lines are no-ops; info/warn/error always record.
     */
    val verboseLogging: Boolean = false,
    /**
     * Ordered metric keys shown on the Android Auto home grid (top 6).
     * Empty list → use the hardcoded defaults in LiveCarScreen.
     */
    val aaTilesHome: List<String> = emptyList(),
    /**
     * Ordered metric keys shown on the Android Auto diagnostics screen.
     * Same fallback shape as aaTilesHome.
     */
    val aaTilesDiag: List<String> = emptyList(),
    /**
     * Display unit system. "imperial" → °F, mph, mi, gal, psi.
     * "metric" → °C, km/h, km, l, kPa. Default imperial since the
     * user's vehicles + fillups are all stored in US units.
     */
    val unitSystem: String = "imperial",
    /**
     * Bluetooth MAC of the user's car (HFP / A2DP profile). When set,
     * the bridge treats "phone connected to this device" as a strong
     * "user is in the car" signal alongside Android Auto. Used by the
     * adaptive BLE backoff (Task #77). Null = not configured; bridge
     * falls back to AA + engine_state alone.
     */
    val pairedCarBtMac: String? = null,
    /**
     * When true, the bridge suppresses every outgoing MQTT publish during
     * drives (per-metric, location, engine_state) and the drive-upload
     * pipeline (immediate kick after seal + periodic worker) becomes a
     * no-op. Local capture continues: BridgeStateBus still drives the
     * Live screen, DriveRecorder still batches sealed drives into the
     * Room queue, the History tab's "Sync now" button still works.
     *
     * Use case: data-/battery-constrained driving where the user wants
     * to upload manually over Wi-Fi later instead of streaming every
     * sample over cellular.
     *
     * Default false preserves the existing always-stream behaviour for
     * users who upgrade through this version without touching settings.
     */
    val manualSyncOnly: Boolean = false,
    /**
     * Independent toggle for the BLE-OBD collector inside the bridge
     * foreground service. When false the service runs but never opens
     * a BLE link to the WiCAN dongle — useful once the WiCAN reaches
     * the broker over its own WiFi path (phone hotspot → WireGuard)
     * so the phone only needs to ship GPS.
     *
     * Defaults to true to preserve the existing collect-OBD-over-BLE
     * behaviour for everyone who upgrades through this release. Will
     * flip to false once the VPN architecture is fully phased in.
     */
    val bridgeBleEnabled: Boolean = true,
    /**
     * Independent toggle for the GPS collector inside the bridge
     * foreground service. When false the bridge never subscribes to
     * LocationManager updates — Live-screen GPS tiles stay blank and
     * `bridge/<slug>/location` is never published. Combined with
     * [bridgeBleEnabled] this enables a "GPS-only" or "BLE-only"
     * mode without restructuring the service.
     *
     * Defaults to true so that the GPS-collector path keeps running
     * for users who upgrade through this release without touching
     * settings.
     */
    val bridgeGpsEnabled: Boolean = true,
    /**
     * When true, an app-level [com.pitstop.presence.InCarDetector] OR-combines
     * three independent "in-car" signals — paired-car WiFi SSID
     * association, Android Auto active, paired-car HFP Bluetooth — and
     * starts the bridge foreground service when any one fires. When all
     * three stay false for the detector's debounce window the service
     * is stopped automatically. When false the detector is inert and
     * only the manual Start/Stop button in Settings drives the bridge
     * (matches v0.1.173 behaviour).
     *
     * Default true: solves the >5 %/h idle drain that happens when the
     * user leaves the bridge manually started overnight. New installs
     * get the right behaviour out of the box; existing installs that
     * already had the bridge manually running keep working (the
     * detector sees their in-car signals when they drive and the auto
     * lifecycle takes over).
     */
    val bridgeAutoTrigger: Boolean = true,
    /**
     * Comma-separated WiFi SSIDs the [com.pitstop.presence.InCarDetector]
     * treats as "you're in the car" when the phone is associated with
     * one. The most reliable hands-on signal: the user's car-only
     * hotspot SSID. Empty by default; the user adds their own (eg. a
     * vehicle nav unit's SSID, or a phone hotspot) via the Settings UI.
     *
     * Reading `WifiInfo.getSSID()` on Android Q+ requires
     * `ACCESS_FINE_LOCATION` — already declared / requested for GPS,
     * so this signal piggybacks on the existing prompt.
     */
    val bridgeAutoTriggerSsids: List<String> = emptyList(),
    /**
     * Opt-in 4th in-car signal: subscribe to
     * [com.google.android.gms.location.ActivityRecognitionClient] and
     * count IN_VEHICLE transitions as "in car". Fires within ~5–15 s of
     * vehicle motion — independent of (and faster than) the WiFi-SSID
     * signal, which can lag 30–60 s while the phone clings to home WiFi.
     *
     * Requires the dangerous `ACTIVITY_RECOGNITION` runtime permission
     * on Android 10+. We NEVER prompt at install or first launch — the
     * user enables this via a sub-toggle under "Auto-start in car"; the
     * UI requests the permission only when the toggle flips on. Disabled
     * by default so existing users don't see a permission prompt.
     */
    val bridgeAutoTriggerActivityEnabled: Boolean = false,
    /**
     * CompanionDeviceManager association id for the WiCAN, persisted after
     * a successful `associate()`. Drives two things: (1) the Settings UI
     * shows "Associated" vs "Not paired"; (2) on API 34+ it's the id passed
     * to `ObservingDevicePresenceRequest.Builder().setAssociationId(...)`.
     * Null = the WiCAN has not been associated as a companion device yet,
     * so the reliable-background-start path is unavailable and the app
     * falls back to the InCarDetector best-effort triggers. The id is an
     * opaque, OS-issued integer — not vehicle-identifying — so persisting
     * it in DataStore (unencrypted) is fine.
     */
    val companionAssociationId: Int? = null,
)

data class SettingsWithSecrets(
    val settings: Settings,
    val mqttPassword: String,
    val ingestToken: String,
    /** Read-only token for /api/{vehicles, fillups, analytics, ...} GETs. */
    val queryToken: String = "",
)
