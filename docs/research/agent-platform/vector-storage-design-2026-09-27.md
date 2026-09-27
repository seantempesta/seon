---
type: research
status: design — for Astra review
created: 2026-09-27
lane: design-vector-storage-opus
tags: [schema, datahike, bridge, components, adoption]
---

# One way to store a vector

**What exists.** The bridge's `compiled-storage` (`src/seon/schema/datahike.clj:77-146`) maps `[:set T]`,
`[:vector T]` and `[:sequential T]` to the same thing (`:111-114`): a cardinality-many attribute of `T`. A
cardinality-many attribute in Datahike is a set of datoms, so a vector loses its order and its duplicates.
Authors patch this in two ways. They add an ordinal attribute to component children, or they put the
position inside a tuple (`:seon.test.failure/contexts`, `src/seon/test/runner.clj:209`). The writer
rebuilds a vector with `::normalize-many` (`src/seon/db.clj:3472`). Adoption validates the stored set
against `[:vector …]` (`src/seon/cluster.clj:1241-1270`).

**Principal approach.** A vector attribute stores each element in a bridge-owned **cell** entity. The
cell holds `:seon.db.vector/position` and the author's own attribute, which carries the element with
exactly its declared type and relationship kind. Cells hang off the parent through one bridge attribute,
`:seon.db.vector/cells`, which is a cardinality-many component. This one encoding covers scalar, tuple,
peer-ref and component elements. Order is a storage concern, and ownership stays the author's (owner
rulings 2026-09-27).

**Requirement (owner, 2026-09-27).** Conversion is transparent. An agent puts a vector in and gets the same vector
back, with its order and its duplicates, from every read it ordinarily uses. It never sees a cell, a position or an
ordinal, and it never writes one. Cells appear only in raw-datom views, which show the true storage. Skills and the
data-modeling guide are updated after the type is implemented and fully tested, not before.

## 0. Facts this design rests on (live `default`, HEAD a2fd4676c)

`default` has 28 installed attributes that are cardinality-many and declared `[:vector …]`. The table
shows value type, component flag and live datom count.

| Kind | Attributes (datoms) |
|---|---|
| Component elements, 7 | `:seon.fn/arities` (2569), `:seon.fn.arity/arguments` (4832), `:seon.fn.binding/entries` (889), `/children` (122), `:seon.cluster.eval/read-evidence` (459), `:seon.test/failures`, `:seon.test/seed`, plus `:seon.maintenance.result/collect-branches` (component, no ordinal) |
| Peer-ref elements, 2 | `:seon.cluster.eval/faults` (1), `:seon.test.accretion/gate-tests` |
| Scalar elements, 14 | `:seon.render/units` (13), `:seon.config.fs/roots`, `:seon.config.ai/stop`, `:seon.render/candidates`, `:seon.test/failing-assertions`, `:seon.effect/content-blobs`, `:seon.fn/unresolved-targets`, `:seon.sci.eval/declarations`, `:seon.test.run/retracted-definitions`, `:seon.env/unscopable-members`, `:seon.flow/missing-config-facts`, `:my.shell/failed-argv`, `:seon.operator.cluster-cleanup/removed`, `/remaining` |
| Tuple elements, 4 | `:seon.test.failure/contexts` (240, `[:tuple int string]`: an ordinal hack), `:seon.ai/wire` (9), `:seon.sci.eval/stacktrace`, `:seon.sci.kernel/in-flight` |

Hand-authored ordinals, found in the element schemas' entries:

- `:seon.fn.arity/order`
- `:seon.fn.argument/order` and `:seon.fn.argument/index`. Both are written from the same `order`
  (`src/seon/program.cljc:1030-1031`).
- `:seon.fn.binding.child/order`
- `:seon.fn.binding.entry/order`
- `:seon.test.failure/ordinal`
- `:seon.test.seed/ordinal`
- The integer in `:seon.test.failure/contexts`

**Correction to the assignment's facts.** `:seon.db/source-argument-position` is not a vector ordinal.
It records which `:in` source of a query the database was (`src/seon/db.clj:812-822`, read at `:1094`).
It stays. Read-evidence order carries no meaning, but the vector declaration stays as written. This design
changes storage, not the declarations.

The largest live vectors are short: at most 5 arities per function (2404 functions) and at most 10
arguments per arity. The validation bound `:seon.config.db/validation-node-limit` is 250000.

## 1. Encodings compared (measured)

**Method.** A plain Datahike memory store in the `default` JVM, created and deleted per run
(`:keep-history? true`, `:schema-flexibility :write`, the pinned fork `fe1d4e26`). It is not the cluster
store and it does not use the Seon writer. N = 2000 parents, each with a 3-element vector: scalar
`["a" "b" "a"]`, component `[{…} {…} {…}]`, or peer `[t_i t_i+1 t_i]`. Times are warm, in one JVM, and
bounded (total under 2 s).

| Element | Encoding | Datoms per parent | Write 2000 (ms) | pull-many 2000 (ms) | pull one (µs) | Order | Duplicates |
|---|---|---|---|---|---|---|---|
| Scalar | Current set (S) | 3 (2 members) | 51 | 10 | 6 | lost | collapsed |
| Scalar | **Cells (A)** | 10 | 191 | 33 | 18 | kept | kept |
| Scalar | One EDN value (B) | 2 | 37 | 10 | 6 | kept | kept |
| Scalar | `[pos v]` tuples (C) | 4 | 63 | 13 | 8 | kept | kept |
| Component | Current child + ordinal (S) | 10 | 192 | 28 | 17 | via ordinal | n/a |
| Component | **Cells (A)** | 13 | 189 | 41 | 25 | kept | n/a |
| Peer ref | Current set (S) | 3 (2 members) | 50 | 9 | 6 | lost | collapsed |
| Peer ref | **Cells (A)** | 10 | 167 | 27 | 20 | kept | kept |

The other options:

- **(C) tuples.** A tuple member gets no ref resolution, sweep or cascade, because those dispatch on the
  attribute's ref type (`reference-code/datahike/src/datahike/db/transaction.cljc:787-791`,
  `:999-1016`). A tuple element would need its members flattened after the position.
- **(B) one value.** No per-element query, no refs, and the index key is the whole value.
- **Homogeneous `:db/tupleType`.** Capped at 8 values (`transaction.cljc:1021-1024`).
- **`:db.type/any`.** Exists (`reference-code/datahike/src/datahike/schema.cljc:87`), but the guide
  (§6) rules it not index-safe.
- **Position on the component child itself.** Cheapest for components, but a peer target can appear in
  many vectors at different positions, so it cannot carry the position. That would make two encodings.

**Semantics measured on cells (A).**

- **Round trip.** `["b" "a" "b"]`, `[k0 k1]` and peers `[t2 t1 t2]` pull back exactly after sorting by
  position.
- **Cascade.** Retracting the parent removed every cell and every component element (27 datoms). The peer
  target `t2` survived.
- **Sweep.** Retracting the peer target `t1` swept only `[cell :p/peers t1]` (3 datoms). That left **one
  position-only cell**, and the decoded vector silently became `[t2 t2]`. §4 turns this into a refusal.
- **Card-one component replacement.** `{:db/id cell :p/kids {new}}` left the old child in the store
  without an owner (`names ("new" "old")`, owned `["new"]`). A changed component element must therefore
  replace the whole cell.

**Write cost of the positional diff** (the old and new vectors are compared position by position; an
unchanged position emits nothing):

| Length | Append | Replace one | Unchanged | Insert at front |
|---|---|---|---|---|
| 10 | 1 op, 2 ms | 1 op | 0 ops | 11 ops, 1 ms |
| 100 | 1 op | 1 op | 0 ops | 101 ops, 2 ms |
| 1000 | 1 op, 3 ms | 1 op, 3 ms | 0 ops, 2 ms | 1001 ops, 10 ms |

Insert is O(n − k). Live vectors are at most 10 long, so dense integer positions are the simplest
correct choice.

**Recommendation: cells (A) for every `[:vector T]`, with no split.** It is the only encoding that keeps
order and duplicates **and** leaves ref semantics with the element's own attribute.

- **Scalars.** 3× the datoms of today's lossy set, still in microseconds.
- **Components.** +30% datoms (13 vs 10 per 3-element parent) and 25 µs vs 17 µs per pull.
- **Live volume.** About 8.9k elements → +8.9k cell entities and about +17.8k datoms. The retired ordinal
  datoms (about −8.4k) offset part of that.

A scalar/ref split would save about 7 datoms per scalar parent across 14 small attributes (about 280
live datoms), but it would create the second encoding the owner ruled out.

## 2. The encoding, exactly

Two bridge attributes, declared once in `resources/seon/schemas/seon.db.edn`:

```clojure
:seon.db.vector/cells    [:set {:seon.db/component true :seon.db/component-schema :seon.db.vector/cell} :seon.db/ref]
:seon.db.vector/position [:int {:min 0}]
:seon.db.vector/cell     [:map [:seon.db.vector/position :seon.db.vector/position]]   ; plus exactly one vector attribute (§4)
```

`compiled-storage` changes one branch (`:111-114`). `:vector` no longer yields `::cardinality many`. It
yields the element's own storage, card-one, with `::vector? true`. The author's attribute keeps exactly
what the element declares:

- `:db.type/ref` plus `:db/isComponent` when the element is a component;
- a plain ref when the element is a peer;
- the scalar or tuple type otherwise;
- `::edn?` passes through from the child. Today `:113` forces it to false.

`:set` and `:sequential` are unchanged.

`:sequential` is not changed here. The owner ruled on `:vector`, and 0 installed many attributes are
`:sequential`. That leaves one open question for Astra: should `:sequential` share the vector encoding?

Relationship kind and collection shape are now orthogonal:

| Element | Author attribute on the cell | Parent retracted | Target / element retracted | Validation |
|---|---|---|---|---|
| Scalar or tuple | Value, card-one | Cells cascade (measured) | n/a | Parent's decoded `[:vector T]` |
| Peer ref | Ref, not a component | Cells cascade; the **target survives** (measured) | Sweep empties the cell → **refused** (§4) | Parent's decoded vector; a required peer stays a statement |
| Component | Ref with `:db/isComponent` | Cells cascade, and so do their elements (measured) | n/a (owned) | The element's `:seon.db/component-schema` applies to it through the author attribute, unchanged |

Cells are storage mechanics. A cell is a component of its parent, never of the element. A peer element
does not become owned, and a component element still cascades, through its own attribute.

## 3. Write, read and decode hooks

**Write: `encode-transaction-in`** (`src/seon/schema/datahike.clj:506`). In `encode-entity-in` (`:425`),
an entry `[a v]` with `a` vector-declared and `v` sequential is removed from the map, and:

- **The parent is new** (a tempid with no identity attribute). Emit nested cells in the map:
  `:seon.db.vector/cells [{:seon.db.vector/position i, a (encode-value-in a x)} …]`. Nested maps recurse,
  so a new arity's arguments become nested cells.
- **The parent may exist** (a numeric `:db/id` or an identity attribute). Emit
  `[:db.fn/call #'seon.schema.datahike/vector-tx parent-ref a v]`. `parent-ref` is the numeric id or the
  `[identity-attr value]` lookup. The call runs after the map's own ops (`in-transaction-db`,
  `transaction.cljc:1055-1062`), so an upserted parent resolves. The existing encoder already wraps
  `:db.fn/call` output (`:490-501`).
- **`vector-tx`** reads the parent's cells for `a`, which is one EAVT read plus one EAVT read per cell, and
  diffs by position:
  - equal decoded element → nothing;
  - different element → `[:db/retractEntity cell]` plus a new cell at the same position. The retraction
    cascades a component element, which the card-one probe showed is required.
  - extra old positions → retract the cell;
  - extra new positions → a new cell.

  Components compare their decoded element value, pulled without `:db/id`. About 25 lines.
- **A raw `[:db/add e a v]` or `[:db/retract e a v]` on a vector attribute refuses at encode**, with the
  fix "assert the whole vector in an entity map". A vector's value is the whole sequence. The one live
  raw retraction, `src/seon/test/runner.clj:1684` (`:seon.test/failing-assertions`), converts to a
  reassertion.
- **Deleted.** `explicit-value-members` (`:440-468`, 29 lines) exists only because Datahike reads a
  2-keyword vector under a card-many attribute as a lookup ref (`maybe-wrap-multival`,
  `transaction.cljc:719-737`). No card-many attribute receives a vector any more once
  `encode-value-in` puts a set-declared sequential value through `set`, which is one line.

**Read: `pull-call`** (`src/seon/db.clj:2606-2661`):

- **Selector rewrite before `(apply operation …)`.** Each requested vector attribute `a` (or `{a sub}`)
  is collected into one `{:seon.db.vector/cells [:seon.db.vector/position a …]}`. A wildcard `*` already
  expands component cells (`pull_api.cljc:346-351`).
- **Decode in `decode-pull-entity`** (`:2208-2233`), the existing per-output decoder for the EDN codec.
  Its `:seon.db.vector/cells` value is grouped by the one vector attribute each cell carries, sorted by
  position, and mapped to element values. Unrequested attributes are dropped, and `:seon.db.vector/cells`
  is removed. Each element goes through the existing `decode-attribute-value` / `decode-attribute-maps`.

  Cost is O(cells of the pulled parents). Measured at 18-25 µs per 3-element parent against 6-17 µs
  today.
- **`pull` inside `q` find-expressions** goes through the same rewrite, in `decode-query-result`
  (`:2150`).

**Datalog: `seon.db/q`** (`:2450`). A data pattern `[?e a ?v]` whose constant attribute `a` is a vector
attribute becomes `[?e :seon.db.vector/cells ?c#] [?c# a ?v]`. The rewrite walks
`not`/`or`/`and`/rule bodies. Positions stay explicit through `:seon.db.vector/position`. With the
rewrite, the 9 `seon.db/q` sites that join `[?function :seon.fn/arities ?arity]` stay correct unchanged:

- `src/seon/db.clj:3778`, `:3974`, `:4010`
- `src/seon/cluster.clj:2690`
- `src/seon/fn.clj:2743`
- `src/my/program.clj:112`, `:199`
- `src/seon/render/transcript.clj:366`

Without the rewrite, those queries silently return nothing, because the parent carries no `a` datom.
The rewrite is about 25 lines. The smaller alternative is converting the 9 sites by hand (+9 lines) with
no protection for the next author.

**Writer's value: `write-entity-value`** (`src/seon/db.clj:3479-3494`). A `:seon.db.vector/cells` datom
reads the cell's EAVT through the existing `visit-child` charge, so cells count against the validation
bound. The element is put on the parent row as a position-sorted vector. The parent's entity schema then
validates the decoded `[:vector T]`: bounds, element schema and element count.

- **Deleted.** `::normalize-many` (`:3472`) and its use (`:3509-3515`). Card-many author attributes are
  then always sets, so `set` is the one normalization.
- **Renderers.** `seon.render.value/transacted` (`src/seon/render/value.clj:181`) receives decoded values
  and leaves a vector as a vector; the encoder does the rest. HTML and AI renderers see decoded pulls.
  Only raw-datom views, such as the debug graph, show cells, and that is the true storage.

**No Malli transformer for this decode.** Every vector decodes the same way. A cell names its own
attribute, so regrouping needs no schema walk. What varies per schema, the element decode, is already
the bridge's per-attribute codec.

Malli's `collection-transformer` (`reference-code/malli/src/malli/transform.cljc:522-530`) coerces
between collection types. It cannot see the parent's cells, because a child interceptor is not given its
map key. If a per-schema storage function is ever needed, the seam is a `:decode/<name>` property, found
through `qname` (`transform.cljc:393-414`). The owner's "function attached to the schemas" is therefore
`::vector?` in `compiled-storage` plus the one bridge decoder, with no per-schema code.

## 4. Validation and adoption

**The owned-value validator** (`write-owned-values-error`, `src/seon/db.clj:3540`):

- Cells are identity-less rows owned through `:seon.db.vector/cells`, so root discovery and the node
  bound apply unchanged.
- The cell schema requires a position and **exactly one** vector-declared attribute. Two refuse
  (`::vector-cell-ambiguous`).
- **None refuses** (`::vector-element-swept`): a sweep emptied it. The refusal names the parent, the
  attribute (from the swept datom in the report's `:tx-data`) and the position.

This is the refuse reading of "required versus optional is the sweep dial". A vector's element is part of
its value: removing one changes the parent's value and shifts every later element. The repair is to
retract the cell, or reassert the vector, in the same transaction.

Live peer-vector users are affected: `:seon.cluster.eval/faults` and `:seon.test.accretion/gate-tests`.
Astra should falsify whether any writer retracts a fault error or a gate test while its referrer
survives.

**Adoption** (`invalid-value-retraction`, `src/seon/cluster.clj:1241`):

- One bridge function answers "what schema does one datom's value satisfy": the element for `:set` and
  `:vector` (`value-schema`, `src/seon/schema/datahike.clj:68-75`), otherwise the root. The writer's
  attribute plan and adoption both call it, which deletes the `many?` branch and its edge-case docstring
  (the uncommitted fix-adoption-vector edit).
- A refused vector element retracts **its cell**, not `[e a v]`, so no position-only cell is left. The
  remaining positions keep their order.
- An unchanged declaration retracts nothing, because every element validates against the element schema.

## 5. What disappears (file:line, lines)

| Item | Where | Lines |
|---|---|---|
| Ordinal entries and declarations | `seon.fn.arity.edn` / `seon.call-preparation.edn:115,135,155` (arity order); `seon.fn.argument.edn` (order; `index` leaves the row but stays declared for call-preparation errors, `seon.call-preparation.edn:193`); `seon.fn.binding.child.edn`, `seon.fn.binding.entry.edn` (2 each); `seon.test.failure.edn:42,66`; `seon.test.seed.edn:10,16,25,31` | ~14 |
| Ordinal writers | `src/seon/program.cljc:732, 791, 846-855, 1030-1031, 1064`; `src/seon/test/runner.clj:1324`; `src/seon/fn.clj:486-494, 509, 559, 582`; `src/seon/test.clj:148` | ~20 |
| Ordinal readers | `src/seon/db.clj:4012-4015` (`get-else … order -1`); `src/seon/sci/eval.clj:2413`; `src/seon/fn.clj:2727` | ~6 |
| Tuple ordinal (`contexts` → `[:vector :string]`) | `src/seon/test/runner.clj:209, 1377-1380`; `src/seon/test.clj:57-58` | ~7 |
| Set/vector branch | `src/seon/db.clj:3472, 3509-3515` | ~8 |
| Two-keyword lookup-ref guard | `src/seon/schema/datahike.clj:440-468` | 29 |
| Adoption's vector special case | `src/seon/cluster.clj:1241-1270` (`many?` branch and docstring edge case) | ~6 |

That is about 90 deleted lines. About 115 are added: encoder about 20, `vector-tx` about 25, pull rewrite
plus decode about 25, `q` rewrite about 25, writer regroup plus the two refusals about 15, and
`seon.db.edn` about 5. The net is about +25. That is over the ~100-added-line threshold only with the `q`
rewrite; without it, about 90 added. The skill and guide claims "Store an ordinal on ordered members"
(`.agents/skills/data-modeling/SKILL.md`) and guide §6 are corrected in the same commit.

`call_preparation.clj`'s 21 uses of `:seon.fn.argument/index` and `:seon.fn.arity/order` build in-memory
plan maps from contracts (`:133`, `:150`, `:544`, `:573`). They keep their keys, and the value comes from
`map-indexed` over the decoded vector.

## 6. Migration on the live store

This uses the existing incremental adoption. There is no reset.

- The 28 author attributes change cardinality from many to one. `accretive-property-change?`
  (`src/seon/cluster.clj:983-1008`) allows only one → many, so all 28 adopt as `:replace`. Their data is
  dropped, and component values are retracted as entities (`attribute-retraction`, `:1050-1090`).
- `:seon.test.failure/contexts` changes value type, so it takes the purge path (`:1074-1077`).
- The ordinal attributes are retired (`retired-attributes`).
- The two `:seon.db.vector/*` attributes install.

**Re-derivation.**

- Program rows (arities, arguments, bindings) are republished from files. `dropped-summary` names them.
- Test failures and seeds come back with the next run or publication.
- Past read evidence and fault links are lost. Missing evidence is unknown, so a system turn re-reads
  once. The data is disposable (owner).

**Landing.** Schema resources, bridge, `seon.db` and the converted writers go in one commit. Root proves
adoption first on a branch of the live store: `d/with` over `declaration-changes` against a fork of
`default`, then counts dropped datoms. Then one batched `bin/seon start --head`.

**RESET NEEDED only if** that adoption refuses, for example with `removed-definition-error` naming a
surviving ordinal writer, which the same commit should have converted. The fallback is a fix-forward, not
a reset.

## 7. One class regression set

The set lives in `test/seon/schema/vector_storage_test.clj`. It uses `with-database` and `transacted!` and
goes through `seon.db`.

1. **Round trip.** A scalar vector with order and a duplicate (`["b" "a" "b"]`); a two-keyword vector
   (`[:a/x :a/y]`, the lookup-ref trap); a tuple-element vector; an EDN-union element. Each goes through
   transact → `pull`, `pull-many`, `q` with a pull find-expression, and a `q` data pattern. The assertion
   is equality, including duplicates and order.
2. **Peer refs inside vectors.** Order and duplicates are kept. Retracting the parent removes its cells,
   and the target survives. Retracting a referenced target alone is refused with
   `::vector-element-swept`, naming parent, attribute and position. Retracting the target together with
   its cell, or reasserting the vector, is admitted.
3. **Components inside vectors.** Retracting the parent cascades cells and elements, with 0 datoms left.
   Replacing the element at position k leaves no ownerless child (the regression for the card-one probe).
   Reasserting an unchanged vector emits 0 datoms. Append emits one cell.
4. **Adoption.** `attribute-change-tx` with an unchanged `[:vector …]` declaration retracts nothing. A
   narrowed element schema retracts exactly the invalid cells, and the survivors keep their order.
5. **Writer validation sees decoded vectors.** `[:vector {:max 2} :string]` with 3 elements refuses at
   write.

Existing tests whose assumptions retire and must be updated in the same commit:

- `test/seon/program_test.clj` (order/index)
- `test/seon/fn/seed_admission_test.clj` (11 ordinal sites)
- `test/seon/schema/datahike_parity.edn`
- `test/seon/test_failure_facts_test.clj`
- `test/seon/test_runner_test.clj` (contexts)
- `test/seon/cluster/instruction_test.clj`, `agent_identity_test.clj`, `source_reconciliation_test.clj`,
  `schema_test.clj` (arity order)

Root runs the focused list: `seon.schema.vector-storage-test` plus those namespaces.

## 8. Open questions for Astra

1. Is refusing a swept vector element (§4) the right reading? The alternative is a declared sweep. Is
   `gate-tests` a token observation that belongs as `[:vector :qualified-symbol]` (data-modeling
   statement/token rule)?
2. The `q` clause rewrite (+25 lines, correct for every future author) against hand conversion of 9 sites
   (+9 lines).
3. Should `:sequential` share this encoding? It has 0 live attributes.
4. Transparency (the owner's requirement). Which read surfaces an agent can reach still expose cells? The
   candidates are raw `d/datoms` or entity access, `history`/`as-of`/`since` values passed through `seon.db`,
   transaction reports seen by listeners and wake matching, read evidence, and `[:db/add e a v]` on a vector
   attribute (refused, with the fix named). Each one either decodes through the one bridge decoder or is
   named as a raw-storage view.

Probe hygiene: all measurements used memory stores created and deleted inside the probe. Two runs that
failed on probe syntax left two small memory stores unreleased in `default`'s JVM, up to 2000 parents.
The scratch namespace `tmp.vector-probe` was removed.
