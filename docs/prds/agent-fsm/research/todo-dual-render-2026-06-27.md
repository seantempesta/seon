---
type: research
status: active
tags: [research, agent, web, dashboard]
---

# `my.todo` dual render — the agent's live task-tracker (ai + html)

> Design-research for `feature/agent-fsm`. DESIGN ONLY. Present-tense (the
> system as it is when built). Sits ON the hierarchical+dependency `my.todo`
> design ([[hierarchical-todo-deps-2026-06-27]]) and the dual-view render engine
> (`src/seon/render.cljs`). Every render claim is grounded by `file:line`.

## TL;DR — the design in seven lines

1. ONE derived snapshot fn `my.todo.internal/todo-view` reads the agent's task
   state into a plain-data map; the ai block and the html block render the SAME
   map (dual-render consistency — the block carries both `:seon.render/ai` and
   `:seon.render/html`, exactly like the transcript block).
2. The block makes **"what's left" exhaustive and "what's done" bounded**: the
   open tree lists EVERY open leaf (never aged out — it is the source of truth
   for remaining work); finished work ages out into COUNTS plus a small
   recently-done window.
3. **Window = last-N done leaves by `:my.todo/completed-at` desc (N=5),
   partitioned by the current run's `started-at` (`─ since you woke ─` /
   `─ earlier ─`).** Bounded, run-aware, pure query, nothing stored.
4. **Trackability without the transcript falls out**: open tree = "what's
   left" (complete), the roll-up bar = "what's done" (complete COUNT), the
   recently-done window = "what I just finished" (named continuity). The
   transcript can scroll away entirely and the agent loses none of these.
5. **Re-doing is structurally impossible**: `next` is derived from
   `:my.todo/status :open`, so a done leaf is never offered as work — it is
   either listed in recently-done, or counted in a roll-up, never silently gone.
6. **Liveness is unmistakable**: the ai header says it re-derives every turn
   ("where you are RIGHT NOW, not a log"); the html header carries a pulsing
   signal dot + a `live` cue; the progress bar is a CHARACTER bar (`▓░`) — byte
   identical across both renders and on-theme for a terminal.
7. **Self-healing**: the block VANISHES (ai → `""`) when there is no open work
   AND no this-run completions — the same derive-everything contract as today's
   `open-todos-block` (nothing stored, nothing to acknowledge).

---

## 0. Where this sits — the block + render facts it grounds on

A context section is a `:seon.agent.ctx/block` map (`src/seon/agent/ctx.cljs:110-115`):
`{:seon.agent.ctx/name, :seon.agent.ctx/priority, :seon.render/ai?,
:seon.render/html?}`. Blocks are SEED-COPIED into the agent's own
`:seon.agent/ctx` at creation and priority-sorted (no render-time merge). The
todo block is the `:open-todos` seed at priority 45
(`agent/ctx.cljs:1664-1665`) — today ai-only; this design adds the html twin and
rewrites the ai render over the hierarchical model.

A block render fn is invoked by the recursive engine `seon.render/render`
(`render.cljs:636-658`) with the injected input
`{:seon.db/db, :seon.agent/id, :seon.render/node <block map>,
:seon.render/render <handle>, :seon.render/slot <handle>}`. The ai view returns a
**String**, the html view returns **bare hiccup** — exactly the contract the
transcript twin already honors (`transcript-block` → String,
`transcript-block-html` → `[:div …]` bare hiccup; `agent/ctx/transcript.cljs:341,425`).

The canonical dual-render seed is the transcript
(`agent/ctx.cljs:1681-1683`): one block, two slots, two fns. The todo block
copies that shape:

```clojure
;; in default-seed-blocks (replaces the ai-only :open-todos line)
{:seon.agent.ctx/name :my.todo :seon.agent.ctx/priority 45
 :seon.render/ai   'my.todo.internal/todo-block
 :seon.render/html 'my.todo.internal/todo-block-html}
```

Glyph set (one vocabulary, both renders — harmonized with the design system's
dot+text status, `design-system.md:223-231`):

| glyph | meaning | color (html) |
|---|---|---|
| `▸` | ready-next — an open, unblocked leaf (the do-now) | `text-signal` (amber) |
| `◐` | active — a parent that still has open work in its subtree | `text-signal` (amber) |
| `○` | blocked — an open leaf waiting on a dependency | `text-text-500` (dim) |
| `●` | done — a completed leaf | `text-success` (green) |

---

## 1. ai render (agent-facing)

### 1.1 Spec

The ai render rides as `;`-comment Clojure (house rule: the whole context reads
as an eval'able REPL; `CLAUDE.md` comment-levels — `;` prose, `;;` block,
`;;;` runtime-structure). Order, top to bottom:

1. **Live header** (`;;` runtime banner) — states plainly that this is
   re-derived NOW, that "what's left" is exhaustive, and that finished work ages
   into the counts. This is the cue that makes the block unmistakably a view of
   the present, not an accumulating log.
2. **Progress line** — a character bar `▓▓▓▓▓▓▓░░░` + `done/total` + the
   sub-counts `(N ready · M blocked)`. Derived from the agent-wide leaf roll-up.
3. **`▸ NEXT`** — the focus queue: ready leaves oldest-first (capped ~5), with
   the one-liner reminder to `done!` when finished. This is the single thing the
   agent acts on (hierarchical-todo §2c `next-ready`).
4. **`◐ ACTIVE PLAN`** — the open forest: roots that still have open work, with
   DONE descendant leaves COLLAPSED into the parent's `done/total` roll-up and
   only the open frontier (ready `▸` / blocked `○`) listed individually. Blocked
   rows name their unmet deps. Dependencies are thus visible as structure.
5. **`● RECENTLY DONE`** — the windowed completions (last-5 by `completed-at`),
   split by the `─ since you woke ─` / `─ earlier ─` divider, with a
   `(+K older done — counted above)` tail so nothing vanishes silently.

When there is no open work AND no this-run completion, the whole block renders
`""` and VANISHES (self-healing; same as `open-todos-block`,
`agent/todo/internal.cljs:93-103`).

**Times are ABSOLUTE stored values (or omitted), never relative `now`.** Today's
`open-todos-body` uses `age-str` ("7m"/"3h", `agent/todo/internal.cljs:64-70`) —
a relative age recomputes every turn and BUSTS the cached prefix for a block that
sits at priority 45 (above the transcript breakpoint). The rewrite drops relative
ages from the ai view: the `─ since you woke ─` divider supplies recency
categorically (no moving byte), and any time shown is the stored
`:my.todo/completed-at` rendered `HH:mm` (byte-stable, the transcript's own rule —
`agent/ctx/transcript.cljs:100-118`). The block's bytes then change iff the task
state changes — which is the CORRECT cache semantics (a completion SHOULD bust the
prefix). **Smell flagged:** the current `age-str` relative-time render is a
turn-to-turn cache-buster; the rewrite fixes it.

### 1.2 SAMPLE ai text

```text
;; ── my.todo · your live task state — re-derived THIS turn ──────────────
; This is where you are RIGHT NOW, not a log. "What's left" below is the
; complete remaining work; finished items age out into the counts. Track
; from THIS block — the transcript scrolls away as turns accumulate.
;
; progress  ▓▓▓▓▓▓▓░░░  7/11 done   (2 ready · 1 blocked)
;
; ▸ NEXT — ready to work now (oldest first):
;   f-2   process notes-b.md
;   f-3   process notes-c.md
;   ; do one, then (my.todo/done! {:my.todo/id "f-2"}) when finished.
;
; ◐ ACTIVE PLAN:
;   ◐ plan-1  Process inbox → KB                        4/6 done
;       ▸ f-2   process notes-b.md
;       ▸ f-3   process notes-c.md
;       ○ syn   synthesize findings        — blocked on f-2, f-3
;   ◐ brief   Vendor brief                              1/3 done
;       ○ write  write the brief           — blocked on research
;
; ● RECENTLY DONE — last 5 (+2 older done, counted above):
;   ─ since you woke ─
;   ● f-1   process notes-a.md
;   ● research  research vendor X
;   ─ earlier ─
;   ● a     set up the inbox watcher
;   ● b     register :my.kb schema
;   ● c     wire the live tile
```

What the agent reads off this with ZERO transcript:

- "What's left?" → the ACTIVE PLAN tree (exhaustive: every open leaf, ready or
  blocked) + the `2 ready · 1 blocked` counts.
- "What do I do next?" → `▸ NEXT`, one ready leaf.
- "What did I just finish?" → RECENTLY DONE, `─ since you woke ─` partition.
- "Am I making progress?" → `7/11 done`, the bar, and per-parent `4/6 done`.

A done leaf (e.g. `f-1`) never appears in `▸ NEXT` (that queue is `:status :open`
only), so the agent cannot re-do it; it shows in RECENTLY DONE while fresh, then
ages into `+K older done` and the `7/11` count — bounded, never silent.

---

## 2. Aging-out / windowing — derived, not stored

### 2.1 The window definition (and why this one)

**The recently-done window = the agent's done LEAVES, ordered by
`:my.todo/completed-at` descending, take N (default 5). The current run's
`:seon.agent.run/started-at` partitions the listed items into `─ since you woke ─`
and `─ earlier ─`. Everything past N is summarized as a count.**

Bounded list + run-aware divider, justified against the alternatives:

| candidate | problem | why last-N wins |
|---|---|---|
| **completed-this-run** (all done since `started-at`) | UNBOUNDED — a marathon run completing 50 leaves blows the token budget; defeats "can't show infinite". | last-N caps the LIST; the run boundary becomes a DIVIDER, not the window. Best of both. |
| **since-T wall window** (e.g. last 1h) | clock-paced, arbitrary; a fast agent finishes 20 in an hour. | last-N is WORK-paced — it tracks the agent's actual rhythm, not the clock. |
| **last-N (chosen)** | N is arbitrary; a turn that closes 8 leaves shows only 5. | the 3 lost names are not lost data — they're in `+K older done`, the parent roll-up, and `my.todo/tree {:all? true}`. Acceptable: the NAMES of old completions are a nicety; the COUNT is exhaustive. |

There is **NO stored `:archived?` / `:aged-out` flag** — the window is a pure
`order-by` + `take`, re-derived every turn (derive-everything; self-healing). A
reopened todo (`my.todo/reopen!`, hierarchical §4) drops out of the window the
instant its `:status` flips back to `:open` and re-enters the open tree — nothing
to clear.

### 2.2 The queries (grounded on the hierarchical model)

All lean on `my.todo/rules` (the shared `descendant`/`leaf`/`open-work`/`blocked`/
`ready` rule set, hierarchical §2) passed as `%`, and the verified datahike
capabilities the hierarchical doc grounds (`:order-by` query.cljc:102/2828-2860;
the `leaf` rule via `not-join`; relation-find counts, NOT collection-find which
dedups — query.cljc:255-261).

```clojure
;; (a) the windowed completions — last-N done LEAVES, newest first.
(defn recent-done [db agent-eid n]
  (->> (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find ?id ?title ?completed
                    :in $ % ?a
                    :where
                    [?t :my.todo/agent ?a]
                    [?t :my.todo/status :done]
                    (leaf ?t)                       ; real work, not a rolled-up parent
                    [?t :my.todo/id ?id]
                    [?t :my.todo/title ?title]
                    [?t :my.todo/completed-at ?completed]]
                  :seon.db/args     [my.todo/rules agent-eid]
                  :seon.db/order-by [[2 :desc]]})   ; completed-at desc (hier §2c)
       (take n)
       (mapv (fn [[id title c]]
               {:my.todo/id id :my.todo/title title :my.todo/completed-at c}))))

;; (b) the agent-wide roll-up — the analog of hier §2a rollup, over ALL the
;;     agent's leaves (relation find ?l ?s — NOT collection, which dedups).
(defn agent-rollup [db agent-eid]
  (let [rows (db/query {:seon.db/db db
                        :seon.db/query
                        '[:find ?l ?s :in $ % ?a
                          :where [?l :my.todo/agent ?a] (leaf ?l)
                                 [?l :my.todo/status ?s]]
                        :seon.db/args [my.todo/rules agent-eid]})]
    {:my.todo/total (count rows)
     :my.todo/done  (count (filter #(= :done (second %)) rows))}))

;; aged-out = (grand done) − (count shown) → the "+K older done" tail.
```

The roll-up is exhaustive (every leaf the agent owns), so `done/total` and
`aged-out` are never approximations — the window can drop names without dropping
the count.

---

## 3. Trackability without the transcript

The owner's hard constraint: as turns accumulate, the transcript rolls out of the
context window, so the agent must answer "what did I recently finish?" and
"what's left?" from the **block alone**. The design satisfies this by construction
— three derived surfaces, each complete on its own axis:

| question | surface | completeness |
|---|---|---|
| "what's LEFT?" | the ACTIVE PLAN open tree (every open leaf, ready or blocked) + `N ready · M blocked` | EXHAUSTIVE — open leaves are NEVER aged out; the tree is the source of truth for remaining work. |
| "what have I DONE (how far)?" | the `done/total` bar + per-parent roll-up | EXHAUSTIVE COUNT — over all leaves; bounded list, unbounded count. |
| "what did I just finish?" | RECENTLY DONE window + `─ since you woke ─` | bounded (last-5), but the OLDER names live in `+K` and `my.todo/tree {:all? true}`. |

**Falsification — "could a completed item silently vanish and get re-done?"**
No, on two independent guarantees:

1. **Structural**: `▸ NEXT` and the open tree both derive from
   `:my.todo/status :open` (`ready` rule). A `:done` leaf is structurally absent
   from "work to do" — it can never be re-offered.
2. **Surfaced**: a freshly-done leaf shows in RECENTLY DONE; an aged one is
   counted in `+K older done` AND the parent's `done/total`. The transition is
   list → count, never list → nothing. (Contrast: if we stored an `:archived?`
   flag and filtered on it, a bug could hide a done item entirely — derive-only
   removes that failure mode.)

The ONE thing the agent genuinely loses without the transcript is the *prose* of
HOW it did a task (the eval sequence). That belongs in the transcript and the KB
(`:my.todo/produced` links the rows the work created, hierarchical §5), not the
todo block — and that is correct separation, not a gap.

---

## 4. html render (user-facing) — a real progress dashboard

### 4.1 Spec

The html twin is the inspector right-pane card the USER glances at to follow the
agent (sibling to `transcript-block-html`). It returns BARE hiccup (NOT the live
tile's compact/expanded contract — that is `:seon.render.live-canvas/content`, a
different surface). It renders the SAME `todo-view` map as the ai block. Layout,
top to bottom:

1. **Header** — `my.todo` label + a **pulsing signal dot + `live`** cue
   (`design-system.md:223-253`, the `● running` + pulse pattern) + `re-derived
   this turn`. This is the visual liveness signal: the user SEES it is current.
2. **Progress bar** — the character bar `▓░` in `text-signal font-mono` + bold
   `7 / 11 done` (`tabular-nums`) + `2 ready · 1 blocked`.
3. **NEXT** — the ready leaves highlighted in amber (`text-signal`, `▸`) — the
   user sees what the agent is about to do.
4. **Plan tree** — indented status rows (`●`/`◐`/`○`/`▸` color-coded), per-parent
   `done/total`, blocked rows showing `waiting on …` (dependency structure made
   visible).
5. **Recently done** — dim green `●` rows with a HUMAN-friendly relative age
   ("2m ago") — relative is FINE here (no LLM cache; the html pane is not the
   cached prompt), the one justified ai/html divergence.

Empty state: a calm `✓ no open tasks` placeholder (the user pane should not be
blank; the ai view vanishes instead — token economy). Mirrors
`transcript-block-html`'s placeholder (`agent/ctx/transcript.cljs:461-462`).

**The progress bar is a CHARACTER bar, not a CSS-width `<div>`** — grounded
choice: the runtime CSS allowlist (the live-tile ns docstring,
`render/live_tile.cljs:67-84`) ships NO arbitrary-width utility and NO
`bg-signal` fill, so a Tailwind-width bar would silently render nothing; a
character bar needs only `font-mono` + `text-signal` (both in the allowlist), is
byte-identical to the ai bar (dual-render consistency), and is the most
phosphor-terminal thing on the page. (An inline-`style` width div is the optional
richer upgrade — inline styles bypass the prebuilt-CSS limit — but the character
bar is the default.)

### 4.2 SAMPLE hiccup (allowlist-only classes)

```clojure
[:div {:class "flex flex-col gap-2 p-3 font-mono text-xs"}

 ;; ── header: label + LIVE pulse + re-derived cue ─────────────────────
 [:div {:class "flex items-center justify-between border-b border-base-800 pb-2"}
  [:div {:class "flex items-center gap-2"}
   [:span {:class "text-text-200 font-semibold tracking-wider uppercase"} "my.todo"]
   [:span {:class "flex items-center gap-1"}
    [:span {:class "text-signal animate-seon-pulse"} "●"]
    [:span {:class "text-text-400"} "live"]]]
  [:span {:class "text-text-500"} "re-derived this turn"]]

 ;; ── progress: character bar + counts ────────────────────────────────
 [:div {:class "flex items-center gap-2"}
  [:span {:class "text-signal"} "▓▓▓▓▓▓▓░░░"]
  [:span {:class "text-text-100 font-bold tabular-nums"} "7 / 11 done"]
  [:span {:class "text-text-500"} "·"]
  [:span {:class "text-signal"} "2 ready"]
  [:span {:class "text-text-500"} "·"]
  [:span {:class "text-text-400"} "1 blocked"]]

 ;; ── NEXT — the focus, highlighted ───────────────────────────────────
 [:div {:class "flex flex-col gap-1 bg-base-900 rounded p-2"}
  [:span {:class "text-text-500 uppercase tracking-wider"} "next"]
  [:div {:class "flex items-center gap-2"}
   [:span {:class "text-signal"} "▸"]
   [:span {:class "text-text-100 truncate"} "process notes-b.md"]
   [:span {:class "text-text-500"} "f-2"]]
  [:div {:class "flex items-center gap-2"}
   [:span {:class "text-signal"} "▸"]
   [:span {:class "text-text-100 truncate"} "process notes-c.md"]
   [:span {:class "text-text-500"} "f-3"]]]

 ;; ── ACTIVE PLAN — tree with status dots + deps + per-parent rollup ───
 [:div {:class "flex flex-col gap-1"}
  [:span {:class "text-text-500 uppercase tracking-wider"} "active plan"]

  [:div {:class "flex items-center justify-between"}
   [:div {:class "flex items-center gap-2"}
    [:span {:class "text-signal"} "◐"]
    [:span {:class "text-text-100"} "Process inbox → KB"]]
   [:span {:class "text-text-400 tabular-nums"} "4/6"]]
  [:div {:class "flex items-center gap-2 px-3"}
   [:span {:class "text-signal"} "▸"]
   [:span {:class "text-text-200 truncate"} "process notes-b.md"]]
  [:div {:class "flex items-center gap-2 px-3"}
   [:span {:class "text-signal"} "▸"]
   [:span {:class "text-text-200 truncate"} "process notes-c.md"]]
  [:div {:class "flex items-center gap-2 px-3"}
   [:span {:class "text-text-500"} "○"]
   [:span {:class "text-text-400 truncate"} "synthesize findings"]
   [:span {:class "text-text-500 italic"} "waiting on f-2, f-3"]]

  [:div {:class "flex items-center justify-between"}
   [:div {:class "flex items-center gap-2"}
    [:span {:class "text-signal"} "◐"]
    [:span {:class "text-text-100"} "Vendor brief"]]
   [:span {:class "text-text-400 tabular-nums"} "1/3"]]
  [:div {:class "flex items-center gap-2 px-3"}
   [:span {:class "text-text-500"} "○"]
   [:span {:class "text-text-400 truncate"} "write the brief"]
   [:span {:class "text-text-500 italic"} "waiting on research"]]]

 ;; ── RECENTLY DONE — windowed, run-partitioned, +K tail ──────────────
 [:div {:class "flex flex-col gap-1 border-t border-base-800 pt-2"}
  [:span {:class "text-text-500 uppercase tracking-wider"}
   "recently done · +2 older"]
  [:span {:class "text-text-500 italic"} "─ since you woke ─"]
  [:div {:class "flex items-center gap-2"}
   [:span {:class "text-success"} "●"]
   [:span {:class "text-text-300 truncate"} "process notes-a.md"]
   [:span {:class "text-text-500"} "2m ago"]]
  [:div {:class "flex items-center gap-2"}
   [:span {:class "text-success"} "●"]
   [:span {:class "text-text-300 truncate"} "research vendor X"]
   [:span {:class "text-text-500"} "8m ago"]]
  [:span {:class "text-text-500 italic"} "─ earlier ─"]
  [:div {:class "flex items-center gap-2"}
   [:span {:class "text-success"} "●"]
   [:span {:class "text-text-300 truncate"} "set up the inbox watcher"]
   [:span {:class "text-text-500"} "1h ago"]]]]
```

The user glances and grasps: a pulsing `live` dot (it is current), `7/11`
progress, two amber `▸` items the agent is about to do, a blocked `○` waiting on
named deps (the dependency structure), and the green `●` trail of what just got
done. That is a genuine dashboard, not a data dump.

---

## 5. Dual-render consistency — ONE query feeds both

Both renders consume ONE derived map from `todo-view`; neither re-queries, so the
ai text and the html card can never disagree about state.

```clojure
;; my.todo.internal — the ONE derived snapshot, pure read, nothing stored.
(defn todo-view
  "The agent's whole task state as plain data — fed to BOTH the ai and html
   renders. run-since = the current run's start instant (nil when idle)."
  [db agent-eid run-since]
  (let [next   (next-ready  db agent-eid)                 ; hier §2c — ready leaves, oldest
        roots  (open-roots  db agent-eid)                 ; roots with open-work (hier §3 + open-work)
        tree   (mapv #(collapse-done (with-rollup db (tree-pull db %))) roots)
        recent (recent-done db agent-eid recent-cap)      ; §2.2(a) — last-N done leaves, desc
        {:my.todo/keys [done total]} (agent-rollup db agent-eid)] ; §2.2(b)
    {:my.todo/next      next
     :my.todo/tree      tree                              ; done-subtrees collapsed to counts
     :my.todo/recent    (mapv #(assoc % :my.todo/this-run?
                                       (and run-since
                                            (>= (.getTime (:my.todo/completed-at %))
                                                (.getTime run-since))))
                              recent)
     :my.todo/rollup    {:my.todo/done done :my.todo/total total
                         :my.todo/aged-out (max 0 (- done (count recent)))
                         :my.todo/ready    (count next)
                         :my.todo/blocked  (blocked-count db agent-eid)}
     :my.todo/run-since run-since}))

(defn- view-for [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [db  (or db @db/*conn*)
        eid (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]}))
        rs  (:seon.agent.run/started-at (derive/current-run db id))] ; run-aware divider
    (when eid (todo-view db eid rs))))

(defn todo-block            ; :seon.render/ai  → String
  {:malli/schema [:=> [:cat :map] :string]}
  [input]
  (let [v (view-for input)]
    (if (or (nil? v) (and (empty? (:my.todo/tree v)) (empty? (:my.todo/recent v))))
      ""                                              ; VANISH — self-healing
      (render-todo-ai v))))

(defn todo-block-html       ; :seon.render/html → bare hiccup
  {:malli/schema [:=> [:cat :map] [:maybe :seon.render.live-canvas/hiccup]]}
  [input]
  (let [v (view-for input)]
    (if (or (nil? v) (and (empty? (:my.todo/tree v)) (empty? (:my.todo/recent v))))
      [:div {:class "text-text-500 italic p-3 text-xs font-mono"} "✓ no open tasks"]
      (render-todo-html v))))
```

`render-todo-ai` and `render-todo-html` are PURE projections of the same `v` — the
glyphs (`▸◐○●`), the character bar, the `done/total`, the window, and the
divider are all read straight from the map. Same state, two views: the invariant
the render engine is built around (`render.cljs:1-27` ns docstring — "two views,
one walker").

`open-roots` (the active forest) is the hierarchical doc's roots query (§3,
`not-join` on `:my.todo/parent`) intersected with the `open-work` rule so a
fully-done root drops out (it has rolled entirely into the grand count). When the
agent never uses `:my.todo/parent`/`:my.todo/depends-on`, every open todo is its
own single-node root — so the view degrades GRACEFULLY to today's flat list, with
the progress bar and recently-done added for free.

---

## 6. Live-clarity framing — making "NOW" unmistakable

The owner's first requirement: the render must make the LIVE, re-derived-every-turn
nature obvious, so the agent reads it as "current state," never as a stale log.
Concrete devices, ai and html:

- **ai header** (literal, every turn): "your live task state — re-derived THIS
  turn … This is where you are RIGHT NOW, not a log." Mirrors the transcript
  masthead's proven framing ("ALWAYS current: it re-derives from the DB every
  turn, so it is never stale", `agent/ctx/transcript.cljs:74-81`) — one house
  voice for liveness across sections.
- **The progress bar moves with reality.** Because the ai render uses stored
  (not relative) times, the block is byte-stable BETWEEN unchanged turns and
  visibly changes (`7/11` → `8/11`, a `▸` row disappears) the turn work
  progresses. The agent perceives advancement as a DIFF in its own context — the
  strongest possible "this is live."
- **`─ since you woke ─`** anchors recency to the run, not the clock — "since you
  woke" is inherently a present-tense, this-session frame.
- **html pulsing `● live` dot** (`animate-seon-pulse`) — the user SEES the pane
  breathing (`design-system.md:336-349` "Liveness — the interface breathes").
- **Vanish-when-clear** — when nothing is open and the run's done, the block is
  gone. Its mere PRESENCE means "there is live work"; its absence means "all
  clear." A stale log can't do that.

---

## How it grounds — the read-first map

| concern | grounded in |
|---|---|
| block shape + dual slots + seed | `agent/ctx.cljs:110-115` (`:seon.agent.ctx/block`), `:1606-1683` (`default-seed-blocks`, transcript = the dual-render exemplar) |
| render input + ai=String/html=hiccup contract | `render.cljs:636-658` (`render`), `agent/ctx/transcript.cljs:341,425` (the twin) |
| today's todo block it replaces | `agent/todo/internal.cljs:50-103` (`open-todos`, `open-todos-block`, the `age-str` smell at `:64-70`) |
| hierarchical model (tree/dep/rules/queries) | [[hierarchical-todo-deps-2026-06-27]] §2 (`rules`, `next-ready`, `rollup`), §3 (reverse-ref pull + roots), §4 (verbs incl `done!`/`reopen!`) |
| run start for the wake divider | `agent/run.cljs:42` (`:seon.agent.run/started-at`), `derive.cljs:70-82` (`current-run`) |
| html allowlist (drives the character bar + class choices) | `render/live_tile.cljs:67-84` (runtime utility vocabulary) |
| Phosphor theme (dot+text, pulse, density, glyphs) | `design-system.md:223-253` (status), `:336-349` (liveness), `:144-152` (density) |
| self-healing / derive-everything | `CLAUDE.md` reactive-context; `architecture.md:178-182` (query empty → surface vanishes) |

## Verdict

The dual render fits the live-context system cleanly — ONE `todo-view` derive
feeds both slots, every surface is a pure query (no stored window/archive flag),
and the block vanishes when work is done. The owner's four asks are met by
construction: (1) liveness is explicit in both the ai header and the html pulse;
(2) trackability-without-transcript holds because the open tree is exhaustive and
the roll-up count is exhaustive, so only OLD completion NAMES age out, never
"what's left" or the done-count; (3) aging-out is a bounded `order-by`+`take` with
a run-partition divider, justified over completed-this-run (unbounded) and
since-T (clock-paced); (4) the html is a genuine dashboard (progress bar, status
tree, visible deps, highlighted next).

Two honest frictions surfaced, both FIXED by the design rather than worked around:

- **Cache-bust smell (existing):** today's relative `age-str` at priority 45
  busts the cached prefix every turn. The rewrite uses stored/absolute times in
  the ai view and the `─ since you woke ─` divider for recency, so the block is
  byte-stable except when state actually changes. Worth a follow-up task to
  retire `age-str` from the ai path.
- **CSS-availability trap:** a Tailwind-width progress div would silently render
  nothing (no arbitrary-width / `bg-signal` in the prebuilt CSS). The character
  bar (`▓░`) sidesteps it AND unifies the two renders. Flagged so the builder
  doesn't reach for `w-[64%]`.

## Cross-links

- [[hierarchical-todo-deps-2026-06-27]] — the tree+dep model + rules + queries
  this render sits on · [[architecture]] (live-context / self-healing) ·
  [[data-model]] §5.3 (the `my.todo` schema) · [[ui]] (block render policy) ·
  [[design-system]] (Phosphor terminal).
