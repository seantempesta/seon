---
type: issue
status: open
severity: friction
tags: [issue, cluster, agent, class/p3]
---

# Cluster disarm awaits turn completion without a bound

## Problem

`seon.cluster.agent/await-turn-completion!` (`src/seon/cluster/agent.clj:589`)
derefs a promise with no timeout inside `disarm!`
(`src/seon/cluster/agent.clj:662`), reached from
`seon.cluster/disarm-agents!` (`src/seon/cluster.clj:2389`) on the stop
path. When the completion never arrives, `cluster/stop!` parks forever —
an unbounded event wait, the exact half of the §2.3 law that turns one
missing fact into a silent wedge. A hang is a worse defect than a
failure: the caller gets nothing to diagnose.

## Evidence

2026-08-13: the quiet-tree rebirth probe rerun completed its entire proof
(evidence file written) and then wedged in the probe's `finally` calling
`cluster/stop!`; jstack of pid 37095 showed main parked in
`CountDownLatch.await` under `await-turn-completion!` → `disarm!` →
`disarm-agents!` (dump: `tmp/rebirth/stop-hang-jstack-2026-08-13.txt`,
preserved in this note's evidence trail). The JVM had to be killed.

## Owner

`seon.cluster.agent/await-turn-completion!`. The await must carry a
declared bound and return a typed diagnostic naming the run/agent whose
completion never arrived — never a silent park. Both halves: keep the
event-driven completion, add the loud bounded backstop.

## Acceptance

A stop against an agent whose turn completion never arrives returns a
typed `:seon.error` diagnostic within the declared bound naming the
agent; one regression proves it; the rebirth probe's stop path completes
on a wedged turn instead of parking the JVM.
