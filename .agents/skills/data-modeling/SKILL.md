---
name: data-modeling
type: skill
status: active
description: "Designing a data model in Seon — schema design IS database design, ONE act. Use when modeling a new domain, authoring resources/seon/schemas/*.edn, deciding an attribute's type, choosing identity vs ref vs component-ref vs cardinality-many, picking optional vs required, writing a function contract over the data, or driving generative tests from a schema. Use when you catch yourself reaching for a :type/:kind field, a 'table' of records, a stored nil, or an inline-duplicated constraint. This skill owns DESIGN (what shape to declare and why); the datahike skill owns the resulting query/transact/pull mechanics."
---

# Data Modeling — schema design IS database design

In Seon, modeling data, validating it, and storing it are **one act**. Declare
first-party shapes under `resources/seon/schemas/`; the classpath
population

- **validates** values through the active projection and live instrumentation,
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

So you never model "a Source table". You declare the attributes a source
*carries* (`:my.kb.source/id`, `/title`, `/author`), and a source IS any entity
asserting them. Design moves, not tables:

- **FIND a set** → query by attribute presence (`[?e :my.kb.source/id]`).
- **IDENTIFY one** → a `{:seon.db/identity true}` attribute (also drives upsert).
- **RELATE / REMOVE** → refs (`:seon.db/component` cascades the delete).
- **SCOPE provenance** → the transaction's `:seon.db/user` and
  `:seon.db/process` refs.
- **SCOPE ownership** → a real domain ref such as
  `:seon.cluster.run/agent`, not a kind field.

If you write "for each kind" or a `:kind` enum, stop and reframe.

## Authoring an attribute — EDN first, bridge-derived

Namespace every attr `:seon.<ns>/<name>` where the namespace is a real code
namespace that owns the data. In EDN, write the full qualified keyword.
`seon.schema.edn/load!` reads the classpath resource `seon/schema.edn`;
`seon.schema.datahike/malli->datahike-attr` derives the
Datahike facet. The design choice is which Malli shape expresses the intent:

```clojure
;; resources/seon/schemas/ — owning knowledge-base family
{:my.kb.source/title :string
 :my.kb.source/rank :int
 :my.kb.source/ratio :double
 :my.kb.source/active :boolean
 :my.kb.source/when :inst
 :my.kb.source/uid :uuid
 :my.kb.source/tag :keyword
 :my.kb.source/rating [:int {:min 1 :max 5}]
 :my.kb.source/status [:enum :open :doing :done]
 :my.kb.source/topics [:vector :keyword]
 :my.kb.source/author :seon.db/ref
 :my.kb.source/findings
 [:vector {:seon.db/component true} :seon.db/ref]
 :my.kb.source/id [:string {:seon.db/identity true}]}
```

What the bridge installs for each (verify live with
`(seon.schema.datahike/malli->datahike-schema [::attr])`):

| Design intent | EDN declaration | Datahike facet |
|---|---|---|
| scalar | `:string`/`:int`/`:keyword`/`:inst`/`:boolean`/`:double`/`:uuid` | matching `:db.type/*`, cardinality one |
| closed set | `[:enum :a :b]` (keyword members) | `:db.type/keyword`, one |
| many values | `[:vector X]` / `[:set X]` | value-type of `X`, **cardinality many** |
| points at entity | `:seon.db/ref` | `:db.type/ref`, one |
| owns children | `[:vector {:seon.db/component true} :seon.db/ref]` | ref, many, **`:db/isComponent`** |
| natural key | `[:string {:seon.db/identity true}]` | + `:db/unique :db.unique/identity` |

The bridge maps `:enum` (keyword members only), `:and` (bridges on its base),
and same-type `:or`; a `[:maybe X]` on a stored attribute and any unmappable
shape THROW — extend the bridge
(`src/seon/schema/datahike.cljc:36-48,149-205`), never hand-write a
`:db.type/*`. The other
properties it reads are `{:seon.db/unique true}` (`:db.unique/value`),
`{:seon.db/index true}`, and `{:seon.db/no-history? true}`. Full table +
query/transact mechanics: the **`datahike`** skill.

### Three design rules the type system enforces

- **Use the omission ruling exactly.** `[:maybe]` is allowed in in-memory
  function RETURN contracts (stored attributes stay nil-free — the bridge
  forces absence there). Express a stored optional field with
  `{:optional true}`; if present it must be valid. To clear it, retract it.
- **id / ref / ident: choose deliberately.** Identity is the *natural key* you
  look entities up by and upsert on. A plain `:seon.db/ref` is a *link*. A
  *component* ref additionally OWNS the child (delete cascades) — use it only for
  data with no life of its own (findings of a source); use a plain ref for a
  shared entity (an author cited by many sources).
- **Cardinality-many is a SET, not a list.** Transacting a many-value ADDS;
  there's no order and no duplicates. If you need ordered/positional data,
  that's a different model (child entities with an index attr), not `[:vector]`.

## Shared shapes: declare once, reference everywhere

If a shape (an id length, a constraint, an enum) would appear in two+
declarations, declare the shape under its own keyword and reference it — never
inline-duplicate (duplication guarantees drift). The canonical shared shape in
the schema population is `:seon.db/ref`, which every ref attribute references;
`seon.schema.datahike/resolve-malli-form` is what follows such a reference to
the shape it names.

```clojure
{:my.kb.source/external-id :string
 :my.kb.source/id
 [:and {:seon.db/identity true} :my.kb.source/external-id]}
```

If the bridge can't follow a reference shape you need, FIX the bridge — don't
duct-tape by inlining.

## Config is derived from one leaf declaration

Declare each config attribute exactly once under `resources/seon/schemas/`.
`seon.schema.edn/derive-config-forms` discovers those leaf registrations and
derives open `:seon.config/manifest`, `:seon.config/effective`,
`:seon.config/agent-overlay`, and `:seon.config/entity` schemas. Never add the
same dial to hand-maintained composite maps or a separate roster.
`config/default.edn` is the complete shipped decision document and
`seon.config/compile-manifest` applies defaults, overlay, and explicit
environment data once
(`src/seon/schema/edn.clj:95-126`; `src/seon/config.clj:137-229`).

## Provenance is NOT a domain attribute — the tx already records it

Before registering a provenance-ish attr — `created-by`, `created-at`,
`updated-by`, `source-turn` — stop. Seon writes exactly two durable provenance
refs on the transaction entity: `:seon.db/user` and `:seon.db/process`;
Datahike also stamps `:db/txInstant`. "Who, through which
stable process, and when wrote this?" is a join through the datom's transaction,
not an attribute duplicated on the domain entity. Do not copy runtime
attribution onto unrelated domain entities. Runtime facts that are durable
system truth live on their owning run, eval, or test entities
(`resources/seon/schemas/seon.cluster.run.edn:10-15,41-49`;
`resources/seon/schemas/seon.sci.eval.edn:11-30`). Add a domain-specific transaction fact
only when it records a real source fact that those two refs cannot express.
See the **`datahike`** skill, "Transaction metadata".

## Composite map schemas + entity declaration

Every Malli map is open. Required declared keys remain rigorously validated;
extra keys are ignored until declared. `{:closed true}` is refused
(`src/seon/schema/admission.clj:250-258`;
`test/seon/schema/admission_gate_test.clj:12-42,85-93`).

A `:map` schema names a composite shape — a fn's request/response, or a declared
entity schema. Reference your attr schemas by keyword (don't re-inline their
shapes); mark optional fields `{:optional true}`:

```clojure
{:my.kb.source/entity
 [:map {:seon.db/entity true}
  [:my.kb.source/id :my.kb.source/id]
  [:my.kb.source/title :my.kb.source/title]
  [:my.kb.source/rating {:optional true} :my.kb.source/rating]
  [:my.kb.source/topics {:optional true} :my.kb.source/topics]
  [:my.kb.source/author {:optional true} :my.kb.source/author]]}
```

The `{:seon.db/entity true}` marker is opt-in and load-bearing: the projection
derives the identity attribute and emits a queryable `:seon.schema` row, so the
renderer can enumerate instances by walking that id-attr's index (NO per-row
`:kind` stamp) (`src/seon/schema.cljc:1149-1209`). Request/response/view maps
OMIT the marker — they're contracts, not catalogued kinds.

## Function specs — DEFAULT to map-in / map-out

Functions operate on this data, so spec them with the SAME declared schemas.
**For any API-like surface, default to map-in / map-out:** one
namespaced-keyword map IN, one map OUT, where the request and response are
explicit named schemas — `::foo-request` and `::foo-response`. This
is the primary shape Seon builds.

Why it's the default: a named input schema + a named output schema make the
function's contract a single unambiguous, discoverable, *referenceable* thing.
That is exactly what you want a generator (an agent — or the diffusion model
under guided generation) to produce: declare two `:map`s, then the body's
`:malli/schema` is trivially `[:=> [:cat ::foo-request] ::foo-response]`. The
generation target is unambiguous and the parser/oracle's job is easy. It also
ACCRETES safely — add an optional field to the request and old callers don't
break.

```clojure
;; resources/seon/schemas/ — owning knowledge-base family
{:my.kb.source/add-request
 [:map
  [:my.kb.source/title :my.kb.source/title]
  [:my.kb.source/rating {:optional true} :my.kb.source/rating]
  [:my.kb.source/topics {:optional true} :my.kb.source/topics]]
 :my.kb.source/add-response
 [:map
  [:my.kb.source/ok? :boolean]
  [:my.kb.source/id {:optional true} :my.kb.source/id]
  [:seon.error/value {:optional true} :seon.error/value]]}
```

```clojure
;; The function contract references the named EDN schemas:
(defn add
  {:malli/schema
   [:=> [:cat :my.kb.source/add-request]
    :my.kb.source/add-response]}
  [request]
  ...)
```

The named schemas are reusable: another fn's response can `[::source ::add-request]`
a request shape, a view can reference `::add-response`, and "what produces an
`::add-response`?" is a registry/DB query, not a guess.

### Secondary shape — named positional via `:catn`

Reach for positional only for an **ordinary data-processing fn** or to **mimic a
well-known API** (Datahike does this — `d/pull`, `d/q`). Each
slot still gets a fully-namespaced spec; the return is still fully specced. The
invariant is completeness — a bare/unspecced arg is the only violation, never a
specced positional one.

```clojure
(defn rename
  {:malli/schema [:=> [:catn [::id ::id] [::new-title ::title]] ::add-response]}
  [id new-title] …)
```

A wrong schema is a bug, not a doc nit. `seon.instrument/apply!` instruments
every loaded public var carrying `:malli/schema` in development, with no
namespace allow list. Tests and generators still prove the contract rather
than relying only on runtime checks.

### Program rows, base context, run fork, and session image

Keep the four boundaries distinct. Read the one checked current/target source,
[`program-state.md`](../data-oriented-clojure/references/program-state.md),
instead of restating this contract elsewhere.

### Global schema lifecycle

Schema identity is global: one `:seon.schema/key` row, never namespace-owned.
A function contract references schema keys; namespace context may reverse-find
those keys, but that is a query, not ownership. Runtime
`schema/unregister!` stages removal inside the current evaluation delta and
refuses outside it (`src/seon/schema.cljc:1089-1108`). Removal refuses while a
schema or function contract depends on the affected key. Replacement and
removal also refuse atomically while any directly or transitively affected
Datahike attribute—including entity-child attributes—carries current data.
After current data and contract dependencies are retracted, the operation may
commit (`src/seon/schema.cljc:1907-1935`;
`src/seon/cluster/run.cljc:562-730`;
`test/seon/schema_usage_guard_test.clj:80-397`).

Ordinary history retains the old datoms and historical global schema row, so a
simulation can rebuild the Malli projection from the same `as-of` database
value. Datahike's physical schema map itself remains current rather than
time-travelling. `:seon.db/no-history? true` deliberately discards the old
values and therefore cannot promise historical simulation
(`docs/prds/sci-execution-runtime/research/schema-removal-history-probe-2026-07-30.md`).

## The schema IS the generator — generative testing

A declared schema is **eligible for the standing generator contract**: it
becomes a generator only after construction and generate-then-validate have
passed on every owning tier. Malli generator overrides REPLACE generation;
Malli never proves an override's output satisfies the schema it decorates.
The loop: design schema → prove it generates honestly → assert properties.

```clojure
(require '[malli.generator :as mg])
(let [values (mg/sample ::source-entity {:seed 20260726 :size 50})]
  (assert (every? #(m/validate ::source-entity %) values)))
```

Rules that make a schema generatively honest:

- **Every `[:fn ...]` predicate schema MUST carry an honest `:gen/schema` or
  `:gen/gen`.** Prefer EDN-readable `:gen/schema` in classpath EDN forms —
  schema forms are database facts; test.check generator objects are not
  durable data. Honest means every emitted value satisfies the predicate AND
  covers meaningful domain partitions — a canned satisfier (`:gen/return`,
  a single `[:= x]`) green-washes an open domain.
- After authoring a predicate schema, add a recurring property that generates
  at fixed seeds/sizes and validates every value against the same compiled
  registry projection. Fail on construction, generation, or validation.
  `:gen/fmap` output is the value that must validate.
- A three-child `[:=> input output [:fn guard]]` states a pure relation over
  ONE `[args result]` pair, and the guard's `:fn` needs its own honest
  generator. Relations involving two calls, a commit, replay, resume, or
  observed facts are explicit seeded `test.check` state-transition properties
  (see `research/spec-authorship-relational-properties-2026-07-26.md`).
- Regex generation is tier-dependent (Malli 0.20.0): a `:re` schema that must
  hold on more than one tier owns a structural generator. Recursive schemas
  keep a reachable base case and are sampled at several sizes with an asserted
  size bound.

- **Every generated input is a function of the seed.** A property body that
  mints a `random-uuid` or reads the wall clock cannot be replayed and its
  shrunk counterexample cannot be reproduced, whatever `:seed` it passes.

Write the properties as normal `clojure.test` namespaces under `test/`, run by
`bin/test` (see `clojure-testing`). The generator remains a data source inside
that suite: for a *shape*, the schema is the oracle and no hand-built fixture
is needed; for a *transition*, the oracle is a pure model you write, and it is
only as good as the invariant it re-derives — see `clojure-testing`, "Four
rules that decide whether the property is worth anything". Full evidence and
the pitfall catalog:
`docs/prds/sci-execution-runtime/research/malli-generative-patterns-2026-07-26.md`.

## Worked example — a small domain end to end

Model a knowledge base: a **source** (natural key, owns its **findings**, cites a
shared **author**).

```clojure
;; resources/seon/schemas/ — owning knowledge-base family
{:my.kb.source/id [:string {:seon.db/identity true}]
 :my.kb.source/title :string
 :my.kb.source/rating [:int {:min 1 :max 5}]
 :my.kb.source/topics [:vector :keyword]
 :my.kb.source/author :seon.db/ref
 :my.kb.source/findings
 [:vector {:seon.db/component true} :seon.db/ref]
 :my.kb.author/id [:string {:seon.db/identity true}]
 :my.kb.author/name :string
 :my.kb.finding/id [:string {:seon.db/identity true}]
 :my.kb.finding/text :string}
```

```clojure
;; Inspect the derived Datahike declarations:
(seon.schema.datahike/malli->datahike-schema
  [:my.kb.source/id :my.kb.source/topics :my.kb.source/author :my.kb.source/findings])

;; One tx links author + source + findings via tempids:
(d/transact connection
  [{:db/id "a1" :my.kb.author/id "auth-1" :my.kb.author/name "Hickey"}
   {:my.kb.source/id "src-1" :my.kb.source/title "Simple Made Easy"
    :my.kb.source/rating 5 :my.kb.source/topics [:design :clojure]
    :my.kb.source/author "a1"
    :my.kb.source/findings [{:my.kb.finding/id "f1" :my.kb.finding/text "simple ≠ easy"}]}])

;; The schema is the test oracle:
(mg/generate [:map [:my.kb.source/id :my.kb.source/id]
                   [:my.kb.source/rating :my.kb.source/rating]])
```

`resources/seon/schemas/seon.cluster.run.edn` and `src/seon/cluster/run.clj` are the live
worked pair: identity attributes, refs, and transition contracts.

## Key files

| File | What it gives you |
|---|---|
| `resources/seon/schemas/` | first-party attribute/entity/value schemas |
| `src/seon/schema/edn.clj` | loading, config derivation, one admission gate |
| `src/seon/schema.cljc` | registry, activation, entity-schema decomposition |
| `src/seon/schema/datahike.cljc` | `malli->datahike-attr` — the bridge (extend it here) |
| `src/seon/schema/form.cljc` | shared form inspection the bridge and gates use |
| `src/seon/fn.clj` | selective durable corpus admission |
| `src/seon/instrument.clj` | computed public-contracted-var instrumentation |
| `src/seon/cluster/run.cljc` | a live domain model end to end |
| `docs/conventions.md` | Malli patterns, request/response, the `:any` boundary |
| `reference-code/malli/src/malli/{core,generator}.cljc` | schema syntax + generator derivation |

For querying / transacting / upsert / retract / refs-at-read-time → the
**`datahike`** skill. For the mindset → **`data-oriented-clojure`**.
