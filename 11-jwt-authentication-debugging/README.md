# Support Ticket Service

A stateless REST API for raising and reading support tickets. Clients sign in once, receive a JSON
Web Token, and present that token on every subsequent request.

## Purpose

There is no session, no cookie and no server-side login state. The token *is* the session: it carries
the user's identity and role, it is signed so it cannot be tampered with, and it expires so a stolen
token stops working.

Three properties have to hold:

1. **Anyone can reach `/api/auth/login`** — you cannot present a token before you have one.
2. **A valid token authenticates and authorises the caller**, with the role it carries.
3. **An invalid, tampered or expired token is refused**, and refreshing re-checks the account rather
   than trusting the old token.

## Architecture

```
com.debuglab.tickets
├── TicketServiceApplication
├── config/SecurityConfig             filter chain, URL rules, filter registration
├── security/
│   ├── JwtService                    issue / reissue / parse
│   └── JwtAuthenticationFilter       reads the header, populates the SecurityContext
├── controller/
│   ├── AuthController                login, refresh
│   └── TicketController              my tickets, raise a ticket, all tickets (admin)
├── service/AppUserDetailsService     loads an account and its authority
├── repository/
├── entity/  AppUser, Ticket
├── dto/
└── exception/
```

Request flow for a protected endpoint:

```
request → JwtAuthenticationFilter → AuthorizationFilter → controller
             reads Authorization:          checks the URL rule
             Bearer <token>,               against the authorities
             populates SecurityContext     in the SecurityContext
```

Everything hinges on the filter putting the right thing into the `SecurityContext`.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation, security)
* Spring Security 6.3
* **JJWT 0.12.6** (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`)
* Hibernate 6.5, MySQL 8.0 (Docker)
* Maven, JUnit 5, MockMvc

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+
* Docker Desktop (or any Docker engine with Compose v2)

### Database setup

```bash
docker compose up -d
```

MySQL is published on **host port 3307**. The schema is created at start-up and `data.sql` seeds
three accounts and five tickets.

| Username | Role | Password | Tickets |
|---|---|---|---|
| `asha` | `USER` | `Secret123!` | 2 |
| `ravi` | `USER` | `Secret123!` | 2 |
| `nadia` | `ADMIN` | `Secret123!` | 1 |

Development credentials only; stored values are BCrypt hashes.

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/ticketsdb?...` | Host port from `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `ticketuser` / `ticketpw` | **Placeholder credentials** |
| `jwt.secret` | `change-me-secret` | **Placeholder.** See below. |
| `jwt.expiration-seconds` | `3600` | How long an issued token stays valid, **in seconds** |

**On the signing secret.** The value in `application.properties` is a development placeholder and
nothing more. Supply your own in any real environment through the environment rather than the file:

```bash
export JWT_SECRET='your-own-long-random-secret'     # Spring maps JWT_SECRET -> jwt.secret
java -jar target/ticket-service-1.0.0.jar
```

or pass it at launch:

```bash
java -jar target/ticket-service-1.0.0.jar --jwt.secret='your-own-long-random-secret'
```

HMAC-SHA signing keys must be **at least 256 bits** (32 bytes / 32 ASCII characters). Never commit a
real secret.

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/ticket-service-1.0.0.jar
```

```bash
mvn test          # requires the container to be up
```

## API endpoints

| Method | Path | Token required | Role | Success |
|---|---|---|---|---|
| `POST` | `/api/auth/login` | **no** | — | `200` |
| `POST` | `/api/auth/refresh` | **no** (token in the body) | — | `200` |
| `GET` | `/api/tickets` | yes | any | `200` |
| `POST` | `/api/tickets` | yes | any | `201` |
| `GET` | `/api/admin/tickets` | yes | **`ADMIN`** | `200` |

### Signing in

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"asha","password":"Secret123!"}'
```

```json
{ "token": "eyJhbGciOiJIUzUxMiJ9...", "username": "asha", "role": "USER" }
```

`401` if the username or password is wrong.

### Using the token

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"asha","password":"Secret123!}"' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/tickets
```

A token stays valid for `jwt.expiration-seconds` (one hour by default), so the same token works for
every request during that hour.

### Raising a ticket

```bash
curl -X POST http://localhost:8080/api/tickets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subject":"Monitor flickers","body":"Since the firmware update","priority":"NORMAL"}'
```

`reportedBy` is taken from the token, never from the request body.

### Admin view

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/admin/tickets
```

`403` for a token whose role is not `ADMIN`.

### Refreshing

```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"token\":\"$TOKEN\"}"
```

Exchanges a **currently valid** token for a new one with a fresh expiry. An expired token cannot be
refreshed — that is the whole point of an expiry — and the new token reflects the account's
**current** role, not whatever the old token happened to say.

### Inspecting a token

JWT payloads are Base64URL, not encrypted. You can always read one:

```bash
python -c "import sys,base64,json;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);print(json.dumps(json.loads(base64.urlsafe_b64decode(p)),indent=2))" "$TOKEN"
```

```json
{ "sub": "asha", "role": "USER", "displayName": "Asha Kulkarni",
  "iat": 1788593789, "exp": 1788597389 }
```

`exp - iat` should equal `jwt.expiration-seconds`.

## Expected functionality

* `POST /api/auth/login` works with no `Authorization` header at all.
* A token issued at login works for the next hour.
* `sub` in the token identifies the account the way the application looks accounts up.
* A token from an `ADMIN` reaches `/api/admin/tickets`; a token from a `USER` gets `403`.
* `POST /api/tickets` records the ticket against the caller in the token.
* An expired or tampered token is refused everywhere, including at `/api/auth/refresh`.
* Refreshing reflects the account's current role.
