---
type: research
status: active
tags: [agent, ui, research, gym]
---

# Canvas-primary guidance + my.ui toolkit + canvas gym scenarios

## TL;DR

Phase A of the gym proved `canvas-updated? false` on EVERY scenario — agents
almost never set `:seon.render.live-canvas/content`. This arc closes that gap and
makes it measured, all in the UI-context lane (no Core edits):

1. **Strengthened the always-on live-tile block** (`src/seon/agent/ctx/live_tile.cljs`)
   — reframed canvas-PRIMARY / messages-BACKUP, hoisted the curated CSS safelist
   into the always-on guidance (agents stopped having to guess invisible classes),
   switched the wiring example to the portable `(seon.db/current-agent-id)` verb,
   and pointed it at the new `my.ui` compose toolkit.
2. **Four DERIVED awareness features in that block** (reactive, errors-as-values,
   nothing stored): (1) LOUD `⚠ YOUR CANVAS IS BROKEN` when the tile render throws;
   (2) reflects what the human sees (the `:seon.render/ai` twin — already present,
   kept); (3) inlines the tile fn's SOURCE from the program graph when the canvas is
   a fn symbol (code-as-data, not a file read); (4) points at the `my.ui` helpers so
   "update your canvas" is a one-liner.
3. **`my.ui` dual-render toolkit** (`src/my/ui.cljs`) — `status-line`, `kv-table`,
   `section` (the combinator). Each returns the `:seon.render/html-response`
   envelope (`:seon.render/hiccup` for the human + `:seon.render/ai` for the agent)
   from ONE input, so the two views can't drift; `section` composes child envelopes
   keeping the mirror through nesting. Unit-tested in `test/my/ui_test.cljs`.
4. **Two paid canvas gym scenarios** (`test/seon/gym/scenarios/canvas-*.edn`) whose
   correct behavior REQUIRES driving the canvas, scored with the EXISTING
   `:canvas-updated` predicate + an `:llm-judge` for answer correctness.

Build + `bin/test-cljs` only this arc (the live drive is serialized by the
orchestrator after the concurrent drive clears).

## What an agent sees — before / after (the actual rendered text)

### BEFORE (the empty/default-tile guidance)

The block reflected the tile then gave an out that undercut canvas use:

```
; PRESENT RICHLY, OR JUST REPLY IN MARKDOWN. You never have to
; build a tile: while you're on the core default, your LATEST
; REPLY to your human renders as a real markdown card on the
; canvas — so a clean `## heading` / `- list` / `**bold**` answer
; already looks good with zero extra work. Build a tile only when
; you want to show something richer than your words.
;
; Two ways to set a tile (copy-paste, swap in your content):
;   ; (a) literal hiccup …            ; (b) a tile FN …
```

Problems: led with "you never have to build a tile" (the markdown fallback as the
DEFAULT), no safelist (agents guessed invisible classes), no broken-state signal,
no fn-source, no compose helpers. Result: `canvas-updated? false` everywhere.

### AFTER (canvas-primary, with the four awareness features)

Header (unchanged) reflects the current tile + a loud broken-state line when the
render threw:

```
; Your live tile — what your human currently sees (as-of this
; turn's render; the human's view live-updates between turns).
; Wired: my.agent.<id>/status-tile (a fn on your entity)
;
; ⚠ YOUR CANVAS IS BROKEN — your human currently sees an
; error tile, not your content. Fix the fn/hiccup driving
; :seon.render.live-canvas/content (its source is below).
; Why: <the exception message>
; {…flattened ex-data…}
;
; Source driving your canvas (redefine it to change what
; your human sees):
;   (defn status-tile [m] …the program-graph source verbatim…)
;
```

Then the canvas-primary guidance + compose pointer + the hoisted safelist:

```
; ── THIS canvas is your PRIMARY surface ── your human WATCHES
; this tile; your messages are backup narration that scrolls
; away. Anything worth seeing at a glance — a status, a plan, a
; data breakdown, a result table, progress — belongs HERE, not
; recited in a paragraph. Set it with ONE transact …
;
;   ; EASIEST — COMPOSE my.ui dual-render helpers …
;   (seon.db/transact! {:seon.db/tx-data
;     [{:seon.agent/id (seon.db/current-agent-id)
;       :seon.render.live-canvas/content
;       (:seon.render/hiccup (my.ui/section { … }))}]})
;
;   ; (a) literal hiccup …   ; (b) a tile FN (re-derives) …
;
; SAFELIST — ONLY these CSS classes exist at runtime; anything
; else is INVISIBLE …
;   layout: flex flex-col flex-row grid grid-cols-{2,3,4} gap-{1-4} …
;   space : p-{0-4} px-{1-4} py-{1-4} mt-{1,2} mb-{1,2}
;   text  : text-{2xs,xs,sm,base,lg} font-mono font-semibold font-bold …
;   color : text-text-{50..700} text-{signal,success,error,warning,info} …
;   border: border border-{t,b} border-base-{700,800} rounded …
;
; A quick conversational answer CAN still be a plain markdown
; reply … but the moment you have data, a list, a table, or
; progress, SET your tile …
```

The markdown-fallback truth is KEPT but demoted from the lead to a closing caveat,
so the agent's default is now "set your tile," not "just reply."

## The `my.ui` dual-render toolkit

`src/my/ui.cljs` — three composable helpers, each map-in / dual-render-out:

- `status-line {:my.ui/label :my.ui/value :my.ui/tone}` → `{:seon.render/hiccup … :seon.render/ai "label: value"}`
- `kv-table {:my.ui/title :my.ui/rows}` → table hiccup + `"title\nk: v\n…"` ai
- `section {:my.ui/title :my.ui/blocks}` → stacks child hiccup, joins child ai

The ai-text and the hiccup are mirrored from the same input, so editing one can't
desync the other — exactly the owner's "two faithful views of ONE source of truth"
principle. `section` is the combinator that holds the mirror through nesting. All
emit only safelisted classes. Reused the EXISTING `:seon.render/html-response`
envelope (the W1 typed-render contract) — did NOT fork the engine.

`my.ui/*` is callable fully-qualified by agents because every `src/my/*.cljs` ns is
build-compiled and seeded into the program graph (same path as `my.data` / `my.kb`).

## The gym scenarios

Both paid (need a live drive to score the canvas axis), both scored with the
EXISTING `:canvas-updated` predicate kind (per-agent, axis `:drives-canvas`):

- **`canvas-budget-breakdown`** (`:db-memory`) — 7 expenses seeded under
  `:my.expense/*`; human asks for a glanceable breakdown "not a paragraph." Honest
  answer reads + aggregates (groceries 136, dining 106, transport 79; top
  groceries) and renders it to the canvas. Predicates: data visible, `canvas-updated`,
  replied, ends-idle, under-cap, + `:llm-judge` grading the breakdown figures.
- **`canvas-goal-board`** (`:planning`) — human names 3 quarter goals, asks for a
  glanceable status board. Honest answer stores the goals + renders a board (ideally
  a re-deriving tile fn). Predicates: modelled-the-goals, `canvas-updated`, replied,
  ends-idle, under-cap, + `:llm-judge` the board conveys all three goals.

No-cheating: nothing tells the agent to "use a tile" or hands it the answer; the
standing live-tile guidance + the `ui-canvas` skill teach the medium generally.

## What the post-drive observation should confirm

When the orchestrator runs the serialized live drive:

1. **`canvas-updated? true`** on both new scenarios (the headline gap closes).
2. The agent **composed `my.ui` helpers** rather than hand-rolling a big `[:div …]`
   (or at least set its tile at all) — check the eval transcript.
3. The rendered canvas uses only **safelisted classes** (nothing invisible) — eyeball
   the `/agent/{id}` page via the node gunzip feed client (browser 503s SSE).
4. The agent's `; Your live tile` section reflected the **right ai-twin** of what it
   rendered, and if it broke a tile, the **`⚠ YOUR CANVAS IS BROKEN`** line fired and
   the agent recovered using the inlined source.
5. The `:llm-judge` PASSed on a correct answer and FAILed a generic one
   (discrimination), confirming the rubric calibrates.

## Flags to Core (out of this lane)

- **judge-ctx can't see the canvas.** `judge-ctx` (`test/seon/gym/driver.cljs`) feeds
  the judge only the agent's REPLY text (`agent-reply-text`), NOT the rendered
  canvas. So the `:llm-judge` "canvas conveys the answer" grades what the agent
  COMMUNICATES (the reply, which an honest agent pairs with the tile). To grade the
  CANVAS content directly, `judge-ctx` should also append the agent's resolved tile
  ai-render (`render/render-agent-tile` → `:seon.render/ai`). Did NOT edit the driver
  (concurrent owner).
- **No `:ui`/`:presentation` competency.** The `:seon.gym.scenario/competency` enum
  (`:planning :db-memory :error-recovery :honesty :over-retrieval`, in driver.cljs)
  has no UI bucket, so the canvas scenarios borrow `:db-memory` / `:planning`. A
  dedicated competency would let a battery run the canvas axis as a group — a
  one-line enum add when Core is back on the driver.
- **`my.ui` unqualified `:refer`.** Agents call `my.ui/*` fully-qualified (works via
  the build-seeded program graph). To allow an unqualified `(:refer …)` of `my.ui`
  vars from a home ns, add `my.ui` to `home-ns-refer-toolkit-nses` in
  `src/seon/eval.cljs` (Core's) — not required for the qualified path.
- **Full helper set.** Shipped `status-line` / `kv-table` / `section` as the proven
  dual-render pattern; `badge` / `bullets` / `progress` are a clean follow-up.
