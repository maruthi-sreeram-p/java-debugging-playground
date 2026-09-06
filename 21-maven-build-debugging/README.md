# Expense Reporting Service

A small expense-tracking API — record an expense, list them, and total a date range by category.

## Purpose

The service itself is deliberately modest: one entity, one repository, one service, one controller.
The interesting part of this project is the **build**. A Spring Boot application is not just the
code you wrote; it is the code you wrote plus the dependency tree Maven resolved, the scopes those
dependencies were given, the plugins that ran, and the resources that were filtered on the way into
the jar.

Every one of those can be wrong while your Java compiles perfectly, and the failure then shows up
somewhere that has nothing to do with the mistake — at start-up, in one endpoint, in the jar but not
in your IDE, or in the shape of a test report nobody read.

```
POST /api/expenses           record one expense
GET  /api/expenses           everything recorded, oldest first
GET  /api/expenses/summary   totals for a date range, per category and overall
GET  /api/meta               which build of the service is running
```

## Architecture

```
com.debuglab.expenses
├── ExpenseApplication
├── controller/ExpenseController    the four endpoints
├── service/ExpenseService          recording and the range summary
├── repository/ExpenseRepository    Spring Data JPA
├── entity/Expense
└── dto/  CreateExpenseRequest, ExpenseSummary
```

The build itself is worth reading as part of the architecture:

* `pom.xml` inherits from `spring-boot-starter-parent`, which supplies dependency versions, plugin
  versions, and resource filtering for `application*.properties` with `@` as the delimiter.
* `src/main/resources/application.properties` is filtered at build time so that the running service
  can report the Maven project version through `/api/meta`.
* `src/main/resources/data.sql` seeds six expenses from August so that the summary endpoint has
  something to add up on a fresh start.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* H2 in-memory database
* Maven, JUnit 5, MockMvc

No Docker and no external services. This project runs on its own.

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+

There is nothing to install and nothing to configure. The database is in memory and is recreated on
every start.

### Environment configuration

| Property | Value | Notes |
|---|---|---|
| `server.port` | `8080` | Change if something else is using it |
| `spring.datasource.url` | `jdbc:h2:mem:expensedb` | In-memory; no credentials of consequence |
| `app.build-version` | from the Maven project version | Stamped in at build time |

No credentials, no API keys, nothing external to sign up for.

## How to run

```bash
mvn clean package
```

```bash
java -jar target/expense-reporting-1.0.0.jar
```

You should see Tomcat report the port it is listening on, followed by
`Started ExpenseApplication in ... seconds`, and the process should stay up.

```bash
mvn test
```

Five tests cover recording, validation, the category breakdown, the range total and the build
metadata endpoint.

An alternative way to run it during development, which uses Maven's own classpath rather than the
packaged jar:

```bash
mvn spring-boot:run
```

## Useful build commands

The dependency tree and the effective POM are the two things worth knowing how to print:

```bash
mvn dependency:tree
```

```bash
mvn dependency:tree -Dincludes=com.fasterxml.jackson.datatype
```

```bash
mvn help:effective-pom
```

```bash
unzip -l target/expense-reporting-1.0.0.jar | head -40
```

The last one is the ground truth about what actually shipped: a Spring Boot executable jar contains
your classes under `BOOT-INF/classes` and every runtime dependency under `BOOT-INF/lib`.

## API endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| `POST` | `/api/expenses` | Record one expense | `201` |
| `GET` | `/api/expenses` | Everything recorded, oldest first | `200` |
| `GET` | `/api/expenses/summary?from={date}&to={date}` | Totals for the range | `200` |
| `GET` | `/api/meta` | Application name and build version | `200` |

### Recording an expense

```bash
curl -s -X POST http://localhost:8080/api/expenses \
  -H "Content-Type: application/json" \
  -d '{"description":"Stationery","category":"OFFICE","amount":640.00,"spentOn":"2026-08-22","submittedBy":"arjun"}'
```

```json
{
  "id": 7,
  "description": "Stationery",
  "category": "OFFICE",
  "amount": 640.00,
  "spentOn": "2026-08-22",
  "submittedBy": "arjun"
}
```

An amount of zero or less, or any missing field, is rejected with `400`.

### Summarising a month

```bash
curl -s "http://localhost:8080/api/expenses/summary?from=2026-08-01&to=2026-08-31"
```

```json
{
  "from": "2026-08-01",
  "to": "2026-08-31",
  "expenseCount": 6,
  "totalByCategory": {
    "MEALS": 7430.00,
    "TRAVEL": 12630.00,
    "LODGING": 9600.00,
    "TRAINING": 15000.00
  },
  "grandTotal": 44660.00
}
```

`grandTotal` is the sum of every expense in the range, and therefore also the sum of the values in
`totalByCategory`.

### Which build is running

```bash
curl -s http://localhost:8080/api/meta
```

```json
{ "application": "expense-reporting", "buildVersion": "1.0.0" }
```

`buildVersion` is the `<version>` from `pom.xml`, substituted into the properties file when the jar
was built. It is how you tell two deployments apart.

## Expected functionality

* `mvn clean package` produces a jar that `java -jar` runs, and the process stays up serving HTTP.
* `mvn test` runs five tests and reports on each of them.
* Dates appear in JSON as `"2026-08-22"`, in requests and in responses.
* `/api/meta` reports the real project version.
* `grandTotal` equals the sum of `totalByCategory`.
* The application behaves the same whether it is started with `mvn spring-boot:run` or from the
  packaged jar.
