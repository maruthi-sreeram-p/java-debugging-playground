# Customer Onboarding API

The service that takes a new customer's details, checks them, and creates the customer record that
the rest of the platform reads.

## Purpose

This service is the **front door**. Everything downstream — billing, delivery, compliance reporting —
trusts that a customer record here is well-formed, because none of them check again.

That makes two things equally important:

1. **Nothing invalid gets in.** Bad data must be rejected at the boundary, with `400` and a field-level
   explanation the caller can act on.
2. **Nothing valid gets turned away**, and nothing valid gets quietly altered on the way in. What the
   caller sent is what gets stored, in the fields they sent it for.

## Architecture

```
com.debuglab.onboarding
├── OnboardingApplication
├── controller/CustomerController      HTTP layer, request validation entry points
├── service/CustomerService            business rules, duplicate checks
├── mapper/CustomerMapper              request -> entity, entity -> response
├── repository/CustomerRepository
├── entity/Customer                    mapped to the `customers` table
├── dto/
│   ├── CustomerRequest                the create/replace payload, with a nested address
│   ├── AddressRequest                 also used on its own by the address endpoint
│   └── CustomerResponse
├── validation/
│   ├── Pincode                        custom constraint annotation
│   └── PincodeValidator               its implementation
└── exception/                         domain exceptions + @RestControllerAdvice
```

Validation is Bean Validation (Jakarta Validation 3.0) applied at the controller boundary. Constraints
live on the **request DTOs**, not on the entity — the entity is a persistence concern and is never the
place to express an API contract.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, **validation**)
* Hibernate Validator 8
* Hibernate 6.5, MySQL 8.0 (Docker)
* Maven, JUnit 5, MockMvc

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+
* Docker Desktop (or any Docker engine with Compose v2)

### Database setup

```bash
docker compose up -d
```

MySQL is published on **host port 3307**. The schema is created at start-up and `data.sql` seeds
three customers.

| id | Name | Email | Age | City | State |
|---|---|---|---|---|---|
| 1 | Ishaan Kapoor | `ishaan.kapoor@mail.com` | 31 | Bengaluru | Karnataka |
| 2 | Sneha Pillai | `sneha.pillai@mail.com` | 27 | Kochi | Kerala |
| 3 | Farhan Ali | `farhan.ali@mail.com` | 45 | Hyderabad | Telangana |

```bash
docker exec -it debuglab12-mysql mysql -uroot -prootpw onboardingdb
```

```sql
SELECT id, full_name, age, city, state, pincode, phone FROM customers;
```

Note the column widths — `phone` is `varchar(15)`, `city` and `state` are `varchar(80)`, `pincode` is
`varchar(10)`. The API contract must not allow anything the schema cannot hold.

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/onboardingdb?...` | Host port from `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `onboarduser` / `onboardpw` | **Placeholder credentials** |
| `server.error.include-message` | `always` | So error responses explain themselves |

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/customer-onboarding-1.0.0.jar
```

```bash
mvn test          # requires the container to be up
```

## API endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| `POST` | `/api/customers` | Onboard a customer | `201` |
| `GET` | `/api/customers/{id}` | Read one | `200`, `404` |
| `PUT` | `/api/customers/{id}` | **Replace** a customer — the full payload is required | `200`, `404` |
| `POST` | `/api/customers/{id}/address` | Replace just the address | `200`, `404` |
| `GET` | `/api/customers?minAge=` | Customers aged at least `minAge` | `200` |

### The customer payload

```json
{
  "fullName": "Ishaan Kapoor",
  "email": "ishaan.kapoor@mail.com",
  "phone": "+919876543210",
  "age": 31,
  "address": {
    "addressLine1": "14 Residency Road",
    "city": "Bengaluru",
    "state": "Karnataka",
    "pincode": "560025"
  },
  "marketingOptIn": true
}
```

### The rules

| Field | Rule |
|---|---|
| `fullName` | required, non-blank |
| `email` | required, a valid address, unique across all customers |
| `phone` | optional; must fit the `varchar(15)` column |
| `age` | required; **at least 18**, at most 120 |
| `address` | required |
| `address.addressLine1` | required, at most 200 characters |
| `address.city` | required, at most 80 characters |
| `address.state` | required, at most 80 characters |
| `address.pincode` | required, **exactly six digits** |
| `marketingOptIn` | optional, defaults to `false` |

Every one of these rules applies on **create and on replace**. `PUT` is a full replacement, so the
whole payload is expected — it is validated exactly as `POST` is.

### Error contract

```json
{
  "timestamp": "2026-01-01T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "fields": { "age": "customers must be at least 18", "address.city": "city is required" }
}
```

Nested fields are reported with their path, e.g. `address.pincode`.

| Situation | Status |
|---|---|
| Any rule above broken | `400` with `fields` |
| Email already belongs to another customer | `409` |
| No such customer | `404` |

**No valid request should ever produce a `5xx`.** A `500` from this service means a rule that should
have been checked at the boundary was not.

### Examples

```bash
# onboard
curl -i -X POST http://localhost:8080/api/customers \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Nisha Verma","email":"nisha.verma@mail.com","phone":"+919812345678","age":29,
       "address":{"addressLine1":"5 Park Street","city":"Kolkata","state":"West Bengal","pincode":"700016"},
       "marketingOptIn":true}'

# read it back - city and state must be the way round you sent them
curl http://localhost:8080/api/customers/4

# replace
curl -i -X PUT http://localhost:8080/api/customers/4 -H "Content-Type: application/json" -d '{...}'

# just the address
curl -i -X POST http://localhost:8080/api/customers/4/address \
  -H "Content-Type: application/json" \
  -d '{"addressLine1":"9 Camac Street","city":"Kolkata","state":"West Bengal","pincode":"700017"}'

# search - any non-negative minimum age is a valid query
curl "http://localhost:8080/api/customers?minAge=0"
curl "http://localhost:8080/api/customers?minAge=30"
```

## Expected functionality

* Every rule in the table is enforced, on create and on replace, with `400` and a `fields` entry.
* A customer reads back exactly as they were sent — same city in `city`, same state in `state`.
* A duplicate email is `409`, never `500`.
* Anything the database cannot store is rejected at the boundary with `400`, never `500`.
* `?minAge=0` and `?minAge=1` are valid searches.
