# Debugging Guide — 12 · Customer Onboarding API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Every previous project in this lab had a defect that *broke* something. This one mostly does not.
Requests succeed. Responses look right. Status codes are plausible. And the data going into the
database is wrong.

Validation defects come in three flavours, and this project has all three:

* **Too lax** — invalid data is accepted, and nothing tells you.
* **Too strict** — valid data is rejected, and it looks like the caller's fault.
* **In the wrong place** — the rule exists, but not where the request actually passes through.

There is a fourth thing here that is not validation at all but hides among them: data that is
accepted correctly and then *stored in the wrong field*. Watch for it.

Because almost nothing throws, **you cannot debug this project from the application log.** You debug
it by writing requests that should fail and checking whether they do, and by comparing what you sent
with the row that was written.

## Expected behaviour

The rules table in `README.md` is the specification. All of it applies on create **and** on replace.
Beyond that:

* No valid request ever returns `5xx`.
* A customer reads back exactly as sent, field for field.
* A duplicate email is `409`.
* `?minAge=0` is a valid search.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/customer-onboarding-1.0.0.jar
```

Keep a MySQL shell open — for at least one symptom here, the API response and the table disagree and
only the table is telling the truth:

```bash
docker exec -it debuglab12-mysql mysql -uroot -prootpw onboardingdb
```

```sql
SELECT id, full_name, age, city, state, pincode, phone FROM customers;
```

Then work down the rules table in the README and, for each rule, send a request that **breaks** it.
Note whether you got the `400` the contract promises. That sweep is the whole exercise — a validation
layer is only as good as the negative tests you have actually run against it.

`mvn test` runs seven tests: one passes, six fail. Two of the defects are not covered by any test.

---

## Known symptoms

### Symptom A — a five-year-old can open an account

```
POST /api/customers   {"fullName":"Too Young","email":"young@mail.com","age":5, ...}
201 Created
```

And with `age` left out of the payload altogether:

```
201 Created
```

```sql
SELECT full_name, age FROM customers WHERE email = 'noage@mail.com';
+---------+-----+
| No Age  |   0 |
```

The rules table says at least 18. There is an annotation on that field. It fired for neither case.

### Symptom B — the address is not checked at all

```
POST /api/customers   { ..., "address": { "addressLine1":"1 Road", "city":"", "state":"KA", "pincode":"abcdef" } }
201 Created
```

Blank city, and a "pincode" that contains no digits whatsoever. Both are covered by the rules table.
Both were accepted.

Now send the *same address* to the address-only endpoint:

```
POST /api/customers/1/address   {"addressLine1":"1 Road","city":"","state":"KA","pincode":"abcdef"}
400 Bad Request   {"fields":{"city":"city is required"}}
```

The identical object, validated in one place and not the other. That contrast is your best lead.

Note that even there, `pincode: "abcdef"` was **not** reported. So there are two separate things
wrong with the address.

### Symptom C — city and state come back swapped

```
POST /api/customers   { ..., "address": { "city":"Pune", "state":"Maharashtra", ... } }

201 Created
{ ..., "city":"Maharashtra", "state":"Pune", ... }
```

```sql
SELECT city, state FROM customers WHERE email = 'address.person@mail.com';
+-------------+------+
| Maharashtra | Pune |
```

Nothing was rejected, nothing was lost, and the response is honest about what was stored. It is
simply in the wrong columns — in the database, permanently, for every customer this service has ever
onboarded. Check the address-only endpoint too.

### Symptom D — replacing a customer accepts anything

```
PUT /api/customers/1   {"fullName":"","email":"not-an-email","age":5, "address":{...garbage...}}
200 OK
{"id":1,"fullName":"","email":"not-an-email","age":5, ...}
```

Customer 1 now has no name and an email address that is not one. The same payload sent to `POST`
is rejected (once you have fixed Symptom A, at least). Same DTO, same rules, two endpoints, two
outcomes.

### Symptom E — a legitimate search is refused

```
GET /api/customers?minAge=1
400 Bad Request   {"fields":{"search.minAge":"minAge must be at least 18"}}
```

The README says any non-negative minimum age is a valid query — asking "who is at least 1?" is a
perfectly reasonable question about a customer base. The constraint that belongs on a *customer's
age* has been applied to a *search filter*.

### Symptom F — a duplicate email is a 500

```
PUT /api/customers/2   { ..., "email":"ishaan.kapoor@mail.com", ... }     (customer 1's address)
500 Internal Server Error
```

```
org.springframework.dao.DataIntegrityViolationException:
  Duplicate entry 'ishaan.kapoor@mail.com' for key 'customers.UK...'
```

Creating a customer with a duplicate email correctly returns `409`. Replacing one does not.

### Symptom G — a long phone number is a 500

```
POST /api/customers   { ..., "phone":"+9198765432100000000000000000000000000000", ... }
500 Internal Server Error
```

```
could not execute statement [Data truncation: Data too long for column 'phone' at row 1]
```

The database refused it, which is the last line of defence rather than the first. The caller gets a
`500` and no idea what they did wrong.

---

## Investigation hints

### Symptom A — the unenforced age

> **Hint A1**
> Read every annotation on that field in the request DTO. There is one. What exactly does it assert,
> and does it cover the rule in the README?

> **Hint A2**
> Look at the field's **Java type**. Can a value of that type ever be absent? What does Jackson put
> there when the JSON omits the property, and what does the annotation on it therefore see?

> **Hint A3**
> Two separate things follow from that. First: the annotation present on that field can never fail
> for a value of that type — it is dead code. Second: the rule the README actually specifies is a
> *range*, and no annotation for it exists anywhere.

> **Hint A4**
> Consider whether the field's type should change as well. If "absent" and "zero" need to be
> distinguishable, a primitive cannot express it.

### Symptom B — the unchecked address

> **Hint B1**
> Start from the contrast. The address-only endpoint validates the same object; the create endpoint
> does not. Put the two handler signatures side by side and compare every annotation on the
> parameter.

> **Hint B2**
> Now go one level in. When a request object *contains* another object, does validating the outer one
> automatically validate the inner one? Look up what makes Bean Validation descend into a nested
> object — it is one annotation, and it goes on the field, not on the method.

> **Hint B3**
> Now the second half of this symptom: even the endpoint that *does* validate the address let
> `"abcdef"` through as a pincode. Find the custom constraint's implementation and read the single
> expression it evaluates. Compare it with the rule the annotation's own default message states.

> **Hint B4**
> "Six characters" and "six digits" are not the same requirement. Fix the check to match what the
> message promises — and note the general lesson: a validator whose message and behaviour disagree is
> worse than no validator, because it creates false confidence.

### Symptom C — the swapped columns

> **Hint C1**
> This is not a validation problem at all. The values arrived correctly and were stored incorrectly,
> so look at the code between the request object and the entity.

> **Hint C2**
> Read the assignments in the method that copies address fields, one line at a time, comparing the
> getter on the right with the setter on the left. Two of them do not agree.

> **Hint C3**
> Both fields are `String`, both are optional-ish, both are about the same length. The compiler
> cannot help, no test that only checks status codes can help, and a reviewer skims it. **The only
> defence is a test that asserts on the values**, which is exactly what one of the failing tests
> does.

> **Hint C4**
> Note which endpoints share this method — the damage is wider than the one you found it on. And
> once you have fixed it, the rows already written are still wrong; decide whether that matters.

### Symptom D — the unvalidated replace

> **Hint D1**
> Compare the create handler and the replace handler, parameter by parameter. One annotation is
> present on one and absent on the other.

> **Hint D2**
> Validation of a request body is not automatic just because the DTO carries constraints. Something
> has to ask for it, at the point where the body is bound.

> **Hint D3**
> Once you have fixed it, re-read the README's line about what `PUT` means here, and check that
> requiring the whole payload is the documented behaviour rather than a side effect you have
> introduced.

### Symptom E — the over-strict search

> **Hint E1**
> Read the error's field path. It names a method parameter, not a DTO field — so the constraint is
> declared somewhere other than the request objects.

> **Hint E2**
> Ask what that number *means* in each of the two places it appears. In one it is a property of a
> person; in the other it is a filter threshold. They are different concepts that happen to share a
> unit.

> **Hint E3**
> Decide what the sensible constraint on a search filter actually is, if any, and make the code match
> the contract in the README rather than making the README match the code.

### Symptom F — the duplicate email 500

> **Hint F1**
> Creating a customer returns `409` for a duplicate. Find the code that produces that, then look for
> the equivalent on the replace path.

> **Hint F2**
> It is not there. The database's unique index catches it instead — which is correct as a safety net
> and useless as an API contract, because the caller gets a `500` and a message about an index name.

> **Hint F3**
> Watch out for one detail when you add the check: on a replace, the customer's *own* email is not a
> duplicate. Make sure updating a customer without changing their email still works.

### Symptom G — the long phone number

> **Hint G1**
> Look at the column definition for that field in the entity, then look at the constraints on the
> same field in the request DTO. One of them constrains the length. The other does not.

> **Hint G2**
> The general principle: **every column with a length limit needs a matching constraint at the
> boundary.** Otherwise the database becomes your validator, and the database reports failures as
> `500`s in a vocabulary the caller cannot use.

> **Hint G3**
> Go through the entity's columns and check each one against the DTO. This is the only symptom
> listed for this defect, but it may not be the only field affected — a sweep is worth doing.

---

## Expected logs and observations

* The application starts cleanly, and **most symptoms produce no log output at all**. Symptoms A, B,
  C, D and E are all `2xx` or a plausible `4xx`.
* Only Symptoms F and G produce an exception, and both are `DataIntegrityViolationException` from the
  persistence layer — the database enforcing what the API should have.
* `spring.jpa.show-sql=true` is on, so you can read the `insert` and `update` statements. For
  Symptom C, the statement is the clearest possible evidence: the values are in the wrong parameter
  positions.
* `server.error.include-message=always` is set, so error bodies explain themselves. Use the `fields`
  object — the *path* it reports (e.g. `address.city` versus `search.minAge`) tells you where the
  constraint that fired lives.
* The best instrument in this project is a checklist. Take the rules table from the README and, for
  each row, send a request that violates it. Record what you got.

## Difficulty

**Advanced.** Expect two to three hours. Eight defects.

None of them blocks another — you can attack them in any order. What makes this hard is that nothing
announces itself: five of the eight return a success status. If you only run the failing tests and
fix those, you will finish with two defects still in place.

## Concepts being tested

* `@Valid` on `@RequestBody`, and that validation is opt-in per parameter
* Cascading validation into nested objects
* Constraints on primitive types, and why `@NotNull` on an `int` is dead code
* The difference between "absent" and "default value" in a bound request object
* Writing a custom `ConstraintValidator`, and keeping its behaviour consistent with its message
* Applying a domain rule to the wrong concept (an entity's attribute versus a query filter)
* Hand-written mappers and same-typed field transposition
* Keeping DTO constraints aligned with the database schema
* Business-rule validation (uniqueness) on every path that can violate it
* Testing the negative case: a validation layer is only as good as the requests you tried to break it
  with

## When you think you are done

- [ ] `mvn test` is green (7 tests).
- [ ] Walk the **entire** rules table in the README. For every row, a request violating it returns
      `400` with that field named in `fields` — on `POST` **and** on `PUT`.
- [ ] `"age": 5`, `"age": 200` and an omitted `age` are all `400`.
- [ ] `"pincode": "abcdef"` and `"pincode": "12345"` are both `400`; `"560025"` is accepted.
- [ ] A customer created with `city: "Pune"`, `state: "Maharashtra"` reads back that way — check the
      table, not just the response.
- [ ] The same is true after `POST /api/customers/{id}/address`.
- [ ] `?minAge=0` and `?minAge=1` both return `200`.
- [ ] A duplicate email is `409` on both create and replace; replacing a customer **without**
      changing their email still returns `200`.
- [ ] A 40-character phone number is `400`, not `500`.
- [ ] Grep the application log for `DataIntegrityViolationException` after your whole sweep — there
      should be none. Every rejection should have happened at the boundary.

Then say **"I think I fixed the project"**.
