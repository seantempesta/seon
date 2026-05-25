(ns seon.handlers.fn
  "Renderers for `:seon.fn` entities — fns the agent has defined via
   `(defn …)` or `(def …)` forms. Stamped at the detect-and-tee site
   in `seon.eval/build-tee-entities` via:
     :seon.render/ai   'seon.handlers.fn/render-ai
     :seon.render/html 'seon.handlers.fn/render-html

   Compact: one row per defined fn. The full source already shows in
   the owning `:seon.eval` entity's render — we don't repeat it here."
  (:require
    [clojure.string :as str]))

(defn render-ai
  "One-line summary: `:seon.fn <sym>  ;; arglists  doc`."
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  (let [sym  (or (:seon.fn/sym entity) "?")
        arglists (:seon.fn/arglists entity)
        doc  (:seon.fn/doc entity)
        priv (:seon.fn/private? entity)]
    {:seon.render/text
     (str ":seon.fn " sym
          (when priv " (private)")
          (when (and arglists (not (str/blank? (str arglists))))
            (str "  " arglists))
          (when (and doc (not (str/blank? doc)))
            (str "  ;; " (first (str/split-lines doc)))))}))

(defn render-html
  "Compact card showing the defined fn — symbol, arglists, doc preview."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  (let [sym  (or (:seon.fn/sym entity) "?")
        arglists (:seon.fn/arglists entity)
        doc  (:seon.fn/doc entity)
        priv (:seon.fn/private? entity)]
    {:seon.render/hiccup
     [:div {:class "py-1"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold text-amber-400"} "defn"]
       [:span {:class "text-xs font-mono text-text-100"} sym]
       (when priv
         [:span {:class "text-xs text-text-500"} "(private)"])
       (when (and arglists (not (str/blank? (str arglists))))
         [:span {:class "text-xs font-mono text-text-400"} (str arglists)])]
      (when (and doc (not (str/blank? doc)))
        [:div {:class "text-xs text-text-400 mt-0.5 italic"}
         (first (str/split-lines doc))])]}))
