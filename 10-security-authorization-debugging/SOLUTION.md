# SOLUTION — 10 · Task Management API

> **Sealed answer key.** Seven planted defects.

---

## Defect 1 — Granted authorities are built without the `ROLE_` prefix

* **Bug:**

  ```java
  Set<GrantedAuthority> authorities = user.getRoles().stream()
          .map(role -> new SimpleGrantedAuthority(role.getName()))
          .collect(Collectors.toSet());
  ```

  The `roles` table holds `USER`, `MANAGER`, `ADMIN` — no prefix — so every user's authorities are
  `[USER]`, `[MANAGER]` or `[ADMIN]`.
* **Affected component:** `service/AppUserDetailsService.loadUserByUsername`
* **Root cause:** `hasRole("X")` and `hasAnyRole(...)` are shorthand for `hasAuthority("ROLE_X")` —
  Spring Security prepends the configured role prefix (`ROLE_` by default) before comparing. Every
  rule in the configuration that uses `hasRole` / `hasAnyRole` therefore demands `ROLE_ADMIN`,
  `ROLE_USER` and so on, and no user carries any authority beginning with `ROLE_`. Every such rule
  fails for everyone.
* **Symptom:** every endpoint returns `403` for every user — **except** `GET /api/admin/audit` for
  `dave`, which returns `200`, because that one rule is written with `hasAuthority("ADMIN")` and
  matches literally.
* **Why the symptom is misleading:**
  * Not a single `401` appears anywhere, so authentication is demonstrably fine. Everyone knows the
    passwords are right. The instinct is still to re-check credentials.
  * `403` with an empty body says nothing about *which* authority was required.
  * The one endpoint that works looks like the anomaly, when it is in fact the only rule consistent
    with the data. Learners tend to investigate why that one is "wrong" rather than why the others
    are.
* **Correct fix — either, but be consistent:**

  1. Add the prefix when building the authority (recommended, keeps the database clean):

     ```java
     .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
     ```

  2. Store prefixed names in the `roles` table (`ROLE_USER`, …) and leave the mapping literal.

  Whichever they pick, **Defect 2 is the consequence** and must be fixed in the same pass.
* **Concept:** roles versus authorities; the `ROLE_` prefix convention; `hasRole` vs `hasAuthority`.
* **Why a fresher makes it:** the convention is invisible. `hasRole("ADMIN")` reads as "the user has
  the role ADMIN", and their `roles` table says `ADMIN`, so it looks like it must match. Nothing
  logs "you asked for ROLE_ADMIN and the user has ADMIN".
* **How to recognise it in the wild:** `403` for a user you are certain has the right role. Log the
  actual authorities (`authentication.getAuthorities()`) and compare them character by character with
  what the rule demands. This is the single most common Spring Security bug there is.

**Gates Defects 3, 4, 5, 6 and 7** — while everything is `403`, none of them is observable.

---

## Defect 2 — `hasAuthority("ADMIN")` used for one rule while the rest use `hasRole(...)`

* **Bug:**

  ```java
  .requestMatchers("/api/admin/users").hasRole("ADMIN")          // wants ROLE_ADMIN
  .requestMatchers("/api/admin/audit").hasAuthority("ADMIN")     // wants ADMIN
  ```

* **Affected component:** `config/SecurityConfig.securityFilterChain`
* **Root cause:** two rules with identical intent expressed in two incompatible styles. Exactly one
  of them can be correct for a given authority naming scheme.
* **Symptom:** in the shipped state, `/api/admin/audit` is the only endpoint that works. **After
  Defect 1 is fixed, the two swap over**: `/api/admin/users` starts working and `/api/admin/audit`
  starts returning `403`.
* **Why the symptom is misleading:** it looks like the learner's own fix broke a working endpoint —
  a regression they caused. It is the opposite: their fix was right, and it exposed a second rule
  that had been wrong all along and was only ever "working" because the data was also wrong. Two
  wrongs were making a right.
* **Correct fix:** make both consistent with the scheme chosen for Defect 1:

  ```java
  .requestMatchers("/api/admin/users").hasRole("ADMIN")
  .requestMatchers("/api/admin/audit").hasRole("ADMIN")
  ```

  (or both `hasAuthority("ROLE_ADMIN")` — same thing, said the long way).
* **Concept:** consistency of authorization vocabulary across a configuration.
* **Why a fresher makes it:** two people wrote the two rules, or one person copied from two different
  tutorials. Both compile, both look reasonable, and until the authorities change, one of them
  silently never fires.
* **How to recognise it in the wild:** grep the security configuration for every privilege check and
  confirm they use one vocabulary. Mixed `hasRole`/`hasAuthority` in one file is a smell even when it
  happens to work.

---

## Defect 3 — A broad task rule shadows the manager-only assign rule

* **Bug:**

  ```java
  .requestMatchers("/api/tasks/**").hasAnyRole("USER", "MANAGER", "ADMIN")
  .requestMatchers(HttpMethod.PUT, "/api/tasks/*/assign").hasRole("MANAGER")
  ```

* **Affected component:** `config/SecurityConfig.securityFilterChain`
* **Root cause:** matchers are evaluated top-down, first match wins. `PUT /api/tasks/2/assign`
  matches `/api/tasks/**` first, so the `MANAGER` rule on the next line is unreachable dead
  configuration.
* **Symptom:** `alice` (a plain `USER`) gets `200` from `PUT /api/tasks/2/assign` and successfully
  reassigns work.
* **Why the symptom is misleading:** the correct rule is present and correct, sitting one line below.
  Reading the file confirms "yes, assign requires MANAGER". Nothing marks a rule as unreachable.
  This is the same class of mistake as project 09's Defect 1, now inside a single resource rather
  than between resources — worth pointing out if the learner does not notice the echo.
* **Correct fix:** put the specific rule first:

  ```java
  .requestMatchers(HttpMethod.PUT, "/api/tasks/*/assign").hasAnyRole("MANAGER", "ADMIN")
  .requestMatchers(HttpMethod.DELETE, "/api/tasks/*").hasRole("ADMIN")
  .requestMatchers("/api/tasks/**").hasAnyRole("USER", "MANAGER", "ADMIN")
  ```

  Note the README says assign is for `MANAGER` **or** `ADMIN`, so `hasRole("MANAGER")` alone is
  incomplete even once it is reachable — a second, smaller defect inside the same line. Credit the
  learner who catches it; `dave` should be able to assign.
* **Concept:** matcher precedence; unreachable security rules; specific-before-general ordering.
* **Why a fresher makes it:** they write the broad "anyone logged in with a role can use tasks" rule
  first because it covers the common case, then append the exception. In a first-match-wins list the
  exception has to come first.
* **How to recognise it in the wild:** hand-evaluate the failing (or wrongly-succeeding) path against
  each matcher in order. If a rule can never be the first match, it is dead code with a security
  label on it.

---

## Defect 4 — No `ADMIN`-only rule for `DELETE`, so the broad rule covers it

* **Bug:** there is no matcher for `DELETE /api/tasks/{id}` at all. It falls into
  `.requestMatchers("/api/tasks/**").hasAnyRole("USER", "MANAGER", "ADMIN")`.
* **Affected component:** `config/SecurityConfig.securityFilterChain`
* **Root cause:** a path-only matcher applies to **every HTTP method** on that path. `GET`, `POST`,
  `PUT` and `DELETE` on `/api/tasks/**` are all governed by the same rule, so a destructive operation
  inherits the privilege level of a read.
* **Symptom:** `alice` (a plain `USER`) issues `DELETE /api/tasks/5`, receives `204 No Content`, and
  the row is permanently gone.
* **Why the symptom is misleading:** the configuration "looks complete" — there is a rule covering
  `/api/tasks/**` and nothing is obviously missing. Absence of a rule is much harder to notice than a
  wrong rule. And the operation reports success, so nothing draws attention to it.
* **Correct fix:** add an explicit method-and-path rule, above the broad one:

  ```java
  .requestMatchers(HttpMethod.DELETE, "/api/tasks/*").hasRole("ADMIN")
  ```

* **Concept:** HTTP-method-aware authorization; the danger of path-only rules on resources with mixed
  privilege levels.
* **Why a fresher makes it:** they think in resources ("tasks are for logged-in users") rather than
  in operations ("deleting a task is an admin action").
* **How to recognise it in the wild:** audit by *operation*, not by path. For every endpoint in your
  API, name the required privilege and prove it with a negative test. Anything destructive deserves
  its own rule.

---

## Defect 5 — Method security is never enabled

* **Bug:** `SecurityConfig` is annotated `@EnableWebSecurity` but not `@EnableMethodSecurity`.
  `TaskService.delete` carries `@Secured("ROLE_ADMIN")`, which is silently ignored.
* **Affected component:** `config/SecurityConfig` (the missing annotation), with the consequence
  visible in `service/TaskService.delete`
* **Root cause:** without method security enabled, no advisor is registered to intercept the
  annotated methods. The annotations are inert metadata — no error, no warning, no log line.
* **Symptom:** the second, independent reason `alice` can delete a task. Fixing Defect 4 alone closes
  the URL layer; the method layer remains inert, so the README's promise of defence in depth is
  false.
* **Why the symptom is misleading:** annotations that do nothing are the quietest failure mode in
  Spring. The code reads as protected. Anyone reviewing `TaskService` would sign it off. Exactly the
  same class of silent-no-op as project 02's manually-constructed bean and project 08's
  self-invocation — worth drawing the parallel.
* **Correct fix:** see Defect 6 — enabling it is necessary but **not sufficient**.
* **Concept:** method security activation; annotations as inert metadata without an enabling
  configuration.
* **Why a fresher makes it:** `@EnableWebSecurity` is on the class and looks like it enables
  "security". Nothing indicates that method-level annotations need their own switch.
* **How to recognise it in the wild:** prove an annotation works before you rely on it. Write one
  negative test that must return `403`. If it returns `200`, the annotation is decorative.

---

## Defect 6 — `@Secured` is used, but `securedEnabled` defaults to `false`

* **Bug:** `TaskService.delete` is annotated `@Secured("ROLE_ADMIN")`.
* **Affected component:** `service/TaskService.delete` together with the method-security
  configuration.
* **Root cause:** `@EnableMethodSecurity` has three switches, and their defaults are
  **`prePostEnabled = true`, `securedEnabled = false`, `jsr250Enabled = false`**. (This is a change
  from the older `@EnableGlobalMethodSecurity`, where all three defaulted to `false`.) So the
  obvious fix for Defect 5 — adding a bare `@EnableMethodSecurity` — activates `@PreAuthorize` and
  `@PostAuthorize` but **not** `@Secured`.
* **Symptom:** **verified**: with the `ROLE_` prefix fixed *and* `@EnableMethodSecurity` added,
  `alice` still deletes task 5 successfully — `204`, and `SELECT COUNT(*) FROM tasks WHERE id=5`
  returns `0`.
* **Why this is the sharpest defect in the project:** the learner does everything right. They
  correctly diagnose that method security is off, they correctly add the annotation that turns it on,
  they rebuild, they re-test — and the behaviour is *identical*. The natural conclusion is "my fix
  didn't apply" or "I misdiagnosed", and they go back to the URL layer. The actual answer is a
  default value in an annotation attribute they never looked at.
* **Correct fix — either:**

  1. Enable the family the annotation belongs to:

     ```java
     @EnableMethodSecurity(securedEnabled = true)
     ```

  2. **Preferred** — move to the family that is already on, which is also the one that is not
     effectively deprecated and which supports SpEL:

     ```java
     @PreAuthorize("hasRole('ADMIN')")
     @Transactional
     public void delete(Long id) { ... }
     ```

  Push for option 2 and make sure they can say why: `@Secured` takes literal authority strings only,
  cannot express anything conditional, and is a legacy annotation the Spring team has moved away
  from. `@PreAuthorize` is the modern default and is why it is the one enabled out of the box.
* **Concept:** the three method-security annotation families; annotation attribute defaults;
  `@Secured` versus `@PreAuthorize` versus JSR-250 `@RolesAllowed`.
* **Why a fresher makes it:** they find `@Secured` in an older tutorial or an existing part of the
  codebase, and they add `@EnableMethodSecurity` without reading its attributes. Both steps are
  reasonable in isolation.
* **How to recognise it in the wild:** the same rule as Defect 5 — never trust an annotation you have
  not seen refuse something. If a method-security annotation appears to do nothing, check *which
  family* it belongs to and whether that family is enabled.

---

## Defect 7 — The task list is not scoped to the caller

* **Bug:**

  ```java
  public List<TaskDto> listTasksFor(String username) {
      for (Task task : taskRepository.findAll()) { ... }     // username is only logged
  }
  ```

  `TaskRepository.findByAssignee` exists and is never called.
* **Affected component:** `service/TaskService.listTasksFor`
* **Root cause:** the caller's identity is passed into the method and used only in a debug log. Every
  caller receives every row.
* **Symptom:** `alice`, the assignee of two tasks, receives all five — including `bob`'s.
* **Why the symptom is misleading:**
  * No authorization rule was violated. `alice` is entitled to call `GET /api/tasks`; the filter
    chain correctly permits it and correctly returns `200`.
  * **No URL rule or method annotation can ever fix this.** Both layers answer "may you invoke this
    operation?" — neither answers "which rows may you see?". A learner who has spent two hours
    thinking in terms of `403`s has to change register entirely.
  * It is a data leak that looks like a feature. Nothing is red.
* **Correct fix:** scope the query by the caller's identity and role:

  ```java
  @Transactional(readOnly = true)
  public List<TaskDto> listTasksFor(String username) {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      boolean privileged = auth.getAuthorities().stream()
              .anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER")
                          || a.getAuthority().equals("ROLE_ADMIN"));

      List<Task> tasks = privileged
              ? taskRepository.findAll()
              : taskRepository.findByAssignee(username);
      ...
  }
  ```

  Passing the roles down from the controller instead of reaching for `SecurityContextHolder` is
  cleaner and equally acceptable — it keeps the service testable without a security context. So is
  `@PostFilter`, though it filters after loading every row and does not scale.
* **Concept:** data-level (row-level) authorization, as distinct from authentication and
  operation-level authorization.
* **Why a fresher makes it:** the endpoint is protected, so it feels protected. The mental model
  stops at "is this user allowed to call this?" — and the framework reinforces that, because that is
  the only question it answers for you.
* **How to recognise it in the wild:** for every endpoint that returns a collection, ask "whose rows
  are these?" and test it as a low-privilege user with data belonging to somebody else. This class of
  bug (IDOR / broken object-level authorization) sits at the top of the OWASP API Security list
  precisely because the framework does not catch it.

---

## Suggested fix order

1. **Defect 1** — the gate. Decide the naming scheme first.
2. **Defect 2** — immediately, in the same pass; it is the fallout of 1.
3. Re-run the full matrix. Symptoms C, D and E now appear.
4. **Defect 3** and **Defect 4** — the URL layer.
5. **Defects 5 and 6** — the method layer, in that order. Expect the delete to *still* work after
   fixing 5; that is the point.
6. **Defect 7** — the data layer, which nothing in the framework was ever going to catch.

## Test expectations

| Test | Before | Notes |
|---|---|---|
| `anAnonymousCallerIsRejected` | pass | — |
| `aPlainUserCanListTasks` | fail (`403`) | Defect 1 |
| `aPlainUserOnlySeesTheirOwnTasks` | fail (`403`) | Defect 1, then Defect 7 |
| `aPlainUserCannotAssignATask` | **pass** | passes for the *wrong reason* — see below |
| `aManagerCanAssignATask` | fail (`403`) | Defect 1, then Defect 3 |
| `aPlainUserCannotDeleteATask` | **pass** | wrong reason |
| `anAdminCanReachBothAdminEndpoints` | fail (`403`) | Defect 1, then Defect 2 |
| `aPlainUserCannotReachAdminEndpoints` | **pass** | wrong reason |

Baseline: `Tests run: 8, Failures: 4, Errors: 0`.

**The three tests that pass before any fix are the most important thing in this table.** They pass
because *everything* is forbidden — including the things that should be. The moment the learner fixes
Defect 1, `aPlainUserCannotAssignATask` and `aPlainUserCannotDeleteATask` will start **failing**,
and that looks like their fix broke something. It did not: those tests were never really passing,
they were just being carried by an outage.

This is a deliberate demonstration of a real hazard — a negative security test that passes because
the feature is broken tells you nothing. If the learner reverts their fix to make the suite go green
again, that is the teaching moment.

## Verification commands

```bash
docker compose down -v && docker compose up -d
mvn clean package && java -jar target/task-manager-1.0.0.jar
mvn test        # 8/8 green

B=http://localhost:8080/api; P='Secret123!'
t() { printf "  %-24s -> %s\n" "$1" "$(curl -s -o /dev/null -w '%{http_code}' "${@:2}")"; }
for u in alice carol dave; do
  echo "== $u =="
  t "GET  /tasks"          -u $u:$P $B/tasks
  t "PUT  /tasks/2/assign" -u $u:$P -X PUT $B/tasks/2/assign -H 'Content-Type: application/json' -d '{"assignee":"bob"}'
  t "DELETE /tasks/4"      -u $u:$P -X DELETE $B/tasks/4
  t "GET  /admin/users"    -u $u:$P $B/admin/users
  t "GET  /admin/audit"    -u $u:$P $B/admin/audit
done
```

Expected: `alice` → 200, 403, 403, 403, 403 · `carol` → 200, 200, 403, 403, 403 ·
`dave` → 200, 200, 204, 200, 200.

```bash
# row scoping
curl -s -u alice:$P $B/tasks | python -c "import sys,json;print(len(json.load(sys.stdin)))"   # 2
curl -s -u carol:$P $B/tasks | python -c "import sys,json;print(len(json.load(sys.stdin)))"   # 5
```

```sql
-- nothing destroyed by a non-admin
SELECT COUNT(*) FROM tasks;
```

Finally, the defence-in-depth check from the guide's last box: comment out the method annotation and
confirm the URL rule still refuses `alice`; restore it, loosen the URL rule, and confirm the
annotation still refuses her.
