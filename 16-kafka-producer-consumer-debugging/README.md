# Order Event Pipeline

An order API that publishes an event for every order it accepts, and a consumer that reads those
events back and materialises a read model from them.

## Purpose

This is the smallest complete event pipeline: one producer, one topic, one consumer, one projection.

```
POST /api/orders
      |
      |-- writes the order to H2          (the source of truth)
      |-- publishes an OrderPlacedEvent   (to Kafka)
                                |
                                v
                    OrderEventConsumer
                          |
                          '-- writes a row to order_projection
```

The projection is what a downstream reporting system would read. The pipeline is correct when, for
every order accepted by the API, exactly one projection row appears carrying that order's details.

Because the pipeline is asynchronous, **a message that never arrives produces no error anywhere.**
The `POST` returns `201`, the order is in the database, and the projection is simply empty. Learning
to prove where a message actually went is the point of this project.

## Architecture

```
com.debuglab.orderevents
├── OrderEventsApplication
├── config/KafkaConfig               producer factory, consumer factory, listener container
├── controller/OrderController       place an order, list orders, read the projection, health
├── service/
│   ├── OrderService                 stores the order and publishes the event
│   ├── OrderEventProducer           KafkaTemplate wrapper
│   └── OrderEventConsumer           @KafkaListener -> projection
├── repository/                      OrderRecordRepository, OrderProjectionRepository
├── entity/  OrderRecord, OrderProjection
├── event/   OrderPlacedEvent        the JSON payload on the wire
└── dto/     PlaceOrderRequest
```

Kafka is configured explicitly in `KafkaConfig` rather than through `spring.kafka.*` properties, so
that every serialisation and consumer decision is visible in one file.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* **Spring for Apache Kafka**
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

One container: `debuglab16-kafka`, a single-node KRaft broker published on **host port 9094** so it
cannot clash with a broker you already run on 9092. Give it about thirty seconds on first start.

```bash
docker ps --filter name=debuglab16     # wait for "(healthy)"
```

**Talking to Kafka directly.** You will need this constantly, and it is the only way to prove where a
message went. On Windows in Git Bash, prefix these with `MSYS_NO_PATHCONV=1` so the container paths
are not rewritten:

```bash
K() { MSYS_NO_PATHCONV=1 docker exec debuglab16-kafka "$@"; }

# which topics exist
K /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# read a topic from the very beginning
K /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic order-events --from-beginning --timeout-ms 5000

# consumer groups, their offsets and their lag
K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group order-projection
```

That last command is the single most useful diagnostic in this project: it shows which partitions a
group is reading, what offset it has committed, and how far behind the end of the topic it is.

Reset everything (this deletes all topics and offsets):

```bash
docker compose down -v && docker compose up -d
```

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:9094` | Host port from `docker-compose.yml` |
| `app.kafka.orders-topic` | `order-events` | The topic order events are published to |
| `spring.datasource.url` | `jdbc:h2:mem:ordersdb` | Orders and projection; nothing to install |

No credentials anywhere — the broker runs PLAINTEXT with no authentication, which is appropriate for
a local development broker and nothing else.

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/order-events-1.0.0.jar
```

```bash
mvn test          # requires the broker to be up
```

## API endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/orders` | Place an order: store it and publish the event |
| `GET` | `/api/orders` | Every order stored (the source of truth) |
| `GET` | `/api/projections/orders` | Every projection row the consumer has built |
| `GET` | `/api/health/kafka` | The producer's topic, plus stored and projected counts |

### Placing an order

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"Aarav Sharma","item":"Mechanical Keyboard","quantity":1,"amount":4499.00}'
```

```json
{ "id": 1, "orderNumber": "ORD-64276D20", "customerName": "Aarav Sharma",
  "item": "Mechanical Keyboard", "quantity": 1, "amount": 4499.00,
  "placedAt": "2026-02-01T10:00:00" }
```

### Checking the pipeline

```bash
curl http://localhost:8080/api/health/kafka
```

```json
{ "producerTopic": "order-events", "ordersStored": 3, "ordersProjected": 3 }
```

**Those two counts must match**, within a second or so of each order being placed.

```bash
curl http://localhost:8080/api/projections/orders
```

```json
[ { "id": 1, "orderNumber": "ORD-64276D20", "customerName": "Aarav Sharma",
    "item": "Mechanical Keyboard", "amount": 4499.00,
    "projectedAt": "2026-02-01T10:00:00.412" } ]
```

Each projection row carries the customer name, item and amount — the consumer looks the order up by
its order number and copies them across.

## Expected functionality

* `POST /api/orders` returns `201` and the order appears in `GET /api/orders`.
* Within a second or two, a matching row appears in `GET /api/projections/orders`, **with the
  customer name, item and amount filled in** — never null.
* `ordersStored` and `ordersProjected` are equal.
* The application log shows a `PUBLISHED` line followed by a `RECEIVED` line for the same order
  number.
* `kafka-console-consumer --from-beginning` on the orders topic shows one JSON message per order.
* Events already on the topic when the application starts are processed, not skipped — restarting
  the application never loses an order.
