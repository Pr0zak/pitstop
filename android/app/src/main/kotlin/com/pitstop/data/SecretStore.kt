package com.pitstop.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tink-backed encrypted-at-rest storage for the MQTT password and the ingest token.
 *
 * EncryptedSharedPreferences uses AES-256-GCM with keys held in the device's hardware-backed
 * KeyStore via the AndroidX Security library. The values never appear in DataStore (which is
 * plaintext on disk).
 */
@Singleton
class SecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "pitstop_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun read(key: String): String = prefs.getString(key, "") ?: ""

    fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        const val KEY_MQTT_PASSWORD = "mqtt_password"
        const val KEY_INGEST_TOKEN = "ingest_token"
    }
}
