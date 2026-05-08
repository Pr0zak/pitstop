# Honda Pilot 2019 — PID set

Standard OBD-II Mode 01 + Honda-specific Mode 22 PIDs. Sources at the bottom.

## Standard OBD-II (Mode 01)

| PID | Name | Formula | Unit | Notes |
|---|---|---|---|---|
| `010C` | engine_rpm | `((A*256)+B)/4` | rpm | |
| `010D` | vehicle_speed | `A` | km/h | convert to mph in UI |
| `0105` | coolant_temp | `A-40` | °C | |
| `010F` | intake_air_temp | `A-40` | °C | |
| `0110` | maf_air_flow | `((A*256)+B)/100` | g/s | needed for fuel-rate estimate |
| `0111` | throttle_position | `(A*100)/255` | % | |
| `0104` | engine_load | `(A*100)/255` | % | |
| `012F` | fuel_level | `(A*100)/255` | % | |
| `0142` | control_module_voltage | `((A*256)+B)/1000` | V | proxy for ignition/alternator state |
| `015C` | engine_oil_temp | `A-40` | °C | available on most 2019 Pilots |
| `0103` | fuel_status | `A` | bitfield | open/closed loop |
| `0106` | short_term_fuel_trim_b1 | `(A-128)*100/128` | % | |
| `0107` | long_term_fuel_trim_b1 | `(A-128)*100/128` | % | |

## Honda Mode 22 PIDs (custom)

Confirmed on Piloteers forum threads:

| PID | Name | Formula | Unit | Source / notes |
|---|---|---|---|---|
| `2201` | atf_temp_c | `AA-40` | °C | Auto-trans fluid temp. ATF temp °F = `(AA*9/5)-40` |
| (varies) | gear | byte index → enum | gear | trial-and-error; community profiles list the byte |
| (varies) | tpms_pressure_fl | `((A*256)+B)/scale` | psi/kPa | 2-byte int per tire |
| (varies) | tpms_pressure_fr | … | … | |
| (varies) | tpms_pressure_rl | … | … | |
| (varies) | tpms_pressure_rr | … | … | |

The exact PIDs and byte offsets for gear and TPMS need to be transcribed from a community-shared Honda Pilot profile JSON when we build Task #12. The Piloteers threads have specific values but they're scattered across multiple posts.

## Common Honda quirks

- Some Mode 22 requests need an `init` frame first (typically `220001` or vehicle-specific). The WiCAN profile's `init` field handles this.
- Polling too aggressively can disturb the bus. Default `period` of 1000–2000 ms per PID is safe.
- Some hybrid Pilot trims (Touring, Black Edition with i-VTM4) expose AWD torque-split PIDs. The 2019 Elite has i-VTM4 — worth probing.

## Polling priorities

For pitstop's profile:

**Priority 1 (high-frequency, ≤1 Hz):**
- engine_rpm, vehicle_speed, throttle_position, control_module_voltage

**Priority 2 (medium, ≤0.2 Hz):**
- coolant_temp, intake_air_temp, maf_air_flow, fuel_level, engine_oil_temp, atf_temp_c, engine_load

**Priority 3 (low, ≤0.05 Hz):**
- fuel trims, fuel_status, gear, TPMS

## References

- [Piloteers — Custom OBD2 PIDs (canonical thread)](https://www.piloteers.org/threads/monitor-honda-custom-obd2-pids-transmission-temp-etc.137202/)
- [Piloteers — 20 AT Temp PID](https://www.piloteers.org/threads/20-at-temp-pid.165774/)
- [Piloteers — Transmission Temp Monitoring](https://www.piloteers.org/threads/transmission-temperature-monitoring.155273/)
- [Honda Ridgeline custom PIDs (related platform)](https://www.ridgelineownersclub.com/threads/custom-pids-for-odb-scantool-app-like-torque-or-carscanner.229500/)
