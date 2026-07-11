---
type: research
status: active
tags: [research, web, ui, agent]
---

# Agent presentation — the focal canvas + transcript (and the root system view)

> Design-only. Every load-bearing claim is grounded in `file:line` and live-pod
> evals (default cluster, 7890, root agent). No `src/` changes were made.

## TL;DR

- **The model is settled: focal CANVAS on top + collapsible TRANSCRIPT below +
  chat input pinned at the bottom.** The canvas is the agent's live tile; the
  transcript is the agent's existing interleaved messages-and-evals stream
  (enriched, not renamed); every entry renders **server-side**, by kind.
- **Make typed rendering FIRST-CLASS (the owner's key ask).** Add ONE reusable
  `value → hiccup` renderer that dispatches on kind — `message | data | source |
  error | hiccup` — so the transcript "just displays the block" and every surface
  reuses it. It's a thin LAYER ABOVE `seon.ui.html` (which stays the dumb,
  stable hiccup→string serializer), delegating to capability that mostly EXISTS:
  `md->hiccup` (message), `seon.render.value` (data drill-down), the
  `error-tile` seam (error), + a small server-side Clojure highlighter (source).
- **Markdown→hiccup already exists and is the answer — `seon.ui.markdown/md->hiccup`**
  (`src/seon/ui/markdown.cljs:211`, 224 lines: headings/lists/code-fences/bold/
  italic/inline-code/links, serializer-escaped). It already renders the chat
  bubbles server-side (`seon.render.chat/bubble`, `chat.cljs:225`). **No new lib;
  reuse it.** (Live-proven: `(md/md->hiccup "## Hi …")` → real `[:h2]`/`[:ul]`/
  `[:strong]` hiccup.)
- **The actual presentation gap is a markdown LANE mismatch, not a missing
  renderer.** Two markdown mechanisms coexist: (a) **server-side** `md->hiccup`
  (chat bubbles) and (b) **client-side** `[:div {:data-markdown "…"}]` + the
  inspector's `marked.js`/`seonMarkdownAll` pass. The message card
  (`handlers/message.cljs:117`) and the eval-narration card
  (`handlers/eval.cljs:130`) use lane (b). **The new world shim loads ONLY
  `datastar.js`** (`web/datastar.cljs:239`) — no `marked.js` — so on `/agent/{id}`
  the body sits in an unread `data-markdown` ATTRIBUTE and shows as plain/blank
  text. That is exactly why root's "how's your env" reply looked raw. Fix =
  collapse onto lane (a) everywhere the world page renders.
- **The canvas default is wrong content, not wrong markdown.** When an agent has
  no `:seon.render.live-canvas/content` (live-proven: root `:has-content? false`),
  the canvas resolves to `welcome` (`live_tile.cljs:439`) — a greeting/date/purpose
  card whose EXPANDED view never shows the latest reply. Owner's fix: when no
  custom tile, the canvas renders the **latest `:origin :agent`→user message as a
  markdown card**. Implement by extending `welcome` in place (it already pulls
  `last-reply`), NOT a parallel default.
- **The transcript already interleaves messages + evals chronologically** —
  `transcript-block-html` (`ctx/transcript.cljs:428`) over `ordered-events`
  (messages + evals UNIONed + time-sorted, `transcript.cljs:321`), each eval
  rendered by the existing `handlers/eval.cljs:102` card (collapsed source + ok/err
  glyph + duration; error already a `<details>`). **Reuse + enrich it** (route each
  entry through the typed `block` renderer: markdown message, value-explorer result,
  highlighted source, error card) and wrap it in a collapsible `<details>` below
  the canvas. Not a new "history" surface — the same transcript.
- **Agent guidance (Core):** extend root's existing `:live-tile` ai block + the
  `welcome` `:seon.render/ai` text with 1-2 copy-paste hiccup examples; v1 needs
  NO `my.canvas` build — "transact `:seon.render.live-canvas/content`" already works.
  `my.canvas`/interactivity (task #22) is the richer follow-up, not a v1 gate.
- **Root (`/`) is the SAME page, specialized via the SAME canvas seam.** Root's
  `:seon.render.live-canvas/content` is seeded (at root bootstrap) to a
  **system-understanding** render fn; its transcript is the UNFILTERED cross-agent
  stream. One mechanism: root doesn't get a special layout, it gets a special
  canvas symbol. Opinionated layout proposed in §6 below (vitals strip → agent
  card grid (#27) → store-inventory summary → recent cross-agent activity).

---

## 1. Markdown→hiccup in our CLJS stack

**Use the existing `seon.ui.markdown/md->hiccup`** (`src/seon/ui/markdown.cljs:211`).
It is a ~224-line pure CLJS block+inline renderer covering everything an agent
reply needs:

- blocks: ATX headings, `-`/`*`/numbered lists, fenced code, paragraphs
  (`render-block` `markdown.cljs:113`, `group-blocks` `:150`);
- inline: `**bold**`, `*italic*`, `` `code` ``, `[text](url)` with scheme-guarded
  hrefs + `rel="nofollow noopener"` (`inline->hiccup` `:48`, `safe-link-url` `:38`);
- output is a `[:div {:class …} …]` vector that drops into any parent
  (`md->hiccup` `:218`), and `seon.ui.html` escapes every text node at
  serialization, so agent-authored raw HTML degrades to visible text (XSS-safe,
  `html.cljc:271`).

Live proof (7890):

```clojure
(md/md->hiccup "## Hi\n\n- a\n- b\n\n**bold** and `code`")
;; => [:div {:class "text-xs"}
;;     [:h2 {:class "text-sm font-bold text-signal …"} "Hi"]
;;     [:ul {:class "list-disc pl-5 my-1 …"} [:li … "a"] [:li … "b"]]
;;     [:p … [:strong … "bold"] " and " [:code … "code"]]]
```

It is ALREADY the production chat-bubble renderer (`render/chat.cljs:248,256`), so
this is the proven, stable path — **not** a new dependency.

**Trade-off / the real decision:** the choice is not "which lib" but "which
LANE." Today the world page is split:

| Lane | Mechanism | Where | World-page support |
|---|---|---|---|
| **(a) server-side** | `md->hiccup` → hiccup text nodes | `render/chat.cljs` bubbles | ✅ curl/SSE sees `<strong>` |
| **(b) client-side** | `[:div {:data-markdown "…"}]` + `marked.js` | `handlers/message.cljs:117`, `handlers/eval.cljs:130` | ❌ shim loads only `datastar.js` (`datastar.cljs:239`) |

**Recommendation:** collapse onto lane (a). Swap the two `data-markdown` sites to
`(md/md->hiccup body …)`. This (1) fixes the world page, (2) deletes a parallel
markdown system (the "don't be a dumbass" rule — one renderer, not two), and (3)
is strictly safer (server-escaped, no client JS, time-travel/`?t=` snapshots
render markdown without a JS pass). The legacy `/debug` view still works because
md->hiccup output is plain hiccup the debug page renders too; if any debug-only
`data-markdown`/`marked.js` truly remains needed it stays isolated to
`web/debug.cljs`, but the agent-WORLD surfaces go server-side. (Searching the
CLJS deps / `reference-code/` for a node markdown lib is unnecessary — we already
own a sufficient, stable one. Owner pref "simple + stable over clever" → reuse.)

Lane note: `seon.ui.markdown` physically lives under `src/seon/ui/` (UI lane) but
is a **pure leaf util** that Core's `seon.render.chat` already requires — treat it
as a shared leaf, callable from either lane.

---

## 2. The canvas default-to-latest-message seam

### What renders today (grounded)

`world-layout` (`ui/world.cljs:123`) sets the focal `#world-canvas` to
`(render/render-agent-tile {:seon.agent/id id :seon.db/db db})`'s `:seon.render/hiccup`
(`world.cljs:144-161`). `render-agent-tile` (`render.cljs:407`) resolves content via
`live-canvas/wired-content` (`live_tile.cljs:360`): **`:seon.render.live-canvas/content`
present → render it (UNCHANGED); else → `welcome-sym`** (`live_tile.cljs:355,377-379`).
So the "else" branch the owner wants to change is **`welcome`** (`live_tile.cljs:439`).

Live proof (root, 7890): `:has-content? false`, canvas hiccup head =
`[:div {:class "seon-tile"} …]` (the welcome). `welcome`'s EXPANDED block
(`live_tile.cljs:494-499`) renders greeting + date + purpose-line + `tile-line`
and **never the latest reply**; only the COMPACT block (`:490-493`, for the grid)
shows `last-reply`, and as `whitespace-pre-wrap` plain text. → the agent's actual
words never reach the canvas richly.

### The exact change (extend `welcome` in place — do NOT fork a new default)

`welcome` already computes `reply` from `seon.render.chat/last-reply`
(`live_tile.cljs:478-483`) — the newest `::agent`→user message
(`chat.cljs:195`, live-proven: root has a 3-message conversation,
`[:agent :human :agent]`). The minimal, no-parallel-system change:

- **Expanded block (`live_tile.cljs:494-499`):** when `reply` is present, lead with
  a **markdown card** — `(md/md->hiccup reply {:wrap-class "markdown text-sm text-text-100"})`
  inside the existing card chrome — and demote the greeting/date to a thin subhead.
  When `reply` is absent (fresh agent), keep today's greeting card (it's a good
  empty-state).
- Compact block (grid) stays plain-text 1-liner — markdown in a grid cell is
  noise; keep it lean.
- `welcome`'s `:seon.render/ai` (`live_tile.cljs:500`) is unchanged — the agent
  still reads "your tile shows the core default; point
  `:seon.render.live-canvas/content` at your own fn to replace it."

This keeps ONE default tile fn, ONE resolution seam (`wired-content`), and the
custom-content path totally untouched. A consumer/agent that sets
`:seon.render.live-canvas/content` still wins exactly as before.

Lane: **Core** (`seon.render.live-tile` is the render engine, Core-owned per
`coordination.md:43`). It gains a require on the leaf `seon.ui.markdown` (Core's
`render.chat` already has it).

---

## 3. The unified typed-block renderer (first-class, reusable)

The owner's architectural ask: **invest in the RENDERING FUNCTION so typed
rendering is first-class and reusable** — a `value → hiccup` renderer that
DISPATCHES ON KIND (`message | data | source | error | hiccup`) — so the
transcript "just displays the block" and every other surface reuses the same
renderer. Not transcript-specific. Reuse existing names/fns; no parallel
renderers.

### What render capability ALREADY exists (inventory — do not reinvent)

| Kind | Existing capability | file:line | State |
|---|---|---|---|
| **message** (markdown) | `seon.ui.markdown/md->hiccup` | `ui/markdown.cljs:211` | ✅ live (chat bubbles) |
| **data / EDN** (collapsible drill-down) | `seon.render.value` — `sample` skeleton + `render-html-data` data contract (`:tree`/`:summary`/`:truncated?`) | `render/value.cljs:248,427` | ⚠ projection live; the html PANEL that turns `:tree` → collapsible hiccup is UI-lane (docstring `:437` "styling+interactivity are U's") — re-wire it into the world UI |
| **source** (Clojure) | `[:code.language-clojure.hljs …]` relying on CLIENT highlight.js | `handlers/eval.cljs:140`; loader `web/debug.cljs:585-614` | ⚠ client-only; world shim has no hljs → unhighlighted |
| **error** (`:seon/error`) | `seon.render.live-canvas/error-tile` seam (`set!`-overridable) + `default-error-tile` | `render/live_tile.cljs:566` | ✅ live, override-proven (acme) |
| **hiccup** (literal) | `seon.ui.html/->string` (seq-flatten, escape) | `ui/html.cljc:319` | ✅ live |

So FOUR of five kinds already have a renderer; the work is **routing**, plus the
source-highlighter gap (§3 Clojure below). The smart-value renderer
(`seon.render.value`) is the "value-explorer / smart value" the coordinator
referenced — its EDN projection is live; only its collapsible HTML panel needs
re-wiring into the world UI (it was the kind of surface deleted in the #6 inspector
cleanup).

### The proposed renderer — `seon.render/block` (one dispatch fn)

A single guarded `value → hiccup` fn that DELEGATES to the inventory above by a
**value-kind flavor** (NOT a stored `:kind` field — the namespaced keyword on the
value IS the discriminator, per the house rule):

```clojure
;; seon.render/block  (the typed render layer — Core)
(defn block [view x]            ; view = :html | :ai
  (cond
    (md-text? x)        (md/md->hiccup (:seon.render/markdown x) …)   ; message
    (value-projection? x) (value-panel x)                            ; data (seon.render.value/:tree)
    (clj-source? x)     (clj->hiccup (:seon.render/source x))        ; source  (§3 highlighter)
    (error-value? x)    (live-canvas/error-tile x)                     ; :seon/error
    (hiccup? x)         x                                            ; literal hiccup passthrough
    :else               (value-panel (value/render-html-data … x)))) ; fallback: project anything
```

This is NOT a new mechanism — it is the SAME unwrap/guard seam
`render-entity-html` (`render.cljs:344`) already centralizes (the ONE
`unwrap-response`, `render.cljs:330`), generalized to dispatch on value-kind. The
entity converters (`handlers/*/render-html`) become THIN: each just tags its
fields and hands them to `block` (message → `{:seon.render/markdown body}`, eval →
source+value+error tagged). The transcript, the canvas, `/debug`, and any future
surface call `block`; styling lives once.

### `seon.ui.html` vs a thin typed layer above it — DECIDE

Owner hint: "improve the html rendering function and then it's just displaying the
block." Two readings:

- **(A) Push typing DOWN into `seon.ui.html/->string`** — teach the serializer to
  recognize tagged values (a `:seon.render.value/*` map, a `:seon/error` map) and
  render them. **Reject.** `seon.ui.html` is the pure hiccup→string serializer
  (`html.cljc:319`); it must stay dumb about domain shapes (it's the one place that
  escapes/flattens, shared by every surface incl the prompt-less ones). Coupling it
  to `:seon.render.value` / `:seon/error` makes the serializer depend on the render
  domain — a layering inversion.
- **(B) A thin typed-render LAYER ABOVE the serializer** — `seon.render/block`
  turns a tagged value INTO hiccup; `seon.ui.html/->string` then serializes that
  hiccup, unchanged. **Recommend B.** "Improve the html rendering function" =
  improve the `value → hiccup` step (`seon.render/block`), which feeds the
  already-good `hiccup → string` step. One renderer, clean layering, and
  `seon.ui.html` stays the stable leaf.

### Clojure source highlighting — grounded

There is **no JS highlighter bundled** in `node_modules`/the shadow build (grep:
none). The only highlighter in the codebase is **client-side highlight.js loaded
from CDN** on the legacy `/debug` shell (`web/debug.cljs:596-598`) + a
re-highlight-after-morph pass (`:609-614`). The new world shim loads ONLY
`datastar.js` (`datastar.cljs:239`), so eval source there is unhighlighted.

Options:

- **(a) Load CDN highlight.js + the clojure module on the world shim** (reuse
  debug's loader) + a MutationObserver re-highlight after every idiomorph morph.
  Zero new code, but: re-introduces a CLIENT rendering lane (the exact thing §1
  kills for markdown), a CDN dependency, and an idiomorph-vs-hljs span race the
  debug page already has to actively manage.
- **(b) A minimal SERVER-SIDE Clojure tokenizer → hiccup spans** — same shape as
  `md->hiccup`: tokenize parens/keywords/strings/`;`comments/symbols/numbers into
  `[:span.hljs-keyword …]` (reuse debug's existing `.hljs-*` CSS classes,
  `debug.cljs:554-558`, so the theme is shared). ~60-100 lines, pure, morph-safe
  (no client pass, no race), renders under `?t=` time-travel and curl.

**Recommend (b)** — it keeps the world page's JS = datastar-only (the consolidated
server-side lane), is morph-safe by construction, and reuses the existing
`.hljs-*` palette so it's not a new theme. It lives in the typed layer
(`seon.render/clj->hiccup`, a sibling of `md->hiccup`). (a) is the acceptable
SAME-DAY stopgap if highlighting is wanted before (b) lands, but it's a parallel
lane we'd later delete. Owner pref "simple + stable" + "improve the rendering
function" both point at (b).

---

## 4. The transcript — every entry rendered by kind via `block`

### What exists (and why it's already the right thing)

`transcript-block-html` (`ctx/transcript.cljs:428`) renders the agent's flat,
time-ordered event stream as cards over `ordered-events` —
**messages + evals UNIONed and time-sorted** (`transcript.cljs:321-333`,
`kind-rank` ties messages before evals). Each event renders through
`render/render-entity-html` → its schema-kind converter:

- **messages** → `handlers/message.cljs:66` (bubble card; body currently
  `data-markdown` at `:117`);
- **evals** → `handlers/eval.cljs:102` — already collapsed: header = `eval <id>` +
  `<dur>ms` + `:ok`/`:error` glyph (`:132-138`), source as `<pre><code>`
  (`:139-140`), a one-line `=> <short>` on ok (`:143`, `short-result` — NOT the
  value-explorer), and a `<details>` for the full error (`:148-152`). Narration
  `data-markdown` at `:130`.

So the owner's "transcript shows real evals, expandable to dive into, interleaved
with messages" is **already the right structure** — `transcript-block-html` IS the
interleaved stream. We ENRICH it (not replace it): route each entry's fields
through `seon.render/block` so every kind gets TLC.

### The enrichment — render each entry by kind through `block`

The converters get THINNER and BETTER (the transcript "just displays the block"):

1. **message** → `block` markdown card (`md->hiccup`), replacing the `data-markdown`
   attr (`handlers/message.cljs:117`).
2. **eval source** → `block` Clojure-highlighted card (§3 path b), replacing the
   bare `<pre><code>` (`handlers/eval.cljs:140`).
3. **eval result (`:ok`)** → `block` data panel = the **value-explorer collapsible
   drill-down** (`seon.render.value/render-html-data` `:tree` → the UI value panel),
   replacing the one-line `short-result` (`handlers/eval.cljs:143`). The collapsed
   eval card shows summary + glyph + duration; expand → the drill-down tree.
4. **eval error / narration** → `block` error card via the `error-tile` seam
   (`live_tile.cljs:566`) for errors; narration via `md->hiccup` (replacing
   `data-markdown` `:130`).

Each eval entry: **collapsed** = source preview + ok/err glyph + duration;
**expanded** = highlighted source + value-explorer result (or error card) +
markdown narration. Same `<details>` affordance the error already uses
(`eval.cljs:148`), generalized.

### Placement (UI)

In `world.cljs`, render the `:transcript` block NOT as a normal supporting tile in
the priority loop (`world.cljs:162-171`) but as a dedicated collapsible
`<details open>` **transcript** section (id `#world-transcript`) BELOW
`#world-canvas` and above the chat input. Exclude `:transcript` from
`agent-html-block-names` (`world.cljs:61`) so it isn't double-rendered. **Lane:
UI** (`ui/world.cljs`).

Note the agent's PROMPT transcript (the `:seon.render/ai` twin,
`transcript.cljs` `transcript-block`) is untouched — the human gets the
typed-rendered transcript, the agent keeps its REPL transcript. Same data, two
renders (the "prompt == page" invariant, `ui.md:80`).

---

## 5. Agent guidance + reusable examples (Core)

**Where:** root (and every agent) already carries a `:live-tile` ctx block
(ai-only, priority 35 — live-proven in root's `:seon.agent/ctx`) and the `welcome`
fn's `:seon.render/ai` render (`live_tile.cljs:500-516`) that teaches the live-tile
contract every turn. **Extend these two existing surfaces** — do not add a new
block:

- Add to the `:live-tile` block's ai render (Core, the block's render fn) a short
  **"present richly OR reply in markdown"** instruction plus **2 copy-paste
  examples**:
  1. a literal-hiccup note card transacted onto `:seon.render.live-canvas/content`
     (no SCI, instant);
  2. a tiny `(defn my.agent.<id>/status-tile [m] {:seon.render/hiccup … :seon.render/ai …})`
     + the transact that points content at it (the dynamic, re-derived path).
- The examples are the worked-example pattern already used by `welcome` itself
  (`live_tile.cljs:530` `wiring-source` shows the agent the transact form as its
  first logged eval).

**Does this need `my.canvas` (task #22)?** **No, not for v1.** The presentation gap
the owner hit is "a plain markdown reply looks raw" — solved entirely by §2
(canvas default) + §1 (server markdown), with ZERO agent effort. The next rung
("set a nice view") is solved by the example above (transact hiccup onto
`:seon.render.live-canvas/content`), which works today. `my.canvas/show!` (prebuilt
`:note`/`:pros-cons`/`:recommendation` views, `toolkit.md:531`) and live-tile
INTERACTIVITY (the DeepSeek observer's "couldn't add a new todo" — task #22) are
the richer follow-up: build them when the observer shows agents want a
fill-in-the-blank view, not as a v1 blocker. **Recommendation: smallest useful
v1 = canvas-default + server-markdown + the two examples in the existing
`:live-tile` block.**

---

## 6. The root view (`/`) — same page, system-understanding canvas

`root = /` is settled (`root-os-vision.md`, "Settled decisions"): `/` is the root
agent's world, `/agent/{id}` uniform for all incl `/agent/root`. So root needs NO
special page — it needs a **special canvas content symbol**, seeded at root
bootstrap, reusing the §2 seam every agent uses:

> root's `:seon.render.live-canvas/content` = `seon.render.system/system-view` (a
> new Core render fn). Generic agents fall through to `welcome` (latest-message
> card); root falls through to the fleet/system view. **One mechanism, one seam.**

### Proposed system-understanding layout (opinionated; "surprise me")

A top-down zoom — the four questions a human orchestrator actually scans, in
order: *is it healthy? who's doing what? what does it know? what just happened?*

1. **Vitals strip** (one dense row). `N agents` broken down by
   `seon.derive/derive-state` (e.g. `3 idle · 1 running · 0 paused`), total
   turns/evals today, last-activity timestamp, embedding on/off (`SEON_EMBED`).
   A single-glance pulse. Self-healing: derived, nothing stored.
2. **The agent card grid** (task #27 — restore `consumer-snapshot` /
   `agents-dash-fragment` richness from `git show
   1eec28dc~1:src/seon/web/inspector.cljs`). Each card = `render/render-agent-tile`
   preview (its live tile / welcome-compact) + `derive-state` dot +
   `derive/agent-turn-count` + purpose line, the whole card a link to
   `/agent/{id}`. Root's own card first, visually marked. **This is the
   mission-control core — one element of the larger view, not the whole view.**
3. **Store / schema overview** — `seon.db/store-inventory` (`db.cljs:1219`)
   rendered as a concise "which attrs hold data" summary (the agent-facing concise
   version is being built in parallel — consume it when it lands; until then render
   the top entity-kinds by datom count + a handful of recently-written attrs).
   This is the system's MEMORY at a glance — the thing that makes root feel like an
   OS, not a process list. Links out to `/data` (the operator datom browser in
   `web/debug.cljs`).
4. **Recent cross-agent activity** — the UNFILTERED version of §4's transcript: the
   `ordered-events` stream WITHOUT the per-agent filter, last N, each line linking
   to its agent. This is the reactive-context "a section that doesn't filter by
   `:seon.agent/id` sees the whole core" property (CLAUDE.md) made literal — root
   watches every agent's messages + evals scroll by.

**Rationale:** health → roster → knowledge → activity is the scan path of someone
running a fleet; the grid alone (today's barren `/world`) answers only "who
exists," not "is it OK / what does it know / what just happened." Putting
store-inventory in the root canvas is the opinionated bet — it's what distinguishes
a system-understanding view from a process list, and it ties directly to the
owner's "teach us the failure modes" milestone.

**Alternatives (brief):** (a) two-column grid + activity rail — richer but a
heavier responsive layout; defer until the single-column proves cramped. (b)
store-inventory as a separate linked tile rather than inline — loses the
at-a-glance "system memory," so inline wins for v1. (c) crash-loop/heartbeat
health flags surfaced inline (the `root-os-vision` supervisor arc) — design space
for later; the vitals strip is where they'll land.

**Core/UI split for the root view:**

- **Core:** the system render fns (`seon.render.system/system-view` +
  `fleet-summary` / `store-summary` / `recent-activity`), root's bootstrap seeding
  `:seon.render.live-canvas/content` = `system-view` + a system-scoped `:root`
  context block (its `:seon.render/ai` twin = root's prompt understanding of the
  fleet — same data, agent-facing). System-scoped = the query fns take NO agent
  filter.
- **UI:** nothing root-special — `/` already routes to `world-layout` for `"root"`
  (`root-os-vision`, `db->routes` live `3c7cfb72`). The card grid's CSS/Phosphor
  chrome and the responsive grid are UI. world-layout renders root's canvas (=
  system-view) and the unfiltered transcript exactly like any agent.

---

## 7. Core / UI work table + sequencing

| # | Work item | Lane | File(s) | Depends on |
|---|---|---|---|---|
| 1 | Server-side Clojure highlighter `clj->hiccup` (reuse `.hljs-*` CSS) | Core | new in typed layer (sibling of `ui/markdown.cljs`) | none |
| 2 | The typed renderer `seon.render/block` (dispatch: message\|data\|source\|error\|hiccup) | Core | `seon.render` (generalize the `unwrap-response`/guard seam `render.cljs:330,344`) | 1, md, value, error-tile (exist) |
| 3 | Re-wire `seon.render.value` `:tree` → collapsible hiccup value panel (the value-explorer) into the world UI | UI | `ui/**` consuming `render/value.cljs:427` | — |
| 4 | `message` converter → `block` markdown card (drop `data-markdown`) | Core* | `handlers/message.cljs:117` | 2 |
| 5 | `eval` converter → `block`: highlighted source + value-panel result + error card + md narration | Core* | `handlers/eval.cljs:130,140,143,148` | 2, 3 |
| 6 | Canvas default: `welcome` expanded → latest-reply markdown card via `block` | Core | `render/live_tile.cljs:439,494-499` | 2 |
| 7 | `:live-tile` block ai render: guidance + 2 copy-paste examples | Core | the `:live-tile` block render fn (`seon.agent.ctx*`) | none |
| 8 | Transcript: wrap `:transcript` in collapsible `<details>` `#world-transcript` below canvas; exclude from supporting-tile loop | UI | `ui/world.cljs:61,162-171` | 4,5 |
| 9 | Root system render fns (`fleet`/`store`/`activity`/`system-view`) | Core | new `seon.render.system` | store-inventory concise (parallel) |
| 10 | Root bootstrap: seed `:seon.render.live-canvas/content`=`system-view` + `:root` ctx block | Core | root bootstrap (`seon.agent`/seed) | 9 |
| 11 | Agent card grid chrome + responsive CSS; unfiltered transcript on `/` | UI | `ui/world.cljs`, `resources/public/css` | 9,10 |

\* **Lane flag (fork below):** `src/seon/handlers/**` is NOT named in the
`coordination.md:43` lane table. The entity converters are render-engine-adjacent
(resolved by `seon.render/render-entity-html`), so I classify them **Core**, but
the owner/coordinator should confirm — items 4,5 touch them and they're the
shared dependency for both the transcript (UI) and the canvas (Core).

**Sequencing / handoffs:**

- **Items 1-2 are the keystone** — the typed renderer + highlighter. Everything
  else routes through `block`, so build it first. Pure functions, no schema/seed,
  **no cluster reset** (hot-reload); verify by gunzipping `/agent/{id}/feed`.
- Item 3 (value panel) and items 4-5 (converters) consume `block`; 5 also needs 3.
- Item 8 (UI) consumes 4-5; coordinate so the `:transcript` exclusion and the
  converter swaps land together (else the transcript shows raw text mid-flight).
- Items 6-7 are independent Core (6 needs `block`), no reset.
- Items 9-11 (root) are the larger arc; 9 depends on the parallel concise
  `store-inventory`; 10 (seeding root's content symbol) is a **seed change → needs
  `bin/seon cluster reset default`** to take effect on the running pod (seed
  upserts on next boot). Announce the reset per `coordination.md` protocol.
- Verify every item server-side (node gunzip client on the feed), not in a browser
  agent (SSE 503s through the chrome tool); final eyeball is the owner's.

---

## 8. Owner forks (tight — real decisions only)

1. **`handlers/**` lane ownership.** Items 4,5 edit `src/seon/handlers/*.cljs`
   (entity converters). Not in the lane table. **My call: Core** (render engine
   resolves them). Confirm, or assign to UI — it changes who lands the typed-render
   keystone. *Recommend: Core.*

   1b. **Where does `seon.render/block` + `clj->hiccup` live (Core)?** The typed
   layer sits above `seon.ui.html` (UI-lane file) but is render-engine logic
   (Core). I propose `seon.render/block` (Core's `seon.render`) + `clj->hiccup` as a
   `seon.ui.markdown` sibling (the leaf-util precedent). Confirm the Core/UI line
   for the highlighter file. *Recommend: render logic = Core; the `.hljs-*` CSS =
   UI.*

2. **Custom hiccup tile vs the message card — replace or stack?** The owner said
   "custom view OR markdown reply." When an agent BOTH sets a custom
   `:seon.render.live-canvas/content` AND keeps chatting, the canvas shows the custom
   tile (existing behavior, unchanged) and the latest reply lives only in the
   transcript below. **My call: REPLACE** — custom tile wins the canvas; the reply
   is in the transcript. (Stacking a reply card under every custom tile fights the
   agent's chosen presentation.) *Recommend: replace; the reply is one scroll away
   in the transcript.* Alternative if the owner wants the reply always visible: a
   thin "latest reply" strip between canvas and transcript (cheap to add later).

3. **Canvas = latest MESSAGE vs latest TURN summary.** Owner's words say "latest
   agent→user message." An agent turn can emit several messages + many evals; the
   "message" is the cleanest unit and matches `last-reply` (already built). **My
   call: latest message.** *Recommend: message.* A turn-summary would need a new
   derivation; not worth it for v1.

4. **Root canvas = system-view, or system-view as a tall tile under a normal
   canvas?** I propose root's `:seon.render.live-canvas/content` = `system-view`
   (root's canvas IS the fleet/system view, via the same seam). Alternative: keep
   root's canvas as welcome and make `system-view` the top supporting tile. **My
   call: canvas = system-view** (root's welcome is low-value; the fleet view is the
   point of `/`). *Recommend: canvas = system-view.*

5. **Store-inventory in the root canvas now, or after the concise version lands?**
   §6 item 3 depends on the parallel "concise which-attrs-hold-data"
   `store-inventory` rework. **My call: design the slot now, render the top-N
   fallback until the concise version lands** (don't block the whole root view on
   it). *Recommend: build with a fallback.*

---

## Grounding index (file:line)

- `seon.ui.markdown/md->hiccup` — `src/seon/ui/markdown.cljs:211` (blocks `:113,150`,
  inline `:48`, safe links `:38`).
- chat server-md model — `src/seon/render/chat.cljs:225` (`bubble`), `:277`
  (`bubble-stream`), `:159` (`conversation`), `:195` (`last-reply`).
- canvas resolution — `render.cljs:407` (`render-agent-tile`), `live_tile.cljs:360`
  (`wired-content`), `:355` (`welcome-sym`), `:439` (`welcome`; compact `:490-493`,
  expanded `:494-499`).
- message card (data-markdown) — `handlers/message.cljs:66,117`.
- eval card (data-markdown narration + error `<details>`) — `handlers/eval.cljs:102,130,148`.
- transcript interleave — `agent/ctx/transcript.cljs:428` (`transcript-block-html`),
  `:321` (`ordered-events` messages+evals union+sort).
- smart value renderer (data drill-down) — `render/value.cljs:248` (`sample`
  skeleton), `:427` (`render-html-data` `:tree`/`:summary`/`:truncated?` contract;
  panel is UI-lane per docstring `:437`); eval card uses one-line `short-result`
  (`handlers/eval.cljs:47,143`), NOT the explorer.
- error seam — `render/live_tile.cljs:566` (`default-error-tile`),
  `error-tile`/`error-response` (override-proven on acme, `ui.md` Total-override).
- Clojure highlighting — client-only highlight.js on legacy `/debug`:
  `web/debug.cljs:596-598` (CDN load), `:609-614` (re-highlight on morph),
  `:554-558` (`.hljs-*` palette); eval source `handlers/eval.cljs:140`
  (`language-clojure hljs`); NO highlighter bundled in `node_modules`/shadow.
- hiccup serializer (seq-flatten + escape) — `ui/html.cljc:319` (`->string`),
  `:271` (children walk).
- world page — `ui/world.cljs:123` (`world-layout`), `:61`
  (`agent-html-block-names`), `:158-171` (canvas + supporting tiles).
- shim loads only datastar.js — `web/datastar.cljs:239`.
- store-inventory — `db.cljs:1219`; old mission-control grid — `git show
  1eec28dc~1:src/seon/web/inspector.cljs` (`consumer-snapshot`,
  `agents-dash-fragment`); inspect helpers — `agent/inspect.cljs:59` (`ctx-preview`),
  `web/debug.cljs:195` (`consumer-snapshot`).
- live evals (7890, root): `md->hiccup` real hiccup; root `:has-content? false`,
  canvas = welcome; message card emits `[:div {:data-markdown "Hi — I'm up…"}]`;
  `conversation` = 3 msgs `[:agent :human :agent]`.

## See also

- [[ui]] — block/render/canvas/slot/layout; canvas=live-tile (#19); error seams.
- [[toolkit]] — `my.canvas` (the richer follow-up; task #22).
- [[root-os-vision]] — `root = /`; the system-understanding / supervisor arc.
- [[coordination]] — the Core⟷UI lanes + cross-lane interface.
