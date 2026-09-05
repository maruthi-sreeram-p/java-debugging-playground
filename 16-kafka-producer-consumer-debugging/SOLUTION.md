# SOLUTION — 16 · Order Event Pipeline

> **Sealed answer key.** Seven planted defects.

---

## Defect 1 — No `group.id` on the consumer

* **Bug:** `KafkaConfig.consumerFactory()` sets `BOOTSTRAP_SERVERS_CONFIG` and
  `KEY_DESERIALIZER_CLASS_CONFIG` but never `ConsumerConfig.GROUP_ID_CONFIG`, and the
  `@KafkaListener` has no `groupId` attribute.
* **Affected component:** `config/KafkaConfig.consumerFactory`
* **Root cause:** a consumer that *subscribes* (rather than assigning partitions manually) takes part
  in Kafka's group-management protocol, which identifies it by group id. Without one there is nothing
  to assign partitions to and nowhere to commit offsets, so the listener container refuses to start.
* **Symptom:** the application fails to start with
  `IllegalStateException: No group.id found in consumer config, container properties, or
  @KafkaListener annotation; a group.id is required when group management is used.`
* **Why it is a good opening defect:** the message is exemplary — it names the missing setting and
  the three places it could be supplied. It is here as a warm-up and to force the learner to find the
  explicit `KafkaConfig` class, since the rest of the project lives there.
* **Correct fix:**

  ```java
  config.put(ConsumerConfig.GROUP_ID_CONFIG, "order-projection");
  ```

  `@KafkaListener(topics = "...", groupId = "order-projection")` is equivalent. Externalising it to a
  property is better still. **Watch for one thing:** a learner who generates a unique group id per
  run (`UUID.randomUUID()`) has made every restart reprocess or skip everything — ask them what
  offsets that group would have.
* **Concept:** consumer groups; group management versus manual partition assignment; offset
  ownership.
* **Why a fresher makes it:** on a default Spring Boot setup `spring.kafka.consumer.group-id` in
  `application.properties` handles this, and they have never had to think about it. Moving to an
  explicit `ConsumerFactory` bean means every setting is now their responsibility, and this is the one
  they forget.
* **How to recognise it in the wild:** the error says exactly what to do. The lesson is the second
  half of hint A2 — understanding that the group id is the identity under which offsets are stored,
  which is why changing it is never a harmless act.

**Gates everything, including all four tests.**

---

## Defect 2 — The producer's value serialiser is `StringSerializer`

* **Bug:**

  ```java
  config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
  ```

  while `KafkaTemplate<String, OrderPlacedEvent>` is asked to send an `OrderPlacedEvent`.
* **Affected component:** `config/KafkaConfig.producerFactory`
* **Root cause:** the serialiser can only turn a `String` into bytes. Handed a POJO, it throws.
* **Symptom:** `POST /api/orders` → `500`, with
  `SerializationException: Can't convert value of class OrderPlacedEvent to class StringSerializer`
  and a `ClassCastException` beneath it.
* **Why the symptom is misleading — mildly, and worth noting:** the key serialiser on the line above
  *is* `StringSerializer` and is correct, because the key is the order number. Two adjacent lines, the
  same class name, one right and one wrong. The generic type on the `KafkaTemplate` says
  `OrderPlacedEvent` in plain sight and does not prevent the mismatch, because serialiser
  configuration is a runtime string, not a compile-time type.
* **Correct fix:**

  ```java
  config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
  ```

  (`org.springframework.kafka.support.serializer.JsonSerializer`.)
* **Concept:** key and value serialisers; the producer/consumer serialisation contract.
* **Why a fresher makes it:** copy-paste from the key line above, or a leftover from when the payload
  really was a string.
* **How to recognise it in the wild:** the exception names both the object and the serialiser. Always
  check that the serialiser on one side and the deserialiser on the other are a matched pair — this
  is the single most common Kafka configuration error there is.

**Note the knock-on:** while this defect stands, no message ever reaches the topic, so Defects 3–6
cannot be observed at all.

---

## Defect 3 — Producer and consumer use different topic names

* **Bug:** the producer reads its topic from `app.kafka.orders-topic=order-events`; the consumer
  hard-codes `@KafkaListener(topics = "order.events")`. A hyphen against a dot.
* **Affected component:** `service/OrderEventConsumer` versus
  `src/main/resources/application.properties`
* **Root cause:** two topic names, one of them a typo, and no single source of truth.
* **Symptom:** `POST` returns `201`, the log shows `PUBLISHED order ORD-... to topic 'order-events'`,
  no `RECEIVED` line ever appears, and `ordersProjected` stays `0`. **No error anywhere.**
* **Why the symptom is misleading — this is the "Kafka message appears to disappear" case from the
  brief:**
  * Kafka **auto-creates** topics on demand. The producer's write created `order-events`; the
    consumer's subscription created `order.events`. Neither failed, because neither was wrong from
    Kafka's point of view.
  * `missing-topics-fatal` defaults to `false` in Spring Kafka 3.x, so the listener does not complain
    about subscribing to an empty topic.
  * From inside the application both halves look healthy: the producer reports success, the consumer
    reports that it is running and idle.
  * The **only** way to see it is `kafka-topics --list`, which shows three topics where there should
    be two. That is why the guide insists on getting the CLI working first.
* **Correct fix:** make both refer to the same value:

  ```java
  @KafkaListener(topics = "${app.kafka.orders-topic}")
  ```

  A SpEL/property placeholder in the annotation is the idiomatic answer. Defining the topic name as a
  constant used by both sides is equally good. What must not survive is the same name typed twice.
* **Concept:** topic naming; auto-creation as a silent failure mode; configuration duplication.
* **Why a fresher makes it:** hyphen-versus-dot is a real house-style split (`order-events` versus
  `order.events` are both common conventions), and the producer's name lives in a properties file
  while the consumer's lives in Java. Nobody sees them side by side.
* **How to recognise it in the wild:** list the topics. An unexpected topic on a broker is almost
  always a typo somewhere. Consider setting
  `spring.kafka.listener.missing-topics-fatal=true` in development, and disabling broker-side
  auto-creation in production, so that this fails loudly instead of silently.

---

## Defect 4 — `JsonDeserializer` with no trusted packages

* **Bug:** `new JsonDeserializer<>(new ObjectMapper())` with no target type and no call to
  `addTrustedPackages(...)`.
* **Affected component:** `config/KafkaConfig.consumerFactory`
* **Root cause:** with no fixed target type, the deserialiser takes the payload type from the
  `__TypeId__` header the `JsonSerializer` adds. Instantiating a class named by a remote message is a
  well-known deserialisation attack vector, so `JsonDeserializer` refuses unless the package is
  explicitly trusted. The defaults are `java.util` and `java.lang` only.
* **Symptom:** every record fails with
  `... is not in the trusted packages: [java.util, java.lang]`, the projection stays empty, and the
  consumer retries the same record indefinitely — so the stack trace repeats forever in the log.
* **Why the symptom is misleading:** the message *is* on the topic and readable with the console
  consumer, so the producer is demonstrably fine. It is also easy to misread the retry loop as many
  different failures rather than one record being stuck.
* **Correct fix:**

  ```java
  valueDeserializer.addTrustedPackages("com.debuglab.orderevents.event");
  ```

  **Push back on `addTrustedPackages("*")` or `spring.json.trusted.packages=*`.** It works, it is what
  most search results suggest, and it re-opens exactly the hole the check exists to close. Hint D3
  warns about it; check whether they took the shortcut. The best answer avoids the question entirely
  by giving the deserialiser a fixed target type
  (`new JsonDeserializer<>(OrderPlacedEvent.class)`), so the header is never trusted for type
  resolution at all — credit that generously.
* **Concept:** JSON type headers; deserialisation security; trusted packages.
* **Why a fresher makes it:** it works instantly in every tutorial, because the tutorial's producer
  and consumer are the same application with the same package on both sides — and Spring Boot's
  property-based setup often has trusted packages configured already.
* **How to recognise it in the wild:** the error names the class and the trusted list. Fix it by
  narrowing what you trust, never by trusting everything.

---

## Defect 5 — A hand-constructed `ObjectMapper` with no `JavaTimeModule`

* **Bug:** `new JsonDeserializer<>(new ObjectMapper())` — a bare mapper, with the event carrying a
  `LocalDateTime placedAt`.
* **Affected component:** `config/KafkaConfig.consumerFactory`
* **Root cause:** `jackson-datatype-jsr310` is on the classpath and Spring Boot registers it on the
  mapper it builds for HTTP — but this mapper was constructed by hand, so it has no modules at all.
* **Symptom:** after Defect 4 is fixed, every record fails with
  `InvalidDefinitionException: Java 8 date/time type java.time.LocalDateTime not supported by
  default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"`.
* **Why the symptom is misleading:** the module is unmistakably present — the same field serialises
  perfectly over HTTP in the `POST` response two lines of code away. So "add the dependency", which
  is what the error says, is already done. The problem is not the classpath, it is *which mapper*.
  It is also the second consecutive failure on the same line of code, which makes it feel like the
  previous fix did not work.
* **Correct fix — either:**

  ```java
  new JsonDeserializer<>(new ObjectMapper().registerModule(new JavaTimeModule()))
  ```

  or, better, stop hand-building the mapper:

  ```java
  new JsonDeserializer<>(JacksonUtils.enhancedObjectMapper())
  ```

  Spring for Apache Kafka's `JacksonUtils.enhancedObjectMapper()` registers the well-known modules
  automatically — which is what the no-argument `JsonDeserializer` constructor uses, and why this
  defect required someone to explicitly pass a worse mapper. Injecting Spring Boot's configured
  `ObjectMapper` bean is also correct.
* **Concept:** Jackson module registration; the difference between the framework's mapper and one you
  construct.
* **Why a fresher makes it:** `new ObjectMapper()` looks like the obvious way to supply a mapper, and
  the failure mode depends on which types happen to be in the payload — an event without a date field
  would have worked fine.
* **How to recognise it in the wild:** any "type not supported by default" error where the module is
  clearly on the classpath means a hand-built mapper. Grep for `new ObjectMapper()`.

---

## Defect 6 — The event is published before the order is saved

* **Bug:**

  ```java
  @Transactional
  public OrderRecord placeOrder(PlaceOrderRequest request) {
      ...
      orderEventProducer.publish(event);       // published first
      ...
      OrderRecord saved = orderRecordRepository.save(order);   // saved second
      return saved;
  }
  ```

* **Affected component:** `service/OrderService.placeOrder`
* **Root cause:** two problems, one behind the other.
  1. **Statement order** — the event is published before `save()` is even called.
  2. **Transaction boundary** — even with the lines swapped, the row is not visible to any other
     connection until the transaction *commits*, which happens after the method returns. The consumer
     runs in a different process and can always win the race.
* **Symptom:** projection rows appear with `customerName`, `item` and `amount` all `null`, and the
  consumer logs `No order row found for ORD-... - projecting without details`. `GET /api/orders`
  shows the order, complete, the whole time.
* **Why the symptom is misleading:** the lookup is correct, the order number matches, and by the time
  a human checks, the row is unmistakably there. Everything about the code reads correctly unless you
  think about *when* each thing becomes visible to a different process. This is a genuine distributed
  race presented as a data bug.
* **Correct fix — accept the first, but make sure they understand the second:**
  1. **Move the publish after the save**, and ideally after the commit. In practice that means
     publishing from a transaction synchronisation:

     ```java
     TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
         @Override public void afterCommit() { orderEventProducer.publish(event); }
     });
     ```

     or a Spring `@TransactionalEventListener(phase = AFTER_COMMIT)`.

  2. **The honest answer is the transactional outbox.** Write the event to an `outbox` table *inside*
     the same transaction as the order, and have a separate poller publish it. That is the only
     approach that survives both the race and a crash between commit and publish. The guide's hint F4
     names it and tells them they will meet it again in project 20.

  A learner who just swaps the two lines has fixed the symptom in practice and should be asked: *what
  happens if the consumer is fast and your commit is slow?* If they can answer that, they have got it.
* **Concept:** transaction visibility across processes; publish-after-commit; dual writes and the
  outbox pattern.
* **Why a fresher makes it:** "tell everyone, then do the work" reads naturally, and in a
  single-process application with an in-memory event bus it would even work. Nothing about the code
  hints that another process is reading the same database concurrently.
* **How to recognise it in the wild:** a consumer that intermittently cannot find data the producer
  "just wrote". It is intermittent by nature — under light load the consumer is slow enough to lose
  the race, so it often only appears in production.

---

## Defect 7 — `auto.offset.reset` left at its default

* **Bug:** `ConsumerConfig.AUTO_OFFSET_RESET_CONFIG` is never set, so Kafka's default of `latest`
  applies.
* **Affected component:** `config/KafkaConfig.consumerFactory`
* **Root cause:** `auto.offset.reset` decides where a consumer group starts when it has **no
  committed offset** — a brand-new group, or one whose offsets have expired. `latest` means "start at
  the end", so everything already on the topic is skipped, permanently.
* **Symptom (verified):** stop the application, put messages on the topic, then start it with a group
  that has never run before. `ordersProjected: 0`, and the console consumer with `--from-beginning`
  reads those same messages perfectly well.
* **Why the symptom is misleading — and why it survives the other six fixes:**
  * It does **not** reproduce during normal development. Once the group has committed an offset,
    `auto.offset.reset` is irrelevant, so everything works for the rest of the session and the defect
    hides.
  * It reappears exactly when it hurts most: on a fresh environment, a new deployment, after
    `docker compose down -v`, or when someone renames the consumer group. So the first
    production deploy silently skips the entire backlog.
  * There is no error, no warning, and no log line. The consumer reports itself healthy and idle.
* **Correct fix:**

  ```java
  config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
  ```

  This is a **design decision, not a formality** (hint G3). `earliest` is right for a projection that
  must reflect every order ever placed. `latest` is right for something like a live metrics feed where
  history is worthless. Ask which they chose and why; the wrong reason with the right value is only
  half an answer.
* **Concept:** `auto.offset.reset`; committed offsets; what a new consumer group does with existing
  data.
* **Why a fresher makes it:** they never set it, because they never had a group without committed
  offsets while developing. Kafka's default is the less useful one for most application use cases.
* **How to recognise it in the wild:** always test with a fresh consumer group. `kafka-consumer-groups
  --describe` on a brand-new group tells you immediately whether it started at `0` or at the log end.

---

## Suggested fix order

Strictly in order, because each hides the next:

1. **Defect 1** — the application starts.
2. **Defect 2** — messages reach the topic.
3. **Defect 3** — the consumer subscribes to the right topic.
4. **Defect 4** — records are accepted.
5. **Defect 5** — records deserialise.
6. **Defect 6** — projections carry their details.

Then, independently:

7. **Defect 7** — verify with a brand-new consumer group after `docker compose down -v`.

The thing to check with the learner is that they tracked the *changing exception* rather than the
unchanging `ordersProjected: 0`. Ask them to recite the sequence: `No group.id` →
`SerializationException` → three topics and silence → `not in the trusted packages` →
`Java 8 date/time not supported` → `No order row found`.

## Test expectations

All four tests fail with `Failed to load ApplicationContext` until Defect 1 is fixed. Afterwards:

| Test | Fails because of |
|---|---|
| `anOrderCanBePlaced` | Defect 2 |
| `theOrderListReflectsWhatWasPlaced` | Defect 2 |
| `everyPlacedOrderReachesTheProjection` | Defects 3, 4, 5 |
| `theProjectionCarriesTheOrderDetails` | Defect 6 |

Baseline: `Tests run: 4, Failures: 0, Errors: 4`.

**Defect 7 is not covered by any test**, and cannot easily be — the test suite's consumer group has
committed offsets after the first run, which is precisely why the defect hides. It must be verified
by hand against a clean broker.

## Verification commands

```bash
docker compose down -v && docker compose up -d      # clean broker, no topics, no offsets
# wait for (healthy)
mvn clean package
K() { MSYS_NO_PATHCONV=1 docker exec debuglab16-kafka "$@"; }

# publish a backlog BEFORE the application has ever run - Defect 7's check
printf '{"orderNumber":"ORD-BACKLOG1","customerName":"Backlog One","item":"Cable","quantity":1,"amount":349.00,"placedAt":"2026-02-01T10:00:00"}\n' \
  | K /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-events

java -jar target/order-events-1.0.0.jar > app.log 2>&1 &
sleep 15
curl -s localhost:8080/api/health/kafka          # ordersProjected must already be 1

mvn test                                          # 4/4 green

for i in 1 2 3; do
  curl -s -o /dev/null -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
    -d '{"customerName":"Aarav Sharma","item":"Mechanical Keyboard","quantity":1,"amount":4499.00}'
done
sleep 3
curl -s localhost:8080/api/health/kafka          # ordersStored == ordersProjected
curl -s localhost:8080/api/projections/orders    # no nulls in customerName / item / amount

K /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
#   __consumer_offsets and exactly ONE order topic

K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group order-projection
#   LAG 0
```
