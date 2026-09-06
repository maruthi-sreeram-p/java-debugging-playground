# Debugging Guide — 21 · Expense Reporting Service

> Symptoms and graded hints. No answers, no file names, no line numbers.

---

## Project objective

Every other project in this laboratory asks you to debug Java. This one asks you to debug the
**build**, and the reason it exists is that build problems do not look like build problems.

They look like this:

* the application starts and then simply ends, with no error and an exit code of `0`;
* the jar will not run at all, while the same code runs perfectly in your IDE;
* the driver that is obviously on the classpath is not on the classpath;
* an endpoint returns `500` because of something in a `.properties` file that Maven should have
  rewritten;
* the build is green and no test has run for weeks.

Not one of those is a Java problem, and none of them will be solved by reading `ExpenseService`.

Three commands are worth more than any amount of staring at code here:

```bash
mvn dependency:tree
```

```bash
mvn help:effective-pom
```

```bash
unzip -l target/expense-reporting-1.0.0.jar
```

The last one is the only real ground truth: it shows what actually shipped.

## Expected behaviour

```bash
mvn clean package
java -jar target/expense-reporting-1.0.0.jar
```

Tomcat reports port 8080, the process stays up, and:

```bash
curl -s "localhost:8080/api/expenses/summary?from=2026-08-01&to=2026-08-31"
```

```json
{ "expenseCount": 6,
  "totalByCategory": {"MEALS":7430.00,"TRAVEL":12630.00,"LODGING":9600.00,"TRAINING":15000.00},
  "grandTotal": 44660.00 }
```

`mvn test` runs five tests and reports on all five.

## How to reproduce

```bash
mvn clean package
java -jar target/expense-reporting-1.0.0.jar
```

That is the whole reproduction. You will not get past it on the first attempt.

---

## Known symptoms

These are ordered the way you will actually meet them: each becomes visible only once the one
before it is out of the way. That is itself the lesson — a broken build hides the next broken thing
behind it.

### Symptom A — the jar will not start

```
$ mvn clean package
[INFO] BUILD SUCCESS

$ java -jar target/expense-reporting-1.0.0.jar
no main manifest attribute, in target/expense-reporting-1.0.0.jar
```

The build succeeded. The class with `main` is definitely in there:

```bash
unzip -l target/expense-reporting-1.0.0.jar | grep -i expenseapplication
```

Also worth noticing: that jar is about **11 KB**. A Spring Boot application jar is normally tens of
megabytes, because it contains every dependency. Ask what a `java -jar` needs that this archive does
not have, and then ask what was supposed to put it there.

### Symptom B — the database driver that is right there

*Visible once Symptom A is out of the way.*

```
Caused by: java.lang.IllegalStateException: Cannot load driver class: org.h2.Driver
	at org.springframework.boot.autoconfigure.jdbc.DataSourceProperties.determineDriverClassName
```

H2 is in `pom.xml`. The application compiles. And this is the part that matters:

```bash
mvn test            # the same driver, no problem at all
mvn spring-boot:run # fails the same way as the jar
```

Two classpaths behave differently for the same declared dependency. That is a very specific kind of
mistake with a very short list of causes.

### Symptom C — "Started", and then nothing

*Visible once Symptom B is out of the way.*

```
2026-09-06T08:16:44.201  INFO --- c.debuglab.expenses.ExpenseApplication : Started ExpenseApplication in 4.238 seconds
2026-09-06T08:16:44.207  INFO --- j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory
2026-09-06T08:16:44.213  INFO --- com.zaxxer.hikari.HikariDataSource : HikariPool-1 - Shutdown completed.
$ echo $?
0
```

It started. It says so. Then it shut itself down cleanly and exited successfully, and nothing is
listening:

```bash
netstat -ano | grep ":8080 "
```

No error, no exception, no stack trace, exit code `0`. Now compare that log with the start-up log of
literally any other project in this laboratory and find the line that is **missing**.

### Symptom D — every endpoint that mentions a date returns 500

*Visible once Symptom C is out of the way.*

```bash
curl -s localhost:8080/api/expenses
```

```json
{ "status": 500, "error": "Internal Server Error", "path": "/api/expenses" }
```

The log is more helpful than the response:

```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException:
  Java 8 date/time type `java.time.LocalDate` not supported by default:
  add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" to enable handling
  (through reference chain: java.util.ArrayList[0]->com.debuglab.expenses.entity.Expense["spentOn"])
```

Spring Boot has registered that module automatically since 2.x, and this project has done nothing
special to Jackson. So the question is not "why was it not registered" — it is "why is it not
there".

```bash
mvn dependency:tree -Dincludes=com.fasterxml.jackson.datatype
```

### Symptom E — one endpoint fails on a string

*Visible once Symptom D is out of the way.*

`/api/expenses` and `/api/expenses/summary` now work. `/api/meta` does not:

```
java.lang.IllegalArgumentException: Could not resolve placeholder 'project.version'
  in value "${project.version}"
```

The property is in `application.properties` and its value was supposed to be replaced with `1.0.0`
while the jar was being built.

```bash
unzip -p target/expense-reporting-1.0.0.jar BOOT-INF/classes/application.properties | grep version
```

Whatever that prints is what the application is reading. Then go and find out what
`spring-boot-starter-parent` configures resource filtering to do — in particular, which character it
uses to mark a placeholder, and why it is not `$`.

### Symptom F — the tests that never ran

```
$ mvn test
[INFO] --- surefire:3.2.5:test (default-test) @ expense-reporting ---
[INFO] Tests are skipped.
[INFO] BUILD SUCCESS
```

The project has a test class with five tests in it. None of them has run — not now, and not in any
build that produced this jar.

### Symptom G — the tests that still never ran

*Visible once Symptom F is fixed.*

```
$ mvn test
[INFO] Building expense-reporting 1.0.0
[INFO] BUILD SUCCESS
```

No "Tests are skipped" this time. Also no "Tests run: 5". No test output whatsoever, and
`target/surefire-reports/` is empty. Surefire ran, found nothing it considered a test, and said so
by saying nothing.

There is a second setting next to the first one. Read it against the name of the test class.

### Symptom H — the total that is always zero

*Visible once Symptom G is fixed — the tests finally run and one of them fails.*

```
ExpenseSummaryTest.theSummaryTotalsEveryExpenseInTheRange:62
  JSON path "$.grandTotal" expected:<44660.0> but was:<0.0>
```

```json
{ "expenseCount": 6,
  "totalByCategory": {"MEALS":7430.00,"TRAVEL":12630.00,"LODGING":9600.00,"TRAINING":15000.00},
  "grandTotal": 0 }
```

Six expenses were found. Every category total is correct. The sum of those four numbers is 44,660.
The grand total is zero.

This one is a genuine Java defect, and it has been in the code since the beginning. It is at the end
of this list because **nothing could tell you about it**: the tests that would have caught it in the
first minute were configured never to run.

---

## Investigation hints

### Symptom A — the jar with no manifest

> **Hint A1**
> Look at the size of the jar. Then list its contents and compare the layout with a working Spring
> Boot jar from any other project in this laboratory (`BOOT-INF/classes`, `BOOT-INF/lib`,
> `org/springframework/boot/loader`). None of that is here.

> **Hint A2**
> `mvn package` on its own produces an ordinary library jar: your classes, a minimal manifest, no
> dependencies, no launcher. Something has to turn that into an executable archive. Look up what
> `repackage` does and which plugin provides it.

> **Hint A3**
> Compare the `<build><plugins>` section with another project's. The parent POM supplies the plugin
> *version*, which is why nobody notices that the plugin itself was never declared.

### Symptom B — the driver that is on the classpath except when it is not

> **Hint B1**
> `mvn dependency:tree` and find H2. Read the word after the version.

> **Hint B2**
> Learn the scopes properly, because this is what they are for: `compile` (everywhere), `runtime`
> (not compiled against, but shipped and run with), `provided` (compiled against, **not** shipped),
> `test` (compiled and run in tests only, **not** shipped). Then ask which of those describes a
> database driver that the application needs in order to start.

> **Hint B3**
> Note which classpath each command uses: `mvn test` uses the test classpath, `spring-boot:run` and
> the packaged jar use the runtime classpath. A dependency in the wrong scope is invisible until the
> classpath changes — which is usually the moment you deploy.

### Symptom C — started, then gone

> **Hint C1**
> Read the start-up log of any web project in this laboratory and diff it against this one. One line
> is missing, and it is the line that mentions a port.

> **Hint C2**
> Spring Boot decides at start-up whether it is a servlet application, a reactive application, or
> neither, purely by what it finds on the classpath. Look up `WebApplicationType.deduceFromClasspath`
> and what class it looks for. If it decides "neither", it runs the context, finds nothing keeping
> the JVM alive, and exits — successfully, because nothing went wrong.

> **Hint C3**
> `mvn dependency:tree` and look for an embedded servlet container. Then look at `pom.xml` for the
> reason it is not there. Somebody removed it deliberately; ask yourself when that would be the
> right thing to do (there is a legitimate case) and whether this project is that case.

### Symptom D — the missing Jackson module

> **Hint D1**
> The exception names the artifact. `mvn dependency:tree -Dincludes=com.fasterxml.jackson.datatype`
> and see whether it is in the tree at all.

> **Hint D2**
> It normally arrives transitively through the web starter. If it is not in the tree, something
> stopped it. Read the `<exclusions>` in `pom.xml` — all of them, not just the first.

> **Hint D3**
> Exclusions are one of the sharpest tools in Maven and one of the easiest to misuse. Each one says
> "I know better than this library about what it needs". When you remove this one, ask why anybody
> would have added it, and whether the same reasoning is hiding elsewhere in the file.

### Symptom E — the placeholder that was never replaced

> **Hint E1**
> Read the property out of the jar itself, not out of `src`. Whatever is in the archive is what the
> application sees.

> **Hint E2**
> Two different systems want to expand `${...}`: Maven's resource filtering, at build time, and
> Spring's property resolver, at runtime. If they used the same delimiter they would fight over
> every property. Look up how `spring-boot-starter-parent` resolves that, and what it changed the
> Maven delimiter to.

> **Hint E3**
> The fix is one character at each end of the placeholder. Then confirm it worked by reading the
> property back out of the rebuilt jar — not by reading the source.

### Symptoms F and G — the tests that were configured away

> **Hint F1**
> "Tests are skipped" is Surefire telling you exactly what it did. Find the configuration that told
> it to, and ask who would have added it and why (usually: "just to get a build out quickly").

> **Hint G1**
> Surefire has a default set of patterns for what counts as a test class — `Test*.java`,
> `*Test.java`, `*Tests.java`, `*TestCase.java`. Once you configure `<includes>` yourself, the
> defaults are gone and only your list applies. Compare the list in the POM with the actual name of
> the test class.

> **Hint G2**
> Note how quiet the failure is. A build that runs no tests looks exactly like a build whose tests
> all passed, unless you read the output carefully. That is why teams put a minimum-test-count
> check, or a coverage gate, in CI.

### Symptom H — the total that stays zero

> **Hint H1**
> The per-category totals are right and the grand total is not, so the loop is running and the data
> is fine. Read the two statements inside the loop next to each other and ask what each one *does
> with its result*.

> **Hint H2**
> `BigDecimal` is immutable. Every operation on it returns a new object and changes nothing. Now
> read the grand-total line again: it computes the right answer and throws it away, and the compiler
> has no reason to object.

> **Hint H3**
> The same class of mistake exists for `String.replace`, `String.trim`, `LocalDate.plusDays` and
> every other method on an immutable type. Once you have fixed it, consider whether `reduce` or a
> `stream().map(...).reduce(BigDecimal.ZERO, BigDecimal::add)` would have made it impossible to
> write.

---

## Expected logs and observations

* Symptom A produces no log at all — the JVM never starts your code.
* Symptom C's log is the dangerous one: it says `Started ExpenseApplication`, which is true, and
  exits `0`, which is also true. Neither is what you wanted.
* Symptom D is fully explained by the exception, if you look past the `500` in the HTTP response and
  read the server log.
* Symptoms F and G produce **BUILD SUCCESS**, which is the most misleading output in this project.
* `mvn dependency:tree` answers B, C and D. `unzip -l` on the jar answers A and E.
* `mvn help:effective-pom` shows you the parent's contribution — plugin versions, resource filtering
  and the `@` delimiter — none of which is visible in your own POM.

## Difficulty

**Advanced.** Expect an hour and a half to two hours. Eight defects.

This project is almost entirely a chain: A hides B hides C hides D hides E, and F hides G hides H.
There is very little to attack in parallel, which is unusual for this laboratory and is the point —
a broken build gives you one symptom at a time and no way to skip ahead.

The last one is the moral of the whole project. A real defect sat in the code from the first commit,
a test existed that caught it immediately, and the build was configured so that the test never ran.

## Concepts being tested

* Maven dependency scopes and which classpath each one reaches
* Transitive dependencies, `<exclusions>`, and what breaks when you remove one
* `spring-boot-maven-plugin` and what `repackage` actually produces
* How Spring Boot decides it is a web application
* Resource filtering, Maven's `@` delimiter under `spring-boot-starter-parent`, and why it is not `$`
* Surefire defaults for test discovery, and what `<includes>` overrides
* `skipTests` and the difference between "all tests passed" and "no tests ran"
* Reading `dependency:tree`, `effective-pom` and the jar itself as evidence
* `BigDecimal` immutability, and discarded return values on immutable types

## When you think you are done

- [ ] `mvn clean package` then `java -jar target/expense-reporting-1.0.0.jar` starts and stays up.
- [ ] The start-up log contains a line about Tomcat and a port.
- [ ] `mvn test` reports `Tests run: 5, Failures: 0, Errors: 0`.
- [ ] `unzip -l target/expense-reporting-1.0.0.jar | grep -c BOOT-INF/lib` is a large number.
- [ ] `curl localhost:8080/api/expenses` returns dates as `"2026-08-03"`.
- [ ] `curl localhost:8080/api/meta` reports `"buildVersion": "1.0.0"`.
- [ ] The summary's `grandTotal` equals the sum of `totalByCategory`, for any range you pick.
- [ ] Change `<version>` in `pom.xml` to `1.0.1`, rebuild, and confirm `/api/meta` follows.
- [ ] `mvn dependency:tree` contains no exclusion you cannot justify out loud.

Then say **"I think I fixed the project"**.
