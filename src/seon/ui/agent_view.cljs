(ns seon.ui.agent-view
  "The live per-agent view derived from one frozen database snapshot.

   The most recently agent-updated surface is selected in the primary panel.
   Every resolved
   context block with a nonblank HTML render appears as a compact card in the
   right rail; selecting one shows the same rendered hiccup in the primary
   panel. AI-only blocks are absent. Surface recency derives from database
   transactions and runtime-observed database reads; selection is browser-local
   state."
  (:require
    [clojure.set :as set]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.render.surface :as surface]
    [seon.schema :as schema]
    [seon.ui.header :as header]
    [seon.ui.html :as html]))

(schema/register! ::changed-attrs [:set :qualified-keyword])
(schema/register! ::surface-attrs [:set :qualified-keyword])
(schema/register! ::structural-attrs [:set :qualified-keyword])
(schema/register! ::surface-read-attrs
                  [:map-of :seon.render.surface/selection
                   :seon.render.surface/read-attrs])
(schema/register! ::surface-read-observations
                  [:map-of :seon.render.surface/selection
                   :seon.db/read-observations])
(schema/register! ::dependencies
  [:map
   [::surface-attrs ::surface-attrs]
   [::structural-attrs ::structural-attrs]
   [::surface-read-attrs ::surface-read-attrs]
   [::surface-read-observations ::surface-read-observations]])
(schema/register! ::element :seon.render.canvas/hiccup)
(schema/register! ::elements [:vector :seon.render.canvas/hiccup])
(schema/register! ::agent-header-html :string)
(schema/register! ::system-header-html :string)
(schema/register! ::render-request
                  [:map
                   [:seon.db/db :seon.db/db-val]
                   [:seon.agent/id :seon.agent/id]
                   [::agent-header-html {:optional true} ::agent-header-html]
                   [::system-header-html {:optional true} ::system-header-html]])
(schema/register! ::render-result
                  [:map [::element ::element] [::dependencies ::dependencies]])
(schema/register! ::transition-request
                  [:map
                   [:seon.db/db :seon.db/db-val]
                   [:seon.agent/id :seon.agent/id]
                   [::changed-attrs ::changed-attrs]
                   [::agent-header-html {:optional true} ::agent-header-html]
                   [::system-header-html {:optional true} ::system-header-html]
                   [::dependencies ::dependencies]])
(schema/register! ::transition-result
                  [:map [::elements ::elements] [::dependencies ::dependencies]])
(declare render-agent-view)

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

(defn agent-header
  "The stable per-agent header render unit derived from one database value."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  ::element]}
  [dbv agent-id]
  (let [state (try
                (derive/derive-state dbv agent-id)
                (catch :default _ :unknown))]
    [:header {:id "agent-view-header"
              :data-agent-state (name state)
              :class "flex items-center justify-between border-b border-base-800 pb-1"}
     [:div {:class "flex items-center gap-2 min-w-0"}
      [:a {:href "/" :class "text-text-400 text-xs font-mono shrink-0"}
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

(defn- pin-icon
  "Small current-color pushpin used by the page-local focus control."
  []
  [:svg {:viewBox "0 0 24 24"
         :width "14"
         :height "14"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M9 3h6l-1 6 3 3v2H7v-2l3-3-1-6Z"}]
   [:path {:d "M12 14v7"}]])

(defn- pin-button
  "Explicitly keep or release one surface as this tab's primary selection."
  [selection]
  [:button {:type "button"
            :aria-label "Keep this surface selected"
            :title "Keep this surface selected"
            :class (str "absolute top-2 right-2 z-10 grid place-items-center "
                        "size-7 rounded border border-base-700 bg-base-900/90 "
                        "text-text-500 hover:text-amber-300 hover:border-amber-700")
            :data-class (str "{'text-amber-300 border-amber-700': "
                             "$pinnedselection === '" selection "'}")
            (keyword "data-attr:aria-pressed")
            (str "$pinnedselection === '" selection "'")
            (keyword "data-on:click")
            (str "$pinnedselection = $pinnedselection === '" selection
                 "' ? '' : '" selection "'")}
   (pin-icon)])

(defn- primary-panel
  "One selectable primary-panel body. Transcript bodies follow their tail."
  [selection block-name expanded touch]
  [:section (cond-> {:id (str "agent-view-primary-" selection)
                     :data-agent-primary selection
                     :data-show (str "$selected === '" selection "'")
                     :class (str "agent-view-surface surface-focus relative min-h-0 overflow-auto border "
                                 "border-base-800 rounded-md bg-base-900 p-2 h-full")}
              (transcript-block? block-name)
              (assoc :data-effect (bottom-effect touch)))
   (pin-button selection)
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
         (keyword "data-on:click") (str "$selected = '" selection "'")
         (keyword "data-on:keydown")
         (str "if ($event.key === 'Enter' || $event.key === ' ') "
              "{ $event.preventDefault(); $selected = '" selection "' }")}
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

(defn- dependencies-for
  [catalog surfaces]
  {::surface-attrs (into #{} (mapcat ::surface/read-attrs) catalog)
   ::structural-attrs structural-attrs
   ::surface-read-attrs
   (into {} (map (juxt ::surface/selection ::surface/read-attrs)) catalog)
   ::surface-read-observations
   (into {} (map (juxt ::surface/selection
                       :seon.db/read-observations)) surfaces)})

(defn- reads-changed?
  "Whether a unit's captured semantic reads differ at `dbv`.

   An empty capture is conservative: its analyzer attr gate earned the check,
   but there is no exact observation capable of proving the unit unchanged."
  [dbv observations]
  (or (empty? observations)
      (some #(db/read-observation-changed?
               {:seon.db/db dbv :seon.db/read-observation %})
            observations)))

(defn- focus-marker [selection touch]
  [:div {:id "agent-view-focus-revision"
         :style "display:none"
         :data-effect
         (str "if ($seenrevision !== " touch ") { "
              "$selected = $pinnedselection || '" selection "'; "
              "$seenrevision = " touch " }")}])

(defn- surface-elements [surface]
  (let [selection (::surface/selection surface)
        block-name (:seon.agent.ctx/name surface)
        label (::surface/label surface)
        touch (::surface/touch surface)]
    [(primary-panel selection block-name (::surface/expanded surface) touch)
     (rail-button selection block-name label (::surface/compact surface) touch)]))

(defn- view-element
  [surfaces latest-selection latest-touch system-header agent-header*]
  [:main {:id "app-view"
          :class "flex flex-col gap-2 w-full min-h-0 flex-1 overflow-hidden"
          :data-signals__ifmissing
          (str "{selected: '" latest-selection
               "', seenrevision: " latest-touch
               ", pinnedselection: ''}")
          :data-effect
          (str "if ($pinnedselection && "
               "!document.querySelector('[data-agent-primary=\"' + "
               "$pinnedselection + '\"]')) { "
               "$pinnedselection = ''; $selected = '" latest-selection "' } "
               "else if ($selected !== 'canvas' && "
               "!document.querySelector('[data-agent-primary=\"' + "
               "$selected + '\"]')) { $selected = '" latest-selection "' }")}
   system-header
   header/header-spacer
   (focus-marker latest-selection latest-touch)
   agent-header*
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

(defn render-agent-view
  "Render one agent view plus its runtime-only exact database dependencies."
  {:malli/schema [:=> [:cat ::render-request]
                  ::render-result]}
  [{db :seon.db/db agent-id :seon.agent/id
    serialized-agent-header ::agent-header-html
    serialized-system-header ::system-header-html}]
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
          latest-touch (::surface/focus-touch latest)
          system-header-element
          (if serialized-system-header
            (html/raw serialized-system-header)
            (header/system-header db))
          agent-header-element
          (if serialized-agent-header
            (html/raw serialized-agent-header)
            (agent-header db agent-id))]
      {::element
       (view-element surfaces latest-selection latest-touch
                     system-header-element
                     agent-header-element)
       ::dependencies
       (dependencies-for catalog surfaces)})
    (catch :default e
      {::element
       [:main {:id "app-view" :class "flex flex-col gap-3 w-full"}
        [:div {:id "agent-view-error" :class "text-error text-xs font-mono"}
         (str "render error: " (.-message e))]]
       ::dependencies
       {::surface-attrs #{:seon.render.canvas/content}
        ::structural-attrs structural-attrs
        ::surface-read-attrs {"canvas" #{:seon.render.canvas/content}}
        ::surface-read-observations {}}})))

(defn agent-view
  "Render one agent's canvas and HTML context blocks from `db`."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  ::element]}
  [db agent-id]
  (::element (render-agent-view {:seon.db/db db :seon.agent/id agent-id})))

(defn transition
  "Render only units whose captured database results changed."
  {:malli/schema [:=> [:cat ::transition-request] ::transition-result]}
  [{dbv :seon.db/db agent-id :seon.agent/id changed-attrs ::changed-attrs
    serialized-agent-header ::agent-header-html
    serialized-system-header ::system-header-html
    dependencies ::dependencies}]
  (if (structural-change? changed-attrs)
    (let [rendered (render-agent-view
                     (cond-> {:seon.db/db dbv :seon.agent/id agent-id}
                       serialized-agent-header
                       (assoc ::agent-header-html serialized-agent-header)
                       serialized-system-header
                       (assoc ::system-header-html serialized-system-header)))]
      {::elements [(::element rendered)]
       ::dependencies (::dependencies rendered)})
    (let [surface-read-attrs (::surface-read-attrs dependencies)
          surface-observations (::surface-read-observations dependencies)
          affected-selections
          (into #{}
                (keep (fn [[selection attrs]]
                        (when (and (seq (set/intersection changed-attrs attrs))
                                   (reads-changed?
                                     dbv (get surface-observations selection [])))
                          selection)))
                surface-read-attrs)
          affected
          (if (seq affected-selections)
            (surface/materialize-surfaces
              {:seon.db/db dbv
               :seon.agent/id agent-id
               ::surface/selections affected-selections})
            [])]
      ;; A conditional surface disappearing requires a complete shell morph.
      (if (< (count affected) (count affected-selections))
        (let [rendered (render-agent-view
                         (cond-> {:seon.db/db dbv :seon.agent/id agent-id}
                           serialized-agent-header
                           (assoc ::agent-header-html
                                  serialized-agent-header)
                           serialized-system-header
                           (assoc ::system-header-html
                                  serialized-system-header)))]
          {::elements [(::element rendered)]
           ::dependencies (::dependencies rendered)})
        (let [catalog (when (seq affected-selections)
                        (surface/surface-catalog dbv agent-id))
              latest-selection (when catalog
                                 (surface/latest-focus-selection catalog))
              latest (when catalog
                       (some #(when (= latest-selection
                                        (::surface/selection %)) %)
                             catalog))
              next-dependencies
              (cond-> dependencies
                (seq affected)
                (update ::surface-read-observations
                        into
                        (map (juxt ::surface/selection
                                   :seon.db/read-observations) affected)))
              elements
              (into (cond-> []
                      catalog
                      (conj (focus-marker (::surface/selection latest)
                                          (::surface/focus-touch latest))))
                    (mapcat surface-elements)
                    affected)]
          {::elements elements ::dependencies next-dependencies})))))
