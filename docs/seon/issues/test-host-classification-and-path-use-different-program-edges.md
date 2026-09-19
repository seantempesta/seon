---
type: issue
status: open
severity: friction
created: 2026-09-19
tags: [issue, test, program-graph, wave/changed-test-selector]
---

# Test host classification cannot explain its selected destructive owner

On default PID 41822, after development program reconciliation, calling
`seon.test/host` for
`seon.fn-test/fresh-population-flattens-with-its-supplied-declarations`
threw an armed return-contract violation: `:seon.test/destructive-path`
was nil where a vector is required. This blocks the normal in-process
regression entry; no destructive admission was bypassed.

`seon.test/destructive-reach` obtains membership from
`seon.fn/tests-reaching`. The in-flight shared gate traversal includes
call, reference and subject edges. `seon.test/destructive-path` follows
only calls and test subjects. The classifier and its evidence producer
therefore answer different graph questions.

Owner: the existing test-system overhaul in `src/seon/test.clj`, currently
held by another lane. The publication-repair session did not edit it.

Acceptance: every selected destructive owner has a complete path over the
same declared graph relation; unavailable evidence is an explicit typed
unknown, never nil in a successful host report. The gate must continue
refusing actual destructive work in the development root. Verify whether
reference-only reach is intended for execution placement before treating
every reference as an actual call.
