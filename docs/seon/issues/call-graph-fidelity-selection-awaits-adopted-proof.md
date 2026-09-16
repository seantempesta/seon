---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, testing, program-graph]
---

# Call-graph fidelity selection awaits adopted proof

The measured graph omitted implementation-body calls, declared handlers and
references with no known arity. The baseline was 409 identities with no incoming
edge, 312 public functions with no reaching test, eight zero-reach capability
handlers and 28 zero-reach seon.print functions.

The implementation and exact verification boundary are in
[the landing note](../../prds/steward-platform/research/call-graph-fidelity-fix-2026-09-17.md).
The authority is [the measured Option B](../../prds/steward-platform/research/call-graph-fidelity-2026-09-17.md).

Acceptance: implementation and declared-value edge/reach regressions,
reference widening regressions, and S1 indexed/evaluated parity pass after
adoption; the orchestrator reviews the diffs before its batched gate. Re-measure
the same baseline queries and record cost. Absence of a call fact is unknown,
not a declaration of no coverage.

Follow-up: implementation commits `15a35c2a7`, `af800d1a0`, `7eeed900d`,
`7907afc7a`, `b13705fc7` are present. Verification remains unknown: default's
loaded test owner refuses runs for missing `:seon.fn/destroys` declarations;
publication reached a writer refusal on `my.agent/identity`'s keyword set.
The exact refusal and correct artifact probe are in the landing note. This
note remains open until adopted fixture/parity proof and the reviewed gate.
