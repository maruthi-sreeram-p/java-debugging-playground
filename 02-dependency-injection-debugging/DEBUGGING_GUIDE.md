# Debugging Guide — 02 · Notification Dispatch Service

> Symptoms and graded hints only. No answers, no file names, no line numbers.
> Read only as far down each hint ladder as you need.

---

## Project objective

Everything in this project is about **how Spring decides which object to give you**. The business
logic — render a template, call a channel, save a row — is only a few lines and is not where the
problems are. The problems are in the wiring: which beans exist, which bean satisfies which
dependency, who created the object you are calling, and how long it lives.

Your job is to make the service behave as `README.md` documents it.

## Expected behaviour

1. The application starts.
2. `POST /api/notifications` with `"type":"EMAIL"` is delivered by the email channel and the
   response says `"channel":"EMAIL"`.
3. `POST /api/notifications` with `"type":"SMS"` is delivered by the SMS channel and the response
   says `"channel":"SMS"`.
4. Every dispatch is counted on its own, so every response says `"attemptCount":1`.
5. `"maxAttempts"` in the response equals whatever `notification.retry.attempts` is set to in
   `application.properties`.
6. `GET /api/notifications/stats` returns the totals.

## How to reproduce

```bash
mvn clean package
java -jar target/notification-dispatch-1.0.0.jar
```

Then work through the `curl` commands in the README. Watch the **application log** as well as the
HTTP responses — each channel announces itself when it delivers, and that log line is independent
evidence of what actually happened.

`mvn test` currently fails entirely. That is expected while Symptom A is unresolved: none of the
tests can run until the application context can be built.

---

## Known symptoms

### Symptom A — the application does not start at all

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Parameter 3 of constructor in com.debuglab.notifications.service.NotificationService required a
bean of type 'com.debuglab.support.template.TemplateRenderer' that could not be found.

Action:

Consider defining a bean of type 'com.debuglab.support.template.TemplateRenderer' in your
configuration.
```

The class exists. It is annotated. It compiles. Spring still says it cannot find it.

Everything below is only observable once the application starts.

### Symptom B — SMS notifications are delivered as email

```
POST /api/notifications   {"type":"SMS","recipient":"+919876543210", ...}

201 Created
{"id":2,"channel":"EMAIL","recipient":"+919876543210", ...}
```

And in the application log:

```
c.d.n.channel.EmailNotificationChannel : [EMAIL] from=alerts@campus.edu to=+919876543210 subject='OTP' bytes=11
```

The service is emailing a phone number. `SmsNotificationChannel` never logs anything — not once, for
any request. No exception is thrown and the notification is recorded as `DELIVERED`.

### Symptom C — the attempt counter keeps climbing

Three consecutive sends:

```
{"id":1, ..., "attemptCount":1}
{"id":2, ..., "attemptCount":2}
{"id":3, ..., "attemptCount":3}
```

Each of those is a separate notification that succeeded on its first attempt. The first request
looks correct; every request after it does not.

### Symptom D — the configured retry limit is ignored

`application.properties` contains:

```
notification.retry.attempts=5
```

Every response says:

```
"maxAttempts": 2
```

Changing the value in `application.properties` and restarting changes nothing. The application does
not complain about the property either way.

### Symptom E — the statistics endpoint always fails

```
GET /api/notifications/stats   ->   500 Internal Server Error
```

```
java.lang.NullPointerException: Cannot invoke
  "com.debuglab.notifications.repository.NotificationLogRepository.count()"
  because "this.notificationLogRepository" is null
```

The repository works perfectly on the other two endpoints, and the class that throws is annotated as
a Spring component with the field marked for injection.

---

## Investigation hints

### Symptom A — start-up failure

> **Hint A1**
> Spring's suggested action ("consider defining a bean") is a generic template, not a diagnosis.
> Resist it. The class already declares itself a bean. So the question is not *"is it a bean?"* —
> it is *"was Spring ever asked to look at that class?"*

> **Hint A2**
> Which packages does a Spring Boot application examine for components, and how is that set
> determined? Compare the package of the class that could not be found with the package of the class
> annotated `@SpringBootApplication`.

> **Hint A3**
> There is more than one correct fix, and they are not equally good. Adding an `@Bean` method
> somewhere would make the error go away without addressing why the class was invisible — and the
> next helper anyone adds to that package will fail the same way.

### Symptom B — SMS delivered by the email channel

> **Hint B1**
> Two beans implement one interface. Look at what the consumer asks for, and ask yourself what
> information Spring actually has to choose between them at injection time.

> **Hint B2**
> A constructor parameter has a *type* and a *name*. Spring uses the type first. It only falls back
> to matching on the name in a specific circumstance. Find out what that circumstance is — and then
> check whether it applies here.

> **Hint B3**
> One of the two implementations carries an extra annotation that the other does not. What exactly
> does that annotation do when Spring is choosing between candidates? Does it apply per injection
> point, or globally?

> **Hint B4**
> Once you understand the cause, there are three standard ways to make the choice explicit at the
> injection point. Any of them fixes this. Pick the one that would still be correct if a third
> channel were added tomorrow.

### Symptom C — the climbing attempt counter

> **Hint C1**
> How many instances of the counter object exist over the lifetime of the application, and how many
> *should* exist?

> **Hint C2**
> The counter class declares a scope. Read what that scope guarantees — and, more importantly, when
> that guarantee is applied. A scope decides what happens when the bean is *requested from the
> container*. How many times is this one requested?

> **Hint C3**
> A short-lived bean injected once into a long-lived bean is short-lived in name only. What is the
> lifetime of the object holding the reference?

> **Hint C4**
> Spring offers several mechanisms for a singleton to obtain a fresh instance on each use. Look for
> the one that lets the singleton *ask for* an instance rather than being handed one at construction
> time.

### Symptom D — the ignored property

> **Hint D1**
> Nothing failed. That is the clue. When a placeholder cannot be resolved at all, start-up breaks
> loudly. This one did not break — so it *was* resolved. To what, and from where?

> **Hint D2**
> Read the placeholder expression character by character and compare it with the key in
> `application.properties`. Then read the part of the expression that comes after the colon.

> **Hint D3**
> Relaxed binding (the rule that lets `maxAttempts` match `max-attempts`) works between different
> *spellings* of the same key. It does not invent a key that was never written. Are these two keys
> the same key spelled differently, or two different keys?

### Symptom E — the null repository

> **Hint E1**
> The stack trace names the exact field that is null. Field injection populates a field when the
> *container* builds the object. So: who built this object?

> **Hint E2**
> Read the method in the service layer that calls the failing class, one line at a time. There is a
> Java keyword in it that guarantees Spring was not involved.

> **Hint E3**
> An object created outside the container has no injection, no proxying, no lifecycle callbacks and
> no transaction support. This is why field injection is dangerous: the same mistake made against a
> constructor-injected class would not have compiled.

---

## Expected logs and observations

* The start-up failure block is printed by Spring Boot's failure analyser and is the whole of
  Symptom A. Read the `Description`, ignore the `Action`.
* For Symptom B, the decisive evidence is the logger name on the delivery line —
  `EmailNotificationChannel` versus `SmsNotificationChannel` — not the JSON response.
* Symptoms C and D produce no log output and no exception at all. They are visible only by comparing
  responses against the README.
* For Symptom E, read the *whole* stack trace, from the `NullPointerException` message down through
  the three frames beneath it. The frame that matters is not the one that threw.
* Setting `logging.level.org.springframework.beans.factory=DEBUG` will show you bean creation order
  and autowiring decisions if you want to watch the container work.

## Difficulty

**Beginner**, but a step up from project 01: nothing here is visible from the HTTP layer alone.
Expect 45–90 minutes.

Symptom A blocks everything else — fix it first. Symptoms B, C, D and E are independent of one
another.

## Concepts being tested

* Component scanning and how a Spring Boot application decides which packages to examine
* Autowiring by type versus by name, and how ambiguity between candidate beans is resolved
* Bean scopes and the lifetime mismatch between a long-lived and a short-lived bean
* Property placeholder resolution and default values
* Constructor injection versus field injection, and what is lost when an object is created outside
  the container
* Reading Spring Boot start-up failure analysis and multi-frame stack traces

## When you think you are done

- [ ] `mvn test` is green (5 tests).
- [ ] `SmsNotificationChannel` appears in the log when, and only when, `"type":"SMS"` is sent.
- [ ] Ten consecutive sends all report `"attemptCount":1`.
- [ ] Setting `notification.retry.attempts=9` and restarting makes the API report `"maxAttempts":9`.
- [ ] `GET /api/notifications/stats` returns counts that match `GET /api/notifications`.
- [ ] You fixed Symptom A in a way that would also work for the *next* class added to
      `com.debuglab.support`.

Then say **"I think I fixed the project"**.
