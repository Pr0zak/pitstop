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
        val deviceId: Preferences.Key<String> = stringPreferencesKey("device_id")
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
        )
    }

    suspend fun current(): SettingsWithSecrets {
        val s = settings.first()
        return SettingsWithSecrets(
            settings = s,
            mqttPassword = secretStore.read(SecretStore.KEY_MQTT_PASSWORD),
            ingestToken = secretStore.read(SecretStore.KEY_INGEST_TOKEN),
        )
    }

    suspend fun update(
        settings: Settings,
        mqttPassword: String?,
        ingestToken: String?,
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
        }
        if (mqttPassword != null) secretStore.write(SecretStore.KEY_MQTT_PASSWORD, mqttPassword)
        if (ingestToken != null) secretStore.write(SecretStore.KEY_INGEST_TOKEN, ingestToken)
    }
}
