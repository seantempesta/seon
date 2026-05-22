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
  exists. See [[#^d7]].
- **`(def …)` outside `defn`/`schema/register!`/`deftest` is
  scratch.** Warning surfaces; the value lives in the process but
  won't survive restart ([[#^d3]]).
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
([[#^d4]]) and what makes "who depends on X?" answerable in one
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

**Settled 2026-05-22** — driven by the three research artifacts in
`research/2026-05-22/*`. Each is fully reflected in the spec body
above; the detail blocks below are kept as design archaeology.

- ✅ **[[#^d11]]** — `:seon.agent/ctx` is a cardinality-many ref
  marked `:db/isComponent true`. Recursive pull inlines ctx entities;
  retracting the agent cascades through. Section fns live in
  `seon.agent`. V1 has NO dynamic dispatch.
- ✅ **Causality graph as forward-ref component chain** (new — was not
  a D-number). Agent → Session → Turn → Messages/Evals via
  cardinality-many component refs. One pull walks everything.
- ✅ **`:seon.turn/prompt-blob` persists the rendered context per
  turn** (new). Playback requires it; deriving-on-replay is broken
  because rendering code mutates faster than data.
- ✅ **`:seon.blob` content-addressed archival storage** (new). For
  prompts, full eval results, and future agent ingest of large docs.
  Bytes on disk at `<pod-data>/blobs/<hash[:2]>/<hash>.zst`.
- ✅ **Auto tx-meta causality bundle** (new) via
  `seon.db/*tx-context*` dynvar. Every tx in an eval scope carries
  `{:seon.db/agent-id, :seon.db/session-id, :seon.db/turn-id,
  :seon.db/eval-id, :seon.db/origin}` without manual plumbing at
  each call site.
- ✅ **Drop `:seon.test/last-passed-at` / `:last-failed-at` /
  `:last-failure`.** Tag test-run txs with `:seon.eval/test
  [:seon.test/sym …]` in tx-meta; "latest pass" is one `d/history`
  query. Three denormalized fields collapse to zero stored state.
- ✅ **`:seon.fn/ns` is a ref to `:seon.ns`** (not a keyword
  prefix). Same for `:seon.schema/ns`, `:seon.test/ns`. Plain refs
  (not components) — child does not own parent. Forget-ns is
  explicit retract of all referencing entities.
- ✅ **Drop `seon.agent/my-*` helpers in favor of bare names** with
  `seon.agent/*id*` dynvar binding. The graph IS the API; one
  helper (`root-pull`) covers discovery.

**Open — design questions:**

- **[[#^d1]]** — Older-DB-on-newer-runtime upgrade. Deferred; focus
  on bootstrap-from-compiled-code first ([[#^d10]]).
- **[[#^d2]]** — Per-kind redefinability rules (specs / fns /
  tests).
- **[[#^d3]]** — Detect `(def …)` via rewrite-clj AST (no regex).
- **[[#^d4]]** — Targeted test auto-run wiring + warning predicate
  + runtime-var stash. Use `d/listen!` for the trigger — no custom
  hook system (see `research/datahike-capabilities-2026-05-22.md`
  §3).
- **[[#^d5]]** — `(forget!)` for whole namespaces. Implementation:
  explicit query for entities pointing at the ns ref, retract each
  in one tx, then retract the ns itself. Not component-cascade
  (child doesn't own parent).
- **[[#^d6]]** — Explicit `seon.repl/remove-spec`, `remove-fn`,
  `remove-test`.
- **[[#^d7]]** — `<name>-example` test convention as the documented-
  happy-path stub.
- **[[#^d8]]** — Reference-graph attrs (`:seon.fn/refs`,
  `:seon.fn/input-spec`). Deferred until the V0 pod actually has an
  analyzer-walk producing the data (currently empty). Confirmed by
  Gemini analysis as V1.2+ work.
- **[[#^d9]]** — Forgiving parse recovery on parse-error; advance
  to next balanced top-level form.
- **[[#^d10]]** — Topological `bootstrap.edn` emission at substrate
  build time. (Resume topo at runtime is solved: sort by datahike
  tx-id; see Resume phase.)
- **D12 (new)** — Blob GC / retention policy. Deferred to V2; v1 lets
  the blob store grow unbounded. Sweep design: retract blob metadata
  + delete file when no entity refs the hash.
- **D13 (new)** — WASM-boundary survival of `seon.db/*tx-context*`
  dynvar. Risk: if a tx is enqueued in one "thread" and applied on
  another (flow message-passing model in wasm32), the binding may
  not propagate. Spike before Phase 3 cutover.

The detailed notes for each live in [Decision details](#decision-details) at the
bottom. [Known issues](#known-issues) at the very bottom tracks bugs
the implementation needs to chase that aren't spec questions.

## Verified live in the V0 pod

Every claim below has been exercised end-to-end against the running
CLJS pod via `mcp__seon_cljs__eval`. Source citations + REPL probes
recorded in commit history.

- **rewrite-clj preserves comments + forms as ordered nodes.**
  `(rewrite-clj.parser/parse-string-all ";; hi\n(+ 1 2)\n")` returns a
  `:forms` root whose `:children` include `:comment` and `:list` nodes
  in source order, with full text including trailing newlines.
- **Bare-symbol undeclared-var rejects loudly.**
  `(seon.eval/eval state "Let")` returns
  `{:ok false :error "undeclared undeclared-var: cljs.user/Let"}`.
  Powered by a `set!`+restore on
  `cljs.analyzer/*cljs-warning-handlers*` + a `truly-undeclared?`
  resolver that checks globalThis. See `src/seon/eval.cljs:117-243`.
- **Unqualified core vars resolve.**
  `(seon.eval/eval state "(reduce + (range 10))")` returns
  `{:ok true :value 45 :ns cljs.user}`. Powered by an explicit
  `cljs.js/load-analysis-cache!` call in `init-bootstrap!` (see
  [analyzer-cache load](#analyzer-cache-load) below).
- **tx-meta is the eval-id pointer.** A datahike transact with
  `:tx-meta {:seon.eval/id "..."}` makes the eval-id queryable as
  a datom on the tx-id entity:
  `(d/q '[:find ?tx :where [?tx :seon.eval/id ?eid]] db "...")`
  returns the tx-id. The eval entity and tx entity coincide.
- **Topological replay is `sort-by tx-id`.** Datahike tx-ids are
  strictly monotonic; entity ordering follows creation order, which
  is a valid topo order by construction (lookup-ref forward
  references would have failed at write time). No analyzer-walk
  needed for resume.

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

## The agent record is the hub

The library is built around serving agents. The mental model is
**start at your record; walk the graph forward; everything you've
ever seen, said, or done is one nested pull away.**

```clojure
(seon.agent/root-pull)
;; => {:seon.agent/id        "AbCdEfGh1234"
;;     :seon.agent/state     :idle
;;     :seon.agent/current-ns :seon.trading
;;     :seon.agent/sessions
;;       [{:seon.session/id            "S1abcdef1234"
;;         :seon.session/at            #inst "..."
;;         :seon.session/turn-count    7
;;         :seon.session/turns
;;           [{:seon.turn/id       "T1aaaaa1111"
;;             :seon.turn/index    6
;;             :seon.turn/status   :done
;;             :seon.turn/prompt-blob {:seon.blob/hash "..." :seon.blob/size 24891}
;;             :seon.turn/messages [{:seon.message/role :user      :seon.message/content "..."}
;;                                  {:seon.message/role :assistant :seon.message/content "..."}]
;;             :seon.turn/evals    [{:seon.eval/id "K9p2x4nB7q" :seon.eval/source "..." :seon.eval/ok? true ...}
;;                                  ...]}
;;            ...]}
;;        ...]
;;     :seon.agent/ctx
;;       [{:seon.ctx/name :system        :seon.ctx/priority 10 :seon.ctx/fn 'seon.agent/system-section}
;;        {:seon.ctx/name :current-ns    :seon.ctx/priority 30 :seon.ctx/fn 'seon.agent/current-ns-section}
;;        ...]}
```

The forward-ref chain Agent → Sessions → Turns → Messages/Evals is
component'd (`:db/isComponent true` on each step), so a recursive pull
inlines the child maps rather than returning `{:db/id N}` placeholders.

What an agent owns and where it lives:

| State | Location | How agent reaches it |
|---|---|---|
| **Identity + liveness** (`:state`, `:current-ns`) | on the agent record | `(seon.agent/root-pull)` |
| **Ctx layout** | `:seon.agent/ctx` cardinality-many component ref | included in `root-pull`; also `(seon.agent/ctx)` |
| **Sessions / turns / messages / evals** | component'd off the agent | included in `root-pull`; targeted accessors: `(messages)`, `(evals)`, `(current-turn)`, `(current-session)` |
| **Home ns** | `seon.agent.<id>` (deterministic) | already in it; `(in-ns)` to switch |
| **Result stash** | globalThis keyed by eval-id | `(result :<eval-id>)` |
| **Big artifacts** (full prompts, full results, ingested docs) | `:seon.blob` entities + bytes on disk | `(seon.blob/get hash)` from a blob-ref attr (`:seon.turn/prompt-blob`, `:seon.eval/result-blob`) |

What's NOT agent-owned and stays globally shared:

- `:seon.fn/*`, `:seon.schema/*`, `:seon.test/*`, `:seon.ns/*` — the
  program graph. All agents collaborate on the same fns / schemas /
  tests. "No ownership boundary" per the lifecycle rule above.
- `:seon.blob` — content-addressed; identical content is stored
  once regardless of which agent wrote it first.

### Self-recovery

If an agent borks their context (their custom ctx fn throws, returns
non-string, returns nothing useful), reset is a single transact on
their own record:

```clojure
(seon.agent/reset-ctx!)
;; equivalent to:
(seon.db/transact!
  {:seon.db/tx-data [[:db/retract [:seon.agent/id <id>] :seon.agent/ctx]
                     {:seon.agent/id <id>
                      :seon.agent/ctx <default-component-maps>}]})
```

The explicit retract precedes the add — cardinality-many ref
attributes accumulate on upsert otherwise (see datahike
`db/transaction.cljc:557-572` semantics). Because `:seon.agent/ctx` is
`:db/isComponent true`, the retracted ctx entities are
auto-cleaned-up.

The default ctx refs point at `seon.agent/*-section` fns (substrate-
shipped, always present). Nothing the agent does to their home ns
`seon.agent.<id>` can break that. Render mechanism never crashes;
worst case the agent gets the substrate's default view back.

### Helper fns in `seon.agent`

Bare names — no `my-` prefix. The current agent is supplied by the
`seon.agent/*id*` dynvar that `eval-batch!` binds for the duration of
each form's eval. Inside agent code, no id-threading required.

```clojure
;; Discovery — one helper to rule them all.
(seon.agent/root-pull)             ; nested pull of agent + sessions + turns + messages + evals + ctx
(seon.agent/root-pull {:depth 1})  ; just the agent record, no walk

;; Bounded tails — convenience over root-pull when you only want one slice.
;; Defaults: n=20, current session only, oldest-first.
(seon.agent/messages)              ; current session's messages
(seon.agent/messages {:n 5})
(seon.agent/evals)                 ; current session's evals
(seon.agent/logs)                  ; current session's log entries
(seon.agent/sessions)              ; all sessions for this agent, lightweight (no nested turns)
(seon.agent/current-session)       ; the latest session entity (with turn-count etc.)
(seon.agent/current-turn)          ; the latest turn in the current session

;; Ctx layout management.
(seon.agent/ctx)                   ; the agent's ctx vector, sorted by priority
(seon.agent/reset-ctx!)            ; revert ctx to substrate defaults (recovery escape hatch)
(seon.agent/update-ctx! f)         ; transact (f current-ctx) onto the agent record

;; Identity (read the dynvar — useful in custom section fns and tests).
(seon.agent/id)                    ; current agent's id (from *id* dynvar; nil if unbound)

;; Explicit override — every accessor accepts {:seon.agent/id "other"} as a
;; second-arg map for inspecting another agent (or in tests).
(seon.agent/messages {:n 10 :seon.agent/id "other-agent-id"})
```

The bodies are short (mostly 2–3 lines wrapping `d/pull` with a
reverse-ref pattern). The agent reads the implementations in their
home ns and learns the pull syntax underneath — the helpers are
ergonomic shortcuts over the graph, not opaque wrappers around it.

For everything not covered by a helper, the agent writes datalog
queries directly. The three pull patterns in **Pull patterns the
agent should learn day one** (above) cover the vast majority of
diagnostic queries.

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

Single database. Three logical layers.

1. **Causality graph** — the agent and its lived history: who, in
   which session, on which turn, said/did what. A forward-ref
   component chain: `:seon.agent` → `:seon.session` → `:seon.turn` →
   `:seon.message` + `:seon.eval`. One nested pull from the agent
   walks every turn, every message, every eval the agent has ever
   produced. This is the **observability/playback** layer.
2. **Program graph** — the code the agent has written and curated:
   namespaces, functions, schemas, tests. Plain refs to `:seon.ns`
   from fn/schema/test entities; reverse-ref pulls do namespace-scoped
   discovery in one step. This is the **persisted-program** layer.
3. **Archival blobs** — content-addressed, compressed-on-disk byte
   storage for large content the agent's runtime context doesn't need
   to see (rendered turn prompts, full eval results, future agent
   ingest of large docs). Datahike entities hold the metadata + hash;
   bytes live at `<pod-data>/blobs/<hash[:2]>/<hash>.zst`.
   `:seon.blob` is referenceable from any entity. See the **Blob
   storage** section below.

The three layers share the same database and the same tx-meta
causality bundle (see **Tx-meta — every tx is forensically
traceable** below).

### Causality graph — "who saw what, said what, did what"

The agent record is the root. Pulling it walks the world.

```clojure
;; Agent — root of one agent's causality graph.
;; Scalars are :db/noHistory true (turn-count etc. churn each turn — don't accumulate history).
::seon.agent/id            [:seon.id/id {:seon.db/identity true}]   ; 12-char base62
::seon.agent/state         [:enum :idle :running] {:seon.db/no-history? true}
::seon.agent/current-ns    :keyword {:optional true :seon.db/no-history? true}
::seon.agent/sessions      [:vector :seon.db/ref {:seon.db/component true}]   ; → :seon.session, cardinality-many
::seon.agent/ctx           [:vector :seon.db/ref {:seon.db/component true}]   ; → :seon.ctx (D11)

;; Session — one run from boot to halt. Holds transient counters
;; that USED to live on the agent (turn-count, turns-since-user).
::seon.session/id              [:seon.id/id {:seon.db/identity true}]
::seon.session/at              :inst                                          ; session start (also = :db/txInstant of the create-session tx)
::seon.session/turn-count      :long {:seon.db/no-history? true}
::seon.session/turns-since-user :long {:seon.db/no-history? true}
::seon.session/interrupted?    :boolean {:optional true :seon.db/no-history? true}
::seon.session/turns           [:vector :seon.db/ref {:seon.db/component true}] ; → :seon.turn

;; Turn — one full render → LLM → parse → eval-batch cycle.
;; The causality container: what the LLM saw, what it said, what got run.
::seon.turn/id            [:seon.id/id {:seon.db/identity true}]
::seon.turn/index         :long                                              ; monotonic within the session (0, 1, 2, ...)
::seon.turn/at            :inst                                              ; turn start
::seon.turn/status        [:enum :running :done :error]
::seon.turn/prompt-blob   :seon.db/ref {:optional true}                      ; → :seon.blob — the literal rendered context the LLM saw
::seon.turn/messages      [:vector :seon.db/ref {:seon.db/component true}]   ; → :seon.message (user + assistant in chronological order)
::seon.turn/evals         [:vector :seon.db/ref {:seon.db/component true}]   ; → :seon.eval (each form from the assistant message's parse)

;; Message — one LLM-side or user-side message tied to a turn.
;; Already implemented in src/seon/agent.cljs:105-109; hoisted into spec.
::seon.message/id       [:seon.id/id {:seon.db/identity true}]
::seon.message/role     [:enum :user :assistant :system]
::seon.message/content  :string
::seon.message/at       :inst

;; Eval — one form's read/eval/result. Each one ALSO becomes its own tx
;; via tx-meta {:seon.db/eval-id <id>}, so history queries answer
;; "what did this eval write?".
::seon.eval/id            [:seon.id/id {:seon.db/identity true}]
::seon.eval/at            :inst                                              ; epoch ms before eval starts
::seon.eval/duration-ms   :long                                              ; wall clock incl. auto-await
::seon.eval/ns            :keyword                                           ; namespace the form LEFT the agent in
::seon.eval/narration     :string {:optional true}                           ; leading ;; comments captured by parse-forms
::seon.eval/source        :string                                            ; the form text (or unparseable chunk)
::seon.eval/ok?           :boolean                                           ; reader + eval both succeeded
::seon.eval/result-edn    :string {:optional true}                           ; pr-str of result, TRUNCATED (2 KB) for renderer display
::seon.eval/result-blob   :seon.db/ref {:optional true}                      ; → :seon.blob — full untruncated result (when result-edn was truncated)
::seon.eval/error         :string {:optional true}                           ; pr-str of error payload on failure
```

**One pull walks the whole agent.**

```clojure
(d/pull db
  '[*
    {:seon.agent/sessions
     [*
      {:seon.session/turns
       [* {:seon.turn/messages [*]
           :seon.turn/evals    [*]}]}]}
    {:seon.agent/ctx [*]}]
  [:seon.agent/id "AbCdEfGh1234"])
```

Components inline (not `{:db/id N}` placeholders), so the result is a
single nested map. The agent learns this pattern day one in the
`system-section`. Backref helpers are not the primary discovery
mechanic — forward walks are.

**Why `:seon.agent` no longer carries `turn-count` / `turns-since-user`.**
Those are session-scoped counters. Moving them onto the session entity
where they belong stops the agent record from drifting into a bag of
transient scalars. The agent record now carries only its identity, its
liveness flag, its current ns (a per-eval cursor), and its
forward-ref vectors.

**Why `:seon.eval` no longer carries an explicit `:seon.eval/agent`
ref.** The eval is component'd off the turn, the turn off the session,
the session off the agent. The chain is unambiguous. Tx-meta also
carries `:seon.db/agent-id` on the eval's tx, so playback joins still
work. Removed denormalization.

### Program graph — "the code the agent has written"

The persistent code the agent curates lives in a separate cluster:
namespaces, functions, schemas, tests. Functions reference their
namespace by ref (not by string-prefix), so namespace-scoped queries
are one reverse-ref pull.

```clojure
;; Namespaces — one entity per agent-defined or substrate ns.
;; The full (ns …) form (including :require clauses) is the :source.
::seon.ns/name    [:keyword {:seon.db/identity true}]   ; :seon.trading.signals
::seon.ns/source  :string                               ; "(ns seon.trading.signals (:require [seon.db :as db]))"

;; Functions
::seon.fn/sym     [:string {:seon.db/identity true}]    ; "seon.trading/analyze"
::seon.fn/ns      :seon.db/ref                          ; → :seon.ns (plain ref, NOT component — fn doesn't OWN the ns)
::seon.fn/source  :string                               ; current source text

;; Schemas
::seon.schema/key     [:keyword {:seon.db/identity true}]
::seon.schema/ns      :seon.db/ref                      ; → :seon.ns
::seon.schema/source  :string                           ; full register! call text

;; Tests
::seon.test/sym       [:string {:seon.db/identity true}]
::seon.test/ns        :seon.db/ref                      ; → :seon.ns
::seon.test/target    :seon.db/ref                      ; → :seon.fn (the fn this test exercises)
::seon.test/source    :string
```

**Discovery via reverse-ref pulls.** Everything an ns owns is one pull
from the ns entity:

```clojure
(d/pull db
  '[:seon.ns/source
    {:seon.fn/_ns     [:seon.fn/sym :seon.fn/source]
     :seon.schema/_ns [:seon.schema/key :seon.schema/source]
     :seon.test/_ns   [:seon.test/sym :seon.test/source :seon.test/target]}]
  [:seon.ns/name :seon.trading])
```

This replaces the current `current-ns-section`'s three datalog queries
+ filter-by-string-prefix passes with a single pull. The `_ns` prefix
is the reverse-ref selector; datahike handles it natively (see
`research/datahike-capabilities-2026-05-22.md` §2).

**Why ns refs are NOT components.** Component direction is
parent-to-child; the parent owns the child and retracting the parent
cascades to the child. A function does not own its namespace, so
`:seon.fn/ns` is a plain ref. Forget-namespace is explicit: query all
entities pointing at the ns, retract them in one tx (see "Forget"
below) — clearer than implicit cascade and avoids accidentally
retracting a populated namespace.

**No `:seon.test/last-passed-at` / `:last-failed-at` / `:last-failure`.**
A test run IS an eval; tag the run's tx with `:seon.eval/test
[:seon.test/sym "…"]` in tx-meta. "Latest pass for test T" is a
single history query:

```clojure
(d/q '[:find (max ?tx) .
       :in $ ?test
       :where [?tx :seon.eval/test ?test]
              [?tx :seon.eval/ok? true]]
     (d/history db) [:seon.test/sym "seon.trading/analyze-test"])
```

Three denormalized fields collapse to zero stored state.

**A namespace is one entity carrying the full `(ns …)` form as
source** — including the `:require` clause. Replaying the entity =
evaluating the source = the namespace and its dependencies become
available in one step. There is no separate `:seon.require/*` entity;
per-clause storage would duplicate what `(ns …)` already structures.

### Tx-meta — every tx is forensically traceable

Datahike's `:tx-meta` writes each map key as a datom on the
transaction entity itself. The eval entity already exploits this:
`{:tx-meta {:seon.db/eval-id <id>}}` makes the eval-id a queryable
property of the tx, so `(d/history db)` on the tx-id recovers what
the eval wrote.

We extend the same primitive to carry the **full causality bundle**
on every tx automatically, via the `seon.db/*tx-context*` dynvar:

```clojure
;; Bound by eval-batch! around each form's eval. db/transact! merges
;; the bound context into every tx's :tx-meta. No manual plumbing on
;; transact sites — the bundle attaches structurally.
::seon.db/agent-id     :seon.id/id                                ; who
::seon.db/session-id   :seon.id/id                                ; in which session
::seon.db/turn-id      :seon.id/id                                ; which turn
::seon.db/eval-id      :seon.id/id {:optional true}               ; which form (absent for non-eval txs: bootstrap, harness writes)
::seon.db/origin       [:enum :user :agent :system :replay]       ; who initiated this tx
::seon.db/replay?      :boolean {:optional true}                  ; true during resume-phase replay
::seon.db/resume-marker? :boolean {:optional true}                ; true on the first tx of a resume phase
::seon.eval/test       :seon.db/ref {:optional true}              ; on test-run txs: → :seon.test (gives "latest pass" via history)
```

**Every tx-meta key MUST be a registered schema attribute.** Datahike
`flush-tx-meta` at `db/transaction.cljc:634` rejects unregistered keys
with a clean error; the bootstrap registers all of the above before
the first tx fires.

**Tx-meta datoms ONLY persist when `:keep-history? true`.** The agent
DB conn must be opened with history on; the bootstrap asserts this
as a precondition (see Boot sequence). If history is off, the entire
causality-bundle mechanic silently degrades — meta-entities just
don't get written.

The eval-IS-tx mechanic still holds: pulling
`[:seon.eval/id "K9p…"]` returns the eval entity AND every tx-meta
datom on it (eval-id, agent-id, session-id, turn-id, origin, etc.).
The renderer needs nothing else to label a row.

### Blob storage — "the archival layer"

Some content is too large to live inline in datahike: rendered turn
prompts (~20–50 KB each), full eval results (variable, can be huge),
and — looking ahead — content the agent itself ingests (web pages,
PDFs, CSVs). These share a single content-addressed primitive.

```clojure
;; Metadata in datahike — small, indexed, queryable.
::seon.blob/hash    [:string {:seon.db/identity true}]   ; sha256 hex (or base64url-no-pad)
::seon.blob/size    :long                                ; bytes, uncompressed
::seon.blob/mime    :string {:optional true}             ; "text/plain" / "application/edn" / "application/pdf" / etc.
::seon.blob/at      :inst                                ; first-write timestamp (= :db/txInstant of the create-blob tx)
```

**Bytes live on disk** at `<pod-data>/blobs/<hash[:2]>/<hash>.zst`,
zstd-compressed. The hex fanout (256 dirs from first 2 hex chars of
hash) keeps any one directory under ~10K files at realistic blob
counts. Access goes through `seon.fs` so the WASM containment story
applies once we cut over to the Phase 3 pod.

**API:**

```clojure
(seon.blob/put! {:seon.blob/content "..." :seon.blob/mime "text/plain"})
;; => hash string. If a blob with this hash already exists, the file
;; is NOT rewritten and the metadata entity is NOT re-transacted. The
;; tx still happens so the read returns; idempotent on duplicates.

(seon.blob/get hash)
;; => decompressed bytes (or string when mime is text/*). Throws if
;; the on-disk file is missing — the datahike metadata and the disk
;; file are intended to be in sync; "metadata without bytes" is an
;; error to surface, not paper over.

(seon.blob/ref-by hash)   ; => [:seon.blob/hash "..."] lookup ref ready to drop in a transact
```

**Where v1 uses it:**

- `:seon.turn/prompt-blob` — the rendered context the LLM saw,
  written by the composer on every turn.
- `:seon.eval/result-blob` — the full result, written by
  `record-eval!` when the truncated `:result-edn` was shortened
  (i.e. the truncation actually clipped data; small results skip the
  blob write).

**The agent learns it as a primitive.** The same `seon.blob/put!`
call is what the agent will reach for in v2+ when ingesting a 50-page
PDF and projecting its structured bits into datahike entities (e.g.
`:my.research/paper-id`, `:my.research/source-blob`, etc.). Hash is
the durable handle; the structured projection can be re-derived from
the blob if the agent's schema evolves later. Same primitive, no
special casing.

**Not in v1:**

- GC / retention policies. The blob store grows unbounded for now;
  v2 adds a sweep ("retract blob metadata + delete file if no entity
  refs the hash") when measurement justifies it.
- Cross-pod blob sharing (e.g. S3-backed store). The on-disk path is
  intentionally simple; a future `seon.blob/*config*` knob can point
  elsewhere without changing the agent-facing API.

### Pull patterns the agent should learn day one

These three idioms cover most of what the agent will ever want to
ask. They appear in `system-section` (see Rendering) so the agent
reads them on every turn.

```clojure
;; 1. The agent's own causality graph — current session + most-recent turn + everything in it.
(d/pull db
  '[* {:seon.agent/sessions
       [* {:seon.session/turns
           [* {:seon.turn/messages [*]
               :seon.turn/evals    [*]}]}]}]
  [:seon.agent/id (seon.agent/id)])

;; 2. A namespace and everything it owns.
(d/pull db
  '[:seon.ns/source
    {:seon.fn/_ns     [:seon.fn/sym :seon.fn/source]
     :seon.schema/_ns [:seon.schema/key :seon.schema/source]
     :seon.test/_ns   [:seon.test/sym :seon.test/source {:seon.test/target [:seon.fn/sym]}]}]
  [:seon.ns/name :seon.trading])

;; 3. What an eval wrote (the eval IS the tx).
(d/q '[:find ?e ?a ?v ?op
       :in $ ?tx
       :where [?e ?a ?v ?tx ?op]]
     (d/history db) [:seon.eval/id "K9p2x4nB7q"])
```

The four primitives behind these (wildcard `*`, ref recursion via
nested maps, reverse-ref `_attr` syntax, the 5-tuple datom pattern
`[?e ?a ?v ?tx ?op]`) cover essentially every query the agent will
ever need. No `:seon.fn/refs` graph walks, no helper-fn-per-thing.

### What's NOT in the model

- **No `:seon.eval/agent` / `:seon.eval/turn` denormalization refs.**
  The eval is component'd off the turn (`:seon.turn/evals`), the turn
  off the session, the session off the agent. The forward chain
  carries the relationship. Tx-meta carries the IDs for direct
  joins.
- **No `:seon.eval/touches` or `:seon.eval/forgot` ref-vectors.** The
  tx that wrote the eval entity IS the tx that wrote (or retracted)
  any persistent datoms — datahike's `:tx-meta {:seon.db/eval-id …}`
  attaches the eval-id to the tx-id, so a 5-tuple history query
  recovers both directions of the answer.
- **No `:reversible?` boolean.** Reversibility is derived per-render
  from the history datoms the eval's tx wrote (see the table in
  "Forget" below).
- **No `:seon.test/last-passed-at` / `:last-failed-at` /
  `:last-failure`.** Test-run txs carry `:seon.eval/test
  [:seon.test/sym …]` in tx-meta; "latest pass" is one `d/history`
  query (see Program graph above).
- **No `:seon.warning/*` persistent entities.** Warnings are pure
  functions of current DB state — every warning is recomputed at
  render time by whichever predicate registers itself. Storing them
  would just risk the stored warning going stale relative to the
  live data it refers to. See `warnings-section` below.
- **No discriminator fields.** "Kind" is implicit in attribute
  presence. An entity is "a function" by carrying `:seon.fn/sym`; a
  message is a `:user` message by carrying `:seon.message/role :user`.
  The renderer never branches on a `:seon.X/kind` enum.

### What the model preserves

- **The database IS the system after first boot.** The seon substrate
  is compiled CLJS that knows how to interpret what's in the DB and
  how to seed it. On a brand-new DB the substrate transacts an
  ordered vector of entity maps (the bootstrap data — see "First
  boot" below). From that point on the DB is authoritative. A new
  runtime version paired with an older DB still resumes — the
  runtime brings the eval machinery; the DB brings the source.
- **An entity is in the DB iff it passed its gates.** A function
  that fails to compile is never persisted in the first place;
  nothing to quarantine. A function that fails on replay surfaces
  the failure through the eval log (`:ok? false` on that replay's
  eval entry) — and is rendered as a warning the next turn. No
  persistent quarantine flag.

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

The per-form work runs under a `seon.db/*tx-context*` binding that
carries the full causality bundle (agent-id, session-id, turn-id,
eval-id, origin `:agent`). `seon.db/transact!` reads `*tx-context*`
and merges it into every tx's `:tx-meta` — so anything the form does
that writes to the DB inherits the same eval-id and tx-context
without manual plumbing. The form's eval entity itself is just
another transact under the same binding.

```clojure
(binding [seon.db/*tx-context* {:seon.db/agent-id  (seon.agent/id)
                                :seon.db/session-id session-id
                                :seon.db/turn-id    turn-id
                                :seon.db/eval-id    eval-id
                                :seon.db/origin     :agent}
          seon.agent/*id*       (seon.agent/id)]
  ;; form eval + record-eval! run here; every transact() inside
  ;; carries the bundle automatically.
  ...)
```

For each entry classified as a form:

1. **Mint an eval-id** (12-char `:seon.id/id`) and a wall-clock start
   timestamp (`(js/Date.now)`).
2. **Bind `*tx-context*` and `seon.agent/*id*`** as above.
3. **Eval** the parsed form in the agent's current ns (the value of
   the `!current-ns` atom seon.eval/eval-batch! already maintains).
   On success, capture `:ok? true` + raw result. On any failure
   (compile, runtime, timeout, unbound-symbol), capture `:ok? false` +
   `:error` (pr-str'd map carrying `:kind :compile | :runtime |
   :timeout`).
4. **Capture `:seon.eval/ns`** as the ending ns returned by
   `cljs.js/eval-str`'s `:ns` field — i.e. where the form left the
   agent. Same value the existing pipeline already writes back into
   `!current-ns`. If `:ns` differs from the agent entity's
   `:seon.agent/current-ns`, the agent-entity upsert is part of the
   same transact below.
5. **Compute `:seon.eval/duration-ms`** = `(- (js/Date.now) start)`.
   Covers the form's eval AND any auto-await — i.e. what the agent
   actually waited for. Cheap (two `Date.now()` calls); always on.
6. **Render the result.** `(pr-str raw-result)` produces the full
   EDN. `(seon.render.default/truncate-edn full)` produces the
   2 KB display version. If the truncator actually clipped (i.e.
   `(not= full truncated)`), write the full string as a blob and
   capture the hash:
   ```clojure
   (let [full-edn (pr-str raw-result)
         display  (truncate-edn full-edn)]
     (cond-> {:seon.eval/result-edn display}
       (not= full-edn display)
       (assoc :seon.eval/result-blob
              (seon.blob/ref-by
                (seon.blob/put! {:seon.blob/content full-edn
                                 :seon.blob/mime    "application/edn"})))))
   ```
7. **Tag the tx with the eval-id** *(automatic via `*tx-context*`)*.
   The eval entity, any persistent-entity datoms produced by the
   form, AND the append onto the current turn's `:seon.turn/evals`
   vector all go in a single `d/transact` call. Tx-meta carries the
   causality bundle. Datahike records each meta key as a datom on
   the tx-id, so **the eval entity IS the tx** — pulling
   `[:seon.eval/id eid]` returns the eval data + the bundle. Effect
   classification at render time is a history query over that tx.
8. **Independent transact per form** — one tx per form. A failure on
   form 5 doesn't roll back forms 1-4. Tx-context unbinds when the
   per-form block exits; the next form re-binds with a fresh
   eval-id.

After every entry is processed, render the full context. The
composer also writes the rendered string as a blob and attaches it
to the **next** turn's `:seon.turn/prompt-blob` (the turn that's
about to start) — see Rendering below.

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

#### Undeclared-var surfacing

`cljs.js/eval-str` is permissive by default: an undeclared var
emits a `:undeclared-var` warning to stderr but the form still
compiles. At runtime the unresolved reference becomes `nil`, and
the eval returns `{:ok true :value nil}`. That's wrong for our
contract — bare prose like `Let me read` would tokenize into four
silent `nil`-valued evals.

The fix is configuration, not design. `seon.eval/raw-eval` installs
a `cljs.analyzer/*cljs-warning-handlers*` binding that throws on
`:undeclared-var` / `:undeclared-ns` / `:undeclared-ns-form`:

```clojure
(set! cljs.analyzer/*cljs-warning-handlers*
  [(fn [type _env extra]
     (when (#{:undeclared-var :undeclared-ns :undeclared-ns-form} type)
       (throw (ex-info (str "undeclared-var: "
                            (:prefix extra) "/" (:suffix extra))
                       {:kind :compile
                        :seon.eval/warning-type type
                        :seon.eval/extra extra}))))])
```

cljs.js wraps the throw as `{:tag :cljs/analysis-error}` and returns
it via `:error`. `raw-eval`'s existing `:error` branch then yields
`{:ok false :error ...}` — and the agent's `recent-evals` tile
shows the loud feedback the design depends on. Verified in REPL.

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
`:seon.render/ai` slot (default `seon.agent/ctx`). It pulls the
agent's `:seon.agent/ctx` vector (component-inlined so no second
query), sorts by priority, resolves each `:seon.ctx/fn` symbol, calls
it, joins the strings — and **persists the rendered output as a blob
attached to the upcoming turn**. That last step is what makes
playback possible: the literal string the LLM saw is recoverable from
the DB at any point in the future.

```clojure
(defn assemble-ctx
  {:malli/schema [:=> [:cat :seon.render/system-input]
                  :seon.render/ai-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [;; Pull the agent's ctx layout — components inline, so no second query.
        agent    (d/pull db
                   '[:seon.agent/id
                     {:seon.agent/ctx [:seon.ctx/name :seon.ctx/priority :seon.ctx/fn]}]
                   [:seon.agent/id id])
        sections (sort-by :seon.ctx/priority (:seon.agent/ctx agent))
        ctx-in   {:seon.db/db db :seon.agent/id id}
        text     (->> sections
                      (map (fn [section]
                             (let [f (seon.render/resolve-symbol (:seon.ctx/fn section))]
                               (if f
                                 (f ctx-in)
                                 (str (default/pretty-ai section))))))
                      (remove str/blank?)
                      (str/join "\n\n"))]
    ;; Persist the rendered prompt as a blob attached to the next turn.
    ;; The :seon.turn/id of the upcoming turn is in the harness's hands
    ;; — the harness creates the turn entity and calls assemble-ctx as
    ;; one indivisible step. See "Harness — the turn cycle" below.
    {:seon.render/text text
     :seon.turn/prompt-blob (seon.blob/ref-by
                              (seon.blob/put! {:seon.blob/content text
                                               :seon.blob/mime    "text/plain"}))}))
```

The composer is the only piece that knows about "sections". Section
functions are ordinary Clojure — the agent can read, write, or replace
any of them by transacting a different symbol into the slot.

The returned map carries `:seon.turn/prompt-blob` alongside the
rendered string. The **harness** is responsible for opening the turn
entity, attaching the blob ref via `:seon.turn/prompt-blob`, and
sending `:seon.render/text` to the LLM. The composer doesn't transact
the turn itself — that's the harness's concern. The composer just
produces the artifact + the ref.

If the composer is called outside a harness (e.g. in the REPL for
inspection), the blob is still written but the ref isn't attached
anywhere — that's fine, the blob is content-addressed and will be
deduped on the next real render. No state is corrupted by ad-hoc
composer calls.

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

These are the section entities transacted on first boot (as
components of the agent record — see D11) and the functions that
drive them. The agent can override or replace any of them by
transacting different attrs on the same entity (lookup by
`:seon.ctx/name`) or by retracting and adding a different one. Note
the default fns live in `seon.agent`, not `seon.render.default` —
the agent is the namespace that owns rendering.

```clojure
;; --- Section entities (baseline, components of :seon.agent/ctx) ---

{:seon.ctx/name :system        :seon.ctx/priority 10 :seon.ctx/fn 'seon.agent/system-section}
{:seon.ctx/name :related-ns    :seon.ctx/priority 20 :seon.ctx/fn 'seon.agent/related-ns-section}
{:seon.ctx/name :current-ns    :seon.ctx/priority 30 :seon.ctx/fn 'seon.agent/current-ns-section}
{:seon.ctx/name :warnings      :seon.ctx/priority 40 :seon.ctx/fn 'seon.agent/warnings-section}
{:seon.ctx/name :recent-evals  :seon.ctx/priority 50 :seon.ctx/fn 'seon.agent/recent-evals-section}
{:seon.ctx/name :prompt        :seon.ctx/priority 99 :seon.ctx/fn 'seon.agent/prompt-section}
```

```clojure
;; --- Section functions (baseline, in seon.agent) ---
;; Each takes :seon.render/system-input and returns a string.
;; Empty string = section omitted.
;;
;; Bodies use `(seon.agent/root-pull)` to start at the agent record
;; and walk forward through the component chain. The `seon.agent/*id*`
;; dynvar is bound by the composer so the helpers know whose agent
;; this is without being passed the id explicitly.

(defn- agent-current-ns [agent-map]
  (or (:seon.agent/current-ns agent-map)
      (seon.agent/home-ns (:seon.agent/id agent-map))))

(defn- host-timezone
  "Best-effort IANA timezone of the pod's host. POD timezone, not the
   user's — surfacing the user's tz needs a signal from outside the
   pod (browser, env var, agent entity attr). See post-MVP note below."
  []
  (.. (js/Intl.DateTimeFormat.) resolvedOptions -timeZone))

(defn system-section
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [agent (d/pull db '[:seon.agent/id :seon.agent/current-ns] [:seon.agent/id id])
        ns    (agent-current-ns agent)
        now   (js/Date.)]
    (str "<system agent=\"" id "\" ns=\"" ns "\">\n"
         "  Now: " (.toISOString now) "  (pod tz: " (host-timezone) ")\n"
         "  Restore defaults: (seon.agent/reset-ctx!)\n"
         "</system>")))

(defn current-ns-section
  "Everything owned by the current ns: the ns entity itself (its
   (ns …) source), then its fns, schemas, tests. ONE pull via
   reverse-ref subpatterns — no hand-rolled queries, no string
   prefix filtering."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [agent (d/pull db '[:seon.agent/current-ns] [:seon.agent/id id])
        ns    (agent-current-ns agent)
        owned (d/pull db
                '[:seon.ns/source
                  {:seon.schema/_ns [:seon.schema/key :seon.schema/source]
                   :seon.fn/_ns     [:seon.fn/sym :seon.fn/source]
                   :seon.test/_ns   [:seon.test/sym :seon.test/source]}]
                [:seon.ns/name ns])
        parts (concat
                (when-let [src (:seon.ns/source owned)] [src])
                (map :seon.schema/source (:seon.schema/_ns owned))
                (map :seon.fn/source     (:seon.fn/_ns owned))
                (map :seon.test/source   (:seon.test/_ns owned)))]
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
  (let [agent (d/pull db '[:seon.agent/current-ns] [:seon.agent/id id])
        ns    (agent-current-ns agent)
        related (compute-related-ns db ns)               ; helper, defined elsewhere
        rows   (for [other (sort related)
                     fns   (->> (d/pull db
                                  '[{:seon.fn/_ns [:seon.fn/sym]}]
                                  [:seon.ns/name other])
                                :seon.fn/_ns)]
                 (str "  " (:seon.fn/sym fns)))]
    (if (seq rows)
      (str "<related-namespaces>\n" (str/join "\n" rows) "\n</related-namespaces>")
      "")))

(defn warnings-section
  "Run every registered warning-predicate over the agent's accessible
   entities. Each predicate returns either nil or a seq of maps
   carrying :seon.warning/text + :seon.warning/severity."
  [{:as input}]
  (let [preds (registered-warning-predicates)
        ws    (->> (for [p preds, w (p input) :when w] w)
                   (sort-by :seon.warning/severity))]
    (if (seq ws)
      (str "<warnings>\n"
           (str/join "\n" (map :seon.warning/text ws))
           "\n</warnings>")
      "")))

;; Example warning predicate, registered as a default. Surfaces any
;; eval in the current session that took longer than the threshold.
;; Pure derivation from :seon.eval/duration-ms — no stored state.
(def slow-eval-threshold-ms 500)

(defn slow-eval-warning
  [_input]
  (let [slow (filter #(> (or (:seon.eval/duration-ms %) 0)
                         slow-eval-threshold-ms)
                     (seon.agent/evals {:n 20}))]
    (for [e slow]
      {:seon.warning/severity :info
       :seon.warning/text
       (str "slow eval " (:seon.eval/id e)
            " took " (:seon.eval/duration-ms e) "ms — consider"
            " profiling: (seon.perf/profile-form …)")})))

(defn recent-evals-section
  "The last N evals in the current session (default N=20), oldest-first
   so it reads top-to-bottom like a real REPL transcript. Walks the
   component chain off the current session — no hand-rolled query,
   no backref idiom."
  [{:seon.agent/keys [_id]}]
  (let [evals (seon.agent/evals {:n 20})]
    (if (seq evals)
      (str "<recent-evals>\n"
           (str/join "\n\n" (map format-eval-row evals))
           "\n</recent-evals>")
      "")))

(defn prompt-section
  "Renders the final piece of context as a REPL prompt the agent is
   typing into. The current ns appears exactly as a real Clojure REPL
   shows it, so the LLM is primed to continue the conversation as
   the next form in that ns. Always present — never empty."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [agent (d/pull db
                '[:seon.agent/current-ns
                  {:seon.agent/sessions [:seon.session/turn-count]}]
                [:seon.agent/id id])
        ns    (agent-current-ns agent)
        turn  (-> agent :seon.agent/sessions last :seon.session/turn-count (or 0))]
    (str ns "=>  ; turn " turn)))
```

That's the whole default surface: 6 section entities + 6 section
functions, ~120 lines of straightforward Clojure. Adding or modifying
any of it = writing one function. The bodies are short because the
component-chain pulls do the heavy lifting — `current-ns-section`'s
old three-queries-plus-filters body becomes one pull with
reverse-ref subpatterns. Nothing is hidden; nothing is special-cased.

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

## Harness — the turn cycle

The harness is the driver loop. It owns turn entities, the LLM
adapter, and the binding around `eval-batch!`. Each turn is one
atomic cycle: open turn → render → ask LLM → record assistant
message → run eval-batch → close turn.

```clojure
(defn run-turn-once!
  "One full turn. Returns the closed :seon.turn entity."
  [{:seon.agent/keys [id] :seon.harness/keys [llm-fn]}]
  (let [;; 1. Open the turn entity. Append to current session's
        ;;    :seon.session/turns vector (component, so the turn
        ;;    is owned by the session).
        turn-id    (seon.id/new-id!)
        session-id (-> (seon.agent/current-session {:seon.agent/id id}) :seon.session/id)
        _          (seon.db/transact!
                     {:seon.db/tx-data
                      [{:seon.session/id session-id
                        :seon.session/turns [{:seon.turn/id     turn-id
                                              :seon.turn/index  (next-turn-index id session-id)
                                              :seon.turn/at     (js/Date.)
                                              :seon.turn/status :running}]}]
                      :seon.db/opts {:tx-meta {:seon.db/origin     :system
                                               :seon.db/agent-id   id
                                               :seon.db/session-id session-id
                                               :seon.db/turn-id    turn-id}}})

        ;; 2. Render the context. Composer returns the text AND a
        ;;    prompt-blob ref. Attach the ref to the turn.
        {:seon.render/keys [text]
         :seon.turn/keys   [prompt-blob]}
        (seon.render/ai-dispatch {:seon.db/db @!conn :seon.agent/id id})

        _ (seon.db/transact!
            {:seon.db/tx-data [{:seon.turn/id turn-id
                                :seon.turn/prompt-blob prompt-blob}]
             :seon.db/opts {:tx-meta {:seon.db/origin :system
                                      :seon.db/agent-id id
                                      :seon.db/session-id session-id
                                      :seon.db/turn-id turn-id}}})

        ;; 3. Ask the LLM. llm-fn returns a Promise<{:text "..."}>.
        ;;    Time it; record any latency in the turn entity later if you want.
        reply (await (llm-fn text))

        ;; 4. Record the assistant message as a component of this turn.
        msg-id (seon.id/new-id!)
        _ (seon.db/transact!
            {:seon.db/tx-data [{:seon.turn/id turn-id
                                :seon.turn/messages
                                [{:seon.message/id      msg-id
                                  :seon.message/role    :assistant
                                  :seon.message/content (:text reply)
                                  :seon.message/at      (js/Date.)}]}]
             :seon.db/opts {:tx-meta {:seon.db/origin :system
                                      :seon.db/agent-id id
                                      :seon.db/session-id session-id
                                      :seon.db/turn-id turn-id}}})

        ;; 5. Eval the LLM's forms. eval-batch! binds *tx-context*
        ;;    around each form's eval (see "Per-form loop" above), so
        ;;    every eval entity AND every persistent-entity datom
        ;;    inherits {:agent-id :session-id :turn-id :eval-id :origin :agent}
        ;;    automatically. Eval entities also append to the turn's
        ;;    :seon.turn/evals vector as components.
        _ (seon.eval/eval-batch!
            {:seon.eval/source  (:text reply)
             :seon.agent/id     id
             :seon.session/id   session-id
             :seon.turn/id      turn-id})

        ;; 6. Close the turn.
        _ (seon.db/transact!
            {:seon.db/tx-data [{:seon.turn/id turn-id
                                :seon.turn/status :done}]
             :seon.db/opts {:tx-meta {:seon.db/origin :system
                                      :seon.db/agent-id id
                                      :seon.db/session-id session-id
                                      :seon.db/turn-id turn-id}}})]
    ;; Return the closed turn entity for the caller (harness UI / test).
    (seon.db/pull-by-name {:seon.turn/id turn-id})))
```

### Playback — "show me turn N"

Because the harness wrote `:seon.turn/prompt-blob`, the assistant
`:seon.message`, and every `:seon.eval` as components of the same
turn entity, **one pull reconstructs everything**:

```clojure
(d/pull db
  '[:seon.turn/id :seon.turn/index :seon.turn/at :seon.turn/status
    {:seon.turn/prompt-blob [:seon.blob/hash :seon.blob/size :seon.blob/mime]}
    {:seon.turn/messages    [:seon.message/role :seon.message/content :seon.message/at]}
    {:seon.turn/evals       [* {:seon.eval/result-blob [:seon.blob/hash]}]}]
  [:seon.turn/id "T1aaaaa1111"])
```

Fetch the prompt-blob bytes via `(seon.blob/get hash)` and you have
the literal string the LLM saw at turn time. Walk `:seon.turn/evals`
for everything the LLM said in response (parsed) and the results
each form produced. Walk back from any eval to the tx that wrote it
(via tx-meta `:seon.db/eval-id`) and `(d/history db)` over that tx
shows what persistent datoms it wrote. The graph is fully connected;
the harness recorded enough at write time that the read side is
trivial.

### Cross-cutting helpers the harness uses

`seon.agent/current-session` and `seon.agent/current-turn` are the
helpers above (see "Helper fns in `seon.agent`"). Internally they're
small `d/pull` wrappers off the agent record; the harness uses them
to avoid duplicating pull patterns.

`next-turn-index` is `(inc (or (-> (current-session) :seon.session/turns last :seon.turn/index) -1))`.
Trivial; lives in `seon.agent`.

### Multi-turn loops

The harness can call `run-turn-once!` repeatedly. The "should I run
another turn?" decision is policy:

- Stop when the last turn's assistant message contained no forms
  (the agent is talking to the user, not the REPL).
- Stop when `:seon.session/turns-since-user` exceeds the configured
  cap (prevents runaway loops).
- Stop when a user-role message lands on the session — the harness
  treats user messages as turn-cycle terminators.

These are heuristics; the harness exposes them as configuration so
the agent can tune. None of them are spec-mandated; the spec just
requires that turns be addressable, idempotent (re-running a turn
should not corrupt data; it would create a new turn entity, which
is fine), and recoverable from the DB.

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
  assert-preconditions!                        ; :keep-history? + tx-meta attrs registered
  init-bootstrap!                              ; cljs.core analyzer-cache load (see below)
  if (database-empty? db) bootstrap-phase!    ; seed the DB
  resume-phase!                                ; rebuild runtime from DB
  start-session!                               ; create the :seon.session entity for this run
  render-initial-context!                      ; first turn for the agent

```

The two phases never run independently. On a brand-new DB, bootstrap
seeds and then resume eval's the freshly-seeded entries. On a persistent
DB, bootstrap is skipped and resume walks whatever the agent has built
up. Either path ends in the same place: every persistent entity has a
DB row AND a live var.

### Preconditions — fail loud, fail early

Two preconditions MUST hold before any agent-eval happens; failing
either silently breaks the causality model. The boot sequence asserts
both and throws clean errors if not.

**1. `:keep-history? true` on the agent conn.** Tx-meta datoms ONLY
persist when history is on (datahike `db/transaction.cljc:898-901`).
Without history, the entire causality-bundle mechanic silently
degrades — meta-entities aren't written, and `(d/history db)` returns
the current db only.

```clojure
(when-not (:keep-history? (d/get-config @!conn))
  (throw (ex-info "Agent conn opened with :keep-history? false — the entire tx-meta causality mechanic is dead."
                  {:kind :seon.boot/precondition-failed
                   :fix  "Open the conn with :keep-history? true in seon.client/open-agent-conn!"})))
```

**2. tx-meta schema attrs are registered.** Datahike `flush-tx-meta`
at `db/transaction.cljc:634` rejects unregistered keys at write
time. The bootstrap must register `:seon.db/agent-id`,
`:seon.db/session-id`, `:seon.db/turn-id`, `:seon.db/eval-id`,
`:seon.db/origin`, `:seon.db/replay?`, `:seon.db/resume-marker?`,
and `:seon.eval/test` BEFORE the first tx-meta-carrying tx fires.

```clojure
(doseq [attr [:seon.db/agent-id :seon.db/session-id :seon.db/turn-id
              :seon.db/eval-id :seon.db/origin :seon.db/replay?
              :seon.db/resume-marker? :seon.eval/test]]
  (when-not (schema/registered? attr)
    (throw (ex-info (str "tx-meta attr " attr " not registered before first transact")
                    {:kind :seon.boot/precondition-failed :attr attr}))))
```

Both checks are cheap, run once at boot, and prevent multi-hour
debugging sessions chasing silently-dropped history.

### <a id="analyzer-cache-load"></a>Analyzer-cache load ^analyzer-cache-load

Before any agent form can be evaluated, the bootstrap-CLJS compile-state
needs `:cljs.analyzer/namespaces 'cljs.core` populated with cljs.core's
defs. Without this, every unqualified core reference (`reduce`,
`map`, `inc`, even the `@` reader macro's underlying `deref`) compiles
to `cljs.user.<name>` (undefined) and fails at runtime.

`cljs.js/empty-state` calls `(dump-core)` which leaves cljs.core
entry's `:name` set but `:defs` empty. Shadow's `boot/init` then
SKIPS loading the real ~712KB `cljs.core.transit.json` analyzer
cache because its loader filter at
`shadow.cljs.bootstrap.node:104` reads "`:name` is set → already
loaded → skip." Net result: the analyzer can't resolve any
unqualified core var.

`seon.eval/init-bootstrap!` works around this by calling
`cljs.js/load-analysis-cache!` for EVERY namespace shadow emitted
into `out/bootstrap/ana/`. The helper `load-all-analysis-caches!`
walks the dir, reads each `*.transit.json`, and loads it into the
compile-state. After init,
`(count (:defs (get-in @compile-state [:cljs.analyzer/namespaces 'cljs.core])))`
should return ~980; and any namespace listed in `shadow-cljs.edn
:bootstrap :entries` is similarly analyzer-visible (e.g.
`cljs.test`, `clojure.set`, `clojure.string`, `clojure.walk` after
the entries expansion that landed alongside this fix).

The discover-and-load-all shape replaced an earlier hand-coded
load list (`[cljs.core cljs.core$macros]` only). Without the
walk, every future expansion of `:bootstrap :entries` would have
to remember a second edit — fragile. Now the bootstrap output is
the single source of truth.

This is invisible from the spec's design but load-bearing for the
eval surface. The `truly-undeclared?` resolver
(`src/seon/eval.cljs:158`) leans on the analyzer having the right
view of bundled namespaces to short-circuit false-positives; if
the cache load fails silently, every form rejects with
`undeclared-var: cljs.user/<core-fn>`.

### Bootstrap phase (runs only when DB is empty)

The substrate seeds the DB from a build-time artifact: an ordered
vector of entity maps (`resources/seon/bootstrap.edn`), emitted by the
substrate's own build process from the same source the runtime was
compiled from.

```clojure
;; resources/seon/bootstrap.edn (shape; ordered for single-transact)
[{:seon.ns/name   :seon.agent
  :seon.ns/source "(ns seon.agent (:require [seon.schema :as schema] [seon.db :as db]))"}
 ...
 {:seon.schema/key :seon.render/ai
  :seon.schema/ns  [:seon.ns/name :seon.render]
  :seon.schema/source "(schema/register! ::ai :string)"}
 ...
 {:seon.fn/sym    "seon.agent/system-section"
  :seon.fn/ns     [:seon.ns/name :seon.agent]
  :seon.fn/source "(defn system-section ...)"}
 ...
 {:seon.test/sym    "seon.agent/system-section-test"
  :seon.test/ns     [:seon.ns/name :seon.agent]
  :seon.test/target [:seon.fn/sym "seon.agent/system-section"]
  :seon.test/source "(deftest system-section-test ...)"}
 ...
 ;; Section entities are NOT in bootstrap.edn as standalone — they're
 ;; created per-agent by (seon.agent/create!), nested INSIDE the agent
 ;; entity so the :seon.agent/ctx component refs auto-link. See D11.
 ]

```

Bootstrap.edn intentionally does NOT include section-entity records
or agent-entity records. The substrate's `seon.agent/create!` fn is
what bootstraps per-agent state (an agent record with the default
6-section ctx vector nested as components). Bootstrap.edn carries
only the **shared program graph**: namespaces, functions, schemas,
tests that all agents reuse.

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
each in creation order. On a freshly-bootstrapped DB this is what
makes the substrate "real" as vars; on a persistent DB this restores
the agent's accumulated work.

**No dep DAG, no analyzer walk.** Datahike tx-ids are strictly
monotonic, and an entity can only reference another via lookup-ref
if the referenced entity already exists at write time. So creation
order IS a valid topological order by construction. Replay is just
"sort by asserting tx-id and eval each entity's `:source`."

Replay runs under a `seon.db/*tx-context*` binding with
`:seon.db/origin :replay` so every datom written during resume is
distinguishable from agent-action and system-bootstrap datoms in
history.

1. Compiled CLJS substrate is loaded.
2. Query all persistent entities along with the tx that asserted
   them: `[:find ?e ?source ?tx :where [?e :seon.fn/source ?source ?tx]]`
   (and similar for `:seon.schema`, `:seon.test`, `:seon.ns`).
3. Sort by `?tx`. That's the order the agent created them in.
4. For each entity, eval its `:source` in the right ns. The replay
   binding carries `*tx-context*` with `{:seon.db/origin :replay
   :seon.db/eval-id <new-id> :seon.db/replay? true}` so every tx
   produced during this eval inherits the marker. The eval entry and
   the persistent datoms share a tx-id automatically.
5. If an eval throws during replay, its eval-log entry has
   `:ok? false`. The renderer surfaces it as a warning ("X failed
   to replay this session — fix or forget") with the source available
   for inspection. The entity stays in the DB unchanged; nothing is
   retracted automatically.

Eval log itself is not replayed. Scratch is scratch.

### Start-session

After resume, the bootstrap creates the **session entity** for this
pod run and appends it to `:seon.agent/sessions`:

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.agent/id <agent-id>
     :seon.agent/sessions [{:seon.session/id   (seon.id/new-id!)
                            :seon.session/at   (js/Date.)
                            :seon.session/turn-count 0
                            :seon.session/turns-since-user 0}]}]
   :seon.db/opts {:tx-meta {:seon.db/origin           :system
                            :seon.db/resume-marker?   true
                            :seon.db/agent-id         <agent-id>}}})
```

`:seon.db/resume-marker? true` on this tx makes "evals since the
most-recent resume marker" trivially queryable; it's the only
"session" demarcation the system needs at the tx level, on top of
the explicit `:seon.session` entity. Both are cheap and serve
different consumers (renderer ⇒ session entity; history rollback ⇒
resume-marker tx).

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
  attrs, never re-type existing ones. See [[#^d1]].

## MVP scope

### In

**Schemas (registered in bootstrap):**

- Causality: `:seon.agent/*`, `:seon.session/*`, `:seon.turn/*`,
  `:seon.message/*`, `:seon.eval/*`, `:seon.ctx/*`.
- Program: `:seon.ns/*`, `:seon.fn/*`, `:seon.schema/*`,
  `:seon.test/*`.
- Archival: `:seon.blob/*`.
- Tx-meta: `:seon.db/agent-id`, `:seon.db/session-id`,
  `:seon.db/turn-id`, `:seon.db/eval-id`, `:seon.db/origin`,
  `:seon.db/replay?`, `:seon.db/resume-marker?`, `:seon.eval/test`.

**Substrate code:**

- `seon.eval/eval-batch!` — runs the forms-and-comments
  read/eval/transact pipeline (rewrite-clj for parse; see "Parse:
  forms-and-comments"). Binds `seon.db/*tx-context*` and
  `seon.agent/*id*` around each form. Trailing comments without a
  following form become a thinking-only eval entry. Bare symbols are
  forms.
- `seon.harness/run-turn-once!` — the turn cycle: open turn, render,
  ask LLM, record assistant message, eval-batch, close turn. See
  "Harness — the turn cycle".
- `seon.agent/*-section` × 6 — `system`, `related-ns`, `current-ns`,
  `warnings`, `recent-evals`, `prompt`. Bodies use `d/pull` with
  reverse-ref subpatterns; no hand-rolled queries.
- `seon.agent` helpers: `root-pull`, `messages`, `evals`, `logs`,
  `sessions`, `current-session`, `current-turn`, `ctx`, `id`,
  `reset-ctx!`, `update-ctx!`. All bare-named (no `my-` prefix);
  current-agent via `seon.agent/*id*` dynvar.
- `seon.repl/forget!` + `seon.schema/unregister!`.
- `seon.render/assemble-ctx` (composer): pulls section entities,
  resolves symbols, joins strings, AND writes the rendered prompt
  as a blob returned in `:seon.turn/prompt-blob` for the harness to
  attach to the open turn.
- `seon.render.default/truncate-edn` helper (`pretty-ai` already
  exists).
- `seon.render/explain-section`, `reset-defaults!`.
- `seon.blob/{put!, get, ref-by}` — content-addressed compressed
  on-disk archival. Used by composer (prompt-blob) and `record-eval!`
  (result-blob when truncation actually clipped).
- `seon.db/*tx-context*` dynvar + `seon.db/transact!`
  auto-merging it into every tx's `:tx-meta`.
- Per-eval timing + Tufte hook per "Self-instrumentation"; optional
  `perf-section` formats Tufte stats on demand.
- Current time in `system-section`. User-timezone lookup is post-v1
  (see "Out" below).
- **Targeted test auto-run** ([[#^d4]]): a `d/listen!` on the agent
  conn watches for `:seon.fn` asserts and post-fires tests that
  target the changed fn (reverse ref via `:seon.test/target`). The
  test-run tx carries `:tx-meta {:seon.eval/test [:seon.test/sym
  …]}`; the `failing-test-warning` predicate queries `d/history`
  for "latest test-run tx where `:seon.eval/ok? false`."
- **Spec-violation warning** ([[#^d2]]): when a schema is
  redefined, validate existing data against the new shape;
  violations become warnings. No reject.
- **Def-not-persisted warning** ([[#^d3]]): bare `(def x …)`
  outside `defn`/`schema/register!`/`deftest` surfaces a warning.
- Bootstrap + resume phases per "Boot sequence", with the two
  preconditions (`:keep-history? true`; tx-meta attrs registered)
  asserted at boot.
- Per-form independent transacts (partial-success preservation).
- Eval classification implicit via the history query over each
  eval's tx + `:ok?` boolean + cross-eval `:ns` comparison — no
  classifier enum to maintain.

### Out

- WASM-side wiring (M2 — the WIT `eval-form` export calls into this; pipeline
  itself runs in V0 Node pod first for testing).
- WASM-survival of the `seon.db/*tx-context*` dynvar (D13). Spike
  before Phase 3 cutover.
- Blob GC / retention policy (D12). The blob store grows unbounded
  for v1.
- Multi-agent ownership coordination (single-agent assumption for
  MVP). Cross-agent collaboration features are V2/V3.
- Baseline reconciliation (m6 capability — comes after MVP).
- Result-value retention across sessions for non-blob values
  (`globalThis` stash dies with the pod). Truncated `:seon.eval/result-edn`
  is in the DB; full results are in `:seon.eval/result-blob` (when
  written). The live JS value the agent had via `(result :<eval-id>)`
  doesn't survive — agent re-evals if needed.
- Token budgeting for the renderer (no compression beyond truncate-edn).
- Auto-run on **dependent**-change (i.e. fn B's tests fire when fn A
  that B depends on changes). MVP runs only tests targeting the
  directly-modified fn; transitive triggering follows. See [[#^d4]].
- Caching of section outputs (recompute every turn for MVP).
- User-timezone lookup. The system-section surfaces *pod* time and
  timezone — that's what `js/Intl.DateTimeFormat` resolves to inside
  the wasmtime/Node host. The *user's* timezone lives outside the
  pod (browser, env var, or an attr the user transacts onto their
  agent entity). Post-v1: pull `:seon.user/timezone` from the agent
  entity when present, format `now` in that zone; until then, the
  agent and user negotiate timezone in conversation if it matters.
- Cross-pod blob sharing (e.g. S3-backed store). The on-disk path
  is intentionally simple; a future `seon.blob/*config*` knob can
  point elsewhere without changing the agent-facing API.

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

### <a id="d1"></a>D1 — Older DB on newer runtime upgrade strategy ^d1

Sketched but not designed: detect substrate version delta, merge
missing-from-DB bootstrap entries (lookup by identity), surface
agent overrides that conflict with the new substrate as warnings
with diffs. Out of MVP scope but the data model must permit it.

Solve [[#^d10]] first — once the substrate analyzer emits a clean
ordered bootstrap vector, upgrades follow naturally (diff old
vector against new; transact the additions).

### <a id="d2"></a>D2 — Per-kind redefinability rules ^d2

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
- Targeted tests auto-run on the new definition ([[#^d4]]); failing
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

### <a id="d3"></a>D3 — `(def …)` not-persisted warning (no regex) ^d3

Agents reach for `(def !x (atom …))` because of a known
bootstrap-CLJS gotcha (bare-value defs don't resolve across
eval-str calls; see `src/seon/eval.cljs` opening docstring). The
defs eval fine but **aren't persisted** — pod restart loses them.

Detection uses the parsed form, not a regex. Since rewrite-clj
already gives us the form structurally, the check is:

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
analyzer surface as [[#^d8]] / [[#^d10]].)

### <a id="d4"></a>D4 — Targeted test auto-run on every define / redefine ^d4

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

### <a id="d5"></a>D5 — `(forget!)` for namespaces ^d5

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

### <a id="d6"></a>D6 — Explicit remove-spec / remove-fn / remove-test ^d6

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

### <a id="d7"></a>D7 — `<name>-example` test convention ^d7

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

### <a id="d8"></a>D8 — Reference-graph attrs ^d8

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
([[#^d4]]) and the spec-violation check ([[#^d2]]) cheap: every
"who is affected by changing X" question is one datalog query
over the reverse index.

### <a id="d9"></a>D9 — Forgiving parse recovery ^d9

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

### <a id="d10"></a>D10 — Topological bootstrap emission ^d10

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

Solve this BEFORE worrying about [[#^d1]] (older-DB-on-newer-runtime).
Once we have the analyzer walk producing a clean ordered vector,
upgrades follow naturally (diff old vector against new; transact
the additions).

### <a id="d11"></a>D11 — Per-agent ctx as a component multi-ref on the agent record ^d11

The agent record is the hub. Each agent owns a cardinality-many
`:seon.agent/ctx` vector of refs to `:seon.ctx` entities, marked
`:db/isComponent true`. Section entities are owned by exactly one
agent; retracting the agent cascade-retracts the ctx entities, and a
recursive pull on the agent inlines the ctx entity maps (not just
`{:db/id N}` placeholders).

```clojure
;; on the agent — component, cardinality-many
::seon.agent/ctx  [:vector :seon.db/ref {:seon.db/component true}]   ; → :seon.ctx

;; on the ctx entity (no :agent attr — owned via component cascade)
::seon.ctx/name      :keyword              ; :system / :recent-evals / etc.
::seon.ctx/priority  :long
::seon.ctx/fn        :symbol               ; ns-qualified, resolves to a section fn
```

Why this shape:

- **The agent's record IS the index.** `(d/pull db '[* {:seon.agent/ctx [*]}]
  [:seon.agent/id id])` returns the agent map with ctx entity maps
  inlined. One pull, no joins, no `{:db/id N}` placeholders.
- **Customization is "transact a different ref onto my own record."**
  Agent writes their custom fn in their home ns (`seon.agent.<id>`),
  transacts a new `:seon.ctx` entity, and either replaces an existing
  ref in `:seon.agent/ctx` or appends to the vector. No global
  side-effect on other agents.
- **Recovery from a borked context.** Reset is one retract+add on
  the agent record (see "Self-recovery" above). The retract first is
  required because cardinality-many upserts accumulate; the
  `:db/isComponent` flag means the retracted ctx entities are
  cascade-retracted from the DB. No GC pass needed.
- **No global registry.** Section entities are per-agent by
  construction. Two agents can have completely different ctx sets
  with zero interference.

#### V1 default — substrate fns, no dispatch

V1 ships with default ctx entities pointing at fns in the
**`seon.agent`** namespace (substrate-shipped, shared across
agents). The substrate's bootstrap creates one default ctx set per
agent at agent-create-time:

```clojure
;; substrate bootstrap creates these as part of (seon.agent/create! …)
;; Note: the ctx entities are nested INSIDE the agent map so they're
;; transacted as components and auto-link to :seon.agent/ctx.
{:seon.agent/id  <id>
 :seon.agent/ctx
 [{:seon.ctx/name :system        :seon.ctx/priority 10 :seon.ctx/fn 'seon.agent/system-section}
  {:seon.ctx/name :related-ns    :seon.ctx/priority 20 :seon.ctx/fn 'seon.agent/related-ns-section}
  {:seon.ctx/name :current-ns    :seon.ctx/priority 30 :seon.ctx/fn 'seon.agent/current-ns-section}
  {:seon.ctx/name :warnings      :seon.ctx/priority 40 :seon.ctx/fn 'seon.agent/warnings-section}
  {:seon.ctx/name :recent-evals  :seon.ctx/priority 50 :seon.ctx/fn 'seon.agent/recent-evals-section}
  {:seon.ctx/name :prompt        :seon.ctx/priority 99 :seon.ctx/fn 'seon.agent/prompt-section}]}
```

Agent customizes by writing their own fn in `seon.agent.<id>`
(their home ns) and re-pointing one ref. Cardinality-many ref
attributes accumulate on upsert — to REPLACE a section, retract the
old ctx entity by id first, then add the new one:

```clojure
;; in their home ns (seon.agent.AbCdEfGh1234)
(defn compact-evals-section [_input] ...)

;; transact: retract the old :recent-evals ctx entity, add a new one
(let [old-ctx-id (-> (d/pull @!conn
                       '[{:seon.agent/ctx [:db/id :seon.ctx/name]}]
                       [:seon.agent/id "AbCdEfGh1234"])
                     :seon.agent/ctx
                     (->> (filter #(= :recent-evals (:seon.ctx/name %))))
                     first :db/id)]
  (seon.db/transact!
    {:seon.db/tx-data
     [[:db/retractEntity old-ctx-id]
      {:seon.agent/id "AbCdEfGh1234"
       :seon.agent/ctx
       [{:seon.ctx/name :recent-evals
         :seon.ctx/priority 50
         :seon.ctx/fn 'seon.agent.AbCdEfGh1234/compact-evals-section}]}]}))
```

#### V1 default — section fn signature

A section fn takes one map and returns a string. The map carries the
db handle and the agent id; the agent id is also available via the
`seon.agent/*id*` dynvar (bound by the composer).

```clojure
{:seon.db/db    <datahike db>
 :seon.agent/id <agent-id string>}
```

Section fn handles the rest (queries DB, formats, returns string).
Empty string omits the section. The body typically starts with a
`(d/pull db '[…] [:seon.agent/id id])` and walks forward through
the component chain — see the default section implementations in
**Initial default context** above for examples.

#### Dynamic dispatch is V2 / V3

The earlier spec described "per-entity dispatch by Malli specificity"
as a post-MVP enhancement. V1 explicitly defers that AND defers any
sophisticated `:seon.render/ai` / `:seon.render/html` symbol-slot
resolution. V1 has:

- A direct vector of section refs.
- Section fns that return strings.
- One composer that walks the vector and joins.

V2 layers per-entity-shape dispatch on top of that (sections can
return entity maps; a registry resolves a specific render-fn for each
shape). V3 explores cross-agent collaboration features, profile
dashboards, etc.

This split is intentional. The MVP must work without any dynamic
resolution infrastructure. If everything compiles to a known symbol
that's part of `:seon.agent` or `seon.agent.<id>`, the contract is
"resolve the symbol at call time" — the simplest possible mechanism.

## <a id="known-issues"></a>Known issues

Implementation problems known to me as of the latest REPL-verification
round. Not spec decisions — bugs / quirks to triage.

### KI-1 — `seon.db/transact!` invocation shape is non-obvious

The fn signature is `[{::keys [tx-data opts conn]}]` — single map
argument with namespaced keys. The common-mistake forms

```clojure
(seon.db/transact! @conn {:tx-data ...})       ; positional — crashes inside seon.db with
                                               ; "ILookup$_lookup$arity$3 is not a function"
(seon.db/transact! {:conn @conn :tx-data ...}) ; unqualified keys — "no conversion to symbol"
```

both fail badly. The correct call is

```clojure
(seon.db/transact! {:seon.db/conn @conn
                    :seon.db/tx-data [...]})
```

…OR rely on the dynamic `seon.db/*conn*` and omit `:conn`. The
crash on form 1 takes the whole Node process down because the
TypeError isn't caught at the boundary. Triage: (a) add a precondition
that throws a clean validation error on bad-shape inputs, OR (b)
accept positional `[conn tx-data]` as a backward-compatible
alternative. The current crash mode is hostile to agents who
mis-call the API.

### KI-2 — `defonce` atoms can hold pre-fix state across hot-reloads

`seon.repl/!compile-state` is `defonce`'d to survive hot-reload. When
the substrate's auto-boot ran the OLD `init-bootstrap!` (before the
analyzer-cache fix landed) and populated the atom, a subsequent
hot-reload of `seon.eval` to the NEW `init-bootstrap!` left the
stale state in place. `seon.repl/dev-init!`'s
`(or @!compile-state ...)` short-circuits on the stale atom.

Workarounds:

- Manually `(reset! seon.repl/!compile-state nil)` before
  `(seon.repl/dev-init!)`.
- Add a `^:dev/before-load` handler in `seon.repl` that nils the
  atoms (loses cached state but pays for hot-reload correctness).
- Stamp the atom with a `:seon.eval/init-version` and re-init if
  the running code's version differs.

Triage: pick one. The third option preserves cached state during
benign reloads but rebuilds when the init code itself changed.

### KI-3 — Eval error envelope is deeply nested

`seon.eval/eval` returns errors with a nested cause chain four
levels deep:

```text
:error
  :seon.error/message  "Could not eval seon.dynamic"
  :seon.error/cause
    :seon.error/message  "<wrap>"
    :seon.error/cause
      :seon.error/message  "undeclared-var: cljs.user/Let"
      :seon.error/ex-data  {:kind :compile, :seon.eval/warning-type :undeclared-var}
```

The actionable info (`:seon.eval/warning-type`,
`:seon.eval/undeclared`) lives at the bottom. The recent-evals
renderer needs to walk the chain to surface it. Worth promoting
the key fields to the top-level `:error` map so renderers don't
have to descend.

### KI-4 — Shadow watcher gets confused after multiple Node restart cycles

After ~2-3 cycles of `pkill node && node out/client/main.js`, shadow's
nREPL piggyback says "no available JS runtime" even though the new
Node process is running and the watcher is up. Recovery requires
restarting the watcher (`Ctrl-C` then `clj -M:cljs watch client`).

This blocks autonomous REPL-iteration loops where an agent might
need to restart Node programmatically. Triage: investigate shadow
runtime-tracker state; possibly an idle-timeout or a runtime-id
collision. Not blocking MVP; manual recovery is fine for now.

### KI-5 — `start-agent!` auto-boot runs init before `dev-init!`

The pod's `seon.client/start-agent!` runs at module load and uses
its own init path. If an iteration session wants a clean
`init-bootstrap!` (e.g. after editing eval.cljs), `dev-init!`
won't run a fresh init because `!compile-state` is already
populated by start-agent's path (see KI-2). Triage: align
start-agent! with `seon.repl/ensure-bootstrap!` so they share the
same atom + same init path, OR decouple `!compile-state` between
the iteration surface and the agent loop entirely.

### KI-6 — `npm install ws` was required for first run

Shadow's hot-reload websocket import expects the `ws` module but
it wasn't in `package.json`. Fresh checkouts will hit this. Either
add `ws` to `package.json` dev deps, or document the install in
the REPL workflow.
