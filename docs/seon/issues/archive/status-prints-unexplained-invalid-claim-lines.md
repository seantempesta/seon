---
type: issue
status: resolved
severity: friction
tags: [issue, operator, class/n5, wave/operator-status-face]
---

# Status prints unexplained invalid-claim lines

## Problem

`bin/seon status` prints one line per stale operator claim record —
currently EIGHT repetitions of
`record unreadable …/claims/roots/<uuid>.edn: The external claim is
invalid.` — naming neither WHAT was invalid (schema mismatch? dead root?
orphaned by a deleted isolated root?) nor what to do about it. The claims
belong to isolated test roots whose directories no longer exist, so the
honest rendering is one line: N orphaned claims for absent roots, with the
reclamation verb to run. A diagnostic that repeats an unexplained sentence
eight times is the N5 evidence-collapse class at the operator face.

## Evidence

2026-08-13: both the orchestrator's status checks and the comprehension
test deployment saw the eight identical lines
(`data/operator/claims/roots/*.edn` — the roots were tmp test-run roots
since deleted).

## Owner

`seon.operator/existence` claim reading and the status face: classify the
invalid-claim cause (absent root vs malformed record), aggregate identical
causes, and name the cleanup action. Orphaned claims for absent roots
should also be reclaimable by the existing cleanup owner rather than
printed forever.

## Acceptance

- Status renders orphaned/invalid claims as one aggregated line naming the
  cause and the action; identical causes never repeat per-file lines.
- Claims for absent roots are reclaimed by the cleanup owner; a fresh
  status after reclamation prints none.

## Resolution — 2026-08-13

Resolved by `a073f7b51` at the existing claim-reader and status-face seams.

- A live `bin/seon --root tmp/status-claim-face.e6kc6W status` probe with two
  fabricated invalid claims reproduced ten repeated `record unreadable` lines:
  the two probe records plus eight pre-existing records.
- Reading those same claim values through `seon.operator.state` established
  two honest causes: seven records identified absent roots and three identified
  present roots but had malformed creator data.
- `resources/seon/operator/state.clj` now attaches the derived cause to every
  invalid-claim error. `script/seon/fresh_operator.clj` renders all such errors
  once as `invalid external claims: 10 total (7 orphaned for absent roots; 3
  malformed); reclaim with bin/seon reset --force.` Paths remain in the
  structured census instead of flooding the status face.
- The regression `status-aggregates-invalid-external-claim-causes` exercised
  real absent-root and malformed-present-root claim files and passed. The
  focused `bin/test seon.dev.fresh-operator-test` gate ran 32 tests / 205
  assertions; three foreign lifecycle tests accounted for four failures and
  one error. One reproducible failure is the held `src/seon/instrument.clj`
  class-union migration, one readiness failure is downstream of in-flight
  source state, and the remaining restart failure was confirmation-parallel
  only in a Konserve `.new` file.
- The three exact probe claim files were deleted through the atomic claim owner,
  and the isolated probe root was removed. No pre-existing shared claim was
  mutated.
