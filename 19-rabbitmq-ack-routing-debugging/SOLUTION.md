# SOLUTION — 19 · Invoice Processing Pipeline

> Reference copy. Do not read this until you have finished, or until you have asked for the solution
> explicitly.

Seven defects.

| # | Symptom | Area |
|---|---|---|
| 1 | Standard tier stops at exactly five | Manual acknowledgement never sent |
| 2 | Priority tier splits five/five | Two listeners competing on one queue |
| 3 | Audit trail always empty | Topic wildcard cannot match the routing key |
| 4 | One invoice redelivered thousands of times a second | `basicNack` with `requeue = true` |
| 5 | Retry limit never enforced | `x-retry-count` stamped once, never incremented |
| 6 | Rejected invoice vanishes entirely | Dead-letter routing key matches no binding |
| 7 | Audit rows lost on failure | Acknowledgement sent before the work is done |

---

## Defect 1 — the standard listener never acknowledges

**Where:** `service/InvoiceListeners.java`, `onStandardInvoice`.

**What is wrong:**

```java
@RabbitListener(queues = RabbitConfig.STANDARD_QUEUE)
public void onStandardInvoice(InvoiceEvent event,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    log.info("STANDARD received {}", event.getInvoiceNumber());
    processedInvoiceRepository.save(new ProcessedInvoice(...));
}
```

The method takes a `Channel` and a delivery tag and never uses either.

**Root cause:** the container factory is configured with
`factory.setAcknowledgeMode(AcknowledgeMode.MANUAL)`. In manual mode Spring AMQP does not
acknowledge anything on your behalf — returning normally from the listener method means nothing to
the broker. The delivery stays outstanding for the life of the channel.

The number five comes from `factory.setPrefetchCount(5)`. Prefetch is the maximum number of
*unacknowledged* deliveries the broker will let a consumer hold. The first five arrive, are
processed, are never settled, and the consumer is now at its limit — so the broker stops delivering.
The consumer is connected and idle for ever.

**Fix:**

```java
@RabbitListener(queues = RabbitConfig.STANDARD_QUEUE)
public void onStandardInvoice(InvoiceEvent event,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
        throws IOException {

    log.info("STANDARD received {}", event.getInvoiceNumber());
    processedInvoiceRepository.save(new ProcessedInvoice(event.getInvoiceNumber(),
            event.getCustomer(), event.getTier(), event.getAmount(), "standard-processor"));
    channel.basicAck(deliveryTag, false);
}
```

A more robust version wraps the work and nacks on failure:

```java
try {
    processedInvoiceRepository.save(...);
    channel.basicAck(deliveryTag, false);
} catch (Exception e) {
    log.error("STANDARD failed for {}", event.getInvoiceNumber(), e);
    channel.basicNack(deliveryTag, false, false);   // dead-letter it, do not requeue
}
```

**Why a fresher writes this:** every tutorial starts with the default `AUTO` mode, where returning
normally *is* the acknowledgement. Somebody switches the factory to `MANUAL` (usually to get
dead-lettering working) and the existing listeners keep compiling and keep running — they just stop
finishing. Nothing warns you. The parameters are even there, because they were copied from a
listener that does use them.

**How to recognise it in a real project:** a queue with a healthy consumer, zero throughput, and a
non-zero `messages_unacknowledged` that exactly equals the prefetch count. That combination has
essentially one cause.

---

## Defect 2 — two listeners compete on the priority queue

**Where:** `service/InvoiceMetricsListener.java`.

**What is wrong:**

```java
@RabbitListener(queues = RabbitConfig.PRIORITY_QUEUE)
public void countPriority(InvoiceEvent event, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    long total = prioritySeen.incrementAndGet();
    channel.basicAck(deliveryTag, false);
}
```

`InvoiceListeners.onPriorityInvoice` is on the same queue.

**Root cause:** a queue delivers each message to exactly one consumer, round-robin. Two consumers on
one queue is the *competing consumers* pattern, which is right when you want to scale one job across
several workers. It is wrong here: these are two different jobs. The dashboard wants to see every
invoice; the processor needs to persist every invoice. Each ends up with roughly half, and because
the metrics listener acknowledges properly the missing invoices are gone — no error, no backlog,
nothing to find in the log.

**Fix:** give the dashboard its own queue bound to the same routing key. A topic exchange copies the
message to every queue whose binding matches.

```java
public static final String METRICS_QUEUE = "invoice.metrics.queue";

@Bean
public Queue metricsQueue() {
    return QueueBuilder.durable(METRICS_QUEUE).build();
}

@Bean
public Binding metricsBinding() {
    return BindingBuilder.bind(metricsQueue()).to(invoiceExchange()).with("invoice.priority.*");
}
```

```java
@RabbitListener(queues = RabbitConfig.METRICS_QUEUE)
public void countPriority(...) { ... }
```

**Why a fresher writes this:** "I just need to count them, I'll listen to the same queue" is a
natural sentence, and it is exactly the wrong mental model — it treats a queue like a topic you can
subscribe to. In Kafka, where every consumer group gets its own copy of the partition's messages,
that instinct would be correct. In AMQP the fan-out happens at the exchange, not at the queue.

**How to recognise it in a real project:** `consumers = 2` on a queue you believe has one worker;
or two components each reporting about half the traffic. `rabbitmqctl list_consumers` and a
project-wide search for `@RabbitListener` settle it in a minute.

---

## Defect 3 — the audit binding uses the wrong wildcard

**Where:** `config/RabbitConfig.java`, `auditBinding()`.

**What is wrong:**

```java
return BindingBuilder.bind(auditQueue()).to(invoiceExchange()).with("invoice.*");
```

The publisher sends with `"invoice." + tier + ".submitted"` — for example
`invoice.standard.submitted`.

**Root cause:** in a topic exchange the routing key is a dot-separated list of words, and the two
wildcards are not interchangeable:

* `*` matches **exactly one word**
* `#` matches **zero or more words**

`invoice.*` matches a two-word key such as `invoice.submitted`. The keys here have three words, so
the pattern never matches, the exchange finds no matching binding, and the message is dropped for
that queue. The audit queue has never received a single message. RabbitMQ regards this as normal
routing, not an error, so nothing is logged anywhere.

**Fix:**

```java
return BindingBuilder.bind(auditQueue()).to(invoiceExchange()).with("invoice.#");
```

And so that this class of mistake announces itself next time:

```java
@Bean
public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter());
    template.setMandatory(true);
    template.setReturnsCallback(returned ->
            log.error("UNROUTABLE key={} reply={}",
                    returned.getRoutingKey(), returned.getReplyText()));
    return template;
}
```

(with `spring.rabbitmq.publisher-returns=true`). Note that a returns callback only fires when *no*
queue matched at all — here the standard queue did match, so the return would not have fired for
this particular defect. The binding table is still the ground truth:
`rabbitmqctl list_bindings source_name routing_key destination_name`.

**Why a fresher writes this:** `*` means "anything" in shell globs, in SQL-ish thinking, and in most
places a developer has met it. AMQP's `*` is much narrower, and the difference only appears when the
number of segments changes — which is exactly what happened when somebody added the tier segment to
the routing key and updated the per-tier bindings but not the catch-all one.

**How to recognise it in a real project:** a queue with a consumer, zero messages ever, and no error
anywhere. Compare bindings against real routing keys — do not compare them against what you think
the routing keys are; print one.

---

## Defect 4 — rejection with requeue creates an infinite hot loop

**Where:** `service/InvoiceListeners.java`, `onRetryInvoice`.

**What is wrong:**

```java
if (event.getAmount() == null || event.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
    log.warn("RETRY rejecting {} - amount is not positive", event.getInvoiceNumber());
    channel.basicNack(deliveryTag, false, true);
    return;
}
```

**Root cause:** the third argument of `basicNack(deliveryTag, multiple, requeue)` is `requeue`.
`true` asks the broker to put the message back on the queue. The same consumer immediately receives
it again, makes the same decision, and asks for it back again — with no delay and no attempt limit.
Measured here: **2,179 redeliveries of one invoice in six seconds**, a pinned core and a log growing
by tens of megabytes a minute.

The condition itself is a permanent property of the message. An invoice with a zero amount will
never become processable by being tried again.

**Fix:**

```java
channel.basicNack(deliveryTag, false, false);
```

`requeue = false` sends the message to the queue's dead-letter exchange (or discards it if the queue
has none). `channel.basicReject(deliveryTag, false)` is equivalent for a single message.

Keep `requeue = true` only for *transient* failures — a database that is briefly down — and even
then pair it with a delay and an attempt limit, or it becomes the same hot loop under load.

**Why a fresher writes this:** the parameter is an unnamed boolean at a call site that already has
another boolean next to it, and "requeue = true" reads like "don't lose my message", which sounds
like the safe choice. It is the opposite: it is the choice that takes the service down.

**How to recognise it in a real project:** a queue at a constant one or two messages with enormous
message rates in and out on the management UI's graph, one invoice number repeating in the log, and
CPU at 100% with no traffic. The management UI's per-queue "Redelivered" rate is the giveaway.

---

## Defect 5 — the retry counter is never incremented

**Where:** `service/InvoicePublisher.java` stamps the header; `InvoiceListeners.onRetryInvoice` reads
it against `MAX_ATTEMPTS`.

**What is wrong:**

```java
rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, event, message -> {
    message.getMessageProperties().setHeader("x-retry-count", 0);
    return message;
});
```

```java
private static final int MAX_ATTEMPTS = 3;
...
log.info("RETRY received {} (x-retry-count header: {}, limit {})",
        event.getInvoiceNumber(), retryCount, MAX_ATTEMPTS);
```

**Root cause:** the header is written once, at publication, as `0`. Nothing ever increments it, and
`MAX_ATTEMPTS` is never compared against anything — it only appears in a log message. A redelivered
message is the *same* message: the broker hands back the original properties, so the header reads `0`
on the two-thousandth attempt exactly as it did on the first. The pipeline looks like it has a retry
budget and does not have one.

**Fix (three legitimate options — pick one, do not stack them):**

1. **Let the broker count.** With defects 4 and 6 fixed, a dead-lettered message carries an `x-death`
   header maintained by the broker, containing the reason, the original queue and a `count`. Route
   the dead-letter queue back to the work queue and read `x-death` to decide when to stop.

2. **Republish with an incremented header.** The listener reads the header, adds one, and publishes a
   *new* message (usually to a delayed retry queue, so the loop is not hot); when the value reaches
   the limit it publishes to the dead-letter queue instead. This is the pattern the code was
   evidently reaching for.

3. **Use Spring AMQP's own retry.** A `RetryInterceptorBuilder` on the container factory with a
   backoff policy and a `RejectAndDontRequeueRecoverer`, which lets the framework handle both the
   attempt counting and the eventual dead-lettering.

Whatever you choose, delete the dead constant rather than leaving it in a log line.

**Why a fresher writes this:** the half-built version looks finished. There is a header, there is a
constant named `MAX_ATTEMPTS`, and there is a log line printing both — so the retry limit "is
implemented". Nobody notices that no code path increases the number or acts on the limit, because
under normal traffic no message is ever retried and the value is always `0` anyway.

**How to recognise it in a real project:** a retry count that is always the same number in the logs.
If a counter never changes value, it is not a counter. Search the codebase for writes to it, not
reads.

---

## Defect 6 — the dead-letter routing key matches no binding

**Where:** `config/RabbitConfig.java`.

**What is wrong:**

```java
public static final String DEAD_LETTER_BINDING_KEY = "invoice.dead";

@Bean
public Queue retryQueue() {
    return QueueBuilder.durable(RETRY_QUEUE)
            .deadLetterExchange(DEAD_LETTER_EXCHANGE)
            .deadLetterRoutingKey("invoice.failed")
            .build();
}

@Bean
public Binding deadLetterBinding() {
    return BindingBuilder.bind(deadLetterQueue())
            .to(deadLetterExchange())
            .with(DEAD_LETTER_BINDING_KEY);
}
```

All three work queues declare `"invoice.failed"`; the dead-letter queue is bound with
`"invoice.dead"`.

**Root cause:** dead-lettering is two independent halves. The queue's `x-dead-letter-routing-key`
decides the routing key the broker republishes with; a binding decides where that exchange delivers
it. `invoice.dlx` is a **direct** exchange, so the key must match a binding key exactly. Nothing is
bound to `invoice.failed`, so the exchange has nowhere to put the message and drops it.

The message leaves the retry queue, never arrives in the dead-letter queue, and ceases to exist —
confirmed in testing: with `requeue = false`, `invoice.retry.queue` shows `0` and
`invoice.dead.queue` shows `0`, and `/api/invoices/dead-letter` returns `[]`. For an
accounts-payable pipeline that is the worst outcome in this project: a supplier invoice accepted by
the API and silently destroyed.

**Fix:** use the constant everywhere, so the two halves cannot drift.

```java
.deadLetterRoutingKey(DEAD_LETTER_BINDING_KEY)
```

Better still, extract the arguments so all three queues are built the same way:

```java
private Queue workQueue(String name) {
    return QueueBuilder.durable(name)
            .deadLetterExchange(DEAD_LETTER_EXCHANGE)
            .deadLetterRoutingKey(DEAD_LETTER_BINDING_KEY)
            .build();
}
```

Note that changing a queue's arguments does **not** take effect on an existing queue: RabbitMQ
rejects a redeclaration with different arguments (`PRECONDITION_FAILED`, `inequivalent arg`). Delete
the queue in the management UI or run `docker compose down -v` after this change.

**Why a fresher writes this:** the constant was defined for the binding, and then the queue builders
were written (or copy-pasted three times) with a literal that reads perfectly sensibly on its own.
Both names are plausible English for the same idea. The two lines are twenty lines apart, and no
test, compiler or broker error connects them — the misconfiguration is only visible at the moment a
message is actually dead-lettered, which under normal traffic is never.

**How to recognise it in a real project:** a message that leaves its queue and does not arrive in the
dead-letter queue. Read the queue's arguments in the management UI (**Queues → the queue →
Details**), then read the dead-letter exchange's bindings, and compare the strings character by
character.

---

## Defect 7 — the audit listener acknowledges before it does the work

**Where:** `service/InvoiceListeners.java`, `onAuditCopy`.

**What is wrong:**

```java
channel.basicAck(deliveryTag, false);

log.info("AUDIT received {} via '{}'", event.getInvoiceNumber(), routingKey);
auditedInvoiceRepository.save(new AuditedInvoice(event.getInvoiceNumber(), routingKey));
```

**Root cause:** an acknowledgement is a promise that you are finished with the message. The instant
the broker receives it the message is deleted — it cannot be redelivered and it cannot be
dead-lettered. Everything after that line runs with no safety net: if the database is down, if the
save throws a constraint violation, if the process is killed between the two statements, the audit
record is lost permanently and nothing anywhere records that it existed.

This one is currently invisible, because defect 3 means the audit listener has never received a
message. Fix the wildcard and this becomes live.

**Fix:**

```java
log.info("AUDIT received {} via '{}'", event.getInvoiceNumber(), routingKey);
auditedInvoiceRepository.save(new AuditedInvoice(event.getInvoiceNumber(), routingKey));
channel.basicAck(deliveryTag, false);
```

That is at-least-once delivery: a crash after the save but before the ack means the message is
redelivered and processed twice. For an audit trail the fix is a unique constraint on the invoice
number plus an upsert, or a de-duplication check — the listener becomes idempotent and the duplicate
becomes harmless. Choosing to lose records instead is almost never the right trade.

**Why a fresher writes this:** "acknowledge that I received it, then deal with it" is a sensible
sentence in English and a wrong one in AMQP, where the acknowledgement means *processed*, not
*received*. It is also a common way of "fixing" a redelivery storm: acking early makes the duplicate
deliveries stop, and quietly converts a duplicate-processing problem into a data-loss problem.

**How to recognise it in a real project:** records that are missing after an incident, with no error
and no dead-letter entry to explain them. Read every listener and check that the acknowledgement is
the last statement on the success path — and that a failure path exists at all.

---

## Order of discovery

```
Defect 1 (no ack)  ─────────────── independent
Defect 2 (competing consumers) ─── independent
Defect 4 (requeue loop) ─────────── independent
Defect 5 (retry counter) ────────── independent, visible in the log from the first run

Defect 3 (topic wildcard) ──┬────── Defect 7 (ack before work)
                            └────── nothing reaches the audit listener until 3 is fixed

Defect 4 (requeue loop) ──────────── Defect 6 (dead-letter key)
                                     nothing is dead-lettered until 4 stops requeueing
```

Defects 1 and 2 both stop at five, for unrelated reasons — that coincidence is the intended trap.

## Test baseline

`mvn test` with all seven defects present: **6 tests run, 1 passes, 2 fail, 3 error.**

| Test | Result | Defect |
|---|---|---|
| `aBatchCanBeSubmitted` | passes | — |
| `everyStandardInvoiceIsProcessed` | timeout, `expected 15 but was 5` | 1 |
| `everyPriorityInvoiceIsProcessedByTheInvoiceProcessor` | timeout, `expected 8 but was 4` | 2 |
| `everySubmittedInvoiceIsAudited` | timeout, `expected 4 but was 0` | 3 |
| `everyWorkQueueHasExactlyOneConsumer` | fails, broker reports 2 | 2 |
| `rejectedInvoicesCanReachTheDeadLetterQueue` | fails, nothing bound to `invoice.failed` | 6 |

Defects 4, 5 and 7 are **not** covered by a test, deliberately. The requeue loop is found by watching
the log and the CPU; the retry counter is found by reading a log line that never changes; the
acknowledgement ordering is found by reading the listener and asking what happens if the next line
throws. Not every production defect announces itself in a test suite.
