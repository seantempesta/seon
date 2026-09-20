---
type: issue
status: open
severity: friction
created: 2026-09-21
tags: [issue, errors, tests, rendering]
---

# A snapshot contract refusal dumps the acquired projection

The sci-program fast run `600f1f2a5da5` (10 executed, 124 assertions)
reached the known call-preparation output-contract defect. The failed
`my.program-test/repl-documentation-and-supplied-database-use-the-canonical-population`
assertion at `test/my/program_test.clj:145` printed the complete return
refusal, including its acquired schema projection. One captured output
chunk exceeded 79 MB. The failure message itself named the missing
`:seon.error/at` at `[:seon.call-preparation/refusals 0]`.

Call-preparation's facet conversion fixes this trigger, but failure reporting
must remain readable when the next snapshot contract fails. This is the
error/test-reporting owners' boundary: retain raw in-memory offending evidence,
but render diagnostic output through the existing bounded value renderer.
Do not add another truncation boundary or serialize per-facet offending data.
Acceptance: a real armed snapshot-return refusal retains its evidence and
prints bounded, readable output through the existing renderer.
