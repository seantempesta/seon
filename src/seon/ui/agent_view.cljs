(ns seon.ui.agent-view
  "The live per-agent view derived from one frozen database snapshot.

   The most recently agent-updated surface is selected in the primary panel.
   Every resolved
   context block with a nonblank HTML render appears as a compact card in the
   right rail; selecting one shows the same rendered hiccup in the primary
   panel. AI-only blocks are absent. Surface recency derives from database
   transactions and renderer read-sets; selection is browser-local state."
  (:require
    [clojure.set :as set]
    [seon.derive :as derive]
    [seon.render.surface :as surface]
    [seon.schema :as schema]
    [seon.ui.header :as header]))

(schema/register! ::changed-attrs [:set :qualified-keyword])
(schema/register! ::surface-attrs [:set :qualified-keyword])
(schema/register! ::structural-attrs [:set :qualified-keyword])
(schema/register! ::header-attrs [:set :qualified-keyword])
(schema/register! ::agent-state-attrs [:set :qualified-keyword])
(schema/register! ::dependencies
  [:map
   [::surface-attrs ::surface-attrs]
   [::structural-attrs ::structural-attrs]
   [::header-attrs ::header-attrs]
   [::agent-state-attrs ::agent-state-attrs]])
(declare agent-view)

(def ^:private state-display
  {:running    {:dot "●" :class "text-signal"   :label "running"}
   :idle       {:dot "●" :class "text-text-400" :label "idle"}
   :paused     {:dot "⚠" :class "text-warning"  :label "paused"}
   :terminated {:dot "✗" :class "text-text-500" :label "terminated"}
   :unknown    {:dot "?" :class "text-warning" :label "unknown"}})

(defn- status-chip
  "The agent's derived FSM state as a dot and label."
  [state]
  (let [{:keys [dot class label]} (or (state-display state)
                                      {:dot "●" :class "text-text-400"
                                       :label (name state)})]
    [:span {:class "flex items-center gap-1 text-xs font-mono"
            :data-agent-state (name state)}
     [:span {:class class} dot]
     [:span {:class "text-text-200"} label]]))

(defn- agent-header
  "The stable per-agent header render unit derived from one database value."
  [dbv agent-id]
  (let [state (try
                (derive/derive-state dbv agent-id)
                (catch :default _ :unknown))]
    [:header {:id "agent-view-header"
              :data-agent-state (name state)
              :class "flex items-center justify-between border-b border-base-800 pb-1"}
     [:div {:class "flex items-center gap-2 min-w-0"}
      [:a {:href "/agents" :class "text-text-400 text-xs font-mono shrink-0"}
       "← all agents"]
      [:span {:class "text-text-500 text-2xs uppercase tracking-wider"} "agent"]
      [:span {:class "text-signal text-sm font-semibold font-mono truncate"} agent-id]
      [:a {:href (str "/agent/" agent-id "/debug")
           :class "text-text-500 hover:text-amber-300 text-2xs font-mono"}
       "debug"]]
     (status-chip state)]))

(defn- transcript-block? [block-name]
  (= block-name :transcript))

(defn- bottom-effect
  "Datastar effect that re-anchors a transcript scroller after its revision morph."
  [touch]
  (str "setTimeout(() => { $el.scrollTop = $el.scrollHeight }, 0); " touch))

(defn- primary-panel
  "One selectable primary-panel body. Transcript bodies follow their tail."
  [selection block-name expanded touch]
  [:section (cond-> {:id (str "agent-view-primary-" selection)
                     :data-agent-primary selection
                     :data-show (str "$selected === '" selection "'")
                     :class (str "agent-view-surface tile-hero min-h-0 overflow-auto border "
                                 "border-base-800 rounded-md bg-base-900 p-2 h-full")}
              (transcript-block? block-name)
              (assoc :data-effect (bottom-effect touch)))
   expanded])

(defn- rail-button
  "A compact selectable card for a non-focused primary-panel body."
  [selection block-name label compact touch]
  [:div {:id (str "agent-view-rail-" selection)
         :role "button"
         :tabindex "0"
         :aria-label (str "Show " label " in the primary view")
         :class (str "w-full text-left border border-base-800 rounded-md cursor-pointer "
                     "bg-base-900 overflow-hidden hover:border-base-700")
         :style (str "order:" (- touch))
         :data-show (str "$selected !== '" selection "'")
         :data-class (str "{'border-amber-700': $selected === '" selection "'}")
         (keyword "data-on:click") (str "$selected = '" selection
                                         "'; $manualselection = true")
         (keyword "data-on:keydown")
         (str "if ($event.key === 'Enter' || $event.key === ' ') "
              "{ $event.preventDefault(); $selected = '" selection
              "'; $manualselection = true }")}
   [:div {:class "px-2 py-1 border-b border-base-800 text-2xs text-text-400 font-mono"}
    label]
   ;; The HTML twin may itself contain buttons/forms. The rail is a visual
   ;; preview; interaction belongs to the selected primary render.
   [:div (cond-> {:class "overflow-hidden"
                  :aria-hidden "true"
                  :style "max-height:20rem;pointer-events:none"}
           (transcript-block? block-name)
           (assoc :data-effect (bottom-effect touch)))
    compact]])

(def ^:private structural-attrs
  "Changes that can add/remove/rebind surfaces, requiring a shell morph."
  #{:seon.agent/ctx
    :seon.agent.ctx/name
    :seon.agent.ctx/priority
    :seon.render/html
    :seon.fn/sym
    :seon.fn/spec
    :seon.fn/ns})

(defn structural-change?
  "Whether changed attrs can add, remove, or rebind an agent-view surface."
  {:malli/schema [:=> [:catn [::changed-attrs ::changed-attrs]] :boolean]}
  [changed-attrs]
  (boolean (seq (set/intersection structural-attrs changed-attrs))))

(def ^:private header-attrs
  "Stored inputs that materially change the fleet status header.

   The datom set is intentionally sampled on these meaningful changes,
   not on every program-graph bookkeeping transaction."
  (set/union
    derive/agent-state-read-attrs
    #{:seon.agent.run/agent
      :seon.agent.run/closed-at
      :seon.agent.run/closed-reason
      :seon.agent.run/resumed-at
      :seon.agent.turn/at
      :seon.agent.turn/status
      :seon.agent.turn/llm-usage
      :seon.eval/id
      :seon.eval/ok?}))

(defn agent-view-dependencies
  "Cached-gradient dependency projection for one live agent feed.

   Values come from the same database-owned context root and analyzer-produced
   renderer read-sets as rendering. The feed refreshes this projection after a
   structural change; ordinary transactions can then be classified without
   rendering or rebuilding the context graph."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  ::dependencies]}
  [dbv agent-id]
  (let [catalog (surface/surface-catalog dbv agent-id)]
    {::surface-attrs (into #{} (mapcat ::surface/read-attrs) catalog)
     ::structural-attrs structural-attrs
     ::header-attrs header-attrs
     ::agent-state-attrs derive/agent-state-read-attrs}))

(defn- focus-marker [selection touch]
  [:div {:id "agent-view-focus-revision"
         :style "display:none"
         :data-effect
         (str "if ($seenrevision !== " touch ") { "
              "if (!$manualselection) $selected = '" selection "'; "
              "$seenrevision = " touch " }")}])

(defn- surface-elements [surface]
  (let [selection (::surface/selection surface)
        block-name (:seon.agent.ctx/name surface)
        label (::surface/label surface)
        touch (::surface/touch surface)]
    [(primary-panel selection block-name (::surface/expanded surface) touch)
     (rail-button selection block-name label (::surface/compact surface) touch)]))

(defn agent-view-changes
  "Complete ID-addressed elements affected by one coalesced transaction batch.

   Structural program/context changes return the full `#app-view`. Ordinary
   data changes render only intersecting surface read-sets plus the header and
   focus-revision controller. Unknown/unrelated attrs never rerender a surface."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]
                             [::changed-attrs ::changed-attrs]]
                  [:vector :any]]}
  [dbv agent-id changed-attrs]
  (if (structural-change? changed-attrs)
    [(agent-view dbv agent-id)]
    (let [catalog (surface/surface-catalog dbv agent-id)
          affected-selections
          (into #{}
                (comp
                  (filter #(seq (set/intersection
                                  changed-attrs
                                  (::surface/read-attrs %))))
                  (map ::surface/selection))
                catalog)
          affected
          (surface/materialize-surfaces
            {:seon.db/db dbv
             :seon.agent/id agent-id
             ::surface/selections affected-selections})
          latest-selection (surface/latest-focus-selection catalog)
          latest (some #(when (= latest-selection (::surface/selection %)) %)
                       catalog)]
      ;; A formerly-present conditional renderer returning nil requires a shell
      ;; morph so Datastar removes both of its old faces. Ordinary updates stay
      ;; on the small, ID-addressed path.
      (if (< (count affected) (count affected-selections))
        [(agent-view dbv agent-id)]
        (into (cond-> [(focus-marker (::surface/selection latest)
                                      (::surface/focus-touch latest))]
                (seq (set/intersection header-attrs changed-attrs))
                (conj (header/system-header dbv))
                (seq (set/intersection derive/agent-state-read-attrs
                                       changed-attrs))
                (conj (agent-header dbv agent-id)))
              (mapcat surface-elements)
              affected)))))

(defn agent-view
  "Render one agent's canvas and HTML context blocks from `db`."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  :any]}
  [db agent-id]
  (try
    (let [catalog (surface/surface-catalog db agent-id)
          surfaces (->> (surface/materialize-surfaces
                          {:seon.db/db db :seon.agent/id agent-id})
                        (sort-by (juxt (comp - ::surface/touch)
                                       ::surface/label))
                        vec)
          present-selections (into #{} (map ::surface/selection) surfaces)
          present-catalog (filterv #(contains? present-selections
                                               (::surface/selection %))
                                   catalog)
          latest-selection (surface/latest-focus-selection present-catalog)
          latest (some #(when (= latest-selection (::surface/selection %)) %)
                       present-catalog)
          latest-touch (::surface/focus-touch latest)]
      [:main {:id "app-view"
              :class "flex flex-col gap-2 w-full min-h-0 flex-1 overflow-hidden"
              :data-signals__ifmissing
              (str "{selected: '" latest-selection
                   "', seenrevision: " latest-touch
                   ", manualselection: false}")
              :data-effect (str "if ($selected !== 'canvas' && "
                                "!document.querySelector('[data-agent-primary=\"' + "
                                "$selected + '\"]')) { $selected = 'canvas'; "
                                "$manualselection = false }")}
       (header/system-header db)
       header/header-spacer
       (focus-marker latest-selection latest-touch)
       (agent-header db agent-id)
       [:div {:id "agent-view-layout"
              :class "grid grid-cols-3 gap-2 min-h-0 flex-1"}
        [:div {:id "agent-view-primary"
               :class "col-span-2 min-h-0 h-full overflow-hidden"}
         (doall
           (map (fn [surface]
                  (primary-panel (::surface/selection surface)
                                 (:seon.agent.ctx/name surface)
                                 (::surface/expanded surface)
                                 (::surface/touch surface)))
                surfaces))]
        [:aside {:id "agent-view-context"
                 :class (str "agent-view-rail col-span-1 flex flex-col gap-2 "
                             "min-h-0 h-full overflow-y-auto")}
         (doall
           (map (fn [surface]
                  (rail-button (::surface/selection surface)
                               (:seon.agent.ctx/name surface)
                               (::surface/label surface)
                               (::surface/compact surface)
                               (::surface/touch surface)))
                surfaces))]]])
    (catch :default e
      [:main {:id "app-view" :class "flex flex-col gap-3 w-full"}
       [:div {:id "agent-view-error" :class "text-error text-xs font-mono"}
        (str "render error: " (.-message e))]])))
