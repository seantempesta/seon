---
name: ui-live-tiles
description: "Show your human a live VIEW, don't just message them. Use this BEFORE you reply to your human with a wall of text — when you have data, a query result, a table, a plan, a status, a recommendation, or progress to convey; when you want to build a tile / dashboard / canvas / chart / view for your human to SEE; when you're about to dump a result map or a list as prose; when you catch yourself narrating numbers you could render. Your human watches your CANVAS (the focal tile on your /agent/{id} page), not a chat log — render to it. Covers wiring :seon.render.live-tile/content (literal hiccup OR a tile fn returning {:seon.render/hiccup … :seon.render/ai …}), semantic-hiccup styling, the safelisted utility vocabulary, compact/expanded faces, seon.render/block for typed data, and the honest read-only scope (no interactive buttons yet). Cross-links data-oriented-clojure (derive-don't-store) + datahike (the data you'd show)."
---

# Live tiles — show your human, don't just tell them

Your human is not reading a chat log. They are looking at your **page** —
`/agent/{id}` — and the thing they actually watch is the **canvas**: the focal
tile at the top, the one HTML surface YOU control. Below it sits the transcript
of your messages and evals. The transcript is the narration; the canvas is where
the human SEES what's happening — your current plan, the data you found, your
progress, the result table, the recommendation.

A `message` is one line of narration that scrolls away. A tile is a persistent,
at-a-glance picture that stays put and re-renders as your work moves. If you found
8 rows, computed a comparison, or finished a plan, **a message that recites it in
prose is the wrong medium** — render it to your canvas and let the human see it.
You still message for turn-by-turn narration ("on it", "found the issue"); you set
the tile for the thing that should remain visible.

The good news: setting your tile is ONE transact, and you already see your own
tile in your context every turn (the `; Your live tile — what your human currently
sees` section). This skill is the deep version of that section.

## The one move: wire `:seon.render.live-tile/content`

Your live tile is ONE attribute on your own agent entity:
`:seon.render.live-tile/content`. You change what your human sees by transacting a
new value onto it — addressing yourself by your `:seon.agent/id` lookup ref (the
same "transact to my own lookup ref" upsert you use for everything about
yourself). The value is either a **literal hiccup vector** (instant, static) or a
**qualified fn symbol** (dynamic, re-derived every render). Grounded in
`src/seon/render/live_tile.cljs` (the ns docstring is the contract) and proven in
a live drive where an agent wired its tile first-try.

### (a) Literal hiccup — instant, no fn needed

For a one-shot view that won't change, transact the hiccup directly:

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.agent/id (seon.db/current-agent-id)
     :seon.render.live-tile/content
     [:div {:class "p-3 flex flex-col gap-1"}
      [:h2 {:class "text-sm font-bold text-signal"} "Status"]
      [:p {:class "text-xs text-text-200"} "All systems go."]]}]})
```

`(seon.db/current-agent-id)` returns YOUR id — it addresses your own entity.
`transact!` returns a data envelope; read `:seon.db/ok?` to confirm the write
landed (an eval can succeed while the write didn't — see the `datahike` skill).

### (b) A tile FN in your home ns — re-derives every render

For anything that reflects live state (a count, a query result, progress), define
a fn in YOUR namespace and wire its qualified SYMBOL. It is late-resolved and
re-invoked on every render, so the view stays current with zero extra writes:

```clojure
;; A tile fn returns the html-response map: hiccup for the human + an ai twin.
(defn status-tile
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [{:seon.db/keys [db]}]
  {:seon.render/hiccup
   [:div {:class "p-3 flex flex-col gap-1"}
    [:h2 {:class "text-sm font-bold text-signal"} "Status"]
    [:p {:class "text-xs text-text-200"} "Green."]]
   :seon.render/ai "Your human sees a green status card."})

;; Wire its SYMBOL (quote it) onto your tile attr:
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.agent/id (seon.db/current-agent-id)
     :seon.render.live-tile/content 'my.agent.<your-id>/status-tile}]})
```

Evolve the tile by **redefining your own fn** — `seon.render.live-tile` is the
shared core default; build your own, never edit the core one.

### The two renders a tile fn carries

The `{:seon.render/hiccup … :seon.render/ai …}` map is the
`:seon.render/html-response` contract (`src/seon/render.cljs`). Two views of the
same thing:

- **`:seon.render/hiccup`** — what the HUMAN sees on the canvas.
- **`:seon.render/ai`** — a short string saying what the content MEANS. This is how
  YOU know what your human currently sees: it renders back into your context every
  turn. Say what the picture shows ("3 sources found, top-ranked: Alpha"). A fn
  that omits the ai twin gets its raw hiccup shown to you instead — less legible,
  so add the twin.

## Render nicely — semantic hiccup first, then a small utility set

Tailwind's reset strips default element styling, but the core re-styles plain
semantic HTML inside your tile with the Phosphor theme (warm blacks, cream text,
amber accents, monospace). So **classless semantic hiccup gets styled for free** —
prefer it over div-soup. Grounded in the base-content layer of
`resources/public/css/input.css`.

```clojure
;; RIGHT — a classless table of rows is styled (borders, header weight, striping):
[:div
 [:h2 "Top sources"]
 [:table
  [:thead [:tr [:th "name"] [:th "rank"]]]
  [:tbody
   [:tr [:td "Alpha"] [:td "1"]]
   [:tr [:td "Beta"]  [:td "2"]]]]]

;; Also free: [:ul]/[:ol]/[:li], [:h1]–[:h4], [:p], [:pre [:code …]],
;;            [:blockquote], [:dl [:dt …] [:dd …]], [:hr], [:a], [:strong], [:em].
```

When you DO want layout or emphasis, only a **small safelisted utility
vocabulary** exists at runtime — anything else silently does nothing (the CSS is
built ahead of time, agents emit hiccup at runtime). The full list lives in the
`seon.render.live-tile` ns docstring and the `@source inline(...)` block of
`input.css`; the essentials:

- layout: `flex` `flex-col` `flex-row` `grid` `grid-cols-{2,3,4}` `gap-{1..4}`
  `items-center` `justify-between` `w-full` `min-w-0` `shrink-0`
- spacing: `p-{0..4}` `px-{1..4}` `py-{1..4}` `mt-{1,2}` `mb-{1,2}`
- text: `text-{2xs,xs,sm,base,lg}` `font-mono` `font-semibold` `font-bold`
  `italic` `truncate` `whitespace-pre-wrap` `tabular-nums`
- color: `text-text-{50..700}` (cream, bright→dim), `text-{signal,success,error,
  warning,info}`, `bg-base-{800,850,900,950}`
- borders: `border` `border-{t,b}` `border-base-{700,800}` `rounded` `rounded-md`
  `divide-y` `overflow-{hidden,auto}`

Wrong vs right — the same finding:

```clojure
;; WRONG — a message dumping the result as prose; it scrolls away, hard to scan:
(message/user {:seon.agent.message/content
  "I found 3 sources. Alpha is rank 1, Beta rank 2, Gamma rank 3."})

;; RIGHT — a tile that stays visible and reads at a glance:
[:div {:class "p-3 flex flex-col gap-2"}
 [:h2 {:class "text-sm font-bold text-signal"} "Sources (3)"]
 [:table
  [:thead [:tr [:th "name"] [:th "rank"]]]
  [:tbody
   [:tr [:td "Alpha"] [:td {:class "tabular-nums"} "1"]]
   [:tr [:td "Beta"]  [:td {:class "tabular-nums"} "2"]]
   [:tr [:td "Gamma"] [:td {:class "tabular-nums"} "3"]]]]]
```

### Already have a value? Hand it to `seon.render/block`

If you're holding a typed value and don't want to author hiccup, tag it and let
`seon.render/block` render it (`src/seon/render.cljs`). It dispatches on the
namespaced key the value carries — markdown, source, a data projection, an error,
or raw — and renders ANYTHING without throwing:

- `{:seon.render/markdown "## heading\n- a\n- b"}` → rendered markdown
- `{:seon.render/source "(defn f [x] x)"}` → syntax-highlighted Clojure
- a `:seon/error` value → a legible error card
- any raw value → a collapsible data drill-down

This is also why, while you're on the core default tile, **a plain markdown reply
already renders richly on your canvas** — your latest reply becomes a markdown
card automatically. So a clean `## heading` / `- list` / `**bold**` answer looks
good with zero tile work. Build a tile only when you want something richer than
your words.

## Compact vs expanded — tag blocks, don't write media queries

Your tile shows at two sizes: small in a grid cell, large on the agent canvas.
Emit BOTH faces in one render and tag them; the core's container queries pick
which is visible (`input.css`). The compact face is height-clamped (grid tiles are
uniform), so put a glanceable summary there and the full content in expanded:

```clojure
[:div {:class "seon-tile"}
 [:div {:class "seon-tile-compact p-3"}  [:span "3 sources found"]]
 [:div {:class "seon-tile-expanded p-4"} [:table …full table…]]]
```

Untagged content shows at every size — fine for simple tiles. Never write
`@media`/`@container` rules yourself; tag the blocks.

## The view is a FUNCTION of your state — re-render, don't snapshot

A tile fn that QUERIES the store re-derives the right picture every render: a fresh
pod, a restart, new data — it just shows the truth. A hardcoded hiccup snapshot of
a computed value goes stale and dies with the session. This is the
derive-don't-store doctrine (see the `data-oriented-clojure` skill) applied to the
human surface: don't store a "current view" you have to keep updating — make the
view a pure fn of the DB and transact findings as linked entities you render by
reference.

```clojure
;; RIGHT — the tile re-derives the open-todo count from the store every render:
(defn plan-tile
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [{:seon.db/keys [db]}]
  (let [open (db/query {:seon.db/db db
                        :seon.db/query '[:find (count ?t) . :where
                                         [?t :my.todo/status :open]]})]
    {:seon.render/hiccup
     [:div {:class "p-3"} [:h2 "Plan"] [:p (str (or open 0) " items open")]]
     :seon.render/ai (str (or open 0) " plan items still open.")}))
```

For the queries you'd put in a tile, see the `datahike` skill. The tile updates the
instant the underlying data changes — no second write, no "refresh the view" step.

## When to message vs when to set the tile

Both, for different jobs — they are not competitors:

- **Message** for the conversational, turn-by-turn thread: acknowledgements,
  questions back to the human, a one-line "done". These are events in time.
- **Set the tile** for the persistent, at-a-glance state: the data, the plan, the
  status, the result, the chart. This is a picture of NOW, not an event.

Rule of thumb: if you're about to recite numbers, a list, a table, or progress in
a message — that belongs on the tile. The message can be one line pointing at it
("updated the sources tile").

## Honest scope — what's renderable today

- **Renderable now:** any static or live VIEW — text, tables, lists, headings,
  code blocks, data drill-downs, compact+expanded faces, the full safelisted
  utility palette, re-derived-every-render tile fns. This is a lot; most "show my
  human X" asks are fully met.
- **NOT yet:** **interactivity.** A live tile is a read-only rendered view. There
  is no built-in way to put a working button or input on the tile that calls back
  into you (the action-registration API isn't public yet, confirmed in a live
  drive that burned ~25 evals discovering this dead end). Likewise the designed
  `my.tile/show!` wrapper is not in the live system — use the raw transact above.
  If your human asks for something they can interact with (e.g. "a tile that lets
  me add a todo"), render the view AND tell them how to drive it through chat
  ("message me `add todo: <title>`"). Don't spend evals hunting for an onClick;
  it isn't there.

## Key files

| File | What it grounds |
|------|-----------------|
| `src/seon/render/live_tile.cljs` | THE contract — ns docstring = the tile vocabulary, faces, styling, welcome example |
| `src/seon/render.cljs` | `:seon.render/html-response`, `seon.render/block`, `render-agent-tile` |
| `src/seon/agent/ctx/live_tile.cljs` | the per-turn "what your human sees" context section (copy-paste examples) |
| `src/seon/ui/world.cljs` | how `/agent/{id}` assembles the canvas (live tile) + supporting tiles |
| `resources/public/css/input.css` | the safelisted utility list + the semantic base-content styling |
