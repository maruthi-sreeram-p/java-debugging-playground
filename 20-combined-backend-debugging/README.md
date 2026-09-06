# E-Commerce Checkout Platform

The final project in the laboratory. A JWT-secured checkout API on top of MySQL, with a
Redis-cached catalogue and a Kafka event stream that drives fulfilment, customer notifications and
the live sales dashboard.

## Purpose

This is a single Spring Boot service that touches everything the earlier projects covered
separately, and — more importantly — the places where those pieces meet:

* a **transaction** that writes an order, reserves stock and publishes an **event**;
* a **cache** that is read on the way in and has to be invalidated on every write path;
* **authorisation** with two roles, on top of authentication that is stateless;
* three **consumers** with different jobs reading the same stream.

```
                    ┌──────────────── JWT (HS256) ────────────────┐
                    ▼                                             │
POST /api/auth/login ──▶ AuthController ──▶ AppUser (MySQL) ──────┘

GET  /api/catalogue        ──▶ CatalogueService ──▶ Redis "catalogue" ──▶ MySQL
GET  /api/catalogue/{sku}  ──▶ CatalogueService ──▶ Redis "products"  ──▶ MySQL

POST /api/checkout  ──▶ CheckoutService  (one transaction)
                          1. read the product
                          2. check the stock
                          3. save the order
                          4. reserve the stock
                          5. publish OrderPlacedEvent
                          6. authorise the card
                          7. screen for risk
                                    │
                              checkout.orders (Kafka, 1 partition)
                                    │
              ┌─────────────────────┼──────────────────────┐
              ▼                     ▼                      ▼
      InventoryConsumer    NotificationConsumer   OrderAnalyticsConsumer
      (fulfilment row)     (customer_notification)  (dashboard counters)

PUT  /api/admin/products/{sku}/price ──▶ AdminService ──▶ MySQL + cache invalidation
GET  /api/admin/report               ──▶ counts and revenue
```

## Architecture

```
com.debuglab.checkout
├── CheckoutApplication
├── config/
│   ├── SecurityConfig            filter chain, matchers, password encoder
│   ├── CacheConfig               Redis cache TTL and serialisation
│   └── KafkaTopicConfig          the orders topic
├── security/
│   ├── JwtService                issues and verifies HS256 tokens
│   └── JwtAuthenticationFilter   reads the bearer token into the SecurityContext
├── controller/
│   ├── AuthController            sign in
│   ├── CatalogueController       public catalogue reads
│   ├── OrderController           checkout, order lookup, fulfilment status
│   ├── AdminController           price and stock maintenance, reporting
│   └── ApiExceptionHandler       turns exceptions into responses
├── service/
│   ├── CatalogueService          cached reads
│   ├── CheckoutService           the checkout transaction
│   ├── AdminService              price and stock writes
│   ├── OrderEventPublisher       Kafka producer
│   ├── PaymentGateway            stands in for the payment provider
│   └── RiskScreeningService      the manual-approval threshold
├── consumer/
│   ├── InventoryConsumer         records fulfilment, marks the order FULFILLED
│   ├── NotificationConsumer      writes the customer notification
│   └── OrderAnalyticsConsumer    counts orders for the dashboard tile
├── repository/                   five Spring Data repositories
├── entity/   AppUser, Product, CustomerOrder, Fulfilment, CustomerNotification
├── event/    OrderPlacedEvent
├── dto/      requests, ProductView, OrderResponse
└── exception/ the business failures the API can report
```

### Business rules

| Rule | Behaviour |
|---|---|
| A customer may buy at most the available stock | `409 Conflict` when there is not enough |
| Quantity must be at least 1 | `400 Bad Request` |
| The card token comes from the payment widget and starts with `tok_` | `402 Payment Required` when the provider rejects it |
| Orders above ₹1,00,000 need manual approval by finance | `422 Unprocessable Entity`, and **no order is created** |
| A customer may read only their own orders | `403 Forbidden` otherwise |
| Only `ADMIN` may change prices or stock, or read the report | `403 Forbidden` otherwise |

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation, security, cache, data-redis)
* Spring Kafka
* JJWT 0.12.6 (HS256)
* MySQL 8, Redis 7, Apache Kafka 3.9 (KRaft) — all in Docker
* Maven, JUnit 5, MockMvc, Awaitility

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+
* Docker Desktop (or any Docker engine with Compose v2)

### Infrastructure

```bash
docker compose up -d
```

Three containers, all on non-default host ports so they cannot clash with anything you already run:

| Container | Port | Purpose |
|---|---|---|
| `debuglab20-mysql` | **3307** | MySQL 8 |
| `debuglab20-redis` | **6380** | Redis 7 |
| `debuglab20-kafka` | **9094** | Kafka (KRaft, single node) |

### Database setup

Nothing to do by hand. `init/01-schema.sql` creates the tables and `init/02-seed.sql` loads three
accounts, six products and six orders migrated from the previous platform. Both run automatically
the first time the MySQL container starts.

```bash
docker exec -it debuglab20-mysql mysql -ucheckoutuser -pcheckoutpw checkoutdb
```

```sql
SELECT * FROM product;
SELECT order_ref, customer_username, total_amount, status FROM customer_order;
```

To start again from scratch:

```bash
docker compose down -v && docker compose up -d
```

### Accounts

All three passwords are `Secret123!` — development credentials, seeded by `init/02-seed.sql`.

| Username | Role | Display name |
|---|---|---|
| `arjun` | `CUSTOMER` | Arjun Mehta |
| `meena` | `CUSTOMER` | Meena Iyer |
| `priya` | `ADMIN` | Priya Raghavan |

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/checkoutdb` | From `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `checkoutuser` / `checkoutpw` | **Placeholder credentials** — replace them here and in `docker-compose.yml` for your own database |
| `spring.data.redis.host` / `.port` | `localhost` / `6380` | |
| `spring.kafka.bootstrap-servers` | `localhost:9094` | |
| `security.jwt.secret` | base64, ≥ 32 bytes | **Placeholder key.** Generate your own with `openssl rand -base64 48` and put it here |
| `security.jwt.expiration-minutes` | `30` | How long a sign-in should last |
| `checkout.cache.ttl-minutes` | `10` | Redis entry lifetime |
| `checkout.risk.review-threshold` | `100000` | Above this, finance approves by hand |

No credential in this repository belongs to a real service. Replace them all before pointing the
application at anything of yours.

## How to run

```bash
docker compose up -d
```

```bash
mvn clean package
```

```bash
java -jar target/checkout-platform-1.0.0.jar
```

```bash
mvn test
```

`mvn test` needs all three containers running, and it places real orders — reset the database
afterwards if you want the seeded numbers back.

## API endpoints

| Method | Path | Role | Description | Success |
|---|---|---|---|---|
| `POST` | `/api/auth/login` | — | Exchange a username and password for a token | `200` |
| `GET` | `/api/catalogue` | — | The whole catalogue | `200` |
| `GET` | `/api/catalogue/{sku}` | — | One product | `200` |
| `POST` | `/api/checkout` | any | Place an order | `201` |
| `GET` | `/api/orders/mine` | any | Your own orders | `200` |
| `GET` | `/api/orders/{orderRef}` | owner | One order | `200` |
| `GET` | `/api/orders/{orderRef}/fulfilment` | any | What the warehouse did with it | `200` |
| `PUT` | `/api/admin/products/{sku}/price` | `ADMIN` | Change a price | `200` |
| `PUT` | `/api/admin/products/{sku}/stock` | `ADMIN` | Set the stock level | `200` |
| `GET` | `/api/admin/report` | `ADMIN` | Orders, revenue, fulfilments, notifications | `200` |

### Signing in

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"arjun","password":"Secret123!"}'
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "arjun",
  "displayName": "Arjun Mehta",
  "role": "CUSTOMER",
  "expiresInSeconds": 1800
}
```

A convenient shell helper:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"arjun","password":"Secret123!"}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
```

### Placing an order

```bash
curl -s -X POST http://localhost:8080/api/checkout \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sku":"SKU-1001","quantity":2,"cardToken":"tok_visa_4242"}'
```

```json
{
  "orderRef": "ORD-20260906-A1B2C3",
  "customer": "arjun",
  "sku": "SKU-1001",
  "quantity": 2,
  "amount": 4998.00,
  "status": "PLACED",
  "placedAt": "2026-09-06T07:50:04.35"
}
```

A second or two later the order becomes `FULFILLED`, a fulfilment record exists, and the customer
has been notified:

```bash
curl -s "http://localhost:8080/api/orders/ORD-20260906-A1B2C3/fulfilment" -H "Authorization: Bearer $TOKEN"
```

### The report

```bash
ADMIN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"priya","password":"Secret123!"}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s http://localhost:8080/api/admin/report -H "Authorization: Bearer $ADMIN"
```

```json
{
  "orders": 7,
  "revenue": 99382.00,
  "fulfilments": 1,
  "notifications": 1,
  "analyticsOrdersSeen": 1,
  "analyticsValueSeen": 4998.00
}
```

`revenue` covers **every** order in the database, including the six migrated ones, which are worth
₹94,384 between them.

## Inspecting the moving parts

```bash
docker exec debuglab20-mysql mysql -ucheckoutuser -pcheckoutpw checkoutdb -e "SELECT sku, price, stock FROM product;"
```

```bash
docker exec debuglab20-redis redis-cli KEYS '*'
```

```bash
docker exec debuglab20-redis redis-cli GET 'products::SKU-1001'
```

```bash
MSYS_NO_PATHCONV=1 docker exec debuglab20-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
```

```bash
MSYS_NO_PATHCONV=1 docker exec debuglab20-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic checkout.orders --from-beginning --timeout-ms 5000
```

(`MSYS_NO_PATHCONV=1` is only needed in Git Bash on Windows, which otherwise rewrites the container
paths.)

## Expected functionality

* A customer signs in once and the token works for the full advertised lifetime.
* An `ADMIN` can reach `/api/admin/**`; a `CUSTOMER` cannot, and a customer cannot read another
  customer's order.
* Business failures come back with the status codes in the table above — not as server errors.
* A checkout that fails leaves nothing behind: no order, no stock movement, no event, no fulfilment.
* A checkout that succeeds is priced at the price the catalogue is showing at that moment.
* Stock can never go below zero, and what the API reports is what the database holds.
* A price or stock change made through the admin API is visible on the next catalogue read.
* Every order event reaches all three consumers: fulfilment, notification and analytics.
* `/api/admin/report` accounts for every order in the database, migrated ones included.
