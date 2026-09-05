# Debugging Guide — 14 · Product Catalogue Read API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

A cache is the only component in a system that is *supposed* to lie to you — it answers from a copy
instead of from the source. That makes cache bugs a category of their own: the API keeps returning
`200`, the data keeps looking plausible, and the only way to know whether anything is right is to
compare three separate things:

1. What the API returned.
2. What the database holds.
3. What is actually in Redis.

You need all three open at once. Get `redis-cli` working before you start.

**A warning about the test suite, before you run it.** Four of the five tests currently pass. Three
of them pass *because the cache is switched off entirely* — there is no cache, so there are no cache
bugs. Your first fix will make them start failing. That is not a regression: those tests were being
carried by an outage. Do not revert to make the suite green.

## Expected behaviour

1. The first request for a key logs `DATABASE READ`; repeats within the TTL do not.
2. `redis-cli DBSIZE` grows as you use the API, and `TTL` on an entry reports close to `600`.
3. Two different searches get two different answers and two different cache entries.
4. A missing product returns `404` every time.
5. The same product returns the same answer ten times in a row.
6. `/stats` reads the database on the first call only.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/product-catalogue-1.0.0.jar
```

In a second terminal:

```bash
docker exec -it debuglab14-redis redis-cli
> MONITOR
```

Leave that running while you make requests. It prints every command Spring sends to Redis. If you
see nothing, that tells you something. If you see the wrong key, that tells you something else.

The counting technique used throughout this guide:

```bash
java -jar target/product-catalogue-1.0.0.jar > app.log 2>&1 &
before=$(grep -c "DATABASE READ" app.log)
curl -s localhost:8080/api/catalogue/1 > /dev/null
curl -s localhost:8080/api/catalogue/1 > /dev/null
after=$(grep -c "DATABASE READ" app.log); echo "database reads: $((after-before))"
```

Two requests for the same product should cost **one** database read.

---

## Known symptoms

### Symptom A — nothing is cached at all

```
curl localhost:8080/api/catalogue/1     (three times)

DATABASE READ  product id=1
DATABASE READ  product id=1
DATABASE READ  product id=1
```

```
127.0.0.1:6379> DBSIZE
(integer) 0
127.0.0.1:6379> KEYS *
(empty array)
```

Redis is running, the application connected to it without complaint, the service methods carry cache
annotations, and `spring.cache.type=redis` is set. Redis is empty and stays empty. `MONITOR` shows no
traffic whatsoever.

**Everything below is invisible until this is fixed.** When you fix it, four new symptoms appear at
once. Work through them one at a time.

### Symptom B — the first request works and the second returns 500

```
GET /api/catalogue/1     ->  200 OK   {"id":1,"sku":"KB-1001", ...}
GET /api/catalogue/1     ->  500 Internal Server Error
```

Same URL, seconds apart, nothing changed in between. `FLUSHALL` in Redis makes it work once more —
and then fail again on the next call.

The stack trace mentions serialisation, and a Jackson class you have never referenced.

### Symptom C — one search returns another search's results

```
GET /api/catalogue/search?term=Monitor&category=Displays
[ {"sku":"MN-3003"}, {"sku":"MN-3004"} ]                 correct

GET /api/catalogue/search?term=Monitor&category=Cables
[ {"sku":"MN-3003"}, {"sku":"MN-3004"} ]                 there are no monitors in Cables
```

The second query is answered with the first query's data. Running it against the database by hand
returns nothing, correctly. And in Redis:

```
127.0.0.1:6379> KEYS catalogueSearch*
1) "catalogueSearch::Monitor"
```

One entry, for two different queries.

### Symptom D — a missing product returns 500 instead of 404

```
GET /api/catalogue/9999   ->  500
```

The service looks the product up, does not find it, and returns nothing. That path is supposed to
end in a `404`.

### Symptom E — entries disappear after a few seconds

```
127.0.0.1:6379> TTL categoryListing::Displays
(integer) 8
```

Eight seconds. `application.properties` says ten minutes.

Wait twelve seconds and call the same endpoint again: `DATABASE READ` in the log, and the key is
gone. The cache "works" but never for long enough to be worth anything.

### Symptom F — /stats never uses the cache

```
GET /api/catalogue/by-category?category=Displays    (twice)
    database reads: 1                                correct

GET /api/catalogue/stats                            (twice)
    category database reads: 8                       four categories, read twice each
```

```
127.0.0.1:6379> KEYS categoryListing*
1) "categoryListing::Displays"          only the one you fetched directly
```

The same method, reached two different ways, is cached one way and not the other. `/stats` is built
from the category listings, so once they are warm it should cost nothing.

---

## Investigation hints

### Symptom A — nothing is cached

> **Hint A1**
> Separate the two questions: *is Redis reachable?* and *is anything trying to use it?* `MONITOR`
> showing no traffic at all answers the second one — the application is not even attempting to cache.
> So the problem is not Redis, the connection, or the configuration of either.

> **Hint A2**
> Annotations do nothing on their own. Something has to create the proxy that interprets them.
> `@Transactional` has one such switch, `@Scheduled` has another, `@PreAuthorize` has a third. What
> is the equivalent for the cache abstraction?

> **Hint A3**
> Confirm it before you go looking: ask the application context whether it has a `CacheManager` bean
> at all. If Spring Boot has not created one, that is decisive — and it explains why the
> `RedisCacheConfiguration` bean in the configuration class is having no effect either.

> **Hint A4**
> This is the same class of mistake you met in project 02 (a bean built with `new`), project 08
> (self-invocation) and project 10 (method security not enabled): **an annotation that nothing is
> listening to**. The habit to build is to prove an annotation is live before relying on it.

### Symptom B — the second request fails

> **Hint B1**
> The first request *wrote* to the cache successfully. The second request *read* from it. So the
> value can be serialised and cannot be deserialised. Write it down that way — it narrows things
> considerably.

> **Hint B2**
> Find which serialiser the cache configuration uses for values, then read the exception's message
> for what it says it could not do. It will mention constructing an instance.

> **Hint B3**
> Look at the class being cached. How does Jackson build an object when reading JSON, and what does
> that class offer it? Count the constructors and look at whether the fields can be set after
> construction.

> **Hint B4**
> Several fixes work: a no-argument constructor with mutable fields, `@JsonCreator` with
> `@JsonProperty` on the constructor parameters, or the `jackson-module-parameter-names` module
> combined with compiling with `-parameters`. Pick one and be able to say why the class could be
> written and not read.

> **Hint B5**
> Worth noticing: this defect only exists because the value crosses a process boundary. An in-process
> cache would never have exposed it. Anything you put in Redis must survive a round trip through your
> serialiser — and that is worth a test.

### Symptom C — the shared cache entry

> **Hint C1**
> Look at the cache entry's name in Redis: `catalogueSearch::Monitor`. Now look at the method that
> populates it. How many arguments does it take, and how many of them appear in that key?

> **Hint C2**
> Find the `key` expression on the annotation. It is a SpEL expression naming one parameter. What
> happens to two calls that agree on that parameter and differ on the other?

> **Hint C3**
> A cache key must identify **everything the result depends on**. If two different inputs can produce
> the same key, the cache will confidently serve one caller the other's answer. Work out what the key
> should be, and how to write it.

> **Hint C4**
> Think about how far this generalises. If the method also took a user id, the same defect would be
> serving one customer another customer's data. That is the version of this bug that ends up in the
> news — and it is the same single missing parameter.

### Symptom D — the 500 on a missing product

> **Hint D1**
> Read the exception. It is thrown by the cache, not by your code, and it says what it will not
> accept.

> **Hint D2**
> Find the line in the cache configuration that sets that policy, and read what it means. Then ask
> what the service method returns when the product is not found.

> **Hint D3**
> Two directions, and they are a genuine design decision rather than a typo:
> * Allow the cache to store the empty result. That means a missing product is remembered, so
>   repeated requests for a nonexistent id do not hammer the database. It also means a product
>   created a moment later stays invisible until the entry expires.
> * Keep the policy and stop the method returning the empty value — tell the annotation not to cache
>   it, or have the method signal absence some other way.
>
> Both are defensible. Decide which you want for a **catalogue** specifically, where products are
> added regularly, and be ready to justify it.

> **Hint D4**
> Whichever you choose, the endpoint must return `404`, not `500`.

### Symptom E — the short-lived entries

> **Hint E1**
> Read the property's name, including its unit. Then find where that value is used and read the
> method it is passed to.

> **Hint E2**
> This is the same class of defect as the token expiry in project 11: a number whose unit is recorded
> only in the name of the variable it came from.

> **Hint E3**
> Fix it so the two agree, and consider whether carrying a `Duration` rather than a bare `long` would
> have made the mistake impossible.

### Symptom F — /stats bypassing the cache

> **Hint F1**
> The identical method is cached when the controller calls it and not cached when `/stats` calls it.
> So the defect is not in the annotation or the key — it is in *how the method is reached*.

> **Hint F2**
> The cache abstraction works by wrapping the bean in a proxy. Look at the code inside `/stats` and
> ask whether that call goes through the proxy or straight to the object.

> **Hint F3**
> You have met this exact mechanism before, in project 08. Same cause, different annotation. The
> remedies are the same: move the cached method to another bean, inject the bean into itself, or take
> the proxy explicitly.

> **Hint F4**
> There is also a design answer worth considering: should `/stats` be doing four cache lookups at
> all, or should it be one cached method of its own? Either is fine — but if you cache the whole
> result, think about what has to happen to that entry when a product is added.

---

## Expected logs and observations

* `DATABASE READ` inside a cached method means the cache was missed. Counting those lines is the
  single most useful measurement in this project.
* The controller logs how long each request took. Once the cache works, a hit is visibly faster than
  a miss — though on ten seeded rows the difference is small. Trust the read count over the timing.
* `redis-cli MONITOR` shows every `GET`/`SET`/`EXPIRE`. Silence means the abstraction is not
  engaged; the wrong key means the key expression is wrong.
* `TTL <key>` returns the seconds remaining, `-1` for no expiry, and `-2` if the key does not exist.
* Symptoms C, E and F produce **no error at all** — `200` with wrong or needlessly expensive
  behaviour.
* `FLUSHALL` between experiments. A stale entry from a previous run will waste your time.

## Difficulty

**Advanced.** Expect two to three hours. Six defects.

Symptom A gates all the others: while nothing is cached, no cache can misbehave. Fixing it reveals
four defects at once and breaks three currently-passing tests. Take them one at a time and use
`FLUSHALL` between attempts.

## Concepts being tested

* Enabling the cache abstraction, and annotations that nothing interprets
* Proxy-based interception and self-invocation, in a third guise
* Cache key generation, and why a key must cover every input
* Value serialisation across a process boundary, and what Jackson needs to rebuild an object
* Time-to-live configuration and units
* Caching of empty results, and the trade-off it represents
* Reading Redis directly as the arbiter of what is really cached

## When you think you are done

- [ ] `mvn test` is green (5 tests).
- [ ] Two requests for the same product produce exactly **one** `DATABASE READ`.
- [ ] Ten consecutive requests for the same product all return `200` with identical bodies.
- [ ] `TTL` on any cache entry reports close to `600` seconds, and an entry is still there after a
      minute.
- [ ] `?term=Monitor&category=Displays` and `?term=Monitor&category=Cables` return different results
      and occupy **two** entries in `KEYS catalogueSearch*`.
- [ ] `/api/catalogue/9999` returns `404` every time.
- [ ] Calling `/stats` twice produces no `DATABASE READ` on the second call.
- [ ] `FLUSHALL`, then exercise every endpoint twice: the second pass produces no `DATABASE READ` at
      all.
- [ ] You can explain why three tests started failing after your first fix.

Then say **"I think I fixed the project"**.
