# Payment Settlement Consumer

A consumer that reads payment events off a Kafka topic and settles each one exactly once.

## Purpose

Project 16 asked "did the message arrive?". This one asks the harder question:

> **Was it processed exactly once?**

Kafka guarantees *at-least-once* delivery. A message can be redelivered after a rebalance, after a
restart, or after a failure — so the consumer, not the broker, is responsible for making sure a
payment is settled once and only once. Two things do that work here:

* **Offsets** tell Kafka how far this consumer group has got. Nothing is redelivered once its offset
  is committed — so committing at the right moment is the whole game.
* **The idempotency table** (`processed_messages`) is the safety net for the redeliveries that happen
  anyway. Before settling a payment, the consumer checks whether it has already seen that reference.

The topic has **three partitions** and the listener runs with a configured concurrency, so several
records are in flight at once.

This is a payments system. Settling a payment twice takes money twice.

## Architecture

```
com.debuglab.settlement
├── SettlementApplication
├── config/KafkaConfig               topic, producer, consumer, ack mode, error handler, concurrency
├── controller/SettlementController  publish, inspect settlements, inspect duplicates, health
├── service/
│   ├── PaymentPublisher             puts payment events on the topic
│   └── SettlementConsumer           @KafkaListener with manual acknowledgement
├── repository/
│   ├── SettlementRepository         plus a query that finds references settled more than once
│   └── ProcessedMessageRepository   the idempotency store
├── entity/  Settlement, ProcessedMessage
└── event/   PaymentEvent
```

Everything about the consumer is configured explicitly in `KafkaConfig` — acknowledgement mode,
concurrency, poll limits, error handling — so that each decision is visible in one file.

Settling a payment deliberately takes about 200 ms, to model real work (a ledger write, a call to a
scheme). That number matters when you start thinking about poll intervals.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* Spring for Apache Kafka — manual acknowledgement, `DefaultErrorHandler`, concurrent listeners
* H2 in-memory database
* Apache Kafka 3.9 in KRaft mode (Docker, single broker)
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

One container, `debuglab17-kafka`, published on **host port 9094**. Give it about thirty seconds on
first start, then check `docker ps --filter name=debuglab17` for `(healthy)`.

**The Kafka CLI is essential in this project.** On Windows in Git Bash, prefix with
`MSYS_NO_PATHCONV=1` so container paths are not rewritten:

```bash
K() { MSYS_NO_PATHCONV=1 docker exec debuglab17-kafka "$@"; }

# offsets, log ends and lag - the single most important command here
K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group settlement-processor

# which consumer holds which partitions, and how many each has
K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --members --group settlement-processor

# the topic and its partitions
K /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --describe --topic payment-events
```

In `--describe`, `CURRENT-OFFSET` is what the group has **committed**. A dash means it has committed
nothing at all.

Start from a clean broker whenever you want a fresh experiment:

```bash
docker compose down -v && docker compose up -d
```

### Environment configuration

| Property | Value | Meaning |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:9094` | Host port from `docker-compose.yml` |
| `app.kafka.payments-topic` | `payment-events` | The topic |
| `app.kafka.topic-partitions` | `3` | Partitions created for it |
| `app.kafka.consumer-concurrency` | `6` | Listener threads |

No credentials — the broker runs PLAINTEXT, which is for local development only.

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/payment-settlement-1.0.0.jar
```

```bash
mvn test          # requires the broker to be up; start from a clean one
```

## API endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/payments/publish?count=N` | Publish `N` payment events (default 5) |
| `POST` | `/api/payments/unprocessable` | Publish one payment the consumer cannot settle (zero amount) |
| `GET` | `/api/settlements` | Every settlement row |
| `GET` | `/api/settlements/duplicates` | References settled more than once |
| `GET` | `/api/health/consumer` | Settlement and idempotency-marker counts |

### Examples

```bash
curl -X POST "http://localhost:8080/api/payments/publish?count=5"
sleep 5
curl http://localhost:8080/api/health/consumer
```

```json
{ "topic": "payment-events", "settlements": 5, "processedMarkers": 5 }
```

```bash
curl http://localhost:8080/api/settlements/duplicates
```

```json
{ "distinctReferences": 5, "settlementRows": 5, "duplicateReferences": [] }
```

`duplicateReferences` must always be empty, and `distinctReferences` must always equal
`settlementRows`.

### The unprocessable payment

```bash
curl -X POST http://localhost:8080/api/payments/unprocessable
```

Publishes a payment with a zero amount, which the consumer rejects. A message that can never succeed
must not be retried forever and must not hold up the payments behind it — it should be retried a
bounded number of times and then set aside somewhere a human can find it.

## Expected functionality

* `publish?count=N` results in exactly `N` settlement rows, whatever `N` is — 5 or 500.
* `duplicateReferences` is always empty.
* Restarting the application does **not** re-settle anything already settled.
* `kafka-consumer-groups --describe` shows a real `CURRENT-OFFSET` that advances as work completes,
  and `LAG` returning to `0`.
* Every consumer thread is assigned at least one partition.
* An unprocessable payment is retried a few times, then set aside — the payments behind it are
  settled normally.
