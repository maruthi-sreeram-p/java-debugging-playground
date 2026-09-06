# SOLUTION — 22 · Loyalty Points Service

> Reference copy. Do not read this until you have finished, or until you have asked for the solution
> explicitly.

Eleven defects: six in the test suite and five in the application. Each application defect is hidden
by exactly one test defect.

| # | Test defect | Hides |
|---|---|---|
| 1 | A test method with no `@Test` annotation | 7 — no balance check on redemption |
| 2 | A test with no assertions at all | 7 — the same defect, from the other side |
| 3 | An assertion inside `ifPresent` on an empty `Optional` | 8 — the tier boundaries |
| 4 | `@Transactional` on the test class | 9 — the redemption is never persisted |
| 5 | A mocked test that asserts its own set-up | 10 — points rounded to nearest, not down |
| 6 | A test that checks the service, not the HTTP contract | 11 — unknown customer returns `200` |

---

## Defect 1 — a test method that JUnit never sees

**Where:** `LoyaltyAccountTest.redeemingMoreThanTheBalanceIsRejected`.

```java
    void redeemingMoreThanTheBalanceIsRejected() throws Exception {
        mockMvc.perform(post("/api/loyalty/cust-1001/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":100000}"))
                .andExpect(status().isConflict());
    }
```

**Root cause:** no `@Test`. JUnit 5 discovers tests by annotation only — there is no naming
convention and no signature rule that would make this method run. It is an ordinary package-private
method that nothing calls.

Nothing reports it. It is not "skipped" (that word is reserved for `@Disabled` and failed
assumptions), so the summary line reads `Skipped: 0` and looks perfectly healthy. The only trace is
that the number of tests is smaller than the number of test-shaped methods.

**Fix:**

```java
    @Test
    void redeemingMoreThanTheBalanceIsRejected() throws Exception {
```

Then it fails — `Status expected:<409> but was:<200>` — which is Defect 7.

**Why a fresher writes this:** the method is written by copying the one above it and editing the
body; the annotation is on the line before the copy started. It also happens when a test is
temporarily disabled by deleting the annotation instead of using `@Disabled`, which at least says
why and shows up in the report.

**How to recognise it in a real project:** compare the reported test count with what you expect, and
read `target/surefire-reports/*.txt`, which names every method that ran. An IDE will usually grey
out an un-annotated method or refuse to offer a "run" gutter icon next to it.

---

## Defect 2 — a test with no assertions

**Where:** `LoyaltyAccountTest.anAccountCanRedeemPoints`.

```java
@Test
void anAccountCanRedeemPoints() throws Exception {
    mockMvc.perform(post("/api/loyalty/cust-1003/redeem")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"points\":1000000}"));
}
```

**Root cause:** `mockMvc.perform(...)` executes the request and returns a `ResultActions`. Without
`.andExpect(...)` nothing is inspected. The test passes for any status code, any body and any
balance; the only way it can fail is if an exception escapes the request entirely.

It is redeeming a million points from an account with 4,990 — the exact scenario that should be
rejected — and it never looks at the answer.

**Fix:**

```java
@Test
void redeemingMoreThanTheBalanceLeavesTheAccountUnchanged() throws Exception {
    mockMvc.perform(post("/api/loyalty/cust-1003/redeem")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"points\":1000000}"))
            .andExpect(status().isConflict());

    mockMvc.perform(get("/api/loyalty/cust-1003"))
            .andExpect(jsonPath("$.points").value(4990));
}
```

Once Defect 1 is also fixed, these two tests assert the same rule; keep the stronger one (this one,
which also checks that nothing changed) and delete the other.

A test with no assertion is not always wrong — a *smoke test* that asserts only "this runs without
throwing" is a legitimate thing to have, especially for a context-loading check. It is wrong when
the method name promises a behaviour, as this one does.

**Why a fresher writes this:** the test was written to reproduce something by hand, the assertion
was going to be added afterwards, and it went green so it looked finished.

**How to recognise it in a real project:** grep the test sources for `perform(` without a following
`andExpect`. Static analysis rules exist for it (SonarQube's "Tests should include assertions",
`JUnitTestsShouldIncludeAssert` in PMD) and are worth switching on.

---

## Defect 3 — an assertion that never executes

**Where:** `LoyaltyAccountTest.reachingOneThousandPointsPromotesTheAccountToSilver`.

```java
loyaltyAccountRepository.findByCustomerId("CUST-1002")
        .ifPresent(account -> assertEquals("SILVER", account.getTier()));
```

**Root cause:** the seeded id is `cust-1002`, lower case, and H2 compares `VARCHAR` values
case-sensitively by default. The lookup returns an empty `Optional`, `ifPresent` runs nothing, and
the test passes having asserted precisely nothing.

This is the most dangerous of the six, because it survives code review: the line contains a real
assertion with the right expected value. It just never runs.

The same shape occurs with `list.forEach(x -> assertThat(x)...)` on an empty list and
`stream().filter(...).forEach(...)` when the filter matches nothing.

**Fix:** make the absence itself a failure.

```java
LoyaltyAccount account = loyaltyAccountRepository.findByCustomerId("cust-1002").orElseThrow();
assertEquals("SILVER", account.getTier());
```

Then it fails — `expected: <SILVER> but was: <BRONZE>` — which is Defect 8. Better still, assert
through the API rather than the repository, so the test also covers what a client sees:

```java
mockMvc.perform(get("/api/loyalty/cust-1002"))
        .andExpect(jsonPath("$.points").value(1000))
        .andExpect(jsonPath("$.tier").value("SILVER"));
```

**Why a fresher writes this:** `ifPresent` reads defensively and looks careful — "check the tier if
the account is there". In production code that is often right; in a test, "if it is there" is
exactly the thing you were supposed to be asserting.

**How to recognise it in a real project:** any assertion inside a lambda passed to `ifPresent`,
`forEach`, `map` or a filtered stream. Ask what happens when the collection is empty. If the answer
is "the test passes", it is not a test.

---

## Defect 4 — `@Transactional` on the test class

**Where:** `LoyaltyAccountTest`.

```java
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoyaltyAccountTest {
```

```java
@Test
void redeemingPointsReducesTheBalance() throws Exception {
    mockMvc.perform(post("/api/loyalty/cust-1001/redeem")...).andExpect(status().isOk());

    LoyaltyAccount account = loyaltyAccountRepository.findByCustomerId("cust-1001").orElseThrow();
    assertEquals(200, account.getPoints());
}
```

**Root cause:** `@Transactional` on a test wraps each method in a transaction that is rolled back at
the end. That is normally a benefit — it gives isolation for free. Here it also changes the
behaviour of the code under test:

* the MockMvc request runs on the same thread, so the service's repository call joins the *test's*
  transaction instead of opening its own;
* the account it loads is therefore managed by the test's persistence context;
* the service mutates that managed instance and never saves it;
* the test then reads the account back — and Spring Data returns the same instance out of the
  first-level cache, without going to the database at all.

The assertion sees the mutation, so the test passes. The application, where each repository call is
its own short transaction and the entity is detached the moment it returns, never writes anything.
The test is not wrong about what it observed; it observed something the application will never do.

**Fix — two problems, two fixes.** First, make the test see what the database sees:

```java
@SpringBootTest
@AutoConfigureMockMvc
class LoyaltyAccountTest {
```

That immediately fails — `expected: <200> but was: <250>` — which is Defect 9.

Second, put isolation back, because the writes are now real and the tests will start interfering
with each other (removing the annotation on its own makes `anAccountCanBeRead` fail too, for exactly
that reason). Options, roughly in order of preference:

* reset the data in `@BeforeEach` — delete and re-seed, or restore the known balances;
* `@Sql(scripts = "/data.sql", executionPhase = BEFORE_TEST_METHOD)`;
* `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` — correct, but slow, because it rebuilds the
  Spring context;
* assert relative changes rather than absolute values, so order does not matter.

If you keep `@Transactional` for other tests, use `TestEntityManager.flush()` and `clear()` before
reading back, which forces the assertion to go to the database rather than the persistence context.

**Why a fresher writes this:** `@Transactional` on tests is standard advice, it appears in the Spring
documentation, and it is genuinely useful. Nobody mentions that it also merges the test and the
application into one transaction, which is precisely the condition under which a missing `save()`
becomes invisible.

**How to recognise it in a real project:** a test that passes and a manual run that does not, for
anything involving a write. It is the single most common reason a green suite ships broken
persistence code.

---

## Defect 5 — a mocked test that asserts its own set-up

**Where:** `LoyaltyPointsCalculationTest.spendingIsConvertedIntoPoints`.

```java
when(loyaltyAccountRepository.findByCustomerId("cust-9001")).thenReturn(Optional.of(account));
when(loyaltyAccountRepository.save(any(LoyaltyAccount.class))).thenReturn(account);

LoyaltyAccount updated = loyaltyService.earn("cust-9001", new BigDecimal("150.00"));

assertNotNull(updated);
verify(loyaltyAccountRepository).save(any(LoyaltyAccount.class));
```

**Root cause:** the two assertions are both statements about the test's own scaffolding.
`assertNotNull(updated)` checks that the stub returned the object the stub was told to return.
`verify(...).save(any(...))` checks that a save happened, with no interest in what was saved. The
number of points — the only thing this test exists to check — is never looked at, so the test passes
whatever the arithmetic does.

**Fix:** assert the outcome.

```java
LoyaltyAccount updated = loyaltyService.earn("cust-9001", new BigDecimal("150.00"));
assertEquals(1, updated.getPoints());
```

Fails with `expected: <1> but was: <2>` — Defect 10. If you want to check what was persisted rather
than what was returned, capture it:

```java
ArgumentCaptor<LoyaltyAccount> saved = ArgumentCaptor.forClass(LoyaltyAccount.class);
verify(loyaltyAccountRepository).save(saved.capture());
assertEquals(1, saved.getValue().getPoints());
```

And a broader point: this test does not need Spring or Mockito at all. The conversion from an amount
to points is a pure function; a plain JUnit test over a table of amounts and expected points would
be faster, clearer, and much harder to write vacuously:

```java
@ParameterizedTest
@CsvSource({"99, 0", "100, 1", "150, 1", "199, 1", "200, 2", "1000, 10"})
void spendingIsRoundedDownToWholePoints(String amount, int expectedPoints) { ... }
```

**Why a fresher writes this:** `verify(...)` feels like a strong assertion because it involves the
mock framework, and `assertNotNull` feels like due diligence. Both are habits picked up from
examples that were demonstrating Mockito's syntax rather than testing anything.

**How to recognise it in a real project:** read each assertion and ask "which line of production
code has to be wrong for this to fail?" If the answer is "none", the test is decoration. Tests whose
only assertions are `verify` and null-checks are the usual offenders.

---

## Defect 6 — the test checks the wrong layer

**Where:** `LoyaltyAccountTest.anUnknownCustomerHasNoAccount`.

```java
@Test
void anUnknownCustomerHasNoAccount() {
    assertTrue(loyaltyService.find("cust-9999").isEmpty());
}
```

**Root cause:** the assertion is true, and the service is correct — `find` returns an empty
`Optional`, exactly as it should. The contract that is broken lives one layer up, in how the
controller turns that empty `Optional` into an HTTP response. The test never makes a request, so it
cannot see it.

**Fix:** test where the contract is.

```java
@Test
void anUnknownCustomerIsNotFound() throws Exception {
    mockMvc.perform(get("/api/loyalty/cust-9999"))
            .andExpect(status().isNotFound());
}
```

Fails with `Status expected:<404> but was:<200>` — Defect 11.

**Why a fresher writes this:** it is easier to call a service method than to build a request, and the
name of the test ("has no account") describes the service's behaviour rather than the API's. It is
also what happens when a test is written against the layer that was in the editor at the time.

**How to recognise it in a real project:** for every rule in your API documentation, ask which test
would fail if you deleted the code that implements it. If the rule is about status codes, headers or
response shape, the test has to go through the web layer.

---

## Defect 7 — redemption is not checked against the balance

**Where:** `service/LoyaltyService.redeem`.

```java
account.setPoints(account.getPoints() - points);
```

**Root cause:** there is no check at all, and `InsufficientPointsException` — which exists, and
carries `@ResponseStatus(HttpStatus.CONFLICT)` — is never thrown by anything. Redeeming 100,000
points from a balance of 252 produces `-99748` and `200 OK`.

**Fix:**

```java
if (points > account.getPoints()) {
    throw new InsufficientPointsException(customerId, points, account.getPoints());
}
account.setPoints(account.getPoints() - points);
```

Enforce it in the database too, since a balance that cannot be negative is an invariant, not a
preference: `CHECK (points >= 0)` on the column turns any future path around this check into a loud
failure instead of a silent one.

**Why a fresher writes this:** the exception class was created first, as part of designing the API,
and the branch that throws it was left for later. Nothing refers to an unused exception class, so
nothing points out that it is unused.

**How to recognise it in a real project:** search for exception types that are declared and never
thrown, and for documented error responses that no test produces.

---

## Defect 8 — the tier boundaries are exclusive

**Where:** `service/LoyaltyService.tierFor`.

```java
private static final int SILVER_FROM = 1000;
private static final int GOLD_FROM = 5000;

if (points > GOLD_FROM) return "GOLD";
if (points > SILVER_FROM) return "SILVER";
return "BRONZE";
```

**Root cause:** the constants are named `..._FROM`, which means inclusive, and the comparisons are
`>`, which is exclusive. Exactly 1,000 points is `BRONZE` and exactly 5,000 is `SILVER` — one tier
too low, at exactly the moment the customer was promised a promotion.

**Fix:**

```java
if (points >= GOLD_FROM) return "GOLD";
if (points >= SILVER_FROM) return "SILVER";
return "BRONZE";
```

**Why a fresher writes this:** "over a thousand points" in English is ambiguous, and `>` is the more
natural translation of it. Off-by-one at a boundary is the most common arithmetic defect there is,
and it is invisible unless a test lands *exactly* on the boundary — which is why the seed data in
this project sits at 995 and 4,990.

**How to recognise it in a real project:** test boundaries at `n-1`, `n` and `n+1`, always. A test
at 1,500 points would have passed here and proved nothing.

---

## Defect 9 — the redemption is never persisted

**Where:** `service/LoyaltyService.redeem`.

```java
public LoyaltyAccount redeem(String customerId, int points) {
    LoyaltyAccount account = loyaltyAccountRepository.findByCustomerId(customerId)...;
    account.setPoints(account.getPoints() - points);
    account.setTier(tierFor(account.getPoints()));
    return account;
}
```

**Root cause:** no `@Transactional`, and no `save`. The repository call runs in its own short
transaction and the entity is **detached** by the time it is returned (`spring.jpa.open-in-view` is
`false`, as it should be). Mutating a detached entity changes an object in memory and nothing else.

The mutated object is what the controller serialises, which is why the response shows the new
balance — and why the next `GET` shows the old one. Compare with `earn` immediately above it, which
is annotated and does call `save`.

**Fix:**

```java
@Transactional
public LoyaltyAccount redeem(String customerId, int points) {
    ...
    return loyaltyAccountRepository.save(account);
}
```

`@Transactional` alone would be enough here — inside a transaction the entity is managed and dirty
checking flushes the change — but keeping the explicit `save` makes the intent readable and survives
somebody later removing the annotation.

**Why a fresher writes this:** it usually works. In a project with `open-in-view` left at its default
of `true`, or in a test wrapped in `@Transactional`, the entity stays managed and the change is
written or at least visible. The defect appears when the surrounding configuration changes — which
is exactly what "works on my machine" means.

**How to recognise it in a real project:** an update whose response shows the new value and whose
next read shows the old one. Turn on `logging.level.org.hibernate.SQL=DEBUG` and look for the
`update` statement. If there is no SQL, there was no write.

---

## Defect 10 — points are rounded to nearest instead of down

**Where:** `service/LoyaltyService.earn`.

```java
int earned = (int) Math.round(amount.doubleValue() / SPEND_PER_POINT.doubleValue());
```

**Root cause:** `Math.round` rounds to nearest, so ₹150 earns 2 points instead of 1 and ₹50 earns 1
instead of 0. The rule is "rounded down". Multiples of ₹100 are unaffected, which is why every
round-number test passes and the defect only appears on real shopping baskets.

There is a second problem in the same line: converting `BigDecimal` to `double` for a money
calculation. It does not bite at these magnitudes, but it is how money calculations start drifting.

**Fix:**

```java
int earned = amount.divide(SPEND_PER_POINT, 0, RoundingMode.FLOOR).intValue();
```

`RoundingMode.FLOOR` states the rule in the code, and staying in `BigDecimal` removes the
floating-point conversion. `Math.floor`, integer division on paise, or `divideToIntegralValue` are
all defensible; `Math.round` is the one that is not.

**Why a fresher writes this:** `Math.round` is the method everyone reaches for when a decimal has to
become an integer. Choosing it is not a slip — it is not knowing that "round" and "round down" are
different requirements.

**How to recognise it in a real project:** totals that are a point or a rupee too high, in the
customer's favour, on some transactions and not others. Test with values that are not multiples of
the divisor — `99`, `150`, `199` — never with `100` and `1000`.

---

## Defect 11 — an unknown customer returns `200` with an empty body

**Where:** `controller/LoyaltyController.account`.

```java
return ResponseEntity.ok(loyaltyService.find(customerId).orElse(null));
```

**Root cause:** `orElse(null)` turns "not found" into a `200 OK` with an empty body — zero bytes,
no JSON, no error. A client parses that into a null object and fails somewhere else entirely, with
a stack trace that has nothing to do with the missing customer.

`AccountNotFoundException` already exists and already carries `@ResponseStatus(HttpStatus.NOT_FOUND)`;
the service's other call sites use it.

**Fix:**

```java
LoyaltyAccount account = loyaltyService.find(customerId)
        .orElseThrow(() -> new AccountNotFoundException(customerId));
return ResponseEntity.ok(account);
```

or, without the exception:

```java
return loyaltyService.find(customerId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
```

**Why a fresher writes this:** `orElse(null)` is the shortest way to get an `Optional` into a method
that wants a value, and the compiler is satisfied. The response looks fine in a browser, which shows
a blank page rather than an error.

**How to recognise it in a real project:** `curl -o /dev/null -w "%{http_code} %{size_download}"`
on a resource you know does not exist. `200 0` is always a defect. Grep for `orElse(null)` in
controllers.

---

## Order of discovery

Unlike most projects in this laboratory, there is no chain here: the six pairs are independent and
can be tackled in any order. The order that matters is *within* each pair.

```
repair the test   →   watch it fail   →   fix the application   →   watch it pass
```

Doing it the other way round is the mistake this project exists to prevent. If you fix the
application first, the suite goes from green to green and you have learned nothing about whether it
would ever have told you.

## Test baseline

As shipped: **7 tests run, 0 failures, BUILD SUCCESS** — while the application gets the tier
boundaries wrong, rounds points the wrong way, allows a negative balance, does not persist
redemptions, and returns `200` for a customer that does not exist.

There are eight test-shaped methods and seven of them run.

With all six test defects repaired and the application untouched:
**8 tests run, 7 fail.**

| Test | Failure | Application defect |
|---|---|---|
| `reachingOneThousandPointsPromotesTheAccountToSilver` | `expected: <SILVER> but was: <BRONZE>` | 8 |
| `redeemingPointsReducesTheBalance` | `expected: <200> but was: <250>` | 9 |
| `redeemingMoreThanTheBalanceIsRejected` | `expected:<409> but was:<200>` | 7 |
| `anAccountCanRedeemPoints` | `expected:<409> but was:<200>` | 7 |
| `anUnknownCustomerHasNoAccount` | `expected:<404> but was:<200>` | 11 |
| `spendingIsConvertedIntoPoints` | `expected: <1> but was: <2>` | 10 |
| `anAccountCanBeRead` | `expected:<250> but was:<260>` | **none — test pollution** |

That last row is worth studying. It was passing, it is now failing, and the application has nothing
to do with it: removing `@Transactional` made every test's writes real, so an earlier test's earning
leaks into this one's balance. Fixing it means restoring isolation deliberately — clean-up in
`@BeforeEach`, `@Sql` re-seeding, `@DirtiesContext`, or assertions on relative change — rather than
putting the annotation back and losing the ability to see Defect 9 again.
