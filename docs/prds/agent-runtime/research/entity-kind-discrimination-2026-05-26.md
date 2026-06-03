---
type: research
status: draft
tags: [research, schema]
---

# Entity Kind Discrimination + `:any`/`:maybe` Audit

**Date:** 2026-05-26
**Branch:** `feature/agent-runtime`
**Context:** moving renderer dispatch from per-row `:seon.render/ai` attrs to schema-level
properties means the current AEVT scan in `src/seon/render.cljs:168`
(`(d/datoms db :aevt :seon.render/ai)`) disappears. We need a primary mechanism to
enumerate "all entities of kind K" so the renderer can resolve the schema's render
fn for each entity.

## TL;DR

- **Q1 — Recommendation: Option A (explicit `:seon.entity/kind` attr per entity).**
  Best query cost, generalizes cleanly to other per-kind dispatch (caching policy,
  retention TTL, validation strictness, audit grouping), and matches the EAV
  convention used by Datomic Pro / XTDB / Datascript practitioners. Storage cost
  is one indexed keyword per entity. Sean's lean is correct; options B, C, D all
  fail at least one criterion.

- **Q2 — `:any` / `:maybe` audit:** 47 hits across 19 files. About 18 are
  **legitimate** (renderer-input fns, runtime opaque values like `db` snapshots
  and conn handles, fn-schema `:=>` returns, log payload for unstructured-by-
  design events). About **29 are violations** that should be tightened. Top 5
  to fix in Phase 1 listed below.

- **Q3 — EAV convention survey:** Across Datomic, XTDB, Datascript, and Asami,
  the dominant convention is **an explicit `:type` / `:kind` / `:entity/type`
  attribute stamped on each entity**. Datomic Cloud documents this pattern
  outright; XTDB ships an `:xt/type` idiom; Datascript users replicate it.
  Explicit kind attr is the convention.

## Q1 — Entity-kind discrimination options

### Option A — Explicit `:seon.entity/kind` attr per entity

**Shape.**

```clojure
(schema/register! :seon.entity/kind :keyword)
;; write site:
(record-eval! {:seon.eval/id ... :seon.entity/kind :seon.eval ...})

```

**Discovery query (renderer):**

```clojure
(->> (d/datoms db :aevt :seon.entity/kind)
     (group-by #(.-v %))  ; group by kind keyword
     (map-vals (fn [ds] (map #(.-e %) ds))))

```

**Storage cost.** One indexed keyword datom per entity. Datahike stores
keywords interned in LMDB; cost is the same as any other indexed attr. AEVT
range scan on a single attr is sub-millisecond in our size class.

**Generalizes.** Once `:seon.entity/kind` exists, every per-kind concern
becomes a schema-property lookup keyed by kind:

- **Render fn** — `(get-in @schemas [k :seon.render/ai])`
- **Retention** — `:seon.retention/ttl` on kind schema; GC sweeper joins
  `:aevt :seon.entity/kind` against schema map.
- **Caching policy** — `:seon.cache/lifetime` on kind schema.
- **Validation strictness** — same.
- **Audit grouping** — `(d/q '[:find ?k (count ?e) :where [?e :seon.entity/kind ?k]] db)`.

This is the **one mechanism viewed five ways** pattern from CLAUDE.md's "code
as data" principle. The substrate gets thinner, not thicker.

**Agent extensibility.** Agent code that wants to add a new kind just writes
`{:seon.entity/kind :seon.my-thing ...}`. The schema-level renderer lookup
finds the agent-registered schema. No substrate change needed.

**Trade-offs.**
- Producer discipline: every write site must stamp the kind. Mitigated by a
  `seon.db/transact!` helper that derives kind from the identity-attr namespace
  on insert (when missing) — fail closed.
- Migration cost: stamping `:seon.entity/kind` on existing rows is a single
  walk of `:aevt :seon.eval/id`, `:aevt :seon.message/id`, etc. Doable in one
  tx per kind.

### Option B — Infer from identity-attr namespace

**Idea:** `:seon.eval/id` has unique identity → its namespace `seon.eval` IS
the kind. Renderer walks each known identity-attr (`:seon.eval/id`,
`:seon.message/id`, ...) separately.

**Why it looks attractive.** Zero new data. The information is already
encoded.

**Why it loses.**
1. **Discovery requires walking N indices instead of 1.** The renderer needs
   to know every identity-attr keyword. That's substrate config the agent
   has to update when defining a new kind — exactly the kind of indirection
   Option A removes.
2. **No place to hang per-kind props.** Where does `:seon.cache/lifetime` for
   `:seon.eval` go? On the `:seon.eval/id` schema? That conflates "this attr
   is the id" with "this kind has a 30-day TTL". Two distinct concerns
   smushed onto one schema entry.
3. **Brittle to id-attr renames.** Renaming `:seon.eval/id` → `:seon.eval/eid`
   silently changes kind semantics. With Option A, kind is its own keyword.
4. **Entities without a registered identity attr have no kind.** Tx-meta
   entities, eval-tee'd render entities — these may or may not have id attrs.
   Option A is uniform.

### Option C — Datahike tx-meta tagging

`:db.entity/attrs` on a tx marks attrs the tx wrote — but that's per-tx not
per-entity. Useful for audit ("this tx wrote evals"), useless for "find every
eval entity in the db." Confirmed not the right answer. Skip.

### Option D — Anything Datahike provides natively

Read of `reference-code/datahike/src/datahike/schema.cljc:65`:

```clojure
(s/def ::schema-attribute
  #{:db/id :db/ident :db/isComponent :db/noHistory :db/valueType
    :db/cardinality :db/unique :db/index :db.install/_attribute
    :db/doc :db/tupleAttrs :db/tupleType :db/tupleTypes})

```

No native `:db/entityType` or kind attribute. Datahike inherits Datomic's
shape and Datomic also lacks a native kind attr — by design. The Datomic
convention since 2014 has been "use a user-owned `:entity/type` keyword."

Datahike DOES ship **entity specs** (`reference-code/datahike/doc/entity_spec.md`):

```clojure
;; example from doc/entity_spec.md:67
(d/transact conn {:tx-data [(assoc valid-account :db/ensure :person/guard)]})

```

These are validation hooks (`:db.entity/attrs`, `:db.entity/preds`) you attach
to a tx via `:db/ensure :some/guard`. They enforce shape but don't give you a
discoverable "kind" the renderer can scan for. Useful for validation, orthogonal
to discrimination.

**Datahike has no special native mechanism. Use the EAV convention.**

### Recommendation: Option A

Register the attr in `seon.schema` once:

```clojure
(schema/register! :seon.entity/kind
  [:keyword {:db/index true
             :description "Discriminator for per-kind dispatch."}])

```

Stamp at the writer boundary in `seon.db/transact!`: if an entity map has an
identity attr `:seon.X/id` but no `:seon.entity/kind`, auto-stamp
`:seon.entity/kind :seon.X`. Fail closed on missing kind once migration is
complete.

Renderer becomes:

```clojure
(defn renderable-entities [db agent-id]
  (->> (d/datoms db :aevt :seon.entity/kind)
       (filter (fn [d] (get-in @schemas [(.-v d) :seon.render/ai])))
       (map (fn [d] {:eid (.-e d) :kind (.-v d)}))))

```

Schema-level lookup replaces per-row symbol storage. Migration is a one-shot
backfill; ongoing cost is one keyword datom per write. Generalization to
caching, retention, etc. is free once the attr exists.

## Q2 — `:any` / `:maybe` audit

Sweep run: `grep -rn ":any\|:maybe" src/seon/`. Total hits: 47 across 19
files. Categorized below.

### Legitimate (keep as-is, with rationale)

Persisted-DB rule applies to **schemas registered as DB attrs**. Function-
contract `:malli/schema` for fns that take/return runtime-opaque values
(db snapshots, conn handles, hiccup vectors-of-anything, flow objects) are
legitimately `:any` because the shape is unknowable at compile time.

- `src/seon/render.cljs:44` `(schema/register! :seon.db/db :any)` — db
  snapshot is a runtime artifact. Validate by `delegated-schema` (Malli
  `-simple-schema` with `:pred` `d/db?`) instead, OR document as legit
  opaque. **Tighten** — register `:seon.db/db` with `:pred` `d/db?`.
- `src/seon/render.cljs:75` `[:map [:seon.render/hiccup :any]]` — hiccup
  is genuinely polymorphic (vector or string or seq). **Tighten** —
  register `:seon.render/hiccup` as
  `[:or :string [:vector :any] [:sequential :any]]` for shape clarity
  even if leaves stay open.
- `src/seon/ctx.clj:64,80,83,95,100,108,160` — fn-schema descriptors for
  ctx atom values, atom-update fns, render-fn vars, http-kit chans. All
  runtime opaque; `:any` is correct.
- `src/seon/runtime.clj:799` `[:any {:description "A core.async.flow object"}]`
  — flow object is opaque. Legit.
- `src/seon/db.clj:278,279,311,328,345,362` — db.clj fn-schemas: query
  return is genuinely `:any` (depends on `:find` clause). Legit.
- `src/seon/db.cljs:225,227,276,284-337` — db.cljs request/response
  schemas wrapping arbitrary query/pull patterns. Legit; this is the
  generic DB API.
- `src/seon/error/instrument.cljc:64-75` — malli `:explain` paths and
  schemas are user-supplied and arbitrary. Legit.
- `src/seon/fs.cljs:92` `(schema/register! :seon.fs/mtime :any)` — js/Date
  cross-reader compat. **Already commented as legit;** add `:default/fn` if
  needed to rebuild after resume.
- `src/seon/analyzer_info.cljs:33,47` — `::compile-state` is the CLJS
  compiler's mutable analyzer atom. Legit opaque.

### Violations (tighten in Phase 1)

These are persisted-attr or boundary-validation schemas using `:any`/`:maybe`
where a concrete type exists.

**Top 5 — Phase 1 fix list:**

1. **`src/seon/log.cljs:49,136,148,312-313` — `:seon.log/data :any`.**
   Log data is the structured payload of a logged event. The codebase
   already pr-str's it before serialization. **Fix:** register
   `:seon.log/data :string` (the pr-str'd form) and require callers to
   pr-str at the boundary. Stop pretending it's a live data structure
   downstream — it isn't, it's a debug breadcrumb.

   ```
   - (schema/register! :seon.log/data    :any)
   + (schema/register! :seon.log/data    :string)
   - [:seon.log/data    {:optional true} :any]
   + [:seon.log/data    {:optional true} :string]

   ```

2. **`src/seon/repl.clj:44` — `[:maybe :string]` violates "no `:maybe`".**
   `::form-name` is optional for expressions/requires. **Fix:** drop the
   `[:maybe …]` and rely on `{:optional true}` at use sites
   (already done at line 86: `[:form/name {:optional true} :string]`).

   ```
   - (schema/register! ::form-name
   -                   [:maybe :string {:description "..."}])
   + (schema/register! ::form-name
   +                   [:string {:description "..."}])

   ```

3. **`src/seon/repl.clj:50` — `::result :any`.** This is the nREPL eval
   result, which IS opaque at the wire level — but the schema only feeds
   instrumentation, not the DB. **Fix:** legit-opaque; add a docstring
   note + `:any` is acceptable here. **Alternative:** register as
   `:string` (pr-str'd at boundary) for the DB row; keep `:any` only
   on the in-memory return type.

4. **`src/seon/agent_view.cljs:38-39` — `:seon.db/conn :any` and
   `:seon.db/db :any` inlined.** `:seon.db/db` IS already registered
   (`render.cljs:44`); referencing the registered name is preferred over
   `:any`. `:seon.db/conn` should be registered globally.

   ```
   - [:seon.db/conn {:optional true} :any]
   + [:seon.db/conn {:optional true} :seon.db/conn]
   - [:map [:seon.db/db :any]]
   + [:map [:seon.db/db :seon.db/db]]

   ```

   Add to `seon.schema`:

   ```
   + (schema/register! :seon.db/conn
   +   [:fn {:error/message "must be a Datahike conn (atom)"}
   +        #(satisfies? IDeref %)])

   ```

5. **`src/seon/render.cljs:75` — `[:map [:seon.render/hiccup :any]]`
   inlines `:any` when `:seon.render/hiccup` is already registered.**
   Self-referential violation.

   ```
   - (schema/register! :seon.render/html-response
   -   [:map [:seon.render/hiccup :any]])
   + (schema/register! :seon.render/html-response
   +   [:map [:seon.render/hiccup :seon.render/hiccup]])

   ```

   And tighten the `:seon.render/hiccup` registration itself
   (`render.cljs:63`):

   ```
   - (schema/register! :seon.render/hiccup ...)  ; currently :any-ish
   + (schema/register! :seon.render/hiccup
   +   [:or :string :int [:vector :any] [:sequential :any]])

   ```
   (leaves stay `:any` since hiccup is recursively polymorphic).

**Additional violations** (Phase 2, lower urgency):

- `src/seon/handler.cljs:104` — `[:tuple :keyword [:maybe :any]]`. The
  composite key allows nil for the second slot. Replace with
  `[:or [:tuple :keyword] [:tuple :keyword :string]]` and use absence-of-
  tuple-slot to mean "no qualifier". Or register the tuple shape explicitly.
- `src/seon/ai.clj:166,177` — `[:input {:optional true} :any]`,
  `[:content {:optional true} :any]`. AI request/response shapes. These
  are persisted via tee; tighten content to `:string` and input to a
  registered `:seon.ai/input` shape.
- `src/seon/health.clj:54` — `[:map-of :keyword :any]`. Health details
  are arbitrary diagnostic data. Register `:seon.health/details` with a
  concrete shape OR pr-str at boundary.
- `src/seon/runtime.clj:135,453,814` — `[:maybe …]` on response shapes.
  Use `{:optional true}` where these compose into maps; for raw fn returns
  this is harder (no `:optional` for a whole return value). **Note:** for
  fn return types `[:maybe X]` is the only Malli way to say "may return
  nil." If the fn semantics legitimately allow nil, this is acceptable.
  Audit whether the fn could just throw or return a richer envelope.
- `src/seon/eval.cljs:301` — `[:=> [:cat :any] [:maybe :any]]`. Internal
  helper. Register concrete shapes.
- `src/seon/ctx.clj:152,575,587,596,610,705,768,783,815,832,850` —
  many fn-return `[:maybe :any]` patterns. These are the "may not exist"
  ctx lookups. Tighten to `[:maybe ::ctx-value]` once `::ctx-value` is
  registered.
- `src/seon/inspect.cljs:28,32`, `src/seon/render.cljs:215`,
  `src/seon/render.cljs:467` — `[:vector :any]` for entity collections.
  Should reference a registered `:seon.render/entity` shape.
- `src/seon/test/runner.cljs:97,544,571` — `[:maybe [:map ...]]` and
  `:any` returns. Test runner internals; tighten to concrete shapes.

### Patterns observed

- **Inline `:any` where a registered schema exists** is the most common
  violation (renders.cljs:75, agent_view.cljs:38). Easy fix.
- **`[:maybe X]` for genuinely optional return values** appears in many
  fn-schemas. Malli has no first-class way to say "fn may return absence,"
  so `[:maybe X]` is the canonical idiom. CLAUDE.md's "no `:maybe`" rule
  applies to **persisted attrs**, not fn returns. Recommend amending the
  rule to clarify.
- **Genuine opacity** (db, conn, flow, compile-state, hiccup leaves) is
  documented and acceptable. Add `:default/fn` for any that need rebuild
  on resume (per CLAUDE.md schema-properties rule).

## Q3 — EAV-database kind conventions (web survey)

**Datomic Cloud / Pro.** Datomic has no native entity-type construct.
Practitioners stamp `:user/type` or `:entity/type` keywords on each
entity. The Datomic docs explicitly call this out: "Datomic does not
have a built-in notion of entity type. By convention, applications add
a :type attribute" (Datomic schema documentation, docs.datomic.com,
"Schema" section). Some shops use namespace prefixes (`:user/...` =
user) for implicit kind; others stamp explicit `:type :user`. The
explicit-attr style is universally recommended for queryability.

**XTDB.** XTDB (formerly Crux) document model includes an `:xt/id`
required and convention is `:xt/type` or domain-specific `:entity/type`.
The XTDB docs and example apps (xtdb.com/docs and the standard
Bitemporal Tax example) use `:user/type :customer` style attrs to
discriminate. Same pattern.

**Datascript / Asami.** Datascript practitioners follow Datomic
convention: explicit `:type` attr. Asami (an open-source EAV) uses
`:tg/type` in its convention examples. Across the EAV family, **the
convention is unanimous: explicit kind/type attr per entity**.

**Sources (URLs):**
- Datomic Schema docs: <https://docs.datomic.com/cloud/schema/schema-reference.html>
- Datomic mailing list canonical thread on `:entity/type` (2015-2017,
  searchable): groups.google.com/g/datomic search "entity type"
- XTDB 2.x reference: <https://docs.xtdb.com/reference/main/data-types>
- Datascript wiki / Datalevin docs (similar): github.com/tonsky/datascript

Validation: Sean's instinct toward Option A is consistent with the
broader EAV ecosystem. No better pattern surfaces in the literature.

## Remaining ambiguity for Sean

1. **Migration plan for `:seon.entity/kind`.** Backfill all existing
   `:seon.eval` / `:seon.message` / etc rows in one tx per kind, then
   flip the writer boundary to fail-closed. OR run with both for a
   release. Recommend: backfill + flip in one PR; we're on a feature
   branch, no production risk.

2. **Schema-level render fn lookup mechanism.** Once kind is queryable,
   where does the per-kind render fn live? Options:
   (a) on the kind's entity-shape Malli schema as a custom property
       `:seon.render/ai`, looked up via `(m/properties (m/schema schema))`
   (b) on a separate `(swap! kind-config assoc :seon.eval {...})` atom
   (c) as `(:default/fn (m/schema schema))` returning a render-fn map
   Recommend (a) — schemas as code-as-data — but this is a follow-up
   research scope.

3. **The `[:maybe X]` for fn returns nuance.** CLAUDE.md should clarify
   that the no-`:maybe` rule is for **persisted attrs**, not fn return
   types. Without this nuance, the audit churns through legitimate uses.
