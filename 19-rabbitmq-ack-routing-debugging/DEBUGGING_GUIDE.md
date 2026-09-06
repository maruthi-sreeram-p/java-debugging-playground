# Debugging Guide — 19 · Invoice Processing Pipeline

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Two batches of ten invoices go into the same pipeline through the same exchange. Neither comes out
right, and **they fail in opposite directions**:

* the standard tier processes five and then goes quiet for ever;
* the priority tier processes five and reports the other five as "counted" by something else.

Same number, two entirely different causes. That coincidence is deliberate, and if you assume one
explanation covers both you will spend an hour proving yourself wrong.

Meanwhile the audit trail is empty, and an invoice that cannot be processed does not get
dead-lettered — it is redelivered a few hundred times a second until you stop the application.

The one idea underneath all of it: **in manual-acknowledgement mode the broker does not care whether
your method returned.** A delivery is outstanding until you acknowledge it, and the broker's
behaviour — redelivery, prefetch, dead-lettering — is decided entirely by what you tell it on the
channel.

**Have the management UI open before you read any code.** <http://localhost:15673>,
`invoiceuser` / `invoicepw`. Watch the `Ready` / `Unacked` columns while you submit a batch.

## Expected behaviour

```bash
curl -s -X POST "localhost:8080/api/invoices/submit?tier=standard&count=10"
curl -s -X POST "localhost:8080/api/invoices/submit?tier=priority&count=10"
sleep 3
curl -s localhost:8080/api/pipeline/summary
```

```json
{ "processedStandard": 10, "processedPriority": 10, "processedRetry": 0,
  "audited": 20, "countedByMetrics": 10 }
```

Every work queue settles at zero messages with one consumer, and

```bash
curl -s -X POST localhost:8080/api/invoices/unprocessable
sleep 3
curl -s localhost:8080/api/invoices/dead-letter
```

returns exactly one invoice.

## How to reproduce

```bash
docker compose down -v && docker compose up -d
mvn clean package
java -jar target/invoice-pipeline-1.0.0.jar > app.log 2>&1 &
```

```bash
curl -s -X POST "localhost:8080/api/invoices/submit?tier=standard&count=10"
curl -s -X POST "localhost:8080/api/invoices/submit?tier=priority&count=10"
sleep 4
curl -s localhost:8080/api/pipeline/summary
curl -s localhost:8080/api/queues/stats
```

`mvn test` runs six tests: one passes, five do not.

**Warning about the unprocessable invoice.** Submitting it starts something that does not stop. Do
it last, keep an eye on the size of `app.log`, and stop the application when you have seen enough.

---

## Known symptoms

### Symptom A — the standard tier processes exactly five, then nothing

```json
{ "processedStandard": 5, "processedPriority": 5, "processedRetry": 0,
  "audited": 0, "countedByMetrics": 5 }
```

Submit ten more standard invoices and the count stays at five. Submit a hundred: still five.

```bash
docker exec debuglab19-rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged consumers
```

```
name                    messages  messages_ready  messages_unacknowledged  consumers
invoice.standard.queue  10        5               5                        1
invoice.priority.queue  0         0               0                        2
invoice.retry.queue     0         0               0                        1
invoice.audit.queue     0         0               0                        1
invoice.dead.queue      0         0               0                        0
```

The queue has a consumer. The consumer is connected, healthy and idle. Five messages are sitting in
`messages_unacknowledged` and will sit there until the application stops.

Note also that `/api/queues/stats` reports `messages: 5` for this queue while `rabbitmqctl` reports
`10`. Neither is wrong. Work out what each of them is counting; the answer is most of Symptom A.

Now stop the application and list the queues again. The five come back.

### Symptom B — the priority tier processes half and "counts" the other half

```
{ "processedPriority": 5, "countedByMetrics": 5 }
```

Ten in, five processed, five counted, nothing left in the queue and no error anywhere. Submit ten
more and it is five and five again. Look at the consumer count for that queue in the table above and
compare it with every other queue.

### Symptom C — the audit trail is empty and always has been

```bash
curl -s localhost:8080/api/invoices/audited
```

```json
[]
```

The audit queue exists. It has a consumer. It has never had a message in it — not one, for any tier,
since the project was written. There is no error in the log, because as far as the broker is
concerned nothing has gone wrong.

```bash
docker exec debuglab19-rabbitmq rabbitmqctl list_bindings source_name routing_key destination_name
```

### Symptom D — one bad invoice pins a CPU core

```bash
curl -s -X POST localhost:8080/api/invoices/unprocessable
```

```
RETRY received INV-04CD7B4A (x-retry-count header: 0, limit 3)
RETRY rejecting INV-04CD7B4A - amount is not positive
RETRY received INV-04CD7B4A (x-retry-count header: 0, limit 3)
RETRY rejecting INV-04CD7B4A - amount is not positive
RETRY received INV-04CD7B4A (x-retry-count header: 0, limit 3)
...
```

Measured on the reference machine: **2,179 redeliveries of one invoice in six seconds.** The
application is doing nothing else, the log grows by tens of megabytes a minute, and it will not stop
on its own.

Two separate things are wrong in that log extract. One is why the message comes back at all. The
other is printed on every single line and has been staring at you the whole time — the same number,
never changing, next to a limit that is never reached.

### Symptom E — the rejected invoice reaches the dead-letter queue and is never seen again

*Visible once Symptom D no longer requeues the message.*

The message leaves `invoice.retry.queue`:

```
invoice.retry.queue   0
invoice.dead.queue    0
```

Both are zero. It is not in the queue it came from, it is not in the queue it was supposed to go to,
and `/api/invoices/dead-letter` returns `[]`. The broker did exactly what it was configured to do and
the invoice no longer exists anywhere.

For an accounts-payable pipeline that is the most serious failure in this project: a supplier invoice
was accepted by the API and then silently destroyed.

### Symptom F — the audit trail records invoices it never processed

*Visible once Symptom C is fixed and audit messages start arriving.*

Force a failure in the audit listener — throw an exception in it, or stop the database — and watch
what happens to the message. It is gone from the queue. It is not in the dead-letter queue. It was
never written to `audited_invoice`. Nothing is retried, and nothing is logged as lost.

---

## Investigation hints

### Symptom A — the five that never finish

> **Hint A1**
> Five is not a coincidence and it is not the batch size. Find the number `5` in the configuration
> and read what it controls.

> **Hint A2**
> Look up what *prefetch* (`basic.qos`) means: the maximum number of **unacknowledged** deliveries
> the broker will allow a consumer to hold at once. Once a consumer is holding that many, the broker
> stops sending it anything until some are settled. So the question is not "why did it stop at five"
> — it is "why has nothing been settled".

> **Hint A3**
> Look at the acknowledgement mode the listener container factory is configured with, then read the
> four listener methods next to each other and compare what each does before it returns.

> **Hint A4**
> Two of the listeners take a `Channel` parameter and never use it. In automatic mode returning
> normally is enough. In manual mode nothing infers an acknowledgement from a method returning —
> that is the entire point of choosing it, and the compiler cannot tell you that you forgot.

> **Hint A5**
> When you fix it, think about *where* the acknowledgement belongs. If you acknowledge and then the
> database write throws, what have you told the broker? Symptom F is that same question from the
> other side.

### Symptom B — five processed, five counted

> **Hint B1**
> `rabbitmqctl list_queues name consumers`. One queue has two.

> **Hint B2**
> Search the project for every `@RabbitListener` annotation and list which queue each one is on. Two
> of them name the same queue.

> **Hint B3**
> Two consumers on one queue is *competing consumers* — a deliberate pattern for scaling a single job
> across several workers, where each message must be handled by exactly one of them and it does not
> matter which. Ask whether that is what these two classes are: are they two workers doing the same
> job, or two different jobs that both want to see every message?

> **Hint B4**
> If a component needs to observe every message that another component also processes, one queue
> cannot serve both. Look at what a topic exchange does when two queues are bound with the same
> routing key, and what that would mean for a dashboard that should count invoices without eating
> them.

### Symptom C — the audit queue nothing reaches

> **Hint C1**
> List the bindings and put the audit queue's routing pattern next to a routing key the publisher
> actually produces. Count the dots in each.

> **Hint C2**
> In a topic exchange the routing key is a list of words separated by dots, and the two wildcards do
> different things: one substitutes for **exactly one word**, the other for **zero or more words**.
> Only one of them can match a three-word key against a two-word pattern.

> **Hint C3**
> This is the same class of failure as an unroutable message: the exchange evaluated the pattern,
> found nothing matched, and dropped the message. Successfully. Nobody is told. If you want to know
> when it happens you have to ask the broker — look up the `mandatory` flag together with a returns
> callback, and publisher confirms.

### Symptom D — the invoice that will not die

> **Hint D1**
> Find the rejection call in the retry listener and look up its third parameter in the AMQP
> documentation. Then say out loud what the code is asking the broker to do with a message it has
> just declared it cannot process.

> **Hint D2**
> The broker is obeying you perfectly. It puts the message back at the head of the queue, the same
> consumer takes it, decides again that it cannot process it, and asks for it back again. There is no
> delay and no attempt limit in that loop, which is why it runs at thousands of iterations a second.

> **Hint D3**
> Now the second problem in the same log line. Every redelivery prints the same retry count, and the
> limit next to it is never reached. Find where that header is set, then search the whole project for
> anywhere that value is increased. Consider what would have to be true for a counter carried in a
> message header to survive a redelivery — who would have to write the new value, and when.

> **Hint D4**
> A header stamped once by the publisher and never touched again is not a retry counter, it is a
> constant. Work out what the sane alternatives are: `x-death` (which the broker maintains for you
> when a message is dead-lettered), a delayed retry queue, or Spring AMQP's own retry interceptor.
> Decide which fits this pipeline before you write anything.

### Symptom E — dead-lettered into nothing

> **Hint E1**
> Dead-lettering has two halves and they are declared in two different places: the queue says which
> exchange and which routing key to republish with, and a binding decides where that exchange sends
> it. Read both halves and compare the strings.

> **Hint E2**
> The dead-letter exchange here is a **direct** exchange, so the routing key must match a binding key
> exactly — no wildcards, no near misses. An exchange with nothing bound to the key it was given
> discards the message, exactly as in Symptom C.

> **Hint E3**
> Both strings are in the same configuration class, a few lines apart. One is a constant that the
> binding uses; the other is a literal, repeated on each queue. Ask why one of them was made a
> constant and the other was not, and whether the same thing could happen again in six months.

> **Hint E4**
> Once it is fixed, look at the dead-lettered message's headers in the management UI. The `x-death`
> header is added by the broker and records the reason, the original queue and **a count**. Compare
> that with what Hint D3 asked you to think about.

### Symptom F — acknowledged, then processed

> **Hint F1**
> Read the audit listener as a sequence of steps and write down the order. Then do the same for the
> priority listener. They do the same two things in the opposite order.

> **Hint F2**
> An acknowledgement is a promise that you are finished with the message. Once the broker has it,
> the message is deleted — it cannot be redelivered, and it cannot be dead-lettered. Everything after
> that line runs without a safety net.

> **Hint F3**
> This is at-least-once delivery: acknowledge after the work, and a crash means the message comes
> back and you may process it twice; acknowledge before, and a crash means it is gone. You cannot
> have both, so decide which risk this pipeline should carry — and if the answer is "process twice",
> what would make that safe?

---

## Expected logs and observations

* `RETRY received ... (x-retry-count header: 0, limit 3)` — the header value and the limit are both
  printed on purpose. Read them.
* Symptom A produces **no log output at all** once it stalls. There is nothing to grep for; the
  evidence is entirely in the broker's `messages_unacknowledged` column.
* Symptom C likewise produces nothing. A topic exchange with no matching binding is not an error.
* Stopping the application makes unacknowledged messages `Ready` again — which means the fastest way
  to see how many were in flight is to stop it and diff the queue table.
* Restart the application after a stall and the same five messages are delivered again, from the
  start. With an in-memory database you cannot see the consequence; against a real one you would
  find duplicate rows. That is not a defect, it is what at-least-once delivery means, and it is worth
  understanding before you fix Symptom F.
* `/api/queues/stats` reports **ready** messages only. `rabbitmqctl list_queues name messages`
  reports ready **plus** unacknowledged. Two numbers that disagree are the most useful instrument in
  this project.

## Difficulty

**Expert.** Expect two to three hours. Seven defects.

Symptoms A, B, C and D are independent — attack them in any order. Symptom E hides behind D: nothing
is dead-lettered while the rejection asks for the message back, so the routing mismatch cannot show
itself. Symptom F hides behind C: the audit listener has never received a message, so the order of
its two statements has never mattered.

The trap is Symptom A and Symptom B both stopping at five. They have nothing to do with each other.

## Concepts being tested

* Manual acknowledgement: `basicAck`, `basicNack`, `basicReject`, and the delivery tag
* Prefetch (`basic.qos`) and the unacknowledged-message limit as back-pressure
* Ready vs unacknowledged vs total, and what each tool reports
* Requeue loops and why an unbounded one is a production outage
* Dead-letter exchanges, dead-letter routing keys, and the `x-death` header
* Direct vs topic exchanges, and the `*` and `#` wildcards
* Competing consumers vs publish/subscribe, and choosing between them
* At-least-once delivery, acknowledgement ordering, and idempotency
* Retry counting: what a message header can and cannot carry across a redelivery
* Using the management UI and `rabbitmqctl` as ground truth about your own application

## When you think you are done

- [ ] `mvn test` is green (6 tests).
- [ ] Ten standard plus ten priority invoices give
      `{"processedStandard":10,"processedPriority":10,"audited":20,"countedByMetrics":10}`.
- [ ] Do it four more times without restarting. The counts keep going up in tens.
- [ ] `rabbitmqctl list_queues name messages messages_unacknowledged consumers` — every work queue at
      zero and zero, with exactly one consumer each.
- [ ] Submit one unprocessable invoice. The log shows it rejected a **small, bounded** number of
      times, and `/api/invoices/dead-letter` returns exactly that invoice.
- [ ] `grep -c "RETRY received" app.log` is a number you would be happy to see in production.
- [ ] Inspect the dead-lettered message's `x-death` header in the management UI and confirm it says
      what you expect.
- [ ] Make the audit listener throw on purpose. Confirm the message is not lost — it is redelivered
      or dead-lettered, not discarded. Then take the exception out.
- [ ] Submit a batch, kill the application mid-flight (`kill -9`), restart it, and confirm every
      invoice is accounted for.

Then say **"I think I fixed the project"**.
