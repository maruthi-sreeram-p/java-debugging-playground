# SOLUTION — 17 · Payment Settlement Consumer

> **Sealed answer key.** Seven planted defects.

---

## Defect 1 — `enable.auto.commit=true` with `AckMode.MANUAL`

* **Bug:** `consumerFactory()` sets `ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG` to `true`, while the
  listener container factory sets `ContainerProperties.AckMode.MANUAL`.
* **Affected component:** `config/KafkaConfig`
* **Root cause:** the two settings answer the same question differently. Auto-commit has the Kafka
  client commit offsets on a timer regardless of what the listener did; `MANUAL` hands the decision
  to the listener. Spring's container validates the combination and refuses to start.
* **Symptom:** `IllegalStateException: Consumer cannot be configured for auto commit for ackMode
  MANUAL`, thrown while starting the listener container.
* **Why it is a good gate:** the message is precise, and resolving it forces a real decision rather
  than a mechanical fix. For a payments consumer, auto-commit is wrong — offsets would advance five
  seconds after a poll whether or not the settlement was written, so a crash would lose payments
  silently.
* **Correct fix:**

  ```java
  config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
  ```

  and keep `AckMode.MANUAL`. A learner who instead removes the manual ack mode has made the code
  start, and has chosen at-most-once semantics for money — push back hard and make them justify it.
  (`AckMode.RECORD` or `BATCH` with auto-commit off is a defensible alternative that removes the need
  for Defect 3's fix; if they propose it deliberately, that is a good answer.)
* **Concept:** who owns offset commits; auto-commit versus manual acknowledgement.
* **Why a fresher makes it:** `enable.auto.commit=true` is Kafka's own default and appears in every
  example; `AckMode.MANUAL` is added later when someone reads about acknowledgement. Neither author
  sees the other's line.
* **How to recognise it in the wild:** the exception says exactly what is wrong. The transferable
  lesson is the question behind it — *when is this offset committed, and what has definitely happened
  by then?*

**Gates everything, including all four tests.**

---

## Defect 2 — `max.poll.interval.ms` is far too short for the work

* **Bug:**

  ```java
  config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
  config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 3000);
  ```

  while `SettlementConsumer` takes ~200 ms per record.
* **Affected component:** `config/KafkaConfig`
* **Root cause:** 50 records × 200 ms = **10 seconds** of processing between polls, against a
  3-second limit. The broker concludes the consumer is dead, revokes its partitions and rebalances.
  The in-flight records are redelivered — to another thread or later to the same one — and because
  the listener is slow, the new owner blows the same deadline. The group rebalances continuously and
  the same records are processed over and over.
* **Symptom (verified):** `publish?count=5` settles 5 payments cleanly. `publish?count=50` produced
  **242 settlement rows**. The log carries
  `consumer poll timeout has expired. This means the time between subsequent calls to poll() was
  longer than the configured max.poll.interval.ms`.
* **Why the symptom is misleading — this is the heart of the project:**
  * **Batch size changes the outcome.** Five messages take 1 second, comfortably inside the limit, so
    a developer testing by hand sees a perfect system. The defect needs a burst to appear, which
    means it appears in production and not in development.
  * The numbers vary from run to run, so it reads as flakiness or as a Kafka problem rather than a
    configuration error.
  * Nothing fails. Every `POST` returns `200`, the consumer reports itself healthy, and the only
    evidence is a `WARN` buried in a log full of successful `SETTLED` lines.
* **Correct fix — either, and the choice is worth discussing:**

  ```java
  config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);      // less work per poll
  // and/or
  config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);  // Kafka's own default: 5 minutes
  ```

  Reducing `max.poll.records` is the more robust answer, because it bounds the work per poll rather
  than betting that the work stays fast. Raising the interval alone means the same defect returns the
  day settlement gets slower. The best answer does both and says why.
* **Concept:** the poll loop; `max.poll.interval.ms` as a liveness deadline; rebalance storms.
* **Why a fresher makes it:** the two settings are configured in different places for different
  reasons and nobody multiplies them together. `max.poll.interval.ms=3000` also looks like a
  sensible, tidy number.
* **How to recognise it in the wild:** `consumer poll timeout has expired` is unambiguous. The
  arithmetic — records per poll × time per record versus the interval — should be done whenever
  either changes.

---

## Defect 3 — The idempotency key is made unique, so it never matches

* **Bug:**

  ```java
  if (processedMessageRepository.existsByMessageKey(event.getReference())) { return; }   // checks the reference
  ...
  processedMessageRepository.save(
          new ProcessedMessage(event.getReference() + "-" + System.nanoTime()));         // stores something else
  ```

* **Affected component:** `service/SettlementConsumer.onPayment`
* **Root cause:** the check looks up the payment reference; the write stores the reference with a
  nanosecond timestamp appended. The stored key can therefore never equal the key that is searched
  for, so the guard never fires and every redelivery settles the payment again.
* **Symptom:** 55 references settled more than once in a 50-message burst, visible through
  `GET /api/settlements/duplicates`.
* **Why the symptom is misleading:**
  * The idempotency mechanism is *present and visibly running*. The `processed_messages` table fills
    up, one row per processing attempt, so it looks like it is working.
  * `processedMarkers` in the health endpoint tracks `settlements` exactly, which reads as
    confirmation rather than as the tell it actually is — in a correct system the markers would equal
    the *distinct* payments, not the settlement rows.
  * It only matters when a redelivery happens, so it is invisible until Defect 2 causes one.
* **Why the mistake is plausible:** appending a timestamp is exactly what someone does after hitting
  a unique-constraint violation on that column. It makes the error go away and destroys the
  mechanism. Hint C6 asks them to work out the motive.
* **Correct fix:**

  ```java
  processedMessageRepository.save(new ProcessedMessage(event.getReference()));
  ```

  Then go further: add a unique constraint on `message_key` so a duplicate insert *fails loudly*
  instead of silently succeeding, and make the check-and-write one transaction. Credit both.
* **Concept:** idempotency keys; making at-least-once delivery safe.
* **How to recognise it in the wild:** an idempotency table whose row count matches the number of
  *attempts* rather than the number of *distinct items* is not deduplicating anything. Assert on the
  distinct count.

**Defects 2 and 3 are a pair.** Fixing 2 removes the redelivery in this scenario; fixing 3 makes
redelivery harmless. Only fixing 2 leaves a consumer that will duplicate the first time a rebalance
happens for any other reason — a broker restart, a deployment, a network blip. Make sure the learner
fixes both and can say why neither alone is enough.

---

## Defect 4 — The listener never acknowledges

* **Bug:** `onPayment(PaymentEvent event, Acknowledgment acknowledgment)` takes the `Acknowledgment`
  and never calls `acknowledgment.acknowledge()`.
* **Affected component:** `service/SettlementConsumer.onPayment`
* **Root cause:** under `AckMode.MANUAL`, offsets are committed only when the listener acknowledges.
  It never does, so the group's committed offset stays empty forever.
* **Symptom (verified):** `kafka-consumer-groups --describe` shows `CURRENT-OFFSET` as `-` on all
  three partitions after hundreds of records. Restarting the application and publishing **nothing**
  produced **325 settlements** — with `auto.offset.reset=earliest` and no committed offset, the group
  replayed the entire topic.
* **Why the symptom is misleading:**
  * Processing works perfectly while the application is running. Records are consumed, settlements
    are written, `LAG` looks like it is being worked through. Nothing indicates that Kafka's record
    of progress is empty.
  * The parameter is *there in the method signature*, which reads as evidence that acknowledgement
    was handled.
  * It only bites on restart — and in development, restarts usually come with a fresh broker, so the
    topic is empty and nothing is replayed. It surfaces the first time the service is restarted
    against a real topic.
* **Correct fix:**

  ```java
  settlementRepository.save(settlement);
  processedMessageRepository.save(new ProcessedMessage(event.getReference()));
  acknowledgment.acknowledge();
  ```

  **Placement is the interesting part (hint D3).** Acknowledging *after* the database writes means a
  crash in between causes a redelivery — which, with Defect 3 fixed, is harmless. Acknowledging
  *first* would mean a crash loses the payment entirely. For money, prefer the duplicate and rely on
  idempotency. Also make sure they do **not** acknowledge on the failure path — the record should be
  redelivered or dead-lettered, not silently committed.
* **Concept:** manual acknowledgement; commit ordering; at-least-once with idempotency versus
  at-most-once.
* **How to recognise it in the wild:** `CURRENT-OFFSET` of `-`, or lag that never falls, on a group
  that is visibly doing work. Check the consumer group's committed offsets after a deployment, always.

---

## Defect 5 — Unlimited retry attempts

* **Bug:**

  ```java
  factory.setCommonErrorHandler(
          new DefaultErrorHandler(new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
  ```

* **Affected component:** `config/KafkaConfig.kafkaListenerContainerFactory`
* **Root cause:** a record that can never succeed is retried forever, every two seconds. Kafka
  partitions preserve order and a consumer cannot skip a record, so that partition is blocked
  permanently.
* **Symptom (verified):** one unprocessable payment was retried 15 times and climbing. Three good
  payments published afterwards produced only **two** settlements — the third hashed to the blocked
  partition and was never reached.
* **Why the symptom is misleading — this is the brief's "consumer receives some messages but not
  others":**
  * It is **partial**. Two-thirds of the payments went through, so the consumer is obviously alive
    and working. Only the ones unlucky enough to share a partition with the poison record are
    affected.
  * Which payments are affected depends on key hashing, so it looks random and unreproducible.
  * `UNLIMITED_ATTEMPTS` reads as robustness — "never give up on a payment" is a defensible-sounding
    intention.
* **Correct fix:** bound the retries.

  ```java
  new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L))
  ```

  See Defect 6 for the `recoverer` — bounding the attempts alone is only half the fix.
* **Concept:** poison messages; head-of-line blocking; partition ordering.
* **How to recognise it in the wild:** one partition's lag growing while the others drain. The same
  offset and the same exception repeating in the log. Never configure unlimited retries on a
  consumer that must keep up.

---

## Defect 6 — No dead-letter destination for records that can never succeed

* **Bug:** the `DefaultErrorHandler` is constructed with no recoverer.
* **Affected component:** `config/KafkaConfig.kafkaListenerContainerFactory`
* **Root cause:** once retries are exhausted, `DefaultErrorHandler`'s default recoverer simply logs
  the failure and moves on. The record is committed and gone.
* **Symptom:** this defect is **invisible while Defect 5 stands** — with unlimited retries, the
  recoverer is never reached. The moment the learner bounds the attempts, the failed payment stops
  blocking the partition and is instead *silently discarded*. A payment has failed and there is no
  record of it anywhere except a log line that will roll over.
* **Why it is worth separating from Defect 5:** they are two distinct decisions — *how many times do
  we try?* and *what happens when we give up?* — and the obvious fix for Symptom E answers only the
  first. For a payments system, silently dropping a payment is arguably worse than blocking the
  partition, because at least the blockage is noisy. Hint E3 pushes the learner here.
* **Correct fix:**

  ```java
  @Bean
  public DefaultErrorHandler errorHandler(KafkaTemplate<String, PaymentEvent> template) {
      DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
      return new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));
  }
  ```

  `DeadLetterPublishingRecoverer` writes the failed record to `<topic>.DLT` by default, carrying
  headers with the original topic, partition, offset and exception. Ask the learner to confirm the
  record actually lands there:

  ```bash
  K /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
      --topic payment-events.DLT --from-beginning --timeout-ms 5000
  ```

  Adding an endpoint or an alert on the DLT is a further improvement worth crediting — a dead-letter
  topic nobody watches is only marginally better than a log line.
* **Concept:** dead-letter topics; retry exhaustion; observability of failures.
* **How to recognise it in the wild:** ask "where does a message go when it can never be processed?"
  If the answer is "the log", that is a defect. Every consumer that can fail needs a destination for
  failures and something watching it.

---

## Defect 7 — Consumer concurrency exceeds the partition count

* **Bug:** `app.kafka.consumer-concurrency=6` against `app.kafka.topic-partitions=3`.
* **Affected component:** `src/main/resources/application.properties` and
  `config/KafkaConfig.kafkaListenerContainerFactory`
* **Root cause:** within a consumer group, a partition is assigned to exactly one consumer. With
  three partitions, at most three consumers can have work; the other three are members of the group
  holding open connections and doing nothing.
* **Symptom (verified):** `--describe --members` shows `#PARTITIONS` of `1, 1, 1, 0, 0, 0`.
* **Why the symptom is misleading:** nothing is *wrong*. Throughput is correct, no message is lost or
  duplicated, and every test passes. It looks like the system is configured for more parallelism than
  it has, which reads as headroom rather than waste. The costs are real but indirect: six connections
  and six threads instead of three, and a slower, riskier rebalance every time membership changes —
  which, given Defect 2, is happening constantly.
* **Correct fix:** set the concurrency to at most the partition count.

  ```properties
  app.kafka.consumer-concurrency=3
  ```

  The important half is the reasoning (hint F3): if more parallelism is genuinely needed, the change
  is to the **topic**, not the application — and adding partitions to a keyed topic changes which
  partition a key lands on, which breaks per-key ordering for keys already in flight. That is a
  migration, not a config tweak.
* **Concept:** partition assignment; concurrency ceilings; the relationship between topic design and
  consumer scaling.
* **Why a fresher makes it:** concurrency looks like a throughput dial and 6 looks better than 3.
  Nothing warns you, because over-provisioning is legal.
* **How to recognise it in the wild:** `--describe --members` and look for `0` in the `#PARTITIONS`
  column. It should be part of any Kafka deployment review.

---

## Suggested fix order

1. **Defect 1** — the gate.
2. **Defect 4** — acknowledge, so offsets start moving. Verify with `--describe`.
3. **Defect 2** — stop the rebalance storm.
4. **Defect 3** — make redelivery harmless. Insist on this even after 2 is fixed.
5. **Defect 5** — bound the retries.
6. **Defect 6** — give the failures somewhere to go. Only visible after 5.
7. **Defect 7** — the concurrency.

The two pairs (2+3 and 5+6) are the point of the project. A learner who fixes 2 and stops has a
consumer that duplicates on the next unrelated rebalance; one who fixes 5 and stops has one that
silently loses payments. Ask about both.

## Test expectations

All four tests fail with `Failed to load ApplicationContext` until Defect 1 is fixed. Afterwards:

| Test | Fails because of |
|---|---|
| `everyConsumerThreadHasPartitionsToRead` | Defect 7 (a pure configuration assertion — no Kafka needed) |
| `aSmallBatchIsSettledExactlyOnce` | passes once the context loads |
| `aBurstIsSettledExactlyOnce` | Defects 2 and 3 |
| `anUnprocessablePaymentDoesNotBlockThePaymentsBehindIt` | Defects 5 and 6 |

Baseline: `Tests run: 4, Failures: 0, Errors: 4`.

**Defect 4 is not directly covered.** Its symptom needs a restart against a surviving topic, which no
in-process test can perform. It must be verified with `kafka-consumer-groups --describe` and a manual
restart — the checklist requires both.

Note that `aSmallBatchIsSettledExactlyOnce` passing while `aBurstIsSettledExactlyOnce` fails is the
single most informative line in the test output: same code, same path, different batch size.

## Verification commands

```bash
docker compose down -v && docker compose up -d     # clean broker, no offsets
mvn clean package && java -jar target/payment-settlement-1.0.0.jar > app.log 2>&1 &
mvn test        # 4/4 green

K() { MSYS_NO_PATHCONV=1 docker exec debuglab17-kafka "$@"; }
B=http://localhost:8080/api

for n in 5 50 200; do
  curl -s -o /dev/null -X POST "$B/payments/publish?count=$n"
done
sleep 30
curl -s $B/settlements/duplicates
#   distinctReferences == settlementRows == 255, duplicateReferences []

K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group settlement-processor
#   CURRENT-OFFSET numeric on every partition, LAG 0

K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --members --group settlement-processor
#   every consumer has at least one partition

# restart must re-settle nothing
kill %1 ; java -jar target/payment-settlement-1.0.0.jar > app2.log 2>&1 &
sleep 20
curl -s $B/health/consumer                          # settlements 0

# poison handling
curl -s -X POST $B/payments/unprocessable
curl -s -o /dev/null -X POST "$B/payments/publish?count=9"
sleep 20
curl -s $B/health/consumer                          # settlements 9
K /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic payment-events.DLT --from-beginning --timeout-ms 5000
#   the unprocessable payment is here
```
