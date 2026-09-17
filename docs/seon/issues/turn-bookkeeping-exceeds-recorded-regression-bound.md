---
type: issue
status: open
severity: friction
tags: [issue, runtime, test, class/n9]
---

# Turn bookkeeping exceeds the recorded regression bound

On 2026-09-16, the fresh canonical in-process candidate for
`seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`
passes its 14 semantic assertions but measures **6694.698498 ms** for the
six-form bookkeeping path, failing the existing **300 ms** assertion.
Recorded run: 41506. The candidate updates saved-text observation and supplies
a contracted definition, without changing the timing assertion. It was not
retained in production tests, and no performance cause is inferred from this
single measurement on a shared machine.

This re-observes the bound documented by
[the earlier turn-batch record](archive/a-run-pays-two-and-a-half-seconds-between-every-form.md).
The current turn owner needs phase attribution on the current algorithm before
changing implementation or policy. The old assertion remains intact. Complete
candidate source and result are preserved with the
[turn-test-reds landing](../../prds/context-generation/research/turn-test-reds-2026-09-16.md).

The probe used an isolated HEAD-plus-owned-paths JVM because default had
concurrent publication/contract mismatches; its base was
`a55bdfc8065c556c988d10afdb8b4b79685579b3`. It constructed the canonical base
anew and used a fresh SCI context under armed contracts. This is not a cold-gate
measurement or evidence that the older recorded root cause has recurred.

Fresh-base recheck at `c1d7d4695`, with the maintained transaction-cache repair:
run **44708**, **14 passes / 1 failure / 0 errors**, measured
**7198.085124 ms**. The current contracted-definition candidate preserves the
same **300 ms** bound. The uncontracted original fixture instead refuses the
definition and therefore does not measure the successful installation path.
No performance candidate or relaxed assertion was retained. Exact values are
in the [batch-23 continuation](../../prds/context-generation/research/turn-test-reds-cache-2026-09-16.md).

After the owner-authorized canonical base refresh in default on 2026-09-16,
run **55845** again passes all **14 semantic assertions** and fails the unchanged
**300 ms** bound: **5645.859042 ms**. The candidate uses the current contracted
definition and saved shown text. It remains unlanded. This verifies the residual
after refreshing the fixture; it does not attribute the cost to delimiter repair.

## Message-wake review observation — 2026-09-17

The isolated HEAD-plus-owned-paths fast run at base `8dff32220` measures
**464.287208 ms** against the unchanged **300 ms** installation bound in
`seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`.
That test and the measured installation owner are unchanged in the slice.
No cause is inferred from this shared-machine measurement. See the
[landing evidence](../../prds/steward-platform/research/message-wake-model-2026-09-17.md).

## S3 residue observation — 2026-09-17

The canonical fast snapshot at `a5516ca62` plus the residue paths measured
**472.133 ms** for `seon.turn/gate-function-install` against the unchanged
**300 ms** assertion. The semantic assertions passed. This is the existing
installation-cost class, not a new delimiter repair failure. The residue
does not change this owner or its bound. Raw iteration:
`tmp/s3-residue-corrected.log`; [landing](../../prds/steward-platform/research/acquisition-s3-residue-2026-09-17.md).

The final worktree rerun at `56b8a1cd8` plus the S3 residue paths measured
**427.760417 ms**. This was the only failure in **143 tests / 980 assertions**;
there were zero errors. The 300 ms assertion remains unchanged.
