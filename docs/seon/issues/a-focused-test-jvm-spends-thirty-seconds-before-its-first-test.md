---
type: issue
status: open
severity: performance
created: 2026-09-23
tags: [issue, testing, performance]
---

# A focused test JVM spends thirty seconds before its first test

Same class as [the twenty-second note](a-focused-test-jvm-spends-twenty-seconds-before-its-first-test.md), filed the same day by another lane; this note keeps the rows other notes cite.

Measured by lane `oversight-owning-instance` on 2026-09-22 (UTC 20:43-20:45),
`bin/test-fast --paths <4 files> -- seon.oversight-test`, five tests:

| phase | seconds |
| --- | --- |
| whole command (`time`) | 49.8 |
| `PHASE snapshot` | 4-5 |
| JVM start + classpath + namespace load, until `PACKAGED TEST PROJECTION ACQUIRED` | about 35 (by subtraction) |
| projection acquired -> `CONTRACTS ARMED` (1700 instrumented) | 4.1 |
| the five test bodies (`BEGIN`..`END namespace`) | 2.4 |

A bare `clojure -M:test tmp/oversight-owning-instance/probe.clj` that requires
`seon.flow`, `seon.oversight`, `seon.cluster.agent` and `seon.cluster` took
20.9 s wall for a 13 ms probe body. Most of the time goes to loading, not to
the work. The unlabelled interval before the projection has no phase line of
its own. Nobody has yet split it into JVM start, dependency class loading and
Seon namespace compilation. Next step: add PHASE lines for those three, then
name whichever load is proportional to the whole program.

Sighting 2026-09-22 (lane three-way-comparison, run `e84538519dbc`, `seon.program-test`, 31 tests):

| phase | seconds |
| --- | --- |
| whole command (`time`) | 46.11 |
| `PHASE snapshot` | 4 |
| JVM start + load, until `PACKAGED TEST PROJECTION ACQUIRED` (20:51:26.15Z) | about 29 (by subtraction) |
| projection acquired -> `CONTRACTS ARMED` (1702 instrumented) | 2.4 |
| test bodies (`BEGIN`..`END namespace`, 20:51:29.69Z..20:51:39.91Z) | 10.2 |
