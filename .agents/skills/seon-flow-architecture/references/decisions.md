---
type: reference
status: active
tags: [reference, flow]
---

# Architecture decisions

Read this when proposing runtime machinery or reviewing why a simpler-looking
alternative was rejected.

## Contents

- [Where the rulings live](#where-the-rulings-live)
- [Agents are flows, not a central loop](#agents-are-flows-not-a-central-loop)
- [Refuse mixed at construction](#refuse-mixed-at-construction)
- [Nothing re-fires](#nothing-re-fires)
- [Channels carry only losable in-flight values](#channels-carry-only-losable-in-flight-values)
- [Presence, not kinds](#presence-not-kinds)
- [Derive, do not store](#derive-do-not-store)
- [One mechanism](#one-mechanism)
- [Namespace UI is built; canvas remains target](#namespace-ui-is-built-canvas-remains-target)

## Where the rulings live

The repository laws are `AGENTS.md`; the current plan's decisions are
[plan §7](../../../../docs/prds/agent-platform/plan/README.md#7-decisions-and-proof-gates).
The earlier numbered ruling ledger was deleted on 2026-09-21 (commit
`215447c46`); read it with
`git show 215447c46^:docs/prds/sci-execution-runtime/plan/README.md` only as
history. Earlier implementations are evidence, not a shape to copy
(`AGENTS.md:42-43`).

This reference explains the reasons that recur in flow work. Check the laws
and the plan before treating any summary here as the latest ruling.

## Agents are flows, not a central loop

The rejected design used one loop/dispatcher/scheduler to maintain active
agents and choose the next unit of work. That recreates a JavaScript event
loop inside the JVM and adds a second scheduling authority beside
core.async.flow and the database.

The law: each agent owns a flow graph and no central scheduler or dispatcher
is added (`AGENTS.md:237-239`). Database facts say which agents and work
exist; each graph derives its own eligible episode when woken.

Current proof:

- the one blueprint, `graph-definition`, with its mailbox, turn and schedule
  procs (`src/seon/cluster/agent.clj:560-607`);
- the derive-all db<->flow, `db<->flow` (`src/seon/cluster/agent.clj:1556`); and
- the measured parked-proc cost, about 8.5 KB and one virtual thread per idle
  graph
  (`git show 215447c46^:docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md`,
  lines 20-42).

This replaced the central-loop model, not merely its namespace.

## Refuse mixed at construction

Core.async's `:mixed` means the proc loop and transform execute inline on a
cached platform thread
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323`).
It does not divide CPU from I/O.

Seon therefore refuses missing or `:mixed` workloads in `var-process`
(`src/seon/flow.clj:286-385`). Construction-time refusal is stronger than a
warning or production metric: an unclassified proc cannot enter a graph. The
law is `AGENTS.md:237`.

This replaced the old willingness to accept core.async's default and discover
the thread cost under scale.

## Nothing re-fires

Recovery does not replay an interrupted effect, form-source suffix, or turn.
At boot every open turn closes and its unfinished evaluations and effects are
stamped interrupted, then graphs are rebuilt and the agent adapts from
durable facts.

Read `recover-call` (`src/seon/turn.clj:1786-1807`), its caller
`recover-runs!` (`src/seon/cluster.clj:3210`), and the boot position: recovery
runs before config application and before any agent arms
(`src/seon/cluster/boot.clj:47-59`). The database records what settled; an
interrupted receipt is evidence for the next agent decision, not authority for
an automatic retry. The law is `AGENTS.md:245-246`.

This replaced replay/retry machinery whose exactly-once claim could not be
proved across external effects.

## Channels carry only losable in-flight values

The transport law divides values by recovery need (`AGENTS.md:239-240`):

- anything recovery or another process may need is a database fact;
- in-flight values may ride channels at full size when loss is free;
- buffers encode whether old values may be superseded, producers must
  backpressure, or observation may drop.

Current examples are the sliding-one agent wake channel
(`src/seon/cluster/agent.clj:131-135`), the cluster's sliding-one db<->flow,
stream, render and pages channels (`src/seon/cluster.clj:3483-3505`).

This replaced both extremes: committing high-churn partial presentation state
as durable history and routing recovery-critical work only through ephemeral
channels.

## Presence, not kinds

An entity is its attributes and relations, not a stamped kind
(`AGENTS.md:188`). Agent identity is discovered by the presence of its
unique identity attribute; graph custody is discovered by presence in the
armed routing map. There is no `:type`, `:kind`, active-set entity, or status
flag.

The derivation is explicit in the routing map (`routing`,
`src/seon/cluster/agent.clj:608`) and db<->flow (`db<->flow`, `:1556`). The
data-model rules are maintained in
[the data guide](../../../../docs/seon/architecture/data-modeling-guide.md).

This replaced object-style taxonomies and stored lifecycle flags that could
disagree with the database or live process.

## Derive, do not store

Status, context, eligibility, current web-surface output, and interest are
projections of existing facts and process-local custody. Persist only source
facts and expensive values that are themselves durable domain truth.

Current examples:

- db<->flow derives missing graph custody from agents minus armed agents;
- turn passes derive work after a payload-free wake; and
- the renderer derives revisioned packages from the current database value; an
  unchanged page produces no new revision (`next-package`,
  `src/seon/render/web.clj:1828-1854`), and each tab receives the smaller
  contiguous delta or the repair keyframe (`package-patches`, `:1856-1866`).

This replaced stored counters, status flags, notification queues, and render
snapshots that required reconciliation.

## One mechanism

When a surviving owner exists, strengthen it in place (`AGENTS.md:86-87`):

- wake selection belongs behind the one cluster wake router;
- work admission belongs in the work launcher (`seon.flow/submit!!`,
  `seon.flow/submit!`);
- core faults belong in the one fault route (`seon.fault/fault!`,
  `src/seon/fault.clj:240`);
- rendering belongs in the one cluster render pipeline until agent-owned
  derivation is deliberately converted; and
- configuration enters through `seon.config/apply!`.

Do not add `-v2`, compatibility namespaces, parallel registries, second feeds,
or side-channel delivery. Delete the superseded path in the same conversion.

This replaced "temporary" duplication that preserved both models and made
tests unable to identify the real owner.

## Namespace UI is built; canvas remains target

The current JVM renderer has canonical namespace pages, root and agent aliases,
and namespace/agent debug variants in the one Reitit route table
(`src/seon/render/route.clj:5-31`). A namespace page renders its first
assigned agent's page, and an unowned namespace renders the agentless
inspection with an explicit create control; a GET never writes
(`canonical-namespace-response`, `src/seon/render/web.clj:3686-3702`). The
debug variant is `debug-response` (`:3558`). Both page kinds acquire through one walk request
(`walk-request`, `src/seon/render/web.clj:3148-3165`) over
`seon.render.walk/neighborhood` (`src/seon/render/walk.clj:698`). Do not
describe context rendering, namespace pages, or debug pages as tabled.

The generalized agent-authored canvas/control API and guarded `/call` route
remain **[TARGET]**: the live route table has neither. Current interaction is
the fixed inbound-message and context-action POST routes
(`src/seon/render/route.clj:17-22`; `inbound`, `src/seon/render/web.clj:2984`;
`context-response`, `:3778`) plus browser-local Datastar signals such as
`showEverything` (`:3570`, `:3657`). Agent-owned `::renders` remains
**[TARGET]**; current delivery already uses revisioned packages with delta and
keyframe bytes, while the agent graph contains mailbox, turn, and schedule
(`src/seon/cluster/agent.clj:560-607`).

These built and target boundaries apply the simpler facts/channels/derived-
render model without restoring the deleted CLJS mechanisms: fresh Seon is
CLJ-only and one JVM runs the system (`AGENTS.md:110`).
