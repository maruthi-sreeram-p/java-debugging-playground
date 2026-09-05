# Debugging Guide — 04 · Product Inventory API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

This project is about the **boundary between your Java objects and the database**. Everything here
turns on one question that you will ask over and over for the rest of your career:

> When I write this, what SQL does Hibernate actually run — and what does the row actually contain?

The application never throws a business error. The catalogue is small, the logic is trivial. The
problems are all in the mapping and query layer, and every one of them produces a *plausible*
answer rather than an obviously wrong one.

`spring.jpa.show-sql=true` is already enabled. Use it. The console tells you the truth; the JSON
response does not.

## Expected behaviour

1. The application starts.
2. Search is case-insensitive: `?term=mouse` and `?term=Mouse` return the same product.
3. `low-stock?threshold=10` returns the four products with ten or fewer in stock, lowest first.
4. A product created with `"reorderLevel": 15` still has `15` when you read it back.
5. `PATCH /{id}/stock` changes only the quantity.

## How to reproduce

```bash
mvn clean package
java -jar target/product-inventory-1.0.0.jar
```

Keep the H2 console open at <http://localhost:8080/h2-console> (JDBC URL `jdbc:h2:mem:inventorydb`,
user `sa`, blank password). For at least two of the symptoms below, the *only* way to see what is
happening is to look at the table.

`mvn test` runs four tests. All of them fail at first for the same reason; three of them keep failing
afterwards for three different reasons.

---

## Known symptoms

### Symptom A — the application does not start

```
***************************
APPLICATION FAILED TO START
***************************
```

...except it is not that tidy. What you actually get is a wall of nested `Caused by:` blocks. The
first one is about a controller, the second about a service, the third about a repository. The line
that matters is the **last** `Caused by:`, six levels down.

This is deliberate. Learning to scroll to the bottom of a Spring stack trace is a real skill and this
is a safe place to practise it.

### Symptom B — the low-stock report is backwards

```
GET /api/products/low-stock?threshold=10

[ {"sku":"KB-1001","quantity":42}, {"sku":"HD-4004","quantity":130} ]
```

Two products, both very well stocked. The four products that are actually running low — including one
with zero in stock — are not in the list. No error, no warning, a perfectly well-formed `200`.

### Symptom C — search works for one spelling and not another

```
GET /api/products/search?term=Mouse    ->  1 product
GET /api/products/search?term=mouse    ->  0 products
```

The README says search is case-insensitive. Notice that `by-category` *is* case-insensitive and works
with `peripherals` or `Peripherals`.

### Symptom D — a field that does not survive being saved

```
POST /api/products   {"sku":"SP-7007", ..., "reorderLevel":15}

201 Created
{"id":7,"sku":"SP-7007", ..., "reorderLevel":15}          <-- looks fine
```

```
GET /api/products/by-name?name=USB-C Hub

[{"id":7,"sku":"SP-7007", ..., "reorderLevel":null}]      <-- not fine
```

Every product in the catalogue reports `"reorderLevel": null`, including the seeded ones. The
create response is the only place the value is ever seen.

### Symptom E — adjusting the stock destroys the product

```
GET /api/products/1
{"id":1,"sku":"KB-1001","name":"Mechanical Keyboard","category":"Peripherals","price":4499.00,"quantity":42, ...}

PATCH /api/products/1/stock   {"quantity":55}
200 OK
{"id":1,"sku":null,"name":null,"category":null,"price":null,"quantity":55, ...}

GET /api/products/1
{"id":1,"sku":null,"name":null,"category":null,"price":null,"quantity":55, ...}
```

The row is still there. Everything in it except the id and the new quantity is gone — permanently.
This is the most damaging problem in the project and it reports `200 OK`.

---

## Investigation hints

### Symptom A — start-up failure

> **Hint A1**
> Scroll to the very last `Caused by:` in the log. Everything above it is Spring explaining which
> bean could not be built because of it. The final line names a type and a property.

> **Hint A2**
> Spring Data builds a query for every method on a repository interface at start-up, by parsing the
> method *name*. The name it could not parse is in the message.

> **Hint A3**
> Now the conceptual question, and it is the whole point of this symptom: when Spring Data parses a
> method name, is it looking for a **column** in the table or a **field** on the entity class? Look
> at how the entity maps that piece of data and you will see why the two are not the same word here.

> **Hint A4**
> There are two ways to make this method work — one that changes the method name, and one that keeps
> the name and supplies the query explicitly. Both are legitimate. Think about which one a reader of
> the repository six months from now would prefer.

### Symptom B — the inverted report

> **Hint B1**
> This method does not derive its query from its name. Read what it *does* use.

> **Hint B2**
> Compare the query, character by character, against the sentence in the README that describes what
> the report is for. There is exactly one character of difference between right and wrong.

> **Hint B3**
> The lesson is bigger than the character: a method name is a comment, and comments lie. The query is
> the code. Whenever a repository method carries an explicit query, read the query and ignore the
> name.

### Symptom C — case-sensitive search

> **Hint C1**
> Two endpoints in this repository do nearly the same thing, and one of them handles case correctly.
> Put the two method names side by side.

> **Hint C2**
> Spring Data derived queries are built from keywords. `Containing` is one keyword. There is another
> keyword that changes how the comparison is performed, and the working method uses it.

> **Hint C3**
> Once you see it, ask the follow-up: is this fixed at the Java level or the SQL level? Turn on the
> SQL log and look at the `where` clause before and after your change. Knowing which one you changed
> matters when the table has an index on that column.

### Symptom D — the vanishing field

> **Hint D1**
> The value is present in the create response and absent everywhere else. What is different about
> the create response? Think about which object it was built from and whether that object had been
> back to the database yet.

> **Hint D2**
> Look at the SQL log for the `insert` that runs when you create a product. Count the columns in it.
> Compare that with the number of fields on the entity.

> **Hint D3**
> Now look at the `create table` statement Hibernate issues at start-up (it is in the log, or you can
> see the columns in the H2 console). One field on the entity has no column at all. Find the
> annotation that caused that, and read what it means.

> **Hint D4**
> This one has a second half. Once the field is persisted, ask whether the seeded rows in `data.sql`
> have a value for it — and whether the low-stock report *should* be using it instead of a query
> parameter. That is a design question, not a defect; form an opinion.

### Symptom E — the destructive stock update

> **Hint E1**
> Read the stock-update method in the service and answer one question: **where did the object being
> saved come from?** Was it loaded from the database, or built in memory?

> **Hint E2**
> Look at the SQL log for the `PATCH`. You will see a `select` and then an `update`. Read the `set`
> clause of that `update` and count how many columns it touches.

> **Hint E3**
> `save()` on a Spring Data JPA repository is not "insert or update the fields I set". For an object
> with an id that already exists, it delegates to the persistence provider's *merge* operation. Look
> up what merge does with the fields you did **not** set. They are not "unset" — they hold `null`,
> and `null` is a value.

> **Hint E4**
> The correct shape for a partial update is: load the managed entity, mutate the one field, and let
> the persistence context write the change. You do not even have to call `save()` if the method is
> transactional — work out why.

> **Hint E5**
> Before you move on: your fix stops the *next* product from being destroyed. Product 1 in your
> running instance is already gone. Restart to reseed, and think about how you would have detected
> this in production — the API returned `200` every time.

---

## Expected logs and observations

* Symptom A is the only failure that produces an exception. Everything else returns `200`.
* The SQL log is the primary instrument for symptoms B, D and E. Get comfortable reading Hibernate's
  generated `select`, `insert` and `update` statements — the `where` clause and the `set` clause tell
  you what really happened.
* At start-up Hibernate logs the `create table products (...)` it generates. That statement is the
  ground truth about which fields are persisted.
* For Symptom E, watch the `update` statement's `set` clause. Seeing every column listed when you
  only meant to change one is the moment the penny drops.
* The H2 console is the arbiter for D and E. A response body is a claim; a table row is evidence.

## Difficulty

**Beginner→Intermediate.** Expect 60–90 minutes.

Symptom A gates everything. B, C, D and E are independent of each other, though D and B are related
in design (see hint D4) even though they are separate defects.

## Concepts being tested

* Derived query method names: parsed against **entity properties**, not table columns
* Reading a deeply nested Spring `Caused by:` chain to its root
* `@Column(name = ...)` and the difference between a field name and a column name
* Derived query keywords, including case-insensitive matching
* JPQL `@Query` and why an explicit query overrides the promise made by the method name
* `@Transient` and which fields Hibernate persists
* `save()` / `merge()` semantics on a detached entity, and why partial updates must not be built from
  a fresh object
* Using the SQL log and the database console as evidence

## When you think you are done

- [ ] `mvn test` is green (4 tests).
- [ ] `?term=mouse` and `?term=Mouse` return the same result.
- [ ] `low-stock?threshold=10` returns `DK-5005`, `MN-3003`, `MS-2002`, `WC-6006` in that order.
- [ ] A product created with `"reorderLevel": 15` reports `15` on a fresh `GET`.
- [ ] After `PATCH /api/products/1/stock`, a `GET` on product 1 shows the new quantity and every
      other field unchanged. Verify in the H2 console, not just in the response.
- [ ] You can say, out loud, what SQL Hibernate runs for the stock update and why.

Then say **"I think I fixed the project"**.
