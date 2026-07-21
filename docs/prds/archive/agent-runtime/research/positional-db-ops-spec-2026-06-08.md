---
type: research
status: active
tags: [research, database, agent, schema]
---

# Positional forms for agent-facing core db ops — implementation spec (2026-06-08)

## TL;DR

Each agent-facing core db op (`seon.db/query`, `transact!`, `pull`,
`entity`; `listen!` recommended) keeps its existing **map-in/map-out**
arity (179 internal callers untouched) AND gains a **positional** arity
that mirrors datahike EXACTLY (per
`research/datahike-api-forms-2026-06-08.md`) and funnels into the SAME
implementation by translating positional args into the existing
`:seon.db/*` request map.

Verified live (JVM `orchestrator` + CLJS pod `default`):

- **`:catn` names each positional slot.** `m/explain` on a `:catn`
  schema reports the failing slot by NAME (`:path [:db]`), not just
  `index 0` — same identifiability as a map key. (Both runtimes.)
- **`:function` carries multiple arities in one schema.** One fn with a
  map arity AND a positional arity validates correctly under
  `m/-instrument` / `malli.instrument/instrument!`. (Both runtimes.)
- **Seon's pod instrument (`seon.instrument`) uses stock
  `malli.instrument/instrument!`**, which wraps via `m/-instrument` —
  so `:function`/`:catn` schemas instrument per-arity with named-slot
  errors, and the existing error envelope (`seon.error.instrument`)
  already renders them (its `:malli.core/invalid-input` branch runs
  `(m/explain input args)`, surfacing the named slot in
  `:seon.error.malli/path`).
- **Dispatch is unambiguous** for all four ops via "map-in iff the
  first arg is a map carrying the op's required request key." A
  datahike **db value is `map?`-true but reports `(keys db) => []`** and
  contains NONE of the `:seon.db/*` keys (verified live) — so a
  positional db value never masquerades as a request map. A **conn is
  `map?`-false** (IDeref, not a map), so transact!/listen! disambiguate
  on `map?` alone.

**The one caveat:** `transact!` (and any positional `^:async` write) is
currently **NOT instrumented** — `seon.instrument/collect-registrations`
deliberately SKIPS `^:async` fns (instrument! can't await a Promise
before output validation). So the positional `transact!` arity gets the
SAME input-shape guarding the map arity already has (the hand-rolled
`assert-invocation-shape!` + envelope), not Malli instrumentation. The
`:function` schema should still be authored (it's the discoverable
contract and documents both arities), it just won't be enforced by the
runtime instrument until the async-aware wrapper lands. Reads
(query/pull/entity) ARE instrumented, so their positional arities get
full Malli enforcement with named-slot errors.

---

## (A) Malli named-positional + instrumentation — verified

### A.1 `:catn` names slots; `m/explain` reports the named slot

```clojure
(def S [:catn [:db [:fn map?]] [:query [:vector :any]]])
(m/explain S [[:not-a-map] [:find ?e]])
;; => {:errors ({:path [:db], :in [0], :schema [:fn map?], :value [:not-a-map]})}

```

`:path [:db]` — the wrong position is identifiable BY NAME, exactly like
a map key. Verified identically on JVM and the CLJS pod.

### A.2 `:function` combines map + positional arities on ONE fn

```clojure
(def Fn
  [:function
   [:=> [:cat [:map [:db/query [:vector :any]]]] :any]              ; map arity (1 arg)
   [:=> [:catn [:db [:fn map?]] [:query [:vector :any]]] :any]])    ; positional arity (2 args)
(m/validate Fn (fn ([m] :map) ([db q] :pos)))   ; => true (both runtimes)

```

`m/-instrument {:schema Fn}` wraps a 2-arity fn and validates inputs
per-arity: a 1-arg call validates against the map arity, a 2-arg call
against the positional arity. A bad positional db slot throws
`:malli.core/invalid-input` whose payload carries `:input` (the `:catn`)
plus `:args`; `(m/explain input args)` yields `:path [:db]`. Verified live
both runtimes (pod via `m/-register-function-schema!` +
`mi/instrument!`).

### A.3 Seon instrumentation fit

`src/seon/instrument.cljc`:

- `collect!` (compile-time macro) scans CLJS analyzer `:malli/schema`
  metadata and emits `m/-register-function-schema!` calls.
- `install!` (runtime) calls `collect!` then stock
  `malli.instrument/instrument! {:report ei/report-fn}`. `instrument!`
  wraps each registered fn via `m/-instrument` — the EXACT machinery
  validated in A.2. A `:function`/`:catn` schema on a multi-arity fn
  instruments correctly: inputs validated per arity, named-slot errors.
- The error reporter `seon.error.instrument/report-fn` →
  `explain-payload` handles `:malli.core/invalid-input` by running
  `(m/explain input args)` over the offending arity's `:cat`/`:catn`
  schema. For a `:catn` arity this puts the NAMED slot in
  `:seon.error.malli/path` and `:seon.error.malli/explain-path`, and
  `render-malli-error` prints it in the `at <path>` column. **No
  envelope change needed — named-positional errors render today.**

**THE CAVEAT — `^:async` fns are skipped.** `collect-registrations`
(instrument.cljc L52-62) filters `(and schema (not async?))`. `transact!`
is `^:async`, so it is **not in the instrument registry at all** — its
map arity is unenforced by Malli today; the positional arity will be the
same. `query`/`pull`/`entity` are sync and ARE instrumented, so their
positional arities get full named-slot enforcement. Pod and JVM
instrument the same way (stock `malli.instrument`), but only the pod
actually runs these ops; the `.clj` siblings are a separate lane.

### A.4 Dispatch — map-in vs positional, per op (live-verified)

A datahike **db value**: `(map? db) => true`, `(record? db) => true`,
BUT `(keys db) => []` and `(contains? db :seon.db/query) => false` (and
likewise for every `:seon.db/*` request key). A datahike **conn**:
`(map? conn) => false`, `(satisfies? IDeref conn) => true`.

Dispatch rule (single, uniform): **map-in iff the first arg is a map
carrying the op's required request key; otherwise positional.**

```clojure
(defn- request-map? [arg req-key] (and (map? arg) (contains? arg req-key)))

```

- `query` req-key `::query`, `pull` `::pull-pattern`, `entity` `::ref`,
  `transact!` `::tx-data`, `listen!` `::handler`.
- For reads, the positional first arg is a db value — `map?`-true but
  lacks the req-key → routes positional. Unambiguous.
- For `transact!`/`listen!`, the positional first arg is a conn —
  `map?`-false → routes positional. Also unambiguous, and even more
  robust (no key collision possible).
- **The transact! ambiguity the task flagged** is real but RESOLVED by
  the req-key test: map-in is `{::db/tx-data [...]}`; positional is
  `(transact! conn tx-data)` where `tx-data` is a vector and `conn` is
  not a map. The arg in *request position* (a `::tx-data`-bearing map)
  can never be confused with `tx-data` itself (a bare vector) NOR with a
  conn (not a map). Even if an agent passed a literal `{:tx-data [...]}`
  datahike-style map as positional arg-1, it lacks the NAMESPACED
  `:seon.db/tx-data` key, so it's still treated as a conn-position arg
  (which would then fail conn resolution with a clear error) — it never
  silently mis-routes to the map path.

---

## (B) Per-op spec

Conventions used below:

- `Sdb` = `[:fn map?]` (a datahike db value; record satisfying map),
  `Sconn` = `:any` (matches `::conn`; conns are opaque IDeref).
  Reuse the registered `::conn` schema where a conn appears.
- Positional arities are authored as `:catn` so every slot is named.
- The combining schema is `:function` with the map `:=>` FIRST
  (1 arg) and the positional `:=>` after (≥2 args). Arity count alone
  selects the arity at instrument time; the `request-map?` guard selects
  the code path at call time. (They agree by construction: the only
  1-arg call is map-in.)
- Every positional arity translates into the EXISTING `:seon.db/*`
  request map and calls the existing body — preserving every envelope
  contract (`transact!` returns `{::db/ok? …}`; `listen!` hands the
  enriched handler-input map; reads return raw datahike values).

### B.1 `entity` (simplest — implement FIRST)

Map arity (today): `(entity {::db/ref eid (::db/db | ::db/conn)})`
Positional (datahike `(d/entity db eid)`, DB-first):
`(entity db eid)`

```clojure
(schema/register! ::db-val [:fn map?])   ; datahike db value (record/map)
;; :function schema:
[:function
 [:=> [:cat ::entity-request] :any]                       ; map arity
 [:=> [:catn [::db ::db-val] [::eid :any]] :any]]          ; positional, DB-first

```

Dispatch: `request-map? arg ::ref`. Translation:
`(entity db eid) ==> {::db/db db ::db/ref eid}` → existing body.

Acceptance:

- `(entity db 1)` returns the SAME entity as
  `(entity {::db/db db ::db/ref 1})`.
- `(entity :not-a-db 1)` (wrong slot-0 type) throws
  `:malli.core/invalid-input` with `:seon.error.malli/path [:db]`
  (named slot), rendered `at [:db]`.

### B.2 `pull` (implement SECOND)

Map arity: `(pull {::db/pull-pattern selector ::db/ref eid (::db/db|::db/conn)})`
Positional (datahike `(d/pull db selector eid)`, DB-first):
`(pull db selector eid)`

```clojure
[:function
 [:=> [:cat ::pull-request] [:maybe :map]]
 [:=> [:catn [::db ::db-val] [::selector [:vector :any]] [::eid :any]] [:maybe :map]]]

```

Dispatch: `request-map? arg ::pull-pattern`. Translation:
`(pull db selector eid) ==> {::db/db db ::db/pull-pattern selector ::db/ref eid}`.

Note seon names the selector `::pull-pattern` and the eid `::ref`; the
positional slots are named `:selector`/`:eid` for datahike-fidelity in
error messages — the translation maps them onto the existing keys.

Acceptance:

- `(pull db [:db/id ::name] 1)` == `(pull {::db/db db
  ::db/pull-pattern [:db/id ::name] ::db/ref 1})`.
- `(pull db :not-a-vector 1)` → `invalid-input` at `:selector`.

(Optional sibling, not currently in seon.db: `pull-many` →
`(pull-many db selector eids) ==> {::db/db db ::db/pull-pattern selector
::db/refs eids}`. Out of scope unless a `pull-many` wrapper is added.)

### B.3 `query` (implement THIRD)

Map arity: `(query {::db/query q [::db/args [...]] (::db/db|::db/conn)})`
Positional (datahike `(d/q query db & inputs)` — query FIRST, db is the
first `:in` input):
`(query q db & inputs)`

```clojure
[:function
 [:=> [:cat ::query-request] :any]
 [:=> [:catn [::query [:or [:vector :any] :map :string]]
             [::db ::db-val]
             [::inputs [:* :any]]] :any]]   ; variadic inputs after db

```

Dispatch: `request-map? arg ::query` — BUT note query's positional
arg-0 is the QUERY (a vector/map/string), not the db. A query vector is
NOT `map?`; a query MAP (`'{:find … :where …}`) IS `map?` but lacks
`::db/query`. So `request-map? arg ::query` still routes a positional
query-map correctly to the positional path. Unambiguous.

Translation (seon prepends the db itself; `::db/args` is the inputs
AFTER `$`): `(query q db & inputs) ==> {::db/query q ::db/db db
::db/args (vec inputs)}` → existing body does `(apply d/q q db args)`.

Acceptance:

- `(query '[:find ?n :where [?e ::name ?n]] db)` ==
  `(query {::db/query '[…] ::db/db db})` (db binds `$`).
- `(query '[:find ?n :in $ ?t :where …] db "Alice")` ==
  `(query {::db/query '[…] ::db/db db ::db/args ["Alice"]})`.
- A positional query-map `'{:find …}` routes positional (not mistaken
  for a request map).
- Wrong slot-1 type (db not a map) → `invalid-input` at `:db`.

Caveat: a variadic positional arity is `[:catn … [::inputs [:* :any]]]`.
`m/-instrument` validates variadic `:catn` arities; confirmed `:catn`
explains by name. The 2-arity (`query`+`db`, no inputs) is covered by
the same `[:* :any]` (zero inputs).

### B.4 `transact!` (implement LAST — the async caveat applies)

Map arity (today, `^:async`):
`(transact! {::db/tx-data [...] [::db/opts {:tx-meta …}] [::db/conn c]})`
→ envelope `{::db/ok? true ::db/tx-report …}` | `{::db/ok? false ::db/error …}`.

Positional (datahike `(d/transact! conn tx-data)`; tx-meta only via the
arg-map, NO 3-arity in datahike). Seon SHOULD expose a 3-arity
convenience for tx-meta since seon already nests it under `::db/opts`:

```clojure
;; conn-first; tx-data is a bare vector; optional tx-meta map
(transact! conn tx-data)            ; 2-arity
(transact! conn tx-data tx-meta)    ; 3-arity (seon convenience for tx-meta)

```

```clojure
[:function
 [:=> [:cat ::transact-request] ::transact-response]                       ; map arity
 [:=> [:catn [::conn ::conn] [::tx-data ::tx-data]] ::transact-response]    ; 2-arity positional
 [:=> [:catn [::conn ::conn] [::tx-data ::tx-data] [::tx-meta :map]]
      ::transact-response]]                                                ; 3-arity positional

```

Dispatch: `request-map? arg ::tx-data`. A conn is `map?`-false → 2/3
positional. Translation:

```
(transact! conn tx-data)         ==> {::db/conn conn ::db/tx-data tx-data}
(transact! conn tx-data tx-meta) ==> {::db/conn conn ::db/tx-data tx-data
                                      ::db/opts {:tx-meta tx-meta}}

```

→ existing `^:async` body, returns the SAME envelope (positional callers
still `await` and read `::db/ok?`/`::db/tx-report`/`::db/error`).

**THE CAVEAT (repeat):** because `transact!` is `^:async`,
`seon.instrument` skips it — the `:function` schema is authored as the
discoverable contract but NOT runtime-enforced by Malli. The existing
hand-rolled `assert-invocation-shape!` + envelope already guards the map
arity; the positional arities must extend that same guard (detect
`map?`-false arg-0 = conn; build the request map; reject e.g. a non-map
tx-data). When the async-aware instrument wrapper lands (the deferred
cleanup noted in instrument.cljc L44-51), the `:function` schema starts
enforcing automatically with no further change.

Acceptance:

- `(await (transact! conn [{::name "A"}]))` returns an envelope with
  `::db/ok? true` and a tx-report equal (modulo tx id) to
  `(await (transact! {::db/conn conn ::db/tx-data [{::name "A"}]}))`.
- `(await (transact! conn [{::name "A"}] {:source :import}))` attaches
  `:tx-meta {:source :import}` (visible in the returned tx-report's
  `:tx-meta`).
- Wrong-shape positional (e.g. tx-data a map not a vector) returns a
  `{::db/ok? false ::db/error …}` envelope tagged `:user-input` — does
  NOT throw, does NOT crash the eval loop.
- Positional NEVER throws into the agent (envelope contract preserved).

### B.5 `listen!` — RECOMMEND: YES (positional form)

Map arity: `(listen! {::db/handler f [::db/key k] [::db/conn c]})`
→ `{::db/key k}`; handler receives the ENRICHED map (handler-input),
not the raw datahike tx-report.

Positional (datahike `(d/listen conn key callback)` and the 2-arity
auto-keyed `(d/listen conn callback)`):

```clojure
(listen! conn handler)        ; 2-arity, auto-key
(listen! conn key handler)    ; 3-arity, explicit key

```

```clojure
[:function
 [:=> [:cat ::listen-request] ::listen-response]
 [:=> [:catn [::conn ::conn] [::handler [:fn fn?]]] ::listen-response]
 [:=> [:catn [::conn ::conn] [::key :any] [::handler [:fn fn?]]] ::listen-response]]

```

Dispatch: `request-map? arg ::handler`. A conn is `map?`-false →
positional. Translation:

```
(listen! conn handler)     ==> {::db/conn conn ::db/handler handler}
(listen! conn key handler) ==> {::db/conn conn ::db/key key ::db/handler handler}

```

→ existing body; the handler STILL receives the enriched handler-input
map (`::db/tx-report`, `::db/db`, `::db/db-before`, `::db/datoms`,
`::db/attr-index`) — the positional form keeps that contract identical.
Recommend ALSO adding the positional `unlisten!`:
`(unlisten! conn key) ==> {::db/conn conn ::db/key key}`.

Recommendation rationale: `listen!` is a primitive agents reach for
directly (ad-hoc reactions, debug taps), its datahike form is muscle
memory, and the conn-first/`map?`-false dispatch is trivially
unambiguous. Cost is low; include it. Note: `listen!` is sync (NOT
`^:async`) per its `::listen-response` schema, so it IS instrumented —
positional `listen!` gets full named-slot enforcement (unlike
`transact!`).

---

## Implementation order + per-chunk tests

One op per chunk, simplest first. Each chunk: author the `:function`
schema in place (replacing the current single-arity `:malli/schema`),
add the `request-map?` dispatch + positional translation in the fn body,
then the targeted test.

1. **`entity`** (chunk 1) — 1 positional arity, no variadics, sync
   (instrumented). Test: equality vs map-in; wrong-slot named error.
   `(user/run-tests 'seon.db-test)` (or the db-ops positional test ns).
2. **`pull`** (chunk 2) — fixed 3-slot positional, sync. Test: equality;
   `:selector` named error.
3. **`query`** (chunk 3) — variadic positional (`[:* :any]` inputs),
   query-first/db-as-input translation, sync. Test: `$`-binding
   equality; extra-input binding; positional query-map routes positional;
   `:db` named error.
4. **`transact!`** (chunk 4) — `^:async`, 2+3 positional arities,
   envelope-preserving, NOT Malli-instrumented (extend
   `assert-invocation-shape!`). Test: envelope equality; tx-meta via
   3-arity; bad-shape → `::db/ok? false` (no throw); never throws.
   `(user/run-tests …)` async via the pod's await test helper.
5. **`listen!` (+ `unlisten!`)** (chunk 5, recommended) — 2+3 positional,
   sync (instrumented), enriched-handler contract preserved. Test:
   handler fires with enriched map for positional registration; explicit
   key round-trips; `unlisten!` positional removes it.

Run the full pod test suite ONCE at the end (per memory: targeted per
chunk, full suite once). Scope is strictly these 4-5 agent-facing fns in
`src/seon/db.cljs` + their request schemas — **the 179 internal map-in
call sites are untouched** (they keep passing a single request map, which
routes through the map arity unchanged).

---

## Shared additions

- Register `::db-val [:fn map?]` once (a datahike db value: a record
  satisfying the map protocol) and reference it in every read op's
  positional `:catn` `:db` slot — don't inline `[:fn map?]` per op
  (shared-shape rule).
- Reuse the existing `::conn` schema for every positional `:conn` slot.
- Keep the map-arity request schemas (`::query-request`, etc.) EXACTLY
  as-is; the `:function` schema's first `:=>` references them unchanged.

## Source citations

- Datahike forms: `research/datahike-api-forms-2026-06-08.md`
  (translation table, DB-first/conn-first, tx-meta-in-map, CLJS-Promise).
- Seon wrappers: `src/seon/db.cljs` — `query` L1234, `pull` L1248,
  `entity` L1256, `transact!` L929, `listen!` L1339, request schemas
  L229-342, envelope contract L236-279, handler-input builder L1297.
- Instrument: `src/seon/instrument.cljc` (`collect-registrations`
  async-skip L52-62; `install!` → stock `mi/instrument!` L99-128).
- Error envelope: `src/seon/error/instrument.cljc`
  (`explain-payload` invalid-input branch L184-234 → named-slot path;
  `render-malli-error` `at <path>` column L298-304).
- Live probes (2026-06-08): JVM `orchestrator` + CLJS pod `default` —
  `:catn` named-slot explain; `:function` 2-arity instrument both
  runtimes; datahike db value `map?`-true / `(keys)=>[]` / no
  `:seon.db/*` keys; conn `map?`-false / IDeref.
