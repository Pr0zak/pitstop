package com.pitstop.obd

/**
 * Honda Mode 22 PIDs for the 2019 Pilot, beyond the ZF TCM pair in [ZfTcm].
 *
 * **Opt-in, default off, and UNVERIFIED over BLE on firmware 6.** The same
 * decoders are proven against the WiCAN's own HTTP/MQTT polling on firmware
 * 6.0.0-beta.2 (2026-10-21, see `docs/research/honda-pilot-pids.md`); what is
 * not yet proven is that the raw `br_ble_obd` bridge passes every line of a
 * 39–59-byte multi-frame answer through to the phone. The phone polls these
 * only when the WiCAN is off home WiFi (on firmware 6 its WiFi is suspended
 * while a BLE client is attached, so only the phone can capture them then).
 *
 * Every request is: `ATSH18DA<mm>F1` + `ATCRA18DAF1<mm>` → `22xxxx` →
 * restore (`ATSH18DB33F1`, `ATCRA` — [ElmSession.DEFAULT_HEADER_RESTORE]).
 * "payload" is the reassembled ISO-TP block with `payload[0] == 0x62` and
 * `payload[1..2]` = DID echo; offsets are payload-absolute. A block too short
 * for an offset decodes to null / nothing, never to a guess.
 *
 * ```
 *   module 1E (TCM)      223083 ATF, 223086 gear          -> see ZfTcm
 *   module 10 (PCM)      222615 active cylinders = p[53]
 *                        222663 misfires cyl n = p[35+2n..36+2n] BE, n = 1..6
 *   module 60 (cluster)  227022 odometer mi = p[9..11] BE (24-bit)
 *                        227029 fuel L = p[15..16] BE / 100
 *                        227028 outside °F = 12-bit signed ((p[19]&0x0F)<<8 | p[20])
 *   module 26 (TPMS)     226001 kPa FR=[11..12] FL=[13..14] RR=[15..16] RL=[17..18]
 *                               °C (signed) FR=[27] FL=[28] RR=[29] RL=[30]
 * ```
 */
object HondaMode22 {

    const val PCM = 0x10
    const val TCM = 0x1E
    const val CLUSTER = 0x60
    const val TPMS = 0x26

    /** TX header + RX filter that address [module]; two separate ELM lines. */
    fun headerFor(module: Int): List<String> = listOf(
        "ATSH18DA%02XF1".format(module),
        "ATCRA18DAF1%02X".format(module),
    )

    private fun u8(p: ByteArray, i: Int): Int? = p.getOrNull(i)?.toInt()?.and(0xFF)

    private fun u16(p: ByteArray, i: Int): Int? {
        val hi = u8(p, i) ?: return null
        val lo = u8(p, i + 1) ?: return null
        return (hi shl 8) or lo
    }

    // ---- decoders ---------------------------------------------------------

    const val CYLINDERS_INDEX = 53

    /** 6 normally, 3 under VCM. Anything outside 1..6 has no meaning → null. */
    fun decodeActiveCylinders(p: ByteArray): Double? {
        val n = u8(p, CYLINDERS_INDEX) ?: return null
        return if (n in 1..6) n.toDouble() else null
    }

    /** misfire_count_1..6, or empty when the block is too short for all six. */
    fun decodeMisfires(p: ByteArray): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (n in 1..6) {
            val v = u16(p, 35 + 2 * n) ?: return emptyMap()
            out["misfire_count_$n"] = v.toDouble()
        }
        return out
    }

    /** Cluster odometer, MILES — equals the dash exactly. */
    fun decodeOdometerMi(p: ByteArray): Double? {
        val a = u8(p, 9) ?: return null
        val b = u8(p, 10) ?: return null
        val c = u8(p, 11) ?: return null
        return ((a shl 16) or (b shl 8) or c).toDouble()
    }

    /** Cluster fuel quantity, litres. */
    fun decodeFuelLitres(p: ByteArray): Double? = u16(p, 15)?.let { it / 100.0 }

    /** Cluster outside-air temperature, °F (12-bit two's complement). */
    fun decodeAmbientF(p: ByteArray): Double? {
        val hi = u8(p, 19) ?: return null
        val lo = u8(p, 20) ?: return null
        val raw = ((hi and 0x0F) shl 8) or lo
        return (if (raw >= 0x800) raw - 0x1000 else raw).toDouble()
    }

    /**
     * Four pressures (kPa) + four temps (°C) from one 226001 answer. A
     * pressure of 0xFFFF (no sensor reply) is left out rather than published.
     * Empty when the block is too short for the temps (index 30).
     */
    fun decodeTpms(p: ByteArray): Map<String, Double> {
        if (p.size < 31) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        val pressure = listOf("fr" to 11, "fl" to 13, "rr" to 15, "rl" to 17)
        for ((corner, i) in pressure) {
            val v = u16(p, i) ?: continue
            if (v != 0xFFFF) out["tire_pressure_$corner"] = v.toDouble()
        }
        val temp = listOf("fr" to 27, "fl" to 28, "rr" to 29, "rl" to 30)
        for ((corner, i) in temp) {
            val b = p.getOrNull(i) ?: continue
            out["tire_temp_$corner"] = b.toInt().toDouble() // signed byte
        }
        return out
    }

    // ---- PIDs -------------------------------------------------------------

    private fun did(did: Int, module: Int, name: String, periodMs: Long, parser: (ByteArray) -> Double?) = Pid(
        name = name,
        mode = 0x22,
        pid = did,
        periodMs = periodMs,
        parser = parser,
        initCommands = headerFor(module),
        isoTp = true,
        request = "22%04X".format(did),
    )

    private fun multi(did: Int, module: Int, name: String, periodMs: Long, outputs: (ByteArray) -> Map<String, Double>) = Pid(
        name = name,
        mode = 0x22,
        pid = did,
        periodMs = periodMs,
        parser = { null },
        initCommands = headerFor(module),
        isoTp = true,
        request = "22%04X".format(did),
        outputs = outputs,
    )

    /** 2 s: VCM drops in and out with load; slower misses whole episodes. */
    val ActiveCylinders = did(0x2615, PCM, "active_cylinders", 2_000, ::decodeActiveCylinders)

    /** 60 s: cumulative counters — only their growth matters. */
    val Misfires = multi(0x2663, PCM, "misfire_counts", 60_000, ::decodeMisfires)

    val OdometerDash = did(0x7022, CLUSTER, "odometer_dash", 30_000, ::decodeOdometerMi)
    val FuelLevelLitres = did(0x7029, CLUSTER, "fuel_level_l", 30_000, ::decodeFuelLitres)
    val AmbientAirTempF = did(0x7028, CLUSTER, "ambient_air_temp_f", 30_000, ::decodeAmbientF)

    /** One DID, eight metrics: tire_pressure_* (kPa) + tire_temp_* (°C). */
    val Tpms = multi(0x6001, TPMS, "tpms", 30_000, ::decodeTpms)

    /**
     * Everything the opt-in extended poll covers, grouped by module so the
     * poller can set a header once per module per batch.
     */
    val ALL: List<Pid> = listOf(
        ZfTcm.GearPosition, ZfTcm.AtfTemp,
        ActiveCylinders, Misfires,
        OdometerDash, FuelLevelLitres, AmbientAirTempF,
        Tpms,
    )
}
