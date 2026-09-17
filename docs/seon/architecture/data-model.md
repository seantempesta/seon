---
type: architecture
status: active
tags: [architecture, schema, database, agent]
---

# Data model — attributes, components, and observations

> Target contract: [agent record and turn loop PRD](../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
> §13–§15. The installed schema population is the current implementation
> catalog; the program roadmap owns gaps and ordering.

An entity is its attributes and refs, never a stamped kind. Each stored
attribute is declared once under `resources/seon/schemas/`; the
Malli-to-Datahike bridge derives its storage facets. Function requests,
responses, and live host objects have contracts without thereby becoming
database entities.

## Modeling rules

Identity attributes identify and upsert entities. Plain refs connect
independently lived entities; component refs express ownership and cascade
retraction. Scalars describe the entity; components hold whole concerns.
Cardinality-many is an unordered set, so ordered members carry ordinals.

Optional stored facts are absent, never nil. Omission from an upsert
leaves a fact unchanged; clearing it requires retraction. Maps are open:
declared keys validate rigorously, while additional keys can accrete.
A semantic change needs a new key rather than redefining an old one.

Provenance joins through the datom's transaction and its
`:seon.db/user`, `:seon.db/process`, and `:db/txInstant`.
Do not copy those projections onto domain entities. A computation carries
its immutable database value, schema projection, and environment.
`seon.db` owns database operations and flat boundary errors.

## Agent, turn, and evaluation

The agent record stores identity, namespace, and authored plan facts.
Several agents may share a namespace; `:seon.ns/steward` identifies its
steward. History, unanswered wakes, and routed faults are queries rather
than stored collections on the record.

A turn belongs to an agent and owns provider attempts as components.
A system turn has a reply and no provider attempt, so its authorship is
derived. A turn is open while its closing fact is absent. The writer
decides whether another may open; a caller's pre-read is not its fence.

An evaluation points to its turn and carries source, namespace, comment,
ordinal, read evidence, and its observed outcome. The stable identity is
the twelve-character digest produced by
`(seon.id/evaluation branch-id turn-id ordinal)`. Its handle is
`(seon.id/symbol-in "result" \\e id)`.
The same parts derive the same id; turn order and ordinal determine
history order. An identity upsert does not itself authorize execution:
the turn transition owns the no-repeat decision.

The evaluation stores shown text, out, and error as appropriate.
It does not store a serialized result object, a print node, or a result
blob. Shown text records the one profile-bounded value rendering at
evaluation time. It is a historical observation that cannot be derived
later from a different live value or profile.

## Objects are private process state

Each agent retains one live SCI context across turns. Its private layer
holds defs and atoms as actual objects; its evaluation-id map holds live
results. Accepted program changes enter through base diffs. Private
objects never enter another agent or the cluster base.

Contracted functions, schemas, and tests persist as program rows.
A plain def does not persist. Data intended to survive restart must be
transacted explicitly. Restart loses private state and results while
retaining shown text; there is no serializer or restoration ladder.

## Temporal evidence and additive context

Datahike supplies transaction `:t`. The turn's basis comes from its
identity datom. Every distinct read form's latest evaluation supplies
the evidence and observation point for the since-query diff. Reads
include generated and agent-written forms; a form that transacts or
requests an effect is excluded from refresh.

Changed reads append evaluations in an ordinary system turn. Earlier
evaluations remain unchanged. Empty reads and retractions must be
represented in dependency evidence; missing evidence means unknown.
The full algorithm and diagrams live in [agent runtime](agent-runtime.md).

A wake is an assertion on a schema-declared listened ref attribute whose
value addresses the agent. Its opening behavior is a schema property,
not a hand-maintained list. A system turn containing the wakes' results
answers them under the transaction-order rule. No per-wake acknowledgement
or turn-to-wake copy is needed.

Compaction retracts the agent's evaluations; the next system turn
regenerates the opening. It does not change installed program identities.
Deletion of a program identity is retraction and the past is a temporal
query; there is no retirement attribute and no tombstone row (owner ruling
2026-09-16, program-facts PRD §1f G1, which retires ruling 47's two
corollaries). The reasoning, the alternatives, and the facts that close by a
positive transition instead are in
[the data-modeling decision guide](data-modeling-guide.md).

## Rendering and inspection

One entity schema declares one AI/HTML render pair. Its scalar attributes
share its block; components and declared derived queries render their
concerns. No pair means the default attribute-map printer.

The history is the walk rendering ordered evaluations through their
schema pair. `my.turn/evals` returns evaluation maps filterable by turn
or form, and `my.turn/eval` includes read evidence. Stored shown text
survives restart; live object availability is a separate observation.

At boot open turns close and unfinished evaluations become interrupted.
An interrupted result says an effect may have happened; absent terminal
evidence is not success. Recovery never replays uncertain work.

See [context](context.md), [UI](ui.md), and
[observability](observability.md).
