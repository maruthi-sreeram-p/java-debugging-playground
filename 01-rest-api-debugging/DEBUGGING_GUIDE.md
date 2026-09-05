# Debugging Guide — 01 · Student Registry API

> This guide describes what you should be seeing and gives you graded hints.
> It does not tell you what is wrong or where. Work through the hints in order and stop as soon as
> you have a theory you can test.

---

## Project objective

The Student Registry is a straightforward CRUD service over a single table. Six endpoints, one
entity, one DTO, one mapper. There is nothing clever in this project — which is the point. Every
failure here is a mistake in how the HTTP layer is wired to the service layer, or in how data is
copied between objects.

Your job is to make the application behave exactly as `README.md` documents it, without changing
what the README promises.

## Expected behaviour

When the application is healthy:

1. `GET /api/students` returns the five seeded students.
2. `GET /api/students/1` returns that one student as JSON, and a missing id returns `404` with the
   documented error body.
3. `GET /api/students/search` accepts `name`, `department`, both, or neither, and always returns
   `200` with a (possibly empty) array.
4. `POST /api/students` returns `201 Created` and the body of the response is the *stored* student —
   generated id included.
5. A student you just created is identical in the database to what you sent, so it turns up in a
   search and reads back unchanged.
6. `PUT /api/students/{id}` replaces a student and returns `200`.
7. `DELETE /api/students/{id}` returns `204`.

## How to reproduce

Start the application:

```bash
mvn clean package
java -jar target/student-registry-1.0.0.jar
```

Then run each request from the **Expected functionality** section of `README.md`. Keep the
H2 console open at <http://localhost:8080/h2-console> (JDBC URL `jdbc:h2:mem:studentdb`, user `sa`,
blank password) so you can look at the `students` table directly instead of trusting the API.

`mvn test` gives you a starting point: one of the four tests passes, three do not. The tests do not
cover everything that is wrong.

---

## Known symptoms

### Symptom A — a single student cannot be fetched

```
GET /api/students/1

{"timestamp":"...","status":405,"error":"Method Not Allowed","path":"/api/students/1"}
```

The list endpoint on the same base path works perfectly.

### Symptom B — updating a student is rejected the same way

```
PUT /api/students/1   ->   405 Method Not Allowed
```

### Symptom C — searching by department alone fails

```
GET /api/students/search?department=CSE

{"timestamp":"...","status":400,"error":"Bad Request","path":"/api/students/search"}
```

Searching with `?name=aar` returns `200` and the right students. Searching with both parameters also
returns `200`. Searching with no parameters at all fails the same way as `?department=CSE`.

### Symptom D — creating a student returns a record with no id

```
POST /api/students
{"firstName":"Nisha","lastName":"Verma","email":"nisha.verma@campus.edu","department":"CSE","cgpa":8.8}

200 OK
{"id":null,"firstName":"Nisha","lastName":"Verma","email":"nisha.verma@campus.edu","department":"CSE","cgpa":8.8}
```

Two things are wrong with that response and the README tells you both of them.

### Symptom E — a created student changes after it is stored

Create the student above, then list everybody:

```
GET /api/students

... {"id":6,"firstName":"Nisha","lastName":"Verma","email":"nisha.verma@campus.edu","department":null,"cgpa":8.8}
```

The `POST` response said `"department":"CSE"`. The stored record says `null`. Consequently the new
student never appears in `GET /api/students/search?department=CSE`, which makes the search endpoint
look broken even though it is returning exactly what the database contains.

Editing that same student through `PUT` (once you can) sets the department correctly.

---

## Investigation hints

Read only as far as you need.

### Symptoms A and B — the 405s

> **Hint A1**
> `405` is not `404`. Spring is telling you that it *does* recognise this URL, but not with this
> verb. Before assuming the handler is missing, find out which handlers Spring actually registered.

> **Hint A2**
> Spring logs its complete URL-to-method mapping table. Add
> `logging.level.org.springframework.web.servlet.mvc.method.annotation=TRACE` to
> `application.properties`, restart, and read the list of paths it prints at start-up. Compare that
> list, line by line, against the table of endpoints in the README.

> **Hint A3**
> A path in that table is built from more than one annotation. Ask yourself what the *final* path of
> each handler method is, not what the annotation next to it says.

> **Hint A4**
> Symptom A and Symptom B look identical from outside, but they do not have the same explanation.
> One handler is answering at an address nobody is calling; a different handler is answering at the
> right address under the wrong verb. Fixing one will not fix the other.

### Symptom C — the 400 on search

> **Hint C1**
> A `400` from Spring before your code runs means the request never reached your method. Something
> about the request did not satisfy the method's signature.

> **Hint C2**
> Turn the default error message back on with `server.error.include-message=always` in
> `application.properties` and repeat the request. Spring will tell you precisely what it was
> missing.

> **Hint C3**
> The README says every search parameter is optional. Two parameters are declared on that handler.
> Are they declared the same way as each other?

### Symptom D — the create response

> **Hint D1**
> Look at what the create handler *returns*, and compare it with what the service layer *gave it*.
> They are not the same object.

> **Hint D2**
> An object that was never handed to the persistence layer cannot know the identifier the database
> generated. Which of the two objects in that method has been through `save()`?

> **Hint D3**
> Separately: check the status code the README promises for a successful creation against the one
> `ResponseEntity` is being built with.

### Symptom E — the disappearing department

> **Hint E1**
> The response to `POST` is not evidence about what was stored. Use the H2 console and look at the
> row. Trust the table, not the JSON.

> **Hint E2**
> The same field survives an update but not a create. Two different pieces of code copy data from
> the DTO onto an entity. Put them side by side and compare them field by field.

> **Hint E3**
> Nothing here throws, logs or warns. A field that is simply never assigned is silently `null`.
> Count the assignments, do not read the code for correctness.

---

## Expected logs and observations

* Nothing in this project fails at start-up. The banner prints, Hibernate creates the `students`
  table, `data.sql` runs, and the port opens. Every failure is at request time.
* `spring.jpa.show-sql=true` is already on, so you can see every statement Hibernate issues. The
  `insert` that runs for Symptom E is worth reading carefully.
* There are no stack traces for symptoms C, D and E — these are *behavioural* failures, not
  exceptions. Getting used to debugging without a stack trace is part of the exercise.
* `mvn test` reports `Tests run: 4, Failures: 3`. The three failing assertions are all about status
  codes, which will point you at symptoms A, C and D but not at E.

## Difficulty

**Beginner.** Expect 30–60 minutes. Five independent problems, no interaction between them except
that fixing Symptom C is what finally lets you *see* Symptom E through the search endpoint.

## Concepts being tested

* Spring MVC request mapping and path composition
* HTTP verb semantics and what `405` versus `404` actually means
* `@RequestParam` binding and optionality
* HTTP status code conventions for REST (`200` vs `201` vs `204`)
* DTO-to-entity mapping and the risk of hand-written mappers
* Reading the Spring handler-mapping table
* Verifying behaviour against the database rather than against the API response

## When you think you are done

Check all of these yourself before asking me:

- [ ] `mvn test` is green.
- [ ] Every `curl` in the README's **Expected functionality** section behaves as documented.
- [ ] A student created through `POST` reads back through `GET /api/students/{id}` with *every*
      field identical to what you sent.
- [ ] That same student is returned by a department search.
- [ ] A `GET`, `PUT` and `DELETE` against a non-existent id all return `404`, not `405` or `500`.

Then say **"I think I fixed the project"** and I will verify it against the answer key.
