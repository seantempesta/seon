# Wakes and faults

Read this when routing database commits into flows, deriving render interest,
or changing fault capture and `:record`/`:panic` behavior.

## Contents

- [One listener router](#one-listener-router)
- [The two hard listener rules](#the-two-hard-listener-rules)
- [Register before derive](#register-before-derive)
- [Current interest routing](#current-interest-routing)
- [Historical E/A/V machinery worth reusing](#historical-eav-machinery-worth-reusing)
- [Fault fan-out](#fault-fan-out)
- [The config dial](#the-config-dial)
- [Change checklist](#change-checklist)

## One listener router

Fresh Seon installs one Datahike `listen!` router per cluster. `route!` lives at
`src/seon/cluster/wake.cljc:163-228` and receives transaction reports.

The router currently:

- offers an armer wake when an agent identity datom appears;
- offers an agent mailbox wake for affected message recipients;
- offers every report to the cluster render input; and
- catches `Throwable` so an observer failure does not escape the listener.

Do not add one Datahike listener per agent, render, page, or feature. Derive
interest behind this router and route to existing graph inputs.

The routed values are signals or reports, not a second durable work log.
Messages and identities are already database facts.

## The two hard listener rules

The owning source documents two measured rules at
`src/seon/cluster/wake.cljc:6-63`.

### Never throw

Datahike invokes listeners before the transaction's delivery completes. An
exception can therefore prevent the caller from receiving a transaction
report even though commit state has advanced. Catch at the router boundary and
send a core fault through the normal fault path; never make observer success a
condition of transaction delivery.

### Never park

Datahike invokes listeners before delivering the transaction report
(`reference-code/datahike/src/datahike/writer.cljc:384-386`). Listener work
therefore remains on the transaction's critical path.

Use non-blocking `offer!` into already-buffered flow inputs. Do not use
blocking channel operations, database queries, rendering, logging transports,
or model calls in the listener.

The same source records a third consequence: reasserting an identical value
produces no datom, so attribute-driven routing produces no wake. Consumers
must not depend on a “write attempt” that the database does not report.

## Register before derive

The safe order is:

1. install the listener or route;
2. derive current work from the database value; and
3. process later transaction reports.

Reversing the first two steps creates a lost interval between the initial read
and listener registration.

Agent arming follows this rule: the cluster starts the graph, installs wake
routing, then invokes the armer's derive-all pass directly
(`src/seon/cluster.clj:1191-1229`). Keep that direct pass. A synthetic “boot
wake” sent before route registration is not equivalent.

## Current interest routing

Current routing is intentionally incomplete:

| consumer | current interest |
|---|---|
| armer | datoms with agent identity |
| agent mailbox | message recipient refs |
| cluster renderer | every transaction report |

Verify the actual datom extraction and offers at
`src/seon/cluster/wake.cljc:163-228`.

Do not describe the current renderer as attribute-selective. It receives every
report, then its own equality checks suppress unchanged output
(`src/seon/render/web.clj:497-549`). Attribute/query interest derivation is
target work needed by agent-owned renders.

## Historical E/A/V machinery worth reusing

The deleted pod writer contains a source-grounded design for selective
interest. Treat it as quarry, not live code:
`src-old/seon/db/writer.clj:2756-3205`.

Its useful design is:

1. Normalize each interest into attribute dependencies and optional E/A/V
   patterns (`src-old/seon/db/writer.clj:2756-2847`).
2. Derive query attributes from Datahike's dependency plan rather than a
   handwritten attribute list (`src-old/seon/db/writer.clj:2848-2981`).
3. Match entity/attribute/value pattern positions against transaction datoms
   (`src-old/seon/db/writer.clj:2982-3002`).
4. Index candidate interests by affected attribute
   (`src-old/seon/db/writer.clj:3174-3189`).
5. Apply the complete pattern before delivery
   (`src-old/seon/db/writer.clj:3191-3205`).

Reuse the construction lessons, not the namespace, atoms, or old writer
boundary. Fresh Seon owns one cluster listener in `seon.cluster.wake`; any
replacement strengthens that one router.

The design obligation is conservative completeness:

- never omit a consumer whose query could change;
- accept extra candidates only when downstream equality suppression is cheap;
- derive attributes from maintained query/dependency data; and
- keep pattern matching pure and testable outside the listener.

## Fault fan-out

Every graph exposes core.async.flow's error and report channels. Seon joins
them into one fan-out:

- monitor taps use sliding buffers;
- the fault tap uses a counted-dropping buffer so observation cannot block the
  producing graph;
- each agent graph joins the cluster fault channel; and
- a `fault-committer-proc` turns each fault into durable database data.

Read `src/seon/flow.clj:553-715`. The committer proc itself is defined at
`src/seon/flow.clj:553-602`.

A counted-dropping channel means overload may drop observations. That is
acceptable only because a fault record is observational data about a core
failure, not a request whose execution must be recovered from that channel.
If a future fault is required for recovery, commit it at the owning boundary
instead of relying on this tap.

Agent mistakes do not belong on the core fault channel. SCI/runtime boundaries
return flat `:seon.error` values for the agent to see; reserve flow errors for
system defects.

## The config dial

The cluster reads one `:seon.config/on-core-error` decision and passes it to
the fan-out at `src/seon/cluster.clj:1151-1190`.

Current modes are declared at `resources/seon/schema.edn:595`:

- `:record`: commit the fault and keep the graph operating where possible;
- `:panic`: call the supplied panic handler.

The current cluster panic handler prints the fault; it does not throw from the
recorder or terminate the JVM. Describe current behavior exactly. If the dial
is strengthened later, change the one handler rather than adding per-site
panic decisions.

## Change checklist

1. Keep one `listen!` router per cluster.
2. Catch everything at the listener boundary.
3. Use only non-blocking offers from the listener.
4. Register the route before deriving current work.
5. Derive interest from queries/dependencies, not namespace or feature lists.
6. Route agent mistakes as values and core faults through the fan-out.
7. Preserve one cluster-level `:record`/`:panic` decision.
8. Probe transaction latency and identical-value reassertion after listener
   changes.
