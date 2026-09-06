# Debugging Guide — 20 · E-Commerce Checkout Platform

> Symptoms and graded hints. No answers, no file names, no line numbers.
> This is the last project in the laboratory. Take your time with it.

---

## Project objective

Everything the previous nineteen projects covered, in one service, with the failures that only
appear where the layers meet: a transaction that also publishes an event, a cache that is read on
the way into a money calculation, and authorisation sitting on top of authentication.

Read this before you start, because it is the shape of the whole project:

> **Almost nothing here fails loudly.** The API says `201 Created` and the order does not exist. The
> API says `422 Declined` and the order shipped. Three different components report three different
> prices for the same product, all at the same second, and every one of them is internally
> consistent. The thing you are debugging is almost never the thing that reported the error.

Work from **state**, not from status codes. After every experiment, look at four places:

```bash
docker exec debuglab20-mysql mysql -ucheckoutuser -pcheckoutpw checkoutdb -e "SELECT sku,price,stock FROM product; SELECT order_ref,sku,quantity,amount,status FROM customer_order ORDER BY id DESC LIMIT 5; SELECT order_ref,state FROM fulfilment ORDER BY id DESC LIMIT 5;"
```

```bash
docker exec debuglab20-redis redis-cli KEYS '*'
```

```bash
MSYS_NO_PATHCONV=1 docker exec debuglab20-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
```

```bash
curl -s localhost:8080/api/admin/report -H "Authorization: Bearer $ADMIN"
```

## Expected behaviour

Everything in the README's **Expected functionality** section. In particular: a failed checkout
leaves nothing behind, a successful one is priced at the price on display, stock never goes
negative, and every order reaches all three consumers.

## How to reproduce

```bash
docker compose down -v && docker compose up -d
mvn clean package
java -jar target/checkout-platform-1.0.0.jar > app.log 2>&1 &
```

```bash
tok() { curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"Secret123!\"}" | python -c "import sys,json;print(json.load(sys.stdin)['token'])"; }
TOKEN=$(tok arjun); ADMIN=$(tok priya)
```

`mvn test` runs thirteen tests: three pass, ten do not.

---

## Known symptoms

### Symptom A — a token that expires while you are still typing

Sign in, and the response says:

```json
{ "role": "CUSTOMER", "expiresInSeconds": 1800 }
```

Call an endpoint straight away: `200`. Go and read some code for a minute, call it again:

```
401
```

Sign in again and it works again. This will happen to you constantly while you debug everything
else, so deal with it first — and note that once you have, **a `401` becomes meaningful evidence**
instead of background noise.

### Symptom B — a customer can read the management report

```bash
curl -s localhost:8080/api/admin/report -H "Authorization: Bearer $TOKEN"
```

`arjun` is a `CUSTOMER`. The response is `200` and contains the whole report.

```json
{"orders":6,"revenue":0.00,"fulfilments":0,"notifications":0,"analyticsOrdersSeen":0}
```

Two things are wrong in that one line. The access control is one of them. The other is in the
numbers: six orders, and revenue `0.00`.

### Symptom C — the administrator is refused

*Visible once Symptom B is fixed.*

`priya` is an `ADMIN`, signs in successfully, receives a token, and can reach ordinary endpoints
with it — `/api/orders/mine` returns `200`. The same token on `/api/admin/report` returns:

```
403
```

Authentication is working. Authorisation is not. That is a much narrower problem than it looks.

### Symptom D — orders that exist but are worth nothing

```bash
curl -s localhost:8080/api/orders/ORD-20250901-0003 -H "Authorization: Bearer $TOKEN"
```

```json
{ "orderRef": "ORD-20250901-0003", "sku": "SKU-1005", "quantity": 1, "amount": null, ... }
```

The database disagrees:

```bash
docker exec debuglab20-mysql mysql -ucheckoutuser -pcheckoutpw checkoutdb -e "SELECT order_ref, total_amount FROM customer_order WHERE order_ref='ORD-20250901-0003';"
```

```
order_ref            total_amount
ORD-20250901-0003    31990.00
```

The value is in MySQL. The API returns `null`, and the report says the revenue is `0.00`. Nothing is
logged. New orders placed through the API are fine — it is only the six migrated ones that are
empty.

Start with `SHOW COLUMNS FROM customer_order;` and count them.

### Symptom E — one product, three prices, all at once

```bash
curl -s -X PUT localhost:8080/api/admin/products/SKU-1001/price -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"price":4499.00}'
```

Then ask the three places that know the price:

```
MySQL                        4499.00     (the write worked)
GET /api/catalogue           3299.00     (the price before this one)
GET /api/catalogue/SKU-1001  2499.00     (the price it started with)
```

And then buy one:

```bash
curl -s -X POST localhost:8080/api/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1001","quantity":1,"cardToken":"tok_visa_4242"}'
```

```json
{ "amount": 2499.00 }
```

The customer is charged the oldest of the three. This is not one problem: the list and the single
product are stale **for different reasons**, and one of those reasons is why the money is wrong.

### Symptom F — five chairs from a shelf of three

`SKU-1006` starts with `stock: 3`. Buy one at a time:

```
order 1 -> 201   MySQL stock 2
order 2 -> 201   MySQL stock 1
order 3 -> 201   MySQL stock 0
order 4 -> 201   MySQL stock -1
order 5 -> 201   MySQL stock -2
```

```bash
curl -s localhost:8080/api/catalogue/SKU-1006
```

```json
{ "stock": 3 }
```

The stock check never fails, because the number it checks never changes.

### Symptom G — a business rule reported as a server crash

```bash
curl -s -X POST localhost:8080/api/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1006","quantity":500,"cardToken":"tok_visa_4242"}'
```

```json
{ "status": 500, "error": "Internal Server Error", "message": "Only 3 units of SKU-1006 are available, 500 were requested" }
```

The message is a perfectly good business message. The status is not. The same happens for a rejected
card, which should be `402`.

But notice this, because it is the clue: an order above the review threshold **does** come back
correctly as `422`. One failure out of three reports itself properly. Work out what is different
about that one and you have the defect.

### Symptom H — a declined payment that shipped anyway

```bash
curl -s -X POST localhost:8080/api/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1002","quantity":1,"cardToken":"card-1234"}'
```

The card token is invalid, so the payment provider rejects it and the API returns an error. Correct.
The order is not in `customer_order` — the transaction rolled back. Also correct.

Now look at the warehouse:

```bash
docker exec debuglab20-mysql mysql -ucheckoutuser -pcheckoutpw checkoutdb -e "SELECT order_ref, state FROM fulfilment ORDER BY id DESC LIMIT 3;"
```

```
order_ref               state
ORD-20260906-4F1A9C     RESERVED
```

There is a fulfilment record for an order that **does not exist and never existed**. The warehouse
has been told to pick and pack an order that was rolled back. `GET /api/orders/{ref}` for that
reference returns an error; `GET /api/orders/{ref}/fulfilment` returns `RESERVED`.

### Symptom I — a declined order that was fulfilled

```bash
curl -s -w " %{http_code}" -X POST localhost:8080/api/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1003","quantity":9,"cardToken":"tok_visa_4242"}'
```

```
{"status":422,"error":"Unprocessable Entity","path":"/api/checkout"} 422
```

₹1,12,491 is above the review threshold, so finance has to approve it by hand and the API correctly
says the order was not completed. Then:

```
order_ref               sku        quantity  amount      status
ORD-20260906-968FDB     SKU-1003   9         112491.00   FULFILLED
```

Stock went from 12 to 3. The customer was told "no" and nine pairs of headphones left the warehouse.

Symptom H and Symptom I are two different defects that look like one. In H the transaction rolled
back and something escaped anyway. In I the transaction **did not roll back at all**. Prove which is
which before you touch anything.

### Symptom J — an order the customer is never told about

Place a valid order and wait:

```json
{ "orders": 8, "fulfilments": 2, "notifications": 0, "analyticsOrdersSeen": 2 }
```

Fulfilment happens. Analytics counts it. The notification never arrives — and there is nothing in
the log from that consumer at all, not even an error. It started cleanly at boot.

```bash
MSYS_NO_PATHCONV=1 docker exec debuglab20-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
```

Read that output carefully — specifically the `CONSUMER-ID` column and which members have a
partition assigned. Then restart the application and run the whole thing again. It may be
**analytics** that goes quiet this time and notifications that work.

### Symptom K — a customer reading somebody else's order

```bash
curl -s localhost:8080/api/orders/ORD-20250901-0002 -H "Authorization: Bearer $TOKEN"
```

That order belongs to `meena`. `arjun`'s token reads it, in full, with `200`.

### Symptom L — an order for minus three laptop stands

```bash
curl -s -X POST localhost:8080/api/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1001","quantity":-3,"cardToken":"tok_visa_4242"}'
```

```json
{ "quantity": -3, "amount": -7497.00, "status": "PLACED" }
```

`201 Created`. The stock went **up** by three. The request object has a constraint on that field.

---

## Investigation hints

### Symptom A — the short-lived token

> **Hint A1**
> Paste the token into <https://jwt.io> (or decode the middle section with base64) and read `iat`
> and `exp`. Subtract them. Compare the answer with what the login response promised.

> **Hint A2**
> Find where the expiry is calculated and read the arithmetic one term at a time: the units of the
> configuration property, the units of the number it is multiplied by, and the units the `Date`
> constructor wants.

> **Hint A3**
> The advertised lifetime and the real lifetime are computed in two different places from the same
> property. When two derivations of one value disagree, the fix is usually to have only one.

### Symptoms B and C — the two halves of authorisation

> **Hint B1**
> List the matchers in the filter chain in the order they are written and, for the URL you are
> testing, find the **first** one that matches it. That is the only rule that will be applied.

> **Hint B2**
> `/api/admin/report` is matched by two of those rules. Ask which comes first, and then look up
> whether the framework evaluates matchers in order or by specificity. (It is one of the two, and
> the answer explains the whole symptom.)

> **Hint C1**
> *After Symptom B is fixed.* Turn on `logging.level.org.springframework.security=DEBUG` and place
> an authenticated request. Find the line that prints the authorities the request is carrying.

> **Hint C2**
> Compare the authority string on the request with the string the rule requires. Look up exactly
> what `hasRole("ADMIN")` checks for, and how it differs from `hasAuthority("ADMIN")`.

> **Hint C3**
> The authority comes from a claim in the token, and the claim is written when the token is issued.
> There are two places you could fix this — where the claim is written, or where it is read. Pick
> one deliberately, and be able to say why. (Hint: which of the two would still be correct if a
> different service issued the token?)

### Symptom D — the column that is not there

> **Hint D1**
> `SHOW COLUMNS FROM customer_order;` and count. There are two columns that mean "how much this
> order was worth".

> **Hint D2**
> One of them was created by `init/01-schema.sql`. Where did the other come from? Look at
> `spring.jpa.hibernate.ddl-auto` and read exactly what `update` does — and, more importantly, what
> it does not do. It adds what is missing. It never renames, never removes and never warns.

> **Hint D3**
> Now the real lesson. This is what schema drift looks like: an entity field name and a column name
> that stopped agreeing, with a setting that quietly papered over it. Decide what the right fix is —
> map the entity to the existing column, or migrate the data into the new one — and then ask why
> `ddl-auto=update` is a bad idea in any environment you care about, and what people use instead.

### Symptoms E and F — the cache

> **Hint E1**
> `docker exec debuglab20-redis redis-cli KEYS '*'` after each read. There are two caches with two
> different shapes of key. Write down which method fills each.

> **Hint E2**
> Find every method that writes a product to the database, and next to each write down which caches
> that write invalidates. There should be no gaps in that table. There are.

> **Hint E3**
> Look closely at the eviction on the price-update path — not whether it is there, but which cache
> it names and which key it removes. Then look at the cache it should have been clearing and ask
> what key that entry is actually stored under when the method that fills it takes no arguments.
> `redis-cli KEYS '*'` prints the answer.

> **Hint F1**
> The stock check reads through a cache. The stock write does not go anywhere near it. That is the
> whole of Symptom F.

> **Hint F2**
> Ask a design question rather than a code question: should a stock check — the thing that decides
> whether you can sell the last chair — be reading a value that may be ten minutes old at all? Some
> data can be cached; some must be read from the source of truth. Which is this, and why?

> **Hint F3**
> Even reading live, two simultaneous checkouts can both see "1 left" and both sell it. Look up
> optimistic locking (`@Version`) and the alternative of a conditional update
> (`UPDATE ... SET stock = stock - ? WHERE sku = ? AND stock >= ?`), and decide which this service
> should use. This is not a defect in the code you have; it is the next thing you would fix.

### Symptom G — the wrong status codes

> **Hint G1**
> Find where exceptions are turned into responses and read the type in the handler's signature.
> Then read the class hierarchy of the exceptions that come back as `500` and the one that comes
> back as `422`. One of them is not like the others.

> **Hint G2**
> Every one of those exception classes is annotated with the status it should produce. Look up which
> resolver applies that annotation, which resolver invokes a handler in an advice, and the order the
> framework tries them in. A handler that matches always wins over the annotation.

> **Hint G3**
> The fix is not to delete the catch-all — an unexpected exception should still produce a clean
> `500` rather than a stack trace. The fix is to make it catch only what nothing else handles.
> Consider handling the specific business exceptions explicitly, or narrowing the catch-all, and
> make sure a genuinely unexpected failure still gets logged with its stack trace.

### Symptoms H and I — the transaction and the event

> **Hint H1**
> Write down the seven numbered steps of the checkout from the README, and mark the exact point at
> which the database transaction commits. It is not where the event is published.

> **Hint H2**
> The event went to Kafka from inside a transaction that later rolled back. Kafka has no idea a
> transaction exists; a `send` is gone the moment it is made. So the consumer acted on an order that
> was never committed — and note the fulfilment row says the stock was what it had been *before* the
> checkout, which tells you the consumer read the database before the commit as well.

> **Hint H3**
> There are two standard answers and you should know both. The framework one:
> `@TransactionalEventListener(phase = AFTER_COMMIT)` — publish a Spring event inside the
> transaction and send to Kafka only once it has committed. The pattern one: the **transactional
> outbox** — write the event to a table in the same transaction, and have a separate process send
> what is in that table. Read about both and pick one for this service.

> **Hint I1**
> Different symptom, do not reuse the previous answer. Here the transaction did *not* roll back:
> both the order and the stock change are committed. Look at what kind of exception ends that
> request, and then look up the default rollback rule of `@Transactional`.

> **Hint I2**
> Spring rolls back on `RuntimeException` and `Error`. Not on checked exceptions — not by default.
> That is a deliberate choice inherited from EJB and it surprises everybody once. Confirm it by
> comparing the two declining paths: one exception type rolls back, the other does not.

> **Hint I3**
> Two fixes exist: declare `rollbackFor`, or make the exception unchecked. Before choosing, ask
> which failures in this service are *business outcomes* (the customer is told no, and nothing
> should be written) and which are *technical faults*. Then decide whether "needs manual approval"
> should even be modelled as an exception thrown from the middle of a transaction, or as a decision
> made before anything is written.

### Symptom J — the consumer that never receives anything

> **Hint J1**
> `kafka-consumer-groups.sh --describe --all-groups`. Count the groups, count the members in each,
> and count how many members have a partition assigned.

> **Hint J2**
> Find every `@KafkaListener` in the project and write down the topic and the group id of each. Two
> of them share something they should not.

> **Hint J3**
> A consumer group is a unit of *work sharing*: every partition is assigned to exactly one member,
> and a message is delivered to one member of each group. Two components that both need to see every
> message are two groups, not two members of one. With a single partition the loser gets nothing at
> all, and which one loses is decided by the rebalance — which is why a restart moves the problem.

> **Hint J4**
> Once fixed, ask what would have happened with three partitions instead of one. Would you have
> noticed this at all, or would you have seen "about a third of the notifications are missing" and
> gone looking for a database problem?

### Symptom K — reading other people's orders

> **Hint K1**
> Compare the two order endpoints. One of them derives what to return from the authenticated
> principal; the other takes an identifier from the URL and trusts it.

> **Hint K2**
> Being authenticated says who you are. It says nothing about which rows are yours. Look up
> "broken object level authorisation" — it is number one on the OWASP API Security Top 10 for a
> reason, and it is exactly this.

> **Hint K3**
> Decide where the check belongs: in the controller, in the service, or in the query. Then ask
> whether an `ADMIN` should be able to read any order, and make that a deliberate decision rather
> than an accident.

### Symptom L — the negative quantity

> **Hint L1**
> Read the request class. The constraint is there. Now read the controller method signature and ask
> what makes the framework actually evaluate that constraint.

> **Hint L2**
> Compare that method with the sign-in method, which does validate its body. The difference is one
> annotation, and nothing warns you when it is missing — the constraints simply become documentation.

> **Hint L3**
> Then ask the harder question: even with validation, should a service method trust that its caller
> validated? The order total and the stock movement were both computed from a number nobody checked.
> Where would you put the guard so that it holds no matter who calls?

---

## Expected logs and observations

* `PUBLISHED order ORD-... to checkout.orders` is logged by the producer whether or not the
  surrounding transaction ever commits. Its presence proves the line ran and nothing more.
* `FULFILMENT recorded for ORD-... (stock now N)` — compare `N` with the stock you expected after
  the checkout. When it is the value from *before* the checkout, the consumer read the database
  before the producing transaction committed.
* `ANALYTICS counted ...` and `NOTIFICATION sent for ...` — one of these two lines will never appear
  in a given run.
* `Request failed: ...` from the exception handler carries the real business message, under the
  wrong status.
* Nothing at all is logged for the stale cache, the missing validation or the missing ownership
  check. Those are visible only as state.
* `logging.level.org.springframework.security=DEBUG` prints the filter chain and the authorities on
  each request — the fastest way through Symptoms B and C.
* `logging.level.org.springframework.cache=TRACE` prints every cache hit, miss and eviction, which
  makes Symptoms E and F obvious in seconds.
* `logging.level.org.springframework.transaction.interceptor=TRACE` prints where transactions begin,
  commit and roll back. Use it on Symptoms H and I rather than guessing.

## Difficulty

**Expert.** Plan on an afternoon, or two. Twelve defects.

Dependencies:

```
Symptom B (matcher order) ────▶ Symptom C (authority prefix)
Symptom E (list eviction)  independent of  Symptom F (product cache), same file
Symptom H (event in the transaction)  and  Symptom I (rollback rules)  are separate defects
Symptom G (catch-all handler) masks the real status of H, and of the stock rule
Symptom A will interrupt every other experiment until you fix it
```

Suggested order: A first (it makes everything else bearable), then G (so failures start telling you
the truth), then B and C, then the state problems — D, E, F, K, L — and finally H, I and J, which
are the ones actually worth the afternoon.

## Concepts being tested

* Matcher evaluation order in a Spring Security filter chain, and first-match-wins semantics
* Roles vs authorities, and the `ROLE_` prefix convention
* JWT claims, expiry arithmetic and where a lifetime should be defined
* Object-level authorisation as distinct from authentication
* `@ExceptionHandler` precedence over `@ResponseStatus`, and status-code discipline
* `@Valid` and where request validation actually happens
* `ddl-auto=update`, schema drift, and why migrations exist
* Transaction boundaries: when a commit really happens
* `@Transactional` rollback rules for checked and unchecked exceptions
* Publishing events from inside a transaction; `@TransactionalEventListener` and the outbox pattern
* Cache coherence across every write path; cache keys for methods with and without arguments
* Reading a decision-critical value through a cache
* Kafka consumer groups, partition assignment and work sharing vs fan-out
* Reading state — MySQL, Redis and Kafka — instead of trusting an HTTP status

## When you think you are done

Reset first — `docker compose down -v && docker compose up -d` — so the seeded numbers are back.

- [ ] `mvn test` is green (13 tests).
- [ ] A token still works five minutes after it was issued, and `expiresInSeconds` is the truth.
- [ ] `priya` reaches `/api/admin/**`; `arjun` gets `403`; `arjun` gets `403` for `meena`'s order.
- [ ] `/api/admin/report` on the freshly seeded database shows `orders: 6` and `revenue: 94384.00`.
- [ ] Ordering more than the stock returns `409`; a bad card returns `402`; quantity `0` returns
      `400`; an order over ₹1,00,000 returns `422`.
- [ ] After a bad card: no new order, no new fulfilment row, stock unchanged, and nothing on the
      topic. Check the topic, do not assume.
- [ ] After an order over the threshold: no order row, stock unchanged, no fulfilment.
- [ ] Change a price, then immediately read `/api/catalogue` and `/api/catalogue/{sku}` and place an
      order. All three agree with MySQL.
- [ ] Buy `SKU-1006` until it runs out. The last one returns `409` and MySQL shows `0`, never
      negative.
- [ ] One order produces one fulfilment, one notification **and** one analytics count.
- [ ] Restart the application and repeat the previous check. Still all three.
- [ ] `grep -c "Internal Server Error" app.log` is `0` for the whole run.

Then say **"I think I fixed the project"**.
