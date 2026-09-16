---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, issue, detectors, F7]
---

# Orchestrator review — first-task detectors (`1a42fbfa7`, `10dfe6926`)

Read: the landing note, the `src/seon/issue/detect.clj` diff (+196), the
regression file stat, the two issue notes.

**Accepted.** `public-without-contract` and `public-without-reaching-test`
are in the exact shape of `public-without-doc` (two arities; the scoped one
joins on `:seon.fn.file/relative-root`); exclusions are by fact
(`:seon.fn/macro?`, shared `:seon.fn/form-span`); the reach detector asks
`seon.fn/tests-reaching` once per candidate, treats an unresolved reach as
"every test" and therefore not a subject, and its problem text names the
basis `:t`, the derivation, and that the graph may be incomplete —
"no reach is recorded", never "no test exercises it". The doc detector is
refactored value-identically onto the shared helpers. `generate!` unchanged.
Measured on `default`: contract 68 unscoped / 8 src (0.1 s); reaching-test
166 / 139 (17–18 s, the per-call reference re-derivation the lane filed
against `gate-set`); doc 33 / 2.

**Two issues it filed are real and routed:** `gate-set` re-derives its
declared-reference population per call (the call-graph lane's widening
follow-up must fix this together with the scope); the row drops kondo's
`:defined-by`, so `deftype` constructors and `defprotocol` methods count as
uncontracted (6 of the 8 `src` contract subjects) — the indexer keeps the
fact already; the row owner should carry it (a small slice on `fn.clj`).

**Boundary accepted:** regressions unverified in process (the publication
rejection blocker); the cold gate is the proof.

**Gate requested:** `seon.issue.detect-test seon.issue-generate-test
seon.issue-test` when a slot frees.
