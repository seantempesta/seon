---
type: prd
status: draft
tags: [prd, agent, web]
---

# Live Tiles — the agent's surface at every zoom level (2026-06-11)

**The vision (user, near-verbatim):** the home screen is Windows Phone
live tiles. CSS and HTML are designed so information is ALWAYS USEFUL AT
EVERY LEVEL — tile → half screen. Like Claude artifacts, but more
interactive. The TILE is whatever the agent is trying to convey to its
human: charts, images, whatever the user asked for. The named key design
question: **how do agents KNOW what their live tile is currently
showing?** — via context rendering, "in such a way that the agent is
very aware of what the user is seeing."

This PRD turns that into a buildable design on top of what already
exists. Honest starting inventory:

| Already live | Where |
|---|---|
| Per-agent tile slot: `:seon.render/html` on the agent entity, symbol or hiccup literal, late-resolved | `seon.render/render-agent-tile`, capabilities-taught (`seon.ctx` "Your live tile" block) |
| Mission control IS a tile grid, SSE-morphed on every tx | `inspector/agents-dash-fragment` + `agent-grid-tile` + `push-index!` |
| Default tile: status dot + turn count + errors + last 5 messages | `seon.render.default/view` |
| Section twins: one section, `:seon.render/ai` + optional `:seon.render/html` | self-context spec 2026-06-10, `:seon.ctx/section` schema |
| The debug view: exact AI context beside entity cards | `/agent/<id>` two-pane inspector |
| The interactivity endpoint: `POST /chat?agent=<id>` → `message!` → wake | chat-bar-fragment + the agent loop |

What's missing is the MIDDLE zoom level (the messaging+artifact view),
the awareness loop (the agent seeing its own surface), and the content
vocabulary (charts, actions). None of it needs a new render system —
the one-render-system rule holds throughout.

## 1. The three zoom levels

```
LEVEL 1 — TILE          /agents              the home screen grid
LEVEL 2 — HALF-SCREEN   /agent/<id>          messaging + live surface
LEVEL 3 — DEBUG         /agent/<id>/debug    today's two-pane inspector
```

### What each level shows by default

**Level 1 — tile** (WP-live-tile density, ~300×175px grid cell, exists
today). Glanceable: one headline thing. Default (uncustomized agent):
purpose line (first sentence of the `:purpose` section text) + status
dot + last-activity age ("idle 4m") + turn count footer. The purpose
line is the fix for today's anonymous tiles — an agent created "to
track my workouts" says so on its tile before it ever customizes.
(Naming of a short display label — `:seon.render/label` — is the PARKED
#34/P20 decision; this PRD does NOT force it. The purpose-derived line
is the no-new-attr default; if the user later wants an explicit label
attr, it slots in as an override without changing anything here.)

**Level 2 — half-screen** (the new view, the messaging+artifact shape):
a two-column page — **conversation on the left** (chat bubbles + the
chat bar, already built as fragments), **the agent's live surface on
the right** (the SAME render slot as the tile, given room). This is the
view a human lives in: talk to the agent, watch the thing it's making
for you update live. Claude-artifacts-shaped, but the artifact is a
standing reactive surface, not a one-shot document.

**Level 3 — full/debug** (exists, KEEP — "what the agent sees exactly"
remains a feature): the current side-by-side — raw AI context sections
left, entity cards + tile right. Moves to `/agent/<id>/debug`;
half-screen gets the prime `/agent/<id>` route. A `⚙ debug` link in the
half-screen header and a `← chat` link back. Mission control tap goes
to half-screen (today it goes to debug).

### How an agent targets the levels — RECOMMENDATION: one slot, one fn, a `:seon.render/level` input key

Three options considered:

- **(a) one slot + responsive CSS only** — agent writes one hiccup tree,
  container queries scale it. Rejected as the WHOLE answer: WP live
  tiles aren't shrunken full views; glanceability needs different
  CONTENT (the workout tile shows this week's count; the half-screen
  surface shows the full chart + table). CSS can't drop content
  semantically.
- **(b) per-level slots** (`:seon.render/tile` + `:seon.render/html` +
  …) — rejected: two slots is two mechanisms, they drift, and every
  consumer (renderer, teaching text, inspector, twin) doubles. This is
  the foo-v2 trap applied to attributes.
- **(c) RECOMMENDED: keep the ONE `:seon.render/html` slot; the render
  input map (which already carries `:seon.db/db`, `:seon.agent/id`,
  `:seon.render/entity`) gains `:seon.render/level` — `:tile` or
  `:surface`.** A fn that ignores the key returns the same hiccup at
  both levels and the chrome clips/scrolls it (the working floor — what
  happens today). A fn that reads it returns level-appropriate content.
  `seon.render.default/view` branches on it: `:tile` = the glanceable
  default above; `:surface` = the richer message list it renders today.

Why (c): it is literally the existing mechanism plus one input key —
zero new attrs, zero new dispatch, the same fn-of-(world, self, now
also: viewing-distance) purity. It also composes with the awareness
answer below: one fn means ONE source of truth for "what is my human
seeing", whichever level they're at.

Registered shape: `(schema/register! :seon.render/level [:enum :tile :surface])`,
added `{:optional true}` to the render input schemas. Level 3 (debug)
is not a render level — it shows the machinery, not a rendering.

Styling floor on top: the tile/surface CONTAINERS get CSS container
queries (`@container`) so even a level-ignorant hiccup tree degrades
sanely (font clamps, overflow fades). Container CSS is chrome,
substrate-owned, one place — agents never write media queries.

## 2. The agent's awareness — how it knows what its human sees

The key question, and the recommendation up front:

**RECOMMENDED: the tile is a SECTION — one section, two renders. The
text twin of the human surface renders into the agent's own context
every turn, derived at the same instant from the same db by the same
fn.** Nothing is stored about "what I showed"; the agent's knowledge of
its surface can never go stale because it IS the surface, re-derived.

Concretely: a substrate default section `:my-surface` (priority ~26,
right after the catalogs — present-tense self-knowledge, before
warnings/transcript) whose renders are:

- **html render** = `render-agent-tile` — exactly what the inspector
  and home screen call. Same fn, same input, same db value.
- **ai render** = the TEXT TWIN of that same output, framed:

  ```
  ## Your live surface (what your human currently sees)
  Renderer: my.workouts/chart-tile   (you set this; default was seon.render.default/view)
  Tile now shows:
    ● running · workouts · turn 12
    "3 workouts this week · 12,400 kg total"
    [bar chart: Mon 4200, Wed 3800, Fri 4400]
  To change it: redefine the fn, or repoint :seon.render/html.
  ```

Where the text twin comes from — two tiers, mechanical first:

1. **Automatic hiccup→text flatten (the floor, every agent gets it
   free).** Hiccup is data; walk the tree the fn just returned, emit
   the strings, line-break on block tags, and describe non-text
   elements from their attrs (`[:svg ...]` → `[svg chart, 7 bars]`
   using `data-seon-desc` when present, tag+child-count when not;
   `[:img {:alt a}]` → `[image: a]`). Deterministic, ~40 lines, no
   LLM, no storage. This directly answers the user's "html in the ai
   context is noise" concern: the agent never sees markup — it sees
   the text content of its surface plus terse descriptions of the
   visuals.
2. **Author-supplied twin (the override).** A tile fn may return
   `:seon.render/text` alongside `:seon.render/hiccup` in its response
   map — same map-out, one more key. Agents writing rich charts are
   taught to describe them ("say what the chart MEANS, your human sees
   the picture; you see your words"). The flatten is the fallback when
   the key is absent.

Why this beats the alternatives the design considered:

- **(b) self-entity pull shows the slot symbol + fn source** — already
  exists (turn-0 demonstrated pull) and stays, but it answers "what is
  my renderer?" not "what is rendered RIGHT NOW?". A chart fn over live
  data produces different pictures every turn; the source doesn't.
- **(c) stored presentation-state** ("last shown: …" datoms written at
  render time) — violates reactive-context: stored state that the next
  tx makes stale, plus render would become a writer (renders must stay
  pure reads; the inspector renders on EVERY tx — writing on render is
  a feedback loop).
- The twin is the reactive-context principle verbatim: a section that
  is a function of the db at render time, vanishing/changing exactly
  when the underlying reality does. One section, two renders, the
  agent literally reads the text version of what the human sees.

**Budget note:** the twin is charged like any section; flatten output
caps at ~800 chars with a loud truncation marker (a tile that flattens
to more than that is failing the glanceability bar anyway — the
truncation marker IS feedback to the agent).

**"When did my human last look?" (ceiling, v2, optional):** presence is
partially derivable live — the inspector's SSE connection registry
knows whether anyone has `/agent/<id>` or `/agents` open right now;
the twin section can append "(your human is watching now)" from it.
Historical "last viewed 2h ago" is NOT derivable — it would need view
EVENTS (page-open facts, append-only like messages: events about the
past, not clearable state, so they don't violate the derived-by-default
rule). Worth having for "should I notify vs. just update the tile"
judgment, but it is explicitly NOT in the floor. Open question for the
user (§7).

## 3. Content kinds — what agents can emit

The slot's value vocabulary, floor → ceiling:

1. **Hiccup + the `seon.ui.components` vocabulary** (live today).
   Components are cljc hiccup factories (status-dot, card, table-*,
   empty-state, buttons) in the Phosphor Terminal system — agents are
   taught to call them rather than hand-rolling classes, so agent tiles
   look native for free. Teaching addition: the components catalog
   renders into capabilities (they're fns; the functions-catalog
   already lists them — add a one-line "use these for your tile" nudge).
2. **Charts = inline SVG hiccup** (the demo-floor chart answer).
   `[:svg ...]` is just hiccup — zero new deps, streams over SSE,
   morphs like everything else. Add `seon.ui.chart` (new cljc ns):
   `bar`, `sparkline`, `line` — data in, `[:svg ...]` out, each
   emitting `data-seon-desc` (feeds the §2 flatten) and phosphor
   palette defaults. ~120 lines total; agents CAN hand-write SVG but
   the helpers are the taught path.
3. **Images** — `[:img {:src "data:image/png;base64,…" :alt "…"}]`.
   Permitted: the slot stores a SYMBOL, the fn computes hiccup at
   render, so data-URIs transit SSE only — they never become datoms
   (three-tier storage rule holds: blobs don't go in the DB). `:alt`
   required by the teaching (it's the twin's description). Discouraged
   above ~100KB; charts should be SVG.
4. **Interactivity = datastar actions that message the agent back**
   (the artifact loop, and it's nearly free): a tile button POSTs to
   the EXISTING `/chat?agent=<id>` endpoint → `message!` → the agent
   wakes → handles it → its tile re-renders → SSE morphs the surface.
   Ship `seon.ui.components/tile-action`:

   ```clojure
   (comp/tile-action {:seon.ui/label "log workout"
                      :seon.ui/message "log: bench 5x5 @ 80kg"})
   ;; => [:button {:data-on-click "@post('/chat?agent=…&text=…')"} …]
   ```

   One component, no new endpoint, no new protocol — the interactivity
   loop is the messaging loop. Forms (an input + send) are the same
   shape with `data-bind`. This is the "more interactive than
   artifacts" part: the surface talks back through the same channel the
   human does, so the agent needs no second event system and the twin
   section shows the agent its own buttons as `[action: "log workout"]`.
5. **Sanitization (required before 4 ships):** agent-authored hiccup is
   an XSS surface in the human's browser. `html/->string` escapes
   strings, but attributes need a gate: strip `on*` attributes and
   `javascript:` URLs from agent-slot hiccup at render; allow `class`,
   `style`, `data-*` (datastar), svg presentation attrs, `src`/`href`
   (scheme-checked). One `sanitize-hiccup` fn in `seon.render`, applied
   to slot output only (substrate fns are trusted). The CLJS sandbox is
   not a security boundary; the render boundary is where this belongs.

**Floor (demo-able):** a live-updating hiccup tile with an SVG chart —
needs NOTHING new; the mechanism is live and capabilities-taught today
(an agent can transact a chart-tile fn this minute). **Ceiling:**
artifact-grade — charts + images + actions + forms, level-aware,
twin-aware, sanitized.

## 4. The home screen

Mission control (`/agents`) already is the tile grid — live,
SSE-morphed per commit, `render-agent-tile` per agent. Changes:

- **Tap → half-screen** (`/agent/<id>`), debug demoted to the `/debug`
  route. One-line change in `agent-grid-tile` once §1's route move
  lands.
- **Default tile content** (uncustomized agent): purpose line + status
  dot + last-activity age + turn footer (the §1 spec). Pulls the
  `:purpose` section text from `:seon.agent/ctx` — already on the
  entity, no new storage. Ties to but does not decide the parked
  `:seon.render/label` question.
- **Tiles render at `:seon.render/level :tile`** — the grid passes the
  key; customized agents that branch get their glanceable form here.
- Cluster strip, knowledge section, completed-agents fold: unchanged.

## 5. Teaching — how agents learn to drive their tile

Aligned with show-don't-tell; mostly extensions of surfaces that exist:

1. **The capabilities block** ("Your live tile") already teaches
   repointing. It grows: the `:seon.render/level` key, the
   `:seon.render/text` twin ("describe what the chart MEANS — your
   human sees the picture, you see your words"), `seon.ui.chart` +
   `tile-action` one-liners.
2. **The `:my-surface` section IS the feedback loop** — the agent sees
   its own tile's text twin every turn. Write a tile fn, next render
   shows you what your human now sees, iterate. No preview verb needed;
   the context is the preview. (This is the §2 mechanism doing double
   duty as the teacher — the same trick as the seeded `:purpose`
   section.)
3. **`(seon.agent/set-tile! {:seon.render/html 'my.ns/fn})`** — sugar
   exactly like `set-purpose!`: a visible one-liner over a transact,
   whose full source teaches that the tile is just an attr on your own
   entity. (Decide at build time whether the sugar earns its keep; the
   raw transact is already taught and works. Lean: ship it — symmetry
   with set-purpose!, and "set-tile!" is the discoverable verb name in
   the functions catalog.)
4. **A `my.kb.instruction` row** ("when your human asks for anything
   visual or standing — a chart, a status, a list — put it ON YOUR
   TILE, don't paste it into chat") — the store-proactively rule's
   visual sibling; user-editable doctrine.
5. **The turn-0 demonstrated pull** already shows `:seon.render/html`
   on the agent's own entity — unchanged, now corroborated by the
   `:my-surface` section above it.

## 6. Unit breakdown (≤7 files each, demo-floor first)

**Honest Friday call:** the demo-floor TILE BEHAVIOR can be in
Friday's demo because it requires **zero substrate change** — the slot,
the SSE morphing, and the teaching are live today; inline SVG is just
hiccup. Per the standing handoff, Thursday freezes the substrate for
rehearsal — so U1 (teaching text + gym scenario only) is the ONLY unit
that may land before the demo, and only if it lands Wednesday alongside
Tier-1 work and re-rehearses clean. U2+ are post-demo. The half-screen
view will NOT exist by Friday; the demo shows tile + debug, which is
what's rehearsed anyway.

- **U1 — demo floor: the chart-tile scenario (Wednesday-eligible,
  teaching-only).** Extend the capabilities tile block with the inline-
  SVG example + "tile, don't paste" instruction row; encode gym scenario
  S-TILE (§ below). Files: `ctx.cljs` (capabilities text), seed
  instruction, one scenario EDN, catalog note. No mechanism changes —
  rehearsal-safe by construction. If Wednesday is full, SKIP and demo
  the already-taught tile mechanism as-is.
- **U2 — awareness twin (the §2 core).** `flatten-hiccup->text` +
  optional `:seon.render/text` in `:seon.render/html-response` + the
  `:my-surface` default section + budget cap + tests. Files:
  `render.cljs`, `ctx.cljs`, two test nses.
- **U3 — level key.** Register `:seon.render/level`; thread through
  `render-agent-tile`/`html-render` inputs; `default/view` branches
  tile/surface; grid + inspector pass their level. Files: `render.cljs`,
  `render/default.cljs`, `inspector.cljs`, tests.
- **U4 — half-screen view + route move.** `/agent/<id>` = chat column
  (reuses chat-bar + message bubbles) beside the `:surface`-level
  render; debug → `/agent/<id>/debug`; header cross-links; grid tap
  target; SSE registry reused (both views are pushed by the same
  per-agent tx listener). Files: `inspector.cljs` (or a split-out
  `web/surface.cljs` if it crowds 7-file budget), routes, tests.
- **U5 — default tile = purpose + activity.** `default/view` `:tile`
  branch reads the `:purpose` section text + last-activity age. Small;
  folds into U3 if it fits.
- **U6 — sanitize + tile-action (interactivity).** `sanitize-hiccup`
  gate on agent-slot output; `tile-action`/`tile-form` components;
  teaching line. Files: `render.cljs`, `ui/components.cljc`,
  `ctx.cljs`, tests.
- **U7 — `seon.ui.chart`.** bar/sparkline/line helpers with
  `data-seon-desc`; teaching line; generative tests on the svg shape.
- **U8 (ceiling, user-gated) — presence.** SSE-registry "watching now"
  in the twin; optionally view events for "last seen". Do not build
  until the user wants the notify-vs-update judgment.

### The gym scenario (measurement angle)

**S-TILE "chart my workouts"** (deepseek tier, S-21's seeded workout
world): human asks "put a chart of my workouts on your tile."
Mechanical predicates: (1) agent entity carries a `:seon.render/html`
override pointing at an agent-authored fn; (2) `render-agent-tile`
returns hiccup containing `[:svg`; (3) post-U2: the agent's NEXT
context contains the `:my-surface` section with the chart's
description; (4) reply directs the human to the tile rather than
pasting an ASCII chart. LLM-judge axis: does the twin's description
match the seeded data (counts/weights)? Re-run after U2/U3 to verify
the awareness loop changed behavior — the falsifiable claim is
"an agent that SEES its surface stops re-describing it in chat."

## 7. Open questions for the user

1. **`:seon.render/label` (#34/P20, parked — your call pending):** the
   default tile derives its headline from the `:purpose` section text.
   Good enough, or do you want an explicit short-label attr? (Nothing
   here blocks on it.)
2. **Route precedence:** OK that `/agent/<id>` becomes the half-screen
   messaging view and debug moves to `/agent/<id>/debug`? (Bookmarks/
   muscle memory change; mission-control tap follows.)
3. **Presence/view events (U8):** do you want agents to know WHEN you
   last looked (requires append-only view events), or is "what you see
   when you look" (fully derived, no events) enough?
4. **set-tile! sugar:** ship the one-liner verb (symmetry with
   set-purpose!), or keep the raw transact as the only taught path?
5. **Data-URI images:** comfortable allowing them (SSE-only transit,
   never datoms, alt required), or SVG-only until a real need shows?
6. **U1 before Friday:** teaching-text-only and Wednesday-eligible, but
   it still touches the capabilities block the demo exercises — land it
   and re-rehearse, or freeze and demo the tile mechanism as already
   taught?
