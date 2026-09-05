# Banking Transaction API

A core-banking service that moves money between accounts, writes a double-entry ledger, and keeps an
audit trail of every attempt.

## Purpose

This is the service that other systems call when money has to move. Three guarantees define it:

1. **Atomicity.** A transfer either debits the source *and* credits the destination, or it does
   neither. There is no state in which money has left one account without arriving at the other.
2. **A ledger that reconciles.** Every movement produces a matching pair of ledger entries, and the
   sum of a ledger equals the account balance.
3. **An audit trail that survives failure.** Every attempt is recorded — including the ones that
   fail — because "why did this transfer not happen?" is a question someone will ask.

## Architecture

```
com.debuglab.banking
├── BankingApplication
├── controller/
│   ├── AccountController        balance, ledger, freeze
│   └── TransferController       transfer, batch, audit
├── service/
│   ├── TransferService          THE TRANSACTION BOUNDARY - orchestrates a transfer
│   ├── AccountService           debit and credit primitives
│   ├── LedgerService            double-entry ledger writes
│   └── TransferAuditService     records every attempt, success or failure
├── repository/
│   ├── AccountRepository
│   ├── LedgerEntryRepository
│   └── TransferAuditRepository
├── entity/
│   ├── Account
│   ├── LedgerEntry
│   └── TransferAudit
├── dto/
└── exception/
```

The design intent is that `TransferService.transfer` is the unit of work. Everything it calls
participates in that one transaction, so if any step fails, the whole transfer is undone — with the
deliberate exception of the audit trail, which has to outlive a rollback.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* Hibernate 6.5, Spring transaction management
* MySQL 8.0 (via Docker Compose) — a real transactional engine, which matters here
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

MySQL is published on **host port 3307** so it cannot clash with a MySQL you already run on 3306.
The application creates its schema at start-up and `data.sql` seeds six accounts:

| Account | Holder | Balance | Frozen |
|---|---|---|---|
| `ACC-1001` | Aarav Sharma | 50000.00 | no |
| `ACC-1002` | Divya Nair | 25000.00 | no |
| `ACC-1003` | Rohan Mehta | 10000.00 | no |
| `ACC-1004` | Priya Iyer | 500.00 | no |
| `ACC-1005` | Kabir Khanna | 75000.00 | **yes** |
| `ACC-1006` | Meera Rao | 15000.00 | no |

A frozen account can still send money but cannot receive it.

Open a shell whenever you want the truth rather than the API's version of it:

```bash
docker exec -it debuglab08-mysql mysql -uroot -prootpw bankdb
```

```sql
SELECT account_number, balance FROM accounts;
SELECT * FROM ledger_entries ORDER BY id;
SELECT * FROM transfer_audit ORDER BY id;
```

Reset to the seeded state at any time by restarting the application (the schema is recreated), or
fully with `docker compose down -v && docker compose up -d`.

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/bankdb?...` | Host port from `docker-compose.yml` |
| `spring.datasource.username` | `bankuser` | **Placeholder credential** — replace if you use your own MySQL |
| `spring.datasource.password` | `bankpw` | As above |
| `spring.datasource.hikari.maximum-pool-size` | `20` | Large enough for the concurrency this service expects |
| `logging.level.org.springframework.transaction.interceptor` | `TRACE` | Prints every transaction being created, joined, committed or rolled back |

That last one is not decoration. In a project about transactions, the transaction log is the
instrument.

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/banking-transfers-1.0.0.jar
```

```bash
mvn test          # requires the container to be up
```

## API endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| `GET` | `/api/accounts/{accountNumber}` | Balance and status | `200`, `404` |
| `GET` | `/api/accounts/{accountNumber}/ledger` | Every ledger entry for the account | `200` |
| `POST` | `/api/accounts/{accountNumber}/freeze` | Stop the account receiving money | `200`, `404` |
| `POST` | `/api/transfers` | Move money | `200` |
| `POST` | `/api/transfers/batch` | Several transfers in one call | `200` |
| `GET` | `/api/transfers/audit` | Every attempt, newest first | `200` |

### A transfer

```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -d '{"reference":"TX-1001","fromAccount":"ACC-1001","toAccount":"ACC-1002","amount":1000.00}'
```

```json
{
  "reference": "TX-1001",
  "status": "COMPLETED",
  "message": "Transfer completed",
  "amount": 1000.00,
  "fromBalance": 49000.00,
  "toBalance": 26000.00
}
```

`reference` is supplied by the caller and must be unique — it is the idempotency key. Replaying a
reference is rejected with `409 Conflict` **and must not move money a second time.**

Error responses:

| Situation | Status | Behaviour |
|---|---|---|
| Destination account does not exist | `404` | Nothing moves |
| Source has insufficient funds | `409` | Nothing moves |
| Destination is frozen | `200` with `"status":"FAILED"` | Nothing moves; the attempt is audited |
| Reference already used | `409` | Nothing moves |

### Batch transfers

```bash
curl -X POST http://localhost:8080/api/transfers/batch \
  -H "Content-Type: application/json" \
  -d '{"transfers":[
        {"reference":"TXB-1","fromAccount":"ACC-1001","toAccount":"ACC-1002","amount":500.00},
        {"reference":"TXB-2","fromAccount":"ACC-1001","toAccount":"ACC-9999","amount":700.00},
        {"reference":"TXB-3","fromAccount":"ACC-1001","toAccount":"ACC-1002","amount":300.00}
      ]}'
```

Each transfer in a batch is **independent and individually atomic**. One failing does not prevent the
others, and a transfer that fails leaves both of its accounts exactly as they were. The response
reports the outcome of each.

### Ledger and audit

```bash
curl http://localhost:8080/api/accounts/ACC-1001/ledger
curl http://localhost:8080/api/transfers/audit
```

Every completed transfer writes two ledger entries — a `DEBIT` on the source and a `CREDIT` on the
destination — sharing the transfer's reference. The audit trail records every attempt, whether it
completed or not.

### Freezing an account

```bash
curl -X POST http://localhost:8080/api/accounts/ACC-1006/freeze
curl http://localhost:8080/api/accounts/ACC-1006      # frozen is now true
```

## Expected functionality

* Money is conserved. Sum the balances before and after any sequence of operations — the total is
  unchanged, always.
* Every account's balance equals the sum of its ledger entries.
* Failed transfers move nothing and still appear in the audit trail.
* Concurrent transfers from the same account are safe: thirty simultaneous transfers of 100 reduce
  the balance by exactly 3000.
* Changes made through the API are still there when you read them back.
