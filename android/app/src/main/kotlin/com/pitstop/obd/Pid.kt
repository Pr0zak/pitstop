package com.pitstop.obd

/**
 * Baseline OBD-II Mode 01 PIDs that the bridge polls. The metric names mirror
 * what the WiCAN AutoPID profile publishes for the Honda Pilot 2019 — see
 * `pid_profiles/honda-pilot-2019.json` in the backend.
 *
 * The "request" string is the ASCII OBD-II ELM-style command (Mode 01 + PID).
 * WiCAN's BLE bridge accepts both raw OBD frames and ELM-style ASCII; ELM is
 * cheaper to debug. If the device firmware needs raw CAN frames the formatter
 * here is the only place to swap.
 */
data class Pid(
    val name: String,
    val mode: Int,
    val pid: Int,
    val periodMs: Long,
    /**
     * Decode the response into a metric value, or null to drop the sample.
     *
     * The bytes handed in depend on [isoTp]:
     *  - **false** (every standard Mode 01 PID): the data bytes *after* the
     *    mode/PID echo, so `bytes[0]` is SAE's "A".
     *  - **true**: the whole reassembled ISO-TP payload starting at the
     *    positive-response byte, so `payload[0] == 0x62`, `payload[1..2]` is
     *    the DID echo and a documented offset like "byte 17" is literally
     *    `payload[17]`. Extended-PID offsets are measured against the full
     *    payload on the vehicle, so re-basing them here would just be an
     *    invitation to an off-by-three.
     */
    val parser: (ByteArray) -> Double?,
    /**
     * Bare ELM commands (no trailing `\r` — the caller appends) that must be
     * sent before [command] for this PID to be answerable. Today that means a
     * TX-header change to address a non-default module, e.g. `ATSH18DA1EF1`
     * for the ZF transmission controller.
     *
     * **Setting a header is sticky for the whole ELM session**, so a PID that
     * declares init commands obliges the poller to restore the default header
     * afterwards — see [ElmSession.DEFAULT_HEADER_RESTORE]. Empty (the
     * default) means the PID is answered on the broadcast header and nothing
     * about the session changes.
     */
    val initCommands: List<String> = emptyList(),
    /**
     * True when the response arrives as a multi-frame ISO-TP block that needs
     * [IsoTp.reassemble] before the parser can see it. Standard Mode 01 PIDs
     * are single-frame and leave this false, which keeps them on the untouched
     * [ObdResponseParser] path.
     */
    val isoTp: Boolean = false,
    /**
     * Explicit request string override, used by Mode 22 where the identifier
     * is a 2-byte DID ("223083") and doesn't fit the `%02X%02X` mode+PID
     * formatting. Null keeps the original behaviour exactly.
     */
    val request: String? = null,
) {
    /** ELM327 ASCII command string for this PID, e.g. "010C\r" for engine RPM. */
    fun command(): String = request?.let { "$it\r" } ?: "%02X%02X\r".format(mode, pid)

    /**
     * True when polling this PID mutates ELM session state that later requests
     * would inherit. The poller must restore the default header afterwards.
     */
    val changesSessionHeader: Boolean get() = initCommands.isNotEmpty()

    /** True for anything that needs the extended request/response path. */
    val isExtended: Boolean get() = isoTp || initCommands.isNotEmpty()

    /**
     * For an [isoTp] Mode 22 PID: does this payload echo the DID we asked for?
     * Guards against attributing another module's in-flight answer to this PID
     * — the offsets are DID-specific, so a mismatched echo must be dropped,
     * not decoded.
     */
    fun matchesDidEcho(payload: ByteArray): Boolean {
        if (!isoTp) return true
        if (payload.size < 3) return false
        val hi = (pid shr 8) and 0xFF
        val lo = pid and 0xFF
        return (payload[1].toInt() and 0xFF) == hi && (payload[2].toInt() and 0xFF) == lo
    }
}

/** ELM session-level commands that aren't tied to any one PID. */
object ElmSession {
    /**
     * Put the session back on the default OBD-II broadcast header.
     *
     * **Two commands, two lines.** `ATSH7DF` restores the functional-broadcast
     * TX header and `ATCRA` (no argument) resets the CAN receive-address
     * filter. They must be written as separate CR-terminated lines: an ELM327
     * parses exactly one command per line, so the concatenated form
     * `ATSH7DFATCRA` is read as `AT SH 7DFATCRA` — an SH argument that is
     * neither 3, 6 nor 8 hex digits ('T' and 'R' aren't hex) — and answers
     * `?` without changing the header at all. That silently turned the whole
     * restore into a no-op.
     *
     * This exists because a TX header is **sticky**: once set, every
     * subsequent request inherits it. The WiCAN dongle demonstrated the
     * failure mode first-hand — adding one header-changing PID to its
     * round-robin collapsed its published PID stream from 62 keys to 19,
     * because everything polled after it was still addressed at the module
     * that only answers two of them. Any code path that sets a header owns
     * restoring it, unconditionally.
     */
    val DEFAULT_HEADER_RESTORE: List<String> = listOf("ATSH7DF", "ATCRA")
}

object Pids {

    /** A=byte0, B=byte1 etc. of the response data bytes (after the mode/PID echo). */
    private fun byte(b: ByteArray, i: Int): Int? = b.getOrNull(i)?.toInt()?.and(0xFF)

    val EngineRpm = Pid(
        name = "engine_rpm",
        mode = 0x01,
        pid = 0x0C,
        periodMs = 1_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 4.0
        },
    )

    val VehicleSpeed = Pid(
        name = "vehicle_speed",
        mode = 0x01,
        pid = 0x0D,
        periodMs = 1_000,
        parser = { bytes -> byte(bytes, 0)?.toDouble() },
    )

    val CoolantTemp = Pid(
        name = "coolant_temp",
        mode = 0x01,
        pid = 0x05,
        periodMs = 5_000,
        parser = { bytes -> byte(bytes, 0)?.let { it - 40.0 } },
    )

    val ControlModuleVoltage = Pid(
        name = "control_module_voltage",
        mode = 0x01,
        pid = 0x42,
        periodMs = 5_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 1000.0
        },
    )

    val FuelLevel = Pid(
        name = "fuel_level",
        mode = 0x01,
        pid = 0x2F,
        periodMs = 30_000,
        parser = { bytes -> byte(bytes, 0)?.let { it * 100.0 / 255.0 } },
    )

    val ThrottlePosition = Pid(
        name = "throttle_position",
        mode = 0x01,
        pid = 0x11,
        periodMs = 1_000,
        parser = { bytes -> byte(bytes, 0)?.let { it * 100.0 / 255.0 } },
    )

    val IntakeAirTemp = Pid(
        name = "intake_air_temp",
        mode = 0x01,
        pid = 0x0F,
        periodMs = 5_000,
        parser = { bytes -> byte(bytes, 0)?.let { it - 40.0 } },
    )

    val EngineLoad = Pid(
        name = "engine_load",
        mode = 0x01,
        pid = 0x04,
        periodMs = 1_000,
        parser = { bytes -> byte(bytes, 0)?.let { it * 100.0 / 255.0 } },
    )

    val ManifoldPressure = Pid(
        name = "manifold_pressure",
        mode = 0x01,
        pid = 0x0B,
        periodMs = 1_000,
        parser = { bytes -> byte(bytes, 0)?.toDouble() },  // kPa absolute
    )

    /**
     * Mode 01 PID 0x10 — "MAF air flow rate". **Live-probed unsupported on
     * this PCM (2026-07-31): the ECU does not advertise 0x10 in its supported-
     * PID bitmap and never answers it.** Kept as a definition because other
     * vehicles do support it, but deliberately OUT of [DEFAULT] — polling it
     * burned a round-robin slot every cycle for a guaranteed NO DATA.
     *
     * Airflow on this vehicle comes from [MafSensorA] (PID 0x66) instead, and
     * fuel from [EngineFuelRate] (PID 0x9D) which needs no airflow at all.
     */
    val MafAirFlow = Pid(
        name = "maf_air_flow",
        mode = 0x01,
        pid = 0x10,
        periodMs = 1_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 100.0  // g/s
        },
    )

    /**
     * Mode 01 PID 0x66 — "Mass air flow sensor". The ONLY airflow source on
     * this vehicle: 0x10 above never produced a single reading on the 2019
     * Pilot — 22,593 historical `maf_air_flow` rows were ALL published by
     * the WiCAN, none by the phone. The WiCAN's AutoPID profile had BOTH
     * `10-MAFAirFlowRate` and `66-MAFSensorA` enabled and the backend
     * aliases both to `maf_air_flow`, so which one this PCM actually
     * answered was ambiguous. Polling both settled it: **0x66 answers,
     * 0x10 does not** (live-probed 2026-07-31), and 0x10 has since been
     * dropped from [DEFAULT].
     *
     * Deliberately published under its OWN metric name rather than
     * `maf_air_flow`: if this PCM answers both, a shared name would put
     * two independent sample streams into one metric and the backend's
     * fuel integration would DOUBLE-COUNT the burn.
     *
     * Layout: A = sensor-support bitmap (bit0 = A present, bit1 = B),
     * B,C = sensor A in g/s scaled x32, D,E = sensor B. We read sensor A
     * and only when the bitmap claims it exists.
     */
    val MafSensorA = Pid(
        // Publishes as `maf_air_flow`, NOT `maf_sensor_a`. 0x66 and 0x10 are
        // two wire encodings of the same physical quantity — mass air flow in
        // g/s — and the server already canonicalises the WiCAN's
        // `66-MAFSensorA` to `maf_air_flow`. Publishing a second name split
        // the same sensor across two metrics depending on whether the data
        // came over BLE or WiFi: every consumer (Live tile, car tile,
        // _TRIP_SAMPLE_METRICS) reads `maf_air_flow`, so phone-bridged drives
        // showed an empty MAF tile and no MAF on the trip chart, while the
        // identical reading from the dongle displayed fine.
        name = "maf_air_flow",
        mode = 0x01,
        pid = 0x66,
        periodMs = 1_000,
        parser = { bytes ->
            val support = byte(bytes, 0) ?: return@Pid null
            if (support and 0x01 == 0) return@Pid null  // sensor A not present
            val b = byte(bytes, 1) ?: return@Pid null
            val c = byte(bytes, 2) ?: return@Pid null
            ((b * 256) + c) / 32.0  // g/s
        },
    )

    /**
     * Mode 01 PID 0x9D — "Engine fuel rate". **Live-probed and supported on this
     * 2019 Pilot Elite V6** (unlike PID 0x10, which this ECU does not advertise
     * or answer at all — see [MafAirFlow]).
     *
     * ### Why this is preferred over integrating MAF
     * This is the ECU's OWN fuel calculation — the number the PCM derives from
     * the injector pulse-widths it is actually commanding. Deriving fuel from
     * airflow instead (`maf_g_s / 14.7`) bakes in the assumption that the engine
     * runs at stoichiometric 14.7:1 forever, which is wrong in two directions
     * that both matter for a trip-level total:
     *  - **Power enrichment.** Under load / WOT the PCM commands a rich AFR
     *    (~12:1). MAF integration at 14.7 *under*-reports the burn exactly when
     *    the burn is highest.
     *  - **DFCO (deceleration fuel cut-off).** On a closed-throttle over-run the
     *    PCM shuts the injectors off completely — real fuel rate is 0.0 — but
     *    air is still being pumped through the engine, so MAF integration keeps
     *    charging fuel for every lift-off and coast-down. That is a systematic
     *    *over*-report on exactly the driving that should be free.
     * PID 0x9D has both effects already folded in, so it needs no correction
     * factor and no AFR model. Integrate it directly for grams burned.
     *
     * ### Layout
     * Response after the `41 9D` echo is 4 data bytes: `A B C D`. A,B are the
     * engine fuel rate; C,D are a second (vehicle-level) rate that this ECU
     * mirrors. We read A,B only.
     *
     * Observed raw frame at idle:
     * `18 DA F1 10 06 41 9D 00 13 00 13` → A=0x00 B=0x13 C=0x00 D=0x13,
     * sampling 20, 20, 19, 20 across consecutive polls.
     *
     * ### Empirical scaling derivation
     * SAE J1979's published scalings did not fit, so the multiplier was pinned
     * by cross-checking against a *simultaneous* MAF Sensor A ([MafSensorA],
     * PID 0x66) reading at steady idle:
     *
     * ```
     *   MAF        = 5.59 g/s air
     *   stoich     = 14.7:1  (valid at closed-loop idle, no enrichment)
     *   => fuel    = 5.59 / 14.7 = 0.380 g/s   (reference)
     *
     *   raw        = (A*256)+B = 20
     *   x 0.02     -> 0.400 g/s   ->   +5.2%  vs reference   <-- chosen
     *   SAE  / 32  -> 0.625 g/s   ->   +64%
     *          / 20 -> 1.000 g/s   ->  +163%
     * ```
     * 5.2% is within the noise of a single-point idle cross-check (MAF sensor
     * tolerance alone is ~±3%), while /32 and /20 are off by factors, so
     * `raw * 0.02` is the scaling. Units are **grams per second**.
     *
     * A raw value of 0 is a legitimate reading (engine off, or DFCO on
     * over-run) and must be reported as `0.0`, never dropped as an error.
     */
    val EngineFuelRate = Pid(
        name = "engine_fuel_rate",
        mode = 0x01,
        pid = 0x9D,
        periodMs = 1_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) * 0.02  // g/s
        },
    )

    /**
     * Exhaust mass flow, Mode 01 PID 0x9E. `(A*256+B)/5` -> kg/h.
     *
     * The WiCAN's own firmware decoder for this PID is broken (it publishes
     * a constant 0), which is why the dongle needs a custom-PID expression
     * for it. The phone is NOT subject to that: it talks ELM327 directly and
     * parses the raw frame itself, exactly as it already does for 0x9D.
     */
    val ExhaustFlow = Pid(
        name = "engine_exhaust_flow",
        mode = 0x01,
        pid = 0x9E,
        periodMs = 2_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 5.0  // kg/h
        },
    )

    /** Catalyst temperature bank 1 sensor 1, PID 0x3C. `(A*256+B)/10 - 40` degC. */
    val CatalystTempB1 = Pid(
        name = "catalyst_temp_b1",
        mode = 0x01,
        pid = 0x3C,
        periodMs = 10_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 10.0 - 40.0
        },
    )

    /** Catalyst temperature bank 2 sensor 1, PID 0x3D. Same scaling as B1. */
    val CatalystTempB2 = Pid(
        name = "catalyst_temp_b2",
        mode = 0x01,
        pid = 0x3D,
        periodMs = 10_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 10.0 - 40.0
        },
    )

    /**
     * O2 sensor 1 wide-range equivalence ratio (lambda), PID 0x24.
     * `(A*256+B) * 2 / 65536`. C,D carry the sensor voltage, which is the
     * field the WiCAN reports as an invariant 2.000 V and which
     * wican_aliases.py therefore refuses to alias — we only take lambda.
     */
    val O2S1Lambda = Pid(
        name = "o2_s1_lambda",
        mode = 0x01,
        pid = 0x24,
        periodMs = 2_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) * 2.0 / 65536.0
        },
    )

    /** Commanded air-fuel equivalence ratio, PID 0x44. Same scaling as 0x24. */
    val CommandedAfrRatio = Pid(
        name = "commanded_afr_ratio",
        mode = 0x01,
        pid = 0x44,
        periodMs = 2_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) * 2.0 / 65536.0
        },
    )

    /** Fuel rail gauge pressure, PID 0x23. `(A*256+B) * 10` -> kPa (~3500 here). */
    val FuelRailPressure = Pid(
        name = "fuel_rail_pressure",
        mode = 0x01,
        pid = 0x23,
        periodMs = 5_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) * 10.0  // kPa
        },
    )

    /** Commanded EGR, PID 0x2C. `A*100/255` %. Reads a constant 0 on this
     *  Pilot (no EGR command exposed) — polled slowly so it costs nothing,
     *  and kept because other vehicles do answer it. */
    val CommandedEgr = Pid(
        name = "commanded_egr",
        mode = 0x01,
        pid = 0x2C,
        periodMs = 10_000,
        parser = { bytes -> byte(bytes, 0)?.let { it * 100.0 / 255.0 } },
    )

    /** Commanded evaporative purge, PID 0x2E. `A*100/255` %. */
    val CommandedEvapPurge = Pid(
        name = "commanded_evap_purge",
        mode = 0x01,
        pid = 0x2E,
        periodMs = 10_000,
        parser = { bytes -> byte(bytes, 0)?.let { it * 100.0 / 255.0 } },
    )

    /**
     * Intake air temperature via PID 0x68, NOT the standard 0x0F.
     *
     * 0x0F answers NO DATA on this PCM — [IntakeAirTemp] above is kept for
     * vehicles that do support it, but on the Pilot it never returns. 0x68
     * is the multi-sensor intake-temperature PID: A is a sensor-support
     * bitmap and the temperatures follow.
     *
     * The offset was established empirically against the real vehicle (see
     * docs/research/honda-pilot-pids.md): the working expression is `B5-40`
     * in the WiCAN's MQTT byte indexing, where B3 = A. So B5 = C, i.e. the
     * THIRD data byte here. Do not "correct" this to B without re-probing
     * the car — the obvious-looking offset is the one that reads -39 degC.
     */
    val IntakeAirTempHonda = Pid(
        name = "intake_air_temp",
        mode = 0x01,
        pid = 0x68,
        periodMs = 10_000,
        parser = { bytes ->
            val support = byte(bytes, 0) ?: return@Pid null
            if (support == 0) return@Pid null
            byte(bytes, 2)?.let { it - 40.0 }
        },
    )

    val Odometer = Pid(
        name = "odometer",
        mode = 0x01,
        pid = 0xA6,
        // Odometer changes < 0.05 km/s even at WOT highway speeds.
        // Polling once every 30 s captures trip start + end accurately
        // without crowding the round-robin slots that fuel_level,
        // RPM, speed need at higher cadence.
        periodMs = 30_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            val c = byte(bytes, 2) ?: return@Pid null
            val d = byte(bytes, 3) ?: return@Pid null
            // SAE J1979 PID 0xA6: 4-byte unsigned, value in 0.1 km
            // units. Cast to Long up front so the << 24 doesn't
            // overflow Int when A is large.
            val raw = (a.toLong() shl 24) or
                (b.toLong() shl 16) or
                (c.toLong() shl 8) or
                d.toLong()
            raw / 10.0  // km
        },
    )

    val RunTimeSinceStart = Pid(
        name = "run_time_since_start",
        mode = 0x01,
        pid = 0x1F,
        periodMs = 5_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b).toDouble()  // seconds
        },
    )

    private fun fuelTrim(b: ByteArray): Double? =
        byte(b, 0)?.let { (it - 128) * 100.0 / 128.0 }

    val StftB1 = Pid(name = "stft_b1", mode = 0x01, pid = 0x06, periodMs = 2_000, parser = ::fuelTrim)
    val LtftB1 = Pid(name = "ltft_b1", mode = 0x01, pid = 0x07, periodMs = 5_000, parser = ::fuelTrim)
    val StftB2 = Pid(name = "stft_b2", mode = 0x01, pid = 0x08, periodMs = 2_000, parser = ::fuelTrim)
    val LtftB2 = Pid(name = "ltft_b2", mode = 0x01, pid = 0x09, periodMs = 5_000, parser = ::fuelTrim)

    /**
     * Default poll set. ATF temp (Honda Mode 22 0x2201) is intentionally OUT — that
     * is the WiCAN device's job (driveway publish over wifi). The phone bridge focuses
     * on the moving-vehicle path: live engine state + GPS + the standard Mode 01 PIDs
     * the Live view expects to see populated.
     */
    val DEFAULT = listOf(
        EngineRpm,
        VehicleSpeed,
        ThrottlePosition,
        CoolantTemp,
        ControlModuleVoltage,
        FuelLevel,
        EngineLoad,
        ManifoldPressure,
        MafSensorA,
        EngineFuelRate,
        Odometer,
        StftB1, LtftB1, StftB2, LtftB2,
        // Everything below was absent, which is why the Live screen showed
        // "—" for a third of its tiles on any BLE drive: the WiCAN polls ~50
        // PIDs, the phone polled 15, and the UI renders tiles for the union.
        // Periods are deliberately uneven — the scheduler is next-due based
        // (PitstopBridgeService round-robin), so a 10 s PID costs a slot once
        // per 10 s and does not slow RPM or speed.
        RunTimeSinceStart,
        IntakeAirTempHonda,
        ExhaustFlow,
        O2S1Lambda, CommandedAfrRatio,
        FuelRailPressure,
        CatalystTempB1, CatalystTempB2,
        CommandedEgr, CommandedEvapPurge,
        // MafAirFlow (0x10) is deliberately NOT here. It was added at ~1 Hz
        // to fix the backend's FUEL-DECREMENT-NULL — fuel-consumed
        // integration needs an airflow stream during phone-BLE drives,
        // because the WiCAN AutoPID path only publishes over WiFi in the
        // driveway and cellular drives had no airflow source. Live probing
        // on 2026-07-31 measured 0x10 as UNSUPPORTED on this PCM: it is not
        // in the supported-PID bitmap and never answers, so every poll spent
        // a round-robin slot to log a NO DATA. [MafSensorA] (0x66) is the
        // airflow source that this ECU actually answers, and it stays.
        // The [MafAirFlow] definition itself is kept for vehicles that do
        // support 0x10. Std PIDs 0x0F (intake_air_temp) + 0x1F
        // (run_time_since_start) stay OUT — the WiCAN AutoPID handles the
        // Honda-extended 0x66/67/68 equivalents over WiFi MQTT;
        // WiCanSubscriber fans those into the BridgeStateBus.
        //
        // EngineFuelRate (0x9D) is live-probed supported on this PCM and is
        // the PREFERRED fuel-integration source — it is the ECU's own
        // calculation, so enrichment and DFCO are already accounted for.
        // 0x66 stays polled as the fallback/cross-check (it is how the 0x9D
        // scaling was pinned in the first place).
    )

    /**
     * Opt-in Mode 22 PIDs on the ZF 9HP transmission controller — ATF
     * temperature and gear position. **Never in [DEFAULT].** They only join
     * the poll set when the user turns on `extendedPidsEnabled` in Settings,
     * because they address a non-default module (sticky TX header) and answer
     * with multi-frame ISO-TP. See [ZfTcm] for the measured offsets and the
     * reasoning.
     */
    val EXTENDED = ZfTcm.ALL
}
