---
type: research
status: active
tags: [research, agent, database, flow]
---

# Reactive-engine patterns mined from reference-code (2026-06-03)

Library evidence (posh, datahike, malli, spectomic, malli-datomic, hyperlith,
datastar-clojure) for the five weaknesses found in `seon.server.reactive`. Each
section: (a) what the library actually does, with `reference-code/<lib>/path:line`
anchors + verbatim excerpts; (b) the recommended Seon pattern; (c) the simpler
alternative that lets us delete code. Ends with numbered platform proposals.

## TL;DR

- **W1 (db-name keying):** datahike already has a canonical conn identity —
  `conn-id = [(store-identity store-config) branch]` where `store-identity` is
  the konserve `:id` UUID (`datahike/src/datahike/store.cljc:40`,
  `connector.cljc:275`). Listeners are keyed by an arbitrary opaque key, NOT by
  db-name (`datahike/src/datahike/core.cljc:206`). The right fix is NOT to invent
  a string/keyword convention but to **key the engine-state registry on the
  keyword db-name the on-ensure-db hook already hands us** (matching
  `seon.server.store/db-name` = `:keyword` and `registry/db-name` = `:keyword`),
  and to **normalize string↔keyword at exactly ONE boundary** — the broadcast
  routing key. Today `broadcast!` routes on the string `(get event "db-name")`
  while the registry keys on a keyword: a mismatch returns nil and silently drops.
  Make `broadcast/subscribe!` and `broadcast!` agree on one type; do the
  stringify at the wire-codec edge only.
- **W2 (wire-envelope ownership):** posh proves the cleanest separation we want.
  `p/after-transact` is PURE — it returns `{:changed <map>}` of moved results and
  touches no transport. The transport (resetting reagent ratoms) lives in a
  SEPARATE `doseq` inside the `:posh-listener` callback
  (`posh/src/posh/plugin_base.cljc:38-44`). Hyperlith is identical: `render-fn`
  returns a value, the handler hashes+diffs+SSE-sends
  (`hyperlith/src/hyperlith/impl/datastar.clj:170-191`). Keep `on-tx!` emitting
  the REGISTERED data map via the injected `emit!`; the CBOR/Transit envelope is
  built ONLY in platform's broadcast/wire layer. The engine never names a wire
  string literal. This is already how the engine is shaped — the proposal is to
  make it a contract.
- **W3 (rows shape):** posh NEVER unions query-results and pull-results into one
  schema. `q-analyze` stores `:results` (find-spec relation) and `pull-analyze`
  stores `:results` (a map / vec-of-maps) as two different artifacts keyed by
  storage-key type (`:q` vs `:pull`) — `q_analyze.cljc:534`, `pull_analyze.cljc:171`.
  Recommendation: type the engine output by find-spec via a **registered
  multi-schema** (`:multi` dispatching on `:seon.server.reactive/find-spec`), with
  `relation`/`scalar`/`collection`/`pull` variants. `rows` (relation) stays as-is;
  pull gets its own `[:vector [:map-of :keyword <leaf>]]`-style variant. No `:any`.
- **W4 (schema derivation):** our bridge correctly rejects nested collections — so
  do spectomic ("Cannot create schema for a collection of collections,"
  `spectomic/.../core.clj:103`) and datahike's flat-datom model. But
  **malli-datomic does two things we don't** (`malli-datomic/.../datomic_schema_gen.cljc`):
  it maps a homogeneous fixed seq → `:db.type/tuple` (cardinality-one, line 184)
  and it copies `:db/tupleType`/`:db/tupleTypes`/`:db/tupleAttrs`/`:db/isComponent`/
  `:db/doc` straight through from malli properties (`datomic-copied-props`, line 88).
  datahike accepts `:db.type/tuple` + `:db/tupleType`/`:db/tupleTypes`
  (`datahike/src/datahike/schema.cljc:51,65,73`). This is the path to store a small
  fixed result row as a REAL datom if we ever want to. Recommend adding tuple +
  prop-passthrough to OUR bridge (in place, not a v2).
- **W5 (ordering + nil trap):** hyperlith compares a **hash of the serialized
  view** (`Integer/toHexString (hash new-view-str)`,
  `hyperlith/.../datastar.clj:183`), not raw structure — order-insensitive in
  practice because it diffs the rendered string, and posh compares cached values
  with `not=` on SETS (it never `vec`s the result set:
  `q_analyze.cljc:534` returns a set). Stop calling `(vec result)` on a relation;
  keep it a set across the diff and sort deterministically only at the
  emit/serialize boundary. For the nil-engine-state trap, mirror datahike's
  `listen!` precondition style — make `engine-state` a "must-exist" lookup that
  throws a typed `ex-info` (registry has the `not-found` envelope idiom already:
  `registry.clj:372`).

---

## W1 — db-name keying type mismatch (silent-drop risk)

### (a) What the libraries do

**datahike has ONE canonical conn identity, and it is not a name string.**
`store-identity` returns the konserve store UUID:

```clojure
;; datahike/src/datahike/store.cljc:40
(defn store-identity
  "Returns the UUID that identifies the store.
   All konserve stores require an :id field containing a UUID.
   This is the stable identifier used for connection tracking,
   distributed coordination, and store matching."
  [config]
  (:id config))
```

The global connection registry and `release` key on a VECTOR of
`[store-id branch]`, never a name:

```clojure
;; datahike/src/datahike/connector.cljc:275
conn-id [(ds/store-identity (get-in db [:config :store]))
         (get-in db [:config :branch])]
```

And the conn's print form is that same pair (`connector.cljc:64`):
`#datahike/Connection [(store-identity (:store config)) (:branch config)]`.

**Listeners are keyed by an arbitrary opaque key — independent of db-name.**

```clojure
;; datahike/src/datahike/core.cljc:206
(defn listen!
  "...`key` is any opaque unique value. Idempotent..."
  ([conn callback] (listen! conn (rand) callback))
  ([conn key callback]
   {:pre [(conn? conn) (atom? (:listeners (meta conn)))]}
   (swap! (:listeners (meta conn)) assoc key callback)
   key))
```

So datahike never forces a db-name string/keyword convention on us. The keys we
use (`::raw-broadcast`, `::reactive`) are per-conn listener slots, not identities.

**Crucially, Seon already derives store-id deterministically from the db-name
keyword.** `seon.server.store/name->uuid` (`store.clj:110-115`) does exactly what
datahike expects: `(UUID/nameUUIDFromBytes (str db-name))` becomes the konserve
`:id`. So the keyword db-name IS the stable name, and the store-id UUID IS the
stable identity, and they are 1:1.

**Where the type mismatch actually lives** (confirmed in live code):

- `seon.server.store/db-name` → `:keyword` (`store.clj:46`)
- `seon.server.registry/db-name` → references `::store/db-name` = `:keyword`
  (`registry.clj:51`)
- `ensure-db!` hands the on-ensure-db hook a **keyword** db-name
  (`registry.clj:263` → `run-on-ensure-db-hooks!` → `(f conn db-name)` where
  db-name is the keyword from the request map)
- `seon.server.reactive/db-name` (wire event field) → `:string` (`reactive.clj:33`)
- `broadcast!` routes per-DB subscribers on the **string** `(get event "db-name")`
  (`broadcast.clj:84`); `subscribe!` keys subscribers by whatever db-name caller
  passes (`broadcast.clj:38-46`)
- the raw-broadcast listener stringifies before broadcasting
  (`wire.clj:305`: `(if (keyword? db-name) (subs (str db-name) 1) ...)`)

So the path is: keyword (registry) → keyword (hook → engine) → engine emits
`:seon.server.reactive/db-name` STRING → must reach `subscribe!`/`broadcast!`
which route by STRING. If the engine-state registry keys by keyword but the
broadcast subscription is registered under a string (or vice-versa), the lookup
returns nil and the event/op is **dropped with no error**.

### (b) Recommended pattern for Seon

1. **Engine-state registry keys on the keyword db-name.** This matches `store`
   and `registry`. Add to `reactive.clj` a `defonce` atom
   `{db-name(keyword) → engine-state-atom}` and an accessor
   `(engine-state db-name)`. The on-ensure-db hook (which receives the keyword)
   creates `(new-engine-state db-name)`, stores it under the keyword, and installs
   the `::reactive` listener. Platform's op-handlers look up
   `(reactive/engine-state db-name)` with the same keyword they already resolve
   the conn with via `registry/resolve-conn`.

2. **Normalize string↔keyword at exactly ONE boundary: the broadcast routing
   key.** Pick keyword as the in-process canonical (it is what registry/store
   use). `broadcast/subscribe!` and the per-DB routing in `broadcast!` should key
   by keyword. The ONLY place a string db-name should exist is inside the CBOR
   wire event (`"db-name"` is a wire field, and the Rust host demuxes on it). So:
   keep `wire.clj`'s stringify for the OUTBOUND socket frame, but have `broadcast!`
   route in-process subscribers by re-keywordizing (or, better, carry the keyword
   db-name alongside the event for in-process routing and only stringify for the
   socket frame).

3. **Make the engine carry the keyword, emit the string.** `on-tx!` already
   receives `db-name` in its ctx. Have the hook pass the **keyword**; the engine
   stringifies into `:seon.server.reactive/db-name` only at the emit point (it
   already does `(subs ...)`-free string today — change the SCHEMA to accept the
   keyword internally and stringify in the wire layer, OR keep the string in the
   event but register the in-process routing on the keyword). The contract: the
   data-boundary event carries the string (wire-portable); in-process routing
   uses the keyword.

### (c) Simpler alternative (delete code)

The cleanest dedup: **register the db-name shape ONCE and reference it
everywhere** (the project's "register once" rule). Today there are three
registrations: `store/::db-name` (`:keyword`), `registry/::db-name` (references
store), and `reactive/db-name` (`:string`). Collapse to:

- `:seon.server.store/db-name` = `:keyword` — the canonical in-process identity.
- The wire event uses a SEPARATE, explicitly-named `:seon.wire/db-name` = `:string`
  with a documented "this is the stringified store name for socket demux only"
  contract, and a single `db-name->wire`/`wire->db-name` pair (the only place the
  `(subs (str kw) 1)` ↔ `(keyword s)` coercion lives).

This deletes the three ad-hoc stringify sites (`wire.clj:240`, `wire.clj:305`,
`reactive.clj`'s implicit assumption) in favor of one coercion pair, and the
silent-drop class of bug becomes impossible because in-process routing is
keyword-only and the string only exists past the wire boundary.

---

## W2 — wire-envelope ownership / transport-agnostic emission

### (a) What the libraries do

**Posh is the canonical clean split.** `p/after-transact` is PURE routing — it
computes the changed cache and returns it as data, touching NO transport:

```clojure
;; posh/src/posh/core.cljc:231 (inside after-transact)
really-changed (reduce-kv (fn [m k v]
                            (if (not= v (get cache k))
                              (assoc m k v)
                              m))
                          {} changed-cache)
;; ... returns:
(merge new-posh-tree
       {:cache (merge cache really-changed)
        :changed really-changed})
```

The transport/sink is a SEPARATE step, in the listener callback, never inside
`after-transact`:

```clojure
;; posh/src/posh/plugin_base.cljc:38-44
((:listen! dcfg) conn :posh-listener
  (fn [tx-report]
    (let [{:keys [ratoms changed]}
          (swap! posh-atom p/after-transact {conn tx-report})]
      (doseq [[k v] changed]
        (reset! (get ratoms k) (:results v))))))   ; <- sink, separate from routing
```

`(:listen! dcfg)` is itself injected via the `dcfg` map — posh never names a
concrete datascript/datomic transact or listen fn; the host supplies it. That is
exactly our injected `emit!`.

**Hyperlith confirms the same shape server-side.** The render fn is pure
`(render-fn req)`; the handler hashes the rendered string, diffs against the last
hash, and only then compresses + SSE-sends:

```clojure
;; hyperlith/src/hyperlith/impl/datastar.clj:178-190
(when-some [new-view (er/try-on-error (render-fn req))]
  (let [new-view-str  (h/html->str new-view)
        new-view-hash (Integer/toHexString (hash new-view-str))]
    (when (not= last-view-hash new-view-hash)            ; diff gate
      (->> (patch-elements new-view-hash new-view-str)   ; build transport frame
        (br/compress-stream out br)
        (send! ch)))                                     ; transport
    new-view-hash))
```

Render → diff → frame-build → send are four separate, layered steps. The render
fn knows nothing about SSE, brotli, or framing.

### (b) Recommended pattern for Seon

Keep `on-tx!`'s injected `emit!` callback (the design is already right). Make the
contract explicit and load-bearing:

- **The engine emits ONE thing: the registered
  `:seon.server.reactive/changed-summaries-event` data map** (namespaced keywords,
  no wire strings). It is pure Clojure data.
- **The wire envelope (`{"event" ... "db-name" ... "payload" (transit ...)}`) is
  built ONLY in the platform broadcast/wire layer**, by the same code that builds
  the `tx` event (`wire.clj:273` `ok-event-from-report` + `wire.clj:112` `T`).
  Add a sibling `changed-summaries-event->wire` next to `ok-event-from-report`,
  living in `wire.clj`, that maps the registered data map into the CBOR envelope
  using the wire string literals. The engine never sees `"event"`/`"db-name"`/
  `"payload"`.
- **Wiring:** the on-ensure-db hook that installs the `::reactive` listener
  supplies `emit!` as `(fn [data-map] (bcast/broadcast! (wire/changed-summaries-event->wire data-map)))`.
  That single closure is the ONLY junction where the pure data map meets the wire
  literals, and it lives on the platform side.

This mirrors posh exactly: `after-transact` (pure, our `on-tx!`) returns/emits
data; the listener closure (platform side) maps it to transport.

### (c) Simpler alternative (delete code)

If platform agrees, **the engine's `emit!` can target the SAME `broadcast!`
fanout the `tx` event already uses** — no new transport. The reactive event is
just another `"event"`-tagged frame on the existing pub socket (the Rust host
demuxes by `"db-name"` just as it does for `tx`). That means:

- No new socket, no new pub server — reuse `bcast/start-pub-server!` /
  `broadcast!`.
- One `*-event->wire` builder per event type in `wire.clj`, all sharing `T`/the
  envelope shape.

The engine shrinks to "produce the registered data map"; everything wire-shaped
is deleted from `reactive.clj` (it has none today — the proposal LOCKS that in so
a future agent doesn't add it).

---

## W3 — `rows` shape only models `:find ?a ?b` relation queries

### (a) What the libraries do

**Posh keeps query-results and pull-results as TWO separate artifacts — it never
unions them into one schema.** `q-analyze` produces `:results` for the relation
find-spec:

```clojure
;; posh/src/posh/lib/q_analyze.cljc:534
(when (some #{:results} retrieve)
  {:results
   ((:q dcfg) {:find (vec (:find qm))
               :in [[vars '...]]}
    (vec r))})
```

`pull-analyze` produces its own `:results`, which is a MAP (or vec of maps for
pull-many), stored under a different storage-key (`[:pull ...]` vs `[:q ...]`):

```clojure
;; posh/src/posh/lib/pull_analyze.cljc:166-172
(defn pull-analyze [dcfg retrieve {:keys [db db-id schema]} pull-pattern ent-id]
  (when (and ent-id (seq retrieve))
    (let [affected-datoms
          (pull-affected-datoms (:pull dcfg) db pull-pattern ((:entid dcfg) db ent-id))]
      (merge
       (when (some #{:results} retrieve)
         {:results affected-datoms})   ; <- a pull map, NOT a tuple set
       ...))))
```

The dispatch is on the storage-key shape (`core.cljc:106` `add-pull` →
`[:pull ...]`; `core.cljc:136` `add-q` → `[:q ...]`). Posh's design answer to W3
is: **the result shape is a function of the query KIND, and you keep the kinds
apart rather than typing a heterogeneous union.**

**Datahike returns shapes by find-spec exactly as the weakness states.** Relation
`:find ?a ?b` → set of tuples; pull `:find (pull ?e sel)` → set of maps. There is
no single datahike "query result" type — the find-spec determines it. (Posh even
notes the absence of `:db.type/tuple` support in datascript as the reason it must
scan where-clauses, `q_analyze.cljc:406-411` — datahike DOES have tuples, see W4.)

**Malli offers `:multi` for exactly this bounded-union case.** A `:multi` schema
dispatches on a discriminator key to one of N concrete variants — no `:any`, no
`[:maybe X]`. This is the idiomatic malli way to type "heterogeneous but bounded
by a tag."

### (b) Recommended pattern for Seon

Type the result BY find-spec, using a registered `:multi` dispatching on a
`:seon.server.reactive/find-spec` enum the engine derives once from the query:

```clojure
(schema/register! :seon.server.reactive/find-spec
                  [:enum :relation :scalar :collection :pull :pull-many])

;; relation rows stay exactly as today (a set/vec of scalar tuples)
(schema/register! :seon.server.reactive/relation-rows
                  [:vector [:vector :seon.server.reactive/scalar]])
;; scalar: one value
(schema/register! :seon.server.reactive/scalar-row :seon.server.reactive/scalar)
;; collection: a vector of scalars
(schema/register! :seon.server.reactive/collection-rows
                  [:vector :seon.server.reactive/scalar])
;; pull: a map keyed by attr keyword -> scalar | ref-map | vector-of
(schema/register! :seon.server.reactive/pull-row
                  [:map-of :keyword :seon.server.reactive/pull-value])
;; pull-many: a vector of pull-rows
(schema/register! :seon.server.reactive/pull-rows
                  [:vector :seon.server.reactive/pull-row])

(schema/register! :seon.server.reactive/result
                  [:multi {:dispatch :seon.server.reactive/find-spec}
                   [:relation   [:map [:seon.server.reactive/find-spec [:= :relation]]
                                      [:seon.server.reactive/relation-rows :seon.server.reactive/relation-rows]]]
                   [:pull       [:map [:seon.server.reactive/find-spec [:= :pull]]
                                      [:seon.server.reactive/pull-row :seon.server.reactive/pull-row]]]
                   ;; ... scalar, collection, pull-many
                   ])
```

`:seon.server.reactive/pull-value` is the bounded leaf for pull values: scalar,
or a `{:db/id int}` ref-map, or a vector of either — still no `:any`. The engine
classifies the find-spec by parsing the query's `:find` once (it already parses
`:where` in `query->patterns`; add a `find-spec` classifier alongside).

**Because the render use-case almost always wants pull** (build a summary from an
entity), the pull variant is the PRIMARY one. The relation `rows` we have today is
the fallback/aggregate case.

### (c) Simpler alternative (delete code)

Two strong simplifications, pick per platform appetite:

1. **"Always pull-shaped" (the posh idiom applied):** Constrain subscriptions to
   pull queries — `:find (pull ?e sel)` — at the subscription contract. Then the
   result is ALWAYS a vector of maps, ONE schema
   (`:seon.server.reactive/pull-rows`), and the whole `:multi` disappears. This
   matches the render use-case (you render an entity), matches posh's pull
   reaction being the common path, and deletes the scalar/collection/relation
   variants entirely. Relation queries that don't pull are an edge case we can
   defer (the user's "add constraints to simplify, relax later" preference).

2. **Defer pull, keep relation only (status quo + classifier guard):** Keep the
   current `rows` relation schema but add the `find-spec` classifier and REJECT
   non-relation subscriptions at register-time with a clear error, instead of
   letting a pull query produce maps that fail instrumentation deep in `on-tx!`.
   Smallest change; explicitly punts pull to a later milestone.

Recommendation: **option 1 (always-pull) is the right long-term shape** — it is
what the render function needs, it is posh's proven idiom, and it deletes the most
code. Option 2 is the safe stopgap if pull isn't needed at M3.

---

## W4 — schema derivation / nested-collection rejection

### (a) What the libraries do

**Our rejection of nested collections is correct and universal.** datahike's
datom model is flat; spectomic throws the identical rejection:

```clojure
;; spectomic/src/provisdom/spectomic/core.clj:101-104
(= ::cardinality-many (first collection-types))
(throw (ex-info "Cannot create schema for a collection of collections."
                {:spec spec}))
```

(Note spectomic is *generation-based* — it samples the spec generator and infers
types from the samples, `core.clj:58-109`. That is heavier and less predictable
than our static-form walk; do NOT adopt it. It also does NOT handle
`:db.type/tuple`.)

**datahike accepts `:db.type/tuple` with `:db/tupleType` / `:db/tupleTypes`:**

```clojure
;; datahike/src/datahike/schema.cljc:51 (in the valueType set)
:db.type/tuple
;; :65  schema-attribute set includes :db/tupleType :db/tupleTypes
;; :73  ::schema :opt includes :db/tupleType :db/tupleTypes
```

`:db/tupleType` = homogeneous fixed-length tuple; `:db/tupleTypes` = a vector of
per-slot types (heterogeneous fixed). This is precisely a "small fixed result row
as one datom."

**malli-datomic does two things our bridge does not** (`malli-datomic/src/blasterai/malli_datomic/datomic_schema_gen.cljc`):

1. Maps a homogeneous fixed seq to a TUPLE (cardinality-one), not cardinality-many:

```clojure
;; datomic_schema_gen.cljc:184-189
it-will-be-tuple? simple-seq-prop?
cardinality (or (:db/cardinality spec-item-options)
                (if ref-seq-prop? :db.cardinality/many)
                (if it-will-be-tuple? :db.cardinality/one)
                :db.cardinality/one)
```

with the documented example (`:113`):

```clojure
{:db/ident :order/comments :db/valueType :db.type/tuple
 :db/cardinality :db.cardinality/one :db/tupleType :db.type/string}
```

2. Copies tuple/component/doc properties straight from the malli entry props:

```clojure
;; datomic_schema_gen.cljc:88-101
(def datomic-copied-props
  [:db/doc :db/unique :db/isComponent :db/noHistory
   :db/tupleType :db/tupleTypes :db/tupleAttrs
   :db/ensure :db.entity/attrs :db.entity/preds])
;; ...
copied-attrs (select-keys spec-item-options datomic-copied-props)
```

It also maps `:map` / vector-of-maps → `:db.type/ref` (component refs,
`datomic_schema_gen.cljc:76-81`) — the case OUR bridge throws on
(`seon/db/datahike/schema.clj:210`).

### (b) Recommended pattern for Seon

Extend `seon.db.datahike.schema` IN PLACE (no v2):

1. **Add `:tuple` support.** A malli `[:tuple X Y Z]` →
   `{:db/valueType :db.type/tuple :db/cardinality :db.cardinality/one
     :db/tupleTypes [<dt-X> <dt-Y> <dt-Z>]}` (heterogeneous), or for a homogeneous
   `[:tuple X X X]` use `:db/tupleType <dt-X>`. This reuses the existing
   `malli-type->datahike-type` leaf map per slot. This is the case our bridge
   currently has NO branch for — it falls through to the "Unsupported Malli type"
   throw (`schema.clj:217`).

2. **Pass through `:seon.db/*` tuple/component props** the way malli-datomic does
   for datomic. Extend `seon-db-props->db-props` (`schema.clj:107`) so that
   `:seon.db/tuple-types`, `:seon.db/component`, `:seon.db/doc` on a malli entry
   become `:db/tupleTypes`, `:db/isComponent`, `:db/doc`. Today it only handles
   `:seon.db/identity`/`:seon.db/unique`. This is a one-time bridge fix per the
   project's "fix the bridge, don't inline" rule.

3. **Keep the nested-collection rejection** (`schema.clj:149`) — it is correct.
   `[:vector [:vector X]]` is genuinely not a datom shape. The tuple path is the
   sanctioned way to store a small FIXED row; an unbounded vector-of-vectors is
   not and should still throw.

For the reactive engine specifically: `install-reactive-schema!` derives the
`:seon.subscription/*` + `:seon.server.reactive/*` attrs via the bridge. Those
attrs (id string, query string, active? bool, basis-t int) are all flat scalars —
they pass the bridge today. The tuple work is only needed IF we decide (W3) to
persist result rows as datoms; with the always-pull / event-only approach the
rows never become datoms, so the tuple support is a "nice to have for later,"
NOT a M3 blocker. Flag it as an enhancement, not a dependency.

### (c) Simpler alternative (delete code)

If we never store result rows as datoms (rows ride the event only — see W2/W3),
then **W4 needs nothing beyond what the bridge already does** for the
subscription entity (flat scalars). The simpler alternative is therefore "do
nothing to the bridge for M3; the subscription datoms are all flat." Adopt the
tuple/prop-passthrough enhancement only when/if a feature genuinely needs a fixed
tuple datom. Document that the bridge is tuple-capable-on-demand so a future agent
adds the branch rather than reaching for a nested-collection workaround.

---

## W5 — set→vector ordering nondeterminism + engine-state nil trap

### (a) What the libraries do

**Posh compares result SETS with `not=`; it does not vec-then-compare a
relation.** `after-transact`'s change gate (`core.cljc:231`) is `(not= v (get
cache k))` over the cached `:results`, and `q-analyze`'s `:results` is built by a
datalog query (`q_analyze.cljc:534`), i.e. a set. Set equality is
order-independent, so two logically-identical re-runs are `=` regardless of
iteration order. Posh only imposes order at the very end (rendering), never in the
diff.

**Hyperlith diffs a HASH of the serialized view, not the structure:**

```clojure
;; hyperlith/src/hyperlith/impl/datastar.clj:182-186
new-view-hash (Integer/toHexString (hash new-view-str))]
;; only send an event if the view has changed
(when (not= last-view-hash new-view-hash) ...)
```

Because it hashes the rendered STRING, the comparison is over a canonical
serialized form — any nondeterminism in intermediate collection order is washed
out by the time it is a string. The transport never sees raw set iteration order.

**datahike's "must-exist" idiom is a precondition + typed throw.** `listen!`
guards with `{:pre [(conn? conn) (atom? (:listeners (meta conn)))]}`
(`core.cljc:206`); `deref-conn` throws a typed `ex-info` for a released conn
(`connector.cljc:71`: `{:type :connection-has-been-released}`). And our own
registry already has the canonical "lookup that must exist" envelope:

```clojure
;; seon/src/seon/server/registry.clj:372 (resolve-conn)
{::error-kind "not-found"
 ::error (str "unknown db-name: " db-name)}
```

### (b) Recommended pattern for Seon

1. **Stop `(vec result)` in the diff path.** `on-tx!` currently does
   `(d/q query db)` and compares `(not= new-result last-result)` (good — both are
   sets, order-insensitive, GATE 2 is correct as-is). The ONLY `vec` is at emit
   (`reactive.clj:256` `(vec result)`). Replace that with a **deterministic sort**
   at the serialize boundary: `(vec (sort result))` for relation rows (tuples sort
   lexicographically), so the guest sees a stable order and never diffs phantom
   row reorderings. Sorting once at emit, not in the diff, matches both posh (diff
   on sets) and hyperlith (canonicalize at serialize).
   - If a stable total order on heterogeneous tuples is awkward, hash-sort:
     `(sort-by hash result)` — deterministic per JVM run, which is what the guest
     diff needs (it diffs consecutive emits within a session).

2. **`engine-state` must-exist lookup throws typed `ex-info`.** Mirror the
   registry `not-found` idiom:

```clojure
(defn engine-state
  "The per-db engine state. Throws a typed not-found if the db has no engine
   (the on-ensure-db hook installs one per opened conn; a missing one means the
   db was never ensured)."
  [db-name]
  (or (get @!engines db-name)
      (throw (ex-info (str "no reactive engine for db-name: " db-name)
                      {:seon.server.reactive/error-kind "not-found"
                       :seon.server.reactive/db-name db-name}))))
```

Platform's `handle-op` wrappers already catch `ExceptionInfo` and turn it into the
`err` envelope (`wire.clj:570`), so a typed throw becomes a clean wire error
instead of a deep NPE.

### (c) Simpler alternative (delete code)

If we adopt the **always-pull** result shape (W3 option 1), rows are vectors of
MAPS, and the natural canonical form is "sort by `:db/id`" — a single, obvious
total order, no `sort-by hash` needed. That removes the heterogeneous-tuple
ordering question entirely. Combined with W2's "emit the registered data map,"
the diff stays on the raw pull result (maps compare structurally) and the only
ordering decision is `(sort-by :db/id pull-rows)` at emit. Cleanest of all.

---

## Proposals for platform

Numbered contract decisions to bring to the platform track. Each: recommendation +
rationale.

1. **Canonical in-process db identity = the keyword db-name; the wire string is a
   boundary-only encoding.** Register the db-name shape ONCE
   (`:seon.server.store/db-name` = `:keyword`) and reference it from registry and
   reactive. Add a single `db-name->wire` / `wire->db-name` coercion pair (the only
   place `(subs (str kw) 1)` / `(keyword s)` lives). `broadcast/subscribe!` and the
   per-DB routing in `broadcast!` key on the KEYWORD; the string exists only inside
   the CBOR frame for host demux.
   *Rationale:* datahike's own identity is the store-id UUID derived 1:1 from the
   keyword db-name (`store.clj:110`, `connector.cljc:275`); the string convention is
   ours and is the source of the silent-drop bug. One canonical type + one coercion
   pair makes the nil-drop class impossible.

2. **Add `(reactive/engine-state db-name)` as a must-exist registry keyed by the
   keyword db-name, installed by the on-ensure-db hook.** Platform op-handlers
   resolve `state` via this, alongside resolving `conn` via `registry/resolve-conn`
   — both keyed by the same keyword.
   *Rationale:* the engine state is per-conn (per-cluster); the hook already fires
   per opened conn with the keyword db-name. This is the missing seam platform needs
   to dispatch subscription ops. Make the lookup throw a typed `not-found` (W5) so a
   missing engine is a clean wire error, not a deep NPE.

3. **The reactive engine emits exactly one registered Clojure data map
   (`changed-summaries-event`); the CBOR/Transit wire envelope is built ONLY in
   `wire.clj`.** Add `changed-summaries-event->wire` next to `ok-event-from-report`;
   wire it into the `::reactive` listener's injected `emit!`. The engine names no
   wire string literal.
   *Rationale:* posh (`plugin_base.cljc:38`) and hyperlith
   (`datastar.clj:178`) both keep pure routing/render separate from transport. The
   wire schema lives in one place; the engine stays transport-agnostic and testable
   in-process. This is already how `reactive.clj` is shaped — the proposal LOCKS it
   as a contract so no future agent inlines the envelope.

4. **Reuse the existing pub fanout for reactive events — no new socket.** The
   `changed-summaries` frame is just another `"event"`-tagged frame on the same pub
   socket, demuxed by `"db-name"` like `tx`.
   *Rationale:* deletes a would-be parallel transport; the host already demuxes the
   single tagged stream by db-name (`broadcast.clj` docstring).

5. **Constrain subscriptions to pull-shaped queries (`:find (pull ?e sel)`) so the
   result is ALWAYS a vector of maps — ONE registered schema
   (`:seon.server.reactive/pull-rows`).** Defer scalar/collection/relation result
   variants. Reject non-pull subscriptions at register-time with a clear error.
   *Rationale:* the render use-case wants an entity (pull), which is posh's common
   reaction path (`pull_analyze.cljc`); this deletes the `:multi` union and the
   `(d/q ...)`-returns-maps-that-fail-the-schema bug. "Add constraints to simplify,
   relax later." If platform needs relation/scalar subs at M3, fall back to a
   registered `:multi` dispatching on `:seon.server.reactive/find-spec` — never
   `:any`.

6. **Canonicalize result ordering at the emit boundary, not in the diff.** Keep
   GATE 2 comparing raw results (sets/maps compare order-insensitively). At emit,
   `(sort-by :db/id pull-rows)` (pull shape) or `(sort-by hash result)` (relation
   shape).
   *Rationale:* posh diffs sets (`core.cljc:231`), hyperlith hashes the serialized
   view (`datastar.clj:183`) — both wash out intermediate order. Sorting once at
   emit gives the guest a stable diff with no phantom reorderings, and `(vec set)`
   (today's `reactive.clj:256`) is replaced by a deterministic sort.

7. **(Enhancement, not a M3 blocker) Extend the Malli→datahike bridge in place with
   `:db.type/tuple` + tuple/component/doc prop passthrough.** Add a `:tuple` branch
   to `schema->attr-partial` and extend `seon-db-props->db-props` to copy
   `:seon.db/tuple-types` → `:db/tupleTypes`, `:seon.db/component` →
   `:db/isComponent`, `:seon.db/doc` → `:db/doc`. Keep the nested-collection
   rejection.
   *Rationale:* malli-datomic already does this (`datomic_schema_gen.cljc:88,184`)
   and datahike accepts tuples (`schema.cljc:51,73`). It is the sanctioned path to
   store a small FIXED result row as one datom IF a future feature wants persisted
   rows. With proposals 3–5 (rows ride the event, not datoms), it is NOT needed for
   M3 — flag it so a future agent extends the bridge rather than inlining a nested
   shape. NO v2 — the fix goes in `seon.db.datahike.schema`.

### Net effect if proposals 1–6 are adopted

`reactive.clj` shrinks: no wire literals (3), one result schema instead of a union
(5), `(sort-by :db/id ...)` replaces `(vec result)` (6), a typed must-exist
`engine-state` (2). `broadcast.clj`/`wire.clj` gain one coercion pair and one
`*-event->wire` builder, and the string/keyword silent-drop bug (1) is structurally
eliminated. The engine stays a pure function of (db, subscriptions) → registered
event, exactly mirroring posh's `after-transact` and hyperlith's `render-fn`.
