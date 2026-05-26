---
type: prd
status: draft
tags: [prd, agent, runtime, render, architecture]
---

# Agent-context render — the consolidated PRD

This document supersedes the earlier ctx-render fragments (the
section-with-detail-levels draft of this same file, plus
`research/repl-session-context-template-2026-05-26.md` and
`research/agent-loop-pattern-survey-2026-05-25.md` /
`research/re-frame-vs-roll-own-2026-05-25.md` for the *design*
questions). Those files remain on disk as history; this is what
implementation agents read.

Authoritative as of 2026-05-26.

---

## 1. Vision

The agent's LLM context is the **REPL transcript its work would have
produced**, reconstructed from the tx-log. Every line is a valid
Clojure form, a Clojure line comment, or a namespaced map literal
wrapped in `(comment …)`. Concatenated, the rendered text reads
top-to-bottom like a session log — substrate boot at the front (stable
prefix, cacheable forever), conversation and evals at the tail
(volatile). The agent doesn't see invented `[user]` brackets, markdown
headings, or "tool envelope" chrome. It sees a `.clj` file the reader
would happily parse.

A **render strategy** is a normal Clojure fn `(req → response)` whose
output schema is `:seon.render.ctx/response`. The substrate ships
`naive-chronological` as the default and three more strategies
(`most-referenced`, `chronological-decay`, `data-shape-matching`) built
on the same per-entity render fns. Strategies are discovered by
walking the program graph (`:seon.fn` entities whose output schema
matches), not registered in a separate table. The agent switches
strategy by transacting `:seon.agent/ctx-render-fn 'sym` onto itself.

---

## 2. Storage discipline (load-bearing)

**The database stores metadata pointers, not transient data.** If
something can be re-derived from source, it does not live in the DB.

### What goes in the DB

- Schema registrations (`:seon.schema/key`, `:seon.schema/shape`).
- Fn source and metadata (`:seon.fn/sym`, `:seon.fn/source`,
  `:seon.fn/output-schema` — these are pointers; the body is in
  `:source`).
- Test source (`:seon.test/sym`, `:seon.test/source`).
- Test status **metadata only**: `:seon.test/last-passed-at`,
  `:seon.test/last-failed-at`, `:seon.test/last-run-id`. The per-run
  event sequence does NOT live in the DB.
- Eval pointers: `:seon.eval/id`, `:seon.eval/source`,
  `:seon.eval/result-edn` (the small printable result), the error
  envelope `:seon.eval/error` (one small map with `:cause` snippet,
  not the full stack).
- Message content (`:seon.message/content`, `:seon.message/role`,
  `:seon.message/at`, `:seon.message/from`, `:seon.message/to`).
- Ns entities (`:seon.ns/name`, `:seon.ns/source`).

### What does NOT go in the DB

- Full test event sequences (per-assertion pass/fail traces). These
  live in the agent-ns stash keyed by `:seon.test/last-run-id`.
- Full LLM API request/response bodies. Pointers in the DB; bodies in
  blob storage or a stash if needed.
- Log messages.
- Stack traces beyond a small `:cause` snippet inside the error
  envelope.
- Precomputed projections of relationships (see §3).

This mirrors the three-tier rule from MEMORY.md: **DB datoms =
renderer projections only; blobs = persistent full content;
globalThis stash = volatile per-session live values.** Transient data
lives in an agent-ns stash (atom) keyed by an id stored in the DB.

---

## 3. No precomputed projections

If a relationship can be derived dynamically — via the analyzer,
Datalog, or a small query — it is **not** stored as a precomputed
projection.

- **`:seon.test/tests-fn`** (which fns a test exercises): NOT stored.
  Computed at render time by walking `:seon.test/source` via the
  analyzer (`seon.analyzer-info/fn-refs-in-source`). The test render
  fn calls this to cluster a test alongside the fn it exercises.
- **`:seon.fn/use-count` / `:seon.fn/last-used-at`** (for
  `chronological-decay`): NOT stored. Computed at strategy run time
  by querying the eval log for fn-symbol mentions.
- **The render strategy fns themselves**: ordinary `:seon.fn`
  entities. Discovered via the program graph, not a registered
  strategy table.

**The cache exception.** If a derivation is expensive enough to
re-run every render, memoize it in a process atom and rebuild
deterministically from the DB at boot. (Mirror of how the schema
registry works: persisted entities are the truth; the in-memory
registry is a fast lookup rebuilt on load.) Default is no caching —
measure before caching.

---

## 4. The REPL-transcript output shape

Each entity has **one** render fn (one symbol on the entity:
`:seon.render/ai`). A strategy that wants a "concise" version
truncates the same output. There is no `:full` / `:concise` /
`:micro` ladder of separate render fns.

### 4.1 `:seon.system-prompt` and `:seon.conventions`

Top-of-file `;;` comment block. Stable substrate entities, written
once at boot. Not drillable.

```clojure
;; ============================================================
;; You are a Clojure-fluent agent inside a CLJS pod on Node.
;; Emit forms as text — no markdown fences, no tool envelopes.
;; Narrate with `;` comments. Each contiguous comment block
;; binds to the form that follows; form N+1 runs even if N failed.
;; Your home namespace is `(ns seon.agent.XAR-…)`.
;; ============================================================
```

### 4.2 `:seon.ns`

Front-block only (substrate-shipped) **or** the agent's home-ns
opener. Per-eval `ns` forms are subsumed by their producing eval.

```clojure
(ns seon.db "Datalog reads + writes. The sole DB API.")
```

Drill-in: `seon.db` evaluates to the namespace; agent can pull
`(seon.db/pull {:seon.db/ref [:seon.ns/name 'seon.db] ,,,})`.

### 4.3 `:seon.schema`

Front-block only **or** drill-in. Per-eval `register!` calls are
subsumed by their producing eval. Rendered as the literal call:

```clojure
(seon.schema/register! :seon.message/content :string)
```

Drill-in: keyword evaluates to itself; **no auto-enrichment**. Agent
pulls via `(seon.db/pull {:seon.db/ref [:seon.schema/key
:seon.message/content] ,,,})` or reads the front-block `register!`
call.

### 4.4 `:seon.fn`

Front-block only **or** drill-in. Per-eval `defn` forms are subsumed
by their producing eval. Rendered as the literal form with an elided
body:

```clojure
(defn seon.db/query
  "Datalog query. Map-in, map-out."
  {:malli/schema [:=> [:cat :seon.db/query-request] :seon.db/query-response]}
  [{:seon.db/keys [query args]}])
```

The body is elided — the form **reads** as valid Clojure but **does
not eval** in a fresh REPL. This is the deliberate boundary between
"readable top-to-bottom" (hard-required) and "eval-able
top-to-bottom" (rough — documented honestly, not papered over).

Drill-in: `seon.db/query` evaluates to a Var; the renderer enriches
with `;; ↳ <pointer-to-:seon.fn-entity>` if a DB hit exists.

**Tests rendering.** The fn render is followed by any tests that
exercise it (analyzer walks `:seon.test/source` for fn-refs; cluster
by overlap with the rendered fn's symbol):

```clojure
(deftest seon.db.query-test
  (testing "round-trip"
    (is (= [[1]] (q '[:find ?x :where [?x :a 1]] db)))))
;; ↳ :last-passed-at #inst "2026-05-26T09:00:00Z"
```

### 4.5 `:seon.message`

Rendered as a namespaced map literal inside `(comment …)`:

```clojure
(comment
  #:seon.message{:role :user
                 :from :user
                 :at #inst "2026-05-26T10:00:00Z"
                 :content "build a calculator with add"})
```

Drill-in: keywords (`:seon.message/role`) evaluate to themselves; **no
auto-enrichment**. Agent uses `seon.db/pull` or the front-block
`register!` for the schema.

### 4.6 `:seon.eval`

The load-bearing case. Rendered as: narration (verbatim `;` lines) →
literal source → `;; => result` (or `;; ! error`) → handle.

```clojure
;; Define add and test it.
(defn add [x y] (+ x y))
;; => #'seon.agent.XAR-2605261000/add
;; #:seon.eval{:id "ev-1"}

(add 2 3)
;; => 5
;; #:seon.eval{:id "ev-2"}
```

Failure:

```clojure
(throw (ex-info "boom" {}))
;; ! #:seon.eval/error{:cause "boom"}
;; #:seon.eval{:id "ev-3"}
```

If the eval result is a Var with a DB hit, the renderer appends
`;; ↳ <:seon.fn entity pointer>` enrichment. (Same enrichment the
drill-in path uses.)

Drill-in: `[:seon.eval/id "ev-1"]` evaluates to a vector; **no
auto-enrichment**. Agent calls `(seon.db/pull {:seon.db/ref
[:seon.eval/id "ev-1"] :seon.db/pull-pattern [:*]})`.

### 4.7 `:seon.async-result`

A foreign event the agent watched land. One permitted "chrome" line
(a comment marker), because there is no Clojure form that naturally
expresses "something happened while you were stopped":

```clojure
(comment
  ;; --- async result arrived ---
  #:seon.async-result{:of "corr-llm-99"
                      :ok? true
                      :value "(brief textual summary)"
                      :at #inst "2026-05-26T12:00:04Z"})
```

Drill-in via correlation id.

### 4.8 `:seon.handler`

Substrate ships **exactly one** worked example in the front block —
the wake-on-message handler — to teach the mechanism by example.
Subsequent agent-registered handlers also render (chronologically, at
their tx-time):

```clojure
(seon.handler/register!
  {:seon.handler/name  :seon.handler/wake-on-message
   :seon.handler/match {:seon.handler.match/attr :seon.message/to}
   :seon.handler/fn    'seon.handlers.wake/wake-on-message})
```

### 4.9 Subsumption rule (decisive)

An entity is chronologically rendered **at most once**, in its most
informative form. `:seon.fn` / `:seon.schema` / `:seon.ns` entities
tee'd by an eval are subsumed by that eval (which already contains
the source). They are **not stamped with `:seon.render/ai` at the tee
write site** — only the front-block substrate-shipped versions are
stamped. The tee'd entities exist for drill-in queries only.

---

## 5. Drill-in: Clojure-native eval

No substrate wrapper helpers. No `(inspect/show …)`, no
`(seon.schema/show …)`, no `(seon.eval/result …)`. Drill-in is normal
Clojure evaluation:

- **Var**: `seon.db/query` evaluates to a Var → renderer enriches
  with `;; ↳ <:seon.fn entity>` if DB hit. (The "renderer enriches"
  applies only when the eval's *result* is being rendered — i.e. for
  values the agent sees as eval output. Drill-in evaluations the
  agent performs return the bare value; the agent then `pull`s if
  they want more.)
- **Keyword** (`:seon.message/role`): evaluates to itself; **no
  enrichment**. Use `seon.db/pull` or read the front-block schema.
- **Lookup ref** (`[:seon.eval/id "ABC"]`): evaluates to itself; **no
  enrichment**. Call `(seon.db/pull {:seon.db/ref [:seon.eval/id
  "ABC"] :seon.db/pull-pattern [:*]})`.

The substrate exposes `seon.db/pull` and `seon.db/query`. Both
already exist. No new verbs.

---

## 6. Substrate boot order (deterministic)

At `start-agent!`, the substrate transacts entities in this order so
the tx-log reads like a freshly opened editor for the
`naive-chronological` strategy:

1. `:seon.system-prompt` (sticky front).
2. `:seon.conventions` (sticky front).
3. Core `:seon.schema` entities — alphabetical by keyword.
4. Core `:seon.fn` entities — least-referenced first (computed from
   the substrate's call-graph at build time; fallback to alphabetical
   if not yet computed).
5. Core `:seon.test` entities — paired with their fn-refs (rendered
   adjacent to the fn they exercise via analyzer fn-refs).
6. Agent's home `:seon.ns` entity (`(ns seon.agent.XAR-…)`).
7. One substrate-shipped `:seon.handler` registration + one worked
   message-pair example, so the agent sees the
   `(handler/register! …)` call and the resulting wake-on-message
   pattern in their context.

After boot, agent activity (user messages, agent evals,
async-results) accrues at the tail with fresh tx-times. Front stays
cacheable.

---

## 7. The four render strategies

All four return `:seon.render.ctx/response`. They share the per-entity
render fns from §4; they differ only in **which entities they pick,
in what order, and whether they truncate**.

### 7.1 `naive-chronological` (default)

Every entity carrying `:seon.render/ai`, scoped to the agent via
tx-meta `:seon.db/agent-id`, sorted by tx-time oldest-first. No
truncation. This is what `assemble-ai-context` already does; nailing
the per-entity render shape from §4 gets us a usable agent without
ever building strategies 2-4.

### 7.2 `most-referenced`

Walk the program graph at strategy-run time, parse each
`:seon.fn/source` for symbols, compute call-graph in-degree. Order:
least-referenced front (stable prefix), most-referenced before the
volatile tail. Top-K rendered full; tail truncated. No DB writes —
all derived.

### 7.3 `chronological-decay`

Query the eval log at strategy-run time; for each `:seon.fn`,
compute use-count + recency from the log. Score = recency × w +
use-count × w + (current-ns? × BIG). Above threshold: render full.
Below: truncate or omit. LRU eviction over a configured budget.
Current-ns + system-prompt + conventions: never evicted. No new
attrs.

### 7.4 `data-shape-matching`

For each `:seon.fn`, check its input schema against entities
actually present in the agent-scoped DB (via `d/datoms` aevt index).
Shape-matched fns: full. Shape-unmatched-but-recently-called:
truncated. Everything else: omitted. Depends on the shape graph
being reachable from CLJS (port or HTTP query — Phase 4 decision).

The four strategies are **discovered**, not registered: a Datalog
query for `:seon.fn` entities whose `:seon.fn/output-schema =
:seon.render.ctx/response`. A new `defn` of a strategy in any
namespace surfaces in the inspector's dropdown on its next render.

---

## 8. What this refactor DELETES

This is a refactor, not an addition. The following code goes away.

- **`src/seon/web/broadcast.cljs`** and **`src/seon/web/sse.cljs`**:
  parallel SSE path with zero live consumers. The per-agent inspector
  SSE replaces it entirely. (~250 LOC)
- **Chronological rendering of `:seon.fn` / `:seon.schema` /
  `:seon.ns` entities.** These are subsumed by their producing eval
  (§4.9). The tee write-sites stop stamping `:seon.render/ai`.
- **`seon.handlers.retro_stamp`** (or its no-op portion): if
  write-site stamping is comprehensive, the retro pass is moot.
  Verify before delete; the audit says 3-of-5 kinds it tries to stamp
  have no rows.
- **Substrate drill-in helpers**: any proposed `(inspect/show ...)`,
  `(result ...)`, `(seon.schema/show ...)`. The §5 rule replaces
  them.
- **The three-level `:micro` / `:compact` / `:full` detail ladder.**
  One render fn per entity kind. Strategy truncation is the only
  variation. The `:seon.render/ai` symbol-on-entity attribute stays;
  there is no `:seon.render.ai/full` / `:seon.render.ai/concise`
  split.
- **`seon.render/ai-render` and `seon.render/html-render`** as
  separate fns if their callers can use the same internal
  resolve-and-call as `assemble-ai-context`. (~25 LOC; audit before
  delete.)
- **`seon.inspect`**: folds into `seon.render` + `seon.handler`. (See
  audit doc §6 B2.)

---

## 9. Implementation phases

Five phases, sequenced. LOC estimates exclude tests. Each phase is
self-contained — partial landing leaves the system in a working
state.

### Phase 1 — Storage discipline + write-site stamping (~150 LOC)

Stop stamping `:seon.render/ai` on tee'd `:seon.fn` / `:seon.schema` /
`:seon.ns` entities. Stamp at substrate boot for the front-block
versions. Update `:seon.eval`, `:seon.message`, `:seon.async-result`
write-sites to stamp comprehensively. Delete `retro_stamp` if it
becomes a no-op.

**Dependencies**: none. Foundation for everything else.

### Phase 2 — Per-entity render fns rewritten (~250 LOC)

Rewrite `seon.handlers.eval/render-ai`, `…/message/render-ai`,
`…/fn/render-ai`, `…/schema/render-ai`, `…/ns/render-ai` to emit the
§4 templates. Add `:seon.async-result` render. Add tests render
clustered with their fn. Add result-enrichment for Var results.

**Dependencies**: Phase 1 (the entities being rendered must be
correctly stamped/unstamped first).

### Phase 3 — Substrate boot order + front block (~120 LOC)

Implement §6 in `start-agent!`. Transact system-prompt, conventions,
core schemas, core fns, home-ns, one example handler + worked
message pair. Verify the chronological renderer produces a sensible
front block on a fresh agent.

**Dependencies**: Phase 2 (renderers must produce the right shape
before front-block content is meaningful).

### Phase 4 — Strategy dispatch + `naive-chronological` formalized (~100 LOC)

Schemas `:seon.render.ctx/{request,response}`. Attr
`:seon.agent/ctx-render-fn` defaulting to
`'seon.strategies.naive/render`. `assemble-ai-context` becomes a
resolve-and-call dispatcher. `list-strategies` Datalog query against
`:seon.fn/output-schema`. Inspector dropdown POSTs to set the slot.
Delete `seon.web.broadcast` and `seon.web.sse`.

**Dependencies**: Phase 3 (strategy 1 needs valid front-block output
to be useful).

### Phase 5 — Strategies 2, 3, 4 (~450 LOC total: 150 + 150 + 150)

`most-referenced`, `chronological-decay`, `data-shape-matching`. All
three are derived-only — no new persisted attrs. Strategy 4 depends
on shape-graph reachability from CLJS (port or HTTP query — Sean
decides; see §11).

**Dependencies**: Phase 4. Strategies 2/3 are independent of each
other; 4 has the shape-graph dependency.

---

## 10. Verification per phase (Socratic questions for the verifier)

The verifier agent for each phase should be able to answer YES to all
three questions. If they cannot, the phase has not landed.

### Phase 1

1. **Drill into a tee'd entity**: query `(d/datoms db :aevt
   :seon.render/ai)`. Are the only rows for substrate-shipped
   front-block entities plus runtime `:seon.eval` / `:seon.message` /
   `:seon.async-result`? (Tee'd `:seon.fn` / `:seon.schema` /
   `:seon.ns` MUST be absent.)
2. **Cold-start**: a fresh agent, no chats. Does `(d/datoms db
   :aevt :seon.render/ai)` return the front-block entity count
   (system-prompt, conventions, core schemas, core fns, home-ns,
   example handler, example message pair) — and no more?
3. **Stamp idempotency**: re-run `start-agent!` against an existing
   agent's DB. Does the stamp count stay constant? (No re-stamping
   on resume.)

### Phase 2

1. **Eval render**: paste the rendered text for a single `:seon.eval`
   entity into a `.clj` buffer and run Clojure's reader. Does it
   parse without errors? (Read OK is required; eval is not.)
2. **Message render**: same test for `:seon.message` — does the
   `(comment #:seon.message{…})` block read?
3. **Fn enrichment**: when a Var (`#'seon.db/query`) is the result of
   an eval, does the rendered output include the
   `;; ↳ <pointer>` enrichment line?

### Phase 3

1. **Front-block determinism**: boot two fresh agents
   back-to-back. Do their front blocks render identically (modulo
   agent id)?
2. **Boot order**: is the rendered text in this order — system-prompt,
   conventions, schemas, fns, home-ns, example handler, example
   message pair? (Sort by tx-time should yield this.)
3. **Cache stability**: after one chat turn, has the prefix (first N
   bytes of rendered text up to the front-block boundary) changed at
   all? It must not.

### Phase 4

1. **Strategy switch**: transact `:seon.agent/ctx-render-fn 'foo`
   pointing at a fn that returns a constant string. Does the next
   `assemble-ai-context` return that string?
2. **Discovery**: define a new strategy in the agent's home ns
   (`defn` with the right `:malli/schema`). Does `list-strategies`
   include it within one tx?
3. **Broadcast deletion**: grep the live pod's process for the
   `seon.web.broadcast` ns. Is it absent (the file is gone)? Does the
   inspector still update on every tx?

### Phase 5

1. **Strategy differentiation**: spawn three agents with three
   different strategies, send the same prompt. Do the rendered
   contexts differ in entity order or truncation?
2. **No new attrs**: query the schema registry post-Phase-5. Are
   `:seon.fn/use-count` and `:seon.fn/last-used-at` absent? (They
   should be — strategies 2/3 derive these at run-time.)
3. **Shape-matching agent**: in an agent with `:seon.email.message`
   entities but no `:seon.trading.*` entities, does the
   `data-shape-matching` rendered output include email-related fns
   at full and omit trading fns entirely?

---

## 11. Open questions for Sean

Three only. The rest are decided in this doc.

1. **Elided-body sentinel.** `(defn foo [x])` is invalid Clojure
   (defn requires a body). Options: (a) ship real bodies in the
   front block (token cost), (b) emit `(defn foo [x] ::elided)` (ugly
   but valid), (c) accept that the front block reads but does not
   eval — document the rough-fidelity contract. **Recommendation: (c).**
   The hard property is "the reader accepts it"; eval-ability is
   rough by design.
2. **Shape-graph reachability from CLJS (Phase 5 strategy 4).**
   Port the JVM-side shape graph to CLJS, or expose a
   `/shape-graph/query` HTTP endpoint the pod hits? Port is cleaner;
   HTTP is faster to build and leaves the JVM authoritative.
3. **`#'self` in rendered messages.** Worked-example message pairs
   use `[#'self]` for `:seon.message/to`, which needs a dynamic var
   bound at the agent's turn. Alternative: substitute the literal
   `[:seon.agent/id "XAR-…"]` lookup ref (noisier but resolvable in a
   fresh REPL). **Recommendation: keep `#'self`; document the
   binding.** Same rough-fidelity contract as Q1.

---

## Cross-references

- `docs/prds/agent-runtime/codebase-audit-and-cleanup-plan-2026-05-26.md`
  — canonical for cleanup-task tracking (NOT superseded; it tracks
  what to delete and is referenced from this PRD).
- `docs/prds/agent-runtime/research/repl-session-context-template-2026-05-26.md`
  — superseded for design decisions; retained for history.
- `docs/prds/agent-runtime/research/agent-loop-pattern-survey-2026-05-25.md`
  — superseded for design decisions; retained for history.
- `docs/prds/agent-runtime/research/re-frame-vs-roll-own-2026-05-25.md`
  — superseded for design decisions; retained for history.
- `src/seon/render.cljs` — `assemble-ai-context` is the dispatch
  point Phase 4 rewrites.
- `src/seon/handlers/{eval,message,fn,schema,ns}.cljs` — Phase 2
  rewrite targets.
- `MEMORY.md` "three-tier storage rule" — the storage-discipline
  rule §2 codifies.
- `CLAUDE.md` "Data Rules" — the namespaced-keyword discipline this
  PRD assumes.
