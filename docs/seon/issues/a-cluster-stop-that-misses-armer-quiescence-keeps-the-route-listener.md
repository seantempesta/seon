---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, flow, wake, agent, wave/flow-join]
---

# A cluster stop that misses db<->flow quiescence keeps the route listener registered

## Evidence (source, 30e6e8f75)

- Since 30e6e8f75 the cluster's `wake/route!` listener lives as long as db<->flow proc:
  `seon.cluster.agent/db<->flow` registers a supplied `:seon.cluster.wake/route` in its init arity and removes it on
  the `:seon.agent/quiesce` message and on `::flow/stop`.
- `seon.cluster/stop-flows!` sends the quiescence request under the stop's one deadline and throws
  (`refuse! ::plan-quiesced`) when the acknowledgement misses the bound. It then never reaches the cluster graph's
  `flow.core/stop`, so neither removal runs.
- Before 30e6e8f75, `stop-flows!` called `wake/unlisten!` first, on every path.

## Why it is a defect

A stop that fails keeps custody for diagnosis, which is correct. But the listener keeps delivering wakes into mailboxes
of agents that are being joined, and it offers unknown recipients to a db<->flow that did not answer. That work goes to
execution the caller already asked to stop.

## Investigated 2026-09-26 (lane inv-lifecycle-storage-opus): not reproduced; the fix is a ruling

Confirmed from source at 61d207c3e (`seon.cluster/stop-flows!`, cluster.clj: `refuse! ::plan-quiesced` precedes
`flow.core/stop`). Not reproduced live: it needs a db<->flow that never answers, which only a wedged step makes.
A wedged db<->flow also cannot run its own `::flow/stop` transition, so "the route stays owned by the proc" and "no
listener survives a missed quiescence" conflict whenever the proc cannot run. Options for root:
1. (simplest) Keep it: a missed quiescence is a panic that needs RESET NEEDED; status names the unanswered db<->flow
   (`status-reads-only-the-root-roster-and-a-stopped-hosted-graph-stays-armed`). No code.
2. (recommended) On a missed quiescence still queue the cluster graph's `flow/stop`, then throw: db<->flow's own
   stop transition removes the listener whenever the step returns; a later stop reads db<->flow's exit (ping
   unknown/not running) instead of re-awaiting quiescence. ~15 lines in `stop-flows!`.
3. The route's delivery checks its plan-channel closure and retires itself (Datahike retires a listener that
   throws, `store/listen-failures!` records it); stop closes the channel on a miss. Listener lifetime moves to the
   channel, a second owner beside the proc.

## Owner and acceptance

The owner is db<->flow proc's lifecycle (`db<->flow`) together with `stop-flows!`.

Acceptance: a stop whose quiescence misses its bound still leaves no route listener on the connection. The route
stays owned by the proc (no second `unlisten!` beside it). One regression: a db<->flow that never acknowledges
quiescence, then a stop that throws `::plan-quiesced`, then the connection holds no `:seon.agent/route` listener.
