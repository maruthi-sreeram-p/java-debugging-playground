# Debugging Guide — 17 · Payment Settlement Consumer

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

This service settles payments twice. Sometimes four times. It depends how many arrive at once.

Kafka delivers **at least once**. Everything in this project is about the machinery that turns that
into *exactly* once — offsets, acknowledgement, idempotency, retry limits — and every piece of it is
mis-set in a way that is invisible until the system is under a little pressure.

The defining feature of this project is that **the size of the batch changes the outcome**. Five
payments settle perfectly. Fifty produce hundreds of settlement rows. If you only ever test with a
handful of messages, you will conclude the service works.

Two instruments:

* `kafka-consumer-groups --describe` — the group's committed offset and lag. This is ground truth
  about what Kafka thinks has been processed.
* `GET /api/settlements/duplicates` — ground truth about what actually happened to the money.

## Expected behaviour

1. The application starts.
2. `publish?count=N` produces exactly `N` settlement rows, for any `N`.
3. `duplicateReferences` is always empty.
4. A restart re-settles nothing.
5. `CURRENT-OFFSET` advances and `LAG` returns to `0`.
6. Every consumer thread has partitions.
7. An unprocessable payment is retried a bounded number of times, set aside, and does not delay the
   payments behind it.

## How to reproduce

```bash
docker compose down -v && docker compose up -d     # always start clean
# wait for (healthy)
mvn clean package
java -jar target/payment-settlement-1.0.0.jar > app.log 2>&1 &

K() { MSYS_NO_PATHCONV=1 docker exec debuglab17-kafka "$@"; }
```

`mvn test` runs four tests. All four currently fail at context load, for the reason in Symptom A.

---

## Known symptoms

### Symptom A — the application does not start

```
java.lang.IllegalStateException: Consumer cannot be configured for auto commit for ackMode MANUAL
    at ...KafkaMessageListenerContainer$ListenerConsumer.determineAutoCommit(...)
```

Two settings in the consumer configuration are giving contradictory instructions about who commits
offsets. Spring refuses to guess. Decide which of the two you actually want, and understand what the
other one would have done.

### Symptom B — a small batch works perfectly

```
POST /api/payments/publish?count=5
GET  /api/health/consumer     -> {"settlements":5,"processedMarkers":5}
GET  /api/settlements/duplicates -> {"distinctReferences":5,"settlementRows":5,"duplicateReferences":[]}
```

Five payments, five settlements, no duplicates. Everything looks correct.

This is not a symptom. It is here because it is what you will see if you test the way most people
test, and it is why the next symptom survives into production.

### Symptom C — a burst is settled many times over

```
POST /api/payments/publish?count=50
```

Wait twenty-five seconds:

```
{"settlements":242,"processedMarkers":242}
{"distinctReferences":188,"settlementRows":243,"duplicateReferences":[ ...55 references... ]}
```

Fifty payments produced **242 settlement rows**, with 55 references settled more than once. The exact
numbers vary from run to run.

In the log:

```
... consumer poll timeout has expired. This means the time between subsequent calls to poll() was
longer than the configured max.poll.interval.ms ...
```

Two separate things are wrong here. One causes the redelivery. The other is the reason the
redelivery is not harmless.

### Symptom D — offsets are never committed

```
K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group settlement-processor

GROUP                 TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
settlement-processor  payment-events  2          -               20              -
settlement-processor  payment-events  1          -               24              -
settlement-processor  payment-events  0          -               11              -
```

`CURRENT-OFFSET` is a dash on every partition, after hundreds of records have been processed. The
group has never committed anything.

Now restart the application. H2 is in-memory, so the settlement table starts empty. Publish
**nothing**:

```
{"settlements":325,"processedMarkers":326}
```

Three hundred and twenty-five payments settled, from a topic nobody wrote to. Every message ever
published was delivered again.

### Symptom E — one bad payment stops the ones behind it

From a clean broker:

```
POST /api/payments/unprocessable      -> {"reference":"PAY-BAD-593A75"}
```

Wait fifteen seconds and count how many times that reference appears in the log. It keeps climbing —
8, then 15, then more, for as long as the application runs. The log line is always the same.

Now publish three good payments:

```
POST /api/payments/publish?count=3
GET  /api/health/consumer     -> {"settlements":2,...}
```

Two of the three were settled. The third is behind the bad one and will never be reached.

### Symptom F — half the consumers do nothing

```
K /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --members --group settlement-processor

CONSUMER-ID                        CLIENT-ID                         #PARTITIONS
consumer-settlement-processor-1    consumer-settlement-processor-1   1
consumer-settlement-processor-2    consumer-settlement-processor-2   1
consumer-settlement-processor-3    consumer-settlement-processor-3   1
consumer-settlement-processor-4    consumer-settlement-processor-4   0
consumer-settlement-processor-5    consumer-settlement-processor-5   0
consumer-settlement-processor-6    consumer-settlement-processor-6   0
```

Six threads, three of them permanently idle. Nothing fails — this one costs memory, connections and
rebalance time rather than correctness. It is here because it is extremely common and because the
fix requires understanding *why*.

---

## Investigation hints

### Symptom A — the contradictory configuration

> **Hint A1**
> The message names both halves of the contradiction. Find them in the consumer configuration — one
> is a Kafka client property, the other a Spring listener-container setting.

> **Hint A2**
> Look up what each one means for offset commits: who decides *when* an offset is committed under
> each. Then decide which one this service wants, given that it is settling payments.

> **Hint A3**
> One of them commits offsets on a timer, whether or not your code succeeded. The other hands the
> decision to your listener. For a payment, which is acceptable?

### Symptom C — the burst that multiplies

> **Hint C1**
> Read the warning in the log completely — it names the setting and explains the rule. Then find that
> setting in the consumer configuration and find the sibling setting that controls how many records
> arrive in one poll.

> **Hint C2**
> Do the arithmetic. Settling one payment takes about 200 ms (there is a deliberate delay in the
> consumer). How many records come back in a single poll? How long will the listener therefore be
> away before it polls again? Compare that with the configured interval.

> **Hint C3**
> When a consumer takes too long, the broker assumes it has died and gives its partitions to someone
> else. The records it was working on are delivered again — to a different thread, or later to the
> same one. That is the redelivery.

> **Hint C4**
> There are two independent ways to fix the redelivery, and a good answer says which is appropriate:
> allow more time between polls, or take fewer records per poll. Think about which one degrades more
> gracefully when the work gets slower.

> **Hint C5**
> **Now the second half, and this is the more important one.** Redelivery is normal in Kafka — a
> rebalance, a restart, a network blip will always cause some. A correct consumer settles a payment
> once even when it receives it twice. There is a table and a check in this service for exactly that
> purpose, and they are not working. Compare the value the check looks for with the value that gets
> stored, character by character.

> **Hint C6**
> The stored value has something appended to it. Ask why anyone would have done that — there is a
> plausible motive — and what it costs.

### Symptom D — the offsets that never move

> **Hint D1**
> The acknowledgement mode you looked at in Symptom A hands the decision to your listener. Read the
> listener method. What is it given as its second parameter, and where does it use it?

> **Hint D2**
> It is never used. So nothing ever tells Kafka this record is done, and the group's position never
> advances.

> **Hint D3**
> **Where** you place that call matters as much as making it. Think about what happens if the process
> dies immediately after acknowledging but before the settlement is written, versus acknowledging
> after the write. One risks losing a payment, the other risks settling one twice. Given that you are
> also going to fix the idempotency check, which risk is the safe one to take?

> **Hint D4**
> Also decide what should happen on the failure path. If the listener throws, should the record be
> acknowledged? What does that mean for the message?

### Symptom E — the message that blocks everything

> **Hint E1**
> Find the error handler in the Kafka configuration and read the back-off it is given. Look up what
> the second argument means.

> **Hint E2**
> Kafka partitions preserve order. A consumer cannot skip a record and come back to it — so a record
> that never succeeds stops that partition permanently. That is why only *some* of the following
> payments got through: the ones that hashed to other partitions.

> **Hint E3**
> Fixing the attempt count is only half of it. Once the retries are exhausted, the default behaviour
> is to log the failure and move on — which means a payment has failed and nobody will ever know.
> For a payments system, decide where that record should go instead, and look up what Spring for
> Apache Kafka provides for it.

> **Hint E4**
> The phrase to search for is *dead letter topic*, and the class you want is a recoverer you hand to
> the error handler. Once it is in place, confirm the failed payment actually lands there.

### Symptom F — the idle consumers

> **Hint F1**
> Compare two numbers in `application.properties`: the listener concurrency and the topic's partition
> count.

> **Hint F2**
> Look up the rule for how partitions are shared among the members of a consumer group. Can two
> consumers in the same group read the same partition?

> **Hint F3**
> So the number of partitions is a hard ceiling on useful parallelism. Set the concurrency to
> something that makes sense, and note the other direction of the same rule: if you want more
> parallelism later, the change you need is to the *topic*, not the application — and increasing
> partitions on a keyed topic has its own consequences for ordering.

---

## Expected logs and observations

* `consumer poll timeout has expired` is Symptom C's smoking gun. It names the setting.
* The same reference appearing repeatedly with the same failure is Symptom E. Count the occurrences —
  if the number keeps growing, the retries are unbounded.
* `SETTLED <ref>` appearing twice for one reference is a duplicate settlement. Grep for it.
* `CURRENT-OFFSET` showing `-` means nothing has ever been committed. `LAG` that never falls means
  the consumer is not making progress.
* Symptoms C, D, E and F produce **no failed request**. Every `POST` returns `200`.
* Restart the application freely — H2 is in-memory, so the settlement table resets while the Kafka
  topic and the group's offsets persist. That asymmetry is what makes Symptom D visible.

## Difficulty

**Advanced → Expert.** Expect three to four hours. Seven defects.

Symptom A is a gate. After that, C, D, E and F are independent, but C is really two defects stacked
(one causes redelivery, the other lets redelivery cause damage) and E is also two (unbounded retries,
and no destination for a record that can never succeed). Fixing half of either pair leaves a system
that is still wrong in a quieter way.

## Concepts being tested

* At-least-once delivery, and where exactly-once processing actually comes from
* `enable.auto.commit` versus manual acknowledgement
* Where to acknowledge, and the trade-off between losing a message and duplicating one
* `max.poll.interval.ms`, `max.poll.records`, and the rebalance they cause together
* Idempotency keys, and how a well-meaning change destroys one
* Poison messages, partition ordering, and head-of-line blocking
* Bounded retries and dead-letter topics
* Consumer concurrency versus partition count

## When you think you are done

Start from a clean broker for every check: `docker compose down -v && docker compose up -d`.

- [ ] `mvn test` is green (4 tests).
- [ ] `publish?count=5` → exactly 5 settlements, no duplicates.
- [ ] `publish?count=50` → exactly 50 settlements, no duplicates. Run it three times.
- [ ] `publish?count=200` → exactly 200 settlements, no duplicates.
- [ ] `kafka-consumer-groups --describe` shows a numeric `CURRENT-OFFSET` that advances, and `LAG`
      returning to `0`.
- [ ] Restart the application and publish nothing: the settlement count stays at `0` — nothing is
      redelivered and re-settled.
- [ ] `--describe --members` shows every consumer with at least one partition.
- [ ] Publish an unprocessable payment, then 9 good ones: all 9 are settled within seconds, and the
      bad one has been retried a small, bounded number of times and set aside where you can find it.
- [ ] `grep -c "SETTLED" app.log` equals the number of distinct payments you published.

Then say **"I think I fixed the project"**.
