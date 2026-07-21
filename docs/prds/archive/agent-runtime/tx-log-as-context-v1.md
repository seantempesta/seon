---
type: prd
status: draft
tags: [prd, agent]
---

# Tx-log-as-context — alternative agent-render model

Companion / counterproposal to
[unified-loop-v1.md](unified-loop-v1.md). Same dispatcher, same
handlers, same effects-as-data. **The render side is different.**
Where v1 queries `:seon.ctx/*` entities and slot-merges their
renderers, this spec walks the tx log directly: every renderable
entity touched in the last N transactions, sorted oldest-first,
concatenated. No `:seon.ctx/*` namespace, no `assemble-ai-context`
fn doing slot lookup, no handlers that exist purely to assert a ctx
entity that mirrors a "real" entity they just touched.

This document is written to be evaluated, not advocated. §10
recommends. §§1-9 do the work needed to recommend honestly.

## 1. Sean's proposal — encoded

The five claims, restated as the design they imply:

1. **The tx log IS the context.** `assemble-ai-context` is a query
   over the tx log: take the last N tx (per some windowing rule —
   §3), collect the entity ids touched in their `:tx-data`, dedupe,
   keep those carrying a `:seon.render/ai` attr, sort by the tx-time
   of their most-recent assertion, render each, concatenate.
2. **Per-entity render.** No change from v1: `:seon.render/ai` is a
   symbol on the entity itself. The difference is the *set* of
   entities considered — v1 hand-curates the set via
   `:seon.ctx/agent`; this spec derives it from "what's been touched
   recently."
3. **Sticky entities anchor the prefix.** Three flavors (§4):
   naturally sticky (old, never re-asserted, sorts to top by tx-time),
   explicitly anchored (`:seon.sticky/position :prefix`), and
   pinned-but-mutable (problematic — see §4).
4. **Agent self-tunes the window.** `:seon.agent/window-size` (and
   friends) on the agent entity. The renderer reads them; the agent
   transacts updates to them as it learns.
5. **System prompt is just a sticky entity.** Transacted once at
   boot with `:seon.render/ai 'seon.runtime/system-prompt`,
   `:seon.sticky/position :prefix`. Nothing special.

Plus the structural alignment: handlers receive
`{:seon.db/db-before _ :seon.db/db-after _ :seon.db/tx-data _}` from
`d/listen!` — already the v1 shape (see `seon.db` listen contract,
lines 81-92 of `src/seon/db.cljs`). **The render side is the only
thing this spec changes.**

## 2. Per-agent scoping

A user message to agent-A must not appear in agent-B's render. v1
solves this by query: each `:seon.ctx/*` row carries `:seon.ctx/agent`
and the renderer filters. We need an equivalent here, since walking
"the last N tx" pod-wide would leak.

**Decision: reuse `:seon.db/agent-id` in tx-meta** (already shipped
2026-04-28, commit 5a82742). Every transact that originates from an
agent — `ask-and-eval!`, `record-eval!`, handler txs scoped to an
agent, the eventual `seon.handler/register!` agent-form — already
tags its meta. Substrate-wide tx (handler registrations at boot,
schema loads) get no `:seon.db/agent-id` and read as global.

The renderer query becomes:

```clojure
;; pseudocode — concrete query in §7
(d/q '[:find ?e ?tx-time
       :in $ ?agent
       :where
       [?e :seon.render/ai _]
       [?e _ _ ?tx]
       [?tx :db/txInstant ?tx-time]
       [(get-else $ ?tx :seon.db/agent-id :substrate) ?owner]
       [(or (= ?owner ?agent) (= ?owner :substrate))]]
     db agent-id)

```

**What this reuses:** `:seon.db/agent-id` (tx-meta), `:seon.render/ai`
(per-entity symbol). **What it invents:** nothing on the data side;
the renderer body is new but `seon.render/assemble-ai-context` is new
in v1 too.

**Edge case — agent-A asserts a fact about agent-B** (e.g. A
"observes" something about B, transacts an entity tagged
`:seon.db/agent-id A` but referencing B). Under this model, B never
sees the fact — the tx is owned by A. This matches v1 (which would
require an explicit `:seon.ctx/agent B` row) but is invisible to the
casual reader. **Recommendation: keep it; cross-agent visibility
requires an explicit message tx (which IS tagged for the recipient
via `:seon.message/to`).**

## 3. Windowing

Four candidates, evaluated against three criteria — agent-friendly,
cache-friendly, implementable cheaply:

| Unit | Agent-friendly | Cache-friendly | Cheap |
|---|---|---|---|
| Last N tx | ok — one event = one tx in 95% of cases | hostile — one big tx in the middle changes everything | yes |
| Last N datoms | poor — splits conceptual events arbitrarily | hostile — same | yes |
| Last N renderable entities | best — N = "how many things you see" | good — bounded prefix | medium |
| Last N seconds | poor — depends on wall-clock | hostile | yes |

**Decision: last N renderable entities, with a tx-time tiebreaker.**

Concretely: the renderer query returns *all* renderable entities for
the agent (the predicate is small — `:seon.render/ai` is a single
attr lookup, indexed). It sorts by tx-time of latest assertion,
takes the last N (default 64), prepends the prefix-sticky set
unconditionally. The agent overrides via
`:seon.agent/window-size` (clamped 1–512 to bound render cost).

**Why not last-N-tx:** under "tx-log-as-context" the seductive
mental model is "walk the log." In practice walking the log to find
renderables is the same query as "find renderables and sort by
tx-time" — Datahike's tx index makes the latter trivial. The
*conceptual* model is "the log is the context"; the *physical*
query is an indexed scan over `:seon.render/ai`. They produce the
same result; the indexed scan is cheaper.

**Override knobs the agent can transact on its own entity:**

```clojure
(schema/register! :seon.agent/window-size  [:int {:min 1 :max 512}])
(schema/register! :seon.agent/window-skip  [:set :keyword])
;; e.g. #{:seon.eval :seon.async-result} — kinds to exclude from the
;; window. The renderer filters before the take-last.

```

`:seon.agent/window-skip` is the agent's way to say "I'm in a tight
debug loop, don't include async results." More knobs land
demand-driven.

## 4. Sticky semantics

Three flavors from the prompt, worked out:

### 4.1 Naturally sticky

An old entity never re-asserted sorts to the top because its
tx-time is oldest. **This is the default; it costs nothing.** The
system-prompt entity, transacted at boot and never updated, lives
here for free. So do schema-reference entities, conventions, etc.

The pitfall: if the renderer's window is "last N renderable
entities," naturally-sticky old entities get **evicted** when N
turns of new entities arrive. The prefix isn't actually stable;
it's stable until you turn enough times.

**Fix:** the renderer always includes entities tagged
`:seon.sticky/position :prefix` regardless of the window. So
naturally sticky becomes a soft idea (and probably eviction-prone);
explicit anchoring (§4.2) is the real prefix mechanism.

### 4.2 Explicitly anchored

```clojure
(schema/register! :seon.sticky/position [:enum :prefix :suffix])

```

An entity carrying `:seon.sticky/position :prefix` is **always**
in the render, sorted by tx-time among other prefix-sticky entities.
The renderer query is now a union:

```
(prefix-sticky entities) ∪ (last-N renderable, non-prefix-sticky)

```

Both sets are sorted by tx-time of latest assertion. Prefix-sticky
goes first; the window goes after.

This is the load-bearing knob. The "system prompt is just a sticky
entity" claim only works with this.

### 4.3 Pinned-but-mutable

This is the trap. If the agent updates its own system prompt and
the update bumps tx-time, the prompt moves to the **end** of the
sticky group — bytes shift, prefix-cache busts.

**Three possible fixes:**

(a) **Sort sticky entities by `:seon.sticky/order` (manual int), not
tx-time.** Mutating the system prompt keeps `:order 0`. Bytes still
change if the *content* changes (unavoidable — the prompt IS the
content), but they change in place; everything after the prompt
position is also dirty after a content change, so prefix cache is
busted anyway. Manual order is honest about that.

(b) **Forbid mutation of prefix-sticky entities.** Updates require
retracting the old one and asserting a new one at the suffix; the
agent explicitly opts into cache invalidation.

(c) **Sticky entities sort by their *initial* assertion tx-time,
not latest.** Datahike supports this — query the first assertion's
tx via history. Slightly more expensive query.

**Recommendation: (a).** Manual `:seon.sticky/order` is dead simple,
matches how humans think ("system prompt comes first, conventions
second"), and makes the cache-busting consequence of mutation
honest. (c) is clever but adds query cost; (b) is dogmatic about a
case that should be agent-controllable.

So the final sticky shape:

```clojure
(schema/register! :seon.sticky/position [:enum :prefix :suffix])
(schema/register! :seon.sticky/order    {:optional true} :int)

```

`:order` only meaningful when `:position` is set; missing ⇒ 0
(stable ties broken by tx-time of initial assertion).

## 5. Non-renderable tx

Most tx in a busy pod are not agent-visible: handler-index updates,
schema loads, internal housekeeping, other agents' work. The render
walk must skip these.

**Mechanism:** the query in §2 already filters by
`[?e :seon.render/ai _]`. Datahike's AEV index on `:seon.render/ai`
means the cost is proportional to the count of *renderable* entities
in the DB, not total entity count. Across a 10k-tx history with 200
renderable entities, the query is cheap.

**Cost analysis (back-of-envelope):**

- Renderable-entity count per agent over a working session: ~64
  (eval results + messages + ctx) before the window evicts older
  ones. Plus ~5 prefix-sticky.
- Query: AEV scan on `:seon.render/ai` → entity ids → for each, fetch
  latest tx-time → sort → take last 64. ~70 lookups in steady state.
- Datahike-CLJS in-memory: sub-millisecond at this scale.

**Cache option (deferred):** maintain a `:seon.runtime/renderable-set`
atom-projection updated by `d/listen!` so the query is replaced by an
atom-deref. Defer until measured; the indexed query is already cheap
and the cache adds reactive complexity (see CLAUDE.md: caching is
the perf escape hatch, not the architecture).

## 6. Effects and handlers — unchanged

This is the load-bearing claim of the proposal: **the handler /
effect pipeline doesn't change.** Verifying:

- Handlers still receive `{:seon.db/db-before :seon.db/db-after
  :seon.db/tx-data :seon.agent/id}`. ✅
- Handlers still emit `{:tx [...] :effects [...]}`. ✅
- The dispatcher still walks added datoms, scopes by tx-meta
  `:seon.db/agent-id`, calls matched handlers, applies `:tx`, queues
  `:effects`. ✅
- The substrate handlers (wake-on-message-to, route-async-result,
  process-turn-request, surface-system-error) need zero changes —
  they don't touch `:seon.ctx/*`.

**What changes:**

- `assemble-ai-context` is reimplemented as the §2 query + §3
  windowing + §4 sticky union. ~60 LOC.
- v1-style handlers that exist purely to assert a `:seon.ctx.foo`
  entity that mirrors a real entity they just touched **vanish**.
  Concretely: any v1 handler whose job is "when a `:seon.eval` lands,
  also assert a `:seon.ctx.recent-eval`" goes away — the
  `:seon.eval` carries its own `:seon.render/ai` and shows up in the
  window directly.

This is the proposal's biggest win: one entity, one tx, one render —
not entity + ctx-mirror + sync-handler.

**The agent still needs `seon.render/ai` on entities the agent
should see.** Handlers that today add a separate ctx-mirror become
handlers that *augment the original entity's transact with the
render symbol*. In most cases, the producer of the entity (eval
recorder, message writer, async-result interpreter) just includes
`:seon.render/ai` in the entity map directly — no handler needed.

## 7. Walkthrough — scenario 1 under tx-log-as-context

User sends "what's 2+2"; one productive turn; one narration turn;
stop. Same scenario as
`loop-walkthrough-2026-05-25.md` §scenario-1, but the renderer side
is different.

### 7.1 Initial state

```clojure
;; Agent entity — adds the window knob; no ctx entities at all.
{:seon.agent/id          "A-abc123def456"
 :seon.agent/state       :stopped
 :seon.agent/step-count  0
 :seon.agent/max-steps   8
 :seon.agent/window-size 64
 :seon.render/ai         'seon.render.default/agent-render
 :seon.render/html       'seon.render.default/view}

;; Boot-time prefix-sticky entities — no :seon.ctx/* namespace.
[{:seon.sticky/id        "sticky-system-prompt"
  :seon.sticky/position  :prefix
  :seon.sticky/order     0
  :seon.render/ai        'seon.runtime/system-prompt
  :seon.render/html      'seon.runtime/system-prompt-html}
 {:seon.sticky/id        "sticky-conventions"
  :seon.sticky/position  :prefix
  :seon.sticky/order     1
  :seon.render/ai        'seon.runtime/conventions
  :seon.render/html      'seon.runtime/pretty-html}
 {:seon.sticky/id        "sticky-schema-reference"
  :seon.sticky/position  :prefix
  :seon.sticky/order     2
  :seon.render/ai        'seon.runtime/schema-reference
  :seon.render/html      'seon.runtime/pretty-html}
 {:seon.sticky/id        "sticky-handlers-list"
  :seon.sticky/position  :prefix
  :seon.sticky/order     3
  :seon.render/ai        'seon.runtime/handlers-list
  :seon.render/html      'seon.runtime/pretty-html}]

```

`sticky-handlers-list` is interesting: it queries `:seon.handler`
rows for the current agent + substrate at render time and lists
them. The agent always knows what handlers are active because they
appear in its context. v1 has to add a `:seon.ctx.handlers-list`
entity + a handler that re-asserts it whenever a handler is
registered; this spec gets it for free from "the renderer reads
current truth."

### 7.2 User stimulus

```clojure
^{:seon.db/origin :user :seon.db/agent-id "A-abc123def456"}
[{:seon.message/id      "msg-u-1"
  :seon.message/role    :user
  :seon.message/from    :user
  :seon.message/to      [[:seon.agent/id "A-abc123def456"]]
  :seon.message/content "what's 2+2"
  :seon.message/at      #inst "2026-05-25T10:00:00.000Z"
  :seon.render/ai       'seon.runtime/render-message
  :seon.render/html     'seon.runtime/render-message-html}]

```

The user-message tx is tagged with the recipient agent id (web
handler does this; or wake-on-message-to copies on the follow-up
transact — either way, the tx-meta carries it).

### 7.3 First turn — `ask-and-eval!` writes the assistant message + eval

```clojure
^{:seon.db/origin :agent :seon.db/agent-id "A-abc123def456"}
[{:seon.message/id      "msg-a-1"
  :seon.message/role    :assistant
  :seon.message/content ";; addition\n(+ 2 2)"
  :seon.message/at      #inst "2026-05-25T10:00:00.800Z"
  :seon.render/ai       'seon.runtime/render-message
  :seon.render/html     'seon.runtime/render-message-html}
 {:seon.eval/id         "ev-1"
  :seon.eval/source     "(+ 2 2)"
  :seon.eval/ok?        true
  :seon.eval/result-edn "4"
  :seon.eval/at         #inst "2026-05-25T10:00:00.820Z"
  :seon.render/ai       'seon.runtime/render-eval
  :seon.render/html     'seon.runtime/render-eval-html}
 {:seon.turn-request/id    "tr-1"
  :seon.turn-request/agent [:seon.agent/id "A-abc123def456"]
  :seon.turn-request/at    #inst "2026-05-25T10:00:00.821Z"}]

```

Note what's **not here**: no `:seon.ctx.recent-eval` mirror entity,
no `:seon.ctx.conversation` mirror entity. The eval and the message
each carry their own render symbol. The turn-request has no render
symbol (the agent shouldn't "see" its own request to take another
turn).

### 7.4 Next-render `assemble-ai-context` for A

Query: prefix-sticky union last-N renderable scoped to
`A-abc123def456`. Result, in order:

| # | Entity id | Source | tx-time | Position |
|---|---|---|---|---|
| 1 | sticky-system-prompt | boot | 09:59:00.000 | prefix order 0 |
| 2 | sticky-conventions | boot | 09:59:00.001 | prefix order 1 |
| 3 | sticky-schema-reference | boot | 09:59:00.002 | prefix order 2 |
| 4 | sticky-handlers-list | boot | 09:59:00.003 | prefix order 3 |
| 5 | msg-u-1 | user | 10:00:00.000 | window |
| 6 | msg-a-1 | agent | 10:00:00.800 | window |
| 7 | ev-1 | agent | 10:00:00.820 | window |

Compare against v1 Scenario 1 final render (5 ctx entities, the
top 3 of which are the prefix-stable ones). Same shape; this spec
just has more entities (because no ctx-mirroring) and a clearer
"this is the actual eval, not a projection of it" mapping.

### 7.5 Second turn (narration only) → stop

LLM returns `";; 2+2 = 4"`. `ask-and-eval!` writes:

```clojure
^{:seon.db/origin :agent :seon.db/agent-id "A-abc123def456"}
[{:seon.message/id "msg-a-2" :seon.message/role :assistant
  :seon.message/content ";; 2+2 = 4"
  :seon.message/at #inst "2026-05-25T10:00:01.620Z"
  :seon.render/ai 'seon.runtime/render-message
  :seon.render/html 'seon.runtime/render-message-html}]

```

No `:seon.turn-request`. `run-turn!` close-path transacts
`:seon.agent/state :stopped`. Final agent state matches v1.

### 7.6 What this scenario revealed

- **The window includes messages + evals directly.** No mirror
  entities. Producers carry their own render symbol.
- **The prefix is just sticky entities sorted by `:order`.** Trivial.
- **`sticky-handlers-list` is a renderer that queries live state.**
  Self-updating without any handler watching it.

## 8. Side-by-side comparison

| Aspect | unified-loop-v1.md (slot-merge) | tx-log-as-context-v1.md |
|---|---|---|
| Ctx entity schema | `:seon.ctx/id`, `:seon.ctx/agent`, `:seon.ctx/updated-at` namespace; entities scoped to agent | none — no `:seon.ctx/*` namespace. Renderables live in their own namespace (eval, message, async-result, sticky) |
| Render assembly fn | query `:seon.ctx/*`-tagged for agent, sort by `:updated-at`, map renderers | query `[?e :seon.render/ai _]` scoped by tx-meta `:seon.db/agent-id`, union prefix-sticky, take last-N by tx-time |
| Agent customization API | transact ctx entities (need ctx schema) | transact entities with `:seon.render/ai`; tune `:seon.agent/window-size` / `window-skip` |
| Cache-friendliness | prefix is stable by convention (ctx entities never re-asserted) | prefix is stable by `:seon.sticky/position :prefix` + manual `:order` (explicit) |
| Scoping mechanism | `:seon.ctx/agent` ref on each ctx entity | tx-meta `:seon.db/agent-id` (already exists, ad ✓) |
| Sticky semantics | implicit (don't re-assert) | explicit (`:position :prefix`, `:order`) |
| "Show me what changed since last turn" | hard — ctx entities mask their underlying source | natural — query tx-range between two t values, filter renderable; "what changed" IS the tx delta |
| Handlers that mirror entity → ctx | needed (e.g. recent-eval ctx mirror) | not needed — eval carries its own renderer |
| LOC for renderer | ~30 LOC `assemble-ai-context` | ~60 LOC (query is more complex, no mirror handlers) |
| LOC saved elsewhere | — | one fewer mirror handler per renderable kind (3-5 fewer at this scale) |
| Net LOC | baseline | -50 to -100 LOC (estimate) |
| Schema surface | adds `:seon.ctx/*` (6 attrs) | adds `:seon.sticky/*` (3 attrs), `:seon.agent/window-*` (2 attrs) |
| Cross-agent visibility | per-entity `:seon.ctx/agent` filter | per-tx `:seon.db/agent-id` filter — same effect, half the data |
| Discoverability ("what am I seeing?") | `(query :seon.ctx/* for me)` — direct | `(assemble-ai-context {:db ... :agent-id ...})` — same query the renderer runs |
| Migration cost | one new ns (`seon.runtime`) + handler scoping | one new ns + change every entity emitter to carry `:seon.render/ai` (~6 sites) |

The killer feature is the **second-to-last data row**: discoverability
parity, half the schema. The killer concern is the **last data row**:
every site that today writes an entity needs to also include a render
symbol. Realistic count: `record-eval!`, message writers (3 paths:
user → web, agent → assistant, system), async-result interpreter,
spawn-agent interpreter. ~6 call-sites. Tractable but real churn.

## 9. What this model is bad at

### 9.1 "Summary of 10000 evals"

The window holds the last N renderable entities. If the agent needs
a *summary* of older state — a token-count, error-rate, "you've
tried this 4 times" — that's a derived quantity, not a window entry.

**Approaches:**

(a) **Summary-as-sticky.** A handler watches eval entities; every
K evals, asserts/updates a `:seon.sticky/id "eval-summary"` entity
with the rollup. It's prefix-sticky, always in render, content
changes as evals accrue. **This is fine; it's exactly what v1
would do too** (via a ctx-mirror handler).

(b) **Renderer queries the DB beyond the window.** Sticky entities
are renderers; nothing stops the system-prompt renderer from running
a Datalog query and including the result. "Show recent error count"
is one query in the renderer body.

Either works. The window is for "things the agent should literally
see scrolled in a list"; summaries are for "things the agent should
know but aren't list entries." Same distinction v1 makes; same
solution.

### 9.2 Multiple sticky entities competing for prefix order

If the agent dynamically asserts new prefix-stickies (e.g. "remember
this fact across turns"), each one needs a unique `:order`. Race
conditions: two handlers assert order=5 simultaneously. Tie-break by
tx-time of initial assertion (entity created first wins ascending
position). Stable; predictable; documentable.

Constraint we should impose: **`:order` is sparse, agent-assigned;
think CSS z-index.** Default boot stickies use 0-9. Agent
additions use 100+. Lets boot stickies always anchor regardless of
agent activity.

### 9.3 Render performance per turn

Cost per turn = one Datalog query scoped to renderables + render fn
invocations. Worst case (N=512, all stickies): ~512 entity pulls +
512 fn calls. At ~50µs per fn call (default renderer is a string
concat), ~25ms per turn. LLM call is ~800ms. **Not the bottleneck.**

Cache hook (not building in v1): memoize render output per entity
keyed on `[entity-id, latest-tx-id]`. Datahike's eid+tx-id is a
content-hash for the entity's state. Memoize map of ~512 entries.
Built only if measured.

### 9.4 Discoverability — does the agent know what it sees?

Yes. `assemble-ai-context` is callable from agent code:

```clojure
(seon.render/assemble-ai-context
  {:seon.db/db @conn :seon.agent/id "A-..."})
;; => {:seon.render/text "..." :seon.render/entities [...]}

```

The `:entities` key in the return is the **list of entity refs the
agent is currently seeing** in render order. The agent can introspect
"what's in my context" without re-deriving the query. v1 has the
same affordance but the entities are `:seon.ctx/*` aliases for the
"real" data; this spec returns the real entities directly.

### 9.5 Migration from `loop-walkthrough-2026-05-25.md`

The walkthrough's four scenarios all explicitly mention
`:seon.ctx.recent-eval`, `:seon.ctx.conversation`, etc. Under this
spec those entities don't exist. **Cost: rewrite the walkthrough.**
~200 lines of literal data change shape; the *step-by-step prose*
mostly survives because handlers and effects are unchanged.

If we adopt this spec, `loop-walkthrough-tx-log-2026-05-25.md`
ships alongside it.

## 10. Recommendation

**(C) Hybrid — adopt the tx-log model for renderable selection;
keep v1's per-entity `:seon.render/ai` mechanism and handler
pipeline unchanged.**

Specifically:

1. **Drop `:seon.ctx/*` namespace.** Renderables are entities of
   any kind that carry `:seon.render/ai`. Apply at the producer
   level — `record-eval!`, message writers, async-result
   interpreter all attach the render symbol.
2. **Adopt `:seon.sticky/position` + `:seon.sticky/order`.** Boot
   stickies replace the v1 baseline `:seon.ctx/*` entities. System
   prompt, conventions, schema reference, handlers list = four
   sticky entities at boot.
3. **Window = last N renderable entities, scoped by tx-meta
   `:seon.db/agent-id`.** N = `:seon.agent/window-size` (default
   64). Plus prefix-sticky union (always included).
4. **Mutable stickies use `:seon.sticky/order` for position, not
   tx-time.** §4.3 fix (a).
5. **Handlers, effects, dispatcher: unchanged from v1.** This is
   a pure renderer rewrite.

**The load-bearing reason:** the v1 ctx-mirror pattern is a recurring
anti-pattern in event-sourced systems — every concept ends up
needing a mirror projection. This spec eliminates the mirror tier by
saying "if it's renderable, it's already the thing; tag it." The
~50-100 LOC savings is a proxy for the conceptual savings: one
fewer kind of entity to think about.

**Why not (A) full proposal:** Sean's framing flirts with "walk the
tx log literally." §3 shows the literal walk is more expensive than
the indexed query that achieves the same result. The conceptual
model survives intact; the implementation is the indexed query.
Calling it "tx-log-as-context" remains accurate (you can
reconstruct the render from the tx log alone — that's the event-
sourcing claim from v1 §5 made concrete on the render side).

**Why not (B) keep v1:** v1's ctx-mirror handlers are
straightforward but they're work the system shouldn't have to do.
Every new renderable kind in v1 means a new ctx-mirror handler.
Under this spec, every new renderable kind means an extra key in
the producer's transact map. The marginal cost of new kinds is the
real metric, and this spec wins it.

## 11. What needs Sean's input

1. **Migration ordering.** If we adopt (C), the walkthrough doc
   needs rewriting and ~6 entity producers need to attach
   `:seon.render/ai`. Is this a single-PR change or staged?
2. **Sticky-order conventions.** Boot stickies at 0-9, agent
   additions at 100+? Or do we let users (the meta-Sean
   configuring the substrate) override the boot range?
3. **Window default.** 64 is a guess. Real LLM context budgets
   suggest something larger (~256?). Want to pick a starting point
   informed by intended LLM (Sonnet 4.7 1M context vs smaller).
4. **`sticky-handlers-list` semantics.** Is it valuable for the
   agent to always see its handler list, or does that just bloat
   the prefix? v1 doesn't include this; this spec gets it cheaply.
   Either way; want a call.

## 12. Cross-references

- [unified-loop-v1.md](unified-loop-v1.md) — the model this
  contrasts. §§4, 6, 9 are unchanged under this spec.
- [loop-walkthrough-2026-05-25.md](loop-walkthrough-2026-05-25.md)
  — needs rewrite if (C) adopted; otherwise unchanged.
- [loop-testing-strategy-2026-05-25.md](loop-testing-strategy-2026-05-25.md)
  — Layer 1-4 unchanged. Layer 5 (replay) actually gets cleaner
  because there's no ctx-mirror handler to replay.
- `src/seon/render.cljs` — `assemble-ai-context` lands here.
- `src/seon/db.cljs:81-92` — `d/listen!` contract that already
  delivers `{:db-before :db-after :tx-data}`.
- `docs/seon/concepts/reactive-context.md` — derive-not-store
  principle; this spec is the renderer side of that principle
  applied harder than v1 applies it.
- `docs/seon/concepts/code-as-data-runtime.md` — entities carrying
  their own render symbol IS code-as-data.
