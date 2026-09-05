# SOLUTION — 09 · Account Management & Authentication Service

> **Sealed answer key.** Six planted defects.

---

## Defect 1 — Matcher order: a broad rule shadows the permissive ones

* **Bug:**

  ```java
  .authorizeHttpRequests(auth -> auth
          .requestMatchers("/api/**").authenticated()        // matches everything below it
          .requestMatchers("/api/auth/**").permitAll()
          .requestMatchers("/api/public/**").permitAll()
          .anyRequest().authenticated())
  ```

* **Affected component:** `config/SecurityConfig.securityFilterChain`
* **Root cause:** `authorizeHttpRequests` evaluates matchers **in declaration order and stops at the
  first match**. It is not "most specific wins". `/api/public/health` matches `/api/**` first, so the
  `permitAll` rules two lines below are unreachable — as is every other rule for any path under
  `/api`.
* **Symptom:** every documented-public endpoint returns `401`.
* **Why the symptom is misleading:** the correct rule is *right there in the file*, plainly visible.
  A reader confirms "yes, `/api/public/**` is permitted" and goes looking elsewhere. Nothing warns
  that a rule is unreachable — unlike a Java `switch` or a `catch` block, Spring Security will
  happily accept dead rules.
* **Correct fix — order from most specific to least, and do not use a prefix wildcard:**

  ```java
  .authorizeHttpRequests(auth -> auth
          .requestMatchers("/api/public/**").permitAll()
          .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
          .anyRequest().authenticated())
  ```

  **`/api/auth/**` is not an acceptable fix** — see Defect 6.
* **Concept:** matcher precedence; first-match-wins; unreachable security rules.
* **Why a fresher makes it:** they add the broad "lock down the API" rule first because it feels like
  the safe default, then append exceptions below it. In an allow-list evaluated top-down, exceptions
  must come *first*.
* **How to recognise it in the wild:** any security rule that appears to be ignored. Write the
  matchers out in order and hand-evaluate the failing path against each one. Spring Security 6 will
  throw at start-up if you place a matcher after `anyRequest()`, but it gives you nothing for a
  wildcard that shadows later rules.

**Gates Defects 3 and 4** — registration is unreachable until this is fixed.

---

## Defect 2 — CSRF protection left enabled on a stateless API

* **Bug:** the filter chain never calls `.csrf(...)`, so Spring Security 6's default — CSRF enabled
  with `HttpSessionCsrfTokenRepository` — applies, while the session policy is `STATELESS`.
* **Affected component:** `config/SecurityConfig.securityFilterChain`
* **Root cause:** `CsrfFilter` runs early in the chain and rejects every non-safe method (`POST`,
  `PUT`, `PATCH`, `DELETE`) that arrives without a valid token. With `STATELESS` sessions there is
  nowhere to keep a token and no way for a client to obtain one, so **no `POST` can ever succeed**.
* **Symptom:** `POST /api/auth/register` fails. Via `curl` it returns **`401`**; via MockMvc it
  returns **`403`**.
* **Why the symptom is misleading — this is the most instructive part of the project:**
  * `CsrfFilter` throws `AccessDeniedException`. `ExceptionTranslationFilter` then asks: is the
    current user anonymous? Yes — so instead of reporting `403 Forbidden`, it *starts authentication*
    by invoking the entry point, which for HTTP Basic means `401 Unauthorized` with a
    `WWW-Authenticate` header. So an authorization failure is reported as an authentication failure.
  * That is exactly the "API returns 403 even though login succeeded" family of confusion, inverted:
    here it returns `401` for something that has nothing to do with credentials.
  * The two different status codes between `curl` and MockMvc make it look environment-dependent.
  * Only one log line names the real cause: `Invalid CSRF token found for http://localhost:8080/...`.
* **Correct fix:**

  ```java
  .csrf(csrf -> csrf.disable())
  ```

  **Insist on the justification.** CSRF attacks depend on the browser automatically attaching an
  ambient credential (a cookie) to a cross-site request. This service is stateless, issues no cookie,
  and requires the client to explicitly attach an `Authorization` header — which a cross-site form
  post cannot do. Therefore CSRF protection buys nothing here. A learner who disables it without
  being able to say that has fixed the symptom and learned nothing; a learner who says "it was in
  the way" should be pushed. Conversely, if they propose a `CookieCsrfTokenRepository` instead,
  that is a thoughtful answer worth discussing — it is what you would do if this service *did* use
  cookies.
* **Concept:** CSRF; `ExceptionTranslationFilter` and the anonymous-user entry-point behaviour; why
  `401` and `403` are not reliable evidence of what failed.
* **Why a fresher makes it:** CSRF is on by default and they never made a decision about it. Most
  tutorials show `.csrf().disable()` with no explanation, so they either copy it blindly or omit it
  blindly.
* **How to recognise it in the wild:** every `POST` failing while every `GET` works is the signature.
  Check for `Invalid CSRF token` in the log before touching anything to do with credentials.

---

## Defect 3 — `AppUserDetailsService` looks the account up by email

* **Bug:**

  ```java
  AppUser user = appUserRepository.findByEmail(username)
          .orElseThrow(() -> new UsernameNotFoundException("No account found for " + username));
  ```

  `findByUsername` exists on the repository and is never used.
* **Affected component:** `service/AppUserDetailsService.loadUserByUsername`
* **Root cause:** the identifier the client presents is matched against the wrong column.
* **Symptom:** `curl -u aarav:'Secret123!'` → `401`; `curl -u 'aarav.sharma@corp.com:Secret123!'` →
  `200`. The same account with the same password, and the identifier the README documents is the one
  that fails.
* **Why the symptom is misleading, on two levels:**
  1. The log reports `BadCredentialsException` — "wrong password" — when the password was never
     reached. `DaoAuthenticationProvider.hideUserNotFoundExceptions` defaults to `true` and converts
     `UsernameNotFoundException` into `BadCredentialsException` on purpose, so that an attacker
     cannot use the error to enumerate valid usernames. Excellent for security, actively hostile to
     debugging. A learner who does not know this will spend a long time re-checking the password.
  2. It *works* with the email, so the service looks half-functional rather than broken. In a team
     this is the bug that gets reported as "login is flaky" or "it works for some people".
* **Correct fix:**

  ```java
  AppUser user = appUserRepository.findByUsername(username)
          .orElseThrow(() -> new UsernameNotFoundException("No account found for " + username));
  ```

  A learner who instead makes it accept *either* identifier has changed the documented contract —
  point that out. It is a reasonable product decision but it is not this ticket, and it also makes
  the email column a login identifier, which has consequences for uniqueness and for account
  takeover via email change.
* **Concept:** `UserDetailsService`; the principal identifier; `hideUserNotFoundExceptions`.
* **Why a fresher makes it:** they think of email as "the" identifier because that is how consumer
  apps work, and `findByEmail` sits next to `findByUsername` in the repository.
* **How to recognise it in the wild:** authentication that works with one identifier and not another.
  To find out whether the failure is lookup or password, temporarily set
  `hideUserNotFoundExceptions=false` on the provider, or add a log line in the
  `UserDetailsService` — never leave either in production.

---

## Defect 4 — Registration stores the raw password

* **Bug:**

  ```java
  user.setPasswordHash(request.getPassword());
  ```

  `AccountService` does not inject the `PasswordEncoder` at all. The bean exists and Spring Security
  uses it to *verify*; nothing uses it to *store*.
* **Affected component:** `service/AccountService.register`
* **Root cause:** the password is persisted verbatim. At login, `BCryptPasswordEncoder.matches()`
  compares the presented password against a value that is not a BCrypt hash, which can never match.
* **Symptom:** registration returns `201`, and the account can never sign in.
  `SELECT password_hash FROM app_users WHERE username='nisha'` returns the literal `Nisha123!`.
* **Why the symptom is misleading:** registration reports complete success, and the seeded accounts
  sign in perfectly — so authentication is demonstrably working. The problem is on the write path, a
  different endpoint, at a different time. Note also that BCrypt logs
  `Encoded password does not look like BCrypt` at `WARN` when it is asked to match against a
  non-hash — a genuine clue that is easy to scroll past.
* **Correct fix:** inject the encoder and use it:

  ```java
  private final PasswordEncoder passwordEncoder;   // constructor-injected
  ...
  user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
  ```

* **Concept:** password encoding on write versus matching on read; storing credentials irreversibly.
* **Why a fresher makes it:** the field is called `passwordHash`, so setting it from the request
  *looks* right. Configuring an encoder for Spring Security and actually applying it on the write
  path are two separate steps and only one of them is enforced by the framework.
* **How to recognise it in the wild:** look at the column. A BCrypt hash starts `$2a$`/`$2b$` and is
  60 characters. Anything else is a security incident. A `NOT NULL` + `CHECK (password_hash LIKE
  '$2%')` constraint is a cheap way to make this impossible.

---

## Defect 5 — `enabled` is never set, so new accounts default to `false`

* **Bug:** `AppUser.enabled` is a primitive `boolean` (default `false`) and
  `AccountService.register` never assigns it. The seeded rows in `data.sql` set it explicitly to
  `true`.
* **Affected component:** `entity/AppUser` + `service/AccountService.register`
* **Root cause:** an unassigned `boolean` is `false`. `AppUserDetailsService` maps that to
  `.disabled(true)`, and `DaoAuthenticationProvider` runs its **pre-authentication checks** —
  including the enabled check — *before* comparing passwords. So a disabled account fails with
  `DisabledException` regardless of the password.
* **Symptom:** the `201` response itself says `"enabled": false`, contradicting the README, and the
  account cannot sign in.
* **Why the symptom is misleading:** it stacks with Defect 4 on the same request, and the two fail at
  *different points* in the authentication flow. Fixing only the password gives `DisabledException`;
  fixing only the enabled flag gives `BadCredentialsException`. Either way the HTTP response is still
  `401`, so a learner who fixes one and re-tests sees "no change" unless they read the log. Hint D4
  is aimed exactly at this — the changing exception type *is* the confirmation that the first fix
  worked.
* **Correct fix:**

  ```java
  user.setEnabled(true);
  ```

  Also acceptable and arguably better: default the field at declaration
  (`private boolean enabled = true;`), or make the column `NOT NULL DEFAULT TRUE` and let the
  database own it. If the learner argues that new accounts *should* start disabled pending email
  verification, that is a good instinct — but the README documents immediate activation, so the fix
  must match the contract.
* **Concept:** `UserDetails` account flags; the order of pre-authentication checks;
  Java default field values.
* **Why a fresher makes it:** nothing prompts them. There is no compiler error, no null check, no
  validation. `boolean` silently defaults, unlike `Boolean`, which would have been `null` and blown
  up on the `NOT NULL` column — a loud failure that would have been *easier* to debug.
* **How to recognise it in the wild:** `DisabledException` in the log, or a `201` response whose body
  contradicts the specification. Read what you actually persisted, not what you meant to.

---

## Defect 6 — `/api/auth/**` groups a protected endpoint with public ones

* **Bug:** the security configuration guards the auth endpoints with the prefix `/api/auth/**`, but
  that prefix covers three endpoints of which only two are public. `/api/auth/me` requires
  authentication.
* **Affected component:** `config/SecurityConfig.securityFilterChain`
* **Root cause:** a wildcard prefix is a statement about every path under it, present and future.
  Here it happens to include the one endpoint that must not be public.
* **Symptom:** **this defect does not manifest until the learner fixes Defect 1.** In the shipped
  state, `/api/**` shadows everything, so `/api/auth/me` is (accidentally) protected. The moment the
  learner reorders the rules in the obvious way —

  ```java
  .requestMatchers("/api/auth/**").permitAll()
  .requestMatchers("/api/public/**").permitAll()
  .requestMatchers("/api/**").authenticated()
  ```

  — `GET /api/auth/me` starts returning `200` with `{"username":null,"authenticated":false}` to
  anonymous callers. Verified: the endpoint documented as requiring authentication becomes public.
* **Why this is the most valuable defect in the project:** it is a security hole introduced by a
  correct-looking fix to a different problem. The learner's instinct after fixing Defect 1 is to move
  on, because the thing they were debugging now works. Nothing fails. No test in the suite catches it
  unless they wrote one. This is precisely how real security regressions happen — and it is why the
  guide's checklist demands they call **every** endpoint both with and without credentials.
* **Correct fix:** enumerate the paths that must be anonymous rather than reaching for a prefix:

  ```java
  .requestMatchers("/api/public/**").permitAll()
  .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
  .anyRequest().authenticated()
  ```

  Pinning the HTTP method as well is a further improvement worth crediting — it means a future
  `GET /api/auth/register` would not be silently public either.
* **Concept:** allow-list design; the blast radius of a wildcard; deny-by-default with explicit
  exceptions.
* **Why a fresher makes it:** prefix wildcards are what every tutorial shows, and grouping by
  controller path is the obvious mental model. The idea that one endpoint on a controller has a
  different security posture from its siblings takes experience.
* **How to recognise it in the wild:** audit by endpoint, never by prefix. For each route, state the
  required authority and test it anonymously. Anything that returns `200` without credentials and was
  not meant to is a finding.

---

## Suggested fix order

1. **Defect 1** — but read the note about Defect 6 before choosing how.
2. **Defect 2** — now `POST` requests can actually reach a controller.
3. **Defect 3** — independent of everything; can be done first using the seeded accounts.
4. **Defects 4 and 5** — both on the registration path, now reachable. Expect the exception type to
   change between them.
5. **Defect 6** — verify by sweeping every endpoint with and without credentials.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `theHealthEndpointIsPublic` | fail (`401`) | Defect 1 |
| `aSeededUserCanAuthenticateWithTheirUsername` | fail (`401`) | Defect 3 |
| `theProfileEndpointRejectsAnonymousCallers` | pass | — (passes for the *wrong reason*: Defect 1) |
| `anAccountCanBeRegistered` | fail (`403`) | Defects 2, then 5 |
| `aNewlyRegisteredUserCanLogIn` | fail (`403`) | Defects 2, then 4 and 5 |
| `aWrongPasswordIsRejected` | pass | — |

Baseline: `Tests run: 6, Failures: 4, Errors: 0`.

Note that **`theProfileEndpointRejectsAnonymousCallers` passes before any fix and will start failing
if the learner introduces Defect 6's hole.** That is the only automated guard against it, and it is
easy to dismiss as "my fix broke a test that was passing". It did — because the fix was wrong.

Defect 6 itself is not covered by a dedicated test; it is caught by that one, indirectly.

## Verification commands

```bash
docker compose down -v && docker compose up -d
mvn clean package && java -jar target/auth-service-1.0.0.jar
mvn test        # 6/6 green

curl -i localhost:8080/api/public/health                          # 200, no credentials
curl -i localhost:8080/api/auth/me                                # 401
curl -i -u aarav:'Secret123!' localhost:8080/api/auth/me          # 200
curl -i -u 'aarav.sharma@corp.com:Secret123!' localhost:8080/api/auth/me   # 401 - email is not a login id

curl -i -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"nisha","email":"nisha.verma@corp.com","password":"Nisha123!","displayName":"Nisha Verma"}'
# 201 and "enabled": true

curl -i -u nisha:'Nisha123!' localhost:8080/api/auth/me           # 200 immediately
```

```sql
-- every row, including newly registered ones, must be a BCrypt hash
SELECT username, LEFT(password_hash, 4) AS prefix, LENGTH(password_hash) AS len, enabled
FROM app_users;
-- prefix '$2a$', len 60, enabled 1
```
