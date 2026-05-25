(ns seon.handlers.ns
  "Renderers for `:seon.ns` entities — namespaces the agent has created
   via `(ns …)` forms. Stamped at the detect-and-tee site in
   `seon.eval/build-tee-entities` via:
     :seon.render/ai   'seon.handlers.ns/render-ai
     :seon.render/html 'seon.handlers.ns/render-html"
  (:require
    [clojure.string :as str]))

(defn render-ai
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  (let [n (or (:seon.ns/name entity) "?")]
    {:seon.render/text (str ":seon.ns " n)}))

(defn render-html
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  (let [n (or (:seon.ns/name entity) "?")
        src (:seon.ns/source entity)]
    {:seon.render/hiccup
     [:div {:class "py-1"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold text-amber-400"} "ns"]
       [:span {:class "text-xs font-mono text-text-100"} (str n)]]
      (when (and src (not (str/blank? src)))
        [:pre {:class "text-xs text-text-400 font-mono mt-0.5 whitespace-pre-wrap"}
         (first (str/split-lines src))])]}))
