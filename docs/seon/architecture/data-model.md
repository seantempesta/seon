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
never a stamped kind. Function arguments, return values, and process-local host
objects remain Malli contracts rather than database entities.

[[agent-runtime]] owns transitions over these facts. [[context]] and [[ui]]
own their projections. [[observability]] owns their forensic interpretation.

## Modeling contract

- Every stored attribute is declared once under `resources/seon/schemas/`.
  `seon.schema.datahike/malli->datahike-schema` derives Datahike value type,
  cardinality, uniqueness, indexing, component ownership, and history facets.
- Attribute presence describes an entity. A unique identity attribute names
  one; there is no stored `:type` or `:kind` discriminator.
- A plain `:seon.db/ref` connects independently lived entities. A component ref
  owns its child and cascades retraction. Cardinality-many is an unordered set;
  ordered children carry an ordinal.
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
- Running code routes database operations through `seon.db`. Callers may carry
  an explicit database value or connection; guarded agent evaluation may elide
  it through the scoped environment. Boundary failures are flat error values.

## Durable relationships

A cluster branch is rooted at the entity identified by `:seon.cluster/name`.
Agents point to that cluster and may point to an owned namespace and one open
run. A message's `:seon.cluster.message/to` ref wakes the recipient's graph.
The root agent is simply the agent identified by `"root"`; it has no second
identity or lifecycle kind.

A run points back to its agent and component-owns its forms through
`:seon.cluster.run/forms`. Each form also retains its forward
`:seon.cluster.run.form/run` ref, so form-to-run queries and recovery do not
depend on walking the component collection. Eval receipts are independently
lived evidence: they point to the run through `:seon.cluster.eval/run` and are
not run components. Form ordinals establish order; receipt ordinals join each
attempt to its form.

Context captures and provider attempts likewise point to the run as
independent evidence. An adopted revision points from the proved run to its
original through `:seon.cluster.run/supersedes`; both histories remain durable
while active-run projections exclude superseded originals.

Program rows and the agent's defs are separate durable families. Contracted
definitions join the cluster's program graph. Uncontracted definitions and
atoms persist as agent-scoped `:seon.def` facts. Namespace ownership says who
should edit a namespace; it never gates which functions an agent may call.

## Presence is state

Stored facts express state without duplicate status rows:

- an agent's `/run` ref is present exactly while it has an open run;
- a run is open while `/closed-at` is absent;
- run custody is the presence of `/process`;
- `/sources-digest` records that ordinary reply forms are frozen;
- an eval receipt is terminal when result, error, or interruption is present;
- a run's `/interrupted-at` records recovery even when the dead process left no
  receipt to stamp; and
- an adopted revision is the presence of a run that `/supersedes` the original.

The exact state-bearing attributes and their constraints live in the schemas,
not in an architecture-maintained attribute table.

## Identity, refs, and ownership

`{:seon.db/identity true}` derives Datahike unique identity, making lookup refs
the ordinary identification and upsert form. Before adding an identity, query
the merged schema registry: identity is for a stable natural identity, not an
entity-family stamp.

Use plain refs for independently retained facts such as cluster membership,
agent ownership, messages, receipts, captures, attempts, and program-graph
edges. Use component refs only where the parent owns a bounded child. Current
examples include run forms, context contributions, function arities and Malli
AST nodes, namespace bindings, and test failure detail. The declaring schema,
not this list, is authoritative when ownership changes.

## Ordering and history

Datahike cardinality-many values are sets. Any durable sequence therefore
stores an explicit ordinal on its members and orders with a deterministic
tie-breaker. Source text and other replaceable bulky values may opt out of
history in their schema; transaction provenance and relationship facts remain
queryable through Datahike history.

Recovery never infers that absence of evidence means success. A terminal
receipt proves settlement; interruption says an effect may have occurred; a
form without a terminal receipt produced no recorded result. [[observability]]
owns that bounded forensic reading.

## Schema authority

The schema directory is the machine-readable catalog. Start with the family
that owns the question, then follow its refs:

- cluster, agent, message, run, form, and eval relationships:
  `seon.cluster*.edn`;
- prompt evidence, provider attempts, and errors: `seon.context*.edn`,
  `seon.ai*.edn`, and `seon.error.edn`;
- program graph and agent definitions: `seon.fn*.edn`, `seon.ns*.edn`,
  `seon.schema*.edn`, `seon.test*.edn`, and `seon.def.edn`;
- rendering and bounded output: `seon.render*.edn` and `seon.print.edn`; and
- schedules and maintenance evidence: `seon.schedule*.edn` and
  `seon.maintenance*.edn`.

Use registry and Datalog queries to answer “which attributes, identities, refs,
or components exist?” A copied census would be stale the moment a schema
accreted.

Routes, browser sessions, Flow channels, executor handles, and operator process
state are not agent-domain entities merely because they have runtime names.
They stay in their owning process-local or operator boundaries unless recovery
or another process needs them as facts.

## See also

- [[architecture]] — process and cluster topology.
- [[agent-runtime]] — generated openings, ordinary runs, transitions, and
  recovery.
- [[context]] — prompt and contribution derivation.
- [[observability]] — receipts and bounded forensic claims.
- [[ui]] — projections of the same database value.
