# Library Catalogue API

A small REST service for a departmental library: keep the catalogue, lend books to members and take
them back again.

## Purpose

The library counter staff and the student self-service kiosk both talk to this API. Because two
clients share it, the **error contract matters as much as the happy path**: the kiosk decides what
to show the student purely from the HTTP status code it gets back, and the counter application logs
the `message` field for the librarian.

## Architecture

```
com.debuglab.library
├── LibraryCatalogueApplication
├── controller/
│   ├── BookController              catalogue endpoints
│   └── BorrowController            lending endpoints
├── service/
│   ├── BookService                 catalogue rules
│   └── BorrowService               lending rules, transactional
├── repository/
│   ├── BookRepository
│   └── BorrowRecordRepository
├── entity/
│   ├── Book                        title, author, total and available copies
│   └── BorrowRecord                who borrowed what, and whether it came back
├── dto/
│   ├── BookDto, BorrowRequest, BorrowResponse
│   └── ApiError                    the single error shape every failure uses
└── exception/
    ├── BookNotFoundException, BookOutOfStockException,
    ├── BorrowRecordNotFoundException, AlreadyReturnedException,
    ├── DuplicateIsbnException
    └── GlobalExceptionHandler      turns exceptions into HTTP responses
```

The design rule is that **services throw and controllers do not catch**. A service signals a
business problem by throwing a domain exception; the single global handler is the only place that
decides what HTTP status that exception deserves. Controllers stay free of `try`/`catch`.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa, validation)
* Hibernate 6.5
* H2 in-memory database
* Maven
* JUnit 5 + MockMvc

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+

### Database setup

Nothing to install. Hibernate creates `books` and `borrow_records` in an in-memory H2 database and
`src/main/resources/data.sql` seeds five titles. Note that *Spring in Action* is seeded with all of
its copies already on loan, so it is the title to use when you want to exercise the out-of-stock
path.

Inspect the tables at <http://localhost:8080/h2-console>, JDBC URL `jdbc:h2:mem:librarydb`, user
`sa`, blank password.

### Environment configuration

Everything is in `src/main/resources/application.properties`. There are no environment variables and
no credentials. If you point the application at a real database, replace
`spring.datasource.url`, `.username` and `.password` with your own values.

## How to run

```bash
mvn spring-boot:run
```

or

```bash
mvn clean package
java -jar target/library-catalogue-1.0.0.jar
```

```bash
mvn test
```

## API endpoints

| Method | Path | Description | Success | Failure |
|---|---|---|---|---|
| `GET` | `/api/books` | List the catalogue | `200` | — |
| `GET` | `/api/books/{id}` | One book | `200` | `404` if no such book |
| `POST` | `/api/books` | Add a title | `201` | `400` invalid body, `409` ISBN already present |
| `POST` | `/api/borrow` | Lend a copy | `201` | `404` no such book, `409` no copies available |
| `POST` | `/api/return/{borrowId}` | Take a copy back | `200` | `404` no such record, `409` already returned |
| `GET` | `/api/borrow/history?member=` | Lending history for a member | `200` | — |

### Error contract

Every failure — validation, business rule or unexpected — returns the same shape:

```json
{
  "timestamp": "2026-01-01T10:00:00",
  "status": 404,
  "error": "Not Found",
  "message": "No book exists with id 999"
}
```

Validation failures add a `fields` object and use status `400`:

```json
{
  "timestamp": "2026-01-01T10:00:00",
  "status": 400,
  "error": "Validation Failed",
  "fields": { "title": "title is required" }
}
```

**The HTTP status line and the `status` field in the body always agree.** Clients are allowed to
rely on either one.

### Expected functionality

**List and fetch**

```bash
curl http://localhost:8080/api/books
curl -i http://localhost:8080/api/books/1
curl -i http://localhost:8080/api/books/999          # 404
```

**Add a book**

```bash
curl -i -X POST http://localhost:8080/api/books \
  -H "Content-Type: application/json" \
  -d '{"isbn":"978-0201633610","title":"Design Patterns","author":"Gamma et al","totalCopies":2}'
```

```bash
# blank title -> 400 with a fields object
curl -i -X POST http://localhost:8080/api/books \
  -H "Content-Type: application/json" \
  -d '{"isbn":"111","title":"","author":"X","totalCopies":2}'

# ISBN already in the catalogue -> 409
curl -i -X POST http://localhost:8080/api/books \
  -H "Content-Type: application/json" \
  -d '{"isbn":"978-0132350884","title":"Clean Code","author":"Robert C. Martin","totalCopies":2}'
```

**Borrow**

```bash
# available -> 201, availableCopies drops by one
curl -i -X POST http://localhost:8080/api/borrow \
  -H "Content-Type: application/json" \
  -d '{"bookId":1,"memberName":"Priya Iyer"}'

# no such book -> 404
curl -i -X POST http://localhost:8080/api/borrow \
  -H "Content-Type: application/json" \
  -d '{"bookId":999,"memberName":"Priya Iyer"}'

# every copy already lent (book 4) -> 409
curl -i -X POST http://localhost:8080/api/borrow \
  -H "Content-Type: application/json" \
  -d '{"bookId":4,"memberName":"Rohan Mehta"}'
```

**Return**

```bash
# valid record -> 200 with returnedAt set and availableCopies incremented
curl -i -X POST http://localhost:8080/api/return/1

# no such record -> 404
curl -i -X POST http://localhost:8080/api/return/999

# returning the same record twice -> 409 on the second call
curl -i -X POST http://localhost:8080/api/return/1
```
