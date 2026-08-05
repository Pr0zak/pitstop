# Honda Pilot 2019 Elite — PID set

Standard OBD-II Mode 01 + Honda/ZF Mode 22 PIDs.

**Everything under "Measured" was probed against the real vehicle on 2026-08-04** via
the WiCAN's `POST /autopid/test_pid` with the engine running, and cross-checked against
a second independent signal wherever possible. Rows marked *forum-sourced* remain
unverified on this car. Sources at the bottom.

---

## Measured: what this ECU actually supports

`GET /scan_available_pids?protocol=0` is the authority — this car advertises **72**
standard PIDs. Two results overturned standing assumptions:

| PID | Status | Consequence |
|---|---|---|
| `0x10` MAFAirFlowRate | **NOT supported** | Was enabled in the dongle's poll list *and* the phone's, burning a slot per cycle for a guaranteed non-answer. Removed from both. |
| `0x66` MAFSensorA | **supported** | The real MAF source. Every historical `maf_air_flow` row came from here. |

Always diff `scan_available_pids` against the enabled `std_pids` before adding or
removing anything.

## Measured: firmware decoder bugs — PIDs reporting 0 while carrying real data

The dongle firmware ships a static parameter table (`main/obd2_standard_pids.h`) in
which **54 of 205 parameters are zero-length stubs** (`bit_length = 0`) and a further
**16 are clamped by `max = 0.0f`**. The decoder computes `bytes_needed = (bit_length+7)/8`
→ 0, never reads the frame, then clamps to `max`. Real CAN data on the wire is discarded
before it is read.

**A PID that "always answers 0" is therefore more likely a firmware table bug than a
vehicle limitation.** Four PIDs previously recorded as "dead on the Pilot" are firmware
bugs:

| PID | What | Raw frame observed | Recovery expression | Independent cross-check |
|---|---|---|---|---|
| `0x9D` | Engine fuel rate | `41 9D 00 14 00 14` | `(B3*256+B4)*0.02` → g/s | MAF ÷ 14.7 = 0.349 vs 0.360 → **3.2%** |
| `0x9E` | Exhaust mass flow | `41 9E 00 67` | `(B3*256+B4)/5` → kg/h | (air+fuel)×3.6 = 19.8 vs 20.6 → **4%** |
| `0x9F` | Fuel system % use | `41 9F 05 FF 00 FF` | not yet derived | — |
| `0x6C` | Cmd throttle actuator | `41 6C 03 0A 09 00 00` | not yet derived | — |
| `0x51` | Fuel type | `41 51 01` | `max=0` clamp, not a stub | `01` = gasoline |

`0x9D` is the most valuable recovery: it is the ECU's **own** fuel calculation, so it
already accounts for power enrichment and deceleration fuel cut-off. Integrating MAF
instead assumes stoichiometric 14.7:1 forever — under-reporting at wide-open throttle
and over-reporting on every lift-off.

`0x68` (intake air temp) is **not** a stub: it has `bit_start = 39` → start_byte 4,
landing on the supported-sensors bitmap and yielding a constant −39 °C. That is the bug
ADR-018 works around, still unfixed as of firmware tag `v4.51p_beta-01`.

## Measured: the Test-button vs MQTT byte-offset trap

**The most dangerous gotcha on this device.** `POST /autopid/test_pid` returns the raw
frame *including* the 4-byte CAN header; the **MQTT publish path strips it**. An
expression validated with the Test button is wrong by exactly 4 byte-positions in
production, and fails silently.

```
Test-button : B0..B3 = CAN header, B4 = ISO-TP PCI, B5 = mode echo, B6 = PID echo, B7 = A, B8 = B
MQTT        : B0 = ISO-TP PCI, B1 = mode echo, B2 = PID echo, B3 = A, B4 = B
              => MQTT B(n) == Test B(n+4)
```

Corroborated by ADR-018's independently-bisected IAT offset: `B5` on MQTT, `B9` on Test.
**Write production expressions in MQTT indexing, then verify by subscribing to the PID
topic — never by the Test button alone.**

Firmware tag `v4.51p_beta-01` contains a commit *"Align ELM327 header parsing across
paths."* If that ships, every expression here may need re-deriving.

## Measured: Mode 22 IS reachable — the earlier "gateway-blocked" finding was wrong

ADR-018 concluded Mode 22 was gateway-blocked on this trim. **It is not.** The earlier
attempts used the wrong DID on the wrong module:

- `22 2201` → `NO DATA`. `2201` is the **Honda-built transaxle** PID (6AT/10AT).
- This car (Elite) has the **ZF 9HP**, which uses a different DID block, `30xx`.
- Addressed to module **`0x1E`** (the ZF TCM), an unknown DID returns `7F 22 31` —
  negative response code `0x31` *requestOutOfRange*, **not** `0x11` serviceNotSupported.
  The service works; only that DID was absent.

Two modules answer on this bus: `0x10` (PCM) and `0x1E` (TCM). `0x1E` also answers Mode
01 (coolant read 85 °C from it vs 86 °C from the PCM).

### Verified Mode 22 PIDs

| Signal | Request | Header | Decode | Verification |
|---|---|---|---|---|
| **ATF temperature** | `22 3083` | `18DA1EF1` | `payload[17] − 40` °C | Read 61 °C; value **moved** 0x64→0x65 while idling as the transmission warmed |
| **Gear position** | `22 3086` | `18DA1EF1` | `payload[23]` | **4/4 shifts matched** a blind D→R→N→D sequence: `2 → 15 → 0 → 2` |

Gear: `0` = Park **or** Neutral (indistinguishable), `1–9` = forward, `15` = Reverse.
Stopped in D reads **`2`, not `1`** — the ZF 9HP launches in 2nd, which is itself
confirmation the correct register was found. `payload[24]` tracked identically (likely
target gear; divergence mid-shift would give shift-in-progress detection for free).

Bytes 7, 8 and 17–19 swing hard with gear state (D `1/244` → R `33/52`) — probably input
and output shaft speeds, i.e. torque-converter slip if decoded.

**"payload" = the reassembled ISO-TP data starting at the `0x62` positive-response
byte**: the first frame contributes bytes after its 2-byte PCI, each consecutive frame
after its 1-byte PCI. `payload[0] = 0x62`, `[1..2]` = DID echo.

### The historical false positive, explained

ADR-018 records a previously "verified" ATF reading of 136.4 °F that turned out to be
static. Now fully explained: `payload[0]` is the `0x62` response echo, and
`0x62 = 98 → 98 × 9/5 − 40 = 136.4`. Reading offset 0 yields that constant on **every**
successful Mode 22 response. This also pins the offset conversion rule:

```
WiCAN_offset = Torque_letter_index + 2      (A=1, B=2, … Z=26, AA=27 …)
```

### The 34-byte ISO-TP truncation cliff

**The dongle reassembles at most ~34 payload bytes (6 frames).** The ATF response
*declares* `0x27 = 39` bytes; only 34 arrive.

| Signal | Byte needed | Reachable? |
|---|---|---|
| ATF temp | 17 | yes |
| Gear | 23 | yes |
| TPMS pressures / temps | 11–30 | yes (untested) |
| **VCM cylinders active** | **53** | **NO — past the cliff** |

`22 2615` answers positively (`62 26 15 …`) but the cylinder-count byte is truncated
away. Cylinder-deactivation state is **not obtainable through this dongle** without
firmware that raises the reassembly limit.

### Why ATF/gear are not in the dongle's poll list

Adding a header-changing PID to `auto_pid` **collapsed the published payload from 62
keys to 19**, and was rolled back. `Init` runs *before* a request and ELM headers are
sticky, so a header set for ATF persists into whatever standard PID polls next — those
then get answered by the wrong module. There is no "after" hook, and `_hdr_reset` is
just another entry competing in the same round-robin.

---

## Standard OBD-II (Mode 01) — polled set

| PID | Name | Formula | Unit | Notes |
|---|---|---|---|---|
| `010C` | engine_rpm | `((A*256)+B)/4` | rpm | |
| `010D` | vehicle_speed | `A` | km/h | convert to mph in UI |
| `0105` | coolant_temp | `A-40` | °C | |
| `0166` | maf_air_flow | `((B*256)+C)/32` | g/s | **sensor A only**; byte 0 is a support bitmap. `0110` is NOT supported |
| `019D` | engine_fuel_rate | `((A*256)+B)*0.02` | g/s | firmware decoder broken — custom PID required |
| `019E` | engine_exhaust_flow | `((A*256)+B)/5` | kg/h | firmware decoder broken — custom PID required |
| `0111` | throttle_position | `(A*100)/255` | % | |
| `0104` | engine_load | `(A*100)/255` | % | |
| `012F` | fuel_level | `(A*100)/255` | % | reads optimistically at low tank — see the two-point calibration ADR |
| `0142` | control_module_voltage | `((A*256)+B)/1000` | V | proxy for ignition/alternator state |
| `0168` | intake_air_temp | `B5-40` (MQTT indexing) | °C | ADR-018; `010F` returns NO DATA on this PCM |
| `01A6` | odometer | `((A<<24)\|(B<<16)\|(C<<8)\|D) × 0.1` | km | **km, not miles.** Reads ~51 km above the dash cluster |
| `0106`/`0107` | stft/ltft b1 | `(A-128)*100/128` | % | |

## Captured but historically unsurfaced

These answer and are stored, but were long kept under raw hex names and therefore
invisible to both UIs: catalyst temps both banks (~560 °C), commanded AFR equivalence
ratio, O2 sensor voltages and trims, fuel rail pressure, commanded EGR and evap purge,
evap vapour pressure, friction torque %, absolute throttle B/D/E.

`6C-CmdThrottleActRel` is deliberately **not** aliased — it is one of the broken-decoder
PIDs above, so a canonical name would surface a permanently-zero metric that looks real.

## Forum-sourced, unverified on this car

| Signal | Request | Header | Decode | Note |
|---|---|---|---|---|
| TPMS pressure | `22 6001` | `18DA26F1` | FL `p[13]*256+p[14]`, FR `p[11]*256+p[12]`, RL `p[17]*256+p[18]`, RR `p[15]*256+p[16]` kPa | wheel mapping suspect — verify by deflating one tyre |
| TPMS temp | `22 6001` | `18DA26F1` | FL `p[28]`, FR `p[27]`, RL `p[30]`, RR `p[29]` °C | |
| Outside air temp | `22 7028` | `18DA60F1` | `signed16(p[19],p[20])` | would prove a third, non-powertrain module is reachable |
| VCM cylinders | `22 2615` | `18DA10F1` | `p[53]` → 3 or 6 | **blocked by the 34-byte cliff** |

## Polling priorities

**Priority 1 (≤1 Hz):** engine_rpm, vehicle_speed, throttle_position, engine_load,
manifold_pressure, engine_fuel_rate
**Priority 2 (≤0.2 Hz):** coolant_temp, intake_air_temp, maf_air_flow, fuel_level,
exhaust_flow, catalyst temps
**Priority 3 (≤0.05 Hz):** fuel trims, odometer, ATF temp
**Event-rate:** gear position (only useful at ~0.5 Hz or better to catch shifts)

## Sources

- Live probing of the vehicle, 2026-08-04 (`/autopid/test_pid`, `/scan_available_pids`,
  MQTT topic subscription) — everything under "Measured".
- meatpiHQ/wican-fw firmware source: `main/obd2_standard_pids.h`,
  `components/autopid/autopid.c`.
- [Ridgeline Owners Club — custom PIDs for Torque/CarScanner](https://www.ridgelineownersclub.com/threads/custom-pids-for-odb-scantool-app-like-torque-or-carscanner.229500/) — ZF 9HP DIDs
- [Ridgeline Owners Club — Torque Pro and the 9-speed](https://www.ridgelineownersclub.com/threads/torque-pro-app-and-9-speed-trans.227156/)
- [OdyClub — 10AT ATF temperature with OBD2 and Torque Pro](https://www.odyclub.com/threads/10at-atf-temperature-measurement-with-obd2-and-torque-pro.378369/) — Honda transaxle `2201` byte layout
- [Piloteers — custom OBD2 PIDs](https://www.piloteers.org/threads/monitor-honda-custom-obd2-pids-transmission-temp-etc.137202/) (paywalled via tollbit redirect at time of writing; HTTP 402)
