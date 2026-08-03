---
type: issue
status: resolved
severity: friction
tags: [issue, database, schema, performance]
---

# Deleted schema-row creation clocks from retained snapshots

## Problem

Every canonical schema row stores `:seon.schema/created-at`, even though the
instant does not define schema identity or content and transaction metadata
already owns database provenance. `seon.schema/canonical-schema-rows` requires
and writes it (`src/seon/schema.clj:2180-2202`), while
`seon.cluster/schema-row-changes` reads and preserves it
(`src/seon/cluster.clj:258-262,314-332`). The non-semantic wall clock is
multiplied through every later retained database snapshot.

## Evidence

The exact-total allocation in
`docs/prds/sci-execution-runtime/research/store-census-2026-08-02.md` ranks
`:seon.schema/created-at` first among 208 attribute/origin rows:

- 187,360,394 B total / 946,265 B per sample;
- 95,580,379 B in temporal indexes;
- 183,968,946 B retained by immutable snapshots; and
- only 3,391,448 B reachable from ending heads.

This is inherited fork-parent data, not agent-authored payload, but it is the
largest contributor to the physical per-sample delta because fused roots carry
it forward.

The 2026-08-02 reader census found exactly eight fresh-source literals: four in
`src/seon/schema.clj`, three in `src/seon/cluster.clj`, and one in
`resources/seon/schema.edn`. No Datalog query reads the attribute and no fresh
test names it. The only database read is reconciliation preserving the first
value. Runtime projection, provenance, namespace rendering, and program-row
replacement use key/form/asserting-transaction facts instead. The complete
evidence and a physical deletion cell are in
`docs/prds/sci-execution-runtime/research/store-census-reductions-2026-08-02.md`.

That cell removed 5,018,204 B / 31.533% from its small controlled
retained-snapshot workload. The landed deletion's paired exact-topology cell
then measured **9,661,654 B / 48,796 B per sample**, not the attributed
187,360,394 B. The exact codec-weighted allocation assigned shared fused-node
framing to this attribute and therefore overstated causal removal by 19.4×.

## Write-site provenance

The clock was introduced for the deleted CLJS bulk program replay, not for
current schema semantics. The ordering design was recorded by `bb917f957`;
the old replay writer landed in `31e31cbe5`; the attribute declaration landed
in `e425f7981`; and `2884c41b1` deleted the pod-wide replay reader. The fresh
canonical writer was later carried forward by `13183c222` and made the static
index explicitly pass epoch zero by `995ccec92`.

The surviving reader did not restore a semantic purpose. Its only job was to
preserve the first value so the wall-clock writer would not make every reopen
look changed. The live value was epoch zero, so the largest attributed field
was not recording creation time at all. It existed to suppress reconciliation
churn created by its own writer.

## Owner

`seon.schema/canonical-schema-rows` owns the row shape;
`seon.cluster/schema-row-changes` owns source-population reconciliation.
The coordinated deletion also removes the epoch argument at
`src/seon/fn.clj:687-723`. Removing only the schema EDN field is invalid because
the two surviving writers would transact an uninstalled attribute.

## Resolution

Commit `e4bffb0d1` removes the declaration, canonical writer and timestamp
arguments, reconciliation pull/preservation/clock, and static indexer's epoch
argument. Transaction metadata remains the provenance authority. Canonical
schema reconciliation now compares semantic fields directly, and its focused
test proves a converged second pass creates no transaction.

The committed measurement is
`docs/prds/sci-execution-runtime/research/scripts/schema-created-at-saving-2026-08-02.clj`.
It builds complete baseline and treatment populations across the archived
158/40 root split, 198 heads, and 13,204 commits. Baseline measured
1,623,533,269 B; treatment measured 1,613,871,615 B; deletion saved
9,661,654 B. The corrected combined counterfactual is 1,817,338,943 B / 9.178
MB per sample, not the theoretical 1,639,640,203 B / 8.281 MB.

Existing branches retain their installed declaration and old datoms because
Datahike history is immutable and this is not an in-place retraction or GC.
Current reconciliation selects only current desired row keys, so those extra
legacy datoms are tolerated and ignored. A pre-deletion branch was preserved
for the live reopen proof; a fresh publication/refork is what omits the
attribute and receives the measured storage saving.
