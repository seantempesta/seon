---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, testing, program-graph, datahike]
---

# Recursive call coverage outlives its cancelled future

The call-graph fidelity lane's whole-graph measurement called
`seon.fn/functions-without-tests` in default PID 53320 after reference facts
became readable. The all-pairs `function-reaches` relation in
`src/seon/fn.clj:1256` did not complete promptly. `future-cancel` returned true,
but a subsequent stack observation still found
`clojure-agent-send-off-pool-93` executing the function in persistent-hash-map
construction. Cancellation is not proof that the query stopped. No process
was stopped or restarted.

A test-rooted unary Datalog closure, using the same three `call-edge` rules,
returned 1,032 public tested functions in 5,093.7085 ms on the same live
program. This is a proposed query probe, not an adopted implementation proof.
The original baseline coverage query was 286.34575 ms. The denser reference
graph makes the previous all-pairs intermediate impractical.

Acceptance: coverage derives the union reachable from test roots without
materializing every function pair; the existing coverage regression remains
green. Separately, a cancelled host database query must have observable
termination or report that execution is still unknown. The call-graph lane
owns the query change; cancellation at the database seam is outside its slice.

Evidence and verification boundary:
[call-graph fidelity landing note](../../prds/steward-platform/research/call-graph-fidelity-fix-2026-09-17.md).
