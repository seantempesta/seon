(ns seon.ui.world
  "The per-agent world layout — `world-layout = f(db, agent-id)` → the
   `[:main#world …]` hiccup the `/agent/{id}` page streams.

   ## What a world is

   One agent's page: a focal **canvas** — the agent's LIVE TILE, the one
   HTML surface it dynamically rewrites — above a
   `:seon.agent.ctx/priority`-ordered scroll of the agent's **tiles**.
   Every tile is one of the agent's OWN `:seon.agent/ctx` blocks that
   carries a `:seon.render/html` render, placed by the `seon.render/slot`
   primitive — `(slot ctx name)` looks the block up by
   `:seon.agent.ctx/name` in the agent's own ctx, renders its html through
   the guarded engine, and wraps it as `[:div#tile-<name> {:data-slot
   \"<name>\"} …]`. Blocks with only an ai render (prompt-only) contribute
   no tile.

   ## Canvas is the live tile

   The canvas is the agent's live tile — `seon.render/render-agent-tile`
   resolves `:seon.render.live-tile/content` (or the `welcome` default)
   against the SAME db value, so a consumer's tile override is the page
   hero. It never throws: a broken tile is a calm placeholder, a missing
   agent is nil hiccup → no canvas. Every `:seon.agent/ctx` html block —
   `:transcript` included — is a supporting tile; there is no second
   ctx-block \"canvas\" concept fighting the live tile for the hero.

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
;; The agent's html-rendering block names — priority-sorted. A pure read of
;; the db value. The agent's eid is resolved via a NON-THROWING query first
;; (an absent agent yields no eid → no tiles), so a /agent/{id} feed left
;; bound to a since-deleted agent (a stale tab after a cluster reset) renders
;; an empty world WITHOUT datahike's entid-strict logging ":error … Nothing
;; found for entity id" on every tx — a strict lookup-ref pull logs that
;; (via log/raise) BEFORE any caller catch, so guarding the throw is not
;; enough; we must not issue the strict lookup at all. Only blocks carrying a
;; :seon.render/html become tiles; ai-only blocks are prompt-only.
;; ============================================================

(defn- agent-html-block-names
  "Names (`:seon.agent.ctx/name` keywords) of the agent's blocks that carry
   an html render, sorted by `:seon.agent.ctx/priority` with a stable
   by-name tiebreak. Empty when the agent is absent or owns no html block."
  [db agent-id]
  (->> (when-let [eid (ffirst
                        (db/query {:seon.db/db    db
                                   :seon.db/query '[:find ?a
                                                    :in $ ?id
                                                    :where [?a :seon.agent/id ?id]]
                                   :seon.db/args  [agent-id]}))]
         (try
           (:seon.agent/ctx
            (db/pull db
                     '[{:seon.agent/ctx [:seon.agent.ctx/name
                                         :seon.agent.ctx/priority
                                         :seon.render/html]}]
                     eid))
           (catch :default _ nil)))
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

   The focal `#world-canvas` is the agent's live tile —
   `(seon.render/render-agent-tile {…})`'s `:seon.render/hiccup`, resolved
   against the SAME supplied db value (passed EXPLICITLY, never defaulted)
   so the layout stays a pure `f(db, agent-id)`. Below it, every
   html-rendering `:seon.agent/ctx` block (`:seon.agent.ctx/priority`-
   ordered, `:transcript` included) is a supporting tile via
   `seon.render/slot`. Pure of external state (reads only the supplied db
   value); NEVER throws — the live tile and each slot self-guard, a
   whole-layout failure degrades to a visible `#world-error`. A missing
   agent (nil live-tile hiccup + no tiles) renders the header +
   `#world-empty`."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  :any]}
  [db agent-id]
  (try
    (let [ctx        {:seon.db/db db :seon.agent/id agent-id}
          tile-names (agent-html-block-names db agent-id)
          live-tile  (:seon.render/hiccup
                       (render/render-agent-tile {:seon.agent/id agent-id
                                                  :seon.db/db    db}))]
      [:main {:id "world" :class "flex flex-col gap-3 w-full"}
       [:header {:id    "world-header"
                 :class "flex items-center justify-between border-b border-base-800 pb-2"}
        [:div {:class "flex items-center gap-2 min-w-0"}
         [:span {:class "text-text-500 text-2xs uppercase tracking-wider"} "agent"]
         [:span {:class "text-signal text-sm font-semibold font-mono truncate"} agent-id]]
        (status-chip db agent-id)]
       (when (some? live-tile)
         [:section {:id    "world-canvas"
                    :class "border border-base-800 rounded-md bg-base-900 p-3 overflow-auto"}
          live-tile])
       [:div {:id "world-tiles" :class "flex flex-col gap-3"}
        (when (seq tile-names)
          ;; EAGER (mapv, not for): a lazy seq would defer a throwing tile until
          ;; `->string` serialization OUTSIDE this fn's try/catch, so a bad slot
          ;; would escape the never-throws guard. Realize inside the catch.
          (mapv #(tile-card ctx %) tile-names))]
       (when (and (nil? live-tile) (empty? tile-names))
         [:div {:id    "world-empty"
                :class "text-text-500 text-xs font-mono"}
          (str "agent " agent-id " has no html tiles yet")])])
    (catch :default e
      [:main {:id "world" :class "flex flex-col gap-3 w-full"}
       [:div {:id    "world-error"
              :class "text-error text-xs font-mono"}
        (str "render error: " (.-message e))]])))
