# Legacy Explorer MCP benchmark

Model: claude-haiku-5-5. Same prompt, tools and fresh workspace for both variants; only the MCP server differs.

| task | correct without MCP | correct with MCP | avg cost without -> with | avg turns without -> with | avg time without -> with | avg MCP calls |
|---|---|---|---|---|---|---|
| entry-points-reaching-cargo-repository | 4/5 | 5/5 | $0.010 -> $0.003 | 9.0 -> 4.6 | 34s -> 15s | 3.2 |
| soap-report-to-queue | 5/5 | 5/5 | $0.004 -> $0.004 | 6.2 -> 7.4 | 10s -> 13s | 1.6 |
| admin-ui-path-to-repository | 5/5 | 5/5 | $0.006 -> $0.003 | 9.8 -> 6.6 | 17s -> 13s | 5.6 |
| callers-of-cargo-repository-find | 5/5 | 5/5 | $0.003 -> $0.003 | 4.4 -> 4.4 | 10s -> 9s | 2.0 |
| tests-referencing-handling-event-factory | 5/5 | 5/5 | $0.002 -> $0.002 | 2.2 -> 3.0 | 8s -> 8s | 1.0 |
| **all tasks** | 24/25 | 25/25 | $0.005 -> $0.003 | 6.3 -> 5.2 | 16s -> 12s | 2.7 |

## Wrong answers

- entry-points-reaching-cargo-repository / WITHOUT_MCP / trial 3: [missing URL /admin/*, missing URL /public/track, extra '/track']
