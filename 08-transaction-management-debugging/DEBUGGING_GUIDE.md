# Debugging Guide — 08 · Banking Transaction API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

This service loses money.

Not metaphorically — run the reproduction steps below and the sum of all balances will be lower than
it was when you started. Money also appears out of nowhere under load. The ledger disagrees with the
balances. Failures leave no trace.

Every one of those outcomes comes from the same family of mistakes: **a transaction boundary is not
where the developer thought it was.** `@Transactional` is an instruction to a proxy, and it is
subject to rules about propagation, exception types, and how the method was called. When those rules
are not understood, code that looks obviously atomic is not.

The single habit to build here:

> Do not reason about what *should* have rolled back. Read the transaction log and look at the rows.

## Expected behaviour

1. **Money is conserved.** Sum all six balances before and after any sequence of operations. The
   total never changes.
2. A transfer that cannot complete moves nothing — neither account changes.
3. Replaying a reference is rejected and moves nothing.
4. A transfer to a frozen account returns `200` with `"status":"FAILED"` and moves nothing.
5. Every attempt, successful or not, appears in `GET /api/transfers/audit`.
6. In a batch, a failing item leaves its own accounts untouched and does not disturb the others.
7. Thirty concurrent transfers of `100.00` reduce the source balance by exactly `3000.00`.
8. Every balance equals the sum of that account's ledger entries.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/banking-transfers-1.0.0.jar
```

Keep a MySQL shell open throughout:

```bash
docker exec -it debuglab08-mysql mysql -uroot -prootpw bankdb
```

This query is your conscience — run it before and after everything:

```sql
SELECT SUM(balance) AS total_money_in_the_bank FROM accounts;
```

It should read `176500.00` on a fresh start and never change.

`mvn test` runs six tests: one passes, five fail.

---

## Known symptoms

### Symptom A — a rejected transfer still takes the money

```
ACC-1001 balance: 50000.00

POST /api/transfers  {"reference":"TX-100","fromAccount":"ACC-1001","toAccount":"ACC-9999","amount":1000.00}
404 Not Found   {"message":"No account exists with number ACC-9999"}

ACC-1001 balance: 49000.00
```

The API correctly refused the transfer. The destination does not exist and was never credited. One
thousand rupees have left the banking system.

### Symptom B — a handled failure returns 500

The code plainly intends to handle a frozen destination gracefully — it catches the exception, writes
an audit row, and returns a `FAILED` result. What actually happens:

```
POST /api/transfers  {"reference":"TX-101","fromAccount":"ACC-1001","toAccount":"ACC-1005","amount":2000.00}
500 Internal Server Error
```

```
org.springframework.transaction.UnexpectedRollbackException:
  Transaction silently rolled back because it has been marked as rollback-only
```

Read that message carefully. Something marked the transaction for rollback, and it was not the code
you are looking at. And the source account is another 2000 lighter.

### Symptom C — a rejected replay moves the money again

```
POST /api/transfers  {"reference":"TX-200", ... "amount":3000.00}    -> 200 COMPLETED
   ACC-1001 44000.00   ACC-1002 28000.00

POST /api/transfers  {"reference":"TX-200", ... "amount":3000.00}    -> 409 Conflict
   "The ledger already holds entries for reference TX-200"
   ACC-1001 41000.00   ACC-1002 31000.00
```

The duplicate was detected and rejected — after the money had already moved. The idempotency key is
worthless.

### Symptom D — freezing an account does nothing

```
POST /api/accounts/ACC-1003/freeze
200 OK   {"accountNumber":"ACC-1003","holderName":"Rohan Mehta","balance":10000.00,"frozen":true}

GET /api/accounts/ACC-1003
200 OK   {"accountNumber":"ACC-1003","holderName":"Rohan Mehta","balance":10000.00,"frozen":false}
```

The write endpoint reports success and returns the new state. Nothing was written. No exception, no
warning, no `update` in the SQL log.

### Symptom E — a failed batch item eats the money

```
before:  ACC-1001 41000.00   ACC-1002 31000.00

POST /api/transfers/batch   [ 500 -> ACC-1002,  700 -> ACC-9999,  300 -> ACC-1002 ]
   TXB-1 COMPLETED
   TXB-2 FAILED   No account exists with number ACC-9999
   TXB-3 COMPLETED

after:   ACC-1001 39500.00   ACC-1002 31800.00
```

ACC-1001 lost 1500. ACC-1002 gained 800. The failed item's 700 is gone, and
`SELECT COUNT(*) FROM ledger_entries WHERE reference='TXB-2'` returns `0` — no ledger entry was ever
written for the money that disappeared.

Note that the *same* failure through `POST /api/transfers` behaves differently. Same code, two entry
points, two behaviours.

### Symptom F — failed transfers leave no audit trail

```
GET /api/transfers/audit
[]
```

After all of the failures above. The successful transfers are there; not one failure is.

### Symptom G — money appears out of nowhere under load

Thirty concurrent transfers of `100.00` from one account:

```
ACC-1001 before: 39500.00
ACC-1001 after:  36900.00      (expected 36500.00)

SELECT COUNT(*) FROM ledger_entries WHERE reference LIKE 'TXC-%' AND entry_type='DEBIT';
30
```

Thirty debits of 100 are recorded in the ledger. The balance dropped by 2600. The ledger and the
balance disagree by 400 rupees, and the discrepancy varies from run to run.

---

## Investigation hints

Before any of the specific hints: turn your attention to the transaction log. It is already at
`TRACE`. Run one transfer and read every line beginning with
`Getting transaction for` and `Completing transaction for`. Count how many transactions a single
transfer creates. That number will surprise you, and it explains Symptoms A, B and E.

### Symptom A — money lost on a rejected transfer

> **Hint A1**
> The exception happened during the *second* step. The first step had already run. Under the
> guarantee the README makes, the first step's effect should have been undone. Why was it not?

> **Hint A2**
> Read the transaction log for this request. How many transactions were begun? If the whole transfer
> were one unit of work, how many would you expect?

> **Hint A3**
> Look at the annotation on the method that performs the first step. It carries an attribute that
> the other steps do not. Look up what that attribute does to the transaction that is already
> running — specifically, what happens to the new transaction's commit when the outer one rolls
> back.

> **Hint A4**
> The word to search for is "suspend". An independent transaction that has already committed cannot
> be undone by anything that happens afterwards. Ask yourself why anyone would have wanted this step
> to be independent, and whether that reason applies.

### Symptom B — the 500 from a handled exception

> **Hint B1**
> The catch block genuinely runs — you can see its log line. So the failure is not in your code
> path; it happens *after* your method returns successfully. What runs after a transactional method
> returns?

> **Hint B2**
> "Marked as rollback-only" — by whom? The exception was thrown inside a method that has its own
> `@Transactional` annotation and joins the caller's transaction. Look up what Spring does to a
> shared transaction when an exception escapes a *participating* transactional method.

> **Hint B3**
> Once a transaction is marked rollback-only, catching the exception does not un-mark it. The
> commit will fail no matter what you do afterwards. Understanding this is the point of the symptom:
> **catching an exception is not the same as preventing a rollback.**

> **Hint B4**
> There are two families of fix. One is to make the check happen *before* anything is modified, so
> the exceptional case never enters the transaction at all. The other is to keep the graceful
> handling but arrange for the failing step not to poison the shared transaction. Think about which
> one you would want in a banking system, where "check before you act" is a general principle.

### Symptom C — the replay that still moves money

> **Hint C1**
> An exception was thrown and the transaction did not roll back. That is a much stronger statement
> than it looks: Spring's default rollback rule does not cover every exception. Find out which
> exceptions it covers.

> **Hint C2**
> Look at the type hierarchy of the exception involved. Compare it with the exceptions in the other
> failure paths, which *do* cause a rollback. There is a single word of difference in what they
> extend.

> **Hint C3**
> The default is: roll back on unchecked exceptions and `Error`, commit on checked exceptions. That
> is a JTA-era convention that surprises nearly everyone. There is an attribute on `@Transactional`
> that overrides it.

> **Hint C4**
> There is a second, better question here. Even with the rollback fixed, this check runs *after* the
> money has moved. What ought to happen to an idempotency check, relative to the work it guards?

### Symptom D — the freeze that does not persist

> **Hint D1**
> Look at the SQL log for the freeze request. There is a `select`. Is there an `update`?

> **Hint D2**
> Compare the annotation on this method with the annotation on the methods that *do* write
> successfully. There is one attribute set here that is not set there.

> **Hint D3**
> Look up what that attribute does to the Hibernate flush mode, and what a flush mode of `MANUAL`
> means for changes to a managed entity at commit time. Note that nothing fails — the change is
> simply never written.

> **Hint D4**
> An important detail worth understanding, because it will confuse you later: that attribute is
> honoured only when the annotated method *starts* a transaction. On a method that merely joins one
> that is already running, it is ignored entirely. Work out which case this is, and why the other
> read-only methods in the same class are harmless.

### Symptom E — the batch that behaves differently

> **Hint E1**
> The same transfer, through two entry points, behaves differently. Whatever is wrong is not in the
> transfer logic — it is in how the transfer method is *reached*.

> **Hint E2**
> `@Transactional` is implemented by wrapping the bean in a proxy. Callers outside the bean go
> through the proxy. Now look at how the batch method invokes the single-transfer method, and ask
> whether that call goes through the proxy or not.

> **Hint E3**
> Turn the transaction log up and compare a single transfer with a batch item. Count the
> transactions begun in each case. The difference is the whole answer.

> **Hint E4**
> There are several standard remedies for this: move the method to a different bean, inject the bean
> into itself, or take the boundary from the proxy explicitly. Look up "self-invocation" and
> `@Transactional`, and pick the one you would defend in review. Note that whatever you choose has to
> keep the documented behaviour — each item independently atomic — intact.

### Symptom F — the missing audit rows

> **Hint F1**
> The README says the audit trail must outlive a rollback, and the audit service's own comment says
> the same. So this is not an oversight in intent — it is an oversight in mechanism.

> **Hint F2**
> If the audit write joins the transaction that is about to be rolled back, what happens to it?

> **Hint F3**
> You already met the propagation mode that would fix this — in Symptom A, where it was causing the
> damage. Same attribute, opposite conclusion. That is worth sitting with: the tool is not good or
> bad, it is right or wrong *for a particular step*. Be able to say why the audit write deserves it
> and the debit does not.

### Symptom G — money appearing under load

> **Hint G1**
> This is not a transaction-boundary problem. Every one of those thirty transfers committed. Look at
> the arithmetic instead: what does the debit operation actually do, in order, to compute the new
> balance?

> **Hint G2**
> Write out the sequence for two threads running that code at the same moment against the same row.
> Read, read, subtract, subtract, write, write. What is the final value, and what should it have
> been?

> **Hint G3**
> The name for this is a lost update. The database is not at fault — it faithfully stored the value
> it was given. The problem is that the value was computed from a balance that was already stale.

> **Hint G4**
> Three standard remedies, and it is worth knowing all three: an optimistic version column with
> retry, a pessimistic row lock taken at read time, or an atomic update that lets the database do the
> arithmetic (`SET balance = balance - :amount`). Consider what each costs and what each does when
> two callers collide. Any of them fixes this; be able to explain the trade-off.

---

## Expected logs and observations

* The application starts cleanly. Everything here happens at request time.
* `logging.level.org.springframework.transaction.interceptor=TRACE` prints a line whenever a
  transaction is created for a method, and whenever one completes. Learning to read these is most of
  this project.
* `spring.jpa.show-sql=true` shows you which `update` statements were actually issued — and, for
  Symptom D, which were not.
* `UnexpectedRollbackException` is one of the most-misread exceptions in Spring. It does not mean
  your rollback failed. It means the commit failed *because* something had already demanded a
  rollback.
* Symptoms D and G produce no exception at all.
* Symptom G is non-deterministic — the size of the discrepancy varies. Run it several times. A
  defect that appears only sometimes is still a defect, and reproducing it reliably enough to
  measure is part of the skill.

## Difficulty

**Intermediate → Advanced.** Expect three to four hours. This is the hardest project in the lab so
far.

Symptoms A, B, C and E all present as "money moved when it should not have", but they have four
different causes and four different fixes. Resist the urge to find one explanation for all of them —
fix them one at a time and re-run the reproduction after each, because several of them overlap in
the same request.

Symptom D and Symptom G are independent of everything else.

## Concepts being tested

* `@Transactional` as a proxy concern, and self-invocation
* Propagation modes — `REQUIRED` versus `REQUIRES_NEW`, and transaction suspension
* Why a participating transaction marks the whole transaction rollback-only, and what
  `UnexpectedRollbackException` really means
* Default rollback rules: unchecked versus checked exceptions, and `rollbackFor`
* `readOnly = true`, flush modes, and when the attribute is silently ignored
* Atomicity of a multi-step business operation
* Lost updates, optimistic and pessimistic locking, and atomic database-side updates
* Designing an audit trail that survives rollback
* Reading the Spring transaction log

## When you think you are done

- [ ] `mvn test` is green (6 tests).
- [ ] `SELECT SUM(balance) FROM accounts` reads `176500.00` after a fresh start, and **still reads
      `176500.00`** after running every reproduction in this guide.
- [ ] A transfer to a missing account returns `404` and moves nothing.
- [ ] A transfer to a frozen account returns `200` with `"status":"FAILED"`, and moves nothing.
- [ ] Replaying a reference returns `409` and moves nothing.
- [ ] `POST /api/accounts/{n}/freeze` survives a re-read.
- [ ] In a batch with a failing item, the source account's total reduction equals the sum of only the
      items that succeeded.
- [ ] Every failed attempt above appears in `GET /api/transfers/audit`.
- [ ] Thirty concurrent transfers of `100.00` reduce the balance by exactly `3000.00`, and the
      ledger agrees.
- [ ] For each account you touched, the balance equals its opening balance plus the sum of its
      ledger entries.

Then say **"I think I fixed the project"**.
