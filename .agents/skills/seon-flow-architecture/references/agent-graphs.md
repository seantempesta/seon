# Agent graphs

Read this when changing the per-agent blueprint, arming lifecycle, episode
limits, or the proposed agent-owned render proc.

## Contents

- [Current blueprint](#current-blueprint)
- [Mailbox and turn procs](#mailbox-and-turn-procs)
- [Arming and the derive-all pass](#arming-and-the-derive-all-pass)
- [Custody is presence](#custody-is-presence)
- [Episode caps and retry semantics](#episode-caps-and-retry-semantics)
- [Measured graph cost](#measured-graph-cost)
- [TARGET: the renders proc](#target-the-renders-proc)
- [Change checklist](#change-checklist)

## Current blueprint

Each agent owns one independent core.async.flow graph. The graph definition is
data built by `src/seon/cluster/agent.clj:240-264`; it currently has exactly:

- `::mailbox`, workload `:io`;
- `::turn`, workload `:io`; and
- one `(sliding-buffer 1)` connection from mailbox output to turn input.

There is no central loop, dispatcher, active-set, or scheduler entity in fresh
`src/`. The armer derives agents and creates these graphs independently.

Both procs use `seon.flow/var-process`, so their step functions remain live
Vars. Redefine behavior in place; rebuild only when proc topology, connections,
ports, or buffers change (`src/seon/flow.clj:83-115`).

## Mailbox and turn procs

The mailbox step at `src/seon/cluster/agent.clj:117-143` is deliberately
small. On input it emits a payload-free wake. The connection is sliding-1, so
many messages may collapse into one signal without losing durable work.

The turn step at `src/seon/cluster/agent.clj:160-234` treats the wake as “derive
current work from facts.” It invokes the cluster turn pass and returns proc
state. It does not carry a durable message or run through the channel.

This separation makes the loss rule explicit:

- messages, runs, receipts, and settled replies are database facts;
- the mailbox signal is disposable because a pass re-derives all eligible
  work; and
- losing a wake is safe only when another current-state derivation is
  guaranteed by arming or a later committed transaction.

## Arming and the derive-all pass

`arm!` creates and starts or resumes one graph, joins its error fan-out,
registers the route, and primes its mailbox
(`src/seon/cluster/agent.clj:337-398`).

The armer proc derives the set difference between database agents and already
armed agents, sorts it, and arms every missing agent
(`src/seon/cluster/agent.clj:435-484`). Do not replace this derive-all pass
with a one-event “new agent” payload. A wake can collapse, so the consumer must
recover every missing graph from current facts.

Boot performs the same derive-all work directly after the cluster graph starts
(`src/seon/cluster.clj:1208-1229`). That direct first pass closes the
register-interest-before-read boundary: the armer route exists before current
database state is derived.

## Custody is presence

An armed graph is identified by the presence of its agent identity in the
process-local armed map; there is no stored `:kind`, active flag, or scheduler
membership. The database says which agents exist. Process-local graph custody
says which of those currently have a graph.

Keep this distinction:

- durable identity and run eligibility are facts;
- a live flow handle is process-local custody;
- restart discards handles and derives custody again; and
- a “status” field duplicating that relationship would become stale.

Read the routing map and armer's `agents`/`armed` set derivation at
`src/seon/cluster/agent.clj:270-335,472-484`.

## Episode caps and retry semantics

The maximum consecutive runs per episode is a database-backed config dial.
Its schema and default live in the config section of `resources/seon/schema.edn` and
`config/default.edn:81-95`. The work loop reads and enforces it in
`src/seon/cluster/work.cljc:424-441,484-517`.

The cap is runaway protection, not a retry scheduler. Nothing re-fires a
failed turn after recovery. Recovery marks dangling receipts interrupted and
the agent adapts from durable context
(`src/seon/cluster/run.cljc:866-930`; `src/seon/cluster.clj:1322-1328`).

Do not add:

- a retry queue outside database facts;
- an agent-active bit;
- a timer that polls for eligible work; or
- a central loop that owns every agent's episode.

## Measured graph cost

The July 28 flow probe ran each section in a fresh JVM on an 18-core Mac with
JDK 26 and `-Xmx512m`. Its idle case used one-proc graphs sharing the default
executors; its lifecycle case ran 1,000 create/start/resume/stop cycles of a
three-proc, two-connection graph. It measured:

- about 8.5 KB and one virtual thread per parked proc — parked meaning
  **`:running` and blocked on a channel read**
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:295`),
  which is the state Seon keeps agents in; no flow-`paused` graph has been
  measured, so do not cite this as a paused-agent cost
  (`docs/prds/sci-execution-runtime/research/flow-control-protocol-2026-07-31.md`);
- about 8.3 MB for 1,000 measured one-proc graphs;
- 21.6 ms to start those 1,000 one-proc graphs; and
- about 0.084 ms per stop/start lifecycle for measured three-proc graphs.

Read the exact host, JDK, heap method, warm-up, and samples in
`docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md`.
The lifecycle timing ends when the stop API returns; it is not an exit-join
measurement.
The current two-proc blueprint therefore suggests roughly two parked virtual
threads and 17 KB of proc baseline per agent, but that multiplication is an
inference from the per-proc measurement, not a separately measured production
heap total.

## TARGET: the renders proc

The intended third proc, `::renders`, would own the agent's derived AI and HTML
views in one memoized proc state. It is **not built**: current
`graph-definition` has only mailbox and turn
(`src/seon/cluster/agent.clj:240-264`).

The July 29 falsifier compared 100 parked agents in an in-memory database on
JDK 26 with `-Xmx512m -XX:+UseG1GC`; it discarded two warm-ups, forced three
GCs with a 120 ms settle before heap reads, and parked 400 ms. It built a
disposable experimental proc with:

- 12 registrations in one pass: eight AI and four HTML;
- +8.9 KB measured heap per agent;
- zero added platform threads;
- about +19 microseconds arm cost in the 100-agent comparison; and
- successful cross-namespace resolution.

It also falsified two assumptions:

- naive attribute interest was too broad; and
- retaining arbitrary render values in proc state had no bounded-memory
  contract.

Read the complete method and result tables in
`docs/prds/sci-execution-runtime/research/agent-flow-render-falsification-2026-07-29.md`.
Do not cite only the favorable numbers. The experiment settled feasibility,
not the production contract.

Before authoring `::renders`, settle:

1. the registration source and stable identity;
2. attribute/query interest derivation;
3. the bounded retained representation (bytes/digests, not arbitrary values);
4. AI and HTML projection error values;
5. its input buffer's loss semantics; and
6. the handoff to per-cluster delivery.

Production delivery remains the cluster JVM renderer in
`src/seon/render/web.clj`; do not move sockets or per-tab state into every
agent graph.

## Change checklist

1. Preserve the one graph per agent boundary.
2. Make every signal payload disposable and derive work from facts.
3. Register routes/listeners before the first derive-all pass.
4. Represent custody by the live map's presence, not stored state.
5. Keep episode limits database-backed and semantically distinct from retry.
6. Mark `::renders` work **[TARGET]** until the current source contains it.
7. Re-run heap and lifecycle probes after changing proc count or proc state.
