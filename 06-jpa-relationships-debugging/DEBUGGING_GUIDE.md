# Debugging Guide — 06 · Order Service

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Every problem in this project is about **who owns a relationship** and **what JPA does when you only
tell one half of it**.

An object graph in memory and a set of rows with foreign keys in a database are two different
things. Java lets you point A at B without B knowing about A. A relational database does not — the
link is one column, on one row, and exactly one side of your mapping is responsible for writing it.
When those two models disagree, you get what you will see here: responses that look perfect and rows
that are not connected to anything.

Because this service generates its own schema from the mappings, **the tables Hibernate creates are
evidence**. If a mapping is wrong, the schema will show you.

## Expected behaviour

1. An order read back has the same items, in the same quantities, as the order that was placed.
2. `totalAmount` equals the sum of the line totals.
3. `SELECT * FROM order_items` and the `items` array in the API response agree.
4. A customer's history lists the orders they placed.
5. An order can carry a shipping address.
6. `DELETE .../items/{itemId}` deletes the row.
7. `GET /api/orders/{id}/raw` returns the entity graph.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/order-service-1.0.0.jar
```

Keep a MySQL shell open the whole time:

```bash
docker exec -it debuglab06-mysql mysql -uroot -prootpw ordersdb
```

Start with `SHOW TABLES;` **before you place a single order**. Count the tables and compare that
count with the number of entity classes. That one command answers a question you will otherwise
spend twenty minutes on.

`mvn test` runs five tests: one passes, four fail.

---

## Known symptoms

### Symptom A — items vanish between the write and the read

```
POST /api/orders   {"customerId":1,"items":[ ...two items... ]}

201 Created
{ ..., "items":[ {"id":1,"productName":"Mechanical Keyboard", ...},
                 {"id":2,"productName":"Wireless Mouse", ...} ] }
```

```
GET /api/orders/1

200 OK
{ ..., "items": [] }
```

And in the database:

```sql
SELECT id, order_id, product_name FROM order_items;
+----+----------+---------------------+
| id | order_id | product_name        |
|  1 |     NULL | Mechanical Keyboard |
|  2 |     NULL | Wireless Mouse      |
```

The rows were written. They are not attached to anything.

### Symptom B — the order total is always zero

```
"totalAmount": 0.00
```

Every order, regardless of what is in it. The individual `lineTotal` values in the `POST` response
are all correct.

### Symptom C — a customer has no orders

```
GET /api/customers/1/orders
[]
```

...while `SELECT customer_id FROM orders` clearly shows orders belonging to customer 1, and
`GET /api/orders/1` happily reports `"customerId": 1, "customerName": "Aarav Sharma"`.

The link works in one direction and not the other.

### Symptom D — orders with an address are rejected

```
POST /api/orders  (with "shippingAddress")
500 Internal Server Error
```

```
org.hibernate.TransientPropertyValueException: object references an unsaved transient instance
  - save the transient instance before flushing :
  com.debuglab.orders.entity.Order.shippingAddress -> com.debuglab.orders.entity.ShippingAddress
```

The identical request without `shippingAddress` returns `201`.

### Symptom E — the raw endpoint always fails

```
GET /api/orders/1/raw
500 Internal Server Error
```

```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException: No serializer found for class
  org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor and no properties discovered to create
  BeanSerializer (through reference chain:
  com.debuglab.orders.entity.Order["customer"]
    ->com.debuglab.orders.entity.Customer$HibernateProxy$QrPgDjn1["hibernateLazyInitializer"])
```

Be careful with this one. There is an obvious way to silence it, and silencing it reveals a second,
worse failure underneath.

### Symptom F — deleting a line reports success and deletes nothing

*Only visible once Symptom A is fixed.*

```
DELETE /api/orders/1/items/1
204 No Content
```

```sql
SELECT id, order_id, product_name FROM order_items WHERE order_id = 1;
+----+----------+---------------------+
|  1 |        1 | Mechanical Keyboard |     <-- still there
|  2 |        1 | Wireless Mouse      |
```

No error, no warning, and the row is untouched.

---

## Investigation hints

### Symptom A — the unattached items

> **Hint A1**
> The rows exist and `order_id` is `NULL`. So the insert happened but nothing ever wrote that
> column. Which of the two classes involved is responsible for writing `order_id`?

> **Hint A2**
> In a bidirectional one-to-many, one side is the **owning** side and the other is the **inverse**
> side. Only the owning side's state is translated into SQL. Find the attribute in the mapping that
> declares which side is inverse, then work out which side that leaves owning.

> **Hint A3**
> Now read the code that builds the order and list every assignment it makes. It sets one direction
> of the relationship. Does it set the other?

> **Hint A4**
> This is also why the `POST` response looked right: it was built from the in-memory object, which
> *does* have the items in its list. The list in memory and the foreign key in the database are two
> separate facts, and only one of them was ever established.

> **Hint A5**
> The durable fix is not to remember to write two lines every time. Give the parent a small helper
> method that maintains both sides at once, and always add items through it.

### Symptom B — the zero total

> **Hint B1**
> Nothing to do with relationships. Read the order-building method top to bottom, in execution
> order, and ask at which point the total is computed relative to when the items are added.

> **Hint B2**
> `BigDecimal.ZERO` is what you get from summing an empty list. Where was the list empty?

### Symptom C — the one-way relationship

> **Hint C1**
> Compare `SHOW TABLES` against your list of entity classes. There is a table there that does not
> correspond to any entity. What kind of mapping causes Hibernate to create a table like that?

> **Hint C2**
> Look at the collection on the customer side. Compare its annotation, attribute by attribute, with
> the collection on the order side — the one you were looking at for Symptom A. One of them declares
> something the other does not.

> **Hint C3**
> Without that attribute, JPA has no idea the two sides are the same relationship. It treats the
> collection as an independent, unidirectional association and gives it its own storage. It is not
> broken — it is a *different relationship*, one that nothing ever populates.

> **Hint C4**
> When you fix this, drop the stale table (or `docker compose down -v`) rather than leaving it
> behind — otherwise you will spend ten minutes wondering why an empty table is still there.

### Symptom D — the transient instance

> **Hint D1**
> Read the exception message literally. It names a class and tells you it is unsaved. Ask why
> nothing saved it: is there a repository call for that object anywhere?

> **Hint D2**
> Compare the annotation on the relationship that fails with the annotation on the relationship that
> works (order to items). The working one carries an attribute that tells JPA to extend an operation
> from the parent to the child. The failing one does not.

> **Hint D3**
> There are two possible fixes: save the child explicitly before saving the parent, or declare the
> relationship so that saving the parent saves the child. Both work. Think about which one is
> consistent with how the rest of this entity is mapped, and which one still works when someone adds
> a second child object next month.

> **Hint D4**
> Whatever you choose, ask the follow-up: if an order is deleted, what should happen to its address
> row? Your answer should influence exactly which cascade you pick.

### Symptom E — the raw endpoint

> **Hint E1**
> Read the reference chain at the end of the message: `Order["customer"] -> Customer$HibernateProxy
> ["hibernateLazyInitializer"]`. That tells you exactly what Jackson tripped over — and note the
> class name, which is not a class you wrote.

> **Hint E2**
> A lazily-loaded association does not hold your entity. It holds a generated subclass that stands in
> for it until it is needed. That stand-in has internal properties. Jackson does not know they are
> internal.

> **Hint E3**
> There is a well-known one-line annotation that tells Jackson to ignore those internal properties.
> **Apply it, then call the endpoint again.** What you get next is the real lesson of this symptom.

> **Hint E4**
> The second failure is about a cycle. Order knows its items; each item knows its order. Serialising
> either one walks into the other, forever. Look up the pair of annotations Jackson provides for
> exactly this situation, or the simpler annotation that cuts one direction of the cycle.

> **Hint E5**
> Now step back and ask the design question the rest of this project has been building towards: every
> other endpoint here returns a DTO and none of them has any of these problems. What is this endpoint
> actually doing differently, and is any amount of Jackson annotation the right answer?

### Symptom F — the delete that does nothing

> **Hint F1**
> Read the SQL log for the `DELETE` request. How many statements does Hibernate issue? Is one of them
> a `delete`?

> **Hint F2**
> Removing an object from a collection tells JPA "this order no longer lists this item". It does not
> say "this item should cease to exist" — those are different statements, and the second one has to
> be requested.

> **Hint F3**
> There is an attribute on the one-to-many mapping for precisely this: it means "a child that is
> removed from this collection has no independent existence, so delete it". Find it and understand
> the difference between it and the cascade attribute next to it — they are not the same thing and
> people conflate them constantly.

> **Hint F4**
> There is a second, blunter fix: delete the child through its own repository. Both work. Decide
> which one you would want in this domain, and be able to say why.

---

## Expected logs and observations

* The application starts cleanly every time. Nothing here is a start-up problem.
* `spring.jpa.show-sql=true` is on. For symptoms A and F, the SQL log is decisive — read which
  `insert`, `update` and `delete` statements are actually issued, and which are not.
* At start-up Hibernate logs every `create table`. That log answers Symptom C on its own if you read
  it.
* `SELECT id, order_id, product_name FROM order_items;` is the single most useful query in this
  project.
* Symptoms A, B, C and F produce **no exception at all**. Only D and E throw.
* Remember that `create-drop` wipes everything on restart, so ids restart from 1 each time you
  relaunch. Do not confuse that with data loss caused by a defect.

## Difficulty

**Intermediate.** Expect two to three hours.

Symptoms B, C, D and E are independent of everything else and can be tackled in any order. Symptom A
is a gate: Symptom F is invisible until it is fixed. Symptom E has two layers — the second only
appears after you address the first.

## Concepts being tested

* Owning versus inverse sides of a bidirectional association, and `mappedBy`
* Why setting only one side of a bidirectional relationship writes nothing to the database
* Unidirectional `@OneToMany` and the join table it silently creates
* `CascadeType` — which operations propagate from parent to child
* `orphanRemoval` and how it differs from `CascadeType.REMOVE`
* Lazy proxies and what happens when one is handed to a JSON serialiser
* Serialisation cycles in bidirectional graphs
* Why controllers return DTOs rather than entities
* Order of operations when building an aggregate in memory

## When you think you are done

- [ ] `mvn test` is green (5 tests).
- [ ] `SHOW TABLES` lists exactly four tables — one per entity, no extras. (Run
      `docker compose down -v && docker compose up -d` first so no stale table survives.)
- [ ] Place a two-line order, then `SELECT id, order_id FROM order_items` — every row has an
      `order_id`.
- [ ] `GET /api/orders/{id}` returns both lines and a `totalAmount` equal to their sum.
- [ ] `GET /api/customers/1/orders` lists the order.
- [ ] An order with a shipping address returns `201` and the address is stored.
- [ ] `DELETE .../items/{itemId}` returns `204`, the row is gone from `order_items`, and
      `totalAmount` on a fresh `GET` reflects only the remaining line.
- [ ] `GET /api/orders/{id}/raw` returns `200` with a complete, non-recursive JSON document.
- [ ] You can explain, in one sentence each, the difference between `mappedBy`, `cascade` and
      `orphanRemoval`.

Then say **"I think I fixed the project"**.
