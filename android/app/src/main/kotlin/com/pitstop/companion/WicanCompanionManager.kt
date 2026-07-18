package com.pitstop.companion

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.PitstopBridgeService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executor
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the CompanionDeviceManager (CDM) lifecycle for the WiCAN.
 *
 * Why this exists: the in-car auto-start path ([com.pitstop.presence.InCarDetector])
 * detects "you're in the car" fine, but on modern Android the actual
 * `startForegroundService()` call is denied with
 * `ForegroundServiceStartNotAllowedException` ("mAllowStartForeground false")
 * because the app is in the BACKGROUND with no exemption. CDM solves this:
 * associating the WiCAN as a companion device grants the standing
 * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` exemption, AND
 * `startObservingDevicePresence` lets the OS WAKE the app + invoke
 * [WicanCompanionService] when the WiCAN comes into BLE range — solving
 * detection + process-wake + FGS-start in one OS-sanctioned mechanism.
 *
 * API-version handling:
 *   - **API 26–30**: `associate()` works (legacy `associate(req, cb, handler)`),
 *     so the user can pair, but `startObservingDevicePresence` and the
 *     FGS-from-background exemption are NOT available. We log this and the
 *     existing InCarDetector triggers remain the only auto-start path.
 *   - **API 31–32 (S / S_V2)**: associate via the legacy overload, observe via
 *     `startObservingDevicePresence(macAddress)`, and the system invokes
 *     [WicanCompanionService.onDeviceAppeared]`(String)`.
 *   - **API 33+ (TIRAMISU)**: associate via `associate(req, executor, cb)`,
 *     `onAssociationCreated(AssociationInfo)` hands back the id directly, and
 *     the system invokes [WicanCompanionService.onDeviceAppeared]`(AssociationInfo)`.
 *
 * Presence observation uses the MAC-based `startObservingDevicePresence(String)`
 * overload uniformly for API 31+; the newer `ObservingDevicePresenceRequest`
 * (setAssociationId) path is API 35+ and not in compileSdk 35, so it's deferred
 * until the project bumps compileSdk.
 *
 * The association dialog requires an Activity to launch the [IntentSender];
 * [associate] therefore returns the IntentSender to the caller (the Activity)
 * via [onIntentSender] rather than launching it here.
 */
@Singleton
class WicanCompanionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val logBuffer: LogBuffer,
    private val stateBus: BridgeStateBus,
) {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { r -> mainHandler.post(r) }

    @Volatile private var idleStopJob: Job? = null

    /**
     * Arm the "stop the bridge once the drive is genuinely idle" backstop.
     *
     * Called from [WicanCompanionService.onDeviceDisappeared]. CDM reports the
     * WiCAN as "disappeared" the moment the bridge holds a GATT link to it (it
     * stops advertising once connected) and does NOT re-fire when that connected
     * device later sleeps — so the CDM service is unbound for most of the drive
     * and cannot host this wait. We run it here on the process-lifetime
     * [ioScope] (kept alive by the bridge foreground service for the drive's
     * duration) so it survives the service unbind, then send ACTION_STOP once
     * [BridgeStateBus.isDriveLikelyActive] has stayed false — the WiCAN has
     * slept and the drive is over. Stopping earlier would seal a live drive and
     * split it into many trips (2026-07-18); never stopping would leak the FGS
     * after a park. Idempotent: a later disappear re-arms, an appear cancels.
     */
    fun scheduleIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = ioScope.launch {
            delay(STOP_GRACE_MS)
            // Confirm the drive is idle over IDLE_CONFIRM_POLLS *consecutive*
            // polls before stopping (hysteresis). A single transient
            // isDriveLikelyActive()==false at a drive boundary — or a brief
            // reconnect gap — must not seal a live drive; any sign of life
            // resets the count, and a genuine restart flips it back to active.
            var idlePolls = 0
            while (isActive) {
                if (stateBus.isDriveLikelyActive()) {
                    idlePolls = 0
                } else if (++idlePolls >= IDLE_CONFIRM_POLLS) {
                    break
                }
                delay(ALIVE_POLL_MS)
            }
            if (!isActive) return@launch
            // Honor the manual contract (v0.1.173): with auto-start off, only
            // the user's manual Stop ends a session — this CDM backstop must
            // never tear down a manually-started bridge. Symmetric with
            // onAppeared's toggle gate; re-checked here so a toggle flipped
            // during the wait is respected. Default to "manual" (skip) on any
            // read failure — never auto-stop on uncertainty. runCatching would
            // swallow a CancellationException from cancelIdleStop() arriving
            // during this suspend, so re-check isActive afterward: without it a
            // stop could fire against a drive onDeviceAppeared is simultaneously
            // restarting.
            val auto = runCatching { settings.settings.first().bridgeAutoTrigger }.getOrDefault(false)
            if (!isActive) return@launch
            if (!auto) {
                logBuffer.info("companion: idle-stop skipped — auto-start off (manual session)")
                return@launch
            }
            runCatching {
                logBuffer.info("companion: bridge stop (idle)")
                context.startService(PitstopBridgeService.stopIntent(context))
            }.onFailure {
                logBuffer.warn(
                    "companion: bridge stop failed",
                    mapOf("err" to (it.message ?: it::class.java.simpleName)),
                )
            }
        }
    }

    /** Cancel a pending idle-stop — the WiCAN reappeared, so a drive is (still)
     *  under way and the bridge must stay up. */
    fun cancelIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    private val cdm: CompanionDeviceManager? by lazy {
        runCatching {
            context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        }.getOrNull()
    }

    /** True on OS versions where presence-observe + the FGS-from-background
     *  exemption actually apply (API 31+). Below this, association is still
     *  possible but only buys cosmetic state — the InCarDetector triggers
     *  carry the load. */
    val presenceSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Result of an [associate] attempt other than the happy "dialog
     *  launched" path. */
    sealed interface AssociateOutcome {
        /** A consent dialog must be shown. Launch [intentSender] from an
         *  Activity via an ActivityResultLauncher. */
        data class NeedsConsent(val intentSender: IntentSender) : AssociateOutcome
        /** Already associated — nothing to do; observe was (re)started. */
        object AlreadyAssociated : AssociateOutcome
        /** CDM unavailable or the request couldn't be built/submitted. */
        data class Failed(val reason: String) : AssociateOutcome
    }

    /**
     * Begin (or short-circuit) WiCAN association.
     *
     * Idempotent: if an association already exists for our WiCAN (matched by
     * the stored MAC, else any single existing association), we re-assert
     * presence observation and return [AssociateOutcome.AlreadyAssociated]
     * without showing the dialog again.
     *
     * Otherwise builds an [AssociationRequest] (BLE filter scoped to the
     * WiCAN) and submits it. The CDM callback comes back asynchronously, so
     * the IntentSender is delivered via [onIntentSender]; this method returns
     * immediately. [onError] reports a build/submit failure.
     */
    @SuppressLint("MissingPermission")
    fun associate(
        knownMac: String?,
        onIntentSender: (IntentSender) -> Unit,
        onAlreadyAssociated: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val manager = cdm ?: run {
            logBuffer.warn("companion: CDM service unavailable")
            onError("Companion device manager not available on this device")
            return
        }

        // Idempotency guard — don't double-associate. myAssociations is API
        // 31+; on 26–30 we can't introspect, so we always present the dialog
        // (the OS de-dupes by showing the existing device pre-selected).
        if (presenceSupported) {
            val existing = existingAssociationId(manager, knownMac)
            if (existing != null) {
                logBuffer.info(
                    "companion: already associated",
                    mapOf("association_id" to existing),
                )
                persistAndObserve(existing, knownMac)
                onAlreadyAssociated()
                return
            }
        }

        val request = buildRequest(knownMac) ?: run {
            onError("Could not build association request")
            return
        }

        val callback = object : CompanionDeviceManager.Callback() {
            // API 33+: the consent UI path.
            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onAssociationPending(intentSender: IntentSender) {
                logBuffer.info("companion: association pending (API 33+)")
                onIntentSender(intentSender)
            }

            // API 33+: fired AFTER the user confirms (we also pick the id up
            // from the Activity result, but this is the authoritative source
            // of the AssociationInfo).
            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onAssociationCreated(association: AssociationInfo) {
                val id = association.id
                logBuffer.info(
                    "companion: association created",
                    mapOf("association_id" to id),
                )
                persistAndObserve(id, knownMac ?: association.deviceMacAddress?.toString())
            }

            // API 26+: pre-33 consent UI path (also still delivered on 33+
            // when associate() is called via the legacy overload).
            override fun onDeviceFound(intentSender: IntentSender) {
                logBuffer.info("companion: device found")
                onIntentSender(intentSender)
            }

            override fun onFailure(error: CharSequence?) {
                val msg = error?.toString() ?: "unknown"
                logBuffer.warn("companion: associate failed", mapOf("err" to msg))
                onError(msg)
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, callback, mainHandler)
            }
        }.onFailure {
            logBuffer.warn(
                "companion: associate threw",
                mapOf("err" to (it.message ?: it::class.java.simpleName)),
            )
            onError(it.message ?: it::class.java.simpleName)
        }
    }

    /**
     * Called by the Activity once the association consent dialog returns OK.
     * On API 33+ the result carries an [AssociationInfo]; on 31–32 it carries
     * a `BluetoothDevice`. Either way we persist what we can and start
     * observing presence. Safe to call redundantly with
     * [onAssociationCreated] — both funnel through [persistAndObserve], which
     * is idempotent.
     */
    @SuppressLint("MissingPermission")
    fun onAssociationConfirmed(associationId: Int, mac: String?) {
        // The API 31–32 consent result yields a BluetoothDevice (MAC) but no
        // integer id; resolve the real id from myAssociations by MAC.
        val resolvedId = if (associationId >= 0) {
            associationId
        } else {
            val manager = cdm
            if (manager != null && presenceSupported) {
                runCatching {
                    manager.myAssociations.firstOrNull {
                        it.deviceMacAddress?.toString().equals(mac, ignoreCase = true)
                    }?.id ?: manager.myAssociations.singleOrNull()?.id
                }.getOrNull()
            } else {
                null
            }
        }
        if (resolvedId == null) {
            logBuffer.warn(
                "companion: confirmed but could not resolve association id",
                mapOf("mac" to (mac ?: "?")),
            )
            return
        }
        logBuffer.info(
            "companion: association confirmed",
            mapOf("association_id" to resolvedId),
        )
        persistAndObserve(resolvedId, mac)
    }

    /**
     * Remove the WiCAN association and stop observing presence. Idempotent —
     * clears persisted state even if the OS-side disassociate fails.
     */
    @SuppressLint("MissingPermission")
    fun unpair() {
        val manager = cdm
        ioScope.launch {
            val s = runCatching { settings.settings.first() }.getOrNull()
            val associationId = s?.companionAssociationId ?: currentAssociationIdBlockingHint
            if (manager != null && presenceSupported && associationId != null) {
                runCatching { stopObserving(manager, associationId) }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        manager.disassociate(associationId)
                    } else {
                        // Pre-33: disassociate by MAC (the only overload).
                        val mac = s?.bleDeviceMac
                            ?: macForAssociation(manager, associationId)
                        @Suppress("DEPRECATION")
                        if (mac != null) manager.disassociate(mac)
                    }
                }.onFailure {
                    logBuffer.warn(
                        "companion: disassociate failed",
                        mapOf("err" to (it.message ?: it::class.java.simpleName)),
                    )
                }
            }
            currentAssociationIdBlockingHint = null
            runCatching { settings.clearCompanionAssociationId() }
            logBuffer.info("companion: unpaired")
        }
    }

    /**
     * Re-assert presence observation for an already-persisted association.
     * Called at app start so the OS keeps binding [WicanCompanionService]
     * across reboots / process death without the user re-pairing. No-op if
     * not associated or below API 31.
     */
    @SuppressLint("MissingPermission")
    fun ensureObserving() {
        if (!presenceSupported) return
        val manager = cdm ?: return
        ioScope.launch {
            val s = runCatching { settings.settings.first() }.getOrNull()
                ?: return@launch
            val id = s.companionAssociationId
            if (id == null) {
                // Not paired. If auto-start is on, the InCarDetector triggers
                // will be denied the background startForegroundService()
                // (mAllowStartForeground=false) and the drive silently never
                // starts. Leave a breadcrumb so a "didn't auto-start" report
                // is one log query away; Settings surfaces the same prompt
                // visually via the auto-start warning callout.
                if (s.bridgeAutoTrigger) {
                    logBuffer.warn(
                        "companion: auto-start on but WiCAN not paired — " +
                            "background bridge start will be blocked until paired",
                    )
                }
                return@launch
            }
            // Only observe if the association still exists OS-side; a user
            // could have removed it from system Settings.
            val stillAssociated = runCatching {
                manager.myAssociations.any { it.id == id }
            }.getOrDefault(true)
            if (!stillAssociated) {
                logBuffer.info("companion: association gone OS-side, clearing")
                runCatching { settings.clearCompanionAssociationId() }
                return@launch
            }
            // Collapse any duplicate associations for the WiCAN down to one
            // before observing — two associations for the same MAC make the OS
            // deliver every presence event twice (see the 2026-07-18 trip-split
            // incident). Persist the survivor so the next cycle short-circuits.
            val observeId = pruneDuplicateAssociations(manager, id, s.bleDeviceMac) ?: id
            if (observeId != id) {
                runCatching { settings.setCompanionAssociationId(observeId) }
            }
            runCatching { startObserving(manager, observeId, s.bleDeviceMac) }
                .onSuccess { logBuffer.info("companion: observing presence", mapOf("association_id" to observeId)) }
                .onFailure {
                    logBuffer.warn(
                        "companion: ensureObserving failed",
                        mapOf("err" to (it.message ?: it::class.java.simpleName)),
                    )
                }
        }
    }

    /** True if the WiCAN is currently associated as a companion device. */
    @SuppressLint("MissingPermission")
    fun hasAssociation(): Boolean {
        if (!presenceSupported) {
            // Can't introspect pre-31; trust the persisted id.
            return currentAssociationIdBlockingHint != null
        }
        val manager = cdm ?: return false
        return runCatching { manager.myAssociations.isNotEmpty() }.getOrDefault(false)
    }

    // ── internals ──────────────────────────────────────────────────────

    /** Best-effort cached id so [unpair]/[hasAssociation] have something on
     *  the pre-31 path. Updated whenever we persist. */
    @Volatile private var currentAssociationIdBlockingHint: Int? = null

    private fun persistAndObserve(id: Int, mac: String?) {
        currentAssociationIdBlockingHint = id
        ioScope.launch {
            runCatching { settings.setCompanionAssociationId(id) }
            if (presenceSupported) {
                val manager = cdm ?: return@launch
                runCatching { startObserving(manager, id, mac) }
                    .onSuccess {
                        logBuffer.info(
                            "companion: observing presence",
                            mapOf("association_id" to id),
                        )
                    }
                    .onFailure {
                        logBuffer.warn(
                            "companion: startObserving failed",
                            mapOf("err" to (it.message ?: it::class.java.simpleName)),
                        )
                    }
            } else {
                logBuffer.info(
                    "companion: associated but presence-observe unsupported (API < 31); " +
                        "relying on in-car triggers",
                )
            }
        }
    }

    /**
     * Observe presence by MAC address — the `startObservingDevicePresence(String)`
     * overload available since API 31. On API 35+ this overload is deprecated in
     * favour of `ObservingDevicePresenceRequest` (setAssociationId), but that
     * class isn't in compileSdk 35, and the String overload still functions for
     * a MAC-addressable BLE device, so we use it uniformly across API 31+. When
     * the project bumps to compileSdk 36 this can switch to the request builder
     * behind a `SDK_INT >= 35` gate.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun startObserving(manager: CompanionDeviceManager, id: Int, mac: String?) {
        // Prefer the stored WiCAN MAC; fall back to the association's own MAC.
        val address = mac ?: macForAssociation(manager, id)
        if (address != null) {
            @Suppress("DEPRECATION")
            manager.startObservingDevicePresence(address)
        } else {
            logBuffer.warn("companion: no MAC to observe")
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun stopObserving(manager: CompanionDeviceManager, id: Int) {
        val address = macForAssociation(manager, id)
        if (address != null) {
            @Suppress("DEPRECATION")
            manager.stopObservingDevicePresence(address)
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun macForAssociation(manager: CompanionDeviceManager, id: Int): String? =
        runCatching {
            manager.myAssociations.firstOrNull { it.id == id }
                ?.deviceMacAddress?.toString()
        }.getOrNull()

    /**
     * Remove duplicate CDM associations for the WiCAN, keeping exactly one.
     *
     * A re-pair — or the stale-bond auto-recovery — can register a SECOND
     * association pointing at the same MAC without disassociating the first.
     * We observe presence *by MAC*, so the OS then delivers
     * [WicanCompanionService.onDeviceAppeared] / `onDeviceDisappeared` once per
     * association. The extra callbacks double-start the bridge and drove the
     * every-~2-min presence flap that split one continuous drive into ~14
     * trips (2026-07-18): each spurious "disappeared" stopped the bridge,
     * which disconnected BLE, which made the WiCAN advertise again → a fresh
     * "appeared" restarted a new drive.
     *
     * Keeps the persisted id (or the lowest id when the persisted one isn't
     * among the matches) and disassociates the rest. Only associations sharing
     * the WiCAN MAC are touched — anything else the user associated is left
     * alone. Returns the id to keep, or the passed-in [persistedId] on any
     * failure so the caller still has something to observe.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun pruneDuplicateAssociations(
        manager: CompanionDeviceManager,
        persistedId: Int?,
        knownMac: String?,
    ): Int? = runCatching {
        val associations = manager.myAssociations
        if (associations.size <= 1) return@runCatching persistedId ?: associations.firstOrNull()?.id
        // Anchor on the WiCAN MAC so we only ever prune duplicates of OUR
        // device. Prefer the caller's known MAC; fall back to the persisted
        // association's MAC.
        val mac = knownMac?.takeIf { it.isNotBlank() }
            ?: persistedId?.let { pid ->
                associations.firstOrNull { it.id == pid }?.deviceMacAddress?.toString()
            }
        val matching = if (mac != null) {
            associations.filter { it.deviceMacAddress?.toString().equals(mac, ignoreCase = true) }
        } else {
            // No MAC to disambiguate. The app only ever associates the WiCAN,
            // so every association is presumed ours — safe to collapse to one.
            associations
        }
        if (matching.size <= 1) return@runCatching persistedId ?: matching.firstOrNull()?.id
        val keep = matching.firstOrNull { it.id == persistedId } ?: matching.minByOrNull { it.id }!!
        for (a in matching) {
            if (a.id == keep.id) continue
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.disassociate(a.id)
                } else {
                    @Suppress("DEPRECATION")
                    a.deviceMacAddress?.toString()?.let { manager.disassociate(it) }
                }
            }.onFailure {
                logBuffer.warn(
                    "companion: prune disassociate failed",
                    mapOf("err" to (it.message ?: it::class.java.simpleName), "id" to a.id),
                )
            }
            logBuffer.info(
                "companion: pruned duplicate association",
                mapOf("removed_id" to a.id, "kept_id" to keep.id),
            )
        }
        keep.id
    }.getOrElse { persistedId }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun existingAssociationId(
        manager: CompanionDeviceManager,
        knownMac: String?,
    ): Int? = runCatching {
        val associations = manager.myAssociations
        if (associations.isEmpty()) return@runCatching null
        if (knownMac != null) {
            associations.firstOrNull {
                it.deviceMacAddress?.toString().equals(knownMac, ignoreCase = true)
            }?.id
                // MAC didn't match but exactly one exists → treat as ours.
                ?: associations.singleOrNull()?.id
        } else {
            associations.singleOrNull()?.id
        }
    }.getOrNull()

    /**
     * Build the [AssociationRequest]. Prefer a [BluetoothLeDeviceFilter]: if a
     * MAC is known we pin to it (scoped, single-result); otherwise match by
     * name pattern containing "wican" (case-insensitive) plus the 0xFFF0
     * service UUID so the chooser shows just the WiCAN. We deliberately do NOT
     * call setSingleDevice(true) unless we have a MAC — without a MAC we can't
     * guarantee a single match, so we present the chooser.
     */
    private fun buildRequest(knownMac: String?): AssociationRequest? = runCatching {
        val scanFilterBuilder = ScanFilter.Builder()
        var pinnedSingle = false
        if (!knownMac.isNullOrBlank()) {
            scanFilterBuilder.setDeviceAddress(knownMac)
            pinnedSingle = true
        } else {
            // Service-UUID scope so unrelated peripherals don't clutter the
            // chooser. 0xFFF0 is the WiCAN-PRO primary BLE service.
            scanFilterBuilder.setServiceUuid(
                ParcelUuid(UUID.fromString(WICAN_SERVICE_UUID)),
            )
        }

        val leFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilterBuilder.build())
            .apply {
                if (knownMac.isNullOrBlank()) {
                    // Name regex fallback — many WiCAN units advertise a name
                    // containing "WiCAN".
                    setNamePattern(Pattern.compile(WICAN_NAME_REGEX, Pattern.CASE_INSENSITIVE))
                }
            }
            .build()

        AssociationRequest.Builder()
            .addDeviceFilter(leFilter)
            .setSingleDevice(pinnedSingle)
            .build()
    }.onFailure {
        logBuffer.warn(
            "companion: buildRequest failed",
            mapOf("err" to (it.message ?: it::class.java.simpleName)),
        )
    }.getOrNull()

    companion object {
        const val WICAN_SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
        const val WICAN_NAME_REGEX = ".*wican.*"

        /** Grace after a CDM "disappeared" before the idle-stop wait begins —
         *  rides out a momentary BLE drop near the car. */
        private const val STOP_GRACE_MS: Long = 120_000L

        /** Re-check cadence while waiting for an active drive to end. */
        private const val ALIVE_POLL_MS: Long = 30_000L

        /** Consecutive idle polls required before the idle-stop fires, so a
         *  single transient "not active" reading can't seal a live drive. */
        private const val IDLE_CONFIRM_POLLS: Int = 2
    }
}
