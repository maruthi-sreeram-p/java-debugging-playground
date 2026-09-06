# Invoice Processing Pipeline

An accounts-payable pipeline. Invoices are submitted onto a topic exchange, routed to a queue per
tier, processed by a listener, copied to an audit trail, and — when they cannot be processed —
dead-lettered for an operator to look at.

## Purpose

The pipeline runs on **manual acknowledgement**. Nothing is removed from a queue because a listener
method returned; it is removed because the listener told the broker, on the channel, that it is
finished with that delivery. Everything in this project follows from that one decision: how many
messages are in flight at once, what happens when a listener cannot process one, and what the broker
does with a message nobody will accept.

```
POST /api/invoices/submit?tier=standard   invoice.standard.submitted ─┐
POST /api/invoices/submit?tier=priority   invoice.priority.submitted ─┤
POST /api/invoices/unprocessable          invoice.retry.submitted    ─┤
                                                                     │
                                                      invoice.exchange (topic)
                                                                     │
       ┌──────────────────┬────────────────────┬─────────────────────┴────────┐
       ▼                  ▼                    ▼                              ▼
invoice.standard.queue  invoice.priority.queue  invoice.retry.queue   invoice.audit.queue
       │                  │                    │                              │
  standard listener   priority listener    retry listener             audit listener
                                                │
                                          nack / reject
                                                │
                                        invoice.dlx (direct)
                                                │
                                        invoice.dead.queue
```

Every processed invoice is written to `processed_invoice` with the name of the processor that
handled it. Every invoice should also land once in `audited_invoice`, which is the compliance copy.

## Architecture

```
com.debuglab.invoicing
├── InvoicingApplication
├── config/RabbitConfig            exchanges, queues, bindings, dead-letter setup,
│                                  converter, template, listener container factory
├── controller/InvoiceController   submit, inspect, queue statistics, dead-letter drain
├── service/
│   ├── InvoicePublisher           builds the routing key and publishes
│   ├── InvoiceListeners           one @RabbitListener per work queue
│   └── InvoiceMetricsListener     running count for the operations dashboard
├── repository/                    ProcessedInvoiceRepository, AuditedInvoiceRepository
├── entity/   ProcessedInvoice, AuditedInvoice
└── event/    InvoiceEvent         the JSON payload
```

Routing keys are three segments: `invoice.<tier>.submitted`. Queue names, exchange names and the
dead-letter binding key are constants in `RabbitConfig` so that both ends of the pipeline agree on
them.

### Acknowledgement policy

| Situation | What the listener does |
|---|---|
| Invoice processed successfully | `basicAck` the delivery tag |
| Invoice can never be processed (bad data) | `basicNack` so the broker dead-letters it |
| Listener crashes | the delivery stays unacknowledged and is redelivered when the channel closes |

`prefetch` is set to **5**: the broker hands a consumer at most five unacknowledged messages at a
time. This is the back-pressure control — a consumer that is behind stops receiving.

### The retry header

The publisher stamps every message with an `x-retry-count` header. Redelivered messages carry it, so
a listener can give up after `MAX_ATTEMPTS` rather than trying forever.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa)
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

One container, `debuglab19-rabbitmq`:

| Port | Purpose |
|---|---|
| **5673** | AMQP (one up from the default 5672, so it cannot clash with a broker you already run) |
| **15673** | Management UI |

**Open the management UI: <http://localhost:15673>, `invoiceuser` / `invoicepw`.**

Two screens matter here:

* **Queues** — `Ready`, `Unacked` and `Total` are three different numbers, and the difference between
  them is the subject of this project. `Ready` is waiting to be delivered; `Unacked` has been
  delivered and not yet acknowledged; `Total` is both.
* **Queues → a queue → Bindings**, and under **Details** the `x-dead-letter-exchange` and
  `x-dead-letter-routing-key` arguments the queue was declared with.

The same information on the command line:

```bash
docker exec debuglab19-rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged consumers
```

```bash
docker exec debuglab19-rabbitmq rabbitmqctl list_bindings source_name routing_key destination_name
```

Reset the broker completely:

```bash
docker compose down -v && docker compose up -d
```

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.rabbitmq.host` / `.port` | `localhost` / `5673` | From `docker-compose.yml` |
| `spring.rabbitmq.username` / `.password` | `invoiceuser` / `invoicepw` | **Placeholder credentials** — replace them (and the matching values in `docker-compose.yml`) if you point this at your own broker |

The database is H2 in memory, so it starts empty every time. Nothing to install.

## How to run

```bash
docker compose up -d
```

```bash
mvn clean package
```

```bash
java -jar target/invoice-pipeline-1.0.0.jar
```

```bash
mvn test
```

`mvn test` needs the broker to be running.

## API endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/invoices/submit?tier={tier}&count={n}` | Publish `n` invoices of that tier (`standard`, `priority`) |
| `POST` | `/api/invoices/unprocessable` | Publish one invoice with a zero amount, which no listener can process |
| `GET` | `/api/invoices/processed` | Everything written to `processed_invoice`, with the processor that handled it |
| `GET` | `/api/invoices/audited` | The compliance copy of every invoice |
| `GET` | `/api/invoices/dead-letter` | **Drains** the dead-letter queue and returns what was in it |
| `GET` | `/api/queues/stats` | Ready-message and consumer counts, straight from the broker |
| `GET` | `/api/pipeline/summary` | Processed counts per tier, audit count, metrics count |

### A batch through the pipeline

```bash
curl -s -X POST "http://localhost:8080/api/invoices/submit?tier=standard&count=10"
```

```bash
curl -s -X POST "http://localhost:8080/api/invoices/submit?tier=priority&count=10"
```

```bash
curl -s http://localhost:8080/api/pipeline/summary
```

```json
{
  "processedStandard": 10,
  "processedPriority": 10,
  "processedRetry": 0,
  "audited": 20,
  "countedByMetrics": 10
}
```

### An invoice that cannot be processed

```bash
curl -s -X POST http://localhost:8080/api/invoices/unprocessable
```

```bash
curl -s http://localhost:8080/api/invoices/dead-letter
```

```json
[ { "invoiceNumber": "INV-...", "customer": "Unknown Trader", "tier": "retry", "amount": 0 } ]
```

One rejection, one message in the dead-letter queue, and the retry queue back to empty.

## Expected functionality

* Every submitted invoice is processed exactly once and appears in `/api/invoices/processed`.
* Every submitted invoice also appears once in `/api/invoices/audited`.
* `countedByMetrics` equals the number of priority invoices submitted — the dashboard counts them,
  it does not consume them.
* `/api/queues/stats` settles at `messages: 0` for every work queue, with one consumer each.
* An unprocessable invoice is rejected **once**, leaves its queue, and is retrievable from
  `/api/invoices/dead-letter`.
* Stopping the application returns any in-flight messages to their queues, and starting it again
  finishes them.
