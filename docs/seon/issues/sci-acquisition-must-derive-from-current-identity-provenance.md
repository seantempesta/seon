---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, sci, acquisition, provenance]
---

# SCI acquisition must derive from current identity provenance

The owner ruled one `base-ctx` of a database value in program-facts S3.
Current acquisition calls `seon.schema/admission-from-asserting-transaction`
for a function's source assertion (`src/seon/sci/eval.clj:1500–1539`). That
helper gathers the **current provenance of every row sharing the transaction**
and classifies mixed provenance as agent-authored
(`src/seon/schema.clj:788–819`). That is not the current admitted row's own
`:seon.schema.admission/source`.

Confirmed on default PID 33583 after the owner reset: accepting an ordinary
turn redefining `seon.eval.drive/uuid-text` made the classifier return `:agent`
for `seon.id/valid?`, whose current row still explicitly says `:core`. Its
source transaction was 536870917. This is a measured counterexample, not an
inferred population count. Full reconstruction exceeded the MCP 60000 ms
bound after acceptance; before acceptance, acquisition completed in
21244.610416 ms for 4336 sourced functions.

The existing base updater also applies entries present in the new base but
never removes entries absent from it (`receive-base!`,
`src/seon/sci/eval.clj:1784–1815`). It is therefore not yet a proved optimization
of regeneration. A generation-owned SCI Var alone cannot distinguish a private
binding from an accepted definition; program identity must participate.

Acceptance and measurements belong to
[the S3 landing note](../../prds/steward-platform/research/acquisition-by-provenance-s3-2026-09-16.md)
and [the binding PRD](../../prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md).
The override-query seam alone does not resolve this issue. The owner replaced
PID 41413 with PID 33583; this lane performed no lifecycle operation. An
isolated implementation and canonical regressions are in progress. The timed
out reconstruction is not a completed regeneration measurement.

## S3 implementation boundary

The implementation and canonical proof are in
[the S3 landing note](../../prds/steward-platform/research/acquisition-by-provenance-s3-2026-09-16.md).
The shared-tree publication attempt refused the still-held
`test/seon/cluster/source_test.clj:507` zero-argument minimal constructor call.
That file was released on recheck, and the caller plus its require were
updated in the main checkout. No edit was made while it was held, and no other
lane's session was operated. The `my.program/overrides` surface remains that lane's ownership;
it needs to delegate to the history-preserving `seon.program/overrides` query
so replacement of the last indexed member does not hide an override.
