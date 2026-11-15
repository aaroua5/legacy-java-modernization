# legacy-explorer-mcp

An MCP server that gives AI coding agents a **map of a legacy Java codebase**: the things plain text
search and "find usages" can't see in a Spring-XML / Hibernate / JMS / RMI application.

Built in Java 21 on the official [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) and
[JavaParser](https://javaparser.org/) with symbol resolution.

## Why

In a legacy app, much of the important structure isn't in the Java code:
- Classes are created and wired in **Spring XML**, so nothing calls their constructor.
- Two web apps talk over **RMI**, linked only by a service name in two XML files.
- **JMS listeners** and **SOAP endpoints** are declared in XML, not annotated.
- Database tables are mapped in **`.hbm.xml`** files, and queries are strings.

An agent that only greps will change a class without knowing that a JMS queue, a SOAP endpoint and
an admin web app all reach it. This server answers those questions directly.

## Tools

| Tool | Answers |
|---|---|
| `entry_points` | Every way into the app: servlets and URLs, web controllers, JMS queues, SOAP endpoints, RMI services, scheduled tasks |
| `find_usages` | Where a class is used or a method is called. Calls through interfaces are resolved and marked; unresolvable calls are listed separately rather than silently dropped |
| `spring_wiring` | A bean's definition, configuration, dependencies, what it's injected into (across contexts and RMI), and the path from each entry point to it |
| `entity_mapping` | Table, field-to-column mapping, relations, and the HQL/SQL strings that name the entity |
| `impact_of_change` | Before editing a class: implementations, dependent files, **tests to run**, beans and the entry points that reach them, DB mapping, config files naming it |

Example (`spring_wiring cargoRepository` on the legacy app in `../app`):

```
reachable from entry points (5):
  RMI service rmi name 'BookingService': bookingServiceFacade -> bookingServiceFacadeTarget -> cargoRepository
  JMS listener queue CargoHandledQueue: cargoHandledConsumer -> cargoInspectionService -> cargoRepository
  JMS listener queue HandlingEventRegistrationAttemptQueue: handlingEventRegistrationAttemptConsumer -> handlingEventService -> handlingEventFactory -> cargoRepository
  Web controller URL /public/track: /track -> cargoRepository
  Web controller URL /admin/*: /* -> remoteBookingService (RMI) -> bookingServiceFacade -> bookingServiceFacadeTarget -> cargoRepository
NOTE: no Java code creates this class with 'new'. It is wired only in XML ...
```

## Does it help? Measured.

`./gradlew bench` asks Claude Code 5 questions about the legacy app, each **with and without** this
server. Both variants get the same prompt, model and read-only tools (Read, Grep, Glob, read-only
shell), and a fresh copy of the app outside this repo, so the agent can't read the answers in our docs.
Answers are scored against facts **verified by hand**, not by this tool. `--selftest` checks
the scoring itself: every reference answer passes, every planted wrong answer fails.

**Results (2026-11-15, 80 runs, $3.33 total):**

| model | correct without -> with | avg cost per question | avg time per question |
|---|---|---|---|
| Haiku (5 trials) | 24/25 -> **25/25** | $0.0048 -> $0.0030 (**-37%**) | 16s -> 12s (-26%) |
| default (3 trials) | 14/15 -> **15/15** | $0.115 -> $0.095 (-17%) | 18s -> 18s (-2%) |

On the hardest question (which entry points reach the `CargoRepository` bean, across 3 Spring
contexts and an RMI link) the server pays off most:

| model | correct without -> with | cost | turns | time |
|---|---|---|---|---|
| Haiku | 4/5 -> 5/5 | $0.0096 -> $0.0034 (**-65%**) | 9.0 -> 4.6 | 34s -> 15s (-56%) |
| default | 2/3 -> 3/3 | $0.211 -> $0.113 (**-46%**) | 8.0 -> 6.0 | 28s -> 20s (-28%) |

Both wrong answers were the same real mistake: without the server, the agent reported the
controllers' bean names (`/track`, `/*`) as URLs, missing the servlet-mapping prefix from `web.xml`
(`/public/track`, `/admin/*`). Haiku also missed the admin app, which is reachable only over RMI.

**Honest caveats:**
- On the simple questions (find callers, find tests) the server doesn't help: grep is already
  enough, and the extra tool call sometimes costs slightly more.
- Accuracy is near the ceiling for both variants; the 1-in-5 and 1-in-3 failures are too few to
  call accuracy significantly better. The clear, repeatable gain is **cost and time on multi-hop
  questions** about XML wiring.
- 5 questions on one codebase. Raw transcripts are kept locally; `report.md` and `results.json`
  for each run are in `bench/results/`.

## Safety model

- **Read-only.** Every tool is annotated `readOnlyHint: true`; nothing writes to the analyzed project.
- **Scoped.** Only the project root given at startup is indexed. Tool arguments are names, never paths;
  anything containing `/` or `\` is rejected.
- **Offline.** XML is parsed without fetching DTDs or schemas (legacy files point at dead URLs).
- **Audited.** Every call is appended to a JSON-lines audit log: tool, arguments, duration, output size, error.
- **Quiet on stdout.** Stdout is the protocol channel; diagnostics go to stderr.

## Run

```sh
./gradlew test fatJar        # tests run against the real legacy app in ../app
java -jar build/libs/legacy-explorer-mcp-all.jar ../app [audit-log.jsonl]
```

The repository root has a `.mcp.json`, so Claude Code started in the repository picks the server up
(it asks once for approval).

## Limits

- Targets Maven-layout projects with Spring XML config, `web.xml` and Hibernate `.hbm.xml`.
  Annotation-based Spring and JPA annotations are not analyzed yet.
- Library types aren't on the symbol solver's classpath, so some calls can't be resolved; those
  are reported under "unresolved candidates" for manual checking.
- Entry-point paths follow XML wiring only, not Java call chains.
