(ns seon.ui.world
  "The per-agent world layout — `world-layout = f(db, agent-id)` → the
   `[:main#world …]` hiccup the `/agent/{id}` page streams.

   ## What a world is

   One agent's page: a focal **canvas** (the agent↔human communication
   block) above a `:seon.agent.ctx/priority`-ordered scroll of the agent's
   remaining **tiles**. Every tile is one of the agent's OWN
   `:seon.agent/ctx` blocks that carries a `:seon.render/html` render,
   placed by the `seon.render/slot` primitive — `(slot ctx name)` looks the
   block up by `:seon.agent.ctx/name` in the agent's own ctx, renders its
   html through the guarded engine, and wraps it as
   `[:div#tile-<name> {:data-slot \"<name>\"} …]`. Blocks with only an ai
   render (prompt-only) contribute no tile.

   ## Canvas selection is data, not a flag

   The canvas is the focal comms block — the first of `:canvas` / `:transcript`
   the agent actually owns. No stored discriminator: a third party that names
   its comms block `:canvas` gets it; the seon seed names it `:transcript`.

   ## Pure + never-crash

   `world-layout` is a pure function of `(db, agent-id)` — same db value +
   id always renders the same hiccup. It NEVER throws: the slot primitive
   already guards each tile (a missing/throwing block becomes an error tile,
   siblings intact), and a whole-layout failure degrades to a visible
   `#world-error` inside `#world`. The root is always `[:main#world …]` —
   the morph target the shim page declares — so datastar's idiomorph patches
   it in place.

   ## Theme

   Phosphor Terminal (warm blacks, cream text, amber accents; monospace;
   density). Chrome classes stay within the safelisted agent utility
   vocabulary (`resources/public/css/input.css`) so the page is styled even
   where the CSS is not rebuilt."
  (:require
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.render :as render]))

;; ============================================================
;; The agent's html-rendering block names — priority-sorted.
;; A pure read of the db value; guarded (a missing agent's pull
;; throws "Nothing found for entity id"). Only blocks carrying a
;; :seon.render/html become tiles; ai-only blocks are prompt-only.
;; ============================================================

(defn- agent-html-block-names
  "Names (`:seon.agent.ctx/name` keywords) of the agent's blocks that carry
   an html render, sorted by `:seon.agent.ctx/priority` with a stable
   by-name tiebreak. Empty when the agent is absent or owns no html block."
  [db agent-id]
  (->> (try
         (:seon.agent/ctx
          (db/pull db
                   '[{:seon.agent/ctx [:seon.agent.ctx/name
                                       :seon.agent.ctx/priority
                                       :seon.render/html]}]
                   [:seon.agent/id agent-id]))
         (catch :default _ nil))
       (filter #(contains? % :seon.render/html))
       (sort-by (juxt #(or (:seon.agent.ctx/priority %) 0)
                      #(str (:seon.agent.ctx/name %))))
       (mapv :seon.agent.ctx/name)))

;; ============================================================
;; Status chip — the DERIVED FSM state as a dot+text indicator
;; (`● running`). Guarded: a failing derive degrades to :idle so the
;; header never aborts the layout.
;; ============================================================

(def ^:private state-display
  "DERIVED state (`seon.derive/derive-state`) → dot glyph + Phosphor text
   class + label. Unknown states fall back to a muted dot."
  {:running    {:dot "●" :class "text-signal"   :label "running"}
   :idle       {:dot "●" :class "text-text-400" :label "idle"}
   :paused     {:dot "⚠" :class "text-warning"  :label "paused"}
   :terminated {:dot "✗" :class "text-text-500" :label "terminated"}})

(defn- status-chip
  "The agent's derived FSM state as `● <state>` (dot colored by state)."
  [db agent-id]
  (let [state (try (derive/derive-state db agent-id) (catch :default _ :idle))
        {:keys [dot class label]} (or (state-display state)
                                      {:dot "●" :class "text-text-400"
                                       :label (name state)})]
    [:span {:class "flex items-center gap-1 text-xs font-mono"}
     [:span {:class class} dot]
     [:span {:class "text-text-200"} label]]))

;; ============================================================
;; world-layout = f(db, agent-id) — the page.
;; ============================================================

(defn- tile-card
  "Wrap one slot in a Phosphor card with a STABLE id so idiomorph anchors
   it across morphs. `(render/slot …)` itself yields `#tile-<name>`; the
   card is the chrome around it."
  [ctx block-name]
  [:section {:id    (str "world-tile-" (name block-name))
             :class "border border-base-800 rounded-md bg-base-900 p-3 overflow-hidden"}
   (render/slot ctx block-name)])

(defn world-layout
  "view = f(db, agent-id): the agent's OWN world as `[:main#world …]`.

   Places the agent's html-rendering `:seon.agent/ctx` blocks as tiles via
   `seon.render/slot`: the focal comms block (`:canvas` else `:transcript`)
   as a prominent canvas region, then a `:seon.agent.ctx/priority`-ordered
   scroll of the rest. Pure of external state (reads only the supplied db
   value); NEVER throws — per-tile failures are guarded by the slot
   primitive, a whole-layout failure degrades to a visible `#world-error`."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  :any]}
  [db agent-id]
  (try
    (let [ctx    {:seon.db/db db :seon.agent/id agent-id}
          names  (agent-html-block-names db agent-id)
          canvas (some (set names) [:canvas :transcript])
          tiles  (vec (remove #(= % canvas) names))]
      [:main {:id "world" :class "flex flex-col gap-3 w-full"}
       [:header {:id    "world-header"
                 :class "flex items-center justify-between border-b border-base-800 pb-2"}
        [:div {:class "flex items-center gap-2 min-w-0"}
         [:span {:class "text-text-500 text-2xs uppercase tracking-wider"} "agent"]
         [:span {:class "text-signal text-sm font-semibold font-mono truncate"} agent-id]]
        (status-chip db agent-id)]
       (when canvas
         [:section {:id    "world-canvas"
                    :class "border border-base-800 rounded-md bg-base-900 p-3 overflow-auto"}
          (render/slot ctx canvas)])
       [:div {:id "world-tiles" :class "flex flex-col gap-3"}
        (when (seq tiles)
          (for [n tiles] (tile-card ctx n)))]
       (when (and (nil? canvas) (empty? tiles))
         [:div {:id    "world-empty"
                :class "text-text-500 text-xs font-mono"}
          (str "agent " agent-id " has no html tiles yet")])])
    (catch :default e
      [:main {:id "world" :class "flex flex-col gap-3 w-full"}
       [:div {:id    "world-error"
              :class "text-error text-xs font-mono"}
        (str "render error: " (.-message e))]])))
