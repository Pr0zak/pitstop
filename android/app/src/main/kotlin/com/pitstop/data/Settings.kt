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
)

data class SettingsWithSecrets(
    val settings: Settings,
    val mqttPassword: String,
    val ingestToken: String,
)
