# Notification Dispatch Service

A small service that sends notifications to students and staff over one of several channels, renders
the message from a template, and keeps an audit log of everything that was sent.

## Purpose

Other campus systems do not talk to the email server or the SMS gateway directly. They post a
notification request to this service, which picks the right delivery channel, fills in the template
placeholders, delivers the message and records the outcome. The delivery log is the single place to
answer "did the student actually get told?".

## Architecture

```
com.debuglab.notifications
├── NotificationDispatchApplication     application entry point
├── controller/NotificationController   HTTP layer
├── service/
│   ├── NotificationService             orchestration: resolve channel, render, deliver, record
│   ├── NotificationStatsCalculator     aggregates the delivery log
│   └── DispatchAttemptTracker          counts the delivery attempts of one dispatch
├── channel/
│   ├── NotificationChannel             the delivery abstraction
│   ├── EmailNotificationChannel        default channel
│   └── SmsNotificationChannel          gateway channel
├── repository/NotificationLogRepository
├── entity/NotificationLog              audit row per delivery
└── dto/                                request and response shapes

com.debuglab.support.template
└── TemplateRenderer                    shared helper: renders {{placeholders}}
```

`com.debuglab.support` holds helpers that are meant to be reusable across our services — they are
deliberately kept outside the notification-specific packages.

Adding a channel means writing one more `NotificationChannel` implementation; nothing else in the
service is supposed to change.

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

No message broker, mail server or SMS provider is required. Both channels are stubbed: they log the
delivery and report success, so you can run the whole service locally with nothing installed.

### Database setup

Nothing to install. Hibernate creates the `notification_log` table in an in-memory H2 database at
start-up. Inspect it at <http://localhost:8080/h2-console> with JDBC URL `jdbc:h2:mem:notifydb`,
user `sa`, blank password.

### Environment configuration

All settings live in `src/main/resources/application.properties`.

| Property | Default | Meaning |
|---|---|---|
| `notification.retry.attempts` | `5` | How many delivery attempts a single notification may use before it is recorded as `FAILED` |
| `notification.retry.backoff-millis` | `200` | Pause between attempts |
| `notification.email.sender` | `alerts@campus.edu` | The `From` address stamped on outgoing mail |
| `notification.sms.gateway` | `REPLACE_WITH_YOUR_GATEWAY_ID` | **Placeholder.** Put your own SMS provider's gateway id here. Nothing in this repository contains real provider credentials, and the stub channel ignores the value. |
| `server.port` | `8080` | HTTP port |

There are no environment variables and no secrets in the repository. If you connect a real provider,
its credentials go in the two properties above (or an `application-local.properties` you keep out of
version control) — never in code.

## How to run

```bash
mvn spring-boot:run
```

or

```bash
mvn clean package
java -jar target/notification-dispatch-1.0.0.jar
```

Run the tests with:

```bash
mvn test
```

## API endpoints

| Method | Path | Description | Success status |
|---|---|---|---|
| `POST` | `/api/notifications` | Send a notification | `201 Created` |
| `GET` | `/api/notifications` | Delivery history, newest first | `200 OK` |
| `GET` | `/api/notifications/stats` | Aggregate counts by channel and status | `200 OK` |

### Send a notification

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
        "type": "EMAIL",
        "recipient": "aarav.sharma@campus.edu",
        "subject": "Fee reminder",
        "message": "Hello {{name}}, your fee of {{amount}} is due.",
        "variables": { "name": "Aarav", "amount": "12500" }
      }'
```

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
        "type": "SMS",
        "recipient": "+919876543210",
        "subject": "OTP",
        "message": "Your code is {{code}}",
        "variables": { "code": "440199" }
      }'
```

`type` is `EMAIL` or `SMS`. Anything else falls back to `EMAIL`. `variables` is optional; any
`{{placeholder}}` in `message` with no matching variable is replaced with an empty string.

Response:

```json
{
  "id": 1,
  "channel": "EMAIL",
  "recipient": "aarav.sharma@campus.edu",
  "subject": "Fee reminder",
  "renderedBody": "Hello Aarav, your fee of 12500 is due.",
  "status": "DELIVERED",
  "attemptCount": 1,
  "maxAttempts": 5,
  "createdAt": "2026-01-01T10:00:00"
}
```

* `channel` — the channel that actually delivered the message.
* `attemptCount` — how many attempts *this* notification needed. A notification that succeeds
  immediately reports `1`.
* `maxAttempts` — the configured `notification.retry.attempts` value, echoed back so callers can see
  the effective policy.

### History

```bash
curl http://localhost:8080/api/notifications
```

### Statistics

```bash
curl http://localhost:8080/api/notifications/stats
```

```json
{ "total": 12, "email": 9, "sms": 3, "delivered": 12, "failed": 0 }
```

## Expected functionality

* An `EMAIL` request is handled by `EmailNotificationChannel` and logged with channel `EMAIL`.
* An `SMS` request is handled by `SmsNotificationChannel` and logged with channel `SMS`.
* Each channel writes one line to the application log naming itself, so the log and the stored
  record always agree.
* Template placeholders are substituted before delivery, and the substituted text is what gets
  stored and returned.
* Every notification is counted independently: two consecutive sends both report `attemptCount: 1`.
* Changing `notification.retry.attempts` in `application.properties` changes the `maxAttempts` value
  the API reports.
* The statistics endpoint returns totals for every notification recorded so far.
