# Architecture

## Components (single Compose stack)

| Service | Image | Port | Role |
|---|---|---|---|
| `mosquitto` | eclipse-mosquitto | 1883 (LAN) | MQTT broker |
| `db` | timescale/timescaledb:2.17.2-pg16 | 5432 (internal) | TimescaleDB / Postgres |
| `backend` | ghcr.io/pr0zak/pitstop-backend | 8000 | FastAPI: ingest + REST + WS |
| `frontend` | ghcr.io/pr0zak/pitstop-frontend | 8080 | Nginx serving Vue build |

## Data plane

```
WiCAN device  ──MQTT──▶  mosquitto  ──asyncio──▶  backend ingest worker  ──asyncpg──▶  db
phone bridge  ──MQTT──▶  mosquitto  ──asyncio──▶  backend ingest worker  ──asyncpg──▶  db

backend ingest worker also feeds:
   ├─ trip detector worker (in-process pubsub) ──▶ db trips table
   └─ HA mirror worker (gated, default off)    ──▶ mosquitto (re-publish)
```

## Connectivity

| Path | Source | Target | How |
|---|---|---|---|
| WiCAN driveway publish | WiCAN device on home WiFi | mosquitto on LAN IP | direct |
| Phone bridge (cellular) publish | Android app | mosquitto on LAN IP | via existing Tailscale subnet router (CT 444) |
| User browser | laptop / phone (anywhere with TS) | frontend :8080 | LAN or via subnet router |

**No public ingress.** No port forwards, no Cloudflare tunnel, no public DNS, no TLS at launch (LAN + WireGuard from subnet router both encrypt their own traffic).

## Storage

- **Hot:** `pid_readings` hypertable, partitioned by `time`. Auto chunked weekly.
- **Rollups:** continuous aggregates `pid_hourly`, `pid_daily` per (vehicle_id, metric).
- **Retention:** raw readings kept indefinitely at launch (~10–50 MB / week of driving estimated). Drop policy added later if needed.
- **Volumes:** `db_data` named volume; backups are out of scope at launch (PBS9000 covers CT-level snapshots).

## Topic conventions

```
wican/<vehicle_id>/<metric>        — driveway WiFi (WiCAN device)
bridge/<vehicle_id>/<metric>       — phone BT bridge

Examples:
  wican/pilot19/engine_rpm           60
  wican/pilot19/vehicle_speed        42
  wican/pilot19/atf_temp_f           185
  bridge/pilot19/gps_lat             39.012345
  bridge/pilot19/gps_lon             -94.654321
```

`<vehicle_id>` is the human-readable slug from `vehicles.name` (e.g. `pilot19`, `truck`, `seadoo`), assigned at vehicle creation. Backend resolves it to the UUID PK.

Payload: numeric for parsed PIDs, raw hex string for unparsed multi-byte (decoded server-side using `pid_profiles`).

## Secrets

Same `.env` pattern as myvitals:
- `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`
- `INGEST_TOKEN`, `QUERY_TOKEN` (32-byte hex)
- `MQTT_USER`, `MQTT_PASSWORD` (Mosquitto auth — mandatory even on LAN)
- `TZ` (default `America/Chicago` per user)

## Open ports per CT (planned)

- `1883/tcp` — Mosquitto, **bind to LAN IP only**, with auth
- `8000/tcp` — backend API, LAN
- `8080/tcp` — frontend, LAN
- `5432/tcp` — **NOT** exposed (internal-only)
