(ns seon.render.print.policy
  "Stage 2 of 4-Stage Printer: Declarative `:rule.preview/*` rule-derived O(1) print policy."
  (:require [datahike.api :as d]
            [seon.rule :as rules]
            [seon.store :as store]))

(defn preview-rules
  "Declarative `:seon.rule/*` rules governing nested sub-schema layout, error grouping, large collections, and multi-facets."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000
   :cost/bytes 131072}
  []
  [{:seon.rule/id :rule.preview/nested-sub-schema
    :seon.rule/doc "Map depth to sub-schema renderer layout (:expanded-card -> :compact-row -> :ref-pill) and priority key order."
    :seon.rule/category :present :seon.rule/stratum 34 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?p :policy/subject :print]]
    :seon.rule/assert '[[?p :policy/depth-0-layout :expanded-card]
                        [?p :policy/depth-1-layout :compact-row]
                        [?p :policy/deep-layout :ref-pill]
                        [?p :policy/max-depth 2]
                        [?p :policy/priority-keys [:error :status :title :ok?]]]}
   {:seon.rule/id :rule.preview/errors-by-kind
    :seon.rule/doc "Group large (> 10) :seon.error/entity collections by :seon.error/kind and surface the newest 5 items."
    :seon.rule/category :present :seon.rule/stratum 35 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?p :policy/subject :print]]
    :seon.rule/assert '[[?p :policy/errors-by-kind? true]
                        [?p :policy/errors-threshold 10]
                        [?p :policy/errors-newest 5]]}
   {:seon.rule/id :rule.preview/large-collection
    :seon.rule/doc "Force bounded smart preview + expand hint whenever a collection exceeds 20 items."
    :seon.rule/category :present :seon.rule/stratum 36 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/total ?n] [:guard (> ?n 20)] [:optional [?e :ctx/limit ?lim]]]
    :seon.rule/assert '[[?e :view/tag :tag/preview-sliced]
                        [?e :view.param/preview-limit (if (some? ?lim) ?lim 20)]
                        [?e :view.param/show-expand-hint? true]
                        [?e :view/layout :compact-card]
                        [?e :ctx/why (str "Large collection (" ?n " items) — bounded smart preview")]]}
   {:seon.rule/id :rule.preview/multi-facet-entity
    :seon.rule/doc "Tag entities satisfying >= 2 Malli schemas via `seon.schema.index/facets-of`."
    :seon.rule/category :present :seon.rule/stratum 36 :seon.rule/enabled? true :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind ?primary] [:count ?n [:facet ?e ?sch]] [:guard (>= ?n 2)] [:facet ?e ?sch] [:guard (not= ?sch ?primary)]]
    :seon.rule/assert '[[?e :view/tag :tag/multi-facet]
                        [?e :view.param/facet-count ?n]
                        [?e :view.param/extra-facet ?sch]
                        [?e :ctx/why (str "Multi-facet entity (" ?n " schemas, also " ?sch ")")]]}])

(def ^:private preview-rule-ids
  [:rule.preview/nested-sub-schema :rule.preview/errors-by-kind
   :rule.preview/large-collection :rule.preview/multi-facet-entity])

(defonce ^:private ^:seon.flow/edge policy-cache (store/lru-cache 16))

(defn- load-preview-rules [db]
  (let [defaults (preview-rules)]
    (if-not (and db (contains? (:schema db) :seon.rule/id))
      defaults
      (let [by-id (into {} (map (juxt :seon.rule/id identity)) defaults)
            rows (d/q '[:find [(pull ?e [:seon.rule/id :seon.rule/doc :seon.rule/category
                                         :seon.rule/stratum :seon.rule/enabled?
                                         :seon.rule/target :seon.rule/when :seon.rule/assert]) ...]
                        :in $ [?rid ...]
                        :where [?e :seon.rule/id ?rid]]
                      db preview-rule-ids)
            merged (reduce (fn [acc r]
                             (let [r' (dissoc r :db/id)]
                               (if (:seon.rule/id r') (assoc acc (:seon.rule/id r') r') acc)))
                           by-id rows)]
        (vec (sort-by #(long (or (:seon.rule/stratum %) 50)) (vals merged)))))))

(defn- valid-rule-map? [r]
  (and (map? r) (keyword? (:seon.rule/id r))
       (or (nil? (:seon.rule/when r)) (sequential? (:seon.rule/when r)))
       (or (nil? (:seon.rule/assert r)) (sequential? (:seon.rule/assert r)))))

(defn- eval-rules-into-policy [db rs]
  (let [wm0 (-> (rules/empty-wm)
                (rules/add-facts [[:policy :policy/subject :print]
                                  [:probe-coll :ctx/total 100]]
                                 {:rule :seed/print :stratum 0}))
        res (rules/step-rules nil wm0 {:main db :branch db} rs nil :any)
        wm (:wm res)
        multi-on? (boolean (some #(and (= :rule.preview/multi-facet-entity (:seon.rule/id %))
                                       (:seon.rule/enabled? % true)) rs))]
    {:depth-layouts {0 (or (rules/wm-scalar wm :policy :policy/depth-0-layout) :expanded-card)
                     1 (or (rules/wm-scalar wm :policy :policy/depth-1-layout) :compact-row)
                     :deep (or (rules/wm-scalar wm :policy :policy/deep-layout) :ref-pill)}
     :max-depth (long (or (rules/wm-scalar wm :policy :policy/max-depth) 2))
     :priority-keys (vec (or (rules/wm-scalar wm :policy :policy/priority-keys) [:error :status :title :ok?]))
     :collection-limit (long (or (rules/wm-scalar wm :probe-coll :view.param/preview-limit) 20))
     :errors-by-kind? (boolean (rules/wm-scalar wm :policy :policy/errors-by-kind?))
     :errors-threshold (long (or (rules/wm-scalar wm :policy :policy/errors-threshold) 10))
     :errors-newest (long (or (rules/wm-scalar wm :policy :policy/errors-newest) 5))
     :annotate-multi-facet? multi-on?}))

(defn compute-print-policy
  "Pure Stage 2 helper: evaluates `explicit-rules` (or defaults) once via `seon.rules/step-rules`."
  {:malli/schema [:=> [:cat [:maybe [:vector :map]]] :map]
   :cost/ticks 5000000
   :cost/bytes 2097152}
  [explicit-rules]
  (let [rs (if (and (sequential? explicit-rules) (every? valid-rule-map? explicit-rules))
             (vec explicit-rules)
             (preview-rules))]
    (eval-rules-into-policy nil rs)))

(defn derive-print-policy
  "Stage 2: Runs `:rule.preview/*` rules from `db` once per call (LRU-cached by tx) to derive O(1) print policy."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map] [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector :map]]] :map]]
   :cost/ticks 5000000
   :cost/bytes 2097152}
  ([db] (derive-print-policy db nil))
  ([db explicit-rules]
   (if (or explicit-rules (nil? db)
           (instance? datahike.db.AsOfDB db) (instance? datahike.db.HistoricalDB db))
     (eval-rules-into-policy db (if (and (sequential? explicit-rules) (every? valid-rule-map? explicit-rules))
                                  (vec explicit-rules)
                                  (load-preview-rules db)))
     (let [ck [(get-in db [:config :store :id]) (get-in db [:config :branch] :main) (:max-tx db)]]
       (store/lru-get! policy-cache 16 ck #(eval-rules-into-policy db (load-preview-rules db)))))))
