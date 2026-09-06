# Debugging Guide — 18 · Shipment Notification Service

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Three event types travel the same pipeline: the same publisher class, the same exchange, the same
listener class, the same message shape. **All three fail, in three completely different ways, at the
same time.**

That is the whole design of this project. Once you can see all three side by side, the differences
between them tell you far more than any one of them would alone:

| Event | Where the message ends up |
|---|---|
| created | nowhere — no error |
| dispatched | nowhere — a 404 buried in the log |
| delivered | in its queue, unread |

RabbitMQ's defining behaviour, and the reason two of these are silent: **a message published with a
routing key that nothing is bound to is discarded by the broker, successfully, with no error to
anybody.** The publisher is told nothing. Unless you ask for it, that silence is the default.

**Open the management UI before you read any code.** <http://localhost:15673>, `shipuser` / `shippw`.

## Expected behaviour

One shipment taken through all three stages produces:

```json
{ "shipments": 1, "created": 1, "dispatched": 1, "delivered": 1 }
```

Every queue settles at `0` messages, and every queue has a consumer.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/shipment-notifications-1.0.0.jar > app.log 2>&1 &

TN=$(curl -s -X POST localhost:8080/api/shipments -H 'Content-Type: application/json' \
  -d '{"recipient":"Aarav Sharma","destination":"Bengaluru"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['trackingNumber'])")
curl -s -X POST "localhost:8080/api/shipments/$TN/dispatch"
curl -s -X POST "localhost:8080/api/shipments/$TN/deliver"
sleep 3
curl -s localhost:8080/api/notifications/summary
docker exec debuglab18-rabbitmq rabbitmqctl list_queues name messages consumers
```

`mvn test` runs five tests: one passes, four fail.

---

## Known symptoms

### Symptom A — every notification is missing, but the queues disagree about why

```
{ "shipments": 1, "created": 0, "dispatched": 0, "delivered": 0 }
```

```
name                       messages
shipment.created.queue     0
shipment.dispatched.queue  0
shipment.delivered.queue   1
```

Read that table carefully. It is three different problems:

* **created** — the queue is empty. The message was published and never arrived.
* **dispatched** — the queue is empty, for a different reason. Search the log for `404`.
* **delivered** — the message **is in the queue**. It arrived and nothing has read it.

Now add the consumer count:

```bash
docker exec debuglab18-rabbitmq rabbitmqctl list_queues name messages consumers
```

One of those queues has no consumer at all.

### Symptom B — a 404 in the log, from a publish that reported success

```
reply-code=404, reply-text=NOT_FOUND - no exchange 'shipment.exchange' in vhost '/',
class-id=60, method-id=40
```

The application logged `PUBLISHED dispatched event for SHP-...` immediately before this, and the
`POST` returned `200`. Compare the name in the error with the exchanges that actually exist:

```bash
docker exec debuglab18-rabbitmq rabbitmqctl list_exchanges name type
```

### Symptom C — a publish with no error at all

The created event produces no `404` and no warning of any kind. The log says `PUBLISHED created
event for SHP-...`, the exchange exists, and the queue stays empty.

```bash
docker exec debuglab18-rabbitmq rabbitmqctl list_bindings source_name routing_key destination_name
```

Compare the routing keys in that list against the keys the publisher actually uses. This is the
silent case, and it is the one that matters most — because in RabbitMQ this is *normal, documented
behaviour*, not a fault.

### Symptom D — a listener that cannot start

```
Failed to declare queue(s):[shipment.delivery.queue]
... NOT_FOUND - no queue 'shipment.delivery.queue' in vhost '/' ...
```

Repeated every few seconds, forever. Meanwhile `shipment.delivered.queue` has a message in it and no
consumer.

### Symptom E — the message arrives and the listener rejects it

*Visible once Symptom D is fixed.*

```
ListenerExecutionFailedException: Listener method could not be invoked with the incoming message
Caused by: org.springframework.amqp.support.converter.MessageConversionException:
  Cannot convert from [[B] to [com.debuglab.shipping.event.ShipmentEvent]
  for GenericMessage [payload=byte[150]...
```

`[B` is a byte array. The listener method expects a `ShipmentEvent` and is being handed raw bytes,
even though the publisher unmistakably sent JSON.

### Symptom F — the queues do not survive a broker restart

```bash
docker exec debuglab18-rabbitmq rabbitmqctl list_queues name
#   shipment.created.queue, shipment.dispatched.queue, shipment.delivered.queue

docker restart debuglab18-rabbitmq
# wait for it to come back, with the application stopped so it cannot re-declare them

docker exec debuglab18-rabbitmq rabbitmqctl list_queues name
#   (nothing)
```

Every queue, and everything waiting in it, is gone.

---

## Investigation hints

### Symptom B — the missing exchange

> **Hint B1**
> The error names the exchange it could not find. List the exchanges that exist and compare the two
> strings.

> **Hint B2**
> The configuration class defines the exchange name once, as a constant. Look at how each of the
> three publish methods refers to it. Two of them do the same thing; one does not.

> **Hint B3**
> Note what the `POST` returned while this was happening. A publish is asynchronous — the broker
> rejects it on the channel afterwards, so the caller has already been told everything went fine.
> Hold on to that observation; Symptom C is the same problem without even the error.

### Symptom C — the silent drop

> **Hint C1**
> The exchange exists this time and the message left the application. So it reached the exchange and
> the exchange decided it belonged nowhere.

> **Hint C2**
> List the bindings. Each one pairs a routing key with a queue. Now find the routing key the created
> event is published with, and look for it in that list.

> **Hint C3**
> The two strings differ by two characters. The constant for this key is defined in the same
> configuration class the binding uses — so the publisher is not using it.

> **Hint C4**
> Now the more important question: **why did nothing tell you?** A topic exchange dropping an
> unroutable message is normal behaviour, not an error. There are two facilities that would have
> made this visible — one asks the broker to confirm it accepted the message, the other asks it to
> hand back messages it could not route. Look up *publisher confirms* and the *mandatory* flag with a
> returns callback, and add them. They are one-line additions and they would have turned this
> twenty-minute hunt into a log line.

### Symptom D — the listener with nowhere to listen

> **Hint D1**
> The error names the queue it wants. Compare that with the queues the configuration declares.

> **Hint D2**
> Notice that the message is sitting safely in a queue that *does* exist. Nothing was lost — it is
> simply unread. That is a much better failure than Symptoms B and C, and it is worth noticing why:
> the broker keeps messages until someone takes them.

> **Hint D3**
> The queue names are constants in the configuration class. The listener annotations use string
> literals. Consider making the annotations reference the constants so the two cannot drift again.

### Symptom E — the byte array

> **Hint E1**
> Somewhere in the configuration a JSON message converter is defined as a bean. Trace who actually
> uses it.

> **Hint E2**
> The publishing side sets it explicitly. Look at the listener container factory in the same file
> and ask what converter it uses when nobody tells it.

> **Hint E3**
> The default converter hands your method the raw body. Both ends of a message pipeline need to
> agree on the format — setting it on one side only means the bytes go out as JSON and come back as
> bytes.

### Symptom F — the disappearing queues

> **Hint F1**
> `rabbitmqctl list_queues name durable` prints the flag directly. Then find where the queues are
> built in the configuration and read the builder method being called.

> **Hint F2**
> Look up what durability means for a queue, and then look up the separate setting that controls
> whether the *messages inside it* survive as well. They are two different things, and you need both.

> **Hint F3**
> Ask whether it matters for this service. If a notification is lost when the broker restarts, does
> a customer fail to hear that their parcel was delivered? That is the question that decides the
> setting — not a general rule.

---

## Expected logs and observations

* `PUBLISHED ...` is logged by the application whether or not the message went anywhere. It proves
  the code ran, and nothing else.
* `RECEIVED ...` is logged by the listener. Its absence is the symptom.
* Symptom B's `404` appears as a **channel shutdown**, not as an exception in the request thread —
  the `POST` has already returned `200` by then.
* Symptom C produces **no log output whatsoever**. There is nothing to grep for. It is only visible
  by comparing bindings against routing keys.
* The management UI's queue list is the fastest way to distinguish the three failures:
  `messages > 0, consumers = 0` is a consumer problem; `messages = 0` on a queue that should have
  received something is a routing problem.
* After a broker restart with the application running, Spring re-declares everything — which hides
  Symptom F. Stop the application first.

## Difficulty

**Advanced.** Expect two to three hours. Six defects.

Symptoms B, C, D and F are independent and can be attacked in any order. Symptom E hides behind D —
no message reaches a listener until the listener is on the right queue, so the converter problem
cannot appear until then.

The hardest part is not any individual defect. It is resisting the assumption that one explanation
covers all three event types, when in fact each one fails differently.

## Concepts being tested

* Exchanges, queues, bindings and routing keys, and which component owns each
* Topic exchanges silently discarding unroutable messages
* Publisher confirms and the `mandatory` flag with a returns callback
* Why a publish reports success before the broker has accepted it
* Message converters, and the need to configure both ends
* Listener containers and queue declaration
* Queue durability and message persistence
* Using the management UI and `rabbitmqctl` as ground truth

## When you think you are done

- [ ] `mvn test` is green (5 tests).
- [ ] One shipment through all three stages gives
      `{"shipments":1,"created":1,"dispatched":1,"delivered":1}`.
- [ ] Run five shipments through all three stages: `{"created":5,"dispatched":5,"delivered":5}`.
- [ ] `rabbitmqctl list_queues name messages consumers` — every queue at `0` messages with at least
      one consumer.
- [ ] `rabbitmqctl list_bindings` — every routing key the publisher uses appears as a binding.
- [ ] `rabbitmqctl list_exchanges` — the application publishes only to exchanges in this list.
- [ ] Publish to a deliberately wrong routing key (change one publish call temporarily) and confirm
      **your application now logs a warning about it**. Then change it back. If nothing is logged,
      you have not finished Symptom C.
- [ ] Stop the application, restart the broker, and confirm the queues are still there.
- [ ] `grep -c "404" app.log` is `0`.

Then say **"I think I fixed the project"**.
