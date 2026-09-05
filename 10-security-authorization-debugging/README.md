# Task Management API

A small work-tracking service with three levels of privilege: people who do tasks, people who assign
them, and people who administer the system.

## Purpose

Everyone who calls this service is authenticated. The question this service has to answer correctly
is not *who are you* but **what are you allowed to do** — and it has to answer it the same way at
every layer, for every endpoint, for every role.

## Roles

| Role | May |
|---|---|
| `USER` | See their own tasks; create tasks |
| `MANAGER` | Everything a `USER` may, plus assign a task to someone |
| `ADMIN` | Everything above, plus delete tasks and read the admin endpoints |

## Architecture

```
com.debuglab.tasks
├── TaskManagerApplication
├── config/SecurityConfig            filter chain + URL authorization rules
├── controller/
│   ├── TaskController               list / create / assign / delete
│   └── AdminController              users / audit
├── service/
│   ├── TaskService                  task rules, plus method-level authorization
│   └── AppUserDetailsService        loads an account and its granted authorities
├── repository/
│   ├── AppUserRepository
│   └── TaskRepository
├── entity/
│   ├── AppUser                      many-to-many with Role
│   ├── Role                         USER / MANAGER / ADMIN
│   └── Task
├── dto/
└── exception/
```

Authorization is applied in **two layers**, deliberately:

1. **URL rules** in `SecurityConfig`, which decide whether a request may reach a controller at all.
2. **Method rules** on the service layer, so that a privileged operation stays privileged even if it
   is later called from somewhere new.

Defence in depth: either layer alone should be enough to keep an operation safe.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation, security)
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
`data.sql` seeds the roles, four accounts and five tasks.

| Username | Role | Password |
|---|---|---|
| `alice` | `USER` | `Secret123!` |
| `bob` | `USER` | `Secret123!` |
| `carol` | `MANAGER` | `Secret123!` |
| `dave` | `ADMIN` | `Secret123!` |

Development credentials only; the stored values are BCrypt hashes.

Seeded tasks: `alice` is the assignee of tasks 1 and 5, `bob` of task 3; tasks 2 and 4 are
unassigned.

```bash
docker exec -it debuglab10-mysql mysql -uroot -prootpw tasksdb
```

```sql
SELECT u.username, r.name FROM app_users u
  JOIN user_roles ur ON ur.user_id = u.id
  JOIN roles r ON r.id = ur.role_id;
SELECT id, title, status, assignee FROM tasks;
```

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/tasksdb?...` | Host port from `docker-compose.yml` |
| `spring.datasource.username` / `.password` | `taskuser` / `taskpw` | **Placeholder credentials** — replace if you use your own MySQL |
| `logging.level.org.springframework.security` | `DEBUG` | Prints the authorities on each request and the rule that refused it |

## How to run

```bash
docker compose up -d
mvn clean package
java -jar target/task-manager-1.0.0.jar
```

```bash
mvn test          # requires the container to be up
```

## API endpoints

| Method | Path | Required role | Success |
|---|---|---|---|
| `GET` | `/api/tasks` | `USER`, `MANAGER` or `ADMIN` | `200` |
| `POST` | `/api/tasks` | `USER`, `MANAGER` or `ADMIN` | `201` |
| `PUT` | `/api/tasks/{id}/assign` | **`MANAGER` or `ADMIN`** | `200` |
| `DELETE` | `/api/tasks/{id}` | **`ADMIN`** | `204` |
| `GET` | `/api/admin/users` | **`ADMIN`** | `200` |
| `GET` | `/api/admin/audit` | **`ADMIN`** | `200` |

All endpoints require authentication. Anything not permitted returns `403 Forbidden`.

### Listing tasks

```bash
curl -u alice:'Secret123!' http://localhost:8080/api/tasks
```

**A `USER` sees only the tasks assigned to them.** A `MANAGER` or `ADMIN` sees every task. `alice`
therefore sees two tasks, `carol` sees five.

### Creating a task

```bash
curl -X POST http://localhost:8080/api/tasks \
  -u alice:'Secret123!' \
  -H "Content-Type: application/json" \
  -d '{"title":"Update the runbook","description":"After the MySQL upgrade"}'
```

### Assigning a task

```bash
curl -X PUT http://localhost:8080/api/tasks/2/assign \
  -u carol:'Secret123!' \
  -H "Content-Type: application/json" \
  -d '{"assignee":"bob"}'
```

`403` for a plain `USER`.

### Deleting a task

```bash
curl -i -X DELETE http://localhost:8080/api/tasks/4 -u dave:'Secret123!'
```

`403` for anyone who is not an `ADMIN`.

### Admin endpoints

```bash
curl -u dave:'Secret123!' http://localhost:8080/api/admin/users
curl -u dave:'Secret123!' http://localhost:8080/api/admin/audit
```

Both are `ADMIN`-only and both behave the same way for every other role: `403`.

## Expected functionality

* Every user can sign in and reach the endpoints their role allows.
* Every user is refused, with `403`, from everything their role does not allow.
* The two admin endpoints behave **identically** with respect to authorization — if one is reachable
  for a given user, so is the other.
* A `USER` listing tasks sees only their own.
* Removing the method-level annotation from a privileged service method would not open a hole,
  because the URL rules cover it too — and vice versa.
