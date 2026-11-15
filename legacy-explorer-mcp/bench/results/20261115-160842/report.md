# Legacy Explorer MCP benchmark

Model: default. Same prompt, tools and fresh workspace for both variants; only the MCP server differs.

| task | correct without MCP | correct with MCP | avg cost without -> with | avg turns without -> with | avg time without -> with | avg MCP calls |
|---|---|---|---|---|---|---|
| entry-points-reaching-cargo-repository | 2/3 | 3/3 | $0.211 -> $0.113 | 8.0 -> 6.0 | 28s -> 20s | 5.0 |
| soap-report-to-queue | 3/3 | 3/3 | $0.109 -> $0.121 | 5.3 -> 6.0 | 16s -> 21s | 1.0 |
| admin-ui-path-to-repository | 3/3 | 3/3 | $0.124 -> $0.086 | 5.7 -> 4.3 | 21s -> 18s | 2.7 |
| callers-of-cargo-repository-find | 3/3 | 3/3 | $0.083 -> $0.086 | 4.3 -> 4.0 | 15s -> 15s | 1.0 |
| tests-referencing-handling-event-factory | 3/3 | 3/3 | $0.047 -> $0.067 | 2.0 -> 3.3 | 10s -> 14s | 1.0 |
| **all tasks** | 14/15 | 15/15 | $0.115 -> $0.095 | 5.1 -> 4.7 | 18s -> 18s | 2.1 |

## Wrong answers

- entry-points-reaching-cargo-repository / WITHOUT_MCP / trial 3: [missing URL /admin/*, missing URL /public/track, extra '/track', extra '/*']
