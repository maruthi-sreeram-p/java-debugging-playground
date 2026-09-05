# Debugging Guide — 03 · Library Catalogue API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

The happy paths in this project all work. Listing the catalogue works, borrowing an available book
works, adding a new title works. **Everything that is broken is on an error path**, which is exactly
the code nobody tests by hand and nobody notices until a client integrates against it.

Your job is to make the error contract in `README.md` true: for every documented failure, the API
must return the documented status code with the documented body.

## Expected behaviour

| Request | Must return |
|---|---|
| `GET /api/books/999` | `404` |
| `POST /api/books` with a blank title | `400` with a `fields` object |
| `POST /api/books` with an ISBN already present | `409` |
| `POST /api/borrow` with an unknown `bookId` | `404` |
| `POST /api/borrow` for a book with no copies left | `409` |
| `POST /api/return/999` | `404` |
| `POST /api/return/{id}` on an already-returned record | `409` |

And in every case, the HTTP status line and the `status` field inside the body agree.

## How to reproduce

```bash
mvn clean package
java -jar target/library-catalogue-1.0.0.jar
```

Use `curl -i` — not plain `curl` — for everything in this project. You need to see the **status
line**, and several of the problems here are invisible if you only look at the response body.

Book id `4` (*Spring in Action*) is seeded with zero available copies, so it is your out-of-stock
fixture.

`mvn test` runs four tests: two pass, two fail. They cover two of the five problems.

---

## Known symptoms

### Symptom A — every business error is a 500

```
GET /api/books/999

HTTP/1.1 500
{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/books/999"}
```

Duplicate ISBN, unknown book, out-of-stock — all of them come back as `500`, and the body is
Spring's default error shape rather than the one the README documents. The domain exceptions are
definitely being thrown: you can see them in the application log.

### Symptom B — returning a book that does not exist "succeeds"

```
POST /api/return/999

HTTP/1.1 200
(empty body)
```

Returning the same borrow record twice does the same thing: `200`, empty body, and the second return
does not increment `availableCopies`. The application log shows only:

```
WARN  c.d.library.service.BorrowService : Could not complete return for borrow record 999
```

No stack trace, no exception class, no cause.

### The next three symptoms are only visible once Symptom A is resolved.

Once the error handler is actually doing its job, three more problems appear underneath it.

### Symptom C — one error path is still a 500

Unknown book on `GET /api/books/{id}` now correctly returns `404`. Unknown book on
`POST /api/borrow` still returns `500`:

```
POST /api/borrow  {"bookId":999,"memberName":"Priya"}

HTTP/1.1 500
{"timestamp":"...","status":500,"error":"Internal Server Error","message":"Something went wrong. Please contact support."}
```

Both endpoints look up a book by id and both are supposed to report `404`. One does, one does not.

### Symptom D — the status line and the body disagree

```
POST /api/borrow  {"bookId":4,"memberName":"Rohan"}

HTTP/1.1 200
{"timestamp":"...","status":409,"error":"Conflict","message":"All copies of 'Spring in Action' are currently on loan"}
```

The body says `409`. The status line says `200`. The kiosk client reads the status line, so as far as
it is concerned the loan succeeded.

### Symptom E — validation got worse, not better

Before you touched anything, posting a book with a blank title returned `400` — the wrong *body*,
but at least the right status. After fixing Symptom A it returns:

```
POST /api/books  {"isbn":"111","title":"","author":"X","totalCopies":2}

HTTP/1.1 500
{"timestamp":"...","status":500,"error":"Internal Server Error","message":"Something went wrong. Please contact support."}
```

This is not a mistake in your fix. Something that was previously dormant is now active. Do not undo
your work on Symptom A — work out what it switched on.

---

## Investigation hints

### Symptom A — everything is a 500

> **Hint A1**
> The handler class exists, the methods exist, the annotations on the methods are right, and the
> exceptions really are thrown. So the question is narrower than it looks: is Spring MVC *aware*
> of this class as a source of exception handlers?

> **Hint A2**
> Being a bean and being an exception-handling bean are two different things. Compare the annotation
> on the class with the annotation the Spring documentation requires for a class whose
> `@ExceptionHandler` methods apply across controllers.

> **Hint A3**
> Confirm it before you change it: set
> `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver=DEBUG`
> and restart. Watch what it says it has registered, and whether it consults your class when an
> exception occurs.

### Symptom B — the silent return

> **Hint B1**
> A `200` with an empty body means a handler ran and produced `null`. Trace back: what did the
> service method return, and under what circumstance does it return that?

> **Hint B2**
> Read the whole body of the returning method, including the part after the main logic. Ask what
> happens to an exception raised inside it.

> **Hint B3**
> The `WARN` line is written by code you can find. Notice what it does *not* contain — no exception
> class, no cause, no stack trace. That is a choice someone made, and it is the reason this problem
> is hard to see from the log.

> **Hint B4**
> The design rule in the README is "services throw and controllers do not catch". Which half of that
> rule is being broken here, and what would the method look like if it followed it?

### Symptom C — one lookup reports 404, the other does not

> **Hint C1**
> Put the two lookups side by side. Both call the same repository method on the same repository.
> The difference is in what happens when the lookup finds nothing.

> **Hint C2**
> `Optional` offers more than one way to fail. One of them lets you choose the exception; the other
> chooses one for you. Find out which exception the no-argument form throws, then check whether the
> global handler has a case for it.

> **Hint C3**
> This is the general shape of the problem: a handler exists for the exception you *meant* to throw,
> and the code throws a different one. The catch-all then hides the difference.

### Symptom D — status line versus body

> **Hint D1**
> The body of that response is built correctly — the right status number, the right message. So the
> defect is not in constructing the error; it is in how the error is handed back.

> **Hint D2**
> A `ResponseEntity` carries a status *and* a payload, and they are set independently. Compare that
> one handler method with the others in the same class, character by character, on the return line.

### Symptom E — validation regression

> **Hint E1**
> Nothing about validation changed. What changed is that a class full of handlers went live. So one
> of those handlers is now claiming an exception it should not claim.

> **Hint E2**
> When several `@ExceptionHandler` methods could apply, Spring picks the most specific match by
> walking up the exception's class hierarchy. If the only match it can find is very high up that
> hierarchy, it still counts as a match.

> **Hint E3**
> Which exception does Spring throw when `@Valid` fails on a `@RequestBody` argument? Is there a
> handler for that exception in this project — and if not, which handler is catching it instead?

> **Hint E4**
> There are two things to fix here, not one. Adding the specific handler is the first. The second is
> a judgement call about the catch-all: what does it currently do with the information it was given,
> and would you be able to debug a production incident from what it leaves behind?

---

## Expected logs and observations

* The application starts cleanly. Every problem here is at request time.
* `logging.level.com.debuglab.library=DEBUG` is already set.
* For Symptom A, the log shows the domain exception propagating all the way out to Tomcat — proof
  that it was thrown and that nothing handled it.
* For Symptom B, the log shows a one-line `WARN` and nothing else. Getting used to noticing what is
  *absent* from a log is the skill this symptom trains.
* For Symptom C, once the catch-all is active you will see `Unexpected failure: ...` in the log with
  the exception's message. That message names the exception's real type, and it is not the one you
  expect.
* Compare `availableCopies` in the `books` table (H2 console) before and after each borrow/return.
  Several of these symptoms leave the stock count wrong, which is the business consequence.

## Difficulty

**Beginner→Intermediate.** Expect 60–90 minutes.

Symptom A is a gate: three of the remaining four are invisible until it is fixed, and fixing it makes
one thing appear to get worse. That staging is deliberate — it is what real debugging feels like.

## Concepts being tested

* `@RestControllerAdvice` / `@ControllerAdvice` registration and how Spring discovers exception
  handlers
* Exception-to-status mapping and honouring an error contract
* `ResponseEntity` status versus body
* `@ExceptionHandler` resolution order and the danger of a catch-all
* Bean Validation failures and which exception they raise for a `@RequestBody`
* `Optional.orElseThrow()` with and without a supplier
* Exception swallowing, loss of the root cause, and why `catch (Exception e)` in a service is a smell
* Reading a log for what is missing from it

## When you think you are done

- [ ] `mvn test` is green (4 tests).
- [ ] Every row of the **Expected behaviour** table above returns the documented status.
- [ ] The status line and the body's `status` field agree on every error response.
- [ ] Validation failures return `400` with a `fields` object naming the rejected field.
- [ ] After a failed return, `availableCopies` in the database is unchanged — no partial effect.
- [ ] The application log for an unexpected failure contains enough to diagnose it.
- [ ] No controller contains a `try`/`catch`, and no service swallows an exception it cannot handle.

Then say **"I think I fixed the project"**.
