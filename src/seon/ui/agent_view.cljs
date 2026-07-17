(ns seon.ui.agent-view
  "Pure agent-page formatting from one coordinate-pinned ordinary projection."
  (:require
   [clojure.set :as set]
   [seon.render.surface :as surface]
   [seon.schema :as schema]
   [seon.ui.header :as header]))

(schema/register! ::changed-attrs [:set :qualified-keyword])
(schema/register! ::state [:enum :idle :running :paused :terminated :unknown])
(schema/register! ::projection
  [:map
   [:seon.agent/id :string]
   [::state ::state]
   [::surface/surfaces ::surface/surfaces]
   [::header/projection ::header/projection]])
(schema/register! ::element :seon.render.canvas/hiccup)

(def structural-attrs
  #{:seon.agent/ctx :seon.agent.ctx/name :seon.agent.ctx/priority
    :seon.render/html :seon.render.canvas/content :seon.fn/source
    :seon.fn/read-attrs :seon.agent/run :seon.agent/terminated-at
    :seon.agent.run/status :seon.agent.run/paused-at})

(defn structural-change?
  "Whether one transaction can change the complete agent-page projection."
  [changed-attrs]
  (or (empty? changed-attrs)
      (boolean (seq (set/intersection structural-attrs changed-attrs)))))

(defn- status-chip [state]
  [:span {:class "flex items-center gap-1 text-xs font-mono"
          :data-agent-state (name state)}
   [:span {:class (if (= state :running) "text-signal" "text-text-400")} "●"]
   [:span {:class "text-text-200"} (name state)]])

(defn agent-header
  "Render the agent-local header from ordinary identity and derived state."
  [agent-id state]
  [:header {:id "agent-view-header" :data-agent-state (name state)
            :class "flex items-center justify-between border-b border-base-800 pb-1"}
   [:div {:class "flex items-center gap-2 min-w-0"}
    [:a {:href "/" :class "text-text-400 text-xs font-mono"} "← all agents"]
    [:span {:class "text-signal text-sm font-semibold font-mono"} agent-id]
    [:a {:href (str "/agent/" agent-id "/debug")
         :class "text-text-500 hover:text-amber-300 text-2xs font-mono"}
     "debug"]]
   (status-chip state)])

(defn- primary-panel [s]
  [:section {:id (str "agent-view-primary-" (::surface/selection s))
             :data-agent-primary (::surface/selection s)
             :data-show (str "$selected === '" (::surface/selection s) "'")
             :class (str "agent-view-surface surface-focus relative min-h-0 "
                         "overflow-auto border border-base-800 rounded-md bg-base-900 p-2 h-full")}
   (::surface/expanded s)])

(defn- rail-button [s]
  [:div {:id (str "agent-view-rail-" (::surface/selection s))
         :role "button" :tabindex "0"
         :class "w-full border border-base-800 rounded-md cursor-pointer bg-base-900 overflow-hidden"
         :data-show (str "$selected !== '" (::surface/selection s) "'")
         :data-on:click (str "$selected = '" (::surface/selection s) "'")}
   [:div {:class "px-2 py-1 border-b border-base-800 text-2xs text-text-400 font-mono"}
    (::surface/label s)]
   [:div {:class "overflow-hidden" :style "max-height:20rem;pointer-events:none"}
    (::surface/compact s)]])

(defn render-agent-view
  "Render one complete page from a child-produced ordinary projection."
  [{agent-id :seon.agent/id state ::state surfaces ::surface/surfaces
    header-projection ::header/projection}]
  (let [surfaces (vec (sort-by (juxt (comp - ::surface/touch) ::surface/label)
                               surfaces))
        selected (or (surface/latest-focus-selection surfaces) "canvas")]
    [:main {:id "app-view" :class "flex flex-col gap-2 w-full min-h-0 flex-1 overflow-hidden"
            :data-signals__ifmissing (str "{selected: '" selected "'}")}
     (header/system-header header-projection)
     header/header-spacer
     (agent-header agent-id state)
     [:div {:id "agent-view-layout" :class "grid grid-cols-3 gap-2 min-h-0 flex-1"}
      (into [:div {:id "agent-view-primary"
                   :class "col-span-2 min-h-0 h-full overflow-hidden"}]
            (map primary-panel)
            surfaces)
      (into [:aside {:id "agent-view-context"
                     :class (str "agent-view-rail col-span-1 flex flex-col "
                                 "gap-2 min-h-0 h-full overflow-y-auto")}]
            (map rail-button)
            surfaces)]]))
