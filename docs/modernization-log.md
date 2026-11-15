# Modernization log

Starting point: DDD Sample 1.1.0 (2009, MIT license, Citerus AB and Domain Language, Inc.).
Java 6, Spring 2.5 XML config, Hibernate 3 `.hbm.xml` mappings, JMS (ActiveMQ), SOAP (CXF), Spring MVC + JSP. ~6.2k lines, 98 tests.

## Step 0: make the old code run on a current JDK (JDK 17)

| Attempt | Result |
|---|---|
| Untouched build | Does not compile: `Source option 6 is no longer supported` |
| Java 7 language level | 87/98 tests pass. 11 persistence tests fail: Spring 2.5 parses `java.version`, doesn't recognise "17", assumes a pre-Java-5 runtime, and refuses `@Transactional` support |
| + test-only shim setting `java.version=1.6.0` | **98/98 pass** |

All step-0 changes are marked in `app/pom.xml` and are temporary:

| Shim | Why | Remove when |
|---|---|---|
| Java 7 language level | JDK 17 cannot compile Java 6 source | moving to Java 21 |
| `java.version=1.6.0` (tests) | Spring 2.5 doesn't recognise "17" and disables `@Transactional` support | Spring is upgraded |
| `--add-opens java.base/java.lang` | CXF and Javassist define classes via reflection; without it Hibernate silently runs **without lazy-loading proxies** | CXF and Hibernate are upgraded |
| `java.locale.providers=COMPAT,CLDR` | JDK 9+ formats dates differently ("3/1/09, 8:00 AM" vs "3/1/09 8:00 AM"): the JDK upgrade alone changes user-visible text | a deliberate, approved text change (COMPAT is gone in newer JDKs) |

## Step 1: characterization tests (golden master)

`LegacyLifecycleCharacterizationTest` boots the app the way `web.xml` does (same XML contexts, Hibernate,
embedded ActiveMQ, RMI, CXF) and drives two cargos through every entry point: the booking facade,
the SOAP handling-report service, the JMS pipeline, and the tracking page via `DispatcherServlet`.
Everything observable is recorded in `app/src/test/resources/characterization/lifecycle.approved.txt`
(303 lines), which was reviewed line by line before approval.

**Is it stable?** 3 consecutive runs, identical output.

**Does it catch regressions?** Three deliberate mistakes, each caught:

| Mutation | Caught by |
|---|---|
| "Fix" the `hh` time format without approval | tracking page line: `12:00` became `00:00` |
| Drop `<tx:annotation-driven>` from the Spring XML | the booking flow breaks (cargo never saved) |
| Misdirection only counted on UNLOAD events | sample cargo JKL567, misdirected by an unexpected LOAD |

**Legacy behaviour found and recorded as-is** (not fixed; changing it must be deliberate):
- Tracking page times use `hh`: a 12-hour clock without AM/PM (midnight shows as 12:00).
- Routing candidates are generated from *today's* date, so any cargo with a past deadline gets zero routes.
- A handling report for an unknown voyage is only logged; `RejectedRegistrationAttemptsQueue` is wired but never used.
- No "delivered" notification if a cargo was unloaded before its destination was changed to that port.
- The existing `CargoTrackingViewAdapterTest` only passes in UTC+1 (where the original authors were).

**Not covered:** JSP rendering (needs a servlet container), the admin booking web UI, and the
upload-directory file import. The facade and SOAP service behind them are covered.

## Step 2: Legacy Explorer MCP server

[`legacy-explorer-mcp/`](../legacy-explorer-mcp/README.md) gives the coding agent five read-only tools
(`entry_points`, `find_usages`, `spring_wiring`, `entity_mapping`, `impact_of_change`) that answer
questions text search can't in this codebase: XML-only wiring, the RMI link between the two web apps,
JMS/SOAP entry points, Hibernate mappings. Indexing the app takes ~0.6 s. 8 tests run against the real app.

**Measured** (80 Claude Code runs, $3.33): with the server, answers were correct 40/40 vs 38/40 without.
On the hardest question (entry points reaching `CargoRepository` across 3 contexts and RMI) it cut
cost by 65% (Haiku) and 46% (default model), and more than halved the time on Haiku. On simple grep-able
questions it made no difference. Details: [legacy-explorer-mcp/README.md](../legacy-explorer-mcp/README.md).
