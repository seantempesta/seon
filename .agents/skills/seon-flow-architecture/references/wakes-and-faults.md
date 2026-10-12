---
type: reference
status: active
tags: [reference, flow]
---

# Wakes and errors

Read this when routing database commits into flows, deriving render interest,
or changing error capture.

## Contents

- [The listeners a cluster connection holds](#the-listeners-a-cluster-connection-holds)
- [The two hard listener rules](#the-two-hard-listener-rules)
- [Register before derive](#register-before-derive)
- [Current interest routing](#current-interest-routing)
- Error fan-out (`#fault-fan-out`)
- [Change checklist](#change-checklist)

## The listeners a cluster connection holds

There is one wake ROUTER per cluster, `seon.cluster.wake/route!`
(`src/seon/cluster/wake.clj`), registered by db<->flow proc as it
starts (`src/seon/cluster/agent.clj`) under the key
`:seon.agent/route` (`src/seon/cluster.clj`). It is not the only
Datahike listener on the connection. At HEAD a cluster connection with N
instrumented agents holds 1 + N long-lived listeners:

| key | owner | registered at |
|---|---|---|
| `:seon.agent/route` | the wake router | `src/seon/cluster.clj` |
| a `random-uuid` per agent | each agent's schedule proc, on `::flow/resume` | `src/seon/schedule.clj` |

`seon.eval.drive/await-fact!` adds one short-lived listener per wait
(`src/seon/eval/drive.clj`). The census is recorded in
`docs/research/agent-platform/flow-usage-audit-2026-09-23.md` (D3) and
`docs/seon/issues/one-cluster-holds-two-plus-n-datahike-listeners.md`. The
call-preparation listener that note names was deleted in `306f32431`, and the
SCI program-identity observer in H3: each commit carries its content revisions.

Do not add another per-agent, per-render, per-web-surface or per-feature
listener. Derive the interest behind the router and route it to an existing
graph input. The routed values are payload-free signals, not a second durable
work log: messages and identities are already database facts.

## The two hard listener rules

The router's namespace docstring states the rules and the measured routing
traps (`src/seon/cluster/wake.clj`).

### Failures reach the connection's handler

At the pinned fork, `transact!` delivers the transaction promise first and
then notifies every listener independently, inside the go block that recorded
the transaction (`reference-code/datahike/src/datahike/writer.cljc`).
Datahike catches each callback's `Throwable`; with a `:listener-failure`
handler on the connection it retires the listener by identity and hands the
handler the failure, else it only logs it (`writer.cljc`). The router
throws a refused delivery into Datahike, whose handler
(`seon.cluster.store/listen-failures!`, `src/seon/cluster/store.clj`)
stores one error; a returned transaction report never proves delivery.

### Never park

Listener work runs inside that go block, so a blocking or slow callback holds
a core.async dispatch thread. Use non-blocking `offer!` into already-buffered
flow inputs. Do not use blocking channel operations, rendering, logging
transports or model calls in a listener, and keep queries out of it.

The router breaks the query rule: it derives its matchers with a Datalog
query from each report's `:db-after` inside the callback
(`src/seon/cluster/wake.clj`, `wake-matchers` at). This is
recorded in
`docs/seon/issues/the-wake-router-queries-on-the-writer-thread-and-drops-its-own-fault.md`.

A refused transaction does not wake: dispatch is gated on `(map? tx-report)`
(`writer.cljc`). Reasserting an identical value produces no datom, so
attribute-driven routing produces no wake. Consumers must not depend on a
"write attempt" that the database does not report.

## Register before derive

The safe order is:

1. install the listener or route;
2. derive current work from the database value; and
3. process later transaction reports.

Reversing the first two steps creates a lost interval between the initial read
and listener registration.

`start-flows!` states this order as its contract
(`src/seon/cluster.clj`): the cluster graph starts and its db<->flow
proc registers `route!` (`src/seon/cluster/agent.clj`), one render wake is
offered (`src/seon/cluster.clj`), then db<->flow's derive-all pass runs
directly. Keep
that direct pass. A synthetic "boot wake" sent before route registration is
not equivalent.

## Current interest routing

| consumer | current interest | source |
|---|---|---|
| db<->flow | an asserted attribute declared `:seon.wake/starts true`, or a recipient with no routing entry | `src/seon/cluster/wake.clj` |
| agent mailbox | attributes declared `:seon.wake/listen true` (the value addresses the agent) plus the agent's runtime `:seon.listen` patterns | `wake-matchers` `src/seon/cluster/wake.clj`; delivery |
| cluster renderer | at most one wake per report, when a changed attribute is in `:seon.render.web/interest` (`:all` before the first complete derivation) | `src/seon/cluster/wake.clj` |

The render interest is the union of retained reads' attributes, published by
the render proc (`publish-interest!`, `src/seon/render/web.clj`).
Every wake target holds one value: db<->flow and render channels are
`(sliding-buffer 1)` (`src/seon/cluster.clj`) and a mailbox is a
sliding-one `CountedSlidingBuffer` that counts overwrites
(`src/seon/cluster/agent.clj`). A wake says only "look".

## Error fan-out

Every producer calls `seon.fault/fault!` on its own thread; there is no error
channel or committer (`src/seon/fault.clj`). A Flow proc step's throw is
recorded by `proc-failed!` on the proc's own thread, and Flow's sliding graph
error channel is replaced by a synchronous error port
(`src/seon/flow.clj`, `fault-port` at).

Agent mistakes do not belong on the core error route. SCI/runtime boundaries
return flat `:seon.error` values for the agent to see; reserve flow errors for
system defects.

## Change checklist

1. Add no listener; derive the interest behind the one router.
2. Use only non-blocking offers from a listener; keep queries out of it.
3. Register the route before deriving current work.
4. Derive interest from declared facts (`:seon.wake/listen`,
   `:seon.wake/starts`, retained reads), not namespace or feature lists.
5. Route agent mistakes as values and core errors through `seon.fault/fault!`.
6. When storage fails, `seon.fault/fault!` throws the error and runs the execution owner's `:seon.fault/stop-request!`; nothing else stops a graph.
7. Probe transaction latency and identical-value reassertion after listener
   changes.
