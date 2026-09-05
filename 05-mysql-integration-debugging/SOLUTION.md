# SOLUTION — 05 · Employee Directory API

> **Sealed answer key.** Five planted defects.

---

## Defect 1 — The entity is mapped to `employee`; the real table is `employees`

* **Bug:** `@Table(name = "employee")` on `Employee`, while `db/init.sql` creates `employees`.
* **Affected component:** `entity/Employee.java`
* **Root cause:** the entity points at a table that does not exist in the DBA-owned schema.
* **Symptom:** `GET /api/employees` returns `[]` and `GET /api/employees/1` returns `404`, while
  `SELECT COUNT(*) FROM employees` returns `8`. The classic "the data is right there in MySQL and
  JPA cannot see it".
* **Why the symptom is misleading:** there is **no error at all**. The application connects, the
  queries succeed, the results are simply empty. A learner naturally suspects the connection string,
  the credentials, the transaction, or the repository — everything except the table name, because
  the idea that Hibernate is querying a *different table* does not occur to most people until they
  run `SHOW TABLES` and find three tables where they expected two.
* **Correct fix:**

  ```java
  @Table(name = "employees")
  ```

* **Concept:** `@Table` mapping; the difference between an entity name and a table name.
* **Why a fresher makes it:** they write the entity first, from the class name (`Employee` →
  `employee`), and never check it against the existing schema. Singular-vs-plural table naming is a
  house-style difference between teams, so the mismatch is extremely common when an application is
  retro-fitted onto an existing database.
* **How to recognise it in the wild:** `SHOW TABLES` after the first application start-up. An
  unexpected extra table is a mapping error that `ddl-auto` has papered over. Also read the `select`
  in the SQL log — the table name is right there in it.

**Interacts with Defect 2:** on its own this would be a loud failure. Defect 2 is what makes it
silent.

---

## Defect 2 — `ddl-auto=create-drop` against a schema the application does not own

* **Bug:** `spring.jpa.hibernate.ddl-auto=create-drop`
* **Affected component:** `src/main/resources/application.properties`
* **Root cause:** `create-drop` makes Hibernate generate the schema from the entity mappings at
  start-up and drop it at shutdown. Applied to an application whose schema is owned by
  `db/init.sql`, it has two effects:
  1. Hibernate **creates** `employee` (the table Defect 1 asks for), so the mapping error never
     surfaces as an error. This is what converts Defect 1 from a five-second fix into a genuine
     investigation.
  2. Everything the application writes is destroyed on every restart.
* **Symptom:** an employee created through the API is gone after restarting the application; and,
  indirectly, the silence around Defect 1.
* **Why the symptom is misleading:** `create-drop` is the default that every tutorial uses, because
  every tutorial uses H2. It is invisible in an in-memory database — you cannot tell the difference
  between "the database was dropped" and "the database went away with the process". Moving to a real
  server exposes the difference, and the setting is usually carried over unchanged.
* **Correct fix:** `spring.jpa.hibernate.ddl-auto=validate`

  `none` is also defensible. **`validate` is the better answer** and worth pressing for: it is the
  value that would have caught Defect 1 *and* Defect 5 on the first start-up, with a precise error
  message naming the missing table and the missing column. If the learner picks `validate`, tell
  them that is exactly right and why. `update` is **wrong** here — it papers over drift the same way
  `create-drop` does, just without destroying data.
* **Concept:** `ddl-auto` semantics; schema ownership; who is responsible for migrations.
* **Why a fresher makes it:** it is copied from the previous project and it makes the application
  start. Nothing about it looks like a decision.
* **How to recognise it in the wild:** any application pointed at a shared or production-shaped
  database must be on `validate` or `none`, with migrations handled by Flyway/Liquibase. Treat
  `create-drop` or `update` in a non-embedded datasource as a defect on sight.

---

## Defect 3 — A hand-written native query becomes a second source of truth

* **Bug:**

  ```java
  @Query(value = "SELECT id, first_name, last_name, email, department, designation, salary, "
          + "joining_date AS date_of_joining, active FROM employees "
          + "WHERE LOWER(department) = LOWER(?1) ORDER BY first_name",
          nativeQuery = true)
  List<Employee> searchByDepartment(String department);
  ```

  A perfectly good derived query, `findByDepartmentIgnoreCase`, exists two lines above it and is
  never called.
* **Affected component:** `repository/EmployeeRepository.java`; used by
  `EmployeeService.findByDepartment`.
* **Root cause:** the native query hardcodes the *real* table name (`employees`) and works around the
  *real* column name (`joining_date AS date_of_joining`). It is therefore correct — and it is the
  only thing in the application that is, which is why it disagrees with everything else.
* **Symptom:** `by-department` returns three fully populated engineers at the same moment that the
  directory listing returns `[]`. Two endpoints of one application, reading two different tables.
* **Why this is the most valuable symptom in the project:** it is the learner's lever. A single
  empty endpoint suggests a hundred possible causes; two endpoints disagreeing narrows it to "these
  two code paths resolve the table differently", which points straight at the entity mapping. Guide
  them here if they are stuck on Defect 1.
* **Correct fix:** delete the native query and use the derived one:

  ```java
  List<Employee> findByDepartmentIgnoreCaseOrderByFirstNameAsc(String department);
  ```

  and call that from the service.
* **Why it is a defect even though it "works":** it duplicates knowledge that already lives in the
  entity mapping — the table name and the column name — in a string literal that no compiler, no
  test and no schema validation will ever check. It is the mechanism by which the other two defects
  stayed hidden. If the learner argues "but it returns the right answer", agree that it does, and
  ask what happens to it the day someone renames the table. That is the lesson.
  A learner who instead fixes the mapping and *keeps* the native query has done 80% of the job;
  point out the alias and ask why it is needed once the mapping is right.
* **Concept:** native queries versus derived queries; single source of truth; the maintenance cost of
  SQL in string literals.
* **Why a fresher makes it:** they could not get a derived query to behave (probably because of
  Defect 1 or Defect 5) and "just wrote the SQL" to unblock themselves. Then it worked, so it stayed.
  This is an extremely realistic sequence of events.
* **How to recognise it in the wild:** a native query alongside a derived query that does the same
  thing is always worth reading. An `AS` alias mapping a column onto a different name is a
  confession that the entity mapping is wrong somewhere.

---

## Defect 4 — Column drift: `dateOfJoining` maps to `date_of_joining`, the table has `joining_date`

* **Bug:** `private LocalDate dateOfJoining;` with no `@Column(name = ...)`. Spring Boot's default
  `CamelCaseToUnderscoresNamingStrategy` derives `date_of_joining`. `db/init.sql` declares
  `joining_date`.
* **Affected component:** `entity/Employee.java` versus `db/init.sql`
* **Root cause:** entity/schema drift on one column.
* **Symptom:** **hidden until Defect 1 and Defect 2 are fixed.** While `create-drop` is in force,
  Hibernate creates its own `employee` table containing a `date_of_joining` column, so the mapping
  is self-consistent and nothing fails. The moment the entity is pointed at the real `employees`
  table with `ddl-auto=validate`, start-up fails with:

  ```
  Schema-validation: missing column [date_of_joining] in table [employees]
  ```

  With `ddl-auto=none` instead, it fails at query time with
  `Unknown column 'e1_0.date_of_joining' in 'field list'`.
* **Why the symptom is misleading:** it appears *after* the learner's own correct fix and looks like
  a regression they caused. The guide warns them explicitly not to revert. The native query in
  Defect 3 is the only place in the codebase that acknowledges this column exists under a different
  name — which is why hint B3 sends them there.
* **Correct fix:**

  ```java
  @Column(name = "joining_date")
  private LocalDate dateOfJoining;
  ```

  Editing `db/init.sql` is **not** an acceptable fix — the README states the schema belongs to the
  DBA team and other systems read it. If the learner changes the SQL, accept that it makes the tests
  pass, then explain why it would not fly in a real organisation.
* **Concept:** implicit naming strategies; `@Column(name = ...)`; schema validation as a drift
  detector.
* **Why a fresher makes it:** they assume the naming strategy will match whatever the DBA chose.
  `date_of_joining` and `joining_date` are both perfectly reasonable names for the same thing, and
  the field name reads correctly either way.
* **How to recognise it in the wild:** this is precisely what `ddl-auto=validate` is for. Any project
  mapped onto an existing schema should run `validate` in every environment, so that drift is a
  start-up failure rather than a production surprise.

---

## Defect 5 — Pool of one connection plus a `REQUIRES_NEW` audit transaction

* **Bug:** `spring.datasource.hikari.maximum-pool-size=1` combined with
  `AuditService.record(...)` annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)` and
  called from inside the `@Transactional` `EmployeeService.create(...)`.
* **Affected component:** `src/main/resources/application.properties` and
  `service/AuditService.java` / `service/EmployeeService.java` together. **Neither is wrong on its
  own** — this defect exists only as a combination, which is why it is the most instructive one in
  the project.
* **Root cause:** `EmployeeService.create` opens a transaction, which acquires the pool's only
  connection and holds it for the duration (Hibernate's default connection handling is
  `DELAYED_ACQUISITION_AND_HOLD`). It then calls `auditService.record(...)`, whose `REQUIRES_NEW`
  causes Spring to **suspend** the outer transaction — suspension does *not* release its
  connection — and start a new one, which requests a second connection from a pool that has none
  left and never will. The caller waits `connection-timeout` milliseconds and then fails. It is a
  self-deadlock: the thread is waiting for a resource that only it holds.
* **Symptom:** `POST /api/employees` blocks for exactly 10 seconds and returns `500`. The log shows
  `Connection is not available, request timed out after 10005ms (total=1, active=1, idle=0,
  waiting=0)` followed by `CannotCreateTransactionException`. All read endpoints are unaffected.
  Nothing is written to either table.
* **Why the symptom is misleading:**
  * It looks like a database performance problem — the request is *slow*, and slow suggests load.
    MySQL is idle.
  * `total=1, active=1, waiting=0` reads like a healthy pool to someone who has not internalised
    that `1` is the maximum.
  * The exception (`CannotCreateTransactionException`) names transactions, and the pool message
    names connections; connecting the two requires knowing that `REQUIRES_NEW` needs its own
    connection.
  * Both ingredients are individually defensible. A pool of 1 is unusual but not illegal; a
    `REQUIRES_NEW` audit write is a documented, deliberate design decision explained in the README.
* **Correct fix — accept either, prefer a discussion of both:**
  1. **Size the pool for the concurrency the application actually needs.** Remove the override or set
     something sane (Hikari's default is `10`). The rule to teach: a pool must be at least as large
     as the maximum number of connections a single request can hold simultaneously, multiplied by
     the number of concurrent requests you intend to serve.
  2. **Question the propagation.** The README justifies `REQUIRES_NEW` with "the audit trail should
     survive a rollback". That is a real requirement, but it is worth asking whether an audit row is
     worth a second connection per write, and whether an event listener bound to the transaction, or
     an append-only log, would serve better.

  A learner who only enlarges the pool has fixed it. A learner who explains *why* two connections
  were needed has understood it. A learner who removes `REQUIRES_NEW` has also fixed it, but has
  changed the documented behaviour — point that out.
* **Concept:** connection pool sizing; transaction propagation and suspension; the fact that a
  suspended transaction retains its connection; deadlock through resource exhaustion by a single
  thread.
* **Why a fresher makes it:** they set the pool to 1 while chasing a "too many connections" error, or
  copied a memory-constrained container config. Then someone else adds `REQUIRES_NEW` months later.
  Neither change is wrong in isolation and neither author sees the other's code.
* **How to recognise it in the wild:** one endpoint hanging for exactly the connection-timeout value
  while the rest of the application is healthy. Read the Hikari numbers. Then count how many
  transactions a single request needs — anything using `REQUIRES_NEW`, a nested `@Async`, or a
  second datasource can need more than one connection at once.

---

## Suggested fix order

1. **Defect 5** — completely independent. A clean win, and it makes the write path usable so the
   learner can test the others.
2. **Defect 3** → use it as evidence, then **Defect 1** (the table name).
3. **Defect 2** — change `ddl-auto` to `validate`. This is what makes Defect 4 announce itself.
4. **Defect 4** — the column mapping.
5. Return to **Defect 3** and delete the native query now that the derived one works.

A learner who sets `ddl-auto=validate` *first* will get an immediate, precise error naming both the
missing table and the missing column, and can fix Defects 1 and 4 in one pass. That is the expert
route. If they do that, say so — it means they understood that the tool was lying to them before they
understood what it was lying about.

## Test expectations

All three tests fail initially.

| Test | Fails because of |
|---|---|
| `directoryListsEveryEmployeeHeldInTheDatabase` | Defect 1 (+2) |
| `departmentLookupAgreesWithTheDirectoryListing` | Defect 1 — the `by-department` half passes, the `GET /{id}` half does not |
| `anEmployeeCanBeCreated` | Defect 5 |

`departmentLookupAgreesWithTheDirectoryListing` is written deliberately so that its first assertion
passes and its second fails. That is the contradiction of Symptom B, expressed as a test.

Defects 2 and 4 are **not** covered by any test — Defect 2 requires a restart and Defect 4 does not
exist until Defects 1 and 2 are fixed.

## Verification commands

```bash
docker compose down -v && docker compose up -d      # clean slate
# wait for readiness
mvn clean package && java -jar target/employee-directory-1.0.0.jar

curl -s http://localhost:8080/api/employees | python -c "import sys,json;print(len(json.load(sys.stdin)))"   # 8
curl -s http://localhost:8080/api/employees/1                                                                # Aarav
curl -s "http://localhost:8080/api/employees/by-department?department=engineering" \
  | python -c "import sys,json;print(len(json.load(sys.stdin)))"                                             # 3

time curl -s -X POST http://localhost:8080/api/employees -H 'Content-Type: application/json' \
  -d '{"firstName":"Nisha","lastName":"Verma","email":"nisha.verma@company.com","department":"Engineering","designation":"Engineer","salary":1300000,"dateOfJoining":"2026-03-01"}'
# 201, well under a second
```

```sql
-- exactly two tables, no stray `employee`
SHOW TABLES;
SELECT COUNT(*) FROM employees;      -- 9
SELECT COUNT(*) FROM audit_entry;    -- 1
```

Then restart the application and confirm `SELECT COUNT(*) FROM employees;` is still `9`.
