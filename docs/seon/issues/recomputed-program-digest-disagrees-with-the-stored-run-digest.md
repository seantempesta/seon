---
type: issue
status: open
severity: blocker
tags: [issue, test, provenance, wave/publication-provenance]
created: 2026-09-16
---

# Recomputed program digest disagrees with the stored run digest

## Problem

`seon.test/verified?` answers "did this test pass on THIS program" by
comparing a run row's stored `:seon.test.run/program-digest` with a digest
derived now by `seon.test.runner/program-digest`. The test-attribution
research lane found, on default at basis 536872496 (read-only probes, note
[test-attribution-plan-2026-09-15.md](../../prds/steward-platform/research/test-attribution-plan-2026-09-15.md),
"The actual red result"), that the digest recomputed from the historical
tested basis does not equal the digest stored with the run at that basis:

| Run | Tested basis | Stored digest | Recomputed from `as-of` basis |
|---|---:|---|---|
| `0dcd16ceefec` (green 3/0/0) | 536872480 | `746cf0b8…1a403` | `49d5132e…64833` |
| `c938001fb2f6` (red 0/3/0) | 536872495 | `b68eaf0a…13d09` | `20333b9c…9ce7` |

A later green run `55b9031a0fda` carries the SAME digest as the red run, so
digest equality cannot by itself explain a pass or a failure.

## Why it matters

Every success predicate the steward platform builds on (`verified?`, the
task done-query, cross-namespace attribution) is only as honest as this
equality. If the derivation is not stable across an `as-of` value and the
live value of the same basis, a green recorded at run time can never be
re-verified later, and "unchanged reach, reuse the green" (the suite
efficiency plan) is unsound.

## Wanted

One derivation, stable for one program content: `program-digest` of a
database value at basis T equals the digest recorded by the run that tested
basis T, for both a live value and an `as-of` value. Find which input differs
(the source seal's `:t` read through `history`/`since` on an `as-of` value;
the "rows touched since the seal" selection; ordering). Regression on the
canonical fixture: record a run, then recompute from `(as-of db basis)` and
assert equality; then change one function and assert inequality. Candidate
owner: the test-provenance slice (`src/seon/test/runner.clj:1358`).
