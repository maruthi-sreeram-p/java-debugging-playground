# SOLUTION — 02 · Notification Dispatch Service

> **Sealed answer key.** Five planted defects.

---

## Defect 1 — `TemplateRenderer` lives outside the component-scan base package

* **Bug:** `TemplateRenderer` is `@Component`-annotated but declared in `com.debuglab.support.template`.
  `NotificationDispatchApplication` is in `com.debuglab.notifications`, so `@SpringBootApplication`
  scans `com.debuglab.notifications` and everything under it — and nothing else.
* **Affected component:** `NotificationDispatchApplication.java` (the scan root) together with
  `com/debuglab/support/template/TemplateRenderer.java` (the location).
* **Root cause:** `@SpringBootApplication` implies `@ComponentScan` with no `basePackages`, which
  defaults to the package of the annotated class. `com.debuglab.support` is a *sibling* of
  `com.debuglab.notifications`, not a child, so it is never scanned. The annotation on the class is
  irrelevant if nothing ever reads it.
* **Symptom:** `APPLICATION FAILED TO START` — "Parameter 3 of constructor in NotificationService
  required a bean of type 'com.debuglab.support.template.TemplateRenderer' that could not be found."
* **Why the symptom is misleading:** Spring Boot's suggested `Action` is "Consider defining a bean of
  type ... in your configuration", which nudges the reader toward writing an `@Bean` factory method.
  That would silence the error while leaving the real problem — an entire package invisible to the
  container — in place.
* **Correct fix (preferred):** widen the scan root to the shared parent package:

  ```java
  @SpringBootApplication(scanBasePackages = "com.debuglab")
  ```

  Equivalent acceptable fixes: an explicit `@ComponentScan({"com.debuglab.notifications", "com.debuglab.support"})`,
  or moving the application class up to `com.debuglab`. **Accept these.**
* **Fixes to push back on:** adding an `@Bean public TemplateRenderer templateRenderer()` factory
  method, or moving `TemplateRenderer` into `com.debuglab.notifications`. Both work, but the README
  states that `com.debuglab.support` is meant to hold reusable helpers, so both leave the next helper
  broken. Point this out rather than marking it wrong.
* **Concept:** component scanning; the default base package of `@SpringBootApplication`.
* **Why a fresher makes it:** they create a "shared" or "common" or "utils" package by typing a new
  package name in the IDE, and place it beside the application package rather than under it. The
  code compiles, the annotation looks right, and nothing warns them.
* **How to recognise it in the wild:** any "required a bean ... that could not be found" for a class
  you can see with your own eyes. First question: is that class under the scan root?

---

## Defect 2 — `@Primary` on `EmailNotificationChannel` captures both injection points

* **Bug:** `EmailNotificationChannel` is annotated `@Primary`. `NotificationService`'s constructor
  declares two parameters of the same interface type:

  ```java
  public NotificationService(NotificationChannel emailChannel,
                             NotificationChannel smsChannel, ...)
  ```

  Both receive `EmailNotificationChannel`.
* **Affected component:** `channel/EmailNotificationChannel.java` (the `@Primary`) and
  `service/NotificationService.java` (the unqualified injection points).
* **Root cause:** when several beans match a dependency by type, Spring resolves the ambiguity in
  `DefaultListableBeanFactory.determineAutowireCandidate`, which checks for a `@Primary` candidate
  **first**. Only if there is no primary does it fall back to matching the bean name against the
  parameter name. Because `EmailNotificationChannel` is primary, the name `smsChannel` is never
  consulted. `resolveChannel("SMS")` returns the field called `smsChannel`, which holds the email
  bean, so the SMS path delivers over email and records `channel = "EMAIL"`.
* **Why the symptom is misleading:**
  * Nothing fails. The notification is delivered and stored as `DELIVERED`.
  * The parameter is *named* `smsChannel`, so the code reads as though it is obviously correct. Many
    developers believe Spring always falls back to the parameter name; it does, but only when no
    primary exists — so the same code would work if `@Primary` were removed.
  * `SmsNotificationChannel` is a perfectly healthy bean that is simply never used, so inspecting it
    reveals nothing.
* **Correct fix:** make the choice explicit at the injection point. Any of:

  ```java
  public NotificationService(@Qualifier("emailNotificationChannel") NotificationChannel emailChannel,
                             @Qualifier("smsNotificationChannel") NotificationChannel smsChannel, ...)
  ```

  or, better for extensibility, inject all channels and key them by their own `channelName()`:

  ```java
  public NotificationService(List<NotificationChannel> channels, ...) {
      this.channels = channels.stream()
              .collect(Collectors.toMap(NotificationChannel::channelName, c -> c));
  }
  ```

  Removing `@Primary` also fixes it (name-based fallback then applies), but it is the most fragile
  fix — it depends on parameter names surviving compilation and on nobody re-adding `@Primary`.
  Prefer the qualifier or the collection injection.
* **Concept:** autowiring resolution order — type match, then `@Primary`, then `@Priority`, then bean
  name versus parameter name.
* **Why a fresher makes it:** they add `@Primary` to silence an earlier
  `NoUniqueBeanDefinitionException` — which is exactly the advice most search results give — without
  realising it now answers *every* injection point of that type, including ones that wanted the
  other bean.
* **How to recognise it in the wild:** "the wrong implementation is running" with no error. Print the
  concrete class at start-up (`log.info("channel={}", smsChannel.getClass())`), or set
  `logging.level.org.springframework.beans.factory=DEBUG`, and compare what you got with what you
  asked for.

---

## Defect 3 — Prototype-scoped tracker injected into a singleton

* **Bug:** `DispatchAttemptTracker` is `@Component @Scope("prototype")`, but it is injected once
  through the constructor of the singleton `NotificationService`.
* **Affected component:** `service/DispatchAttemptTracker.java` and
  `service/NotificationService.java`.
* **Root cause:** scope governs what happens *when the bean is requested from the container*.
  `NotificationService` requests it exactly once, at start-up. That single instance is then reused
  for the entire life of the application, so its `attempts` field accumulates across every dispatch:
  1, 2, 3, ... This is the classic scoped-bean lifetime mismatch.
* **Why the symptom is misleading:** the very first request is correct (`attemptCount: 1`), so a
  developer who tests once concludes the feature works. It is also easy to blame the response mapping
  or the database rather than the object's lifetime.
* **Correct fix:** let the singleton obtain a fresh instance per use. Preferred:

  ```java
  private final ObjectProvider<DispatchAttemptTracker> attemptTrackerProvider;
  ...
  DispatchAttemptTracker tracker = attemptTrackerProvider.getObject();
  int attempt = tracker.recordAttempt();
  ```

  Also acceptable: `@Lookup` method injection; a scoped proxy
  (`@Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)`) — note this one still
  shares state within a single method call but resets between calls; injecting `ApplicationContext`
  and calling `getBean(...)` (works, but couples the service to the container). Simply making the
  tracker a plain `new DispatchAttemptTracker()` local variable is also a legitimate answer and
  arguably the best design — the object has no dependencies and does not need to be a bean at all.
  If the learner proposes that, agree with them.
* **Concept:** bean scopes; singleton/prototype lifetime mismatch; when scope is actually applied.
* **Why a fresher makes it:** they read that `prototype` means "a new instance every time" and take
  it to mean "every time it is used", rather than "every time it is requested from the container".
* **How to recognise it in the wild:** any per-request state that leaks between requests — a counter
  that only grows, a `StringBuilder` that accumulates, one user seeing another's data. Ask what the
  scope of the *holder* is, not of the bean.

---

## Defect 4 — `@Value` placeholder does not match the configured property key

* **Bug:**

  ```java
  @Value("${notification.retry.max-attempts:2}")
  private int maxAttempts;
  ```

  while `application.properties` defines `notification.retry.attempts=5`.
* **Affected component:** `service/NotificationService.java` and
  `src/main/resources/application.properties`.
* **Root cause:** `notification.retry.max-attempts` and `notification.retry.attempts` are different
  keys — this is not a spelling variation that relaxed binding can bridge. The placeholder therefore
  falls back to the literal default supplied after the colon, `2`, and the configured `5` is never
  read by anything.
* **Why the symptom is misleading:** the default value is what makes this silent. Without `:2` the
  application would refuse to start with
  `Could not resolve placeholder 'notification.retry.max-attempts'`, which would be trivially
  diagnosable. The defensive default converts a loud failure into a wrong answer, and the developer
  concludes "my configuration isn't being picked up" and starts suspecting profiles, packaging or
  the classpath.
* **Correct fix:** make the two agree. Either read the documented key:

  ```java
  @Value("${notification.retry.attempts:2}")
  ```

  or rename the property to `notification.retry.max-attempts=5`. The README documents
  `notification.retry.attempts`, so changing the Java side is the fix that honours the contract.
* **Concept:** property placeholder resolution; default values in `@Value`; relaxed binding and its
  limits.
* **Why a fresher makes it:** they rename a property in one place, or copy a `@Value` line from
  another class, and the default hides the mismatch. Adding "a sensible default" to every `@Value`
  is widely taught as good practice and quietly removes your best error detector.
* **How to recognise it in the wild:** "the config has no effect". Temporarily delete the default
  from the placeholder and restart — if it now fails to start, the key was never being found. The
  actuator `/env` endpoint answers the same question without editing code.

---

## Defect 5 — `NotificationStatsCalculator` is instantiated with `new`

* **Bug:**

  ```java
  public Map<String, Long> statistics() {
      NotificationStatsCalculator calculator = new NotificationStatsCalculator();
      return calculator.calculate();
  }
  ```

  `NotificationStatsCalculator` is a `@Component` whose repository is `@Autowired` **as a field**.
* **Affected component:** `service/NotificationService.java`; enabled by the field injection in
  `service/NotificationStatsCalculator.java`.
* **Root cause:** an object created with `new` never passes through the container, so no
  autowiring, no proxying and no lifecycle callbacks are applied. `notificationLogRepository` stays
  `null` and the first call throws `NullPointerException`.
* **Why the symptom is misleading:** the same repository works on the other two endpoints, so the
  repository bean is obviously fine. The stack trace's top frame points at
  `NotificationStatsCalculator`, which looks correctly annotated. The mistake is one frame *below*,
  in the caller.
* **Correct fix:** inject the calculator instead of constructing it:

  ```java
  private final NotificationStatsCalculator statsCalculator;   // constructor-injected

  public Map<String, Long> statistics() {
      return statsCalculator.calculate();
  }
  ```

  A strong additional answer: convert `NotificationStatsCalculator` to constructor injection, which
  would have made `new NotificationStatsCalculator()` a compile error in the first place. Credit this
  generously — it is the point of the defect.
* **Concept:** the container-managed lifecycle; why constructor injection is preferred over field
  injection.
* **Why a fresher makes it:** helper classes feel like utilities, and `new` is reflexive. Field
  injection lets it compile.
* **How to recognise it in the wild:** an NPE on a dependency that is unmistakably a Spring bean.
  Search the call path for `new` on that type. The same root cause explains `@Transactional`,
  `@Cacheable` and `@Async` silently doing nothing on a hand-constructed object — you will meet this
  again in projects 08 and 14.

---

## Suggested fix order

1. **Defect 1** — mandatory; nothing else is observable until the context starts.
2. Then 2, 3, 4 and 5 in any order; they are fully independent.

## Test expectations

All five tests fail with `IllegalStateException: Failed to load ApplicationContext` until Defect 1
is fixed. Afterwards:

| Test | Fails because of |
|---|---|
| `emailNotificationIsRenderedAndDelivered` | passes once the context loads |
| `smsNotificationUsesTheSmsChannel` | Defect 2 |
| `everyDispatchStartsFromAttemptNumberOne` | Defect 3 |
| `configuredRetryLimitIsReported` | Defect 4 |
| `statisticsEndpointReportsTotals` | Defect 5 |

`everyDispatchStartsFromAttemptNumberOne` is order-sensitive by design: it sends twice and asserts
the *second* response reports `1`.

## Verification commands

```bash
mvn test    # 5/5 green

curl -s -X POST http://localhost:8080/api/notifications -H 'Content-Type: application/json' \
  -d '{"type":"SMS","recipient":"+919876543210","subject":"OTP","message":"Code {{code}}","variables":{"code":"440199"}}'
# expect "channel":"SMS", "attemptCount":1, "maxAttempts":5
# expect the log line to come from SmsNotificationChannel

curl -s -X POST http://localhost:8080/api/notifications -H 'Content-Type: application/json' \
  -d '{"type":"EMAIL","recipient":"a@campus.edu","subject":"x","message":"y"}'
# expect "attemptCount":1 again, not 2

curl -s http://localhost:8080/api/notifications/stats
# expect 200 and counts consistent with GET /api/notifications
```
