# SOLUTION — 04 · Product Inventory API

> **Sealed answer key.** Five planted defects.

---

## Defect 1 — Derived query method named after the column, not the property

* **Bug:** `ProductRepository.findByProductName(String name)`. The entity field is `name`, mapped
  with `@Column(name = "product_name")`. There is no `productName` property.
* **Affected component:** `repository/ProductRepository.java`; consumed by
  `ProductService.findByExactName` and `GET /api/products/by-name`.
* **Root cause:** Spring Data's `PartTree` parses repository method names against the **entity's
  property metadata**, not against the table's columns. `@Column(name = ...)` is a persistence-layer
  mapping that Spring Data's method-name parser never sees. Query derivation happens eagerly when the
  repository proxy is created, so the failure is at start-up, not at first call.
* **Symptom:** `APPLICATION FAILED TO START`, with six nested `Caused by:` frames. The chain runs
  `UnsatisfiedDependencyException` (controller) → `UnsatisfiedDependencyException` (service) →
  `BeanCreationException` (repository) → `QueryCreationException` → `IllegalArgumentException` →
  `PropertyReferenceException: No property 'productName' found for type 'Product'`.
* **Why the symptom is misleading:** the top of the trace blames `productController`, which is
  entirely innocent. A learner who reads only the first exception will start debugging the controller.
  The actual cause is on the last line of a very long log block.
* **Correct fix (either is acceptable):**

  ```java
  List<Product> findByName(String name);
  ```

  or keep the method name and supply the query explicitly:

  ```java
  @Query("SELECT p FROM Product p WHERE p.name = :name")
  List<Product> findByProductName(@Param("name") String name);
  ```

  The first is better — the method name then tells the truth. Note the service must be updated to
  match if the name changes.
* **Concept:** derived query parsing; entity properties versus table columns; eager repository
  bootstrapping.
* **Why a fresher makes it:** they have the table open in a SQL client, see a `product_name` column,
  and name the finder after it. It is the single most common Spring Data start-up failure.
* **How to recognise it in the wild:** `PropertyReferenceException` always means "this name is not a
  property of that class". Read it as a *Java* error, not a SQL error. Corollary worth teaching:
  renaming an entity field silently breaks every derived query that mentioned it, and only at
  start-up.

**This defect gates everything else in the project.**

---

## Defect 2 — The low-stock JPQL comparison is inverted

* **Bug:**

  ```java
  @Query("SELECT p FROM Product p WHERE p.quantity >= :threshold ORDER BY p.quantity ASC")
  List<Product> findLowStock(@Param("threshold") Integer threshold);
  ```

* **Affected component:** `repository/ProductRepository.java`
* **Root cause:** `>=` where the requirement is `<=`. One character.
* **Symptom:** `low-stock?threshold=10` returns the two best-stocked products (42 and 130 units) and
  omits the product with zero stock. `200 OK`, well-formed JSON, plausible-looking data.
* **Why the symptom is misleading:** the method is named `findLowStock`, the endpoint is called
  `/low-stock`, the service logs "Low stock report ... matched N products", and the results are
  correctly sorted ascending. Everything *around* the query asserts that it is a low-stock query. A
  reader skims the name and moves on. This is the "the method name is a comment, and comments lie"
  lesson.
* **Correct fix:**

  ```java
  @Query("SELECT p FROM Product p WHERE p.quantity <= :threshold ORDER BY p.quantity ASC")
  ```

* **Concept:** JPQL; explicit `@Query` overriding name derivation; verifying behaviour against a
  specification rather than against a name.
* **Why a fresher makes it:** an inverted comparison is the most common single-character logic bug
  there is, and nothing — not the compiler, not the type system, not a name-derived query — can catch
  it. It survives review because reviewers read the method name.
* **How to recognise it in the wild:** a report with the right *shape* and the wrong *contents*.
  Always spot-check a report against hand-computed expected rows. Note also that had this method
  relied on name derivation (`findByQuantityLessThanEqual`), the bug would have been impossible —
  worth discussing.

---

## Defect 3 — Search is case-sensitive

* **Bug:** `List<Product> findByNameContaining(String term)` — no case-insensitivity keyword, while
  `findByCategoryIgnoreCase` in the same interface has one.
* **Affected component:** `repository/ProductRepository.java`
* **Root cause:** `Containing` generates `where product_name like ?` with the term wrapped in `%`.
  Without `IgnoreCase`, the comparison is whatever the database's collation says — case-sensitive on
  H2's default collation, and on MySQL/PostgreSQL it varies, which is its own trap.
* **Symptom:** `?term=Mouse` finds the product, `?term=mouse` does not. The endpoint works "for one
  user but not another" depending purely on how they typed it.
* **Why the symptom is misleading:** it works when *you* test it, because you type the product name
  the way it appears on screen. It fails for the user who types lowercase. Intermittent-looking
  input-dependent failures are hard to reproduce and easy to dismiss as user error.
* **Correct fix:**

  ```java
  List<Product> findByNameContainingIgnoreCase(String term);
  ```

* **Concept:** Spring Data derived-query keywords; `IgnoreCase` generating `lower(...) like lower(?)`.
* **Why a fresher makes it:** they write `Containing`, test with the exact product name, and ship.
  The presence of a correct `IgnoreCase` method a few lines away in the same file is realistic — real
  codebases are inconsistent.
* **How to recognise it in the wild:** always test search with the "wrong" case, and with a substring
  from the middle of the value. Follow-up worth raising with the learner: `IgnoreCase` produces
  `lower(product_name) like ?`, which cannot use a plain index on `product_name`. On a large table
  that is a real cost, and the answer is a functional index or a normalised search column — not
  reverting the fix.

---

## Defect 4 — `reorderLevel` is annotated `@Transient`

* **Bug:**

  ```java
  @Transient
  private Integer reorderLevel;
  ```

* **Affected component:** `entity/Product.java`
* **Root cause:** `jakarta.persistence.Transient` tells the persistence provider to ignore the field
  entirely. Hibernate creates the `products` table without a `reorder_level` column, never writes the
  value on `insert`, and always leaves it `null` on load.
* **Symptom:** the create response shows `"reorderLevel": 15` and every subsequent read shows
  `null`.
* **Why the symptom is misleading:** the create response is built from the object returned by
  `save()`. For an `IDENTITY`-generated entity, `save()` returns the *same in-memory instance* it was
  given, with the id populated — so the transient field is still sitting in that object. The value is
  real, in memory, at that moment. It has simply never been written anywhere. The learner sees a
  correct `201` response and concludes the write path is fine, then goes looking for a bug in the
  read path or the mapper. Compare this with project 01's Defect 5, where the response also lied but
  for a different reason.
* **Correct fix:** remove the annotation and map the field:

  ```java
  @Column(name = "reorder_level")
  private Integer reorderLevel;
  ```

  A complete fix also adds the column to `data.sql` so the seeded rows have sensible values, e.g.
  `10, 8, 2, 40, 3, 5`. Credit the learner for noticing that the seeded products are still `null`
  after their fix.
* **Concept:** `@Transient` semantics; which fields participate in the generated schema and DML;
  the difference between an in-memory object and a persisted row.
* **Why a fresher makes it:** two plausible routes. Either they wanted the field excluded from JSON
  and reached for the wrong `@Transient` (the JSON one is `@JsonIgnore`; there is also a
  `java.beans.Transient`), or they added the field as a computed value first and forgot to map it
  when it became real data. Note that the import is `jakarta.persistence.Transient` — importing the
  wrong `Transient` is itself a classic.
* **How to recognise it in the wild:** "the API accepted it but the column is empty". Check the
  generated `create table` and the `insert` statement in the SQL log — if the column is not in the
  `insert`, the mapping is the problem, not the data.
* **Design follow-up (hint D4 in the guide):** with `reorderLevel` persisted, the low-stock report
  arguably should be `WHERE p.quantity <= p.reorderLevel` rather than taking a threshold parameter.
  That is a legitimate improvement, not a required fix. If the learner proposes it, agree — but make
  sure they keep the `threshold` endpoint working, since the README documents it.

---

## Defect 5 — Partial update built from a detached, half-empty entity

* **Bug:**

  ```java
  if (!productRepository.existsById(id)) {
      throw new ProductNotFoundException(id);
  }
  Product product = new Product();
  product.setId(id);
  product.setQuantity(request.getQuantity());
  Product saved = productRepository.save(product);
  ```

* **Affected component:** `service/ProductService.updateStock`
* **Root cause:** `SimpleJpaRepository.save()` checks `isNew()`. The id is set, so the entity is not
  new and `save()` calls `entityManager.merge()`. Merge loads the existing row into the persistence
  context and then copies **every** field from the detached instance onto it — including the five
  fields that were never set and are therefore `null`. The flush issues an `update` whose `set`
  clause covers every column. The original values are gone.
* **Symptom:** `PATCH /api/products/1/stock` returns `200 OK` with a response in which `sku`, `name`,
  `category`, `price` and `lastRestockedAt` are all `null`, and the row in the database matches.
  Irreversible data loss, reported as success.
* **Why the symptom is misleading:**
  * The `existsById` check makes the method *look* careful. It correctly returns `404` for a missing
    product, which is the behaviour anyone would test first.
  * The response is `200`. Nothing in the HTTP layer suggests damage.
  * A developer testing on a fresh database with one product may not notice, because they check
    "quantity is 55" and it is.
  * The columns have no `NOT NULL` constraints, so the database does not object. Had they been
    `NOT NULL`, this would have been a loud `DataIntegrityViolationException` and a five-minute fix.
    The absence of constraints is what makes it silent — a good discussion point.
* **Correct fix:** load the managed entity and mutate it:

  ```java
  @Transactional
  public ProductDto updateStock(Long id, StockUpdateRequest request) {
      Product product = productRepository.findById(id)
              .orElseThrow(() -> new ProductNotFoundException(id));
      product.setQuantity(request.getQuantity());
      return toDto(product);          // dirty checking flushes the update
  }
  ```

  An explicit `productRepository.save(product)` at the end is harmless and many teams prefer it for
  readability. Either is correct. What matters is that the object being saved was **loaded**, not
  **constructed**. Ask the learner why the `save()` call is optional here — if they can explain
  dirty checking and the transactional write-behind, they have understood it.
* **Concept:** JPA entity states (transient / managed / detached); `merge` semantics; dirty checking;
  why `save()` is not an "upsert the fields I care about".
* **Why a fresher makes it:** it reads beautifully — "make a product, give it the id and the new
  quantity, save it". It matches the mental model of an SQL `UPDATE ... SET quantity = ?`, which is
  exactly what it is not. This is one of the most damaging JPA mistakes in real systems and it is
  extremely common.
* **How to recognise it in the wild:** read the `update` statement in the SQL log. A partial update
  that lists every column is the signature. Structurally: never call `save()` on an object you built
  with `new` unless you intend to write all of its fields. Adding `NOT NULL` constraints to columns
  that should never be null converts this class of bug from silent to loud, which is a good argument
  for tight schemas.

---

## Suggested fix order

1. **Defect 1** — mandatory gate.
2. **Defect 5** — do this early; every minute it survives, another product can be destroyed.
3. **Defects 2, 3, 4** — independent, any order.

## Test expectations

All four tests fail initially with `Failed to load ApplicationContext` (Defect 1). Afterwards:

| Test | Fails because of |
|---|---|
| `catalogueListsEverySeededProduct` | passes once the context loads |
| `lowStockReportListsProductsAtOrBelowTheThreshold` | Defect 2 |
| `createdProductRetainsItsReorderLevel` | Defect 4 |
| `updatingStockDoesNotDisturbTheRestOfTheProduct` | Defect 5 |

**Defect 3 is not covered by any test** and must be found by exercising the API.

The tests call `entityManager.flush(); entityManager.clear();` between the write and the read. That
is essential for `createdProductRetainsItsReorderLevel` — without clearing the persistence context
the test would read the same in-memory instance back and pass despite the defect. Worth pointing out
to the learner: it is the test-level version of the same illusion that makes Defect 4 hard to see.

## Verification commands

```bash
mvn test    # 4/4 green

curl -s "http://localhost:8080/api/products/search?term=mouse"      # 1 product
curl -s "http://localhost:8080/api/products/search?term=Mouse"      # same 1 product

curl -s "http://localhost:8080/api/products/low-stock?threshold=10"
# expect DK-5005 (0), MN-3003 (3), MS-2002 (6), WC-6006 (9), in that order

curl -s -X POST http://localhost:8080/api/products -H 'Content-Type: application/json' \
  -d '{"sku":"SP-7007","name":"USB-C Hub","category":"Peripherals","price":3199.00,"quantity":25,"reorderLevel":15}'
curl -s "http://localhost:8080/api/products/by-name?name=USB-C%20Hub"   # reorderLevel must be 15

curl -s http://localhost:8080/api/products/1
curl -s -X PATCH http://localhost:8080/api/products/1/stock -H 'Content-Type: application/json' -d '{"quantity":55}'
curl -s http://localhost:8080/api/products/1
# quantity 55, everything else identical to the first GET
```

Then confirm in the H2 console:

```sql
SELECT * FROM products WHERE id = 1;
```
