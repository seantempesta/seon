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
