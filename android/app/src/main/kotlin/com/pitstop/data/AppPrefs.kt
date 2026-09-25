package com.pitstop.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPrefsStore by preferencesDataStore(name = "pitstop_app_prefs")

/** The range basis a widget / car tile can use without refetching 30 days of trips. */
@Serializable
data class CachedRangeBasis(
    val slug: String,
    val mpg: Double,
    val source: String,
    val tankUsGallons: Double?,
    val atMs: Long,
)

/**
 * UI + notification preferences that are NOT connection config: which
 * vehicle the app is looking at, the three notification toggles, and the
 * dedupe state those notifications need. A separate DataStore from
 * [SettingsRepository] on purpose — that one's whole-object write path and
 * its secret-field rules don't need a fourth kind of field.
 */
@Singleton
class AppPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val viewVehicleSlug = stringPreferencesKey("view_vehicle_slug")
        val driveSummaryNotif = booleanPreferencesKey("notif_drive_summary")
        val serviceReminderNotif = booleanPreferencesKey("notif_service_reminder")
        val vehicleAlertNotif = booleanPreferencesKey("notif_vehicle_alert")
        fun reminderStates(vehicleId: String) = stringPreferencesKey("notified_reminder_states_$vehicleId")
        fun seenDtcs(vehicleId: String) = stringSetPreferencesKey("seen_dtcs_$vehicleId")
        fun dtcSeeded(vehicleId: String) = booleanPreferencesKey("dtc_seeded_$vehicleId")
        fun rangeBasis(slug: String) = stringPreferencesKey("range_basis_$slug")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val stateMap = MapSerializer(String.serializer(), String.serializer())

    /** The vehicle the app is showing; null/blank = the bridge's configured slug. */
    val viewVehicleSlug: Flow<String?> = context.appPrefsStore.data.map { it[Keys.viewVehicleSlug] }

    suspend fun setViewVehicleSlug(slug: String) {
        context.appPrefsStore.edit { it[Keys.viewVehicleSlug] = slug }
    }

    val driveSummaryNotif: Flow<Boolean> = context.appPrefsStore.data.map { it[Keys.driveSummaryNotif] ?: true }
    val serviceReminderNotif: Flow<Boolean> = context.appPrefsStore.data.map { it[Keys.serviceReminderNotif] ?: true }
    val vehicleAlertNotif: Flow<Boolean> = context.appPrefsStore.data.map { it[Keys.vehicleAlertNotif] ?: true }

    suspend fun setDriveSummaryNotif(on: Boolean) = context.appPrefsStore.edit { it[Keys.driveSummaryNotif] = on }
    suspend fun setServiceReminderNotif(on: Boolean) = context.appPrefsStore.edit { it[Keys.serviceReminderNotif] = on }
    suspend fun setVehicleAlertNotif(on: Boolean) = context.appPrefsStore.edit { it[Keys.vehicleAlertNotif] = on }

    /** DTC codes already seen active for [vehicleId], and whether the set was ever seeded. */
    suspend fun seenDtcs(vehicleId: String): Pair<Set<String>, Boolean> {
        val p = context.appPrefsStore.data.first()
        return (p[Keys.seenDtcs(vehicleId)] ?: emptySet()) to (p[Keys.dtcSeeded(vehicleId)] ?: false)
    }

    suspend fun setSeenDtcs(vehicleId: String, codes: Set<String>) {
        context.appPrefsStore.edit {
            it[Keys.seenDtcs(vehicleId)] = codes
            it[Keys.dtcSeeded(vehicleId)] = true
        }
    }

    /** expense_id → last seen DueState name, per vehicle. */
    suspend fun reminderStates(vehicleId: String): Map<String, String> {
        val raw = context.appPrefsStore.data.first()[Keys.reminderStates(vehicleId)] ?: return emptyMap()
        return runCatching { json.decodeFromString(stateMap, raw) }.getOrDefault(emptyMap())
    }

    suspend fun setReminderStates(vehicleId: String, states: Map<String, String>) {
        context.appPrefsStore.edit { it[Keys.reminderStates(vehicleId)] = json.encodeToString(stateMap, states) }
    }

    suspend fun rangeBasis(slug: String): CachedRangeBasis? {
        val raw = context.appPrefsStore.data.first()[Keys.rangeBasis(slug)] ?: return null
        return runCatching { json.decodeFromString(CachedRangeBasis.serializer(), raw) }.getOrNull()
    }

    suspend fun setRangeBasis(b: CachedRangeBasis) {
        context.appPrefsStore.edit {
            it[Keys.rangeBasis(b.slug)] = json.encodeToString(CachedRangeBasis.serializer(), b)
        }
    }
}

/** The slug the app shows: the top-bar pick, else the bridge's configured vehicle. Pure for tests. */
fun effectiveVehicleSlug(viewSlug: String?, configuredSlug: String): String =
    viewSlug?.trim()?.takeIf { it.isNotEmpty() } ?: configuredSlug.trim()
