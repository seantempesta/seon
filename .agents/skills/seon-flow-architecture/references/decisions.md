# Architecture decisions

Read this when proposing runtime machinery or reviewing why a simpler-looking
alternative was rejected.

## Contents

- [Use the ruling ledger](#use-the-ruling-ledger)
- [Agents are flows, not a central loop](#agents-are-flows-not-a-central-loop)
- [Refuse mixed at construction](#refuse-mixed-at-construction)
- [Nothing re-fires](#nothing-re-fires)
- [Channels carry only losable in-flight values](#channels-carry-only-losable-in-flight-values)
- [Presence, not kinds](#presence-not-kinds)
- [Derive, do not store](#derive-do-not-store)
- [One mechanism](#one-mechanism)
- [Namespace UI is built; canvas remains target](#namespace-ui-is-built-canvas-remains-target)

## Use the ruling ledger

The authoritative ruling index is
`docs/prds/sci-execution-runtime/plan/README.md`. Follow its numbered rulings
and current ladder rather than copying old implementation shapes from
the Git-history quarry (`AGENTS.md:247-254`).

This reference explains the reasons that recur in flow work. Check the ledger
for exact wording and later supersessions before treating any summary as the
latest ruling.

## Agents are flows, not a central loop

The rejected design used one loop/dispatcher/scheduler to maintain active
agents and choose the next unit of work. That recreates a JavaScript event
loop inside the JVM and adds a second scheduling authority beside
core.async.flow and the database.

The ruled replacement is one independently parked graph per agent, created
from one blueprint. Database facts say which agents and work exist; each graph
derives its own eligible episode when woken.

Plan ruling: the 2026-07-28 agents-are-flows ruling at
`docs/prds/sci-execution-runtime/plan/README.md:475-500`.

Current proof:

- three-proc blueprint at `src/seon/cluster/agent.clj:286-318`;
- derive-all armer at `src/seon/cluster/agent.clj:435-484`; and
- measured parked-proc cost in
  `docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md`.

This replaced the central-loop model, not merely its namespace.

## Refuse mixed at construction

Core.async's `:mixed` means the proc loop and transform execute inline on a
cached platform thread
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323`).
It does not divide CPU from I/O.

Seon therefore refuses missing or `:mixed` workloads in `var-process`
(`src/seon/flow.clj:83-115`). Construction-time refusal is stronger than a
warning or production metric: an unclassified proc cannot enter a graph.

Plan rulings: workload derivation at
`docs/prds/sci-execution-runtime/plan/README.md:256-269` and the
agents-are-flows propagation decision at
`docs/prds/sci-execution-runtime/plan/README.md:490-500`.

This replaced the old willingness to accept core.async's fail-closed default
and discover the thread cost under scale.

## Nothing re-fires

Recovery does not replay an interrupted effect, form-source suffix, or turn.
Reopening the database marks dangling receipts interrupted, rebuilds graphs,
and lets the agent adapt from durable facts.

Read `src/seon/cluster/run.clj:866-930` and the boot recovery position at
`src/seon/cluster.clj:1322-1328`. The database records what settled; absence or
an interrupted receipt is evidence for the next agent decision, not authority
for an automatic retry.

Plan ruling 23:
`docs/prds/sci-execution-runtime/plan/README.md:889-899`.

This replaced replay/retry machinery whose exactly-once claim could not be
proved across external effects.

## Channels carry only losable in-flight values

The transport law divides values by recovery need:

- anything recovery or another process may need is a database fact;
- in-flight values may ride channels at full size when loss is free;
- buffers encode whether old values may be superseded, producers must
  backpressure, or observation may drop.

Current examples are the sliding agent wake at
`src/seon/cluster/agent.clj:286-318`, render/stream inputs at
`src/seon/cluster.clj:1119-1148`, and counted-dropping fault observation at
`src/seon/flow.clj:633-701`.

Plan ruling: the channel-versus-database boundary commissioned by the
agents-are-flows decision at
`docs/prds/sci-execution-runtime/plan/README.md:475-500`.

This replaced both extremes: committing high-churn partial presentation state
as durable history and routing recovery-critical work only through ephemeral
channels.

## Presence, not kinds

An entity is its attributes and connections. Agent identity is discovered by
the presence of its unique identity attribute; graph custody is discovered by
presence in the armed map. There is no `:type`, `:kind`, active-set row, or
status flag.

Current derivation is explicit in the routing map and armer at
`src/seon/cluster/agent.clj:270-335,472-484`. The data-model ruling is maintained in
`docs/seon/architecture/data-model.md`.

Plan ruling: presence-not-kinds decision 2 at
`docs/prds/sci-execution-runtime/plan/README.md:451-467`.

This replaced object-style taxonomies and stored lifecycle flags that could
disagree with the database or live process.

## Derive, do not store

Status, context, eligibility, current web-surface output, and interest are
projections of existing facts and process-local custody. Persist only source
facts and expensive values that are themselves durable domain truth.

Current examples:

- armer derives missing graph custody from agents minus armed agents;
- turn passes derive work after a payload-free wake; and
- the renderer derives revisioned packages from the current database value and
  suppresses equal bytes (`src/seon/render/web.clj:553-600,631-735`).

Plan ruling 19 derives reactivity from render input and display-fact presence:
`docs/prds/sci-execution-runtime/plan/README.md:961-981`.

This replaced stored counters, status flags, notification queues, and render
snapshots that required reconciliation.

## One mechanism

When a surviving owner exists, strengthen it in place:

- wake selection belongs behind the one cluster `listen!` router;
- work admission belongs in `seon.flow/submit!!`;
- core faults belong in one fan-out and committer;
- rendering belongs in the one cluster render pipeline until agent-owned
  derivation is deliberately converted; and
- configuration enters through `seon.config/apply!`.

Do not add `-v2`, compatibility namespaces, parallel registries, second feeds,
or side-channel delivery. Delete the superseded path in the same conversion.

Plan law L17:
`docs/prds/sci-execution-runtime/plan/README.md:1615-1616`.

This replaced “temporary” duplication that preserved both models and made
tests unable to identify the real owner.

## Namespace UI is built; canvas remains target

The current JVM renderer has canonical namespace pages, root and agent aliases,
and namespace/agent debug variants in the one Reitit route table
(`src/seon/render/route.clj:5-34`). Namespace routes resolve through the owning
agent, while the debug response shows the AI and HTML projections together
(`src/seon/render/web.clj:1041-1102,1176-1220`). Both projections use the same
deterministic walk membership and ordering seam
(`src/seon/render/web.clj:300-350,988-1009`;
`src/seon/render/walk.clj:693-876`). Do not describe context rendering,
namespace pages, or debug pages as tabled.

The generalized agent-authored canvas/control API and guarded `/call` route
remain **[TARGET]**: the live route table has neither, and current interaction
is the fixed inbound-message route plus a browser-local checkbox
(`src/seon/render/route.clj:5-27`;
`src/seon/render/web.clj:1027-1037,1104-1110`). Agent-owned `::renders` remains
**[TARGET]**; current delivery already uses revisioned packages with delta and
keyframe bytes, while the agent graph contains mailbox, turn, and schedule
(`src/seon/render/web.clj:553-600,930-1026`;
`src/seon/cluster/agent.clj:286-318`).

These built and target boundaries apply the simpler facts/channels/derived-
render model without restoring the deleted CLJS mechanisms
(`AGENTS.md:20-52`).
