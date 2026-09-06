# SOLUTION — 20 · E-Commerce Checkout Platform

> Reference copy. Do not read this until you have finished, or until you have asked for the solution
> explicitly.

Twelve defects.

| # | Symptom | Area |
|---|---|---|
| 1 | A customer can read the admin report | Security matcher order |
| 2 | The administrator is refused (`403`) | JWT role claim without the `ROLE_` prefix |
| 3 | Tokens die after 30 seconds | Expiry arithmetic in the wrong unit |
| 4 | Business rules come back as `500` | Catch-all `@ExceptionHandler` |
| 5 | Negative quantities accepted | Missing `@Valid` |
| 6 | A declined order is committed and shipped | `@Transactional` rollback rules |
| 7 | A rolled-back order is fulfilled | Event published inside the transaction |
| 8 | Migrated orders are worth `null` | Schema drift under `ddl-auto=update` |
| 9 | The catalogue list serves a stale price | `@CacheEvict` with the wrong key |
| 10 | Stale price charged; stock goes negative | The per-product cache is never evicted |
| 11 | One consumer never receives anything | Two listeners in one consumer group |
| 12 | A customer can read another customer's order | No object-level authorisation |

---

## Defect 1 — the security matchers are in the wrong order

**Where:** `config/SecurityConfig.java`.

```java
.requestMatchers("/error").permitAll()
.requestMatchers("/api/auth/**").permitAll()
.requestMatchers("/api/catalogue/**").permitAll()
.requestMatchers("/api/**").authenticated()
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.anyRequest().authenticated()
```

**Root cause:** matchers are evaluated **in the order they are declared, and the first one that
matches wins**. `/api/admin/report` matches `/api/**`, which comes first, so the rule applied is
`authenticated()`. The `hasRole("ADMIN")` line below it is unreachable — for every URL it could
match, an earlier line already did. Spring Security does not sort by specificity and does not warn
about unreachable rules.

**Fix:** specific first, general last.

```java
.requestMatchers("/error").permitAll()
.requestMatchers("/api/auth/**").permitAll()
.requestMatchers("/api/catalogue/**").permitAll()
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.requestMatchers("/api/**").authenticated()
.anyRequest().authenticated()
```

**Why a fresher writes this:** the rules are added in the order the endpoints were built. `/api/**`
was written when the whole API needed a login; `/api/admin/**` was appended later, at the bottom,
where new rules go. Everything still compiles, the application still starts, every test that checks
"a signed-in user can use the API" still passes, and the hole is invisible unless you specifically
test a customer against an admin URL.

**How to recognise it in a real project:** whenever an authorisation rule "does not seem to apply",
list the matchers in order and find the first match by hand. Turning on
`logging.level.org.springframework.security=DEBUG` prints the chain. Also worth knowing: a security
test per role per endpoint group is cheap and catches exactly this.

---

## Defect 2 — the roles claim has no `ROLE_` prefix

**Where:** `security/JwtService.java` writes the claim; `security/JwtAuthenticationFilter.java`
reads it.

```java
.claim("roles", List.of(role))                       // role is "ADMIN"
```

```java
authorities.add(new SimpleGrantedAuthority(String.valueOf(role)));   // authority "ADMIN"
```

**Root cause:** `hasRole("ADMIN")` is shorthand for `hasAuthority("ROLE_ADMIN")` — Spring Security
prepends `ROLE_` before comparing. The token carries `ADMIN`, so the granted authority is `ADMIN`,
and `ROLE_ADMIN` is not among the authorities. Authentication succeeds, the principal is correct,
every non-admin endpoint works, and the admin endpoint returns `403`.

This defect is invisible until Defect 1 is fixed, because until then no rule requires the role at
all.

**Fix — pick one end, not both:**

```java
authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
```

or keep the filter literal and issue the claim with the prefix:

```java
.claim("roles", List.of("ROLE_" + role))
```

Prefixing at the reading end is usually better: the token stays a clean statement about the user
(`ADMIN`), and the framework convention stays inside the framework's own layer — which matters if
another service ever issues these tokens. Using `hasAuthority("ADMIN")` in the config is also
defensible, as long as the project does it consistently and never mixes the two.

**Why a fresher writes this:** `hasRole("ADMIN")` reads as "the role is ADMIN", so storing `ADMIN`
looks right. The prefix is a convention with no compile-time or runtime warning: a wrong authority
does not error, it just never matches.

**How to recognise it in a real project:** the classic "login works, everything works, one endpoint
gives 403". Print the authorities on the request (security DEBUG logging does it for you) and
compare the exact strings against what the rule requires.

---

## Defect 3 — the token lifetime is computed in the wrong unit

**Where:** `security/JwtService.java`.

```java
.expiration(new Date(now + expirationMinutes * 1000))
```

with `security.jwt.expiration-minutes=30`, while the login response advertises:

```java
public long getExpiresInSeconds() {
    return expirationMinutes * 60;      // 1800
}
```

**Root cause:** `new Date(long)` takes milliseconds. `30 * 1000` is 30 seconds, not 30 minutes —
the conversion from minutes to milliseconds needs `* 60 * 1000`. The login response computes the
advertised figure separately and correctly, so the API promises 1800 seconds and delivers 30. Every
`401` after that looks like a broken filter or a bad key.

**Fix:**

```java
private static final long ONE_MINUTE_MS = 60 * 1000L;

.expiration(new Date(now + expirationMinutes * ONE_MINUTE_MS))
```

Better, remove the second derivation so the two can never disagree:

```java
private final Duration lifetime;   // Duration.ofMinutes(expirationMinutes)

.expiration(Date.from(Instant.now().plus(lifetime)))
...
public long getExpiresInSeconds() { return lifetime.toSeconds(); }
```

`Duration` in the configuration (`security.jwt.expiration=30m`) removes the unit from the arithmetic
entirely, which is the real fix.

**Why a fresher writes this:** `System.currentTimeMillis() + 3600` and friends are one of the most
common Java bugs in existence. The property name says "minutes", the literal `1000` looks like a
unit conversion, and the code compiles and runs. In tests everything finishes in under 30 seconds,
so it passes.

**How to recognise it in a real project:** decode the token and subtract `iat` from `exp`. That one
subtraction turns "our sessions keep dropping" into a five-minute fix.

---

## Defect 4 — the exception advice catches everything

**Where:** `controller/ApiExceptionHandler.java`.

```java
@ExceptionHandler(RuntimeException.class)
public ResponseEntity<Map<String, Object>> handleFailure(RuntimeException exception) {
    ...
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
}
```

**Root cause:** `InsufficientStockException`, `PaymentDeclinedException`, `OrderNotFoundException`
and `ProductNotFoundException` are all annotated with the status they should produce — and all
extend `RuntimeException`. Spring tries `ExceptionHandlerExceptionResolver` **before**
`ResponseStatusExceptionResolver`, so this handler matches first and the annotations never take
effect. Every business failure is reported as a server fault.

`CheckoutDeclinedException` is a *checked* exception, so it does not match the handler, falls
through to the `@ResponseStatus` resolver, and returns `422` — the one status in the whole API that
tells the truth, and the clue to the whole defect.

**Fix:** handle the specific cases, and keep a genuine catch-all for genuinely unexpected failures:

```java
@ExceptionHandler({InsufficientStockException.class, PaymentDeclinedException.class,
        OrderNotFoundException.class, ProductNotFoundException.class})
public ResponseEntity<Map<String, Object>> handleBusinessFailure(RuntimeException exception) {
    HttpStatus status = Optional
            .ofNullable(AnnotatedElementUtils.findMergedAnnotation(exception.getClass(),
                    ResponseStatus.class))
            .map(ResponseStatus::value)
            .orElse(HttpStatus.BAD_REQUEST);
    return ResponseEntity.status(status).body(body(status, exception.getMessage()));
}

@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
    log.error("Unhandled failure", exception);        // with the stack trace
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong"));
}
```

Two other things worth fixing while you are there: the current handler logs only
`exception.getMessage()` and throws the stack trace away, and it returns the raw message to the
caller, which for an unexpected exception can leak internals (as it did here — a duplicate-key SQL
statement).

**Why a fresher writes this:** a catch-all advice is the standard answer to "stop Whitelabel error
pages appearing", and it works. It is only wrong for the exceptions that had a correct status of
their own, and nothing points that out — the endpoint still returns valid JSON.

**How to recognise it in a real project:** a `500` whose message is a perfectly sensible sentence
about the business domain. `500` means "we did not expect this"; a message that reads like a policy
means somebody did expect it.

---

## Defect 5 — the checkout body is never validated

**Where:** `controller/OrderController.java`.

```java
public ResponseEntity<OrderResponse> checkout(@RequestBody CheckoutRequest request,
                                              Authentication authentication)
```

`CheckoutRequest` declares `@NotBlank` on `sku` and `cardToken` and `@Min(1)` on `quantity`, and
none of them is evaluated.

**Root cause:** constraints on a `@RequestBody` are only checked when the parameter is annotated
`@Valid` (or `@Validated`). Without it the annotations are documentation. A quantity of `-3`
produces an order for `-7497.00` and *increases* the stock by three, because the same number is
used to compute the total and to decrement the stock. `AuthController.login` does have `@Valid`,
which makes the omission easy to see once you compare them.

**Fix:**

```java
public ResponseEntity<OrderResponse> checkout(@Valid @RequestBody CheckoutRequest request,
                                              Authentication authentication)
```

Then defend the invariant where it actually matters, in the service, so that it holds no matter who
calls:

```java
if (request.getQuantity() < 1) {
    throw new IllegalArgumentException("quantity must be at least 1");
}
```

A database check constraint (`CHECK (stock >= 0)`, `CHECK (quantity > 0)`) is the third layer and
costs nothing.

**Why a fresher writes this:** the constraints are on the DTO, so validation "is implemented". The
missing annotation is one word in a different file, produces no warning, and the happy-path tests
never send a bad value.

**How to recognise it in a real project:** send deliberately invalid input to every endpoint. If a
constraint violation does not come back as `400`, the validation is not running. A grep for
`@RequestBody` that is not preceded by `@Valid` finds them all.

---

## Defect 6 — `@Transactional` does not roll back on a checked exception

**Where:** `service/CheckoutService.java`, and `exception/CheckoutDeclinedException.java`.

```java
@Transactional
public OrderResponse checkout(String username, CheckoutRequest request)
        throws CheckoutDeclinedException {
    ...
    riskScreeningService.screen(orderRef, amount);   // throws a checked exception
```

```java
public class CheckoutDeclinedException extends Exception {
```

**Root cause:** Spring's declarative transaction management rolls back on `RuntimeException` and
`Error` only. A checked exception propagating out of the method **commits** the transaction — a
rule inherited from EJB and one of the most surprising defaults in the framework.

So an order above the review threshold is declined to the customer with `422` and, at the same time,
committed with status `PLACED` and its stock reserved. Because the event was already published
(Defect 7), the inventory consumer then marks it `FULFILLED`. Measured: ₹1,12,491 declined, order
stored, stock 12 → 3, nine pairs of headphones shipped.

**Fix — either:**

```java
@Transactional(rollbackFor = CheckoutDeclinedException.class)
```

**or** make the exception unchecked, which is what most codebases do:

```java
public class CheckoutDeclinedException extends RuntimeException {
```

(If you do that, remember Defect 4: an unchecked exception starts matching the advice, so fix the
advice first or this one starts returning `500`.)

The deeper fix is design: "this order needs manual approval" is a decision, not a failure. Screen
the order **before** writing anything, or write it with status `PENDING_APPROVAL` and let finance
move it forward. Throwing from the middle of a transaction to express a business outcome is what
made this fragile.

**Why a fresher writes this:** nothing in the code says "checked exceptions do not roll back". The
method is annotated, the exception propagates, the client gets an error — every visible signal says
it was rolled back. It is only ever caught by looking at the table afterwards.

**How to recognise it in a real project:** an error response with committed data behind it. When a
"failed" operation leaves rows, check the exception type against the rollback rules first, before
suspecting propagation or proxying.

---

## Defect 7 — the event is published inside the transaction

**Where:** `service/CheckoutService.java`.

```java
orderEventPublisher.publish(new OrderPlacedEvent(...));   // step 5

paymentGateway.authorise(request.getCardToken(), amount); // step 6, can throw
riskScreeningService.screen(orderRef, amount);            // step 7, can throw
```

**Root cause:** the Kafka send happens while the database transaction is still open. Kafka has no
part in that transaction: once the record is sent it cannot be recalled. When step 6 throws a
`PaymentDeclinedException` the database rolls back correctly — and the event is already on the
topic. The inventory consumer picks it up, finds no order (rightly, it never committed), and writes
a fulfilment row anyway. The warehouse is told to pick an order that does not exist.

The same publish-before-commit ordering shows up in the log even on the successful path:

```
FULFILMENT recorded for ORD-20260906-968FDB (stock now 5)
```

The stock was 5 *before* the checkout. The consumer read the database before the producing
transaction committed — a race that this consumer happens to survive and a slower one would not.

**Fix — the framework answer:**

```java
// in the service, inside the transaction
applicationEventPublisher.publishEvent(new OrderPlacedEvent(...));

// elsewhere
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderCommitted(OrderPlacedEvent event) {
    kafkaTemplate.send(ordersTopic, event.getOrderRef(), event);
}
```

**The pattern answer — the transactional outbox:** insert the event into an `outbox` table in the
same transaction as the order, and have a separate poller (or Debezium) publish rows from that table
and mark them sent. This is the standard solution to the dual-write problem and the one to reach for
when losing an event is not acceptable — `AFTER_COMMIT` still drops the event if the process dies
between the commit and the send.

Whichever you choose, the consumer should be defensive: an event whose order cannot be found is a
signal, not something to write a fulfilment row for.

**Why a fresher writes this:** "save the order, then publish the event" is the correct sequence in
the code, and it looks atomic because both lines are inside one `@Transactional` method. The idea
that one of the two writes cannot participate in the transaction is not obvious until it bites.

**How to recognise it in a real project:** downstream records for upstream records that do not
exist. Any time a message is sent from inside a transaction, ask what the consumer sees if that
transaction rolls back — or merely commits a few milliseconds later.

---

## Defect 8 — schema drift hidden by `ddl-auto=update`

**Where:** `entity/CustomerOrder.java` against `init/01-schema.sql`, with
`spring.jpa.hibernate.ddl-auto=update`.

```sql
total_amount DECIMAL(12, 2) DEFAULT NULL
```

```java
@Column(name = "amount", precision = 12, scale = 2)
private BigDecimal amount;
```

**Root cause:** `ddl-auto=update` compares the mapping with the live schema and adds what is
missing. It never renames and never removes. Hibernate found no `amount` column, so it created one
— beside `total_amount`, which still holds the migrated values. New orders write `amount`; the six
migrated orders have their money in `total_amount` and `NULL` in `amount`.

`SELECT SUM(o.amount)` therefore ignores the migrated orders entirely, and `GET /api/orders/{ref}`
returns `"amount": null` for them while MySQL clearly holds `31990.00`. Nothing is logged: from
Hibernate's point of view the schema is now correct.

```
SHOW COLUMNS FROM customer_order;
   ... total_amount decimal(12,2) YES NULL
   ... amount       decimal(12,2) YES NULL      <- added at start-up
```

**Fix — map the entity to the column that holds the data:**

```java
@Column(name = "total_amount", precision = 12, scale = 2)
private BigDecimal amount;
```

then drop the stray column:

```sql
ALTER TABLE customer_order DROP COLUMN amount;
```

And fix the cause rather than the instance: set `spring.jpa.hibernate.ddl-auto=validate` so a
mismatch fails at start-up instead of being silently patched, and manage the schema with Flyway or
Liquibase. `validate` would have refused to start and pointed straight at the column.

**Why a fresher writes this:** `ddl-auto=update` is in every tutorial and feels helpful — it makes
entities "just work" in development. Renaming a field and letting Hibernate "sort it out" is a
natural next step, and in an empty development database it genuinely does work. The damage only
appears against a database that already has rows in it, which is to say in production.

**How to recognise it in a real project:** data that exists in SQL but is `null` through the ORM.
`SHOW COLUMNS` and count. Two columns meaning the same thing, one of them empty, is the signature.

---

## Defect 9 — the price update evicts the wrong cache key

**Where:** `service/AdminService.java` against `service/CatalogueService.java`.

```java
@Cacheable("catalogue")                       // no arguments -> key is SimpleKey.EMPTY
public List<ProductView> listCatalogue() { ... }
```

```java
@CacheEvict(value = "catalogue", key = "#sku")
public ProductView updatePrice(String sku, BigDecimal price) { ... }
```

**Root cause:** a `@Cacheable` method with no parameters stores its result under `SimpleKey.EMPTY`
— in Redis, `catalogue::SimpleKey []`. The eviction asks for `catalogue::SKU-1001`, which has never
existed, so it removes nothing and reports no error. The list keeps serving the price it was cached
with until the 10-minute TTL expires.

```
127.0.0.1:6379> KEYS *
1) "catalogue::SimpleKey []"
2) "products::SKU-1001"
```

**Fix:** the whole list is invalidated by any product change, so evict the whole cache:

```java
@CacheEvict(value = "catalogue", allEntries = true)
```

and, together with Defect 10, evict on every write path:

```java
@Caching(evict = {
        @CacheEvict(value = "catalogue", allEntries = true),
        @CacheEvict(value = "products", key = "#sku")
})
```

Caching a whole list under one key is itself worth questioning: any change to any product throws it
all away. Caching per product and assembling the list is often better, at the cost of more round
trips.

**Why a fresher writes this:** `key = "#sku"` is right for the per-product cache two lines away, and
copying it to the list cache looks consistent. Nothing fails: an eviction for a key that is not
there is a successful no-op, in Spring and in Redis.

**How to recognise it in a real project:** `redis-cli KEYS '*'` before and after the write. If the
key you expected to disappear is still there — or was never named that in the first place —
the eviction never matched. `logging.level.org.springframework.cache=TRACE` prints every hit, miss
and eviction with the key it used.

---

## Defect 10 — the per-product cache is never invalidated

**Where:** `service/AdminService.java` (`updatePrice`, `updateStock`) and
`service/CheckoutService.java`.

```java
@Cacheable(value = "products", key = "#sku")
public ProductView findBySku(String sku) { ... }
```

Nothing anywhere evicts `products`. Meanwhile the checkout reads through it:

```java
ProductView product = catalogueService.findBySku(request.getSku());
if (product.getStock() < request.getQuantity()) { ... }
BigDecimal amount = product.getPrice().multiply(...);
```

**Root cause:** two consequences, both serious.

*Money.* The price the customer pays comes from a cache entry that no write path invalidates. After
a price rise the shelf says one thing, MySQL says another, and the checkout charges a third —
measured: MySQL `4499.00`, catalogue list `3299.00`, product endpoint and the actual charge
`2499.00`.

*Stock.* The availability check reads the cached stock, which never changes, so it always passes.
Five `SKU-1006` chairs were sold from a shelf of three and MySQL went to `-2` while the API still
reported `3`.

**Fix:** evict `products` on every write (see Defect 9's `@Caching` block), and — more importantly —
stop reading the decision-critical value through a cache:

```java
Product stored = productRepository.findBySku(request.getSku())
        .orElseThrow(() -> new ProductNotFoundException(request.getSku()));
if (stored.getStock() < request.getQuantity()) {
    throw new InsufficientStockException(...);
}
BigDecimal amount = stored.getPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
```

A cache is for data that is allowed to be a little old. A stock check and a price charged to a card
are not that kind of data. Even reading live, two concurrent checkouts can both see the last unit:
add `@Version` for optimistic locking, or make the reservation a conditional update —

```sql
UPDATE product SET stock = stock - :qty WHERE sku = :sku AND stock >= :qty
```

— and treat "zero rows updated" as out of stock. A `CHECK (stock >= 0)` constraint turns the
remaining races into loud failures instead of negative inventory.

**Why a fresher writes this:** the cache was added to the read path, which is where caches belong,
and it worked. Nobody thought about the write path, because the write path is in a different class
written by a different person for a different ticket. Reading the product through the existing
service in the checkout felt like good reuse — it is reuse of a *caching* method, and that is the
part nobody noticed.

**How to recognise it in a real project:** the API and the database disagree, and restarting the
application or waiting for the TTL "fixes" it. That pair of facts means a cache, every time. List
every write to an entity and, for each, the caches it invalidates; the gaps are the defects.

---

## Defect 11 — two listeners share one consumer group

**Where:** `consumer/NotificationConsumer.java` and `consumer/OrderAnalyticsConsumer.java`.

```java
@KafkaListener(topics = "${checkout.topic.orders}", groupId = "notification-service")
public void onOrderPlaced(OrderPlacedEvent event) { ... }   // NotificationConsumer
```

```java
@KafkaListener(topics = "${checkout.topic.orders}", groupId = "notification-service")
public void onOrderPlaced(OrderPlacedEvent event) { ... }   // OrderAnalyticsConsumer
```

**Root cause:** a consumer group is a unit of *work sharing*. Every partition of the topic is
assigned to exactly one member of the group, and each record is delivered to exactly one member. The
topic has a single partition, so one of these two listeners gets it and the other is assigned
nothing and receives nothing — for ever, silently, with a healthy connection and no error.

Which one loses is decided by the group's assignment protocol, so a restart can swap them. That is
why the symptom appears to move between the notification table and the dashboard counter.

```
GROUP                 TOPIC            PARTITION  CONSUMER-ID
notification-service  checkout.orders  0          consumer-notification-service-3-...
notification-service  -                -          consumer-notification-service-1-...   <- nothing assigned
inventory-service     checkout.orders  0          consumer-inventory-service-2-...
```

**Fix:** two independent subscribers are two groups.

```java
@KafkaListener(topics = "${checkout.topic.orders}", groupId = "analytics-service")
```

Fan-out in Kafka is by consumer group, not by listener. Use one group per *purpose*, and add members
to a group only when you want that work shared across instances.

**Why a fresher writes this:** the second listener was copy-pasted from the first and the group id
came with it. The name `notification-service` even looks deliberate. The mental model behind it —
"the group id names the application" — is right for scaling one job and wrong for two jobs, and
nothing distinguishes the two cases in the code.

**How to recognise it in a real project:** a consumer that connects, rebalances, logs nothing, and
never receives a record. `kafka-consumer-groups.sh --describe --all-groups` and look for a member
with no partition. With more partitions than groups it is nastier still: each listener gets a
fraction of the traffic, and the symptom becomes "some notifications are missing", which sends
people hunting through the database instead.

---

## Defect 12 — no object-level authorisation on the order endpoint

**Where:** `controller/OrderController.java`.

```java
@GetMapping("/orders/{orderRef}")
public ResponseEntity<OrderResponse> order(@PathVariable String orderRef) {
    return ResponseEntity.ok(checkoutService.findByRef(orderRef));
}
```

**Root cause:** the endpoint is authenticated but not authorised. It takes an identifier from the
URL and returns whatever it names. `arjun` reads `meena`'s order in full — customer name, items and
value — by changing one string. The neighbouring `/orders/mine` derives everything from the
principal and is safe, which makes the contrast easy to miss: the "secure" endpoint and the insecure
one sit next to each other.

This is **broken object level authorisation**, number one on the OWASP API Security Top 10.

**Fix — check ownership where the data is loaded:**

```java
@Transactional(readOnly = true)
public OrderResponse findByRef(String orderRef, String username) {
    CustomerOrder order = customerOrderRepository.findByOrderRef(orderRef)
            .orElseThrow(() -> new OrderNotFoundException(orderRef));
    if (!order.getCustomerUsername().equals(username)) {
        throw new AccessDeniedException("Not your order");
    }
    return toResponse(order);
}
```

Better still, make it impossible to ask the wrong question:

```java
Optional<CustomerOrder> findByOrderRefAndCustomerUsername(String orderRef, String username);
```

Then decide deliberately whether an `ADMIN` may read any order — with `@PreAuthorize` or an explicit
role check — rather than leaving it to chance. Note also that `/orders/{ref}/fulfilment` has the
same hole.

Returning `404` rather than `403` for someone else's order is worth considering: `403` confirms the
reference exists.

**Why a fresher writes this:** "the endpoint requires a token, so it is protected." Authentication
answers *who you are*; it says nothing about *which rows are yours*. Every test is written as the
owner, so the hole is never exercised.

**How to recognise it in a real project:** for every endpoint that takes an id, ask "what happens if
I put someone else's id here". Test it with two accounts — it takes a minute and finds this class of
defect immediately.

---

## Order of discovery

```
Defect 3  (token lifetime) ─────── independent, but fix it first or it interrupts everything

Defect 1  (matcher order) ───────▶ Defect 2 (role prefix)
                                   no rule requires the role until Defect 1 is fixed

Defect 4  (catch-all advice) ────▶ masks the real status of the stock rule and the card decline
                                   (and turns Defect 6 into a 500 if you make its exception unchecked)

Defect 7  (event in transaction)   \  two separate defects behind one story:
Defect 6  (rollback rules)         /  H rolled back and leaked; I never rolled back at all

Defect 9  (list eviction)          \  same file, same class of mistake,
Defect 10 (product cache)          /  different consequences: display vs money and stock

Defect 5  (missing @Valid) ─────── independent
Defect 8  (schema drift) ───────── independent
Defect 11 (consumer group) ─────── independent, and moves between restarts
Defect 12 (object authorisation) ─ independent
```

## Test baseline

`mvn test` with all twelve defects present, against a freshly seeded database:
**13 tests run, 3 pass, 9 fail, 1 error.**

| Test | Result | Defect |
|---|---|---|
| `theCatalogueIsPublic` | passes | — |
| `signingInReturnsAToken` | passes | — |
| `anAdministratorCanReadTheReport` | **passes for the wrong reason** | 1 |
| `aTokenIsValidForAsLongAsTheResponseSays` | `expected 1800 but was 30` | 3 |
| `aCustomerCannotReadTheReport` | `expected 403 but was 200` | 1 |
| `aCustomerCannotReadSomebodyElsesOrder` | `expected 403 but was 200` | 12 |
| `anImpossibleQuantityIsRejected` | `expected 400 but was 201` | 5 |
| `orderingMoreThanTheAvailableStockIsARejectionNotACrash` | `expected 409 but was 500` | 4 |
| `aDeclinedCardLeavesNothingBehind` | fulfilments `expected 3 but was 4` | 7 |
| `anOrderThatNeedsApprovalIsNotCompleted` | orders `expected 6 but was 7` | 6 |
| `aPriceChangeIsVisibleImmediately` | price `expected 9199.0 but was 8750.0` | 10 |
| `everyOrderReachesEverySubscriber` | timeout, notifications `expected 1 but was 0` | 11 |
| `aMigratedOrderStillShowsItsValue` | amount `expected 31990.0 but was null` | 8 |

Note `anAdministratorCanReadTheReport`. It passes now because Defect 1 lets *everybody* read the
report. Fix Defect 1 and it starts failing, because of Defect 2. Fix Defect 2 and it passes again —
this time for the right reason. A test that goes green → red → green during a fix is telling you
that two defects were cancelling out, and it is worth watching for.

**Defects 2 and 9 are not covered by any test**, deliberately. Defect 2 is invisible until Defect 1
is fixed, so no test can assert it from a cold start. Defect 9 needs the list cache to be warm
before the price change, which is a sequence you have to construct by hand — exactly the kind of
state-dependent behaviour that a test suite will not find for you and a `redis-cli KEYS '*'` will.
