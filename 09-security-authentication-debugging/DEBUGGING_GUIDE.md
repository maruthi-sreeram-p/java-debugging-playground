# Debugging Guide — 09 · Account Management & Authentication Service

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Right now this service rejects everybody. Every endpoint returns `401`, including the ones that are
documented as public and including a request carrying credentials you can see are correct in the
database.

That is the defining difficulty of debugging Spring Security: **`401` and `403` are the answers to
half a dozen different questions.** "Who are you?", "may you?", "did you send a CSRF token?", "is
this account enabled?" and "does this account exist?" all collapse into the same two status codes by
design — telling an attacker which one failed would leak information.

So the status code will not tell you what is wrong. The **`DEBUG` log will**, and it is already
turned on. Learning to read it is the point of this project.

## Expected behaviour

1. `GET /api/public/health` → `200`, no credentials.
2. `POST /api/auth/register` → `201`, no credentials, and the returned account is `enabled: true`.
3. An account you just registered can sign in immediately with the password you sent.
4. Users sign in with their **username**, not their email address.
5. `GET /api/auth/me` → `401` when anonymous, `200` with the profile when authenticated.
6. `password_hash` in the database always starts with `$2a$`.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/auth-service-1.0.0.jar
```

Then work through the README's `curl` commands. Use `-i` throughout.

**Read the log after every single request.** With
`logging.level.org.springframework.security=DEBUG` you will see lines like
`Securing GET /api/public/health`, `Set SecurityContextHolder to anonymous`, `Invalid CSRF token`,
`Failed to authorize ... with authorization manager ...`. Those lines are the answer key to which
of the six questions above failed.

`mvn test` runs six tests: two pass, four fail.

---

## Known symptoms

### Symptom A — the public health endpoint requires credentials

```
GET /api/public/health
401 Unauthorized
```

It is documented as public, it contains nothing private, and the security configuration plainly
contains a rule permitting it.

### Symptom B — registering returns 401, not 403

```
POST /api/auth/register  {"username":"nisha", ...}
401 Unauthorized
```

`401` means "authenticate yourself" — but registering is precisely what you do when you have no
account to authenticate with. Interestingly, the *same* request from the test suite (MockMvc)
returns **`403`**, not `401`. Two status codes for one request, depending on how it is sent. That
discrepancy is a strong hint, and the log line for this request does not mention authentication at
all.

### Symptom C — a valid password is rejected

```
curl -u aarav:'Secret123!' http://localhost:8080/api/auth/me
401 Unauthorized
```

The account exists — you can see `aarav` in `app_users` — and `Secret123!` really is the password
behind that BCrypt hash. The log says `BadCredentialsException`.

But this works:

```
curl -u 'aarav.sharma@corp.com:Secret123!' http://localhost:8080/api/auth/me
200 OK  {"username":"aarav","authenticated":true, ...}
```

Same account, same password, two different identifiers, two different outcomes. The README is
explicit about which one is supposed to work.

Note also that the log said `BadCredentialsException` — "wrong password" — which is not what
happened. Work out why Spring reported it that way; it is deliberate and worth knowing.

### Symptom D — a freshly registered account cannot sign in

Once you can reach `/register` at all:

```
POST /api/auth/register  {"username":"nisha","email":"nisha@corp.com","password":"Nisha123!"}
201 Created
{"id":4,"username":"nisha","email":"nisha@corp.com","enabled":false, ...}
```

Then signing in with exactly those credentials fails. The seeded accounts work; accounts created
through the API never do.

Read the `201` response body carefully — one field in it contradicts the README. And then look at
what actually landed in the table:

```sql
SELECT username, password_hash, enabled FROM app_users WHERE username='nisha';
+----------+---------------+---------+
| nisha    | Nisha123!     |       0 |
```

There are **two** separate problems visible in that single row.

### Symptom E — the fix that opens a hole

This one you will create yourself, so read it before you start fixing.

The natural repair for Symptom A is to move the permissive rules above the restrictive one. Do that
in the most obvious way and then run:

```
GET /api/auth/me          (no credentials at all)
200 OK   {"username":null,"authenticated":false,"authorities":"[]"}
```

The endpoint documented as requiring authentication is now public. Your fix was right in principle
and wrong in detail. Getting Symptom A right *without* causing this is part of the exercise.

---

## Investigation hints

### Symptom A — the public endpoint that is not public

> **Hint A1**
> There is a rule permitting that path. There is also another rule that matches the same request.
> Both are true. So the question is not "is there a rule?" but "which rule wins?".

> **Hint A2**
> Look up how `authorizeHttpRequests` evaluates its matchers. Is it "most specific wins", or
> something simpler? Read the order the rules are written in and compare it with the order you would
> need.

> **Hint A3**
> `/api/public/health` matches two patterns in that list. Write out which patterns match it, in the
> order they appear, and stop at the first.

> **Hint A4**
> Before you reorder anything, read Symptom E. The obvious reordering introduces a security hole.
> Think about *which specific paths* need to be public, rather than which prefix is convenient.

### Symptom B — 401 from a POST, 403 from the same POST in a test

> **Hint B1**
> The two different status codes for one request are the clue. Something rejects the request before
> any authentication is attempted; how that rejection is *reported* then depends on whether the
> caller looks like a browser or an API client.

> **Hint B2**
> Search the `DEBUG` log for the line logged during this request that does not contain the words
> "authenticate" or "authorize". It names the actual problem in three words.

> **Hint B3**
> That protection exists to stop a browser being tricked into submitting a form to your site using
> a cookie it already holds. Ask yourself whether that attack is even possible against this service —
> look at the session policy the configuration sets, and at how clients present their credentials.

> **Hint B4**
> Turning it off is the right answer *for this service* and the wrong answer in general. Be able to
> say precisely why it is safe here. If your reasoning is "it was in the way", you have not finished
> the hint.

### Symptom C — the wrong identifier

> **Hint C1**
> Spring Security's flow is: load the account by the identifier the client sent, then check the
> account flags, then compare the password. Which of those three steps could produce
> `BadCredentialsException` even when the password is right?

> **Hint C2**
> Find the class that implements `UserDetailsService` and read the single line that looks the
> account up. Which column is it searching?

> **Hint C3**
> On the reported exception: `DaoAuthenticationProvider` has a setting called
> `hideUserNotFoundExceptions`, and it defaults to `true`. Look up what it converts, and why. It
> means the log is deliberately not telling you the difference between "no such user" and "wrong
> password". Knowing this changes how you read every authentication failure for the rest of your
> career.

> **Hint C4**
> You can prove which step failed without changing any code: try an identifier that certainly does
> not exist and compare the log with the failing case. If they look identical, that tells you
> something.

### Symptom D — the two problems in one row

> **Hint D1**
> Compare the `password_hash` value for `nisha` with the one for `aarav`. One of them is a hash. Now
> find the registration code and list everything it does to the password on the way in.

> **Hint D2**
> There is a `PasswordEncoder` bean declared in the configuration and used by Spring Security to
> *verify* passwords. Who is supposed to use it when a password is *stored*? Trace whether anything
> does.

> **Hint D3**
> For the second problem: look at the `enabled` column, then at the entity field behind it, then at
> what registration sets. What is the default value of an unassigned `boolean` field in Java, and
> what does Spring Security's `DaoAuthenticationProvider` do with an account whose `isEnabled()`
> returns `false`?

> **Hint D4**
> These two problems fail at different points in the authentication flow, and one of them happens
> before the other. Fix one and the error you get from the next attempt will change. That change is
> your confirmation that the first fix worked — do not assume nothing happened just because you still
> get `401`.

### Symptom E — the hole your fix opened

> **Hint E1**
> List the three endpoints under the path prefix you just permitted. Now compare that list with the
> "Auth required" column in the README's endpoint table.

> **Hint E2**
> A wildcard in a security rule is a promise about every path that will ever be added under that
> prefix, not just the ones that exist today. Would you be comfortable if a colleague added
> `/api/auth/reset-password` tomorrow?

> **Hint E3**
> Enumerate the paths that genuinely must be anonymous. There are exactly two.

> **Hint E4**
> Prove it, do not assume it. For every endpoint in the README table, call it twice — once with
> credentials and once without — and check the result against the "Auth required" column. That
> two-by-two sweep is how you verify any security configuration.

---

## Expected logs and observations

* The application starts cleanly. Everything here is a request-time decision.
* `logging.level.org.springframework.security=DEBUG` is your primary instrument. Useful lines:
  * `Securing GET /api/...` — the chain has taken the request
  * `Set SecurityContextHolder to anonymous` — no credentials were established
  * `Invalid CSRF token ...` — rejected before authentication was even considered
  * `Failed to authorize ... with authorization manager ...` — an authorization rule refused it
  * `BadCredentialsException` — but see hint C3 before believing it
* At start-up, Spring Security logs the full ordered filter chain. Reading that list once, properly,
  is worth an hour of guessing.
* `curl` and MockMvc can produce **different status codes for the same underlying failure**, because
  the entry point behaves differently for a caller that does or does not accept an HTML challenge.
  Do not treat `401` and `403` as reliable evidence of *which* thing failed.
* Nothing in this project throws a stack trace into the response. Every symptom is a status code plus
  a log line.

## Difficulty

**Intermediate → Advanced.** Expect two to three hours.

The defects are heavily layered. Symptoms A and B both have to be resolved before you can even reach
the registration endpoint, and Symptom D is invisible until then. Symptom C is independent and can be
attacked immediately using the seeded accounts. Symptom E is a trap that your own fix creates.

Work outside-in: get the filter chain letting the right requests through first, then fix what happens
once they are through.

## Concepts being tested

* Filter chain construction and the order `authorizeHttpRequests` evaluates matchers
* Path patterns, wildcards, and why a prefix rule is a promise about future endpoints
* CSRF protection: what it defends against, and when it is genuinely unnecessary
* Why the same rejection surfaces as `401` in one client and `403` in another
* `UserDetailsService` and the identifier an application authenticates on
* `hideUserNotFoundExceptions` and deliberately uninformative security errors
* `PasswordEncoder` — encoding on write versus matching on read
* `UserDetails` account flags (`enabled`) and where they are checked in the authentication flow
* Java's default field values meeting a security decision

## When you think you are done

- [ ] `mvn test` is green (6 tests).
- [ ] `GET /api/public/health` returns `200` with no credentials.
- [ ] `POST /api/auth/register` returns `201` with no credentials, and `"enabled": true`.
- [ ] `GET /api/auth/me` returns **`401`** with no credentials.
- [ ] `curl -u aarav:'Secret123!'` on `/api/auth/me` returns `200`.
- [ ] `curl -u 'aarav.sharma@corp.com:Secret123!'` on `/api/auth/me` returns **`401`** — the email is
      not a login identifier.
- [ ] An account you register through the API can sign in immediately with the password you sent.
- [ ] `SELECT password_hash FROM app_users` shows `$2a$...` for **every** row, including ones you
      created.
- [ ] You have called every endpoint in the README table both with and without credentials, and each
      one matched its "Auth required" column.
- [ ] You can explain in one sentence why disabling CSRF is safe for this particular service.

Then say **"I think I fixed the project"**.
