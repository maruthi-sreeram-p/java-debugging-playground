# SOLUTION — 15 · Pricing & Inventory Service

> **Sealed answer key.** Seven planted defects.

---

## Defect 1 — `@CacheEvict` names a cache that nothing reads

* **Bug:**

  ```java
  @Cacheable(value = "price", key = "#sku")      // reads populate "price"
  public PriceDto findBySku(String sku) { ... }

  @CacheEvict(value = "pricing", key = "#sku")   // writes clear "pricing"
  public PriceDto updatePrice(String sku, PriceUpdateRequest request) { ... }
  ```

* **Affected component:** `service/PricingService.updatePrice`
* **Root cause:** `price` and `pricing` are two different caches. The eviction dutifully clears an
  entry in a cache that no read ever consults, and the real entry survives its full one-hour TTL.
* **Symptom:** `PUT` returns `200`, MySQL shows the new price, and `GET /api/pricing/{sku}` keeps
  returning the old one. `KEYS *` shows `price::KB-1001` still present after the write.
* **Why the symptom is misleading:** there **is** an eviction annotation, right there on the write
  method, with the right key. Everything about the code says invalidation was considered. Spring
  creates caches on demand and never warns that you are evicting from one nobody reads — there is no
  error, no log line, and no way to notice except by looking at Redis.
* **Correct fix:**

  ```java
  @CacheEvict(value = "price", key = "#sku")
  ```

  (See Defect 3 — this method needs to clear more than one cache.)
* **Concept:** cache names as identifiers; evictions that silently target nothing.
* **Why a fresher makes it:** the service is called "pricing", the cache is called "price", and both
  words are in scope everywhere. A typo of this kind is invisible in review.
* **How to recognise it in the wild:** `redis-cli MONITOR` during the write shows exactly which key
  Spring deleted. If the key you see deleted is not the key you see created, that is the whole bug.
  Defining cache names as constants (`public static final String PRICE_CACHE = "price";`) makes this
  class of defect a compile error.

---

## Defect 2 — The evict key is the request object instead of the SKU

* **Bug:**

  ```java
  @CacheEvict(value = "price", key = "#request")
  public PriceDto updateStock(String sku, StockUpdateRequest request) { ... }
  ```

* **Affected component:** `service/PricingService.updateStock`
* **Root cause:** the SpEL expression names the wrong parameter. The cache key becomes a whole
  `StockUpdateRequest` instance, which the configured `StringRedisSerializer` cannot turn into a key.
* **Symptom:** `PATCH /api/pricing/{sku}/stock` returns **500**:
  `IllegalStateException: Cannot convert cache key com.debuglab.pricing.dto.StockUpdateRequest to
  String; Please register a suitable Converter via 'RedisCacheConfiguration.configureKeyConverters(...)'
  or override '...StockUpdateRequest.toString()'`
* **Why the symptom is misleading — and this is the interesting part:** the exception offers two
  helpful-sounding suggestions, **and both of them are wrong**. Registering a key converter or adding
  a `toString()` would make the error disappear and produce a key like
  `StockUpdateRequest@1a2b3c` — which matches nothing, evicts nothing, and converts a loud failure
  into Defect 1's silent staleness. A learner who follows the error message's advice has made the
  system worse while making the test pass. The guide warns about this explicitly (hint B3); check
  whether they fell for it.
* **Correct fix:**

  ```java
  @CacheEvict(value = "price", key = "#sku")
  ```

  The key must be the same value the `@Cacheable` read used.
* **Concept:** SpEL key expressions; the key contract between read and write.
* **Why a fresher makes it:** `#request` is the parameter they are thinking about — it holds the new
  value — and it is easy to write the annotation while looking at the body of the method rather than
  at the read it has to match.
* **How to recognise it in the wild:** a serialisation or conversion error mentioning a cache key
  always means the key expression is wrong. Do not fix it by teaching the serialiser about your DTO.

---

## Defect 3 — Single-item writes never evict the list cache

* **Bug:** `updatePrice`, `updateStock` and `rename` each evict (at most) the `price` cache. The
  `priceList` cache, populated by `findAll()`, is never touched.
* **Affected component:** `service/PricingService` — all the single-item write methods
* **Root cause:** a changed row appears in more than one cached view. Evicting the entity's own entry
  leaves every collection containing it stale.
* **Symptom:** after a price change, `GET /api/pricing/{sku}` is correct and `GET /api/pricing`
  reports the old price. Two endpoints, one row, two answers.
* **Why the symptom is misleading:** it only appears **after** Defect 1 is fixed. Before that, both
  views are equally stale and consistent with each other, so nothing looks odd. The learner's correct
  fix creates a *visible inconsistency* where there was previously a uniform lie — which reads like
  they broke something.
* **Correct fix:** evict every cache holding the row, using `@Caching` to group them:

  ```java
  @Caching(evict = {
      @CacheEvict(value = "price", key = "#sku"),
      @CacheEvict(value = "priceList", allEntries = true),
      @CacheEvict(value = "priceStats", allEntries = true)
  })
  @Transactional
  public PriceDto updatePrice(String sku, PriceUpdateRequest request) { ... }
  ```

  (`allEntries = true` is the right choice for `priceList` and `priceStats` because each holds a
  single fixed-key entry — clearing the cache and clearing the entry are the same thing.)
* **The design question (hint C4), worth pressing on:** this has to be repeated on every write
  method, and the next person to add one will forget. Better answers exist: extract the eviction set
  into a single `@Caching` meta-annotation, publish a domain event that one listener reacts to, or
  question whether a whole-list cache and an aggregate cache earn their keep at all when every write
  must clear them entirely. A learner who raises this unprompted has understood the project.
* **Concept:** invalidating every cached *view* of changed data; `@Caching`; collection caches.
* **Why a fresher makes it:** they think in entities. The list feels like a different feature rather
  than another copy of the same data.
* **How to recognise it in the wild:** for every write, enumerate the cached reads that could include
  the affected row. Any read whose result set could contain it must be invalidated. Endpoints
  disagreeing with each other is the signature.

---

## Defect 4 — `@CachePut` stores an entity where the cache holds a DTO

* **Bug:**

  ```java
  @CachePut(value = "price", key = "#sku")
  public PriceEntry rename(String sku, String newName) { ... }   // returns the ENTITY
  ```

  while `findBySku` is `@Cacheable(value = "price", key = "#sku")` returning a `PriceDto`.
* **Affected component:** `service/PricingService.rename`
* **Root cause:** `@CachePut` always executes the method and writes its **return value** into the
  cache. It therefore signs an implicit contract: *what this method returns must be exactly what the
  cached read returns.* Here it writes a `PriceEntry` into a cache from which a `PriceDto` will be
  read.
* **Symptom:** the rename returns `200` and the database is correct. The **next** read of that SKU
  returns `500`:
  `ClassCastException: class PriceEntry cannot be cast to class PriceDto`. In Redis the entry is
  visibly the wrong shape:
  `["com.debuglab.pricing.entity.PriceEntry",{"id":4,"sku":"MN-3003", ...`
* **Why the symptom is misleading:** the write succeeds, the data is right, and the failure appears
  on a *different endpoint* on a *later request*. It is a delayed-action defect: the rename poisons
  the cache and something else trips over it. Note also the entity leaking into Redis — id, lazy
  proxies and all — which is a second problem hiding inside the first.
* **Correct fix — either:**

  1. Make the method honour the contract:

     ```java
     @CachePut(value = "price", key = "#sku")
     public PriceDto rename(String sku, String newName) { ...; return toDto(entry); }
     ```

     plus the `priceList` and `priceStats` evictions from Defect 3.

  2. **Preferred:** stop updating in place and evict like every other write. `@CachePut` is only
     correct when the value it computes is *identical* to what a fresh read would produce — a
     guarantee that is easy to break later and impossible to check. Evicting is always safe.

  Ask which they chose and why. Answer 2 shows better judgement.
* **Concept:** `@CachePut` semantics; the type contract between a put and a cacheable read; never
  putting entities in a shared cache.
* **Why a fresher makes it:** `@CachePut` reads as "update the cache", which sounds strictly better
  than throwing the entry away. Returning the entity is the path of least resistance because it is
  already in hand.
* **How to recognise it in the wild:** a `ClassCastException` on a cached read means something wrote
  the wrong type. Any cache with both `@Cacheable` and `@CachePut` needs their return types checked
  against each other — nothing else will.

---

## Defect 5 — `delete` evicts nothing

* **Bug:** `PricingService.delete(String sku)` carries `@Transactional` and no cache annotation at
  all.
* **Affected component:** `service/PricingService.delete`
* **Root cause:** a missing annotation. The row is deleted; every cached copy of it survives its full
  TTL.
* **Symptom:** `DELETE` returns `204`, `SELECT COUNT(*)` returns `0`, and `GET /api/pricing/{sku}`
  keeps returning `200` with the product for an hour. It also remains in the list and in the
  statistics.
* **Why the symptom is misleading:** `204 No Content` is the documented success status and there is
  nothing in the response to inspect. This is the "DELETE appears successful but the data remains"
  case from the lab brief, and it is the most commercially dangerous defect in this project — the
  service will keep offering a product that no longer exists.
* **Correct fix:**

  ```java
  @Caching(evict = {
      @CacheEvict(value = "price", key = "#sku"),
      @CacheEvict(value = "priceList", allEntries = true),
      @CacheEvict(value = "priceStats", allEntries = true)
  })
  @Transactional
  public void delete(String sku) { ... }
  ```

* **Concept:** deletion as a cache-invalidating operation.
* **Why a fresher makes it:** when adding caching to an existing service, attention goes to the
  reads (to cache them) and the updates (to evict them). Delete is the method nobody revisits.
* **How to recognise it in the wild:** after adding a cache, audit **every** method that writes —
  including deletes, soft-deletes, status flips and bulk paths. A quick grep for `@Transactional`
  without a neighbouring `@CacheEvict` is a decent first pass.

---

## Defect 6 — Bulk update uses `allEntries = false`

* **Bug:**

  ```java
  @CacheEvict(value = "priceList", allEntries = false)
  public int bulkUpdate(BulkPriceRequest request) { ... }
  ```

* **Affected component:** `service/PricingService.bulkUpdate`
* **Root cause:** `allEntries = false` (the default) evicts a *single* key — computed from the method
  parameters, which here is the `BulkPriceRequest`. Nothing matching that key is ever in the cache, so
  nothing is removed. The individual `price::` entries for the changed SKUs are not addressed at all.
* **Symptom:** the bulk update reports `{"updated":2}` and changes two rows. `KEYS *` afterwards still
  shows `price::NW-7001` and `priceList::SimpleKey []`, and the list serves `15999.00` where the
  database says `20000.00`.
* **Why the symptom is misleading:** the annotation is present, names the right cache, and the
  attribute is spelled out explicitly — which reads as a deliberate decision rather than an
  oversight. `allEntries = false` looks like it means "don't clear the whole cache, just the relevant
  bit", when it actually means "clear one key, computed by the default key generator, which is
  almost certainly not in the cache".
* **Correct fix:**

  ```java
  @Caching(evict = {
      @CacheEvict(value = "price", allEntries = true),
      @CacheEvict(value = "priceList", allEntries = true),
      @CacheEvict(value = "priceStats", allEntries = true)
  })
  public int bulkUpdate(BulkPriceRequest request) { ... }
  ```

  Clearing the whole `price` cache is the honest answer for a bulk operation — you cannot express
  "evict these N keys" in a single annotation. A learner who instead injects `CacheManager` and evicts
  precisely the touched SKUs has done something better; credit it.
* **Concept:** `allEntries` semantics; bulk operations and cache invalidation.
* **Why a fresher makes it:** they read `allEntries = true` as "nuclear option, avoid" and set it to
  `false` to be careful. It is the one place where the cautious-looking choice is the broken one.
* **How to recognise it in the wild:** after any bulk operation, check `KEYS *`. Bulk writes almost
  always need `allEntries = true`, and if that is too blunt, the operation should evict explicitly
  through the `CacheManager`.

---

## Defect 7 — The `priceStats` cache is never invalidated by anything

* **Bug:** `stats()` is `@Cacheable(value = "priceStats")` and the name `priceStats` appears nowhere
  else in the codebase.
* **Affected component:** `service/PricingService` — every write method
* **Root cause:** a cache with a populating read and no invalidation on any write path.
* **Symptom:** `GET /api/pricing/stats` returns the same `totalListPrice` and `totalUnits` for an
  hour, regardless of how many prices and stock levels have changed underneath it.
* **Why the symptom is the easiest to miss:** it is an *aggregate*. Nobody checks it while debugging
  the individual SKU problems, no test asserts on it, and the numbers are plausible — you would have
  to add up the price column by hand to know they are wrong. It is also the cache most likely to be
  consumed by a dashboard nobody watches closely, which is precisely how a wrong number survives.
* **Correct fix:** include `@CacheEvict(value = "priceStats", allEntries = true)` in the `@Caching`
  group on **every** write method — update, stock, rename, bulk and delete.
* **The design point (hint G3):** this is the strongest argument for not solving the problem
  annotation-by-annotation. An aggregate that must be invalidated by every write in the system will
  eventually be missed by some write. Better options: derive the statistics from the (already cached)
  list rather than caching them separately; give this cache a short TTL and accept brief staleness
  as an explicit trade; or publish one domain event that a single listener turns into all the
  evictions. If the learner proposes any of these, that is the best possible answer to this project.
* **Concept:** aggregate caches; the maintenance burden of manual invalidation; choosing what *not*
  to cache.
* **Why a fresher makes it:** they add the statistics endpoint later, cache it because it is
  expensive, and never revisit the five write methods that now need to know about it.
* **How to recognise it in the wild:** for every cache in the system, ask "which writes invalidate
  this?" and check each one. A cache whose name appears exactly once in the codebase is a defect by
  inspection.

---

## Suggested fix order

1. **Defect 2** — the only one that errors on write; get the writes working first.
2. **Defect 1** — the single-entry staleness.
3. **Defect 3** — now that single reads are correct, the list disagreeing with them becomes visible.
4. **Defect 4** — the rename.
5. **Defect 5** — the delete.
6. **Defect 6** — the bulk path.
7. **Defect 7** — the statistics, which nothing has evicted throughout.

A learner who, partway through, stops and builds a single `@Caching` group used by every write method
has found the right shape. Encourage that over seven individual patches.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `aPriceCanBeFetched` | pass | — |
| `aPriceChangeIsVisibleOnTheVeryNextRead` | fail | Defect 1 |
| `aStockChangeSucceedsAndIsVisible` | error | Defect 2 |
| `theFullListReflectsAPriceChange` | fail | Defect 3 |
| `renamingLeavesTheEntryReadable` | error | Defect 4 |
| `aDeletedSkuStopsBeingServed` | fail | Defect 5 |

Baseline: `Tests run: 6, Failures: 3, Errors: 2`.

**Defects 6 and 7 are not covered by any test.** The bulk path and the statistics endpoint will still
be wrong when the suite goes green. If the learner reports the project finished on six passing tests,
ask them what `POST /bulk` does to `GET /api/pricing`, and what `GET /api/pricing/stats` says after a
price change.

## Verification commands

Run every check from a cold cache.

```bash
docker compose down -v && docker compose up -d
mvn clean package && java -jar target/pricing-service-1.0.0.jar > app.log 2>&1 &
mvn test        # 6/6 green

R() { docker exec debuglab15-redis redis-cli "$@" | tr -d '\r'; }
M() { docker exec debuglab15-mysql mysql -uroot -prootpw pricingdb -N -e "$1" 2>/dev/null; }
B=http://localhost:8080/api/pricing

# price
R FLUSHALL; curl -s -o /dev/null $B/KB-1001; curl -s -o /dev/null $B; curl -s -o /dev/null $B/stats
curl -s -o /dev/null -X PUT $B/KB-1001 -H 'Content-Type: application/json' -d '{"price":5999.00}'
curl -s $B/KB-1001 | grep -o '"price":[0-9.]*'                       # 5999.0
curl -s $B | grep -o '"sku":"KB-1001","name":"[^"]*","category":"[^"]*","price":[0-9.]*'
M "SELECT price FROM price_entries WHERE sku='KB-1001';"             # 5999.00

# stock
R FLUSHALL; curl -s -o /dev/null $B/MS-2002
curl -s -o /dev/null -w "%{http_code}\n" -X PATCH $B/MS-2002/stock -H 'Content-Type: application/json' -d '{"stock":500}'   # 200
curl -s $B/MS-2002 | grep -o '"stock":[0-9]*'                        # 500

# rename
R FLUSHALL; curl -s -o /dev/null $B/MN-3003
curl -s -o /dev/null -X PATCH "$B/MN-3003/name?name=Monitor%2027%20inch"
curl -s -o /dev/null -w "%{http_code}\n" $B/MN-3003                  # 200, not 500

# delete
R FLUSHALL; curl -s -o /dev/null $B/WC-6006
curl -s -o /dev/null -X DELETE $B/WC-6006
curl -s -o /dev/null -w "%{http_code}\n" $B/WC-6006                  # 404

# bulk
R FLUSHALL; curl -s -o /dev/null $B; curl -s -o /dev/null $B/NW-7001
curl -s -o /dev/null -X POST $B/bulk -H 'Content-Type: application/json' \
  -d '{"prices":{"NW-7001":20000.00,"HD-4004":399.00}}'
R KEYS '*'                                                            # nothing stale left
curl -s $B | grep -o '"sku":"NW-7001".*"price":[0-9.]*' | head -1     # 20000.0

# stats
R FLUSHALL; curl -s $B/stats
curl -s -o /dev/null -X PUT $B/HD-4004 -H 'Content-Type: application/json' -d '{"price":9999.00}'
curl -s $B/stats                                                      # totalListPrice must have moved
```
