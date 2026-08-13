---
type: architecture
status: active
tags: [architecture, agent, flow, database, web]
---

# Seon runtime architecture

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

This page is the map: the target-system thesis, deployment topology,
cross-domain boundaries, and links to the five domain contracts. It does not
repeat repository law, maintain an API or source-file catalog, or record dated
measurements. `AGENTS.md` owns binding practice and vocabulary; the program
graph owns the live function, namespace, schema, and test catalog.

## Thesis

Seon is a long-lived Clojure runtime where agents and one human build on the
same durable world. Facts live in a temporal Datahike database. A run decision,
agent context, human surface, and forensic view are derived from an explicit
database value rather than synchronized copies. Code is data in that world too:
contracted functions, namespaces, schemas, and tests form a queryable program
graph shared by every agent in one cluster.

One supervised JVM process root hosts one or more clusters. The process root
holds one physical store under a lifetime filesystem lock and owns the shared
executors. Each cluster owns one branch connection, environment, program graph,
agents, shared plumbing, and web endpoint. Each agent owns its own Flow graph
and receives a scoped view of the cluster environment. Clusters share process
resources but never database branches or live program state.

The database is durable coordination; channels are lossy in-flight transport.
The agent and human see different projections of the same rendered values.
External effects cross one guarded boundary and return ordinary admitted data.
After a crash, the process reopens facts, records interrupted custody, and
derives what can happen next; it does not replay uncertain work.

## Topology

### Process root

The process root is the lifetime owner of the physical store lock, bounded
`:compute` executor, virtual-thread `:io` executor, and operator-visible process
identity. One physical store is never opened by two JVMs. A process death stops
all clusters it hosts, but their branch facts remain independently recoverable.

### Cluster

A cluster is one named database branch and the running machinery derived from
it. Boot constructs one environment in dependency order; running code receives
that value instead of fetching process-global state. Datahike serializes
transactions per connection. Reads use immutable database values, so one
computation cannot silently change basis halfway through.

Source publication and live redefinition are distinct. A published
`current-src` commit is the fork point for new clusters. An existing cluster is
a sovereign program until explicitly reforked. Re-evaluating a Var can change
loaded behavior immediately without rewriting that branch's program facts.

### Agent graphs and shared plumbing

Every agent graph comes from one blueprint and is parked between episodes.
Messages and other declared database interests wake the graph to re-derive
work; a wake payload is never the durable work itself. The cluster also owns a
small number of shared render and fault-commit graphs. There is no central
agent dispatcher or scheduler.

Every proc declares `:io` or `:compute`. Latest-value render signals use
sliding-one delivery, work requiring backpressure uses fixed buffers, and
observation may use counted dropping. Channel loss is admissible only when the
value is re-derivable from facts or superseded by a newer complete value.

### Browser and external systems

The browser is a client of the cluster's web endpoint. It receives bounded HTML
packages and submits actions; it owns no database logic or durable UI state.
Model providers, filesystems, shells, and downstream services remain outside
the process. Calls to them are bounded effects whose durable receipts and
settled results live in the cluster database.

Downstream products compose public data and function seams and pin a
coordinated Seon build. Consumer-specific UI, integrations, and domain models
remain downstream rather than becoming a second mechanism inside core.

## Cross-domain boundaries

### Facts and values

Anything recovery or another process may need is a database fact, with bulky
payloads addressed as blobs. A computation carries its database value,
environment, schema projection, render profile, and effect settlement inputs.
Nothing stores a projection that can be derived from those values.

Durable identities and relationships are attributes and refs, not kind stamps
or parallel registries. The admitted schemas are the exact fact census;
[[data-model]] explains only their stable semantic relationships.

### Language and callability

Agents work in Clojure against named, contracted functions over namespaced
data. Every function in a cluster's program graph is callable by every agent.
Context affects discovery and presentation, never execution permission.
Questions such as “what can consume this value?” or “which tests reach this
function?” are program-graph queries, not prose rosters or naming conventions.

### Capability boundary

Protected `seon.*` owners enforce database, evaluation, lifecycle, bounds,
policy, and external-effect crossings. Application namespaces compose those
functions as ordinary program facts; `my.*` is a convention, not a fixed
catalog or security boundary. Filesystem, process, web, model, and database
effects enter `seon.effect/request!` with their request identity.

A portable capability keeps public data, validation, and pure policy in its
shared core while the platform leaf owns native sessions and interop. The same
source or compiled artifact crosses tiers; handwritten mirror APIs do not.
Actual namespaces and contracts are always derived from the program graph.

### Bounded execution and recovery

Detection is event-driven and every execution entrance carries a bound. Agent
work is claimable database state; custody is the presence of the holding
process ref. Settlement fences live in database transitions rather than caller
pre-reads. A bound firing or dead process creates explicit interruption or
fault evidence. Recovery closes or releases wreckage from facts and never
re-executes an uncertain effect.

[[agent-runtime]] owns generated and ordinary episodes, run and receipt
transitions, per-agent graph behavior, and crash recovery.

### Context, rendering, and the human surface

One render contract produces form, AI, and HTML projections of the same value.
Context is a bounded derivation for the next model call; the web UI is a bounded
human projection over the same render blocks. Stable identities permit package
replacement and morphing without storing a second DOM or render snapshot.

[[context]] owns context acquisition, ordering, continuity, and shared-artifact
semantics. [[ui]] owns namespace pages, blocks, the canvas/control boundary, routing, and
human-visible delivery guarantees.

### Errors and evidence

Agent-facing failures are flat values. Core faults become durable facts with
provenance. Prompt captures, provider attempts, forms, eval receipts, messages,
and errors form one evidence spine, but each claim remains bounded: missing
evidence is unknown, never health or proof that an effect did not happen.

[[observability]] owns the forensic questions and joins. Operational logs and
process advertisements support operation; they do not replace durable agent
evidence.

## Domain authority

| Question | Sole architecture owner |
|---|---|
| What durable entities mean and how they relate | [[data-model]] |
| How agents advance, settle, and recover work | [[agent-runtime]] |
| What enters model context and how continuity works | [[context]] |
| What the human sees and how controls cross the browser boundary | [[ui]] |
| What evidence can support a forensic claim | [[observability]] |

Editing mechanics belong to the triggered skill. Current state and ordering
belong to [[roadmap]] and its working edge. Dated evidence, measurements, and
rejected alternatives belong to bounded PRDs and research records. Binding
repository behavior and settled vocabulary belong to `AGENTS.md`.

## Further orientation

- [[roadmap]] — rulings, current implementation state, and work order.
- [[datahike-primer]] — the source-grounded database-value and transaction
  mindset.
- `docs/seon/vision/` — the product premise and longer horizon.
