# Debugging Guide — 13 · Asset Tracking API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

This is the first project in the lab on **PostgreSQL**, and that is the point. Almost everything here
is a place where PostgreSQL behaves differently from MySQL, or where Hibernate's defaults quietly
disagree with a schema somebody else wrote.

`db/init.sql` is the specification. It is not yours to change — the finance export reads those tables.
Every fix in this project is on the Java side.

Two instruments, both already switched on:

* `logging.level.org.hibernate.SQL=DEBUG` prints every statement Hibernate issues. When a query fails,
  the statement it *tried* to run is in the log, and it is usually not the one you think you wrote.
* `psql` shows you what the schema actually is. `\d assets` gives you the column types.

## Expected behaviour

1. Every endpoint returns `2xx`. Nothing returns `5xx`.
2. `GET /api/assets/report` gives `grandTotal: 1904373.20` — exactly.
3. Statuses read back as names: `IN_SERVICE`, `IN_REPAIR`, `RETIRED`.
4. `POST /api/assets` works on the first attempt and every attempt after it.
5. A given `registered_at` reads back as the same moment wherever the application runs.
6. Anything the schema forbids is a `400` from the API, not a `500` from PostgreSQL.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/asset-tracking-1.0.0.jar
```

Then call each endpoint in the README in turn. Four of the six fail immediately. Keep `psql` open:

```bash
docker exec -it debuglab13-postgres psql -U assetuser -d assetsdb
```

`mvn test` runs five tests: one passes, four error.

---

## Known symptoms

### Symptom A — the category endpoint says the table does not exist

```
GET /api/categories
500

JDBC exception executing SQL [select ac1_0.id,ac1_0.description,ac1_0.name from asset_category ac1_0]
  [ERROR: relation "asset_category" does not exist]
```

```sql
\dt
              List of relations
 Schema |         Name         | Type
--------+----------------------+-------
 public | AssetCategory        | table
 public | asset_status_history | table
 public | assets               | table
```

The table is right there. Read the failing SQL very carefully and compare the name in it with the
name in the list — **and** with the name written in the entity's mapping annotation. All three are
different, and that is the whole puzzle.

### Symptom B — the status history cannot be read

```
GET /api/assets/5/history
500

Could not extract column [5] from JDBC ResultSet [Bad value for type byte : IN_SERVICE]
```

`byte`. Nothing in this application has a `byte` in it. Meanwhile:

```sql
\d asset_status_history
   Column   |          Type          |
------------+------------------------+
 status     | character varying(20)  |
```

The column is text, the value is `IN_SERVICE`, and something is trying to read it as a number.

### Symptom C — the report query is not valid PostgreSQL

```
GET /api/assets/report
500

[ERROR: function ifnull(numeric, integer) does not exist]
  Hint: No function matches the given name and argument types.
```

The function name will look extremely familiar if you have written any MySQL.

### Symptom D — registering an asset fails on a fresh database

```
POST /api/assets   {"assetTag":"AST-0100", ...}
500

[ERROR: duplicate key value violates unique constraint "assets_pkey"
  Detail: Key (id)=(1) already exists.]
```

You did not supply an id. The application did not supply an id. Something chose `1`, and `1` has been
taken since the database was seeded.

Try it again. And again. Count how many attempts fail before one succeeds, and compare that number
with something you can see in the `assets` table. This is the "works after a while" defect the lab
brief asks for, and understanding *why* it eventually starts working is the whole lesson.

### Symptom E — the grand total has floating-point residue

Once the report runs at all:

```json
{ "byLocation": [ ... ], "assetCount": 6, "grandTotal": 1904373.2000000002 }
```

The correct figure is `1904373.20`. The per-location subtotals, which PostgreSQL computed, are exact.
Only the total the application computed is not. This number goes to finance.

### Symptom F — the same row reports a different time in a different environment

```bash
java -jar target/asset-tracking-1.0.0.jar
curl -s localhost:8080/api/assets | grep -o '"registeredAt":"[^"]*"' | head -1
#   "registeredAt":"2026-09-05T18:58:42.589714"
```

```bash
java -Duser.timezone=UTC -jar target/asset-tracking-1.0.0.jar
curl -s localhost:8080/api/assets | grep -o '"registeredAt":"[^"]*"' | head -1
#   "registeredAt":"2026-09-05T13:28:42.589714"
```

Same database, same row, same code — five and a half hours apart. Nothing changed except the
timezone the JVM happens to be running in. Deploy this to a server in UTC and every timestamp in the
system moves.

```sql
SELECT registered_at FROM assets WHERE asset_tag = 'AST-0100';
```

### Symptom G — a required column is rejected by PostgreSQL, not by the API

```
POST /api/assets   { ... no "location" ... }
500

[ERROR: null value in column "location" of relation "assets" violates not-null constraint]
```

The caller gets a `500` and a message about a constraint name. They have no way to know they simply
forgot a field.

---

## Investigation hints

### Symptom A — the missing relation

> **Hint A1**
> Three names are in play: the one in `init.sql`, the one in the entity's annotation, and the one in
> the SQL Hibernate generated. Write all three down. Two of them match; the third does not match
> either.

> **Hint A2**
> Look at how `init.sql` writes that table name. There is punctuation around it that is not around
> the other two tables. Look up what quoting an identifier does in PostgreSQL, and what happens to an
> **un**quoted identifier.

> **Hint A3**
> Now the Spring-specific half, and it is the surprising one: the developer wrote the table name
> *exactly right* in the annotation, and Hibernate still emitted something else. Spring Boot installs
> a physical naming strategy that rewrites identifiers — including ones you spelled out yourself.
> Find out what `CamelCaseToUnderscoresNamingStrategy` does to `AssetCategory`.

> **Hint A4**
> Two ways out: make the name survive the naming strategy, or stop the strategy applying. Look up how
> to quote an identifier in a JPA `@Table`/`@Column` annotation so that it is passed through
> untouched. Then decide whether you would rather fix this one mapping or change the naming strategy
> for the whole application — and what the second option would do to the *other* two entities, which
> currently work.

### Symptom B — the byte

> **Hint B1**
> Find the field behind that column in the entity and look at its Java type. It is not a `String`.

> **Hint B2**
> How does JPA store an enum by default, if you do not tell it otherwise? There are exactly two
> options and the default is the one almost nobody wants.

> **Hint B3**
> The default stores the enum's *position* in the declaration list as a number — hence the attempt to
> read a `byte`. Find the annotation that changes this, and the value that makes it store the name.

> **Hint B4**
> Worth understanding even after you have fixed it: what would have happened if the column *had*
> been an integer and somebody later inserted a new constant in the middle of the enum declaration?
> That is why the default is dangerous, and why it is worth checking every enum field in a codebase.

### Symptom C — the unknown function

> **Hint C1**
> The error names the function. Search for it in the repository. It is in a hand-written native
> query.

> **Hint C2**
> That function exists in MySQL. It does not exist in PostgreSQL, which has a standard-SQL equivalent
> that does the same job. Find it.

> **Hint C3**
> The broader point: a native query is a promise that you will maintain SQL for one specific
> database. This one was clearly written against a different one. Ask whether it needs to be native
> at all — could the same result be expressed in JPQL, or as a projection, and thereby survive a
> change of database?

### Symptom D — the duplicate primary key

> **Hint D1**
> You did not choose that id and neither did the application, so the database did. Find out which
> mechanism generates it:
>
> ```sql
> \d assets
> ```
>
> Look at the `Default` column for `id`.

> **Hint D2**
> That mechanism has its own state — a counter — and you can read it:
>
> ```sql
> SELECT last_value, is_called FROM assets_id_seq;
> SELECT MAX(id) FROM assets;
> ```
>
> Compare the two numbers.

> **Hint D3**
> Now look at `init.sql` and notice what the `INSERT` statements do that most seeds do not: they name
> the `id` column explicitly. Inserting an explicit value into an identity column does **not** advance
> the counter. So the counter is still where it started, and the rows it will hand out have already
> been used.

> **Hint D4**
> There are two things to fix, and they are different in kind. One is data: bring the counter up to
> date, which is a one-line SQL command you should look up (`setval` and `pg_get_serial_sequence`).
> The other is process: the seed script will do the same thing again the next time anyone runs
> `docker compose down -v`. Where does that correction belong so it is not a manual step? Note the
> constraint that you may not change what the ids *are* — finance depends on them.

### Symptom E — the floating-point total

> **Hint E1**
> The subtotals are exact and the grand total is not. So the two are computed in different places.
> Find where each one comes from.

> **Hint E2**
> Look at the Java type of the cost field on the entity, and then at the column's type in `psql`.
> One of them stores decimal digits exactly; the other stores a binary approximation.

> **Hint E3**
> `0.1 + 0.2` in a `double` is not `0.3`, and never will be. Any money value is a decimal quantity,
> and the type that represents it exactly in Java is not a primitive.

> **Hint E4**
> Changing the type will ripple — the DTO, the mapper, the accumulation in the report. Do the whole
> chain, and be careful how you *add* the values once you have changed it: the new type does not use
> `+`, and it is immutable.

### Symptom F — the shifting timestamp

> **Hint F1**
> Look at the column's type in `psql` and then at the Java type of the field mapped to it. Read the
> `TZ` at the end of the column type out loud — it means the column stores a point in time. Does the
> Java type?

> **Hint F2**
> A `LocalDateTime` is a wall-clock reading with no timezone. Converting an instant into one requires
> choosing a zone, and the driver chooses the JVM's. That is why the answer moves when the JVM moves.

> **Hint F3**
> Java has types that *do* carry the necessary information. Pick the one that best matches "a point on
> the timeline", change the field to it, and re-run the two-JVM comparison from Symptom F until both
> agree.

> **Hint F4**
> Also check what the application *writes*. It currently sets that field from a wall-clock reading
> too, which has the same problem in reverse.

### Symptom G — the not-null constraint

> **Hint G1**
> Compare that column's definition in `init.sql` with the constraints on the corresponding field in
> the request DTO and the entity. The schema requires it. Does anything else?

> **Hint G2**
> The schema is the last line of defence, not the first. Add the constraint where the caller can be
> told about it usefully — and check the other columns while you are there, in both directions:
> anything the schema requires and anything whose length or type it limits.

---

## Expected logs and observations

* The application **starts cleanly** — `ddl-auto=none` means Hibernate checks nothing at start-up.
  Every mismatch between the entity and the schema therefore waits until a query touches it. That is
  the price of `none`, and it is worth thinking about whether `validate` would have been the better
  setting here.
* `org.hibernate.SQL=DEBUG` prints the statement before each failure. For Symptom A the printed SQL
  is the entire answer.
* PostgreSQL error messages are unusually good — they name the relation, the constraint, the function
  and the types. Read them completely, including the `Detail:` and `Hint:` lines.
* Symptoms E and F produce **no error at all**. They are wrong answers, not failures.
* Symptom D is the interesting one to reproduce carefully: it fails a fixed number of times and then
  starts working. Do not stop at "it works now".

## Difficulty

**Advanced.** Expect two to three hours. Seven defects.

They are independent — nothing here gates anything else, so you can attack them in any order. Four
announce themselves loudly, one announces itself only on the sixth attempt, and two never announce
themselves at all.

## Concepts being tested

* PostgreSQL identifier case folding, and what quoting an identifier means
* Hibernate physical naming strategies rewriting names you wrote explicitly
* `@Enumerated` — `ORDINAL` versus `STRING`, and why the default is a trap
* Identity columns, their underlying sequence, and what an explicit-id insert does to it
* `NUMERIC` versus `double`, and why money is never a floating-point type
* `timestamptz` versus `LocalDateTime`, and timezone-dependent behaviour
* Native SQL as a database-specific commitment
* Aligning DTO constraints with schema constraints
* `ddl-auto=none` versus `validate` when you do not own the schema

## When you think you are done

- [ ] `mvn test` is green (5 tests).
- [ ] Every endpoint in the README returns `2xx`.
- [ ] `GET /api/assets/report` returns **exactly** `1904373.20`, with no trailing digits.
- [ ] `GET /api/assets/5/history` shows `IN_SERVICE` then `RETIRED`.
- [ ] `docker compose down -v && docker compose up -d`, then `POST /api/assets` — it must succeed on
      the **first** attempt.
- [ ] Run the application twice, once normally and once with `-Duser.timezone=UTC`, and confirm the
      same asset reports the same `registeredAt` both times.
- [ ] `POST` without `location` returns `400` naming the field, not `500`.
- [ ] `git diff db/init.sql` is empty — you fixed the application, not the schema.
- [ ] Grep the log for `ERROR:` after exercising everything. There should be none.

Then say **"I think I fixed the project"**.
