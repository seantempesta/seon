---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, flow]
---

# Run opening retries storm against an answered trigger

## Problem

Something in the run-opening path retries `run/open-tx` against a trigger
that already has an answering run, at roughly ninety attempts per second,
instead of treating the first `:seon.cluster.loop/trigger-already-answered`
refusal as a settled verdict. The refusal is correct; the caller's retry loop
is the defect. A refusal that is retried forever is neither loud nor final,
and the storm buries real writer errors in noise (the N1/N5 pattern) while
burning a compute core.

## Evidence

A bounded opening probe on a fresh isolated root at HEAD
(`tmp/opening/probe.clj`, log `tmp/opening/probe-run-2.log`, 2026-08-13)
booted one cluster, observed the root and one fresh agent's generated opening
runs close normally (4.2 s and 3.2 s), and stopped. The ~56-second run logged
**4,885** identical `datahike.writer :datahike/write-rejected` lines:

```text
{:kind :seon.cluster.loop/trigger-already-answered,
 :cause "run opening refused: the trigger already has an answering run"}
```

The rejections continue in a tight loop (sub-millisecond spacing) until
cluster stop. The openings themselves succeeded, so the retrying caller is
re-deriving the same next-work and re-attempting an open whose refusal it
never consumes as terminal.

A hot-looping proc per answered trigger is also a candidate mechanism for the
2026-08-12 integration-gate wedge (no reporter progress for 300 s with all
worker JVMs alive, `tmp/test-runs/run.ZyS5O7`): property tests that create
agents would accumulate spinning procs that starve the bounded `:compute`
pool. That attribution is a hypothesis until the wedge lane probes it.

## Owner

The work-derivation/arm seam that opens runs from triggers —
`seon.cluster.work/next-agent-work` consumers and the agent proc loop in
`src/seon/cluster/agent.clj` / `src/seon/cluster/loop.clj`. Diagnosis must
identify which proc loops on the refused open instead of parking until a new
wake fact.

## Acceptance

- A `trigger-already-answered` refusal is consumed as a terminal verdict for
  that trigger; the caller re-derives work only on a new wake fact, never in
  a hot loop.
- One class regression proves an already-answered trigger produces at most
  one refused open attempt per wake.
- The probe rerun logs zero repeated rejections for the same trigger.
