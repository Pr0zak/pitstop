# WiCAN-fw — MQTT & AutoPID format

Source: WiCAN docs, GitHub discussions, real-world community examples.

## Topic conventions (WiCAN-recommended)

```
<system>/<device>/<vehicle>/<module>_<metric>
```

Examples:
- `homeassistant/wican/f350/pcm_engine_rpm`
- `homeassistant/wican/pilot19/pcm_atf_temp_f`

For pitstop we adopt:
```
wican/<vehicle_id>/<metric>
bridge/<vehicle_id>/<metric>     # phone-bridge variant
```

`<vehicle_id>` is the slug (e.g. `pilot19`); backend resolves to UUID PK. Module prefix is dropped — pitstop tracks it via the profile, not the topic.

## AutoPID profile JSON

Each PID is its own object with:

| Field | Meaning |
|---|---|
| `name` | attribute name in the MQTT payload (e.g. `engine_rpm`) |
| `expression` | math formula on returned bytes (e.g. `((A*256)+B)/4`) |
| `init` | precursor frame to send before the request (Honda often needs 22 0001) |
| `period` | poll interval in ms |
| `type` | response parse type (decimal, raw hex) |
| `send_to` | output destination (MQTT topic, HTTP URL, Wallbox channel) |
| `min`, `max` | sanity bounds |

Pitstop stores the full profile as JSONB in `pid_profiles`; one row = one vehicle's profile. The same JSON file uploads to the WiCAN device unmodified.

## Supported protocols (per WiCAN docs)

ISO 15765-4 CAN variants:
- 11-bit ID @ 500 kbaud (most common)
- 29-bit ID @ 500 kbaud
- 11-bit ID @ 250 kbaud
- 29-bit ID @ 250 kbaud

The 2019 Honda Pilot is **11-bit @ 500 kbaud**.

## Payload format

WiCAN publishes one topic per PID (not a consolidated JSON). Per-message payload:

- **Parsed** (most cases): just the decimal/integer result of the `expression` applied to the response bytes.
- **Raw hex** (multi-byte custom PIDs): the raw response bytes as a hex string. Backend re-applies the formula server-side using the profile.

QoS 0 by default. Retain flag controlled per-PID (Settings → AutoPID).

## HA Auto-Discovery

WiCAN can publish HA-compatible discovery topics. Pitstop's HA-mirror worker (built but disabled at launch) re-publishes pitstop-parsed readings under a configurable HA discovery prefix, so HA can also pick up sensors when the toggle is flipped.

## Limitations to plan around

- **No GPS on the WiCAN device.** Map views require GPS — only available via the phone bridge, which attaches Android GPS to its publishes.
- **No Tailscale client in the firmware.** WiCAN publishes only over LAN, never from the road.
- **WiCAN buffers on-device when the broker is unreachable.** Driveway-only ingestion still gets the most recent trip — but a long stale period dumps into one batch on reconnect. Trip detector must handle bursty arrival.
- **Auth on Mosquitto:** WiCAN supports username/password. We require it.

## References

- [meatpiHQ/wican-fw GitHub](https://github.com/meatpiHQ/wican-fw)
- [WiCAN AutoPID usage docs](https://meatpihq.github.io/wican-fw/config/automate/usage/)
- [Discussion #728 — HA MQTT best practices](https://github.com/meatpiHQ/wican-fw/discussions/728)
- [Discussion #198 — AutoPID + HA discovery](https://github.com/meatpiHQ/wican-fw/discussions/198)
