# Account Management & Authentication Service

The service that owns user accounts for the platform: register an account, sign in, and ask "who am
I?". Everything else in the estate delegates authentication here.

## Purpose

Three things have to be true of this service:

1. **Public things are public.** The health endpoint and the register/login endpoints must work
   without credentials, because a caller who has no account cannot present any.
2. **Private things are private.** The profile endpoint must reject anonymous callers.
3. **Credentials are never stored in a recoverable form.** Passwords are hashed with BCrypt on the
   way in and only ever compared as hashes.

It is a stateless API. There is no session, no login form and no cookie — clients present HTTP Basic
credentials on every request, or call `/api/auth/login` to check a pair of credentials.

## Architecture

```
com.debuglab.auth
├── AuthServiceApplication
├── config/SecurityConfig            filter chain, matchers, password encoder
├── controller/
│   ├── AuthController               register / login / me
│   └── PublicController             health
├── service/
│   ├── AccountService               registration and lookup
│   └── AppUserDetailsService        loads an account for Spring Security
├── repository/AppUserRepository
├── entity/AppUser
├── dto/
└── exception/
```

Authentication is Spring Security's standard `DaoAuthenticationProvider` flow: the framework calls
`AppUserDetailsService` to load the account, checks the account flags, then compares the presented
password against the stored hash using the configured `PasswordEncoder`.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation, **security**)
* Spring Security 6.3
* Hibernate 6.5, MySQL 8.0 (Docker)
* Maven, JUnit 5, MockMvc, `spring-security-test`

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+
* Docker Desktop (or any Docker engine with Compose v2)

### Database setup

```bash
docker compose up -d
```

MySQL is published on **host port 3307**. The application creates its schema at start-up and
`data.sql` seeds three accounts so that you have working credentials before registering your own:

| Username | Email | Password | Enabled |
|---|---|---|---|
| `aarav` | `aarav.sharma@corp.com` | `Secret123!` | yes |
| `divya` | `divya.nair@corp.com` | `Secret123!` | yes |
| `rohan` | `rohan.mehta@corp.com` | `Passw0rd!` | yes |

These are throwaway development credentials that exist only in `data.sql`. The stored values are
BCrypt hashes — the plaintext above is documented here purely so you can sign in locally.

```bash
docker exec -it debuglab09-mysql mysql -uroot -prootpw authdb
```

```sql
SELECT id, username, email, LEFT(password_hash, 12) AS hash_prefix, enabled FROM app_users;
```

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/authdb?...` | Host port from `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `authuser` / `authpw` | **Placeholder credentials** — replace if you use your own MySQL |
| `logging.level.org.springframework.security` | `DEBUG` | Prints every decision the filter chain makes |

That last setting is the instrument for this project. Spring Security explains itself in detail at
`DEBUG` — which filter matched, which authority was required, which exception was thrown.

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/auth-service-1.0.0.jar
```

```bash
mvn test          # requires the container to be up
```

## API endpoints

| Method | Path | Auth required | Description | Success |
|---|---|---|---|---|
| `GET` | `/api/public/health` | **no** | Liveness check | `200` |
| `POST` | `/api/auth/register` | **no** | Create an account | `201` |
| `POST` | `/api/auth/login` | **no** | Verify a username and password | `200` |
| `GET` | `/api/auth/me` | **yes** | The signed-in user's profile | `200`, `401` when anonymous |

### Health

```bash
curl -i http://localhost:8080/api/public/health
```

### Register

```bash
curl -i -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"nisha","email":"nisha.verma@corp.com","password":"Nisha123!","displayName":"Nisha Verma"}'
```

```json
{ "id": 4, "username": "nisha", "email": "nisha.verma@corp.com",
  "displayName": "Nisha Verma", "enabled": true, "createdAt": "2026-01-01T10:00:00" }
```

A new account is **enabled immediately** and can sign in straight away. The password is hashed before
it is stored — `password_hash` must never contain anything you could read back.

`409 Conflict` if the username or email is already taken; `400` with a `fields` object if the payload
is invalid (username at least 3 characters, valid email, password at least 8 characters).

### Login

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"aarav","password":"Secret123!"}'
```

```json
{ "username": "aarav", "authorities": "[ROLE_USER]", "message": "Login successful" }
```

`401` if the username is unknown, the password is wrong, or the account is disabled.

### Profile

```bash
curl -i -u aarav:'Secret123!' http://localhost:8080/api/auth/me
```

```json
{ "username": "aarav", "authenticated": true, "authorities": "[ROLE_USER]",
  "email": "aarav.sharma@corp.com", "displayName": "Aarav Sharma" }
```

Without credentials this endpoint returns `401`.

## Expected functionality

* `GET /api/public/health` works with no credentials.
* `POST /api/auth/register` works with no credentials and returns an account that is `enabled`.
* An account created through `/register` can sign in immediately with the password that was sent.
* Users sign in with their **username**. The email address is a contact detail, not a login
  identifier.
* `GET /api/auth/me` returns `401` for an anonymous caller and the profile for an authenticated one.
* `password_hash` in the database is always a BCrypt hash (it starts with `$2a$`), never a
  recoverable password.
