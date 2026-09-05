# Pricing & Inventory Service

The **write** side of the product catalogue. Prices and stock levels are changed here, and the same
Redis cache that makes reads fast has to be kept honest when they change.

## Purpose

Project 14 cached a read-only catalogue, where the only question was "is this cached?". Here the data
changes, which raises the harder question:

> When something is written, **which cached copies of it are now wrong**, and who is responsible for
> removing them?

The rule this service is built on is simple to state and easy to get wrong:

**Every write must invalidate every cached view of the data it changed.** A price entry appears in
three caches — on its own, inside the full list, and inside the aggregate statistics — so changing
one price has to reach all three.

## Architecture

```
com.debuglab.pricing
├── PricingApplication               @EnableCaching
├── config/CacheConfig               Redis cache manager, serialisation, one-hour TTL
├── controller/PricingController
├── service/PricingService           the cached reads and the annotated writes
├── repository/PriceEntryRepository
├── entity/PriceEntry                -> the `price_entries` table
├── dto/                             PriceDto and the request shapes
└── exception/
```

### The three caches

| Cache | Holds | Keyed by | Invalidated by |
|---|---|---|---|
| `price` | one SKU's price entry | the SKU | any write touching that SKU |
| `priceList` | the whole list | a single fixed key | **any** write at all |
| `priceStats` | SKU count, total list price, total units | a single fixed key | **any** write at all |

Cached reads log `DATABASE READ` inside the method body, so that line appearing means the cache was
missed. The TTL is one hour, which is far too long to rely on expiry to fix a stale entry — that is
deliberate. Correctness here has to come from invalidation, not from waiting.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation, data-redis, cache)
* Spring Cache abstraction over Redis — `@Cacheable`, `@CacheEvict`, `@CachePut`
* Hibernate 6.5, MySQL 8.0, Redis 7 (both Docker)
* Maven, JUnit 5, MockMvc

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+
* Docker Desktop (or any Docker engine with Compose v2)

### Infrastructure

```bash
docker compose up -d
```

| Service | Host port | Container |
|---|---|---|
| MySQL 8 | **3307** | `debuglab15-mysql` |
| Redis 7 | **6380** | `debuglab15-redis` |

`data.sql` seeds six SKUs across four categories.

You will need all three views open constantly — the API, the database and the cache:

```bash
docker exec -it debuglab15-redis redis-cli
docker exec -it debuglab15-mysql mysql -uroot -prootpw pricingdb
```

```
KEYS *                        which cache entries exist
GET "price::KB-1001"          what is actually stored
TTL "price::KB-1001"
FLUSHALL                      start from cold
MONITOR                       watch every command as it happens
```

**The database is always right.** When the API and the table disagree, the cache is at fault.

Reset everything:

```bash
docker compose down -v && docker compose up -d
```

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/pricingdb?...` | Host port from `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `pricinguser` / `pricingpw` | **Placeholder credentials** |
| `spring.data.redis.host` / `.port` | `localhost` / `6380` | Redis, from `docker-compose.yml` |
| `spring.cache.type` | `redis` | |

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/pricing-service-1.0.0.jar
```

```bash
mvn test          # requires both containers to be up
```

## API endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/pricing/{sku}` | One price entry (cached) |
| `GET` | `/api/pricing` | Every price entry (cached) |
| `GET` | `/api/pricing/stats` | SKU count, total list price, total units (cached) |
| `PUT` | `/api/pricing/{sku}` | Set a price |
| `PATCH` | `/api/pricing/{sku}/stock` | Set a stock level |
| `PATCH` | `/api/pricing/{sku}/name?name=` | Rename a SKU |
| `POST` | `/api/pricing/bulk` | Set many prices at once |
| `DELETE` | `/api/pricing/{sku}` | Remove a SKU |

### Examples

```bash
curl http://localhost:8080/api/pricing/KB-1001
curl http://localhost:8080/api/pricing
curl http://localhost:8080/api/pricing/stats

curl -i -X PUT http://localhost:8080/api/pricing/KB-1001 \
  -H "Content-Type: application/json" -d '{"price":5999.00}'

curl -i -X PATCH http://localhost:8080/api/pricing/KB-1001/stock \
  -H "Content-Type: application/json" -d '{"stock":500}'

curl -i -X PATCH "http://localhost:8080/api/pricing/MN-3003/name?name=Monitor%2027%20inch"

curl -i -X POST http://localhost:8080/api/pricing/bulk \
  -H "Content-Type: application/json" \
  -d '{"prices":{"NW-7001":20000.00,"HD-4004":399.00}}'

curl -i -X DELETE http://localhost:8080/api/pricing/WC-6006
```

## Expected functionality

* A price set with `PUT` is returned by the very next `GET` — of that SKU, of the list, and reflected
  in the statistics.
* The same holds for a stock change, a rename, a bulk update and a delete.
* A deleted SKU returns `404` on the next read.
* At no point does the API return something the `price_entries` table does not say.
* All of this holds **immediately**, not after the one-hour TTL expires.
