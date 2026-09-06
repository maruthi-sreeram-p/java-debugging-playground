# SOLUTION — 18 · Shipment Notification Service

> **Sealed answer key.** Six planted defects.

---

## The shape of this project

Three event types travel identical paths and fail three different ways **simultaneously**. That
contrast is the teaching device — the queue listing shows all three at once:

| Event | `messages` | `consumers` | Cause |
|---|---|---|---|
| created | 0 | 1 | Defect 2 — routing key nothing is bound to; silently discarded |
| dispatched | 0 | 1 | Defect 1 — published to a non-existent exchange; `404` on the channel |
| delivered | **1** | **0** | Defect 4 — the listener is on a queue that does not exist |

A learner who works out why those three rows differ has essentially solved the project.

---

## Defect 1 — The dispatch event is published to an exchange that does not exist

* **Bug:**

  ```java
  public void publishDispatched(ShipmentEvent event) {
      rabbitTemplate.convertAndSend("shipment.exchange", RabbitConfig.DISPATCHED_KEY, event);
  }
  ```

  The declared exchange is `shipping.exchange` (`RabbitConfig.EXCHANGE`). The other two publish
  methods use the constant; this one uses a string literal, and the literal is wrong.
* **Affected component:** `service/ShipmentEventPublisher.publishDispatched`
* **Root cause:** `shipment.exchange` was never declared. RabbitMQ rejects the publish with
  `404 NOT_FOUND` on the channel.
* **Symptom (verified):** `reply-code=404, reply-text=NOT_FOUND - no exchange 'shipment.exchange' in
  vhost '/', class-id=60, method-id=40`, while `rabbitmqctl list_exchanges` shows only
  `shipping.exchange`.
* **Why the symptom is misleading:**
  * **The `POST` returns `200` and the application logs `PUBLISHED dispatched event`.** A publish
    without confirms is fire-and-forget: `convertAndSend` returns as soon as the frame is written, so
    the caller is told everything succeeded before the broker has even looked at it.
  * The `404` arrives asynchronously as a *channel shutdown*, logged by the AMQP client rather than
    thrown into the request thread. It is easy to miss in a busy log and impossible to correlate with
    a specific request.
  * The name is plausible — `shipment.exchange` reads perfectly naturally alongside
    `shipment.created`, `shipment.dispatched` and so on. Only `shipping.exchange` is the odd one out,
    and it is the correct one.
* **Correct fix:**

  ```java
  rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.DISPATCHED_KEY, event);
  ```

  The constant exists and the other two methods already use it. The real fix is the rule: **never
  type an exchange, queue or routing key as a literal.**
* **Concept:** exchange declaration; asynchronous publish failures; constants over literals.
* **Why a fresher makes it:** they type the name from memory in one place, and the naming convention
  in this system genuinely is confusable (`shipping` for the exchange, `shipment` for everything
  else).
* **How to recognise it in the wild:** `list_exchanges` and compare. And note that this defect would
  have been reported instantly if publisher confirms were on — see Defect 3.

---

## Defect 2 — The created event uses a routing key nothing is bound to

* **Bug:**

  ```java
  rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "shipment.create", event);
  ```

  The binding uses `RabbitConfig.CREATED_KEY`, which is `shipment.created`. The literal is
  `shipment.create` — two characters short.
* **Affected component:** `service/ShipmentEventPublisher.publishCreated`
* **Root cause:** the exchange is correct and the message reaches it. No binding matches the routing
  key, so the topic exchange discards it.
* **Symptom:** `shipment.created.queue` has `0` messages and a healthy consumer. **There is no error,
  no warning and no log line anywhere.**
* **Why this is the most important defect in the project:**
  * Unlike Defect 1, there is not even a `404` to find. An unroutable message on a topic exchange is
    **normal, specified behaviour** — the broker did exactly what it is supposed to do.
  * The publisher logs `PUBLISHED created event for SHP-...` and the `POST` returns `201`.
  * Every component involved is individually healthy: the exchange exists, the queue exists, the
    binding exists, the consumer is attached. The message just does not match.
  * The only way to see it is to compare `list_bindings` against the key in the code — which means
    knowing to look.
* **Correct fix:**

  ```java
  rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.CREATED_KEY, event);
  ```

  **And fix Defect 3 as well** — otherwise the next routing-key typo is equally invisible.
* **Concept:** topic exchange routing; unroutable messages; bindings as the contract between
  publisher and consumer.
* **Why a fresher makes it:** singular/plural and past/present tense slips in routing keys are
  endemic (`shipment.create` vs `shipment.created`, `order.ship` vs `order.shipped`). Nothing checks
  them.
* **How to recognise it in the wild:** an empty queue with a healthy consumer means a routing
  problem, not a consumer problem. `list_bindings` is the diagnostic. Prevent it with constants —
  and detect it with confirms.

---

## Defect 3 — No publisher confirms, no `mandatory` flag, no returns callback

* **Bug:** `RabbitConfig.rabbitTemplate(...)` sets only the message converter. There is no
  `spring.rabbitmq.publisher-confirm-type`, no `setMandatory(true)`, and no `ReturnsCallback`.
* **Affected component:** `config/RabbitConfig.rabbitTemplate` and
  `src/main/resources/application.properties`
* **Root cause:** by default a publish is fire-and-forget. The application never learns that the
  broker rejected the message (Defect 1) or discarded it as unroutable (Defect 2).
* **Symptom:** this defect has no symptom of its own. It is the reason Defects 1 and 2 are silent —
  it is *the absence of a symptom*, which is exactly why it is worth planting.
* **Why it matters:** the two hardest defects in this project would each have been a one-line log
  entry with confirms and returns enabled. A learner who fixes 1 and 2 without adding these has
  fixed today's typos and left the next one just as invisible. Hint C4 pushes them here explicitly.
* **Correct fix:**

  ```properties
  spring.rabbitmq.publisher-confirm-type=correlated
  spring.rabbitmq.publisher-returns=true
  ```

  ```java
  @Bean
  public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
      RabbitTemplate template = new RabbitTemplate(connectionFactory);
      template.setMessageConverter(jsonMessageConverter());
      template.setMandatory(true);
      template.setReturnsCallback(returned ->
              log.error("UNROUTABLE: exchange={} routingKey={} reply={}",
                      returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
      template.setConfirmCallback((correlation, ack, cause) -> {
          if (!ack) {
              log.error("Broker did not confirm the publish: {}", cause);
          }
      });
      return template;
  }
  ```

  `mandatory` + `ReturnsCallback` catches Defect 2 (routed nowhere); the confirm callback catches
  Defect 1 (exchange rejected). Both are needed — they detect different failures.
* **Concept:** publisher confirms; the `mandatory` flag and returned messages; making silent
  infrastructure failures observable.
* **Why a fresher makes it:** none of it is on by default and nothing prompts for it. `convertAndSend`
  returning `void` reads like a guarantee.
* **How to recognise it in the wild:** ask "if this message were dropped, how would I know?" If the
  answer is "I wouldn't", that is the defect. Any service where message loss matters needs confirms.

---

## Defect 4 — A listener is bound to a queue that does not exist

* **Bug:**

  ```java
  @RabbitListener(queues = "shipment.delivery.queue")
  public void onDelivered(ShipmentEvent event) { ... }
  ```

  The declared queue is `shipment.delivered.queue` (`RabbitConfig.DELIVERED_QUEUE`).
* **Affected component:** `service/ShipmentNotificationListener.onDelivered`
* **Root cause:** the listener container tries a passive declaration of a queue that was never
  created, fails, and retries forever. No consumer is ever attached to the real queue.
* **Symptom (verified):** `Failed to declare queue(s):[shipment.delivery.queue]` repeating every few
  seconds, while `shipment.delivered.queue` shows `messages 1, consumers 0`.
* **Why the symptom is the *best* of the three:** **nothing is lost.** The message is sitting safely
  in the queue waiting for a consumer, and will be delivered the moment one attaches. Compare that
  with Defects 1 and 2, where the message is gone forever. This contrast — a broker holding a message
  for you versus a broker discarding it — is worth drawing out explicitly. It is the difference
  between a consumer outage and a data-loss incident.
* **Correct fix:**

  ```java
  @RabbitListener(queues = RabbitConfig.DELIVERED_QUEUE)
  ```

  Referencing the constants from all three listener annotations (they are `static final`, so they are
  valid annotation values) prevents the whole class of defect. Credit that over just fixing the
  string.
* **Concept:** listener queue declaration; `messages > 0, consumers = 0` as a diagnosis.
* **Why a fresher makes it:** `delivery` and `delivered` are both natural words for the same thing,
  and the literal is written in a different file from the declaration.
* **How to recognise it in the wild:** the queue list with a `consumers` column. Any queue with
  messages and no consumers is either a crashed listener or a name mismatch.

**Note on start-up behaviour:** `missingQueuesFatal` is set to `false` on the container factory so
the application starts and keeps retrying, rather than aborting the context. With the default
(`true`) the whole application fails to start — which is arguably safer in production but would have
turned this project into a single start-up failure rather than the three-way contrast it is built
around. Worth mentioning to the learner as a real configuration trade-off.

---

## Defect 5 — The listener container has no message converter

* **Bug:** `RabbitConfig.rabbitListenerContainerFactory(...)` sets the connection factory and prefetch
  and never calls `factory.setMessageConverter(jsonMessageConverter())` — while the `RabbitTemplate`
  bean directly above it does.
* **Affected component:** `config/RabbitConfig.rabbitListenerContainerFactory`
* **Root cause:** the publishing side serialises to JSON; the consuming side uses the default
  `SimpleMessageConverter`, which hands the listener the raw body.
* **Symptom:** `MessageConversionException: Cannot convert from [[B] to
  [com.debuglab.shipping.event.ShipmentEvent] for GenericMessage [payload=byte[150]...`
* **Why the symptom is misleading:**
  * The converter bean **exists and is visibly configured** a few lines above, on the template. It
    looks like the application has a JSON converter, because it does — on one side only.
  * `[B` is the JVM's notation for `byte[]`, which is not obvious to everyone.
  * It is invisible until Defect 4 is fixed. No message reaches a listener before then, so the
    converter is never exercised — the learner's correct fix to the queue name is what reveals it.
* **Correct fix:**

  ```java
  factory.setMessageConverter(jsonMessageConverter());
  ```

  Alternatively, declaring the `Jackson2JsonMessageConverter` as a `@Bean` and letting Spring Boot's
  auto-configuration wire it into both sides works — but note that this project defines its own
  container factory, which overrides that. Worth discussing.
* **Concept:** message converters; the need for both ends of a pipeline to agree on the format.
* **Why a fresher makes it:** they configure the template because that is where sending happens, and
  never think about the receiving side until it fails.
* **How to recognise it in the wild:** any conversion error mentioning `byte[]` means one end is not
  using the converter. Configure serialisation symmetrically, and test a full round trip.

---

## Defect 6 — Queues are declared non-durable

* **Bug:**

  ```java
  return QueueBuilder.nonDurable(CREATED_QUEUE).build();
  ```

  on all three queues.
* **Affected component:** `config/RabbitConfig`
* **Root cause:** a non-durable queue exists only in memory. It — and everything in it — is discarded
  when the broker restarts.
* **Symptom (verified):** with the application stopped so it cannot re-declare them,
  `docker restart debuglab18-rabbitmq` followed by `rabbitmqctl list_queues name` returns **nothing**.
  All three queues, and any messages waiting in them, are gone.
* **Why the symptom is misleading:**
  * It never appears during development. Spring re-declares the queues on every application start,
    so the moment you restart the app after restarting the broker, everything looks normal again —
    minus whatever was in the queues, which nobody notices.
  * `nonDurable` reads as a deliberate, informed choice rather than an oversight.
  * The failure needs a broker restart *without* an application restart to be visible, which is
    exactly what happens during a broker upgrade or a node failure in production.
* **Correct fix:**

  ```java
  return QueueBuilder.durable(CREATED_QUEUE).build();
  ```

  **Durability alone is not sufficient**, and hint F2 points at this: a durable queue survives a
  restart, but the messages inside it only survive if they are also *persistent*. Spring AMQP's
  `Jackson2JsonMessageConverter` sets `MessageDeliveryMode.PERSISTENT` by default, so this project is
  covered — but the learner should be able to say that queue durability and message persistence are
  two separate settings. Ask them.
* **Concept:** queue durability; message persistence; what survives a broker restart.
* **Why a fresher makes it:** `nonDurable` is a real method they chose from autocomplete, often
  because a tutorial used it for a throwaway example.
* **How to recognise it in the wild:** `rabbitmqctl list_queues name durable` and check every row.
  For any queue whose contents matter, both durability and persistence are required — and the honest
  question, per hint F3, is whether the contents matter. For customer notifications, they do.

---

## Suggested fix order

1. **Defect 4** — the queue name. It is the clearest, and it unlocks Defect 5.
2. **Defect 5** — the converter, which appears the moment 4 is fixed.
3. **Defect 1** — the exchange name; the `404` names it for you.
4. **Defect 3** — add confirms and returns **before** hunting Defect 2. With them on, Defect 2 stops
   being a mystery and becomes a log line. A learner who does this in this order has understood the
   project.
5. **Defect 2** — the routing key.
6. **Defect 6** — durability.

If the learner finds Defect 2 by inspection before adding confirms, that is fine — but still make
them add the confirms, and ask what would have happened if the typo had been in a system with fifty
routing keys.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `aShipmentCanBeCreated` | pass | — (the `POST` succeeds regardless; the message goes nowhere) |
| `everyQueueSurvivesABrokerRestart` | fail | Defect 6 |
| `theCreatedNotificationArrives` | error (timeout) | Defect 2 |
| `theDispatchedNotificationArrives` | error (timeout) | Defect 1 |
| `theDeliveredNotificationArrives` | error (timeout) | Defects 4, then 5 |

Baseline: `Tests run: 5, Failures: 1, Errors: 3`.

`aShipmentCanBeCreated` passing while `theCreatedNotificationArrives` times out is the single most
informative pair in the output: **the write path works and the message goes nowhere.**

**Defect 3 is not covered by any test**, and cannot easily be — it is the absence of an alarm, not a
behaviour. The guide's checklist covers it by asking the learner to deliberately introduce a bad
routing key and confirm their own application now complains about it.

## Verification commands

```bash
docker compose down -v && docker compose up -d
mvn clean package && java -jar target/shipment-notifications-1.0.0.jar > app.log 2>&1 &
mvn test        # 5/5 green

R() { docker exec debuglab18-rabbitmq "$@"; }
B=http://localhost:8080/api

for i in 1 2 3 4 5; do
  TN=$(curl -s -X POST $B/shipments -H 'Content-Type: application/json' \
    -d '{"recipient":"Aarav Sharma","destination":"Bengaluru"}' \
    | python -c "import sys,json;print(json.load(sys.stdin)['trackingNumber'])")
  curl -s -o /dev/null -X POST "$B/shipments/$TN/dispatch"
  curl -s -o /dev/null -X POST "$B/shipments/$TN/deliver"
done
sleep 3
curl -s $B/notifications/summary
#   {"shipments":5,"created":5,"dispatched":5,"delivered":5}

R rabbitmqctl list_queues name messages consumers
#   every queue: 0 messages, >= 1 consumer

R rabbitmqctl list_queues name durable         # all true
R rabbitmqctl list_bindings source_name routing_key destination_name
R rabbitmqctl list_exchanges name type

grep -c "404" app.log                          # 0

# durability check - application stopped first
kill %1
docker restart debuglab18-rabbitmq && sleep 25
R rabbitmqctl list_queues name                 # all three still present
```
