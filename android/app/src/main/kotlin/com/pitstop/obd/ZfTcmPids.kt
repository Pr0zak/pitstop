package com.pitstop.obd

/**
 * Mode 22 "extended" PIDs on the ZF 9HP transmission controller.
 *
 * **Opt-in and off by default** — see `SettingsRepository.extendedPidsEnabled`.
 * These are not standard SAE PIDs; they are module-specific DIDs on a module
 * that has to be addressed directly, and both facts make them riskier than
 * anything in [Pids.DEFAULT]:
 *
 *  - They require a **TX header change** (`ATSH18DA1EF1`) which is sticky for
 *    the rest of the ELM session, so every poll of one of these obliges the
 *    poller to restore the default header afterwards.
 *  - They answer with **multi-frame ISO-TP**, which the standard single-frame
 *    parser cannot read at all.
 *
 * ### Measured on the vehicle (2026-07-31)
 * ```
 *   module 0x1E   ZF TCM, TX header 18DA1EF1
 *   ATF temp      request 223083 -> payload[17], degC = byte - 40   (live: 61 degC)
 *   gear          request 223086 -> payload[23]
 *                 0 = Park or Neutral (indistinguishable), 1..9 = forward, 15 = Reverse
 * ```
 * The gear decode was validated against a recorded D -> R -> N -> D shift
 * sequence, which produced 2 -> 15 -> 0 -> 2 (4/4). Note the **2**: the ZF 9HP
 * launches in second gear, so sitting still in Drive reads 2, not 1. Anything
 * that "corrects" that to 1 is wrong.
 *
 * "payload" throughout means the reassembled ISO-TP block starting at the 0x62
 * positive-response byte — see [IsoTp]. The device truncates its own
 * reassembly at roughly 34 payload bytes; both offsets used here sit safely
 * inside that, and a short block decodes to null rather than to a wrong value.
 */
object ZfTcm {

    /** ELM command that points requests at the transmission controller. */
    const val TX_HEADER = "ATSH18DA1EF1"

    const val ATF_DID = 0x3083
    const val GEAR_DID = 0x3086

    /** Offset of the ATF temperature byte inside the ISO-TP payload. */
    const val ATF_PAYLOAD_INDEX = 17

    /** Offset of the gear byte inside the ISO-TP payload. */
    const val GEAR_PAYLOAD_INDEX = 23

    /** Raw gear byte the TCM reports for Reverse. */
    const val GEAR_REVERSE = 15

    /** Highest forward gear on the 9HP. */
    private const val GEAR_MAX_FORWARD = 9

    /**
     * ATF temperature in degrees C, or null if the payload is too short.
     *
     * Same -40 offset as the standard coolant / intake temperature PIDs.
     */
    fun decodeAtfTempC(payload: ByteArray): Double? {
        val raw = payload.getOrNull(ATF_PAYLOAD_INDEX)?.toInt()?.and(0xFF) ?: return null
        return raw - 40.0
    }

    /**
     * Gear position as a plain number: 0 for Park/Neutral, 1..9 for a forward
     * gear, 15 for Reverse.
     *
     * Codes outside that set have never been observed and have no known
     * meaning, so they decode to null — publishing an unknown code as a gear
     * number would put a value on the chart that doesn't correspond to
     * anything the transmission is doing.
     */
    fun decodeGearPosition(payload: ByteArray): Double? {
        val raw = payload.getOrNull(GEAR_PAYLOAD_INDEX)?.toInt()?.and(0xFF) ?: return null
        return when (raw) {
            in 0..GEAR_MAX_FORWARD -> raw.toDouble()
            GEAR_REVERSE -> raw.toDouble()
            else -> null
        }
    }

    /**
     * ATF temperature, published as `atf_temp` (degC).
     *
     * 10 s cadence: transmission fluid has an enormous thermal mass and takes
     * minutes to move a degree, so anything faster is pure bus time for no
     * extra information.
     */
    val AtfTemp = Pid(
        name = "atf_temp",
        mode = 0x22,
        pid = ATF_DID,
        periodMs = 10_000,
        parser = ::decodeAtfTempC,
        initCommands = listOf(TX_HEADER),
        isoTp = true,
        request = "223083",
    )

    /**
     * Gear position, published as `gear_position` (unitless).
     *
     * 2 s cadence: fast enough to see the shift pattern of a drive without
     * spending a header-change round-trip every second. Shifts between samples
     * are expected and fine — this is a state trace, not a shift-quality
     * instrument.
     */
    val GearPosition = Pid(
        name = "gear_position",
        mode = 0x22,
        pid = GEAR_DID,
        periodMs = 2_000,
        parser = ::decodeGearPosition,
        initCommands = listOf(TX_HEADER),
        isoTp = true,
        request = "223086",
    )

    val ALL = listOf(AtfTemp, GearPosition)
}
