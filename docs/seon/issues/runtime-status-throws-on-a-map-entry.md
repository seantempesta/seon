---
type: issue
status: open
severity: friction
tags: [issue, mcp, runtime, wave/dev-mcp]
created: 2026-09-15
---

# Runtime status throws on a map entry

Run4-blockers session-start observation: `runtime_status` on root
`/Users/sean/src/seon`, cluster `default`, pid 23729, returned
`java.lang.ClassCastException` at `clojure.lang.RT/dissoc` (RT.java:911):
`class clojure.lang.MapEntry cannot be cast to class clojure.lang.IPersistentMap`.
The cluster runtime field is an exception, so this is unavailable health
evidence. A separate JVM `eval_clj` of `(+ 1 1)` returned 2.

Owner: development MCP runtime health projection; outside run4-blockers.
Acceptance: the same status call returns explicit health observations,
including unavailable observations, without throwing.
