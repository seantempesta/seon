---
type: architecture
status: active
created: 2026-09-17
tags: [architecture, schema, database, datahike, deletion, refs, identity, components]
---

# Data-modeling decision guide

Model the fact a writer can assert, the questions readers need to ask and the
behavior when a related entity disappears. The stored schema declares those
choices. This guide preserves the current guarantees without a migration inventory;
[A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md) owns database
changes, [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md)
declaration facts and [B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md)
the target error/task families. Planned representations are labeled as targets.

## 1. Identity and attributes

An entity is its attributes, values and refs. Query attribute presence to find it;
use a unique identity attribute to address it; follow refs for relationships. Do
not stamp a kind to select its schema. A genuinely bounded state or dependency
grammar may use a declared enum, but it describes the entity rather than choosing
a table. Derivable state does not acquire a second stored flag.

Declare one globally identified schema under `resources/seon/schemas/`, discovered
through the registry first. Maps remain open. A declared member is checked rigorously;
unrelated supplied members do not refuse. Inputs may require no more and outputs
promise no less under accretion. Changed meaning requires a new key. A required
member means its writer always knows the fact, not merely that existing examples
happen to contain it.

Use symbols for functions, Vars, namespaces and other symbol identities. A qualified
name observed in source is a `:qualified-symbol`, never a string plus conversion
at every reader. `seon.id` owns derived identity; identities with existing parts
hash those parts, while a genuinely fresh event may mint an identity once. Database
entity ids are local addresses, not portable identities across branches.

Datahike distinguishes `:db.unique/identity` upsert from `:db.unique/value` uniqueness.
Identity unification occurs at transaction admission, not through a caller's
existence pre-read. Reasserting a retracted identity can allocate a new eid; an old
numeric ref does not follow it. Ordinary symbol/value attributes need explicit
indexing for efficient reverse lookup. Refs and unique attributes are indexed;
there is no VAET index to assume.

**Grounding:** `reference-code/datahike/src/datahike/db/transaction.cljc:641-715`
upsert; `reference-code/datahike/src/datahike/db/utils.cljc:307-313` indexing;
`src/seon/schema/datahike.clj:66-67,95-102,176-181` symbol/identity projection.

## 2. Retraction and lifecycle closure

Deleting a declaration retracts its entity. There is no program tombstone or
retirement sentinel. History/as-of/since answer what was previously true, provided
history is enabled and the attribute does not declare `:db/noHistory`. Retraction
is not physical erasure: retained commits can still reach old index nodes. Purge
and GC do not promise erasure from every retained snapshot.

Ending a lifecycle is different from deleting its subject. A turn closes with
`closed-tx`; a resolved task remains addressable through its closing fact; archiving
an agent retains the record needed by its readers. A query may derive open from
absence of closure only when every closing writer supplies that fact. An imported
terminal status without a corresponding transition is incomplete evidence, not a
reason to silently call the entity open or delete the only terminal observation.

A function with surviving named referrers cannot be retracted until those referrers
are repaired in the same transaction's final database. Call/reference values remain
readable after target deletion, enabling a complete refusal and unresolved-name
query. Never mint a target row to hide an unresolved name. Fresh publication with
no previous identity reports unresolved names; removing a prior identity has no
special exemption. Include changed callers/tests as well as changed callees in
validation so a new edge cannot escape merely because its target did not change.

**Existing implementation:** calls/references and test reach already use symbol
values; they are not pending ref-to-value migrations. Canonical arities link to
shared `seon.schema.shape` facts; the old AST family is not a current model.
**Grounding:** `reference-code/datahike/src/datahike/db/transaction.cljc:440-484`
history; `reference-code/datahike/src/datahike/gc.cljc:22-81,144-167` retained roots;
`src/seon/db.clj:4050-4121` final report; `src/seon/program.cljc` replacement.

## 3. Ref or observed value?

Ask whether the fact states a relationship to a living entity or observes a token.
A relationship is a ref. An observed name is a value, and whether it currently
resolves is a separate query. Function namespace/file relations are refs; an
analyzer's callee name and a test's past reach are values. A fault must retain the
identity it observed even if the function is later deleted.

| Choice | What deletion does | Declaration and use |
|---|---|---|
| Component ref | Deleting the parent cascades to its owned child | Use only when the child is part of the parent's value |
| Optional peer ref | Target deletion sweeps the incoming ref datom | Choose when losing that relationship is intended |
| Required peer ref | Seon's final validation refuses the sweep if the surviving owning value becomes invalid | Declare required presence and ensure complete validation selects the owner |
| Identity value | Target deletion does not change the observation | Use when the observed token must remain queryable |
| Settlement moves an edge | Application logic writes a durable successor relationship | Not a native deletion mode; prove the writer's transition |

Document the selected behavior on each relationship. There is no separate deletion
policy property. Required presence is not a native foreign-key constraint: Datahike
accepts numeric refs without proving the target has datoms. A domain requiring a live
target checks it through the final writer's complete value. Coordinated deletion of
both entities is legitimate; an empty retracted row need not validate as a survivor.

Do not infer health from fewer join results. A dangling ref may disappear from a
Datalog join or selected pull, return an id-only map under wildcard pull, or yield
nil at top level. A lookup ref to an absent identity refuses, but that does not make
every numeric ref safe. Ref deletion sweeps automatically before final-state checks;
where losing a relationship would erase an obligation, compare before and final
facts and refuse unless the transaction explicitly repairs it.

B3's target task `parent`/`needs` relations apply that rule: deleting a prerequisite
cannot make a dependent ready by sweeping its last edge. The writer checks missing
members, both cycle types, scope and order before accepting. Message subject is a
string identity token supplied by the sender; `from` independently supplies the
inside-wake marker; a handling-turn claim is separate from answering the wake.
Do not restore the old inbox-edge move.

**Grounding:** `reference-code/datahike/src/datahike/db/transaction.cljc:831-836,998-1015`
cascade/sweep; `reference-code/datahike/src/datahike/db/utils.cljc:109-148`
ref resolution; `reference-code/datahike/src/datahike/pull_api.cljc:351-357,483-486,508-515`
dangling reads; `src/seon/cluster/message.clj` message ownership.

## 4. Components are owning values

A component belongs to its parent value. Pull expands component children and parent
retraction cascades into them. Datahike does not itself enforce exclusive ownership;
Seon's writer validates the entire owning value and the relation's declared
`:seon.db/component-schema`. A child-only edit or unlink finds owners in both before
and after databases. Cycles, multiple owners, missing children and exhausted node
bounds refuse. A nonempty identity-less row without an owning root refuses.

This complete component validation already exists. It is not a future repair for
an old inventory of unselected rows. A2 may narrow discovery using indexed facts,
but must preserve the whole guarantee, including cascaded/swept changes and attempted
idempotent writes. Wildcard pull is not a completeness proof; current validation
builds complete EAVT owning values under the declared bound.

Do not add identity to an owned child merely to make a selector find it. Conversely,
shared content-addressed schema shapes are peers, not components owned by each
referrer. The shape owns its child/entry occurrence rows; those rows can reference
shared shapes without claiming exclusive ownership. Reclamation of shared shapes
belongs to a separately evidenced maintenance policy, not cascade.

**Grounding:** `src/seon/db.clj:3586-3609,4050-4121` complete values/final admission;
`reference-code/datahike/src/datahike/db/transaction.cljc:831-836,1206-1226`;
`resources/seon/schemas/seon.schema.shape.edn` and
`resources/seon/schemas/seon.schema.shape.child.edn`.

## 5. Positive observation, empty collections and time

A cardinality-many empty collection emits no datoms. Required-many therefore cannot
represent a legitimately empty result by presence alone. If a reader must distinguish
“looked and found nothing” from “never looked,” record positive evidence of the
completed observation. A required analysis digest plus no calls means analyzed and
calls nothing. A digest identifies content; it is not a count of repeated observations.
Idempotent assertions can emit no effective datoms.

Evidence proves only its writer's completed operation. Analysis does not prove test
execution, a run admission does not prove completion, and an empty detector result
is meaningful only when its declared subject was actually inspected. Missing test,
subject, detector, arming or child-work observation is unknown. Submission-only checks
cannot recover an empty collection after the authority normalizes it away; the final
validator sees resulting datoms. Keep attempted-write validation for constraints that
must inspect a submitted operation even if its effective datoms are empty.

A transition recorded by the database in its own transaction uses a transaction ref,
providing both basis and transaction instant. An external observation genuinely made
before recording uses its real instant. Do not substitute import time for observed
time. Minimal writing provenance belongs in transaction metadata rather than copied
onto every domain entity; an occurrence may separately name the process it observed.

**Grounding:** `reference-code/datahike/src/datahike/db/transaction.cljc:587-625,718-770`
normalization/idempotence, `:903-922` tx metadata, `:1153-1154` transaction functions,
`:1206-1226` report admission. [B4](../../prds/agent-platform/plan/lane-b4-tests-in-process.md)
defines completed test evidence; [C1](../../prds/agent-platform/plan/lane-c1-wrapper-profiling.md)
defines cumulative observation and lifetime semantics.

## 6. Collections, tuples and native values

Cardinality-many is a set even when a source schema used vector/sequential notation.
Store explicit ordinal/position facts when order matters. A tuple is one fixed
ordered observation; its complete value is the index key. Members do not become
independently indexed attributes or live refs. A ref-shaped tuple member gets neither
ref resolution nor incoming-ref sweep. Scalar qualified function identity stays scalar.

The current fork's heterogeneous tuple check has an all-invalid case: equality of
validity results can admit members whose types all fail. Regressions must assert
stored types, not just transaction success. A bare tuple supplied as a many-valued
attribute can be expanded into scalar datoms; the outer collection must represent
the collection of tuple values deliberately.

The bridge owns representation codecs. Do not add a hand-encoded EDN mirror of a
declared value or name its transport encoding as if it were domain semantics.
Genuinely opaque provider bytes remain opaque when declared as such. A2's target
native heterogeneous storage replaces a codec only after ordering, equality,
history, retraction, reconnect and supported-shape proofs. Merely admitting
`:db.type/any` is insufficient for index safety. Arbitrary nested paths do not
become a mixed tuple workaround.

**Grounding:** `reference-code/datahike/src/datahike/index/persistent_set.cljc:31-132`
index keys; `reference-code/datahike/src/datahike/db/transaction.cljc:718-736,1020-1039`
tuple handling; `reference-code/datahike/src/datahike/datom.cljc:325-359` comparator;
`src/seon/schema/datahike.clj` codec owner.

## 7. Stored entities and pulled shapes

The entity schema describes the stored entity. Transaction data, stored datoms and
pull results are different grammars. A pulled reference map is not a reason to widen
every stored attribute to accept `{:db/id ...}`. Derive the pulled shape from the
entity schema and selector instead of maintaining a second schema by hand.

Current Datahike pull defaults each many-valued attribute to 1,000 and silently takes
that prefix. Recursive pull may also yield id-only maps. Neither a wildcard nor a
successful return establishes completeness. A reader needing the whole value must
acquire it under a declared work bound or return an explicit refusal/elision. The
target A2 native-pull change removes hidden truncation without pretending unlimited
work is cheap. HTML has no presentation clipping; an AI profile cannot excuse missing
stored data. Query-work bounds and presentation bounds are separate decisions.

Temporal values retain the schema interpretation of their origin; a reader does
not apply whichever cluster projection happens to be globally current. A1/A2 carry
that projection through raw/materialized/temporal/branch constructors before removing
fallback reconstruction. Branch transfer normalizes refs by installed identities and
components through their owner, never by raw eid replay.

**Grounding:** `reference-code/datahike/src/datahike/pull_api.cljc:16,238-243,315-351`;
`src/seon/db.clj:1219` carried projection; [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md)
and [D1](../../prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md).

## 8. Errors, tasks and facts readers can trust

B3's target error is a flat declared map, with base time/layer/operation and domain
members. No kind/class stamp or general predicate replaces the callee's exact output
union. Recording computes the complete set of satisfied schemas for recurrence
identity; successful return validation does not perform that population scan.
Preserve distinct cause/evidence values when removing duplicated diagnostic labels.

An evaluation's live result is never serialized for recovery. Saved shown text is
durable and the result handle is explicitly unavailable after restart. The proposed
error payload simplification has a separate owner decision: complete-rendering-blob
durability must be reconciled before deleting that evidence. Native path storage
also waits for A2's comparator proof. These are representation gates, not permission
to lose information silently.

The target task family uses one writer-owned identity and separate trigger/start/
settlement operations. Test and function citations are observed symbol values;
missing targets remain visible obligations. Error roots are relationships where the
declared optional sweep is appropriate. A plan step is a task related by parent,
needs and position, without a separate plan family or stored task-kind switch.
Conversation completion follows its accepted reply; repair completion requires every
stated test/detector condition; aggregate completion requires a nonempty complete
child set. Namespace agents express responsibility, not assignment or identity.

Derive what existing facts already answer: open turn from closure, past definition
from history, callers from indexed symbol edges, current test obligations from the
static graph and explicit subjects. If a useful query instead needs text parsing,
name conventions or a manually maintained list, identify the missing declaration at
its single writer. A historical observation, derived query and asserted decision are
different facts; preserving that distinction prevents absence from becoming success.
