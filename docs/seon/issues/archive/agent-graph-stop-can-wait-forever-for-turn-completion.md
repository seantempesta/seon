---
type: issue
status: resolved
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

## Root cause and repair evidence

Core.async Flow `start` initializes a proc, submits its runnable to the selected
executor, and returns without acknowledging that the runnable began
(`reference-code/core.async` commit
`dc35f3e0d7bc2eef502e77982f48641f025c8051`,
`flow/impl.clj:149-172,256-323`). `stop` only broadcasts `::flow/stop`, closes
the report and error channels, clears the graph's channel registry, and returns
(`flow/impl.clj:174-183`).

Agent arming nevertheless returned an armed entry whose promise completion
channel was empty. Its only producer was the turn proc's `::flow/stop`
transition. Therefore an executor could accept the turn runnable without ever
starting it, `flow/stop` could return, and `disarm!` would take forever from a
channel whose sole producer had never run. Paused graphs and closed input
channels were falsified as causes: Flow still reads its priority control
channel in both states. The missing boundary was proc startup readiness.

The deterministic regression replaces the graph's executor with one that
accepts and withholds the turn runnable, then observes the exact completion
take before releasing the runnable. Before the repair, arming published parked
completion in 0/100 controlled stop interleavings: the wedge reproduced
100/100 without machine load. Commits `109c33909` and `6289a7d1f` preserve the
red falsifier and its measured repetition.

Commit `da67ebcf2` changes the existing completion channel into the event the
interface actually depends on:

- `arm!` synchronously publishes one ready permit before Flow scheduling;
- a turn transform consumes that permit and republishes it from `finally`;
- `disarm!` sends Flow's stop, consumes the permit, then closes it; and
- a transform selected after disarm observes the closed channel and performs
  no database work.

The channel is therefore ready while the proc is parked or has not started,
and absent exactly while an active turn owns the branch dependency. Stop and
turn entrance linearize on that one permit. No timeout or last-resort backstop
was added. After the repair the same controlled interleaving published
completion in 100/100 trials. Focused proof passed 13 tests / 95 assertions / 0
failures / 0 errors, including arm/disarm, pause, park/wake, hot reload, and
recovery paths. The combined agent + boot lifecycle gate passed 40 tests / 222
assertions / 0 failures / 0 errors; its active-pass falsifier proved cluster
stop still waits for an in-flight transaction to commit before releasing the
branch connection.

Live proof used only the isolated operator root
`tmp/agent-disarm-live.HeZKf5`. After publishing current source, cluster
`disarm-proof` booted and explicit `bin/seon --root ... stop disarm-proof`
completed through prepl and reaped its empty JVM. A second boot followed by
`bin/seon --root ... down` completed through prepl plus SIGTERM, released the
flock, and left 0/0 clusters alive with no orphan Seon JVMs. The default
operator root and live default cluster were never addressed.

## Provider-call backstop closure — 2026-08-03

The readiness permit repairs observable proc-start and parked-state races, but
an active turn can still be inside a remote provider call whose completion is
not observable by Flow. Commit `d62561f24` keeps teardown event-driven until a
durable prompt-capture fact proves that a provider call may have crossed the
external boundary. Only then does it arm a loud last-resort backstop derived
from that turn's effective primary/backup provider timeout and finite retry
budget. Firing offers the existing Flow fault value, prints a core-fault line,
throws, and leaves the route armed so stop is retryable.

The local never-answering socket regression configured a 100 ms provider
timeout with zero retries and observed the 100 ms derived backstop plus the
matching fault value. The whole regression completed in 226 ms including
fixture setup, socket release, and the successful retry. Before this repair,
two captured suite JVMs remained stuck for 91 and 83 minutes; the old wait had
no bound.

Focused proof before unrelated schema-lane churn: `bin/test
seon.cluster.agent-test` passed 14 tests / 100 assertions / 0 failures / 0
errors.
