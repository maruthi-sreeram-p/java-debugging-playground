# SOLUTION — 03 · Library Catalogue API

> **Sealed answer key.** Five planted defects.

---

## Defect 1 — `GlobalExceptionHandler` is annotated `@Component`, not `@RestControllerAdvice`

* **Bug:** the class carries `@Component`. Its `@ExceptionHandler` methods are therefore never
  registered with `ExceptionHandlerExceptionResolver`.
* **Affected component:** `exception/GlobalExceptionHandler.java`
* **Root cause:** `ExceptionHandlerExceptionResolver` builds its advice cache by scanning for beans
  annotated with `@ControllerAdvice` (of which `@RestControllerAdvice` is the meta-annotated
  variant). A plain `@Component` is a perfectly valid bean that nothing ever consults. Every domain
  exception therefore escapes to the servlet container and Spring Boot's default `/error` handling
  turns it into `500` with the default body shape.
* **Symptom:** every business error — `404`, `409` — arrives as `500` with Spring's default error
  JSON (note the `path` field, which the project's own `ApiError` does not have; that field is the
  tell that the response did *not* come from this class).
* **Why the symptom is misleading:** the class looks complete and correct. Every handler method is
  individually right. The learner reads the handler for `BookNotFoundException`, confirms it returns
  `404`, and concludes the problem must be that the exception is not reaching it — sending them to
  debug the service layer instead of the registration.
* **Correct fix:**

  ```java
  @RestControllerAdvice
  public class GlobalExceptionHandler { ... }
  ```

  `@ControllerAdvice` plus `@ResponseBody` on each method is equivalent and also acceptable.
* **Concept:** how Spring MVC discovers cross-controller exception handlers.
* **Why a fresher makes it:** "annotate it so Spring picks it up" is the rule they have internalised,
  and `@Component` is the annotation they reach for. It *is* picked up — as a bean. Nothing warns
  them that being a bean is insufficient.
* **How to recognise it in the wild:** compare the error body you got with the error body your code
  builds. If the shape is Spring's default (`timestamp`/`status`/`error`/`path`, no custom fields),
  your advice never ran. Confirm with
  `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver=DEBUG`.

**This defect gates defects 3, 4 and 5** — they are unobservable while it stands.

---

## Defect 2 — `BorrowService.returnBook` swallows every exception and returns `null`

* **Bug:**

  ```java
  } catch (Exception e) {
      log.warn("Could not complete return for borrow record {}", borrowId);
      return null;
  }
  ```

  The whole method body is wrapped, including the two `orElseThrow` calls and the
  `AlreadyReturnedException` check.
* **Affected component:** `service/BorrowService.java`; visible through
  `controller/BorrowController.java`, which does `ResponseEntity.ok(...)` on the result.
* **Root cause:** the two domain exceptions the method deliberately raises are caught by its own
  `catch (Exception e)` and converted into `null`. `ResponseEntity.ok(null)` is a `200` with no body.
  The exception object `e` is never passed to the logger, so the class, message and stack trace are
  all discarded — `log.warn("...{}", borrowId)` binds `borrowId` to the placeholder and drops `e`
  entirely.
* **Symptom:** `POST /api/return/999` → `200` with an empty body. Returning the same record twice →
  `200` both times, and `availableCopies` is not incremented the second time. The only trace is a
  `WARN` with no cause.
* **Why the symptom is the nastiest in this project:**
  * It is visible even before Defect 1 is fixed, and it does *not* change when Defect 1 is fixed —
    so a learner who fixes the advice and re-tests will see this one stubbornly unchanged and may
    assume their fix failed.
  * A `200` reads as success to every client.
  * The `catch` defeats the global handler entirely: no exception ever reaches it, so no amount of
    work on `GlobalExceptionHandler` affects this endpoint.
  * `@Transactional` is on the method, and swallowing the exception means the transaction is *not*
    marked rollback-only by Spring — the partial work done before the throw would commit. (In this
    particular method nothing is written before the first possible throw, so there is no data
    corruption; but the learner should notice the hazard. Credit them if they do — this is the
    setup for project 08.)
* **Correct fix:** delete the `try`/`catch` and let the domain exceptions propagate to the global
  handler, which already has cases for both `BorrowRecordNotFoundException` (`404`) and
  `AlreadyReturnedException` (`409`):

  ```java
  @Transactional
  public BorrowResponse returnBook(Long borrowId) {
      BorrowRecord record = borrowRecordRepository.findById(borrowId)
              .orElseThrow(() -> new BorrowRecordNotFoundException(borrowId));
      ...
      return toResponse(record, book);
  }
  ```

  If the learner keeps a `catch` but rethrows, or logs `e` and rethrows, that is acceptable — the
  requirement is that the exception reaches the handler and that the cause is preserved.
* **Concept:** exception swallowing; root-cause preservation; `catch (Exception e)` in a service
  layer; SLF4J's argument binding versus its trailing-`Throwable` convention.
* **Why a fresher makes it:** defensive programming taught badly. They were told "never let the API
  crash", so they wrapped the method. Returning `null` felt safer than throwing.
* **How to recognise it in the wild:** an endpoint that returns success for an obviously invalid
  request; a `WARN` or `ERROR` log line with a message but no stack trace. Grep the service layer for
  `catch (Exception` and for logger calls that do not pass the exception as the last argument.

---

## Defect 3 — `borrow()` uses the no-argument `orElseThrow()`

* **Bug:**

  ```java
  Book book = bookRepository.findById(request.getBookId()).orElseThrow();
  ```

  while `BookService.findById` correctly uses
  `.orElseThrow(() -> new BookNotFoundException(id))`.
* **Affected component:** `service/BorrowService.java`
* **Root cause:** `Optional.orElseThrow()` with no argument throws
  `java.util.NoSuchElementException: No value present`. The global handler has no case for it, so it
  falls through to the `Exception.class` catch-all and becomes `500`.
* **Symptom:** `POST /api/borrow` with an unknown `bookId` returns `500` with the catch-all's generic
  message, while `GET /api/books/{id}` with an unknown id correctly returns `404`. Two lookups of the
  same entity, two different outcomes.
* **Why the symptom is misleading:** the catch-all's message — "Something went wrong. Please contact
  support." — deliberately destroys the evidence. The learner sees a generic `500` and has no
  indication that a `NoSuchElementException` was involved. The `log.error("Unexpected failure: {}",
  ex.getMessage())` line prints only `No value present`, which does not name the exception type
  either.
* **Correct fix:**

  ```java
  Book book = bookRepository.findById(request.getBookId())
          .orElseThrow(() -> new BookNotFoundException(request.getBookId()));
  ```

  An acceptable alternative is adding a handler for `NoSuchElementException` — but push back: that
  treats the symptom. The service should raise its own domain exception, not leak a JDK one.
* **Concept:** `Optional.orElseThrow()` overloads; domain exceptions versus library exceptions;
  handler resolution falling through to a catch-all.
* **Why a fresher makes it:** the IDE offers `orElseThrow()` as the shortest completion and it
  compiles. In a different method of the same class they got it right — inconsistency inside one
  codebase is extremely common.
* **How to recognise it in the wild:** identical operations behaving differently on the error path.
  Improve the catch-all's logging first (`log.error("msg", ex)` with the exception as the last
  argument) so the real type is visible, then look for the throw site.

---

## Defect 4 — the out-of-stock handler returns `ResponseEntity.ok(...)`

* **Bug:**

  ```java
  @ExceptionHandler(BookOutOfStockException.class)
  public ResponseEntity<ApiError> handleOutOfStock(BookOutOfStockException ex) {
      ApiError error = new ApiError(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage());
      return ResponseEntity.ok(error);          // status 200, body says 409
  }
  ```

* **Affected component:** `exception/GlobalExceptionHandler.java`
* **Root cause:** the `ApiError` payload and the `ResponseEntity` status are set independently. The
  payload is built with `409`; the response is built with `ok()`, which hard-codes `200`. Every other
  handler in the class uses `ResponseEntity.status(...)`.
* **Symptom:** `HTTP/1.1 200` with `{"status":409,...}` in the body. A client that switches on the
  status line believes the loan succeeded; a client that reads the body believes it failed.
* **Why the symptom is misleading:** the body is completely correct, so anyone testing with plain
  `curl` (no `-i`) or with a REST client that only pretty-prints JSON will see a correct-looking
  `409` error and move on. This is the reason the guide insists on `curl -i`.
* **Correct fix:**

  ```java
  return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
  ```

  A structurally better answer, worth crediting: derive the status from the `ApiError` itself, or
  annotate the domain exceptions with `@ResponseStatus`, so the two can never disagree again.
* **Concept:** `ResponseEntity` status versus payload; duplicated sources of truth.
* **Why a fresher makes it:** `ResponseEntity.ok(...)` is the shortest way to wrap a body and is
  typed everywhere else in the codebase out of habit. Nothing about it looks like a status decision.
* **How to recognise it in the wild:** always assert on the status line, never on the body's copy of
  it. Any codebase that carries the status in two places will eventually disagree with itself.

---

## Defect 5 — no handler for `MethodArgumentNotValidException`, and a catch-all that claims it

* **Bug:** the advice has an `@ExceptionHandler(Exception.class)` catch-all but no handler for
  `MethodArgumentNotValidException`. It also does not extend `ResponseEntityExceptionHandler`.
* **Affected component:** `exception/GlobalExceptionHandler.java`
* **Root cause:** when `@Valid` fails on a `@RequestBody` argument, Spring raises
  `MethodArgumentNotValidException`. `ExceptionHandlerExceptionResolver` walks the exception's
  hierarchy looking for the most specific registered handler and finds only `Exception.class` — a
  match. So validation failures are reported as `500` with the generic support message, and the
  `fields` object the README promises is never produced.
* **Symptom:** `POST /api/books` with a blank title returns `500`.
  **Crucially, this symptom only appears after Defect 1 is fixed.** Before that, the advice is
  inactive and Spring Boot's default handling correctly returns `400` (with the wrong body). So the
  learner's first real fix appears to break validation.
* **Why the symptom is misleading:** it presents as a regression caused by the learner's own change.
  The instinct is to revert. The guide explicitly tells them not to, because recognising "my fix
  activated something that was already wrong" is the lesson.
* **Correct fix:** add the specific handler —

  ```java
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
      Map<String, String> fields = new HashMap<>();
      ex.getBindingResult().getFieldErrors()
              .forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
      ApiError error = new ApiError(HttpStatus.BAD_REQUEST.value(), "Validation Failed", null);
      error.setFields(fields);
      return ResponseEntity.badRequest().body(error);
  }
  ```

  Extending `ResponseEntityExceptionHandler` and overriding `handleMethodArgumentNotValid` is an
  equally good answer.

  **Second half of the fix:** the catch-all logs `ex.getMessage()` only. It should log the exception
  itself so the stack trace survives:

  ```java
  log.error("Unexpected failure handling request", ex);
  ```

  A learner who fixes the validation handler but leaves the lossy log has done half the job. This is
  the same lesson as Defect 2, seen from the handler side rather than the service side.
* **Concept:** `@ExceptionHandler` resolution by exception hierarchy; the hazard of a broad
  catch-all; Bean Validation's exception types (`MethodArgumentNotValidException` for `@RequestBody`,
  `ConstraintViolationException` for `@Validated` method parameters — a distinction worth raising
  with the learner if they ask).
* **Why a fresher makes it:** a catch-all "so the user never sees a stack trace" is standard advice,
  and it works — right up to the point where it starts intercepting exceptions that had a proper
  handler waiting for them. Nobody tests the invalid-payload path after adding it.
* **How to recognise it in the wild:** any framework-level exception arriving as `500`. Temporarily
  remove or narrow the catch-all and see what the real status would have been. In production, a
  catch-all should be the *last* handler and must always log the full exception.

---

## Suggested fix order

1. **Defect 1** — the gate. Nothing else about the error contract is observable until this is done.
2. **Defect 5** — the apparent regression it causes. Fix it before the learner loses confidence.
3. **Defect 3** — the remaining `500`.
4. **Defect 4** — the status/body disagreement.
5. **Defect 2** — independent of all the others and can be done at any point. A learner who tackles
   it first has correctly noticed that it does not respond to changes in the advice, which is good
   reasoning.

## Test expectations

| Test | Before | After all fixes |
|---|---|---|
| `catalogueListsEverySeededBook` | pass | pass |
| `borrowingAnAvailableBookDecrementsStock` | pass | pass |
| `requestingAnUnknownBookReturnsNotFound` | fail (`500`) | pass |
| `borrowingABookWithNoCopiesLeftReturnsConflict` | fail (`500`, then `200` after Defect 1) | pass |

Note that `borrowingABookWithNoCopiesLeftReturnsConflict` fails twice for different reasons — `500`
because of Defect 1, then `200` because of Defect 4. Defects 2, 3 and 5 are **not** covered by any
test and must be found by exercising the API.

## Verification commands

```bash
mvn test    # 4/4 green

curl -i http://localhost:8080/api/books/999                                    # 404
curl -i -X POST http://localhost:8080/api/borrow -H 'Content-Type: application/json' \
     -d '{"bookId":999,"memberName":"P"}'                                      # 404
curl -i -X POST http://localhost:8080/api/borrow -H 'Content-Type: application/json' \
     -d '{"bookId":4,"memberName":"R"}'                                        # 409 on the STATUS LINE
curl -i -X POST http://localhost:8080/api/return/999                           # 404
curl -i -X POST http://localhost:8080/api/books -H 'Content-Type: application/json' \
     -d '{"isbn":"111","title":"","author":"X","totalCopies":2}'               # 400 + fields object
curl -i -X POST http://localhost:8080/api/books -H 'Content-Type: application/json' \
     -d '{"isbn":"978-0132350884","title":"Clean Code","author":"RCM","totalCopies":2}'  # 409

# borrow then return twice: 201, 200, 409
curl -s -X POST http://localhost:8080/api/borrow -H 'Content-Type: application/json' \
     -d '{"bookId":2,"memberName":"K"}'
curl -i -X POST http://localhost:8080/api/return/1
curl -i -X POST http://localhost:8080/api/return/1
```

Also check in the H2 console that `available_copies` for book 2 is back to `3` after the successful
return and is *not* `4` after the failed second return.
