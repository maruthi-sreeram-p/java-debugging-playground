# Debugging Guide — 10 · Task Management API

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Everyone can log in. Almost nobody can do anything.

This is the canonical Spring Security confusion: **authentication succeeded, authorization failed.**
The password was right, the account was found, the credentials were accepted — and the answer is
still `403`. When that happens, the problem is never the password. It is the gap between *the
authorities your user actually carries* and *the authorities your rules actually demand*.

Once you close that gap, the project turns around completely, and the second half of the exercise is
the opposite problem: endpoints that are open to people who should not reach them.

**Both directions matter.** An endpoint that is wrongly closed is an outage. An endpoint that is
wrongly open is a breach. This project has both, and the ones that are wrongly open do not announce
themselves at all.

## Expected behaviour

The authorization matrix from the README, in full:

| | `GET /tasks` | `POST /tasks` | `PUT /tasks/{id}/assign` | `DELETE /tasks/{id}` | `GET /admin/users` | `GET /admin/audit` |
|---|---|---|---|---|---|---|
| anonymous | 401 | 401 | 401 | 401 | 401 | 401 |
| `alice` (USER) | 200 (own only) | 201 | **403** | **403** | **403** | **403** |
| `carol` (MANAGER) | 200 (all) | 201 | 200 | **403** | **403** | **403** |
| `dave` (ADMIN) | 200 (all) | 201 | 200 | 204 | 200 | 200 |

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/task-manager-1.0.0.jar
```

Build yourself a matrix script early — you will run it many times:

```bash
B=http://localhost:8080/api; P='Secret123!'
t() { printf "  %-34s -> %s\n" "$1" "$(curl -s -o /dev/null -w '%{http_code}' "${@:2}")"; }
for u in alice carol dave; do
  echo "== $u =="
  t "GET  /tasks"        -u $u:$P $B/tasks
  t "PUT  /tasks/2/assign" -u $u:$P -X PUT $B/tasks/2/assign -H 'Content-Type: application/json' -d '{"assignee":"bob"}'
  t "GET  /admin/users"  -u $u:$P $B/admin/users
  t "GET  /admin/audit"  -u $u:$P $B/admin/audit
done
```

Filling in that whole matrix, every time you change something, is the discipline this project
teaches. A single `curl` proves nothing about a security configuration.

`mvn test` runs eight tests: four pass, four fail. **Read the note about the passing ones below** —
it matters.

---

## Known symptoms

### Symptom A — everyone is forbidden from everything, with one exception

```
== alice (USER) ==                == carol (MANAGER) ==        == dave (ADMIN) ==
  GET  /tasks          -> 403       GET  /tasks     -> 403       GET  /tasks        -> 403
  PUT  /tasks/2/assign -> 403       PUT  .../assign -> 403       GET  /admin/users  -> 403
  GET  /admin/users    -> 403                                    GET  /admin/audit  -> 200   <--
  GET  /admin/audit    -> 403
```

Not one `401` anywhere — every login is accepted. And in that wall of `403`s there is exactly one
`200`: `dave` can read `/api/admin/audit`, an `ADMIN`-only endpoint, while being refused
`/api/admin/users`, an equally `ADMIN`-only endpoint.

**That single working endpoint is the most valuable thing on this page.** Two rules protect two
endpoints with the same requirement, and one works. Find out what is different about it and you have
the answer to Symptom A.

### Symptom B — the admin endpoints swap places

Fix Symptom A and re-run the matrix. `dave` can now reach `/api/admin/users`... and `/api/admin/audit`
starts returning `403`. The endpoint that was the one working thing is now the one broken thing.

This is not a mistake in your fix. Two rules were written in two different styles, only one of which
was ever consistent with the data. Your fix changed which one.

### Symptom C — a plain user can assign work

After Symptom A is fixed:

```
alice (USER)   PUT /api/tasks/2/assign   -> 200
```

The README says `MANAGER` or `ADMIN`. There *is* a rule in the configuration demanding `MANAGER` for
exactly this path, and `alice` is not a manager. She gets `200` anyway.

### Symptom D — a plain user can delete a task, permanently

After Symptom A is fixed:

```
alice (USER)   DELETE /api/tasks/5   -> 204
```

```sql
SELECT COUNT(*) FROM tasks WHERE id = 5;
0
```

The row is gone. There is a method-level annotation on the service that is supposed to make this
impossible. It did nothing.

There are **two** independent reasons this request succeeded — one at each of the two layers the
README describes. Fixing one of them still leaves the other, and after fixing the first you will
find that the annotation *still* does not work. That second stage is deliberate and is the sharpest
lesson in this project.

### Symptom E — everyone sees everyone's tasks

```bash
curl -u alice:'Secret123!' http://localhost:8080/api/tasks
```

`alice` is the assignee of two tasks. She gets five, including `bob`'s.

No rule was violated — she is allowed to call this endpoint. The endpoint simply hands her data that
is not hers. Notice that no `403` will ever protect you from this: URL rules and method annotations
answer "may you call this?", never "which rows may you see?".

---

## Investigation hints

### Symptom A — everything forbidden

> **Hint A1**
> Start from the one thing that works. Find the two rules protecting the two admin endpoints and put
> them side by side. They are written with two different methods. Look up what each of those two
> methods does with the string you pass it.

> **Hint A2**
> One of them adds something to the string before comparing. The other compares it literally. Now
> look at the `DEBUG` log line that prints the authorities loaded for `dave`, and compare those
> authorities against what each rule is demanding.

> **Hint A3**
> There is a naming convention in Spring Security that separates the concept of a *role* from the
> concept of an *authority*. Find out what the convention is, and then decide where it should be
> applied in this application: when the authority is built, or when the rule is written.

> **Hint A4**
> Whichever place you choose, apply it **consistently**. Half-applying it is what produced Symptom B.
> Before you change anything, decide what the rule is going to be for the whole codebase and write it
> down.

### Symptom B — the admin endpoints swapping

> **Hint B1**
> You changed what authorities a user carries. Any rule that was written against the *old* shape is
> now wrong. Grep the configuration for every place a privilege is named.

> **Hint B2**
> Two of those places use different helper methods. Consistency is the fix, not cleverness. Pick one
> style for the whole file and make every rule match it.

### Symptom C — the user who can assign

> **Hint C1**
> The rule you want exists and demands the right role. So the question is the same one you met in the
> previous project: is that rule ever *reached*?

> **Hint C2**
> Write out every rule in the authorization block, in order, and hand-evaluate
> `PUT /api/tasks/2/assign` against each. Stop at the first one that matches.

> **Hint C3**
> A pattern ending in `/**` matches everything below it — including paths you wrote a more specific
> rule for further down. Order matters, and specific must come before general.

### Symptom D — the user who can delete

> **Hint D1** *(layer one)*
> Do the same walk as Symptom C for `DELETE /api/tasks/5`. Which rule matches it first, and what does
> that rule require? Compare that with what the README requires.

> **Hint D2** *(layer one)*
> Note the subtlety: a rule based only on a path applies to every HTTP method on that path. If one
> method on a path needs a higher privilege than the others, the rule has to say so.

> **Hint D3** *(layer two — the important half)*
> Now the annotation. It is on the service method, it names the right role, and the call from the
> controller goes through the proxy, so self-invocation is not the problem this time. What has to be
> switched on before Spring will look at method-level security annotations at all?

> **Hint D4**
> Switch it on in the obvious way, rebuild, and try the delete again as `alice`. **It still works.**
> That is the real puzzle. The annotation is now being looked for — but this particular annotation
> still is not being honoured.

> **Hint D5**
> Spring Security supports three different families of method-security annotation, and the switch you
> just added takes parameters controlling which families are active. Read its attributes and their
> **default values**. Only one family is on by default. Check which family the annotation on that
> method belongs to.

> **Hint D6**
> Two legitimate fixes: turn on the family that annotation belongs to, or change the annotation to
> the family that is already on. One of these leaves the codebase using a mechanism most teams have
> moved away from. Form a view on which, and be able to say why.

### Symptom E — data that is not yours

> **Hint E1**
> This is not a filter-chain problem and no annotation will fix it. Read the service method that
> builds the list and ask what it does with the identity of the caller.

> **Hint E2**
> The caller's username is available to that method. What is it currently used for?

> **Hint E3**
> The rule from the README is role-dependent: one role sees a subset, the others see everything. So
> the method needs to know not just *who* is calling but *what they are*. Work out how to get the
> caller's authorities inside a service method — there is a static holder for exactly this.

> **Hint E4**
> This distinction has a name worth knowing: **authentication** (who), **authorization** (may you
> perform this operation), and **data-level** or **row-level** authorization (which records may you
> see). The first two are handled by the framework. The third is always your own code, and it is the
> one most often forgotten.

---

## Expected logs and observations

* The application starts cleanly. Everything is a request-time decision.
* `logging.level.org.springframework.security=DEBUG` prints, for each request, the authorities the
  authenticated user carries and the authorization decision. The line reading
  `Loaded <user> with authorities [...]` in the application's own log is the fastest way to see the
  gap in Symptom A.
* `401` versus `403` is a real distinction here and worth internalising: `401` means "I do not know
  who you are", `403` means "I know exactly who you are and the answer is no". In this project you
  should see `401` **only** for anonymous requests. If you ever see `401` while sending valid
  credentials, something else is wrong.
* Symptoms C, D and E produce no error at all — they are `200`, `204` and `200`. Nothing in the log
  is coloured red. The only way to find them is to test the negative cases deliberately.
* For Symptom D, confirm with SQL. A `204` is a claim; a missing row is evidence.

## Difficulty

**Advanced.** Expect two to three hours.

Symptom A is a gate — nothing else is observable until it is fixed, because everything is refused.
Symptom B is caused by your fix to A. Symptoms C, D and E only become visible once A is resolved, and
none of them fails loudly. Symptom D has two independent causes stacked on one endpoint, and the
second one survives the obvious fix.

The hardest part of this project is psychological: after you fix Symptom A the application will look
like it is working, and you will want to stop. That is precisely the moment the security holes open.

## Concepts being tested

* Authorities versus roles, and the `ROLE_` prefix convention
* `hasRole(...)` versus `hasAuthority(...)` and what each does to the string you give it
* Building `GrantedAuthority` values in a `UserDetailsService`
* Matcher precedence in `authorizeHttpRequests` — specific before general
* Path-only rules versus method-and-path rules
* Enabling method security, and which annotation families are active by default
* `@Secured` / `@PreAuthorize` / JSR-250 and how they differ
* Defence in depth: URL rules and method rules as independent layers
* Data-level (row-level) authorization, and why no annotation provides it
* Reading `403` as evidence about authorities rather than credentials

## When you think you are done

- [ ] `mvn test` is green (8 tests).
- [ ] You have filled in the **entire** authorization matrix from the README — 6 endpoints × 4
      callers, including anonymous — and every cell matches.
- [ ] `alice` sees exactly 2 tasks; `carol` and `dave` see all of them.
- [ ] `alice` gets `403` on assign, on delete, and on both admin endpoints.
- [ ] `carol` gets `200` on assign and `403` on both admin endpoints.
- [ ] `dave` gets `200` on **both** admin endpoints — not one of them.
- [ ] After a run of the matrix, `SELECT COUNT(*) FROM tasks` is unchanged except where an `ADMIN`
      deliberately deleted something.
- [ ] Temporarily comment out the method-level annotation on the delete operation: the URL rules must
      still refuse `alice`. Then put it back and temporarily loosen the URL rule: the annotation must
      still refuse her. **Each layer has to hold on its own.**
- [ ] You can state the difference between `hasRole("ADMIN")` and `hasAuthority("ADMIN")` without
      looking it up.

Then say **"I think I fixed the project"**.
