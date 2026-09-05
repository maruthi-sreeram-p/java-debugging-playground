# SOLUTION — 06 · Order Service

> **Sealed answer key.** Six planted defects.

---

## Defect 1 — Only the inverse side of `Order` ↔ `OrderItem` is set

* **Bug:** in `OrderService.placeOrder`, each item is added to the parent collection but the child is
  never pointed back at its parent:

  ```java
  OrderItem item = new OrderItem();
  ...
  order.getItems().add(item);        // item.setOrder(order) is never called
  ```

  `Order.items` is `@OneToMany(mappedBy = "order", cascade = CascadeType.ALL)`, so `OrderItem.order`
  is the **owning** side.
* **Affected component:** `service/OrderService.placeOrder`
* **Root cause:** JPA only writes the foreign key from the owning side. `mappedBy` declares
  `Order.items` to be the inverse side, which means Hibernate reads it but never derives SQL from it.
  The cascade still persists the children (that is what `CascadeType.ALL` does), so the rows are
  inserted — with `order_id` left `NULL`.
* **Symptom:** the `POST` response contains both items; a subsequent `GET` returns `"items": []`;
  `SELECT id, order_id FROM order_items` shows `NULL` in every `order_id`.
* **Why the symptom is misleading:** the create response is built from the **in-memory** `Order`,
  whose `items` list genuinely does contain both items. The object graph in memory is correct. Only
  the database disagrees, and only on a fresh read. A developer who tests by looking at the `POST`
  response sees a completely healthy order.
* **Correct fix:** set both directions. The durable version is a helper on the parent:

  ```java
  // Order
  public void addItem(OrderItem item) {
      items.add(item);
      item.setOrder(this);
  }

  // OrderService
  order.addItem(item);
  ```

  A bare `item.setOrder(order);` next to the `add` call is also correct. Prefer the helper — the
  point is that the two sides can never again be updated independently.
* **Concept:** owning vs inverse side; `mappedBy`; the difference between an object graph and a set
  of foreign keys.
* **Why a fresher makes it:** `order.getItems().add(item)` reads exactly like what they want to
  happen, and in plain Java it *is* what happens. Nothing in the code hints that only one direction
  counts. They also test with the `POST` response, which lies.
* **How to recognise it in the wild:** `NULL` foreign keys on child rows that were clearly inserted
  by the parent's cascade. Any bidirectional association without an `addX`/`removeX` helper on the
  parent is a candidate.

**Gates Defect 6** — the remove-item endpoint cannot be exercised until items are attached.

---

## Defect 2 — The order total is computed before the items are added

* **Bug:**

  ```java
  order.setStatus("PLACED");
  order.setTotalAmount(sumOf(order.getItems()));   // items is still empty here
  ...
  for (OrderItemRequest itemRequest : request.getItems()) { ... order.getItems().add(item); }
  ```

* **Affected component:** `service/OrderService.placeOrder`
* **Root cause:** plain statement ordering. `sumOf` is called on an empty list and returns
  `BigDecimal.ZERO`.
* **Symptom:** `"totalAmount": 0.00` on every order, while every individual `lineTotal` in the same
  response is correct.
* **Why the symptom is misleading:** it sits in the middle of a project about relationships, so it
  reads like another mapping problem — "the total is zero because the items aren't attached". It is
  not: it would still be zero even with Defect 1 fixed, because the sum runs before the loop either
  way. A learner who fixes Defect 1 and expects the total to correct itself will be surprised, which
  is exactly the intended lesson: **verify each symptom independently rather than assuming one cause
  explains several.**
* **Correct fix:** move the computation after the loop:

  ```java
  for (OrderItemRequest itemRequest : request.getItems()) { ... order.addItem(item); }
  order.setTotalAmount(sumOf(order.getItems()));
  ```

  A better answer, worth crediting: make the total derived rather than stored, or recompute it in one
  place that both `placeOrder` and `removeItem` call. Note `removeItem` already recomputes correctly.
* **Concept:** order of operations when assembling an aggregate; the hazard of storing a value that
  is derived from a collection.
* **Why a fresher makes it:** they write the "set up the order" block first, in the order the fields
  appear on the class, and add the item loop afterwards.
* **How to recognise it in the wild:** a stored aggregate that is always the identity value (`0`, `""`,
  empty). Read the method in execution order, not in reading order.

---

## Defect 3 — `Customer.orders` is a unidirectional `@OneToMany` with no `mappedBy`

* **Bug:**

  ```java
  @OneToMany(fetch = FetchType.LAZY)
  private List<Order> orders = new ArrayList<>();
  ```

* **Affected component:** `entity/Customer.java`
* **Root cause:** without `mappedBy`, JPA does not know this is the other half of
  `Order.customer`. It treats it as an independent unidirectional association and, with no
  `@JoinColumn` to guide it, defaults to a **join table** — `customers_orders`. Nothing in the
  application ever writes to that table, so the collection is permanently empty.
* **Symptom:** `GET /api/customers/1/orders` returns `[]` while `orders.customer_id` is correctly
  populated and `GET /api/orders/1` reports the right customer. The relationship works in one
  direction only. `SHOW TABLES` reveals five tables for four entities.
* **Why the symptom is misleading:** `Order.customer` is mapped perfectly, so the learner can see the
  foreign key in the database and reasonably conclude "the relationship exists, so the collection
  should be populated". The extra table is the giveaway, and it is invisible unless you look.
* **Correct fix:**

  ```java
  @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY)
  private List<Order> orders = new ArrayList<>();
  ```

  Also acceptable and arguably better for a large customer: drop the collection entirely and add
  `List<Order> findByCustomerId(Long customerId)` to `OrderRepository`. If the learner proposes that,
  agree — an unbounded `@OneToMany` on a customer is a real design smell.

  Either way, the stale `customers_orders` table survives until the schema is regenerated. Tell them
  to `docker compose down -v` (or drop it manually) so they are not debugging a ghost.
* **Concept:** `mappedBy`; unidirectional `@OneToMany` and the join table it implies; using the
  generated schema as evidence.
* **Why a fresher makes it:** they add the collection for convenience, the code compiles, the
  application starts, and no error ever appears. `mappedBy` is easy to forget precisely because the
  consequence is silence.
* **How to recognise it in the wild:** count your tables against your entities after any mapping
  change. An unexplained join table means an association is not mapped the way you think it is.

---

## Defect 4 — `Order.shippingAddress` has no cascade

* **Bug:**

  ```java
  @OneToOne
  @JoinColumn(name = "shipping_address_id")
  private ShippingAddress shippingAddress;
  ```

  and `OrderService` builds a brand-new `ShippingAddress` per order, which is never saved through any
  repository.
* **Affected component:** `entity/Order.java` (the missing cascade); triggered by
  `service/OrderService.toAddress` / `placeOrder`.
* **Root cause:** at flush time the new `Order` references a `ShippingAddress` that has no identifier
  and is not managed by the persistence context. Without a cascade, Hibernate refuses to guess and
  throws `TransientPropertyValueException`.
* **Symptom:** `POST /api/orders` returns `500` when — and only when — `shippingAddress` is present in
  the request. The identical order without an address returns `201`. "Works for one request and not
  another."
* **Why the symptom is misleading:** it is intermittent from the outside — the endpoint is not
  broken, only one input shape is. It is also the one defect here that produces a genuinely helpful
  exception message, so it is the easiest of the six once the learner reproduces it deliberately.
  The trap is that it masks Defects 1 and 2 for any request that includes an address: the whole
  transaction rolls back, so nothing else about that request can be observed.
* **Correct fix:**

  ```java
  @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "shipping_address_id")
  private ShippingAddress shippingAddress;
  ```

  `CascadeType.PERSIST` alone fixes the reported symptom. **Press for `ALL` plus `orphanRemoval`** and
  ask hint D4's question: an address created for exactly one order has no independent life, so when
  the order goes the address should go with it. A learner who saves the address through a repository
  before saving the order has also fixed it — that works, but ask them what happens on delete.
* **Concept:** `CascadeType`; transient / managed / detached entity states; which operations propagate
  across an association.
* **Why a fresher makes it:** `@OneToOne` with a `@JoinColumn` looks complete. Cascade is not
  required by the annotation and not mentioned by the compiler. They also may only ever have tested
  the no-address path.
* **How to recognise it in the wild:** `object references an unsaved transient instance` names the
  exact association in its message — read it and go straight there. The broader habit: whenever you
  `new` up a child inside a service, ask who is responsible for persisting it.

---

## Defect 5 — `/raw` returns JPA entities directly from the controller (two-layer defect)

* **Bug:**

  ```java
  @GetMapping("/{id}/raw")
  public ResponseEntity<Order> getOrderGraph(@PathVariable Long id) {
      return ResponseEntity.ok(orderService.findEntity(id));
  }
  ```

* **Affected component:** `controller/OrderController.getOrderGraph`
* **Root cause and the two layers:**

  **Layer 1 — the lazy proxy.** `Order.customer` is `@ManyToOne(fetch = LAZY)`, so the field holds a
  Hibernate-generated subclass (`Customer$HibernateProxy$…`), not a `Customer`. Jackson reflects over
  it, finds the framework's internal `hibernateLazyInitializer` property, and cannot serialise it:

  ```
  InvalidDefinitionException: No serializer found for class
    org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor ...
    (through reference chain: Order["customer"] -> Customer$HibernateProxy["hibernateLazyInitializer"])
  ```

  **Layer 2 — the cycle.** The obvious fix is
  `@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})` on the entities, which is what
  every search result recommends. Apply it and (once Defect 1 is fixed so `items` is non-empty) the
  endpoint fails again, this time with `Infinite recursion (StackOverflowError)`: `Order` serialises
  `items`, each `OrderItem` serialises `order`, which serialises `items`…
* **Symptom:** `GET /api/orders/{id}/raw` returns `500`, always. After the naive fix it returns `500`
  again, differently.
* **Why the symptom is misleading:** the first exception points at Hibernate internals and a class the
  learner has never heard of, which suggests a framework bug or a missing Jackson module. It is
  neither — it is a design error one layer up.
* **Correct fixes, in ascending order of quality:**
  1. `@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})` on the entities, **plus**
     breaking the cycle with `@JsonIgnore` on `OrderItem.order` (or the
     `@JsonManagedReference`/`@JsonBackReference` pair). This makes it work.
  2. Register `com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module`, which teaches Jackson
     about proxies properly. Still needs the cycle broken.
  3. **The right answer: stop returning entities.** Give `/raw` a DTO like every other endpoint —
     a richer one if support staff genuinely need more detail. Every problem in this symptom
     disappears, and so does the risk of a mapping change silently altering the API contract.

  Accept 1 or 2, but make sure the learner can articulate 3. Hint E5 is aimed squarely at it.
* **Concept:** lazy proxies; JSON serialisation of a persistent object graph; serialisation cycles in
  bidirectional relationships; why the DTO boundary exists.
* **Why a fresher makes it:** returning the entity is one line and "the DTO is just extra work". The
  endpoint often even works at first — until the association becomes lazy, or until the reverse side
  is populated.
* **How to recognise it in the wild:** `ByteBuddyInterceptor`, `hibernateLazyInitializer` or
  `Infinite recursion` in a serialisation stack trace all mean the same thing: an entity reached the
  JSON layer. The fix is at the boundary, not in the annotations.

---

## Defect 6 — `Order.items` has no `orphanRemoval`

* **Bug:** `@OneToMany(mappedBy = "order", cascade = CascadeType.ALL)` — cascade is present,
  `orphanRemoval` is not. `OrderService.removeItem` removes the item from the collection and saves
  the order.
* **Affected component:** `entity/Order.java`
* **Root cause:** removing a child from an inverse-side collection tells JPA nothing that it can
  translate into SQL. The owning side (`OrderItem.order`) still points at the order, so no `update`
  is warranted; and without `orphanRemoval`, no `delete` is warranted either. Hibernate issues
  nothing at all. `CascadeType.ALL` does **not** cover this: `CascadeType.REMOVE` propagates
  `entityManager.remove(order)` to its children — it says nothing about a child being dropped from
  the collection.
* **Symptom:** `DELETE /api/orders/1/items/1` returns `204 No Content` and the row is still in
  `order_items`. A fresh `GET` still shows the line. The `totalAmount` written by the same method
  *does* change, so the order is left internally inconsistent — a total that no longer matches its
  own lines.
* **Why the symptom is misleading:** `204` is the documented success status, the service logs
  "Removed item 1 from order 1", the total visibly changes, and `CascadeType.ALL` is right there in
  the mapping looking like it covers everything. Everything says it worked.
* **Correct fix:**

  ```java
  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<OrderItem> items = new ArrayList<>();
  ```

  and, for symmetry with Defect 1, a `removeItem` helper on `Order` that clears `item.setOrder(null)`
  as well. Deleting through `OrderItemRepository.delete(item)` is an acceptable alternative — ask
  which they would prefer and why.
* **Concept:** `orphanRemoval` versus `CascadeType.REMOVE`; what a change to an inverse-side
  collection does and does not mean.
* **Why a fresher makes it:** `CascadeType.ALL` sounds total. The word "all" is doing a lot of work in
  their mental model, and `orphanRemoval` is a separate attribute with a name that does not obviously
  describe "delete children removed from this list".
* **How to recognise it in the wild:** "the delete returns success but the row is still there" — the
  exact phrase in the brief for this lab. Read the SQL log: if there is no `delete` statement, no
  delete was requested.

---

## Suggested fix order

1. **Defect 4** (shipping address) — independent, has a clear message, and unblocks testing orders
   that carry an address.
2. **Defect 3** (`mappedBy` on the customer collection) — independent; `SHOW TABLES` is the evidence.
3. **Defect 1** (link both sides) — the central one. Fixing it makes Defect 6 reachable.
4. **Defect 2** (total ordering) — independent; catch it by noticing the total is *still* zero after
   fixing 1.
5. **Defect 6** (`orphanRemoval`).
6. **Defect 5** (`/raw`) — last, because its second layer only appears once items are attached.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `placingAnOrderReturnsAnOrderNumber` | pass | — |
| `anOrderStillHasItsItemsWhenItIsReadBack` | fail | Defect 1 |
| `theOrderTotalIsTheSumOfItsLines` | fail | Defect 2 |
| `aCustomerCanSeeTheOrdersTheyPlaced` | fail | Defect 3 |
| `anOrderCanCarryAShippingAddress` | error | Defect 4 |

Baseline: `Tests run: 5, Failures: 3, Errors: 1`.

**Defects 5 and 6 are not covered by any test.** Defect 6 is reachable only after Defect 1 is fixed,
and Defect 5 requires calling `/raw` by hand.

## Verification commands

```bash
docker compose down -v && docker compose up -d      # start from a clean schema
mvn clean package && java -jar target/order-service-1.0.0.jar

# exactly four tables
docker exec debuglab06-mysql mysql -uroot -prootpw ordersdb -e "SHOW TABLES;"

curl -s -X POST http://localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productName":"Mechanical Keyboard","unitPrice":4499.00,"quantity":1},{"productName":"Wireless Mouse","unitPrice":1299.00,"quantity":2}]}'
# expect totalAmount 7097.00

curl -s http://localhost:8080/api/orders/1        # two items, total 7097.00
curl -s http://localhost:8080/api/customers/1/orders   # one order
curl -s http://localhost:8080/api/orders/1/raw    # 200, complete JSON

curl -s -X POST http://localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":2,"items":[{"productName":"Monitor 24 inch","unitPrice":11999.00,"quantity":1}],"shippingAddress":{"line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","pincode":"560001"}}'
# expect 201

curl -i -X DELETE http://localhost:8080/api/orders/1/items/1     # 204
curl -s http://localhost:8080/api/orders/1                       # one item, total 2598.00
```

```sql
SELECT id, order_id, product_name FROM order_items;   -- no NULL order_id, deleted row gone
SELECT COUNT(*) FROM shipping_addresses;              -- 1
```
