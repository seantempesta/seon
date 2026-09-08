---
type: architecture
status: active
tags: [architecture, schema, database, agent]
---

# The Seon data model — attributes and connections

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

Seon's durable model is the admitted EDN population under
`resources/seon/schemas/`. Those declarations are the exact, current census;
this page explains only the relationships architecture prose relies on. A
database entity is the attributes it carries and the connections it follows,
never a stamped kind. Function arguments, return values, and process-local
host objects remain Malli contracts rather than database entities.

[[agent-runtime]] owns transitions over these facts. [[context]] and [[ui]]
own their projections. [[observability]] owns their forensic interpretation.

## Modeling contract

- Every stored attribute is declared once under `resources/seon/schemas/`.
  `seon.schema.datahike/malli->datahike-schema` derives Datahike value type,
  cardinality, uniqueness, indexing, component ownership, and history facets.
- Attribute presence describes an entity. A unique identity attribute names
  one; there is no stored `:type` or `:kind` discriminator.
- A plain `:seon.db/ref` connects independently lived entities. A component
  ref owns its child and cascades retraction. Cardinality-many is an
  unordered set; ordered children carry an ordinal.
- Optional stored values are absent, never nil. Clearing is an explicit
  retraction; omission from an upsert leaves the current value.
- Malli maps are open. Adding an optional key is accretion; requiring more or
  promising less is breakage.
- A registered reference predicate declares
  `:seon.schema/identity-only` and a qualified projection. Admission retains
  only that identity at every depth; it never admits a live database or
  connection graph.
- Transaction provenance belongs on the transaction entity as
  `:seon.db/user`, `:seon.db/process`, and Datahike's `:db/txInstant`. Domain
  entities do not copy it.
- Stored symbols are scalar values. Program relationships use refs to the
  canonical `:seon.fn`, `:seon.ns`, `:seon.schema`, and `:seon.test` entities.
- Running code routes database operations through `seon.db`. Callers may
  carry an explicit database value or connection; guarded agent evaluation
  may elide it through the scoped environment. Boundary failures are flat
  error values.
- Two schema properties, `:seon.wake/listen` and `:seon.wake/opens-turn?`,
  are lifted onto a ref attribute's own schema row as facts, not carried on
  the data. "Which attributes wake an agent" is therefore a Datalog query
  over `:seon.wake/listen`, never a hand-maintained list.

## Durable relationships

A cluster branch is rooted at the entity identified by `:seon.cluster/name`.
An agent's whole stored record is three attributes: `:seon.agent/id`,
`:seon.agent/namespace` (a ref to its `:seon.ns`, not unique — several
agents may share a namespace), and the `:my.plan/*` facts it stores on
itself. Everything else about an agent — its history, what it is waiting on,
whether it has an open turn — is a query over turns and evaluations, never a
stored back-edge or wrapper entity. The root agent is simply the agent
identified by `"root"`; it has no second identity or lifecycle kind.

A **turn** points back to its agent and component-owns its provider
`:seon.turn/attempts`. **Evaluations** are independently lived evidence: each
points to its turn through `:seon.eval/turn` and is identified by
`:seon.eval/id`, a `:db.unique/identity` string over `(turn, ordinal)` — the
structural fence that makes re-freezing an already-settled ordinal an upsert,
never a duplicate. There is no separate frozen-form family: an evaluation
carries both the form's source (`comment`, `source`, `ns`) and, once run, its
terminal facts (`value`/`missing`, `out`, `error`, `duration-ms`,
`interrupted-at`) on the one entity.

A **wake** is a datom asserted on a ref attribute whose schema row declares
`:seon.wake/listen true` — `:seon.message/to`, `:seon.error/steward`,
`:seon.schedule.firing/agent`, and `:seon.effect/to` today. The entity
carrying that datom (a message, a fault, a firing) is ordinary data; no wake
entity, claim, or back-reference exists. A wake is answered by a later turn's
own transaction `:t`, never by a stored ref from turn to wake.

Program rows are a separate durable family, joined to a turn or evaluation
only through ordinary refs (a program row an evaluation's form defines) or
not at all. There is no `:seon.def` family: an agent's uncontracted,
in-fork definitions are not persisted, and a `defn` an agent wants kept
becomes a program row the same way any contracted definition does. Namespace
ownership (`:seon.ns/steward`) says who should edit a namespace; it never
gates which functions an agent may call.

## Presence is state

Stored facts express state without duplicate status rows:

- a turn is open while `/closed-at` is absent — there is no agent-level
  open-turn pointer, because "the agent's open turn" is the query "the turn
  of this agent with no `closed-at`", enforced as a writer fence on `open`
  itself;
- an evaluation is terminal when `value`/`missing`, `error`, or
  `interrupted-at` is present; no terminal fact means not yet evaluated;
- a wake is answered when some turn of that agent has a basis `:t` at or
  after the wake's own `:t` AND that turn's reply came from a model attempt
  — derived entirely from the `:t` every datom already carries, never a
  stored flag;
- `:seon.turn/attempts` presence with no evaluations means the turn's model
  call happened but nothing was read yet, or all reads settled with no
  side-effecting form; a turn with attempts but a reply that produced no
  evaluations is not itself a distinct stored state — it is simply a turn
  whose reply had nothing to freeze; and
- there is no process-custody attribute anywhere in this model. At boot, a
  turn with no `closed-at` belongs to a dead process by construction (one
  JVM, one lifetime `flock` per store, one in-memory turn permit per
  agent), so "custody" needs no stamp to represent — its absence at any
  live moment is simply "another turn may not open."

The exact state-bearing attributes and their constraints live in the
schemas, not in an architecture-maintained attribute table.

## Identity, refs, and ownership

`{:seon.db/identity true}` derives Datahike unique identity, making lookup
refs the ordinary identification and upsert form. Before adding an identity,
query the merged schema registry: identity is for a stable natural identity,
not an entity-family stamp.

Use plain refs for independently retained facts such as cluster membership,
agent namespace ownership, messages, evaluations, and program-graph edges.
Use component refs only where the parent owns a bounded child — a turn's
provider attempts, function arities, Malli AST nodes, namespace bindings,
and test failure detail are current examples. The declaring schema, not this
list, is authoritative when ownership changes.

## Ordering and history

Datahike cardinality-many values are sets. Any durable sequence therefore
stores an explicit ordinal on its members and orders with a deterministic
tie-breaker — an evaluation's `ordinal` within its turn is the current
example. Source text and other replaceable bulky values may opt out of
history in their schema; transaction provenance and relationship facts
remain queryable through Datahike history.

Recovery never infers that absence of evidence means success. A terminal
evaluation proves settlement; `interrupted-at` says an effect may have
occurred; an evaluation with no terminal fact produced no recorded result.
[[observability]] owns that bounded forensic reading.

## Schema authority

The schema directory is the machine-readable catalog. Start with the family
that owns the question, then follow its refs:

- agent, turn, evaluation, and wake relationships: `seon.agent.edn`,
  `seon.turn.edn`, `seon.eval.edn`, and `seon.wake.edn`;
- messages, faults, and provider attempts: `seon.message.edn`,
  `seon.error.edn`, and `seon.ai*.edn`;
- program graph: `seon.fn*.edn`, `seon.ns*.edn`, `seon.schema*.edn`, and
  `seon.test*.edn`;
- rendering and bounded output: `seon.render*.edn` and `seon.print.edn`; and
- schedules and maintenance evidence: `seon.schedule*.edn` (including the
  per-firing entity) and `seon.maintenance*.edn`.

Use registry and Datalog queries to answer "which attributes, identities,
refs, or components exist?" A copied census would be stale the moment a
schema accreted.

Routes, browser sessions, Flow channels, executor handles, and operator
process state are not agent-domain entities merely because they have runtime
names. They stay in their owning process-local or operator boundaries unless
recovery or another process needs them as facts.

## See also

- [[architecture]] — process and cluster topology.
- [[agent-runtime]] — the agent record, the turn loop, waking, and recovery.
- [[context]] — byte-identical projection and continuity.
- [[observability]] — evaluations and bounded forensic claims.
- [[ui]] — projections of the same database value.
