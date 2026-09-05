# Product Inventory API

A stock-control service for a small hardware store: keep a product catalogue, search it, watch which
lines are running low, and adjust stock counts as goods arrive and leave.

## Purpose

The warehouse tablet and the purchasing dashboard both read from this API. The purchasing dashboard
in particular relies on the **low-stock report** to decide what to reorder, so that report has to be
trustworthy.

## Architecture

```
com.debuglab.inventory
├── InventoryApplication
├── controller/ProductController    HTTP layer
├── service/ProductService          catalogue rules, DTO mapping
├── repository/ProductRepository    derived queries + one JPQL query
├── entity/Product                  mapped to the `products` table
├── dto/
│   ├── ProductDto                  the wire representation
│   └── StockUpdateRequest          body of the stock adjustment call
└── exception/                      domain exceptions + @RestControllerAdvice
```

The entity is mapped onto a table whose column names do not all match the Java field names — the
`products` table predates the application and its column naming is fixed. `@Column` carries the
mapping.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* Hibernate 6.5
* H2 in-memory database
* Maven
* JUnit 5 + MockMvc

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+

### Database setup

Nothing to install. Hibernate creates the `products` table from the entity mapping at start-up and
`src/main/resources/data.sql` seeds six products.

Inspect the table while the application runs at <http://localhost:8080/h2-console>, JDBC URL
`jdbc:h2:mem:inventorydb`, user `sa`, blank password. `spring.jpa.show-sql=true` is on, so every
statement Hibernate issues is printed to the console — use it.

### Environment configuration

Everything lives in `src/main/resources/application.properties`. No environment variables, no
credentials. To point at a real database, replace `spring.datasource.url`, `.username` and
`.password` with your own values and change `spring.jpa.hibernate.ddl-auto` to `validate`.

## How to run

```bash
mvn spring-boot:run
```

or

```bash
mvn clean package
java -jar target/product-inventory-1.0.0.jar
```

```bash
mvn test
```

## API endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| `GET` | `/api/products` | Whole catalogue | `200` |
| `GET` | `/api/products/{id}` | One product | `200`, `404` if absent |
| `GET` | `/api/products/search?term=` | Products whose name **contains** the term, case-insensitively | `200` |
| `GET` | `/api/products/by-name?name=` | Products with exactly this name | `200` |
| `GET` | `/api/products/by-category?category=` | Products in a category, case-insensitively | `200` |
| `GET` | `/api/products/low-stock?threshold=10` | Products whose quantity is **at or below** the threshold, lowest first | `200` |
| `POST` | `/api/products` | Add a product | `201`, `409` if the SKU exists |
| `PATCH` | `/api/products/{id}/stock` | Set the stock count of one product | `200`, `404` if absent |

### Product representation

```json
{
  "id": 1,
  "sku": "KB-1001",
  "name": "Mechanical Keyboard",
  "category": "Peripherals",
  "price": 4499.00,
  "quantity": 42,
  "reorderLevel": 10,
  "lastRestockedAt": "2026-01-12"
}
```

* `sku` is unique and required.
* `reorderLevel` is the stock level at which purchasing should reorder this line. It is supplied when
  the product is created and is stored with the product.
* `lastRestockedAt` is set by the server when the product is created.

### Expected functionality

**Browse and search**

```bash
curl http://localhost:8080/api/products
curl http://localhost:8080/api/products/1

# search is case-insensitive: these two must return the same product
curl "http://localhost:8080/api/products/search?term=Mouse"
curl "http://localhost:8080/api/products/search?term=mouse"

curl "http://localhost:8080/api/products/by-name?name=Wireless%20Mouse"
curl "http://localhost:8080/api/products/by-category?category=peripherals"
```

**Low-stock report**

```bash
curl "http://localhost:8080/api/products/low-stock?threshold=10"
```

Returns every product whose `quantity` is less than or equal to `10`, ordered by quantity ascending —
the most urgent first. With the seeded data and a threshold of `10` that is four products:
`DK-5005` (0), `MN-3003` (3), `MS-2002` (6) and `WC-6006` (9).

**Create a product**

```bash
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"sku":"SP-7007","name":"USB-C Hub","category":"Peripherals","price":3199.00,"quantity":25,"reorderLevel":15}'
```

Responds `201` with the stored product. Fetching it again afterwards must return exactly the same
values, `reorderLevel` included.

**Adjust stock**

```bash
curl -X PATCH http://localhost:8080/api/products/1/stock \
  -H "Content-Type: application/json" \
  -d '{"quantity":55}'
```

This is a *partial* update: it sets `quantity` and touches nothing else. The product's SKU, name,
category, price, reorder level and restock date are unaffected.
