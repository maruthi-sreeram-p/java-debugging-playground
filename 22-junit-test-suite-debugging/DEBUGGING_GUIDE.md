# Debugging Guide — 22 · Loyalty Points Service

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

```
$ mvn test
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```
$ curl -s -X POST localhost:8080/api/loyalty/cust-1001/redeem \
    -H 'Content-Type: application/json' -d '{"points":100000}'
{"customerId":"cust-1001","points":-99748,"tier":"BRONZE"}
```

Both of those are the truth. That is the project.

Every other exercise in this laboratory asks you to find a defect in the application. This one asks
you to find out **why the tests did not**. The application is wrong in several ways that a competent
suite would have caught in seconds, and the suite is green, and has been green the whole time.

So the work runs in this order, and it is the order that matters:

1. Use the API by hand and write down everything that disagrees with the README's rules.
2. For each of those, find the test that should have caught it, and work out why it did not.
3. Repair the test. Watch it go red.
4. Only then fix the application.

Doing it the other way round — fixing the code first — teaches you nothing, because you will still
have a suite that cannot tell you whether you were right.

A test can be green for at least five reasons that have nothing to do with correct code: it never
ran; it asserts nothing; what it asserts is true by construction; it asserts against a stub instead
of the system; or it runs in conditions the application will never be in. All five are here.

## Expected behaviour

Everything in the README's **Business rules** and **Expected functionality**. In particular: ₹150
earns 1 point, exactly 1,000 points is `SILVER`, a redemption survives a page refresh, a balance
never goes negative, and an unknown customer is a `404`.

## How to reproduce

```bash
mvn clean package
java -jar target/loyalty-points-1.0.0.jar > app.log 2>&1 &
```

```bash
curl -s localhost:8080/api/loyalty
```

Then work through the symptoms below. `mvn test` will stay green throughout, until you start
repairing it.

---

## Known symptoms

### Symptom A — the tier boundaries are one point out

`cust-1002` has 995 points. Five more make exactly 1,000, which the README says is `SILVER`:

```bash
curl -s -X POST localhost:8080/api/loyalty/cust-1002/earn \
  -H 'Content-Type: application/json' -d '{"amount":500.00}'
```

```json
{ "customerId": "cust-1002", "points": 1000, "tier": "BRONZE" }
```

The same at the top boundary — `cust-1003` at 4,990 plus ₹1,000 of spending:

```json
{ "customerId": "cust-1003", "points": 5000, "tier": "SILVER" }
```

Exactly on the boundary is one tier too low, both times. One point more and both are correct.

There is a test whose name says it covers this. It passes.

### Symptom B — ₹150 earns two points

`cust-1001` has 250 points. The rule is one point per ₹100 spent, rounded down, so ₹150 should earn
one:

```json
{ "customerId": "cust-1001", "points": 252, "tier": "BRONZE" }
```

₹1,000 earns exactly 10 and is correct. ₹150 earns 2. ₹149 earns 1. The error only appears for
amounts that are not close to a multiple of ₹100 — which, in a shop, is most of them.

There is a test dedicated to the points calculation. It passes.

### Symptom C — the redemption that returns the new balance and does not keep it

```bash
curl -s -X POST localhost:8080/api/loyalty/cust-1001/redeem \
  -H 'Content-Type: application/json' -d '{"points":100000}'
```

```json
{ "customerId": "cust-1001", "points": -99748, "tier": "BRONZE" }
```

```bash
curl -s localhost:8080/api/loyalty/cust-1001
```

```json
{ "customerId": "cust-1001", "points": 252, "tier": "BRONZE" }
```

Read those two responses again. They are **two separate defects arriving together**, and until you
separate them you will chase the wrong one:

* the service agreed to redeem 100,000 points from a balance of 252, and returned a negative
  balance instead of rejecting the request;
* and then it did not save anything, so the balance is unchanged and the negative number existed
  only in that one response.

Redeem a sensible number — 50 — and the second defect is still there on its own, which is the
cleaner experiment. The response says 202. The database says 252.

There is a test called `redeemingPointsReducesTheBalance`. It passes.

### Symptom D — an unknown customer is a success

```bash
curl -s -o /dev/null -w "%{http_code} %{size_download} bytes\n" localhost:8080/api/loyalty/cust-9999
```

```
200 0 bytes
```

Not `404`. A `200` with an empty body, which any client will treat as "the request worked" and then
fall over on the missing fields.

There is a test about unknown customers. It passes.

### Symptom E — the suite is smaller than it looks

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

Count the methods in the two test classes that look like tests. Compare that with 7. Then read
`target/surefire-reports/com.debuglab.loyalty.LoyaltyAccountTest.txt`, which lists what actually
ran.

Nothing is reported as skipped, because nothing was skipped. Something was never a test at all.

### Symptom F — a test that cannot fail

One of the tests in `LoyaltyAccountTest` sends a redemption for a million points and then ends.
Read it to the last line and ask what would have to happen for it to go red. The answer is: the
application would have to throw an exception on the way out. Anything short of that — any status
code, any body, any balance — is a pass.

### Symptom G — a test that is true by construction

Another test earns points for `cust-1002` and then checks the tier of the account it reads back. Run
it in a debugger, or add a `System.out.println` inside the block that does the checking, and find
out how many times that block runs.

The seeded customer ids are lower case. That is the whole clue.

---

## Investigation hints

Work on the tests first. Each of these hints is about a test, and the application defect behind it
becomes obvious the moment the test can fail.

### The test that never ran (Symptom E)

> **Hint E1**
> `mvn test` prints a count. `target/surefire-reports/*.txt` prints the names. Diff that list against
> the methods in the class.

> **Hint E2**
> JUnit 5 finds tests by annotation, not by naming convention or by signature. A method with a
> test-sounding name and no annotation is just a method — no error, no warning, no "skipped" line,
> because as far as JUnit is concerned it does not exist.

> **Hint E3**
> Add the annotation and run it. What it then tells you is Symptom C's first half: the service
> accepted a redemption it should have rejected. Now go and read what the service does before it
> subtracts.

### The test that asserts nothing (Symptom F)

> **Hint F1**
> `mockMvc.perform(...)` returns a `ResultActions`. On its own it performs the request and hands
> back the result. Nothing about it inspects anything.

> **Hint F2**
> A test with no assertion still passes as long as no exception escapes, which makes it a *smoke
> test*: it proves the code runs. That is a legitimate thing to want, and it is not what this
> method's name promises.

> **Hint F3**
> Give it the assertion its name implies and it fails immediately — and it fails for the same reason
> as the previous one, which tells you those two tests were always testing the same thing and one of
> them should probably be deleted rather than repaired.

### The test that is vacuously true (Symptom G)

> **Hint G1**
> The check is inside a lambda passed to `ifPresent`. Ask what `ifPresent` does when the `Optional`
> is empty. Nothing runs, nothing is asserted, and the test passes.

> **Hint G2**
> Print the `Optional` before the `ifPresent`, or replace it with `orElseThrow()`. The lookup returns
> nothing, and the reason is in the string literal — compare it with the ids in `data.sql`. H2
> compares `VARCHAR` values case-sensitively by default.

> **Hint G3**
> This is the most dangerous pattern of the six, because it is invisible in review: the test *looks*
> like it asserts something. `orElseThrow()`, `assertTrue(optional.isPresent())` first, or
> `assertThat(optional).contains(...)` all fail loudly when the lookup is wrong. Prefer any of them
> to `ifPresent` inside a test.

> **Hint G4**
> Once it can fail, it will — and what it then reports is Symptom A. Go and read the method that
> decides the tier, one comparison operator at a time, against the boundaries in the README.

### The test that passes because of the conditions it runs in (Symptom C)

> **Hint C1**
> `redeemingPointsReducesTheBalance` reads the balance back through the repository and sees the new
> value. `curl` does not. The test is not lying — it is running somewhere the application never runs.

> **Hint C2**
> Look at the annotations on the test class. One of them wraps every test method in a transaction
> that is rolled back at the end. That is normally a good thing: it keeps tests isolated. Now ask
> what else it changes — specifically, what happens to an entity that was loaded inside that
> transaction, and whether a repository read afterwards goes to the database at all or comes back
> out of the persistence context.

> **Hint C3**
> Remove the annotation and the test fails, which is what you wanted. But now the tests interfere
> with each other, because the writes are real. That is the actual engineering problem: you need
> both a test that sees what the database sees, and isolation between tests. Look up
> `@DirtiesContext`, cleaning up in `@BeforeEach`, `TestEntityManager.flush()` and `clear()`, and
> deciding what each test should assert against — and pick deliberately.

> **Hint C4**
> Then the application defect. The service method mutates a loaded entity and returns it. Ask who
> was going to write that change to the database, and what `spring.jpa.open-in-view=false` means for
> an entity once the repository call has returned. Compare the method with the one next to it, which
> does persist correctly.

### The test that mocks away the thing it is testing (Symptom B)

> **Hint B1**
> Read `LoyaltyPointsCalculationTest` and list what it actually asserts. Then ask which of those
> assertions would change if the points calculation returned a different number.

> **Hint B2**
> `assertNotNull` on a value the stub was told to return, and `verify(...save...)` on a mock, are
> both statements about the test's own set-up. Neither one looks at the arithmetic, which is the only
> thing this test exists for.

> **Hint B3**
> Assert the number of points on the returned account. Then read the one line in the service that
> converts rupees into points and check it against "rounded down" in the README —
> `Math.round`, `Math.floor`, integer division and `BigDecimal.setScale(0, RoundingMode.FLOOR)` do
> four different things, and only some of them are right here.

> **Hint B4**
> Consider whether this test needs Spring at all. A calculation like this is a pure function of its
> input; a plain JUnit test with no context, no mock and a table of amounts and expected points
> would be faster, clearer and much harder to write wrongly.

### The test that checks the wrong layer (Symptom D)

> **Hint D1**
> The test about unknown customers asks the service. The symptom is in the HTTP response. Those are
> two different questions, and the service's answer is correct.

> **Hint D2**
> Change it to make the request and assert the status. Then look at how the controller turns an
> empty result into a response, and what `ResponseEntity.ok(null)` produces on the wire.

> **Hint D3**
> There is a ready-made exception in this project with the right status on it, used by the other
> code path. Use it, and note the general rule: a `200` with an empty body is never the right answer
> to "that does not exist".

---

## Expected logs and observations

* The application logs `... redeemed 100000 points and now has -99748` — the service is doing
  exactly what it was told, and saying so.
* Nothing in the log marks the missing persistence. There is no error, because nothing failed; a
  write simply never happened.
* `mvn test` is green from the first run to the moment you repair the first test. There is nothing
  to grep for; the evidence is in what the test file says versus what it does.
* `target/surefire-reports/*.txt` is the list of what really ran. Read it, rather than trusting the
  count.
* `mvn test -Dtest=ClassName#methodName` runs one test on its own — the fastest way to see a repair
  go red.

## Difficulty

**Advanced.** Expect two hours. Eleven defects — six in the test suite, five in the application.

Each application defect sits behind exactly one test defect, so the pairs can be attacked in any
order:

```
never ran           →  redemption is not checked against the balance
asserts nothing     →  (the same defect, from the other side)
vacuously true      →  tier boundaries are exclusive instead of inclusive
transactional test  →  the redemption is never persisted
mocked calculation  →  points are rounded to nearest instead of down
wrong layer         →  an unknown customer returns 200 with an empty body
```

The temptation is to fix the five application defects straight away — they are not hard once you
have seen them. Resist it. The exercise is the other five minutes: making each test fail first, so
that you know it was watching.

## Concepts being tested

* JUnit 5 discovery: what makes a method a test, and what happens to one that is not
* Tests without assertions, and when a smoke test is legitimate
* Vacuous assertions — `ifPresent`, `forEach`, `stream().filter()` — that never execute
* `@Transactional` on a test class: isolation, rollback, and the persistence context hiding a
  missing write
* Test isolation without a rollback: `@DirtiesContext`, per-test clean-up, flush and clear
* Mockito stubs and `verify`, and the difference between testing behaviour and restating your set-up
* Testing at the layer where the contract lives
* Reading Surefire reports rather than the summary line
* `Math.round` vs floor vs integer division vs `RoundingMode`
* Inclusive and exclusive boundaries, and why seed data should sit on them

## When you think you are done

- [ ] Every method in both test classes either runs or has been deleted on purpose.
- [ ] Every test has at least one assertion, and you can say in one sentence what each one would
      catch.
- [ ] Temporarily reintroduce each application defect, one at a time, and confirm a test goes red
      for it. This is the only check that matters in this project.
- [ ] `mvn test` is green with the application fixed.
- [ ] `mvn test -Dtest=LoyaltyAccountTest#redeemingPointsReducesTheBalance` on its own is green too.
- [ ] Run the suite twice in a row without rebuilding. Still green — no test depends on another
      having run first.
- [ ] ₹150 earns 1 point; ₹199 earns 1; ₹200 earns 2.
- [ ] Exactly 1,000 points is `SILVER`; exactly 5,000 is `GOLD`.
- [ ] A redemption of 50 is visible on the next `GET`; a redemption of 100,000 returns `409` and
      changes nothing.
- [ ] `/api/loyalty/cust-9999` returns `404`.

Then say **"I think I fixed the project"**.
