---
name: datahike
description: "Use Seon's database owner for Datalog, pull, transactions, refs, temporal values, and read evidence. Use for database queries, schema-bridge behavior, or since-diff diagnosis."
---

# Datahike — one database owner

Read [the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 before changing record, temporal refresh, or result storage.
The data-modeling skill owns shape choices; this skill owns operations.
Every `reference-code/datahike/…` line below was opened at the checked-out
gitlink; verify a line before you rely on it, and read the gitlink rather
than a remembered commit.

## Explicit values and transaction authority

Use `seon.db`. Its query and pull owners accept explicit immutable
database values or agent-supplied defaults (`src/seon/db.clj:1677`,
`:1806`). Its transaction owner accepts an explicit connection or the
agent's connection and returns errors as values (`src/seon/db.clj:3343`).
A host JVM probe should supply explicit custody
(`seon.db/call-with-custody`, `src/seon/db.clj:262`).

Datahike accepts a transaction map with `:tx-data` and optional
`:tx-meta`, or a raw vector/sequence. A map lacking `:tx-data`
refuses (`reference-code/datahike/src/datahike/api/impl.cljc:30`).
Use those dependency keys rather than inventing a wrapper.

A database value is immutable. Hand one value through a pure
derivation; obtain a new value between forms when a preceding form
transacted.

## Deletion is retraction, and it reaches the neighbours

`[:db/retractEntity e]` and `[:db.fn/retractEntity e]` are the same
operation (`db/transaction.cljc:1080`, `:1082` → `retract-entity`,
`:998`). It retracts three sets of datoms, and the second surprises
people:

1. **the entity's own datoms** — `(dbi/search db [e])` (`:1001`);
2. **every incoming ref datom** — for each attribute in
   `(dbi/-attrs-by db :db.type/ref)` it scans `(dbi/search db [nil a e])`
   and retracts what it finds (`:1002-1013`, retracted at `:1014`).
   Another entity's statement *about* this one disappears with it,
   silently;
3. **component children, recursively** — `retract-components` emits a
   `[:db.fn/retractEntity <value>]` for every retracted datom whose
   attribute is `:db/isComponent` (`:831-836`, spliced at `:1015`).

The narrower operations do not do (2): `[:db.fn/retractAttribute e a]`
retracts that entity's datoms for `a` and cascades components of `a`
only (`:1073-1078`); `[:db/retract e a v]` / `[:db/retract e a]` retract
matching datoms with no cascade and no incoming-ref sweep (`:1060-1071`).

**Optional versus required IS the refuse/sweep dial.** Nobody declared a
deletion policy property; required-ness already is one. Every swept datom is
written through `transact-retract-datom` (`db/transaction.cljc:813-819`), so
it lands in the report's `:tx-data`. Seon wires a final-report validator into
every `transact!` (`src/seon/db.clj:3178`); Datahike calls it once and throws
`:transaction/validation-rejected` on any non-nil return, aborting the whole
transaction (`db/transaction.cljc:1206-1216`, invoked at `:1276`).
`write-report-error` computes `affected` as the distinct `:e` over attempted
**and** effective tx-data (`src/seon/db.clj:3014`) — which therefore includes
every entity the sweep touched — and re-validates each one's whole resulting
row against the schemas its identities select (`write-entity-error`, `:2958`).
So:

- the swept ref is **required** in the referrer's entity schema → the referrer
  fails `:malli.core/missing-key` and **the deletion refuses**;
- the swept ref is `{:optional true}` → the row still validates and **the
  deletion sweeps silently**.

Two holes in that dial, both real. `write-entity-error` skips an entity
retracted to nothing (`(when (seq row))`, `:2961`) — correct, and it is why
coordinated deletion works. And it selects schemas from the row's **identity
attributes** (`:2964`), so an entity with **no identity attribute is never
validated** — which is exactly the component rows a foreign deletion sweeps.

State in the attribute's docstring which behaviour applies and why. There are
five: **cascade** (component), **sweep** (optional ref), **refuse** (required
ref), **value** (retype off `:seon.db/ref`; the fact then outlives its
target), and **move** — a pending edge its own settlement migrates to a
durable sibling, so the live set is small and self-draining
(`:seon.message/inbox` → `:seon.message/read-tx`, `src/seon/turn.clj:435-437`;
`:seon.effect/notify` → `:seon.effect/to`, `src/seon/effect.clj:356-360`,
`:389-393`). What is NOT on the menu is a pre-read asking "does anything point
at this?" — the authority re-decides it. The one legitimate write-time
decision point is `[:db.fn/call f]`, below.

**Retraction preserves history.** Every retraction is written as
`(datom e a v tx false)` (`transact-retract-datom`, `:813-819`), so the
fact stays in the temporal index.

**Purge is true deletion, and the only operation that stops `as-of`
answering.** `:db/purge` (`:1084`), `:db.purge/attribute` (`:1095`),
`:db.purge/entity` (`:1106`) and `:db.history.purge/before` (`:1119`) each
refuse unless `keep-history?`, and each routes its datoms through
`transact-purge-datom` (`:820-829`), which removes the fact from the temporal
index via `with-temporal-datom` — retraction only adds a `false` datom, purge
takes the past away. `:db.purge/entity` mirrors `retract-entity` exactly: own
datoms, **every incoming ref datom**, and component children
(`:1106-1117`). It preserves the committed change in the report's
`::effective-tx-data` as an ordinary retraction, so a listener does not read
purge as "unchanged". It is the compliance escape hatch:
"Normal retractions preserve data in history" (`doc/time_variance.md:303`)
and "Use retractions for normal data lifecycle - reserve purging for
compliance requirements" (`:360`). Seon runs with history on
(`:seon.config.db/keep-history? true`, `config/default.edn:4`), so the
past of anything you retract is a `history` / `as-of` / `since` query.

**A ref to a retracted entity is legal, and that is the trap.**
`validate-val` checks the value's *type* against the installed schema and
nothing else (`db/transaction.cljc:33-52`, called from `transact-add` at
`:788`); it never asks whether the target has datoms. So:

- the entity id survives with no datoms attached;
- a wildcard pull of the referring entity shows `{:db/id n}` — a link to
  nothing, indistinguishable from a live one;
- a pull with a **sub-selector** silently drops it, and so does a Datalog
  join, because there is no datom to join on;
- re-asserting the unique identity mints a **new** entity id:
  `upsert-eid` resolves an identity through
  `(:e (first (dbi/datoms db :avet [a v])))` (`:641`, `:660`), and once
  the identity datom is retracted that AVET entry is gone. The old ref
  points at the dead id forever.

Measured end to end, with the surviving temporal answers, in
[the deletion study](../../../docs/prds/steward-platform/research/datahike-deletion-and-the-program-graph-2026-09-16.md)
§2–§3. The design consequence is the owner's ruling: a fact that must
outlive its target stores a **value** (a symbol), not a ref; refs stay
where a genuine entity relation exists.

## The empty set is not a fact

A cardinality-many attribute with no members has **no datoms**. `explode`
emits one `[:db/add e a v]` per member of
`(maybe-wrap-multival db a-ident vs)` (`db/transaction.cljc:739-770`,
`:718-737`), and an empty collection yields nothing — there is no
empty-set datom in Datahike by construction.

Therefore `{:a/id 1 :a/calls #{}}` and `{:a/id 1}` are byte-identical
after storage. "We looked and found nothing" and "we never looked" are
the same database state unless **the looking is its own positive fact**.
Do not encode an event in the cardinality of a collection, and do not
repair it with a submission-time-only check: the whole-entity write
validator rebuilds the row from the resulting datoms
(`seon.db/write-entity-value`, `src/seon/db.clj:2895`;
`write-entity-error`, `:2942`), where the empty collection is already
gone, so a submission-only check is a pre-read the authority re-decides.

## Three dependency behaviours that report nothing when they fire

**A heterogeneous tuple whose members are ALL the wrong type stores.**
`check-tuple` refuses only when the per-member validity results DIFFER:
`(not (apply = (map s/valid? (:db/tupleTypes attr-schema) v)))`
(`db/transaction.cljc:1037`). All-invalid is `(= false false)`, which passes.
Only the member COUNT is genuinely enforced (`:1033`). So a regression over a
tuple attribute asserts the STORED MEMBER TYPES, never merely that the
transaction succeeded. (The homogeneous `:db/tupleType` path at `:1020-1031`
is unreachable from Seon: the bridge emits only `:db/tupleTypes`,
`src/seon/schema/datahike.clj:267`. Do not introduce it.) Also
`maybe-wrap-multival` treats any non-map collection under a cardinality-many
attribute as the member sequence (`:718-736`), so a cardinality-many tuple
attribute handed a bare tuple vector instead of a set explodes into scalar
datoms.

**Pull truncates a cardinality-many result at 1,000 members with no signal.**
`+default-limit+` is 1000 (`pull_api.cljc:16`), `limit` defaults to it
(`:315`) and the transducer is `(take limit)` (`:323`) — no marker, no
elision, no refusal; the caller sees a shorter collection. Measured on cluster
`default` 2026-09-16, `:seon.test/reach`
(`resources/seon/schemas/seon.test.edn:2`) held 484,412 datoms across tests,
so any wildcard pull of a test row was already silently cut
([reset recommendations §8](../../../docs/prds/steward-platform/research/reset-schema-recommendations-2026-09-16.md)).
That is the project's named failure class living inside the dependency: a pull
owner that does not report the cut as an elision naming the bound is reading
absence of signal as health.

**An attribute whose form the bridge cannot map is not stored at all.**
`form->datahike-value-type-in` (`src/seon/schema/datahike.clj:123-185`) admits
`:seon.db/ref`, a scalar `:=` literal, an all-keyword `:enum`, an `:or` (one
type, else the EDN-string codec), and the heads in `malli-type->datahike-type`
(`:55-68`) — string, re, int, double, float, keyword, qualified-keyword,
boolean, inst, uuid, symbol, qualified-symbol, tuple. **There is no `:map`
case**, so a `:map`-typed attribute throws `::value-type-unavailable`,
`storable-attribute-in?` (`:299`) answers false, and the attribute has NO
datoms — `:seon.maintenance.result/value`
(`resources/seon/schemas/seon.maintenance.result.edn:236`) is the live
instance. A non-keyword `:enum` fails the same way
(`:seon.cluster.wake/offer-result`,
`resources/seon/schemas/seon.cluster.wake.edn:10`). Before reasoning about a
"stored" attribute, ask `storable-attribute-in?`; five attributes another
lane's review treated as stored were absent from `default`'s installed schema
on 2026-09-16 (reset recommendations X2).

## The three grammars of a reference

One reference has three different spellings depending on where you meet
it, and one Malli key cannot describe all three.

| Where | Spelling | Source |
|---|---|---|
| Transaction data | entity id (int); tempid; lookup ref `[:attr v]`; keyword resolved through `:db/ident`; **a nested entity map** | `db/transaction.cljc:946` (`entity-map->op-vec`), `:739-770` (`explode`), `:641` (`upsert-eid`) |
| Datom | always the entity id | `db/transaction.cljc:786-790` — `transact-add` never rewrites `v` beyond `entid-strict` |
| Pull result, no sub-selector | `{:db/id n}`, or `{:db/id n :db/ident k}` when the target has a `:db/ident` | `pull_api.cljc:353-357`, `db-ident-and-id` at `:298-302` |
| Pull result, sub-selector | the nested map that selector describes | `pull_api.cljc:335-339` (`subpattern-frame`, `:204`) |
| Pull result, **component** ref, no selector | the **full** nested entity map, unasked | `pull_api.cljc:346-351` (`expand-frame`, `:291`) |
| Pull result, cardinality-many | a **vector** of whichever of the above applies | `pull_api.cljc:353-357` (`single? (not multi?)`) |
| Already-seen entity under recursion | `{:db/id n}` | `pull_api.cljc:238-243` |

**A map under a ref attribute in transaction data is a nested entity that
links.** `explode` sees a map value under a ref attribute and emits
`(assoc v (dbu/reverse-ref a-ident) eid)` (`db/transaction.cljc:758`) —
the nested map with a reverse ref added. That map re-enters
`entity-map->op-vec` (`:946`), a numeric `:db/id` resolves to itself, and
the reverse ref becomes one `[:db/add e a n]`. So `{:db/id n}` pulled out
of the database is a legal thing to transact back in: it asserts the link
and nothing else.

**Deriving a pulled form from an entity schema plus a selector has five gaps
the selector grammar opens, and the derivation must handle or refuse each.**
`:as` renames the result key (`pull_api.cljc:316`); `:default` fabricates a
key for an attribute that has no datom (`:361-365`); `:limit` silently
truncates and defaults to 1,000 (`:16`, `:315`, `:323`); a reverse attribute
`:a/_b` produces a key no entity schema declares and is single-valued exactly
when the forward attribute is a component (`multi? (if forward? … (not
component?))`, `:328-329`); and recursion returns `{:db/id n}` for an
already-seen entity (`:238-243`). An unexpanded ref is `{:db/id n}` or
`{:db/id n :db/ident k}` (`db-ident-and-id`, `:298-302`). Handle `:as`,
`:default`, component-reverse cardinality and the `:db/ident` extra key;
REFUSE — a typed refusal, not a guess — a selector carrying `:limit`, a
recursion operator, or `:default` under recursion.

The consequence for contracts: an entity schema describes the **stored**
entity. A reader's pulled shape derives from that schema **under the
reader's selector** — it is not a per-attribute `[:map [:db/id :int]]`
widening and not a second hand-written pulled schema. The inventory of
the twelve hand-written descriptions this class has already cost is
[the pulled-shape study](../../../docs/prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md).
Seon's existing normalizer back to transaction shape is
`seon.render.value/transacted` (`src/seon/render/value.clj:16`), which
uses the installed value type and cardinality as authority.

## Decisions that must hold at write time — `:db.fn/call`

`[:db.fn/call f & args]` calls `(apply f db args)` with the
**mid-transaction database** and splices the returned transaction data
into the same transaction (`db/transaction.cljc:1153-1154`). That is the
one place a decision the writer must re-decide belongs: the function sees
what earlier operations in this transaction already did, so it is not a
pre-read.

**A throw inside it aborts the whole transaction, not just that
operation.** The reducer does not catch it; the writer's `try` around
`(apply op-fn old args)` catches the throwable, delivers it to the
callback and sets the result to `:error`
(`reference-code/datahike/src/datahike/writer.cljc:147-160`). The error
branch neither enqueues a report nor advances the database — it
`(recur old)` (`:201-218`) — so no datom from that transaction is
committed, including operations processed before the throw. External
side effects a transaction function performed are not database writes and
are not undone.

Return transaction data from a transaction function, never a refusal map:
Datahike expects transaction data there. Seon's refusals are built with
`seon.error/diagnostic` and thrown, and `seon.db/transact!`
(`src/seon/db.clj:3343`) reads the throwable chain back into a flat
`:seon.error` value. Evidence and the final-report validation seam are in
[the write-admission study](../../../docs/prds/steward-platform/research/write-admission-2026-09-17.md).

## Schema and references

Query the installed declarations before using an attribute.
The schema population refuses duplicates (`src/seon/schema/edn.clj:316`)
and exposes canonical forms (`packaged-forms`, `:393`).
The bridge derives ref type (`src/seon/schema/datahike.clj:123`),
cardinality (`:205`), the child form of a collection (`:224`), and
identity, components, indexes and history properties
(`malli->datahike-attr-in`, `:232`). `storable-attribute-in?` (`:299`)
answers whether the bridge maps an attribute at all.

Identity is a natural key. Join through a ref to query a target
attribute. Cardinality-many is a set. Upsert omission leaves existing
facts unchanged; use an explicit retraction to clear them. Stored
nilable forms refuse (`src/seon/schema/datahike.clj:170`).

A **component** is part of its parent's value, not an independent entity:
pull expands it without being asked (`pull_api.cljc:346-351`) and
`retractEntity` destroys it with the parent (`db/transaction.cljc:831-836`).
Validate the parent pulled with its components expanded, as one value
against the parent's schema; do not invent identity attributes on
component rows to make a selector see them.

**A component's back-pointer to its parent is redundant.** The parent is never
deleted without the component, and `:parent/_children` answers the same
question from the forward datom. `:seon.test.failure/test`
(`resources/seon/schemas/seon.test.failure.edn:3`) is a plain ref back up an
edge already declared as `:seon.test/failures`
(`resources/seon/schemas/seon.test.edn:40`, a component vector) — one fact in
two encodings, which the next writer can put out of step. Do not add one.

For fixture operations use `seon.test-support/with-database`, which
supplies a branch of the canonical populated base
(`test/seon/test_support.clj:1019`), and write through
`transacted!` (`:300`) so a refused write fails the test instead of
reading as absence. Do not build a small hand-rostered schema to
impersonate production.

## What `listen!` delivers

`datahike.core/listen!` registers a callback under an opaque key on ONE
connection's `:listeners` atom (`reference-code/datahike/src/datahike/core.cljc:200-211`);
`unlisten!` removes it (`:213-218`). After a successful `transact` the writer
calls every registered callback with the WHOLE transaction report
(`reference-code/datahike/src/datahike/writer.cljc:414-415`), and `merge!`
does the same (`:441-442`). Two consequences worth holding:

- **it is `(when (map? tx-report) …)`** — a failed transaction notifies
  nobody, so a listener that never fires is not evidence of no write;
- **it is in-process and per connection.** Another JVM's transaction against
  the same store delivers nothing here.

The report is the matchable surface: `:db-before`, `:db-after`, `:tx-data`
(every effective datom as `[e a v tx added]`, accumulated at
`db/transaction.cljc:615`) and `:tx-meta`. Transaction metadata is not a
side channel — `flush-tx-meta` turns each `:tx-meta` entry into
`[:db/add <tx-eid> attr value <tx-eid>]` (`db/transaction.cljc:903-922`), so
it arrives as ordinary datoms ON the transaction entity inside `:tx-data`,
and an undeclared meta attribute refuses the transaction (`:919-920`). So
matching entity id, attribute, value and transaction metadata — in any
combination — is one predicate over the datoms of one report, needing no
stored pattern entity.

## Temporal values and read evidence

`seon.db/history`, `as-of`, and `since` preserve explicit and
agent-default forms (`src/seon/db.clj:2118`, `:2131`, `:2145`).
Datahike's `as-of` predicate includes the time point;
`since` excludes it (`reference-code/datahike/src/datahike/db.cljc:142-152`).
Because deletion is retraction, these are how the past is read: a
current-basis query cannot distinguish "A never referred to B" from "B
was deleted", and only a temporal query can.

`seon.db/read-evidence` retains dependency plans and revisions
without database values or read payloads by default
(`src/seon/db.clj:784`). Read-result retention is explicit for
process-local semantic replay. `read-evidence-current?` compares
revisions and may replay supported reads to compare their results
(`:951`). Query execution captures evidence through the dependency's
evidence-carrying path (`seon.db/q`, `:1677`).

These are the existing mechanisms to inspect before adding a refresh
index or cache. Their presence does not prove the whole-history
since-query target is implemented.

## Since-query diff — target

For every distinct read form in the agent's history, use its latest
evaluation's read evidence and `:t`. If a named dependency changed
since then, append a fresh evaluation of the same form in a system
turn. Include generated and agent-written reads. Exclude any form
that transacts or requests an effect.

An empty result still has read dependencies. Insertions and retractions
must invalidate the appropriate observation. Missing evidence is
unknown, never an empty set that proves health. Do not replace this
algorithm with message-only refresh or one handler per block.

System turns store ordinary evaluations; previous shown text never
changes. Compaction retracts evaluations and the next system turn
regenerates the opening. A system turn holding wakes' results answers
those wakes under the transaction `:t` rule.

## Result lifetime — target

The agent's persistent SCI context holds actual result objects and
private defs/atoms. The database stores shown text, source, out, error,
and read evidence. It does not store result EDN, print nodes, result
blobs, or a restoration ladder. The single profile rendering happens
at evaluation time; restart loses the object, not the saved text.

Stable evaluation identity comes from `seon.id/evaluation`
(`src/seon/id.clj:55`) and its result handle from `seon.id/symbol-in`
(`:47`).

For a dependency defect, inspect its checked-out source and gitlink
rather than retaining a commit hash in this skill. Verify behavior
on the canonical fixture and through the owning dependency's actual
test task. Historical notes may explain a mechanism but do not
override the current source or PRD.
