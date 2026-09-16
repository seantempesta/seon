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
