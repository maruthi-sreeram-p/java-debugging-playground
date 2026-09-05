# SOLUTION — 01 · Student Registry API

> **Sealed answer key.** Do not read this until you have finished the project or explicitly asked
> for the solution. Five planted defects.

---

## Defect 1 — Fetch-by-id handler is registered at a doubled path

* **Bug:** `StudentController.getStudentById` is annotated `@GetMapping("/api/students/{id}")`
  inside a class annotated `@RequestMapping("/api/students")`.
* **Affected component:** `controller/StudentController.java`
* **Root cause:** Spring MVC concatenates the class-level `@RequestMapping` path with the
  method-level path. The handler is therefore registered at
  `/api/students/api/students/{id}`, not at `/api/students/{id}`.
* **Why the symptom is misleading:** the request does not produce `404`. It produces
  `405 Method Not Allowed`, because a *different* handler — `updateStudent`, mapped
  `@PostMapping("/{id}")` — does match the path `/api/students/1`, just not the `GET` verb. Spring
  finds a path match with no verb match and answers `405`. The learner is pushed toward "my GET
  method is broken" when the GET method simply is not at that address.
* **Correct fix:**

  ```java
  @GetMapping("/{id}")
  public ResponseEntity<StudentDto> getStudentById(@PathVariable Long id) { ... }
  ```

* **Concept:** request-mapping path composition; the difference between `404` (no handler for this
  path) and `405` (handler exists for the path, not for this method).
* **Why a fresher makes it:** they copy the full URL out of the README or Postman into the
  method-level annotation, not realising the class-level annotation is already a prefix. It is one
  of the two or three most common Spring MVC mistakes.
* **How to recognise it in the wild:** the start-up handler-mapping log (or the actuator `/mappings`
  endpoint) shows a path with a repeated prefix. Any `405` on a URL you are sure exists should send
  you straight to that table.

---

## Defect 2 — Update handler uses the wrong HTTP verb

* **Bug:** `updateStudent` is annotated `@PostMapping("/{id}")` while the API contract documents
  `PUT /api/students/{id}`.
* **Affected component:** `controller/StudentController.java`
* **Root cause:** wrong annotation, nothing more. `PUT` requests find a matching path with no
  matching verb, so Spring returns `405`.
* **Correct fix:**

  ```java
  @PutMapping("/{id}")
  public ResponseEntity<StudentDto> updateStudent(...) { ... }
  ```

* **Concept:** HTTP verb semantics; `POST` creates a subordinate resource, `PUT` replaces a known
  one.
* **Why a fresher makes it:** `@PostMapping` is the annotation they type most often, and because
  `POST /api/students/1` *works* when they test it by hand, nothing tells them they are wrong.
* **Interaction with Defect 1:** this is the defect that turns Defect 1's `404` into a `405`. Fixing
  Defect 2 alone changes Symptom A from `405` to `404` — a good moment to check whether the learner
  notices that the symptom moved rather than disappeared.
* **How to recognise it in the wild:** the same `405` signature. Ask "which verbs are registered for
  this path?" before asking "why is my handler broken?".

---

## Defect 3 — `name` is a required request parameter on the search endpoint

* **Bug:**

  ```java
  @RequestParam String name,
  @RequestParam(required = false) String department
  ```

  `@RequestParam` defaults to `required = true`.
* **Affected component:** `controller/StudentController.java`
* **Root cause:** a missing `name` parameter makes Spring reject the request with
  `MissingServletRequestParameterException` before the handler body ever runs, producing `400`.
  `department` was correctly declared optional, which is what makes the inconsistency findable.
* **Why the symptom is misleading:** the error body is bare (`spring.error.include-message` is not
  enabled), so the learner sees an unexplained `400` on an endpoint whose logic they can read and
  believe is correct. The service method already handles `null` for both arguments — the failure is
  upstream of it.
* **Correct fix:**

  ```java
  @RequestParam(required = false) String name,
  @RequestParam(required = false) String department
  ```

* **Concept:** `@RequestParam` binding, parameter optionality, where request binding sits relative to
  handler execution.
* **Why a fresher makes it:** they add the second parameter later, remember `required = false` for
  it, and never revisit the first. Or they test only the `?name=` case, which works.
* **How to recognise it in the wild:** an unexplained `400` with an empty message. Turn on
  `server.error.include-message=always` (or read the `DEBUG` log from
  `org.springframework.web.servlet.mvc.method.annotation`) and the exception names the parameter.

---

## Defect 4 — Create handler returns the request object instead of the saved one

* **Bug:**

  ```java
  studentService.createStudent(studentDto);
  return ResponseEntity.ok(studentDto);
  ```

  The service's return value is discarded, and the incoming DTO is echoed back. The status is also
  `200 OK` where the contract says `201 Created`.
* **Affected component:** `controller/StudentController.java`
* **Root cause:** the request DTO never went through `save()`, so its `id` is still `null`. The
  service *did* return a properly mapped DTO containing the generated id; the controller threw it
  away.
* **Correct fix:**

  ```java
  StudentDto created = studentService.createStudent(studentDto);
  return ResponseEntity.status(HttpStatus.CREATED).body(created);
  ```

  (Returning a `Location` header is a further improvement but is not required by the README.)
* **Concept:** identity generation happens at persist time; REST status-code conventions.
* **Why a fresher makes it:** the echoed object "looks right" in Postman — every field they typed
  comes back. Only the `id` betrays it, and it is easy to skim past a `null`.
* **How to recognise it in the wild:** a create endpoint whose response has a null/zero identifier,
  or a client that cannot follow up its own `POST` with a `GET`. Trace whether the returned object is
  the one the persistence layer handed back.

---

## Defect 5 — `StudentMapper.toEntity` silently drops `department`

* **Bug:** `toEntity` assigns `firstName`, `lastName`, `email` and `cgpa` but never `department`.
  `copyToExisting` (used by the update path) assigns all five.
* **Affected component:** `mapper/StudentMapper.java`
* **Root cause:** the create path builds its entity through `toEntity`, so every student created via
  `POST` is stored with `department = null` regardless of what the client sent.
* **Why the symptom is misleading — this is the key defect of the project:**
  1. The `POST` response still shows `"department":"CSE"`, because of Defect 4 the response is the
     *request* object. Defect 4 actively conceals Defect 5.
  2. The visible failure surfaces later and elsewhere: the record looks wrong in
     `GET /api/students`, and the new student is missing from
     `GET /api/students/search?department=CSE`. Both point the learner at the read path and at the
     search endpoint, neither of which is faulty.
  3. There is no exception, no log line and no warning. A field that is never assigned is just
     `null`.
* **Correct fix:**

  ```java
  student.setDepartment(dto.getDepartment());
  ```

* **Concept:** DTO/entity mapping; the maintenance cost of hand-written mappers; the difference
  between what an API *echoes* and what it *stored*.
* **Why a fresher makes it:** they write `toEntity` and `copyToExisting` at different times, or add
  the `department` field to the model afterwards and update only the mapper they happened to have
  open. There is no compiler error and no test unless someone wrote one.
* **How to recognise it in the wild:** any "the API said it saved it but the column is empty"
  report. The discipline is to verify against the database, never against the response body, and to
  diff two mappers that copy the same object.

---

## Suggested fix order

1. **Defect 3** (search `400`) — independent, and it restores the tool you need for step 5.
2. **Defect 2** (`PUT` verb) — this changes Symptom A from `405` to `404`, which is diagnostic.
3. **Defect 1** (doubled path) — now `GET /api/students/{id}` works.
4. **Defect 4** (create response) — now the `POST` response reflects reality...
5. **Defect 5** (mapper) — ...which is what finally makes the missing department obvious.

A learner who fixes 5 before 4 has genuinely reasoned from database state rather than from the API
response, which is the better outcome. Say so if they did.

## Test expectations

| Test | Before | After all fixes |
|---|---|---|
| `listAllStudents_returnsEverySeededStudent` | pass | pass |
| `fetchSingleStudent_returnsThatStudent` | fail (`405`) | pass |
| `createStudent_returnsCreatedResourceWithGeneratedId` | fail (`200` vs `201`) | pass |
| `createdStudent_isVisibleThroughDepartmentSearch` | fail (`400`) | pass |

Note that `createdStudent_isVisibleThroughDepartmentSearch` fails twice over: first with `400`
(Defect 3), then — once that is fixed — with a count of `1` instead of `2` (Defect 5). If the
learner reports "I fixed the search bug and the test still fails", that is the expected staging, not
a regression.

## Verification commands

```bash
mvn test                                                     # expect 4/4 green

curl -i http://localhost:8080/api/students/1                 # 200, full record
curl -i -X PUT http://localhost:8080/api/students/1 \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Aarav","lastName":"Sharma","email":"aarav.sharma@campus.edu","department":"IT","cgpa":8.6}'
                                                             # 200
curl -i "http://localhost:8080/api/students/search?department=CSE"   # 200
curl -i "http://localhost:8080/api/students/search"                  # 200, all 5
curl -i -X POST http://localhost:8080/api/students \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Nisha","lastName":"Verma","email":"nisha.verma@campus.edu","department":"CSE","cgpa":8.8}'
                                                             # 201, id present, department present
curl -s http://localhost:8080/api/students/6                 # department must be "CSE", not null
curl -i http://localhost:8080/api/students/999               # 404, not 405
```
