(ns seon.render.canvas
  "The canvas — the ONE thing an agent is currently conveying to
   its human (a chart, a status, a list — whatever the human asked
   for). One wired value renders at every zoom surface: the root grid
   (compact), the agent view (expanded), and — as an ai render — the
   agent's own context every turn, so the agent always knows what its
   human currently sees.

   ## Wiring the canvas

   An explicit pin is ONE attr on your agent entity:
   `:seon.render.canvas/content`. Its value follows the
   `:seon.render/html` semantics exactly:

   - a LITERAL HICCUP vector for static content — `[:h1 \"hi\"]`
   - a QUALIFIED FN SYMBOL for dynamic content, late-resolved at
     every render via `seon.eval/lookup-value` (core fns and
     fns you define yourself both resolve — same single path).

   Wire it with a raw transact (no sugar — one pattern):

       (seon.db/transact!
         {:seon.db/tx-data
          [{:seon.agent/id (seon.db/current-agent-id)
            :seon.render.canvas/content 'my.workouts/chart-canvas}]})

   ## The two renders

   A canvas fn returns the standard `:seon.render/html-response` map,
   carrying the html render AND an ai render:

       {:seon.render/hiccup [...]   ;; what the human sees
        :seon.render/ai \"3 workouts this week: Mon 4200kg, …\"}

   The `:seon.render/ai` string is how YOU know what your human sees —
   say what the content MEANS. Your human sees the picture; you see
   your words. A fn that omits the ai render gets its hiccup shown to it
   verbatim instead.

   ## Compact vs expanded — tag blocks, never write media queries

   Small-vs-large is core CSS container queries over ONE render.
   Emit both blocks in one document and tag them:

       [:div.seon-card
        [:div.seon-card-compact  [:span \"3 workouts this week\"]]
        [:div.seon-card-expanded [:svg …full chart…] [:table …]]]

   The core shows `.seon-card-compact` below the breakpoint and
   `.seon-card-expanded` at or above it (container rules live in
   `resources/public/css/input.css`). Untagged content renders at
   every size. The compact block is HEIGHT-CLAMPED by the core
   (grid cards are uniform; overflow clips) — put a glanceable
   summary there and the full content in the expanded block.

   ## Styling — write SEMANTIC hiccup, zero classes needed

   The core styles plain HTML elements inside your canvas (and
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

   ## Canvas updates should be rendered database queries

   Transact important findings as linked entities; render by
   reference. A canvas fn that queries the db re-derives on a fresh
   pod (session resume works for free) — a hardcoded hiccup snapshot
   of a computed value goes stale and dies with the session. This is
   the reactive-context principle applied to the human surface.

   ## Who calls what

   `seon.render/render-agent-canvas` is the one entry point: it calls
   [[wired-content]] to resolve WHICH value is wired
   (`::content` pin → configured canvas-block default → the caller-derived
   last-updated surface → [[welcome]]),
   invokes it
   through `seon.render/html-render` (the ONE value-or-fn dispatch),
   and on a throw builds the legible [[error-response]] — a broken
   canvas must never silently vanish (vanish = indistinguishable from
   unwired; banned)."
  (:require
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.render.chat :as chat]
    [seon.render.schema]
    [seon.schema :as schema]
    [seon.ui.html :as html]
    [seon.ui.markdown :as md]))

;; ============================================================
;; The hiccup predicate + the canonical value-or-fn shape.
;;
;; This ns loads BEFORE seon.render (which requires it), and
;; register!'s compilability guard rejects forward references — so
;; the ONE definition of the value-or-fn slot shape lives here, and
;; `:seon.render/html` is registered in seon.render BY REFERENCE to
;; `::content` (shared-shape rule: one shape, registered once, every
;; other key references it).
;;
;; PLATFORM LAW (2026-06-11, sci-not-available incident): registered
;; schema forms must be PURE DATA — no `[:fn]`, no function objects,
;; nothing whose form needs evaluation to reconstruct. Registered
;; forms round-trip as forms (boot index → :seon.schema/form →
;; re-read), and the pod has no sci: a fn in a registered form
;; serializes as a symbol or `#object[...]` and dies (or degrades to
;; garbage) on every subsequent read. Deep-structure validation that
;; needs a predicate belongs at a FN boundary (instrumentation on a
;; compiled fn never round-trips as a form) — see [[valid-hiccup?]].
;;
;; `::hiccup` below is therefore the PRAGMATIC STRUCTURAL BOUND
;; (option b): a hiccup element = a vector with a keyword head.
;; Option (a) — a recursive ref-based registered schema — was tested
;; and REJECTED twice over: register!'s compilability guard throws
;; `:malli.core/invalid-ref` on self-reference, and a recursive seqex
;; trips `:malli.core/potentially-recursive-seqex` inside any
;; instrumented fn schema. Deep validation runs at the render
;; boundary instead, where [[valid-hiccup?]] stays a PLAIN fn.
;; ============================================================

(declare valid-hiccup?)

(defn valid-hiccup-elem?
  "True if `x` is a valid hiccup ELEMENT.

   String, int, nil, or a nested vector that starts with a keyword."
  {:malli/schema [:=> [:catn [::elem :any]] :boolean]}
  [x]
  (or (string? x)
      (int? x)
      (nil? x)
      (valid-hiccup? x)))

(defn valid-hiccup?
  "True if `x` is a valid hiccup VECTOR.

   Starts with a keyword tag,
   optional second-position attrs map, zero or more children where
   each child is a valid hiccup element. Non-recursive Malli idiom —
   handles arbitrary-depth nesting without
   :malli.core/potentially-recursive-seqex.

   A PLAIN fn for render-boundary checks and fn instrumentation —
   deliberately NOT inside any `register!` form (registered schema
   forms must be pure data; see the platform-law comment above).
   `::content` / `:seon.render/html-response` carry the shallow
   `::hiccup` data shape instead. (`:any` input — this IS the
   validator for arbitrary values.)"
  {:malli/schema [:=> [:catn [::x :any]] :boolean]}
  [x]
  (and (vector? x)
       (keyword? (first x))
       (let [rest-x (rest x)
             [_maybe-attrs children]
             (if (map? (first rest-x))
               [(first rest-x) (rest rest-x)]
               [nil rest-x])]
         (every? valid-hiccup-elem? children))))

;; ============================================================
;; Serialization-boundary structural check (serialization-boundary
;; hardening): [[valid-hiccup?]] above is the strict
;; AUTHORING shape, deliberately narrower than what the serializer
;; accepts (seqs, numbers, raw, stringifiable values all render fine
;; via seon.ui.html/->string) — so it CANNOT gate the render path
;; without falsely erroring legitimate canvases. The fns below mirror
;; the SERIALIZER's acceptance exactly and return the FIRST fatal
;; defect with its path, so a broken canvas degrades to
;; [[error-response]] with a legible message instead of throwing
;; later at page serialization and 500ing /agent/<id> + the grid.
;; ============================================================

(schema/register! ::structure-path [:vector :int])
(schema/register! ::structure-message :string)
(schema/register! ::structure-error
  [:map
   [::structure-path    ::structure-path]
   [::structure-message ::structure-message]])

(defn- structure-error-at
  "Walk `x` the way seon.ui.html renders it; return the first fatal
   structural defect as {::structure-path ::structure-message}, or
   nil. Fatal = exactly what makes ->string THROW: a vector element
   whose tag slot isn't a keyword/symbol/string. Everything the
   serializer tolerates (nil/false, raw, seqs, stringifiable scalars)
   passes.

   ATTRS-POSITION RULE (#42): in a hiccup element [tag attrs? & children]
   the attrs map MUST be the SECOND element (immediately after the tag),
   before any children. The serializer reads attrs ONLY in that position;
   a map placed later among the children silently degrades to garbage
   content. This walk reports that one unambiguous misplaced-attrs case
   — the 2nd slot is a non-map child AND a (non-raw) map sits at child
   index ≥ 1 — as a specific ::structure-message. It deliberately does
   NOT flag the genuinely-ambiguous shapes (a single map that COULD be
   intended as the attrs: [:div {…}] is correct; [:h3 \"x\"] has no map)."
  [x path]
  (cond
    (or (nil? x) (false? x)) nil
    (html/raw? x)            nil

    (vector? x)
    (let [tag (nth x 0 nil)]
      (cond
        (vector? tag)
        {::structure-path path
         ::structure-message
         (str "vector-of-vectors child — the element at this path is a "
              "vector whose first slot is itself a vector ("
              (tokens/bounded-pr-str tag 30) "). Splice the children into the "
              "parent vector, or emit them as a seq — "
              "(list [:div …] [:div …]) — never a nested vector of "
              "elements.")}

        (not (or (keyword? tag) (symbol? tag) (string? tag)))
        {::structure-path path
         ::structure-message
         (str "invalid tag — must be a keyword, symbol, or string; got "
              (tokens/bounded-pr-str tag 30))}

        :else
        (let [body      (rest x)
              attrs?    (and (map? (first body))
                             (not (html/raw? (first body))))
              children  (if attrs? (rest body) body)
              offset    (if attrs? 2 1)
              ;; MISPLACED-ATTRS (#42): the unambiguous case — the 2nd
              ;; slot is a NON-map child (so it's read as content, not
              ;; attrs) yet an attrs-looking map sits LATER among the
              ;; children. The serializer reads attrs only in 2nd
              ;; position, so this map silently becomes garbage content.
              ;; CONSERVATIVE on purpose: fires ONLY when the 2nd slot is
              ;; already a non-map child AND a (non-raw) map appears at
              ;; child index ≥ 1 — never on valid hiccup ([:h3 "x"] has no
              ;; map; [:div {:k 1} "x"] has the map in correct 2nd
              ;; position so attrs? is true and this branch is skipped).
              misplaced-i (when (and (seq body) (not attrs?))
                            (first
                              (keep-indexed
                                (fn [i c]
                                  (when (and (pos? i)
                                             (map? c)
                                             (not (html/raw? c)))
                                    i))
                                children)))]
          (if misplaced-i
            {::structure-path (conj path (+ offset misplaced-i))
             ::structure-message
             (str "misplaced attrs map — the attrs map must be the SECOND "
                  "element (immediately after the tag), before any children; "
                  "got a map at child index " misplaced-i " ("
                  (tokens/bounded-pr-str (nth children misplaced-i) 30)
                  "). Move it to the second slot, e.g. [" (pr-str (nth x 0))
                  " {…} child …], or drop it if it was meant as content.")}
            (some identity
                  (map-indexed
                    (fn [i c] (structure-error-at c (conj path (+ offset i))))
                    children))))))

    (seq? x)
    (some identity
          (map-indexed (fn [i c] (structure-error-at c (conj path i)))
                       x))

    :else nil))

(defn hiccup-structure-error
  "Serializer-faithful structural check for a canvas's hiccup.

   Returns
   nil when `seon.ui.html/->string` would serialize `x` cleanly, or
   `{::structure-path […] ::structure-message \"…\"}` locating the
   FIRST fatal defect (e.g. a vector-of-vectors child). A PLAIN fn at
   the render boundary, like [[valid-hiccup?]] — never inside a
   registered form. (`:any` input — this IS the validator for
   arbitrary values.)"
  {:malli/schema [:=> [:catn [::x :any]] [:maybe ::structure-error]]}
  [x]
  (structure-error-at x []))

;; The shared `::hiccup` and `::content` data forms are registered by
;; `seon.render.schema`, a dependency-free leaf used by both context blocks and
;; this renderer. Deep validation remains here at the actual render boundary.

;; Where the wired value came from — the canvas's provenance. Rendered
;; into the agent's awareness section header so the agent always sees
;; HOW to change the display. (The legacy `:seon.render/html` arm was
;; deleted in the render sweep — PRD canvas §8.1, no legacy: that
;; key now means ONLY the generic entity-surface render slot.)
(schema/register! ::source [:enum ::content ::configured ::derived ::welcome])

(schema/register! ::derived :symbol)
(schema/register! ::configured ::content)

(schema/register! ::wired-request
  [:map
   [:seon.render/entity :map]
   ;; A database-configured default on the agent's :canvas context block.
   ;; The explicit agent-entity pin above wins; `:none` is omitted by
   ;; [[canvas-state]].
   [::configured {:optional true} ::configured]
   ;; The DERIVED canvas default — the agent's last-updated surface fn
   ;; (seon.agent.ctx.render-fns/last-updated-surface), computed by the
   ;; caller (this ns loads below render-fns) and consulted only when
   ;; no pin is stored. Derive the default, store only the pin.
   [::derived {:optional true} ::derived]])

(schema/register! ::wired-response
  [:map
   [::source ::source]
   [::value  ::content]])

(schema/register! ::state-request
  [:map
   [:seon.db/db :seon.db/db]
   [:seon.agent/id :string]])

(schema/register! ::state-response
  [:map
   [:seon.render/entity :map]
   [::configured {:optional true} ::configured]])

(schema/register! ::error-request
  [:merge :seon.db/error
   [:map [::content {:optional true} ::content]]])

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
;; Resolution — which value is wired.
;; ============================================================

(def welcome-sym
  "The core default canvas — [[welcome]], late-resolved like any
   other wired symbol (the default eats the same dogfood)."
  'seon.render.canvas/welcome)

(def ^:private canvas-entity-pattern
  "The exact agent and configured-canvas facts needed for resolution."
  [:db/id
   :seon.agent/id
   :seon.agent/run
   :seon.agent/purpose
   :seon.render.canvas/content
   {:seon.agent/ctx
    [:seon.agent.ctx/name :seon.render.canvas/content]}])

(defn canvas-state
  "Read the facts that resolve one agent's canvas.

   Returns the bounded agent entity plus an optional configured default from
   its `:canvas` context block. The context component is consumed here and is
   not exposed to renderer input. Missing agents return an empty entity. A
   block value of `:none` means no configured default and is omitted."
  {:malli/schema [:=> [:cat ::state-request] ::state-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [raw (or (db/pull db canvas-entity-pattern [:seon.agent/id id]) {})
        configured (some (fn [block]
                           (when (= :canvas (:seon.agent.ctx/name block))
                             (let [value (some->>
                                           (:seon.render.canvas/content block)
                                           (db/decode-edn-value ::content))]
                               (when (and (some? value) (not= :none value))
                                 value))))
                         (:seon.agent/ctx raw))]
    (cond-> {:seon.render/entity (dissoc raw :seon.agent/ctx)}
      (some? configured) (assoc ::configured configured))))

(defn wired-content
  "Resolve WHICH value is the agent's canvas, with provenance.

   Resolution over [[canvas-state]] plus the caller's derivation:
   `:seon.render.canvas/content` (the explicit canvas pin) when present;
   else `::configured` from the agent's canvas context block; else the
   caller-supplied `::derived` symbol (the agent's last-updated surface, see
   `seon.agent.ctx.render-fns/last-updated-surface`); else [[welcome-sym]]
   (the core welcome). Pin wins over configured, configured wins over
   derived; retract the pin to resume the configured or derived default.
   Neither the per-entity
   `:seon.render/html` nor the `:seon.agent` KIND default is consulted
   — that key means ONLY the generic entity-surface render slot (one key,
   one meaning; the legacy fallback was deleted per PRD
   canvas-prd-2026-06-11 §8.1).

   Values arrive pr-str-encoded from the mixed-:or bridge; the attr
   read decodes via `seon.db/decode-edn-value`."
  {:malli/schema [:=> [:cat ::wired-request] ::wired-response]}
  [{:seon.render/keys [entity] ::keys [configured derived]}]
  (let [content (some->> (::content entity)
                         (db/decode-edn-value ::content))]
    (cond
      (and (some? content) (not= :none content))
      {::source ::content ::value content}

      (some? configured)
      {::source ::configured ::value configured}

      (some? derived)
      {::source ::derived ::value derived}

      :else
      {::source ::welcome ::value welcome-sym})))

(defn wired-label
  "The awareness-section header identity for a [[wired-content]] result.

   The wired fn's fully-qualified name (its source is one
   `:seon.fn`/catalog lookup away) or \"literal hiccup on your
   entity\", with provenance (legacy slot / core default), so
   the agent reading the section always sees HOW to change the
   display."
  {:malli/schema [:=> [:cat ::wired-response] :string]}
  [{::keys [source value]}]
  (case source
    ::content
    (if (symbol? value)
      (str value " (explicit pin)")
      "literal hiccup (explicit pin)")

    ::configured
    (str value " (configured default)")

    ::derived
    (str value " (derived — your last-updated surface; transact "
         ":seon.render.canvas/content to pin a different one)")

    ::welcome
    (str value " (core welcome)")))

;; ============================================================
;; The welcome — the default canvas every uncustomized agent shows.
;; ============================================================

(defn greeting
  "Time-of-day greeting for `hour` (0-23).

   Morning 5-11, afternoon 12-16, evening 17-21, night otherwise."
  {:malli/schema [:=> [:catn [::hour ::hour]] :string]}
  [hour]
  (cond
    (<= 5 hour 11)  "Good morning"
    (<= 12 hour 16) "Good afternoon"
    (<= 17 hour 21) "Good evening"
    :else           "Good night"))

(defn user-name
  "The human's name, when the db carries one.

   From `:seon.user/name` on
   the user entity. Returns `{::user-name \"Sean\"}` or `{}` —
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

(def welcome-line
  "The double-duty line: tells the human what the canvas is, and —
   because the agent reads this fn's render and source every turn —
   reinforces to the AGENT that writing hiccup-returning fns is
   normal and easy."
  "I'll update this canvas as I work — charts, statuses, whatever you ask for.")

(defn welcome
  "The core default canvas — elegant, simple, time-aware.

   COMPACT (the root grid): purpose headline + agent id + the agent's
   last reply as readable text (`seon.render.chat/last-reply` — the
   conversation query, not raw message data), so an uncustomized
   agent's grid card is worth glancing at. Expanded (the canvas): when
   the agent has a reply, LEAD with that reply as a real markdown card
   (`seon.ui.markdown/md->hiccup`) with the greeting/date demoted to a
   thin subhead — so a plain chat answer renders richly on the canvas;
   when there's no reply yet, a greeting empty-state (by name when the
   store knows one), today's date and time, the purpose line, and
   [[welcome-line]].

   This fn is itself the worked example of the canvas contract: one
   render emitting tagged compact + expanded blocks, plus the
   `:seon.render/ai` render saying what the human sees. Note it
   DERIVES everything from the db value and the wall clock at render
   time — nothing stored, nothing stale (write your canvas fns the
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
     [:div {:class "seon-card"}
      [:div {:class "seon-card-compact flex flex-col gap-1 p-3"}
       [:div {:class "text-sm text-text-100"} purpose-line]
       (when agent-id
         [:div {:class "text-[10px] font-mono text-text-500"} agent-id])
       (if reply
         [:div {:class "seon-card-reply text-xs text-text-300 whitespace-pre-wrap"}
          reply]
         [:div {:class "text-xs text-text-400 italic"} greet-line])]
      (if reply
        ;; The agent has spoken: the canvas LEADS with the latest reply as
        ;; a real markdown card (server-side md->hiccup — `block`'s message
        ;; arm, called direct here because seon.render requires THIS ns), and
        ;; demotes the greeting/date to a thin subhead. So a plain chat reply
        ;; renders richly on the canvas with zero agent effort.
        [:div {:class "seon-card-expanded flex flex-col gap-2 p-4"}
         (md/md->hiccup reply {:wrap-class "markdown text-sm text-text-100"})
         [:div {:class "text-[10px] font-mono text-text-500 pt-1.5 border-t border-base-800"}
          (str greet-line " · " date-str " · " time-str)]]
        ;; Fresh agent, no reply yet — keep the greeting empty-state.
        [:div {:class "seon-card-expanded flex flex-col gap-3 p-4"}
         [:div {:class "text-lg text-text-50"} greet-line]
         [:div {:class "text-xs font-mono text-signal"}
          (str date-str " · " time-str)]
         [:div {:class "text-sm text-text-200"} purpose-line]
         [:div {:class "text-xs text-text-400 italic"} welcome-line]])]
     :seon.render/ai
     (str "Core welcome canvas. "
          (if reply
            (str "Human sees the latest reply: " (pr-str reply) ".")
            (str "Human sees " (pr-str greet-line) ", " date-str " " time-str
                 ", and purpose " (pr-str purpose-line) "."))
          " Replace it with my.canvas/show! when another view is useful.")}))

;; ============================================================
;; Creation wiring — the eval every NEW agent runs as its first
;; logged act (canvas PRD 2026-06-11 §6 U4, minimal scope).
;;
;; ONE canonical definition of the wiring form, AS DATA (a source
;; string), so the boot path (seon.client/creation-evals!) and the
;; tests assert against the same artifact. The `;;` comments are the
;; teaching register from the context-v4 PRD §3.1 — parse-forms
;; accumulates them as the eval's :seon.eval/narration, so the agent
;; re-reads the tutorial every time it reads its own log.
;; ============================================================

(defn wiring-source
  "The creation-time wiring eval SOURCE for agent `agent-id`.

   Tutorial
   `;;` comments + ONE form that transacts [[welcome-sym]] onto the
   agent's OWN entity by its `:seon.agent/id` lookup ref (identity-attr
   upsert — the same 'transact to my own lookup ref' move the agent
   uses for every later canvas change). Evaled AS the new agent at
   creation by `seon.client/creation-evals!`, so the eval log's first
   entry is a real worked example; the datom it writes is durable,
   surviving pod restarts with no re-seed."
  {:malli/schema [:=> [:catn [:seon.agent/id :seon.db/id]] :string]}
  [agent-id]
  (str
    ";; I am an entity in the shared store — everything about me is data,\n"
    ";; and I change myself by transacting to my own lookup ref: the\n"
    ";; identity attr :seon.agent/id in this map addresses MY entity.\n"
    ";; First act: wire my canvas (the surface my human sees) to the\n"
    ";; core welcome fn. Any fn returning\n"
    ";; {:seon.render/hiccup … :seon.render/ai …} can go here — when I\n"
    ";; have something better to show, I define a fn and point this\n"
    ";; attr at it.\n"
    "(seon.db/transact!\n"
    "  {:seon.db/tx-data\n"
    "   [{:seon.agent/id " (pr-str agent-id) "\n"
    "     :seon.render.canvas/content '" welcome-sym "}]})\n"))

;; ============================================================
;; Errors are legible — a broken canvas never silently vanishes.
;;
;; ONE overridable seam ([[error-card]]) renders error surfaces
;; (entity render, agent-view slot, a render failure); a consumer `set!`s it to
;; a branded card (acme does) and the override carries across them all.
;; The canvas HERO ([[error-response]]) is the deliberate EXCEPTION: it
;; stays CALM — the human never sees the failure there (it rides the agent
;; twin), so the hero does NOT route through the seam.
;; ============================================================

(defn default-error-card
  "The core default html render of a `:seon/error` value.

   The ONE error
   card shared by the error surfaces (entity render, slot, a render
   failure). Reads only the shared error core (message + optional
   where/symbol/hint), so it renders ANY error. Override the whole look by
   `set!`-ing [[error-card]]."
  {:malli/schema [:=> [:cat :seon.db/error] ::hiccup]}
  [{:seon.error/keys [message where hint] sym :seon.error/symbol}]
  [:div {:class (str "seon-card flex flex-col gap-1 p-3 border "
                     "border-error/40 bg-error/10 rounded")}
   [:div {:class "text-xs text-error font-mono font-bold"}
    (str "⚠ " (when where (str (name where) " — ")) "render error")]
   [:div {:class "text-xs font-mono text-text-300 break-all"} message]
   (when sym  [:div {:class "text-[10px] font-mono text-text-500"} (str sym)])
   (when hint [:div {:class "text-xs text-text-400 italic"} hint])])

(def error-card
  "THE one overridable error-card seam — a fn `(fn [:seon/error] → hiccup)`
   the error surfaces call (entity render, slot, a render failure) —
   NOT the calm canvas hero ([[error-response]]). A consumer `set!`s
   this var to a branded card and the override carries across those
   surfaces (the late-binding `set!` pattern acme already uses). Defaults
   to [[default-error-card]]. One error renderer, no forks."
  default-error-card)

(defn error-response
  "Build the html-response for a canvas fn that THREW.

   THE HUMAN sees a calm,
   nicely-formatted 'updating this canvas' placeholder — never a scary error
   (NO failure text leaks to the human card), never a blank (vanish is
   indistinguishable from unwired, banned). THE AGENT is told
   the truth: the `:seon.render/ai` render carries the failure (awareness
   section) and the full `:seon.error/*` envelope rides on `:seon.render/error`.
   Breakage is a DERIVED surface only (#43 / D2) — the
   `:seon.agent.ctx.canvas/canvas-block` re-derives this render into the
   agent's context EVERY turn (a pure fn of state, no stored flag,
   self-healing on the next clean render). There is NO active push: a forged
   self-message would wake the agent and defeat the loop's halt. So the human
   stays calm while the agent learns of the breakage by reading its own
   context."
  {:malli/schema [:=> [:cat ::error-request] :seon.render/html-response]}
  [{wired ::content :as error-data}]
  (let [error     (dissoc error-data ::content)
        msg       (:seon.error/message error)
        wired-str (if (symbol? wired)
                    (str wired)
                    "literal hiccup on your entity")]
    {:seon.render/hiccup
     [:div {:class "seon-card"}
      [:div {:class "seon-card-compact flex flex-col gap-1 p-3"}
       [:div {:class "text-sm text-text-200"} "Updating this canvas…"]]
      [:div {:class "seon-card-expanded flex flex-col gap-2 p-4"}
       [:div {:class "text-base text-text-100"} "Updating this canvas…"]
       [:div {:class "text-xs text-text-400 italic"}
        "I'm refining what I show here."]]]
     :seon.render/ai
     (str "YOUR CANVAS IS BROKEN — the wired renderer (" wired-str
          ") threw: " msg ". Your human sees a calm 'updating this canvas' "
          "placeholder, not your content. Fix the fn, or transact a working "
          "value onto :seon.render.canvas/content.")
     :seon.render/error error}))
