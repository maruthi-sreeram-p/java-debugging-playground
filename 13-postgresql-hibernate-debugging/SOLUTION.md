# SOLUTION — 13 · Asset Tracking API

> **Sealed answer key.** Seven planted defects.

---

## Defect 1 — Quoted mixed-case table name versus Hibernate's naming strategy

* **Bug:** `init.sql` creates `CREATE TABLE "AssetCategory" (...)` — quoted, so PostgreSQL stores the
  name with its capitals intact. The entity declares `@Table(name = "AssetCategory")`.
* **Affected component:** `entity/AssetCategory`
* **Root cause:** **two** independent rules collide.
  1. PostgreSQL folds unquoted identifiers to **lower case**. A table created as `"AssetCategory"`
     can only be referenced as `"AssetCategory"`; writing `AssetCategory` unquoted resolves to
     `assetcategory`, which does not exist.
  2. Spring Boot installs `CamelCaseToUnderscoresNamingStrategy` as the *physical* naming strategy,
     and it rewrites identifiers **even when you supplied them explicitly**. So `AssetCategory`
     becomes `asset_category` before it ever reaches SQL.
* **Symptom:** `relation "asset_category" does not exist`, with `\dt` plainly showing `AssetCategory`.
* **Why the symptom is misleading — the best part of this defect:** the developer wrote the table name
  *correctly*, character for character, and Hibernate emitted something else. Three different spellings
  are in play (`AssetCategory` in the schema, `AssetCategory` in the annotation, `asset_category` in
  the SQL), and the one that appears in the error message matches neither of the other two. Nobody
  suspects that an explicit `@Table(name = ...)` is subject to rewriting.
* **Correct fix — quote the identifier so it passes through untouched:**

  ```java
  @Table(name = "\"AssetCategory\"")
  ```

  Backticks (`` @Table(name = "`AssetCategory`") ``) work too — Hibernate treats them as portable
  quoting. Either way the name is emitted verbatim and PostgreSQL preserves the case.

  The alternative is to replace the physical naming strategy globally:

  ```properties
  spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
  ```

  **Push back on this one.** It fixes this entity and simultaneously breaks the other two, whose
  column names (`asset_tag`, `purchase_cost`, `changed_at`, …) currently rely on the camel-case
  strategy. If the learner takes this route, ask them what happens to `Asset.assetTag`. It is a good
  discussion: a global setting to fix a local problem is nearly always the wrong trade.
* **Concept:** PostgreSQL identifier folding and quoting; Hibernate implicit versus physical naming
  strategies.
* **Why a fresher makes it:** the DBA quoted the name (perhaps to preserve a convention from another
  system), and nothing about `@Table(name = "AssetCategory")` suggests it will be rewritten. On MySQL,
  where identifiers are case-insensitive on Windows/macOS, the same code often works — which is
  exactly why this bites on the first PostgreSQL deployment.
* **How to recognise it in the wild:** read the SQL in the log, not the annotation. Any
  `relation ... does not exist` where you can see the table in `\dt` is a quoting or naming-strategy
  problem. A general lesson: mixed-case identifiers in PostgreSQL are a permanent tax — the right
  long-term answer is `snake_case_unquoted` everywhere.

---

## Defect 2 — Enum field with no `@Enumerated(EnumType.STRING)`

* **Bug:**

  ```java
  @Column(name = "status", nullable = false, length = 20)
  private AssetStatus status;
  ```

  on `AssetStatusHistory`, against a `VARCHAR(20)` column holding `'IN_SERVICE'`.
* **Affected component:** `entity/AssetStatusHistory`
* **Root cause:** JPA's default for an enum is `EnumType.ORDINAL` — it stores the constant's *position*
  as a number. Hibernate therefore binds the column as a small integer and tries to read `IN_SERVICE`
  as one.
* **Symptom:** `Could not extract column [5] from JDBC ResultSet [Bad value for type byte : IN_SERVICE]`.
* **Why the symptom is misleading:** the word `byte` appears nowhere in the codebase, the schema or
  the data. It comes from Hibernate's internal choice of JDBC type for a small ordinal. A learner
  searching the project for `byte` finds nothing.
* **Correct fix:**

  ```java
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AssetStatus status;
  ```

* **The bigger lesson (hint B4):** `ORDINAL` is dangerous even when it "works". If the column *were*
  an integer and somebody later inserted a new constant into the middle of the enum declaration, every
  historical row would silently change meaning. There is no migration, no error, and no way to detect
  it afterwards. `EnumType.STRING` should be the default habit; some teams ban bare `@Enumerated`
  entirely. Ask the learner what would have happened in the integer case — it is the point of the
  defect.
* **Why a fresher makes it:** they simply do not add the annotation, because the field compiles and
  the mapping looks complete. Note that `Asset.status` in this same project is a `String` and works
  fine, which makes the inconsistency easy to miss.
* **How to recognise it in the wild:** any type-mismatch error on a column you know holds text. Grep
  for enum-typed entity fields and check every one has `@Enumerated(EnumType.STRING)`.

---

## Defect 3 — `IFNULL` in a native query against PostgreSQL

* **Bug:**

  ```sql
  SUM(IFNULL(a.purchase_cost, 0))
  ```

* **Affected component:** `repository/AssetRepository.costByLocation`
* **Root cause:** `IFNULL` is a MySQL function. PostgreSQL implements the SQL-standard `COALESCE`.
* **Symptom:** `ERROR: function ifnull(numeric, integer) does not exist`, with a `Hint:` suggesting an
  explicit type cast.
* **Why the symptom is misleading:** it is not, particularly — PostgreSQL's message is excellent. This
  defect is here for a different reason: it is the unmistakable fingerprint of SQL ported from another
  database, and it makes the point that a native query is a commitment to one dialect.
* **Correct fix:**

  ```sql
  SUM(COALESCE(a.purchase_cost, 0))
  ```

  The better answer, worth raising (hint C3): `purchase_cost` is `NOT NULL`, so the null-guard is
  pointless, and the whole query could be a JPQL aggregate or a Spring Data projection that survives a
  change of database. Native SQL should be reserved for things JPQL genuinely cannot express.
* **Concept:** SQL dialect portability; native queries as database-specific code.
* **Why a fresher makes it:** they wrote it against MySQL — as several earlier projects in this lab
  do — and copied it across. `IFNULL`, `NOW()` vs `CURRENT_TIMESTAMP`, `LIMIT x, y` vs
  `LIMIT y OFFSET x`, backtick quoting and `AUTO_INCREMENT` are the usual suspects.
* **How to recognise it in the wild:** grep for `nativeQuery = true` before any database migration and
  review every one. Ideally, run the integration tests against the target database in CI.

---

## Defect 4 — The identity sequence was never advanced past the seeded ids

* **Bug:** `init.sql` inserts assets, categories and history rows with **explicit** `id` values into
  columns declared `GENERATED BY DEFAULT AS IDENTITY`.
* **Affected component:** `db/init.sql` — but see the fix, which belongs on the application/ops side.
* **Root cause:** `GENERATED BY DEFAULT AS IDENTITY` permits an explicit value and, when one is
  supplied, **does not advance the underlying sequence**. After seeding six assets with ids 1–6, the
  sequence is still at 1. `GenerationType.IDENTITY` asks PostgreSQL for the next value, gets `1`, and
  collides with a row that already exists.
* **Symptom:** `duplicate key value violates unique constraint "assets_pkey" Detail: Key (id)=(1)
  already exists.` The **second** attempt fails with `(id)=(2)`, and so on. The **seventh** attempt
  succeeds and everything works from then on.
* **Why the symptom is misleading — this is the "works after a while" defect:**
  * A developer who retries a few times sees it start working and concludes it was a transient
    problem. It never comes back until the database is reseeded — which happens on every fresh
    environment, every CI run and every `docker compose down -v`.
  * Neither the application nor the request supplies an id, so the id in the error message appears out
    of nowhere.
  * `SELECT MAX(id)` looks perfectly healthy. The broken state is in the sequence, which nobody thinks
    to inspect.
* **Correct fix — the learner should do both halves:**

  1. **Correct the data now:**

     ```sql
     SELECT setval(pg_get_serial_sequence('assets', 'id'), (SELECT MAX(id) FROM assets));
     SELECT setval(pg_get_serial_sequence('"AssetCategory"', 'id'), (SELECT MAX(id) FROM "AssetCategory"));
     SELECT setval(pg_get_serial_sequence('asset_status_history', 'id'), (SELECT MAX(id) FROM asset_status_history));
     ```

  2. **Stop it recurring.** The seed script recreates the problem on every fresh volume, so the
     `setval` calls belong at the end of `init.sql` itself (or in a migration that runs after it).
     Note the constraint the README imposes: the ids must stay as they are, because finance depends
     on them, so "just let the sequence assign them" is not available.

  A learner who only runs the `setval` has fixed their laptop and nobody else's. Ask them what happens
  on a clean checkout.

  *(Strictly, adding the `setval` lines to `init.sql` does edit a file the README calls DBA-owned.
  That is the right answer here and worth discussing: it is a correction to the seed data, not a
  change to the schema or to any value another system reads. If the learner instead proposes handing
  it to the platform team as a defect report, that is an equally good answer.)*
* **Concept:** identity columns and their backing sequence; `GENERATED BY DEFAULT` versus
  `GENERATED ALWAYS`; seed data and sequence synchronisation.
* **Why a fresher makes it:** carrying ids over from a migration is completely normal and often
  required. Nobody tells you the sequence is a separate object with separate state.
* **How to recognise it in the wild:** duplicate-key errors on a column you never set, immediately
  after a data import or a restore from dump. The diagnostic is always
  `SELECT last_value FROM <table>_id_seq;` next to `SELECT MAX(id) FROM <table>;`. Adding a
  sequence-resync step to every import script prevents an entire class of incident.

---

## Defect 5 — `purchaseCost` is a `Double` against a `NUMERIC(12,2)` column

* **Bug:** `private Double purchaseCost;` mapped to `purchase_cost NUMERIC(12,2)`, and
  `AssetService.costReport` accumulates it with `double grandTotal += ...`.
* **Affected component:** `entity/Asset`, `dto/CreateAssetRequest`, `service/AssetService.costReport`
* **Root cause:** `double` is binary floating point and cannot represent most decimal fractions
  exactly. Summing six such values accumulates representation error.
* **Symptom:** `"grandTotal": 1904373.2000000002` where the correct figure is `1904373.20`. The
  per-location subtotals in the same response are exact, because PostgreSQL computed those in
  `NUMERIC`.
* **Why the symptom is misleading:**
  * It is *nearly* right. It passes a casual glance and every eyeball test.
  * A single value round-trips perfectly — store `210000.55`, read back `210000.55`. The error only
    appears once values are **accumulated**, so a developer who checks one record concludes the
    mapping is fine.
  * The exact subtotals sitting beside the inexact total in the same JSON document is the tell, and it
    is the lead hint E1 gives.
  * It reaches finance, where it will be noticed by somebody other than a developer.
* **Correct fix:** use `BigDecimal` end to end —

  ```java
  @Column(name = "purchase_cost", nullable = false, precision = 12, scale = 2)
  private BigDecimal purchaseCost;
  ```

  and in the report:

  ```java
  BigDecimal grandTotal = BigDecimal.ZERO;
  for (Asset asset : all) {
      grandTotal = grandTotal.add(asset.getPurchaseCost());
  }
  ```

  Change `CreateAssetRequest.purchaseCost` too. Watch for two things: `BigDecimal` is **immutable**, so
  `grandTotal.add(x)` must be assigned; and the accumulation must not fall back to `doubleValue()`.
* **Concept:** decimal versus binary floating point; `NUMERIC`/`DECIMAL` mapping to `BigDecimal`;
  never using floating point for money.
* **Why a fresher makes it:** `Double` is easier — it supports `+`, prints nicely, and works for a
  single value. The rule "money is `BigDecimal`" is one of those things you learn once, usually the
  hard way.
* **How to recognise it in the wild:** trailing `...0000002` or `...9999998` in a monetary figure.
  Grep the entity package for `double`/`Double`/`float` on anything that is a currency amount. A
  reconciliation test that sums a known set of rows and asserts an exact total catches it immediately.

---

## Defect 6 — `LocalDateTime` mapped to a `TIMESTAMPTZ` column

* **Bug:** `private LocalDateTime registeredAt;` against `registered_at TIMESTAMPTZ NOT NULL`, written
  from `LocalDateTime.now()`.
* **Affected component:** `entity/Asset`, and `AssetService.create` / `changeStatus`
  (`AssetStatusHistory.changedAt` has the same problem).
* **Root cause:** `TIMESTAMPTZ` stores an absolute point in time. `LocalDateTime` is a wall-clock
  reading with **no** zone. Converting between them requires choosing a zone, and the JDBC driver
  chooses the JVM's default. The stored instant is therefore interpreted differently depending on
  where the application runs.
* **Symptom (verified):** the same row, read by the same build:

  | JVM timezone | `registeredAt` returned |
  |---|---|
  | IST (`Asia/Kolkata`) | `2026-09-05T18:58:42.589714` |
  | UTC (`-Duser.timezone=UTC`) | `2026-09-05T13:28:42.589714` |

  Five and a half hours apart, from one `SELECT`.
* **Why the symptom is misleading — this is the "works locally, fails in another environment" defect:**
  * On any single machine it is completely self-consistent. Write `18:58`, read `18:58`. Every test
    passes. There is no error, ever.
  * It only manifests when the application moves — to a UTC container, a CI runner, a colleague in
    another country — and then *every timestamp in the system* shifts at once, which looks like a data
    corruption incident rather than a mapping choice.
  * A developer on a UTC machine will never reproduce it at all.
* **Correct fix:** use a type that carries the offset —

  ```java
  @Column(name = "registered_at", nullable = false)
  private OffsetDateTime registeredAt;
  ```

  (`Instant` is equally correct and often preferable for storage; `ZonedDateTime` also works.) Then set
  it from `OffsetDateTime.now()` / `Instant.now()` rather than `LocalDateTime.now()` — hint F4 covers
  the write side, which has the same problem in reverse. Apply the same change to
  `AssetStatusHistory.changedAt`.

  Setting `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` makes the behaviour *consistent* while
  leaving the type wrong. Accept it as a mitigation, not as the fix — the field still cannot express
  what the column means.
* **Concept:** `TIMESTAMP` versus `TIMESTAMPTZ`; instants versus local date-times; timezone-dependent
  behaviour.
* **Why a fresher makes it:** `LocalDateTime` is the type they know, and it "works". Java's date-time
  API offers five plausible types and the tutorials use the wrong one.
* **How to recognise it in the wild:** run the application with `-Duser.timezone=UTC` and diff the
  output. Any timestamp that moves is mis-mapped. As a rule: store instants, and only convert to a
  local wall-clock time at the point of display.

---

## Defect 7 — `location` is `NOT NULL` in the schema and unconstrained in the API

* **Bug:** `location VARCHAR(120) NOT NULL` in `init.sql`; `Asset.location` has no `nullable = false`
  and `CreateAssetRequest.location` has no `@NotBlank`.
* **Affected component:** `dto/CreateAssetRequest` (the gap), `entity/Asset` (the mapping)
* **Root cause:** the API contract is looser than the schema, so PostgreSQL becomes the validator.
* **Symptom:** `POST` without `location` → `500`,
  `null value in column "location" of relation "assets" violates not-null constraint`.
* **Why the symptom is misleading:** the field is absent from the request rather than wrong, and
  nothing in the DTO suggests it is required. The failure surfaces from the persistence layer, so the
  stack trace points at Hibernate rather than at the request.
* **Correct fix:**

  ```java
  // CreateAssetRequest
  @NotBlank(message = "location is required")
  private String location;

  // Asset
  @Column(name = "location", nullable = false, length = 120)
  private String location;
  ```

  Hint G2 asks for a sweep: check every column in `init.sql` against the DTO in both directions —
  `NOT NULL` columns need `@NotBlank`/`@NotNull`, and length-limited columns need `@Size`. `assetTag`
  (40), `name` (150) and `location` (120) all lack length constraints.
* **Concept:** validating at the boundary; keeping the API contract aligned with the schema.
* **Why a fresher makes it:** they map the columns they need and constrain only the fields they think
  of as "important". The database quietly covers for them until someone omits a field.
* **How to recognise it in the wild:** any `DataIntegrityViolationException` reaching a client. It
  always means a missing check upstream. `ddl-auto=validate` would not catch this one — nullability
  drift in that direction is not a schema mismatch — so it needs a deliberate review.

---

## A note on `ddl-auto`

Worth raising once the learner has finished. This project uses `ddl-auto=none`, which is why the
application starts cleanly and every mapping error waits for a query. Had it been `validate`,
Defects 1, 2 and 5 would all have failed loudly at start-up with precise messages naming the table,
the column and the type — before a single request was served.

Project 05 taught that `create-drop` hides schema drift. This one shows the other end of the scale:
`none` does not hide drift, it just *defers* it to whichever unlucky user first calls the affected
endpoint. `validate` is almost always the right answer for a schema you do not own. If the learner
proposes switching to it as an eighth improvement, that is exactly the right instinct — credit it.

## Suggested fix order

All seven are independent. A reasonable route:

1. **Defect 3** (`IFNULL`) — one word, and it makes the report reachable.
2. **Defect 5** (`Double` → `BigDecimal`) — now visible in that report.
3. **Defect 2** (`@Enumerated`) — the history endpoint.
4. **Defect 1** (quoted table name) — the categories endpoint.
5. **Defect 4** (sequence) — reproduce from a clean volume first.
6. **Defect 7** (`location`) — then sweep the other columns.
7. **Defect 6** (timestamps) — last, because verifying it needs the two-JVM comparison.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `everyMigratedAssetIsListed` | pass | — |
| `theCategoryReferenceDataIsAvailable` | error | Defect 1 |
| `theStatusHistoryOfAnAssetIsAvailable` | error | Defect 2 |
| `theCostReportIsProducedAndAddsUpExactly` | error | Defect 3, then Defect 5 |
| `aNewAssetCanBeRegistered` | error | Defect 4 |

Baseline: `Tests run: 5, Failures: 0, Errors: 4`.

`theCostReportIsProducedAndAddsUpExactly` fails twice for different reasons: first the query does not
run at all, then it runs and returns `1904373.2000000002` against an expected `1904373.20`. If the
learner reports "I fixed the SQL and the test still fails", that is the intended staging.

**Defects 6 and 7 are not covered by any test.** Defect 7 needs a deliberately incomplete payload;
Defect 6 cannot be caught by a test running in a single JVM timezone at all — which is precisely why
it survives into production.

## Verification commands

```bash
docker compose down -v && docker compose up -d      # clean schema and seed
mvn clean package && java -jar target/asset-tracking-1.0.0.jar
mvn test        # 5/5 green

curl -s localhost:8080/api/categories | python -c "import sys,json;print(len(json.load(sys.stdin)))"   # 4
curl -s localhost:8080/api/assets/5/history | python -c "import sys,json;print([h['status'] for h in json.load(sys.stdin)])"
#   ['IN_SERVICE', 'RETIRED']
curl -s localhost:8080/api/assets/report | python -c "import sys,json;print(json.load(sys.stdin)['grandTotal'])"
#   1904373.20   -- exactly, no trailing digits

# first attempt on a clean database must succeed
curl -i -X POST localhost:8080/api/assets -H 'Content-Type: application/json' \
  -d '{"assetTag":"AST-0100","name":"ThinkPad P16","categoryId":1,"location":"Bengaluru HQ","purchaseCost":210000.55,"purchasedAt":"2026-02-01"}'

# missing location must be 400, not 500
curl -i -X POST localhost:8080/api/assets -H 'Content-Type: application/json' \
  -d '{"assetTag":"AST-0101","name":"Dell Latitude","categoryId":1,"purchaseCost":90000.00,"purchasedAt":"2026-02-02"}'
```

The timezone check — both runs must print the same value:

```bash
java -jar target/asset-tracking-1.0.0.jar &
curl -s localhost:8080/api/assets | grep -o '"registeredAt":"[^"]*"' | head -1
# stop it, then
java -Duser.timezone=UTC -jar target/asset-tracking-1.0.0.jar &
curl -s localhost:8080/api/assets | grep -o '"registeredAt":"[^"]*"' | head -1
```

And the sequence check:

```sql
SELECT last_value FROM assets_id_seq;      -- must be >= MAX(id)
SELECT MAX(id) FROM assets;
```
