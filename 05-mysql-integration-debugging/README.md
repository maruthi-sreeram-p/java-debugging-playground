# Employee Directory API

An HR service backed by MySQL. It holds the company's employee records and is the source of truth
that the payroll export and the internal org chart both read from.

## Purpose

Unlike the earlier projects in this lab, this application does **not** own its schema. The
`employees` table already exists — it was created by the DBA team and is described in
`db/init.sql`. The application is mapped onto that table and must live with the column names it
finds there.

## Architecture

```
com.debuglab.hr
├── EmployeeDirectoryApplication
├── controller/EmployeeController      HTTP layer
├── service/
│   ├── EmployeeService                directory rules, transactional writes
│   └── AuditService                   writes an audit row for every change,
│                                      in its own transaction so that the audit
│                                      trail survives even if the change is rolled back
├── repository/
│   ├── EmployeeRepository             derived queries + one hand-written native query
│   └── AuditEntryRepository
├── entity/
│   ├── Employee                       mapped onto the pre-existing employees table
│   └── AuditEntry
├── dto/EmployeeDto
└── exception/                         domain exceptions + @RestControllerAdvice
```

The audit trail is deliberately written in a **separate transaction** (`REQUIRES_NEW`): if creating
an employee fails halfway through and rolls back, we still want a record that the attempt was made.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* Hibernate 6.5
* MySQL 8.0 (via Docker Compose)
* HikariCP connection pool
* Maven, JUnit 5, MockMvc

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+
* Docker Desktop (or any Docker engine with Compose v2)

### Database setup

The database runs in a container. From the project directory:

```bash
docker compose up -d
```

This starts MySQL 8.0 and, on first start only, runs `db/init.sql` to create the `employees` table
and seed it with eight employees.

**Port note.** The container publishes MySQL on **host port 3307**, not 3306, so that it cannot
clash with a MySQL server you already have installed. The application's JDBC URL points at 3307.

Check the data at any time:

```bash
docker exec -it debuglab05-mysql mysql -uroot -prootpw hrdb
```

```sql
SHOW TABLES;
SELECT id, first_name, department FROM employees;
```

To wipe everything and start over (this destroys the volume and re-runs `init.sql`):

```bash
docker compose down -v && docker compose up -d
```

### Environment configuration

| Property (`src/main/resources/application.properties`) | Value | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/hrdb?...` | Host port from `docker-compose.yml` |
| `spring.datasource.username` | `hruser` | **Placeholder credential.** Defined in `docker-compose.yml`. Replace both if you point at your own MySQL. |
| `spring.datasource.password` | `hrpw` | As above. Nothing in this repository is a real credential. |
| `spring.datasource.hikari.maximum-pool-size` | `1` | Connection pool size |
| `spring.datasource.hikari.connection-timeout` | `10000` | How long a caller waits for a free connection |
| `spring.jpa.hibernate.ddl-auto` | `create-drop` | Schema management strategy |

There is also an `application-docker.properties` profile, used when the application itself is run
inside the compose network (where MySQL is reachable as `mysql:3306`). Activate it with
`SPRING_PROFILES_ACTIVE=docker`. You do not need it for local development.

## How to run

```bash
docker compose up -d          # once, and leave it running
mvn clean package
java -jar target/employee-directory-1.0.0.jar
```

or

```bash
mvn spring-boot:run
```

Tests require the container to be up:

```bash
docker compose up -d
mvn test
```

## API endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| `GET` | `/api/employees` | The whole directory | `200` |
| `GET` | `/api/employees/{id}` | One employee | `200`, `404` if absent |
| `GET` | `/api/employees/by-department?department=` | Everyone in a department, case-insensitive, ordered by first name | `200` |
| `POST` | `/api/employees` | Add an employee | `201`, `409` on duplicate email |
| `PUT` | `/api/employees/{id}` | Replace an employee | `200`, `404` if absent |

### Employee representation

```json
{
  "id": 1,
  "firstName": "Aarav",
  "lastName": "Sharma",
  "email": "aarav.sharma@company.com",
  "department": "Engineering",
  "designation": "Senior Engineer",
  "salary": 1850000.00,
  "dateOfJoining": "2021-06-14",
  "active": true
}
```

### Expected functionality

```bash
# the eight seeded employees
curl http://localhost:8080/api/employees

# one of them
curl http://localhost:8080/api/employees/1

# the three engineers, case-insensitive on department
curl "http://localhost:8080/api/employees/by-department?department=engineering"
curl "http://localhost:8080/api/employees/by-department?department=Engineering"

# add one
curl -X POST http://localhost:8080/api/employees \
  -H "Content-Type: application/json" \
  -d '{"firstName":"Nisha","lastName":"Verma","email":"nisha.verma@company.com","department":"Engineering","designation":"Engineer","salary":1300000,"dateOfJoining":"2026-03-01"}'

# update one
curl -X PUT http://localhost:8080/api/employees/2 \
  -H "Content-Type: application/json" \
  -d '{"firstName":"Divya","lastName":"Nair","email":"divya.nair@company.com","department":"Engineering","designation":"Senior Engineer","salary":1600000,"dateOfJoining":"2023-01-09","active":true}'
```

Every listing endpoint reads the same table, so their answers are always consistent with each other
and with what you see in a MySQL shell. Records you create survive a restart of the application —
the database, not the application, owns the data. Each successful write also adds a row to
`audit_entry`.
