---
type: issue
status: open
severity: blocker
tags: [issue, agent, flow, lifecycle, testing]
---

# Make agent graph stop always publish turn completion

## Problem

Agent graph teardown can stop the Flow graph and then wait forever for a turn
completion value that no proc will publish. This wedges both direct disarm and
cluster stop, including the full test gate.

## Evidence

On 2026-08-02, two orphaned full-suite JVMs were still alive after 91 and 83
minutes with zero CPU progress. Their independent `jcmd Thread.print -l`
dumps both parked the main thread in the same unbounded wait:

- PID 7519: `clojure.core.async/<!!` → `seon.cluster.agent/disarm!`
  (`src/seon/cluster/agent.clj:419-421`) → the `disarm-all!` cleanup for
  `lint-refusals-continue-the-episode-until-the-cap`
  (`test/seon/cluster/agent_test.clj:147-151,333-336`).
- PID 8569: the same `disarm!` wait through `seon.cluster/disarm-agents!` and
  `seon.cluster/stop!` while
  `test/seon/cluster/program_restart_test.clj:272` stopped its cluster.

Neither JVM had a live child process. `flow/stop` is asynchronous, while
`disarm!` then performs an unbounded take from
`:seon.cluster.loop/completion`. The turn proc is intended to publish that
value in its stop transition (`src/seon/cluster/agent.clj:190-195`), but the
dumps prove that transition does not publish on every reachable stop path.

The suite-level liveness backstop contains the development impact but does not
repair this production lifecycle contract.

## Owner

The one agent graph lifecycle in `seon.cluster.agent`: make every stopped turn
proc publish exactly one completion value before its graph can become
unresponsive, using Flow's existing lifecycle/report mechanisms.

## Acceptance

- Every reachable in-flight and parked turn state stops and publishes the one
  completion value; direct disarm and cluster stop both complete.
- A deterministic interleaving reproduces both captured stacks before the fix
  and observes completion after it, without treating elapsed time as success.
- Repeated full gates cannot strand an agent or cluster teardown at
  `disarm!`, and no second lifecycle mechanism is introduced.
