# Shipment Notification Service

A shipping API that publishes an event at each stage of a shipment's life, and listeners that turn
those events into customer notifications.

## Purpose

Three things happen to a shipment — it is **created**, **dispatched** and **delivered** — and each
one has to reach the notification listener for that stage.

```
POST /api/shipments                 --(shipment.created)---> shipment.created.queue    --> listener
POST /api/shipments/{tn}/dispatch   --(shipment.dispatched)-> shipment.dispatched.queue --> listener
POST /api/shipments/{tn}/deliver    --(shipment.delivered)--> shipment.delivered.queue  --> listener
                                      |
                                 shipping.exchange
                                   (topic)
```

All three go through one topic exchange, and the routing key decides which queue each lands in. A
message published with a routing key nothing is bound to is **discarded by the broker without any
error at all** — which is the single most important thing to know about RabbitMQ when debugging it.

## Architecture

```
com.debuglab.shipping
├── ShippingApplication
├── config/RabbitConfig                exchange, queues, bindings, converter, template, container factory
├── controller/ShipmentController      create, dispatch, deliver, list, inspect notifications
├── service/
│   ├── ShipmentService                stores the shipment and publishes the event
│   ├── ShipmentEventPublisher         one method per event type
│   └── ShipmentNotificationListener   one @RabbitListener per queue
├── repository/                        ShipmentRepository, ReceivedNotificationRepository
├── entity/  Shipment, ReceivedNotification
├── event/   ShipmentEvent             the JSON payload
└── dto/     CreateShipmentRequest
```

Exchange, queue and routing-key names are declared as constants in `RabbitConfig`, so that both ends
of the pipeline can refer to the same values.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* **Spring AMQP** (`spring-boot-starter-amqp`)
* H2 in-memory database
* RabbitMQ 3 with the management plugin (Docker)
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

One container, `debuglab18-rabbitmq`:

| Port | Purpose |
|---|---|
| **5673** | AMQP (one up from the default 5672) |
| **15673** | Management UI |

**The management UI is the main instrument for this project.** Open
<http://localhost:15673> and log in with `shipuser` / `shippw`.

The three screens that matter:

* **Exchanges** — which exchanges exist, and what is bound to each. Click `shipping.exchange` to see
  its bindings and their routing keys.
* **Queues** — how many messages are sitting in each queue, and how many consumers each has. A queue
  with messages and no consumer is a very specific kind of problem; a queue with a consumer and no
  messages is a different one.
* **Queue → Publish/Get message** — put a message on a queue by hand, or read one off, without
  touching the application.

Everything the UI shows is also available on the command line:

```bash
R() { docker exec debuglab18-rabbitmq "$@"; }

R rabbitmqctl list_exchanges name type
R rabbitmqctl list_queues name messages consumers durable
R rabbitmqctl list_bindings source_name routing_key destination_name
```

Reset everything:

```bash
docker compose down -v && docker compose up -d
```

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.rabbitmq.host` / `.port` | `localhost` / `5673` | From `docker-compose.yml` |
| `spring.rabbitmq.username` / `.password` | `shipuser` / `shippw` | **Placeholder credentials** — replace if you point at your own broker |

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/shipment-notifications-1.0.0.jar
```

```bash
mvn test          # requires the broker to be up
```

## API endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/shipments` | Create a shipment and publish `shipment.created` |
| `POST` | `/api/shipments/{trackingNumber}/dispatch` | Publish `shipment.dispatched` |
| `POST` | `/api/shipments/{trackingNumber}/deliver` | Publish `shipment.delivered` |
| `GET` | `/api/shipments` | Every shipment |
| `GET` | `/api/notifications/received` | Every notification a listener has recorded |
| `GET` | `/api/notifications/summary` | Counts per event type |

### A full journey

```bash
TN=$(curl -s -X POST http://localhost:8080/api/shipments \
  -H "Content-Type: application/json" \
  -d '{"recipient":"Aarav Sharma","destination":"Bengaluru"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['trackingNumber'])")

curl -s -X POST "http://localhost:8080/api/shipments/$TN/dispatch"
curl -s -X POST "http://localhost:8080/api/shipments/$TN/deliver"

sleep 2
curl http://localhost:8080/api/notifications/summary
```

```json
{ "shipments": 1, "created": 1, "dispatched": 1, "delivered": 1 }
```

One shipment through all three stages produces exactly one notification of each type.

## Expected functionality

* Each of the three stages produces exactly one notification of its type, within a second or so.
* `rabbitmqctl list_queues name messages` shows every queue at `0` once processing has settled —
  messages are consumed, not accumulated.
* `rabbitmqctl list_queues name consumers` shows every queue with at least one consumer.
* The management UI's **Exchanges → shipping.exchange** page shows three bindings whose routing keys
  match the keys the publisher uses.
* Nothing is published to an exchange that does not exist, and nothing is published with a routing
  key that no queue is bound to.
* The queues and their contents survive a restart of the broker.
