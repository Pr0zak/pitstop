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
)

data class SettingsWithSecrets(
    val settings: Settings,
    val mqttPassword: String,
    val ingestToken: String,
    /** Read-only token for /api/{vehicles, fillups, analytics, ...} GETs. */
    val queryToken: String = "",
)
