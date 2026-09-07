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
