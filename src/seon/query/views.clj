(ns seon.query.views
  "Small, focused, schema-owned render functions for reusable query/diagnostic value schemas:
   - `:seon.query/table` (`render-table`)
   - `:seon.rule/trace` (`render-rule-trace`)
   - `:seon.schema/fingerprint` (`render-fingerprint`)
   - `:seon.walk/reads` (`render-walk-reads`)
   Each render function draws only its own schema and adapts cleanly to `:mode` (`:human` vs `:agent`/`:model`)
   and `:layout` — usable anywhere in Seon (Canvas, Workspace, Boards, Eval output, or nested values)."
  (:require [seon.render.print :as rprint]))

(defn- cell-hiccup [v]
  (cond
    (keyword? v) [:code {:class "mono"} (str v)]
    (symbol? v) [:code {:class "mono"} (str v)]
    (number? v) [:span {:class "mono strong"} (str v)]
    (boolean? v) [:span {:class (str "badge " (if v "green" "red"))} (str v)]
    (string? v) v
    :else [:code {:class "mono tiny"} (let [s (pr-str v)] (if (> (count s) 88) (str (subs s 0 88) "…") s))]))

(defn render-table
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.query/table`."
  {:malli/schema [:=> [:cat :map :seon.query/table] :any]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [_opts {:keys [columns rows total more?]}]
  [:div {:class "table-scroll"}
   [:table {:class "data-table"}
    (when (seq columns)
      [:thead [:tr (for [c columns] [:th (str c)])]])
    [:tbody
     (for [row rows]
       [:tr (for [cell (if (sequential? row) row [row])]
              [:td (cell-hiccup cell)])])]]
   (when more?
     [:div {:class "muted tiny mono" :style "margin-top:4px"}
      (str "Showing " (count rows) " of " total " live rows")])])

(defn render-rule-trace
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.rule/trace`."
  {:malli/schema [:=> [:cat :map :seon.rule/trace] :any]
   :cost/ticks 8000000
   :cost/bytes 4194304}
  [{:keys [mode] :as opts} res]
  [:div {:class "stack-sm"}
   [:div {:class "pill-row"}
    [:span {:class "badge blue mono"} (str "agent " (:agent res))]
    [:span {:class "badge purple mono"} (str "target " (:target res))]
    [:span {:class "pill mono"} (str (:rules-count res) " active rules")]
    [:span {:class "pill mono"} (str (:fired-count res) " fired")]
    [:span {:class "pill mono"} (str (:facts-count res) " WM facts")]
    [:span {:class "pill mono"} (str (:tiles-count res) " tiles")]
    [:span {:class "badge green mono"} (str (:derive-ms res) " ms")]]
   (when (seq (:fired-rows res))
     (render-table opts {:columns ["Stratum" "Rule" "Category" "Fired" "Asserts" "DB Reads"]
                         :rows (:fired-rows res)
                         :total (:fired-count res)
                         :more? (> (long (or (:fired-count res) 0)) (count (:fired-rows res)))}))
   (when (and (= :human (or mode :human)) (seq (:tile-rows res)))
     [:details {:class "fold"}
      [:summary {:class "muted tiny mono"} (str "Projected tiles (" (:tiles-count res) ")")]
      (render-table opts {:columns ["Tile ID" "Schema" "Source" "Zone" "Layout" "View Fn" "Pri"]
                          :rows (:tile-rows res)
                          :total (:tiles-count res)
                          :more? false})])])

(defn render-fingerprint
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.schema/fingerprint`."
  {:malli/schema [:=> [:cat [:map [:db {:optional true} [:maybe :seon.host/db]]
                                  [:main-db {:optional true} [:maybe :seon.host/db]]]
                            :seon.schema/fingerprint] :any]
   :cost/ticks 10000000
   :cost/bytes 8388608}
  [{:keys [db main-db mode] :as opts} res]
  [:div {:class "stack-sm"}
   [:div {:class "pill-row"}
    [:span {:class "badge amber mono"} (str "primary " (:primary res))]
    [:span {:class "badge blue mono"} (str "renderer " (:renderer res))]
    [:span {:class "pill mono"} (str (count (:facets res)) " facet(s)")]
    (when (:eid res) [:span {:class "pill mono"} (str "eid " (:eid res))])]
   (when (seq (:facets res))
     (render-table opts {:columns ["Facet Schema" "Kind" "Attr Hits" "Identity?" "Drawn?" "Complete?"]
                         :rows (mapv (fn [f] [(:schema f) (:kind f) (:hits f) (:has-identity? f) (:drawn? f) (:complete? f)])
                                     (:facets res))
                         :total (count (:facets res))
                         :more? false}))
   (when (and (map? (:entity res)) (= :human (or mode :human)))
     [:details {:class "fold"}
      [:summary {:class "muted tiny mono"} "Live 4-Stage Self-Printer Output (Tree → Hiccup)"]
      (rprint/format-value-hiccup (or main-db db) (:entity res) {:mode :human :limit 10})])])

(defn render-walk-reads
  "Schema-attached renderer (`:seon.render/hiccup`) for `:seon.walk/reads`."
  {:malli/schema [:=> [:cat :map :seon.walk/reads] :any]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [opts res]
  [:div {:class "stack-sm"}
   [:div {:class "pill-row"}
    [:span {:class "badge amber mono"} (str "subject " (:subject res))]
    [:span {:class "badge green mono"} (str (:reads-count res) " datom reads")]
    [:span {:class "pill mono"} (str (:indexed-keys res) " indexed keys")]
    [:span {:class "pill mono"} (str (:ms res) " ms")]]
   (when (seq (:rows res))
     (render-table opts {:columns ["Attribute Read (datahike.reads)" "Reads"]
                         :rows (:rows res)
                         :total (count (:rows res))
                         :more? false}))])
