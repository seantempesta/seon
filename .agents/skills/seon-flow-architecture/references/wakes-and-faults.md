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
(`src/seon/cluster/wake.clj:438-548`), registered by `arm-agents!` under the
key `:seon.agent/route` (`src/seon/cluster.clj:3421-3432`). It is not the only
Datahike listener on the connection. At HEAD a cluster connection with N
armed agents holds 2 + N long-lived listeners:

| key | owner | registered at |
|---|---|---|
| `:seon.agent/route` | the wake router | `src/seon/cluster.clj:3421-3432` |
| a `random-uuid` per agent | each agent's schedule proc, on `::flow/resume` | `src/seon/schedule.clj:795-799` |
| `::program-identity` | the SCI program-identity observer | `src/seon/sci/eval.clj:2412-2421`, called from `:2713`, `:2782` |

`seon.eval.drive/await-fact!` adds one short-lived listener per wait
(`src/seon/eval/drive.clj:60-64`). The census is recorded in
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
traps (`src/seon/cluster/wake.clj:1-58`).

### Never throw

At the pinned fork, `transact!` delivers the transaction promise first and
then notifies every listener independently, inside the go block that settled
the transaction (`reference-code/datahike/src/datahike/writer.cljc:390-414`).
Datahike catches each callback's `Throwable` and only logs it
(`writer.cljc:379-388`). An uncaught listener failure therefore reaches a
log line and nobody else. Catch at the router boundary and send the failure
to the fault channel; a returned transaction report never proves delivery.

### Never park

Listener work runs inside that go block, so a blocking or slow callback holds
a core.async dispatch thread. Use non-blocking `offer!` into already-buffered
flow inputs. Do not use blocking channel operations, rendering, logging
transports or model calls in a listener, and keep queries out of it.

The router currently breaks the query rule on one path: when a transaction
carries a `seon.wake`/`seon.listen`/runtime/schema datom it rebuilds its
matchers with a Datalog query inside the callback
(`src/seon/cluster/wake.clj:512-523`, `wake-matchers` at `:397-436`). Its
catch also `offer!`s a bare `Throwable` and drops the offer's answer
(`:546-547`). Both are recorded in
`docs/seon/issues/the-wake-router-queries-on-the-writer-thread-and-drops-its-own-fault.md`.

A refused transaction does not wake: dispatch is gated on `(map? tx-report)`
(`writer.cljc:400`). Reasserting an identical value produces no datom, so
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
(`src/seon/cluster.clj:3307-3323`): the cluster graph starts, the error fan-out
joins, `route!` registers (`:3421-3432`), one render wake is offered
(`:3437`), then the armer's derive-all pass runs directly (`:3441-3446`). Keep
that direct pass. A synthetic "boot wake" sent before route registration is
not equivalent.

## Current interest routing

| consumer | current interest | source |
|---|---|---|
| armer | an asserted attribute declared `:seon.wake/arms true`, or a recipient with no routing entry | `src/seon/cluster/wake.clj:534-535`, `:539-542` |
| agent mailbox | attributes declared `:seon.wake/listen true` (the value addresses the agent) plus the agent's runtime `:seon.listen` patterns | `wake-matchers` `src/seon/cluster/wake.clj:397-436`; delivery `:536-542` |
| cluster renderer | at most one wake per report, when a changed attribute is in `:seon.render.web/interest` (`:all` before the first complete derivation) | `src/seon/cluster/wake.clj:510-511`, `:526-528`, `:543-545` |

The render interest is the union of retained reads' attributes, published by
the render proc (`publish-interest!`, `src/seon/render/web.clj:2068-2080`).
Every wake target holds one value: the armer and render channels are
`(sliding-buffer 1)` (`src/seon/cluster.clj:3325`, `:3344`) and a mailbox is a
sliding-one `CountedSlidingBuffer` that counts overwrites
(`src/seon/cluster/agent.clj:101-128`). A wake says only "look".

## Fault fan-out

Every graph exposes core.async.flow's error and report channels.
`seon.flow/start-error-fanout!` joins them (`src/seon/flow.clj:1191-1264`):

- each source channel is `mult`ed; monitor taps use sliding buffers;
- the fault tap is a `CountedDroppingBuffer` (`src/seon/flow.clj:966-1003`),
  so observation never blocks the producing graph and overflow becomes one
  synthetic fault carrying the dropped count;
- each agent graph joins the cluster's fault channel; and
- the fault committer proc (`fault-committer-step`, `src/seon/flow.clj:1005-1079`)
  commits each fault as a database fact, in its own graph whose `:io-exec` is
  supplied (`:1183-1189`).

The error policy (`AGENTS.md:279-300`) says a core fault is committed at the
owning boundary and never dropped by an overload channel. The installed
fan-out is an overload channel of capacity 64
(`src/seon/cluster.clj:3377`), so that half of the policy is **[TARGET]**.
Core.async's own `error-chan` is also sliding, and a stop-transition error is
lost by construction
(`docs/seon/issues/durable-faults-cross-dropping-channels.md`,
`docs/seon/issues/flow-stop-transition-errors-are-dropped-on-a-closed-error-channel.md`).

Agent mistakes do not belong on the core fault channel. SCI/runtime boundaries
return flat `:seon.error` values for the agent to see; reserve flow errors for
system defects.

## The config dial

`arm-agents!` hands the fan-out a zero-argument mode reader that reads the
cluster's effective `:seon.config/on-core-error` on every fault, falling back
to `:record` when the fact is absent (`src/seon/cluster.clj:3379-3383`). The
modes are declared at `resources/seon/schemas/seon.config.edn:39-40`; the
shipped default is `:panic` (`config/default.edn:294`).

Installed behavior (`fault-committer-step`, `src/seon/flow.clj:1054-1066`):

- `:record`: commit the fault; call the panic handler only when the commit was
  refused;
- `:panic`: commit the fault, then call the panic handler;
- a repeated signature is committed and not reported again.

The cluster's panic handler, `emit-core-fault!`
(`src/seon/cluster.clj:3187-3210`), prints one `SEON CORE FAULT` line to
stderr and returns nil. It does not throw, stop the graph or mark it failed.
The owner's `:panic` policy (the operation throws to its caller and the
failing graph stops and shows as failed, `AGENTS.md:290-294`) and delivery of
the fault fact to the responsible agent (`AGENTS.md:288-289`) are
**[TARGET]**. Change the one handler and the committer; do not add per-site
panic decisions.

## Change checklist

1. Add no listener; derive the interest behind the one router.
2. Catch everything at the listener boundary and route it with its cause.
3. Use only non-blocking offers from a listener; keep queries out of it.
4. Register the route before deriving current work.
5. Derive interest from declared facts (`:seon.wake/listen`,
   `:seon.wake/arms`, retained reads), not namespace or feature lists.
6. Route agent mistakes as values and core faults through the fan-out.
7. Preserve one cluster-level `:record`/`:panic` decision.
8. Probe transaction latency and identical-value reassertion after listener
   changes.
