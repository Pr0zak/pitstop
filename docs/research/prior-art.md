# Prior art survey

Adjacent projects we evaluated. Why we're building from scratch instead of forking any one of them.

| Project | Stars | What it does | Why not as-is | What we steal |
|---|---|---|---|---|
| [meatpiHQ/wican-fw](https://github.com/meatpiHQ/wican-fw) | — | WiCAN device firmware | This is the source — we consume its output | AutoPID profile JSON shape (1:1) |
| [adlerre/obd2-mqtt](https://github.com/adlerre/obd2-mqtt) | 197 | ESP32 OBD→MQTT for HA | No storage, no UI, no analytics | HA discovery payload shape, MQTT topic conventions |
| [dlashua/torque2mqtt](https://github.com/dlashua/torque2mqtt) | — | Torque app webhook → MQTT bridge | Bridge only, no storage/UI | Phone-as-bridge pattern for our Android app |
| [thatlarrypearson/telemetry-obd](https://github.com/thatlarrypearson/telemetry-obd) | — | Long-running OBD logger (JSONL files) | File-based, no live UI, no DB | Long-term retention discipline, PID enumeration |
| [tzebrowski/ObdGraphs](https://github.com/tzebrowski/ObdGraphs) | — | Android OBD viewer (ELM327) | Phone-only, no server | Inspiration for the phone-bridge UI |
| [LubeLogger](https://lubelogger.com/) | — | Self-hosted maintenance log (.NET) | No live OBD, no MQTT, narrow fuel feature | Shape of the maintenance/service UI |
| [Freematics](https://freematics.com/) + Trackie | — | OSS hardware + open server stack | Requires their dongle | Trip-centric data model, map+timeline UX |
| [ElektorLabs/obd2-dashboard](https://github.com/ElektorLabs/obd2-dashboard) | — | ESP32 with on-device LCD UI | Embedded, not a server app | — |
| AutoPi (commercial) | — | Polished fleet dashboard (proprietary) | Closed, subscription | UI/UX target to aim for |
| Fuelio (Sygic) | — | Mobile fuel tracker | What we're partially replacing | CSV import (one-shot, full history) |

## The unfilled gap

None of the above does **all** of:
- WiCAN MQTT input (driveway + phone bridge)
- First-class trip semantics (open/close/stats per drive)
- App-owned DB (no Influx, no Grafana coupling)
- Polished bespoke UI (not an HA dashboard, not a generic time-series tool)
- Phone GPS bridge for cellular live data
- Tailscale subnet ingress (no public exposure, no port forwards)
- Fuel + service tracking integrated with telemetry (correlate fuel-MPG vs OBD-MPG)

That intersection is pitstop.
