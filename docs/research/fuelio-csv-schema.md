# Fuelio CSV export schema (decoded)

Source: real export files from the user's Fuelio (Sygic) account, May 2026. Confirms and extends the public docs.

## File shape

- One zip per vehicle: `vehicle-<id>-sync.csv.zip` containing `vehicle-<id>-sync.csv`.
- Each CSV is multi-section: sections are delimited by lines like `"## SectionName"`.
- All fields are double-quoted, even empty ones.
- Date format header is `"yyyy-MM-dd"`. Newer rows include time: `"yyyy-MM-dd HH:mm"`. Importer must accept both.

## Sections observed

| Section | Cols | Purpose |
|---|---|---|
| `## Vehicle` | 22 | One row, vehicle metadata |
| `## Log` | 21 | Fillups (one row each) |
| `## CostCategories` | 6 | Default expense categories (9 rows, identical across exports) |
| `## Costs` | 18 | Service / expense / income entries |
| `## Pictures` | 6 | Photo metadata (filenames only — JPEGs not in zip) |
| `## Category` | 4 | Trip categories (Private / Work) |

## `## Vehicle` columns

```
Name, Description, DistUnit, FuelUnit, ConsumptionUnit, ImportCSVDateFormat,
VIN, Insurance, Plate, Make, Model, Year,
TankCount, Tank1Type, Tank2Type, Active, Tank1Capacity, Tank2Capacity,
FuelUnitTank2, FuelConsumptionTank2, guid, lastupdated
```

Unit codes (from observation):
- `DistUnit=1` → miles, `DistUnit=0` → km
- `FuelUnit=1` → US gallons, `FuelUnit=0` → litres (UK gallons might be 2; not yet verified)
- `ConsumptionUnit=1` → MPG, `ConsumptionUnit=0` → l/100km, `ConsumptionUnit=2` → km/l (TBD)
- `Tank1Type=100` → gasoline (other codes TBD; lookup table seeded on demand)

`guid` and `lastupdated` (epoch ms) added by Sygic for cloud sync. Use as upsert key.

## `## Log` columns

```
Data, Odo (mi|km), Fuel (us gallons|litres), Full, Price (optional), mpg (optional),
latitude (optional), longitude (optional), City (optional), Notes (optional),
Missed, TankNumber, FuelType, VolumePrice, StationID (optional), ExcludeDistance,
UniqueId, TankCalc, Weather, guid, lastupdated
```

Notes:
- Header reveals dist/fuel units (`Odo (mi)` vs `Odo (km)`) — use it, not the vehicle's `DistUnit`, since older exports may differ.
- `Full=1` full tank, `0` partial.
- `Missed=1` driver missed a fillup (e.g. forgot to log) — exclude-from-MPG flag.
- `mpg (optional)` is unreliable — sometimes empty, sometimes `0.0`, sometimes wrong on partial fills. Importer **stores it** but pitstop **recomputes** from odo deltas + volume.
- `StationID=0` → no station; treat as null (otherwise station clustering creates a phantom "station #0").
- `Weather` field exists but is empty in observed exports.
- `guid` is the upsert key.

Common `FuelType` codes observed:
- `0` = unknown
- `100`, `102`, `104` = different gasoline grades (regular / mid / premium, mapping TBD by user input)
- Likely `200`+ for diesel.

## `## Costs` columns

```
CostTitle, Date, Odo, CostTypeID, Notes, Cost, flag, idR, read,
RemindOdo, RemindDate, isTemplate, RepeatOdo, RepeatMonths, isIncome,
UniqueId, guid, lastupdated
```

Powerful: this section already encodes a maintenance-reminder system.
- `RemindOdo` — odometer threshold to remind at (0 = no reminder)
- `RemindDate` — date threshold (often `2011-01-01` placeholder = no reminder)
- `RepeatOdo`, `RepeatMonths` — interval for repeating reminders
- `isIncome=1` — flips the sign (e.g. fuel reimbursement)
- `CostTypeID` references `CostCategories.CostTypeID`

## `## CostCategories` columns

```
CostTypeID, Name, priority, color, guid, lastupdated
```

Default 9 categories: `Service`, `Maintenance`, `Repairs`, `Insurance`, `Tires`, `Wash`, `Tolls`, `Parking`, `Other`. Identical across exports — dedupe globally on import by `(Name, priority)`.

## `## Pictures` columns

```
Filename, Note, Type, target_id, guid, lastupdated
```

Metadata only — actual JPEGs stay on the phone (`/sdcard/Fuelio/Pictures/`). Importer creates orphan rows; if user later copies the JPEGs into a watched folder, UI links by filename.

## `## Category` columns

```
IdCategory, Name, guid, lastupdated
```

Trip categories — `Private` and `Work`. Imported but not surfaced at launch.

## Idempotency strategy

Importer upserts on `(table, fuelio_guid)` with last-write-wins by `lastupdated`:

```sql
INSERT ... ON CONFLICT (fuelio_guid) DO UPDATE
  SET ... WHERE EXCLUDED.last_updated_at > pitstop_table.last_updated_at;
```

Re-importing the same export is a no-op. Importing a newer export replaces older versions of changed rows.

## Real-world counts (user's data, May 2026)

| Vehicle | Fillups | Expenses | Photos | Notes |
|---|---|---|---|---|
| Truck | 254 | 2 | (in main set) | last fillup 2019-03-14, retired |
| Pilot (2019 Honda Pilot) | 238 | 9 | 7 | active, OBD target |
| SeaDoo 170 STI | 1 | 0 | 0 | engine hours not miles |

## References

- [Fuelio FAQ — backup files](https://www.fuel.io/faq_backup_help.html)
- [Fuelio CSV format thread (UserEcho)](https://fuelio.userecho.com/communities/1/topics/120-import-csv-format)
- [`dundee/fuelio2fuelly` converter (canonical source)](https://github.com/dundee/fuelio2fuelly)
- [Sygic acquisition (Android Police, 2015)](https://www.androidpolice.com/2015/07/20/sygic-buys-fuelio-and-makes-all-of-the-fuel-logging-apps-pro-features-available-for-free/)
