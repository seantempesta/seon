---
type: issue
status: open
severity: blocker
created: 2026-09-22
tags: [testing, schema, program-graph, class/p1]
---

# Program graph tests do not carry their current contract projection

The slice 2 fast snapshot at `05c77ac1d` plus its analysis changes reached
four errors in `seon.fn-test`: `cross-file-implementations-keep-edges-or-widen`,
`declared-function-values-contribute-edges-without-arities`,
`implementation-bodies-contribute-edges-and-test-reach`, and
`unresolved-call-shapes-preserve-reference-edges-and-reach`.
Each calls `reconcile-tx` with `@connection`; that owner calls
`program/shapes-in` on `db/carried-projection`, which is nil. The armed
contract refuses before reconciliation. Other tests in the same run using
`db/db connection` pass this boundary. Raw connection dereference is not
the database owner's acquisition of the carried projection.

`contract-findings-ranks-each-incomplete-function-contract` also fails:
it writes synthetic function contracts, then `contract-findings` finds their
stored spec but `malli.registry/schema` on its supplied projection returns
nil; `malli.core/-function-schema-arities` refuses nil. The observation
proves a mismatch between the stored contracts and the supplied compiled
registry. It does not yet establish whether the fixture or the production
acquisition owner must supply the newer projection.

These owners and test bodies are unchanged in the slice 2 diff. No separate
HEAD-only baseline was run, so this is a verification boundary, not a claim
that the redesign is independently exonerated. Repair the carried inputs
on the canonical fixture and rerun these regressions armed. Do not weaken
`program/shapes-in` or treat a missing compiled contract as an empty one.

## Focused fixture repair — 2026-09-21

The combined gate `fresh-start-combined-gate-repaired.log` reproduced these
four reconciliation errors. Their five reconciliation calls now acquire the
database through `db/db connection`, carrying the canonical projection into
`reconcile-tx`. This includes the real-analyzer declared-dispatch selection
regression. Production contracts are unchanged. The contract-findings registry
mismatch remains unresolved and outside this bounded repair. No tests were run
by the repair lane; the owner's armed checkpoint is still required.
