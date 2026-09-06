# Java Backend Debugging Laboratory

Twenty standalone Spring Boot projects for practising **backend debugging** — investigating
unfamiliar code, reproducing failures, forming a theory, and finding the root cause rather than the
symptom.

Each project is a small, realistic application with a clear purpose. Each one misbehaves. Your job
is to work out why.

## How to use this lab

For each project, in order:

1. Read `README.md`. It documents the application as if it were healthy — that is your specification.
2. Start it and use it. Break it deliberately. Compare what it does with what the README promises.
3. When you are stuck, open `DEBUGGING_GUIDE.md`. It lists the symptoms and gives **graded hints**.
   Read one hint at a time, and stop as soon as you have a theory you can test.
4. Fix it.
5. **Do not open `SOLUTION.md`.** It is the answer key.

Then come back and say **"I think I fixed the project"**. I will inspect your changes, build it, run
it, re-run the original reproduction steps, and tell you which defects are genuinely fixed, which
remain, and whether your reasoning about the root cause was right — including the cases where you
fixed a symptom rather than a cause.

You can also ask, at any point:

| Say this | And you get |
|---|---|
| `Give me a hint` | Exactly the next hint in the ladder, nothing more |
| `What is the bug?` | The specific defect named, without the fix |
| `Show me the solution` | The full entry from the answer key |

## Prerequisites

* **JDK 17 or newer** on the `PATH` (JDK 22 is what this was built and verified with)
* **Maven 3.8+**
* **Docker Desktop** — required from project 05 onwards. Projects 01–04 need nothing but Java.

Verify with:

```bash
java -version && mvn -version && docker info --format "{{.ServerVersion}}"
```

## Running a project

Every project is an independent Maven project. There is no parent POM — open one folder and build it.

```bash
cd 01-rest-api-debugging
mvn clean package
java -jar target/*.jar
```

Projects with a `docker-compose.yml` need their infrastructure up first:

```bash
cd 05-mysql-integration-debugging
docker compose up -d
mvn clean package && java -jar target/*.jar
```

**Run one project at a time.** Every application binds port `8080`. Shut a project's containers down
when you move on:

```bash
docker compose down        # add -v to also wipe the data volume and re-run the seed script
```

### Ports

Lab containers deliberately avoid the default ports so they can never collide with a database or
broker you already run locally:

| Service | Lab port |
|---|---|
| MySQL | 3307 |
| PostgreSQL | 5433 |
| Redis | 6380 |
| Kafka | 9094 |
| RabbitMQ | 5673 (management UI 15673) |

### Credentials

There are none. Every username, password and connection string in this lab is a throwaway
placeholder that matches the project's own `docker-compose.yml`. Each README has an **Environment
configuration** section naming exactly which values to replace if you want to point a project at
your own database or broker. Nothing here contacts an external service, and no API keys are used or
needed.

## The projects

| # | Project | Concepts | Infrastructure | Defects | Difficulty |
|---|---|---|---|---|---|
| 01 | REST API request/response | Spring MVC mapping, status codes, DTOs | none | 5 | Beginner |
| 02 | Dependency injection | component scan, qualifiers, scopes, `@Value` | none | 5 | Beginner |
| 03 | Exception handling | `@RestControllerAdvice`, error contracts | none | 5 | Beginner |
| 04 | JPA entities & repositories | mappings, derived queries, `save`/`merge` | none | 5 | Beginner→Int. |
| 05 | MySQL integration | `ddl-auto`, schema drift, connection pooling | MySQL | 5 | Intermediate |
| 06 | JPA relationships | `mappedBy`, cascades, orphan removal | MySQL | 6 | Intermediate |
| 07 | Lazy / eager loading | fetch types, `open-in-view`, N+1 | none | 6 | Intermediate |
| 08 | Transaction management | proxies, propagation, rollback rules | MySQL | 7 | Int.→Advanced |
| 09 | Security: authentication | filter chains, CSRF, password encoding | MySQL | 6 | Int.→Advanced |
| 10 | Security: authorization | roles vs authorities, matcher order | MySQL | 7 | Advanced |
| 11 | JWT authentication | claims, keys, expiry, filter ordering | MySQL | 9 | Advanced |
| 12 | Validation & DTOs | `@Valid`, nested constraints, mapping order | MySQL | 8 | Advanced |
| 13 | PostgreSQL + Hibernate | identifier case, sequences, enums | PostgreSQL | 7 | Advanced |
| 14 | Redis caching | cache abstraction, keys, serialisation, TTL | MySQL + Redis | 6 | Advanced |
| 15 | Redis cache invalidation | evict vs put, transactions, collection caches | MySQL + Redis | 7 | Advanced |
| 16 | Kafka producer/consumer | topics, groups, offset reset, serdes | Kafka | 7 | Advanced |
| 17 | Kafka offsets & processing | acknowledgement, retries, idempotency | Kafka | 7 | Adv.→Expert |
| 18 | RabbitMQ producer/consumer | exchanges, bindings, converters | RabbitMQ | 6 | Advanced |
| 19 | RabbitMQ ack & routing | manual ack, prefetch, dead-letter, wildcards | RabbitMQ | 7 | Expert |
| 20 | Combined | all of the above, interacting | MySQL + Redis + Kafka | 12 | Expert |

All twenty projects are built, and every one has been started, exercised and verified. There
are **133 planted problems** across the lab. `PROJECT_PLAN.md` describes the architecture of
each project in detail.

## A note on how to debug

The projects are built around one principle: **the symptom is usually not the root cause**. A `403`
that looks like an authentication problem turns out to be an authorization rule; an empty API
response turns out to be a table name; a slow endpoint turns out to be a transaction holding the only
connection in the pool.

So the habits worth building here are:

* Reproduce it reliably before you theorise.
* Read the *whole* stack trace, especially the last `Caused by:`.
* Trust the database, the broker and the logs over the API response.
* Change one thing at a time.
* When a fix makes something else break, that is information — not a reason to revert.
