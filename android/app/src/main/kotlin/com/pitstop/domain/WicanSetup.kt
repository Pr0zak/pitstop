package com.pitstop.domain

/**
 * ONE state machine for "is the WiCAN set up", replacing two different
 * things that were both called "Pair":
 *
 *  1. **Bluetooth bond** — firmware 6 refuses the OBD characteristics until
 *     the phone bonds with Secure Connections + the WiCAN's static passkey.
 *     Firmware 4.x needs no bond, so this step only exists once the bridge
 *     has seen the WiCAN ask for one (or a bond exists).
 *  2. **Auto-start** — the CompanionDeviceManager association that lets the
 *     OS start logging from the background on API 31+.
 *
 * The link can't work without step 1, so that outranks step 2. Every
 * surface (top-bar pill, bridge sheet, Home, Settings, setup wizard) renders
 * from [evaluate] + the vocabulary below, so they can't contradict each
 * other the way "Pair WiCAN" (pill) vs "Paired" (Settings) did on
 * 2026-10-21. The word "Pair" is not used for anything that is already
 * paired — or, in fact, anywhere.
 *
 * CDM association does NOT bond a BLE device (the app has to call
 * createBond itself), but forgetting the device in Android Bluetooth
 * settings drops BOTH the bond and the association — so after a re-bond the
 * auto-start step usually has to run again; the primary action chains it.
 */
object WicanSetup {

    /** Android's bond with the picked WiCAN; [Unknown] = no permission / adapter. */
    enum class Bond { None, Bonding, Bonded, Unknown }

    /** What the bridge last reported about pairing (mirrors BlePairing). */
    enum class LinkPairing { None, InProgress, Required, Failed, Rejected }

    data class Inputs(
        /** A WiCAN has been picked (BLE MAC in Settings) or associated. */
        val deviceKnown: Boolean,
        val bond: Bond,
        /** Learned: this WiCAN demands a bond (firmware 6). */
        val bondRequired: Boolean,
        val linkPairing: LinkPairing = LinkPairing.None,
        /** The auto-start master toggle. */
        val autoStartOn: Boolean,
        /** API 31+ — CDM presence is what makes auto-start reliable. */
        val cdmSupported: Boolean,
        val cdmAssociated: Boolean,
        /** "OBD via BLE" collector on; off = GPS-only, no WiCAN to nag about. */
        val bleEnabled: Boolean = true,
    )

    enum class State {
        /** No WiCAN picked yet. */
        NoDevice,
        /** Firmware 6 and no (working) bond — the link cannot come up. */
        BluetoothNeeded,
        /** The system passkey dialog is up. */
        BluetoothInProgress,
        /** A bond exists but the WiCAN refuses it (reset / reflashed). */
        BluetoothRejected,
        /** Link fine, but auto-start can't fire from the background. */
        AutoStartOff,
        /** Nothing to do. */
        Ready,
    }

    fun evaluate(i: Inputs): State = when {
        !i.bleEnabled -> State.Ready
        !i.deviceKnown -> State.NoDevice
        i.linkPairing == LinkPairing.Rejected -> State.BluetoothRejected
        i.bond == Bond.Bonding || i.linkPairing == LinkPairing.InProgress -> State.BluetoothInProgress
        // A bond the bridge says isn't working (Required/Failed while
        // bonded can't happen in practice; treat it as needed too).
        i.linkPairing == LinkPairing.Required || i.linkPairing == LinkPairing.Failed -> State.BluetoothNeeded
        i.bondRequired && i.bond == Bond.None -> State.BluetoothNeeded
        i.autoStartOn && i.cdmSupported && !i.cdmAssociated -> State.AutoStartOff
        else -> State.Ready
    }

    /** True when the link itself can't work — the loud states. */
    fun blocksLink(s: State): Boolean =
        s == State.NoDevice || s == State.BluetoothNeeded || s == State.BluetoothRejected

    enum class Tone { Neutral, Working, Warn, Bad }

    /** The top-bar pill; null = let the bridge-phase pill speak. */
    fun pill(s: State): Pair<String, Tone>? = when (s) {
        State.NoDevice, State.BluetoothNeeded -> "Connect WiCAN" to Tone.Warn
        State.BluetoothRejected -> "Connect WiCAN" to Tone.Bad
        State.BluetoothInProgress -> "Connecting WiCAN…" to Tone.Working
        State.AutoStartOff -> "Auto-start off" to Tone.Neutral
        State.Ready -> null
    }

    /** One sentence for the bridge sheet / Home / Settings; null when Ready. */
    fun message(s: State): String? = when (s) {
        State.NoDevice -> "Pick your WiCAN so pitstop can read the car."
        State.BluetoothNeeded ->
            "Your WiCAN needs a Bluetooth connection with this phone. You'll type the passkey " +
                "shown on the WiCAN's Settings › Bluetooth page."
        State.BluetoothInProgress ->
            "Type the passkey shown on the WiCAN's Settings › Bluetooth page into the Android dialog."
        State.BluetoothRejected ->
            "The WiCAN no longer accepts this phone's saved Bluetooth connection — it was probably " +
                "reset or reflashed. Reconnect it and type its passkey again (if that fails, forget " +
                "the WiC_ device in Android Bluetooth settings first)."
        State.AutoStartOff ->
            "The WiCAN works, but drives won't start logging on their own."
        State.Ready -> null
    }

    /** The ONE primary action's label; null = nothing to tap. */
    fun action(s: State): String? = when (s) {
        State.NoDevice, State.BluetoothNeeded -> "Connect WiCAN"
        State.BluetoothRejected -> "Reconnect WiCAN"
        State.AutoStartOff -> "Turn on auto-start"
        State.BluetoothInProgress, State.Ready -> null
    }

    /** Settings step 1 line. */
    fun bluetoothStep(i: Inputs): String = when {
        !i.deviceKnown -> "Bluetooth: no WiCAN picked"
        i.linkPairing == LinkPairing.Rejected -> "Bluetooth: saved connection rejected"
        i.bond == Bond.Bonding || i.linkPairing == LinkPairing.InProgress -> "Bluetooth: waiting for passkey"
        i.bond == Bond.Bonded -> "Bluetooth: connected (bonded)"
        i.bondRequired -> "Bluetooth: needs connecting"
        i.bond == Bond.Unknown -> "Bluetooth: status unavailable"
        else -> "Bluetooth: no bond needed (firmware 4.x)"
    }

    /** Settings step 2 line. */
    fun autoStartStep(i: Inputs): String = when {
        !i.autoStartOn -> "Auto-start: switched off in Auto-start settings"
        !i.cdmSupported -> "Auto-start: on"
        i.cdmAssociated -> "Auto-start: on"
        else -> "Auto-start: off — needs the WiCAN linked for background start"
    }
}
