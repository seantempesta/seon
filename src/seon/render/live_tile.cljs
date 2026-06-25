(ns seon.render.live-tile
  "The live tile — the ONE thing an agent is currently conveying to
   its human (a chart, a status, a list — whatever the human asked
   for). One wired value renders at every zoom surface: the root grid
   (compact), the agent view (expanded), and — as a text twin — the
   agent's own context every turn, so the agent always knows what its
   human currently sees.

   ## Wiring the tile

   ONE attr on your agent entity: `:seon.render.live-tile/content`.
   Its value follows the `:seon.render/html` semantics exactly:

   - a LITERAL HICCUP vector for static content — `[:h1 \"hi\"]`
   - a QUALIFIED FN SYMBOL for dynamic content, late-resolved at
     every render via `seon.eval/lookup-value` (core fns and
     fns you define yourself both resolve — same single path).

   Wire it with a raw transact (no sugar — one pattern):

       (seon.db/transact!
         {:seon.db/tx-data
          [{:seon.agent/id (seon.db/current-agent-id)
            :seon.render.live-tile/content 'my.workouts/chart-tile}]})

   ## The twin contract

   A tile FN returns the standard `:seon.render/html-response` map,
   carrying the html twin AND a text twin:

       {:seon.render/hiccup [...]   ;; what the human sees
        :seon.render/ai \"3 workouts this week: Mon 4200kg, …\"}

   The `:seon.render/ai` string is how YOU know what your human sees —
   say what the content MEANS. Your human sees the picture; you see
   your words. A fn that omits the twin gets its hiccup shown to it
   verbatim instead.

   ## Compact vs expanded — tag blocks, never write media queries

   Small-vs-large is core CSS container queries over ONE render.
   Emit both blocks in one document and tag them:

       [:div.seon-tile
        [:div.seon-tile-compact  [:span \"3 workouts this week\"]]
        [:div.seon-tile-expanded [:svg …full chart…] [:table …]]]

   The core shows `.seon-tile-compact` below the breakpoint and
   `.seon-tile-expanded` at or above it (container rules live in
   `resources/public/css/input.css`). Untagged content renders at
   every size. The compact block is HEIGHT-CLAMPED by the core
   (grid tiles are uniform cards; overflow clips) — put a glanceable
   summary there and the full content in the expanded block.

   ## Styling — write SEMANTIC hiccup, zero classes needed

   The core styles plain HTML elements inside your tile (and
   chat/markdown/debug surfaces) — `[:table …]` with `[:thead]`/
   `[:tbody]`, `[:ul …]`/`[:ol …]`, `[:h1 …]`–`[:h4 …]`, `[:p …]`,
   `[:pre [:code …]]`, `[:blockquote …]`, `[:dl [:dt …] [:dd …]]`,
   `[:hr]`, `[:a …]`, `[:strong]`/`[:em]` all render themed with no
   classes. PREFER that: a classless `[:table]` of rows beats a div
   soup of utilities.

   When you do want layout/emphasis control, ONLY this utility
   vocabulary exists at runtime (anything else silently does
   nothing — the CSS is built ahead of time):

   - layout: `flex` `flex-col` `flex-row` `flex-wrap` `flex-1`
     `shrink-0` `grid` `grid-cols-2/3/4` `items-center` `items-start`
     `items-baseline` `justify-between` `justify-end` `justify-center`
     `gap-1..4` `w-full` `h-full` `min-w-0`
   - spacing: `p-0..4` `px-1..4` `py-1..4` `mt-1/2` `mb-1/2`
   - text: `text-xs/sm/base/lg` `text-left/center/right` `font-mono`
     `font-semibold` `font-bold` `italic` `uppercase` `tracking-wider`
     `tabular-nums` `whitespace-pre-wrap` `truncate`
   - color: `text-text-50/100/200/300/400/500` (cream, bright→dim),
     `text-signal` (amber) `text-success` `text-error` `text-warning`
     `text-info` `text-amber-300/400/500` `bg-base-800/850/900/950`
   - borders/overflow: `border` `border-t` `border-b`
     `border-base-700/800` `rounded` `rounded-md` `divide-y`
     `divide-base-800` `overflow-hidden` `overflow-auto`
     `overflow-x-auto`

   ## Tile updates should be RENDERED DATABASE QUERIES

   Transact important findings as linked entities; render by
   reference. A tile fn that QUERIES the store re-derives on a fresh
   pod (session resume works for free) — a hardcoded hiccup snapshot
   of a computed value goes stale and dies with the session. This is
   the reactive-context principle applied to the human surface.

   ## Who calls what

   `seon.render/render-agent-tile` is the one entry point: it resolves
   WHICH value is wired (`::content` → [[welcome]]), invokes it through
   `seon.render/html-render` (the ONE value-or-fn dispatch), and on a
   throw builds a legible error card — a broken tile must never silently
   vanish (vanish = indistinguishable from unwired; banned). The
   validation / resolution / error-card PLUMBING this teaching ns
   describes lives in `seon.render.live-tile.internal` (framework-only,
   not shown to agents); THIS ns is the authoring tutorial + the
   [[welcome]] worked example + [[wiring-source]]."
  (:require
    [seon.db :as db]
    [seon.render.chat :as chat]
    [seon.schema :as schema]))

;; ============================================================
;; The canonical value-or-fn shape — the wiring VOCABULARY an agent
;; uses. This ns loads BEFORE seon.render (which requires it), and
;; register!'s compilability guard rejects forward references — so
;; the ONE definition of the value-or-fn slot shape lives here, and
;; `:seon.render/html` is registered in seon.render BY REFERENCE to
;; `::content` (shared-shape rule: one shape, registered once, every
;; other key references it).
;;
;; PLATFORM LAW (2026-06-11, sci-not-available incident): registered
;; schema forms must be PURE DATA — no `[:fn]`, no function objects,
;; nothing whose form needs evaluation to reconstruct. So `::hiccup`
;; below is the PRAGMATIC STRUCTURAL BOUND (a hiccup element = a vector
;; with a keyword head); deep validation runs at the render boundary in
;; a PLAIN fn (`seon.render.live-tile.internal/valid-hiccup?`).
;; ============================================================

;; The PURE-DATA hiccup bound: a vector with a keyword head. Shallow
;; on purpose — children are `:any` (sanctioned: arbitrary hiccup
;; trees; the deep walk happens at the render boundary via
;; [[valid-hiccup?]] and html->string). Verified against the bridge:
;; `:cat`/`:and` are unmappable datahike types, so any `:or` carrying
;; this arm stays a MIXED-:or (pr-str'd EDN string storage) exactly
;; as the `[:fn]` arm did.
(schema/register! ::hiccup [:and [:vector :any] [:cat :keyword [:* :any]]])

;; THE live-tile key — qualified fn symbol OR literal hiccup, stored
;; as a pr-str'd EDN string by the mixed-:or bridge, decoded on read
;; via `seon.db/decode-edn-value`. `:seon.render/html` references
;; this shape (deliberate uniformity — agents already know that
;; vocabulary).
(schema/register! ::content [:or :symbol ::hiccup])

;; Where the wired value came from — the tile's provenance. Rendered
;; into the agent's awareness section header so the agent always sees
;; HOW to change the display. (The legacy `:seon.render/html` arm was
;; deleted in the render sweep — PRD live-tiles §8.1, no legacy: that
;; key now means ONLY the generic entity-card render slot.)
(schema/register! ::source [:enum ::content ::welcome])

(schema/register! ::hour [:int {:min 0 :max 23}])

(schema/register! ::user-name :string)

;; `:seon.db/db` (the datahike db snapshot — a runtime handle,
;; registered `:any` in seon.render today; reported drift — wants to
;; live in seon.db) loads AFTER this ns, so the request shape specs
;; the handle inline as `:any` (the sanctioned third-party-boundary
;; exception).
(schema/register! ::user-name-request
  [:map [:seon.db/db {:optional true} :any]])

(schema/register! ::user-name-response
  [:map [::user-name {:optional true} ::user-name]])

;; ============================================================
;; The core default tile.
;; ============================================================

(def welcome-sym
  "The core default tile — [[welcome]], late-resolved like any
   other wired symbol (the default eats the same dogfood). The
   resolution that selects it (when no `::content` is wired) lives in
   `seon.render.live-tile.internal/wired-content`."
  'seon.render.live-tile/welcome)

;; ============================================================
;; The welcome — the default tile every uncustomized agent shows.
;; ============================================================

(defn greeting
  "Time-of-day greeting for `hour` (0-23): morning 5-11,
   afternoon 12-16, evening 17-21, night otherwise."
  {:malli/schema [:=> [:catn [::hour ::hour]] :string]}
  [hour]
  (cond
    (<= 5 hour 11)  "Good morning"
    (<= 12 hour 16) "Good afternoon"
    (<= 17 hour 21) "Good evening"
    :else           "Good night"))

(defn user-name
  "The human's name, when the store carries one (`:seon.user/name` on
   the user entity). Returns `{::user-name \"Sean\"}` or `{}` —
   gracefully generic when the attr was never installed (querying an
   attr datahike has never seen THROWS, so the `seon.db/installed-schema`
   gate is load-bearing, not defensive fluff)."
  {:malli/schema [:=> [:cat ::user-name-request] ::user-name-response]}
  [{:seon.db/keys [db]}]
  (let [db (or db (some-> db/*conn* deref))]
    (if (and db (contains? (db/installed-schema db) :seon.user/name))
      (if-some [n (ffirst (db/query
                            {:seon.db/db    db
                             :seon.db/query '[:find ?n
                                              :where [_ :seon.user/name ?n]]}))]
        {::user-name n}
        {})
      {})))

(def panel-line
  "The double-duty line: tells the HUMAN what the panel is, and —
   because the agent reads this fn's twin and source every turn —
   reinforces to the AGENT that writing hiccup-returning fns is
   normal and easy."
  "I'll update this panel as I work — charts, statuses, whatever you ask for.")

(defn welcome
  "The core default tile — elegant, simple, TIME-AWARE.

   COMPACT (the root grid): purpose headline + agent id + the agent's
   last reply as readable text (`seon.render.chat/last-reply` — the
   conversation query, not raw message data), so an uncustomized
   agent's grid tile is worth glancing at. EXPANDED (the agent view):
   a greeting (by name when the store knows one), today's date and
   time, the purpose line, and [[panel-line]].

   This fn is itself the worked example of the tile contract: ONE
   render emitting tagged compact + expanded blocks, plus the
   `:seon.render/ai` twin saying what the human sees. Note it
   DERIVES everything from the db value and the wall clock at render
   time — nothing stored, nothing stale (write your tile fns the
   same way: rendered database queries, not hiccup snapshots)."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [{:seon.db/keys [db] :seon.render/keys [entity] :seon.agent/keys [id]}]
  (let [now        (js/Date.)
        greet      (greeting (.getHours now))
        ;; Optional = absent, never nil-valued (house rule).
        uname      (::user-name (user-name (if db {:seon.db/db db} {})))
        greet-line (if uname
                     (str greet ", " uname ".")
                     (str greet "."))
        date-str   (.toLocaleDateString now "en-US"
                                        #js {:weekday "long"
                                             :month   "long"
                                             :day     "numeric"})
        time-str   (.toLocaleTimeString now "en-US"
                                        #js {:hour   "2-digit"
                                             :minute "2-digit"})
        purpose    (:seon.agent/purpose entity)
        purpose-line (or purpose
                         "I'm still finding my purpose — tell me what you need.")
        agent-id   (or id (:seon.agent/id entity))
        ;; Last reply — derived from the message log, schema-gated
        ;; like [[user-name]] (querying a never-installed attr THROWS
        ;; on datahike-cljs).
        reply      (when (and db agent-id
                              (contains? (db/installed-schema db)
                                         :seon.agent.message/content))
                     (:seon.render.chat/last-reply
                       (chat/last-reply {:seon.agent/id agent-id
                                         :seon.db/db    db})))]
    {:seon.render/hiccup
     [:div {:class "seon-tile"}
      [:div {:class "seon-tile-compact flex flex-col gap-1 p-3"}
       [:div {:class "text-sm text-text-100"} purpose-line]
       (when agent-id
         [:div {:class "text-[10px] font-mono text-text-500"} agent-id])
       (if reply
         [:div {:class "seon-tile-reply text-xs text-text-300 whitespace-pre-wrap"}
          reply]
         [:div {:class "text-xs text-text-400 italic"} greet-line])]
      [:div {:class "seon-tile-expanded flex flex-col gap-3 p-4"}
       [:div {:class "text-lg text-text-50"} greet-line]
       [:div {:class "text-xs font-mono text-signal"}
        (str date-str " · " time-str)]
       [:div {:class "text-sm text-text-200"} purpose-line]
       [:div {:class "text-xs text-text-400 italic"} panel-line]]]
     :seon.render/ai
     (str "Welcome card — your tile is showing the core default "
          "(point :seon.render.live-tile/content at your own fn to "
          "replace it). Your human currently sees — in the root grid "
          "(compact): "
          (if purpose
            (str "your purpose (\"" purpose "\")")
            "a note that you're still acquiring your purpose")
          ", your id"
          (if reply
            (str ", and your last reply (\"" reply "\")")
            (str ", and \"" greet-line "\""))
          "; expanded (the agent view): \"" greet-line "\" with "
          date-str " " time-str ", your purpose line, and: \""
          panel-line "\" "
          "To replace it, transact :seon.render.live-tile/content onto "
          "your agent entity — a qualified fn symbol or literal hiccup.")}))

;; ============================================================
;; Creation wiring — the eval every NEW agent runs as its first
;; logged act (live-tiles PRD 2026-06-11 §6 U4, minimal scope).
;;
;; ONE canonical definition of the wiring form, AS DATA (a source
;; string), so the boot path (seon.client/creation-evals!) and the
;; tests assert against the same artifact. The `;;` comments are the
;; teaching register from the context-v4 PRD §3.1 — parse-forms
;; accumulates them as the eval's :seon.eval/narration, so the agent
;; re-reads the tutorial every time it reads its own log.
;; ============================================================

(defn wiring-source
  "The creation-time wiring eval SOURCE for agent `agent-id`: tutorial
   `;;` comments + ONE form that transacts [[welcome-sym]] onto the
   agent's OWN entity by its `:seon.agent/id` lookup ref (identity-attr
   upsert — the same 'transact to my own lookup ref' move the agent
   uses for every later tile change). Evaled AS the new agent at
   creation by `seon.client/creation-evals!`, so the eval log's first
   entry is a real worked example; the datom it writes is durable,
   surviving pod restarts with no re-seed."
  {:malli/schema [:=> [:catn [:seon.agent/id :seon.db/id]] :string]}
  [agent-id]
  (str
    ";; I am an entity in the shared store — everything about me is data,\n"
    ";; and I change myself by transacting to my own lookup ref: the\n"
    ";; identity attr :seon.agent/id in this map addresses MY entity.\n"
    ";; First act: wire my live tile (the panel my human sees) to the\n"
    ";; core welcome fn. Any fn returning\n"
    ";; {:seon.render/hiccup … :seon.render/ai …} can go here — when I\n"
    ";; have something better to show, I define a fn and point this\n"
    ";; attr at it.\n"
    "(seon.db/transact!\n"
    "  {:seon.db/tx-data\n"
    "   [{:seon.agent/id " (pr-str agent-id) "\n"
    "     :seon.render.live-tile/content '" welcome-sym "}]})\n"))

;; ============================================================
;; Errors are legible — a broken tile never silently vanishes.
;; ============================================================

(defn error-response
  "Build the html-response for a tile fn that THREW. THE HUMAN sees a calm,
   nicely-formatted 'updating this panel' card — never a scary error, never a
   blank (vanish is indistinguishable from unwired, banned). THE AGENT is told
   the truth: the `:seon.render/ai` twin carries the failure (awareness
   section) and the full `:seon.error/*` envelope rides on `:seon.render/error`.
   Breakage is a DERIVED surface only (#43 / D2) — the
   `:seon.ctx.live-tile/live-tile-section` re-derives this twin into the
   agent's context EVERY turn (a pure fn of state, no stored flag,
   self-healing on the next clean render). There is NO active push: a forged
   self-message would wake the agent and defeat the loop's halt. So the human
   stays calm while the agent learns of the breakage by reading its own
   context."
  {:malli/schema [:=> [:cat ::error-request] :seon.render/html-response]}
  [{error :seon.db/error wired ::content}]
  (let [msg       (:seon.error/message error)
        wired-str (if (symbol? wired)
                    (str wired)
                    "literal hiccup on your entity")]
    {:seon.render/hiccup
     [:div {:class "seon-tile"}
      [:div {:class "seon-tile-compact flex flex-col gap-1 p-3"}
       [:div {:class "text-sm text-text-200"} "Updating this panel…"]]
      [:div {:class "seon-tile-expanded flex flex-col gap-2 p-4"}
       [:div {:class "text-base text-text-100"} "Updating this panel…"]
       [:div {:class "text-xs text-text-400 italic"}
        "I'm refining what I show here."]]]
     :seon.render/ai
     (str "YOUR LIVE TILE IS BROKEN — the wired renderer (" wired-str
          ") threw: " msg ". Your human sees a calm 'updating this panel' "
          "placeholder, not your content. Fix the fn, or transact a working "
          "value onto :seon.render.live-tile/content.")
     :seon.render/error error}))
