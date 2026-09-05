# SOLUTION — 14 · Product Catalogue Read API

> **Sealed answer key.** Six planted defects.

---

## Defect 1 — `@EnableCaching` is missing

* **Bug:** no class in the application carries `@EnableCaching`. `CacheConfig` declares a
  `RedisCacheConfiguration` bean and nothing consumes it.
* **Affected component:** `config/CacheConfig` (and the application class)
* **Root cause:** without `@EnableCaching`, Spring registers no `CacheInterceptor`, so `@Cacheable`
  is inert metadata. Spring Boot's `CacheAutoConfiguration` is itself conditional on the caching
  infrastructure being enabled, so **no `CacheManager` bean is created at all** — which is why the
  `RedisCacheConfiguration` bean, `spring.cache.type=redis` and the Redis connection all sit there
  doing nothing.
* **Symptom:** every request logs `DATABASE READ`. `DBSIZE` is `0`, `KEYS *` is empty, and `MONITOR`
  shows no traffic. The application connects to Redis (the health check passes) but never sends it a
  command.
* **Why the symptom is misleading:** everything that *looks* like configuration is present and
  correct — the dependency, the property, the cache configuration bean, the annotations on the
  service. There is no error, no warning, and no log line saying "caching is off". A learner
  reasonably concludes the problem is in the Redis connection or the serialiser, and goes to debug
  the wrong layer entirely.
* **Correct fix:**

  ```java
  @Configuration
  @EnableCaching
  public class CacheConfig { ... }
  ```

  On the application class is equally fine.
* **Concept:** the cache abstraction must be switched on; annotations require an interpreter.
* **Why a fresher makes it:** they add the starter, the properties and the annotations — three of the
  four steps — and every tutorial mentions `@EnableCaching` exactly once, in a code block they
  skimmed.
* **How to recognise it in the wild:** an empty cache is the tell. Before debugging *what* is cached,
  prove *that* something is. Ask the context for a `CacheManager`; if there isn't one, stop looking
  anywhere else. This is the fourth appearance of this pattern in the lab (projects 02, 08, 10 and
  now 14) and it is worth naming as a family: **an annotation nothing is listening to.**

**This defect gates all five others** and, importantly, is why three tests currently pass.

---

## Defect 2 — `ProductDto` cannot be deserialised by Jackson

* **Bug:** `ProductDto` has `final` fields, a single all-arguments constructor, and no no-argument
  constructor. The cache serialises values with `GenericJackson2JsonRedisSerializer`.
* **Affected component:** `dto/ProductDto`, with `config/CacheConfig` choosing the serialiser
* **Root cause:** Jackson can *write* the object (it has getters) but cannot *read* it back — there
  is no default constructor and no `@JsonCreator`, so it has no way to construct an instance.
  Writing succeeds; reading throws.
* **Symptom:** the first request for a product returns `200`; the second returns `500`. `FLUSHALL`
  makes it work exactly once more. The exception is
  `InvalidDefinitionException: cannot deserialize from Object value (no delegate- or property-based
  Creator)`.
* **Why the symptom is the best one in this project:** it is precisely the "first request works but
  subsequent requests behave differently" failure from the lab brief, and it is genuinely
  disorienting — the same URL, seconds apart, with nothing changed. It also *looks* intermittent,
  because clearing the cache resets it. Developers report this one as "flaky".
* **Correct fix — any of these:**

  1. Add a no-argument constructor and setters (or drop `final`).
  2. Annotate the constructor:

     ```java
     @JsonCreator
     public ProductDto(@JsonProperty("id") Long id, @JsonProperty("sku") String sku, ...) { ... }
     ```

  3. Add the `jackson-module-parameter-names` module and compile with `-parameters` (Spring Boot's
     parent POM already sets this) so Jackson can match constructor parameters by name.

  Option 2 keeps the DTO immutable and is the nicest answer. `implements Serializable` on the class
  is a red herring — it would only matter with the JDK serialiser, which this configuration does not
  use. If the learner "fixes" it by switching to `JdkSerializationRedisSerializer`, point out that
  they have made the cache contents unreadable to every other tool and language.
* **Concept:** serialisation across a process boundary; what Jackson needs to reconstruct an object;
  cached values are not the same as returned values.
* **Why a fresher makes it:** immutable DTOs with all-args constructors are good practice, and the
  class works perfectly for *responses* — Jackson only ever writes those. Nothing warns you that
  putting it in Redis means it must be readable too.
* **How to recognise it in the wild:** any cached type should have a round-trip test — serialise,
  deserialise, assert equality. It takes three lines and catches this whole class of bug.

---

## Defect 3 — The search cache key omits a parameter

* **Bug:**

  ```java
  @Cacheable(value = "catalogueSearch", key = "#term")
  public List<ProductDto> search(String term, String category) { ... }
  ```

* **Affected component:** `service/CatalogueService.search`
* **Root cause:** the key covers `term` but not `category`. Two calls that share a term and differ in
  category collide on one entry, and the second caller receives the first caller's results.
* **Symptom:** `?term=Monitor&category=Displays` correctly returns two monitors. `?term=Monitor&
  category=Cables` returns **the same two monitors**, although there are no monitors in Cables. Redis
  holds a single key, `catalogueSearch::Monitor`.
* **Why the symptom is misleading:** the query is correct, the database is correct, and running the
  same search by hand returns the right (empty) answer. Only the cached path is wrong, and it is
  wrong by returning *plausible, well-formed data belonging to a different question*. Nothing about
  the response looks like an error.
* **Correct fix:**

  ```java
  @Cacheable(value = "catalogueSearch", key = "{#term, #category}")
  ```

  `key = "#term + '::' + #category"` works too but is fragile against values containing the
  separator. Omitting `key` entirely makes Spring use `SimpleKeyGenerator`, which includes all
  parameters — also correct, and arguably the safest default.
* **Concept:** cache keys must cover every input the result depends on.
* **Why a fresher makes it:** they write the key for the parameter that "matters", or they add the
  second parameter to the method later and never revisit the annotation above it.
* **How to recognise it in the wild — and why this is the most serious defect here:** the same
  mistake with a user identifier is a data-leak incident. If this method took a `customerId` and the
  key omitted it, the cache would serve one customer another customer's data, with a `200` and no
  trace in any log. Make the learner say this out loud. The rule: **every parameter goes in the key
  unless you can prove the result does not depend on it.**

---

## Defect 4 — Empty results and `disableCachingNullValues()`

* **Bug:** `CacheConfig` calls `.disableCachingNullValues()`, and `findById` returns `null` when the
  product does not exist.
* **Affected component:** `config/CacheConfig` and `service/CatalogueService.findById`
* **Root cause:** with null caching disabled, Spring's Redis cache **throws** rather than silently
  skipping when a cached method returns `null`:
  `IllegalArgumentException: Cache 'product' does not allow 'null' values. Avoid storing null via
  '@Cacheable(unless="#result == null")' or configure RedisCache to allow 'null' via
  RedisCacheConfiguration.` The controller's `404` branch is never reached.
* **Symptom:** `GET /api/catalogue/9999` returns `500`. The controller plainly contains a `404` path.
* **Why the symptom is misleading:** the exception is thrown by the caching layer *after* the method
  returns successfully, so the service and controller both look correct in isolation. And with
  Defect 1 in place the endpoint returns a perfectly good `404` — so this only appears once caching
  starts working, which reads like the learner's own fix having broken it.
* **Correct fix — this is a genuine design choice, not a typo (hint D3):**

  * **Do not cache the empty result:**

    ```java
    @Cacheable(value = "product", key = "#id", unless = "#result == null")
    ```

    A missing product is never cached, so a product added a moment later is visible immediately —
    but every request for a nonexistent id hits the database, which is a denial-of-service vector if
    ids are guessable.

  * **Or allow it**, by removing `.disableCachingNullValues()`. Absence is then remembered for the
    TTL, protecting the database — at the cost of a newly-created product being invisible for up to
    ten minutes.

  For a catalogue where products are added regularly, `unless` is usually the better answer; a
  shorter TTL for negative entries is the sophisticated version. **Accept either, but require a
  justification** — a learner who just deletes the line without thinking has not engaged with the
  trade-off. Either way, the endpoint must end up returning `404`.

  A third answer worth crediting highly: have the service throw a `ProductNotFoundException` and map
  it in a `@RestControllerAdvice`. Nothing is cached, the `404` is explicit, and the ambiguity of
  `null` disappears.
* **Concept:** negative caching; `unless` and `condition` on `@Cacheable`; cache stampede on
  nonexistent keys.
* **Why a fresher makes it:** `disableCachingNullValues()` is copied from a blog post as a
  "sensible default", and nobody tests the not-found path against a warm cache.
* **How to recognise it in the wild:** test the not-found path *with the cache enabled*. Any
  `IllegalArgumentException` mentioning null values is this exact defect.

---

## Defect 5 — TTL configured in minutes, applied as seconds

* **Bug:**

  ```java
  @Value("${catalogue.cache.ttl-minutes}") long ttlMinutes
  ...
  .entryTtl(Duration.ofSeconds(ttlMinutes))
  ```

  with `catalogue.cache.ttl-minutes=10`.
* **Affected component:** `config/CacheConfig`
* **Root cause:** a unit mismatch. Every entry lives ten **seconds** instead of ten minutes.
* **Symptom:** `TTL categoryListing::Displays` reports single digits. Wait twelve seconds, call again,
  and the database is read once more. The cache technically works and is worth almost nothing.
* **Why the symptom is misleading:**
  * A developer testing by hand issues two requests within a couple of seconds, sees one
    `DATABASE READ`, and concludes the cache is working. It *is* working — for ten seconds.
  * In production it presents as "the cache isn't helping" or "Redis hit rate is terrible", which
    gets investigated as a capacity or key-design problem rather than a one-word bug.
  * It also intermittently masks the other defects: an entry that has expired between two of your
    test requests makes Defect 2 and Defect 3 look sporadic.
* **Correct fix:**

  ```java
  .entryTtl(Duration.ofMinutes(ttlMinutes))
  ```

  The better answer, and the same lesson as project 11's token expiry: bind a `Duration` directly
  rather than a bare number —

  ```java
  @Value("${catalogue.cache.ttl}") Duration ttl      // catalogue.cache.ttl=10m
  ```

  Spring Boot parses `10m`, `600s`, `PT10M`, and the unit can no longer be lost.
* **Concept:** units in configuration; `Duration` over primitives.
* **Why a fresher makes it:** `ofSeconds` and `ofMinutes` are adjacent in autocomplete, and the only
  record of the intended unit is the property's name.
* **How to recognise it in the wild:** always assert the TTL after configuring it — `redis-cli TTL`
  or the equivalent. A cache with a hit rate far below what you expect usually has a TTL problem, not
  a key problem.

---

## Defect 6 — `stats()` self-invokes the cached `byCategory()`

* **Bug:**

  ```java
  public Map<String, Object> stats() {
      for (String category : productRepository.findAllCategories()) {
          int size = byCategory(category).size();      // direct call on `this`
          ...
      }
  }
  ```

* **Affected component:** `service/CatalogueService.stats`
* **Root cause:** the cache abstraction is proxy-based. A call made on `this` from inside the same
  bean never reaches the proxy, so `@Cacheable` is not applied. `/stats` therefore reads the database
  once per category on every call, and never populates or consults the cache.
* **Symptom:** two `/stats` calls produce eight category database reads (four categories, twice).
  Calling `/api/catalogue/by-category` directly produces exactly one read for two calls. `KEYS
  categoryListing*` shows only the categories fetched through the controller.
* **Why the symptom is misleading:** **the same method is cached one way and not cached the other
  way.** The annotation is right, the key is right, and it demonstrably works — through one entry
  point. That asymmetry is the diagnostic, and it is the third time this exact mechanism appears in
  the lab: project 02 (an object built with `new`), project 08 (`@Transactional` self-invocation) and
  now `@Cacheable`. By this point the learner should recognise it; if they do, say so.
* **Correct fix — any of:**
  * Move the cached read methods into a separate bean (e.g. `CatalogueReader`) and inject it.
  * Inject `CatalogueService` into itself (`@Lazy`) and call `self.byCategory(category)`.
  * `AopContext.currentProxy()` with `@EnableAspectJAutoProxy(exposeProxy = true)`.

  There is also a legitimate design answer (hint F4): make `/stats` a single `@Cacheable` method of
  its own. Accept it — but ask what has to happen to that entry when a product is added, which is
  precisely the subject of project 15.
* **Concept:** proxy-based AOP and self-invocation, applied to caching.
* **Why a fresher makes it:** calling your own method is the most natural thing in Java, and nothing
  indicates the annotation lives on a wrapper rather than on the method.
* **How to recognise it in the wild:** the same code behaving differently by entry point. When an
  annotation appears not to apply, ask whether the call went through the proxy.

---

## Suggested fix order

1. **Defect 1** — mandatory. Expect three passing tests to start failing.
2. **Defect 2** — the `500` on the second request; nothing else can be tested reliably until reads
   work.
3. **Defect 5** — fix the TTL early, because a ten-second expiry makes everything else look
   intermittent.
4. **Defect 3**, **Defect 4**, **Defect 6** — independent, any order.

## Test expectations

| Test | Before | After fixing Defect 1 | Ultimately fails because of |
|---|---|---|---|
| `aProductCanBeFetched` | pass | pass | — |
| `theCacheAbstractionIsActiveAndStoresTheProduct` | **fail** | pass | Defect 1 |
| `fetchingTheSameProductTwiceSucceedsBothTimes` | **pass** | **fails** | Defect 2 |
| `searchResultsAreScopedToTheCategoryAsked` | **pass** | **fails** | Defect 3 |
| `aProductThatDoesNotExistReturnsNotFound` | **pass** | **fails** | Defect 4 |

Baseline: `Tests run: 5, Failures: 1, Errors: 0`.

**This table is the most important thing to discuss with the learner.** Three tests pass at the start
*because there is no cache*, and their first correct fix breaks all three. The guide warns them not
to revert, but check that they understood *why*: a negative test that passes because the feature is
switched off is not evidence of anything. It is the same trap as project 10's authorization tests,
in a different costume.

**Defects 5 and 6 are not covered by any test.** A TTL of ten seconds is longer than any test takes,
and the self-invocation defect only shows up as extra database reads, which no assertion here counts.
Both must be found with `redis-cli` and the log.

## Verification commands

```bash
docker compose down -v && docker compose up -d
mvn clean package
java -jar target/product-catalogue-1.0.0.jar > app.log 2>&1 &
mvn test        # 5/5 green

R() { docker exec debuglab14-redis redis-cli "$@"; }
R FLUSHALL

# two reads of the same product cost one database read
before=$(grep -c "DATABASE READ" app.log)
curl -s localhost:8080/api/catalogue/1 > /dev/null
curl -s localhost:8080/api/catalogue/1 > /dev/null
after=$(grep -c "DATABASE READ" app.log); echo "reads: $((after-before))"     # 1

# ten reads all succeed
for i in $(seq 1 10); do curl -s -o /dev/null -w "%{http_code} " localhost:8080/api/catalogue/1; done; echo

# TTL is ten minutes
R TTL "product::1"                                                            # ~600

# two searches, two entries, two answers
curl -s "localhost:8080/api/catalogue/search?term=Monitor&category=Displays" | python -c "import sys,json;print(len(json.load(sys.stdin)))"   # 2
curl -s "localhost:8080/api/catalogue/search?term=Monitor&category=Cables"   | python -c "import sys,json;print(len(json.load(sys.stdin)))"   # 0
R KEYS "catalogueSearch*"                                                     # two keys

# missing product
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/catalogue/9999    # 404 (repeat it - still 404)

# stats uses the cache
R FLUSHALL
curl -s -o /dev/null localhost:8080/api/catalogue/stats
before=$(grep -c "DATABASE READ" app.log)
curl -s -o /dev/null localhost:8080/api/catalogue/stats
after=$(grep -c "DATABASE READ" app.log); echo "second /stats reads: $((after-before))"   # 0

# and the whole-suite check: warm everything, then do it all again
R FLUSHALL
for u in "/1" "/search?term=Monitor&category=Displays" "/by-category?category=Cables" "/stats"; do
  curl -s -o /dev/null "localhost:8080/api/catalogue$u"; done
before=$(grep -c "DATABASE READ" app.log)
for u in "/1" "/search?term=Monitor&category=Displays" "/by-category?category=Cables" "/stats"; do
  curl -s -o /dev/null "localhost:8080/api/catalogue$u"; done
after=$(grep -c "DATABASE READ" app.log); echo "second pass reads: $((after-before))"     # 0
```
