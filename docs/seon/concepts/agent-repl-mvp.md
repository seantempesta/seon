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

## What we're strict about (everything else is flexible)

We're building this to work **with** the agent. Understand intent;
ship good defaults; let them experiment. We're only strict about
the few things that protect the substrate from incoherence.

**Hard rules (eval-batch enforces; failures land as `:ok? false`):**

1. **Forms must be parseable.** rewrite-clj parses the input; on
   failure we recover (skip to the next balanced top-level form;
   continue). Genuinely malformed code becomes an `:ok? false` eval
   entry with the parse error. We do not silently drop or guess.
2. **Functions must have `:malli/schema`.** The existing
   `seon.code/check` gate enforces this (`src/seon/code.cljc`).
3. **Spec changes against persisted data must be accretive.** If
   `(schema/register! ::foo new)` would invalidate existing
   `[?e ::foo _]` datoms, reject with a warning naming the affected
   entities. Accretive changes (add optional key, loosen constraint,
   widen union) go through. `seon.repl/remove-spec` is the explicit
   escape hatch.
4. **Datahike attribute schemas are mostly append-only.** Under
   `:schema-flexibility :write`, `:db/valueType` is immutable, and
   `:db/unique` cannot be removed once set. `:db/cardinality
   :one→:many` is permitted only when no `:db/unique` is set;
   `:db/doc`, `:db/noHistory`, and `:db/isComponent` are always
   updatable. In practice the parts we use (valueType + identity)
   are immutable; rename to change. New attrs welcome.

**Soft rules (warnings only; never reject):**

- **Functions without tests don't fully count.** A `:seon.fn/*`
  entity transacts normally. The warnings tile derives the no-test
  warning on the fly: query every `:seon.fn` entity, left-join
  against `:seon.test/target`, emit a warning for any fn with no
  matching test. Pure derivation — nothing on the fn entity says
  "I have no tests"; the absence is computed. Convention: every
  public fn ships with a `<name>-example` test exercising the
  documented happy path; the warning vanishes the moment one
  exists. See [[#^d9]].
- **`(def …)` outside `defn`/`schema/register!`/`deftest` is
  scratch.** Warning surfaces; the value lives in the process but
  won't survive restart ([[#^d5]]).
- **Slow forms get flagged.** Default 500ms; agent can retune.

**Flexible everywhere else.** Functions redefine freely. Tests
redefine freely. Refactors break things in flight; the warnings
tile is the always-current picture of what's broken; the agent
decides when to fix.

## Reference graph (specs / fns / tests link together)

Every `:seon.fn` / `:seon.schema` / `:seon.test` entity is reachable
from the others via explicit refs. The agent and the warning
predicates can ask any direction of the question:

- `:seon.fn/input-spec`, `:seon.fn/output-spec`: `:seon.db/ref` →
  `:seon.schema`.
- `:seon.fn/refs`: cardinality-many `:seon.db/ref` → other
  `:seon.fn` entities this fn calls. Extracted by the analyzer
  walk at define time.
- `:seon.test/target`: `:seon.db/ref` → the `:seon.fn` the test
  exercises.

The reverse index is what makes targeted test auto-run cheap
([[#^d6]]) and what makes "who depends on X?" answerable in one
query.

## ID conventions

**All ids use the same encoding.** Eval-id, message-id, log-id,
agent-id — every generated id has identical shape. Defined once as
a Malli schema and referenced from each identity attr:

```clojure
;; in seon.id
(schema/register! ::seon.id/id [:string {:min 12 :max 12}])
;; 12-char base62: 8-char non-wrapping time prefix + 4-char random.
;; 62^8 ≈ 2.2 × 10^14, covers epoch-ms past year 9000.
;; Lex-sort = creation-time sort.

(defn new-id! []  ; the one generator
  …)

;; every identity attr references the shared shape
(schema/register! :seon.eval/id    [:seon.id/id {:seon.db/identity true}])
(schema/register! :seon.message/id [:seon.id/id {:seon.db/identity true}])
(schema/register! :seon.agent/id   [:seon.id/id {:seon.db/identity true}])
(schema/register! :seon.log/id     [:seon.id/id {:seon.db/identity true}])
```

No per-kind variants of the generator. Agent home-ns is
`seon.agent.<id>` — e.g. `seon.agent.AbCdEfGh1234`. URLs use the
same id verbatim.

- **`:seon.eval/at`**: `:long` epoch-millis. Indisputable; doesn't
  require id-decoding. The canonical timestamp.
- **Entity references in maps**: identity-attr **values** are
  strings (`:seon.fn/sym "seon.foo/bar"`). References use lookup-refs
  `[:seon.fn/sym "seon.foo/bar"]`.
- **Function references in slot attrs** (`:seon.ctx/fn`,
  `:seon.render/ai`): fully-qualified **symbols**, e.g.
  `'seon.render.default/system-section`. Stored as `:symbol`. The
  dispatcher resolves at call time.

When this doc says "symbol" it means the Clojure symbol type
(resolvable reference). "string" / "keyword" mean those types.

## Decisions pending

Each links to the detail block at the bottom via Obsidian block-id.
Refer by id ("yes D3, defer D7"). The body of the spec reflects the
current design; only items below remain open.

**Open — needs REPL verification:**

- **[[#^d1]]** — Add `rewrite-clj` to deps; verify it works in
  bootstrap-CLJS / shadow.
- **[[#^d2]]** — Verify bootstrap-CLJS unbound-symbol error shape.

**Open — design questions:**

- **[[#^d3]]** — Older-DB-on-newer-runtime upgrade. Deferred; focus
  on bootstrap-from-compiled-code first ([[#^d12]]).
- **[[#^d4]]** — Per-kind redefinability rules (specs / fns /
  tests).
- **[[#^d5]]** — Detect `(def …)` via rewrite-clj AST (no regex).
- **[[#^d6]]** — Targeted test auto-run wiring + warning predicate
  + runtime-var stash.
- **[[#^d7]]** — `(forget!)` for whole namespaces.
- **[[#^d8]]** — Explicit `seon.repl/remove-spec`, `remove-fn`,
  `remove-test`.
- **[[#^d9]]** — `<name>-example` test convention as the documented-
  happy-path stub.
- **[[#^d10]]** — Reference-graph attrs (`:seon.fn/refs`,
  `:seon.fn/input-spec`, `:seon.test/target`) — confirm shape +
  cardinality.
- **[[#^d11]]** — Forgiving parse recovery on parse-error; advance
  to next balanced top-level form.
- **[[#^d12]]** — Topological bootstrap: how to emit
  `bootstrap.edn` from substrate source in correct dep order.
  Solve this first; indexing follows.

The detailed notes for each live in [Decision details](#decision-details) at the
bottom.

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

```text
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
::seon.agent/current-ns  :keyword {:optional true}           ; agent's current ns; upserted on every (ns …) form. Falls back to home-ns.

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
history API (`d/history` + `d/q` with the 5-tuple datom pattern). The
default V0.5 conn uses `:keep-history? false`; turning history on for
the agent DB is a prerequisite for this part of the model, and a
deliberate tradeoff (storage cost vs render power).

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
::seon.eval/id              [:seon.id/id {:seon.db/identity true}]   ; shared id shape; see "ID conventions"
::seon.eval/agent           :seon.db/ref                          ; → :seon.agent entity (owning agent)
::seon.eval/turn            :long                                 ; the agent's turn-counter at eval time
::seon.eval/at              :long                                 ; epoch-millis at eval start (canonical timestamp)
::seon.eval/duration-ms     :long                                 ; wall-clock elapsed for this form (eval + auto-await)
::seon.eval/ns              :keyword                              ; namespace the form LEFT the agent in (= ending ns)

;; Form text + result
::seon.eval/narration       :string                               ; leading ;; comments captured by parse-forms
::seon.eval/source          :string                               ; the form text (or the unparseable chunk)
::seon.eval/ok?             :boolean                              ; reader + eval both succeeded
::seon.eval/result-edn      :string {:optional true}             ; pr-str of result on success (truncated)
::seon.eval/error           :string {:optional true}             ; pr-str of error payload on failure

```

Effects on persistent state are not stored on the eval entity. Each
eval transacts its persistent datoms in a single tx with `:tx-meta
{:seon.eval/id <id>}`. Datahike records the metadata as datoms on the
tx-id, so **the eval entity IS the tx entity**. "What did this eval
touch?" is answered by querying `(d/history db)` for datoms in that
tx; "what did it retract?" is the same query filtered to retractions.
No denormalized ref-vectors.

`:seon.eval/ns` is the namespace the agent ended in after this form
ran — i.e. the live `!current-ns` value seon.eval/eval-batch! writes
after each form. A `(ns other)` form's eval entry carries `:ns :other`
even though the form itself was parsed in the previous ns.

The agent's **current namespace** also lives on the agent entity as
`:seon.agent/current-ns :keyword {:optional true}`, upserted on
every form that changes the ns. Two writes (eval-log + agent entity)
sound redundant, but the agent-entity attr is the one renderers
pull on every render — turning that into a "sort eval log, take
most recent" query at render time would be a measurable cost for no
gain. The eval-log `:ns` is per-form history; the agent attr is the
current value. Both load-bearing, both cheap. Falls back to
`seon.agent/home-ns` when absent.

Ns-switch events are derivable from consecutive eval entries
(`:ns` differs from prior eval's `:ns`); no `:switched-ns-to`
attr needed.

The "kind" of an eval is read from `:ok?` and from the history-query
answer "what datoms did this eval's tx write?". The renderer never
branches on a discriminator field.

- `:ok?` false → look at `:error`. The kind of failure (parse vs runtime
  vs timeout) lives in the error payload, not as a separate attr.
- History query for the eval's tx returns asserted datoms on a
  `:seon.fn` / `:seon.schema` / `:seon.test` entity → the eval created
  or updated persistent state.
- History query returns retraction datoms → the eval retracted
  entities.
- `:ns` differs from the previous eval's `:ns` → the form switched
  namespace. Renderer compares; no discriminator attr.
- None of the above → the eval ran successfully but produced no
  persistent change (an expression like `(+ 1 2)` or `(d/q …)`).

### What's NOT in the model

- No separate `:read-error` / `:exception` attrs. The kind of failure
  lives in the `:seon.eval/error` payload, not as a top-level attr.
- No `:seon.eval/touches` or `:seon.eval/forgot` ref-vectors. The
  tx that wrote the eval entity IS the tx that wrote (or retracted)
  the persistent datoms — datahike's `:tx-meta` attaches the eval-id
  to the tx-id, so the history query recovers both directions of
  the answer.
- No `:reversible?` boolean. Reversibility is derived per-render from
  the history datoms the eval's tx wrote (see the table in "Forget"
  below).
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

## The eval batch

### Input

```clojure
{::seon.eval/agent-id  "agent-alpha"
 ::seon.eval/source    "(defn analyze ...)\n(deftest analyze-test ...)"}

```

One string containing N top-level forms.

### Processing

The agent's response is a single string of **valid ClojureScript** —
forms interleaved with `;;` comments. Nothing else. The reader does
the heavy lifting: we ask it for every form AND every comment, then
pair them up in order.

**No reordering.** Forms run in input order. `(in-ns 'foo)` mid-batch
switches the namespace for subsequent forms.

#### Parse: forms-and-comments

Use `rewrite-clj.parser/parse-string-all`, which parses Clojure
source as a tree of nodes that **includes** whitespace and `:comment`
nodes alongside form nodes (verified against
`reference-code/rewrite-clj/src/rewrite_clj/parser.cljc` and
`node/comment.cljc`). Walk the top-level children, filtering
whitespace, and you get the ordered vector below — no extra
machinery, no hand-rolled scanner.

(Aside: Edamame, the other parser in our deps, drops comments at the
reader — `edamame.impl.parser/parse-comment` reads the line and
returns the reader. rewrite-clj is the right tool here.)

```clojure
[{:kind :comment :text "## Plan"}
 {:kind :comment :text "Schema first, then the function."}
 {:kind :form    :form  '(schema/register! ::ticker :string) :source "(schema/register! ::ticker :string)"}
 {:kind :comment :text "Now the analyzer:"}
 {:kind :form    :form  '(defn analyze …) :source "(defn analyze …)"}
 {:kind :comment :text "Sanity-check it returns the right shape:"}
 {:kind :form    :form  'analyze :source "analyze"}]
```

Notice the last entry: a bare symbol `analyze`. **Bare symbols are
forms.** They eval like any other expression — return the value, or
throw an unbound-symbol error if the agent referenced something that
doesn't exist. That's standard REPL behavior; we don't try to
disambiguate at parse time.

**Markdown lives inside `;;` comments.** Multi-line markdown is
just multiple `;;` lines in a row:

```clojure
;; ## Plan
;;
;; 1. Register the ticker schema
;; 2. Build analyze
;; 3. Verify by evaling `analyze`
```

The reader sees these as four/five consecutive comments. The pairer
joins consecutive comments into one narration block. The agent
formats with markdown freely (`##`, `-`, code-spans, whatever);
those characters are valid inside a `;;` line.

**Comments are how the agent talks to the user.** The web view (a
later milestone) renders `:seon.eval/narration` as Markdown — the
user sees the same `## Plan` / bullets / code-spans the agent wrote,
formatted. So `;;` is two channels at once: the agent's thinking
captured in the eval log, AND the agent's explanation to the user.
Teach this in the system-section so the LLM uses comments as a
first-class communication device, not an afterthought.

#### Pair: comments → narration → form

Walk the vector front to back:

- Comments accumulate into pending-narration, separated by `\n`.
- The next form's `:seon.eval/narration` = the accumulated text;
  pending-narration resets.
- If the vector ends with pending narration and no following form,
  emit a **thinking-only** eval entry: `{:seon.eval/source ""
  :seon.eval/narration <accumulated> :seon.eval/ok? true}`. The
  agent's closing thoughts survive in scrollback.

#### Per-form loop

For each entry classified as a form:

1. **Snap a start timestamp.** `(js/Date.now)` before the eval call.
2. **Eval** the parsed form in the agent's current ns (the value of
   the `!current-ns` atom seon.eval/eval-batch! already maintains).
   On success, record `:ok? true` + `:result-edn`. On any failure
   (compile, runtime, timeout, unbound-symbol), record `:ok? false` +
   `:error` (pr-str'd map carrying `:kind :compile | :runtime |
   :timeout`).
3. **Record `:seon.eval/ns`** as the ending ns returned by
   `cljs.js/eval-str`'s `:ns` field — i.e. where the form left the
   agent. Same value the existing pipeline already writes back into
   `!current-ns`. If `:ns` differs from the agent entity's
   `:seon.agent/current-ns`, upsert the new value onto the entity
   in the same tx as the eval entry.
4. **Record `:seon.eval/duration-ms`** = `(- (js/Date.now) start)`.
   Covers the form's eval AND any auto-await — i.e. what the agent
   actually waited for. Cheap (two `Date.now()` calls); always on.
5. **Tag the tx with the eval-id.** The eval entity and any
   persistent-entity datoms produced by the form go in a single
   `d/transact` call with `:tx-meta {:seon.eval/id <id>}`. Datahike
   records `:seon.eval/id` as a datom on the tx-id, so the eval
   entity IS the tx — no separate denormalized "what did this touch"
   attr. Effect classification at render time is a history query
   over that tx. Ns switches need no special handling — `:ns`
   already captured it.
6. **Independent transact per form** — one tx per form. A failure on
   form 5 doesn't roll back forms 1-4.

After every entry is processed, render the full context.

#### Parse failures

If the parser throws (the agent emitted something that isn't valid
ClojureScript and isn't a `;;` comment — e.g. raw markdown outside
a comment), we record an `:ok? false` eval entry with `:kind :read`,
advance to the next balanced top-level boundary, and continue. The
error message tells the agent what went wrong; the system-section
reminds them prose belongs in `;;` comments. Bare prose tokenizes
into unbound-symbol errors the agent sees in the next turn's
`recent-evals` tile — a loud, self-correcting signal, no
prose-detection heuristic needed.

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

{:seon.ctx/name :prompt  :seon.ctx/priority 99
 :seon.ctx/fn 'seon.render.default/prompt-section}

```

```clojure
;; --- Section functions (baseline, in seon.render.default) ---
;; Each takes :seon.render/system-input and returns a string.
;; Empty string = section omitted. `current-ns` is read directly
;; off the agent entity; eval-batch! upserts it on every (ns …) form.

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

(defn prompt-section
  "Renders the final piece of context as a REPL prompt the agent is
   typing into. The current ns appears exactly as a real Clojure REPL
   shows it, so the LLM is primed to continue the conversation as
   the next form in that ns. Always present — never empty."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ent  (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})
        ns   (or (:seon.agent/current-ns ent) (seon.agent/home-ns id))
        turn (or (:seon.agent/turn-count ent) 0)]
    (str ns "=>  ; turn " turn)))

```

That's the whole default surface: 6 section entities + 6 section
functions, ~100 lines of straightforward Clojure. Adding or modifying
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

Note: section fns read the agent's current ns off the agent entity
(`:seon.agent/current-ns`), which the eval pipeline upserts on every
ns-changing form. The render input schema stays identical to the
existing surface — no extra parameter, no extra lookup.

### XML wrappers around structural sections

LLMs parse XML cleanly — clear start/end markers, no paren-counting
needed. The default sections use XML wrappers at the section level
for unambiguous boundaries:

- `<system>…</system>`
- `<current-namespace name=":seon.trading">…</current-namespace>`
- `<related-namespaces>…</related-namespaces>`
- `<warnings>…</warnings>`
- `<recent-evals>…</recent-evals>`

Inside the section, we **don't** wrap individual entities. Clojure
source stays as Clojure source — that's what we want the agent
emitting, and the more it looks like REPL output the more naturally
it writes the same. The XML is the scaffolding around the Clojure;
the Clojure is the content.

Exception: the per-eval row in recent-evals uses bash-style `> form`
+ `; # eval-id  Nms` because that's what a real REPL transcript
looks like. No XML around each row — too noisy.

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

;; Eval log (last 2 from this session). Each was transacted with
;; :tx-meta {:seon.eval/id <id>}, so the tx-id IS the eval-entity id;
;; the persistent datoms the form wrote are on the same tx.
{:seon.eval/id "K9p2x4nB7q" :seon.eval/turn 7 :seon.eval/ok? true
 :seon.eval/source "(schema/register! ::ticker :string)"
 :seon.eval/result-edn "true"}

{:seon.eval/id "L4m9p1xA3v" :seon.eval/turn 7 :seon.eval/ok? true
 :seon.eval/source "(defn analyze ...)"
 :seon.eval/result-edn "#'seon.trading/analyze"}

```

Render input: `{:seon.db/db <db> :seon.agent/id "AbCdEfGh1234"}`. Each section
fn pulls the agent entity itself to learn the current ns (here:
`:seon.trading`).

Render walk:

```text
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
                          ;; ## Plan
                          ;; 1. Register the ticker schema
                          ;; 2. Build analyze on top
                                                            ; # J3p8m2rA1k
                          > (schema/register! ::ticker :string)
                          true                              ; # K9p2x4nB7q  3ms
                          > (defn analyze …)
                          #'seon.trading/analyze            ; # L4m9p1xA3v  1ms
                          ;; sanity check the var resolves
                          > analyze
                          #object[seon$trading$analyze]     ; # M2q7w0vB9x  0ms
                          </recent-evals>"
section :prompt        → ":seon.trading=>  ; turn 7"

composer drops blank strings and joins the rest with "\n\n":
  <system>…</system>
  <current-namespace>…</current-namespace>
  <warnings>…</warnings>
  <recent-evals>…</recent-evals>
  :seon.trading=>  ; turn 7

```

The full path: query for section entities → resolve each section's
symbol → call the function → join the resulting strings.

## Recent-evals tile (REPL-style)

For each eval in the rendered window, emit one of two shapes
depending on whether the entry has a form or is thinking-only:

```text
> (form-source-as-typed)
result-rendered    ; # eval-id  4ms
```

```text
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

Prior art and a portable opt-out prefix idea (`#_:full` for "don't
truncate this form") live in `bin/mcp-server` — see the
"Prior art located" section below for the full reference.

### Result auto-save (per-eval addressable values)

Every successful eval's value is reachable by eval-id on the next
turn. `:seon.eval/result-edn` stores the **truncated** value for
display in recent-evals (default 2 KB; configurable). The full live
value lives on `globalThis` under the eval-id (implementation in
`seon.eval/stash-result-raw!`, `src/seon/eval.cljs:235`), reachable
via `(result :<eval-id>)` within the same pod session. No pr-str
round-trip — values that don't read back through `read-string`
(datahike DB tagged literals, JS objects, fns) still come back
identical.

If the agent wants a full value preserved across restart, they
transact it explicitly via `seon.db/transact!` against an attr they
register. The agent decides what's worth keeping.

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

Two layers, cheap-by-default and precise-on-demand.

**Layer 1 — per-eval timing (always on).** `:seon.eval/duration-ms`
is captured on every eval (see "Per-form loop" #4) and rendered next
to the eval-id; `slow-eval-warning` (defined alongside the other
default warning predicates) lifts slow forms into the warnings tile.
No registry, no opt-in.

**Layer 2 — Tufte profiling (opt-in).** When timing says "slow" and
the agent wants to know *why*, they enable Tufte. The hook point is
the existing `:seon.dev/instrumentation` Integrant layer that
already wraps every `:malli/schema`-annotated public fn for runtime
validation; when profiling is enabled, the same wrapper additionally
wraps each fn with `(taoensso.tufte/p ::ns/name body)`. Every public
fn — substrate AND agent-authored — flows through this seam, because
`seon.code/check` requires `:malli/schema` before a fn is persisted.

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

Off by default because `tufte/p` at ~50ns per call adds up in tight
loops — cheap `Date.now()` deltas carry the routine signal, Tufte is
the precision tool the agent reaches for when routine signal points
at a problem.

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

```text
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
2. Retract the entity from datahike. The retracting transact carries
   `:tx-meta {:seon.eval/id <id>}`, so the eval entry and the
   retraction datoms share a tx-id.
3. `ns-unmap` the var (or `seon.schema/unregister!` for a schema, or the
   analog for a test).
4. Surface dependents (entities whose source references the forgotten
   symbol) as warnings on the next render.

Forgetting a default brought in by bootstrap is allowed — the next
`(seon.render/reset-defaults!)` brings it back. There is no
forget-refusal.

**Reversibility is derived, not stored.** A small classifier runs at
render time over each eval entry and decides reversibility from the
datoms its tx wrote (read via `(d/history db)`):

| Eval shape | Reversible? | Mechanism |
|---|---|---|
| Tx wrote assertions on persistent entities, no atom/capability calls in `:source` | Yes | retract each asserted entity + ns-unmap (or unregister) |
| `:ns` differs from previous eval's `:ns`, tx wrote no other datoms | Yes | re-eval the previous eval's source to restore the old ns |
| Tx wrote retraction datoms | Partial | the entity can be re-defined by re-evaluating its source |
| `:source` calls `swap!`/`reset!` or a WIT capability | No | state already mutated; no recorded "before" |
| Plain expression — tx wrote no datoms beyond the eval entity itself, no mutating call | Yes | no side effects to undo |

The renderer surfaces "↶ reversible" / "✘ irreversible" alongside each
recent-evals entry so the agent always knows which steps can be cleanly
walked back. Classifier lives next to the renderer (`seon.render.default`),
not in the eval log — it can be replaced by registering a more specific
classifier without a schema change.

## Boot sequence

```text
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
5. For each entity, eval its `:source` in the entity's home ns. The
   replay transact carries `:tx-meta {:seon.eval/id <id>}` so the
   eval entry and the persistent datoms share a tx-id.
6. If an eval throws during replay, its eval-log entry carries
   `:ok? false`. The renderer surfaces it as a warning ("X failed
   to replay this session — fix or forget") with the source available
   for inspection. The entity stays in the DB unchanged; nothing is
   retracted automatically.

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
- Datahike attribute-schema changes are constrained: `:db/valueType`
  is immutable; `:db/unique` cannot be removed; `:db/cardinality
  :one→:many` is allowed only when no `:db/unique` is set. The
  baseline attribute schema in `seon.schema/register!` calls should
  be treated as non-negotiable across versions; extensions add new
  attrs, never re-type existing ones. See [[#^d3]].

## MVP scope

### In

- The database attributes defined above (`:seon.ns/*`, `:seon.fn/*`,
  `:seon.schema/*`, `:seon.test/*`, `:seon.eval/*`, `:seon.ctx/*`).
- `seon.repl/eval-batch!` — runs the forms-and-comments
  read/eval/transact pipeline (rewrite-clj for parse; see "Parse:
  forms-and-comments"). Trailing comments without a following form
  become a thinking-only eval entry. Bare symbols are forms.
- `seon.repl/forget!` + `seon.schema/unregister!`.
- `seon.render.default/*` — six default section functions
  (system, related-ns, current-ns, warnings, recent-evals, prompt)
  plus the `truncate-edn` helper (`pretty-ai` already exists). The
  prompt section renders the trailing `:current.ns=> ; turn N` line
  so the agent's view ends like a real Clojure REPL prompt.
- `seon.render/explain-section`, `reset-defaults!`.
- Per-eval timing + Tufte hook per "Self-instrumentation"; optional
  `perf-section` formats Tufte stats on demand.
- Current time in `system-section`. User-timezone lookup is post-v1
  (see "Out" below).
- **Targeted test auto-run** ([[#^d6]]): every eval whose tx
  asserted datoms on a `:seon.fn` entity post-fires the tests that
  target that fn (reverse ref via `:seon.test/target`). Test
  entities' `:last-passed-at` / `:last-failed-at` / `:last-failure`
  update. The `failing-test-warning` predicate surfaces any test
  whose latest run failed.
- **Spec-violation warning** ([[#^d4]]): when a schema is
  redefined, validate existing data against the new shape;
  violations become warnings. No reject.
- **Def-not-persisted warning** ([[#^d5]]): bare `(def x …)`
  outside `defn`/`schema/register!`/`deftest` surfaces a warning.
- Bootstrap + resume phases per "Boot sequence".
- Per-form independent transacts (partial-success preservation).
- Eval classification implicit via the history query over each
  eval's tx + `:ok?` boolean + cross-eval `:ns` comparison — no
  classifier enum to maintain.

### Out

- WASM-side wiring (M2 — the WIT `eval-form` export calls into this; pipeline
  itself runs in V0 Node pod first for testing)
- Multi-agent ownership (single-agent assumption for MVP)
- Baseline reconciliation (m6 capability — comes after MVP)
- Result-value retention across sessions (eval-ids reference values that
  don't survive pod restart; agent re-evals if needed)
- Token budgeting for the renderer (no compression beyond truncate-edn)
- Auto-run on **dependent**-change (i.e. fn B's tests fire when fn A
  that B depends on changes). MVP runs only tests targeting the
  directly-modified fn; transitive triggering follows. See [[#^d6]].
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
2a. Submit a response that uses `;;` multi-line markdown
   (`;; ## Plan\n;; - step one\n;; - step two`) between forms; see
   the comments captured as `:seon.eval/narration` on the next
   form's entry, with the markdown formatting preserved verbatim.
   Trailing `;;` comments after the last form land as a
   thinking-only eval entry.
2b. Eval a bare symbol that resolves (e.g. just `analyze` after it
   was defined); see its value returned. Eval a bare symbol that
   doesn't resolve; see the natural unbound-symbol error in the
   eval log (`:ok? false`, kind `:compile`).
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
  `:seon.schema/key` this spec uses. The CLJS-pod attrs run as a
  separate (parallel) family of entities for now.
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

## Out-of-scope but adjacent

- **Benchmarks under WASM**: `pod-host/datahike-harness` workloads
  ported to CLJS. Follows MVP.
- **Multi-agent**: when does ownership matter? `:seon.fn/owner-agent`
  attribute is the next addition; MVP single-agent.

## Decision details

The "Decisions pending" dashboard at the top is the index. Each item
below is the full discussion. Anchor IDs are stable across edits;
refer by id. Each heading uses both an HTML anchor (for GFM) and an
Obsidian block-id (for `[[#^dN]]` links in this vault).

### <a id="d1"></a>D1 — Add `rewrite-clj` to deps ^d1

rewrite-clj preserves `:comment` nodes alongside form nodes
(verified in `reference-code/rewrite-clj/src/rewrite_clj/node/
comment.cljc`). Edamame drops comments at the reader
(`edamame.impl.parser/parse-comment`). The forms-and-comments parse
the spec describes requires rewrite-clj.

Confirm the version bundled in `reference-code/rewrite-clj/` works
with bootstrap-CLJS + shadow-cljs. Small dep addition; should be
straightforward.

### <a id="d2"></a>D2 — Bare-symbol unbound-symbol error shape ^d2

The forms-and-comments design relies on the eval surface throwing a
clean unbound-symbol error for undefined references rather than us
inferring "this was probably prose."

Confirm: when bootstrap-CLJS `eval-str` is asked to evaluate `Let`,
it returns `{:ok false :error <unbound>}` rather than a vague
compile failure. Test against the actual compile-state behavior;
adjust the `:error` payload's `:kind` field accordingly (`:compile`
vs `:runtime`).

### <a id="d3"></a>D3 — Older DB on newer runtime upgrade strategy ^d3

Sketched but not designed: detect substrate version delta, merge
missing-from-DB bootstrap entries (lookup by identity), surface
agent overrides that conflict with the new substrate as warnings
with diffs. Out of MVP scope but the data model must permit it.

Solve [[#^d12]] first — once the substrate analyzer emits a clean
ordered bootstrap vector, upgrades follow naturally (diff old
vector against new; transact the additions).

### <a id="d4"></a>D4 — Per-kind redefinability rules ^d4

The three persistent kinds have different redefine rules because
they have different relationships to stored data:

**Specs (`:seon.schema/*`).** Strict.

- No data uses the spec yet (no `[?e ::foo _]` datoms) → replace
  freely.
- Data exists AND the change is **accretive** (add optional key;
  loosen constraint; widen union; add to enum) → replace.
- Data exists AND the change is **breaking** (remove key; narrow
  type; tighten constraint; change cardinality; remove from enum) →
  **reject**. Eval entry is `:ok? false` with a clear error showing
  which entities would be invalidated. The agent's recourse is
  `seon.repl/remove-spec ::foo` first (which is itself rejected if
  data uses it; explicit migration is the only path).
- Underlying datahike attribute type (`:db.type/string` etc) is
  immutable in either case. Spec changes that would require a
  datahike-level retype are rejected with that specific message.

**Functions (`:seon.fn/*`).** Flexible.

- Always allowed to redefine. The agent is iterating.
- Targeted tests auto-run on the new definition ([[#^d6]]); failing
  tests surface as warnings.
- Callers that reference the fn keep working (the var binding
  updates).
- No data-validity check — fn definitions aren't stored data, just
  source.

**Tests (`:seon.test/*`).** Flexible.

- Always allowed to redefine.
- The new test runs on the next define-or-redefine of its target fn
  (or immediately on redefine of the test itself, since redefining
  a test = re-eval'ing it = same trigger).

The spec instrumentation layer (Malli runtime validation per
CLAUDE.md "Function Instrumentation") stays on always. After a
spec redefine, the next call to any fn using that spec will throw
at the call site if shapes don't match — that's the in-call
feedback channel. After a fn redefine, targeted tests fire. After
a test redefine, the test fires. All three give immediate
feedback without the agent asking.

### <a id="d5"></a>D5 — `(def …)` not-persisted warning (no regex) ^d5

Agents reach for `(def !x (atom …))` because of a known
bootstrap-CLJS gotcha (bare-value defs don't resolve across
eval-str calls; see `src/seon/eval.cljs` opening docstring). The
defs eval fine but **aren't persisted** — pod restart loses them.

Detection uses the parsed form, not a regex. Since rewrite-clj
already gives us the form structurally ([[#^d1]]), the check is:

```clojure
(defn- def-not-persisted? [parsed-form]
  (and (seq? parsed-form)
       (= 'def (first parsed-form))
       (symbol? (second parsed-form))))
```

That's it. `def` as the head symbol, a symbol as the name. `defn`
doesn't match because `defn` isn't `def`. `(let [x …])` doesn't
match. No regex.

Warning predicate then queries recent evals where `:source` was a
plain `(def …)`, cross-checks against persisted entities'
`:source` for references to the var name, and emits two tiers:
`:warn` (sitting around) and `:error` (a persisted fn's source
references it; restart will break).

Dependent-finding ALSO uses the AST, not text search: each
`:seon.fn/source` parses via rewrite-clj; we walk the form looking
for symbol-references that match the orphan `def`'s name. (Same
analyzer surface as [[#^d10]] / [[#^d12]].)

### <a id="d6"></a>D6 — Targeted test auto-run on every define / redefine ^d6

Tests run automatically every time a function is defined or
redefined — and ONLY the tests targeting that specific function.
The agent never has to say "run tests." If the tests pass, the
warnings tile says nothing. If any fail, the warnings tile shows
a summary of what broke, with the full failure output reachable
via a runtime var the agent can dig into.

**Triggering.** A `:seon.eval` entry whose tx asserted datoms on a
`:seon.fn` entity fires a post-eval hook. The hook queries every
`:seon.test` entity whose `:seon.test/target` ref resolves to that
fn. Run each. Update each test's `:last-passed-at` /
`:last-failed-at` / `:last-failure`.

**Why this works cheaply.** Tests target one fn each via
`:seon.test/target → :seon.fn`. The reverse index is one datalog
query. Most fns have a couple tests. Post-eval cost is "run the
few tests for the one fn that just changed."

**Runtime var stash.** Full output of the test run lives on
globalThis under a stable id (same mechanism as eval-id results).
The agent fetches via `(result :<test-run-id>)` if they want to
see assertion-by-assertion detail. The warnings tile just shows
the summary: "3 of 4 tests passed for analyze; 1 failed
(analyze-empty-ticker)" + the failure's `:last-failure` message.

**Warning predicate.** `failing-test-warning` queries every test
where `:last-failed-at > :last-passed-at` (or `:last-passed-at`
is nil). Each becomes one warning. Pure derivation from
current test-entity state.

**Manual runs.** `(seon.test/run-all)` runs every persisted test.
`(seon.test/run-fn 'fn-sym)` runs just that fn's tests.
`(seon.test/run 'test-sym)` runs one by name. But the common
case — write a fn, tests run, warnings render next turn — needs
nothing from the agent.

**Instrumentation interplay.** Spec instrumentation (Malli
runtime validation per CLAUDE.md "Function Instrumentation")
stays on always. That's the in-call feedback: "you called fn X
with wrong shape → throws at the call site." Test auto-run is
the post-define feedback. Both always on; the agent doesn't ask
for either.

In MVP, default-on.

### <a id="d7"></a>D7 — `(forget!)` for namespaces ^d7

Currently `(forget! 'sym)` works on functions / schemas / tests via
their identity attr. Should it also work on a whole `:seon.ns/*`
entity? Semantics:

- Retract the `:seon.ns` entity.
- Cascade: retract every `:seon.fn`/`:seon.schema`/`:seon.test`
  entity whose ns-prefix matches.
- `ns-unmap` each member; `goog.object/remove` the ns namespace
  object from globalThis.
- The whole cascade transacts in one tx with `:tx-meta
  {:seon.eval/id <id>}` so the retractions and the eval entry
  share a tx-id.

Useful when the agent decides an entire experiment-namespace is
trash. MVP-include or defer?

### <a id="d8"></a>D8 — Explicit remove-spec / remove-fn / remove-test ^d8

Three explicit verbs, one for each persistent kind. Each takes a
map specifying what's being removed and refuses (with a clear
warning) when the removal would break invariants.

```clojure
(seon.repl/remove-spec {:seon.schema/key ::ticker})
;; -- if no datoms use the spec: retract the :seon.schema entity,
;;    unregister from the Malli registry.
;; -- if data exists: refuse with the list of affected entities.
;;    "Cannot remove ::ticker; 12 entities use it. Migrate or
;;     accept-cascade-retract first."

(seon.repl/remove-fn {:seon.fn/sym "seon.trading/analyze"})
;; -- retract the :seon.fn entity, ns-unmap the var, retract any
;;    :seon.test entities whose :target was this fn (the targets
;;    no longer exist; the tests are stale).
;; -- the retracting transact carries :tx-meta {:seon.eval/id <id>},
;;    so the eval entry and the retraction datoms share a tx-id.

(seon.repl/remove-test {:seon.test/sym "seon.trading/analyze-example"})
;; -- retract the :seon.test entity, ns-unmap the deftest var.
```

Explicit verbs are clearer to the agent and easier to teach in the
system-section (one example each).

The reversibility classifier in the "Forget" section is used by the
targeted-test auto-run and the warning predicates to explain what
would break if you removed something, not as a do-anything `undo`.

### <a id="d9"></a>D9 — `<name>-example` test convention ^d9

A function persists as `:seon.fn/*`. The warnings tile runs a
no-test predicate at render time — for every `:seon.fn`, check
whether any `:seon.test/target` resolves to it; if not, emit a
warning. Nothing about "no test coverage" is stored on the fn
entity; the warning is the result of the query against the current
graph. The moment a test targeting the fn lands, the next render
omits the warning.

Convention to clear the warning fast:

For a fn `seon.trading/analyze`, the agent writes a test named
`seon.trading/analyze-example` that exercises the documented happy
path. The pattern is:

```clojure
(defn analyze
  "Compute the trading signal for a ticker."
  {:malli/schema [:=> [:cat ::analyze-req] ::analyze-resp]}
  [{::keys [ticker]}]
  {::signal :hold ::confidence 0.5})

(deftest analyze-example
  (is (= {:seon.trading/signal :hold
          :seon.trading/confidence 0.5}
         (analyze {:seon.trading/ticker "AAPL"}))))
```

Why `-example`: it's a documented use-case the agent (and future
agents reading the persistent entities) can look at. It demonstrates
shape, intent, and at least one passing input. Edge-case tests live
under other names; `*-example` is the canonical "this is what calling
this fn looks like."

The warning predicate looks for `:seon.test/target` matches; it
doesn't care about the name. The convention is for human + LLM
readability, not enforcement.

### <a id="d10"></a>D10 — Reference-graph attrs ^d10

Confirming the schema shape:

```clojure
;; Function entity gains reference attrs
::seon.fn/input-spec   :seon.db/ref                          ; → :seon.schema entity
::seon.fn/output-spec  :seon.db/ref                          ; → :seon.schema entity
::seon.fn/refs         [:vector :seon.db/ref] {:optional true}  ; other :seon.fn entities this fn calls

;; Test entity already has
::seon.test/target     :seon.db/ref                          ; → :seon.fn entity
```

`:seon.fn/refs` is populated at define-time by the analyzer walk:
parse `:seon.fn/source` with rewrite-clj, walk the AST, collect
every qualified symbol that resolves to a `:seon.fn`, emit those
as refs. Reverse-index via datalog gives "who calls X" for free.

Spec dependencies (a `:seon.schema/source` like
`(schema/register! ::foo [:map [::bar :string]])` references
`::bar`) are similarly extracted — the analyzer walks the
registered Malli schema for keyword references to other registered
schemas. New attr:

```clojure
::seon.schema/refs  [:vector :seon.db/ref] {:optional true}  ; other :seon.schema entities this schema uses
```

The reference graph is what makes the targeted-test auto-run
([[#^d6]]) and the spec-violation check ([[#^d4]]) cheap: every
"who is affected by changing X" question is one datalog query
over the reverse index.

### <a id="d11"></a>D11 — Forgiving parse recovery ^d11

The parser surface needs to be helpful when the agent writes
something partially-broken. Current spec already says
"continue with the next top-level form" on parse-fail (in §"Parse
failures"); this decision is about how aggressively we recover.

Proposal:

- rewrite-clj parses the top-level form; if it throws, capture the
  source up to the next balanced top-level boundary as the failing
  chunk's `:source`.
- Recovery boundary detection: scan forward token-by-token tracking
  paren/bracket/brace depth; when depth returns to 0 AND a newline
  follows, that's the next boundary.
- The failing chunk becomes one `:ok? false` eval entry with the
  parse error as `:error`. Subsequent forms parse normally.

A more ambitious recovery — try to extract sub-forms from a chunk
that contains both valid and invalid pieces — is out of MVP scope.
The simple "skip to next boundary" path covers the common case
(agent wrote a typo in form N; forms N+1, N+2 are fine).

Open question: when the recovery boundary detection itself runs
out of input (the agent's whole response after the broken form is
an unclosed paren spanning EOF), the whole tail becomes one
`:ok? false` entry. Is that the right behavior? I think yes
(the agent sees one error and knows where to look) but worth
confirming.

### <a id="d12"></a>D12 — Topological bootstrap emission ^d12

The substrate is compiled CLJS. To seed the agent's DB on first
boot, we need a `bootstrap.edn` containing every substrate
`:seon.ns` / `:seon.schema` / `:seon.fn` / `:seon.test` /
`:seon.ctx` entity in correct dependency order. The "topological"
problem: an entity can't reference (via `:seon.fn/refs` or a
spec-key reference) something that hasn't been transacted yet.

Solve in two passes:

1. **Walk the substrate source.** For each `.cljs` file we ship,
   parse with rewrite-clj, extract every top-level `(defn)` /
   `(schema/register!)` / `(deftest)` / `(ns …)` form. Build a
   provisional entity for each, with a placeholder `:refs` list.
2. **Resolve references and toposort.** Walk each entity's
   parsed source AST; turn each name-reference into a ref to the
   provisional entity. Toposort by depends-on. Output the ordered
   vector to `resources/seon/bootstrap.edn`.

If we do (1) and (2) right, indexing future agent work is the
same code — the substrate is just data the analyzer happens to
see first. No special "compile vs runtime" path.

Solve this BEFORE worrying about [[#^d3]] (older-DB-on-newer-runtime).
Once we have the analyzer walk producing a clean ordered vector,
upgrades follow naturally (diff old vector against new; transact
the additions).
