---
type: research
status: active
tags: [research, database, schema]
---

# Grounding — DB + schema engine (datahike / malli / the seon.db bridge)

> Read-the-source distillation for anyone touching `seon.db` / `seon.schema` /
> the malli→datahike bridge. Every claim is cited to the VENDORED source
> (`reference-code/…:LINE`) and to OUR code. Companion to
> [[library-grounding]] (the per-phase map) — this is the engine-deep version:
> what datahike's transactor/query/validation actually do, what malli's
> instrument actually wraps, and where our bridge re-implements vs. reuses.
> Sibling: [[datahike-primer]] (the no-kinds mindset).

## TL;DR (the load-bearing facts)

1. **There are no kinds, all the way down — and the source PROVES it.** Every
   datahike transactor behavior (cascade-retract, ref-resolution, cardinality,
   CAS) keys off the **attribute's installed schema** via `is-attr?`
   (`db/utils.cljc:24-46`), NEVER off the entity. `component?`, `ref?`,
   `multival?` are one-line `is-attr?` lookups. A ref validates only that its
   value resolves to an eid (`:db.type/id`, `schema.cljc:6`) — datahike does NOT
   check the target's "shape". "What an entity is" is unrepresented at the
   storage layer.
2. **Two validation layers, not one.** seon-malli (rich: `validate-values!`,
   `internal.cljs:824`) runs FIRST, then datahike's own `value-valid?`
   (`schema.cljc:205`) does a COARSE `clojure.spec` type check
   (`:db.type/long` = `Number.isSafeInteger`, etc.). Datahike never sees your
   malli constraints (`:min`/`:max`/`:enum` membership) — if you rely on those,
   the seon gate is the only thing enforcing them.
3. **CAS aborts the WHOLE tx, not just the op.** `compare-and-swap`
   (`transaction.cljc:873-895`) `log/raise`s on mismatch; the raise unwinds
   `transact-tx-data` entirely. This is exactly why `db/cas-assert` works as a
   fence over a bundled work-tx. (Confirmed against the live code — unchanged.)
4. **Lookup-refs require a `:db/unique` attr** — `entid` (`utils.cljc:122-125`)
   raises `:lookup-ref/unique` otherwise. The whole-keyword-in-a-ref-slot trap
   (`[?e :seon.fn/ns :seon.db]` throws) is `entid` trying to resolve the keyword
   as a `[:db/ident …]` lookup (`utils.cljc:133`).
5. **The bridge re-implements malli form-walking by hand** (`internal.cljs:147-360`)
   instead of using malli's schema API (`m/deref`/`m/type`/`m/children`/
   `m/properties`) the way `malli-datomic` does
   (`datomic_schema_gen.cljc:41-201`). This is deliberate (it walks RAW registry
   forms before they're compiled into Schema objects, so it never needs a
   coherent registry), but it is the brittlest seam — see SMELLS §1, §2.
6. **`register!` ≠ datahike install.** `register!` (`schema.cljc:193`) only
   touches the in-memory malli atom; the datahike `:db/valueType` is installed
   LAZILY at first `transact!` via `ensure-datahike-attrs!` (`internal.cljs:1211`).
   This is why `pull`/`query` guard on `installed-schema` (`db.cljs:613`).
7. **`d/q` only uses the AVET point-lookup fast path for `:db/unique` attrs**
   (`query.cljc:1995-1998`). A `[?e a v]` clause on a NON-unique attr falls to
   batch search, not an index seek.

## datahike — what the transactor source actually does

Read: `reference-code/datahike/src/datahike/db/transaction.cljc`,
`…/db/utils.cljc`, `…/schema.cljc`, `…/query.cljc`.

### Everything keys on the attribute, never the entity (`db/utils.cljc:24-46`)

```clojure
(defn multival?  [db attr] (is-attr? db attr :db.cardinality/many))   ; utils.cljc:24
(defn ref?       [db attr] (is-attr? db attr :db.type/ref))           ; utils.cljc:28
(defn component? [db attr] (is-attr? db attr :db/isComponent))        ; utils.cljc:36
```

- **Training-memory mistake:** reasoning "this entity is a Todo, so deleting it
  cascades / its `parent` is typed to Todo." Datahike has no such notion. The
  cascade, the ref-resolution, the cardinality are ALL decided by looking up the
  ATTRIBUTE's installed flags. `:my.todo/parent` cascades iff IT carries
  `:db/isComponent` — independent of what it points at.
- **Correct seon idiom:** model and branch on **attribute presence + ref
  direction**, never a `:kind`. Our bridge sets `:db/isComponent` only from
  `{:seon.db/component true}` on the attr's malli registration
  (`internal.cljs:350`); `:my.todo/parent` is a PLAIN ref → no cascade
  (data-model §2.1, confirmed by the `component?` filter at
  `transaction.cljc:732`).

### CAS is a tx-wide fence (`transaction.cljc:873-895`) ✓ unchanged

```clojure
(defn compare-and-swap [db report op-vec]
  (let [[_ e a ov nv] op-vec
        e  (dbu/entid-strict db e)                 ; lookup-ref → eid (RAISES if missing)
        _  (dbu/validate-attr a op-vec db)
        nv (if (dbu/ref? db a) (dbu/entid-strict db nv) nv)
        datoms (dbi/search db [e a])]
    (if (nil? ov)
      (if (empty? datoms) [(transact-add …) []]   ; OPEN-race arm
          (log/raise ":db.fn/cas failed … expected nil" {:error :transact/cas …}))
      (let [ov (if (dbu/ref? db a) (dbu/entid-strict db ov) ov)]
        …(if (= v ov) [(transact-add …) []]
             (log/raise ":db.fn/cas failed …" {:error :transact/cas …}))))))
```

- The `log/raise` is an `ex-info` throw; it propagates out of the per-op reduce
  in `transact-tx-data`, so **the entire tx is rejected** — the bundled work-tx
  dies with the fence. This is the mechanism `db/cas-assert` (`db.cljs:400`)
  relies on: lead a work-tx with the no-op `[:db.fn/cas ref attr V V]` and the
  whole thing commits atomically iff the assertion holds.
- **OV/NV are ref-resolved** (`:877`, `:884`): for a ref attr, pass an eid or a
  lookup-ref — never a bare keyword/value. The doc-shorthand `[run R]` is wrong;
  use `[:seon.agent.run/id R]` (a lookup-ref). The run-pointer fence is
  live-proven in `run.cljs` (`open-run!`); KEEP it.
- **Training-memory mistake (Datomic muscle):** "CAS just fails that one
  assertion." No — it `raise`s and unwinds the tx. That is the FEATURE here.

### Component cascade is op-expansion, not a deep walk (`transaction.cljc:730`)

```clojure
(defn- retract-components [db datoms]
  (into #{} (comp (filter (fn [^Datom d] (dbu/component? db (.-a d))))
                  (map    (fn [^Datom d] [:db.fn/retractEntity (.-v d)]))) datoms))
```

`retract-entity` (`:897-914`) returns `[report (retract-components db e-datoms)]`
— the emitted `[:db.fn/retractEntity child]` ops are fed back through the
transactor, so the recursion is breadth-first op-expansion. The depth is bounded
only by which attrs are `:db/isComponent`. Idiom to imitate: **transduce
`(comp (filter…) (map…))` into a set**, not `(set (map (filter…)))`.

### Refs resolve THEN coarse-validate (`transaction.cljc:693-702`, `schema.cljc:205`)

`transact-add` resolves a ref value with `entid-strict` BEFORE building the datom
(`:701`), so by the time `validate-val` (`utils.cljc:32`) runs, the ref is a
number. `value-valid?` (`schema.cljc:205`) then checks it against the
`clojure.spec` for `:db.type/ref` = `:db.type/id` (`schema.cljc:6,29`) — i.e.
"is it an eid/string-tempid", nothing more.

- **Training-memory mistake:** expecting datahike to reject a ref pointing at the
  "wrong kind" of entity, or to enforce your malli `:enum`/`:min`. It validates
  ONLY the coarse `:db.type/*` predicate. Rich validation is the seon-malli gate
  (`validate-values!`, `internal.cljs:824`) that runs BEFORE the tx is built.
- **Correct seon idiom:** trust the seon gate for shape/constraint enforcement;
  trust datahike only for type + uniqueness + referential resolution. Two layers,
  on purpose.

### Lookup-refs need `:db/unique` (`utils.cljc:109-148`)

```clojure
(not (is-attr? db attr :db/unique))
(or error-code (log/raise "Lookup ref attribute should be marked as :db/unique" …))
;; and: a bare keyword eid resolves as a :db/ident lookup —
(keyword? eid) (-> (dbi/datoms db :avet [:db/ident eid]) first :e)   ; utils.cljc:133
```

- **Training-memory mistake:** "I can address an entity by any attr value." Only
  `:db.unique/identity` / `:db.unique/value` attrs. Our `db.cljs` cheat sheet
  (`:94-98`) and `internal.cljs:1180-1191` already translate this cryptic raise
  into guidance — and warn against re-registering a shared attr as identity just
  to make a lookup-ref work.
- The keyword-as-eid path (`:133`) is WHY `[?e :seon.fn/ns :seon.db]` throws
  "Nothing found for entity id :seon.db": `:seon.fn/ns` is a ref, so datahike
  calls `entid` on the value `:seon.db`, which tries `[:db/ident :seon.db]`.
  Correct idiom is the ref-JOIN in the cheat sheet (`db.cljs:86-87`).

### `d/q` index selection (`query.cljc:1985-1999`)

```clojure
;; [e a ?v]  → :ea  on EAVT
(and (number? e) a-ground? (symbol? v) tx-free? (not (dbu/multival? source a))) :ea
;; [?e a v]  → :av  on AVET — ONLY when attr is :db/unique
(and (symbol? e) a-ground? tx-free? (scalar-value? v)
     (:avet source) (dbu/is-attr? source a :db/unique)) :av
```

- **Training-memory mistake:** assuming `[?e :some/attr v]` always seeks AVET.
  The fast AVET point-lookup (`fast-ground-lookup`, `:2001`) fires only for
  **`:db/unique`** attrs; a non-unique `[?e a v]` goes through
  `lookup-batch-search` (`:2031`). For hot value-equality lookups on a
  high-cardinality attr, that's a scan — prefer joining through an identity attr,
  or accept the batch path. (Perf note, not a correctness one.)

### LMDB durable store (`datahike-lmdb/`, `konserve-lmdb/`)

The pod never embeds these — `wire-server` (JVM) is the sole writer and owns the
LMDB-backed konserve store at `data/clusters/default/store`
(`datahike-lmdb/src/datahike_lmdb/storage.clj`, `konserve-lmdb/src/konserve_lmdb/store.clj`).
The pod forwards raw tx-data (the `:db.fn/cas` op included as pure data) over the
UDS; the CAS therefore executes at the single total-ordered writer, which is what
makes the fence sound across the wire (library-grounding "wire boundary" §). No
seon code should reach into these namespaces — `seon.db` is the only API.

## malli — what instrument actually wraps + how schemas validate

Read: `reference-code/malli/src/malli/instrument.cljs`, `…/core.cljc`,
`…/malli-datomic/…/datomic_schema_gen.cljc`.

### Instrument wraps the fn object per arity (`instrument.cljs:62-93`)

- `-replace-fn` (`:77`) dispatches: **pure-variadic** → `-replace-variadic-fn`
  (`:41`), **multi-arity** (`-max-fixed-arity`) → `-replace-multi-arity` (`:62`,
  one `m/-instrument` per `cljs$core$IFn$_invoke$arity$N`), else a single
  `m/-instrument` (`:82`). The wrapper validates input+output+guard
  (`core.cljc:-instrument:3110`, default scope `#{:input :output :guard}`).
- **This is why our pure-variadic `transact!`/`query`/`pull` bodies matter:** a
  `[& args]` body compiles to the variadic arity so malli wraps EVERY call
  shape. `query`'s schema comment (`db.cljs:577-591`) spells out the
  `::duplicate-arities` hazard — overlapping positional arities can't be distinct
  `:=>`s, so they're folded into arity-1 (`[:or …]`) + a `[:+ :any]` varargs
  `:=>`. Imitate that encoding for any "datahike-shaped + map-in + db-omitted"
  surface.
- **`:catn` is a SEQUENCE (regex) schema, not a map** (`core.cljc:2995`). A
  `:=>` input is a `:cat`/`:catn` validated POSITIONALLY against the arg vector
  (`-=>-schema` asserts `(#{:cat :catn} (type input))`, `core.cljc:2154`).
  Training-memory mistake: writing `[:=> [::id :int] …]` (missing the
  `:cat`/`:catn` head). Our convention is `[:=> [:catn [::id :int]] …]` — named
  positional slots, each referencing a registered shape.

### How `register!`'s forms validate (`schema.cljc:44-48`, `core.cljc`)

`relink-registry!` (`schema.cljc:44`) points malli's process-global default
registry at `(composite-registry (m/default-schemas) (mutable-registry *schemas))`
— so a `::foo` reference resolves by registry lookup at validation time. Built-in
heads (`:string`/`:int`/`:inst`) live in the default-schemas half; seon shapes in
the atom half.

- **Training-memory mistake / live gotcha (already documented):** the self-host
  compiler can re-run malli.core's top-level `set-default-registry!`, stomping
  the composite back to default-only and severing every `:seon.*` schema
  (`schema.cljc:33-41`). `seon.eval`'s `:load` wrapper re-calls
  `relink-registry!` after every load. Don't "simplify" the defonce/relink dance.

### Our bridge vs. malli-datomic — the divergence (read both)

`malli-datomic` walks COMPILED schema objects:
`derive-value-type` does `(-> schema m/deref m/deref m/type)`
(`datomic_schema_gen.cljc:45-46`) and reads props via `select-keys` on
`spec-item-options` (`:160`), enum members via `(m/children enum-schema)` (`:131`).

Our bridge walks RAW FORMS by hand: `resolve-malli-form` chases keyword
indirections (`internal.cljs:167`), `form-head`/`form-children`/`form-properties`
(`internal.cljs:132-210`) destructure the vector literally.

- **Why ours is deliberate:** it runs on the registry's raw stored form
  (`schema/schema-definition`, a vector/keyword) WITHOUT needing `(m/schema …)`
  to compile — so it works during boot ordering and never throws on a
  forward-reference. It also special-cases `:seon.db/ref` (`:169`) which has no
  datahike value-type of its own.
- **The cost:** every malli form shape the bridge must support is hand-coded
  (`:enum`/`:and`/`:or`/container), and a shape malli would handle via its API
  (e.g. `:schema`/`:ref` wrappers, `:merge`, function schemas) is invisible to
  the walker. See SMELLS.
- **Divergence worth noting:** malli-datomic emits `:db/ident` ENTITIES for enums
  (`:128`); our bridge stores enums as a bare `:db.type/keyword`
  (`internal.cljs:222-228`) — correct for datahike, which has no enum/ident-entity
  convention like Datomic's.

## SMELLS I found in `seon.db` / `seon.db.internal` / `seon.schema`

Skeptic pass. Each: location, what looks wrong, what it should be, confidence.

### §1 — two different "is this a db value?" predicates across the read APIs — LOW

`db.cljs:214` registers `(schema/register! ::db-val 'map?)`, and the positional
3-arity of `pull` (`db.cljs:905`) and `entity` (`db.cljs:972`) declare their db
slot as `::db-val` (`'map?`). But `query`'s positional path detects an explicit
db with `internal/db-value?` (`db.cljs:604`), NOT `map?`. So the three read APIs
disagree on the definition of "a db value."

I chased the worst case — that the temporal wrappers aren't maps and would fail
`'map?` instrumentation — and it does NOT hold: **FilteredDB / HistoricalDB /
AsOfDB / SinceDB are `defrecord`s** (CLJS `defrecord-updatable` expands to
`(defrecord …)` + `extend-type`, `datahike/db.cljc:117-121`), so they implement
`IMap` and `(map? as-of-db)` is **true**. The `installed-schema` docstring's
"don't implement ILookup" line (`db.cljs:631`) is about the absent `:schema`
field, not the record's map-ness. So this is NOT an instrumentation bug.

- **What's left:** a shared-shape duplication smell. The canonical "a datahike db
  value" predicate is defined twice (`'map?` inlined at `::db-val`,
  `internal/db-value?` used in `query`). Per the shared-shape rule, lift
  `db-value?` into ONE registered `:seon.db/db-value` schema and reference it from
  `pull`/`entity`/`query`/`as-of`/`since` alike. `'map?` is also LOOSER than
  `db-value?` (any map passes), so the positional `pull`/`entity` db slot is
  under-constrained vs. `query`.
- **Confidence: LOW** (consistency/duplication, not a live bug). **Quick REPL
  confirm:** `(map? (seon.db/as-of (seon.db/basis-t)))` → expect `true`.

### §2 — `form-properties` grabs the first map child anywhere in `(rest form)` — LOW/MEDIUM

`internal.cljs:132-134`:
```clojure
(when (vector? form)
  (some (fn [x] (when (map? x) x)) (rest form)))
```
This returns the first MAP it finds among all non-head children, on the
assumption "there's at most one props map per schema form" (its docstring). For
malli's own shapes that's true (props, if present, are the immediate 2nd element).
But the walker also feeds it forms like `[:map [:k {:optional true} :int] …]` —
here `(rest form)` is `([:k {:optional true} :int] …)`, whose first element is a
VECTOR not a map, so `some` skips it and (correctly) finds no top-level props.
The risk is a future shape where a map appears as a legitimate CHILD (e.g. a
literal `[:enum {:a 1} …]`-style value, or `:map-of` with a map default) — the
walker would mis-read it as properties.

- **What it should be:** malli's contract is "properties are the element
  immediately after the type, iff it's a map." Check `(second form)` for
  map-ness, don't `some` across all children. Or use `m/properties` on a compiled
  schema (the malli-datomic approach) where the boot-ordering constraint allows.
- **Confidence: LOW/MEDIUM** — no current registered shape triggers it (verified
  the bridge handles all live shapes), but it's a latent foot-gun and a deviation
  from malli's actual properties-position rule.

### §3 — `:and` / `:or` bridge assumes the BASE is the first child — LOW

`form->datahike-value-type` `:and` branch (`internal.cljs:236-237`) bridges on
`(first (form-children …))`. For the canonical id shape
`[:and {:seon.db/identity true} :seon.db/id]` this is right (the props map is
stripped by `form-children`, leaving `:seon.db/id` first). But malli `:and`
order is author-defined; `[:and [:string {:min 14}] :seon.db/id]` would bridge on
the `:string` (fine, same type) — yet `[:and :some-predicate :seon.db/id]` would
bridge on whatever `:some-predicate` maps to. It happens to be safe today because
every seon `:and` puts a storable base first.

- **What it should be:** document the invariant ("the FIRST non-prop child of an
  `:and` is the storable base type") at the bridge, or pick the first child that
  successfully bridges (mirroring the `:or` single-type collapse at `:250-257`).
- **Confidence: LOW** — works for all live shapes; it's an undocumented
  assumption, not a bug.

### §4 — `validate-values!` and datahike `value-valid?` are redundant on TYPE but
not on CONSTRAINT — INFO (not a bug, a thing to know)

`internal.cljs:824` runs full malli `m/validate`/`m/explain` per value; datahike
then re-checks the coarse `:db.type/*` spec (`schema.cljc:205`). The malli pass is
the ONLY enforcement of `:min`/`:max`/`:enum`-membership/regex — datahike will
happily store a `:string` of length 3 where the malli says `[:string {:min 14}]`
if the seon gate is ever bypassed. Keep `validate-values!` on the write path; it
is not redundant despite datahike "also validating."

- **Confidence: INFO.** Flagging so nobody "optimizes away" the seon malli gate
  thinking datahike covers it. It does not.

### §5 — `::transact-response` success branch is `:any` for `::tx-report` — already
sanctioned, noting for completeness — INFO

`db.cljs:170-173` uses `:any` for the raw report (carries datahike db handles, a
third-party boundary — the documented exception). Correct per the no-`:any` rule's
boundary clause. Not a smell; called out so a future reviewer doesn't "fix" it.

## Idioms to imitate (grounded in the source)

- **Resolve-then-validate for refs** — `transact-add` resolves the eid before the
  datom exists (`transaction.cljc:701`); mirror "normalize input into the stored
  canonical form, THEN validate" (`coerce-identity-symbol-idents` /
  `normalize-entity-ref-keys`, `internal.cljs:1353-1358`).
- **`cond->` for optional datahike attr fields** — `malli->datahike-attr`
  (`internal.cljs:344-350`) starts from the required `{:db/ident :db/valueType
  :db/cardinality}` and conditionally `assoc`s identity/component. Never mutate.
- **Transduce a `(comp (filter…) (map…))` into a set/vector** —
  `retract-components` (`transaction.cljc:730`), `extract-tx-attrs`
  (`internal.cljs:466-490`).
- **Errors as `ex-info` with `:seon.error/kind`** — every bridge/gate throw
  carries `{:seon.error/kind :user-input}` so `transact!*`'s single catch
  (`internal.cljs:1381`) turns it into the `{::ok? false …}` value. Never throw a
  bare string; never `try` for control flow.
- **`is-attr?`-style "ask the installed schema"** — when you need to know if an
  attr is a ref/component/many, query the db's installed schema
  (`db/installed-schema`, `db.cljs:613`), don't infer from the value or a
  hardcoded set.

## Verify-in-REPL checklist (live proofs, not inference)

1. `(map? (seon.db/as-of (seon.db/basis-t)))` — expect `true` (defrecord);
   confirms SMELL §1 is a duplication smell, not an instrumentation hazard.
2. `(seon.db/cas-assert [:seon.agent/id "x"] :seon.agent/run [:seon.agent.run/id "r"])`
   → `[:db.fn/cas [:seon.agent/id "x"] :seon.agent/run [:seon.agent.run/id "r"]
   [:seon.agent.run/id "r"]]` (pure data, OV==NV).
3. Register a `[:string {:min 14}]` attr, `transact!` a 3-char value → expect the
   seon malli gate to reject it (proves §4: datahike alone would not).
4. `(seon.db/query '[:find ?e :where [?e :seon.fn/ns :seon.db]])` → expect the
   "Nothing found for entity id :seon.db" raise (proves the keyword-as-eid path,
   `utils.cljc:133`); the ref-join form returns rows.
