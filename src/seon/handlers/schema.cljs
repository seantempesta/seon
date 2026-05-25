(ns seon.handlers.schema
  "Renderers for `:seon.schema` entities — Malli schemas the agent has
   registered via `(seon.schema/register! …)`. Stamped at the detect-
   and-tee site in `seon.eval/build-tee-entities` via:
     :seon.render/ai   'seon.handlers.schema/render-ai
     :seon.render/html 'seon.handlers.schema/render-html"
  (:require
    [clojure.string :as str]))

(defn render-ai
  "One-line summary: `:seon.schema <key>  ;; source one-liner`."
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  (let [k    (or (:seon.schema/key entity) "?")
        src  (:seon.schema/source entity)]
    {:seon.render/text
     (str ":seon.schema " k
          (when (and src (not (str/blank? src)))
            (str "  ;; " (first (str/split-lines src)))))}))

(defn render-html
  "Compact card — schema key + first line of source."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  (let [k    (or (:seon.schema/key entity) "?")
        src  (:seon.schema/source entity)]
    {:seon.render/hiccup
     [:div {:class "py-1"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold text-amber-400"} "schema"]
       [:span {:class "text-xs font-mono text-text-100"} (str k)]]
      (when (and src (not (str/blank? src)))
        [:pre {:class "text-xs text-text-400 font-mono mt-0.5 whitespace-pre-wrap"}
         (first (str/split-lines src))])]}))
