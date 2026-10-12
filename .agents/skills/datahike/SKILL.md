---
name: datahike
description: "Use Seon's database owner for Datalog, pull, transactions, refs, temporal values, and read evidence. Use for database queries, schema-bridge behavior, or read-evidence currency."
---

# Datahike — one database owner

Read [the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 before changing the agent entity, temporal refresh, or result storage.
The data-modeling skill owns shape choices; this skill owns operations.
Every `reference-code/datahike/…` function named below was opened at the
checked-out gitlink; read the function there before you rely on it, and read
the gitlink rather than a remembered commit.

## Explicit values and transaction authority

Use `seon.db`. Its query and pull owners accept explicit immutable
database values or agent-supplied defaults (`seon.db/q`, `seon.db/pull`).
Its transaction owner accepts an explicit connection or the
agent's connection and returns errors as values (`seon.db/transact!`).
A host JVM probe should supply an explicit checked-out branch (`seon.db/with-branch`).

Datahike accepts a transaction map with `:tx-data` and optional
`:tx-meta`, or a raw vector/sequence. A map lacking `:tx-data`
refuses (`datahike.api.impl/transact!`).
Use those dependency keys rather than inventing a wrapper.

A database value is immutable. Hand one value through a pure
derivation; obtain a new value between forms when a preceding form
transacted.

The MODELING consequences of everything below — when to retract, when to
close by a positive fact, ref versus value, component versus peer, required
versus optional — are consolidated with their rulings in
[the data-modeling decision guide](../../../docs/seon/architecture/data-modeling-guide.md).

## Deletion is retraction, and it reaches the neighbours

`[:db/retractEntity e]` and `[:db.fn/retractEntity e]` are the same
operation (both cases of `datahike.db.transaction/apply-db-op` call
`datahike.db.transaction/retract-entity`). It retracts three sets of datoms,
and the second surprises people:

1. **the entity's own datoms** — `(dbi/search db [e])`;
2. **every incoming ref datom** — for each attribute in
   `(dbi/-attrs-by db :db.type/ref)` it scans `(dbi/search db [nil a e])`
   and retracts what it finds. Another entity's statement *about* this one
   disappears with it, silently;
3. **component children, recursively** — `retract-components` emits a
   `[:db.fn/retractEntity <value>]` for every retracted datom whose
   attribute is `:db/isComponent`, spliced after the retractions.

The narrower operations do not do (2): `[:db.fn/retractAttribute e a]`
retracts that entity's datoms for `a` and cascades components of `a`
only; `[:db/retract e a v]` / `[:db/retract e a]` retract
matching datoms with no cascade and no incoming-ref sweep (both cases of
`apply-db-op`).

**Optional versus required IS the refuse/sweep dial.** Nobody declared a
deletion policy property; required-ness already is one. Every swept datom is
written through `datahike.db.transaction/transact-retract-datom`, so
it appears in the report's `:tx-data`. Seon wires a final-report validator into
every admitted `transact!`: `prepared-write` puts `validate-write-report` under
`:tx-meta :datahike/validate-report` (`src/seon/db.clj`, `prepared-write`). It
judges the report by the final database's own declarations
(`carried-projection` of `:db-after`), so a schema transaction that retires a required
key and rewrites its entity schema in one transaction is admitted. Datahike
calls it once and throws `:transaction/validation-rejected` on any non-nil
return, aborting the whole transaction (`datahike.db.transaction/validate-report`,
invoked at the end of `datahike.db.transaction/transact-tx-data`).
`seon.db/write-report-error` computes `affected` as the distinct `:e` over attempted
**and** effective tx-data — which therefore
includes every entity the sweep touched — and re-validates each one's whole
resulting entity against the schemas its identities select
(`seon.db/write-entity-error`).
So:

- the swept ref is **required** in the referrer's entity schema → the referrer
  fails `:malli.core/missing-key` and **the deletion refuses**;
- the swept ref is `{:optional true}` → the entity still validates and **the
  deletion sweeps silently**.

`write-entity-error` skips an entity retracted to nothing — coordinated deletion
is valid. Final owning-value validation (`seon.db/write-owned-values-error`) discovers roots
from before and after, expands complete EAVT children, and validates each owned
child through its relation's `:seon.db/component-schema`. Identity-less unowned
entities, missing children, cycles, multiple owners and exhausted
`:seon.config.db/validation-node-limit` refuse. No component identity is invented.
The registry carries the declared bootstrap bound (`seon.schema/with-compiled-cache`);
the final callback reads asserted configuration
from the report's final database (`seon.db/validate-write-report`).

State the lifecycle consequence in the attribute's docstring: **cascade**
(component), **sweep** (optional ref), **refuse** (required ref in a validated
surviving entity), or **value** (the observation outlives the named entity).
Required presence alone does not prove a peer target exists. Owned children
have the complete-value check above. A transaction that moves an edge is application logic,
not a fifth native deletion mode. Do not copy the old message inbox move:
[program-facts PRD §1h](../../../docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md)
restores listened `:seon.message/to` and a handling-turn claim.
Make decisions inside `:db.fn/call` when earlier operations suffice; enforce
final invariants in the final-report validator when later repairs must count.

**Retraction preserves temporal evidence only with `keep-history?` and without
`:db/noHistory`.** `datahike.db.transaction/with-datom` tests both.
A retained retraction removes current entries and inserts the old assertion
and the negative datom into temporal EAVT/AEVT and, if indexed, AVET.
Those insertions can deduplicate existing evidence; they are not a promise of
two newly allocated datoms per retraction. History therefore costs index
updates and retained nodes even after the current entity disappears.

**Purge changes temporal indexes in the resulting database; it is not an
all-snapshots erasure guarantee.** The purge operations require history
(the `:db/purge` cases of `datahike.db.transaction/apply-db-op`) and route through
`datahike.db.transaction/transact-purge-datom`. Older retained commits/branches can still
reach the old index nodes. `datahike.gc/reachable-in-branch` marks current AND temporal roots of
retained commits; GC does not prune datoms from a retained head's history.
Specify branch/commit retention separately from temporal retention. A reset
does not preserve a historical query route to the discarded store.

**A ref to a retracted entity is legal, and that is the trap.**
`datahike.db.transaction/validate-val` checks the value's *type* against the installed schema and
nothing else (called from `transact-add`); it never asks whether the target has datoms. So:

- the entity id survives with no datoms attached;
- a wildcard pull of the referring entity shows `{:db/id n}` — a link to
  nothing, indistinguishable from a live one;
- a pull with a **sub-selector** silently drops it, and so does a Datalog
  join, because there is no datom to join on;
- re-asserting the unique identity without supplying the old numeric eid can
  mint a **new** entity id:
  `datahike.db.transaction/upsert-eid` resolves an identity through
  `(:e (first (dbi/datoms db :avet [a v])))`, and once
  the identity datom is retracted that AVET entry is gone. The old ref
  does not follow the identity value to its new owner. An explicit old eid
  can instead reuse that number; numeric `entid` is not an existence check
  (`datahike.db.utils/entid`).

Measured end to end, with the surviving temporal answers, in
[the deletion study](../../../docs/prds/steward-platform/research/datahike-deletion-and-the-program-graph-2026-09-16.md)
§2–§3. The design consequence is the owner's ruling: a fact that must
outlive its target stores a **value** (a symbol), not a ref; refs stay
where a genuine entity relation exists.

## The empty set is not a fact

A cardinality-many attribute with no members has **no datoms**. `explode`
emits one `[:db/add e a v]` per member of
`(maybe-wrap-multival db a-ident vs)` (`datahike.db.transaction/explode`,
`datahike.db.transaction/maybe-wrap-multival`), and an empty collection yields nothing — there is no
empty-set datom in Datahike by construction.

Therefore `{:a/id 1 :a/calls #{}}` and `{:a/id 1}` are byte-identical
after storage. "We looked and found nothing" and "we never looked" are
the same database state unless **the looking is its own positive fact**.
Do not encode an event in the cardinality of a collection, and do not
repair it with a submission-time-only check: the whole-entity write
validator rebuilds the entity from the resulting datoms
(`seon.db/write-entity-value`; `seon.db/write-entity-error`), where the empty collection is already
gone, so a submission-only check is a pre-read the authority re-decides.

## Three dependency behaviours that report nothing when they fire

**A heterogeneous tuple whose members are ALL the wrong type stores.**
`datahike.db.transaction/check-tuple` refuses only when the per-member validity results DIFFER:
`(not (apply = (map s/valid? (:db/tupleTypes attr-schema) v)))`.
All-invalid is `(= false false)`, which passes.
Only the member COUNT is genuinely enforced. So a regression over a
tuple attribute asserts the STORED MEMBER TYPES, never merely that the
transaction succeeded. (The dependency also supports homogeneous
`:db/tupleType`; Seon's current tuple bridge emits
`:db/tupleTypes`, from `seon.schema.datahike/compiled-storage`.) Also
`maybe-wrap-multival` treats any non-map collection under a cardinality-many
attribute as the member sequence, so a cardinality-many tuple
attribute handed a bare tuple vector instead of a set explodes into scalar
datoms.

**An explicit pull `:limit` truncates a cardinality-many result with no
signal.** At the pinned fork `datahike.pull-api/+default-limit+` is nil,
`limit` defaults to it in `datahike.pull-api/pull-attr-datoms` and the
transducer is `(if limit (take limit) identity)`: a default pull
returns every member, and a supplied `:limit n` cuts with no marker, no
elision and no refusal. The cut is per entity/attribute, not across the
population. Before that pin the fork defaulted to 1,000; do not carry that
number into current reasoning. A pull owner that passes a limit and does not
report the cut as an elision naming the bound is reading absence of signal as
health.

**An attribute whose form the bridge cannot map is not stored at all.**
The bridge's fold, `seon.schema.datahike/compiled-storage`,
admits `:seon.db/ref`, a scalar `:=` literal (`literal->datahike-value-type`),
an all-keyword `:enum`, an `:or` (one type, else the EDN-string
codec unless `:seon.db/value-type` names one), a tuple of storable members,
`inst?`, and the heads in `seon.schema.datahike/malli-type->datahike-type` — string,
re, int, double, float, keyword, qualified-keyword, boolean, inst, uuid,
symbol, qualified-symbol, tuple. `:maybe` refuses. **There is no `:map`
case**, so `seon.schema.datahike/malli->datahike-attr-in` throws for a `:map`-typed
attribute, `seon.schema.datahike/storable-attribute-in?` answers false, and the
attribute has NO datoms — `:seon.maintenance.result/value` is the live
instance. A non-keyword `:enum` fails the same way
(`:seon.cluster.wake/offer-result`). Before reasoning about a
"stored" attribute, ask `storable-attribute-in?`.

## The three grammars of a reference

One reference has three different spellings depending on where you meet
it, and one Malli key cannot describe all three.

| Where | Spelling | Source |
|---|---|---|
| Transaction data | entity id (int); tempid; lookup ref `[:attr v]`; keyword resolved through `:db/ident`; **a nested entity map** | `datahike.db.transaction/entity-map->op-vec`, `explode`, `upsert-eid` |
| Datom | always the entity id | `datahike.db.transaction/transact-add` never rewrites `v` beyond `entid-strict` |
| Pull result, no sub-selector | `{:db/id n}`, or `{:db/id n :db/ident k}` when the target has a `:db/ident` | `datahike.pull-api/pull-attr`, `datahike.pull-api/db-ident-and-id` |
| Pull result, sub-selector | the nested map that selector describes | `datahike.pull-api/pull-attr` (`datahike.pull-api/subpattern-frame`) |
| Pull result, **component** ref, no selector | the **full** nested entity map, unasked | `datahike.pull-api/pull-attr` (`expand-frame`) |
| Pull result, cardinality-many | a **vector** of whichever of the above applies | `datahike.pull-api/pull-attr` (`single? (not multi?)`) |
| Already-seen entity under recursion | `{:db/id n}` | `datahike.pull-api/pull-seen-eid` |

**A map under a ref attribute in transaction data is a nested entity that
links.** `explode` sees a map value under a ref attribute and emits
`(assoc v (dbu/reverse-ref a-ident) eid)` —
the nested map with a reverse ref added. That map re-enters
`entity-map->op-vec`, a numeric `:db/id` resolves to itself, and
the reverse ref becomes one `[:db/add e a n]`. So `{:db/id n}` pulled out
of the database is a legal thing to transact back in: it asserts the link
and nothing else.

**Deriving a pulled form from an entity schema plus a selector has five gaps
the selector grammar opens, and the derivation must handle or refuse each.**
`:as` renames the result key (`datahike.pull-api/pull-attr-datoms`); `:default` fabricates a
key for an attribute that has no datom (`datahike.pull-api/pull-attr`); a supplied `:limit`
silently truncates, and the pinned default is no limit
(`datahike.pull-api/+default-limit+`); a reverse attribute
`:a/_b` produces a key no entity schema declares and is single-valued exactly
when the forward attribute is a component (`multi? (if forward? … (not
component?))` in `pull-attr`); and recursion returns `{:db/id n}` for an
already-seen entity (`datahike.pull-api/pull-seen-eid`). An unexpanded ref is `{:db/id n}` or
`{:db/id n :db/ident k}` (`datahike.pull-api/db-ident-and-id`). Handle `:as`,
`:default`, component-reverse cardinality and the `:db/ident` extra key;
For unsupported selectors, return a typed refusal. A bounded presentation
may carry an honest elision; a whole-entity validator must obtain the complete
value or refuse. Do not infer that all limits or recursion are illegal in
Datahike: the pinned default pull has no cap, and recursion has an explicit
id-only cycle result. Their contract derivation must reflect those semantics.

The consequence for contracts: an entity schema describes the **stored**
entity. A reader's pulled shape derives from that schema **under the
reader's selector** — it is not a per-attribute `[:map [:db/id :int]]`
widening and not a second hand-written pulled schema. The inventory of
the twelve hand-written descriptions this class has already cost is
[the pulled-shape study](../../../docs/prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md).
Seon's existing normalizer back to transaction shape is
`seon.render.value/transacted`, which
uses the installed value type and cardinality as authority.

## Decisions that must hold at write time — `:db.fn/call`

`[:db.fn/call f & args]` calls `f` with the **mid-transaction database**
(carrying the revision context of the effective datoms so far,
`datahike.db.transaction/in-transaction-db`) and splices the returned
transaction data into the same transaction (`apply-db-op`'s `:db.fn/call` case). It sees earlier
operations, not later repairs. It can inspect an empty collection passed as
an explicit argument; it cannot recover one already erased by entity-map
expansion. Tempid conflict resolution can restart transaction processing
(`datahike.db.transaction/retry-with-tempid`), so keep transaction functions pure; invocation is not an
exactly-once effect guarantee.

The fork's final-report validator is the other authority: it sees completed
`:db-after`, effective `:tx-data`, and attempted datoms including idempotent
assertions (`datahike.db.transaction/validate-report`). Nil accepts; even false rejects. Its callback is
removed from stored metadata. Use it for invariants allowing same-transaction
repairs. Raw import through `datahike.db.transaction/transact-entities-directly` is a
different path and must not be mistaken for this admitted transaction path.

**A throw inside it aborts the whole transaction, not just that
operation.** The reducer does not catch it; the writer's `try` around
`(apply op-fn old args)` catches the throwable, delivers it to the
callback and sets the result to `:error`; an `Error` (not an `Exception`)
closes the queues and rethrows, stopping the writer
(`datahike.writer/create-thread`). The error
branch neither enqueues a report nor advances the database — it
`(recur old)` — so no datom from that transaction is
committed, including operations processed before the throw. External
side effects a transaction function performed are not database writes and
are not undone.

Return transaction data from a transaction function, never a refusal map:
Datahike expects transaction data there. Seon's refusals are built with
`seon.error.refusal/diagnostic` and thrown,
and `seon.db/transact!` reads the throwable back into
a flat `:seon.error` value in `transact-call`'s catch, through `write-refusal` and
`seon.error.refusal/refusal`. Evidence and the final-report validation seam are in
[the write-admission study](../../../docs/prds/steward-platform/research/write-admission-2026-09-17.md).

## Schema and references

### Indexes, identities, and tuple values

The primary indexes are EAVT, AEVT and AVET, with temporal counterparts;
there is no VAET (`datahike.index.persistent-set/index-type->kwseq` names
exactly `:eavt`, `:aevt`, `:avet`). A bound entity seeks EAVT; a bound
attribute seeks AEVT; bound indexed attribute/value seeks AVET. Without
the index, the latter filters the attribute's AEVT range; value alone
filters EAVT (`datahike.db.search/get-search-strategy-impl`). Reverse traversal of indexed
symbol edges uses the same AVET shape as refs. Ref attributes are
automatically indexed, as are BOTH kinds of unique attribute
(`datahike.db.utils/attr->properties`); ordinary symbol edges need explicit indexing.
An absent literal `:db/index` on a unique declaration does not mean no index.

`:db.unique/identity` participates in entity-map upsert;
`:db.unique/value` only enforces uniqueness (`datahike.db.transaction/upsert-eid`,
`datahike.db.transaction/validate-datom`). Both work as lookup attributes (`datahike.db.utils/entid`).
Retract-then-assert resolves against the mid-transaction AVET state; assert
before retract may conflict. Neither property promises permanent ownership
of a key after its identity datom disappears.

A tuple is ONE indexed value, not an index on every member. Exact tuple
lookup seeks AVET when indexed; tuple member extraction is not that seek
(`datahike.index.persistent-set/cmp-for-kwseq`, `datahike.datom`). Store a tuple
for a fixed ordered observation, a cardinality-many attribute for membership,
and an owned child with an ordinal for independently described ordered items.
Do not put refs inside tuples expecting ref resolution or incoming sweep:
those operations dispatch on the attribute's ref type
(`datahike.db.transaction/transact-add`, `datahike.db.transaction/retract-entity`).

### Branches, storage, and collection

Same-store `datahike.versioning/branch!` reuses stored index roots;
`datahike.versioning/fork-database` copies store keys into another store. Those are
different costs. `datahike.versioning/merge!` records supplied parents and applies supplied
transaction data; it does not invent a semantic merge.

Datahike writes nodes/schema/commit before the mutable branch head
(`datahike.writing/commit!`). Konserve ordered `multi-assoc` preserves sequence
order; backend-wide atomicity is not universal
(`konserve.core/multi-assoc`). GC roots are the
branch registry and reachable commit/index roots, plus the explicit reachable
extension (`datahike.gc/reachable-in-branch`, `datahike.gc/gc-storage!`). Merely holding an old database value
does not register a GC root. The sweep removes unmarked store objects older
than the writer safe point, not history datoms still reachable from the head
(`konserve.gc/sweep!`).

### Query semantics versus planner estimates

Rules and negation must preserve their binding semantics independently of
population size. The planner samples 64 datoms for some estimates
(`datahike.query.estimate/sample-size`) and switches entity-group ordering
from exact DP to greedy above 16 groups (`datahike.query.plan/dp-group-threshold`).
These are plan choices, not permissible correctness thresholds.
The current lowerer's NOT binding pass omits recursive-rule outputs
(`datahike.query.lower/lower`, step 7); the existing
[plan-derivation issue](../../../docs/seon/issues/the-query-planner-rejects-a-negation-bound-by-a-recursive-rule.md)
records population-sensitive failures. Do not teach its observed 405/1,005
sizes as hard-coded limits or duplicate derived plan state to hide it.

Query the installed declarations before using an attribute.
The schema population refuses duplicates within a resource and across
resources (`seon.schema.edn/merge-schema-resources`)
and exposes canonical forms (`seon.schema.edn/packaged-forms`).
The bridge's one fold, `seon.schema.datahike/compiled-storage`,
derives value type, cardinality and a collection's child, and
`seon.schema.datahike/malli->datahike-attr-in` adds identity, components, indexes and
history properties. `seon.schema.datahike/storable-attribute-in?` answers whether the
bridge maps an attribute at all.

Identity is a natural key. Join through a ref to query a target
attribute. Cardinality-many is a set. Upsert omission leaves existing
facts unchanged; use an explicit retraction to clear them. Stored
nilable forms refuse (`seon.schema.datahike/attribute-declaration`).

A **component** is part of its parent's value, not an independent entity:
pull expands it without being asked (`datahike.pull-api/pull-attr`) and
`retractEntity` destroys it with the parent (`datahike.db.transaction/retract-components`).
Validate its complete owning value against the parent's schema; do not invent
identity attributes on component entities to make a selector see them. Child-only
edits and removed component links require discovering affected owners from
before AND after, not just the changed eid. A supplied pull `:limit` and
id-only cycle results cannot establish completeness (`datahike.pull-api/pull-seen-eid`,
`datahike.pull-api/pull-attr-datoms`). Component flags prescribe traversal/cascade; `retract-components`
does not enforce exclusive ownership. Shared shapes need ordinary refs to
the shared target, with components only for genuinely owned edge entities.

**Derive an owned child's parent from the forward edge when that is the only
meaning of a proposed back-pointer.** `:parent/_children` answers it without
another asserted fact (`datahike.pull-api/pull-attr`). This relies on the
model's ownership invariant, not on Datahike enforcing one parent.
`:seon.test.failure/test`
(`resources/seon/schemas/seon.test.failure.edn`) is a plain ref back up an
edge already declared as `:seon.test/failures`
(`resources/seon/schemas/seon.test.edn`, a component vector) — one fact in
two encodings, which the next writer can put out of step. Do not add one.

For fixture operations use `seon.test-support/with-database`, which
runs the body on an isolated branch of the executing test's database,
and write through `seon.test-support/transacted!` so a refused write fails the test instead of
reading as absence. Do not build a small hand-listed schema to
impersonate production.

## What `listen!` delivers

`datahike.core/listen!` registers a callback under an opaque key on ONE
connection's `:listeners` atom (`datahike.core/listen!`);
`datahike.core/unlisten!` removes it. After a successful `transact` the writer
calls every registered callback with the WHOLE transaction report
(`datahike.writer/notify-listeners!`). Consequences:

- **it is `(when (map? tx-report) …)`** — a failed transaction notifies
  nobody, so a listener that never fires is not evidence of no write;
- **it is in-process and per connection.** Another JVM's transaction against
  the same store delivers nothing here.
- **it has no replay.** Register before deriving current work, and recover
  from facts after disconnect/restart (`datahike.core/listen!`).
- **commits may batch transactions.** `datahike.writer/create-thread` substitutes the
  batch's committed database into each report's `:db-after`; per-transaction
  `:tx-data` stays separate. Do not use that report's after-value as if it
  were the final-validator's exact transaction boundary, or assume one commit
  id per transaction. Promise delivery precedes the callback loop;
  each callback has an independent exception catch (`datahike.writer/notify-listeners!`).
  Await the listener's event to prove delivery; a returned transaction report
  alone does not establish it. Seon's callback reports failures and never parks.

The report is the matchable surface: `:db-before`, `:db-after`, `:tx-data`
(every effective datom as `[e a v tx added]`, accumulated by
`datahike.db.transaction/transact-report`) and `:tx-meta`. Transaction metadata is not a
side channel — `datahike.db.transaction/flush-tx-meta` turns each `:tx-meta` entry into
`[:db/add <tx-eid> attr value <tx-eid>]`.
Those operations enter transaction processing when keep-history is enabled
(`datahike.db.transaction/transact-tx-data`); then metadata arrives as ordinary datoms ON the transaction
entity inside `:tx-data`. An undeclared meta attribute refuses
(`flush-tx-meta`). Under that retention setting,
matching entity id, attribute, value and transaction metadata — in any
combination — is one predicate over the datoms of one report, needing no
stored pattern entity.

## Temporal values and read evidence

`seon.db/history`, `as-of`, and `since` preserve explicit and
agent-default forms. Datahike's `as-of` predicate includes the time point;
`since` excludes it (`datahike.db/as-of-pred`, `datahike.db/since-pred`).
Because deletion is retraction, these are how the past is read: a
current-basis query cannot distinguish "A never referred to B" from "B
was deleted", and only a temporal query can.

`seon.db/read-evidence` retains dependency plans and revisions
without database values or read payloads by default. Read-result retention is explicit for
process-local semantic replay. `seon.db/read-evidence-current?` compares
revisions and may replay supported reads to compare their results.
Query execution captures evidence through the dependency's
evidence-carrying path (`seon.db/q`).

These are the existing mechanisms to inspect before adding a refresh
index or cache.

## Read evidence currency

`seon.db/read-evidence-current?` is the one answer to whether a retained
read still holds: equal attribute revisions prove no commit touched what the
read depends on, and when revisions differ, a retained read whose bound
patterns match no datom changed since its basis is still current, checked
through the index (`index-evidence-current`). Renders reuse a cached result
only while it holds (`seon.render.walk/acquire-entity`).

An empty result still has read dependencies. Insertions and retractions
must invalidate the appropriate observation. Missing evidence is
unknown, never an empty set that proves health. Do not replace this
algorithm with message-only refresh or one handler per block.

## Result lifetime — target

The agent's persistent SCI context holds actual result objects and
private defs/atoms. The database stores the printed value, source, out, error,
and read evidence. It does not store result EDN, print nodes, result
blobs, or a restoration ladder. The single profile rendering happens
at evaluation time; restart loses the object, not the saved text.

Stable evaluation identity comes from `seon.id/evaluation`
and its result handle from `seon.id/symbol-in`.

For a dependency defect, inspect its checked-out source and gitlink
rather than retaining a commit hash in this skill. Verify behavior
on the canonical fixture and through the owning dependency's actual
test task. Historical notes may explain a mechanism but do not
override the current source or PRD.
