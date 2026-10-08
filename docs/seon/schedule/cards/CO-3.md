---
title: "Card CO-3: Admission decides by present witnesses"
type: reference
status: active
created: 2026-10-08
---

# CO-3 Admission decides by present witnesses

Pattern P1. Continues `44cce8fe4` (check-alloc-astra). Lane: Sonnet. Datahike fork `86c3301a`.

## What exists
`seon.test/admit-run` accepts the tested digest when the run's `:seon.test.run/program-revisions` equals the writer
value's `program-revisions-digest`. When both are present **and differ**, it still calls
`seon.test.runner/program-written-since?`, a history query over every program attribute since the run's basis
(1.09 GB sampled in a two-test check window; a direct call 1,537 ms, 3.36 GB), and then derives
`program-digest` anyway when a write is found.

## Change
Within one connection generation, a fresh attribute revision is issued only for an effective write (fork contract of
`datahike.db/advance-cache-context`, `speculative-cache-context`), so differing present witnesses already answer
"written": go straight to `program-digest`. The history query runs only when a witness is absent (another
generation, a published snapshot without revisions, an old run). A conservative revision change costs a digest
derivation, never a false acceptance.

## Functions
- CHANGE `seon.test/admit-run`: the `digest` decision orders witness-present-equal → witness-present-differ
  (derive) → basis equality → history.

## Proof
`seon.test.admission-digest-test/differing-revisions-derive-without-history` (new row in the existing table test):
a prior program write with revision evidence present: `(calls-of m 'seon.test.runner/program-written-since?)` 0,
the admitted digest equals `program-digest`. The existing missing-evidence row still calls history once.

## Cost
Expected: O(program attributes) to compare witnesses; history only without one. Parent: 1.5 s / 3.36 GB per
fallback call.

## Commit
`bin/lane commit <lane> -m "CO-3: admission decides by present witnesses" --test seon.test.admission-digest-test src/seon/test.clj test/seon/test/admission_digest_test.clj`

## Dependencies
CO-0; `src/seon/test.clj` order after CO-2.
