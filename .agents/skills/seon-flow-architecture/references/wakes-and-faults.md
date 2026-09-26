---
type: reference
status: active
tags: [reference, flow]
---

# Wakes and faults

Read this when routing database commits into flows, deriving render interest,
or changing fault capture and `:record`/`:panic` behavior.

## Contents

- [The listeners a cluster connection holds](#the-listeners-a-cluster-connection-holds)
- [The two hard listener rules](#the-two-hard-listener-rules)
- [Register before derive](#register-before-derive)
- [Current interest routing](#current-interest-routing)
- [Fault fan-out](#fault-fan-out)
- [The config dial](#the-config-dial)
- [Change checklist](#change-checklist)

## The listeners a cluster connection holds

There is one wake ROUTER per cluster, `seon.cluster.wake/route!`
(`src/seon/cluster/wake.clj:433-532`), registered by the armer proc as it
starts (`src/seon/cluster/agent.clj:1527`) under the key
`:seon.agent/route` (`src/seon/cluster.clj:3440-3453`). It is not the only
Datahike listener on the connection. At HEAD a cluster connection with N
armed agents holds 2 + N long-lived listeners:

| key | owner | registered at |
|---|---|---|
| `:seon.agent/route` | the wake router | `src/seon/cluster.clj:3440-3453` |
| a `random-uuid` per agent | each agent's schedule proc, on `::flow/resume` | `src/seon/schedule.clj:761-765` |
| `::program-identity` | the SCI program-identity observer | `src/seon/sci/eval.clj:3276-3285`, called from `:3618`, `:3693` |

`seon.eval.drive/await-fact!` adds one short-lived listener per wait
(`src/seon/eval/drive.clj:50-54`). The census is recorded in
`docs/research/agent-platform/flow-usage-audit-2026-09-23.md` (D3) and
`docs/seon/issues/one-cluster-holds-two-plus-n-datahike-listeners.md`. The
call-preparation listener that note names was deleted in `306f32431`; the
`::program-identity` observer it predates took its place in the count.

Do not add another per-agent, per-render, per-web-surface or per-feature
listener. Derive the interest behind the router and route it to an existing
graph input. The routed values are payload-free signals, not a second durable
work log: messages and identities are already database facts.

## The two hard listener rules

The router's namespace docstring states the rules and the measured routing
traps (`src/seon/cluster/wake.clj:1-65`).

### Failures reach the connection's handler

At the pinned fork, `transact!` delivers the transaction promise first and
then notifies every listener independently, inside the go block that settled
the transaction (`reference-code/datahike/src/datahike/writer.cljc:417-441`).
Datahike catches each callback's `Throwable`; with a `:listener-failure`
handler on the connection it retires the listener by identity and hands the
handler the failure, else it only logs it (`writer.cljc:394-415`). The router
throws a refused delivery into Datahike, whose handler
(`seon.cluster.store/listen-failures!`, `src/seon/cluster/store.clj:621`)
stores one fault; a returned transaction report never proves delivery.

### Never park

Listener work runs inside that go block, so a blocking or slow callback holds
a core.async dispatch thread. Use non-blocking `offer!` into already-buffered
flow inputs. Do not use blocking channel operations, rendering, logging
transports or model calls in a listener, and keep queries out of it.

The router breaks the query rule: it derives its matchers with a Datalog
query from each report's `:db-after` inside the callback
(`src/seon/cluster/wake.clj:506-507`, `wake-matchers` at `:392-431`). This is
recorded in
`docs/seon/issues/the-wake-router-queries-on-the-writer-thread-and-drops-its-own-fault.md`.

A refused transaction does not wake: dispatch is gated on `(map? tx-report)`
(`writer.cljc:427`). Reasserting an identical value produces no datom, so
attribute-driven routing produces no wake. Consumers must not depend on a
"write attempt" that the database does not report.

## Register before derive

The safe order is:

1. install the listener or route;
2. derive current work from the database value; and
3. process later transaction reports.

Reversing the first two steps creates a lost interval between the initial read
and listener registration.

`arm-agents!` states this order as its contract
(`src/seon/cluster.clj:3465-3479`): the cluster graph starts and its armer
proc registers `route!` (`src/seon/cluster/agent.clj:1527`), one render wake is
offered (`src/seon/cluster.clj:3551`), then the armer's derive-all pass runs
directly (`:3556-3561`). Keep
that direct pass. A synthetic "boot wake" sent before route registration is
not equivalent.

## Current interest routing

| consumer | current interest | source |
|---|---|---|
| armer | an asserted attribute declared `:seon.wake/arms true`, or a recipient with no routing entry | `src/seon/cluster/wake.clj:520-521`, `:528` |
| agent mailbox | attributes declared `:seon.wake/listen true` (the value addresses the agent) plus the agent's runtime `:seon.listen` patterns | `wake-matchers` `src/seon/cluster/wake.clj:392-431`; delivery `:522-528` |
| cluster renderer | at most one wake per report, when a changed attribute is in `:seon.render.web/interest` (`:all` before the first complete derivation) | `src/seon/cluster/wake.clj:508-509`, `:512-514`, `:529-530` |

The render interest is the union of retained reads' attributes, published by
the render proc (`publish-interest!`, `src/seon/render/web.clj:2011-2023`).
Every wake target holds one value: the armer and render channels are
`(sliding-buffer 1)` (`src/seon/cluster.clj:3483`, `:3505`) and a mailbox is a
sliding-one `CountedSlidingBuffer` that counts overwrites
(`src/seon/cluster/agent.clj:108-135`). A wake says only "look".

## Fault fan-out

Every producer calls `seon.fault/fault!` on its own thread; there is no fault
channel or committer (`src/seon/fault.clj:1-7`). A Flow proc step's throw is
recorded by `proc-failed!` on the proc's own thread, and Flow's sliding graph
error channel is replaced by a synchronous error port
(`src/seon/flow.clj:202-245`, `fault-port` at `:267-284`).

Agent mistakes do not belong on the core fault route. SCI/runtime boundaries
return flat `:seon.error` values for the agent to see; reserve flow errors for
system defects.

## The config dial

The modes of `:seon.config/on-core-error` are declared at
`resources/seon/schemas/seon.config.edn:40-41`; the shipped default is
`:panic` (`config/default.edn:303`). `seon.fault/policy` is the one decision:
`:record` only when record mode's write committed, else `:panic`
(`src/seon/fault.clj:189-195`). Under `:panic`, `fault!` throws to its caller
with the receipt in its data (`src/seon/fault.clj:205-219`). Change that one
decision; do not add per-site panic decisions.

## Change checklist

1. Add no listener; derive the interest behind the one router.
2. Use only non-blocking offers from a listener; keep queries out of it.
3. Register the route before deriving current work.
4. Derive interest from declared facts (`:seon.wake/listen`,
   `:seon.wake/arms`, retained reads), not namespace or feature lists.
5. Route agent mistakes as values and core faults through `seon.fault/fault!`.
6. Preserve one cluster-level `:record`/`:panic` decision.
7. Probe transaction latency and identical-value reassertion after listener
   changes.
