# Java Backend Debugging Laboratory — Project Plan & Architecture

This document is the blueprint for the lab. It describes **what each project is, what it is
supposed to do, and how it is built**. It deliberately says nothing about where defects live.

---

## 1. Global technical decisions

| Decision | Value | Reason |
|---|---|---|
| Java language level | 17 | LTS baseline; compiles cleanly on the installed JDK 22 |
| Spring Boot | 3.3.5 | Current 3.x line, Jakarta EE 10, Hibernate 6.5 |
| Build tool | Maven (system `mvn`) | Matches the requested stack |
| Lombok | **not used** | Explicit getters/setters keep every field visible while reading unfamiliar code — which is the skill being trained |
| Object mapping | Hand-written mappers / constructors | No MapStruct magic hiding behaviour |
| Test framework | JUnit 5 + Spring Boot Test + MockMvc | Only where a test genuinely adds value |
| Infrastructure | Docker Compose per project | Each project is independently runnable |

**Each project is a standalone Maven project.** There is no parent POM, no shared module and no
multi-module reactor. You can open, build and run any single folder on its own.

### Datastore / broker allocation

| Infrastructure | Used by |
|---|---|
| H2 (in-memory, no Docker needed) | 01, 02, 03, 04, 07, 16, 17, 18, 19 |
| MySQL 8 (Docker) | 05, 06, 08, 09, 10, 11, 12, 14, 15, 20 |
| PostgreSQL 16 (Docker) | 13 |
| Redis 7 (Docker) | 14, 15, 20 |
| Apache Kafka (Docker, KRaft mode) | 16, 17, 20 |
| RabbitMQ 3 (Docker, management UI) | 18, 19 |

**Ports are deliberately non-standard.** This machine already runs a local MySQL 8.0 on 3306, so
every lab container is published one port up from the default to guarantee it can never collide with
a service you already have installed:

| Service | Default port | Port used by this lab |
|---|---|---|
| MySQL | 3306 | **3307** |
| PostgreSQL | 5432 | **5433** |
| Redis | 6379 | **6380** |
| Kafka | 9092 | **9094** |
| RabbitMQ | 5672 | **5673** (management UI on **15673**) |

Each project's `docker-compose.yml` and `application.properties` already agree on these. **Run one
project at a time** — every application listens on `8080`, and every compose stack is named after
its project so `docker compose down` in the project directory shuts down exactly that project.

### Credentials

No real credentials are used anywhere. Passwords and connection strings are placeholders defined in
each project's `application.properties` and `docker-compose.yml`, and every README has an
**Environment configuration** section telling you exactly which values to change if you point a
project at your own database or broker. Nothing in this lab contacts an external service.

---

## 2. Standard project layout

Every project follows the same shape, so that after project 01 you always know where to look:

```
NN-topic-debugging/
├── pom.xml
├── docker-compose.yml            (only when external infrastructure is required)
├── README.md                     normal project documentation
├── DEBUGGING_GUIDE.md            symptoms + progressive hints (no answers)
├── SOLUTION.md                   sealed answer key — do not read until you have finished
└── src/
    ├── main/
    │   ├── java/com/debuglab/<module>/
    │   │   ├── <App>Application.java
    │   │   ├── config/
    │   │   ├── controller/
    │   │   ├── service/
    │   │   ├── repository/
    │   │   ├── entity/
    │   │   ├── dto/
    │   │   └── exception/
    │   └── resources/
    │       ├── application.properties
    │       └── data.sql / schema.sql   (where seeding is useful)
    └── test/java/com/debuglab/<module>/
```

---

## 3. The twenty projects

> **Difficulty** is cumulative: later projects assume you have already practised the earlier skills.
> **Defect count** is the number of independent problems planted in the project. Some are
> independent, some are sequential (one hides another), and in the later projects some only appear
> as a *combination* of two otherwise harmless decisions.

### Level 1 — Foundations (read a stack trace, trace one request)

#### 01 — REST API Request/Response Debugging
* **Domain:** Student Registry API
* **Store:** H2 in-memory, seeded from `data.sql`
* **Architecture:** `StudentController` → `StudentService` → `StudentRepository` → `Student` entity, with a `StudentDto` on the wire.
* **Endpoints:** `GET /api/students`, `GET /api/students/{id}`, `GET /api/students/search`, `POST /api/students`, `PUT /api/students/{id}`, `DELETE /api/students/{id}`
* **Concepts:** request mapping, path variables, request parameters, HTTP verbs, status codes, JSON serialisation, DTO boundaries
* **Defects:** 5
* **Difficulty:** Beginner

#### 02 — Spring Dependency Injection Debugging
* **Domain:** Notification Dispatch Service (email / SMS channels)
* **Store:** H2 in-memory
* **Architecture:** `NotificationController` → `NotificationService` → multiple `NotificationChannel` implementations; an `AuditService`, a `RetryPolicy` component and a `@Configuration` class that wires them.
* **Endpoints:** `POST /api/notifications`, `GET /api/notifications`, `GET /api/notifications/stats`
* **Concepts:** component scanning, constructor vs field injection, `@Qualifier` / `@Primary`, bean scopes, property placeholders, bean lifecycle
* **Defects:** 5
* **Difficulty:** Beginner — but the application does not start until you fix the first one.

#### 03 — Exception Handling Debugging
* **Domain:** Library Catalogue API (books, borrowing)
* **Store:** H2 in-memory
* **Architecture:** `BookController` / `BorrowController` → services → repositories, plus a `GlobalExceptionHandler`, a small hierarchy of domain exceptions and an `ApiError` response model.
* **Endpoints:** `GET/POST /api/books`, `GET /api/books/{id}`, `POST /api/borrow`, `POST /api/return/{id}`
* **Concepts:** `@RestControllerAdvice`, `@ExceptionHandler`, exception-to-status mapping, error response contracts, exception swallowing, root-cause preservation
* **Defects:** 5
* **Difficulty:** Beginner

#### 04 — JPA Entity & Repository Debugging
* **Domain:** Product Inventory API
* **Store:** H2 in-memory, seeded
* **Architecture:** `ProductController` → `ProductService` → `ProductRepository` (derived queries, JPQL `@Query`, one native query) → `Product` entity.
* **Endpoints:** `GET /api/products`, `GET /api/products/{id}`, `GET /api/products/search`, `GET /api/products/low-stock`, `POST /api/products`, `PATCH /api/products/{id}/stock`
* **Concepts:** entity mapping, column mapping, derived query naming, JPQL parameters, native queries, persistence of computed/derived fields
* **Defects:** 5
* **Difficulty:** Beginner→Intermediate

#### 05 — MySQL Integration Debugging
* **Domain:** Employee Directory API
* **Store:** MySQL 8 (Docker), plus a seed script executed by the container
* **Architecture:** Standard three-layer app with explicit datasource/JPA configuration and two Spring profiles (`default` and `docker`), so that configuration differences between environments are part of the exercise.
* **Endpoints:** `GET /api/employees`, `GET /api/employees/{id}`, `GET /api/employees/by-department`, `POST /api/employees`, `PUT /api/employees/{id}`
* **Concepts:** datasource URL and driver configuration, `ddl-auto` semantics, physical naming strategy, schema/entity drift, connection pooling, time zones, profile-specific configuration
* **Defects:** 5
* **Difficulty:** Intermediate — you will need to open a MySQL shell and compare what the database holds against what the API returns.

---

### Level 2 — Persistence and security mechanics

#### 06 — JPA Relationships Debugging
* **Domain:** Order Service (Customer → Order → OrderItem, Order ↔ Coupon)
* **Store:** MySQL 8 (Docker)
* **Architecture:** Three related entities with one-to-many, many-to-one and one-to-one associations; a service that composes an order in a single call; DTOs for the API surface.
* **Endpoints:** `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/customers/{id}/orders`, `DELETE /api/orders/{id}/items/{itemId}`
* **Concepts:** `mappedBy` and association ownership, cascade types, orphan removal, join columns, bidirectional consistency, JSON serialisation of graphs
* **Defects:** 6
* **Difficulty:** Intermediate

#### 07 — Hibernate Lazy / Eager Loading Debugging
* **Domain:** Course Enrolment API (Course → Module → Lesson, Student ↔ Course)
* **Store:** H2 (SQL logging switched on so you can count queries)
* **Architecture:** Deep object graph; several read endpoints implemented in different styles — one entity-returning, one DTO-projecting, one using an explicit fetch join — plus a report component that runs outside a web request.
* **Endpoints:** `GET /api/courses`, `GET /api/courses/{id}`, `GET /api/courses/{id}/summary`, `GET /api/students/{id}/transcript`, `GET /api/reports/catalogue`
* **Concepts:** `FetchType.LAZY` / `EAGER`, persistence-context lifetime, `open-in-view`, N+1 selects, fetch joins with pagination, DTO projections
* **Defects:** 6
* **Difficulty:** Intermediate — some failures occur only on certain endpoints or only under a certain profile.

#### 08 — Transaction Management Debugging
* **Domain:** Banking Transaction API (accounts, transfers, ledger)
* **Store:** MySQL 8 (Docker)
* **Architecture:** `TransferController` → `TransferService` (the transactional boundary) → `AccountService`, `LedgerService`, `FeeService`; an audit component that must survive rollback.
* **Endpoints:** `POST /api/transfers`, `POST /api/transfers/batch`, `GET /api/accounts/{id}`, `GET /api/accounts/{id}/ledger`
* **Concepts:** `@Transactional` proxying and self-invocation, rollback rules for checked vs unchecked exceptions, propagation levels, `readOnly`, atomicity, lost updates under concurrency
* **Defects:** 7
* **Difficulty:** Intermediate→Advanced — the headline symptom is money that half-moves.

#### 09 — Spring Security Authentication Debugging
* **Domain:** Account Management / Authentication Service
* **Store:** MySQL 8 (Docker)
* **Architecture:** `AuthController` (register / login / me) → `AccountService` → `AppUser` entity; a `SecurityConfig` defining the filter chain, a `UserDetailsService` implementation and a `PasswordEncoder` bean.
* **Endpoints:** `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me`, `GET /api/public/health`
* **Concepts:** filter chain construction, matcher ordering, CSRF and stateless APIs, password encoding, `UserDetails` account flags, authentication providers
* **Defects:** 6
* **Difficulty:** Intermediate→Advanced

#### 10 — Spring Security Authorization / Roles Debugging
* **Domain:** Task Management API with `USER`, `MANAGER` and `ADMIN` capabilities
* **Store:** MySQL 8 (Docker), users and roles seeded
* **Architecture:** URL-based rules in `SecurityConfig` plus method-level rules on the service layer; a `Role` entity associated to users.
* **Endpoints:** `GET /api/tasks`, `POST /api/tasks`, `PUT /api/tasks/{id}/assign`, `DELETE /api/tasks/{id}`, `GET /api/admin/users`, `GET /api/admin/audit`
* **Concepts:** authorities vs roles, the `ROLE_` prefix convention, matcher precedence, method security activation, granted-authority construction, per-user differences
* **Defects:** 7
* **Difficulty:** Advanced — expect endpoints that are too open as well as endpoints that are wrongly closed.

---

### Level 3 — Advanced application concerns

#### 11 — JWT Authentication Debugging
* **Domain:** Stateless API for a Support Ticket service
* **Store:** MySQL 8 (Docker)
* **Architecture:** `JwtService` (issue / parse / validate), `JwtAuthenticationFilter`, `SecurityConfig`, `AuthController`, plus protected ticket endpoints at two privilege levels.
* **Endpoints:** `POST /api/auth/login`, `POST /api/auth/refresh`, `GET /api/tickets`, `POST /api/tickets`, `GET /api/admin/tickets`
* **Concepts:** token issuance and claims, signing keys and key material, expiry arithmetic, filter registration and ordering, populating the `SecurityContext`, stateless session policy
* **Defects:** 9
* **Difficulty:** Advanced — several defects mask each other; the order in which you fix them matters.

#### 12 — REST Validation & DTO Debugging
* **Domain:** Customer Onboarding API (registration, address, preferences)
* **Store:** MySQL 8 (Docker)
* **Architecture:** Request DTOs with Bean Validation constraints, a custom constraint annotation and validator, nested DTOs, hand-written mappers, and a validation error handler.
* **Endpoints:** `POST /api/customers`, `PUT /api/customers/{id}`, `POST /api/customers/{id}/address`, `GET /api/customers/{id}`
* **Concepts:** `@Valid` activation, constraint applicability to primitives and nested objects, custom validators, validation vs mapping order, error response shaping
* **Defects:** 8
* **Difficulty:** Advanced — some invalid data is rejected, some is accepted, and some is accepted and then stored in the wrong place.

#### 13 — PostgreSQL + Hibernate Debugging
* **Domain:** Asset Tracking API (assets, categories, status history)
* **Store:** PostgreSQL 16 (Docker) with an explicit `init.sql` that creates the schema the application expects to find
* **Architecture:** Entities mapped against a pre-existing, hand-written schema; `ddl-auto` set to a non-creating mode; a mixture of JPQL and native SQL; an enum-valued column and a numeric money column.
* **Endpoints:** `GET /api/assets`, `POST /api/assets`, `GET /api/assets/{id}/history`, `GET /api/assets/report`, `PATCH /api/assets/{id}/status`
* **Concepts:** PostgreSQL identifier case rules, identity vs sequence generation, sequence synchronisation after seeding, enum persistence strategies, numeric type mapping, dialect-specific SQL
* **Defects:** 7
* **Difficulty:** Advanced — one of the failures only appears after the application has been used for a while.

#### 14 — Redis Caching Debugging
* **Domain:** Product Catalogue read API in front of MySQL
* **Store:** MySQL 8 + Redis 7 (Docker)
* **Architecture:** `CacheConfig` defining the cache manager, serialisation and TTLs; a service layer annotated with `@Cacheable`; a timing aspect that logs how long each call took so you can see whether a cache is doing anything.
* **Endpoints:** `GET /api/catalogue/{id}`, `GET /api/catalogue/search`, `GET /api/catalogue/by-category`, `GET /api/catalogue/stats`
* **Concepts:** cache abstraction activation, proxy-based interception, key generation, value serialisation, time-to-live configuration, caching of empty results
* **Defects:** 6
* **Difficulty:** Advanced — you will need `redis-cli` to see what is actually stored.

#### 15 — Redis Cache Invalidation Debugging
* **Domain:** Pricing & Inventory service — the write side of the same catalogue
* **Store:** MySQL 8 + Redis 7 (Docker)
* **Architecture:** Read endpoints backed by `@Cacheable`, write endpoints annotated with `@CacheEvict` / `@CachePut`, some writes inside a transaction, plus a bulk-import path.
* **Endpoints:** `GET /api/pricing/{sku}`, `GET /api/pricing`, `PUT /api/pricing/{sku}`, `POST /api/pricing/bulk`, `DELETE /api/pricing/{sku}`
* **Concepts:** cache names and key expressions, `@CacheEvict` vs `@CachePut`, `allEntries`, eviction relative to transaction commit, collection caches versus single-entity caches
* **Defects:** 7
* **Difficulty:** Advanced — the database is always right; the API sometimes is not.

---

### Level 4 — Distributed systems

#### 16 — Kafka Producer / Consumer Debugging
* **Domain:** Order Event Pipeline — an order API that emits events and a consumer that materialises them
* **Store:** H2 + Kafka (Docker, KRaft, single broker)
* **Architecture:** `OrderEventProducer`, `OrderEventConsumer`, a `KafkaConfig` with explicit producer/consumer factories, a JSON event payload class and a projection table written by the consumer.
* **Endpoints:** `POST /api/orders`, `GET /api/orders`, `GET /api/projections/orders`, `GET /api/health/kafka`
* **Concepts:** topic naming and configuration, consumer groups, offset reset policy, key/value serialisers and deserialisers, JSON type mapping and trusted packages, listener method signatures
* **Defects:** 7
* **Difficulty:** Advanced — the hardest part is proving where the message actually went. `kafka-console-consumer` is your friend.

#### 17 — Kafka Offset & Message Processing Debugging
* **Domain:** Payment Settlement Consumer
* **Store:** H2 + Kafka (Docker)
* **Architecture:** A multi-partition topic, a listener container with configured concurrency, an acknowledgement mode, an error handler, retry/back-off configuration and a dead-letter path; a processed-message table used for idempotency.
* **Endpoints:** `POST /api/payments/publish`, `GET /api/settlements`, `GET /api/settlements/duplicates`, `POST /api/admin/replay`
* **Concepts:** offset commit strategies, manual acknowledgement, container concurrency versus partition count, error handlers and retry loops, poison messages, redelivery and idempotency
* **Defects:** 7
* **Difficulty:** Advanced→Expert — symptoms include duplicates, stalls and messages that reappear after a restart.

#### 18 — RabbitMQ Producer / Consumer Debugging
* **Domain:** Shipment Notification Service
* **Store:** H2 + RabbitMQ 3 (Docker, management plugin on 15672)
* **Architecture:** A `RabbitConfig` declaring exchanges, queues and bindings; a producer publishing several event types; listeners for each event type; a message converter shared (in principle) by both sides.
* **Endpoints:** `POST /api/shipments`, `POST /api/shipments/{id}/dispatch`, `GET /api/shipments`, `GET /api/notifications/received`
* **Concepts:** exchange types, routing keys and bindings, queue declaration and durability, message converters, listener registration, publisher confirms
* **Defects:** 6
* **Difficulty:** Advanced — the management UI will show you queues; it will not tell you why they are empty.

#### 19 — RabbitMQ Acknowledgement & Routing Debugging
* **Domain:** Invoice Processing Pipeline with a dead-letter path
* **Store:** H2 + RabbitMQ 3 (Docker)
* **Architecture:** A topic exchange with several wildcard bindings, manual acknowledgement mode, a dead-letter exchange and queue, a retry-count header, and two listeners competing on related queues.
* **Endpoints:** `POST /api/invoices`, `POST /api/invoices/{id}/submit`, `GET /api/invoices/processed`, `GET /api/invoices/dead-letter`, `GET /api/queues/stats`
* **Concepts:** manual `ack` / `nack` / `reject`, prefetch and unacknowledged-message limits, requeue loops, dead-letter exchanges and routing keys, topic wildcards (`*` vs `#`), competing consumers
* **Defects:** 7
* **Difficulty:** Expert — the pipeline processes some messages, stalls on others, and loses a third group entirely.

#### 20 — Combined Spring Boot + Security + Database + Messaging + Redis Debugging
* **Domain:** E-Commerce Checkout Platform
* **Store:** MySQL 8 + Redis 7 + Kafka (Docker) — full stack
* **Architecture:** JWT-secured REST API → transactional checkout service writing orders and reserving stock in MySQL → Redis-cached product catalogue → Kafka event published on checkout → an inventory consumer and a notification consumer that read back from the database → an admin reporting endpoint.
* **Endpoints:** `POST /api/auth/login`, `GET /api/catalogue`, `POST /api/checkout`, `GET /api/orders/{id}`, `GET /api/orders/{id}/fulfilment`, `GET /api/admin/report`
* **Concepts:** everything above, plus the interaction between transaction boundaries and event publication, cache coherence across write paths, and authorisation across two roles
* **Defects:** 12, several of which only manifest as a combination
* **Difficulty:** Expert — this is a full afternoon (or two).

---

## 4. How each project is validated before being handed to you

For every project the following is checked:

1. `mvn -q clean test-compile` completes — the code compiles, so nothing is a mere syntax error.
2. The application (or its required infrastructure) starts, except where a start-up failure is itself
   the exercise, which the guide will hint at.
3. The documented symptoms are reproduced by actually calling the API / publishing messages.
4. Every defect is one a junior developer could plausibly write — no contrived code, no giveaways.
5. `README.md` describes the project as if it were healthy, and its setup instructions work.
6. `DEBUGGING_GUIDE.md` describes symptoms and graded hints without naming a file, line or fix.
7. `SOLUTION.md` contains the complete answer key.

## 5. Working agreement

* `SOLUTION.md` exists in every project folder. **Do not open it.** It is there so that you can
  verify yourself afterwards, and so that I can check your diagnosis without re-deriving it.
* When you want help, say: `Give me a hint` (you get exactly the next hint),
  `What is the bug?` (I name the defect), or `Show me the solution` (I reveal the whole entry).
* When you say `I think I fixed the project`, I will inspect your changes, build it, run it, re-run
  the original reproduction steps, and tell you which defects are genuinely fixed, which remain, and
  whether your reasoning about the root cause was right — including when you fixed a symptom rather
  than a cause.
