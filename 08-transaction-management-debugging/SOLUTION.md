# SOLUTION — 08 · Banking Transaction API

> **Sealed answer key.** Seven planted defects.

---

## Defect 1 — `AccountService.debit` is `@Transactional(propagation = REQUIRES_NEW)`

* **Bug:**

  ```java
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BigDecimal debit(String accountNumber, BigDecimal amount) { ... }
  ```

  while `credit` next to it is a plain `@Transactional`.
* **Affected component:** `service/AccountService.debit`
* **Root cause:** `REQUIRES_NEW` **suspends** the caller's transaction and starts an independent one.
  That inner transaction commits as soon as `debit` returns. When `TransferService.transfer` later
  fails and rolls back, there is nothing left to roll back — the debit was committed minutes (or
  microseconds) earlier, in a transaction Spring no longer has any relationship with.
* **Symptom:** `POST /api/transfers` to a non-existent destination returns `404`, and the source
  account is nonetheless `1000.00` lighter. Money leaves the banking system.
* **Why the symptom is misleading:** the API's error response is *correct*. It correctly identified
  the problem, correctly refused the transfer, and correctly reported `404`. Everything the client
  can see says "nothing happened". A developer trusting the response will not go looking at the
  balance. And `TransferService.transfer` carries `@Transactional` in plain sight, so the code
  reads as obviously atomic.
* **Correct fix:** remove the propagation override so the debit joins the caller's transaction:

  ```java
  @Transactional
  public BigDecimal debit(String accountNumber, BigDecimal amount) { ... }
  ```

  `@Transactional(propagation = Propagation.MANDATORY)` is an even better answer worth crediting
  generously — it makes it a *runtime error* to call `debit` outside a transaction, which would have
  caught Defect 5 as well.
* **Concept:** propagation; transaction suspension; the fact that an inner `REQUIRES_NEW` commit is
  irreversible from the outer transaction.
* **Why a fresher makes it:** `REQUIRES_NEW` is usually copied in from an audit or logging example
  ("so it always gets written") without understanding that it breaks atomicity for anything that is
  part of the business operation. It also frequently arrives as a "fix" for a transaction-timeout or
  a lock problem.
* **How to recognise it in the wild:** count the transactions in one logical operation. Anything that
  begins a transaction of its own inside a unit of work is outside that unit of work's atomicity, by
  definition. Grep for `REQUIRES_NEW` and justify every occurrence.

---

## Defect 2 — Catching an exception thrown by a participating transactional method

* **Bug:** `TransferService.transfer` wraps its body in
  `try { ... } catch (AccountFrozenException ex) { ...return FAILED result... }`, and the exception
  originates inside `AccountService.credit`, which is itself `@Transactional`.
* **Affected component:** `service/TransferService.transfer` together with `AccountService.credit`
* **Root cause:** `credit` participates in the caller's transaction (propagation `REQUIRED`, no new
  transaction created). When an exception escapes a participating transactional method, Spring's
  `TransactionAspectSupport` calls `setRollbackOnly()` on the shared transaction — it cannot roll
  back only its own part, so it flags the whole thing as doomed. Catching the exception afterwards
  does not clear that flag. When `transfer` returns normally, the outer interceptor tries to commit,
  the transaction manager refuses, and Spring throws
  `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as
  rollback-only`.
* **Symptom:** `500 Internal Server Error` from a code path that visibly and deliberately handles the
  error. The `WARN` log line from the catch block *does* appear, which makes it more confusing, not
  less.
* **Why the symptom is misleading:** the developer's mental model is "I caught it, so it is handled".
  The exception message compounds this — "silently rolled back" sounds like a description of a
  rollback that succeeded, so people read it as informational and go looking elsewhere. It is
  actually saying: *your commit failed*.
* **Correct fix — two acceptable families:**

  1. **Check before you act** (preferred in this domain). Validate that the destination is
     creditable before any money moves, so the exceptional case never enters the transaction:

     ```java
     @Transactional
     public TransferResult transfer(TransferRequest request) throws LedgerException {
         Account destination = accountService.load(request.getToAccount());
         if (destination.isFrozen()) {
             transferAuditService.record(..., "FAILED", "Account " + ... + " is frozen");
             return failedResult(request, "...");
         }
         ...
     }
     ```

  2. **Keep the graceful handling, stop the poisoning.** Make the frozen check happen somewhere that
     does not participate in the transaction, or have `credit` signal the condition with a return
     value rather than an exception.

  Whatever they choose, the acceptance criterion is: `200` with `"status":"FAILED"`, and no money
  moved.
* **Concept:** participating transactions; `setRollbackOnly`; `UnexpectedRollbackException`;
  the difference between handling an exception and preventing a rollback.
* **Why a fresher makes it:** it is genuinely counter-intuitive. Everywhere else in Java, catching an
  exception contains it. This is the one place where the damage is already done by the time you
  catch it.
* **How to recognise it in the wild:** `UnexpectedRollbackException` with no obvious cause almost
  always means "a nested `@Transactional` method threw, and someone upstream swallowed it". Search
  the call path for a `catch` around a transactional call.

---

## Defect 3 — A checked exception does not trigger rollback

* **Bug:** `LedgerService.record` throws `LedgerException extends Exception` (checked).
  `TransferService.transfer` is annotated with a bare `@Transactional` and declares
  `throws LedgerException`.
* **Affected component:** `service/TransferService.transfer` and `exception/LedgerException`
* **Root cause:** Spring's default rollback rule is **roll back on `RuntimeException` and `Error`,
  commit on checked exceptions**. `LedgerException` is checked, so when it propagates out of
  `transfer`, Spring commits the transaction and then lets the exception continue to the caller. The
  balance changes made before the throw are permanent.
* **Symptom:** replaying a reference returns `409 Conflict` with "The ledger already holds entries
  for reference TX-200", and the money moves a second time anyway. The idempotency key detects the
  duplicate and does nothing to prevent it.
* **Why the symptom is misleading:** the error handling is visibly working — the duplicate *was*
  detected and the client *was* told. Nothing suggests that the detection came too late and that the
  commit happened anyway. This defect also sits behind Defect 1: a learner who has not yet fixed the
  debit's propagation will see money move on replay for two independent reasons.
* **Correct fix — both halves matter:**

  1. Make the rollback rule cover it:

     ```java
     @Transactional(rollbackFor = LedgerException.class)
     public TransferResult transfer(TransferRequest request) throws LedgerException { ... }
     ```

     Or, better, make `LedgerException extend RuntimeException` — there is no value in forcing every
     caller to declare it, and unchecked is what the rest of the codebase uses.

  2. **Move the idempotency check to the front.** A guard that runs after the work it guards is not a
     guard. Checking `existsByReference` before the debit is the real fix; `rollbackFor` is the
     safety net. Hint C4 points at this — credit the learner who does both, and push the one who only
     does the first.
* **Concept:** default rollback rules; checked versus unchecked exceptions; `rollbackFor`;
  ordering of validation relative to side effects.
* **Why a fresher makes it:** nothing warns them. The code compiles, the exception is declared and
  handled, the client gets a sensible error. The rule that checked exceptions commit is a piece of
  inherited EJB-era behaviour that is not visible anywhere in the code.
* **How to recognise it in the wild:** any custom exception extending `Exception` rather than
  `RuntimeException` in a transactional service is a rollback bug waiting to happen. Most teams settle
  this by making all domain exceptions unchecked.

---

## Defect 4 — `AccountService.freeze` is `@Transactional(readOnly = true)`

* **Bug:**

  ```java
  @Transactional(readOnly = true)
  public AccountResponse freeze(String accountNumber) {
      Account account = load(accountNumber);
      account.setFrozen(true);
      accountRepository.save(account);
      ...
  }
  ```

* **Affected component:** `service/AccountService.freeze`
* **Root cause:** Spring's Hibernate integration sets the session's `FlushMode` to `MANUAL` for a
  read-only transaction. Dirty checking still tracks the change, but nothing ever flushes it, and
  `save()` on an already-managed entity is a no-op merge that does not force a flush either. At
  commit there is no automatic flush, so no `update` statement is ever generated. The change exists
  only in memory.
* **Symptom:** `POST /api/accounts/ACC-1003/freeze` returns `200` with `"frozen": true` — because the
  response is built from the in-memory entity, which genuinely was modified. A subsequent `GET`
  returns `"frozen": false`. No exception, no warning, no `update` in the SQL log.
* **Why the symptom is misleading:** identical in shape to project 01's create-response defect and
  project 04's `@Transient` defect — the response reflects an in-memory object that was never
  persisted. By this point in the lab the learner should recognise the pattern; if they do, say so.
  The additional trap here is that `readOnly = true` appears on three other methods in the same class
  where it is entirely correct, so it does not look out of place.
* **Correct fix:**

  ```java
  @Transactional
  public AccountResponse freeze(String accountNumber) { ... }
  ```

* **Concept:** `readOnly` and flush modes; **and the crucial subtlety in hint D4**: `readOnly` is
  applied only when the annotated method *starts* the transaction. On a method that merely joins an
  existing read-write transaction, the flag is ignored entirely. `freeze` is called straight from the
  controller, so it starts the transaction and the flag bites. Make sure the learner understands this
  — it is why "add `readOnly` everywhere for performance" is a dangerous habit that fails
  unpredictably depending on the call path.
* **Why a fresher makes it:** they add `readOnly = true` to every service method they think of as
  cheap, or copy the annotation from the `lookup` method directly above it. In a read-heavy class,
  the one write method is easy to miss.
* **How to recognise it in the wild:** "the API says it saved and the column did not change", with no
  `update` in the SQL log. Check the transaction's read-only flag before suspecting the mapping.

---

## Defect 5 — `batchTransfer` self-invokes `transfer`

* **Bug:**

  ```java
  public List<TransferResult> batchTransfer(BatchTransferRequest request) {
      for (TransferRequest each : request.getTransfers()) {
          try {
              results.add(transfer(each));      // direct call on `this`
          } ...
      }
  }
  ```

  `batchTransfer` is deliberately **not** transactional (each item must be independent), and calls
  `transfer` directly rather than through the proxy.
* **Affected component:** `service/TransferService.batchTransfer`
* **Root cause:** `@Transactional` is implemented by an AOP proxy that wraps the bean. A call made
  on `this` from inside the same bean never reaches the proxy, so `transfer`'s `@Transactional` is
  simply not applied for batch items. Each item therefore runs with **no** surrounding transaction at
  all: the debit gets its own (`REQUIRES_NEW`), the credit starts one of its own, each ledger write
  starts one of its own, and every one of them commits independently. A failure part-way through
  leaves everything before it permanently committed.
* **Symptom:** a batch of `500 → ok`, `700 → fails`, `300 → ok` reduces the source by `1500` and
  increases the destination by only `800`. The failed item's `700` is gone, with no ledger entry.
  Crucially, **the identical failure through `POST /api/transfers` behaves differently**, because
  that path enters through the proxy.
* **Why the symptom is misleading:** the same method, the same request, two behaviours depending on
  the caller. That is not a hypothesis most people reach for. `transfer` is annotated correctly and
  demonstrably works — so the learner concludes the annotation is fine and looks at the batch logic
  instead.
* **Correct fix — any of these; ask them to justify their choice:**
  * Move `transfer` into its own bean and inject it (cleanest, and makes the boundary explicit).
  * Inject `TransferService` into itself (with `@Lazy` to break the cycle) and call
    `self.transfer(each)`.
  * Obtain the proxy via `AopContext.currentProxy()` (requires `@EnableAspectJAutoProxy(exposeProxy = true)`).
  * Use `TransactionTemplate` explicitly around each item.

  What must **not** change: `batchTransfer` must stay non-transactional so items remain independent,
  as documented.
* **Concept:** proxy-based AOP; self-invocation; why `@Transactional`, `@Cacheable`, `@Async` and
  `@PreAuthorize` all fail the same way when a method is called on `this`.
* **Why a fresher makes it:** calling your own method is the most natural thing in Java. Nothing in
  the language or the compiler hints that the annotation was applied to a wrapper rather than to the
  method.
* **How to recognise it in the wild:** the tell is *the same code behaving differently by entry
  point*. When an annotation appears not to apply, ask whether the call went through the proxy. This
  is the same root cause as project 02's Defect 5, seen from the other side.

---

## Defect 6 — `TransferAuditService.record` joins the caller's transaction

* **Bug:**

  ```java
  @Transactional
  public void record(String reference, ..., String status, String message) {
      transferAuditRepository.save(new TransferAudit(...));
  }
  ```

  The class's own comment states the audit trail must outlive a rollback.
* **Affected component:** `service/TransferAuditService.record`
* **Root cause:** propagation `REQUIRED` joins the transfer's transaction. When the transfer rolls
  back, the audit row rolls back with it. The one record that was supposed to explain the failure is
  destroyed by the failure.
* **Symptom:** `GET /api/transfers/audit` shows only successful transfers. Every failed attempt is
  invisible.
* **Why the symptom is misleading:** the audit code is correct, the call site is correct, and the
  intent is documented. There is no error at any point — the row is genuinely written, and then
  genuinely un-written. If the learner watches the SQL log they will see the `insert` and conclude it
  worked.
* **Correct fix:**

  ```java
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(...) { ... }
  ```

* **Concept:** propagation as a deliberate design decision; separating an audit trail from the
  transaction it audits.
* **The pairing with Defect 1 is the whole point of this project.** The same annotation is *wrong*
  on the debit and *right* on the audit write. `REQUIRES_NEW` is not a code smell and it is not a
  best practice — it is a decision about whether a particular step belongs inside the unit of work.
  Ask the learner to articulate the difference in one sentence. If they can say "the debit is part of
  the transfer, the audit row is a record *about* the transfer", they have understood transaction
  design rather than memorised a rule.
* **Why a fresher makes it:** `@Transactional` with no attributes is the default they always write.
  The requirement — "must survive rollback" — is a sentence in a comment, not something the
  annotation makes them think about.
* **How to recognise it in the wild:** any audit, metrics or outbox write that disappears exactly
  when you most need it. Ask: "should this survive the failure it is describing?"

---

## Defect 7 — Lost update: read-modify-write with no locking

* **Bug:**

  ```java
  Account account = load(accountNumber);
  account.setBalance(account.getBalance().subtract(amount));
  accountRepository.save(account);
  ```

  `Account` has no `@Version` field, no pessimistic lock is taken, and the arithmetic happens in
  Java.
* **Affected component:** `service/AccountService.debit` and `credit`; `entity/Account`
* **Root cause:** two concurrent transactions both read balance `X`, both compute `X - 100`, and both
  write it. InnoDB serialises the two `UPDATE` statements with row locks, so neither fails — they
  simply both store the same wrong value. One debit is silently lost. The ledger, meanwhile, records
  both, so the ledger and the balance diverge.
* **Symptom:** thirty concurrent transfers of `100.00` reduce the balance by `2600.00` instead of
  `3000.00`, while thirty `DEBIT` ledger rows exist. The size of the discrepancy varies run to run.
* **Why the symptom is misleading:**
  * It is invisible in every single-threaded test. `mvn test` cannot catch it.
  * It is non-deterministic, so a developer who runs it twice and sees the right answer once
    concludes it is fine.
  * There is no exception, no warning and no failed request — every one of the thirty transfers
    reported `200 COMPLETED`.
  * The obvious suspect is the transaction boundary, which by this point the learner has been
    staring at for hours. It is not the boundary; it is the arithmetic.
* **Correct fix — three legitimate answers:**

  1. **Optimistic locking.** Add `@Version private Long version;` to `Account`. Concurrent writers
     then get `ObjectOptimisticLockingFailureException` and the caller retries. Cheap under low
     contention, needs retry logic.
  2. **Pessimistic locking.** `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a repository method that
     loads the account, so the row is locked for the duration. Simple and correct; serialises access
     to hot accounts.
  3. **Atomic database-side update.**
     `@Modifying @Query("UPDATE Account a SET a.balance = a.balance - :amount WHERE a.accountNumber = :n AND a.balance >= :amount")`
     and check the affected-row count. Fastest and no retries, but moves the balance check into SQL.

  Accept any of the three. What matters is that the learner can explain the trade-off and that the
  concurrency check passes afterwards.
* **Concept:** lost updates; optimistic versus pessimistic locking; why read-modify-write in
  application code is unsafe without one of them; isolation levels not being sufficient on their own
  (MySQL's default `REPEATABLE READ` does not prevent this).
* **Why a fresher makes it:** the code is the obvious way to express "subtract from the balance", and
  it is correct in every test they will write. Concurrency bugs are invisible until production load.
* **How to recognise it in the wild:** a derived total that disagrees with its own audit trail.
  Reconciliation queries — "does the balance equal the sum of the ledger?" — exist precisely to
  catch this class of bug, and running one is the first thing to do when money does not add up.

---

## Suggested fix order

1. **Defect 4** (`readOnly` freeze) — independent, quick, and a confidence-builder.
2. **Defect 1** (`REQUIRES_NEW` on debit) — the biggest source of lost money.
3. **Defect 2** (the rollback-only 500) — now that transactions behave, this one is legible.
4. **Defect 3** (checked exception + late idempotency check).
5. **Defect 5** (batch self-invocation).
6. **Defect 6** (audit propagation) — do it *after* Defect 1 so the contrast between the two uses of
   `REQUIRES_NEW` is deliberate rather than accidental.
7. **Defect 7** (locking) — last; it is the only one that requires a concurrent reproduction.

A learner who fixes 6 by copying the `REQUIRES_NEW` they just deleted in 1 has understood the
project. A learner who deletes every `REQUIRES_NEW` in the codebase because "it caused a bug" has
not — ask them why the audit row is different.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `aTransferMovesMoneyFromOneAccountToTheOther` | pass | — |
| `aTransferToAMissingAccountLeavesBothBalancesUntouched` | fail | Defect 1 |
| `aTransferToAFrozenAccountIsReportedAsFailed` | error | Defect 2 |
| `replayingAReferenceDoesNotMoveTheMoneyASecondTime` | fail | Defects 1 + 3 |
| `everyTransferAttemptLeavesAnAuditRow` | fail | Defect 6 |
| `freezingAnAccountIsPersisted` | fail | Defect 4 |

Baseline: `Tests run: 6, Failures: 4, Errors: 1`.

**Defects 5 and 7 are not covered by any test.** Defect 5 needs the batch endpoint exercised by hand;
Defect 7 needs concurrent requests and cannot be caught by a single-threaded test at all. If the
learner reports the project fixed with six green tests but has not run the concurrency check, send
them back to the checklist.

## Verification commands

```bash
docker compose down -v && docker compose up -d
mvn clean package && java -jar target/banking-transfers-1.0.0.jar
mvn test        # 6/6 green

M() { docker exec debuglab08-mysql mysql -uroot -prootpw bankdb -N -e "$1" 2>/dev/null; }
M "SELECT SUM(balance) FROM accounts;"        # 176500.00 -- re-run after everything below

# 404, nothing moves
curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"reference":"V-1","fromAccount":"ACC-1001","toAccount":"ACC-9999","amount":1000.00}'

# 200 FAILED, nothing moves
curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"reference":"V-2","fromAccount":"ACC-1001","toAccount":"ACC-1005","amount":2000.00}'

# 200 then 409, money moves exactly once
curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"reference":"V-3","fromAccount":"ACC-1001","toAccount":"ACC-1002","amount":3000.00}'
curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"reference":"V-3","fromAccount":"ACC-1001","toAccount":"ACC-1002","amount":3000.00}'

# freeze persists
curl -s -X POST localhost:8080/api/accounts/ACC-1006/freeze
curl -s localhost:8080/api/accounts/ACC-1006

# batch: source reduction must equal 500 + 300 only
curl -s -X POST localhost:8080/api/transfers/batch -H 'Content-Type: application/json' \
  -d '{"transfers":[{"reference":"VB-1","fromAccount":"ACC-1001","toAccount":"ACC-1002","amount":500.00},
                    {"reference":"VB-2","fromAccount":"ACC-1001","toAccount":"ACC-9999","amount":700.00},
                    {"reference":"VB-3","fromAccount":"ACC-1001","toAccount":"ACC-1002","amount":300.00}]}'

# every failure above must appear here
curl -s localhost:8080/api/transfers/audit

# concurrency: drop must be exactly 3000.00
before=$(curl -s localhost:8080/api/accounts/ACC-1001 | python -c "import sys,json;print(json.load(sys.stdin)['balance'])")
for i in $(seq 1 30); do curl -s -o /dev/null -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d "{\"reference\":\"VC-$i\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-1002\",\"amount\":100.00}" & done; wait
after=$(curl -s localhost:8080/api/accounts/ACC-1001 | python -c "import sys,json;print(json.load(sys.stdin)['balance'])")
python -c "print('drop:', $before - $after)"     # must be exactly 3000.0

M "SELECT SUM(balance) FROM accounts;"           # must still be 176500.00
```

Final reconciliation — for any account, the balance must equal its opening balance plus the sum of
its ledger:

```sql
SELECT account_number,
       SUM(CASE WHEN entry_type='CREDIT' THEN amount ELSE -amount END) AS net_from_ledger
FROM ledger_entries GROUP BY account_number;
```
