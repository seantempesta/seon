---
type: research
status: active
tags: [research, agent, web, ui]
---

# Live agent-activity signal — thinking / evaluating / waiting

A design (NO src edits) for a LIVE per-agent activity signal so the HUMAN
(header status bar) and ROOT (an agent-facing context block) always know,
at least roughly, what every agent is doing right now — finer than the
current coarse `derive-state` (idle / running / paused / terminated). The
owner wants a "thinking mode" (the LLM is generating), hooked at the
OpenAI-compat level.

## TL;DR

- **ONE new stored primitive**, written at the OpenAI-compat call boundary:
  a transient marker `:seon.agent.turn/llm-started-at` (inst) stamped on the
  CURRENT turn when the LLM request goes out, retracted when it returns.
  Everything else is DERIVED from signals already in the DB.
- **The richer activity enum is fully derivable** from `open-run?` +
  `current-turn status` + that ONE marker:
  `idle · thinking · evaluating · waiting · paused · terminated`.
- **The hook point is `seon.ai.openai-compat/complete`**
  (`src/seon/ai/openai_compat.cljs`), bracketing the
  `.stream` → `await .finalChatCompletion` await at **lines 381-392**.
  Attribution rides the ambient `(seon.db/current-agent-id)` /
  `(seon.db/current-tx-context)` — **LIVE-PROVEN to survive `await`**
  (AsyncLocalStorage), so the openai-compat layer needs NO new args.
- **Both surfaces update LIVE for free**: the marker write/retract are tiny
  txs, and `seon.web.datastar` already morphs `#world` on EVERY tx
  (`db/listen!` IS the refresh signal). The header (`seon.ui.header` =
  `f(db)`) rides inside `#world`; a root context block is a pure
  `f(db,id)` section like `seon.agent.ctx.warnings/warnings-block`.
- **Derive-vs-store decision**: store the single transient marker (the
  "LLM call in-flight" fact is genuinely runtime, like compile-state, and
  is NOT otherwise in the DB); DERIVE the whole activity enum from it +
  existing turn/run datoms. This is the compile-state exception to
  "derive, don't store", but we surface it THROUGH the DB so it rides the
  existing tx→morph pipeline uniformly — no new push channel, no atom, no
  event bus.

---

## 1. The activity model

### 1.1 Live states + where each comes from

| activity        | source signal                                                        | derive or store |
|-----------------|----------------------------------------------------------------------|-----------------|
| `:terminated`   | `:seon.agent/terminated-at` present                                  | derive (exists) |
| `:idle`         | no open run (`:seon.agent/run` absent / not `:open`)                 | derive (exists) |
| `:paused`       | open run carries `:seon.agent.run/paused-at`                        | derive (exists) |
| `:thinking`     | the run's CURRENT `:running` turn carries `:seon.agent.turn/llm-started-at` | **STORE the marker**, derive the rest |
| `:evaluating`   | open run + a turn `:status :running`, NO llm-started-at marker       | derive |
| `:waiting`      | open run, no `:running` turn (between turns / loop deciding)         | derive |

Only ONE new datom in the whole model: `:seon.agent.turn/llm-started-at`.
Everything else already exists — `:seon.agent/terminated-at`,
`:seon.agent.run/paused-at`, the open-run pointer, and the per-turn
`:seon.agent.turn/status :running` (turn.cljs registers it at line 57 and
opens the turn `:running` at line 211, closes to `:done`/`:error`).

### 1.2 Why this maps cleanly onto the existing turn lifecycle

A turn (`seon.agent.turn/run-turn!`) is exactly: open turn `:running`
(before the LLM call) → `ask-and-eval!` → `call-llm!` → `llm-fn` (the
adapter → `openai-compat/complete`) → parse → `eval-batch!` → close turn
`:done`/`:error`. So WITHIN one `:running` turn there are two observable
sub-phases, and the LLM-in-flight marker is precisely the boundary:

```
open turn :running ─┬─ [llm-started-at stamped] ── LLM generating ──► :thinking
                    │
   LLM returns  ────┴─ [llm-started-at retracted] ── eval-batch runs ─► :evaluating
                                                       │
   close turn :done ──────────────────────────────────┴── between turns ─► :waiting
```

`:waiting` is the brief gap the loop spends deciding / beating / hitting a
bound. (A run that decides to wait closes with `:waited` → `:idle`, so
`:waiting` is genuinely transient, not a stable resting state.)

### 1.3 The derivation (extends `seon.derive`, does NOT replace `derive-state`)

`derive-state` stays exactly as-is — the wake gate / armable roster need
the coarse 4-state rule (`state-from-primitives`, terminated→idle→paused→
running) and MUST NOT see the finer enum. Add a SIBLING derivation in the
same leaf `seon.derive`:

```clojure
(schema/register! :seon.derive/activity
  [:enum :idle :thinking :evaluating :waiting :paused :terminated])

;; reads the SAME db value; reuses current-run + one running-turn lookup +
;; the marker. Pure, no writes. Sits beside derive-state in the leaf.
(defn derive-activity [db agent-id] ...)
```

`derive-activity` is a refinement of `derive-state`: it returns the coarse
state for `:terminated`/`:idle`/`:paused`, and for an open non-paused run it
splits `:running` into `:thinking` / `:evaluating` / `:waiting` by reading
the run's current `:running` turn + the marker. One running-turn query per
agent (cheap; `:memory`/local db value, sub-ms).

Keeping it in `seon.derive` preserves the "ONE leaf of DB-derived
projections" invariant the namespace doc asserts — every consumer
(`seon.ui.header`, the root block, `seon.render.system`) already requires
this leaf.

---

## 2. The OpenAI-compat hook point (the only place "thinking" is born)

**File:** `src/seon/ai/openai_compat.cljs`
**Fn:** `complete` (the `^:async` chat-completions call)
**Exact site:** lines **381-392** — the `try` that wraps the streamed call:

```clojure
;; current lines 381-392
(try
  (let [^js stream (.stream completions params)
        completion (await (.finalChatCompletion stream))   ; <-- "thinking" window
        result     (parse-completion completion)]
    ...)
  (catch :default e ...))
```

The "thinking" window is precisely **request-sent → `await
.finalChatCompletion` resolves**. The design:

- **call-start** (immediately before `(.stream completions params)`): stamp
  the marker on the current turn.
- **call-end** (in BOTH the success branch after `.finalChatCompletion`
  resolves AND the `catch`): retract the marker — wrap the existing `try`
  body so the marker is cleared on every exit (success, HTTP error,
  timeout, parse failure).

### 2.1 Attribution without new args — the ambient context

`complete` receives a `:seon.ai.openai-compat/complete-request` map that has
NO agent-id (it carries `:seon.ai/ctx` + call settings). The owner wants the
signal at THIS level anyway. Attribution comes from the ambient
fiber-local context, which is in scope because `run-turn!` calls the LLM
inside `(db/with-agent id ...)` + `(db/with-tx-context {:seon.db/turn-id
turn-id ...} ...)` (turn.cljs lines 377-398):

- `(seon.db/current-agent-id)` → the calling agent's id
- `(seon.db/current-tx-context)` → carries `:seon.db/turn-id`

**LIVE-PROVEN (pod 7890, 2026-06-28)** that these survive `await`
(AsyncLocalStorage): inside `(db/with-agent "root" ...)`, after a
`(.then (js/Promise.resolve 1) ...)`, `current-agent-id` was still
`"root"`. So `complete` can read the turn-id at call-start and use it again
after the await — no plumbing through `complete-request`, `agent-adapter`,
or `call-llm!`.

**Graceful no-op off the turn path:** ad-hoc `complete` calls (gym,
bootstrap, the stub-llm fall-back, a bare REPL probe) run with NO ambient
turn-id → the marker write is simply skipped (`when turn-id`). Only real
agent turns signal. This keeps the openai-compat layer the single
chokepoint while staying safe for non-turn callers.

### 2.2 The minimal mechanism (tx at the call boundary)

Two tiny txs per turn, on the turn entity that already exists:

```clojure
;; call-start (in complete, when (current-tx-context) has a turn-id):
(db/transact! {:seon.db/tx-data
               [{:seon.agent.turn/id turn-id
                 :seon.agent.turn/llm-started-at (js/Date.)}]})

;; call-end (success + catch):
(db/transact! {:seon.db/tx-data
               [[:db/retract [:seon.agent.turn/id turn-id]
                 :seon.agent.turn/llm-started-at]]})
```

- New attr `:seon.agent.turn/llm-started-at` (inst, `{:optional true}`)
  registered by its data-owner `seon.agent.turn` (alongside the other
  `:seon.agent.turn/*` attrs at lines 53-92).
- **New require:** `seon.ai.openai-compat` → `seon.db`. Confirmed ACYCLIC:
  `seon.db` requires only `clojure.string`, `cljs.reader`, datahike,
  `seon.db.internal`, `seon.schema` — never `seon.ai`.
- The marker is a per-turn fact; retract-at-end is what flips
  `:thinking → :evaluating` LIVE (the first thing the loop does after the
  LLM returns). Self-heals: it is only ever READ through an agent's
  current OPEN run; on a pod crash, `recover-crashed-runs!` closes the run
  → agent `:idle` → no `:thinking` shown, and the stale turn marker is
  never reachable (same harmlessness as today's lingering `:running` turn
  status on crash).

### 2.3 Why a tx, not a runtime atom

The owner's instinct is right: "if the adapter writes a tiny tx at
call-start and call-end, both surfaces update LIVE for free." The morph is
driven by `db/listen!` → `seon.web.datastar/on-tx` →
`schedule-broadcast!` → `broadcast!` (re-render each feed's `#world`,
morph). A runtime atom would NOT trigger a broadcast (the header wouldn't
tick), would violate `f(db)` purity in the render fns, and would be
pod-local (invisible to any other reader of the store). The tx rides the
existing pipeline with zero new mechanism. Cost: 2 extra small txs (+2
coalesced broadcasts) per turn, against the open-tx / beat / per-eval /
close-tx the turn already commits — negligible.

---

## 3. HEADER surface (UI lane) — `seon.ui.header`

`system-header` is already `view = f(db)` and already rides inside the
morphed `#world` (header.cljs ns-doc: "every commit re-renders it and the
stats tick LIVE"). The fleet-state cluster (`agents-chunk`) already draws
color-coded dots from `seon.render.system/fleet-summary`'s `::state-counts`
(`frequencies` of `derive-state` across agents, render/system.cljs line
135). Two changes, both additive:

### 3.1 Global activity counts (always shown)

`fleet-summary` computes a parallel `::activity-counts` (frequencies of
`derive-activity`). `agents-chunk` renders the active ones with a glyph
vocabulary consistent with the existing Phosphor dots:

```
3 agents  ● 1 idle  ◐ 1 thinking  λ 1 evaluating
```

Proposed glyphs (extend `state-dot`'s palette in header.cljs lines 104-108;
amber = live):

| activity     | glyph | class            |
|--------------|-------|------------------|
| `:idle`      | `●`   | `text-text-400`  |
| `:thinking`  | `◐`   | `text-amber-400` |
| `:evaluating`| `λ`   | `text-signal`    |
| `:waiting`   | `…`   | `text-text-500`  |
| `:paused`    | `⚠`   | `text-warning`   |
| `:terminated`| `○`   | `text-text-600`  |

Idle + thinking + evaluating shown always (the states that matter at a
glance); waiting/paused/terminated only when count > 0 — the same
"always-show-the-two-that-matter" rule `agents-chunk` uses today (lines
119-126). This subdivides the existing `:running` bucket; the coarse health
dot in `actions-chunk` (amber when any `:running`) keeps working unchanged.

### 3.2 Localized to the viewed agent (the `/agent/{id}` pages)

The header is global today (one fixed bar on every page). To honor "OR
localized to the agent the user is viewing", the per-agent view passes the
viewed id so the header can prepend a localized chip:

```
◐ Rtd-2606281344 thinking · turn 4/20
```

`system-header` gains an optional `:seon.agent/id` (a 2-arity or a
map-in variant — keep the existing 1-arg `f(db)` for the global pages).
When present, render a single chip via `derive-activity db id` + the
existing `seon.derive/derive-status` run fields (turn n/limit). Absent →
the global counts only. Either way it MORPHS live because the chip is
`f(db)` and the marker tx triggers the broadcast.

The throughput cluster (`throughput`, lines 70-97) already reports a rolling
60s tok/s and goes amber when live — that stays as the "is the fleet busy"
quantitative companion to the qualitative activity dots.

---

## 4. CONTEXT BLOCK (Core lane, agent-facing) — fleet activity for ROOT

Root (`:seon.agent/id "root"`) is the supervisor; its `/` view IS the
system dashboard (`seon.render.system`). Give root a context BLOCK so the
live fleet activity is in its PROMPT, not just the human's screen.

### 4.1 The block — a pure `f(db,id)` section, like `warnings-block`

A new section fn mirrors `seon.agent.ctx.warnings/warnings-block` exactly
(the canonical derived-block template): a fn of the render input map
(`{:seon.db/db db :seon.agent/id id ...}`) returning a `;`-quoted prose
block, empty when nothing to say. Proposed home: a small new ns
`seon.agent.ctx.fleet` (sibling of `warnings.cljs`), symbol-wired:

```clojure
;; seon.agent.ctx.fleet/fleet-activity-block
;; -> for each non-root agent: id · derived activity · purpose · turn n/limit
;;    (derive-activity + derive-status over the SAME db value)
;; rendered through ctx/quote-lines so the prompt stays valid Clojure source
```

Example agent-facing render (prose, single `;`):

```
; ── FLEET ACTIVITY (live) ─────────────────────────────
; ◐ Rtd-2606281344  thinking    turn 4/20   "index the kb sources"
; ● aZ4-2606271801  idle         —          "watch the inbox"
```

### 4.2 Wire it for ROOT ONLY first

`default-seed-blocks` (ctx.cljs line 1603) seed-COPIES blocks into each
fresh agent at creation. It already builds the vector conditionally
(`filterv some?` around file-blocks). Add the fleet block conditionally so
ONLY root seeds it — the cleanest spot is the seed path that knows the new
agent's id (root is the literal `"root"`). Two equivalent options:

- gate inside `default-seed-blocks` on the in-scope `(db/current-agent-id)`
  (the seed-copy runs inside the new agent's `with-agent` scope, per the
  install!/remove! doc at lines 1676-1677), OR
- leave `default-seed-blocks` generic and `ctx/install!` the fleet block
  onto root specifically in the root-seed path.

Because blocks are SEED-COPIED (not live-shared), enabling for root only =
root is the only agent whose ctx carries the block. Later "enable for any
supervisor" = `install!` it on that agent — no mechanism change. And it is
override-proof per the standing acme rule: a consumer can `remove!`/replace
the `:fleet` block by name with zero `src/seon` edits.

### 4.3 Why it updates every turn

The block is re-rendered every time root's prompt is assembled
(`render-context`), reading the db value pinned for that turn — so root sees
the fleet activity as of its turn's basis-t. The HUMAN header sees it morph
continuously (every tx); ROOT sees it sampled per turn. Same data
(`derive-activity`), two render views (`:seon.render/html` for the human,
`:seon.render/ai` for root's prompt) — the "turtles all the way down" one
mechanism.

---

## 5. Core / UI lane split + impl plan

### Core lane (`.cljs` pod — the activity FACT + agent-facing block)

1. **`seon.agent.turn`** — register `:seon.agent.turn/llm-started-at`
   (inst, optional) beside the other turn attrs; add to the
   `:seon.agent.turn` entity map. (data-owner of `:seon.agent.turn/*`.)
2. **`seon.ai.openai-compat`** — add `[seon.db :as db]` require; in
   `complete`, read `turn-id` from `(db/current-tx-context)` at call-start,
   stamp the marker; wrap the `try` (lines 381-392) so the retract fires on
   every exit. No-op when no ambient turn-id.
3. **`seon.derive`** — add `:seon.derive/activity` enum + `derive-activity`
   (sibling of `derive-state`, same leaf, pure read). Optionally surface it
   on `derive-status` as `:seon.agent/activity`.
4. **`seon.agent.ctx.fleet`** (new) — `fleet-activity-block`
   (`warnings-block` shape); symbol-wire into `default-seed-blocks`
   gated to root.

### UI lane (`.cljs` pod — the human surfaces)

5. **`seon.render.system/fleet-summary`** — add `::activity-counts`
   (frequencies of `derive-activity`) beside `::state-counts`.
6. **`seon.ui.header`** — extend `agents-chunk` to draw activity dots from
   `::activity-counts`; add the optional localized per-agent chip
   (`derive-activity` + run fields) for the viewed `/agent/{id}`.

### Sequencing / proof

- Steps 1-3 are the mechanism; verify LIVE first (drive one DeepSeek turn
  on acme, watch `derive-activity` flip `:thinking → :evaluating → :idle`
  in the wire-REPL while the turn runs) BEFORE wiring surfaces.
- Step 4 (root block) + steps 5-6 (header) are pure render consumers of the
  derivation — add after the fact is proven, then drive a DeepSeek agent +
  a dedicated observer to confirm the header morphs and root's prompt shows
  the fleet block (the standing "every UI/context unit gets a live drive +
  observer" rule).
- Acme override-proof: confirm a consumer can `remove!`/replace the `:fleet`
  block and restyle the header dots with zero `src/seon` edits.

### What is explicitly NOT done

- No new enum stored on the agent/run (`:kind`-free — the activity is
  DERIVED, the namespaced marker IS the discriminator).
- No notification/event system, no atom registry, no "mark seen" state —
  the marker self-heals (read only through an open run; cleared on retract
  or by run-close/crash-recovery).
- `derive-state` is untouched — the wake gate keeps its coarse 4-state rule;
  `derive-activity` is a strictly additive refinement.

---

## Grounding (files + live proofs)

- `src/seon/ai/openai_compat.cljs:320-392` — `complete`; the
  `.stream`/`.finalChatCompletion` await window (381-392) is the hook.
- `src/seon/agent/turn.cljs:53-92,187-345` — turn entity + status
  lifecycle; LLM call inside `with-agent` + `with-tx-context` (377-398).
- `src/seon/agent/run.cljs` — run FSM; `recover-crashed-runs!` (503) closes
  open runs at boot (self-heal anchor).
- `src/seon/derive.cljs:37-101,260-323` — `derive-state` +
  `state-from-primitives` + `derive-status` (where the sibling goes).
- `src/seon/ui/header.cljs:104-126,204-233` — `state-dot`, `agents-chunk`,
  `system-header = f(db)`.
- `src/seon/render/system.cljs:101-135` — `fleet-summary` / `::state-counts`.
- `src/seon/agent/ctx/warnings.cljs` — the derived-block template.
- `src/seon/agent/ctx.cljs:110-115` (block schema), `1603-1671`
  (`default-seed-blocks`), `1673-1690` (`install!`/`remove!` scope).
- `src/seon/web/datastar.cljs:184-198` — `db/listen!` IS the refresh signal;
  every tx → coalesced `#world` morph.
- `src/seon/db.cljs:340-376` — `current-tx-context` / `current-agent-id`
  (fiber-local across awaits); requires only string/reader/datahike/
  internal/schema (so openai-compat → db is acyclic).
- **LIVE (pod 7890, 2026-06-28):** 2 agents (root + Rtd-2606281344), both
  `:idle`, no `:running` turns. `current-agent-id` survives `await`:
  before = after = `"root"` across a `.then`.
