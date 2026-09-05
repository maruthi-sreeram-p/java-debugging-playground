# SOLUTION — 12 · Customer Onboarding API

> **Sealed answer key.** Eight planted defects.

---

## Defect 1 — No age range constraint, and `@NotNull` on a primitive

* **Bug:**

  ```java
  @NotNull(message = "age is required")
  private int age;
  ```

  The README requires 18–120. There is no `@Min` or `@Max` anywhere.
* **Affected component:** `dto/CustomerRequest`
* **Root cause:** two problems in two lines.
  1. `@NotNull` on a **primitive** can never fail. Jackson cannot put `null` into an `int`; an
     omitted property leaves the field at its default `0`, which is not null, so the constraint
     passes. The annotation is dead code that creates the impression the field is guarded.
  2. The rule that actually matters — the range — was never expressed at all.
* **Symptom:** `"age": 5` → `201`. Omitting `age` entirely → `201`, and `0` is stored.
* **Why the symptom is misleading:** there *is* an annotation on the field, with a sensible message.
  Reading the DTO gives every impression that age is validated. Worse, omitting the field produces a
  successful create with a silently invented value, so no error ever draws attention to it.
* **Correct fix:**

  ```java
  @NotNull(message = "age is required")
  @Min(value = 18, message = "customers must be at least 18")
  @Max(value = 120, message = "age must be realistic")
  private Integer age;
  ```

  **Changing `int` to `Integer` is the important half** and is worth pressing for: only a boxed type
  can distinguish "the caller omitted this" from "the caller sent zero", which is exactly what
  `@NotNull` is for. A learner who adds `@Min`/`@Max` but leaves the primitive has fixed the visible
  symptom — ask them what an omitted `age` now does. (Answer: `0`, which fails `@Min`, so it happens
  to be rejected — but with a misleading message about being under 18 rather than about being
  missing.)
* **Concept:** primitives versus wrappers in request DTOs; `@NotNull` semantics; expressing the whole
  rule, not part of it.
* **Why a fresher makes it:** `int` is the natural type for an age, and `@NotNull` is the annotation
  they reach for to mean "required". Nothing warns that the combination is meaningless.
* **How to recognise it in the wild:** any `@NotNull` on a primitive is dead. Grep for it. As a rule,
  request DTOs should use wrapper types throughout so that "missing" is representable.

---

## Defect 2 — The nested address is never validated

* **Bug:**

  ```java
  @NotNull(message = "address is required")
  private AddressRequest address;      // no @Valid
  ```

* **Affected component:** `dto/CustomerRequest`
* **Root cause:** `@Valid` on the controller parameter validates the *outer* object's own
  constraints. Bean Validation only descends into a nested object when the field is itself annotated
  `@Valid`. Without it, `AddressRequest`'s `@NotBlank`, `@Size` and `@Pincode` constraints are never
  evaluated as part of a customer payload.
* **Symptom:** `POST /api/customers` with `"city": ""` and `"pincode": "abcdef"` returns `201`. The
  *same* address object sent to `POST /api/customers/{id}/address` correctly returns `400` — because
  there it is the top-level `@Valid`-annotated parameter.
* **Why the symptom is misleading:** the constraints exist, are correct, and demonstrably work — on
  the other endpoint. So the learner confirms "the address rules are fine" and looks elsewhere. The
  contrast between the two endpoints is the intended lead, and hint B1 points straight at it.
* **Correct fix:**

  ```java
  @NotNull(message = "address is required")
  @Valid
  private AddressRequest address;
  ```

* **Concept:** cascading validation; `@Valid` on a field versus on a parameter.
* **Why a fresher makes it:** they add `@Valid` to the controller parameter, see validation working
  for the top-level fields, and assume it is recursive. It is the single most common Bean Validation
  mistake.
* **How to recognise it in the wild:** send a payload whose *nested* object is invalid. If it is
  accepted, a `@Valid` is missing on the field. Any DTO field whose type is another DTO needs one.

---

## Defect 3 — The pincode validator checks length, not digits

* **Bug:**

  ```java
  return value.trim().length() == 6;
  ```

  while `@Pincode`'s own default message says `"pincode must be exactly six digits"`.
* **Affected component:** `validation/PincodeValidator`
* **Root cause:** the implementation asserts something weaker than the annotation promises.
* **Symptom:** `"abcdef"` is accepted as a pincode. Visible only after Defect 2 is fixed on the create
  path — but visible **immediately** through `POST /api/customers/{id}/address`, which does validate
  the address and still lets `"abcdef"` through. The guide points this out explicitly so the learner
  does not conclude the address validation is entirely fine once Defect 2 is fixed.
* **Why the symptom is misleading:** the custom constraint fires, the mechanism works, and short or
  long values are correctly rejected. Only the *content* rule is missing. A developer who tests with
  `"12345"` (rejected) concludes the validator works.
* **Correct fix:**

  ```java
  return value.trim().matches("\\d{6}");
  ```

  Returning `true` for `null` is correct and should be left alone — that is the standard contract, so
  that `@NotNull`/`@NotBlank` owns the "required" decision separately. If the learner "fixes" the
  null case too, explain why the convention exists.
* **Concept:** custom `ConstraintValidator`; keeping behaviour consistent with the constraint's
  message; the null-handling convention.
* **Why a fresher makes it:** length is the easy half of the rule and they intended to come back to
  it. The message was written first, from the requirement, and never re-checked against the code.
* **How to recognise it in the wild:** test a custom validator with a value that satisfies the shape
  but not the content. A validator's message is documentation — if it disagrees with the code, the
  code is wrong. Unit-test validators directly; they are pure functions and there is no excuse.

---

## Defect 4 — `applyAddress` transposes `city` and `state`

* **Bug:**

  ```java
  customer.setCity(address.getState());
  customer.setState(address.getCity());
  ```

* **Affected component:** `mapper/CustomerMapper.applyAddress`
* **Root cause:** two same-typed fields assigned to each other's setters.
* **Symptom:** every customer is stored with city and state swapped — on create, on replace, and via
  the address-only endpoint, since all three call this method. The API response is *honest* about it
  (it reports what was stored), so the corruption is visible in the response as well as the table.
* **Why the symptom is misleading:**
  * Nothing fails. No validation is violated — "Maharashtra" is a perfectly valid city string.
  * The compiler cannot help: both sides are `String`.
  * A test that asserts only on the status code passes. Only an assertion on the *values* catches it,
    which is precisely what `theAddressIsStoredInTheColumnsItWasSentFor` does.
  * It reads correctly at a glance: four assignments, all present, all plausible.
  * By the time anyone notices, every row in the table is wrong, and fixing the code does not fix the
    data — a point worth making with the learner (hint C4).
* **Correct fix:**

  ```java
  customer.setCity(address.getCity());
  customer.setState(address.getState());
  ```

* **Concept:** hand-written mappers and same-typed field transposition; asserting on values rather
  than status codes.
* **Why a fresher makes it:** copy-paste and a moment's inattention. It is one of the most common
  real defects in existence and one of the least catchable by review.
* **How to recognise it in the wild:** round-trip tests. Create with distinctive values and assert
  every field on read-back. Where a mapper copies several fields of the same type, that test is not
  optional. (This is also the strongest argument for a mapping library that generates the code from
  names.)

---

## Defect 5 — The replace endpoint has no `@Valid`

* **Bug:**

  ```java
  @PutMapping("/{id}")
  public ResponseEntity<CustomerResponse> update(@PathVariable Long id,
                                                 @RequestBody CustomerRequest request) {
  ```

  The create handler two methods above has `@Valid @RequestBody`.
* **Affected component:** `controller/CustomerController.update`
* **Root cause:** validation of a request body is opt-in, per parameter. Without `@Valid`, the DTO's
  constraints are simply never evaluated.
* **Symptom:** `PUT /api/customers/1` with a blank name, `"not-an-email"` and `"age": 5` returns
  `200` and writes all of it.
* **Why the symptom is misleading:** the DTO is the *same class* used by the create endpoint, where
  the constraints work perfectly. The rules are visibly present and visibly enforced elsewhere. The
  learner is looking at the DTO when the defect is on the method signature.
* **Correct fix:**

  ```java
  public ResponseEntity<CustomerResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody CustomerRequest request) {
  ```

* **Concept:** validation is per-parameter and opt-in; the same DTO can be validated on one endpoint
  and not another.
* **Why a fresher makes it:** the update method is written by copying the create method and editing
  it, and the annotation is easy to lose in the edit. Or the update endpoint was added later, by
  someone else.
* **How to recognise it in the wild:** grep every `@RequestBody` in the codebase and check each one
  has `@Valid`. It should be a review checklist item. Better: enforce it with an ArchUnit rule or a
  static analysis check.

---

## Defect 6 — A person's age rule applied to a search filter

* **Bug:**

  ```java
  @RequestParam(defaultValue = "18") @Min(value = 18, message = "minAge must be at least 18") int minAge
  ```

* **Affected component:** `controller/CustomerController.search`
* **Root cause:** the domain rule "a customer must be at least 18" has been copied onto a **query
  parameter**, where it means something entirely different. `minAge` is a filter threshold, not a
  person's age; there is no reason a caller cannot ask "who is at least 1?".
* **Symptom:** `GET /api/customers?minAge=1` → `400`, `{"fields":{"search.minAge":"minAge must be at
  least 18"}}`. A legitimate query is refused.
* **Why the symptom is misleading — and a note for you:** this is the "too strict" defect, and it is
  the one learners are least likely to look for. Everything about the response is *correct-looking*:
  a `400` with a clear field-level message reads like the caller's mistake, not the server's. The
  only way to catch it is to hold the API contract next to the behaviour.

  **Note the field path in the error: `search.minAge`.** That prefix is the method name — Spring
  Framework 6.1 performs built-in method validation on controller parameters, so this constraint
  fires *without* `@Validated` on the class. If you have older Spring experience you may expect it to
  be silently ignored; on this version it is not. Worth mentioning if the learner is surprised.
* **Correct fix:** drop the constraint, or replace it with one that makes sense for a filter:

  ```java
  @RequestParam(defaultValue = "18") @Min(value = 0, message = "minAge cannot be negative") int minAge
  ```

  Either is acceptable. What matters is that they can articulate *why* the original was wrong — that
  the same number means two different things in two places.
* **Concept:** applying a domain invariant to the wrong concept; the difference between validating an
  entity's state and validating a query.
* **Why a fresher makes it:** the constraint was copied from the DTO in good faith, on the reasoning
  that "18 is the minimum age in this system". The reasoning is right about customers and wrong about
  filters.
* **How to recognise it in the wild:** any `400` a caller complains about. Check the contract before
  assuming the caller is wrong. Over-strict validation is a real outage that looks like a support
  ticket.

---

## Defect 7 — No duplicate-email check on the replace path

* **Bug:** `CustomerService.create` calls `customerRepository.existsByEmail(...)` and throws
  `DuplicateEmailException`. `CustomerService.update` does not.
* **Affected component:** `service/CustomerService.update`
* **Root cause:** a business rule enforced on one path that can violate it and not the other. The
  database's unique index catches it, but only after the statement is issued, and the resulting
  `DataIntegrityViolationException` is unhandled.
* **Symptom:** `PUT /api/customers/2` with customer 1's email → `500`, with
  `Duplicate entry 'ishaan.kapoor@mail.com' for key 'customers.UK...'` leaking the constraint name to
  the caller. The equivalent `POST` correctly returns `409`.
* **Why the symptom is misleading:** the rule is implemented, tested and working — on create. Nobody
  thinks to ask whether the *other* write path enforces it. The unique index also means the data
  never actually becomes corrupt, so this presents purely as an ugly error rather than a data
  problem, which lowers its apparent priority.
* **Correct fix:**

  ```java
  customerRepository.findByEmail(request.getEmail())
          .filter(existing -> !existing.getId().equals(id))
          .ifPresent(existing -> { throw new DuplicateEmailException(request.getEmail()); });
  ```

  **The `!existing.getId().equals(id)` part is essential** and hint F3 warns about it: on a replace,
  the customer's own email is not a duplicate. A learner who copies the `existsByEmail` check
  verbatim will break every update that does not change the email — a new defect in place of the old
  one. Check this specifically.

  Adding a `@ExceptionHandler(DataIntegrityViolationException.class)` that maps to `409` is a
  reasonable belt-and-braces addition, but it is not a substitute: it would also swallow genuinely
  unexpected integrity errors.
* **Concept:** enforcing a business rule on every path that can violate it; database constraints as a
  safety net rather than an API contract.
* **Why a fresher makes it:** create and update are written at different times, and uniqueness feels
  like a "creation" concern.
* **How to recognise it in the wild:** for every invariant, list every code path that can break it and
  check each one. Any `DataIntegrityViolationException` reaching a client is a missing check upstream.

---

## Defect 8 — No length constraint on `phone`

* **Bug:** `CustomerRequest.phone` carries no constraints at all, while `Customer.phone` is
  `@Column(name = "phone", length = 15)`.
* **Affected component:** `dto/CustomerRequest` (the gap) versus `entity/Customer` (the limit)
* **Root cause:** the API contract is looser than the schema, so the database becomes the validator
  of last resort. MySQL in strict mode rejects the insert with a data-truncation error.
* **Symptom:** a 40-character phone number → `500`,
  `Data truncation: Data too long for column 'phone' at row 1`.
* **Why the symptom is misleading:** `phone` is documented as optional, and "optional" is easily read
  as "unconstrained". The failure surfaces from Hibernate rather than from the validation layer, so
  the stack trace points at persistence and not at the DTO. And it only happens with unusually long
  input, which nobody tests by hand.
* **Correct fix:**

  ```java
  @Size(max = 15, message = "phone must be at most 15 characters")
  private String phone;
  ```

  A `@Pattern` for the expected shape would be better still. Ask the learner to do the sweep hint G3
  suggests — check every length-limited column against its DTO field. In this project `fullName`
  (120), `city`/`state` (80, constrained in `AddressRequest`), `addressLine1` (200, constrained) and
  `pincode` (10) are the others; `fullName` has no `@Size` either and is worth catching.
* **Concept:** keeping DTO constraints aligned with schema limits; validating at the boundary rather
  than letting the database do it.
* **Why a fresher makes it:** the column length was chosen in the entity, the constraints were written
  in the DTO, and nothing connects the two. This drifts constantly as schemas evolve.
* **How to recognise it in the wild:** any `Data too long`, `value too long`, or numeric-overflow error
  reaching a client. The fix is always at the boundary. Some teams generate DTO constraints from the
  schema for exactly this reason.

---

## Suggested fix order

All eight are independent. A sensible route is to work down the README's rules table, breaking each
rule in turn:

1. **Defect 5** first (`@Valid` on replace) — it makes the replace path testable, which you need for
   several of the others.
2. **Defect 1** (age), **Defect 2** (nested address), **Defect 3** (pincode) — the "too lax" group.
3. **Defect 4** (the swap) — found by asserting on values.
4. **Defect 6** (the search filter) — the "too strict" one.
5. **Defects 7 and 8** — the two `500`s.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `aValidCustomerIsOnboarded` | pass | — |
| `theAddressIsStoredInTheColumnsItWasSentFor` | fail | Defect 4 |
| `anUnderageApplicantIsRejected` | fail (`201`) | Defect 1 |
| `aBlankCityInTheAddressIsRejected` | fail (`201`) | Defect 2 |
| `anInvalidUpdateIsRejected` | fail (`200`) | Defect 5 |
| `updatingToAnEmailThatIsTakenReturnsConflict` | error (`500`) | Defect 7 |
| `theSearchAcceptsAnyNonNegativeMinimumAge` | fail (`400`) | Defect 6 |

Baseline: `Tests run: 7, Failures: 5, Errors: 1`.

**Defects 3 (the pincode validator) and 8 (the phone length) are not covered by any test.** Both
require deliberately hostile input that no happy-path test would send. If the learner reports the
project fixed with seven green tests, ask them what `"pincode": "abcdef"` does — that is the check.

## Verification commands

```bash
docker compose down -v && docker compose up -d
mvn clean package && java -jar target/customer-onboarding-1.0.0.jar
mvn test        # 7/7 green

C=http://localhost:8080/api/customers
body() { cat <<JSON
{"fullName":"$1","email":"$2","phone":"$3","age":$4,
 "address":{"addressLine1":"12 Example Street","city":"$5","state":"$6","pincode":"$7"}}
JSON
}
post() { curl -s -o /dev/null -w "%{http_code}\n" -X POST $C -H 'Content-Type: application/json' -d "$1"; }

post "$(body 'Ok Person'   ok@mail.com    '+919876543210' 34 Pune Maharashtra 411001)"   # 201
post "$(body 'Too Young'   young@mail.com '+919876543210' 5  Pune Maharashtra 411001)"   # 400
post "$(body 'Too Old'     old@mail.com   '+919876543210' 200 Pune Maharashtra 411001)"  # 400
post "$(body 'Blank City'  bc@mail.com    '+919876543210' 34 ''   Maharashtra 411001)"   # 400
post "$(body 'Bad Pin'     bp@mail.com    '+919876543210' 34 Pune Maharashtra abcdef)"   # 400
post "$(body 'Short Pin'   sp@mail.com    '+919876543210' 34 Pune Maharashtra 12345)"    # 400
post "$(body 'Long Phone'  lp@mail.com    '+91987654321000000000000000000000' 34 Pune Maharashtra 411001)"  # 400

# no age at all -> 400
curl -s -o /dev/null -w "%{http_code}\n" -X POST $C -H 'Content-Type: application/json' \
  -d '{"fullName":"No Age","email":"na@mail.com","address":{"addressLine1":"1 Road","city":"Pune","state":"Maharashtra","pincode":"411001"}}'

# replace is validated too
curl -s -o /dev/null -w "%{http_code}\n" -X PUT $C/1 -H 'Content-Type: application/json' \
  -d "$(body '' not-an-email '+919876543210' 5 Pune Maharashtra 411001)"                 # 400

# duplicate email: 409 on both paths, and an unchanged email still updates
curl -s -o /dev/null -w "%{http_code}\n" -X PUT $C/2 -H 'Content-Type: application/json' \
  -d "$(body 'Sneha Pillai' ishaan.kapoor@mail.com '+919812345678' 27 Kochi Kerala 682011)"   # 409
curl -s -o /dev/null -w "%{http_code}\n" -X PUT $C/2 -H 'Content-Type: application/json' \
  -d "$(body 'Sneha Pillai' sneha.pillai@mail.com  '+919812345678' 28 Kochi Kerala 682011)"   # 200

# searches
curl -s -o /dev/null -w "%{http_code}\n" "$C?minAge=0"    # 200
curl -s -o /dev/null -w "%{http_code}\n" "$C?minAge=1"    # 200
```

```sql
-- city and state the right way round, for every row written after the fix
SELECT full_name, city, state, pincode, phone FROM customers;
```

And the final check from the guide: grep the application log for `DataIntegrityViolationException`
after the whole sweep. There should be none.
