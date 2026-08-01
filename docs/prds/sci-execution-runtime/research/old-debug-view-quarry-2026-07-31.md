---
type: research
status: active
tags: [research, ui, render]
---

# Old debug view quarry — layout, bottom bar, and what ports

The owner's memory is correct and the artifact is recoverable. The old
two-pane `/agent/{id}/debug` view — LEFT exact AI bytes, RIGHT rendered
HTML twins, BOTTOM a per-block stacked token bar with cache markers —
lived in `src/seon/web/debug.cljs` and was deleted in stages during the
pod cut. Its richest surviving form is at commit `f90019ffc` (1128
lines, "Route debug through compiled prompt child", 2026-07-16); the
peak line count is `bc13d9a49`/`3c38b4dd9` (1196 lines) which differs
only in vocabulary (sections→blocks) and the pre-Datastar-registry SSE
plumbing. The context bar was born in `921f9fba6`
(`feat(agent-fsm): debug-panel context bar + seon.ai.tokens + usage extractor`).

What remains in the working tree at `src-old/seon/web/debug.cljs` (304
lines) is the GUTTED late version: one `<pre>` of the last persisted
turn's reply. Do not quarry that file for layout — it has none.

Recovery command used throughout this document:

```sh
git show f90019ffc:src/seon/web/debug.cljs
```

Line citations below of the form `debug.cljs@f90019ffc:NNN` are into
that blob (a verbatim copy was staged at `tmp/debug-quarry/debug-f90019ffc.cljs`
for this pass; `tmp/` is disposable, the commit is the authority).

## 1. The layout

### 1.1 The ns docstring states the design in one paragraph

`debug.cljs@f90019ffc:1-26`:

```clojure
(ns seon.web.debug
  "Operator dev tools — the two surfaces that have NO agent-view equivalent:

     GET /agent/<id>/debug      — the two-pane debug view: the EXACT
                                  bytes the LLM receives (left) beside the
                                  rendered context-block html twins (right),
                                  plus the per-block token + cache-line
                                  audit bar along the bottom.
     ...
   Both panes derive from the one coordinate-pinned compiled child result.
   The left pane shows exact AI text; the right pane shows only blocks
   declaring an HTML twin."
```

The earlier `bc13d9a49` docstring is more explicit about the shared
source, and is the sentence to preserve in the rebuild:

```
     - LEFT  `:seon.render/ai`   — the EXACT bytes the LLM would receive on its
       next render ...
     - RIGHT `:seon.render/html` — the CONTEXT sections' html twins
       (`:seon.render/section-html`), each rendered as one right-pane card in
       render order.
```

### 1.2 The page skeleton — flex column, one CSS grid, five rows

`debug-app-view` is the whole morph target, `debug.cljs@f90019ffc:631-643`:

```clojure
(defn- debug-app-view
  "The live debug morph target. Only active unit producers run."
  [dbv agent-id view-id snap catalog active-tokens]
  [:main {:id "app-view"
          :class "flex-1 min-h-0 flex flex-col overflow-hidden"}
   (header/system-header dbv)
   header/header-spacer
   (header-fragment agent-id snap)
   [:div {:class "flex-1 grid h-0 min-h-0"
          :style "grid-template-columns: 1fr 1fr;"}
    (ai-pane-fragment agent-id view-id snap catalog active-tokens)
    (html-pane-fragment agent-id view-id snap catalog active-tokens)]
   (context-bar-fragment agent-id snap)])
```

Vertical order, top to bottom:

1. `header/system-header` — the fixed global bar (fleet counts, `⛁ data` link);
   `src-old/seon/ui/header.cljs:20-46`.
2. `header/header-spacer` — a 2.25rem shim under the fixed header
   (`src-old/seon/ui/header.cljs:48`).
3. `header-fragment` — the per-agent debug header (see §3).
4. The two-pane grid — **exactly `1fr 1fr`, inline style, not a Tailwind
   class**, with `h-0 min-h-0` so the grid children own the scroll.
5. `context-bar-fragment` — the bottom bar (§2).

Outside the morph target, in the page shell `debug-shell`
(`debug.cljs@f90019ffc:645-674`), the body is `h-screen … flex flex-col`
and carries two more fixed rows below the morph: a chat bar and a
one-line footer.

```clojure
[:body {:class "h-screen bg-base-950 text-text-50 font-sans antialiased flex flex-col"}
 [:main {:id "app-view" :class "flex-1 min-h-0 flex items-center justify-center …"}
  "loading debug view…"]
 [:div {:id "debug-feed-opener" :style "display:none" :data-init "@get('/agent/…/debug/feed?view=…')"}]
 (chat-bar-fragment agent-id)
 [:div {:class "shrink-0 px-2 py-0.5 text-right text-[10px] font-mono text-text-500 bg-base-900 border-t border-base-800"}
  "esc → agent"]
 [:script …keydown: Escape → /agent/<id>; Cmd/Ctrl+Enter → submit chat…]]
```

So the true full-page stack is: global header · agent header · **two
panes** · context bar · chat bar · hint footer. The feed opener sits
OUTSIDE `#app-view` deliberately — a sibling-of-the-morph-target
placement whose earlier absence made `/agents` a dead first frame
(`3c38b4dd9 fix(web): roster feed opener moved outside the morph target`).

### 1.3 Scrolling and responsive behavior

There is no media query and no responsive collapse. The design is a
desktop operator instrument: `h-screen` body, `overflow-hidden` on every
container except the two designated scrollers, so **only the two pane
bodies scroll** and header/bar/chat never move.

Left pane, `debug.cljs@f90019ffc:373-397`:

```clojure
[:div {:id (ai-pane-id agent-id)
       :class "flex flex-col h-full overflow-hidden border-r border-base-800"}
 [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
  ":seon.render/ai  (exact prompt and source-block breakdown)"]
 …
 (into [:div {:class "flex-1 overflow-auto p-3 bg-base-950"}] …)
```

Right pane, `debug.cljs@f90019ffc:411-420`:

```clojure
[:div {:id (html-pane-id agent-id) :class "flex flex-col h-full overflow-hidden"}
 [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
  ":seon.render/html  (rendered view)"]
 [:div {:id (str "debug-cards-" agent-id)
        :class "seon-agent-content flex-1 overflow-auto p-2 text-xs bg-base-950"} …]]
```

Both panes carry a one-line **caption naming the projection keyword
itself** (`:seon.render/ai` / `:seon.render/html`). That is the clearest
single design idea in the whole view: the pane header teaches the
vocabulary while labelling the pane. Keep it verbatim.

Stable ids for morph targeting: `debug-ai-<id>`, `debug-html-<id>`,
`debug-header-<id>`, `debug-ctxbar-<id>`, `debug-cards-<id>`
(`debug.cljs@f90019ffc:194-196,456`).

### 1.4 Panes are lazy — `<details>` per block, activated over the wire

Neither pane rendered its bodies eagerly. Each block is a `<details>`
whose open state activates a view-unit producer:

`debug.cljs@f90019ffc:260-265,350-363`:

```clojure
(defn- unit-toggle-expression
  "Activate while this exact details element is open; deactivate on close."
  [view-id descriptor]
  (str "if (evt.target === el) { @get('" (unit-url view-id descriptor)
       "' + (el.open ? '1' : '0'), {retry: 'never'}) }"))

(defn- ai-block-details
  [view-id active-tokens descriptor {sec-name :seon.agent.ctx/name sec-text :seon.render/text}]
  (let [active? (contains? active-tokens (::datastar/token descriptor))]
    [:details (cond-> {:class "mb-1"
                       :data-seon-key (str "ai-sec-" (::datastar/token descriptor))
                       :data-on:toggle (unit-toggle-expression view-id descriptor)}
                active? (assoc :open true))
     [:summary {:class "cursor-pointer select-none text-xs font-mono text-text-400 hover:text-text-200 py-0.5"}
      (str (name sec-name) " (" (fmt-int (tokens/estimate sec-text)) " tokens)")]
     (html/raw (datastar/unit-element-html-in-view view-id descriptor active?))]))
```

**The per-block token count appeared in TWO places**: the `<summary>`
line of every left-pane block, and the bottom bar. The summary form is
`"<block-name> (3,214 tokens)"` — comma-grouped by `fmt-int`
(`debug.cljs@f90019ffc:243-251`).

The first left-pane entry is always the whole exact prompt, injected
ahead of the per-block list (`debug.cljs@f90019ffc:369-372`):

```clojure
displayed-blocks (into [{:seon.agent.ctx/name :exact-prompt
                         :seon.render/text ai-text}]
                       block-texts)
```

Right pane, `debug.cljs@f90019ffc:421-443`: same `<details>` shape with
a left amber rule (`border-l-2 border-amber-700/40 pl-2 py-1 animate-appear`),
`::datastar/label` as the summary, and an honest empty state:

```clojure
[:div {:class "text-text-500 italic p-2"}
 "no context block currently declares an HTML twin"]
```

Plus a "thinking" bubble pinned under the cards while the agent is
running, deliberately WITHOUT a stable id so every morph re-animates it
(`debug.cljs@f90019ffc:399-409,444-445`).

### 1.5 CSS / design language

Everything is the Phosphor Terminal palette expressed as Tailwind
tokens — `bg-base-950/900`, `border-base-800`, `text-text-{100..600}`,
`text-amber-{200,300,400,500}`, `text-xs` / `text-[10px]`, `font-mono`,
`tabular-nums`. Those tokens are STILL MAINTAINED in the fresh tree:
`resources/public/css/input.css:67-102` defines `--color-base-950
#0d0d0c` … `--color-text-700`, `--color-signal #f0b429`, and the
agent-facing safelist at `input.css:28-52`.

Caveat for the rebuild: the safelist is deliberately tight. Classes the
old bar used that are NOT currently safelisted include
`bg-amber-800/60`, `bg-base-700/70`, `hover:bg-amber-700/70`,
`text-[10px]`, `text-amber-{50,200}`, `animate-pulse`, `animate-appear`,
`w-px`, `-translate-x-1/2`, `z-10`, `pointer-events-none`. Since the
debug view is FIRST-PARTY source under `src/`, `@source
"../../../src/**/*.clj"` (`input.css:13-15`) scans it and the classes
emit — the safelist only governs agent-authored runtime hiccup. No
safelist edit is required as long as the bar is written in first-party
Clojure source and not generated from database strings.

The view also injected an inline `<style>` block for markdown +
highlight.js color bending (`debug.cljs@f90019ffc:583-606`). That is
DEAD — `resources/public/css/input.css:472-501` now owns markdown/table/
code styling in the maintained sheet.

## 2. The bottom bar — exactly what it showed

### 2.1 Its own docstring states the thesis

`debug.cljs@f90019ffc:68-76`:

```clojure
;; Context bar — the per-block token + cache-line audit instrument.
;; DISPLAY-ONLY, all DERIVED at render time: per-block estimated tokens
;; (seon.ai.tokens/estimate over the SAME block texts the LLM prompt is
;; built from — one render, two consumers), the STRUCTURAL cache breakpoint
;; (end of the byte-stable prefix = after :namespaces), and the LIVE cached
;; extent (exact, off the last turn's persisted :seon.agent.turn/llm-usage).
;; The divergence between the two markers is the point.
```

"The divergence between the two markers is the point" is the whole
feature in one sentence: the bar exists to show where the prompt SHOULD
be cacheable versus where the provider actually cached.

### 2.2 The model

`context-bar-data`, `debug.cljs@f90019ffc:107-156`:

```clojure
{::segments [{::name ::tokens ::stable?} …]  ; render order
 ::total-tokens N
 ::cache-line-tokens N        ; structural breakpoint (end of prefix)
 ::live-cached-tokens N|nil   ; exact, from last turn's usage
 ::provider-shape kw|nil}
```

with the derivations:

```clojure
body-segs (mapv (fn [{nm :seon.agent.ctx/name priority :seon.agent.ctx/priority
                      txt :seon.render/text}]
                  (let [t (or txt "")]
                    {::name    nm
                     ::tokens  (tokens/estimate t)
                     ::stable? (<= (or priority js/Number.MAX_SAFE_INTEGER)
                                   (ctx/cache-breakpoint))}))
                block-texts)
body-total (reduce + 0 (map ::tokens body-segs))
assembly-tokens (max 0 (- total-tokens body-total))
segs (cond-> body-segs
       (pos? assembly-tokens)
       (conj {::name :prompt-assembly ::tokens assembly-tokens ::stable? false}))
last-stable-idx (->> (map-indexed vector segs) (filter (comp ::stable? second))
                     (map first) (reduce max -1))
cache-line (->> segs (take (inc last-stable-idx)) (map ::tokens) (reduce + 0))
usage (try (ctx-usage/extract (:seon.agent.turn/llm-usage
                               (ctx/current-turn {:seon.db/db dbv :seon.agent/id agent-id})))
           (catch :default _ nil))
```

Four things worth naming:

- **The estimator is `seon.ai.tokens/estimate`** — confirmed at the
  call site above, and it is the same `chars/4` heuristic that survives
  today at `src/seon/ai/tokens.cljc:28-40` (`chars-per-token 4`,
  `(quot (count text) 4)`).
- **`:prompt-assembly`** is a synthesized residual segment: whole-prompt
  estimate minus the sum of the block estimates, so the bar's widths sum
  to the real prompt and the glue (separators, system framing) is
  visible rather than silently missing. This is an honest-accounting
  trick worth keeping.
- **`::stable?` was derived from block priority vs the configured cache
  breakpoint** (`ctx/cache-breakpoint`, a `:seon.agent.ctx/cache-breakpoint`
  datom, `src-old/seon/agent/ctx.cljc:104,313-314,1762-1763`) — NOT a
  hand list. The peak version `bc13d9a49` DID use a hand list
  (`stable-section-names #{:soul-system :system :namespaces}`) and
  `f90019ffc` replaced it with the derived rule. That is the correct
  direction of travel and the rebuild must not regress it.
- **`::live-cached-tokens` came from the LAST PERSISTED TURN**, via
  `seon.agent.ctx.usage/extract` over `:seon.agent.turn/llm-usage`. The
  usage namespace normalizes provider shapes and survives at
  `src-old/seon/agent/ctx/usage.cljc:11-57` — `::total ::cached ::output
  ::provider-shape [:enum :openai-compat :anthropic]`, projected from
  named turn attributes `:seon.agent.turn.usage/{prompt,completion,cached,
  input,output,cache-read-input,cache-creation-input}-tokens`.

### 2.3 The stat line above the bar

`context-bar-fragment`, `debug.cljs@f90019ffc:501-537`:

```clojure
[:div {:id (context-bar-id agent-id)
       :class "shrink-0 border-t border-base-800 bg-base-900 px-2 pt-5 pb-1.5"}
 [:div {:class "flex flex-wrap items-center gap-x-3 gap-y-0.5 mb-1 text-[10px] font-mono text-text-400"}
  [:span {:class "text-text-200"} "context"]
  [:span {:class "text-amber-400"}  (str "~" total-tokens " tok total")]
  [:span {:class "text-amber-200"}  (str "cache-line ~" cache-line-tokens " tok (after :namespaces)")]
  (if live-cached-tokens
    [:span {:class "text-emerald-300"}
     (str "live cached " live-cached-tokens " tok"
          (when provider-shape (str " · " (clojure.core/name provider-shape))))]
    [:span {:class "text-text-600 italic"} "no live usage yet"])
  [:span {:class "shrink-0 text-text-600"} "stable prefix amber · volatile tail grey"]]
```

So the readouts riding the bottom bar were, left to right:

| readout | value | source |
|---|---|---|
| label | `context` | literal |
| total | `~N tok total` | `:seon.render/token-estimate` of the whole prompt |
| structural cache line | `cache-line ~N tok (after :namespaces)` | cumulative tokens of the stable prefix |
| live cached extent | `live cached N tok · openai-compat` | last turn's normalized usage + provider shape |
| legend | `stable prefix amber · volatile tail grey` | literal |

Note `"(after :namespaces)"` is HARDCODED PROSE that no longer matched
the derived breakpoint after `f90019ffc` changed `::stable?` to read
priority. That is a lying label — the rebuild must derive the phrase
from the last stable segment's name, or drop it.

### 2.4 The bar itself — flex-grow weights, not percentages

`bar-segment`, `debug.cljs@f90019ffc:458-478`:

```clojure
(defn- bar-segment
  "One block segment of the stacked bar. Width is a flex-grow weight
   (∝ tokens). Stable-prefix blocks read amber (the cached prefix);
   volatile-tail blocks read cooler. The block name + token count
   show inline when the segment is wide enough; always in the title."
  [{::keys [name tokens stable?]} total]
  (let [pct (if (pos? total) (* 100.0 (/ tokens total)) 0)
        wide? (>= pct 6)]
    [:div {:class (str "relative h-full flex items-center justify-center "
                       "overflow-hidden border-r border-base-950 "
                       (if stable? "bg-amber-800/60 hover:bg-amber-700/70 "
                                   "bg-base-700/70 hover:bg-base-600/80 "))
           :style (str "flex: " (max 0.01 tokens) " 1 0; min-width: 2px;")
           :title (str (clojure.core/name name) " · ~" (fmt-int tokens) " tok · "
                       (.toFixed pct 1) "%"
                       (when stable? " · cached prefix"))}
     (when wide?
       [:span {:class (str "px-1 truncate text-[10px] font-mono "
                           (if stable? "text-amber-50" "text-text-200"))}
        (str (clojure.core/name name) " " tokens)])]))
```

Answers to the specific questions:

- **How blocks were labelled**: bare `(name block-name)` — `:namespaces`
  → `namespaces`, and per-namespace splits `:namespaces/seon.db` →
  `seon.db`. Inline label + raw token count shown **only when the
  segment is ≥ 6% of total width**; below that the segment is a bare
  colored sliver with a 2px floor.
- **On hover**: the native `title` tooltip — `"<name> · ~3,214 tok ·
  12.4% · cached prefix"`. Plus a background lightening
  (`hover:bg-amber-700/70` / `hover:bg-base-600/80`). **There was NO
  click handler on a segment.** The bar was read-only; navigation to a
  block's text was via the left pane's `<details>`, not the bar. That is
  the one clear gap the rebuild should close (see §5).
- **Width math**: `flex: <tokens> 1 0` — the browser does the
  proportional layout, so no percentage arithmetic is serialized and the
  bar re-lays out correctly at any width. Keep this exactly.

### 2.5 The two markers

`cache-marker`, `debug.cljs@f90019ffc:480-499`, absolutely positioned
over the bar at a percentage offset, with a label floated above
(`-top-4`, hence the parent's `pt-5`):

```clojure
[:div {:class "absolute top-0 bottom-0 pointer-events-none z-10"
       :style (str "left: " (min 100.0 (max 0.0 pct)) "%;")}
 [:div {:class (str "absolute top-0 bottom-0 w-px "
                    (if structural? "bg-amber-300" "bg-emerald-400"))
        :style (when structural?
                 "background-image:repeating-linear-gradient(to bottom,#fcd34d 0 3px,transparent 3px 6px);")}]
 [:div {:class "absolute -top-4 whitespace-nowrap text-[10px] font-mono px-1 rounded …"} label]]
```

Rendered as (`debug.cljs@f90019ffc:533-537`):

```clojure
(cache-marker struct-pct :structural (str "▏ cache-line " cache-line-tokens))
(when live-pct
  (cache-marker live-pct :live (str "live " live-cached-tokens " ▏")))
```

Dashed amber = where the composer thinks the byte-stable prefix ends.
Solid emerald = where the provider actually cached. The label glyphs
`▏` point INWARD from each side so two adjacent markers stay legible.

Empty state, `debug.cljs@f90019ffc:531-532`:

```clojure
[:div {:class "flex items-center px-2 text-[10px] font-mono text-text-600"} "no context yet"]
```

## 3. Other session-useful readouts

### 3.1 The per-agent header

`header-fragment`, `debug.cljs@f90019ffc:222-241`:

```clojure
[:header {:id (header-id agent-id)
          :class "flex items-center gap-3 p-2 border-b border-base-800 bg-base-900"}
 [:span {:class "text-xs font-mono text-text-200"} "agent " agent-id]
 (if (= :running state)
   [:span {:class "inline-flex items-center gap-1.5 text-xs font-mono text-amber-400"}
    [:span {:class "w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse"}]
    (str "thinking — turn " (inc turns))]
   (comp/status-dot state))
 [:span {:class "text-xs text-text-400"} (str "turn " turns)]
 (activity-sparkline turn-durs)
 [:span {:class "text-xs text-text-500 ml-auto"} (str "~" token-est " tokens")]
 [:a {:href (str "/agent/" agent-id) …} "← agent"]
 [:a {:href "/" …} "← all agents"]]
```

Readouts: agent id · derived run state (dot, or a pulsing "thinking —
turn N+1") · turn count · sparkline · total token estimate ·
breadcrumbs. `token-est` here is the SAME number the bar shows as
`~N tok total` — duplicated on purpose, so the header answers "how big"
without the operator looking down.

### 3.2 The sparkline was already dead

`activity-sparkline` (`debug.cljs@f90019ffc:205-220`) renders the last
12 turns' eval time as bare divs with a `title` of `"turn N · 1.2s"`,
using `fmt-ms` (`:198-203`, `1234 → "1.2s"`, `53 → "53ms"`). But its
input was hardcoded empty from the earliest recoverable version:

`debug.cljs@f90019ffc:175` — `turn-durs []`, and identically at
`1eec28dc8:222`. **The sparkline never displayed anything in any
recoverable commit.** It is a design intent, not a working feature. Do
not "port" it; if per-turn timing is wanted, derive it fresh from
receipt facts.

### 3.3 The global header

`src-old/seon/ui/header.cljs:20-46` — brand name, agent count, `agent`/
`agents` pluralization, a `⛁ data` link, a `home` link, and one amber
dot that lights when any agent is running. Fixed, `z-20`, with a
2.25rem spacer sibling. Small and good.

### 3.4 What was NOT displayed

Explicitly absent from every recoverable version: model name,
temperature, max tokens, provider base URL, latency, cost, eval counts,
error counts, run id, basis-t. The ONLY provider-side fact on screen was
`::provider-shape` (`:openai-compat` / `:anthropic`) appended to the
live-cached readout — a wire-shape, not a model name.

The turn-level projection that COULD have fed such readouts existed
next door and still does: `src-old/seon/agent/debug.cljs:74-135`
(`turn` — verbatim prompt + reply from blobs, `::prompt-tokens`,
`::reply-tokens`, `::usage/line`, `:seon.agent.turn/usage-estimated?`)
and `:137-210` (`turn-diff` — `::basis-t-delta`, `::prompt-token-delta`,
`::prompt-lines-added/-removed`, described as a "cache-stability
instrument: frozen bytes that moved show up here"). None of it was ever
wired into the two-pane view.

## 4. Hand-built vs derived

Derived, correctly:

- per-block token counts — `tokens/estimate` over the same texts the
  prompt was built from, one render two consumers (`:107-128`);
- the `:prompt-assembly` residual (`:128-134`);
- `::stable?` from `:seon.agent.ctx/priority` ≤ `ctx/cache-breakpoint`
  (`:125-126`) — a config datom, replacing the earlier hand list;
- the structural cache line as a cumulative sum over segments in render
  order (`:137-145`);
- the live cached extent from persisted turn usage (`:146-155`);
- agent state and turn count via `derive/derive-state` /
  `derive/agent-turn-count` (`:176-177`) — never a stored status;
- segment widths delegated to flexbox (`:471`);
- the whole view re-derived per SSE push with equality/stable-id morphs;
  `render-observed` / `capture-reads` bounded the wake set (`:966-987`).

Hand-built, and each one is a defect to not repeat:

- `stable-section-names #{:soul-system :system :namespaces}` — the hand
  list at `bc13d9a49`, already fixed by `f90019ffc`; the lesson is
  recorded, not the code;
- `"(after :namespaces)"` — hardcoded prose that outlived the rule it
  described (`:520`);
- the `:namespaces` explosion `expand-namespaces-block` (`:78-105`) —
  regex-splitting a rendered blob on `^; namespace ` to recover
  structure the composer had already thrown away. Honest about being
  display-only ("The LLM prompt is untouched"), but it is text
  archaeology over a lost boundary. The fresh tree does not need it:
  contributions are already per-block rows;
- `turn-durs []` — a live-looking widget with no data (§3.2);
- `chat-bar-fragment`'s inline `onsubmit` JS string (`:539-581`) — a
  hand-rolled `fetch` with string-concatenated error handling, from
  before Datastar signals;
- the inline markdown/highlight `<style>` (`:583-606`) — superseded by
  the maintained sheet.

## 5. Adopt / drop for the rebuild under ruling #16

Ruling #16 (`docs/prds/sci-execution-runtime/plan/README.md:1576-1587`):
LEFT = exactly the agent's AI context, byte-exact from the same walk
projection assembly produces; RIGHT = ALL walked units rendered as HTML
including floor renders that are not in the AI context; a unit may be
ai-only, html-only, or both, decided by what its renderers emit
(nil-punning omission), never by lists. Ruling #17 (`:1588-1594`) names
the screen a namespace page; the debug variant is the same walk, two
panes.

Current state to build onto: `GET /agent/{id}/debug` already exists and
already returns 200, but it is ONE pane — the generic-entity drill
(`src/seon/render/web.clj:399-411` `debug-shell`, dispatched at
`:955-965`, backed by `generic-entity` at `:302-360`). There is no left
pane, no bottom bar.

### Ports as CSS/layout, essentially verbatim

| old piece | citation | note |
|---|---|---|
| the five-row flex column + `grid-template-columns: 1fr 1fr` | `:631-643` | inline grid style, `h-0 min-h-0` on the grid, `overflow-hidden` everywhere but the two pane bodies |
| pane captions naming the projection keyword | `:375-376,417-418` | change the words to the new projection names; keep the idea |
| stable per-region ids | `:194-196,456` | required by the current per-block morph writer (`web.clj` `changed`, `:392-412`) |
| the bar container geometry | `:512-527` | `shrink-0 border-t … px-2 pt-5 pb-1.5`, `relative h-6 w-full flex … overflow-visible` — `pt-5` exists to clear the markers' `-top-4` labels |
| `bar-segment` flex-weight sizing + `min-width:2px` + `wide?` 6% label threshold | `:458-478` | the single most portable piece; pure CSS reasoning, no CLJS |
| `cache-marker` absolute overlay | `:480-499` | dashed-vs-solid, `pointer-events-none z-10` |
| the `fmt-int` comma grouping and `~N tok` phrasing | `:243-251` | house rule: sizes are estimated tokens |
| the stat-line row above the bar | `:515-526` | a flex-wrap `text-[10px]` strip |
| the `<details>` + `<summary>` per unit, summary = `name (N tokens)` | `:350-363` | the summary token count is the second half of the owner's memory |
| empty/honest states | `:442-443,531-532` | "no context yet", "no … declares an HTML twin" |
| Phosphor tokens | `input.css:67-102` | unchanged and maintained |

### Ports as DERIVATIONS onto fresh owners

The old bar's model already exists in the fresh tree as durable facts —
this is the good news, and it means the bar becomes a query, not an
instrument bolted onto a renderer.

| old derivation | fresh owner | note |
|---|---|---|
| per-block `tokens/estimate` | `seon.context/contribution-tokens`, `src/seon/context.clj:141-146`, stored as `:seon.context.contribution/tokens` (`:86-88`) | already computed per contribution at capture time; the bar reads rows instead of re-estimating |
| block name / order | `:seon.render.block/name`, `:seon.context.contribution/position` (`src/seon/context.clj:84-86`) | render order is a stored integer; no re-sorting |
| `::stable?` binary + `cache-breakpoint` | `:seon.context.contribution/band`, enum `[:anchor :program :authored :continuity :dynamic]` (`resources/seon/schema/block.edn:48`; schema comment `resources/seon/schema/context.edn:78-81`: "bands never cross; within a band, priority is a prior") | **strictly better than the old binary** — five ordered bands give a five-step color ramp static→volatile and the structural cache line falls at a named band boundary, derived, no prose |
| `::total-tokens` | `:seon.context.capture/prompt` through `tokens/estimate`, or the sum of contribution rows | the `:prompt-assembly` residual trick still applies and still earns its place |
| the whole-prompt bytes for the LEFT pane | `:seon.context.capture/prompt` (`src/seon/context.clj:119-120`) — the exact text committed BEFORE the provider call | this is what makes "byte-exact" constructional rather than aspirational, exactly as ruling #16 requires |
| `::live-cached-tokens` + `::provider-shape` | the surviving normalizer `src-old/seon/agent/ctx/usage.cljc:11-57` (`::total ::cached ::output ::provider-shape`) over whatever the fresh attempt row records | design survives; re-derive against the fresh attempt/receipt attributes rather than porting the file |
| `:seon.context.contribution/hash` | `src/seon/context.clj:133-139` | NEW capability the old bar lacked: a segment can show whether its bytes CHANGED since the previous capture — the `turn-diff` "frozen bytes that moved" instrument (`src-old/seon/agent/debug.cljs:177-210`) rendered inline, which is what the cache markers were groping toward |
| agent state / turn count | derived from run + receipt facts (`walk/refs`, `src/seon/render/walk.clj:427`) | never a stored status |

### Dies

- `expand-namespaces-block` (`:78-105`) — regex re-splitting of a
  rendered blob. Contribution rows are already per-block; if a
  namespaces block is still one 49k-token segment, that is a
  BLOCK-GRANULARITY question for the composer, not a display hack.
- `activity-sparkline` + `fmt-ms` (`:198-220`) — never had data. If
  per-run timing is wanted, derive from receipts fresh.
- `chat-bar-fragment` (`:539-581`) — superseded by the current message
  bar and its `data-on:submit` signal handling
  (`src/seon/render/web.clj:119-180`, and `datastar-web-ui` skill).
  Hand-rolled `fetch` strings do not come back.
- `page-style-css` markdown/hljs block (`:583-606`) — the maintained
  sheet owns it (`input.css:472-501`).
- The whole view-unit / `unit-toggle-expression` / `::datastar/token`
  activation protocol (`:253-334,949-1000`) — that was the pod's
  per-unit producer registry. The current pipeline is complete
  snapshots, per-block byte diffing, and one `mult`
  (`src/seon/render/web.clj:229-285,392-412`); `<details>` becomes
  ordinary HTML whose open state is a per-tab transient signal, matching
  ruling #8's "show everything" checkbox pattern.
- `agent-exists?` as written (`:55-66`) — the fresh handler already has
  its own (`src/seon/render/web.clj:956`).
- Anything `/data`-flavored in the file (`:676-909,1002-1128`) — the
  fresh `/data` drill and `seon.render.data` own it.

### The one thing to ADD that the old view lacked

The bar was read-only: no click target, no link between a segment and
its text. In the two-pane rebuild the bar sits under BOTH panes and each
segment names a unit that exists in both, so a segment click should
scroll/open that unit in the left pane and its twin in the right —
turning the bar from an audit readout into the view's navigation
control. That is one `data-on:click` setting a per-tab signal, no new
mechanism, and it is the natural completion of the layout the owner
remembers.

## Recovery index

| what | where |
|---|---|
| richest full version | `git show f90019ffc:src/seon/web/debug.cljs` (1128 lines, 2026-07-16) |
| peak line count / pre-registry SSE | `git show bc13d9a49:src/seon/web/debug.cljs` (1196 lines) |
| context bar origin | `921f9fba6` `feat(agent-fsm): debug-panel context bar + seon.ai.tokens + usage extractor` |
| gutting commit | `9d9e870bd` `refactor web views around coordinate-pinned projections` (1128 → 190 lines) |
| surviving stub | `src-old/seon/web/debug.cljs` (304 lines) — no layout, do not quarry |
| turn/turn-diff projections | `src-old/seon/agent/debug.cljs:74-210` |
| usage normalizer | `src-old/seon/agent/ctx/usage.cljc:11-57` |
| global header | `src-old/seon/ui/header.cljs:20-48` |
| maintained Phosphor tokens + safelist | `resources/public/css/input.css:28-52,67-102` |
| fresh per-contribution facts | `src/seon/context.clj:76-146`; `resources/seon/schema/context.edn:59-118`; `resources/seon/schema/block.edn:48` |
| fresh debug route (one pane today) | `src/seon/render/web.clj:399-411,955-965`, `generic-entity` `:302-360` |
| rulings | `docs/prds/sci-execution-runtime/plan/README.md:1576-1594` (#16, #17), `:1395-1415` (#8 addendum) |
