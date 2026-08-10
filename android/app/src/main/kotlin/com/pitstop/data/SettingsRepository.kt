package com.pitstop.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val aaTilesEngine: Preferences.Key<String> = stringPreferencesKey("aa_tiles_engine")
        val aaTilesFuel: Preferences.Key<String> = stringPreferencesKey("aa_tiles_fuel")
        val aaTabs: Preferences.Key<String> = stringPreferencesKey("aa_tabs")
        val dongleAlertEnabled: Preferences.Key<Boolean> =
            booleanPreferencesKey("dongle_alert_enabled")
        val unitSystem: Preferences.Key<String> = stringPreferencesKey("unit_system")
        val pairedCarBtMac: Preferences.Key<String> = stringPreferencesKey("paired_car_bt_mac")
        val manualSyncOnly: Preferences.Key<Boolean> = booleanPreferencesKey("manual_sync_only")
        val bridgeBleEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("bridge_ble_enabled")
        val bridgeGpsEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("bridge_gps_enabled")
        val bridgeAutoTrigger: Preferences.Key<Boolean> = booleanPreferencesKey("bridge_auto_trigger")
        val bridgeAutoTriggerSsids: Preferences.Key<String> = stringPreferencesKey("bridge_auto_trigger_ssids")
        val bridgeAutoTriggerActivityEnabled: Preferences.Key<Boolean> =
            booleanPreferencesKey("bridge_auto_trigger_activity_enabled")
        val companionAssociationId: Preferences.Key<Int> =
            intPreferencesKey("companion_association_id")

        // Process-survival flag for the Activity Recognition signal.
        // ActivityTransitionUpdates fire via PendingIntent → BroadcastReceiver
        // and survive process death; the receiver writes the latest
        // IN_VEHICLE state here so InCarDetector can re-emit it on the
        // next process boot before the next AR transition arrives.
        val inVehicleState: Preferences.Key<Boolean> =
            booleanPreferencesKey("ar_in_vehicle_state")
        val inVehicleStateAtMs: Preferences.Key<Long> =
            longPreferencesKey("ar_in_vehicle_state_at_ms")

        // When the bridge was last started by the auto-trigger (InCarDetector),
        // as opposed to a manual Home/Settings start. Surfaced in the Settings
        // "Auto-start status" card so the user can confirm auto-start is
        // actually firing.
        val lastAutoStartAtMs: Preferences.Key<Long> =
            longPreferencesKey("last_auto_start_at_ms")

        // Set once the user finishes (or skips) the first-run setup wizard, so
        // the wizard never gates the pager again.
        val heatmapMode: Preferences.Key<String> = stringPreferencesKey("heatmap_mode")
        // Comma-joined metric names selected on the trip-detail timeline.
        val tripSeriesMetrics: Preferences.Key<String> =
            stringPreferencesKey("trip_series_metrics")
        val onboardingComplete: Preferences.Key<Boolean> =
            booleanPreferencesKey("onboarding_complete")

        // Opt-in Mode 22 "extended" PIDs (ZF TCM: ATF temp + gear position).
        // Its own key, written only by setExtendedPidsEnabled below — see the
        // accessor docs for why it stays out of the whole-object write.
        val extendedPidsEnabled: Preferences.Key<Boolean> =
            booleanPreferencesKey("extended_pids_enabled")
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
            // Defaults ON: it fires only when the dongle has demonstrably
            // stopped answering mid-drive, which is data actively being lost.
            dongleAlertEnabled = prefs[Keys.dongleAlertEnabled] ?: true,
            aaTabs = prefs[Keys.aaTabs]?.split(",")
                ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            aaTilesEngine = prefs[Keys.aaTilesEngine]?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList(),
            aaTilesFuel = prefs[Keys.aaTilesFuel]?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList(),
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
            companionAssociationId = prefs[Keys.companionAssociationId],
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
            prefs[Keys.aaTilesEngine] = settings.aaTilesEngine.joinToString(",")
            prefs[Keys.aaTabs] = settings.aaTabs.joinToString(",")
            prefs[Keys.dongleAlertEnabled] = settings.dongleAlertEnabled
            prefs[Keys.aaTilesFuel] = settings.aaTilesFuel.joinToString(",")
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
        // Trim secrets before persisting — a stray leading/trailing space or a
        // newline pasted onto a token produces a silent 401/NOT_AUTHORIZED that
        // only surfaces two screens away. (mqttPassword is trimmed too; a real
        // password shouldn't have edge whitespace on this LAN-only broker.)
        if (!mqttPassword.isNullOrBlank()) secretStore.write(SecretStore.KEY_MQTT_PASSWORD, mqttPassword.trim())
        if (!ingestToken.isNullOrBlank()) secretStore.write(SecretStore.KEY_INGEST_TOKEN, ingestToken.trim())
        if (!queryToken.isNullOrBlank()) secretStore.write(SecretStore.KEY_QUERY_TOKEN, queryToken.trim())
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

    /** Persist the CompanionDeviceManager association id after a
     *  successful `associate()`. Managed exclusively by focused
     *  setter/clear (NOT through [update]) so the Settings Save button —
     *  which rebuilds [Settings] from the form — never clobbers it. */
    suspend fun setCompanionAssociationId(id: Int) {
        context.dataStore.edit { it[Keys.companionAssociationId] = id }
    }

    /** Clear the persisted companion association id (on unpair / disassociate). */
    suspend fun clearCompanionAssociationId() {
        context.dataStore.edit { it.remove(Keys.companionAssociationId) }
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

    /** Epoch-ms of the last auto-triggered bridge start (0 = never). Powers the
     *  "Last auto-started" line in the Settings auto-start status card. */
    val lastAutoStartAtMs: Flow<Long> =
        context.dataStore.data.map { it[Keys.lastAutoStartAtMs] ?: 0L }

    /** Stamp the auto-start clock. Called by [com.pitstop.presence.InCarDetector]
     *  the moment it fires a background bridge start. */
    suspend fun writeLastAutoStart(atMs: Long) {
        context.dataStore.edit { it[Keys.lastAutoStartAtMs] = atMs }
    }

    /**
     * Opt-in Mode 22 "extended" PIDs — ATF temperature + gear position off the
     * ZF transmission controller, polled over the phone's BLE session.
     *
     * **Defaults to false and stays false unless the user asks for it.** These
     * PIDs address a non-default module, which means a sticky TX-header change
     * on the ELM session for every poll; that is a real (measured, on the
     * WiCAN) way to break the rest of the PID stream if the restore ever slips.
     * Nobody gets that risk by upgrading — they have to opt in.
     *
     * Deliberately its own DataStore key rather than a [Settings] field: the
     * config form writes [Settings] as a whole object, so a field there can be
     * clobbered by any screen that saves a partially-populated form. Written
     * only by [setExtendedPidsEnabled]; [update] never touches this key.
     *
     * Observed live by [com.pitstop.service.PitstopBridgeService], so flipping
     * it adds/removes the extended PIDs from the round-robin mid-drive without
     * restarting the bridge.
     */
    /**
     * ALWAYS FALSE. The Settings toggle was removed in v0.1.233 and this is
     * hard-wired off rather than deleted, because the key may still hold
     * `true` on a device where it was switched on — and with no UI left, a
     * stored `true` would be permanently unreachable while continuing to
     * break capture.
     *
     * Mode 22 transmission PIDs (ATF temp, gear) were proven unusable on this
     * hardware on 2026-08-05: the dongle exposes ONE ELM session, headers are
     * sticky, and pointing it at the TCM makes every following standard PID
     * be answered by the wrong module — the published stream collapsed from
     * 62 keys to 19. The decode work is real and correct and is kept in
     * ZfTcmPids.kt; it is the transport that cannot carry it. See ADR-022.
     */
    val extendedPidsEnabled: Flow<Boolean> =
        kotlinx.coroutines.flow.flowOf(false)

    /** One-shot read for bridge start-up, before the flow collector lands. */
    /** See [extendedPidsEnabled] — hard-wired off. */
    suspend fun extendedPidsEnabledNow(): Boolean = false

    /** Focused setter for the extended-PID opt-in. */
    suspend fun setExtendedPidsEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.extendedPidsEnabled] = value }
    }

    /** True once the first-run setup wizard has been finished or skipped. */
    val onboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.onboardingComplete] ?: false }

    /** Mark the first-run wizard done so it never gates the pager again. */
    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[Keys.onboardingComplete] = value }
    }

    /**
     * Last-selected Map-tab colouring ("Density" / "Speed" / "Single"),
     * matching web's `pitstop_heatmap_mode` in localStorage.
     *
     * Stored as its own key rather than a [Settings] field on purpose:
     * [Settings] is written as a whole object by the config form, so
     * folding a map preference into it would make the map screen able to
     * clobber server/token fields (the blank-overwrite race Settings
     * already guards against). Null until the user picks a mode.
     */
    val heatmapMode: Flow<String?> =
        context.dataStore.data.map { it[Keys.heatmapMode]?.takeIf { m -> m.isNotBlank() } }

    suspend fun setHeatmapMode(mode: String) {
        context.dataStore.edit { it[Keys.heatmapMode] = mode }
    }

    /**
     * Series selected on the trip-detail timeline, or null if the user has
     * never picked — which is NOT the same as "picked nothing". Null means
     * fall back to the per-metric `defaultVisible` flags; an empty set is a
     * deliberate "show me none" and must survive a round trip.
     *
     * Persisted because the picker moved behind a tap: re-selecting Fuel
     * rate on every trip you open would be a worse screen than the chip
     * wall it replaced. Matches the web, which stores the same choice in
     * localStorage under `pitstop_trip_series_visible`.
     */
    val tripSeriesMetrics: Flow<Set<String>?> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.tripSeriesMetrics]?.let { raw ->
                raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        }

    suspend fun setTripSeriesMetrics(metrics: Set<String>) {
        context.dataStore.edit { it[Keys.tripSeriesMetrics] = metrics.joinToString(",") }
    }

    companion object {
        /** AR state older than this is treated as unknown / false on
         *  process boot. 30 min is long enough to cover most process-death
         *  windows while short enough that a stale "ENTER IN_VEHICLE" from
         *  the previous drive doesn't auto-start the bridge the next day. */
        const val AR_STATE_STALE_MS: Long = 30L * 60_000L
    }
}
