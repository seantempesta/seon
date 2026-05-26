---
type: prd
status: draft
tags: [prd, agent, runtime, render, architecture]
---

# Agent-context render strategies — substrate PRD

## TL;DR

The agent's LLM context is produced by a **render strategy**, which is just a Clojure fn whose output schema is `:seon.render.ctx/response`. Strategies aren't registered explicitly — they're **discovered** via the program graph (query all `:seon.fn` entities whose `:malli/schema` output matches the ctx-response shape). The agent picks one by transacting `:seon.agent/ctx-render-fn 'fully-qualified-sym` onto itself — a normal handler-update tx, no special verb. Agents may write their own strategies; discovery picks them up automatically.

Substrate ships **four** strategies — `naive-chronological`, `most-referenced`, `chronological-decay`, `data-shape-matching` — built in that order over four weekly phases. Each entity renders at one of **two detail levels**, `:full` or `:concise` (concise falls back to a truncated `:full` if no concise renderer is defined). Every rendered item ends with a real Clojure `#'sym` reference so the agent can drill in via `(seon.inspect/show {:seon.inspect/symbol 'sym})`.

The inspector exposes a dropdown that lets the user (or the agent itself) switch strategies live. Switching causes one LLM-cache miss and then steady state on the new prefix.

## 1. Vision

### 1.1 What we are trying to do

Today the substrate has one render path: walk every entity carrying `:seon.render/ai`, sort by tx-time, render each via its slot-symbol, concatenate. That's correct, but it's a single naïve ordering — it can't represent "stable prefix first, volatile tail last," it can't surface fns that match the user's data, and it can't fade unused fns out of the context as the agent's session grows.

This PRD introduces a thin layer of indirection: the agent entity carries a `:seon.agent/ctx-render-fn` slot pointing at a **strategy** — a fully-qualified symbol naming a Clojure fn that, given the agent's view of the DB, returns the full `:seon.render.ctx/response` map. The substrate's `assemble-ai-context` becomes a dispatcher that resolves this slot and calls the fn. Everything else (which entities to include, in what order, at what detail level) is decided by the strategy.

This is the smallest change consistent with the audit's V1–V6 vision (audit doc §1): one mechanism for reads, writes, and rendering; data-driven (not protocol-driven) overrides; cache-aware ordering; section-with-detail-levels; substrate defaults with agent override; code-is-data-is-render.

### 1.2 What makes a strategy

A strategy is a normal Clojure fn with `:malli/schema` metadata:

```clojure
(defn my-strategy
  "What this strategy optimizes for."
  {:malli/schema [:=> [:cat :seon.render.ctx/request] :seon.render.ctx/response]}
  [{:seon.agent/keys [id] :seon.db/keys [db] :as req}]
  ;; ... query the db, decide what to include, decide :full vs :concise,
  ;;     call render-one per entity, concat, return ...
  {:seon.render/text "..."
   :seon.render/entities [...]
   :seon.render/token-estimate 0})
```

That's the entire contract. No registry, no protocol, no multimethod, no `register-strategy!` verb. The strategy IS a fn, period. Discovery walks the program graph (the substrate's existing `:seon.fn` index, populated by the analyzer) and pulls every fn whose `:malli/schema` output is `:seon.render.ctx/response`.

### 1.3 Why this shape

- **No new mechanism.** Strategies are fns. Fns are already entities in the DB (analyzer-populated). The "registry" of strategies is just a Datalog query.
- **Reactive.** A new strategy appears in the dropdown the moment its `defn` form is evaled. Delete it: it disappears. No bifurcated "registered" vs "defined" state.
- **Agent-authored.** An agent writes its own strategy in its home ns; switching to it is one transact. The same mechanism the substrate uses.
- **Honest with caching.** Switching strategies changes the prefix → one LLM-cache miss, then steady state. We accept this and warn agents not to ping-pong.

## 2. Schemas

### 2.1 Request / response

```clojure
(schema/register! :seon.render.ctx/request
  [:map
   [:seon.agent/id        :string]
   [:seon.db/db           {:optional true} :any]
   [:seon.agent/window-size {:optional true} :seon.agent/window-size]])

(schema/register! :seon.render.ctx/response
  [:map
   [:seon.render/text           :string]
   [:seon.render/entities       [:vector :any]]
   [:seon.render/token-estimate :int]])
```

This is the same shape `assemble-ai-context` already returns — strategies are drop-in replacements for the body of that fn.

### 2.2 Detail levels

```clojure
(schema/register! :seon.render.ctx/full    :symbol)   ; required on renderable entity
(schema/register! :seon.render.ctx/concise :symbol)   ; optional
```

`:seon.render.ctx/full` is the existing per-entity render symbol (back-compat alias: `:seon.render/ai` is treated as a synonym so older entities continue to work — no migration tx needed). `:seon.render.ctx/concise` is new and optional. If a strategy asks for `:concise` and the entity has no concise renderer, the substrate calls the `:full` renderer and truncates: `(subs full-text 0 default-concise-chars)`. `default-concise-chars` is a per-agent attr (`:seon.agent/concise-chars`) defaulting to 200.

**Two levels, not three.** No `:hidden`. A strategy that wants to hide an entity simply doesn't include it. This keeps the per-entity contract symmetrical: render or don't.

### 2.3 The active strategy slot

```clojure
(schema/register! :seon.agent/ctx-render-fn :symbol)
```

Defaults to `'seon.strategies.naive/render` at agent boot. The agent (or the user via the inspector) transacts a new value to switch.

## 3. The drill-in affordance — real Clojure references everywhere

Every rendered chunk ends with a clojure-comment reference the agent can copy into its next form to fetch the full entity. Three flavors:

**Eval result reference**:

```clojure
(+ 1 1)
;; => 2   #'seon.agent.XAR-.../eval-ABC123XYZ
```

**Fn reference (concise render)**:

```clojure
;; seon.db/query — Datalog query against agent-scoped db
;; (db/query {::db/query '[:find ...] ::db/args [...]}) ;; → vector of rows
;; #'seon.db/query
```

**Schema reference**:

```clojure
;; :seon.message/content :string
;; #'seon.schema/<schema-key :seon.message/content>
```

The `#'<sym>` pattern is the agent's drill-in handle. Substrate ships:

```clojure
(defn show
  "Resolve `symbol` (qualified) to its :seon.fn / :seon.schema / :seon.eval
   entity and return its :seon.render.ctx/full render."
  {:malli/schema [:=> [:cat :seon.inspect/show-request]
                       :seon.render/ai-response]}
  [{:seon.inspect/keys [symbol]}]
  ...)
```

`show` lives in `seon.inspect` (extending the existing ns). It does an identity-attr lookup on `:seon.fn/sym` (and `:seon.schema/name`, `:seon.eval/id` for the other two flavors), pulls the entity, and calls the entity's `:seon.render.ctx/full` symbol. Documented in the default system prompt so the agent learns the pattern on turn one.

## 4. Discoverability — list-strategies

```clojure
(defn list-strategies
  "Query the program graph for all fns whose :malli/schema output is
   :seon.render.ctx/response. Returns a vector of
   {:seon.fn/sym 'qualified.sym :seon.fn/doc \"...\"}."
  {:malli/schema [:=> [:cat :map] :seon.render.ctx/list-strategies-response]}
  [_]
  (let [rows (db/query
               {:seon.db/query
                '[:find ?sym ?doc
                  :where
                  [?f :seon.fn/sym ?sym]
                  [?f :seon.fn/output-schema ?out]
                  [(= ?out :seon.render.ctx/response)]
                  [(get-else $ ?f :seon.fn/doc "") ?doc]]})]
    {:seon.render.ctx/strategies (mapv (fn [[s d]] {:seon.fn/sym s :seon.fn/doc d}) rows)}))
```

This lives in `src/seon/render/list_strategies.cljs` (~20 LOC). The inspector calls it to populate its dropdown; agents call it to ask "what can I switch to?". A `defn` form for a new strategy lands in the program graph (analyzer-populated) and shows up in the next query — no registration, no cache invalidation.

This depends on the analyzer indexing `:seon.fn/output-schema` (the keyword name of the output spec) on every `:seon.fn` entity. The audit doc confirms the analyzer pipeline exists; this PRD asserts that the output-schema attr is populated alongside `:seon.fn/sym` and `:seon.fn/source`. If it isn't yet, Phase 1 adds it (~5 LOC in the analyzer).

## 5. The four strategies

All four return `:seon.render.ctx/response`. They differ only in **which entities they include**, **in what order**, and **at what detail level**.

### 5.1 Strategy 1 — `naive-chronological`

**Goal**: smallest possible baseline. Useful as the control case in A/B experiments and as the strategy a fresh agent boots with.

**Query**: every entity carrying `:seon.render.ctx/full` (or `:seon.render/ai` for back-compat), scoped to the agent via tx-meta `:seon.db/agent-id`. Same query the current `assemble-ai-context` already uses.

**Order**: oldest tx-time first.

**Detail**: all entities rendered at `:full`. No truncation; let the LLM context fill naturally. (Token budget is a future concern — Phase 1 ships with the assumption that small agents fit; Phase 3 introduces decay-based eviction.)

**Sample output** (10 lines of what the agent would see):

```text
## System prompt
You are an agent in the Seon substrate. ...
;; #'seon.runtime/system-prompt

## Conventions
- Stay in your home namespace seon.agent.XAR-... .
;; #'seon.runtime/conventions

## Schema  :seon.message/content :string
;; #'seon.schema/<schema-key :seon.message/content>

## Fn  seon.db/query — Datalog query
;; (db/query {::db/query '[...] ::db/args [...]}) ;; → vector of rows
;; #'seon.db/query
```

### 5.2 Strategy 2 — `most-referenced`

**Goal**: programmer-shop view. What matters is the fns the codebase leans on most heavily; the agent should see those at high detail, and rarely-touched fns can fade to one-liners.

**Query**: walk the program graph; parse each `:seon.fn/source` for symbols matching other registered fns, build a directed call-reference graph, compute an in-degree (reference-count) per fn. Include:

- all `:seon.schema` entities (compact, one line each)
- top-K `:seon.fn` entities by reference-count
- all `:seon.message` and `:seon.eval` entities for the agent (recent N at `:full`, older at `:concise`)

**Order**: least-referenced-first at the very front (stable prefix — the foundational ns/system-prompt entities anchor and rarely change), most-referenced near the back (these grow as the agent defines more fns), then schemas in an alphabetical block, then recent messages/evals at the tail.

**Detail**: top-K fns by reference count, plus all current-ns fns → `:full`. Less-referenced fns → `:concise`. Messages → `:full` for last N, `:concise` older. Schemas → `:concise` always (one line is enough).

**Sample output**:

```text
## seon.fn  seon.platform/host  (refs: 2)
;; Returns the host runtime kind — :node or :wasi.
;; (seon.platform/host) ;; → :node
;; #'seon.platform/host

## seon.schema  :seon.message/role  [:enum :user :assistant :system]
;; #'seon.schema/<schema-key :seon.message/role>

## seon.fn  seon.db/transact!  (refs: 47)
;; Transact one map or vector of maps; map-in form.
;; Full source: (defn transact! ...)
;;   ...body...
;; #'seon.db/transact!
```

**When it's best**: agents working on the substrate itself, or agents whose job is to understand a codebase's structure.

### 5.3 Strategy 3 — `chronological-decay`

**Goal**: long-running agent. The fns and schemas the agent has actually used recently stay at `:full`; everything else fades to `:concise` and eventually evicts.

**Query**: all schemas (always concise); all fns with non-zero `:seon.fn/use-count`; all messages; last N evals.

**New attrs** (Phase 3 adds them):

```clojure
(schema/register! :seon.fn/use-count    :int)
(schema/register! :seon.fn/last-used-at :inst)
```

Incremented at eval-time: when the analyzer detects a fn call in an evaled form, the post-eval handler bumps `:use-count` and updates `:last-used-at` on the callee.

**Order**: oldest tx-time first (stable prefix).

**Detail**: scoring fn assigns `:full` or `:concise`:

```clojure
score = (recency × w-recency)
      + (use-count × w-use)
      + (current-ns? × BIG)
;; :full when score > threshold; :concise otherwise.
```

LRU eviction if over a budget: evict lowest-`:seon.fn/last-used-at` from the `:concise` tier first. Current-ns fns never evicted; system-prompt + conventions sticky-prefix never evicted.

**Sample output**:

```text
## seon.fn  seon.db/transact!  (used 12× — last 3m ago)
;; Full source: ...
;; #'seon.db/transact!

## seon.fn  seon.fs/walk-dir  (used 1× — last 45m ago)  [concise]
;; Walks a directory tree, returns absolute paths.
;; #'seon.fs/walk-dir

## seon.fn  seon.health/import  [evicted from concise tier]
```

**When it's best**: agents in extended sessions where the working set is small but the substrate has grown.

### 5.4 Strategy 4 — `data-shape-matching`

**Goal**: the best long-term signal — what data exists in the system tells us which fns are useful. If the user's DB has `:seon.email.message/*` entities but no `:seon.trading.*` entities, the agent should see email-related fns at `:full` and trading fns hidden or `:concise`.

**Query**: walk all `:seon.fn` entities. For each, look at its input schema (the `:cat` types in `:malli/schema`). Use the existing shape graph (MEMORY.md "Shape Graph" + `project_shape_graph.md` — 138 shapes, 333 entries already indexed) to check: do any entities currently in the user-scoped DB match the fn's input shape?

Include:

- all schemas the user's actual data uses (filter by `(d/datoms db :aevt <attr>)` returning non-empty)
- fns whose input or output shape matches user-data shapes — at `:full`
- fns whose shape matches nothing in the user's data — exclude entirely, or `:concise` if they're in the agent's call history
- recent messages + evals at the tail

**Order**: foundational data (schemas the user uses) → derived fns (that consume/produce those shapes) → recent activity.

**Detail**: shape-matched → `:full`; shape-unmatched-but-recently-called → `:concise`; everything else → excluded.

**Sample output**:

```text
## Data shape  :seon.email.message/*  (47 entities in db)
;; #'seon.schema/<shape-cluster :seon.email.message>

## seon.fn  seon.email/search  (input matches your data)
;; Full source: (defn search [{:seon.email.search/keys [query]}] ...)
;; #'seon.email/search

## seon.fn  seon.email.message/parse-mime  (input matches your data)
;; Full source: ...
;; #'seon.email.message/parse-mime

;; (trading-related fns hidden — no :seon.trading/* entities in db)
```

**When it's best**: the agent's job is to operate on the user's data. Show only the fns that can touch that data.

**Why it's last to build**: it depends on the shape-graph index being live in the CLJS pod (currently CLJ-side per MEMORY.md), and on the shape-graph having coverage for the fns we want to index. Phase 4 either ports the shape graph to CLJS or queries it across the JVM/pod boundary. This is the riskiest piece of the plan.

## 6. Bootstrap order at agent boot

When `start-agent!` runs, it transacts entities in a deliberate order so the tx-log reads like a freshly opened editor. This means the naive strategy (which orders by tx-time) gets sensible initial content for free.

1. **`:seon.system-prompt`** entity (one-time, `:seon.sticky/position :prefix`, `:seon.sticky/order 0`).
2. **`:seon.conventions`** entity (one-time, `:prefix`, `:order 1`).
3. **Core schemas** — alphabetical by keyword. Schemas don't change often; sorting alphabetically gives a stable mid-prefix block.
4. **Core fns** — sorted least-referenced first (the same ordering `most-referenced` would compute), so even naïve-chronological gets reasonable initial fn order without computing reference counts.
5. **Agent's home `:seon.ns` entity** — the empty home ns the agent will `defn` into.

After boot, every subsequent tx (eval, message, fn defn) lands at the tail with a fresh tx-time. The strategies pick from there.

## 7. Switching strategies

The agent (or the user via the inspector) transacts:

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.agent/id          (seon.db/current-agent-id)
     :seon.agent/ctx-render-fn 'seon.strategies.most-referenced/render}]})
```

The next `assemble-ai-context` call resolves the new symbol and uses it. No restart, no special verb, no event bus, no broadcast.

The inspector's strategy dropdown is a tiny `data-on-change` POST to `/agent/<id>/ctx-render-fn` which does exactly the transact above.

**Cache impact**: switching changes the prefix → the next turn is an LLM-cache miss. After that turn the new prefix is hot. We **document this trade-off in the system prompt** and encourage agents to commit to a strategy for a session unless they're deliberately experimenting.

## 8. Agent-authored strategies

An agent writes its own strategy by `defn`-ing it in its home ns with the right `:malli/schema`:

```clojure
;; in seon.agent.XAR-...
(defn my-favorite-render
  "Like data-shape-matching but boosted by my taste."
  {:malli/schema [:=> [:cat :seon.render.ctx/request] :seon.render.ctx/response]}
  [req]
  ;; ... own logic ...
  )
```

The analyzer picks up the new fn at eval time and writes its `:seon.fn` entity with `:seon.fn/output-schema :seon.render.ctx/response`. `list-strategies` immediately returns it. The agent transacts `:seon.agent/ctx-render-fn 'seon.agent.XAR-.../my-favorite-render` onto itself. Done.

This is the V2 vision (audit doc §1.2) realized: agent customization via the same mechanism as `schema/register!` — by writing a fn with the right shape from your own ns.

## 9. Code organization

```text
src/seon/render.cljs                       — assemble-ai-context dispatcher    (~50 LOC)
src/seon/render/list_strategies.cljs       — discovery query                   (~20 LOC)
src/seon/inspect.cljs                      — extend with show + dropdown helper (~80 LOC added)
src/seon/strategies/naive.cljs             — Strategy 1 — naive-chronological  (~80 LOC)
src/seon/strategies/most_referenced.cljs   — Strategy 2 — most-referenced      (~180 LOC)
src/seon/strategies/chronological_decay.cljs — Strategy 3 — chronological-decay (~220 LOC)
src/seon/strategies/data_shape.cljs        — Strategy 4 — data-shape-matching  (~250 LOC)
src/seon/handlers/{fn,schema,ns,eval,message}.cljs — extend each render fn
                                              with `:full` + `:concise` arities (~30 LOC each, +150 LOC total)
```

**Estimated total**: ~1030 LOC across four strategies + dispatcher + helpers + handler updates. Strategy 4 is the riskiest and biggest; Strategy 1 + dispatcher + inspector dropdown fit in a ~250 LOC patch.

## 10. Implementation phases

### Phase 1 (week 1) — minimum viable framework + Strategy 1

**Scope**:

- New schemas: `:seon.render.ctx/{request,response,full,concise}`
- New attr: `:seon.agent/ctx-render-fn` (defaults to `'seon.strategies.naive/render`)
- `assemble-ai-context` becomes a dispatcher: resolve the slot, call the fn, fall back to naive if symbol misses
- `list-strategies` fn + tiny inspector dropdown (POST to set the slot)
- `seon.inspect/show` for drill-in via `#'sym` references
- Extend `seon.handlers.{fn,schema,ns,eval,message}` with `:full` + `:concise` arities
- Real-Clojure-reference rendering in concise output
- Bootstrap-order tx in `start-agent!` (system-prompt + conventions + schemas alpha + fns by ref-count-or-zero + home ns)
- **Back-compat**: `:seon.render/ai` continues to work as an alias for `:seon.render.ctx/full`. No migration tx needed.

**Acceptance**:

- Open browser at `/agent/<id>`, see strategy dropdown with one option (`naive-chronological`)
- `(seon.inspect/ctx-preview {:seon.agent/id "..."})` returns the same text the LLM sees
- `(seon.inspect/show {:seon.inspect/symbol 'seon.db/query})` returns the full render of the `seon.db/query` `:seon.fn` entity
- 0 lint warnings

### Phase 2 (week 2) — Strategy 2 + reference graph

**Scope**:

- New attr `:seon.fn/refs` — vector of symbols this fn calls, computed by analyzer when the `:seon.fn` entity lands
- Reference-count derivation (Datalog query against `:seon.fn/refs`)
- `seon.strategies.most-referenced/render`
- Inspector dropdown now shows two strategies; switching is a one-click op

**Acceptance**:

- Switch from naive to most-referenced in the inspector; same agent, different rendered order
- A new `defn` form in the agent's home ns lands and surfaces near the bottom of the ctx after one turn (because its initial use-count is low)

### Phase 3 (week 3) — Strategy 3 + use-count tracking

**Scope**:

- New attrs `:seon.fn/use-count :int`, `:seon.fn/last-used-at :inst`
- Post-eval handler: walk evaled form, increment use-count + bump last-used-at for each fn symbol found
- `seon.strategies.chronological-decay/render` with scoring + LRU eviction
- Per-agent attrs: `:seon.agent/concise-chars`, `:seon.agent/decay-half-life-min`

**Acceptance**:

- Run an agent through 50+ evals; verify that fns called many times stay `:full` and ones called once fade to `:concise`
- Total token estimate stays under a configured budget

### Phase 4 (week 4+) — Strategy 4 + the experiment

**Scope**:

- Port (or query across pod boundary) the shape graph index to CLJS
- `seon.strategies.data-shape/render`
- A/B framework: spawn 3 agents with 3 different `:seon.agent/ctx-render-fn` values, give them the same task, log outcomes (see §11)

**Acceptance**:

- Same agent task, three strategies, three different rendered contexts, measurable difference in outcome quality (success rate, token cost, redundant-question count)
- Agents using `data-shape-matching` outperform the other three on tasks that operate on user data

## 11. Test plan

The Platform track is generating test data — agents reading source, building Q/A understanding documents, writing tests. This gives us a corpus of "agent tasks with known-good outcomes" to A/B strategies against.

**Strategy A/B framework**:

1. Spawn three agents from a clean state, each with a different `:seon.agent/ctx-render-fn`.
2. Send the same prompt to all three (queued via `/chat?agent=...`).
3. Log every turn's: token-estimate, eval count, eval success rate, time-to-first-useful-output, redundant-question count (heuristic — questions the agent asks that were answered in its ctx).
4. Final acceptance: did the agent complete the task? did its assistant reply match the known-good shape?

**Metrics**:

- **Time to first useful output** — wall-clock from POST to first `:assistant` message.
- **Eval success rate** — `(:ok? true) / total evals`.
- **Redundant-question rate** — proportion of agent questions whose answers were already in its ctx (manual scoring, but consistent across strategies because the same prompt and same DB).
- **Token cost per turn** — `:seon.render/token-estimate` averaged.

**Logic-issue detection**: agents reading source via different strategies will surface different conceptual gaps. Cross-reference findings — a gap surfaced by all three strategies is a real docs issue; one surfaced by only one is a strategy artifact.

**Unit tests**: each strategy gets a tablet of fixture-DB → expected `:seon.render/entities` order tests. Cheap to write, catches regressions in the ordering logic.

## 12. Risks + open questions

### 12.1 Risks

1. **Shape-graph coverage in CLJS** (Phase 4). The shape graph is JVM-side per MEMORY.md. Porting to CLJS or querying across the pod boundary is the single largest engineering risk in this plan. Mitigation: design now, defer build to Phase 4 when we have the other three strategies as fallbacks.
2. **Analyzer output-schema attribute**. `list-strategies` depends on `:seon.fn/output-schema` being indexed. If the analyzer doesn't yet write it, Phase 1 must add it. Quick check + small patch; not a blocker.
3. **Cache-miss thrash**. If the inspector dropdown is too inviting, agents (or curious users) will switch strategies between turns and pay the LLM-cache miss every time. Mitigation: log a `;; ⚠ strategy switched, prefix will miss cache next turn` line in the ctx for the turn after a switch.

### 12.2 Open questions

These aren't blocking Phase 1 but want a Sean decision before Phase 2 or 3:

- **Reference count** (Phase 2): is `:seon.fn/refs` per-source-symbol-occurrence or per-unique-callee? (PRD assumes per-unique-callee — one ref per source even if called 5 times.)
- **Decay half-life** (Phase 3): default minutes for `:seon.agent/decay-half-life-min`? PRD proposes 30 min; cheap to tune.
- **Shape-graph crossing the JVM/pod boundary** (Phase 4): port to CLJS, or expose a `/shape-graph/query` HTTP endpoint the pod hits? Port is cleaner; HTTP is faster to build.

## 13. What we are explicitly NOT doing

- **No `register-strategy!` verb.** Strategies are discovered by output schema, full stop. Adding a registration verb would create two sources of truth (the registry + the program graph) and they would drift.
- **No three-level detail.** `:full` + `:concise` with concise-fallback-to-truncated-`:full`. A strategy that wants to hide an entity simply doesn't include it.
- **No separate "section composer" alongside the strategies.** Each strategy IS the composition. We considered (in earlier drafts) a `:specs / :related-ns-fns / :current-ns-fns / :eval-history / :messages / :errors` section registry; that became "the strategy does whatever it wants with the entities it picks." Sections were a way of saying "a strategy can mix detail levels per group" — the strategy is allowed to do that directly without a separate framework.
- **No breaking change to `:seon.render/ai`.** The existing symbol on every entity continues to work; the substrate treats it as an alias for `:seon.render.ctx/full`. Older entities require no migration.
- **No tone-deaf "future strategies" list.** What we build after Phase 4 is decided by what the four-strategy experiment teaches us — not by speculative additions.

## 14. Acceptance for the PRD itself

This PRD is done (status moves from `draft` to `active`) when:

- Sean has signed off on the four strategies and the phase ordering
- The three open questions in §12.2 have decisions (or explicit "decide later" punts)
- Phase 1 has a tracking issue in `docs/seon/orchestrator/issues/`
