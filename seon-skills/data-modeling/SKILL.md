---
name: data-modeling
description: "Designing a data model in Seon — schema design IS database design, ONE act. Use when modeling a new domain, deciding an attribute's type, choosing identity vs ref vs component-ref vs cardinality-many, picking optional vs required, writing a fn spec over your data, or driving generative tests from a schema. Use when you catch yourself reaching for a :type/:kind field, a 'table' of records, a [:maybe X], a stored nil, or an inline-duplicated constraint. This skill owns DESIGN (what shape to register and why); the datahike skill owns the resulting query/transact/pull mechanics — cross-linked, not duplicated."
---

# Data Modeling — schema design IS database design

In Seon, modeling data, validating it, and storing it are **one act**. You
`schema/register!` a Malli type for an attribute; that single registration

- **validates** every value at runtime (instrumented fns + the `transact!` gate),
- **auto-derives** the datahike attribute (`:db/valueType`/`:db/cardinality`/
  `:db/unique`/`:db/isComponent`) — you NEVER hand-write datahike schema, and
- **generates** example data for tests (the schema is a test.check generator).

So "design the schema" = "design the database" = "design the contract". This
skill is the DESIGN decisions. The resulting query/transact/pull/upsert
mechanics belong to the **`datahike`** skill; the data-oriented *why* (no kinds,
namespaced keys, derive-don't-store) belongs to **`data-oriented-clojure`**.
Read both — this skill assumes their mindset and won't repeat it.

## Step 0 — there are NO entity kinds; you model ATTRIBUTES + connections

The single biggest design error from an OO/table background: reaching for a
`:type`/`:kind`/class. Datahike has none — an entity is just an id plus the
datoms it carries. What an entity "is" comes from **which attributes are present**
and **how refs connect it**. Schema attaches to attributes, never entities; one
entity can carry attrs from several namespaces at once (no migration, no join).

So you never model "a Source table". You register the attributes a source
*carries* (`:my.kb.source/id`, `/title`, `/author`), and a source IS any entity
asserting them. Design moves, not tables:

- **FIND a set** → query by attribute presence (`[?e :my.kb.source/id]`).
- **IDENTIFY one** → a `{:seon.db/identity true}` attribute (also drives upsert).
- **RELATE / REMOVE** → refs (`:seon.db/component` cascades the delete).
- **SCOPE** → provenance (`:seon.db/origin` on the tx), not a kind field.

If you write "for each kind" or a `:kind` enum, stop and reframe.

## Authoring an attribute — pick the type, the bridge does the rest

Namespace every attr `:seon.<ns>/<name>` where the namespace is a REAL code ns
that owns the data; inside that ns write `::name` and the reader expands it.
Register the Malli type; `seon.db.internal/malli->datahike-attr` derives the
datahike facet. The DESIGN choice is which Malli shape expresses your intent:

```clojure
(require '[seon.schema :as schema])

;; scalars — concrete types only (no :any / :some / [:maybe X])
(schema/register! ::title  :string)
(schema/register! ::rank   :int)        ; → :db.type/long
(schema/register! ::ratio  :double)
(schema/register! ::active :boolean)
(schema/register! ::when   :inst)       ; js/Date — → :db.type/instant
(schema/register! ::uid    :uuid)
(schema/register! ::tag    :keyword)

;; constrain the VALUE, not just the type — the constraint is the contract
(schema/register! ::rating [:int {:min 1 :max 5}])
(schema/register! ::title2 [:string {:min 1}])     ; non-blank

;; enum = a closed set (stores :db.type/keyword, cardinality one)
(schema/register! ::status [:enum :open :doing :done])

;; vector/set/sequential → cardinality-MANY (a SET of values, order not kept)
(schema/register! ::topics [:vector :keyword])

;; ref → points at another entity (:db.type/ref, cardinality one)
(schema/register! ::author :seon.db/ref)

;; component ref → owned children that CASCADE-retract with the parent
(schema/register! ::findings [:vector {:seon.db/component true} :seon.db/ref])

;; identity → the natural key; same value ⇒ same entity (UPSERT) + lookup-ref
(schema/register! ::id [:string {:seon.db/identity true}])
```

What the bridge installs for each (verify live with
`(seon.db/malli->datahike-schema [::attr])`):

| Design intent | Register | Datahike facet |
|---|---|---|
| scalar | `:string`/`:int`/`:keyword`/`:inst`/`:boolean`/`:double`/`:uuid` | matching `:db.type/*`, cardinality one |
| closed set | `[:enum :a :b]` (keyword members) | `:db.type/keyword`, one |
| many values | `[:vector X]` / `[:set X]` | value-type of `X`, **cardinality many** |
| points at entity | `:seon.db/ref` | `:db.type/ref`, one |
| owns children | `[:vector {:seon.db/component true} :seon.db/ref]` | ref, many, **`:db/isComponent`** |
| natural key | `[:string {:seon.db/identity true}]` | + `:db/unique :db.unique/identity` |

The bridge maps `:enum` (keyword members only), `:and` (bridges on its base),
and same-type `:or`; an unmappable shape THROWS — extend the bridge
(`src/seon/db/internal.cljs`), never hand-write a `:db.type/*`. Full table +
query/transact mechanics: the **`datahike`** skill.

### Three design rules the type system enforces

- **Optional = ABSENT, never nil.** Express it `[::field {:optional true} …]` in
  the `:map`; if present it must be valid. Never store nil; to clear a field,
  retract it. (`[:maybe X]` is banned — it's the nil door.)
- **id / ref / ident: choose deliberately.** Identity is the *natural key* you
  look entities up by and upsert on. A plain `:seon.db/ref` is a *link*. A
  *component* ref additionally OWNS the child (delete cascades) — use it only for
  data with no life of its own (findings of a source); use a plain ref for a
  shared entity (an author cited by many sources).
- **Cardinality-many is a SET, not a list.** Transacting a many-value ADDS;
  there's no order and no duplicates. If you need ordered/positional data,
  that's a different model (child entities with an index attr), not `[:vector]`.

## Shared shapes: register once, reference everywhere

If a shape (an id length, a constraint, an enum) would appear in two+
registrations, register the SHAPE under its own keyword and reference it — never
inline-duplicate (duplication guarantees drift). The canonical examples live in
`seon.schema`: `:seon.db/id` (the 14-char id shape) and `:seon.db/ref`.

```clojure
(schema/register! :seon.db/id [:string {:min 14 :max 14}])   ; ONE source of truth
(schema/register! ::agent-id   :seon.db/id)                  ; reference it
(schema/register! ::session-id :seon.db/id)
(schema/register! ::id [:and {:seon.db/identity true} :seon.db/id])  ; identity wrap
```

If the bridge can't follow a reference shape you need, FIX the bridge — don't
duct-tape by inlining.

## Composite map schemas + entity declaration

A `:map` schema names a composite shape — a fn's request/response, or a declared
entity kind. Reference your attr schemas by keyword (don't re-inline their
shapes); mark optional fields `{:optional true}`:

```clojure
(schema/register! ::source-entity
  [:map {:seon.db/entity true}                 ; ← declares a stored entity kind
   [::id ::id]                                 ; identity attr (required)
   [::title ::title]
   [::rating {:optional true} ::rating]        ; absent when unknown
   [::topics {:optional true} ::topics]
   [::author {:optional true} ::author]])
```

The `{:seon.db/entity true}` marker is opt-in and load-bearing: `register!`
derives `:seon.entity/id-attr` and emits a queryable `:seon.schema` row, so the
renderer can enumerate instances by walking that id-attr's index (NO per-row
`:kind` stamp). Request/response/view maps OMIT the marker — they're contracts,
not catalogued kinds.

## Function specs — DEFAULT to map-in / map-out

Functions operate on this data, so spec them with the SAME registered schemas.
**For any API-like surface, default to map-in / map-out:** one
namespaced-keyword map IN, one map OUT, where the request and response are
EXPLICIT, NAMED, REGISTERED schemas — `::foo-request` and `::foo-response`. This
is the primary shape Seon builds.

Why it's the default: a named input schema + a named output schema make the
function's contract a single unambiguous, discoverable, *referenceable* thing.
That is exactly what you want a generator (an agent — or the diffusion model
under guided generation) to produce: register two `:map`s, then the body's
`:malli/schema` is trivially `[:=> [:cat ::foo-request] ::foo-response]`. The
generation target is unambiguous and the parser/oracle's job is easy. It also
ACCRETES safely — add an optional field to the request and old callers don't
break.

```clojure
;; 1. register the request + response as :map schemas — every key namespaced,
;;    every value a registered shape, optional fields marked (absent, never nil):
(schema/register! ::add-request
  [:map
   [::title  ::title]                    ; required
   [::rating {:optional true} ::rating]  ; optional → absent when unknown
   [::topics {:optional true} ::topics]])

(schema/register! ::add-response
  [:map
   [::ok?   :boolean]
   [::id    {:optional true} ::id]       ; present on success
   [::error {:optional true} :string]])  ; present on failure

;; 2. the fn's :malli/schema just references the two named schemas:
(defn add
  "Add a source. Map in (::add-request), map out (::add-response)."
  {:malli/schema [:=> [:cat ::add-request] ::add-response]}
  [{::keys [title rating topics]}]       ; destructure the named keys
  (if (str/blank? title)
    {::ok? false ::error "::title is required"}
    (let [id (seon.db/new-id!)]
      (seon.db/transact! {:seon.db/tx-data [(cond-> {::id id ::title title}
                                              rating (assoc ::rating rating)
                                              topics (assoc ::topics topics))]})
      {::ok? true ::id id})))            ; one namespaced map out
```

The named schemas are reusable: another fn's response can `[::source ::add-request]`
a request shape, a view can reference `::add-response`, and "what produces an
`::add-response`?" is a registry/DB query, not a guess.

### Secondary shape — named positional via `:catn`

Reach for positional only for an **ordinary data-processing fn** or to **mimic a
well-known API** (datahike does this — `seon.db/pull`, `seon.db/query`). Each
slot still gets a fully-namespaced spec; the return is still fully specced. The
invariant is completeness — a bare/unspecced arg is the only violation, never a
specced positional one.

```clojure
(defn rename
  {:malli/schema [:=> [:catn [::id ::id] [::new-title ::title]] ::add-response]}
  [id new-title] …)
```

Every schema'd public fn is instrumented and THROWS on a mismatch — a wrong
schema is a runtime bug, not a doc nit. (Agent-facing verbs return their `::ok?`
response map instead of throwing — see `data-oriented-clojure`.)

## The schema IS the generator — generative testing

A registered schema yields a test.check generator for free
(`malli.generator/generate`, `/sample`) — so the data model *drives* its own
tests. The loop: design schema → generate example data → assert a property.

```clojure
(require '[malli.generator :as mg])
(mg/generate ::source-entity)   ; → a valid example map, every required attr present
(mg/sample   ::rating 5)        ; → (3 1 5 2 4)   — respects {:min 1 :max 5}
```

Use it to round-trip your model before writing real code: generate an entity,
`transact!` it, query it back, check it matches. On the **active pod (CLJS)**,
write *example* tests (the schema is still the unit you assert against — see
`clojure-testing`); the `mg/sample` + `user/run-tests` generative-property idiom
is the **paused JVM track**. Either way the schema, not a hand-built fixture, is
the source of test data.

## Worked example — a small domain end to end

Model a knowledge base: a **source** (natural key, owns its **findings**, cites a
shared **author**).

```clojure
;; 1. attributes — each carries the namespace of its owning code ns
(schema/register! :my.kb.source/id     [:string {:seon.db/identity true}]) ; natural key
(schema/register! :my.kb.source/title  :string)
(schema/register! :my.kb.source/rating [:int {:min 1 :max 5}])
(schema/register! :my.kb.source/topics [:vector :keyword])                 ; cardinality-many
(schema/register! :my.kb.source/author :seon.db/ref)                       ; shared → plain ref
(schema/register! :my.kb.source/findings
                  [:vector {:seon.db/component true} :seon.db/ref])        ; owned → component
(schema/register! :my.kb.author/id     [:string {:seon.db/identity true}])
(schema/register! :my.kb.author/name   :string)
(schema/register! :my.kb.finding/id    [:string {:seon.db/identity true}])
(schema/register! :my.kb.finding/text  :string)

;; 2. derived datahike schema (what got installed — no hand-writing):
(seon.db/malli->datahike-schema
  [:my.kb.source/id :my.kb.source/topics :my.kb.source/author :my.kb.source/findings])
;;=> [{:db/ident :my.kb.source/id     :db/valueType :db.type/string :db/cardinality :db.cardinality/one
;;     :db/unique :db.unique/identity}
;;    {:db/ident :my.kb.source/topics  :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
;;    {:db/ident :my.kb.source/author  :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
;;    {:db/ident :my.kb.source/findings :db/valueType :db.type/ref    :db/cardinality :db.cardinality/many
;;     :db/isComponent true}]

;; 3. one tx links author + source + findings via tempids (datahike mechanics → datahike skill):
(seon.db/transact!
  {:seon.db/tx-data
   [{:db/id "a1" :my.kb.author/id "auth-1" :my.kb.author/name "Hickey"}
    {:my.kb.source/id "src-1" :my.kb.source/title "Simple Made Easy"
     :my.kb.source/rating 5 :my.kb.source/topics [:design :clojure]
     :my.kb.source/author "a1"
     :my.kb.source/findings [{:my.kb.finding/id "f1" :my.kb.finding/text "simple ≠ easy"}]}]})

;; 4. the schema is the test oracle — generate, then assert the property:
(mg/generate [:map [:my.kb.source/id :my.kb.source/id]
                   [:my.kb.source/rating :my.kb.source/rating]])
```

`src/my/kb.cljs` is the runnable, test-exercised version of exactly this — read
it for live idiom. `src/seon/agent/todo.cljs` is the exemplar for refs + tree/DAG
modeling.

## Key files

| File | What it gives you |
|---|---|
| `src/seon/schema.cljc` | `register!`, the registry, entity-schema decomposition |
| `src/seon/db/internal.cljs` | `malli->datahike-attr` — the bridge (extend it here) |
| `src/my/kb.cljs` | runnable schema-design manual — copy a recipe |
| `docs/conventions.md` | Malli patterns, base+provider, request/response, `:any` boundary |
| `reference-code/malli/src/malli/{core,generator}.cljc` | schema syntax + generator derivation |
| `reference-code/spectomic`, `reference-code/malli-datomic` | the upstream spec/malli→datomic bridges this generalizes |

For querying / transacting / upsert / retract / refs-at-read-time → the
**`datahike`** skill. For the mindset → **`data-oriented-clojure`**.
