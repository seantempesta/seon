---
type: issue
status: open
severity: blocker
tags: [issue, flow, boot, error, agent-runtime]
---

# Wire Flow errors and wake faults during cluster start

## Problem

`seon.cluster/start!` stops after database/config boot and never constructs or
starts the run-loop graph, registers the wake listener with a fault channel, or
wires `seon.flow/start-error-fanout!`. A started Flow graph therefore exposes a
buffered error channel with no consumer; once full, its sliding buffer silently
drops the oldest core faults.

## Evidence

`src/seon/cluster.clj:418-447` builds only the store, branch connection,
recovery, and config layers, and `src/seon/cluster.clj:449-534` returns that
tower without Flow or wake composition. `src/seon/cluster/wake.cljc:99-134`
requires an injected `:seon.cluster.wake/fault-channel`.
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:99-102`
creates the error channel with `(sliding-buffer 100)`, and lines 169-172 merely
return it. The existing consumer remains isolated in
`src/seon/flow.clj:514-565`; no production call site invokes it.

This wiring is still absent after commits `21215ce28`, `ba723b2d1`, and
`a6d426983`.

## Owner

Cluster boot composition in `seon.cluster/start!`, using the existing
`seon.flow/start-error-fanout!` and wake-listener contracts.

## Acceptance

- Cluster start constructs and starts the run-loop graph, registers the wake
  listener, and supplies its fault channel.
- The graph's error channel is consumed through the existing error fanout from
  the moment the graph starts until coordinated stop.
- Overflow is admitted and durably reported rather than silently dropped.
- Cluster stop unwires listeners and fanout and stops both graphs in reverse
  boot order.
- A live fault reaches durable facts and its addressed agent through the
  existing message/wake mechanism.
