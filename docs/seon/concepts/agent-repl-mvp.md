---
type: concept
status: draft
tags: [concept, agent, cljs]
---

# Agent REPL MVP — Spec

The shape of the LLM-facing REPL: the data model, the eval pipeline, the
rendering layer, and the defaults that make the loop work out of the
box. Reference for `seon.repl`, `seon.render`, `seon.eval` namespace
work.

## Goal

Deliver an MVP where an LLM agent can:

- Eval one or many Clojure forms per turn
- See a structured, always-current view of its world after each eval
- Write functions, schemas, and tests that accrete in the database
- Curate **any namespace** in the project — not just the agent's own — by
  adding, modifying, and forgetting entities, organized around whatever
  mission the user assigned
- Customize how the rendered context looks, with a guaranteed fallback
- Restart the system and have its persistent work replay in the right order

The defaults must be **simple to explain, simple to understand, simple to
use**. Power comes from the agent extending the system, not from the
defaults being clever.

## Agent + namespace lifecycle

An agent has an identity. The DB stores a session reference under that
identity at `seon.agent.<agent-id>`. That's where the agent **starts**,
but the agent's job is to grow the system: define new namespaces around
whatever data their mission requires (`seon.trading.signals`,
`seon.notes.calendar`, `seon.email.inbox` — whatever the work calls for),
populate them with schemas / fns / tests, and curate them over time.

There is no ownership boundary. Any agent can `(in-ns 'seon.foo)` and
work there. Naming hygiene is a social convention enforced through
rendering (warnings on cross-namespace edits, etc.), not through ACL.

## Mental model

```
┌──────────────────────────────────────────────────────────────────┐
│  REPL conversation = data exchange                               │
│                                                                  │
│  agent → {forms-source}                                          │
│  pod  → {rendered context, fully refreshed}                      │
│                                                                  │
│  The reply IS the context. No separate "eval envelope" type.     │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  Database = the program                                          │
│                                                                  │
│  Persistent entities (fns, schemas, tests, requires) accrete     │
│  attribute changes. Replay rebuilds the runtime from them.       │
│                                                                  │
│  Eval log records what was typed and what came back. Never       │
│  replayed; consumed by the renderer for scrollback context.      │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  Rendering = `render-ai` dispatch, applied recursively           │
│                                                                  │
│  section entities → section fns → entity-shaped maps             │
│        ↓                                                         │
│  every map → render-ai (specificity dispatch) → string           │
│                                                                  │
│  Sections are entities; section fns are arbitrary Clojure.       │
│  Agent extends by writing more-specific render-ai fns.           │
└──────────────────────────────────────────────────────────────────┘

```

## Data model

Single database. Two logical layers.

### Persistent entities — "the things that stick"

Built on the `:seon.fn/*` / `:seon.schema/*` / `:seon.test/*` taxonomy.

```clojure
;; Functions
::seon.fn/sym         [:string {:seon.db/identity true}]    ; "seon.trading/analyze"
::seon.fn/ns          :keyword
::seon.fn/source      :string                                ; current source text

;; Schemas
::seon.schema/key     [:keyword {:seon.db/identity true}]
::seon.schema/source  :string                                ; full register! call text

;; Tests
::seon.test/sym             [:string {:seon.db/identity true}]
::seon.test/target          :seon.db/ref                     ; → :seon.fn
::seon.test/source          :string
::seon.test/last-passed-at  :inst {:optional true}          ; most recent successful run
::seon.test/last-failed-at  :inst {:optional true}          ; most recent failed run
::seon.test/last-failure    :string {:optional true}        ; ex-message of most recent failure

;; Namespaces (one entity per agent-defined or substrate ns)
::seon.ns/name    [:keyword {:seon.db/identity true}]        ; :seon.trading.signals
::seon.ns/source  :string                                    ; "(ns seon.trading.signals (:require [seon.db :as db]))"

```

A namespace is one entity carrying the full `(ns …)` form as source —
that includes the `:require` clause and anything else inside the ns
declaration. Replaying the entity = evaluating the source = the
namespace and its dependencies become available in one step. There
is no separate `:seon.require/*` entity; per-clause storage would
duplicate what `(ns …)` already structures.

**The database IS the system after first boot.** The seon substrate is
compiled CLJS that knows how to interpret what's in the DB and how to
seed it. On a brand-new DB the substrate transacts an ordered vector of
entity maps (the bootstrap data — see "First boot" below). From that
point on the DB is authoritative. A new runtime version paired with an
older DB still resumes — the runtime brings the eval machinery; the DB
brings the source.

**An entity is in the DB iff it passed its gates.** A function that
fails to compile is never persisted in the first place; nothing to
quarantine. A function that fails on replay surfaces the failure
through the eval log (`:ok? false` on that replay's eval entry) —
and is rendered as a warning the next turn. No persistent quarantine
flag.

**No `:touched-tx` attribute.** Datahike attaches `:db/txInstant` and a
tx-id to every datom — provided the conn was opened with
`:keep-history? true`. "What changed since tx T" comes from datahike's
history / tx-range API. The default V0.5 conn uses
`:keep-history? false`; turning history on for the agent DB is a
prerequisite for this part of the model, and likely a tradeoff
worth making (storage cost vs render power).

**Entity kind is implicit in attribute presence.** No `:seon.X/kind`
discriminator. An entity is "a function" by carrying `:seon.fn/sym`; it
is "a schema" by carrying `:seon.schema/key`. Queries match on the
attrs they need, not on a type tag. The same principle drops
test-status as an enum: a test is "passing" when its `:last-passed-at`
is more recent than its `:last-failed-at` (or `:last-failed-at` is
absent).

### Eval log — "the REPL scrollback"

```clojure
;; Identity + context
::seon.eval/id              [:string {:seon.db/identity true}]   ; 10-char base62, time-prefixed → sorts by creation
::seon.eval/agent           :seon.db/ref                          ; → :seon.agent entity (owning agent)
::seon.eval/turn            :long                                 ; the agent's turn-counter at eval time
::seon.eval/at              :inst                                 ; wall-clock at eval start
::seon.eval/ns              :keyword                              ; namespace the form ran in

;; Form text + result
::seon.eval/narration       :string                               ; leading ;; comments captured by parse-forms
::seon.eval/source          :string                               ; the form text (or the unparseable chunk)
::seon.eval/ok?             :boolean                              ; reader + eval both succeeded
::seon.eval/result-edn      :string {:optional true}             ; pr-str of result on success (truncated)
::seon.eval/error           :string {:optional true}             ; pr-str of error payload on failure

;; Effects on persistent state
::seon.eval/touches         [:seon.db/ref {:db/cardinality :db.cardinality/many
                                            :optional true}]      ; entities created / updated by this form
::seon.eval/forgot          [:seon.db/ref {:db/cardinality :db.cardinality/many
                                            :optional true}]      ; entities retracted by this form
::seon.eval/switched-ns-to  :keyword       {:optional true}      ; (in-ns 'foo) / (ns foo) → :foo

```

The "kind" of an eval is read from which optional attrs are present.
The renderer never branches on a discriminator field; it asks
"is `:ok?` false?", "what does `:touches` resolve to?", "is
`:switched-ns-to` set?". Each `:touches` ref is itself a persistent
entity carrying `:seon.fn/sym` or `:seon.schema/key` or `:seon.test/sym`
— that's how the renderer learns "this eval produced a function".

- `:ok?` false → look at `:error`. The kind of failure (parse vs runtime
  vs timeout) lives in the error payload, not as a separate attr.
- `:touches` populated → the eval created or updated persistent state.
  One ref per entity touched; cardinality-many because `(defn` +
  inline `(deftest` is a single form that touches both.
- `:forgot` populated → the eval retracted entities.
- `:switched-ns-to` set → an `(in-ns …)` / `(ns …)` call. Used by
  the next form's `:seon.eval/ns` and by the renderer to anchor the
  agent's "current ns" indicator.
- None of the above → the eval ran successfully but produced no
  persistent change (an expression like `(+ 1 2)` or `(d/q …)`).

### What's NOT in the model

- No separate `:read-error` / `:exception` attrs. The kind of failure
  lives in the `:seon.eval/error` payload, not as a top-level attr.
- No `:reversible?` boolean. Reversibility is derived per-render from
  which attrs the eval carries (see the table in "Forget" below).
- No `:session-id` and no monotonic `:seq`. The eval-id is already
  time-prefixed base62 and unique; ordering is by `:at` (or by id).
  "This session" is the suffix of evals after the most recent
  resume-marker (see "Resume phase").
- No `:seon.eval/grades` storage. Grades are computed on render.
- No tx-metadata extension. Plain datahike tx info only.

## The eval batch

### Input

```clojure
{::seon.eval/agent-id  "agent-alpha"
 ::seon.eval/source    "(defn analyze ...)\n(deftest analyze-test ...)"}

```

One string containing N top-level forms.

### Processing

Forms are read and evaluated one at a time, in input order. Read errors
and eval errors are both per-form events — never batch-level rejections.
**Eval forms in input order.** No reordering. `(in-ns 'foo)` mid-batch
switches the namespace for subsequent forms.

For each form:

1. **Split the source into the next top-level form.** Use Edamame's
   `source-reader` + `parse-next+string` in a try-loop — each call
   returns `[form source-string]` for the next form, or throws on a
   read error. On a read error, scan ahead to the next balanced
   top-level expression boundary and resume; record the unreadable
   chunk as a failed eval (`:ok? false`) and continue.
2. **Capture `:seon.eval/ns`** at the moment of evaluation — read from
   the agent's `!current-ns` atom.
3. **Eval** the parsed form in that ns. On success, record
   `:ok? true` + `:result-edn`. On any failure (compile, runtime,
   timeout), record `:ok? false` + `:error` (a pr-str'd map carrying
   the failure kind: `:read | :compile | :runtime | :timeout`).
4. **Classify effects implicitly.** If the form defined a function,
   add the new `:seon.fn` entity to `:touches`. If it registered a
   schema, add the `:seon.schema` entity. If it called
   `(forget! 'x)`, add the now-retracted entity to `:forgot`. If it
   changed namespace, set `:switched-ns-to`.
5. **Independent transact per form** — one `:seon.eval` datom + any
   persistent-entity datoms in its own tx. A failure on form 5 doesn't
   roll back forms 1-4.

After all forms are processed, render the full context.

**Partial-success principle.** If the agent sends 10 forms and 9
succeed, the database keeps 9 successes. Read failures and eval
failures both land in the eval log as `:seon.eval` entries with
`:ok? false` and a structured `:error` payload — same partial-success
shape, no special batch-level handling. Dependents of a failed form
(later forms that referenced what it would have defined) get their own
runtime errors naturally and appear in the log as such. No rollback
machinery anywhere.

### Output

The reply IS the next-turn context render. Same shape, same renderer.

## Rendering — per-entity is the primitive

The whole context is a reduce over entities pulled from the database.
Solve the case for one entity, then extend to N, with priority
attributes on section entities to order things.

### The atomic step: render one entity

```clojure
;; Schema: [:=> [:cat ::seon.render/context ::entity] ::seon.render/ai]
(defn render-ai [ctx entity]
  ...)

```

The renderer is dispatched on the entity's shape via the existing seon
specificity-based dispatch — the more attrs an input schema requires,
the higher its specificity. Default renderers branch on **rendering
context**, not on an explicit fidelity tag: the same entity rendered
inside `:current-ns` ends up verbose; the same entity inside
`:related-ns` ends up terse. Context comes from attrs set by the
section function (e.g. `:seon.render/from-related-ns? true`) or from
information already on the entity.

Adding a more compact (or more verbose, or differently structured) view
is just writing a more specific renderer that matches when those attrs
are present. There is no enum of "fidelity tiers" anywhere in the
system.

### Sections are entities with section-functions

A section is an entity in the database. Presence of these attrs makes
something a section — there's no separate "section type":

```clojure
{:seon.ctx/name      :current-ns
 :seon.ctx/priority  30
 :seon.ctx/fn        'seon.render.default/current-ns-section}

```

The `:seon.ctx/fn` is a **regular Clojure function** that takes the
render-context and **returns a vector of entity-shaped maps**. Each map
in the vector is rendered individually via `:seon.render/ai` dispatch.

That contract is fixed:

- **Return value is always a vector** (possibly empty).
- **Each element is a map with namespaced keys** — either a real entity
  pulled from the DB (carries `:db/id`) or a synthetic map carrying
  whatever attrs the renderer is supposed to dispatch on.
- **A renderer must exist for each element's shape.** The fallback
  renderer matches `:map` (any map) and emits `(pr-str entity)` so the
  default is never "blow up" — but if the agent wants nice rendering,
  they write a renderer with a more-specific input schema.

The section function itself is unrestricted: it can merge multiple
queries, sort/group/paginate, inject synthetic entities (a banner row,
a separator), or branch on `ctx`. Anything that returns a vector of
maps is valid.

### The composer

```clojure
(defn assemble-context [ctx]
  (->> (sections-in-db ctx)                     ; query for :seon.ctx entities
       (sort-by :seon.ctx/priority)
       (mapv (fn [section]
               (let [section-fn (resolve (:seon.ctx/fn section))
                     entities   (section-fn ctx)             ; vector of maps
                     rendered   (mapv #(seon.render/render-ai ctx %) entities)]
                 (seon.render/render-ai ctx (assoc section ::children rendered)))))
       (str/join "\n")))

```

Note that the **section entity itself is rendered** via the same
dispatch — its renderer sees the rendered children in `::children` and
decides how to wrap them. So `<current-namespace>…</current-namespace>`
isn't a hardcoded wrap function; it's the `:seon.render/ai` renderer
for entities matching `[:map [:seon.ctx/name [:= :current-ns]] [::children …]]`.

That's the whole rendering pipeline at the conceptual level. Section
entities + section-functions + the universal `render-ai` dispatch.
Same pattern at every level. Nothing about "sections" is special; they
are just entities with the `:seon.ctx/*` attribute family.

### Solving for 1 → extending to N

- **1 entity render**: `(seon.render/render-ai ctx entity)` → string.
- **N entities in a section**: `(mapv #(seon.render/render-ai ctx %) entities)` then concat.
- **N sections in the context**: query all `:seon.ctx` entities, sort by
  priority, render each (which itself is the per-section reduce above).

Same primitive, applied at three nesting levels.

### Agent customization, three levers

1. **Override per-entity render**: write a `:seon.render/ai` function
   with a more-specific input schema. Affects every section that
   surfaces an entity of that shape.
2. **Change what a section returns**: write a new section function and
   transact `(:seon.ctx/fn 'my.ns/my-section)` on the section entity, or
   change priority by transacting `:seon.ctx/priority` on it. Add a
   section by transacting a new `:seon.ctx` entity.
3. **Override the composer**: rare; needed only if the agent wants a
   completely different top-level layout.

### Initial default context — what ships

These are the section entities transacted on first boot and the
functions that drive them. The agent can override or replace any of
them by transacting different attrs on the same entity (lookup by
`:seon.ctx/name`) or by retracting and adding a different one.

```clojure
;; --- Section entities (baseline) ---

{:seon.ctx/name :system  :seon.ctx/priority 10
 :seon.ctx/fn 'seon.render.default/system-section}

{:seon.ctx/name :related-ns  :seon.ctx/priority 20
 :seon.ctx/fn 'seon.render.default/related-ns-section}

{:seon.ctx/name :current-ns  :seon.ctx/priority 30
 :seon.ctx/fn 'seon.render.default/current-ns-section}

{:seon.ctx/name :warnings  :seon.ctx/priority 40
 :seon.ctx/fn 'seon.render.default/warnings-section}

{:seon.ctx/name :recent-evals  :seon.ctx/priority 50
 :seon.ctx/fn 'seon.render.default/recent-evals-section}

```

```clojure
;; --- Section functions (baseline, in seon.render.default) ---

(defn system-section
  "Always-present preamble. Returns one synthetic entity with the
   restore-defaults recipe and the agent's current ns banner."
  [{::keys [agent-id ns] :as ctx}]
  [{:seon.render/kind  :system-banner
    :seon.system/agent agent-id
    :seon.system/ns    ns
    :seon.system/restore-recipe "(seon.render/reset-defaults!)"}])

(defn current-ns-section
  "Every persistent entity owned by the current ns: the ns entity itself
   (which carries the (ns …) form), then its fns, schemas, tests.
   Schema/test ownership is derived from the namespaced key or sym."
  [{::keys [db ns]}]
  (let [ns-prefix (name ns)
        ns-ent   (d/q '[:find (pull ?e [*]) .
                        :in $ ?ns
                        :where [?e :seon.ns/name ?ns]] db ns)
        fns      (d/q '[:find [(pull ?e [*]) ...]
                        :in $ ?ns
                        :where [?e :seon.fn/ns ?ns]] db ns)
        schemas  (->> (d/q '[:find [(pull ?e [*]) ...]
                             :where [?e :seon.schema/key _]] db)
                      (filter #(= ns-prefix (namespace (:seon.schema/key %)))))
        tests    (->> (d/q '[:find [(pull ?e [*]) ...]
                             :where [?e :seon.test/sym _]] db)
                      (filter #(let [s (:seon.test/sym %)
                                     slash (.indexOf s "/")]
                                 (and (pos? slash)
                                      (= ns-prefix (subs s 0 slash)))))) ]
    (vec (concat (when ns-ent [ns-ent]) schemas fns tests))))

(defn related-ns-section
  "Entities from namespaces referenced by the current ns. Each entity
   gets `:seon.render/from-related-ns? true` so a separate, more compact
   render-ai dispatch can match it."
  [{::keys [db ns]}]
  (let [rel (compute-related-ns db ns)]
    (->> (sort rel)
         (mapcat #(current-ns-section {::db db ::ns %}))
         (mapv #(assoc % :seon.render/from-related-ns? true)))))

(defn warnings-section
  "Run every registered warning-predicate over current-ns entities,
   flatten, sort by severity. Returns warning maps (no :db/id; derived)."
  [{::keys [db ns] :as ctx}]
  (let [entities (current-ns-section ctx)
        preds    (registered-warning-predicates db)]
    (->> (for [entity entities, pred preds
               :let [w (pred ctx entity)]
               :when w]
           w)
         (sort-by :seon.warning/severity))))

(defn recent-evals-section
  "The last N evals (default N=20), oldest-first so it reads
   top-to-bottom like a real REPL transcript. The eval-id is
   time-prefixed base62 — sorting by id is identical to sorting by
   creation order, and cheaper than sorting by `:at`."
  [{::keys [db agent-id]}]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :in $ ?aid
              :where [?e :seon.eval/agent ?aid]]
            db [:seon.agent/id agent-id])
       (sort-by :seon.eval/id)
       (take-last 20)))

```

```clojure
;; --- Per-entity renderers (baseline) ---

;; An entity marked `:seon.render/from-related-ns? true` matches a more
;; specific (compact) renderer; the same shape without the marker
;; matches the verbose default. Agent overrides win when even more
;; specific.

(defn render-fn-default
  {:malli/schema [:=> [:cat ::seon.render/context ::seon.fn/entity]
                  ::seon.render/ai]}
  [_ {:seon.fn/keys [sym source]}]
  (str "<function name=\"" sym "\">\n" source "\n</function>"))

(defn render-fn-from-related-ns
  {:malli/schema [:=> [:cat ::seon.render/context
                            [:and ::seon.fn/entity
                             [:map [:seon.render/from-related-ns? [:= true]]]]]
                  ::seon.render/ai]}
  [_ {:seon.fn/keys [sym]}]
  (str "<fn-signature>" sym "</fn-signature>"))

;; …and so on for :seon.schema/entity, :seon.test/entity, :seon.eval/entity,
;; :seon.warning, :seon.system/banner. Each is a small function; agent can
;; override any of them by registering a more-specific dispatch.

```

That's the whole default surface: 5 section entities, 5 section
functions, ~6 per-entity renderers. About 100 lines of straightforward
Clojure. Adding or modifying any of it = writing one function. Nothing
is hidden; nothing is special-cased.

### Data shapes

```clojure
::seon.render/context
  [:map
   [::db          :any]
   [::agent-id    :string]
   [::ns          :keyword]]

;; A section entity. Identified by presence of name + priority + fn.
::seon.ctx/entity
  [:map
   [:seon.ctx/name      :keyword]
   [:seon.ctx/priority  :long]
   [:seon.ctx/fn        :symbol]]   ; ns-qualified, resolves to a section function

;; Derived warning (not stored as a DB entity; produced by warning preds at render time).
::seon.warning/record
  [:map
   [:seon.warning/entity      :string]
   [:seon.warning/issue       :keyword]
   [:seon.warning/severity    :keyword]   ; :persist-blocker | :runtime-warning | :info
   [:seon.warning/repair-hint :string]]

```

## Worked example — DB to rendered text

Database state (illustrative):

```clojure
;; Persistent entities
{:seon.fn/sym "seon.trading/analyze"
 :seon.fn/ns :seon.trading
 :seon.fn/source "(defn analyze {:malli/schema [:=> [:cat ::analyze-req] ::analyze-resp]} [{::keys [ticker]}] {::signal :hold})"}

{:seon.schema/key :seon.trading/analyze-req
 :seon.schema/source "(schema/register! ::analyze-req [:map [::ticker :string]])"}

{:seon.schema/key :seon.trading/ticker
 :seon.schema/source "(schema/register! ::ticker :string)"}

;; Section entities (transacted at bootstrap)
{:seon.ctx/name :system        :seon.ctx/priority 10 :seon.ctx/fn 'seon.render.default/system-section}
{:seon.ctx/name :related-ns    :seon.ctx/priority 20 :seon.ctx/fn 'seon.render.default/related-ns-section}
{:seon.ctx/name :current-ns    :seon.ctx/priority 30 :seon.ctx/fn 'seon.render.default/current-ns-section}
{:seon.ctx/name :warnings      :seon.ctx/priority 40 :seon.ctx/fn 'seon.render.default/warnings-section}
{:seon.ctx/name :recent-evals  :seon.ctx/priority 50 :seon.ctx/fn 'seon.render.default/recent-evals-section}

;; Eval log (last 2 from this session)
{:seon.eval/id "K9p2x4nB7q" :seon.eval/turn 7 :seon.eval/ok? true
 :seon.eval/source "(schema/register! ::ticker :string)"
 :seon.eval/result-edn "true"
 :seon.eval/touches [[:seon.schema/key :seon.trading/ticker]]}

{:seon.eval/id "L4m9p1xA3v" :seon.eval/turn 7 :seon.eval/ok? true
 :seon.eval/source "(defn analyze ...)"
 :seon.eval/result-edn "#'seon.trading/analyze"
 :seon.eval/touches [[:seon.fn/sym "seon.trading/analyze"]]}

```

Render context: `{::db <db> ::agent-id "alpha" ::ns :seon.trading}`.

Render walk:

```
(sections-in-db ctx)  → query for entities with :seon.ctx/name + :priority + :fn
                      → 5 default section entities
(sort-by :seon.ctx/priority)

For each section:
  entities ← ((resolve (:seon.ctx/fn section)) ctx)   ; section function returns a vector
  rendered ← (mapv #(render-ai ctx %) entities)       ; per-entity dispatch
  section-output ← (render-ai ctx (assoc section ::children rendered))

section :system   (system-section ctx)
                  → [{:seon.render/kind :system-banner
                      :seon.system/agent "alpha"
                      :seon.system/ns :seon.trading
                      :seon.system/restore-recipe "(seon.render/reset-defaults!)"}]
                  → render-ai on the banner entity → banner text
                  → render-ai on the section entity (with ::children attached)
                  → "<system>You are agent alpha, in :seon.trading. …</system>"

section :related-ns   (related-ns-section ctx) → []   ; no cross-ns refs in this example
                      → render-ai on section with empty ::children → "" (default elides empty)

section :current-ns   (current-ns-section ctx)
                      → [analyze-req-schema, ticker-schema, analyze-fn]
                      → render-ai on each (no :from-related-ns? marker, verbose dispatch wins)
                      → render-ai on section → "<current-namespace name=\":seon.trading\">…</current-namespace>"

section :warnings     (warnings-section ctx)
                      → [{:seon.warning/entity "seon.trading/analyze"
                          :seon.warning/issue :seon.warning/no-test-coverage
                          :seon.warning/severity :persist-blocker
                          :seon.warning/repair-hint "(deftest analyze-test (is (= ...)))"}]
                      → render-ai on the warning record → "<warning entity=\"analyze\" …/>"
                      → render-ai on section → "<warnings>…</warnings>"

section :recent-evals (recent-evals-section ctx)
                      → [eval-K9p2, eval-L4m9]   ; sorted by :seon.eval/id
                      → render-ai on each eval entity → "> (form)\nresult # eval-id"
                      → render-ai on section → "<recent-evals>…</recent-evals>"

composer concatenates non-empty section renders, separated by \n:
  <system>…</system>
  <current-namespace>…</current-namespace>
  <warnings>…</warnings>
  <recent-evals>…</recent-evals>

```

The full path: query for section entities → each section function
returns a vector of entity maps → `render-ai` fires per entity by
specificity dispatch → the section entity itself is rendered with
children attached → composer concatenates → final text.

### Reduce framing

The whole pipeline is a reduce-of-reduces over the database:

```clojure
(reduce (fn [acc section]
          (str acc (render-section ctx section)))
        ""
        (sort-by :seon.ctx/priority (sections-in-db ctx)))

```

Each `(render-section …)` is itself a reduce over the entities its
section function returned. Section entities choose the slices; the
universal `render-ai` dispatch shapes the strings.

## Recent-evals tile (REPL-style)

For each eval in the rendered window, emit:

```
> (form-source-as-typed)
result-rendered    # eval-id

```

Where `result-rendered` is determined as follows:

1. Parse `::seon.eval/result-edn` to data (skip if it was already a var
   reference like `#'seon.trading/analyze`).
2. Compute the result's **shape signature** (e.g. `:map-of-string-keys`,
   `:vector-of-strings`, `:set-of-tuples`).
3. Look up renderers matching `[:=> [:cat <shape>] ::seon.render/ai]` via
   specificity dispatch.
4. If a renderer matches → call it; output its result.
5. If no renderer matches → render raw with `truncate-edn` (see below).

The `# eval-id` comment ALWAYS appears on the result line, regardless of
custom rendering. It's the handle the agent uses to reference past
results in subsequent forms.

### Smart EDN truncation

`seon.render.default/truncate-edn` is a budgeted, structure-preserving
EDN truncator. Behavior:

- Hard byte cap per result (default 2 KB; configurable).
- Map: keep first N keys, then `..., #_(<n> more keys)`.
- Vector: keep first N entries, then `..., #_(<n> more)`.
- Set: same as vector.
- Nested values truncated recursively with diminishing budget.
- Trailing `...` is valid EDN — the truncated output round-trips
  through the reader (no half-open delimiters).

### Retro-render

Because rendering is pure-functional over current data + current renderer
set, when the agent adds a renderer for shape `X`, **all past eval results
of shape X in the recent-evals window are rendered using the new renderer
on the next turn**. No replay needed. The result-edn is stored in the
log; the renderer is dispatched at render time.

## Custom renderers

The agent writes a function:

```clojure
(defn render-confidence-result
  "Custom rendering for `{::signal-type _ ::confidence _}` maps."
  {:malli/schema [:=> [:cat ::analyze-response] ::seon.render/ai]}
  [{::keys [signal-type confidence]}]
  (str "signal=" (name signal-type) " conf=" (format "%.2f" confidence)))

```

They eval it. Next turn, any recent-evals result matching `::analyze-response`
renders compactly. Specificity dispatch (existing `seon.render` system)
picks this over the generic `truncate-edn` fallback.

If their custom renderer **throws** or returns non-string, the dispatch
catches it and falls back to the default. The error appears as a warning
("your render-confidence-result threw — using default") so they know.

## Restoring defaults

The runtime cannot be destroyed by agent action. The compiled CLJS
substrate is always loaded; every var seon ships with is callable
regardless of what's in the DB. The DB carries source records for those
vars (and any agent additions/overrides). Forgetting a DB entity
removes the source record, not the runtime var.

**`(seon.render/reset-defaults!)`** re-runs the bootstrap transaction
(`resources/seon/bootstrap.edn`) as an idempotent upsert. Effect:

- Missing-from-DB defaults are added back (section entities, default
  `seon.render.default/*` source records, etc.).
- Entries the agent has modified keep the agent's version; the bootstrap
  doesn't overwrite (lookup-by-identity finds the existing entity and
  leaves attrs the agent set alone).
- Entries the agent retracted are re-added.
- Strictly additive — never destructive of agent work.

A more aggressive `(seon.render/reset-defaults! :overwrite true)` exists
for "I broke my context and want the original everything back" — this
version transacts the bootstrap with retractions for any attr the agent
set differently. Always logged.

System-instructions tile (in every render) includes:

```
If your context renders incorrectly, restore the defaults:
  (seon.render/reset-defaults!)            ; idempotent upsert, agent edits preserved
  (seon.render/reset-defaults! :overwrite true) ; full reset, overwrites agent renderer edits

```

Forgetting a default the agent didn't intend to: the next
`reset-defaults!` brings it back. No persistent "you can't touch this"
flag — just the substrate's right to re-seed itself.

## Provenance — "why is this in my context?"

Provenance is derivable, not stored. Each section entity carries
`:seon.ctx/fn`; the per-entity renderer that fires is the
most-specific match for the entity's shape — both pieces of information
are recoverable at any time.

```clojure
(seon.render/explain-context)
;; => returns a map shaped like the rendered context, where each section
;;    and each rendered entity is annotated with:
;;      ::seon.render/by         — symbol of the function that produced it
;;      ::seon.render/dispatched — the input schema that matched (for entity
;;                                 renderers, this shows specificity)
;;      ::seon.render/inputs     — what data the fn was called with

```

By default this trace is NOT in the rendered context (saves tokens).
The agent calls `explain-context` when something looks weird, or toggles
`(seon.render/show-provenance!)` to attach inline provenance comments to
subsequent renders.

The dispatch model is uniform across the system: write a function with
the right schema; the system discovers and runs it. `explain-context`
exposes that dispatch trace.

## Forget — symbol deletion

```clojure
(seon.repl/forget! 'seon.trading/analyze)

```

Steps:

1. Look up the entity by identity attr (`:seon.fn/sym`, `:seon.schema/key`,
   `:seon.test/sym`, etc.).
2. Retract the entity from datahike.
3. `ns-unmap` the var (or `seon.schema/unregister!` for a schema, or the
   analog for a test).
4. Log an `:seon.eval` entry with `:seon.eval/forgot [ref to the now-gone entity]`.
5. Surface dependents (entities whose source references the forgotten
   symbol) as warnings on the next render.

Forgetting a default brought in by bootstrap is allowed — the next
`(seon.render/reset-defaults!)` brings it back. There is no
forget-refusal.

**Reversibility is derived, not stored.** A small classifier runs at
render time over each eval entry and decides reversibility from the
attrs already present:

| Eval shape | Reversible? | Mechanism |
|---|---|---|
| `:touches` populated, no atom/capability calls in `:source` | Yes | retract each touched entity + ns-unmap (or unregister) |
| Only `:switched-ns-to` set | Yes | just an ns pointer change |
| `:forgot` populated | Partial | the entity can be re-defined by re-evaluating its source |
| `:source` calls `swap!`/`reset!` or a WIT capability | No | state already mutated; no recorded "before" |
| Plain expression — no `:touches`, no `:forgot`, no mutating call | Yes | no side effects to undo |

The renderer surfaces "↶ reversible" / "✘ irreversible" alongside each
recent-evals entry so the agent always knows which steps can be cleanly
walked back. Classifier lives next to the renderer (`seon.render.default`),
not in the eval log — it can be replaced by registering a more specific
classifier without a schema change.

## Boot sequence

```
boot:
  if (database-empty? db) bootstrap-phase!    ; seed the DB
  resume-phase!                                ; rebuild runtime from DB
  render-initial-context!                      ; first turn for the agent

```

The two phases never run independently. On a brand-new DB, bootstrap
seeds and then resume eval's the freshly-seeded entries. On a persistent
DB, bootstrap is skipped and resume walks whatever the agent has built
up. Either path ends in the same place: every persistent entity has a
DB row AND a live var.

### Bootstrap phase (runs only when DB is empty)

The substrate seeds the DB from a build-time artifact: an ordered
vector of entity maps (`resources/seon/bootstrap.edn`), emitted by the
substrate's own build process from the same source the runtime was
compiled from.

```clojure
;; resources/seon/bootstrap.edn (shape; ordered for single-transact)
[{:seon.ns/name   :seon.render.default
  :seon.ns/source "(ns seon.render.default (:require [seon.schema :as schema] [seon.db :as db]))"}
 ...
 {:seon.schema/key :seon.render/ai  :seon.schema/source "(schema/register! ::ai :string)"}
 ...
 {:seon.fn/sym "seon.render.default/render-fn"
  :seon.fn/ns  :seon.render.default
  :seon.fn/source "(defn render-fn ...)"}
 ...
 {:seon.test/sym "seon.render.default/render-fn-test"
  :seon.test/target [:seon.fn/sym "seon.render.default/render-fn"]
  :seon.test/source "(deftest render-fn-test ...)"}
 ...
 ;; Section entities — the default context layout
 {:seon.ctx/name :system  :seon.ctx/priority 10 :seon.ctx/fn 'seon.render.default/system-section}
 ...]

```

The bootstrap is a single `(d/transact! conn bootstrap)`. Datahike
resolves intra-tx lookup refs (e.g. `:seon.test/target [:seon.fn/sym
"…"]`) inside the transaction, so dependency order in the vector is
enough — no special multi-pass logic.

After bootstrap the database is the system. The substrate code is
identical to anything the agent might write: ordinary persisted
entities the agent can edit, override, or — for non-load-bearing
things — forget.

### Resume phase (runs every boot)

Restore runtime state by walking the persistent entities and evaling
each in dependency order. On a freshly-bootstrapped DB this is what
makes the substrate "real" as vars; on a persistent DB this restores
the agent's accumulated work.

1. Compiled CLJS substrate is loaded.
2. Query all `:seon.ns` / `:seon.schema` / `:seon.fn` / `:seon.test`
   entities from the DB.
3. Build dep DAG by analyzing `:source` for references.
4. Topo sort:
   - `:seon.ns` first (each carries its own `(ns foo (:require [...]))`
     source — re-evaluating it re-establishes the namespace + requires
     in one step)
   - `:seon.schema` next (topological by schema-key references)
   - `:seon.fn` next (topological by var references)
   - `:seon.test` last (after their target fns)
5. For each entity, eval its `:source` in the entity's home ns. Log
   each as a `:seon.eval` entry, with `:touches` pointing at the
   entity that was recreated.
6. If an eval throws during replay, its eval-log entry carries
   `:ok? false` and references the failing entity in `:touches` (so
   the source-of-the-attempt is reachable). The renderer surfaces it
   as a warning ("X failed to replay this session — fix or forget")
   with the source available for inspection. The entity stays in the
   DB unchanged; nothing is retracted automatically.

The first eval transacted by the resume phase carries an
`:seon.eval/resume-marker? true` attr (cheap signal, default-false so
absent on every other entry). "This session's evals" =
"entries since the most recent resume marker." That's the only
"session" demarcation the system needs.

Eval log itself is not replayed. Scratch is scratch.

### Resuming an older DB on a newer runtime

The intended contract: a database from runtime version V can be opened
by runtime version V' (V' ≥ V) and the agent sees their state restored.
Mechanisms supporting this:

- The substrate code on disk is whatever V' ships; the DB carries
  whatever source it carries; replay eval's the DB source, overwriting
  any substrate var the agent had customized.
- New substrate fns/schemas/tests that V' adds and the DB doesn't have
  yet: re-run the bootstrap procedure for entries not already present
  (lookup by identity attr; transact only missing ones).
- Substrate fns the agent had overridden in V's DB stay overridden in
  V' — agent edits beat upstream changes. Conflicts surface as
  warnings.
- Datahike attribute-schema changes are constrained (datahike's schema
  evolution is more restrictive than tx-data evolution — research item
  below). The baseline attribute schema in `seon.schema/register!`
  calls should be treated as non-negotiable across versions; extensions
  add new attrs, never re-type existing ones.

## MVP scope

### In

- The database attributes defined above (`:seon.ns/*`, `:seon.fn/*`,
  `:seon.schema/*`, `:seon.test/*`, `:seon.eval/*`, `:seon.ctx/*`).
- `seon.repl/eval-batch!` — pod-side CLJS, runs the per-form
  read/eval/transact pipeline.
- `seon.repl/forget!` + `seon.schema/unregister!`.
- `seon.render.default/*` — the five default section functions
  (`system-section`, `related-ns-section`, `current-ns-section`,
  `warnings-section`, `recent-evals-section`) + per-entity render-ai
  defaults for each entity kind + smart EDN truncator.
- `seon.render/explain-context`, `reset-defaults!`, optional
  `show-provenance!`.
- Bootstrap phase: detect-empty + ordered `(d/transact!)` from
  `resources/seon/bootstrap.edn`.
- Resume phase: topo-walk persistent entities, eval each, log results.
- Per-form independent transacts (partial-success preservation).
- Eval classification implicit via `:seon.eval/touches` / `:forgot` /
  `:switched-ns-to` presence + `:ok?` boolean — no classifier enum to
  maintain.

### Out

- WASM-side wiring (M2 — the WIT `eval-form` export calls into this; pipeline
  itself runs in V0 Node pod first for testing)
- Multi-agent ownership (single-agent assumption for MVP)
- Baseline reconciliation (m6 capability — comes after MVP)
- Result-value retention across sessions (eval-ids reference values that
  don't survive pod restart; agent re-evals if needed)
- Token budgeting for the renderer (no compression beyond truncate-edn)
- Test auto-run on dependent-change (manual `(run-tests)` for MVP)
- Caching of section outputs (recompute every turn for MVP)

### Acceptance criteria for MVP

A new agent session can:

1. See a default-rendered context with the relevant sections present
   (empty sections suppressed).
2. Eval a multi-form batch including a schema, defn, and test.
3. **Partial success**: send 10 forms where one fails; see 9 successes
   persisted and 1 error reported in the eval log.
4. `(in-ns 'seon.foo)` to switch namespaces mid-batch and see subsequent
   forms land in the new ns; see the next-turn context now focused on
   `seon.foo` with `seon.trading` (or wherever they came from) demoted
   to `:related-ns` digest.
5. See the new entities in the next-turn context, plus warnings for any
   missing pieces.
6. Write a per-entity render override; see it applied retroactively on
   next turn (recent-evals AND current-ns AND related-ns sections all
   pick it up where the shape matches).
7. Forget a function and see dependents flagged.
8. Break their renderer; see the fallback engage with a warning.
9. `(seon.render/reset-defaults!)`; see defaults restored.
10. Restart the pod; see all persistent entities re-eval'd in the correct
    order; see the eval log retained as readable scrollback.

## Open questions / research items

1. **Smart EDN truncation prior art.** Research whether seon already has
   a structure-preserving EDN truncator (likely under `seon.render.*` or
   `seon.util.*`). Reuse if so.
2. **Datalog query for shape-based renderer dispatch.** The existing
   `seon.render` system dispatches via `:malli/schema` specificity. Confirm
   it handles dispatch on "any value matching schema X" efficiently when
   thousands of entities exist. Benchmark.
3. **Edamame parse-time comment extraction.** Need to extract leading
   `;;` comments per form for `::seon.eval/comment` (or drop that attr
   from MVP if Edamame doesn't expose it cleanly).
4. **Replay topological analysis.** The "what schemas does this fn ref?"
   analyzer can be lightweight (regex over `::schema-key`-like patterns)
   or heavy (full `cljs.analyzer`). MVP prefers lightweight; verify it
   covers normal agent-authored code.
5. **Specificity dispatch for renderer overrides + retro-render.** The
   recent-evals tile parses past EDN per turn and dispatches per result.
   Per-turn cost at N=20 results × M renderers. Estimate; cache if
   meaningful.
6. **Provenance vs token cost.** Should the per-section provenance
   trace (`(seon.render/explain-context)` output) be attached inline by
   default, or only when `(show-provenance!)` is on? Confirm default
   doesn't bloat prompts.
7. **Custom renderer infinite loop guard.** If an agent writes a renderer
   that itself emits forms that re-trigger rendering, we need a
   recursion limit. MVP: render-depth cap at 3, fall back to default
   beyond.
8. **`(d/q ...)` result rendering.** A common case. Verify the smart
   truncator + shape dispatch handles datalog result sets well by
   default. Possibly ship a built-in renderer for common shapes
   (set-of-tuples, vector-of-pulls).
9. **Property-test generation prior art.** seon has prior work on
   generating property-based tests from Malli schemas (research how
   it's been used in the existing REPL flow). The MVP should:
   - ship at least one default unit test per substrate function
   - surface "no property tests yet" as a warning that includes a
     generated stub the agent can accept
   - run property tests on demand via `(seon.test/run-properties …)`
     or analogous
10. **Datahike schema evolution constraints.** Datahike's tx-data is
    fully mutable (retract, assert) but its attribute-schema layer
    appears more constrained. Confirm: which attribute properties are
    immutable after first install? Cardinality? Ref-vs-value? Identity?
    The answer bounds how aggressively we can evolve `:seon.X/*` attrs
    across substrate versions.
11. **Bootstrap emission step.** Build process that walks the
    substrate's `src/`, extracts every `defn` / `schema/register!` /
    `deftest` / `(ns … (:require …))`, and emits an ordered vector to
    `resources/seon/bootstrap.edn`. Research existing seon work (the
    JVM-side codebase indexer per CLAUDE.md; the `seon.code` gate;
    `seon.graph.ingest`) for prior art on extracting and ordering.
12. **Optimal replay on a newer runtime.** Strategy for opening an
    older DB on a newer substrate: detect substrate version delta,
    merge missing-from-DB bootstrap entries, surface agent overrides
    that conflict with the new substrate as warnings with diffs. Out
    of MVP scope but the data model should permit it.

## Out-of-scope but adjacent

- **Test runner**: when does `(deftest)` actually run? MVP says: only when
  the agent eval's `(run-tests)` or specific `(t/run-test x-test)`. No
  auto-run. Auto-run on dependent-change is a follow-on.
- **Benchmarks under WASM**: `pod-host/datahike-harness` workloads ported
  to CLJS. Follows MVP.
- **Multi-agent**: when does ownership matter? `:seon.fn/owner-agent`
  attribute is the next addition; MVP single-agent.
