---
name: data-modeling
description: "Design Seon attributes, identities, refs, components, and function contracts in the canonical schema population. Use for schema EDN and domain-model changes; database operations belong to datahike."
---

# Model declared attributes and whole concerns

Use [AGENTS.md](../../../AGENTS.md) for the design laws and
[the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 for the current record, rendering, and result target.
Start by querying the merged registry before declaring another shape.

## One declaration, derived storage

The classpath resources under `resources/seon/schemas/` form one
population. Duplicate keys refuse; `packaged-forms` returns the
declarations (`src/seon/schema/edn.clj:316`, `:393`).
The schema bridge consumes a supplied projection and derives Datahike
facets (`src/seon/schema/datahike.clj:232`).

| Intent | Declaration | Derived behavior |
|---|---|---|
| Natural identity | `[:string {:seon.db/identity true}]` | Unique identity and upsert |
| Link | `:seon.db/ref` | Ref to another entity |
| Owned child | `[:seon.db/ref {:seon.db/component true}]` | Component ownership |
| Many members | `[:set :seon.db/ref]` | Cardinality-many, unordered |
| Optional value | Optional map entry, omitted when absent | No stored nil |
| Relation to a NAME that must outlive the target | `[:set :qualified-symbol]` — a VALUE edge, not a ref | Cardinality-many symbols; retracting the named entity touches no edge (`reference-code/datahike/src/datahike/db/transaction.cljc:998-1015` sweeps incoming REF datoms only) |
| "We looked" — an analysis actually ran | A required positive fact naming what it read (file digest, evaluation) | Presence is `analyzed?`; an empty result is then ordinary absence of edges |

The bridge's type, cardinality, and property owners are
`src/seon/schema/datahike.clj:123`, `:205`, and `:232`.

The two added rows are one rule. A `:seon.db/ref` asserts a relation
between two ENTITIES; what an analyzer knows is a NAME appearing in text,
and a name denotes itself. And `#{}` stores nothing — `explode` emits one
datom per member (`transaction.cljc:739-770`, `:718-737`), so "found
nothing" and "never ran" are the same bytes unless the looking is its own
datom. If a reader will ever need to distinguish them, declare the event.
Grounding:
[the deletion study](../../../docs/prds/steward-platform/research/datahike-deletion-and-the-program-graph-2026-09-16.md)
§8 and program-facts PRD §1f G2/G4.

Cardinality-many does not acquire order because a Malli declaration used
a vector. Store an ordinal on ordered members.

Use fully namespaced keys and reuse declared constraints by reference.
An entity is its attributes and connections, never a stored kind.
Scalars are facts other queries can use independently; components hold
whole owned concerns. Derived concerns are queries, not stored mirrors.

An entity map's `:seon.db/attributes` property contributes its entry
attributes to storage derivation; it does not stamp a kind on an entity.
The decomposition owner is `src/seon/schema/datahike.clj:319`.

## Contracts and honest generators

API-like functions use named request/response maps; ordinary operations
may use named positional contracts. Public functions carry complete
Malli input/output contracts. Adding optional data is accretion;
requiring more or promising less changes the contract.

Stored nilable shapes refuse at
`src/seon/schema/datahike.clj:170`. Clearing is a retraction;
omission from an upsert is not a clear operation.
Do not generalize allowances for in-memory polymorphic results to
stored attributes.

Authored incomplete contracts and predicate requirements are checked at
`src/seon/schema/internal.cljc:119`. Malli generator overrides are
selected at `reference-code/malli/src/malli/generator.cljc:466`;
mapping an output occurs at `:474`. Neither proves the generated
value satisfies the target schema. Use fixed seeds, generate and
validate against the same projection, and exercise meaningful domain
partitions. The clojure-testing skill owns the fixture and assertion.

Config composites already derive from leaf declarations
(`src/seon/schema/edn.clj:66`). A new dial belongs in its owning
schema family, not a manually maintained second composite.

## Record and render contract — target

One entity schema declares one AI/HTML render pair. Scalars share its
own block; component entities render whole concerns. The agent schema
names derived query functions once. No pair means the default
attribute-map printer. Never declare a pair per scalar attribute.

A render function chooses forms from the data. `dir` returns public
function data; `doc` returns the full docstring and contract.
A `my.*` read returns small maps with the item's own keys;
a write returns the changed entity.

History, unanswered wakes, and routed faults remain queries.
System turns store generated evaluations in the same family as
agent-written evaluations. “System” derives from reply presence and
no provider attempt. Do not add an author-kind stamp or a parallel
generated-form family.

## Results and identity — target

The agent retains one SCI context receiving base diffs across turns.
Its private defs, atoms, and result objects remain in memory.
Shown text is stored because it records what was seen, not because
it can restore the object. The profile is applied once at evaluation
time; no result blob, print-node encoding, or separate result byte cap.

Use `seon.id/evaluation` for branch/turn/ordinal identity and
`seon.id/symbol-in` for its result handle
(`src/seon/id.clj:55`, `:47`). Do not add a random-id generator.

Read evidence is a dependency observation, not a copied result:
`src/seon/db.clj:784`. Every distinct read form's latest evidence
feeds the since-query diff; changed reads append, writes/effects never
rerun. Compaction retracts evaluations and regenerates the opening.
Program identity tombstones are RETIRED by owner ruling 2026-09-16
(program-facts PRD §1f G1/G3): deletion is `[:db/retractEntity …]` and the
past is a temporal query. The tombstone machinery
(`seon.db/write-tombstone-validator`, `src/seon/db.clj:2913`) is still in
the tree until the edge schema lands; do not build on it.
