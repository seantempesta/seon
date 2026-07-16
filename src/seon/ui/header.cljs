(ns seon.ui.header
  "Pure global header formatting from an ordinary database projection."
  (:require [seon.schema :as schema]))

(schema/register! ::brand-name [:string {:min 1}])
(schema/register! ::agent-count [:int {:min 0}])
(schema/register! ::datom-count [:int {:min 0}])
(schema/register! ::running-count [:int {:min 0}])
(schema/register! ::projection
  [:map {:closed true}
   [::brand-name ::brand-name]
   [::agent-count ::agent-count]
   [::running-count ::running-count]
   [::datom-count ::datom-count]])

(def default-projection
  {::brand-name "seon" ::agent-count 0 ::running-count 0 ::datom-count 0})

(defn system-header
  "Render the persistent header from eager ordinary data only."
  [projection]
  (let [{::keys [brand-name agent-count running-count datom-count]}
        (merge default-projection projection)]
    [:header {:id "system-header"
              :data-agent-count agent-count
              :data-running-agents running-count
              :style "top:0"
              :class (str "fixed left-0 right-0 z-20 flex items-center gap-x-4 "
                          "border-b border-base-800 bg-base-900/95 px-3 py-1.5 "
                          "text-xs font-mono")}
     [:a {:href "/" :class "text-text-100 font-semibold hover:text-amber-300"}
      [:span {:class "text-amber-400"} "◆"] " " brand-name]
     [:span {:class "text-text-700"} "│"]
     [:span {:class "text-text-200"} (str agent-count)]
     [:span {:class "text-text-500"} (if (= 1 agent-count) "agent" "agents")]
     [:span {:class "text-text-700"} "│"]
     [:a {:href "/data" :class "text-text-400 hover:text-amber-300"}
      (str datom-count " ⛁ datoms")]
     [:span {:class "ml-auto flex items-center gap-3"}
      [:a {:href "/" :class "text-text-400 hover:text-amber-300"} "home"]
      [:span {:class (if (pos? running-count)
                       "text-amber-400" "text-text-600")}
       "●"]]]))

(def header-spacer
  [:div {:id "system-header-spacer" :style "height:2.25rem"}])
