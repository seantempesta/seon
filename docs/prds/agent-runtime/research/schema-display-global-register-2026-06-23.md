---
type: research
status: active
tags: [research, schema]
---

# Schema Display + Global Register + Dual-Render + Dependency-Coherent Ranking

## TL;DR

Seon already has 80% of the substrate for the owner's vision; the missing
20% is a thin layer of *enumerate + walk + render*. Concretely:

- **A global register already exists.** `seon.schema/*schemas` is a single
  process-global atom (`{key -> raw-malli-form}`) wired into Malli's
  default registry via `composite-registry`. Every `register!` call —
  scattered or not — lands in that ONE atom. "Consolidate into a global
  register" is therefore not a migration; it's **`registered-schemas`
  already returns the whole thing.** The scattered `register!` calls are
  the *write* surface; the atom is the *read/enumerate* surface. Keep
  both. (`src/seon/schema.cljc:39`, `:567`.)

- **Dependency walking is already half-built.** The Malli→datahike bridge
  (`seon.db.internal/resolve-malli-form`,
  `src/seon/db/internal.cljs:147`) already follows keyword indirections
  (`:seon.agent/id` → `:seon.db/id` → `[:string {:min 14 :max 14}]`).
  That same traversal generalizes into a **transitive dependency closure**
  for colocation.

- **Dual-render is the house pattern, not a new mechanism.** Schemas are
  ALREADY entities (`:seon.schema` kind) with `:seon.render/ai` →
  `seon.handlers.schema/render-ai` (one-line text) and `:seon.render/html`
  → `seon.handlers.schema/render-html` (a hiccup card). A *registry-wide*
  view is a new section fn (`schema-catalog`) that loops these per-schema
  renderers — NOT a new rendering engine.

- **The compact LLM format should BE the source.** Per the system's
  north-star (whole context = eval'able Clojure), the text rendering is
  the `(register! ::k <form>)` forms themselves, grouped by namespace
  under `;;` headers — they round-trip, they're copy-paste-runnable, and
  the agent already reads thousands of them. Gemini independently landed
  on the same recommendation (`register-all!` block per namespace).

- **Ranked + coherent = closure-expand each retrieved root.** Embedding
  retrieval (the `seon.embed/search` wire → Proximum HNSW) returns top-k
  schema keys; expand each to its dependency closure, merge, topo-sort (or
  colocate refs immediately after their referencer), drop lowest-ranked
  *roots* first when over budget but NEVER drop a kept root's dependency.

The rest of this doc gives the registry mechanics with code, a concrete
design for (a)-(e), and preserves the raw Gemini answer in an appendix.

> **SUPERSEDED in part by the settled decision (see
> `context-as-living-system-prd-2026-06-23.md` §D1).** This report's framing —
> a *registry-wide catalog decoupled from the namespace blocks*, with the
> recommendation in §5 to "drop schemas from the ns block, single catalog
> source" — is the OPPOSITE of what was settled. The settled decision is:
> schemas are **COLOCATED** inside each namespace block as real `register!`
> forms, rendered **deps-before-dependents (topo order) ABOVE the functions
> that reference them**, and the repetition across blocks is a **FEATURE**
> (at high context, long-range *distance* degrades comprehension more than
> volume, so keeping every reference local is the win). The mechanics this
> report nails down — `*schemas` enumeration, `dep-closure`, `topo-order`,
> the ranked-coherent packer — are all still correct and reused; only the
> "put it all in one global catalog and drop it from the ns block"
> *presentation recommendation* is superseded. Read the per-section notes
> below for exactly which paragraphs are overridden.

---

## 1. How Malli's registry + form/walk works, and what Seon does today

### 1.1 The global register already is the atom

`src/seon/schema.cljc`:

```clojure
(defonce ^:private *schemas (atom {}))           ; line 39 — THE global register

(defn relink-registry! []                        ; line 41
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)                           ; built-ins: :string :int :map …
    (mr/mutable-registry *schemas))))             ; seon's mutable atom
```

`malli.registry/mutable-registry` wraps the atom in the `Registry`
protocol; `composite-registry` chains it after the built-ins so a lookup
of `:seon.db/id` resolves through the atom and `:string` through the
built-ins. `set-default-registry!` makes this the process-wide default so
`(m/schema :seon.db/id)` and `:malli/schema` metadata both see seon's
attrs. (Seon must *re-call* `relink-registry!` after bootstrap evals
because the self-host compiler re-runs Malli's load-time
`set-default-registry!` side effect — see the long docstring at
`schema.cljc:41-60`. This is a CLJS-pod quirk, not relevant to the display
design, but it confirms `*schemas` is the durable source of truth that
survives the relink.)

**Enumerating the whole register** is already a public fn:

```clojure
(schema/registered-schemas)   ; => {:seon.db/id [:string {:min 14 :max 14}] …}  (schema.cljc:567)
(schema/current-keys)         ; => #{:seon.db/id :seon.agent/id …}              (schema.cljc:446)
(schema/schema-definition k)  ; => raw form for one key                          (schema.cljc:579)
(schema/schemas-in-namespace "seon.agent")  ; => {k form} filtered by ns        (schema.cljc:586)
```

Because the `Registry` protocol is opaque to enumeration (you can't list
keys from a compiled registry object), **retaining `*schemas` is what makes
the system enumerable** — and Seon already does. The owner's goal #2
("consolidate into a global register that can be enumerated") is *met by
the existing atom*; the work is presentation, not consolidation.

### 1.2 Raw form vs. compiled schema

| | Raw form | Compiled schema |
|---|---|---|
| Type | Clojure data (`[:string {:min 14}]`, keyword) | `Schema` object (`m/schema`) |
| Get it | `(schema/schema-definition k)` reads `*schemas` | `(m/schema k)` |
| Back to data | already data | `(m/form compiled)` |
| Follow a ref | it's just a keyword | `(m/deref compiled)` |

For **display** we want raw forms, not compiled objects: forms are
data, tiny, serialize/tokenize cleanly, and round-trip through `register!`.
`*schemas` stores raw forms (the `register!` arg verbatim, modulo the
entity-id-attr rewrite at `schema.cljc:437`). So the display layer reads
`*schemas` directly and never needs `m/schema`/`m/form` round-tripping.

**Caveat — built-ins pr-str opaquely.** A few seon attrs are stored as
`m/-simple-schema` *objects*, not forms: `:inst` (`schema.cljc:74`),
`:seon.flow/dynamic` (`:83`). `seon.handlers.schema/shape-type` already
handles this: `(cond (keyword? shape) shape (vector? shape) (first shape)
:else :any)`. The display layer reuses that guard.

### 1.3 Dependency following already exists in the bridge

`seon.db.internal/resolve-malli-form` (`src/seon/db/internal.cljs:147`)
follows keyword indirections through the registry until it hits a built-in
or a vector form. It already encodes the exact "this schema references
that registered schema" relation we need for colocation — it just stops at
the first concrete type (it's resolving to a datahike valueType, not
collecting deps). Generalizing it from "resolve to one type" to "collect
all referenced keys" is the new walk in §3d.

### 1.4 Schemas are ALREADY dual-rendered entities

Seon decomposes registered entity-kind `:map` schemas into `:seon.schema`
DB rows (`schema.cljc:502 entity-schema-tx-data`, seeded at boot by
`seon.client`). Each `:seon.schema` row renders via two handlers:

`src/seon/handlers/schema.cljs`:

```clojure
(defn render-ai  [{:seon.render/keys [entity]}]   ; one-line text for the LLM
  {:seon.render/ai (str "[schema " (pr-str k) "]  :shape " shape-text)})

(defn render-html [{:seon.render/keys [entity]}]  ; a hiccup card for the human
  {:seon.render/hiccup [:div … [:pre [:code.language-clojure shape-text]]]})
```

And `seon.ctx/schema-block-ai` (`src/seon/ctx.cljs:1160`) already renders a
schema inside the per-namespace `<namespaces>` block:

```clojure
(defn- schema-block-ai [{:seon.schema/keys [key source]}]
  (let [shape (try (schema/schema-definition key) (catch :default _ nil))
        form  (cond shape (clip (pr-str shape) 200) …)]
    (str "[schema " (pr-str key) "]  " form)))
```

**So the dual-render surface is solved per-schema.** What's missing is a
*registry-wide* catalog view (all schemas, or a ranked subset) rather than
the current "only the schemas in whitelisted namespaces, inlined in their
ns block." Today the agent sees schemas only for namespaces that pass
`seon.ctx.namespaces/full-source-ns?` (`src/seon/ctx/namespaces.cljs`) —
the whitelist the owner wants to escape.

---

## 2. The gap, precisely

The owner wants ALL schemas visible compactly, decoupled from the
namespace-source whitelist. Today:

- **Schema display is coupled to namespace display.** Schemas render only
  inside `render-one-ns-ai` (`ctx.cljs:1186`), and only for full-source
  whitelisted namespaces. A non-whitelisted ns shows neither its source
  NOR its schemas. So an agent cannot see `:seon.trading/*` attr shapes
  unless `seon.trading` is whitelisted for full source — an all-or-nothing
  coupling.
- **No registry-wide compact view.** There's no "here are all ~500 attr
  shapes" section. `registered-schemas` exists but nothing renders it.
- **No dependency closure.** A schema referencing `:seon.db/ref` shows the
  keyword but not what `:seon.db/ref` *is*, unless `seon.db` also happens
  to be whitelisted. For a *ranked subset* this breaks entirely.

The design below adds one read-only layer — **enumerate → (optionally
rank) → closure-expand → render (text | html)** — that is independent of
the source whitelist.

---

## 3. Design

### (a) A single global / enumerable schema register

**No new registry. Formalize the existing atom as the enumeration API.**
`*schemas` already holds everything. Add three thin public fns to
`seon.schema` (the ns that owns the atom — no new ns, no parallel path):

```clojure
;; seon.schema — enumeration helpers (the "global register" view)

(defn all-schemas
  "Every registered attr as {key raw-form}, EXCLUDING the opaque
   built-in IntoSchema objects (:inst, :seon.flow/dynamic) which don't
   pr-str as data. Display callers want forms, not compiled objects."
  []
  (into (sorted-map)
        (filter (fn [[_ v]] (or (keyword? v) (vector? v)))
                @*schemas)))

(defn schemas-by-namespace
  "{ns-string {key form}} — the natural grouping unit for the text view."
  []
  (->> (all-schemas)
       (group-by (fn [[k _]] (namespace k)))
       (into (sorted-map)
             (map (fn [[ns kvs]] [ns (into (sorted-map) kvs)])))))
```

These are pure reads of the existing atom. The scattered `register!`
calls stay exactly as-is (write surface); this is the read/enumerate
surface the owner asked for. **"Do away with scattered register! being
visible everywhere"** = the agent no longer needs to *find* the register!
calls in source; it reads the enumerated catalog instead. The calls remain
inline (colocation is a feature — schema lives with its data per CLAUDE.md)
but are no longer the *discovery* mechanism.

> **SUPERSEDED (§D1):** the settled decision is that the COLOCATED `register!`
> forms ARE the agent's read surface — the render emits them, deps-first,
> inside each namespace block. The enumeration helpers (`all-schemas`,
> `schemas-by-namespace`, `dep-closure`, `topo-order`) are still built and
> used; they are what *drives* the colocated render (look up a fn's
> `:malli/schema`, close over deps, topo-sort, emit above the fn). What is
> overridden is the idea that the catalog *replaces* the inline forms as the
> discovery mechanism — colocated forms are the discovery mechanism.

### (b) Compact TEXT rendering for the LLM

**Format: the `register!` forms themselves, grouped by namespace under
`;;` headers.** This satisfies the north-star (whole context = eval'able
Clojure) — the block is valid Clojure the agent can copy into a REPL tool
and run, and it round-trips with zero ambiguity. Gemini independently
recommended the same (grouped `register-all!` per ns; see Appendix §C).

Recommended rendering (`seon.ctx` or a new `seon.ctx.schemas`, a section fn
— reactive-context pattern, renders from the live registry each turn):

```clojure
(defn schema-catalog-text
  "Compact eval'able text of registered schemas, grouped by namespace.
   `ks` (optional) restricts to a key set (the ranked-subset path);
   default = all. Each ns is a `;; ── ns ──` header + one register!
   line per attr. Entity :map schemas print ref-only (entries reference
   attr keywords, never inline their shapes — those appear as their own
   lines), keeping a 15-entry map ~as cheap as 15 scalar lines but
   de-duplicated."
  ([] (schema-catalog-text (keys (schema/all-schemas))))
  ([ks]
   (let [by-ns (->> (select-keys (schema/all-schemas) ks)
                    (group-by (fn [[k _]] (namespace k)))
                    (sort-by key))]
     (str/join "\n\n"
       (for [[ns kvs] by-ns]
         (str ";; ── " ns " ──\n"
              (str/join "\n"
                (for [[k form] (sort-by key kvs)]
                  (str "(register! " (pr-str k) " " (pr-str form) ")")))))))))
```

Example output (real seon attrs):

```clojure
;; ── seon.db ──
;; ORDER IS LOAD-BEARING: a referenced schema appears BEFORE the schema that
;; references it (deps before dependents, topo order). :seon.db/ref references
;; :seon.db/lookup-ref-value, so lookup-ref-value is registered FIRST. This
;; matches the real source (schema.cljc:104 then :121).
(register! :seon.db/id [:string {:min 14 :max 14}])
(register! :seon.db/lookup-ref-value [:or :string :uuid :keyword :int])
(register! :seon.db/ref [:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]])

;; ── seon.agent ──
;; Scalars first; the entity :map LAST, so every keyword it references is
;; already defined above it (:seon.agent/id, /state, /purpose).
(register! :seon.agent/id [:and {:seon.db/identity true} :seon.db/id])
(register! :seon.agent/state [:enum :idle :active :waiting :completed :terminated])
(register! :seon.agent/purpose :string)
(register! :seon.agent
  [:map {:seon.db/entity true}
   [:seon.agent/id :seon.agent/id]
   [:seon.agent/state :seon.agent/state]
   [:seon.agent/purpose {:optional true} :seon.agent/purpose]])
```

**Why forms over a bare EDN map** (`{:k form …}`): the EDN map is ~10%
fewer tokens but loses the eval'ability invariant AND reads as "data the
agent inspects" rather than "the live definitions." The whole-context
discipline says every block should be runnable; `register!` forms are. The
token delta is small because the dominant cost is the forms, which are
identical in both. **Scalars** print as one short line; **entity maps**
print ref-only (entries are bare keywords → the referenced shapes appear
once, as their own lines in their own ns). This is the key compactness
lever: a `:map` with 15 entries costs ~15 keyword-pairs, not 15 inline
shapes, and the shapes are de-duplicated across every map that uses them.

**Token budget.** ~500 attrs × ~12 tokens/line ≈ 6k tokens for the WHOLE
registry — already well under the budget that the >100k-token full-source
forces a whitelist for. So the all-schemas text view is affordable to show
in full; the ranked-subset path (§e) is for when you also want docstrings
or want to keep the schema budget tiny. **Recommendation: show ALL schemas
compactly by default** (it fits), and reserve ranking for richer per-schema
detail (docstrings, sample values, generators).

### (c) Hiccup / HTML rendering for the `:seon.render/html` route

The per-schema html already exists (`seon.handlers.schema/render-html` — a
phosphor-terminal card with a `language-clojure` highlighted `<pre>`). The
registry-wide html view is a section fn that loops it:

```clojure
;; seon.ctx.schemas (html twin) — registry-wide catalog card
(defn schema-catalog-html
  "Hiccup for the schema catalog. `ks` optional (ranked subset).
   Groups by namespace; each group is a collapsible block; each schema
   reuses seon.handlers.schema/render-html (ONE per-schema renderer,
   looped — no second html path). Referenced attr keywords become
   in-page anchor links (#seon-schema-<key>) so a human can jump to a
   ref's definition — the colocation idea, expressed as navigation."
  [db ks]
  (let [by-ns (schemas-by-namespace-for ks)]
    [:div {:class "flex flex-col gap-4"}
     (for [[ns kvs] by-ns]
       [:section {:class "flex flex-col gap-1"}
        [:h3 {:class "text-xs font-mono text-amber-400 border-b border-base-700 pb-1"}
         ns]
        (for [[k _] kvs]
          (:seon.render/hiccup
            (h-schema/render-html {:seon.render/entity {:seon.schema/key k}})))])]))
```

Key reuse rules (consistency / "turtles all the way down"):

- **One per-schema renderer.** `render-html` stays the single source of a
  schema card; the catalog *loops* it. No parallel html path.
- **Cross-links for colocation.** `render-html` already emits a stable
  anchor `id` per schema (`seon-schema-<munged-key>`,
  `handlers/schema.cljs:62`). Extend the shape `<pre>` so a referenced
  registered keyword (e.g. `:seon.db/ref`) renders as `<a
  href="#seon-schema-seon_db_ref">`. The HTML equivalent of "drag in the
  dependency" is "make it one click away." (Gemini's `render-schema-link`
  in Appendix §D is exactly this; adapt its inline styles to seon's
  Tailwind phosphor classes — `text-amber-300`, not `#2563eb`.)
- **Theme.** Use the existing phosphor classes already in `render-html`
  (`bg-base-900`, `text-amber-400`, `text-text-100`), NOT the light-mode
  inline styles Gemini emitted.

### (d) Dependency-coherent colocation (the closure walk)

Generalize `resolve-malli-form` into a closure walker. Lives in
`seon.schema` (owns the atom) so it has no require cycle:

```clojure
;; seon.schema — dependency closure over the registry

(defn immediate-deps
  "The registered keywords a schema FORM references directly. Walks the
   raw form; collects any keyword that is itself a registered key.
   (Built-in heads like :string/:map/:enum are registered too, so
   filter to KEYS WE STORE AS DATA — i.e. seon-namespaced attrs and
   :seon.db/* — to avoid dragging in :string. Concretely: a key is a
   dep iff it's in *schemas AND its own form is a vector/keyword, never
   an opaque built-in.)"
  [form]
  (let [reg @*schemas]
    (into #{}
          (filter (fn [x]
                    (and (keyword? x)
                         (let [v (get reg x ::absent)]
                           (and (not= v ::absent)
                                (or (keyword? v) (vector? v))))))
            (tree-seq coll? seq form)))))   ; tree-seq walks nested vectors/maps

(defn dep-closure
  "Transitive closure: the input keys plus everything they reference,
   recursively. {key form}."
  [ks]
  (loop [todo (set ks), seen #{}]
    (if-let [k (first todo)]
      (if (seen k)
        (recur (disj todo k) seen)
        (let [form (get @*schemas k)
              deps (if form (immediate-deps form) #{})]
          (recur (into (disj todo k) deps) (conj seen k))))
      (select-keys @*schemas seen))))

(defn topo-order
  "Order a {key form} closure so a referenced schema appears BEFORE any
   schema that references it (leaf scalars first, entity maps last).
   Stable: ties broken by key string. Falls back to plain sort on a
   cycle (Malli refs can be mutually recursive via :ref/:schema; we
   don't error, we just emit in sorted order — display, not compile)."
  [closure]
  (let [adj (into {} (map (fn [[k form]]
                            [k (disj (immediate-deps form) k)])
                          closure))]
    (loop [order [], placed #{}, pending (set (keys closure))]
      (if (empty? pending)
        (mapv (fn [k] [k (closure k)]) order)
        (let [ready (->> pending
                         (filter #(every? (some-fn placed (complement pending))
                                          (adj %)))
                         sort)]
          (if (seq ready)
            (recur (into order ready) (into placed ready)
                   (reduce disj pending ready))
            ;; cycle — emit the rest sorted, don't loop forever
            (recur (into order (sort pending))
                   (into placed pending) #{})))))))
```

`tree-seq coll? seq` is the simplest correct walker for raw Malli forms
(nested vectors + the optional props map) — no `malli.core/walk`/compiled
schema needed, and it can't trip the recursive-seqex instrumentation issue
that bites compiled schemas in this codebase (noted at `render.cljs:55-65`).

**Two colocation strategies, both supported by the closure:**

1. **Topo-grouped (recommended for text):** `topo-order` the closure, then
   group by namespace for the `;; ── ns ──` headers. Referenced scalars
   (`:seon.db/id`, `:seon.db/ref`) naturally float to the top because
   nothing they reference is in the set. The agent reads dependencies
   before dependents.
2. **Inline-colocated (recommended for ranked subsets):** for each ranked
   root, immediately follow it with its (not-yet-emitted) closure under a
   `;; deps of <root>` sub-comment. Keeps a retrieved schema and its refs
   visually adjacent even when ranking scrambles namespaces.

### (e) Plugging into embedding-ranked retrieval (Proximum) while staying coherent

The retrieval side already exists: `seon.embed/search`
(`src/seon/embed.cljs:137`) is the pod's thin client over the
`knn-search` wire verb → the JVM wire-server's Proximum HNSW index
(`reference-code/proximum`). It returns ranked `{eid distance}` hits;
`search-pull` enriches them with pulled entities.

To rank *schemas*:

1. **Embed each schema once.** Per the embeddings PRD's
   attribute-anchored model (`register-embeddable!` any string attr), make
   `:seon.schema/source` (the `(register! …)` form string) — or a synthetic
   "key + docstring + shape" string — embeddable. Each `:seon.schema` row
   then gets a `:seon/embedding` vector in the single Proximum index.
   (The row already carries `:seon.schema/key` + `:seon.schema/source`;
   `src/seon/agent.cljs:213`.)
2. **Query.** `(embed/search {::query "how is an agent's lifecycle state
   stored" ::k 12 ::where '[[?e :seon.schema/key _]]})` → top-12 schema
   keys, ranked. The `:where` scopes the KNN to schema rows only.
3. **Closure-expand for coherence.** Feed the ranked keys to a
   budget-aware packer that NEVER emits a root without its full closure:

```clojure
;; seon.ctx.schemas — ranked + coherent + budgeted
(defn pack-ranked
  "ranked-keys: schema keys in similarity order (best first).
   budget: char budget (≈ tokens*4). Returns ordered [key form] pairs:
   as many top-ranked ROOTS as fit, each with its COMPLETE dep closure,
   topo-sorted. Over budget ⇒ drop the lowest-ranked root that doesn't
   fit; never drop a kept root's dependency."
  [ranked-keys budget]
  (loop [todo ranked-keys, chosen #{}]
    (if-let [k (first todo)]
      (let [merged   (into chosen (keys (schema/dep-closure #{k})))
            cost     (reduce + (map #(count (pr-str (schema/schema-definition %)))
                                    merged))]
        (recur (rest todo)
               (if (<= cost budget) merged chosen)))  ; skip k+its deps if over
      (schema/topo-order (select-keys (schema/all-schemas) chosen)))))
```

Notes on the packer:

- **Closure-before-budget** is the invariant: cost is always computed on
  the *merged closure*, so a root is admitted only if its whole closure
  fits. This is what keeps the displayed set self-contained — no shown
  schema references an undefined keyword. (Mirrors JSON-Schema `$ref`
  bundling and GraphQL SDL "include the types you reference"; Appendix §F.)
- **Dropping the lowest-ranked root first** falls out for free: we iterate
  best-first and skip a root only when *it* pushes over budget; later
  (lower-ranked, possibly smaller-closure) roots still get a chance.
- **De-dup is automatic** — `chosen` is a set; a scalar referenced by ten
  roots is emitted once. A high-fanout scalar (`:seon.db/id`) is paid for
  by the first root that needs it and free thereafter.
- **Token estimate.** The `char/4` heuristic is fine for a budget *gate*;
  if Seon later wants exactness, swap in the real tokenizer used elsewhere
  in `seon.ctx` (there's already a render-cap regime there — `eval-render-cap`,
  `result-body-render-cap`, `ctx.cljs:248`). Reuse that machinery rather
  than inventing a second budget concept.

**Coherence + ranking together:** retrieve top-k → `pack-ranked` →
`schema-catalog-text` (or `-html`) on the packed key set. The agent gets
the most-relevant schemas for its current task, each fully defined, under
a hard budget. When embeddings aren't wanted, pass `(keys (all-schemas))`
as the "ranked" list and the same path renders everything (closure
expansion is a no-op since the whole registry is present).

---

## 4. Build map (consolidate, don't fork)

All of this is additive read-only layering on existing mechanisms. No
`*-v2`, no parallel registry. Suggested units:

1. **`seon.schema`** — add `all-schemas`, `schemas-by-namespace`,
   `immediate-deps`, `dep-closure`, `topo-order`. Pure reads of `*schemas`.
   (The closure walk is the generalization of the existing
   `resolve-malli-form`; consider having the bridge call into it later to
   kill the duplication, but that's a follow-up, not this unit.)
2. **`seon.ctx.schemas`** (new section ns, sibling to `seon.ctx.namespaces`)
   — `schema-catalog-text` (LLM) + `schema-catalog-html` (human), plus
   `pack-ranked` for the ranked path. Reuses `seon.handlers.schema/render-html`
   for the per-card html; reuses `ctx.cljs` caps for budgeting.
3. **Wire the section in.** Add a `<schemas>` section to the agent prompt
   composer (decouple from `full-source-ns?` — schemas show for ALL
   namespaces now). Add the html twin to the inspector right pane (mirrors
   the existing section-twin pattern, `render.cljs:196`).
   > **SUPERSEDED (§D1):** do NOT wire a separate `<schemas>` catalog section
   > decoupled from the ns blocks. Instead, render each ns's schemas
   > COLOCATED inside that ns's block (deps-first, above its fns), and EXPAND
   > the `full-source-ns?` whitelist rather than escaping it. (Also: section
   > headers are comment-block `;; ── schemas ──`, not the XML `<schemas>`
   > tag — the whole context is eval'able Clojure per the FSM north star.)
   > The dep-closure/topo helpers from unit 1 still drive this; they just
   > feed the per-ns block instead of a global section.
4. **(Later) embeddable schemas** — `register-embeddable!` on
   `:seon.schema/source`; index `:seon.schema` rows in Proximum; flip the
   `<schemas>` section from "all" to "top-k for the current task" when the
   registry outgrows the budget.
   > **NOTE (§D1):** ranked retrieval still applies — but to which
   > *namespaces* / fn-sets to colocate, with each retrieved root still
   > closure-expanded. The `pack-ranked` invariant (never emit a root
   > without its full topo-ordered dep closure) is unchanged.

**Live proof to demand at each unit:** eval `(schema/all-schemas)` against
the live pod and read back the count; eval `(schema/dep-closure
#{:seon.agent})` and confirm it pulls in `:seon.agent/id`, `:seon.db/id`,
`:seon.agent/state`, etc.; fetch the inspector page and SEE the schema
cards; read the actual `<schemas>` text the agent receives (the
"flag-garbage / read the agent-facing output" rule) and confirm it's
legible eval'able Clojure, not token-optimized noise.

---

## 5. Open questions / smells surfaced

- **Built-in opacity.** `:inst` and `:seon.flow/dynamic` are stored as
  `m/-simple-schema` objects, not forms (`schema.cljc:74,83`). They
  pr-str as `:inst` / opaque — fine for display, but `all-schemas` must
  filter or special-case them (done above). Consider registering `:inst`
  as a *form* (`[:inst]`?) for uniformity — but Malli has no `:inst`
  vector form, so the object is unavoidable. Low priority; the guard
  handles it.
- **Coupling to remove.** `schema-block-ai` renders schemas ONLY inside
  whitelisted namespace blocks (`ctx.cljs:1205`). After this work, that
  inline rendering is redundant with the registry-wide `<schemas>` section
  — decide whether to keep schemas in the ns block (colocated with source,
  but duplicated) or drop them there and rely on the catalog. Recommend:
  drop from the ns block, single catalog source (don't show the same
  schema twice).
  > **SUPERSEDED (§D1) — this recommendation is REVERSED.** The settled
  > decision is the colocated path: KEEP schemas in each ns block (rendered
  > deps-before-dependents, above the fns that reference them) and EXPAND
  > the whitelist; do NOT drop them in favor of a single global catalog.
  > The duplication is intentional ("repetition is a FEATURE" — it removes
  > long-range lookup, the thing that degrades at high context). The
  > "single catalog" shrinks at most to an orphan-list of schemas no shown
  > ns references, or vanishes entirely as the whitelist grows. So fix
  > `schema-block-ai` to render the topo-ordered dep-closure, do not delete
  > it.
- **Cycle handling.** Malli `:ref`/`:schema`/`:recursion` can make the dep
  graph cyclic. `topo-order` degrades to sorted output on a cycle rather
  than throwing (display must never crash). Seon's current attr schemas
  appear acyclic (scalars → maps), but agent-authored schemas could
  introduce cycles — the fallback covers it.
- **`:seon.schema/ns` ref already links schema→ns.** The DB rows already
  carry a `:seon.schema/ns` ref to the owning `:seon.ns`
  (`agent.cljs:214`, populated in `client.cljs:1619`). The html catalog
  could group/link by that ref instead of re-deriving from the keyword
  namespace — but keyword-namespace grouping is simpler and needs no DB
  read. Use keyword grouping for the registry view; the `:seon.schema/ns`
  ref stays the *durability/reconstitution* link.

---

## Appendix A — Raw Gemini response (verbatim)

> Model: `gemini-3.5-flash` via `agy`. The CLI wrote the body to a brain
> file rather than stdout; reproduced here unedited. Prompt: the six-part
> question (A registry model, B walk/deps, C compact text, D hiccup/html,
> E ranked coherence, F prior art) is in
> `scratchpad/gemini-prompt.txt`.

```markdown
# Seon Schema Consolidation & Dual-Render Presentation Design

This document details the architectural design and Clojure implementation for managing, walking, and rendering Malli schemas in the Seon system. It covers two primary consumption surfaces:
1. **AI Agent Render (`:seon.render/ai`)**: Compact, token-efficient, eval'able Clojure syntax suited for LLM context windows.
2. **Human Web UI Render (`:seon.render/html`)**: Navigable, interactive Hiccup HTML with dependency cross-linking.

---

## A. Malli Registry Model

Malli organizes schemas via registries that implement the `malli.core/Registry` protocol. Understanding how these registries compose and compile forms is critical for establishing a global, queryable schema inventory.

### 1. Mutable and Composite Registries
*   **`malli.registry/mutable-registry`**: Wraps a mutable cell (usually a Clojure `atom` containing a map) in the registry protocol. It supports dynamic addition and deletion of schemas at runtime.
*   **`malli.registry/composite-registry`**: Composes multiple registries into a single lookup chain. When looking up a schema key, it queries each registry in the sequence.
*   **`malli.registry/set-default-registry!`**: Globally registers a registry instance as the default registry for the current process.

In `seon.schema`, these are composed as follows:
```clojure
(defn relink-registry! []
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)            ; Standard built-in schemas (:string, :int, etc.)
    (mr/mutable-registry *schemas)))) ; Custom Seon schemas atom
```

### 2. Enumerating the Registry
Because the `Registry` protocol is opaque, you cannot easily list keys directly from a compiled registry object. Therefore, **retaining a reference to the backing atom (`*schemas`) is essential**.

To enumerate every custom schema registered in the system as a `key -> form` map:
```clojure
(defn schema-inventory []
  @seon.schema/*schemas)
```

### 3. Raw Forms vs. Compiled Schemas
It is critical to distinguish between the declaration of a schema and its runtime compiled representation:

| Attribute | Raw Schema Form | Compiled Schema Object |
| :--- | :--- | :--- |
| **Type** | Clojure Data (Vectors, Keywords, Maps) | JVM/JS Object (implements `malli.core/Schema`) |
| **Example** | `[:string {:min 14 :max 14}]` | `#object[malli.core$into_schema$...]` |
| **Creation** | Written inline or read from EDN | Created via `(m/schema form)` or `(m/schema k)` |
| **Overhead** | Minimal; easily serialized & tokenized | Heavy; holds validation fns, compilers, generators |
| **Extraction** | Already data | Accessible via `(m/form compiled-schema)` |
| **Resolution** | Static references are just keywords | `(m/deref compiled-schema)` extracts referenced shapes |

```clojure
(require '[malli.core :as m])

;; Raw form (Data)
(def raw-id-form [:string {:min 14 :max 14}])

;; Compiled Schema (Object)
(def compiled-schema (m/schema raw-id-form))

;; Extract form back from compiled object
(m/form compiled-schema)
;; => [:string {:min 14 :max 14}]

;; Dereferencing keyword references
(def ref-schema (m/schema :seon.db/id))
(m/deref ref-schema)
;; => [:string {:min 14 :max 14}]
```

---

## B. Schema Form / Walk / Dependencies

To support dependency-coherent presentation, we must trace which schemas refer to other registered schemas. For example, if a schema references `:seon.db/id`, it depends on that schema.

We can walk a schema form or compiled object to build a **Transitive Dependency Closure** and sort it **Topologically** (ensuring referenced items appear before referencing items).

### Implementation: Dependency Walker & Topological Sort

```clojure
(ns seon.schema.walk
  (:require [clojure.walk :as walk]
            [malli.core :as m]
            [seon.schema :as schema]))

(defn find-immediate-deps
  "Walks a schema's raw form to find all registered schema keywords it references."
  [form]
  (let [registry-keys (schema/current-keys)
        deps (atom #{})]
    (walk/postwalk
     (fn [x]
       (when (and (keyword? x) (contains? registry-keys x))
         (swap! deps conj x))
       x)
     form)
    @deps))

(defn transitive-closure
  "Computes the transitive dependency closure for a set of schema keys.
   Returns a map of {schema-key raw-form} containing all dependencies."
  [keys]
  (loop [to-process (set keys)
         visited #{}]
    (if (empty? to-process)
      (select-keys (schema/registered-schemas) visited)
      (let [current (first to-process)
            remaining (disj to-process current)]
        (if (contains? visited current)
          (recur remaining visited)
          (let [form (schema/schema-definition current)
                deps (if form (find-immediate-deps form) #{})]
            (recur (into remaining deps) (conj visited current))))))))

(defn topological-sort
  "Sorts a dependency map of {key form} topologically.
   Returns a vector of [key form] pairs where dependencies appear before dependents."
  [dependency-map]
  (let [adjacency-list (into {}
                             (map (fn [[k form]]
                                    [k (disj (find-immediate-deps form) k)])
                                  dependency-map))
        visited (atom #{})
        temp-mark (atom #{})
        sorted (atom [])]
    (letfn [(visit [node]
              (when-not (contains? @visited node)
                (when (contains? @temp-mark node)
                  (throw (ex-info "Circular dependency detected" {:node node})))
                (swap! temp-mark conj node)
                (doseq [dep (get adjacency-list node)]
                  ;; Only visit if it is part of the dependency map
                  (when (contains? dependency-map dep)
                    (visit dep)))
                (swap! temp-mark disj node)
                (swap! visited conj node)
                (swap! sorted conj node)))]
      (doseq [node (keys dependency-map)]
        (visit node))
      (mapv (fn [k] [k (get dependency-map k)]) @sorted))))
```

---

## C. Compact Textual Representation for an LLM

For AI agent context windows, the schema representation must be **token-efficient**, **legible**, and preferably **eval'able Clojure** so that the agent can read and run it in REPL tools.

### 1. Formatting Comparison

Let's compare representations for a set of registered attributes:

#### Option (a): Raw Individual `register!` Forms
```clojure
(schema/register! :seon.db/id [:string {:min 14 :max 14}])
(schema/register! :seon.agent/id [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent/state [:enum :idle :active :waiting :completed :terminated])
```
*   **Tokens (approx)**: ~70 tokens.
*   **Legibility**: High.
*   **Eval'ability**: Excellent (individually copy-pasteable).
*   **Efficiency**: Low (repeats namespace prefix and `register!` for every single attribute).

#### Option (b): Single `register-all!` Call
```clojure
(schema/register-all!
  :seon.db/id [:string {:min 14 :max 14}]
  :seon.agent/id [:and {:seon.db/identity true} :seon.db/id]
  :seon.agent/state [:enum :idle :active :waiting :completed :terminated])
```
*   **Tokens (approx)**: ~50 tokens (~30% savings).
*   **Legibility**: High.
*   **Eval'ability**: Excellent.
*   **Efficiency**: Medium-High.

#### Option (c): Pretty-Printed EDN Data Map (inside comments or metadata)
```clojure
{:seon.db/id [:string {:min 14 :max 14}]
 :seon.agent/id [:and {:seon.db/identity true} :seon.db/id]
 :seon.agent/state [:enum :idle :active :waiting :completed :terminated]}
```
*   **Tokens (approx)**: ~40 tokens.
*   **Legibility**: High for data; hides the API side effects.
*   **Eval'ability**: Requires wrapping with code to execute/register.
*   **Efficiency**: Highest.

> [!TIP]
> **Recommendation**: Group schemas by namespace and output them as a single `schema/register-all!` block per namespace. This provides the ideal blend of token efficiency, layout organization, and direct eval'ability.

### 2. Large Map Entities vs. Scalars
To keep large entity maps (e.g., 15+ attributes) compact:
1.  Define scalar attributes individually first.
2.  In the `:map` entity definition, **reference the attribute keywords directly** rather than nesting their full declarations. This keeps the entity schema self-describing and brief.

```clojure
;; Scalar attributes
(schema/register-all!
  :seon.agent/id [:and {:seon.db/identity true} :seon.db/id]
  :seon.agent/purpose :string
  :seon.agent/state [:enum :idle :active :waiting :completed :terminated]
  :seon.agent/wake :seon.db/id)

;; Entity Map Schema (highly compact ref-only representation)
(schema/register! :seon.agent
  [:map {:seon.db/entity true}
   [:seon.agent/id :seon.agent/id]
   [:seon.agent/purpose {:optional true} :seon.agent/purpose]
   [:seon.agent/state :seon.agent/state]
   [:seon.agent/wake {:optional true} :seon.agent/wake]])
```

---

## D. Rendering Malli Schema as HTML/Hiccup

A human viewer requires interactive elements, such as schema documentation parsing, visual cues for constraints (`:optional`, `:seon.db/identity`), and clickable anchor links to jump between schema definitions.

### Implementation: Hiccup Schema Walker

```clojure
(ns seon.schema.render.html
  (:require [clojure.walk :as walk]
            [seon.schema :as schema]))

(declare render-schema-node)

(defn- render-properties [props]
  (when (seq props)
    [:span.schema-properties
     {:style "font-size: 0.85em; color: #666; margin-left: 8px;"}
     (pr-str props)]))

(defn render-schema-link [k]
  (if (schema/registered? k)
    [:a.schema-link
     {:href (str "#" (namespace k) "/" (name k))
      :style "color: #2563eb; text-decoration: none; font-weight: 500;"}
     (str k)]
    [:span.schema-primitive {:style "color: #0f172a;"} (str k)]))

(defn render-map-entry [entry]
  ;; entry: [key properties? schema-form]
  (let [k (first entry)
        has-props? (map? (second entry))
        props (when has-props? (second entry))
        val-schema (if has-props? (nth entry 2) (second entry))
        optional? (:optional props)]
    [:tr {:style "border-bottom: 1px solid #e2e8f0;"}
     [:td {:style "padding: 8px; font-family: monospace; font-weight: bold;"}
      (render-schema-link k)]
     [:td {:style "padding: 8px;"}
      (if optional?
        [:span {:style "color: #94a3b8; font-size: 0.85em;"} "optional"]
        [:span {:style "color: #ef4444; font-size: 0.85em; font-weight: bold;"} "required"])]
     [:td {:style "padding: 8px; font-family: monospace;"}
      (render-schema-node val-schema)]]))

(defn render-schema-node [form]
  (cond
    (keyword? form)
    (render-schema-link form)

    (vector? form)
    (let [head (first form)
          body (rest form)
          props (when (map? (first body)) (first body))
          children (if props (rest body) body)]
      (case head
        :map
        [:div.schema-map {:style "margin-top: 4px;"}
         [:span.schema-head {:style "font-weight: bold; color: #0284c7;"} ":map"]
         (render-properties props)
         [:table {:style "width: 100%; border-collapse: collapse; margin-top: 8px; border: 1px solid #e2e8f0; font-size: 0.9em;"}
          [:thead {:style "background-color: #f8fafc;"}
           [:tr
            [:th {:style "padding: 8px; text-align: left; border-bottom: 1px solid #e2e8f0;"} "Attribute"]
            [:th {:style "padding: 8px; text-align: left; border-bottom: 1px solid #e2e8f0;"} "Requirement"]
            [:th {:style "padding: 8px; text-align: left; border-bottom: 1px solid #e2e8f0;"} "Schema"]]]
          [:tbody
           (map render-map-entry children)]]]

        :vector
        [:span.schema-vector
         [:span {:style "color: #0284c7;"} "[:vector "]
         (render-schema-node (first children))
         [:span {:style "color: #0284c7;"} "]"]]

        ;; Default vector handler
        (into [:span.schema-nested {:style "font-family: monospace;"}
               [:span {:style "color: #d97706;"} (str "[" head " ")]]
              (concat
               (when props [(pr-str props) " "])
               (interpose " " (map render-schema-node children))
               ["]"]))))

    :else
    [:span.schema-val (pr-str form)]))

(defn schema->hiccup
  "Generates a complete Hiccup card rendering for a registered schema."
  [k form]
  [:div.schema-card
   {:id (str (namespace k) "/" (name k))
    :style "border: 1px solid #cbd5e1; border-radius: 8px; padding: 16px; margin-bottom: 24px; background-color: #ffffff; box-shadow: 0 1px 3px rgba(0,0,0,0.1);"}
   [:h3 {:style "margin-top: 0; font-family: monospace; border-bottom: 2px solid #3b82f6; padding-bottom: 8px;"}
    (str k)]
   [:div.schema-body {:style "margin-top: 12px;"}
    (render-schema-node form)]])
```

---

## E. Dependency-Coherent Ranked Presentation

When embedding similarity retrieval (e.g., via HNSW/Proximum) ranks schemas to fit a token budget, retrieving isolated leaves results in broken keyword references. We need a budget-aware expansion algorithm.

### Token Budgeting & Coherence Expansion Algorithm

1.  **Retrieve**: Get the ranked list of matching schema keys from the similarity search.
2.  **Iterative Packing**: Maintain a set of `selected-roots` and calculate the cost of their unified topological dependency closure.
3.  **Validate Budget**: If adding the next ranked root (along with its unique transitive dependencies) exceeds the token budget:
    *   Skip this root (discard it and its dependencies).
    *   Continue trying lower-ranked roots (which might have smaller closures).
    *   Never keep a root without all of its dependencies.
4.  **Order**: Sort the final selection topologically, grouped by namespace, and render.

```clojure
(defn calculate-token-cost
  "Estimate token cost of a schema registration map.
   Simplistic token count heuristic: 1 token ≈ 4 characters."
  [schema-map]
  (let [edn-string (pr-str schema-map)]
    (quot (count edn-string) 4)))

(defn pack-coherent-schemas
  "Given ranked retrieved schema keys and a token budget, returns a
   topologically sorted list of [key form] pairs containing as many top
   ranks as possible while guaranteeing complete dependency closures."
  [ranked-keys token-budget]
  (loop [remaining-ranks ranked-keys
         included-roots #{}
         current-closure {}]
    (if (empty? remaining-ranks)
      (topological-sort current-closure)
      (let [candidate-root (first remaining-ranks)
            next-ranks (rest remaining-ranks)]
        (if (contains? (set (keys current-closure)) candidate-root)
          ;; Candidate is already included as a dependency of a prior root
          (recur next-ranks (conj included-roots candidate-root) current-closure)
          ;; Candidate needs expansion
          (let [candidate-closure (transitive-closure #{candidate-root})
                merged-closure (merge current-closure candidate-closure)
                cost (calculate-token-cost merged-closure)]
            (if (<= cost token-budget)
              (recur next-ranks (conj included-roots candidate-root) merged-closure)
              ;; Over budget: skip this candidate root and try to pack smaller remaining ones
              (recur next-ranks included-roots current-closure))))))))
```

---

## F. Prior Art

Seon's schema consolidation can borrow pattern ideas from established ecosystem standards:

### 1. GraphQL Schema Definition Language (SDL)
*   **Borrow**: GraphQL uses a declarative text format (SDL) to show types, fields, and descriptions. We replicate this by printing `register-all!` forms. This is highly compressed compared to presenting database migrations or dynamic objects.

### 2. JSON Schema Bundling & Ref Resolution
*   **Borrow**: Tools like JSON Schema bundle remote `$ref` values into local definitions (using definitions/components). Our `transitive-closure` traversal does exactly this: inline keyword references are unpacked and appended to the output document.

### 3. Malli Instrumentation & Errors
*   **Borrow**: `malli.dev/pretty` and `malli.error/humanize` are used to format error structures. Seon extends this during `register!` checking by executing dynamic compilations upfront to fail fast on malformed entries.
```

## Appendix B — Seon source references

| Concern | File:line |
|---|---|
| Global register atom | `src/seon/schema.cljc:39` |
| Registry relink (composite) | `src/seon/schema.cljc:41` |
| Enumerate all / keys / one | `src/seon/schema.cljc:567,446,579` |
| Per-namespace filter | `src/seon/schema.cljc:586` |
| Entity → :seon.schema decomposition | `src/seon/schema.cljc:502` |
| Dep-following resolver (bridge) | `src/seon/db/internal.cljs:147` |
| Malli→datahike attr (uses resolver) | `src/seon/db/internal.cljs:286` |
| Per-schema render-ai / render-html | `src/seon/handlers/schema.cljs` |
| Inline schema text in ns block | `src/seon/ctx.cljs:1160` |
| Namespace whitelist (the coupling to escape) | `src/seon/ctx/namespaces.cljs` |
| `:seon.schema` entity-kind + render syms | `src/seon/agent.cljs:288` |
| `:seon.schema/ns` ref + key/source attrs | `src/seon/agent.cljs:213` |
| Render slots + dual-render dispatch | `src/seon/render.cljs` |
| Embedding search wire client | `src/seon/embed.cljs:137` |
| Render caps / budget machinery | `src/seon/ctx.cljs:248` |
| Proximum HNSW index (JVM) | `reference-code/proximum` |
