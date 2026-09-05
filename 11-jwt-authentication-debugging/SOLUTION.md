# SOLUTION — 11 · Support Ticket Service

> **Sealed answer key.** Nine planted defects.

---

## Defect 1 — The signing secret is too short for HS256

* **Bug:** `jwt.secret=change-me-secret` — 16 ASCII characters, 128 bits.
  `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` in the `JwtService` constructor rejects it.
* **Affected component:** `src/main/resources/application.properties` and
  `security/JwtService` (constructor)
* **Root cause:** RFC 7518 §3.2 requires an HMAC-SHA key of at least the hash output size — 256 bits
  for HS256. JJWT enforces it rather than silently padding.
* **Symptom:** the application does not start.
  `WeakKeyException: The specified key byte array is 128 bits which is not secure enough for any JWT
  HMAC-SHA algorithm.`
* **Why this one is friendly:** it is the most self-explanatory error in the lab — it names the
  requirement, the RFC and the section. It is here as a warm-up, and to make the point that a
  security library refusing to do something weak is a feature.
* **Correct fix:** supply a secret of at least 32 characters, **from the environment**:

  ```bash
  export JWT_SECRET='development-only-secret-at-least-32-chars-long'
  ```

  or `--jwt.secret=...` at launch. Editing a long literal into `application.properties` also starts
  the application, but the README explicitly says secrets belong in the environment — push on this.
  The best answer generates a key (`Jwts.SIG.HS256.key().build()`) and stores it Base64-encoded
  outside the repository.
* **Concept:** key strength; secrets as environment configuration, not source.
* **Why a fresher makes it:** `change-me-secret` is a placeholder they never changed, and on many
  older JWT libraries a short key simply worked.
* **How to recognise it in the wild:** any library that refuses your key is doing you a favour. Do
  not reach for a weaker algorithm to make the error go away.

**Gates everything.**

---

## Defect 2 — The filter rejects requests instead of delegating

* **Bug:**

  ```java
  if (header == null || !header.startsWith("Bearer ")) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing bearer token");
      return;                                    // chain never continues
  }
  ```

* **Affected component:** `security/JwtAuthenticationFilter.doFilterInternal`
* **Root cause:** the filter takes the authorization decision itself. It runs before
  `AuthorizationFilter`, so it never gives the `permitAll` rule for `/api/auth/**` a chance to apply.
  An authentication filter's only job is: *if there is a credential, establish an identity;
  otherwise pass the request on untouched.* Whether an anonymous request is acceptable is the
  authorization rules' decision, and only they know which paths are public.
* **Symptom:** `POST /api/auth/login` returns **`403`** via `curl` and **`401`** via MockMvc.
* **Why the symptom is misleading — worth teaching explicitly:** the filter sends `401`. Because
  `sendError` triggers a servlet **ERROR dispatch** to `/error`, and that dispatch is re-processed by
  the security chain where `/error` is not permitted, the client finally sees `403`. MockMvc performs
  no ERROR dispatch, so the test sees the original `401`. Two clients, one cause, two numbers. This
  is the concrete lesson behind "read the log, not the status".
* **Correct fix:** pass the request along and let the chain decide:

  ```java
  if (header == null || !header.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
  }
  ```

  Adding a `shouldNotFilter` override that skips `/api/auth/**` also works, but it is a weaker
  answer: it fixes this path and leaves the design error in place for the next public endpoint
  somebody adds. Prefer the delegation fix; mention the override as a complement, not a substitute.
* **Concept:** separation of authentication and authorization; filter ordering; ERROR dispatch and
  status translation.
* **Why a fresher makes it:** "if there is no token, reject it" reads as obviously correct. Nothing
  points out that some endpoints legitimately have no token.
* **How to recognise it in the wild:** a `permitAll` endpoint that is not permitted. Something ahead
  of `AuthorizationFilter` is short-circuiting. Set
  `logging.level.org.springframework.security=TRACE` and watch how far the request gets.

---

## Defect 3 — `header.substring(6)` instead of `substring(7)`

* **Bug:** `String token = header.substring(6);` — `"Bearer "` is **seven** characters including the
  space, so the extracted token keeps a leading space.
* **Affected component:** `security/JwtAuthenticationFilter`
* **Root cause:** off-by-one. `" eyJhbGciOi..."` is not valid Base64URL, so JJWT throws
  `MalformedJwtException`.
* **Symptom:** every authenticated request returns `401` with `MalformedJwtException` in the log,
  while the token itself decodes perfectly with any external tool.
* **Why the symptom is misleading:** the learner verifies the token is well-formed — and it is. The
  corruption happens after the token arrives and before it is parsed, in one character of one line.
  "The server is not parsing what I sent" is a hypothesis people reach only after exhausting the
  token itself.
* **Correct fix:** `header.substring(7)`, or better, avoid the magic number:

  ```java
  private static final String PREFIX = "Bearer ";
  ...
  String token = header.substring(PREFIX.length());
  ```

  Credit the constant — it makes the class of bug impossible.
* **Concept:** header parsing; magic numbers.
* **Why a fresher makes it:** they count the letters in "Bearer" and forget the space.
* **How to recognise it in the wild:** log the extracted credential (never in production) and compare
  it byte for byte with what was sent. `Malformed*` exceptions on input you know is well-formed
  almost always mean the extraction is wrong.

---

## Defect 4 — The `sub` claim carries the user id; the filter resolves by username

* **Bug:** `JwtService.issue` sets `.subject(String.valueOf(user.getId()))`, while the filter does
  `userDetailsService.loadUserByUsername(claims.getSubject())`.
* **Affected component:** `security/JwtService.issue` and `security/JwtAuthenticationFilter`
* **Root cause:** the issuer and the consumer disagree about what `sub` means.
* **Symptom:** `401` with `UsernameNotFoundException: No account found for 1` — the message
  helpfully prints the id it searched for as if it were a username.
* **Why the symptom is misleading:** both halves are individually defensible. Using the immutable id
  as the subject is genuinely good practice in many systems. Resolving by username is what the rest
  of this application does. Neither line looks wrong on its own; only together are they a defect.
* **Correct fix (preferred here):** issue the username as the subject:

  ```java
  .subject(user.getUsername())
  ```

  The alternative — keep the id and have the filter load by id — is defensible, but then
  `authentication.getName()` becomes the id, and `TicketController.myTickets` and
  `TicketRepository.findByReportedBy` both key on the username. Fixing the issuer is one line; fixing
  the consumer is a change that ripples. Ask the learner which they chose and why; either is
  acceptable if they made the rest consistent.
* **Concept:** the `sub` claim; the identifier an application resolves principals by; issuer/consumer
  contracts.
* **Why a fresher makes it:** they read that `sub` should be a stable identifier, use the id, and
  never revisit the filter that was written before that change.
* **How to recognise it in the wild:** a "not found" for a value that is obviously not of the type
  being searched for. Decode the token and compare every claim against how it is consumed.

---

## Defect 5 — `jwt.expiration-seconds` is used as milliseconds

* **Bug:** `Date.from(now.plusMillis(tokenLifetime))` where `tokenLifetime` came from
  `jwt.expiration-seconds=3600`.
* **Affected component:** `security/JwtService` (both `issue` and `reissue`)
* **Root cause:** a unit mismatch. 3600 milliseconds is 3.6 seconds; the token is dead almost
  immediately.
* **Symptom:** the first request after login succeeds; a request a few seconds later returns `401`
  with `ExpiredJwtException`. Decoding the token shows `exp - iat == 4`.
* **Why the symptom is misleading:**
  * It looks intermittent. Fast manual testing succeeds; slow testing fails. The same `curl` works
    and then does not.
  * `ExpiredJwtException` is *technically accurate* — the token really has expired — so the log
    points at the symptom rather than the cause.
  * It masks Defects 6 and 7 during hand testing: a learner who is slow between commands sees `401`
    everywhere and cannot reach the endpoints where those defects live.
* **Correct fix:**

  ```java
  .expiration(Date.from(now.plusSeconds(tokenLifetime)))
  ```

  or convert once at construction (`this.tokenLifetime = Duration.ofSeconds(expirationSeconds)`),
  which is the more robust answer — carry a `Duration`, not a bare number, and the ambiguity cannot
  recur. **Fix `reissue` too**; both methods have it.
* **Concept:** units in configuration; `Duration` over primitive longs.
* **Why a fresher makes it:** `plusMillis` and `plusSeconds` sit next to each other in autocomplete,
  and the property name is the only place the unit is recorded.
* **How to recognise it in the wild:** always assert on `exp - iat`. Any time-valued configuration
  should carry its unit in the type (`Duration`) rather than in the variable name.

---

## Defect 6 — The `Authentication` is built with no authorities

* **Bug:**

  ```java
  new UsernamePasswordAuthenticationToken(principal.getUsername(), null, Collections.emptyList());
  ```

  The token's `role` claim is never read, and the `UserDetails` that was just loaded — which *does*
  carry `ROLE_ADMIN` — is discarded.
* **Affected component:** `security/JwtAuthenticationFilter`
* **Root cause:** the request is authenticated but carries no granted authorities, so any rule
  requiring a role fails.
* **Symptom:** `nadia`, whose token plainly contains `"role": "ADMIN"`, gets `403` from
  `/api/admin/tickets`. Everything else about her request is correct.
* **Why the symptom is misleading — this is the archetypal case for this lab:** authentication
  visibly succeeded. The user is logged in, the token is valid, `/api/tickets` returns her data. Only
  the role check fails. The instinct is to suspect the URL rule or the role name (as in project 10),
  but here both are correct — the authorities simply are not there at all. The three-argument
  constructor also *marks the token authenticated*, so nothing looks broken.
* **Correct fix — two legitimate options, and the choice matters:**

  1. Use the authorities from the `UserDetails` that was just loaded:

     ```java
     new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
     ```

     Fresh from the database on every request — a revoked role takes effect immediately, at the cost
     of a query per request.

  2. Derive them from the token's `role` claim:

     ```java
     List.of(new SimpleGrantedAuthority("ROLE_" + claims.get("role", String.class)))
     ```

     No query, fully stateless — but the authorities are as stale as the token, which is exactly
     Defect 9.

  Either is acceptable; **ask them to state the trade-off**. Note that option 1 also fixes Defect 7
  in the same line.
* **Concept:** constructing an authenticated `Authentication`; where authorities come from in a
  stateless system.
* **Why a fresher makes it:** `Collections.emptyList()` satisfies the constructor signature and the
  code compiles. They test the happy path (a plain user reading their own tickets), which needs no
  authorities at all.
* **How to recognise it in the wild:** log `authentication.getAuthorities()` on a failing request. An
  empty list against a role-protected endpoint is the whole answer.

---

## Defect 7 — The principal is a `String`, but the controller casts it to `UserDetails`

* **Bug:** the filter passes `principal.getUsername()` (a `String`) as the principal;
  `TicketController.raise` does `(UserDetails) authentication.getPrincipal()`.
* **Affected component:** `security/JwtAuthenticationFilter` and
  `controller/TicketController.raise`
* **Root cause:** a type contract mismatch on `Authentication.getPrincipal()`. Spring's own
  `DaoAuthenticationProvider` puts a `UserDetails` there; this filter puts a `String`.
* **Symptom:** `POST /api/tickets` fails with
  `ClassCastException: class java.lang.String cannot be cast to ...UserDetails`. Reported as `500`
  by MockMvc and `403` by `curl` — the same ERROR-dispatch translation as Defect 2.
* **Why the symptom is misleading:** `GET /api/tickets` works perfectly throughout, because it uses
  `authentication.getName()` rather than casting. Two endpoints, one `SecurityContext`, one works.
  That asymmetry is the clue, and it is easy to misread as "the write path has a permissions
  problem" given everything else in this project.
* **Correct fix:** put the `UserDetails` in the principal slot, following the framework's convention:

  ```java
  new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
  ```

  Changing the controller to `authentication.getName()` also works and is arguably cleaner for this
  particular endpoint — but the filter would still be violating the convention, and the next person
  to write a controller will hit the same wall. Prefer fixing the filter; accept the controller fix
  with that caveat.
* **Concept:** the `Authentication` principal contract; framework conventions as an API.
* **Why a fresher makes it:** passing the username string is simpler and works for
  `getName()`-based code, which is most of it.
* **How to recognise it in the wild:** a `ClassCastException` on `getPrincipal()` always means a
  custom filter is populating the context differently from the rest of the application. Standardise
  on `UserDetails`.

---

## Defect 8 — `/api/auth/refresh` accepts an expired token

* **Bug:**

  ```java
  public Claims claimsForRefresh(String token) {
      try {
          return parse(token);
      } catch (ExpiredJwtException ex) {
          return ex.getClaims();          // proceeds with the expired token's claims
      }
  }
  ```

* **Affected component:** `security/JwtService.claimsForRefresh`, used by
  `controller/AuthController.refresh`
* **Root cause:** the one exception that means "this credential is no longer valid" is caught and
  used to recover the claims, which are then reissued. `JwtException` for a *tampered* token is still
  thrown, so signatures are checked — only expiry is ignored.
* **Symptom:** `200 OK` and a brand-new token, from a token that expired an hour ago. Since the new
  one is refreshable too, **no token this service issues ever truly expires.** Nothing errors and
  nothing is logged.
* **Why the symptom is misleading:** it looks like a feature. "Refresh lets you carry on working"
  is exactly what a refresh endpoint is for, and the code reads like careful, defensive handling of
  an expected exception. Only by asking "what is expiry *for*?" does it become obviously wrong.
  Defect 5 also hides it: while tokens die in four seconds, refreshing an expired token feels
  necessary just to test anything.
* **Correct fix:** refresh only a currently valid token:

  ```java
  Claims claims = jwtService.parse(request.getToken());   // ExpiredJwtException propagates -> 401
  ```

  and map `JwtException` to `401` in the exception handler. The complete answer, worth raising: if
  long sessions are wanted, issue a **separate refresh token** with its own longer lifetime, stored
  server-side so it can be revoked. An access token that refreshes itself forever is an access token
  with no expiry.
* **Concept:** token expiry semantics; access tokens versus refresh tokens; catching an exception
  that is a security decision.
* **Why a fresher makes it:** they hit `ExpiredJwtException` while testing refresh, notice that
  `ex.getClaims()` conveniently returns the payload, and use it. The library offering the accessor
  feels like permission.
* **How to recognise it in the wild:** test the negative case deliberately — wait for expiry, then
  refresh. Any `catch` block around an authentication or expiry exception deserves a second look.

---

## Defect 9 — Reissue copies claims from the old token instead of re-reading the account

* **Bug:**

  ```java
  public String reissue(Claims previousClaims) {
      return Jwts.builder()
              .subject(previousClaims.getSubject())
              .claim("role", previousClaims.get("role"))          // never consults the database
              ...
  }
  ```

* **Affected component:** `security/JwtService.reissue`
* **Root cause:** the new token asserts whatever the old one asserted. The account is never
  consulted, so a privilege change never propagates.
* **Symptom (verified):** with a valid `nadia` token in hand, run
  `UPDATE app_users SET role='USER' WHERE username='nadia'`, then refresh. The new token still
  contains `"role": "ADMIN"`. Demotion has no effect for as long as the holder keeps refreshing.
* **Why the symptom is misleading:**
  * It produces no error at any point, and the refresh response looks entirely normal.
  * It is fully weaponised only once Defect 6 is fixed in the "read the role claim" direction — so a
    learner who fixes Defect 6 the stateless way *creates* the exploitable version of this defect
    while fixing something else. If they fix Defect 6 the "use the fresh `UserDetails`" way, the
    stale claim is inert for authorisation but the token still lies about the user, which will bite
    the first consumer that trusts it.
  * The database is the source of truth and it is correct throughout. Nothing is corrupt; the system
    is simply not asking.
* **Correct fix:** re-derive from the account:

  ```java
  AppUser user = appUserRepository.findByUsername(claims.getSubject())
          .orElseThrow(() -> new UsernameNotFoundException(claims.getSubject()));
  return jwtService.issue(user);      // same path as login
  ```

  Reusing `issue(...)` rather than having a parallel `reissue(...)` is the better structural answer —
  one place where a token's claims are decided.
* **Concept:** the central trade-off of stateless authentication — **a self-contained token cannot be
  revoked, only expired**; claims as a point-in-time snapshot; keeping token lifetimes short so
  staleness is bounded.
* **Why a fresher makes it:** "refresh means give me the same thing with a later expiry" is the
  intuitive reading, and copying the claims is the literal implementation of it.
* **How to recognise it in the wild:** change a user's permissions and verify the change takes effect
  within one token lifetime. If it never does, something is re-signing stale claims. This is why
  production JWT setups keep access tokens to minutes rather than hours.

---

## Suggested fix order

The first five are a strict chain and must be done in order:

1. **Defect 1** — start-up.
2. **Defect 2** — reach `/api/auth/login`.
3. **Defect 3** — the token survives extraction.
4. **Defect 4** — the subject resolves.
5. **Defect 5** — the token lives longer than four seconds.

Then, independently:

6. **Defect 6** — authorities (fixes Defect 7 too if done via `principal`).
7. **Defect 7** — the principal type, if not already covered.
8. **Defect 8** — refresh must reject expired tokens.
9. **Defect 9** — reissue from the account.

**The most important thing to check with the learner** is that they noticed the exception class
changing at each step of 2→5 while the status code stayed at `401`/`403` throughout. That is the
skill this project exists to build. Ask them to recite the sequence: `WeakKeyException` →
"No bearer token" → `MalformedJwtException` → `UsernameNotFoundException` → `ExpiredJwtException`.

## Test expectations

| Test | Before | Fails because of |
|---|---|---|
| `loginIsReachableWithoutAToken` | fail (`401`) | Defect 2 |
| `aRequestWithoutATokenIsRejected` | **pass** | passes for the wrong reason (Defect 2) |
| `aValidTokenReachesTheTicketList` | fail at login | Defect 2, then 3, 4 |
| `theTokenIsValidForTheConfiguredNumberOfSeconds` | fail at login | then Defect 5 |
| `anAdminCanReadEveryTicket` | fail at login | then Defect 6 |
| `aPlainUserCannotReadEveryTicket` | fail at login | then passes |
| `aTicketCanBeRaised` | fail at login | then Defect 7 |
| `aGarbageTokenIsRejected` | **pass** | — |

Baseline: `Tests run: 8, Failures: 6, Errors: 0`.

The suite overrides `jwt.secret` via `@TestPropertySource`, so **Defect 1 does not affect the
tests** — they fail at login instead. A learner who runs only `mvn test` will never see the
start-up failure. That is deliberate: the test suite and the running application disagree about the
first defect, which is a realistic and useful trap.

**Defects 8 and 9 are not covered by any test.** Both are security holes that return `200 OK`; no
assertion on a happy path will ever catch them.

## Verification commands

```bash
docker compose down -v && docker compose up -d
mvn clean package
export JWT_SECRET='development-only-secret-at-least-32-chars-long'
java -jar target/ticket-service-1.0.0.jar        # must start
mvn test                                          # 8/8 green

jwtdecode() { python -c "import sys,base64,json;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);print(json.dumps(json.loads(base64.urlsafe_b64decode(p)),indent=2))" "$1"; }
login() { curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"Secret123!\"}" | python -c "import sys,json;print(json.load(sys.stdin)['token'])"; }

T=$(login asha);  jwtdecode "$T"                  # exp - iat == 3600
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $T" localhost:8080/api/tickets   # 200
sleep 30
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $T" localhost:8080/api/tickets   # still 200

A=$(login nadia)
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $A" localhost:8080/api/admin/tickets  # 200
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $T" localhost:8080/api/admin/tickets  # 403

curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/tickets \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"subject":"Monitor flickers","body":"Since the update"}'                                     # 201

# tampering must be refused
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer ${T%?}X" localhost:8080/api/tickets  # 401

# expired tokens must not be refreshable: set jwt.expiration-seconds=2, log in, wait 5s, then
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"token\":\"$OLD\"}"                                     # 401

# revocation must take effect
docker exec debuglab11-mysql mysql -uroot -prootpw ticketsdb \
  -e "UPDATE app_users SET role='USER' WHERE username='nadia';"
jwtdecode "$(curl -s -X POST localhost:8080/api/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"token\":\"$A\"}" | python -c "import sys,json;print(json.load(sys.stdin)['token'])")"      # role: USER
```
