# Debugging Guide — 15 · Pricing & Inventory Service

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Every write in this service succeeds. The database is correct after every single one of them. And
the API goes on reporting the old values, sometimes for an hour, sometimes forever.

That is what makes cache invalidation its own discipline. There is no failing request to trace, no
exception to read, no wrong query to fix. The write worked. The read works. They just disagree, and
the only way to see it is to hold three things next to each other:

| Source | What it tells you |
|---|---|
| The API response | what the service *believes* |
| `SELECT` in MySQL | what is **true** |
| `KEYS` / `GET` in `redis-cli` | *why* the service believes otherwise |

**The database is always right in this project.** Every symptom below is the API disagreeing with it.

The TTL is one hour, deliberately. You cannot wait out a stale entry; correctness has to come from
invalidation.

## Expected behaviour

After **any** write, the very next read reflects it — the single SKU, the full list, and the
statistics. A deleted SKU returns `404`.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/pricing-service-1.0.0.jar > app.log 2>&1 &
```

Set up the three views:

```bash
docker exec -it debuglab15-redis redis-cli          # KEYS *, GET, TTL, FLUSHALL, MONITOR
docker exec -it debuglab15-mysql mysql -uroot -prootpw pricingdb
```

The routine for every symptom is the same:

1. `FLUSHALL` so you start cold.
2. `GET` the thing (this warms the cache).
3. Write to it.
4. `GET` it again — and check MySQL and `KEYS *`.

Step 1 matters. A leftover entry from a previous experiment will waste your afternoon.

`mvn test` runs six tests: one passes, five do not.

---

## Known symptoms

### Symptom A — a price change is invisible

```
GET  /api/pricing/KB-1001        -> price 4499.00      (warms the cache)
PUT  /api/pricing/KB-1001        {"price":5999.00}     -> 200 OK
GET  /api/pricing/KB-1001        -> price 4499.00      still
```

```sql
SELECT price FROM price_entries WHERE sku='KB-1001';    -- 5999.00
```

The write worked. The read is stale, and will be for an hour.

```
127.0.0.1:6379> KEYS *
1) "price::KB-1001"
```

The entry is still there. Nothing removed it. There *is* an eviction annotation on the update method —
read it very carefully against the annotation on the read method.

### Symptom B — a stock change returns 500

```
PATCH /api/pricing/KB-1001/stock   {"stock":500}   -> 500
```

```
java.lang.IllegalStateException: Cannot convert cache key
  com.debuglab.pricing.dto.StockUpdateRequest to String; Please register a suitable Converter
  via 'RedisCacheConfiguration.configureKeyConverters(...)' or override
  '...StockUpdateRequest.toString()'
```

Redis is being asked to use a whole request object as a cache key. The suggestion in the message —
register a converter, or add a `toString()` — is Redis being helpful about the wrong thing. Ask why
that object is being used as a key at all.

### Symptom C — the list does not follow the item

Once Symptom A is fixed, the single-SKU read is correct. The list is not:

```
GET /api/pricing                       -> HD-4004 at 349.00       (warms the list)
PUT /api/pricing/HD-4004  {"price":999.00}
GET /api/pricing/HD-4004               -> 999.00        correct
GET /api/pricing                       -> HD-4004 at 349.00       wrong
```

Two endpoints, one row, two different answers. One of them is a whole hour out of date.

### Symptom D — renaming breaks the next read

```
PATCH /api/pricing/MN-3003/name?name=Monitor%2027%20inch   -> 200 OK
GET   /api/pricing/MN-3003                                 -> 500
```

```
java.lang.ClassCastException: class com.debuglab.pricing.entity.PriceEntry
  cannot be cast to class com.debuglab.pricing.dto.PriceDto
```

And in Redis:

```
127.0.0.1:6379> GET "price::MN-3003"
["com.debuglab.pricing.entity.PriceEntry",{"id":4,"sku":"MN-3003","name":"Monitor 27 inch", ...
```

Something put the wrong *kind* of object into the cache. The rename itself worked — check MySQL.

### Symptom E — a deleted SKU is still for sale

```
GET    /api/pricing/WC-6006   -> 200        (warms the cache)
DELETE /api/pricing/WC-6006   -> 204 No Content
GET    /api/pricing/WC-6006   -> 200        still returns the product
```

```sql
SELECT COUNT(*) FROM price_entries WHERE sku='WC-6006';   -- 0
```

The row is gone. The API will keep selling it for an hour.

### Symptom F — a bulk update clears almost nothing

```
GET  /api/pricing                                    (warms the list)
GET  /api/pricing/NW-7001                            (warms the entry)
POST /api/pricing/bulk  {"prices":{"NW-7001":20000.00,"HD-4004":399.00}}  -> {"updated":2}
```

```
127.0.0.1:6379> KEYS *
1) "price::NW-7001"
2) "priceList::SimpleKey []"
```

Both entries survived a write that changed both of them. The list still reports `15999.00` where the
database says `20000.00`.

### Symptom G — the statistics never move

```
GET /api/pricing/stats    -> {"skuCount":6,"totalListPrice":39144.00,"totalUnits":759}
PUT /api/pricing/HD-4004  {"price":9999.00}
GET /api/pricing/stats    -> {"skuCount":6,"totalListPrice":39144.00,"totalUnits":759}
```

Identical. The total list price has changed in the database by nearly ten thousand rupees. This one
is not mentioned by any test, and it is the easiest to miss because nobody looks at the statistics
endpoint while debugging the others.

---

## Investigation hints

### Symptom A — the price that will not update

> **Hint A1**
> The entry is still in Redis after the write, so no eviction happened for that key. There *is* an
> eviction annotation on the method. So either it did not run, or it removed something else.

> **Hint A2**
> Put the annotation on the read method and the annotation on the write method side by side and
> compare them **word by word**. There are two things to compare: which cache, and which key.

> **Hint A3**
> A cache is identified by name. Evicting from a cache nobody reads from is a no-op that never
> reports anything — Spring will happily create and clear a cache called anything you like.

> **Hint A4**
> `redis-cli MONITOR` during the write will show you exactly which key Spring asked Redis to delete.
> Compare that with the key the read created.

### Symptom B — the request object as a key

> **Hint B1**
> The exception names the type it was given. Find the key expression that produced it.

> **Hint B2**
> A SpEL key expression names method parameters. Look at which parameter this one names, and which
> one the corresponding `@Cacheable` names.

> **Hint B3**
> Do not take the exception's advice. Adding a `toString()` or a key converter would make the error
> go away and evict a key that still matches nothing. The key has to be the same value the read used.

### Symptom C — the stale list

> **Hint C1**
> There are three caches in this service and the README names all three. A price change invalidates
> data in how many of them?

> **Hint C2**
> Look at what the update method evicts. Now count the caches that hold a copy of that price.

> **Hint C3**
> One annotation can only name one cache's worth of keys. Look up how to evict from more than one
> cache in a single write — there is an annotation for grouping them.

> **Hint C4**
> Then consider the general problem: every future write will have to remember all three. Is there a
> structure that makes it harder to forget? Think about what the list and statistics caches actually
> buy you, and what their keys would have to be for a targeted eviction to even be possible.

### Symptom D — the object of the wrong type

> **Hint D1**
> Look at what is stored in Redis for that key and at what the read method's return type is. They
> are not the same class.

> **Hint D2**
> Find the annotation on the rename method. It is not the same annotation as on the other writes.
> Read what it does: it does not remove an entry, it **replaces** one — with whatever the method
> returns.

> **Hint D3**
> So the contract is: any method annotated that way must return exactly what the cached read returns.
> Look at the rename method's return type and compare it with the read's.

> **Hint D4**
> Two fixes. Change the write to produce the right type, or stop trying to update the cache in place
> and simply evict. Think about which is safer as a habit — and note that the write-in-place version
> is only correct if the value it computes is *identical* to what a fresh read would produce.

### Symptom E — the SKU that outlives its row

> **Hint E1**
> Compare the delete method with the other write methods. What are they all wearing that it is not?

> **Hint E2**
> There is nothing subtle here — the annotation is simply absent. The interesting question is why it
> was never noticed: what does the endpoint return, and does anything about the response suggest a
> problem?

> **Hint E3**
> Deletion invalidates the same caches an update does, and one more thing besides. Make sure your fix
> covers all of them.

### Symptom F — the bulk update

> **Hint F1**
> The write changed two SKUs. Look at the annotation on it and ask how many entries it can possibly
> remove.

> **Hint F2**
> Read the attribute that is set on that annotation, and what its other value does. The name says
> exactly what it means.

> **Hint F3**
> Also: which caches does a bulk price change invalidate? The same three as a single change. Check
> that your fix reaches all of them.

### Symptom G — the frozen statistics

> **Hint G1**
> Search the service for every place the statistics cache is named. Count them.

> **Hint G2**
> It is created by a read and never mentioned again. It is not a wrong annotation — it is a missing
> one, on every write in the class.

> **Hint G3**
> This is the strongest argument for the design question in hint C4: a cache that every write must
> remember to evict will eventually be forgotten by some write. Once you have fixed it, ask yourself
> how you would stop the *next* developer adding a write method that forgets — and whether an
> aggregate that changes on every write should be cached at all.

---

## Expected logs and observations

* Every symptom returns `200` (or `204`) except B and D. There is nothing to grep for.
* `DATABASE READ` in the log means a cached read missed. After a correct eviction you should see it
  again on the next read — that is your proof the eviction worked.
* `redis-cli MONITOR` during a write is the single most useful tool here: it shows precisely which
  keys Spring deleted, and which it did not.
* `KEYS *` after a write tells you what survived. Anything left that describes changed data is a
  defect.
* `FLUSHALL` between experiments, every time.
* Remember that a stale entry lives for an hour. If something "fixes itself" while you are working,
  you cleared the cache, not the bug.

## Difficulty

**Advanced.** Expect two to three hours. Seven defects.

They are independent — none gates another — but they overlap on the same endpoints, so fix one at a
time and re-run the cold-cache routine after each. Two of them (F and G) are not covered by any test
and will still be there when the suite goes green.

## Concepts being tested

* `@CacheEvict` — cache names, key expressions, and `allEntries`
* `@CachePut` versus `@CacheEvict`, and the type contract `@CachePut` implicitly signs
* Invalidating **every** cached view of a changed row, not just the obvious one
* Collection and aggregate caches, and why they are harder to invalidate than single entities
* Grouping multiple evictions on one write
* Delete as a cache-invalidating operation
* Using Redis directly as the arbiter of what the service actually believes

## When you think you are done

Run each of these from a cold cache (`FLUSHALL` first).

- [ ] `mvn test` is green (6 tests).
- [ ] `PUT` a price → the next `GET /{sku}`, `GET /`, and `GET /stats` all reflect it.
- [ ] `PATCH` stock → returns `200`, and all three views reflect it.
- [ ] `PATCH` a name → returns `200`, the next read succeeds and shows the new name.
- [ ] `POST /bulk` with two SKUs → all three views reflect **both** changes.
- [ ] `DELETE` a SKU → the next `GET` is `404`, it is absent from the list, and `skuCount` has
      dropped.
- [ ] After every one of the above, `KEYS *` contains nothing describing data you just changed.
- [ ] Pick any SKU, change it, and confirm the API and `SELECT * FROM price_entries` agree —
      immediately, without touching Redis by hand.
- [ ] You can name, for each of the three caches, which writes must invalidate it.

Then say **"I think I fixed the project"**.
