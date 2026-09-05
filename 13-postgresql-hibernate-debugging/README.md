# Asset Tracking API

A register of the company's IT assets — laptops, monitors, servers, network gear — with what each one
cost, where it is, and how its status has changed over time.

## Purpose

The important thing about this service is what it **does not** own: the schema. `db/init.sql` was
written by the data platform team, and the finance export and the insurance report both read those
tables directly. The application is mapped onto that schema and has to live with it exactly as it
finds it — table names, column names, column types and all.

That is the normal situation for a service added to an existing system, and it is a different
discipline from a greenfield project where Hibernate generates whatever it likes.

## Architecture

```
com.debuglab.assets
├── AssetTrackingApplication
├── controller/AssetController        assets, history, status changes, report, categories
├── service/AssetService              registration, status transitions, reporting
├── repository/
│   ├── AssetRepository               derived queries + one hand-written native report query
│   ├── AssetCategoryRepository
│   └── AssetStatusHistoryRepository
├── entity/
│   ├── Asset                         -> assets
│   ├── AssetCategory                 -> the category reference table
│   ├── AssetStatusHistory            -> asset_status_history
│   └── AssetStatus                   IN_SERVICE / IN_REPAIR / RETIRED
├── dto/
└── exception/
```

`spring.jpa.hibernate.ddl-auto=none`. The application neither creates nor validates the schema — it
simply uses it. Read `db/init.sql`; it is the specification.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* Hibernate 6.5 with the PostgreSQL dialect
* **PostgreSQL 16** (Docker)
* Maven, JUnit 5, MockMvc

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+
* Docker Desktop (or any Docker engine with Compose v2)

### Database setup

```bash
docker compose up -d
```

PostgreSQL is published on **host port 5433** so it cannot clash with a PostgreSQL you already run on
5432. On first start the container executes `db/init.sql`, which creates the three tables and loads
the reference data plus the six assets migrated from the old spreadsheet.

```bash
docker exec -it debuglab13-postgres psql -U assetuser -d assetsdb
```

Useful things to run in there:

```sql
\dt                                    -- list tables
\d assets                              -- describe a table, with types
SELECT * FROM assets ORDER BY id;
SELECT * FROM "AssetCategory";         -- note the quoting
```

Reset to the seeded state:

```bash
docker compose down -v && docker compose up -d
```

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/assetsdb` | Host port from `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `assetuser` / `assetpw` | **Placeholder credentials** — replace if you point at your own PostgreSQL |
| `spring.jpa.hibernate.ddl-auto` | `none` | The schema is owned elsewhere |
| `logging.level.org.hibernate.SQL` | `DEBUG` | Every statement is printed |

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/asset-tracking-1.0.0.jar
```

```bash
mvn test          # requires the container to be up
```

## API endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| `GET` | `/api/assets` | Every asset | `200` |
| `POST` | `/api/assets` | Register a new asset | `201`, `409` on a duplicate tag |
| `GET` | `/api/assets/{id}/history` | Status history for one asset | `200`, `404` |
| `PATCH` | `/api/assets/{id}/status` | Change an asset's status and record it | `200`, `404` |
| `GET` | `/api/assets/report` | Cost totals by location, plus a grand total | `200` |
| `GET` | `/api/categories` | The category reference data | `200` |

### Registering an asset

```bash
curl -i -X POST http://localhost:8080/api/assets \
  -H "Content-Type: application/json" \
  -d '{"assetTag":"AST-0100","name":"ThinkPad P16","categoryId":1,
       "location":"Bengaluru HQ","purchaseCost":210000.55,"purchasedAt":"2026-02-01"}'
```

A new asset starts `IN_SERVICE`. `location` is required by the schema.

### Changing status

```bash
curl -i -X PATCH http://localhost:8080/api/assets/6/status \
  -H "Content-Type: application/json" \
  -d '{"status":"IN_SERVICE","note":"Keyboard replaced"}'
```

Valid statuses are `IN_SERVICE`, `IN_REPAIR` and `RETIRED`. Every change appends a row to the
history.

### The cost report

```bash
curl http://localhost:8080/api/assets/report
```

```json
{
  "byLocation": [
    { "location": "Bengaluru HQ", "assetCount": 2, "totalCost": 155271.35 },
    { "location": "Chennai DC",   "assetCount": 2, "totalCost": 986086.50 },
    { "location": "Pune Office",  "assetCount": 2, "totalCost": 763015.35 }
  ],
  "assetCount": 6,
  "grandTotal": 1904373.20
}
```

The seeded assets cost **1904373.20** in total. This figure goes to finance, so it has to be exactly
right — to the paisa, with no floating-point residue.

### History

```bash
curl http://localhost:8080/api/assets/5/history
```

```json
[ { "id": 5, "assetId": 5, "status": "IN_SERVICE", "changedAt": "...", "note": "Rack 3" },
  { "id": 6, "assetId": 5, "status": "RETIRED",    "changedAt": "...", "note": "End of support" } ]
```

## Expected functionality

* Every endpoint returns `200`/`201`; nothing returns `5xx`.
* `grandTotal` is exactly `1904373.20` for the seeded data.
* Statuses read back as their names (`IN_SERVICE`), matching what is stored in the `status` columns.
* Registering assets works from the first attempt, and keeps working.
* `registered_at` means the same instant regardless of where the application is deployed — the same
  row must not report a different time on a server in another timezone.
* Anything the schema forbids is rejected by the API with `400`, not by PostgreSQL with `500`.
