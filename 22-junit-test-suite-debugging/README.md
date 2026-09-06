# Loyalty Points Service

A customer loyalty scheme: customers earn points when they spend, redeem points against future
purchases, and move up a tier as their balance grows.

## Purpose

The service is small on purpose. What matters here is that it arrives with a **test suite**, and the
test suite is the part you are meant to read most carefully.

A passing test is a claim: "this behaviour is correct, and it will stay correct". A suite is only
worth what its weakest test is worth, and a test can be green for reasons that have nothing to do
with the code being right — it can assert nothing, assert something that is true by construction,
never run at all, or run in conditions the application will never see.

```
POST /api/loyalty/{customerId}/earn     spend money, gain points
POST /api/loyalty/{customerId}/redeem   spend points
GET  /api/loyalty/{customerId}          one account
GET  /api/loyalty                       every account
```

## Business rules

| Rule | Detail |
|---|---|
| Earning | 1 point for every ₹100 spent, **rounded down**. ₹150 earns 1 point; ₹199 earns 1; ₹200 earns 2 |
| Tiers | `BRONZE` below 1,000 points; `SILVER` from **1,000** to 4,999; `GOLD` from **5,000** |
| Redeeming | A customer may redeem up to their balance. More than that is rejected with `409`, and nothing changes |
| Balances | A balance can never be negative |
| Unknown customer | `404`, on every endpoint that names one |

The tier boundaries are inclusive at the bottom: exactly 1,000 points is `SILVER`, and exactly 5,000
is `GOLD`.

## Architecture

```
com.debuglab.loyalty
├── LoyaltyApplication
├── controller/LoyaltyController         the four endpoints
├── service/LoyaltyService               earning, redeeming, tier calculation
├── repository/LoyaltyAccountRepository  Spring Data JPA
├── entity/LoyaltyAccount
├── dto/  EarnRequest, RedeemRequest
└── exception/  AccountNotFoundException (404), InsufficientPointsException (409)
```

```
src/test/java/com/debuglab/loyalty
├── LoyaltyAccountTest             end-to-end through MockMvc against the real database
└── LoyaltyPointsCalculationTest   the points calculation in isolation, with a mocked repository
```

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* H2 in-memory database
* Maven, JUnit 5, MockMvc, Mockito

No Docker and no external services.

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+

Nothing to install. The database is in memory and is rebuilt on every start from
`src/main/resources/data.sql`, which seeds three accounts:

| Customer | Points | Tier |
|---|---|---|
| `cust-1001` | 250 | `BRONZE` |
| `cust-1002` | 995 | `BRONZE` |
| `cust-1003` | 4,990 | `SILVER` |

Those numbers are chosen to sit just under the tier boundaries, so that a single small purchase
crosses one.

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `server.port` | `8080` | |
| `spring.datasource.url` | `jdbc:h2:mem:loyaltydb` | In-memory, no credentials of consequence |
| `spring.jpa.open-in-view` | `false` | The persistence context is closed when the service method returns, as it would be in production |

No credentials and no API keys.

## How to run

```bash
mvn clean package
```

```bash
java -jar target/loyalty-points-1.0.0.jar
```

```bash
mvn test
```

To run a single test class, or a single test:

```bash
mvn test -Dtest=LoyaltyAccountTest
```

```bash
mvn test -Dtest=LoyaltyAccountTest#redeemingPointsReducesTheBalance
```

Test reports are written to `target/surefire-reports/`. The `.txt` file per class is the quickest
read; it lists every test that ran and what it did.

## API endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| `GET` | `/api/loyalty` | Every account | `200` |
| `GET` | `/api/loyalty/{customerId}` | One account | `200`, or `404` if unknown |
| `POST` | `/api/loyalty/{customerId}/earn` | Convert a spend into points | `200` |
| `POST` | `/api/loyalty/{customerId}/redeem` | Spend points | `200`, or `409` if the balance is too low |

### Earning

```bash
curl -s -X POST http://localhost:8080/api/loyalty/cust-1001/earn \
  -H "Content-Type: application/json" -d '{"amount":1000.00}'
```

```json
{ "id": 1, "customerId": "cust-1001", "points": 260, "tier": "BRONZE",
  "updatedAt": "2026-09-06T08:26:23.13" }
```

₹1,000 earns 10 points, so 250 becomes 260.

### Crossing a tier boundary

```bash
curl -s -X POST http://localhost:8080/api/loyalty/cust-1002/earn \
  -H "Content-Type: application/json" -d '{"amount":500.00}'
```

`cust-1002` has 995 points and earns 5, landing exactly on 1,000:

```json
{ "customerId": "cust-1002", "points": 1000, "tier": "SILVER" }
```

### Redeeming

```bash
curl -s -X POST http://localhost:8080/api/loyalty/cust-1001/redeem \
  -H "Content-Type: application/json" -d '{"points":50}'
```

```json
{ "customerId": "cust-1001", "points": 200, "tier": "BRONZE" }
```

Read it back and the new balance is there:

```bash
curl -s http://localhost:8080/api/loyalty/cust-1001
```

Redeeming more than the balance changes nothing and returns `409`:

```bash
curl -s -X POST http://localhost:8080/api/loyalty/cust-1001/redeem \
  -H "Content-Type: application/json" -d '{"points":100000}'
```

## Expected functionality

* `mvn test` runs the suite and reports on every test in it.
* ₹150 earns exactly 1 point; ₹1,000 earns exactly 10.
* Exactly 1,000 points is `SILVER`; exactly 5,000 is `GOLD`.
* A redemption is visible on the next `GET` — what the response says and what the database holds are
  the same thing.
* A balance never goes below zero, and an over-redemption is rejected with `409` without changing
  anything.
* An unknown customer produces `404`, not an empty `200`.
