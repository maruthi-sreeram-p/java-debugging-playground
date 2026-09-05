# Debugging Guide — 16 · Order Event Pipeline

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

In every previous project, the thing that went wrong happened inside one process, and you could see
all of it. Here the message leaves your application, crosses a network, sits in a broker, and comes
back into a different thread. When it does not arrive, **nothing anywhere reports a failure**: the
`POST` returns `201`, the order is safely in the database, and the projection is just empty.

So the skill this project trains is a specific one:

> Prove where the message actually went. Do not infer it.

There are exactly three places to look, and you need all three:

| Question | How you answer it |
|---|---|
| Did the producer send it? | `kafka-console-consumer --from-beginning` on the topic |
| Which topics exist at all? | `kafka-topics --list` |
| Is the consumer reading, and how far behind? | `kafka-consumer-groups --describe --group ...` |

The README has these commands ready. Get them working before you change any code.

**This project is a chain.** Six of the seven defects sit one behind another, and each one produces a
completely different failure. When the error message changes, you have made progress — even if
`ordersProjected` is still `0`.

## Expected behaviour

1. The application starts.
2. `POST /api/orders` returns `201`.
3. Within a second or two, `ordersStored` and `ordersProjected` are equal.
4. Each projection row carries the customer name, item and amount — never null.
5. Events already on the topic when the application starts are processed, not skipped.

## How to reproduce

```bash
docker compose up -d
# wait for "(healthy)"
mvn clean package
java -jar target/order-events-1.0.0.jar 2>&1 | tee app.log
```

`mvn test` runs four tests. All four currently fail at context load, for the same reason as Symptom A.

---

## Known symptoms

### Symptom A — the application does not start

```
No group.id found in consumer config, container properties, or @KafkaListener annotation;
a group.id is required when group management is used.
```

The message is unusually direct: it tells you what is missing and lists the three places it could
have been set. Choose one and understand why a subscribing consumer cannot work without it.

### Symptom B — placing an order returns 500

```
POST /api/orders   ->  500
```

```
org.apache.kafka.common.errors.SerializationException:
  Can't convert value of class com.debuglab.orderevents.event.OrderPlacedEvent
  to class org.apache.kafka.common.serialization.StringSerializer ...
Caused by: java.lang.ClassCastException:
  class OrderPlacedEvent cannot be cast to class java.lang.String
```

Read the exception's two halves together: it names the object being sent and the serialiser being
asked to send it.

### Symptom C — the order is stored, and the message vanishes

```
POST /api/orders   ->  201
GET  /api/health/kafka
{ "producerTopic": "order-events", "ordersStored": 1, "ordersProjected": 0 }
```

The log shows `PUBLISHED order ORD-... to topic 'order-events'` and never a matching `RECEIVED`.
No error, anywhere.

Now list the topics on the broker:

```
__consumer_offsets
order-events
order.events
```

**Three topics.** Two of them look like the same name. Kafka created both, because a producer or a
consumer asked for each — and it will happily create a topic for a name nobody meant to type.

### Symptom D — the consumer rejects every message

Once the topics agree, the consumer starts failing on every record:

```
... is not in the trusted packages: [java.util, java.lang]. If you believe this class is safe to
deserialize, please provide its name. If the serialization is only done by a trusted source, you can
also enable trust all ...
```

The projection stays empty. The message is on the topic — you can read it with the console consumer
— and the consumer will not accept it.

Note the phrase "if you believe this class is safe". That is a hint about *why* this restriction
exists, and it is worth understanding before you switch it off.

### Symptom E — the consumer rejects every message, differently

Clear Symptom D and the error changes:

```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException:
  Java 8 date/time type `java.time.LocalDateTime` not supported by default:
  add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" to enable handling
```

The module is already on the classpath — the rest of the application serialises `LocalDateTime`
perfectly well over HTTP. Something in the Kafka path is not using it.

### Symptom F — the projection arrives with no details

```
GET /api/projections/orders

[ { "id": 1, "orderNumber": "ORD-37AF1D6B",
    "customerName": null, "item": null, "amount": null,
    "projectedAt": "2026-02-01T10:00:00.107" } ]
```

```
WARN  c.d.o.service.OrderEventConsumer : No order row found for ORD-37AF1D6B - projecting without details
```

The order **is** in the database — `GET /api/orders` shows it, complete. The consumer looked for it,
by the right order number, and did not find it.

Think about *when* the consumer looked.

### Symptom G — a restart loses everything that was waiting

Stop the application. Publish two events straight to the topic with the console producer (or simply
place orders while the consumer is down). Start the application again with a consumer group that has
never run before — for example after `docker compose down -v`, or by changing the group name.

```
ordersProjected: 0
```

The messages are still on the topic; the console consumer reads them with `--from-beginning`
perfectly well. This consumer group ignored all of them.

---

## Investigation hints

### Symptom A — no group.id

> **Hint A1**
> The error lists three places the value could go. Find where this application configures its
> consumer — the Kafka setup here is explicit Java configuration, not `spring.kafka.*` properties.

> **Hint A2**
> Before you just add one: what is a consumer group *for*? Look up what Kafka uses it to track, and
> what would happen on a restart without it. That answer becomes important again at Symptom G.

### Symptom B — the serialisation failure

> **Hint B1**
> The exception names both sides of the mismatch. Find where the producer's value serialiser is
> configured and read what class it is set to.

> **Hint B2**
> The payload is an object that needs to become bytes. Spring for Apache Kafka ships a serialiser
> for exactly this case — find it, and note that its counterpart on the consumer side is already in
> use.

> **Hint B3**
> Once you have changed it, look at what the producer now puts on the wire — run the console
> consumer and read a raw message, headers included. There is something in there beyond the JSON that
> matters for Symptom D.

### Symptom C — the vanishing message

> **Hint C1**
> `kafka-topics --list` is the whole diagnosis. Count the topics and read the two similar names
> character by character.

> **Hint C2**
> Kafka auto-creates a topic when anyone asks for one that does not exist — so a typo does not fail,
> it *creates a new place for messages to go and be ignored*. Work out which component asked for
> each of the two.

> **Hint C3**
> One of them is a configuration property; the other is a literal in the code. Decide which should
> be the single source of truth, and make the other refer to it — a literal string in two places is
> how this happened.

> **Hint C4**
> Note that nothing failed. Consider whether you would want the application to *refuse to start* when
> its topic does not exist, and look up the listener property that controls that.

### Symptom D — the untrusted class

> **Hint D1**
> The deserialiser is being told the payload's type by something in the message. Find where that
> information comes from — you saw it in hint B3.

> **Hint D2**
> Deserialising an arbitrary class named by a remote message is how a number of serious
> vulnerabilities have worked. The restriction is a safety mechanism, and the fix is to tell it which
> packages you actually trust — not to disable it.

> **Hint D3**
> Find the method on the deserialiser that does this, and give it the narrowest package that
> contains your event type. If you find yourself typing `*`, stop and think about what you are
> allowing.

### Symptom E — the date type

> **Hint E1**
> The rest of the application handles `LocalDateTime` fine, so the capability exists. Compare the
> object mapper the HTTP layer uses with the one the Kafka deserialiser was handed.

> **Hint E2**
> A bare `new ObjectMapper()` knows nothing about Java 8 dates. Spring Boot configures the one it
> uses for HTTP; this one was constructed by hand.

> **Hint E3**
> Two ways out: register the module on the mapper you constructed, or stop constructing one and let
> the library build a properly configured mapper for you. Look at what Spring for Apache Kafka's own
> utilities offer before you write the code yourself.

### Symptom F — the projection with no details

> **Hint F1**
> The consumer looked the order up and did not find it. Then, moments later, the order is
> unmistakably there. So the question is about **ordering in time**, not about the lookup.

> **Hint F2**
> Read the method that places an order, line by line, in execution order. What does it do first?

> **Hint F3**
> Now add the transaction to your model. The method is transactional — so even after the save
> statement runs, when does the row become visible to a *different* connection?

> **Hint F4**
> Moving one line is enough to make the symptom go away almost every time. Be honest with yourself
> about whether "almost every time" is the same as "correct" — the consumer is a separate process
> racing your commit, and it can always win. What would make this genuinely safe rather than usually
> lucky? Look up the *transactional outbox* pattern, and note that you will meet this exact problem
> again, in a harder form, in project 20.

### Symptom G — the ignored backlog

> **Hint G1**
> The messages are on the topic. The group is connected. So the group has decided its reading
> position is the end of the topic rather than the start. What setting decides that, for a group that
> has never committed an offset?

> **Hint G2**
> Look up that setting's **default value**, and then look for it in the consumer configuration. It is
> not there — which means the default applies.

> **Hint G3**
> The two useful values are opposites, and picking one is a real decision, not a formality. One
> means "process everything that ever happened", the other means "only care from now on". For a
> projection that must reflect every order ever placed, which is correct? And what would the other one
> mean the first time this service is deployed?

> **Hint G4**
> Prove your fix: `kafka-consumer-groups --describe --group <your group>` should show `LAG` dropping
> to zero rather than the group starting at the end.

---

## Expected logs and observations

* Each defect has a distinct signature. Track which one you are on:

  | Log signature | Symptom |
  |---|---|
  | `No group.id found` | A, at start-up |
  | `SerializationException ... to class StringSerializer` | B |
  | `PUBLISHED` with no matching `RECEIVED`, and three topics | C |
  | `not in the trusted packages` | D |
  | `Java 8 date/time type ... not supported` | E |
  | `No order row found for ORD-...` | F |
  | no log at all; a new group with `ordersProjected: 0` | G |

* `PUBLISHED` and `RECEIVED` lines are the pipeline's two ends. A `PUBLISHED` with no `RECEIVED`
  within a second means the message did not arrive, or arrived and was rejected.
* Symptoms C and G produce **no error whatsoever**. They are silent losses, which is what makes
  asynchronous systems hard.
* The consumer retries a record it cannot deserialise, so symptoms D and E fill the log with the same
  stack trace repeatedly. That repetition is itself information — the record is not being skipped, it
  is stuck.
* `kafka-consumer-groups --describe` shows `CURRENT-OFFSET`, `LOG-END-OFFSET` and `LAG`. Lag that
  never decreases means the consumer is failing on the record it is holding.

## Difficulty

**Advanced.** Expect three to four hours. Seven defects.

Symptoms A → B → C → D → E form a strict chain: each is invisible until the one before it is
cleared, and each announces itself differently. Symptom F appears once messages finally flow.
Symptom G is independent and only shows up when a consumer group starts from scratch — so it will
survive everything else you do unless you go looking for it.

## Concepts being tested

* Consumer groups: what they are for and why a subscribing consumer requires one
* Key and value serialisers, and matching the producer to the consumer
* Topic naming, auto-creation, and how a typo becomes a silent black hole
* JSON type headers and trusted packages
* Object mapper configuration in a messaging path versus in the HTTP path
* `auto.offset.reset` and what a brand-new consumer group does with existing data
* Publishing an event before the transaction that produced it has committed
* Using the Kafka CLI to establish ground truth instead of inferring it

## When you think you are done

- [ ] `mvn test` is green (4 tests).
- [ ] `kafka-topics --list` shows exactly **one** order topic (plus `__consumer_offsets`). Run
      `docker compose down -v && docker compose up -d` first so the stray one is gone.
- [ ] `POST /api/orders` → within two seconds, `ordersStored` equals `ordersProjected`.
- [ ] Every projection row has a non-null `customerName`, `item` and `amount`.
- [ ] Place ten orders in a loop; all ten are projected, with details.
- [ ] Stop the application, place messages on the topic with the console producer, restart, and
      confirm they are all processed.
- [ ] `docker compose down -v && docker compose up -d`, publish some events with the console
      producer, **then** start the application for the first time — it must process them.
- [ ] `kafka-consumer-groups --describe --group <yours>` reports `LAG 0`.
- [ ] You can name which exception each of the five chained defects produced.

Then say **"I think I fixed the project"**.
