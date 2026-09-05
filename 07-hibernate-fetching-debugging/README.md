# Course Enrolment API

A read-mostly service over a course catalogue: courses contain modules, modules contain lessons,
students enrol on courses. It powers the course browser, the student transcript page and the nightly
catalogue report.

## Purpose

The catalogue is a deep object graph — `Course → CourseModule → Lesson` — and different consumers
need different amounts of it. The course browser wants a list with a module count. The course page
wants everything. The transcript wants a student's courses. The report wants totals across the whole
catalogue.

Serving each of those efficiently, from the same set of entities, is the job of this service.

## Architecture

```
com.debuglab.enrolment
├── EnrolmentApplication
├── controller/
│   ├── CourseController        catalogue endpoints
│   ├── StudentController       transcript
│   └── ReportController        nightly catalogue report
├── service/
│   ├── CourseService           list, detail, summary, paged
│   ├── TranscriptService       a student's courses and credit total
│   └── CatalogueReportService  aggregation, pushed onto a worker thread
├── repository/
│   ├── CourseRepository        a fetch-join query and an interface projection
│   └── StudentRepository
├── entity/
│   ├── Course                  has many CourseModules, many-to-many with Student
│   ├── CourseModule            belongs to a Course, has many Lessons
│   ├── Lesson                  belongs to a CourseModule
│   └── Student                 many-to-many with Course, owns the `enrolments` join table
├── dto/                        response shapes + a CourseSummary projection interface
└── exception/
```

Two configuration choices shape everything in this project, and both are deliberate:

* **`spring.jpa.open-in-view=false`.** The persistence context closes when the service method
  returns, not when the HTTP response is written. Each endpoint is responsible for loading
  everything it needs while it still can. This is the setting most teams move to once they
  understand what the default costs them.
* **`hibernate.generate_statistics=true` and `show-sql=true`.** Every statement is printed and
  Hibernate reports query counts and timings. In a project about fetching, the SQL log is the
  primary instrument — treat it as part of the API.

## Technologies

* Java 17
* Spring Boot 3.3.5 (web, data-jpa)
* Hibernate 6.5
* H2 in-memory database
* Maven, JUnit 5, MockMvc

## Setup

### Prerequisites

* JDK 17 or newer
* Maven 3.8+

No Docker, no database server. Everything runs in-memory.

### Database setup

Hibernate creates the schema at start-up and `src/main/resources/data.sql` seeds it with 5 courses,
13 modules, 26 lessons, 3 students and 7 enrolments.

Browse it at <http://localhost:8080/h2-console> — JDBC URL `jdbc:h2:mem:enrolmentdb`, user `sa`,
blank password.

### Environment configuration

All settings live in `src/main/resources/application.properties`. No environment variables, no
credentials, no external services.

| Property | Value | Why it matters here |
|---|---|---|
| `spring.jpa.open-in-view` | `false` | The persistence context closes with the service method |
| `spring.jpa.show-sql` | `true` | Every statement is printed |
| `spring.jpa.properties.hibernate.generate_statistics` | `true` | Query counts and timings |

## How to run

```bash
mvn clean package
java -jar target/course-enrolment-1.0.0.jar
```

```bash
mvn test
```

## API endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| `GET` | `/api/courses` | Catalogue listing: code, title, credits, module count | `200` |
| `GET` | `/api/courses/{id}` | One course with all its modules and lessons | `200`, `404` |
| `GET` | `/api/courses/{id}/summary` | Title plus module and lesson counts | `200`, `404` |
| `GET` | `/api/courses/paged?page=0&size=2` | The same detail view, a page at a time | `200` |
| `GET` | `/api/students/{id}/transcript` | A student's courses and total credits | `200`, `404` |
| `GET` | `/api/reports/catalogue` | Totals across the whole catalogue | `200` |

### Catalogue listing

```bash
curl http://localhost:8080/api/courses
```

```json
[ { "id": 1, "code": "CS101", "title": "Introduction to Programming", "credits": 4, "moduleCount": 3 }, ... ]
```

The listing needs four columns and a count. It should not need to read the lesson table.

### Course detail

```bash
curl http://localhost:8080/api/courses/1
```

```json
{
  "id": 1, "code": "CS101", "title": "Introduction to Programming", "credits": 4,
  "modules": [
    { "id": 1, "title": "Getting Started", "position": 1,
      "lessons": [ { "id": 1, "title": "Hello World", "durationMinutes": 25 }, ... ] },
    ...
  ]
}
```

### Summary

```bash
curl http://localhost:8080/api/courses/1/summary
```

```json
{ "title": "Introduction to Programming", "moduleCount": 3, "lessonCount": 6 }
```

Computed by the database in a single aggregate query — no entities are loaded.

### Paged detail

```bash
curl "http://localhost:8080/api/courses/paged?page=0&size=2"
curl "http://localhost:8080/api/courses/paged?page=1&size=2"
```

Two courses per page, each with its modules loaded in the same query.

### Transcript

```bash
curl http://localhost:8080/api/students/1/transcript
```

```json
{ "studentId": 1, "studentName": "Aarav Sharma", "totalCredits": 11, "courses": [ ... ] }
```

### Catalogue report

```bash
curl http://localhost:8080/api/reports/catalogue
```

```json
{ "courses": [ { "code": "CS101", "title": "...", "modules": 3, "minutes": 193 }, ... ],
  "totalCourses": 5, "totalModules": 13, "totalMinutes": 843 }
```

The aggregation is handed to a worker thread so a large catalogue does not tie up the request
thread.

## Expected functionality

* Every endpoint returns `200` with the shape documented above.
* The catalogue listing issues a small, constant number of queries regardless of how many courses
  exist — adding a sixth course must not add queries.
* The paged endpoint reads only the page it was asked for.
* The summary endpoint answers from one aggregate query.
* The report totals match the seed data: 5 courses, 13 modules, 843 minutes.
