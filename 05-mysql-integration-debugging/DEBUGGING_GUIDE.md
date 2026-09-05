# Debugging Guide — 05 · Employee Directory API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

This is the first project in the lab with a **real database server** and a schema the application
did not create. That changes the nature of debugging completely: you now have a second, independent
source of truth. When the API and the database disagree, the database is right.

The single most important habit this project teaches is:

> Open a MySQL shell. Look at the actual tables. Do not reason about persistence from HTTP responses.

## Expected behaviour

1. `GET /api/employees` returns the eight seeded employees.
2. `GET /api/employees/{id}` returns one of them.
3. `GET /api/employees/by-department?department=engineering` returns three of them — and those three
   are a **subset** of what the directory listing returned.
4. `POST /api/employees` returns `201` promptly and writes a row to `employees` and a row to
   `audit_entry`.
5. Data written by the application is still there after you restart the application.

## How to reproduce

```bash
docker compose up -d
# wait until it is ready
docker exec debuglab05-mysql mysql -uroot -prootpw hrdb -e "SELECT COUNT(*) FROM employees;"
#   -> 8

mvn clean package
java -jar target/employee-directory-1.0.0.jar
```

Keep a MySQL shell open in a second terminal throughout:

```bash
docker exec -it debuglab05-mysql mysql -uroot -prootpw hrdb
```

`spring.jpa.show-sql=true` is on. Read the SQL. Read it at start-up too, not just at request time.

`mvn test` runs three tests (with the container up). All three fail.

---

## Known symptoms

### Symptom A — the directory is empty, but the database is not

```
$ docker exec debuglab05-mysql mysql -uroot -prootpw hrdb -e "SELECT COUNT(*) FROM employees;"
8

$ curl http://localhost:8080/api/employees
[]

$ curl http://localhost:8080/api/employees/1
{"status":404,"error":"Not Found","message":"No employee exists with id 1"}
```

The application connects successfully — there is no error anywhere. It simply reports that there are
no employees, while eight of them are sitting in the table you just queried.

### Symptom B — two endpoints disagree about the same data

```
$ curl "http://localhost:8080/api/employees/by-department?department=engineering"
[ {"id":1,"firstName":"Aarav","email":"aarav.sharma@company.com","dateOfJoining":"2021-06-14", ...},
  {"id":2,"firstName":"Divya", ...},
  {"id":3,"firstName":"Rohan", ...} ]
```

Three employees, fully populated, correct joining dates. From the *same application*, over the
*same connection*, at the *same moment* that `GET /api/employees` returns `[]` and
`GET /api/employees/1` returns `404`.

This is the most useful symptom in the project. One of these two endpoints is reading something the
other one is not.

### Symptom C — creating an employee hangs and then fails

```
$ time curl -X POST http://localhost:8080/api/employees -H 'Content-Type: application/json' \
    -d '{"firstName":"Nisha", ... }'

{"status":500,"error":"Internal Server Error","path":"/api/employees"}
real    0m10.4s
```

It is not slow because MySQL is slow — MySQL answers the other endpoints in milliseconds. It waits
for a fixed period and then gives up. The application log shows:

```
... Connection is not available, request timed out after 10005ms (total=1, active=1, idle=0, waiting=0)
... CannotCreateTransactionException
```

Nothing is written: no employee row, no audit row.

### Symptom D — records do not survive a restart

Once you can write at all, write something, confirm it is in the table, then stop and restart the
application. Query the table again.

### Symptom E — a mapping problem that only appears after you fix Symptom A

When you correct whatever is causing Symptom A, the application may start refusing to start, or
every query may begin failing with an "unknown column" error naming a column that is *not* in
`db/init.sql`.

That is not a mistake in your fix. It is a second, independent problem that the first one was
hiding. Do not revert.

---

## Investigation hints

### Symptom A — empty directory, populated table

> **Hint A1**
> Before touching any code: list the tables.
>
> ```sql
> SHOW TABLES;
> ```
>
> Do that **after** the application has started at least once. Count them. Is the number what you
> expected?

> **Hint A2**
> Read the SQL the application logs when it starts up, and the `select` it logs when you call
> `GET /api/employees`. What table name appears in that `select`?

> **Hint A3**
> Two things decide which table an entity reads: an annotation on the entity class, and the
> implicit naming strategy that applies when that annotation is absent. Find the annotation and
> compare what it says with what `db/init.sql` creates.

> **Hint A4**
> Now the deeper question, and it is the one worth taking away: **why did nothing fail?** If the
> application is mapped onto a table that does not exist, you would expect a loud error on the first
> query. Something in the configuration made the application create the table it was looking for
> instead of complaining that it was missing. Find that setting and read what its possible values
> mean.

### Symptom B — endpoints disagreeing

> **Hint B1**
> Compare the two repository methods behind these endpoints. They are not written in the same style.
> One of them is not derived from the entity mapping at all.

> **Hint B2**
> Read the hand-written query carefully — the whole thing, including the part after `SELECT`. There
> is an `AS` in it. Ask yourself why anyone would have needed to write that alias. What were they
> working around?

> **Hint B3**
> That alias is a clue to Symptom E, not just to Symptom B. Someone before you knew the entity and
> the table did not agree about one column, and papered over it in one query instead of fixing the
> mapping.

> **Hint B4**
> The fix here is not "make the native query match". It is to ask why this endpoint has a
> hand-written query at all, when a derived query on the same repository would express the same
> thing and could never drift from the entity mapping. Notice that such a method already exists a
> few lines above and is never called.

### Symptom C — the hanging create

> **Hint C1**
> Read the numbers in the log message. `total=1, active=1, idle=0`. The pool has one connection and
> it is already in use. In use *by whom*? You are the only caller.

> **Hint C2**
> The read endpoints work fine. Only the write path hangs. What does the write path do that the read
> path does not? Follow the call from the controller down, and count how many separate database
> transactions one `POST` involves.

> **Hint C3**
> Look up what `Propagation.REQUIRES_NEW` does to the transaction that is already running. The word
> to search for is "suspend". A suspended transaction does not give its connection back.

> **Hint C4**
> There are two independent things to consider here, and a good answer addresses both. One is the
> pool size — is `1` a defensible number for an application that legitimately needs two concurrent
> transactions? The other is the design: is a separate transaction actually the right way to write
> this audit row, given the reason the README gives for it? Form an opinion on both.

### Symptom D — data that does not survive

> **Hint D1**
> This is the same setting you already found in hint A4. Read its documentation properly — all of
> the values it accepts, not just the one that is set.

> **Hint D2**
> Ask a design question: this application does not own its schema. `db/init.sql` does. So what is
> the *only* defensible value for that setting in a project like this — the one that would have
> made Symptom A fail loudly on day one instead of silently succeeding?

### Symptom E — the unknown column

> **Hint E1**
> Compare the column list in `db/init.sql` with the fields on the entity, one by one. Seven of them
> line up. One does not.

> **Hint E2**
> When a field has no explicit column annotation, Hibernate derives the column name from the field
> name using a naming strategy. Work out what name it derives for the field in question, then look
> for that name in `db/init.sql`.

> **Hint E3**
> You cannot change `db/init.sql` — the schema belongs to the DBA team and other systems read it.
> So the fix has to be on the Java side. There is an annotation attribute for exactly this.

---

## Expected logs and observations

* The application starts cleanly and connects to MySQL without complaint. Every problem here is
  behavioural or configurational, not a connection failure.
* At start-up Hibernate logs the DDL it executes. Read those statements — they tell you what the
  application believes the schema should be, which you can then diff against reality.
* `SHOW TABLES` before and after the first application start is the single most informative command
  in this project.
* The Hikari message `Connection is not available, request timed out after 10005ms (total=1,
  active=1, idle=0, waiting=0)` is precise: read every number in it.
* After a failed `POST`, check both `employees`/`employee` **and** `audit_entry`. Understanding why
  *neither* was written tells you where the failure happened.

## Difficulty

**Intermediate.** Expect 90 minutes to two hours.

Symptoms A, B and D are one tangle — B is your evidence for A, and A and D share a contributing
cause. Symptom E is hidden behind A. Symptom C is completely independent and can be done first if
you prefer a clean win.

## Concepts being tested

* `@Table` and `@Column` versus Hibernate's implicit naming strategy
* `spring.jpa.hibernate.ddl-auto` and what each value does to a schema the application does not own
* Schema/entity drift, and why `validate` catches it while `update` and `create-drop` hide it
* Native queries as a second source of truth, and how they drift from entity mappings
* HikariCP sizing, connection acquisition and pool exhaustion
* `@Transactional(propagation = REQUIRES_NEW)`, transaction suspension and connection lifetime
* Diagnosing from database state rather than API responses

## When you think you are done

- [ ] `mvn test` is green (3 tests, container running).
- [ ] `GET /api/employees` returns eight employees.
- [ ] `by-department` returns a strict subset of the directory listing, and every id in it is
      fetchable via `GET /api/employees/{id}`.
- [ ] `POST /api/employees` returns `201` in well under a second, and adds a row to both `employees`
      and `audit_entry`.
- [ ] `SHOW TABLES` lists exactly the tables that should exist — no extras.
- [ ] An employee you create is still there after restarting the application.
- [ ] `docker compose down -v && docker compose up -d`, then start the application: it still works,
      and the seed data is intact afterwards.

Then say **"I think I fixed the project"**.
