package com.pitstop.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "pitstop_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretStore: SecretStore,
) {

    private object Keys {
        val brokerUrl: Preferences.Key<String> = stringPreferencesKey("broker_url")
        val mqttUser: Preferences.Key<String> = stringPreferencesKey("mqtt_user")
        val vehicleSlug: Preferences.Key<String> = stringPreferencesKey("vehicle_slug")
        val apiBaseUrl: Preferences.Key<String> = stringPreferencesKey("api_base_url")
        val bleMac: Preferences.Key<String> = stringPreferencesKey("ble_device_mac")
        val bleName: Preferences.Key<String> = stringPreferencesKey("ble_device_name")
        val publishHz: Preferences.Key<Float> = floatPreferencesKey("publish_hz")
        val verboseLogging: Preferences.Key<Boolean> = booleanPreferencesKey("verbose_logging")
        val bridgeAutoStart: Preferences.Key<Boolean> = booleanPreferencesKey("bridge_auto_start")
        val deviceId: Preferences.Key<String> = stringPreferencesKey("device_id")
        val aaTilesHome: Preferences.Key<String> = stringPreferencesKey("aa_tiles_home")
        val aaTilesDiag: Preferences.Key<String> = stringPreferencesKey("aa_tiles_diag")
        val unitSystem: Preferences.Key<String> = stringPreferencesKey("unit_system")
        val pairedCarBtMac: Preferences.Key<String> = stringPreferencesKey("paired_car_bt_mac")
        val manualSyncOnly: Preferences.Key<Boolean> = booleanPreferencesKey("manual_sync_only")
        val bridgeBleEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("bridge_ble_enabled")
        val bridgeGpsEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("bridge_gps_enabled")
        val bridgeAutoTrigger: Preferences.Key<Boolean> = booleanPreferencesKey("bridge_auto_trigger")
        val bridgeAutoTriggerSsids: Preferences.Key<String> = stringPreferencesKey("bridge_auto_trigger_ssids")
        val bridgeAutoTriggerActivityEnabled: Preferences.Key<Boolean> =
            booleanPreferencesKey("bridge_auto_trigger_activity_enabled")

        // Process-survival flag for the Activity Recognition signal.
        // ActivityTransitionUpdates fire via PendingIntent → BroadcastReceiver
        // and survive process death; the receiver writes the latest
        // IN_VEHICLE state here so InCarDetector can re-emit it on the
        // next process boot before the next AR transition arrives.
        val inVehicleState: Preferences.Key<Boolean> =
            booleanPreferencesKey("ar_in_vehicle_state")
        val inVehicleStateAtMs: Preferences.Key<Long> =
            longPreferencesKey("ar_in_vehicle_state_at_ms")
    }

    /**
     * Stable device id for log shipping. Seeded once from `Settings.Secure.ANDROID_ID`
     * (or a random UUID fallback) by [LogShipper]. Stored here so the same value
     * persists across reinstall on most devices, but resets on factory reset.
     */
    val deviceId: Flow<String?> = context.dataStore.data.map { it[Keys.deviceId] }

    suspend fun deviceIdOrNull(): String? = context.dataStore.data.first()[Keys.deviceId]

    suspend fun setDeviceId(id: String) {
        context.dataStore.edit { it[Keys.deviceId] = id }
    }

    /**
     * Has the user started the bridge service at least once and not since
     * stopped it? Drives BootReceiver — only auto-start the foreground
     * service after a phone reboot if the user had it running before.
     */
    suspend fun bridgeAutoStart(): Boolean =
        context.dataStore.data.first()[Keys.bridgeAutoStart] ?: false

    suspend fun setBridgeAutoStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.bridgeAutoStart] = enabled }
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            brokerUrl = prefs[Keys.brokerUrl].orEmpty(),
            mqttUser = prefs[Keys.mqttUser].orEmpty(),
            vehicleSlug = prefs[Keys.vehicleSlug].orEmpty(),
            apiBaseUrl = prefs[Keys.apiBaseUrl].orEmpty(),
            bleDeviceMac = prefs[Keys.bleMac]?.takeIf { it.isNotBlank() },
            bleDeviceName = prefs[Keys.bleName]?.takeIf { it.isNotBlank() },
            publishHz = prefs[Keys.publishHz] ?: 1f,
            verboseLogging = prefs[Keys.verboseLogging] ?: false,
            aaTilesHome = prefs[Keys.aaTilesHome]?.split(",")
                ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            aaTilesDiag = prefs[Keys.aaTilesDiag]?.split(",")
                ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            unitSystem = prefs[Keys.unitSystem]?.takeIf {
                it == "imperial" || it == "metric"
            } ?: "imperial",
            pairedCarBtMac = prefs[Keys.pairedCarBtMac]?.takeIf { it.isNotBlank() },
            manualSyncOnly = prefs[Keys.manualSyncOnly] ?: false,
            bridgeBleEnabled = prefs[Keys.bridgeBleEnabled] ?: true,
            bridgeGpsEnabled = prefs[Keys.bridgeGpsEnabled] ?: true,
            bridgeAutoTrigger = prefs[Keys.bridgeAutoTrigger] ?: true,
            bridgeAutoTriggerSsids = prefs[Keys.bridgeAutoTriggerSsids]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            bridgeAutoTriggerActivityEnabled =
                prefs[Keys.bridgeAutoTriggerActivityEnabled] ?: false,
        )
    }

    /**
     * Cached snapshot of the last [current] result. Read non-blocking by
     * [currentCached] so the OkHttp [com.pitstop.http.PitstopAuthInterceptor]
     * doesn't hit DataStore + EncryptedSharedPreferences on every HTTP
     * call. Invalidated on every write below, and the settings half is
     * refreshed live by the flow collector in [init].
     */
    @Volatile private var cachedSnapshot: SettingsWithSecrets? = null

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Keep the cached settings half fresh: any DataStore edit emits
        // here. Secrets aren't in this flow, so each write path also
        // invalidates explicitly; this collector covers the focused
        // boolean setters that don't go through update().
        cacheScope.launch {
            settings.collect { s ->
                val prev = cachedSnapshot
                cachedSnapshot = if (prev != null) {
                    prev.copy(settings = s)
                } else {
                    null // first read populates via current()
                }
            }
        }
    }

    /** Drop the cache so the next [currentCached]/[current] rebuilds it. */
    private fun invalidateCache() {
        cachedSnapshot = null
    }

    suspend fun current(): SettingsWithSecrets {
        val s = settings.first()
        val snap = SettingsWithSecrets(
            settings = s,
            mqttPassword = secretStore.read(SecretStore.KEY_MQTT_PASSWORD),
            ingestToken = secretStore.read(SecretStore.KEY_INGEST_TOKEN),
            queryToken = secretStore.read(SecretStore.KEY_QUERY_TOKEN),
        )
        cachedSnapshot = snap
        return snap
    }

    /**
     * Non-blocking snapshot read for hot paths (the auth interceptor runs
     * on every request). Returns the cache if warm; otherwise falls back
     * to a one-time blocking [current] to populate it. After the first
     * request the cache stays warm until a write invalidates it.
     */
    fun currentCached(): SettingsWithSecrets =
        cachedSnapshot ?: runBlocking { current() }

    suspend fun update(
        settings: Settings,
        mqttPassword: String?,
        ingestToken: String?,
        queryToken: String? = null,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.brokerUrl] = settings.brokerUrl.trim()
            prefs[Keys.mqttUser] = settings.mqttUser.trim()
            prefs[Keys.vehicleSlug] = settings.vehicleSlug.trim()
            prefs[Keys.apiBaseUrl] = settings.apiBaseUrl.trim()
            settings.bleDeviceMac?.let { prefs[Keys.bleMac] = it } ?: prefs.remove(Keys.bleMac)
            settings.bleDeviceName?.let { prefs[Keys.bleName] = it } ?: prefs.remove(Keys.bleName)
            prefs[Keys.publishHz] = settings.publishHz
            prefs[Keys.verboseLogging] = settings.verboseLogging
            prefs[Keys.aaTilesHome] = settings.aaTilesHome.joinToString(",")
            prefs[Keys.aaTilesDiag] = settings.aaTilesDiag.joinToString(",")
            prefs[Keys.unitSystem] = settings.unitSystem
            settings.pairedCarBtMac?.let { prefs[Keys.pairedCarBtMac] = it }
                ?: prefs.remove(Keys.pairedCarBtMac)
            prefs[Keys.manualSyncOnly] = settings.manualSyncOnly
            prefs[Keys.bridgeBleEnabled] = settings.bridgeBleEnabled
            prefs[Keys.bridgeGpsEnabled] = settings.bridgeGpsEnabled
            prefs[Keys.bridgeAutoTrigger] = settings.bridgeAutoTrigger
            prefs[Keys.bridgeAutoTriggerSsids] = settings.bridgeAutoTriggerSsids.joinToString(",")
            prefs[Keys.bridgeAutoTriggerActivityEnabled] = settings.bridgeAutoTriggerActivityEnabled
        }
        // Treat blank as "leave alone" rather than "clear" — otherwise a
        // save fired before the form's init coroutine has populated the
        // secret from disk silently wipes the stored value. The user has
        // no way to recover except retyping. (This was the cause of the
        // 0.1.82 → broker NOT_AUTHORIZED loop.)
        // Explicit clears go through clearSecret() below.
        if (!mqttPassword.isNullOrBlank()) secretStore.write(SecretStore.KEY_MQTT_PASSWORD, mqttPassword)
        if (!ingestToken.isNullOrBlank()) secretStore.write(SecretStore.KEY_INGEST_TOKEN, ingestToken)
        if (!queryToken.isNullOrBlank()) secretStore.write(SecretStore.KEY_QUERY_TOKEN, queryToken)
        // Secrets aren't in the settings flow — drop the cache so the next
        // read rebuilds with the new tokens/password.
        invalidateCache()
    }

    /** Explicit clear of a single secret. Used by Settings UI when the
     *  user actually wants to remove a stored credential. */
    suspend fun clearSecret(key: String) {
        secretStore.write(key, "")
        invalidateCache()
    }

    /** Focused setter so the manual-sync toggle in Settings can persist
     *  the instant the switch flips, instead of waiting on the bulk
     *  Save button. Booleans don't carry the secret-clobber risk that
     *  [update] guards against, so a narrow edit is safe. */
    suspend fun setManualSyncOnly(value: Boolean) {
        context.dataStore.edit { it[Keys.manualSyncOnly] = value }
    }

    /** Focused setter for the Bridge → "OBD via BLE" collector switch.
     *  Toggling it mid-session is observed by [PitstopBridgeService]
     *  off the settings flow and takes effect without a restart. */
    suspend fun setBridgeBleEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.bridgeBleEnabled] = value }
    }

    /** Focused setter for the Bridge → "GPS capture" collector switch.
     *  Same hot-reload contract as [setBridgeBleEnabled]. */
    suspend fun setBridgeGpsEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.bridgeGpsEnabled] = value }
    }

    /** Focused setter for the Bridge → "Auto-start in car" toggle.
     *  Observed by [com.pitstop.presence.InCarDetector] off the
     *  settings flow — flipping at runtime starts / stops the
     *  detector's signal collection without restarting anything. */
    suspend fun setBridgeAutoTrigger(value: Boolean) {
        context.dataStore.edit { it[Keys.bridgeAutoTrigger] = value }
    }

    /** Focused setter for the SSID allowlist that drives the in-car
     *  detector's WiFi signal. Empty list = the WiFi signal is inert
     *  (the other two signals still fire). */
    suspend fun setBridgeAutoTriggerSsids(values: List<String>) {
        val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
        context.dataStore.edit {
            it[Keys.bridgeAutoTriggerSsids] = cleaned.joinToString(",")
        }
    }

    /** Focused setter for the opt-in Activity Recognition sub-toggle.
     *  Observed by [com.pitstop.presence.InCarDetector] off the settings
     *  flow — flipping at runtime starts / stops the AR subscription
     *  without restarting anything. The UI is responsible for ensuring
     *  the runtime permission is granted BEFORE flipping this to true. */
    suspend fun setBridgeAutoTriggerActivityEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.bridgeAutoTriggerActivityEnabled] = value }
    }

    /**
     * Latest IN_VEHICLE state persisted by the activity-recognition
     * broadcast receiver. Read on process boot by [com.pitstop.presence.InCarDetector]
     * to re-establish the signal before the next AR transition arrives.
     * Stale values (older than [AR_STATE_STALE_MS]) are treated as false
     * by the consumer.
     */
    suspend fun readInVehicleState(): Pair<Boolean, Long> {
        val prefs = context.dataStore.data.first()
        return (prefs[Keys.inVehicleState] ?: false) to
            (prefs[Keys.inVehicleStateAtMs] ?: 0L)
    }

    /** Persist the latest IN_VEHICLE transition from the AR receiver. */
    suspend fun writeInVehicleState(value: Boolean, atMs: Long) {
        context.dataStore.edit {
            it[Keys.inVehicleState] = value
            it[Keys.inVehicleStateAtMs] = atMs
        }
    }

    companion object {
        /** AR state older than this is treated as unknown / false on
         *  process boot. 30 min is long enough to cover most process-death
         *  windows while short enough that a stale "ENTER IN_VEHICLE" from
         *  the previous drive doesn't auto-start the bridge the next day. */
        const val AR_STATE_STALE_MS: Long = 30L * 60_000L
    }
}
