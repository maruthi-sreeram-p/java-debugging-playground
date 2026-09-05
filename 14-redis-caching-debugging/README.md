# Product Catalogue Read API

The read side of the product catalogue. It serves product pages, category listings and search to the
storefront, and it is backed by Redis so that the same query does not hit MySQL twice.

## Purpose

The catalogue is read constantly and changes rarely — the ideal shape for a cache. Every read path in
this service is cached in Redis with a ten-minute time to live, so:

* The **first** request for a given piece of data reads MySQL.
* Every request after it, for ten minutes, is served from Redis without touching MySQL.
* Different queries get different cache entries. One query never sees another query's answer.

## Architecture

```
com.debuglab.catalogue
├── CatalogueApplication
├── config/CacheConfig               Redis cache manager: serialisation and TTL
├── controller/CatalogueController   HTTP layer, logs how long each request took
├── service/CatalogueService         the cached read methods
├── repository/ProductRepository
├── entity/Product                   -> the `products` table
└── dto/ProductDto                   what gets returned, and what gets cached
```

Each cached method logs a line beginning `DATABASE READ` **inside its body**. Because a cache hit
skips the method body entirely, that log line is a direct signal:

> `DATABASE READ` in the log means the cache was missed.

Watch for it. It is the main instrument for this project.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation, **data-redis**, **cache**)
* Spring Cache abstraction over Redis
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

Two containers:

| Service | Host port | Container |
|---|---|---|
| MySQL 8 | **3307** | `debuglab14-mysql` |
| Redis 7 | **6380** | `debuglab14-redis` |

Both are one port up from the default so they cannot clash with servers you already run.

The application creates the `products` table at start-up and `data.sql` seeds ten products across
four categories (`Peripherals`, `Displays`, `Cables`, `Networking`).

**Talking to Redis** — you will do this constantly:

```bash
docker exec -it debuglab14-redis redis-cli
```

```
DBSIZE                      how many keys exist
KEYS *                      list them (fine on a cache this small)
GET product::1              read one entry
TTL product::1              how many seconds until it expires
FLUSHALL                    empty the cache
MONITOR                     watch every command in real time
```

`MONITOR` in a second terminal while you make requests is the fastest way to see what the cache
abstraction is actually doing.

Reset everything:

```bash
docker compose down -v && docker compose up -d
```

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/cataloguedb?...` | Host port from `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `catalogueuser` / `cataloguepw` | **Placeholder credentials** |
| `spring.data.redis.host` / `.port` | `localhost` / `6380` | Redis, from `docker-compose.yml` |
| `spring.cache.type` | `redis` | Use Redis rather than an in-process cache |
| `catalogue.cache.ttl-minutes` | `10` | How long a cached entry may be served |

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/product-catalogue-1.0.0.jar
```

```bash
mvn test          # requires both containers to be up
```

## API endpoints

| Method | Path | Description | Cached under |
|---|---|---|---|
| `GET` | `/api/catalogue/{id}` | One product | `product` |
| `GET` | `/api/catalogue/search?term=&category=` | Products in a category whose name contains the term | `catalogueSearch` |
| `GET` | `/api/catalogue/by-category?category=` | Everything in a category | `categoryListing` |
| `GET` | `/api/catalogue/stats` | Product counts per category | — (built from the cached category listings) |

### Examples

```bash
# a product; the second call within ten minutes must not touch MySQL
curl http://localhost:8080/api/catalogue/1
curl http://localhost:8080/api/catalogue/1

# a product that does not exist
curl -i http://localhost:8080/api/catalogue/9999          # 404

# search, scoped to a category
curl "http://localhost:8080/api/catalogue/search?term=Monitor&category=Displays"   # 2 products
curl "http://localhost:8080/api/catalogue/search?term=Monitor&category=Cables"     # none

# a whole category
curl "http://localhost:8080/api/catalogue/by-category?category=Peripherals"

# counts per category, assembled from the category listings
curl http://localhost:8080/api/catalogue/stats
```

```json
{ "categories": { "Cables": 2, "Displays": 2, "Networking": 2, "Peripherals": 4 },
  "totalProducts": 10 }
```

`/stats` is built by asking for each category listing in turn, so once the listings are warm it
should cost nothing at all.

## Expected functionality

* The first request for a given key logs `DATABASE READ`. Repeat requests within the TTL do not.
* `redis-cli DBSIZE` grows as you exercise the endpoints, and `TTL` on any entry reports close to
  **600 seconds**.
* Two different searches return two different answers and occupy two different cache entries.
* A product that does not exist returns `404`, consistently, however many times you ask.
* Requesting the same product ten times in a row returns the same product ten times.
* Calling `/stats` twice reads the database only on the first call.
