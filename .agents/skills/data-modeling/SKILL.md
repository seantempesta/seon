---
name: data-modeling
description: "Design Seon attributes, identities, refs, components, and function contracts in the canonical schema population. Use for schema EDN and domain-model changes; database operations belong to datahike."
---

# Model declared attributes and whole concerns

Use [AGENTS.md](../../../AGENTS.md) for the design laws and
[the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 for the current record, rendering, and result target.
Start by querying the merged registry before declaring another shape.
[The data-modeling decision guide](../../../docs/seon/architecture/data-modeling-guide.md)
consolidates every ruled answer — retract versus close by a positive fact, ref
versus value versus component versus tuple, required-ness, events, and what is
a query instead — with its ruling and its Datahike grounding.

## One declaration, derived storage

The classpath resources under `resources/seon/schemas/` form one
population. Duplicate keys refuse within and across resources
(`src/seon/schema/edn.clj:216-228`, `:307-330`); `packaged-forms` returns the
declarations (`:415`). The schema bridge consumes a supplied projection and
derives Datahike attribute declarations (`malli->datahike-attr-in`,
`src/seon/schema/datahike.clj:141-180`).

| Intent | Declaration | Derived behavior |
|---|---|---|
| Natural identity | `[:string {:seon.db/identity true}]` | Unique identity and upsert |
| Link | `:seon.db/ref` | Ref to another entity |
| Owned child | `[:seon.db/ref {:seon.db/component true}]` | Component ownership |
| Many members | `[:set :seon.db/ref]` | Cardinality-many, unordered |
| Optional value | Optional map entry, omitted when absent | No stored nil |
| Relation to a NAME that must outlive the target | `[:set :qualified-symbol]` — a VALUE edge, not a ref | Cardinality-many symbols; retracting the named entity touches no edge (`reference-code/datahike/src/datahike/db/transaction.cljc:999-1016` sweeps incoming REF datoms only) |
| "We looked" — an analysis actually ran | A required positive fact naming what it read (file digest, evaluation) | Presence is `analyzed?`; an empty result is then ordinary absence of edges |

The bridge derives type and cardinality in one fold, `compiled-storage`
(`src/seon/schema/datahike.clj:77-139`), and properties in
`malli->datahike-attr-in` (`:141-180`).

## The one question that decides a ref: statement or token?

Before declaring any `:seon.db/ref`, ask what the fact MEANS to the writer
that produced it.

- **A STATEMENT ABOUT A LIVING ENTITY.** The referrer asserts a relation to
  something that must exist. If the referrer owns the target as part of its
  value, the parent-to-child attribute carries `:seon.db/component true`
  and the child dies when the parent is retracted. Otherwise they are peers,
  and the fact becomes false when the target goes. A peer's deletion policy is
  already declared, by required-ness: a required ref in the referrer's entity
  map REFUSES a sweep only when the surviving row is selected and validated;
  an optional one lets it SWEEP silently. Required presence is not a native
  foreign-key constraint: numeric ref values do not prove target existence
  (`reference-code/datahike/src/datahike/db/utils.cljc:109-148`). See the
  datahike skill for the writer chain and complete owned-value check.
- **AN OBSERVATION OF A TOKEN.** The writer saw a NAME — in source text, in
  metadata, in a note, in a report — and a name denotes itself. Whether
  anything by that name exists is a SEPARATE, derivable question. Store the
  VALUE (`:qualified-symbol`, `:qualified-keyword`, the path string), never a
  ref. Then deleting the named entity touches no observer's datom, and "names
  something with no row" is one `not-join` clause instead of an
  impossibility.

The tell for a misfiled token is machinery that exists only to survive a
rename or a republish: a `:seon.fn/reference-to` annotation whose whole job is
telling a reader how to recover the name from a ref
(`resources/seon/schemas/seon.fn.edn:17`, read by `src/seon/fn.clj:1462`), a
preservation pass that strips and re-resolves refs across a publication, a
sibling attribute storing the same name as a value beside the ref. Every one of those is a name-observation
wearing a ref.

**A function with live callers is not deletable until the callers are fixed**
(owner, 2026-09-16). Call edges being VALUES is what makes the edges survive
the retraction; it is not permission to drop the function silently. The
retraction and the repair belong in one transaction, or the deletion refuses
and hands the agent the breaking call graph to fix first. "Every connection is
important. Retraction shouldn't be allowed until a fix is also proffered …
STOP AGENTS from breaking things until a fix is in place." That contract binds
the three deletion origins equally: an SCI evaluation, an edit-hook file
deletion, and a complete republish.

The second rule in the table is the other half of the same reading. `#{}`
stores nothing — `explode` emits one
datom per member (`db/transaction.cljc:740-771`, `:719-738`), so "found
nothing" and "never ran" are the same bytes unless the looking is its own
datom. If a reader will ever need to distinguish them, declare the event.
Grounding:
[the deletion study](../../../docs/prds/steward-platform/research/datahike-deletion-and-the-program-graph-2026-09-16.md)
§8 and program-facts PRD §1f G2/G4.

Cardinality-many does not acquire order because a Malli declaration used
a vector. Store an ordinal on ordered members.

Choose a tuple for one fixed ordered observation, such as callee and argument
count; its complete value is the index key, not each member
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:31-132`).
Choose components when children belong to one parent's value. Datahike's
cascade does not enforce exclusive ownership (`db/transaction.cljc:832-836`);
shared fingerprint-identified shapes stay behind ordinary refs. The existing
shape model owns child/entry occurrence rows but their `/schema` refs lead
to shared shapes (`resources/seon/schemas/seon.schema.shape.edn:10-13`,
`resources/seon/schemas/seon.schema.shape.child.edn:3-4`). Preserve that
distinction when merging another representation into it.

Validate complete owning values, including owners discovered from before and
after a child-only edit or unlink. Pull is not a completeness proof: a
supplied `:limit` truncates a many-valued attribute silently and recursion can
yield id-only maps (`reference-code/datahike/src/datahike/pull_api.cljc:238-243`,
`:315-351`).
Obtain the complete value under the declared work bound or refuse.
Every owned relation declares `:seon.db/component-schema`; the final writer
validates those typed values as well as the root (`write-owned-values-error`,
`src/seon/db.clj:3372`). The config schema declares
`:seon.config.db/validation-node-limit`
(`resources/seon/schemas/seon.config.db.edn:1`), carried by
projection acquisition and shared across writers; there is no per-constructor
escape. A nonempty identity-less row without an owner refuses.

An event fact proves exactly the observation its writer completed. A definition
analysis digest does not prove test reach ran; a maintenance request's existence
does not prove every sub-operation ran. Do not require nonempty datoms for a
legitimate empty result. The event's positive provenance permits interpreting
absent membership as empty (`db/transaction.cljc:719-771`). A digest records
content identity, not the number of times identical content was observed:
idempotent assertions are omitted from effective tx-data (`:588-626`).

Use history for retained changes, and a positive transition fact for current
state when its writer actually supplies one. `:db/noHistory` deliberately
removes the former guarantee (`db/transaction.cljc:441-485`). An imported
observation time is not its import transaction time. The
[modeling study](../../../docs/prds/steward-platform/research/datahike-modeling-study-2026-09-17.md)
records live counterexamples: terminal issue status without resolved-tx,
zero-argument arity without argument datoms, and separate message subject/sender.

Use fully namespaced keys and reuse declared constraints by reference.
An entity is its attributes and connections, never a stored kind.
Scalars are facts other queries can use independently; components hold
whole owned concerns. Derived concerns are queries, not stored mirrors.

An entity map's `:seon.db/attributes` property contributes its entry
attributes to storage derivation; it does not stamp a kind on an entity.
The decomposition owner is `compiled-attribute-selection`
(`src/seon/schema/datahike.clj:214-246`).

## Contracts and honest generators

API-like functions use named request/response maps; ordinary operations
may use named positional contracts. Public functions carry complete
Malli input/output contracts. Adding optional data is accretion;
requiring more or promising less changes the contract.

Stored nilable shapes refuse at
`src/seon/schema/datahike.clj:131` (message `:162`). Clearing is a retraction;
omission from an upsert is not a clear operation.
Do not generalize allowances for in-memory polymorphic results to
stored attributes.

Authored incomplete contracts and predicate requirements are checked at
`assert-complete-schema!` (`src/seon/schema/internal.cljc:291`). Malli
generator overrides are selected at
`reference-code/malli/src/malli/generator.cljc:466`; mapping an output occurs
at `:489` (`-create-from-fmap`). Neither proves the generated
value satisfies the target schema. Use fixed seeds, generate and
validate against the same projection, and exercise meaningful domain
partitions. The clojure-testing skill owns the fixture and assertion.

Config composites already derive from leaf declarations
(`derive-config-forms`, `src/seon/schema/edn.clj:62`). A new dial belongs in its owning
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

## Results and identity

Installed: the agent retains a context handle; `fork-for-turn`
(`src/seon/sci/eval.clj:2287`) forks the current base and
`regenerate-agent-context!` (`:2224-2285`) carries its private layer over it.
Its private defs, atoms, and result objects remain in memory.
Shown text is stored because it records what was seen, not because
it can restore the object. The profile is applied once at evaluation
time; no result blob, print-node encoding, or separate result byte cap.

Use `seon.id/evaluation` for branch/turn/ordinal identity and
`seon.id/symbol-in` for its result handle
(`src/seon/id.clj:55`, `:47`). Do not add a random-id generator.

Read evidence is a dependency observation, not a copied result:
`src/seon/db.clj:914`. Every distinct read form's latest evidence
feeds the since-query diff; changed reads append, writes/effects never
rerun. Compaction retracts evaluations and regenerates the opening.
Program identity tombstones are RETIRED by owner ruling 2026-09-16
(program-facts PRD §1f G1/G3): deletion is `[:db/retractEntity …]` and the
past is a temporal query; `src/` carries no tombstone mechanism.
The final report refuses identity removal or rename when surviving symbol
observers still name it; repairs are judged in the same final database
(`removed-definition-error`, `src/seon/db.clj:3916`; `deletion-error`, `:3976`).
