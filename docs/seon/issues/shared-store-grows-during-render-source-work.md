---
type: issue
status: open
severity: friction
tags: [issue, database, render, performance, wave/store-perf]
---

# Identify shared store growth during renderer changes

On 2026-09-06, `bin/seon status` reported shared store growth from 7.68 GiB
to 9.28 GiB during source edits and focused tests. `du -sh data/store`
confirmed 9.3G. The separate stopped source-submission proof root was 68M;
`tmp/test-runs` was 1.5G and is not included in that shared-store measurement.

The live `lab-run-inspection` database still has four run entities and no
matching evaluation entities. This rules out a large evaluation population in
that cluster as the explanation, but does not establish the cause of physical
growth. Other clusters and `current-src` share the store. Source publication,
retained commits, blobs, faults, and reclaimable storage have not been measured
separately; none is yet identified as the cause.

Before resetting away evidence, measure those owners and identify which writes
account for growth. Verify that rendering and repeated context consumption do
not create additional evaluations or persistent artifacts for unchanged inputs.
Do not infer a fault loop or publication defect from physical size alone.

The next session check reported 9.76 GiB, with the same three live clusters
and PID 14798. No reset was performed; the evidence remains available.

## Stopped Juniper root attribution, 2026-09-06

The stopped `tmp/juniper-context-live` root reached 13 GiB after 1,713 run
entities. A read-only physical inventory found 46,692 files under its one
`data/store` directory, totaling 14,210,622,019 bytes (304,348 bytes average).
The size distribution was:

| file size | count | bytes |
|---|---:|---:|
| below 4 KiB | 9 | 6,073 |
| 4–64 KiB | 10,535 | 504,010,130 |
| 64 KiB–1 MiB | 33,047 | 9,298,171,842 |
| 1–3 MiB | 3,070 | 4,287,977,568 |
| above 3 MiB | 31 | 120,456,406 |

The largest object was 4,509,485 bytes. The only substantial non-store file
was the 10,517,708-byte `data/clusters/build/current-src.edn`; cluster logs and
operator metadata together were negligible. This rules out one or a few giant
evaluation payloads as the physical explanation. The footprint is a large
population of medium-sized immutable store objects. Konserve filenames do not
identify whether an individual object is an index node, commit record, or
content-addressed blob, so this inventory does not claim a byte-exact logical
split. It does establish that the growth shape is persistent store-object
accretion, not logs, build output, or a standalone blob directory.

The existing GC finding explains why this population remains. The only
collector owner, `seon.cluster.registry/collect!`, defaults its cutoff to the
epoch (`src/seon/cluster/registry.clj:491-527`). Datahike therefore marks every
intermediate commit in every extant branch and its reachable index nodes;
plain collection can remove only objects outside those histories
(`reference-code/datahike/src/datahike/gc.cljc:83-120`). Repeated run/evaluation
settlement supplies many commits, but the physical evidence cannot assign all
46,692 objects to the 1,713 runs because source publication and other writes
share the same branch histories. Run count is therefore a workload correlate,
not the attribution.

The smallest existing reclamation mechanism is the same
`seon.cluster.registry/collect!` call with an explicit `remove-before` retention
cutoff. It already extends Datahike's reachability mark with schema-discovered
Seon blob references, so a separate file sweeper or blob GC would duplicate and
weaken the authority. Datahike's online GC is not the immediate prevention
mechanism for this root: it is safe only for a single-branch database, while
this root retains at least the published `current-src` and Juniper branch.
Preventing recurrence requires choosing the snapshot/commit retention window
and scheduling cutoff collection through that same registry owner, exactly the
unresolved decision recorded in
`storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md`. No
collection or deletion was performed during this investigation.

## Source-publication amplification audit, 2026-09-06

There was no live cluster during this audit, so this section identifies a
code-supported write path and a measurement that can assign bytes on the next
isolated run. It does not attribute the prior 13 GiB by inference.

The existing observation surfaces are sufficient. `seon.operator.state/footprint`
recursively sums physical file bytes without following links
(`resources/seon/operator/state.clj:1201-1222`). The operator's collection
observation already combines that count with `(count (konserve.core/keys ...))`
(`src/seon/operator.clj:676-686`). Datahike exposes branch heads, commit IDs and
parent commit IDs directly (`reference-code/datahike/src/datahike/api/impl.cljc:332-375`).
No database telemetry or second collector is needed.

One unnecessary-write candidate is established by source. A complete refresh
always creates a fresh scratch branch, commits its source schema, populates the
whole graph, commits an activation seal containing a fresh
`:seon.source/built-at`, and force-publishes that head
(`src/seon/cluster/source.clj:272-352`, especially `:295-315`).
`full-source-refresh!` calls that path without comparing the newly computed
digest with the current published digest (`src/seon/cluster.clj:1637-1646`).
Thus a second bare `bin/seon init` over identical bytes necessarily constructs
and publishes a new database value.

The incremental path has the same smaller no-op defect. Once it has proved the
unreported source current, it collects the planned change rows and calls
`source/upsert!` even when the source digest is unchanged and the row vector is
empty (`src/seon/cluster.clj:1703-1733`). `upsert!` then performs one rows
transaction and a second transaction that retracts and recreates the activation
seal, again with a new build instant, before force-publishing
(`src/seon/cluster/source.clj:374-441`). A hook report naming a file whose bytes
did not change can therefore add commits and copy-on-write index objects despite
changing no program fact. This is a prevention target supported by code; its
share of the old root's bytes remains unmeasured.

### Bounded attribution procedure for the next isolated root

At each quiescent boundary, capture one immutable observation containing:

1. `seon.operator.state/footprint` of `data/store`;
2. the set returned by `konserve.core/keys` from the existing Datahike store;
3. `datahike.api/branches`, the `:current-src` commit ID, and the selected
   cluster branch's commit ID and basis transaction.

Keep each key set only for the duration of the experiment. For the next
boundary report byte delta, object-count delta, `(count (set/difference after
before))`, and whether each branch head changed. The prior root had only 46,692
objects, so these bounded sets are materially smaller than its stored program
artifact and require no durable instrumentation.

Take boundaries after exactly these isolated operations:

1. initial `bin/seon init` into the empty root;
2. an immediate second bare `bin/seon init` with identical source;
3. `bin/seon init --changed PATH` naming an unchanged source file;
4. one actual source edit published through the same changed-path path;
5. one controlled render evaluation with publication disabled.

The branch heads make absence of signal fail loudly. A claimed no-op whose
`:current-src` head changes is a publication write; a render evaluation that
changes `:current-src` crossed owners. Physical/object deltas distinguish
write amplification from an in-place metadata update. Repeating each operation
once is enough to separate setup cost from steady-state cost; do not loop a
known writer against a large store.

The smallest prevention change to falsify after measurement is at the existing
publication authority: when the stable snapshot digest equals the current
published digest, return the existing published head before creating a scratch
branch or transaction. The incremental owner should likewise return the
existing head when its proven snapshot digest is unchanged and its planned row
delta is empty. This preserves actual-edit publication and removes no history;
it merely declines to mint a database value for identical source.

### Identical complete publication measured and fixed

The isolated fresh-root measurement confirmed the amplification. Immediately
before the second identical complete publication the store was 57,476 KiB;
afterward it was 114,808 KiB, an increase of 57,332 KiB. The source digest
remained
`2101a3e72457465d942d1b2eea3f8c090c8bf761c977528bde7cc4dd843aa6ee`,
while the `:current-src` head changed from
`6a9e0964-b1c2-51fe-9f6d-94228e188eb5` to
`6a9e09e3-7301-513e-81e4-1441ded5a3ac`.

The refresh planner now returns the existing published head with
`:seon.source/built? false` only when the stable digest, exact per-file digest
map, cached artifact commit, live branch commit, and digest stored in that
commit all agree. This location preserves `source/publish!`'s intentional
equal-digest repair: a branch changed behind the artifact has a different head
and still rebuilds. The unchanged incremental path additionally requires an
empty planned row delta. Development adoption still runs after either no-op,
so reload reconciliation, SCI acquisition, instrumentation, and its wake are
not skipped.
