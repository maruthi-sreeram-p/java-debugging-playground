# SOLUTION — 07 · Course Enrolment API

> **Sealed answer key.** Six planted defects.

---

## Defect 1 — `courseDetail` is not transactional, with `open-in-view=false`

* **Bug:** `CourseService.courseDetail(Long id)` carries no `@Transactional`, while every other read
  method in the class is `@Transactional(readOnly = true)`.
* **Affected component:** `service/CourseService.courseDetail`
* **Root cause:** with `spring.jpa.open-in-view=false` there is no request-scoped persistence
  context. `courseRepository.findById(id)` runs inside `SimpleJpaRepository`'s own read-only
  transaction, which commits and closes as the call returns. The `Course` handed back is **detached**.
  `toDetail(course)` then calls `course.getModules()`, and the uninitialised collection has no
  session to load itself from.
* **Symptom:** `GET /api/courses/{id}` → `500`, `LazyInitializationException: failed to lazily
  initialize a collection of role: Course.modules: could not initialize proxy - no Session`.
* **Why the symptom is misleading:** the listing endpoint reads the *same* collection on the *same*
  entity type and works fine, so the mapping is obviously not at fault. The difference is one
  annotation on a different method, which is easy to miss when the two methods sit ten lines apart.
* **Correct fix — two acceptable answers:**

  1. Extend the persistence context to cover the mapping:

     ```java
     @Transactional(readOnly = true)
     public CourseDetail courseDetail(Long id) { ... }
     ```

  2. Better, and consistent with the rest of the project: load the graph you need in one query, so
     nothing is lazy by the time it is mapped:

     ```java
     @Query("SELECT c FROM Course c LEFT JOIN FETCH c.modules WHERE c.id = :id")
     Optional<Course> findByIdWithModules(@Param("id") Long id);
     ```

  Accept either. Answer 1 is the minimal fix and is what most people reach for; answer 2 is what
  Defect 2 is teaching them to prefer. **Do not accept `spring.jpa.open-in-view=true`** — see the
  note at the end.
* **Concept:** persistence context lifetime; detached entities; `open-in-view`.
* **Why a fresher makes it:** on a default Spring Boot setup (`open-in-view=true`) this code works.
  It breaks the day someone turns that setting off — often in a different sprint, by a different
  developer, for good reasons — and then only on the endpoints that were relying on it.
* **How to recognise it in the wild:** `could not initialize proxy - no Session` always means the
  same thing: you touched a lazy association after its session closed. Find the transaction boundary
  and see which side of it you are on.

---

## Defect 2 — N+1 in the catalogue listing

* **Bug:** `CourseService.listCourses` calls `course.getModules().size()` for every course:

  ```java
  for (Course course : courseRepository.findAll()) {
      items.add(new CourseListItem(..., course.getModules().size()));
  }
  ```

* **Affected component:** `service/CourseService.listCourses`
* **Root cause:** `findAll()` issues one query for the courses; `getModules()` then forces a separate
  `select` per course to initialise the lazy collection. With 5 courses that is 1 + 5 = 6 statements
  before Defect 3 is even counted. The endpoint only needs a *count*, and it loads whole entities to
  get it.
* **Symptom:** `GET /api/courses` returns correct data and costs 19 statements. Adding a course adds
  more. No error, no warning.
* **Why the symptom is misleading:** the response is right, the code reads naturally, and on five
  rows it is fast. It becomes a production incident at ten thousand rows, by which time nobody
  remembers writing it. This is the defect most likely to survive code review.
* **Correct fix:** let the database do the counting. Either an aggregate query:

  ```java
  @Query("SELECT new com.debuglab.enrolment.dto.CourseListItem(c.id, c.code, c.title, c.credits, COUNT(m)) "
       + "FROM Course c LEFT JOIN c.modules m GROUP BY c.id, c.code, c.title, c.credits")
  List<CourseListItem> listWithModuleCounts();
  ```

  (note `CourseListItem` would need a `long` count parameter), or a fetch join if the modules are
  genuinely needed. Either way the endpoint must cost a constant number of statements.
  `@BatchSize` on the collection is a partial answer — it reduces N+1 to N/batch+1, not to a
  constant. Credit it, then push for the aggregate.
* **Concept:** the N+1 select problem; loading data you do not need; measuring query counts.
* **Why a fresher makes it:** `.size()` on a list is free in plain Java. Nothing in the code says
  "this line issues a database query". It is invisible unless you read the SQL log.
* **How to recognise it in the wild:** count statements per request. Any count proportional to the
  number of rows returned is N+1. Hibernate statistics, `datasource-proxy`, or `grep -c "^Hibernate:"`
  all work.

---

## Defect 3 — `CourseModule.lessons` is mapped `FetchType.EAGER`

* **Bug:**

  ```java
  @OneToMany(mappedBy = "module", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
  private List<Lesson> lessons = new ArrayList<>();
  ```

* **Affected component:** `entity/CourseModule.java`
* **Root cause:** an eagerly-mapped collection is loaded every time its owning entity is loaded, by
  every query, with no way for a caller to opt out. So the moment Defect 2 loads the modules, all 13
  lesson collections are loaded too — accounting for the remaining 13 of the 19 statements, on an
  endpoint that never mentions lessons.
* **Symptom:** part of Symptom B. Invisible except through the query count.
* **Why the symptom is misleading:** it is in a different file from the code that suffers for it. A
  learner counting statements in `listCourses` finds 6 they can explain and 13 they cannot, and
  those 13 are caused by an annotation two classes away. Fixing Defect 2 alone leaves 13 of them.
* **Correct fix:**

  ```java
  @OneToMany(mappedBy = "module", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  ```

  The endpoints that genuinely need lessons (`courseDetail`, `pagedCourses`) must then fetch them
  explicitly — which is the point. If the learner fixes Defect 1 with a fetch join, they will need to
  extend it to `LEFT JOIN FETCH m.lessons`.
* **Concept:** `EAGER` versus `LAZY` as a *default* that callers cannot override; why the JPA
  specification's eager default for `@ManyToOne` is a known misfeature.
* **Why a fresher makes it:** they hit a `LazyInitializationException` (Defect 1, most likely), read
  that `EAGER` makes it go away, and apply it. It does make it go away — everywhere, permanently, at
  a cost nobody measures. This is the single most common "fix" that creates a worse problem.
* **How to recognise it in the wild:** grep the entity package for `FetchType.EAGER`. Each one is a
  decision made once, for one use case, imposed on every other query in the system. The default for
  every collection should be lazy, with fetching decided per query.

---

## Defect 4 — Projection getters do not match the query aliases

* **Bug:**

  ```java
  @Query("SELECT c.title AS title, COUNT(DISTINCT m.id) AS modules, COUNT(l.id) AS lessons ...")
  Optional<CourseSummary> summaryFor(@Param("id") Long id);
  ```

  with

  ```java
  public interface CourseSummary {
      String getTitle();
      Long getModuleCount();     // needs an alias called moduleCount
      Long getLessonCount();     // needs an alias called lessonCount
  }
  ```

* **Affected component:** `repository/CourseRepository.summaryFor` and `dto/CourseSummary`
* **Root cause:** Spring Data implements a closed interface projection by binding each getter to a
  result alias of the matching name. `getTitle()` finds `title`. `getModuleCount()` looks for
  `moduleCount`, finds nothing, and yields `null`. So does `getLessonCount()`.
* **Symptom:** `{"title":"...","moduleCount":null,"lessonCount":null}` for every course. `200 OK`, no
  exception, no warning. The SQL runs and returns the right numbers.
* **Why the symptom is misleading:** the query is correct and verifiable by hand in the H2 console,
  so the learner concludes the problem must be in serialisation or in the DTO. The failure mode of a
  projection is silence — an unbound getter returns `null` rather than throwing, which makes this
  exactly the kind of defect that a status-code-only test will never catch.
* **Correct fix:** make the aliases match the getters:

  ```java
  @Query("SELECT c.title AS title, COUNT(DISTINCT m.id) AS moduleCount, COUNT(l.id) AS lessonCount "
       + "FROM Course c LEFT JOIN c.modules m LEFT JOIN m.lessons l "
       + "WHERE c.id = :id GROUP BY c.title")
  ```

  Renaming the getters to `getModules()`/`getLessons()` also works but changes the JSON contract the
  README documents, so it is the worse answer. A class-based DTO projection with a constructor
  expression is an equally good alternative — it would have failed loudly at start-up instead of
  silently at runtime, which is worth pointing out.
* **Concept:** Spring Data interface projections; alias binding; silent failure modes.
* **Why a fresher makes it:** they write the JPQL first with short, natural aliases and design the
  interface separately. Nothing connects the two except a string match that no compiler checks.
* **How to recognise it in the wild:** any projection field that is `null` when the underlying query
  clearly returns a value. Always assert on projection *values* in tests, never just on the status.

---

## Defect 5 — Fetch join combined with `Pageable`

* **Bug:**

  ```java
  @Query("SELECT c FROM Course c JOIN FETCH c.modules")
  Page<Course> findAllWithModules(Pageable pageable);
  ```

* **Affected component:** `repository/CourseRepository.findAllWithModules`
* **Root cause:** joining a collection multiplies rows — 5 courses with 13 modules produce 13 rows.
  A database-level `LIMIT 2` on that result set would return two *rows*, which is one course and part
  of another, not two courses. Hibernate detects the combination, refuses to produce a wrong answer,
  and instead **loads the entire result set into memory** and paginates there, warning:
  `HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory`.
* **Symptom:** the pages are *correct*. `page=0` gives CS101 and CS201, `page=1` gives CS310 and
  CS340, `page=2` gives CS420. The only evidence of a problem is one `WARN` line and the absence of
  `limit` in the generated SQL.
* **Why the symptom is misleading:** everything the API promises is delivered. There is no wrong
  data, no exception, and no slowness at five courses. On a real catalogue this endpoint loads every
  row of two tables into heap on every request and is a straightforward path to an
  `OutOfMemoryError`. It is the purest example in this lab of "correct output, broken
  implementation".
* **Correct fix — the standard two-query pattern:**

  ```java
  @Query("SELECT c.id FROM Course c")
  Page<Long> findCourseIdPage(Pageable pageable);

  @Query("SELECT DISTINCT c FROM Course c LEFT JOIN FETCH c.modules WHERE c.id IN :ids")
  List<Course> findAllWithModulesByIds(@Param("ids") List<Long> ids);
  ```

  The first is paginated in the database; the second fetches the graph for exactly those ids.
  Alternatively, drop the fetch join and set `hibernate.default_batch_fetch_size` (or `@BatchSize`)
  so the collections load in a small constant number of batched queries. Both are correct; either
  must eliminate the warning and produce SQL containing `limit`.
* **Concept:** fetch joins and row multiplication; why the database cannot apply the limit; in-memory
  pagination.
* **Why a fresher makes it:** it is the obvious way to write "a page of courses with their modules",
  it compiles, and it returns the right answer. The warning is one line in a busy log.
* **How to recognise it in the wild:** grep your logs for `HHH90003004`. Treat it as an error, not a
  warning. More generally: whenever a query both fetch-joins a collection and paginates, one of the
  two has to go.

---

## Defect 6 — The report aggregates detached entities on a worker thread

* **Bug:**

  ```java
  public Map<String, Object> buildReport() {
      List<Course> courses = courseRepository.findAll();
      return CompletableFuture.supplyAsync(() -> aggregate(courses)).join();
  }
  ```

  `aggregate` walks `course.getModules()` and `module.getLessons()`.
* **Affected component:** `service/CatalogueReportService`
* **Root cause:** two things combine. The entities are detached the moment `findAll()` returns
  (`buildReport` is not transactional and `open-in-view` is off), *and* the aggregation runs on a
  ForkJoinPool thread. Spring binds a transaction and its `EntityManager` to the thread that started
  it, so even making `buildReport` transactional would not help the worker — the second half of the
  lesson.
* **Symptom:** `GET /api/reports/catalogue` → `500`,
  `java.util.concurrent.CompletionException: org.hibernate.LazyInitializationException: ... no
  Session`.
* **Why the symptom is misleading:** it is the *same exception text* as Defect 1, so a learner who
  fixed Defect 1 by adding `@Transactional` will try that here first. It will not work, and the
  reason it does not work is the actual lesson. The `CompletionException` wrapper is the only clue in
  the message that a thread boundary is involved.
* **Correct fix — two acceptable answers:**

  1. Initialise everything the worker needs **before** handing it over, inside a transaction —
     realistically by fetching the graph in one query:

     ```java
     @Query("SELECT DISTINCT c FROM Course c LEFT JOIN FETCH c.modules m LEFT JOIN FETCH m.lessons")
     List<Course> findAllWithModulesAndLessons();
     ```

     then `CompletableFuture.supplyAsync(() -> aggregate(courses))` is safe because nothing is lazy.
  2. Better: do not send entities across the boundary at all. Aggregate in the database with a
     projection query and hand the worker plain data (or drop the worker thread — the aggregation is
     trivial once the database does the summing).

  Removing the `CompletableFuture` entirely and making the method `@Transactional(readOnly = true)`
  also fixes it and is a perfectly defensible answer; ask the learner whether the worker thread was
  ever earning its keep.
* **Concept:** thread affinity of the persistence context; why `@Transactional` does not follow work
  onto another thread; the danger of passing managed entities outside their transaction.
* **Why a fresher makes it:** "push slow work onto a thread pool" is good advice they have correctly
  internalised. Nothing warns them that the objects they are passing are lazily-loaded proxies with
  a hard dependency on a session that is about to close.
* **How to recognise it in the wild:** a persistence exception wrapped in `CompletionException`,
  `ExecutionException` or an `@Async` stack frame. The rule: never pass a managed entity to another
  thread. Pass DTOs, or ids and let the other thread load what it needs in its own transaction.

---

## The trap to watch for

`spring.jpa.open-in-view=true` makes Symptom A disappear and does nothing for Symptom E. It is
**not** an acceptable fix and the guide's checklist calls it out explicitly. If the learner tries it:

* It hides Defect 1 rather than fixing it — the lazy load still happens, just later, outside any
  meaningful transaction, during response rendering.
* It makes Defect 2 worse everywhere in the application, because every accidental lazy load in a view
  now silently succeeds and issues a query nobody counted.
* It holds a database connection for the entire duration of the HTTP response, including the time
  spent serialising JSON to a slow client.

Spring Boot logs a warning about this setting at start-up for exactly these reasons. It is worth
walking through with the learner if they reach for it, because the reasoning generalises: *a setting
that makes a symptom disappear everywhere is usually hiding a class of bugs, not fixing one.*

---

## Suggested fix order

All six are independent. A sensible route:

1. **Defect 1** — get the detail endpoint working; it is the loudest.
2. **Defect 4** — the null counts; quick and self-contained.
3. **Defects 2 and 3 together** — count the statements, then fix both causes. Fixing only one leaves
   the count high, which is the check that they found both.
4. **Defect 5** — the pagination warning.
5. **Defect 6** — last, because the right answer reuses the fetch-join thinking from 1, 2 and 5.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `catalogueListsEveryCourse` | pass | — |
| `courseDetailIncludesItsModulesAndLessons` | error | Defect 1 |
| `courseSummaryReportsModuleAndLessonCounts` | fail | Defect 4 |
| `transcriptListsTheCoursesAStudentIsEnrolledOn` | pass | — |
| `catalogueReportAggregatesEveryCourse` | error | Defect 6 |

Baseline: `Tests run: 5, Failures: 1, Errors: 2`.

**Defects 2, 3 and 5 are not covered by any test.** They are performance and scalability defects that
return correct data, and no assertion on a response body can catch them. That is deliberate — the
point is that the learner has to go looking in the SQL log. If they report the project fixed with all
five tests green but the listing still costing 19 statements, they have missed the most important
part of the project.

## Verification commands

```bash
mvn test    # 5/5 green

java -jar target/course-enrolment-1.0.0.jar > app.log 2>&1 &

# statement count must be <= 2, and unchanged if a sixth course is added
before=$(grep -c "^Hibernate:" app.log); curl -s http://localhost:8080/api/courses > /dev/null
after=$(grep -c "^Hibernate:" app.log); echo "listing statements: $((after-before))"

curl -s http://localhost:8080/api/courses/1 | head -c 200          # 3 modules with lessons
curl -s http://localhost:8080/api/courses/1/summary                # moduleCount 3, lessonCount 6
curl -s "http://localhost:8080/api/courses/paged?page=1&size=2"    # CS310, CS340
curl -s http://localhost:8080/api/reports/catalogue | python -c "import sys,json;d=json.load(sys.stdin);print(d['totalCourses'],d['totalModules'],d['totalMinutes'])"
#  -> 5 13 843

grep -c "HHH90003004" app.log        # must be 0
grep -i "limit" app.log | head       # the paged query must contain a limit
grep -rn "FetchType.EAGER" src/      # must be empty
grep -n "open-in-view" src/main/resources/application.properties   # must still be false
```
