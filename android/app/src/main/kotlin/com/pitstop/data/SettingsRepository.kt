package com.pitstop.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
        )
    }

    suspend fun current(): SettingsWithSecrets {
        val s = settings.first()
        return SettingsWithSecrets(
            settings = s,
            mqttPassword = secretStore.read(SecretStore.KEY_MQTT_PASSWORD),
            ingestToken = secretStore.read(SecretStore.KEY_INGEST_TOKEN),
            queryToken = secretStore.read(SecretStore.KEY_QUERY_TOKEN),
        )
    }

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
    }

    /** Explicit clear of a single secret. Used by Settings UI when the
     *  user actually wants to remove a stored credential. */
    suspend fun clearSecret(key: String) {
        secretStore.write(key, "")
    }

    /** Focused setter so the manual-sync toggle in Settings can persist
     *  the instant the switch flips, instead of waiting on the bulk
     *  Save button. Booleans don't carry the secret-clobber risk that
     *  [update] guards against, so a narrow edit is safe. */
    suspend fun setManualSyncOnly(value: Boolean) {
        context.dataStore.edit { it[Keys.manualSyncOnly] = value }
    }
}
