---
type: research
status: design, revision 2 — answers Astra's review (c2534eef9, REVISE, V1–V6 accepted by root); prototype proven at the REPL; ends in THE INSTRUCTIONS
created: 2026-09-27
revised: 2026-09-27
lane: design-vector-storage-opus (rev 1), vector-design-revise-opus (rev 2)
tags: [schema, datahike, bridge, components, adoption]
---

# One way to store a vector

**Requirement (owner, 2026-09-27).** "I want transparent conversions to happen so agents have a vector they can put
data in and just retrieve it and the vector comes back." An agent puts a vector in and gets the same vector back,
with its order and its duplicates, from every read it ordinarily uses. It never sees a cell, a position or a presence
fact, and it never writes one. Cells appear only in raw-storage views, which show the true storage. Vectors are
first-class in the schema bridge, orthogonal to component, with one consistent storage. Skills and the data-modeling
guide are updated after the type is implemented and fully tested, not before.

**What exists.** The bridge's `compiled-storage` (`src/seon/schema/datahike.clj:77-145`) maps `[:set T]`,
`[:vector T]` and `[:sequential T]` alike to a cardinality-many attribute of `T` (`:111-114`). Datahike stores
cardinality-many as a set of datoms, so a vector loses its order and its duplicates, and `[]` stores nothing
(`reference-code/datahike/src/datahike/db/transaction.cljc:719-779`). Authors patch order with ordinals on component
children or inside tuples.

**Principal approach (revised).** Each element of a `[:vector T]` attribute `a` lives in one bridge-owned **cell**:
an identity-less component of its parent through `:seon.db.vector/cells`, holding `:seon.db.vector/position` and
exactly one value of `a` itself. `a` keeps the attribute the bridge installs today, cardinality-many of T's own
storage, so ref resolution, sweep and cascade stay the element's own. A parent names every present vector attribute,
empty ones included, in `:seon.db.vector/present`. The codec is transparent at four seams that already exist: the
transaction encoder (`encode-transaction-in`), the final-report validator (`write-owned-values-error`), the read
seams every Seon read and replay already passes (`pull-call`/`replay-read` and `encode-query-request`/
`decode-query-result`), and one logical view of transaction reports.

**What changed from revision 1, and why.**

| Rev 1 | Rev 2 | Evidence |
|---|---|---|
| `a` becomes card-one: all 28 attributes adopt as `:replace`, their data dropped | `a` keeps its installed card-many; members move into cells once, identities kept | §1, §6; prototype `:v6/migration-moves-members-keeps-identities` |
| no presence fact | `:seon.db.vector/present` per present vector (V1) | `:v1/*` |
| positional diff retracts changed cells | multiset diff: a retained element keeps its cell, only its position moves (V2) | `:v2/*` |
| one `:db.fn/call` per parent | one batched call per transaction (per-call cost is O(effective datoms so far): quadratic) | §5.2 |
| Datalog: constant-attribute rewrite only | membership contract for every pattern form, `missing?`, rules, input-bound and free attributes (V3) | `:v3/*` |
| pull rewrite in `pull-call` only | one lowering used by pull, query pulls and replay; options, reverse refs, legacy forms; change views refused (V4) | `:v4/*` |
| "one raw retraction converts" | raw vector operations keep a meaning (append, remove, clear); full caller list (V5) | `:v5/*`, §7 |
| `explicit-value-members` deleted | kept: the two-keyword trap fires for sets too | `:dep/two-keyword-set-still-read-as-lookup-ref` |

## 0. Facts (HEAD c2534eef9; live `default` archive b7e148de6)

`default` has 28 installed attributes declared `[:vector …]`, all cardinality-many (probe: `(schema.datahike/storage-schema …)`
over `database-attributes-in`, 0.2 ms). Live datoms:

| Kind | Attributes (current datoms) |
|---|---|
| Component, 8 | `:seon.fn/arities` (2569), `:seon.fn.arity/arguments` (4831), `:seon.fn.binding/entries` (889), `/children` (122), `:seon.cluster.eval/read-evidence` (458), `:seon.test/failures` (0), `:seon.test/seed` (0), `:seon.maintenance.result/collect-branches` (0) |
| Peer ref, 2 | `:seon.cluster.eval/faults` (1), `:seon.test.accretion/gate-tests` (0) |
| Scalar, 14 | `:seon.render/units` (13), `:seon.config.fs/roots` (1), `:seon.config.ai/stop` (1), the other 11 (0) |
| Tuple, 4 | `:seon.test.failure/contexts` (245, `[:tuple [:int {:min 0}] :string]`), `:seon.ai/wire` (9), `:seon.sci.eval/stacktrace` (0), `:seon.sci.kernel/in-flight` (0) |

**Ordinals.** `:seon.fn.arity/order`, `:seon.fn.argument/order` and `/index` (both from one `order`,
`src/seon/program.cljc:1030-1031`), `:seon.fn.binding.child/order`, `:seon.fn.binding.entry/order`, the stored
`:seon.test.seed/ordinal` and the int in `:seon.test.failure/contexts`. **Correction:** `:seon.test.failure/ordinal`
is not a vector ordinal; it is the occurrence number at one site and an input of `:seon.test.failure/id`
(`src/seon/test/runner.clj:1326-1329`). It stays. `:seon.db/source-argument-position` stays (rev 1 §0).

**Entity-id order is not ordinal order.** On live data the arity eid order equals `:seon.fn.arity/order` for 2401 of
2414 functions, and argument eid order equals `:seon.fn.argument/order` for 2330 of 2499 arities (probe, 12 ms). The
stored definition digests were computed over datom order, which is eid order (`declaration-value`,
`src/seon/db.clj:4334-4363`, "reproduced all 7,040 stored function and test digests").

## 1. Encodings compared, afresh

Requirements: order, duplicates, `[]` distinct from absent (V1), per-element native ref semantics including tempids
created in the same transaction, retained component identity (V2), transparent membership Datalog (V3).

| Encoding | Order + dups | `[]` ≠ absent | Element refs (resolve, sweep, cascade, tempids) | Verdict |
|---|---|---|---|---|
| Today: card-many set | no | no | yes | the defect |
| Members stay on the parent + one order datom listing them | yes | yes | **no** for tempids: Datahike resolves a tempid only in a ref-typed value (`transaction.cljc:1336-1340`); a tuple or string order value naming a same-transaction element cannot (`:684` resolves tempids only for composite `:db/tupleAttrs` upserts). New arities and new faults are same-transaction entities | refused |
| Position on each component child | yes | no | a peer target sits in many vectors at different positions | two encodings; refused |
| One EDN value / `[pos v]` tuples / homogeneous tuple | yes | yes | no refs in the value; tuples cap at 8 (`:1021-1024`) | refused |
| Container entity per vector | yes | yes | needs a second, bridge-invented attribute name per vector for the element | refused (invented names) |
| **Cells, element attribute card-one (rev 1)** | yes | only with presence | yes | changes all 28 installed attributes: `:replace`, data dropped, 30 cardinality-reading call sites see scalars |
| **Cells, element attribute keeps its installed card-many (rev 2)** | yes | with presence | yes | **chosen** |
| Cells ordered by cell eid instead of a position datom | yes | with presence | yes | −1 datom per element, but order then rests on eid allocation order, which Datahike does not promise and its import path remaps (`transaction.cljc:1395-1407`); refused |
| Presence stored only for empty vectors | yes | yes | yes | −1 datom per non-empty vector, but the writer must flip the marker and `missing?` needs two clauses; refused for one uniform rule |

Why rev 2 is smaller in change although equal in datoms: Datahike allows a cardinality-many attribute to hold one value
per entity. Keeping `a` card-many means the bridge's derived declaration, every installed schema, adoption, and every
caller that asks "is this attribute a collection" by installed cardinality (30 sites, §7 S9) stay as they are. The
writer enforces "exactly one value of `a` per cell". The element is written `{a [v]}`, which also defuses Datahike's
two-keyword lookup-ref guess (`maybe-wrap-multival`, `transaction.cljc:719-737`) for keyword and tuple elements.

Storage per vector: 1 presence datom + 3 datoms per element (link, position, element). Measured: a 3-element scalar
parent 10 datoms (set: 3); a 3-element component parent 14 (today with ordinals: 10). The live program subset
(2414 functions, 2579 arities, 4862 arguments, fields probed) stores 42,276 datoms as cells against 22,481 as sets
without ordinals, about 34,800 with today's three ordinal datoms: **+22%**.

## 2. The encoding, exactly

`resources/seon/schemas/seon.db.vector.edn`:

```clojure
#:seon.db.vector{:cells    [:set {:seon.db/component true :seon.db/component-schema :seon.db.vector/cell} :seon.db/ref]
                 :position [:int {:min 0}]
                 :present  [:set :qualified-keyword]
                 :cell     [:map {:seon.db/attributes true}
                            [:seon.db.vector/position :seon.db.vector/position]]}
```

- `compiled-storage` (`src/seon/schema/datahike.clj:111-114`) keeps `::cardinality :db.cardinality/many` for `:vector`
  and `:sequential` and adds `::vector? true`. `:set` is unchanged. Q3 of rev 1 is answered: `:sequential` shares the
  encoding and reads back as a vector (0 installed instances).
- `vector-attribute-in? [projection a]` is true when `a` is `::vector?` **and** the projection declares
  `:seon.db.vector/cells`. The second condition is the temporal switch, not a staging flag: an as-of value older than
  the migration carries a projection without the bridge attributes (`carried-projection`, `src/seon/db.clj:1565`,
  keys an as-of view by its own declarations) and reads today's storage as today.
- A parent: `[p :seon.db.vector/present a]` for each present vector; `[p :seon.db.vector/cells c]` per cell.
- A cell: `[c :seon.db.vector/position i]`, `[c a v]`, nothing else. Positions of one `(p, a)` are exactly `0..n-1`.
- Relationship kind and collection shape stay orthogonal:

| Element | `a` on the cell | Parent retracted | Element or target retracted | Validation |
|---|---|---|---|---|
| Scalar / tuple | value | cells cascade | n/a | parent's decoded `[:vector T]` |
| Peer ref | ref | cells cascade, target survives | sweep empties the cell → refused unless the vector is reasserted or the cell retracted in the same transaction | decoded vector |
| Component | ref, `:db/isComponent` | cells and elements cascade | n/a (owned) | element through `a`'s `:seon.db/component-schema`; one owner (cell) |

## 3. The contract, surface by surface

### 3.1 Writes (V1, V2, V5)

| Transaction data | Meaning |
|---|---|
| `{… a [x0 x1 …]}` in an entity map | the whole value of `a`; replaces the stored vector |
| `{… a []}` | present and empty (V1) |
| `[:db/retract e a]`, `[:db.fn/retractAttribute e a …]` | absent |
| `[:db/add e a x]` | append `x` |
| `[:db/retract e a x]` | remove every occurrence of `x` |
| `[:db/retractEntity p]` | cells cascade; component elements cascade; peer targets survive |
| any raw operation on `a` with a tempid `e` | refused `::vector-tempid-operation`: "assert a new entity's vector in its entity map" |
| a map value that is not sequential | refused `::vector-value-not-sequential` |

- **Encoding.** A map with no `:db/id` (or a tempid) and no identity attribute is always a new entity: its vectors
  become inline cells. Any other map, and every raw vector operation, becomes a request `[handle a op arg]`;
  `handle` is the numeric id, lookup ref or `[identity-attribute value]`. All requests of one transaction run in
  **one** `[:db.fn/call vectors-tx projection requests]` appended after the transaction's own operations, so an
  upserted parent resolves (`in-transaction-db`, `transaction.cljc:1055-1062`, `:1171-1172`). Requests on one
  `(e, a)` fold in order (assert replaces, add appends, retract removes, clear makes absent): a `retractAttribute`
  before an assertion (`src/seon/program.cljc:1365`, `src/seon/reconcile.cljc:284`, `src/seon/fn.clj:3267-3271`) is a
  replacement, not a clear. Two maps giving one identity the same vector: the last wins. Two maps giving one tempid
  the same vector: refused `::vector-positions` ("assert a vector once per entity per transaction").
- **Identity (V2).** `vector-diff` matches desired elements to stored cells as a multiset. Key: the value for scalars;
  the entity id for peers and for components named by id; the logical value (pulled `[*]`, ids stripped) for component
  maps without an id. A matched element keeps its cell; if its index differs, one `[:db/add cell position i]`. An
  unmatched element gets a new cell; an unmatched cell is `retractEntity`d, which destroys a component element that
  was removed and nothing else. So `[A B] → [B A]` changes 2 positions and keeps both entity ids, by id or by value;
  reasserting an unchanged vector emits 0 datoms; appending emits one cell; two equal independent maps stay two
  entities; one component id twice is refused `::vector-component-shared` (existing single-owner law). A component
  named by id with more attributes also gets those attributes written.
- **Cells a sweep emptied** are retracted by any request on that parent; a gap then left in another vector refuses.
- Internal operations the codec emits (cells, migration) are marked encoded (`mark-encoded`,
  `src/seon/schema/datahike.clj:342-344`), so they are never re-encoded; a map carrying `:seon.db.vector/position`
  is a cell and its `a` holds one element.

### 3.2 Final validation

Inside `write-owned-values-error` (`src/seon/db.clj:3558`), `write-entity-value` (`:3497`) reads a parent's cells into
logical vectors and refuses, through the same owned-value refusal: a cell with no vector value (`::vector-element-swept`,
naming parent, attribute from the swept datom and position), more than one (`::vector-cell-ambiguous`), positions not
`0..n-1` (`::vector-positions`), cells without presence, and a vector attribute's datom on an entity with no position
(`::vector-member-outside-cell`: a write that bypassed the encoder). An unowned cell is already refused as an unowned
identity-less entity. Roots are discovered from cells upward (cells is a component attribute), so every touched parent
is validated as a whole value against its entity schema: `[:vector {:max 2} T]` with 3 elements refuses at write.

### 3.3 Datalog (V3)

**Contract.** A data pattern over a vector attribute means **membership**, exactly as Datalog means a
cardinality-many attribute today: `[?e a ?v]` binds each element; results are relations (sets); order and
multiplicity are a pull concern. Every existing query keeps its meaning.

**Lowering, once, in `encode-query-request`** (`src/seon/db.clj:2072`), which both `q` (`:2468`) and `replay-read`
(`:1015`) already call:

| Pattern | Lowered |
|---|---|
| `[$? e a v tx? added?]`, `a` a vector attribute, or `?a` bound by `:in` only to vector attributes | `[$? e :seon.db.vector/cells ?c] [$? ?c a v tx? added?]` |
| `?a` bound by `:in` only to non-vector attributes | unchanged |
| `?a` free or mixed | `(or-join [e ?a v …] (and [e ?a v …] [(logical-attribute? ?a)] [(missing? $ e :seon.db.vector/position)]) (and [e :seon.db.vector/cells ?c] [?c ?a v …] [(logical-attribute? ?a)]))` |
| `(not …)`, `(or …)` containing lowered patterns | `not-join` / `or-join` over the original variables (a fresh `?c` would otherwise break `not`/`or` variable rules) |
| rule bodies in the `%` input | lowered the same way |
| `[(missing? $ e a)]` | `(not [$ e :seon.db.vector/present a])`: an empty vector is not missing |
| `get-else` / `get-some` on `a` | refused `::vector-scalar-function` with the fix (pull, or a membership pattern) |
| `(pull ?e sel)` find elements, literal or `:in`-bound | selector lowered before execution, result decoded (§3.4) |

`missing?` is used instead of `not` in the union because Datahike inlines a scalar input into `?e`, and a `not` with no
variable is refused ("Insufficient bindings", observed at the REPL). Reverse lookups (`?v` bound, `?e` free) are the
same lowered pattern.

**Temporal Datalog.** Cells are element-immutable: a changed element is a new cell, written with its link and
position in one transaction, and removed with them in one transaction; only positions change in place. So membership
over `history` and `since` is an exact change view: `["a" "b"] → ["b" "c" "b"]` answers `{["c" true] ["a" false]
["b" true]}` over `(since (history db) t)` (`:v4/since-history-membership-is-an-exact-change-view`). Rev 1's concern
("a changed cell without its older link") cannot arise.

### 3.4 Pull, entity and replay (V4)

One function, used by `pull-call` (`src/seon/db.clj:2624`), `entity` (`:2789`, wildcard), query pull elements and
`replay-read` `:pull`/`:pull-many` (`:1053-1067`): **lower the logical selector → `pull-plan-with-evidence` → decode**.
Read evidence therefore records the physical plan (cells and positions included), so a reorder invalidates an ordered
read; the stored request stays the logical selector, so replay lowers again.

- `a` or `{a sub}` → one shared `{:seon.db.vector/cells [:seon.db.vector/position a {b sub} …]}` plus
  `:seon.db.vector/present`; decoding groups cells by the one vector attribute each carries, sorts by position,
  decodes elements (nested selectors recursively) and removes the bridge keys.
- `[a :as k :limit n :default d]` and the legacy `(limit a n)` / `(default a d)` forms (used at
  `src/seon/sci/eval.clj:2408-2413`): the options are stripped from the physical selector and applied to the logical
  vector (so a limit can never cut the shared cells relation of another vector). A default keeps `:db/id` in the
  physical selector so an entity with nothing stored still answers.
- `*` needs no rewrite (cells are components, `pull_api.cljc:346-350`); decoding restores every present vector.
- Reverse `:x/_a` → `{:x/_a [{:seon.db.vector/_cells sub}]}`, decoded to the distinct owning parents.
- **as-of**: complete; the lowering reads the value's own carried projection.
- **history / since**: a pull there would mix versions (probe: two cells at position 0 in a history pull), so a pull
  touching a vector attribute over a history or since value refuses `::vector-temporal-view` with the fix "pull from
  `(as-of db t)`; query membership changes over history".

### 3.5 Transaction reports, listeners, wakes

Native reports stay native. `seon.db/logical-datoms` maps a report's datoms to logical facts: `[cell a v]` reads
`[parent a v]`; a position change reads `{:e parent :a a :moved i}`; bridge links drop (O(tx-data), one AVET seek per
touched cell; 13 ms for a 2000-parent report). Every first-party consumer that interprets report datoms by attribute
or entity reads it (list in §7 S8). Wake matchers on a vector attribute match logical datoms.

### 3.6 Read-surface inventory (review Q4), dispositions

| Surface | Disposition |
|---|---|
| `seon.db/pull`, `pull-many`, `entity`, query pulls, replay | logical (§3.4) |
| `seon.db/q` and replay `:q` | membership contract (§3.3) |
| as-of values through `seon.db` | logical, by the value's own projection |
| history / since values | Datalog: exact membership change view; pull: refused by name |
| transaction reports seen by first-party consumers, wakes | `logical-datoms` |
| `seon.db/datoms`, index pages, `seon.db/diff`/`apply-diff`, native `datahike.api`, the debug page's `generic-entity` (`src/seon/render/web.clj:607`) | raw storage, declared so; they show cells, which is the truth |
| raw `:db/add` / `:db/retract` on `a` | a write with a declared meaning (§3.1) |

## 4. Adoption and migration (V6)

- **Adoption.** No vector attribute's installed declaration changes, so all converge; the three bridge attributes
  install. `invalid-value-retraction` (`src/seon/cluster.clj:1266`) keeps validating each value against the element
  schema, and for a vector attribute emits the logical `[:db/retract parent a v]`, so the request renumbers the
  survivors instead of leaving a swept cell.
- **Migration, once**, appended by `declaration-changes` (`src/seon/cluster.clj:1298`) when `:seon.db.vector/cells`
  is declared but not installed: for each vector attribute, the members stored directly on each parent (one AEVT
  range; an entity with a position is already a cell) are retracted by `[:db/retract p a v]` (no cascade,
  `transaction.cljc:1078-1089`) and re-asserted as cells, with presence. **Order: entity id for refs, value for
  scalars.** Eid order is today's datom order, so `declaration-value` produces the same vectors and every stored
  definition digest stays valid through the switch; the ordinals remain the order readers use until step S11, whose
  republication rewrites every program vector in source order. No data is dropped and component entity ids survive.

| Attribute group | Live datoms | At S10 (switch) | Re-derivation |
|---|---|---|---|
| arities, arguments, binding entries/children | 8411 | moved into cells, eids kept | S11 republication writes source order |
| read-evidence | 458 | moved, eids kept; evaluations stay refreshable | none needed |
| config `fs/roots`, `ai/stop` | 2 | moved; no restoration needed | none |
| `render/units`, `ai/wire` (schema-entity properties) | 22 | moved | next schema-entity publication writes declared order |
| `cluster.eval/faults` | 1 | moved | none |
| `test.failure/contexts` | 245 | changes to `[:vector :string]` at S11: `:replace` with purge (value type) | next test run |
| `test.accretion/gate-tests` | 0 | changes to `[:vector :qualified-symbol]` at S10 (a receipt must not block deleting a test; review Q1) | none |
| other 16 | 0 | nothing | — |

Measured (prototype, memory store): 10,000 members of 2000 parents migrate in 25 ms planning + 399 ms transaction;
running the plan again yields an empty plan. The Seon writer's armed validation of the same transaction is not
measured; S10's branch proof times it.

## 5. Costs (V6), honestly

All numbers: prototype `tmp/vector/prototype.clj` over plain Datahike memory stores (pinned fork `fe1d4e26`) in the
`default` JVM, warm, **dependency plus prototype validator; not the Seon armed writer**. S3–S10 each time the same
probes through `seon.db` on parent and change.

### 5.1 Measured

| Operation (2000 parents, 3 elements) | Today's set | Cells |
|---|---|---|
| write new parents | 42–47 ms | 140–163 ms |
| write identity parents (one batched `vectors-tx`) | — | 190–201 ms |
| reassert unchanged | — | 33–42 ms, 0 datoms |
| append / reorder | — | 104–106 / 148–150 ms |
| component parents / unchanged | — | 236–276 / 70–77 ms (0 datoms) |
| one entity with two vectors, single transaction | 0.38 ms | 0.69 ms |
| `pull-many` scalars | 3.4–3.8 ms | 25–33 ms (physical pull 23.8, decode 3.0) |
| `pull-many` components | — | 42–46 ms |
| one nested pull (vector of components with a nested vector) | — | 57 µs |
| membership query | 1.2–1.9 ms (2000 rows) | 8.3–11.5 ms (4000 rows) |
| free-attribute discovery of one entity | 0.02 ms | 0.74–0.90 ms warm, 35 ms first call; the same at 20k and 42k datoms |
| real program: 2414 functions, arities + arguments `pull-many` | 26 ms | 69 ms |
| real program: arity-gate query | — | 8.8 ms first call; both hit Datahike's query cache warm (0.006–0.009 ms) |
| real program: write all / republish unchanged | — | 388 ms / 91 ms, 0 datoms |
| `logical-datoms` of a 2000-parent report | — | 13 ms |

### 5.2 Why one batched transaction function

Every `:db.fn/call` derives a speculative cache context over the transaction's effective datoms so far
(`in-transaction-db`, `transaction.cljc:1055-1062` → `speculative-cache-context`,
`reference-code/datahike/src/datahike/db.cljc:444-462`). One call per parent is quadratic: 250 → 34 ms, 500 → 91,
1000 → 281, 2000 → 951 ms; 2000 empty calls interleaved with maps take 180 ms, one call at the end 29 ms. Batching
made the identity-parent write 201 ms. This is also a finding about the fork (§8).

### 5.3 Complexity per operation

- **Write** of a vector on an existing parent: O(cells of that parent) reads (all its vectors' cells are scanned to
  find this one's), O(n log n) sort, O(n) multiset matching; component value matching pulls each old component whole:
  O(its nodes). Insert at the front moves O(n − k) positions. Live vectors are ≤ 10 long.
- **Validation**: O(cells of each touched parent) plus the existing owned-value walk; cells count against
  `:seon.config.db/validation-node-limit` (250,000).
- **Pull**: ~3× the datoms of a set; Datahike's nested component pull dominates (89% of the time above). A pull budget
  (`:datahike/budget-exceeded`) is reached about 3× sooner on vector-heavy selectors.
- **One-time**: migration ~0.4 s at live volume (dependency level); S11's full republication (§9).

## 6. Prototype and proof

Files (disposable, `tmp/vector/`): `prototype.clj` (the codec: encode, `vectors-tx`, validator, selector and Datalog
lowering, decode, `logical-datoms`, migration; ~530 non-comment lines including docstrings), `regressions.clj`
(36 cases), `bench.clj`, `live_probe.clj`. Run in JVM mode:

```clojure
(load-file "tmp/vector/prototype.clj") (load-file "tmp/vector/regressions.clj")
(tmp.vector.regressions/run)   ;=> 36/36 green, 97 ms warm including 36 store create/delete; slowest case 4 ms
```

Regressions (all green): round trip with order, duplicates, two-keyword elements and tuples; new-parent inline then
existing-parent diff; `[]` vs absent; clear to empty then absent; unchanged reassertion emits 0 datoms; peer order,
duplicates and same-transaction tempid targets; parent retraction leaves no cell and keeps the target; target
retraction alone refused `::vector-element-swept`; target retraction with reassertion admitted; raw add appends and
raw retract removes every occurrence; tempid raw operation refused; one tempid asserting a vector twice refused; one
identity twice, last wins; component cascade leaves exactly the prior datom count; reorder by id and by value keeps
entity ids; replacing one component leaves no ownerless child; equal independent maps stay two; one component twice
refused; append is one cell; nested vector update inside a component; membership and reverse membership;
input-bound, collection-bound and free attributes; `not`, `or`, rules, `missing?`; `get-else` refused; pull find
elements literal and input-bound; aliases, limits, defaults, nested and reverse selectors; wildcard with nested
components; as-of reads the older vector; history membership; since/history exact change view; pull over change
views refused; `logical-datoms` name the parent; legacy selector forms; migration moves members, keeps component ids,
refuses a raw member written outside a cell, and is idempotent; the two-keyword set trap.

**Real data** (`live_probe.clj`, reads `default` only): every function's arities and arguments, ordered by today's
ordinals and stripped of them, written as vectors into a memory store: 2414 functions, 2579 arities, 4862 arguments
round-trip **exactly**; the arity gate's query lowered over cells returns **exactly** the live answer (2579 rows);
republishing the whole program unchanged emits 0 datoms.

Probe hygiene: every store was created and deleted inside its probe (`finally`); no default data was written; the
`tmp.vector.*` namespaces were removed from default's JVM at the end.

## 7. Answers to the review

- **V1**: `:seon.db.vector/present` (§2, §3.1); `missing?` and pull defaults honor it; `:v1/*`.
- **V2**: multiset diff keeping cells, only positions move (§3.1); `:v2/*`, and 0 datoms for an unchanged program.
- **V3**: the membership contract and its lowering for every pattern form (§3.3); `:v3/*`; exact on the live gate query.
- **V4**: one lowering + decode for pull, entity, query pulls and replay; options applied logically; reverse refs;
  as-of by the value's projection; change views: Datalog exact, pull refused (§3.4); `:v4/*`.
- **V5**: raw operations keep declared meanings, so the receipt writers (`src/seon/turn.clj:1472-1478`,
  `src/seon/effect.clj:334-338`, receipts are existing entities) need no change; the complete conversion list is S2–S11.
- **V6**: costs in §5 labeled by what they measure, the quadratic transaction-function finding, the data-preserving
  migration inventory in §4. Net source lines in §10.

## 8. Findings outside the assignment

1. **Datahike fork: each `:db.fn/call` costs O(effective datoms so far)** (§5.2). Any Seon transaction with many
   function calls is quadratic. Owner: the datahike fork (`speculative-cache-context` could be maintained
   incrementally). Needs a task entry (this lane edits no `resources/`).
   Implementation (lane vector-impl-opus, 2026-09-27) keeps one batched call per transaction
   (`schema.datahike/batched-vector-requests`); the fork is not changed. Open against the fork.
2. **The two-keyword lookup-ref trap fires for sets too** (`#{:p/id :t/id}` under a card-many keyword attribute is
   read as one value and refused, "Bad entity value"). Rev 1's deletion of `explicit-value-members` was wrong; it stays.
3. **Datahike's writer logs every refused transaction at error level** (`:datahike/write-error … validation
   rejected`), expected refusals included: a log line beside the delivered refusal.
4. **`dropped-summary` misses component-held attributes** (`src/seon/cluster.clj:1348-1382` joins `:seon.fn/file` on
   the entity carrying the attribute; arity and argument ordinals live on components with no file). Fixed in S11.
5. **Many-to-set normalizers** compare or restore vectors as sets (`reconcile.cljc:194`, `turn.clj:1185`,
   `fn.clj:3546-3570`, `fn.clj:3812-3836`, `error.clj:212`, `cluster.clj:1440`, `render/value.clj:222-226`): a
   reorder-only edit compares equal and is never written. Fixed in S9.

## 9. Owner question

**One-time full program republication at S11 (over 10 s; AGENTS requires owner authorization).** Retiring the
ordinals changes every program entity's canonical content, so every definition digest changes, and test evidence
keyed by digests must be regenerated once.

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| **A (recommended)**: S11 as written — retire ordinals, republish every program file once, root runs the bulk tier once | one order per vector, no mirror | one publication of all files (a 241-file publication measured 10.7 s warm inside a 47 s boot publication, `src/seon/fn.clj:2733-2736`); one bulk test run | one slow boot, measured and reported |
| B: land S1–S10 now; run S11 with the next planned nuke or reset | transparent vectors now | none now | the ordinals stay as a written mirror of position until then (the writer keeps writing both) |
| C: never retire ordinals | — | 0 | a permanent hand-authored mirror; rejected by AGENTS "mirrors are derived" |

## 10. THE INSTRUCTIONS

Ownership: `src/seon/schema/datahike.clj` (bridge lane) and `src/seon/db.clj` (db lane) can run in parallel after S1;
`src/seon/cluster.clj` and `src/seon/render/value.clj` currently carry another lane's uncommitted edits — check
`tmp/orchestrator/file-ownership.md` and send those hunks as patches to their owner. Every step: edit in place, lint
`src script test` with `--cache false`, compile touched schema resources with their consumers
from a `git archive` of HEAD plus the diff, name the focused tests, commit with `git commit --only`. Until S10 the new
code is inert on the live store (`vector-attribute-in?` is false without the bridge attributes); S1–S9 regressions run
in a `with-database` world with `:seon.test-support/extra-schema` supplying `seon.db.vector.edn` plus synthetic vector
attributes (`test/seon/test_support.clj:673-694`). Port each prototype regression into
`test/seon/schema/vector_storage_test.clj` in the step that makes it pass. The prototype function named in each step
is the reference implementation.

**S1 — Bridge declarations (bridge, 30 min).** `compiled-storage` `:111-114`: keep many, add `::vector? true` for
`#{:vector :sequential}`. Add `vector-attribute-in?` (memoized with `schema/projection-cache-value` like
`edn-encoded-attr-in?` `:308-314`; true only when the projection declares `:seon.db.vector/cells`) and
`logical-collection [projection a value]` (vector attribute → `vec`, other many → `set`). +18.
Tests: `vector-attributes-are-declared-by-shape`; `seon.schema.datahike-test` and `datahike_parity.edn` unchanged
(installed declarations do not change).

**S2 — Encoder (bridge, 50 min).** `encode-value-in` `:405`, `encode-entity-in` `:425`, `encode-transaction-data-in`
`:470`, `encode-call-output-in` `:433`, `encode-transaction-in` `:506`: prototype `encode-map`, `inline-cells`,
`encode-tx`, `finish`. New maps get inline cells (`{position i a [v]}`) and presence; other maps and raw vector
operations become requests; `encode-transaction-in` gains the arity `[projection vectors-fn transaction]` and
`encode-call-output-in` receives `vectors-fn` in its call arguments, so requests (top level and inside any
transaction function's output) batch into one `[:db.fn/call vectors-fn projection requests]` at the end; refusals
reuse `refuse-slot!` with rules `::vector-tempid-operation` and `::vector-value-not-sequential`. `explicit-value-members`
stays. +45. Tests: pure expectations of encoded operations (no database).

**S3 — `vectors-tx` (db, 60 min).** In `seon.db`: `cells-of`, `vector-diff`, `vectors-tx` (prototype of the same
names, element keys `element-key`/`stored-keys`; component values via `d/pull` + the S5 decoder, so land S3's
component-by-value matching after S5 or match components by id only until then). `prepared-write` `:4782`: pass
`#'vectors-tx` to `encode-transaction-in`; the batched call precedes `namespace-digests-tx`. +85.
Tests (`extra-schema` world): `:r1/*`, `:r2/*`, `:r3/*`, `:v2/*`, `:v5/*`.

**S4 — Writer validation (db, 50 min).** `write-entity-value` `:3497`: read `:seon.db.vector/cells` and `/present`
into logical vectors (cells charged through `visit-child`), drop the bridge keys, refuse the §3.2 causes through the
owned-value refusal. `declaration-value` `:4334-4367` decodes the same way. **Delete** `::normalize-many`
(`write-attribute-plan` `:3490`), the `normalized` reduce in `write-entity-error` `:3527-3535` and its use at `:4366`:
`write-entity-value` builds each many value in its declared collection with `logical-collection` (a vector attribute on
legacy storage, before S10 or in an older as-of value, stays a vector in datom order, as today). +30 −14. Tests: each refusal cause; `[:vector {:max 2}]` refuses at write; digest parity:
`definition-digest-of` unchanged for a function before and after moving its arities into cells.

**S5 — Selector lowering and decoding (bridge, 50 min).** Pure `lower-selector` and `decode-vectors` (prototype
`attr-spec`, `lower-selector`, `decode-entity`): options, legacy list forms, reverse refs, wildcard, nested. +65.
Tests: pure cases from `:v4/aliases-limits-defaults-nested-reverse`, `:v4/wildcard-nested-components`,
`:v4/legacy-selector-forms`.

**S6 — Read integration (db, 45 min).** One private `pull-logical` used by `pull-call` `:2624` and `replay-read`
`:1053-1067` (the two replay pull branches collapse into it); decoding after `decode-pull-result` `:2253`; the
change-view refusal. `entity` inherits. +20 −8. Tests: `:v1/*` through `seon.db/pull`; `:v4/as-of-reads-the-older-vector`;
`:v4/pull-over-change-views-refused`; `read-evidence-current?` false after a reorder of a pulled vector; replay of a
recorded vector pull equals the pull.

**S7 — Datalog lowering (bridge + db, 60 min).** Bridge: pure `lower-where` (prototype `lower-pattern`,
`lower-clause`, `lower-query`'s rule and pull handling). `seon.db`: apply it in `encode-query-request` `:2072` after
value encoding; decode pull find elements in `decode-query-result` `:2168`; `get-else`/`get-some` refusal returned as
a query refusal beside `query-value-type-error` `:1999`; add private `storage-q` (lower + `d/q`) for the writer's own
queries. +70. Tests: `:v3/*`, `:v4/history-membership-changes`, `:v4/since-history-membership-is-an-exact-change-view`.

**S8 — Reports and wakes (db + callers, 60 min).** Add `seon.db/logical-datoms` (prototype of the same name, +18).
Convert: `write-owned-values-error` changed-roots `:3747`; `report-arity-comparison` `:3938` (`touched`,
`dbi/search [nil :seon.fn/arities arity]` `:3966` → parents via cells, inline `d/q` `:3992` → `storage-q`); the `d/q`
arguments at `:3895-3896`, `:3951-3952`, `:3979` → `storage-q`; `removed-definition-error` `:4216` (arity owner via
the cell's parent); `write-digest-error` `touched` `:4425`; `render-transaction-ai` `:4957` and
`render-transaction-html` `:4997` show logical datoms; `seon.fn/caller-files` `src/seon/fn.clj:2729-2759`;
`seon.fn/report-identities` `:3712`; `seon.call-preparation/report-snapshot` `src/seon/call_preparation.clj:484-504`;
`seon.cluster.wake/route!` `src/seon/cluster/wake.clj:433-520` (match vector attributes on logical datoms). ≈+33.
Tests: arity gate after a reorder-only arity write (`seon.db` arity tests); caller-files names callers of a function
whose arities changed; a listen pattern on a vector attribute wakes on append and on reorder.

**S9 — Collection normalizers (callers, 50 min).** Replace `set` with `logical-collection` in:
`src/seon/reconcile.cljc:192-196` (`normalize-value`) and `:244-252` (`canonical-desired-entity`: keep `[]` for a
vector); `src/seon/turn.clj:1183-1187` (`declared-value`); `src/seon/fn.clj:3546-3570` (`normalized-index-entity`)
and `:3812-3836` (`published-index-entities`); `src/seon/error.clj:212` (`stored-observation`);
`src/seon/cluster.clj:1431-1441` (`store-comparable-value`); `src/seon/render/value.clj:222-226` (`transacted`,
patch to its owner); `src/my/program.clj:354-362` (`entity-facts`: read the entity through `seon.db/pull '[*]`
instead of raw datoms). ≈±0. Tests: a reorder-only republish writes positions; an error restored with a stacktrace
keeps its order; a schema entity's `:seon.render/units` keeps declared order.

**S10 — The switch (resources + cluster, 60 min, then root).** Add `resources/seon/schemas/seon.db.vector.edn` (§2).
`declaration-changes` `src/seon/cluster.clj:1298`: when cells are declared but not installed, append the migration
(prototype `migration-tx`, as a `seon.db` function returning encoded operations; +21). `invalid-value-retraction`
`:1266-1296`: for a vector attribute emit `[:db/retract parent a v]` (+4). `:seon.test.accretion/gate-tests` →
`[:vector :qualified-symbol]` in its schema resource; `src/seon/turn.clj:171-174` stores the symbols (−2).
+35. **Root proof on a branch of live default before the restart:** time the adoption transaction and its armed
validation; 0 dropped datoms; 0 digest refusals; run `tmp/vector/live_probe.clj`'s comparison through `seon.db`
(arities and arguments equal to today's ordinal order except the 13 + 169 eid-order cases, which S11 rewrites);
config `fs/roots` and `ai/stop` read back unchanged. Then one batched `bin/seon start --head`. Focused list:
`seon.schema.vector-storage-test`, `seon.db-test`, `seon.schema-test`, `seon.schema-in-place-test`,
`seon.db.schema-retirement-test`, `seon.schema-encoding-test`, `seon.source-reconciliation-test`, `seon.reconcile-test`,
`seon.program-test`, `seon.fn.seed-admission-test`, `seon.test-runner-test`, `seon.cluster.instruction-test`,
`seon.cluster.agent-identity-test`, `seon.turn-test`.

**S11 — Ordinals retire (callers + resources, 60 min, then root; owner option A).** Delete the writers
(`src/seon/program.cljc:732`, `:791`, `:846-855`, `:1030-1031`, `:1064`; stored seed ordinal `src/seon/fn.clj:494`,
`:559`, `src/seon/test.clj:146`), the readers (`prepared-arity-bounds` `src/seon/db.clj:4013-4040` → pull the arities
and `map-indexed`; `src/seon/sci/eval.clj:2413`; `src/seon/fn.clj:2727`), and the declarations
(`seon.fn.arity.edn` `:order`; `seon.fn.argument.edn` `:order`, and `:index` leaves the row but stays declared for
`seon.call-preparation.edn:85-109`; binding child and entry `:order`; the seed row's ordinal, kept in the seed error
schema). `:seon.test.failure/contexts` → `[:vector :string]`: `src/seon/test/runner.clj:209`, `src/seon/test.clj:55-56`.
Delete the member-by-member retractions now subsumed by vector replacement: `runner.clj:1380-1386`,
`runner.clj:1684-1692`, `fn.clj:3267-3271`, `turn.clj:2319-2320`. Fix `dropped-summary` `src/seon/cluster.clj:1348`
to name the files of component owners. `call_preparation.clj`'s plan maps keep their keys, filled by `map-indexed`
(`:133`, `:150`, `:544`, `:573`). −75 +10. Root: one full republication and one bulk tier run. Focused list as S10 plus
`seon.call-preparation-test` and `seon.fn-test`.

**S12 — Docs (after the full tests).** Guide §9: the collection row, open questions 1 and 6 closed; data-modeling skill:
delete "Store an ordinal on ordered members", state the vector contract with `file:line`; datahike skill: presence in
"The empty set is not a fact", the transaction-function cost; this design's status → implemented.

**Net source lines** (estimates from the prototype, which is larger because it carries its own world and tests):

| Step | Added | Deleted |
|---|---|---|
| S1 | 18 | 0 |
| S2 | 45 | 0 |
| S3 | 85 | 0 |
| S4 | 30 | 14 |
| S5 | 65 | 0 |
| S6 | 20 | 8 |
| S7 | 70 | 0 |
| S8 | 33 | 0 |
| S9 | 8 | 8 |
| S10 | 35 | 2 |
| S11 | 10 | 75 |
| **Total** | **≈ 419** | **≈ 107** |

**Net ≈ +312 source lines**, plus ≈ 300 test lines (the 36 prototype regressions ported). This exceeds the ~100-line
threshold. Justification to the owner: it is the price of the owner's transparency requirement across five read and
write seams; nothing smaller keeps element refs (§1); it deletes every hand-authored ordinal and its writers, readers
and member-retraction loops, the `::normalize-many` branch, and the adoption `:replace` rev 1 required. What it lets go
later: any future ordered attribute is a declaration, not an ordinal plus writer code.

**Time.** Eleven steps of ≤ 1 hour. Bridge lane (S1, S2, S5, S7-bridge) and db lane (S3, S4, S6, S7-db, S8) run in
parallel after S1: about one working day of wall clock, then S9, S10 with root's branch proof and one restart, then
S11 with the authorized republication.
