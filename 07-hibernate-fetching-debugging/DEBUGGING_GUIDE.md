# Debugging Guide — 07 · Course Enrolment API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Two questions run through every problem in this project:

1. **When is the persistence context open, and is my code inside it?**
2. **How many queries did that actually cost?**

Three of the defects here throw. Three do not — they return correct-looking data while doing far
more work than they should, or quietly return `null` where a number belongs. Those three are the
ones worth slowing down for, because in a real system they are the ones that reach production.

**The SQL log is your instrument.** `show-sql` and `generate_statistics` are already on. Before you
change anything, learn to count statements per request.

## Expected behaviour

1. Every endpoint returns `200`.
2. `GET /api/courses` costs a **constant** number of queries — adding a course must not add queries.
3. `GET /api/courses/{id}` returns the course with its modules and their lessons.
4. `GET /api/courses/{id}/summary` returns real numbers for `moduleCount` and `lessonCount`.
5. `GET /api/courses/paged` reads only the page it was asked for.
6. `GET /api/reports/catalogue` returns totals: 5 courses, 13 modules, 843 minutes.

## How to reproduce

```bash
mvn clean package
java -jar target/course-enrolment-1.0.0.jar
```

Counting queries per request, from a second terminal:

```bash
# Windows Git Bash / Linux / macOS
java -jar target/course-enrolment-1.0.0.jar > app.log 2>&1 &

before=$(grep -c "^Hibernate:" app.log); curl -s http://localhost:8080/api/courses > /dev/null
after=$(grep -c "^Hibernate:" app.log); echo "statements: $((after-before))"
```

Get that working first. It is the single most useful thing in this project.

`mvn test` runs five tests: two pass, three fail. The two most interesting defects are **not**
covered by any test.

---

## Known symptoms

### Symptom A — the course detail endpoint always fails

```
GET /api/courses/1
500 Internal Server Error
```

```
org.hibernate.LazyInitializationException: failed to lazily initialize a collection of role:
  com.debuglab.enrolment.entity.Course.modules: could not initialize proxy - no Session
```

Meanwhile `GET /api/courses` reads the very same collection on the very same entity and works
perfectly.

### Symptom B — the catalogue listing costs 19 queries

```
GET /api/courses     ->  200 OK, 5 courses
statements: 19
```

The response is correct. The listing needs a course's code, title, credits and a module count — and
it never shows a single lesson. Add a sixth course and the number climbs. There is no error and no
warning anywhere.

Two separate decisions combine to produce that 19. Find both.

### Symptom C — the summary returns nulls where numbers belong

```
GET /api/courses/1/summary

{"title":"Introduction to Programming","moduleCount":null,"lessonCount":null}
```

`title` is right. The other two are always `null`, for every course. No exception, no warning, and
the query itself runs — you can see it in the SQL log, and if you run it by hand in the H2 console
it returns the correct counts.

### Symptom D — pagination reads the whole table

```
GET /api/courses/paged?page=0&size=2   ->  CS101, CS201
GET /api/courses/paged?page=1&size=2   ->  CS310, CS340
GET /api/courses/paged?page=2&size=2   ->  CS420
```

The pages look right. But the log says:

```
WARN o.h.o.j.i.JpaQueryImpl : HHH90003004: firstResult/maxResults specified with
  collection fetch; applying in memory
```

Read that warning literally, then work out what it means for a catalogue with fifty thousand
courses.

### Symptom E — the catalogue report always fails

```
GET /api/reports/catalogue
500 Internal Server Error
```

```
java.util.concurrent.CompletionException: org.hibernate.LazyInitializationException:
  failed to lazily initialize a collection of role: com.debuglab.enrolment.entity.Course.modules:
  could not initialize proxy - no Session
```

The same exception as Symptom A, reached a different way, and it needs a different fix. Note the
wrapper exception — it is telling you something about *where* the failure happened.

---

## Investigation hints

### Symptom A — the lazy collection

> **Hint A1**
> "no Session" means the persistence context that loaded this entity has already closed. So the
> question is: when does it close, and was your code still inside it?

> **Hint A2**
> Read `spring.jpa.open-in-view` in `application.properties` and look up precisely what that setting
> does. With the default value this endpoint would work. Understanding *why* it would work — and why
> relying on that is a bad idea — is the point of this symptom.

> **Hint A3**
> Compare the failing service method with the listing method next to it, which touches the same
> collection successfully. There is one annotation on one of them and not the other.

> **Hint A4**
> Now the deeper choice, because there is more than one right answer. You can extend the persistence
> context to cover the mapping work, or you can load the graph you need up front so that nothing is
> lazy by the time you map it. Both are legitimate; they have different costs. Decide which one this
> endpoint deserves, and hold that thought — Symptom B is about the cost of getting it wrong.

### Symptom B — 19 queries for a listing

> **Hint B1**
> Get the statement count working first. Then run the listing and read the 19 statements in order.
> Group them: how many are about courses, how many about modules, how many about lessons?

> **Hint B2**
> One query for the courses and then one more per course is the classic shape. It has a name.
> Look at what the listing does inside its loop — it calls one innocent-looking method on a
> collection. What does that method have to do before it can answer?

> **Hint B3**
> That accounts for six of the nineteen. Where do the other thirteen come from? The listing never
> mentions lessons. Something is loading them anyway — look at how the lesson collection is mapped
> and what its fetch strategy says.

> **Hint B4**
> A collection mapped so that it is always loaded is loaded even by code that does not want it, and
> there is no way for a caller to opt out. The opposite default can always be opted *into*, per
> query. That asymmetry is the whole argument.

> **Hint B5**
> For the count itself: you do not need to load a collection to know how big it is. Think about what
> a database can answer in one statement, and what shape of query would let this endpoint cost two
> queries instead of nineteen.

### Symptom C — the null counts

> **Hint C1**
> The query works. Copy it into the H2 console (adjusting for JPQL versus SQL) and confirm it
> returns three columns with the right values. So the data is right and something between the result
> set and the JSON is losing two of them.

> **Hint C2**
> The return type is an interface with three getters. Spring Data implements it at runtime by
> matching each getter against something in the query result. Match them up yourself, by name, one
> at a time. Two of the three do not line up.

> **Hint C3**
> An alias in a JPQL `SELECT` is what a projection getter binds to. `getTitle()` binds to an alias
> called `title`. What would `getModuleCount()` need the alias to be?

> **Hint C4**
> When a projection getter finds nothing to bind to, it returns `null` rather than failing. That is
> why this defect is silent, and it is why interface projections should always be covered by a test
> that asserts on the values, not just the status code.

### Symptom D — in-memory pagination

> **Hint D1**
> Look at the query behind this endpoint. It does two things at once: it fetches an association
> eagerly for this one query, and it is handed a page request.

> **Hint D2**
> Think about what the result set of that SQL actually looks like. If a course has three modules,
> how many rows come back for that one course? Now ask: if the database applied `LIMIT 2` to those
> rows, would you get two courses?

> **Hint D3**
> Hibernate knows it cannot let the database do the limiting without returning the wrong answer, so
> it does the honest thing — and tells you, in the warning. Read the warning again. "Applying in
> memory" means it fetched *how much*?

> **Hint D4**
> The standard solution is two queries: first find the identifiers for the page you want, then fetch
> the full graph for exactly those identifiers. Look up "fetch join with pagination" or Hibernate's
> `@BatchSize` and `hibernate.default_batch_fetch_size`, and pick an approach. Whichever you choose,
> the warning must be gone and the SQL must contain a `limit`.

### Symptom E — the report on a worker thread

> **Hint E1**
> The exception is the same as Symptom A, but the fix that works for A will not work here. Before
> you try anything, ask: on which thread does the aggregation run, and on which thread was the
> persistence context bound?

> **Hint E2**
> Spring binds the transaction and its `EntityManager` to the thread that started it. Work handed to
> another thread is outside it — annotating the calling method changes nothing for the worker.

> **Hint E3**
> Read the `CompletionException` wrapper. It is Java telling you "this failed somewhere other than
> where you are standing". Any time you see it around a persistence exception, look for a thread
> boundary.

> **Hint E4**
> Two honest fixes. Either load everything the worker needs **before** handing it over — so the
> worker only ever touches initialised data — or do not hand the entities over at all and aggregate
> in the database instead. Think about which one still works when the catalogue has fifty thousand
> courses. Note that Symptom B has been pushing you towards the same answer.

---

## Expected logs and observations

* The application starts cleanly. Every problem is at request time.
* `Hibernate: select ...` lines are your query counter. `grep -c "^Hibernate:"` before and after a
  request is all the tooling you need.
* `hibernate.generate_statistics=true` also prints a session summary after each request — number of
  statements prepared, collections fetched, and time spent. Read it.
* `HHH90003004` is the only warning in this project and it is easy to scroll past. It is Symptom D's
  entire evidence.
* Symptoms B, C and D produce **no exception** and return `200`. Symptoms A and E throw.
* Note that the exception in Symptom E is wrapped and the one in Symptom A is not. That difference
  is the clue to E.

## Difficulty

**Intermediate.** Expect two to three hours.

All six defects are independent — none of them hides another. What makes this project hard is that
half of them do not announce themselves at all, so you have to go looking. If you only fix the ones
that throw, you have done a third of the work.

## Concepts being tested

* `FetchType.LAZY` versus `FetchType.EAGER`, and why eager cannot be opted out of
* Persistence context lifetime, and what `spring.jpa.open-in-view` really controls
* `@Transactional` on a read path, and detached entities
* Thread affinity of a transaction and its `EntityManager`
* The N+1 select problem: recognising it, counting it, and fixing it with a fetch join or an
  aggregate query
* Fetch joins combined with pagination, and why the database cannot apply the limit
* Spring Data interface projections and how getters bind to query aliases
* Reading the SQL log and Hibernate statistics as a debugging instrument

## When you think you are done

- [ ] `mvn test` is green (5 tests).
- [ ] `GET /api/courses` issues **at most two** statements, and that number does not change when you
      insert a sixth course into `data.sql`.
- [ ] `GET /api/courses/1` returns three modules, each with its lessons.
- [ ] `GET /api/courses/1/summary` returns `moduleCount: 3` and `lessonCount: 6`.
- [ ] `GET /api/courses/paged?page=1&size=2` returns CS310 and CS340, the log contains **no**
      `HHH90003004`, and the SQL contains a `limit`.
- [ ] `GET /api/reports/catalogue` returns `totalCourses: 5`, `totalModules: 13`,
      `totalMinutes: 843`.
- [ ] You did **not** fix anything by setting `spring.jpa.open-in-view=true`. That makes two symptoms
      disappear without addressing either of them, and it makes Symptom B worse everywhere else in
      the application. If you tried it, revert it.
- [ ] You can state, for each endpoint, how many queries it costs and why.

Then say **"I think I fixed the project"**.
