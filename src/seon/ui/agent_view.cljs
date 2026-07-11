(ns seon.ui.agent-view
  "The live per-agent view derived from one frozen database snapshot.

   The most recently agent-updated surface is selected in the primary panel.
   Every resolved
   context block with a nonblank HTML render appears as a compact card in the
   right rail; selecting one shows the same rendered hiccup in the primary
   panel. AI-only blocks are absent. Surface recency derives from database
   transactions and renderer read-sets; selection is browser-local state."
  (:require
    [seon.agent.ctx :as agent-ctx]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.render :as render]
    [seon.ui.header :as header]))

(def ^:private state-display
  {:running    {:dot "●" :class "text-signal"   :label "running"}
   :idle       {:dot "●" :class "text-text-400" :label "idle"}
   :paused     {:dot "⚠" :class "text-warning"  :label "paused"}
   :terminated {:dot "✗" :class "text-text-500" :label "terminated"}})

(defn- status-chip
  "The agent's derived FSM state as a dot and label."
  [db agent-id]
  (let [state (try (derive/derive-state db agent-id) (catch :default _ :idle))
        {:keys [dot class label]} (or (state-display state)
                                      {:dot "●" :class "text-text-400"
                                       :label (name state)})]
    [:span {:class "flex items-center gap-1 text-xs font-mono"}
     [:span {:class class} dot]
     [:span {:class "text-text-200"} label]]))

(defn- selection-key
  "Stable browser selection key for one resolved context block."
  [block-name]
  (str "context-"
       (when-let [ns (namespace block-name)] (str ns "-"))
       (name block-name)))

(defn- primary-panel
  "One selectable primary-panel body."
  [selection hiccup]
  [:section {:id (str "agent-view-primary-" selection)
             :data-agent-primary selection
             :data-show (str "$selected === '" selection "'")
             :style (when (= selection "canvas")
                      "min-height:10rem;max-height:min(38vh,22rem)")
             :class (str "agent-view-surface tile-hero min-h-0 overflow-auto border "
                         "border-base-800 rounded-md bg-base-900 p-2 "
                         (when-not (= selection "canvas") "h-full"))}
   hiccup])

(defn- rail-button
  "A compact selectable card for one primary-panel body."
  [selection label hiccup]
  [:div {:role "button"
         :tabindex "0"
         :aria-label (str "Show " label " in the primary view")
         :class (str "w-full text-left border border-base-800 rounded-md cursor-pointer "
                     "bg-base-900 overflow-hidden hover:border-base-700")
         :data-class (str "{'border-amber-700': $selected === '" selection "'}")
         (keyword "data-on:click") (str "$selected = '" selection "'")
         (keyword "data-on:keydown")
         (str "if ($event.key === 'Enter' || $event.key === ' ') "
              "{ $event.preventDefault(); $selected = '" selection "' }")}
   [:div {:class "px-2 py-1 border-b border-base-800 text-2xs text-text-400 font-mono"}
    label]
   ;; The HTML twin may itself contain buttons/forms. The rail is a visual
   ;; preview; interaction belongs to the selected primary render.
   [:div {:class "overflow-hidden"
          :aria-hidden "true"
          :style "max-height:7rem;pointer-events:none"}
    hiccup]])

(defn- agent-attr-touch
  "Latest transaction by this agent touching `attr` on its own entity."
  [dbv agent-id attr]
  (or
    (ffirst
      (db/query
        {:seon.db/db (db/history dbv)
         :seon.db/query
         '[:find (max ?tx)
           :in $ ?aid ?attr
           :where
           [?e :seon.agent/id ?aid]
           [?e ?attr _ ?tx]
           [?tx :seon.db/agent-id ?aid]]
         :seon.db/args [agent-id attr]}))
    0))

(defn- renderer-touch
  "Agent-scoped database touch coordinate for a symbolic HTML renderer."
  [dbv agent-id renderer]
  (if (symbol? renderer)
    (or (::render-fns/touch
          (render-fns/renderer-touch {:seon.db/db dbv
                                      :seon.agent/id agent-id
                                      :seon.render/html renderer}))
        0)
    0))

(defn- canvas-touch
  "Canvas recency from its explicit slot and its derived tile renderer."
  [dbv agent-id]
  (let [pinned (try
                 (some-> (db/pull dbv [:seon.render.canvas/content]
                                  [:seon.agent/id agent-id])
                         :seon.render.canvas/content
                         (db/decode-edn-value :seon.render.canvas/content))
                 (catch :default _ nil))
        derived (when-not (symbol? pinned)
                  (::render-fns/tile-sym
                    (render-fns/last-updated-tile
                      {:seon.db/db dbv :seon.agent/id agent-id})))
        renderer (when (symbol? (or pinned derived)) (or pinned derived))]
    (max (agent-attr-touch dbv agent-id :seon.render.canvas/content)
         (renderer-touch dbv agent-id renderer))))

(defn agent-view
  "Render one agent's canvas and HTML context blocks from `db`."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  :any]}
  [db agent-id]
  (try
    (let [ctx         {:seon.db/db db :seon.agent/id agent-id}
          blocks      (agent-ctx/rendered-context-blocks ctx #{:html})
          canvas      (:seon.render/hiccup
                        (render/render-agent-canvas {:seon.agent/id agent-id
                                                   :seon.db/db db}))
          context-surfaces
          (mapv (fn [{nm :seon.agent.ctx/name
                      h  :seon.render/hiccup
                      renderer :seon.render/html}]
                              {:selection (selection-key nm)
                               :label (name nm)
                               :hiccup h
                               :touch (renderer-touch db agent-id renderer)})
                blocks)
          surfaces (->> (conj context-surfaces
                              {:selection "canvas"
                               :label "canvas"
                               :hiccup (or canvas
                                           [:div {:class "p-2 text-text-500 text-xs"}
                                            "No canvas render yet."])
                               :touch (canvas-touch db agent-id)})
                        (sort-by (juxt (comp - :touch) :label))
                        vec)
          latest (or (first surfaces) {:selection "canvas" :touch 0})
          latest-selection (:selection latest)
          latest-touch (:touch latest)]
      [:main {:id "app-view"
              :class "flex flex-col gap-2 w-full min-h-screen"
              :data-signals (str "{selected: '" latest-selection
                                 "', seenrevision: " latest-touch "}")
              :data-effect (str "if ($seenrevision !== " latest-touch ") { "
                                "$selected = '" latest-selection "'; "
                                "$seenrevision = " latest-touch "; } "
                                "if ($selected !== 'canvas' && "
                                "!document.querySelector('[data-agent-primary=\"' + "
                                "$selected + '\"]')) $selected = 'canvas'")}
       (header/system-header db)
       header/header-spacer
       [:header {:id "agent-view-header"
                 :class "flex items-center justify-between border-b border-base-800 pb-1"}
        [:div {:class "flex items-center gap-2 min-w-0"}
         [:a {:href "/agents" :class "text-text-400 text-xs font-mono shrink-0"}
          "← all agents"]
         [:span {:class "text-text-500 text-2xs uppercase tracking-wider"} "agent"]
         [:span {:class "text-signal text-sm font-semibold font-mono truncate"} agent-id]
         [:a {:href (str "/agent/" agent-id "/debug")
              :class "text-text-500 hover:text-amber-300 text-2xs font-mono"}
          "debug"]]
        (status-chip db agent-id)]
       [:div {:id "agent-view-layout"
              :class "grid grid-cols-3 gap-2 min-h-0 flex-1"}
        [:div {:id "agent-view-primary" :class "col-span-2 min-h-0"}
         (doall
           (map (fn [{:keys [selection hiccup]}]
                  (primary-panel selection hiccup))
                surfaces))]
        [:aside {:id "agent-view-context"
                 :class "agent-view-rail col-span-1 flex flex-col gap-2 min-h-0 overflow-auto"}
         (doall
           (map (fn [{:keys [selection label hiccup]}]
                  (rail-button selection label hiccup))
                surfaces))]]])
    (catch :default e
      [:main {:id "app-view" :class "flex flex-col gap-3 w-full"}
       [:div {:id "agent-view-error" :class "text-error text-xs font-mono"}
        (str "render error: " (.-message e))]])))
