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
│  Rendering = section entities → section fns → strings            │
│                                                                  │
│  composer queries :seon.ctx entities, sorts by priority,         │
│  resolves each :seon.ctx/fn symbol, calls it with ctx, joins     │
│  the resulting strings. Empty strings are elided.                │
│                                                                  │
│  Agent extends by rewriting section fns and transacting          │
│  different :seon.ctx/fn symbols on the section entities.         │
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

;; Agent (extends the existing :seon.agent/* family with one new attr)
::seon.agent/current-ns  :keyword {:optional true}           ; ns the agent is "in"; falls back to home-ns when absent

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
::seon.eval/duration-ms     :long                                 ; wall-clock elapsed for this form (eval + auto-await)
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
- No `:seon.warning/*` persistent entities. Warnings are pure functions
  of current DB state — every warning is recomputed at render time by
  whichever predicate registers itself. Storing them would just risk
  the stored warning going stale relative to the live data it refers
  to. See `warnings-section` below.
- No tx-metadata extension. Plain datahike tx info only.

## The eval batch

### Input

```clojure
{::seon.eval/agent-id  "agent-alpha"
 ::seon.eval/source    "(defn analyze ...)\n(deftest analyze-test ...)"}

```

One string containing N top-level forms.

### Processing

The agent's response is a single string. The pipeline walks it
top-to-bottom and classifies each chunk into one of three kinds.
**No reordering.** Forms run in input order. `(in-ns 'foo)` mid-batch
switches the namespace for subsequent forms.

#### Three-way line/chunk classification

1. **Clojure form** — starts with `(`, `[`, `{`, `@`, `'`, `\``, `~`,
   or a reader macro (`#"…"`, `#{…}`, `#(…)`, `#_…`). Try to parse
   with Edamame's `source-reader` + `parse-next+string`. On parse
   success → eval. On parse failure → this is a malformed form (the
   agent meant to write code and got the syntax wrong); record an
   eval entry with `:ok? false` and an `:error` payload of kind
   `:read`.
2. **`;;`-comment line** — the existing narration convention.
   Strip the leading `;;` and accumulate into pending-narration.
3. **Prose** — anything else. Markdown headings (`# Plan`, `## Step
   1`), bullets (`- foo`, `* foo`), bare text, code-fence delimiters
   (` ``` `). Accumulate verbatim into pending-narration.

Pending narration is **attached to the next Clojure-form chunk** as
`:seon.eval/narration`. If the input ends with pending narration and
no following form, emit a **thinking-only** eval entry:
`{:seon.eval/source "" :seon.eval/narration <accumulated>
:seon.eval/ok? true}`. This preserves the agent's reasoning in
scrollback even when they choose not to eval anything else this
turn.

#### Per-chunk loop

For each chunk the classifier identifies as a form:

1. **Snap a start timestamp.** `(js/Date.now)` before the eval call.
2. **Capture `:seon.eval/ns`** at the moment of evaluation — read
   from the agent's `!current-ns` atom.
3. **Eval** the parsed form in that ns. On success, record
   `:ok? true` + `:result-edn`. On any failure (compile, runtime,
   timeout), record `:ok? false` + `:error` (a pr-str'd map
   carrying the failure kind: `:read | :compile | :runtime |
   :timeout`).
4. **Record `:seon.eval/duration-ms`** = `(- (js/Date.now) start)`.
   Covers the form's eval AND any auto-await — i.e. what the agent
   actually waited for. Cheap (two `Date.now()` calls); always on.
5. **Classify effects implicitly.** If the form defined a function,
   add the new `:seon.fn` entity to `:touches`. If it registered a
   schema, add the `:seon.schema` entity. If it called
   `(forget! 'x)`, add the now-retracted entity to `:forgot`. If it
   changed namespace, set `:switched-ns-to`.
6. **Independent transact per form** — one `:seon.eval` datom + any
   persistent-entity datoms in its own tx. A failure on form 5
   doesn't roll back forms 1-4.

After all chunks are processed, render the full context.

#### Why no `;;`-prefix discipline

The previous design (still live in `seon.repl/parse-forms`,
`src/seon/repl.cljs`) required prose to be `;;`-prefixed and silently
dropped bare-symbol "prose-shaped" reader tokens. That rule existed
because the reader cheerfully tokenized `Let me read the file` into
four symbol forms that polluted the eval log.

The three-way classification subsumes that rule. A bare-symbol
chunk no longer reaches the eval call — it's classified as prose at
the parse step, so the reader never produces those phantom forms in
the first place. The agent can write natural markdown between forms;
the LLM doesn't have to remember the `;;` discipline. The `how-you-
respond` ctx fragment becomes much shorter: "write your thinking
freely; wrap code in s-expressions" rather than "every prose line
must start with `;;`".

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

## Rendering — sections compose strings

The whole context is a concat over **section entities** queried from
the database. Each section function reduces the DB into a chunk of
text. The composer joins them by priority.

This is deliberately small: no per-entity-shape dispatch in the MVP.
The agent extends rendering by writing more section functions and by
overriding the symbol stored in each section's `:seon.ctx/fn` slot. A
later milestone adds specificity-based per-entity dispatch on top
(see "Future: per-entity dispatch" below); the MVP doesn't need it.

### Sections are entities with section-functions

A section is an entity in the database. Presence of these attrs makes
something a section — there's no separate "section type":

```clojure
{:seon.ctx/name      :current-ns
 :seon.ctx/priority  30
 :seon.ctx/fn        'seon.render.default/current-ns-section}

```

The `:seon.ctx/fn` slot holds a **fully-qualified symbol**. At render
time the existing `seon.render/resolve-symbol` (CLJS) or
`requiring-resolve` (CLJ) resolves it to a function; the function
takes the render context and **returns a string** (possibly empty).

That contract is fixed:

- **Return value is always a string.** An empty string elides the
  section in the composer's output (no double newlines).
- **The function decides its own internal structure.** It can run
  multiple DB queries, sort/group/paginate, call helper renderers, or
  branch on `ctx`. The MVP imposes no schema on the function body —
  whatever returns a string is valid.
- **Symbol misses fall through to pretty-print.** If a slot points at
  a symbol that doesn't resolve (typo, agent retracted the fn), the
  composer renders the section entity itself via the universal
  `pretty-ai` fallback. The render never crashes.

### The composer

The composer IS the function pointed at by the agent's
`:seon.render/ai` slot (currently `seon.render.default/ctx`). It
returns the map-shape the existing `ai-dispatch` expects
(`{:seon.render/text "..."}`), so it plugs into the agent surface
already in code:

```clojure
(defn assemble-ctx
  {:malli/schema [:=> [:cat :seon.render/system-input]
                  :seon.render/ai-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  {:seon.render/text
   (->> (db/query
          {:seon.db/db db
           :seon.db/query '[:find [(pull ?e [*]) ...]
                            :where [?e :seon.ctx/name _]]})
        (sort-by :seon.ctx/priority)
        (map (fn [section]
               (let [f (seon.render/resolve-symbol (:seon.ctx/fn section))
                     ctx-in {:seon.db/db    db
                             :seon.agent/id id}]
                 (if f
                   (f ctx-in)
                   (str (default/pretty-ai section))))))
        (remove str/blank?)
        (str/join "\n\n"))})

```

The composer is the only piece that knows about "sections". Section
functions are ordinary Clojure — the agent can read, write, or replace
any of them by transacting a different symbol into the slot.

### Future: per-entity dispatch (post-MVP)

The CLJ side of seon already has a specificity-based renderer
discovery (see `seon.render/find-renderer` and `resolve-renderer` in
`src/seon/render.clj`): functions whose `:malli/schema` output is
`:seon.render/ai` are queried out of the codebase graph, then ranked
by how many of their required input keys match the data's keys.

A natural follow-on for the agent REPL: lift that dispatch into the
CLJS pod so section functions can `(render entity)` and get the most
specific renderer for whatever shape `entity` has. That's strictly
additive — the section function still returns a string, it just
delegates more of the work to dispatch. Reserved for a later
milestone; the MVP ships without it.

### Agent customization, two levers

1. **Change what a section returns**: write a new section function and
   transact `:seon.ctx/fn 'my.ns/my-section` on the section entity, or
   change priority by transacting `:seon.ctx/priority` on it. Add a
   new section by transacting a new `:seon.ctx` entity. The body of
   the section function is unconstrained — query the DB, format the
   string, return it.
2. **Override the composer**: rare; needed only if the agent wants a
   completely different top-level layout. Transact a different
   `:seon.render/ai` symbol on the agent entity.

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
;; Each takes :seon.render/system-input and returns a string.
;; Empty string = section omitted. `current-ns` is read from the
;; agent entity (:seon.agent/current-ns); falls back to the agent's
;; home ns when absent.

(defn- agent-current-ns [db id]
  (or (-> (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})
          :seon.agent/current-ns)
      (seon.agent/home-ns id)))

(defn- host-timezone
  "Best-effort IANA timezone of the pod's host. POD timezone, not the
   user's — surfacing the user's tz needs a signal from outside the
   pod (browser, env var, agent entity attr). See post-MVP note below."
  []
  (.. (js/Intl.DateTimeFormat.) resolvedOptions -timeZone))

(defn system-section
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns  (agent-current-ns db id)
        now (js/Date.)]
    (str "<system agent=\"" id "\" ns=\"" ns "\">\n"
         "  Now: " (.toISOString now) "  (pod tz: " (host-timezone) ")\n"
         "  Restore defaults: (seon.render/reset-defaults!)\n"
         "</system>")))

(defn current-ns-section
  "Every persistent entity owned by the current ns: the ns entity itself
   (which carries the (ns …) form), then its fns, schemas, tests.
   Schema/test ownership is derived from the namespaced key or sym."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns       (agent-current-ns db id)
        ns-prefix (name ns)
        ns-ent   (db/entity {:seon.db/db db :seon.db/ref [:seon.ns/name ns]})
        fns      (db/query {:seon.db/db db
                            :seon.db/query '[:find [(pull ?e [*]) ...]
                                             :in $ ?ns
                                             :where [?e :seon.fn/ns ?ns]]
                            :seon.db/args [ns]})
        schemas  (->> (db/query {:seon.db/db db
                                 :seon.db/query '[:find [(pull ?e [*]) ...]
                                                  :where [?e :seon.schema/key _]]})
                      (filter #(= ns-prefix (namespace (:seon.schema/key %)))))
        tests    (->> (db/query {:seon.db/db db
                                 :seon.db/query '[:find [(pull ?e [*]) ...]
                                                  :where [?e :seon.test/sym _]]})
                      (filter #(let [s (:seon.test/sym %)
                                     slash (.indexOf s "/")]
                                 (and (pos? slash)
                                      (= ns-prefix (subs s 0 slash))))))
        parts    (concat
                   (when ns-ent     [(:seon.ns/source ns-ent)])
                   (map :seon.schema/source schemas)
                   (map :seon.fn/source fns)
                   (map :seon.test/source tests))]
    (if (seq parts)
      (str "<current-namespace name=\"" ns "\">\n"
           (str/join "\n\n" parts)
           "\n</current-namespace>")
      "")))

(defn related-ns-section
  "Symbols from namespaces referenced by the current ns, signature-only.
   Agent reaches for `:seon.fn/source` via current-ns-section when they
   switch ns and want the full body."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns      (agent-current-ns db id)
        related (compute-related-ns db ns)              ; helper, defined elsewhere
        rows    (for [other (sort related)
                      f     (db/query {:seon.db/db db
                                       :seon.db/query
                                       '[:find [(pull ?e [:seon.fn/sym]) ...]
                                         :in $ ?ns
                                         :where [?e :seon.fn/ns ?ns]]
                                       :seon.db/args [other]})]
                  (str "  " (:seon.fn/sym f)))]
    (if (seq rows)
      (str "<related-namespaces>\n" (str/join "\n" rows) "\n</related-namespaces>")
      "")))

(defn warnings-section
  "Run every registered warning-predicate over the agent's accessible
   entities. Each predicate returns either nil or a map carrying
   :seon.warning/text + :seon.warning/severity."
  [{:seon.db/keys [db] :as input}]
  (let [preds (registered-warning-predicates db)
        ws    (->> (for [p preds, w (p input) :when w] w)
                   (sort-by :seon.warning/severity))]
    (if (seq ws)
      (str "<warnings>\n"
           (str/join "\n" (map :seon.warning/text ws))
           "\n</warnings>")
      "")))

;; Example warning predicate, registered as a default. Surfaces any
;; eval in the recent-evals window that took longer than the threshold.
;; Pure derivation from :seon.eval/duration-ms — no stored state.
(def slow-eval-threshold-ms 500)

(defn slow-eval-warning
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [recent (recent-evals-rows db id 20)
        slow   (filter #(> (or (:seon.eval/duration-ms %) 0)
                           slow-eval-threshold-ms)
                       recent)]
    (for [e slow]
      {:seon.warning/severity :info
       :seon.warning/text
       (str "slow eval " (:seon.eval/id e)
            " took " (:seon.eval/duration-ms e) "ms — consider"
            " profiling: (seon.perf/profile-form …)")})))

(defn recent-evals-section
  "The last N evals (default N=20), oldest-first so it reads
   top-to-bottom like a real REPL transcript. The eval-id is
   time-prefixed base62 — sorting by id is identical to sorting by
   creation order, and cheaper than sorting by `:at`."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [rows (->> (db/query {:seon.db/db db
                             :seon.db/query
                             '[:find [(pull ?e [*]) ...]
                               :in $ ?aid
                               :where [?e :seon.eval/agent ?aid]]
                             :seon.db/args [[:seon.agent/id id]]})
                  (sort-by :seon.eval/id)
                  (take-last 20))]
    (if (seq rows)
      (str "<recent-evals>\n"
           (str/join "\n\n" (map format-eval-row rows))
           "\n</recent-evals>")
      "")))

```

That's the whole default surface: 5 section entities + 5 section
functions, ~80 lines of straightforward Clojure. Adding or modifying
any of it = writing one function. Nothing is hidden; nothing is
special-cased.

### Data shapes

```clojure
;; Render-context: every section fn receives this as its sole argument.
;; Matches the existing :seon.render/system-input shape (src/seon/render.cljs)
;; so a section fn can also be called as an agent's :seon.render/ai slot.
:seon.render/system-input
  [:map
   [:seon.db/db    :any]
   [:seon.agent/id :string]]

;; Section entity (persisted). Identified by :seon.ctx/name.
::seon.ctx/entity
  [:map
   [:seon.ctx/name      :keyword]
   [:seon.ctx/priority  :long]
   [:seon.ctx/fn        :symbol]]   ; ns-qualified, resolves to a section fn

;; Warning record (transient; produced by warning predicates at render time).
::seon.warning/record
  [:map
   [:seon.warning/text     :string]                ; rendered text
   [:seon.warning/severity :keyword]]              ; :error | :warn | :info

```

Note: the section fn pulls the agent's current ns from the agent
entity itself (`(:seon.agent/current-ns ent)`) rather than getting it
on `ctx`. This keeps the ctx schema identical to the existing render
input and avoids the bookkeeping of passing `ns` separately.

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

Render input: `{:seon.db/db <db> :seon.agent/id "alpha"}`. Each section
fn pulls the agent entity itself to learn the current ns (here:
`:seon.trading`).

Render walk:

```
(sections-in-db ctx)  → query for entities with :seon.ctx/name + :priority + :fn
                      → 5 default section entities
(sort-by :seon.ctx/priority)

For each section:
  text ← ((resolve-symbol (:seon.ctx/fn section)) ctx)
  ; → string (possibly "")

section :system        → "<system agent=\"alpha\" ns=\":seon.trading\">…</system>"
section :related-ns    → ""   ; no cross-ns refs in this example
section :current-ns    → "<current-namespace name=\":seon.trading\">
                          (ns seon.trading)
                          (schema/register! ::analyze-req …)
                          (schema/register! ::ticker :string)
                          (defn analyze …)
                          </current-namespace>"
section :warnings      → "<warnings>analyze has no test coverage</warnings>"
section :recent-evals  → "<recent-evals>
                          ;; thinking: I'll start with the ticker
                          ;; schema, then build analyze on top.
                                                            ; # J3p8m2rA1k
                          > (schema/register! ::ticker :string)
                          true                              ; # K9p2x4nB7q  3ms
                          > (defn analyze …)
                          #'seon.trading/analyze            ; # L4m9p1xA3v  1ms
                          </recent-evals>"

composer drops blank strings and joins the rest with "\n\n":
  <system>…</system>
  <current-namespace>…</current-namespace>
  <warnings>…</warnings>
  <recent-evals>…</recent-evals>

```

The full path: query for section entities → resolve each section's
symbol → call the function → join the resulting strings.

## Recent-evals tile (REPL-style)

For each eval in the rendered window, emit one of two shapes
depending on whether the entry has a form or is thinking-only:

```
> (form-source-as-typed)
result-rendered    ; # eval-id  4ms

```

```
;; thinking: <narration text, indented as a markdown block>
                                ; # eval-id

```

The trailing `<n>ms` on a form row is `:seon.eval/duration-ms`
formatted: ms for sub-second, `1.2s` / `12.5s` / `2m 30s` for
longer. Always present on form rows; omitted on thinking-only rows
(no duration to report). Cheap, always-on per-form timing so the
agent sees the cost of every form they evaluate. Fast forms vanish
into the noise; slow forms shout.

Thinking-only entries — `:seon.eval/source` blank, `:narration`
populated — render as a quoted block. The agent's reasoning between
turns survives as scrollback context, exactly as their code does.

For the MVP, `result-rendered` is just `truncate-edn` applied to
`:seon.eval/result-edn`. Smart per-shape result rendering is the
first thing the per-entity dispatch (post-MVP) enables.

The `# eval-id` comment ALWAYS appears on the result line, regardless
of custom formatting. It's the handle the agent uses to reference past
results in subsequent forms via `(result :<eval-id>)`.

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

Prior art: `bin/mcp-server` (babashka, JVM-side) already implements
the same shape under different mechanics — `max-eval-output-chars`
(default 2 KB), `truncated-preview-chars` (1.5 KB), `truncate-stdout`
which keeps the LAST N chars rather than the first. The opt-out
prefix `#_:full` in agent code there skips truncation entirely. We
can port the prefix idea into `eval-batch!` so the agent has the same
escape hatch from inside the pod.

### Result auto-save (per-eval addressable values)

Every successful eval's value is reachable by eval-id on the next
turn. Implementation already exists in `seon.eval/stash-result-raw!`
(`src/seon/eval.cljs:235`): the raw value is written to
`globalThis` under `__seon_results_<eval-id>`, and the agent's home
ns exposes `(result :<eval-id>)` to look it up. No pr-str round-trip
— values that don't round-trip through `read-string` (datahike DB
tagged literals, JS objects, fns) still come back identical.

Prior art on the JVM side: `bin/mcp-server` (`wrap-code-with-autosave`,
L461) wraps every form so its return value lands in
`@user/repl-<session>` under a content-hash key, and the rendered
output ends with `;; stored as :r-<hash> in @user/repl-<session>`.
Same idea, different storage. The CLJS pod keeps the per-eval-id
keying because that's the handle the eval-log already exposes; the
mcp-server's hash-key approach is incompatible with replay (the
hash is not the eval-id).

Cross-session retention: `globalThis` values die with the pod. On
the next pod boot, `(result :<eval-id>)` for an eval recorded by a
prior session returns nil. The agent re-evals the source from
`:seon.eval/source` if they need the value live again — surface this
behavior in the system-section so the agent learns the pattern.

### Retro-format

Because rendering is pure-functional over current data + current
section functions, when the agent rewrites `recent-evals-section` (or
the `format-eval-row` helper it calls) the new format applies to
every entry in the window on the next turn. No replay needed —
`:seon.eval/result-edn` is stored in the log, the format is computed
at render time.

## Custom rendering

The agent customizes rendering by rewriting section functions, not by
registering per-shape renderers (post-MVP). Example: collapse the
recent-evals tile to one line per eval.

```clojure
(defn my.work/compact-recent-evals
  [{::keys [db agent-id]}]
  (let [rows (->> (db/q db '[:find [(pull ?e [:seon.eval/id :seon.eval/source]) ...]
                             :in $ ?aid
                             :where [?e :seon.eval/agent ?aid]]
                        [:seon.agent/id agent-id])
                  (sort-by :seon.eval/id)
                  (take-last 20))]
    (str "<recent-evals>\n"
         (str/join "\n" (map (fn [{:seon.eval/keys [id source]}]
                               (str id "  " (subs source 0 (min 60 (count source)))))
                             rows))
         "\n</recent-evals>")))

(db/transact! {:seon.db/tx-data
               [{:seon.ctx/name :recent-evals
                 :seon.ctx/fn 'my.work/compact-recent-evals}]})
```

If the new function throws or returns a non-string, `pretty-ai` takes
over for that section and the failure surfaces as a warning so the
agent knows what broke.

## Self-instrumentation

Two layers, both opt-in past their default:

### Layer 1 — per-eval timing (always on, cheap)

Every eval entry carries `:seon.eval/duration-ms` (two `Date.now()`
calls per form, negligible cost). The recent-evals tile shows the
duration next to each eval-id. A default warning predicate
(`slow-eval-warning`) surfaces any eval over a threshold in the
warnings tile so the agent doesn't have to scan visually.

That's it for the default surface. No registry, no opt-in step. The
agent learns "this took 1200ms" without doing anything; the warning
tells them "go look" once it crosses the line.

### Layer 2 — Tufte profiling (opt-in, deeper)

When per-eval timing says "this form was slow" and the agent wants
to know *why*, they reach for Tufte. The hook point is the existing
`:seon.dev/instrumentation` Integrant layer that already wraps every
`:malli/schema`-annotated public fn for runtime validation. When
profiling is enabled, the same wrapper additionally wraps each fn
with `(taoensso.tufte/p ::ns/name body)`.

```clojure
;; turn profiling on for the next render or for a specific form
(seon.perf/with-profiling
  (assemble-ctx input))
;; => {:value <result> :stats #tufte/PStats { … }}

;; or globally for a window of time
(seon.perf/set-enabled! true)
;; … do work …
(seon.perf/set-enabled! false)
(seon.perf/last-stats)   ; the accumulated stats since enable
```

Why not always on: even at ~50ns per `p`, in a tight loop that adds
up. The user said it — leave deep profiling off by default and use
cheap timestamps for routine signal. Tufte is the precision tool
the agent picks up when the cheap signal points them at a problem.

When enabled, every call to every public fn lands in Tufte's stats
store under its ns-qualified id — including agent-authored fns,
since they pass through `seon.code/check` which requires
`:malli/schema`, which gets them instrumented, which gets them
profiled. The agent cannot write a public fn that's invisible to
profiling.

### Surface section: `perf-section`

```clojure
;; ships disabled — agent enables when they want to look
(defn perf-section
  [_input]
  (let [stats (seon.perf/last-stats)]
    (if stats
      (str "<perf>\n" (tufte/format-pstats stats {:columns [:n :sum :mean :p90]})
           "\n</perf>")
      "")))
```

The agent enables this tile when they're optimizing; disables it
when they're not. Both states are one transact on the `:perf`
section entity. The section is silent when stats are empty (e.g.
profiling has been off since boot).

### Out of scope

- Per-form sampling, percentiles aggregated across sessions,
  automatic regression detection.
- Always-on Tufte — see above; the cheap default covers the common
  case.

## Restoring defaults

The runtime cannot be destroyed by agent action. The compiled CLJS
substrate is always loaded; every var seon ships with is callable
regardless of what's in the DB. The DB carries source records for those
vars (and any agent additions/overrides). Forgetting a DB entity
removes the source record, not the runtime var.

**`(seon.render/reset-defaults!)`** replays the bootstrap as an
"add-missing-only" pass. Implementation: for each entry in
`resources/seon/bootstrap.edn`, pull the entity by its identity attr;
if absent, transact the entry; if present, skip it. This preserves
every attr the agent has edited — datahike's plain upsert would
overwrite them, so the no-op-on-existing check is explicit.

- Missing-from-DB defaults are added back (section entities, default
  `seon.render.default/*` source records, etc.).
- Entries the agent has modified keep the agent's version; the
  bootstrap's version is skipped, not merged.
- Entries the agent retracted are re-added.
- Strictly additive — never destructive of agent work.

A more aggressive `(seon.render/reset-defaults! :overwrite true)`
transacts the bootstrap directly (datahike upsert), overwriting every
attr that conflicts. Always logged. The agent uses this when they've
broken their context and want the original everything back.

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
`:seon.ctx/fn` — the function that produced that section's text. The
agent can pull the section entity to find out which function ran:

```clojure
(seon.db/pull-by-name {:seon.ctx/name :recent-evals})
;; => {:seon.ctx/name :recent-evals
;;     :seon.ctx/priority 50
;;     :seon.ctx/fn 'seon.render.default/recent-evals-section}
```

For "what did this function emit?" the agent simply calls the section
function directly in the REPL with `(seon.render/explain-section ctx :recent-evals)`
— it runs the section-fn and returns the string with the source-symbol
annotation. No special tracing infrastructure; just two operations on
the section entity (pull + call).

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
  read/eval/transact pipeline with the three-way classifier
  (form / `;;`-comment / prose). Prose chunks accumulate as
  `:seon.eval/narration` and attach to the next form; trailing prose
  with no following form becomes a thinking-only eval entry.
- `seon.repl/forget!` + `seon.schema/unregister!`.
- `seon.render.default/*` — the five default section functions
  (`system-section`, `related-ns-section`, `current-ns-section`,
  `warnings-section`, `recent-evals-section`) returning strings +
  `truncate-edn` helper + `pretty-ai` fallback (already exists).
- `seon.render/explain-section`, `reset-defaults!`.
- Per-eval timing: `:seon.eval/duration-ms` captured for every form
  via two `Date.now()` calls; rendered in the recent-evals tile;
  surfaced as warnings via `slow-eval-warning` over a configurable
  threshold. Always on, no opt-in.
- Tufte hook wired into the existing `:seon.dev/instrumentation`
  layer so every `:malli/schema`-annotated public fn can be profiled
  on demand (section fns + composer + eval-batch + db/query +
  db/transact! + every agent-authored public fn — the whole surface,
  one indirection). OFF by default. `seon.perf/with-profiling` /
  `seon.perf/set-enabled!` toggle it. Optional `perf-section`
  formats the latest stats when the agent wants to see them.
- Current time in `system-section` (`(js/Date.)` + the pod's IANA
  timezone). User-timezone lookup is post-v1; the section surfaces
  pod-time today with a clear note.
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
- User-timezone lookup. The system-section surfaces *pod* time and
  timezone — that's what `js/Intl.DateTimeFormat` resolves to inside
  the wasmtime/Node host. The *user's* timezone lives outside the
  pod (browser, env var, or an attr the user transacts onto their
  agent entity). Post-v1: pull `:seon.user/timezone` from the agent
  entity when present, format `now` in that zone; until then, the
  agent and user negotiate timezone in conversation if it matters.

### Acceptance criteria for MVP

A new agent session can:

1. See a default-rendered context with the relevant sections present
   (empty sections suppressed), including current pod-time in the
   system tile.
2. Eval a multi-form batch including a schema, defn, and test; see
   each form's `:duration-ms` rendered next to its eval-id.
2a. Submit a response that mixes markdown prose ("## Plan\n- step
   one") with Clojure forms; see the prose captured as
   `:seon.eval/narration` on the next form's entry, and any
   trailing prose stored as a thinking-only entry. Verify NO false
   "read-error" entries are produced for the markdown lines.
3. **Partial success**: send 10 forms where one fails; see 9 successes
   persisted and 1 error reported in the eval log.
4. `(in-ns 'seon.foo)` to switch namespaces mid-batch and see subsequent
   forms land in the new ns; see the next-turn context now focused on
   `seon.foo` with `seon.trading` (or wherever they came from) demoted
   to `:related-ns` digest.
5. See the new entities in the next-turn context, plus warnings for any
   missing pieces.
5a. Eval a deliberately slow form (e.g. `(do (js/Date.now)
   ; busy-wait 600ms; (js/Date.now))`); see the slow-eval warning
   surface in the warnings tile on the next render.
6. Rewrite a section function (e.g. `recent-evals-section` to a compact
   one-line-per-row form); see it applied on the next turn.
7. Forget a function and see dependents flagged.
8. Break a section function; see `pretty-ai` engage for that section
   with a warning.
9. `(seon.render/reset-defaults!)`; see defaults restored.
10. Restart the pod; see all persistent entities re-eval'd in the correct
    order; see the eval log retained as readable scrollback.

## Open questions / prior art

### Prior art located

- **Smart EDN truncator.** Not present. The closest is
  `seon.render.default/try-read-edn` (in `src/seon/render/default.cljs`)
  which slices the raw string at 400 chars — not structure-preserving.
  The new `truncate-edn` is a fresh helper for `seon.render.default`.
- **Codebase indexer.** Already exists JVM-side in
  `src/seon/graph/ingest.clj` + `src/seon/graph/analyzer.clj`: uses
  clj-kondo to extract every `defn` / `var` / schema-as-spec
  (`:seon.spec/*`) / ns-dep into datahike. The bootstrap-emission step
  is conceptually the CLJS-side equivalent — note that the existing
  attrs are `:seon.fn/qualified-name` / `:seon.fn/namespace` /
  `:seon.spec/key`, not the `:seon.fn/sym` / `:seon.fn/ns` /
  `:seon.schema/key` this spec uses. Reconciling the two namings is a
  follow-on; for now we can either rename, or treat the CLJS-pod
  attrs as a separate (parallel) family of entities.
- **Renderer specificity dispatch (CLJ-only).** Implemented in
  `src/seon/render.clj`: `find-renderer` (L133), `resolve-renderer`
  (L202), `namespace-proximity` tiebreak (L112). Queries
  `seon.graph.query/functions-with-output-key` to find candidates,
  filters to those whose required input keys are a subset of the
  data's keys, ranks by `(count required-keys)` descending. The
  post-MVP per-entity dispatch can lift this directly into the CLJS
  pod once the graph indexer is mirrored there.
- **CLJS renderer dispatch (today's surface).** Symbol-only slot
  resolution in `src/seon/render.cljs`: `ai-dispatch` /
  `html-dispatch` resolve a `:seon.render/ai` slot to a fn (via
  `resolve-symbol` against bootstrap compile-state OR `globalThis`)
  and fall through to `seon.render.default/pretty-ai` on miss. This
  is the surface the MVP composer uses.
- **Property-test infrastructure.** Malli 0.20.0 + test.check 1.1.3
  are in `deps.edn`. `malli.generator/generate` is reachable today.
  There is no existing property-test runner wrapper in
  `src/seon/test/*` — that helper still needs to be written.
- **Agent-source structural gate.** `src/seon/code.cljc` already
  checks "this is a `(defn name [{::keys [...]}] …)` form with
  `:malli/schema` metadata and namespaced destructure keys". The
  eval-batch's pre-eval gate (if we want one) reuses this directly.
- **`bin/mcp-server` — JVM-side analog of this pipeline.** A babashka
  script wired into Claude Code's MCP. It implements many of the
  patterns this spec describes, against the JVM nREPL instead of the
  CLJS pod:
  - Output cap: `max-eval-output-chars 2000`, `truncated-preview-chars
    1500`. Keeps the trailing portion (last N chars) rather than the
    head — different tradeoff from the spec's structure-preserving
    `truncate-edn`, both valid.
  - Opt-out prefix: forms prefixed with `#_:full ` skip truncation
    entirely (`full-output-prefix`, L92). Port this idea into
    `eval-batch!` so the agent can demand the full value on demand.
  - Result auto-save: `wrap-code-with-autosave` (L461) wraps the
    user's code so the value lands in `@user/repl-<session>` under a
    content-hash key (`:r-<hash>`). The rendered output ends with
    `;; stored as :r-1234 in @user/repl-abc1`. Same intent as the
    CLJS pod's `stash-result-raw!`, different storage.
  - Concurrent-eval guard: `orchestrator-eval-state` CAS prevents two
    evals from racing on the same nREPL session.
  - AI-render fallback: `try-ai-render` (L492) calls
    `seon.render/try-render` (CLJ) for the result's data shape; if
    no renderer matches, the response ends with a suggestion of
    which `-request/-response` specs to add. Same shape the
    post-MVP per-entity dispatch enables on the CLJS side.
- **Tufte (CLJS profiler).** Same `com.taoensso/*` family already in
  `deps.edn` (Timbre + Nippy). `taoensso/tufte` works in CLJS,
  exposes `(p ::id body)` / `(profile {…} body)` / `format-pstats`
  — exactly the surface the "Self-instrumentation" section needs.
  Add to `deps.edn`, hook into the existing
  `:seon.dev/instrumentation` registration so every Malli-validated
  fn gets a `tufte/p` wrap at the same boundary. Existing
  instrumentation code: CLAUDE.md "Function Instrumentation" calls
  out the Integrant key; the wrapper is the single seam where this
  bolts on.

### Open architectural questions

1. **Naming reconciliation.** The CLJ codebase already has
   `:seon.fn/qualified-name`, `:seon.fn/namespace`, `:seon.spec/key`.
   This spec proposes `:seon.fn/sym`, `:seon.fn/ns`,
   `:seon.schema/key`. Either rename to match the existing graph
   model (cheap, consistent) or treat the CLJS-pod entities as a
   parallel family — to be decided by the spec author. Affects
   bootstrap, queries, renderers, every example in this doc.
2. **Datahike + `:keep-history? true`.** `:db/txInstant` and the
   history/tx-range API depend on it. The V0.5 agent conn currently
   opens with `:keep-history? false` (see
   `seon.client/open-agent-conn!`). Turning history on for the agent
   DB is the prerequisite for "what changed since tx T" style
   queries; warrants an ADR.
3. **Datahike schema-flexibility and attr evolution.** The V0.5 conn
   uses `:schema-flexibility :write`, so attribute properties
   (cardinality, valueType, unique, ref-vs-value) must be installed
   before any data is transacted, and cannot be re-typed afterwards.
   The bootstrap ships attr-schemas first, persistent entities
   second. New runtime versions can ADD attrs but not change existing
   ones.
4. **Three-way classifier implementation.** Existing
   `seon.repl/parse-forms` (`src/seon/repl.cljs`) uses
   `cljs.tools.reader` with a hand-rolled comment collector AND a
   bare-symbol drop rule. Extending it to classify prose as a third
   kind (instead of dropping it) is a small change: do a line-based
   pre-pass to classify by leading character before reading. Lines
   starting with `(`/`[`/`{`/`@`/`'`/`\``/`~`/`#` (specifically the
   reader-macro `#` forms) are form-starters and feed the reader;
   lines starting with `;;` are narration; everything else is prose.
   Multi-line forms are stitched by tracking paren depth across the
   form-starter lines.
5. **Form-starter set.** The exact prefix set determining "this line
   starts a form" matters for false-positives. `#` is ambiguous —
   `#"…"` is a regex literal (form) but `# Heading` is markdown (prose).
   Resolution: `#` followed by `"`/`{`/`(`/`_`/`'` is a form-starter;
   `#` followed by whitespace is prose. Document the table; cover
   in tests.
6. **Replay dep analysis.** Topological ordering of resume needs to
   know "what schemas does this fn ref?". Lightweight (regex over
   `::keyword` patterns) probably works for agent-authored CLJS code,
   but verify against real agent outputs before committing.
7. **Result-value retention vs eval-id references.** Eval-ids are
   stable in the DB; the live values they reference live on
   `globalThis` (per existing `seon.eval/stash-result-raw!`) and do
   NOT survive pod restart. The agent must re-eval to get a fresh
   value. Document this explicitly so the agent learns the pattern.
8. **Resume of an older DB on a newer runtime.** Strategy is sketched
   but not designed: detect substrate version delta, merge
   missing-from-DB bootstrap entries (lookup by identity), surface
   agent overrides that conflict with the new substrate as warnings.
   Out of MVP scope.

## Out-of-scope but adjacent

- **Test runner**: when does `(deftest)` actually run? MVP says: only when
  the agent eval's `(run-tests)` or specific `(t/run-test x-test)`. No
  auto-run. Auto-run on dependent-change is a follow-on.
- **Benchmarks under WASM**: `pod-host/datahike-harness` workloads ported
  to CLJS. Follows MVP.
- **Multi-agent**: when does ownership matter? `:seon.fn/owner-agent`
  attribute is the next addition; MVP single-agent.
