# Debugging Guide — 11 · Support Ticket Service

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

The application does not start. When you get it started, you cannot log in. When you can log in, the
token does not work. When the token works, it stops working four seconds later.

This is the deepest project in the lab so far and it is built as a **chain**: several defects sit one
behind another, and each one is invisible until the one in front of it is cleared. That is exactly
what debugging an unfamiliar authentication stack feels like, and the skill it trains is precise:

> Every one of these failures produces the same HTTP status. **Every one of them produces a
> different exception in the log.** Stop reading the status code and start reading the exception.

Write down the exception class you are currently fighting. When it changes, you have made progress —
even if the status code has not moved at all.

## Expected behaviour

1. The application starts.
2. `POST /api/auth/login` works with **no** `Authorization` header.
3. The issued token's `exp - iat` equals `3600` seconds.
4. That token authenticates every subsequent request for the next hour.
5. `nadia` (ADMIN) reaches `/api/admin/tickets`; `asha` (USER) gets `403`.
6. `POST /api/tickets` returns `201` and records `reportedBy` as the caller.
7. An expired token is refused, including by `/api/auth/refresh`.
8. A refreshed token reflects the account's **current** role.

## How to reproduce

```bash
docker compose up -d
mvn clean package
java -jar target/ticket-service-1.0.0.jar 2>&1 | tee app.log
```

Two things you will use constantly — set them up now.

**Decode a token:**

```bash
jwtdecode() { python -c "import sys,base64,json;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);print(json.dumps(json.loads(base64.urlsafe_b64decode(p)),indent=2))" "$1"; }
```

**Log in and capture the token:**

```bash
login() { curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"Secret123!\"}"; }
```

A JWT is not encrypted. Decoding it is free, requires no key, and tells you exactly what the server
put in it. Use it constantly.

`mvn test` runs eight tests: two pass, six fail — five of them because they cannot even get past
login.

---

## Known symptoms

### Symptom A — the application will not start

```
Caused by: io.jsonwebtoken.security.WeakKeyException: The specified key byte array is 128 bits
which is not secure enough for any JWT HMAC-SHA algorithm. The JWT JWA Specification (RFC 7518,
Section 3.2) states that keys used with HMAC-SHA algorithms MUST have a size >= 256 bits ...
```

This is the friendliest error in the whole project — it tells you the requirement, the standard and
the section number. Fix it in the way the README's **Environment configuration** section describes,
not by weakening the algorithm.

### Symptom B — you cannot log in

```
POST /api/auth/login   {"username":"asha","password":"Secret123!"}
403 Forbidden
```

`403` on the one endpoint that is explicitly `permitAll`, from a request that carries no credentials
because it is not supposed to need any. The log for that request says:

```
DEBUG c.d.t.security.JwtAuthenticationFilter : No bearer token on POST /api/auth/login
```

Note the oddity: run the same request from `mvn test` and MockMvc reports **`401`**, not `403`. Same
cause, two status codes. Work out why the two clients differ — the answer explains a lot about how
Spring reports failures that happen inside a filter.

### Symptom C — every request with a perfectly good token is refused

Once you can log in, you get a token. Use it and:

```
GET /api/tickets   -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
401 Unauthorized
```

The log says:

```
WARN  ... Rejecting GET /api/tickets: MalformedJwtException - ...
```

Decode the token yourself with `jwtdecode` — it is perfectly well-formed. So the token the server is
trying to parse is not the token you sent.

### Symptom D — the token parses, and is still refused

Clear Symptom C and the exception changes:

```
WARN  ... Rejecting GET /api/tickets: UsernameNotFoundException - No account found for 1
```

Read that message very carefully. It names what the server searched for. Then decode your token and
compare.

### Symptom E — the token expires almost immediately

Clear Symptom D and `GET /api/tickets` finally returns `200`. Run it again five seconds later:

```
401   ... ExpiredJwtException - JWT expired ...
```

```bash
jwtdecode "$TOKEN"
{ "sub": "...", "iat": 1788593789, "exp": 1788593793 }
```

`exp - iat` is **4**. `application.properties` says `3600`.

### Symptom F — the admin is forbidden from the admin endpoint

```
nadia (role ADMIN)   GET /api/admin/tickets   ->  403
```

Her token is valid, it has not expired, and `jwtdecode` shows `"role": "ADMIN"`. The URL rule
requires `ADMIN`. She is refused.

### Symptom G — raising a ticket fails with a cast error

```
POST /api/tickets   -> 403 (curl) / 500 (MockMvc)
```

```
java.lang.ClassCastException: class java.lang.String cannot be cast to class
  org.springframework.security.core.userdetails.UserDetails
```

Reading tickets works. Only writing one fails, and it fails in the controller rather than in the
filter.

### Symptom H — expired tokens can be refreshed forever

```bash
# take a token, wait until it expires, then:
curl -X POST localhost:8080/api/auth/refresh -H 'Content-Type: application/json' -d "{\"token\":\"$EXPIRED\"}"
200 OK   {"token":"eyJ...","subject":"nadia"}
```

An expired token was exchanged for a fresh one. Since the new one can be refreshed too, no token
issued by this service ever really expires.

### Symptom I — revoking a role does not revoke anything

```sql
UPDATE app_users SET role = 'USER' WHERE username = 'nadia';
```

```bash
curl -X POST localhost:8080/api/auth/refresh -d "{\"token\":\"$NADIA_TOKEN\"}" ...
jwtdecode "$NEW_TOKEN"
{ "sub": "nadia", "role": "ADMIN", ... }        <-- the database says USER
```

Demoting a user has no effect as long as they keep refreshing. This one is fully visible only once
Symptom F is fixed and the token's role claim actually drives authorisation — but you can prove the
mechanism right now with the commands above.

---

## Investigation hints

### Symptom A — start-up

> **Hint A1**
> The exception states the requirement in bits. Convert that to characters for a UTF-8 ASCII string
> and count what the configured value gives you.

> **Hint A2**
> The README tells you where a secret should come from in a real deployment. Fix it the way the
> README describes rather than by editing a literal into the file — the point is that this value is
> environment configuration, not source code.

### Symptom B — 403 on the public login endpoint

> **Hint B1**
> The URL rule permitting that path is correct. So the request is not being stopped by the
> authorization rules — it is being stopped earlier. Which component in the request flow runs
> *before* the authorization filter?

> **Hint B2**
> Read that component's very first decision, before it looks at any token. What does it do when the
> header is absent? Is refusing the request its job, or somebody else's?

> **Hint B3**
> A filter that authenticates should do exactly one thing: if there is a credential, establish an
> identity; otherwise, pass the request along **unchanged**. Deciding whether an anonymous request
> is acceptable belongs to the authorization rules, which know which paths are public. Your filter
> does not.

> **Hint B4**
> On the `403`-versus-`401` discrepancy: when a filter calls `sendError`, the servlet container
> performs an internal ERROR dispatch to `/error`. That dispatch goes through the security chain
> again, and `/error` is not a permitted path. MockMvc does not perform that dispatch, so it reports
> the original status. Remember this — it is why a `403` can be a lie about what actually happened.

### Symptom C — MalformedJwtException on a well-formed token

> **Hint C1**
> The server is not parsing what you sent. Something between the header and the parser is changing
> it. Find the line that extracts the token from the header value.

> **Hint C2**
> Count the characters in the string `"Bearer "` — including the space. Compare that with the index
> the code starts from.

> **Hint C3**
> Prove it before you fix it: log the extracted token and compare it, character by character, with
> the one you sent. An off-by-one at the front of a Base64 string produces exactly this exception.

### Symptom D — UsernameNotFoundException for "1"

> **Hint D1**
> The message names what was searched for. Decode your token and find which claim holds that value.

> **Hint D2**
> There are two places to reconcile: what the issuer puts in the `sub` claim, and what the filter
> does with `sub` when the token comes back. They currently disagree.

> **Hint D3**
> Decide which of the two should change and be able to justify it. `sub` is meant to identify the
> subject; the question is whether *this application* identifies accounts by their database id or by
> their username, and which one the rest of the code — including `Authentication.getName()`, which
> the ticket queries rely on — already assumes.

### Symptom E — the four-second token

> **Hint E1**
> Decode the token and compute `exp - iat`. Now find the configured value and the unit named in the
> property's own key.

> **Hint E2**
> Find where the configured number is turned into an expiry instant. Read the name of the method it
> is passed to and ask what unit *that* method expects.

> **Hint E3**
> `3600` was interpreted as one unit when it meant another. Fix it in whichever direction you like —
> convert at the point of use, or store the value in the unit the code expects — but make sure the
> property key still tells the truth about its own unit.

### Symptom F — the forbidden admin

> **Hint F1**
> `403` means the request was authenticated but the authorities did not satisfy the rule. So the
> question is: what authorities does this request actually carry?

> **Hint F2**
> Find where the filter constructs the `Authentication` object it puts into the `SecurityContext`.
> There are three arguments. Look at the third one.

> **Hint F3**
> The token has a `role` claim. Nothing currently reads it. Decide where the authorities should come
> from — the claim in the token, or a fresh lookup of the account — and note that this is a real
> design decision with a real trade-off, not a typo. Hint I revisits it.

### Symptom G — the cast error on write

> **Hint G1**
> The controller casts the principal to a particular type. Go and look at what the filter actually
> put there as the principal.

> **Hint G2**
> Two ways to reconcile it, and they are not equivalent. You can change the controller to work with
> what the filter provides, or change the filter to provide what the rest of Spring Security expects
> from an authenticated request. Look at what the framework's own `DaoAuthenticationProvider` puts
> in that slot and follow the same convention.

> **Hint G3**
> Note that `GET /api/tickets` works throughout this symptom, because it uses
> `authentication.getName()` rather than casting the principal. Two endpoints, same context object,
> one works — that asymmetry is the clue.

### Symptom H — refreshable expired tokens

> **Hint H1**
> Find the code path the refresh endpoint uses to read the incoming token, and compare it with the
> path the filter uses. They are not the same method.

> **Hint H2**
> One of them catches a specific exception and carries on with the claims it recovers from it. Read
> what that exception means and ask whether recovering from it is ever appropriate here.

> **Hint H3**
> Ask the design question: what is the point of an expiry that can be extended indefinitely by the
> holder of the expired token? If you want long-lived sessions, the standard answer is a **separate
> refresh token** with its own lifetime and its own storage, so it can be revoked. A refresh
> endpoint that accepts expired access tokens is just an access token with no expiry.

### Symptom I — the un-revokable role

> **Hint I1**
> Look at how the reissued token is built and where each of its claims comes from.

> **Hint I2**
> Every claim is copied from the old token. Nothing consults the database. So the new token asserts
> whatever the old one asserted, indefinitely.

> **Hint I3**
> This is the general trade-off of stateless authentication and it is worth being able to state:
> **a self-contained token cannot be revoked, only expired.** Any privilege change takes effect only
> when the token is re-derived from the source of truth. Reissue from the account, and keep expiry
> short enough that a stale token is a bounded problem.

---

## Expected logs and observations

* The whole project is designed to be debugged from the log, because the status codes lie.
  Watch for the exception name in each `Rejecting ...` line:

  | Exception in the log | What is actually wrong |
  |---|---|
  | `WeakKeyException` | Symptom A, at start-up |
  | `No bearer token on POST /api/auth/login` | Symptom B |
  | `MalformedJwtException` | Symptom C |
  | `UsernameNotFoundException` | Symptom D |
  | `ExpiredJwtException` | Symptom E |
  | *(none — a clean `403`)* | Symptom F |
  | `ClassCastException` | Symptom G |

* `curl` and MockMvc report **different status codes for the same failure** whenever a filter calls
  `sendError`. Trust the log, not the number.
* `logging.level.org.springframework.security=DEBUG` prints the authorities on each request — the
  fastest confirmation for Symptom F.
* Decoding the token is not cheating and needs no key. If you are ever unsure what the server
  believes about the caller, look inside the token.

## Difficulty

**Advanced.** Expect three to four hours. Nine defects.

Symptoms A → B → C → D → E form a strict chain: each is invisible until the previous one is cleared,
and clearing one changes the exception rather than the status code. Symptoms F and G are independent
once you have a working token. H and I are security holes that produce no error at all — they only
show up if you go looking.

Fix them in the order they present. Do not try to reason about the whole stack at once.

## Concepts being tested

* HMAC signing key strength and where a secret should live
* The correct responsibility of an authentication filter versus the authorization rules
* Why `sendError` inside a filter can surface as a different status code
* Token parsing, header prefixes, and off-by-one extraction
* The `sub` claim and the identifier an application resolves principals by
* Expiry arithmetic and units
* Populating `SecurityContext` with the right principal type and the right authorities
* Where authorities should come from — token claims versus a fresh lookup
* Refresh semantics, and why an expired access token must not be refreshable
* The fundamental limitation of stateless tokens: they expire, they do not revoke

## When you think you are done

- [ ] `mvn test` is green (8 tests).
- [ ] The application starts without a hardcoded secret in `application.properties`.
- [ ] `POST /api/auth/login` returns `200` with no `Authorization` header.
- [ ] `jwtdecode "$TOKEN"` shows `exp - iat == 3600`.
- [ ] The same token still works five minutes after it was issued.
- [ ] `nadia` gets `200` from `/api/admin/tickets`; `asha` gets `403`.
- [ ] `POST /api/tickets` returns `201` with `reportedBy` set to the caller.
- [ ] A token with its last character changed is refused everywhere.
- [ ] An expired token is refused by `/api/auth/refresh`, not exchanged.
- [ ] After `UPDATE app_users SET role='USER' WHERE username='nadia'`, a re-login **and** a refresh
      both produce a token whose `role` claim is `USER`.
- [ ] You can name, from memory, which exception each of the seven failures produced.

Then say **"I think I fixed the project"**.
