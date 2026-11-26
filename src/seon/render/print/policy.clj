(ns seon.render.print.policy
  "Stage 2 of 4-Stage Printer: Declarative `:rule.preview/*` rule-derived O(1) print policy."
  (:require [seon.rule :as rules]
            [seon.store :as store]
            [seon.rule.seed :as rseed]))

(defonce ^:private ^:seon.flow/edge policy-cache (store/lru-cache 16))

(defn- valid-rule-map? [r]
  (and
  (map? r)
  (keyword? (:seon.rule/id r))
  (or
    (nil? (:seon.rule/query r))
    (sequential? (:seon.rule/query r))
    (map? (:seon.rule/query r)))
  (or (nil? (:seon.rule/assert r)) (sequential? (:seon.rule/assert r)))))

(defn- eval-rules-into-policy [db rs]
  (let [wm0 (-> (rules/empty-wm)
                (rules/add-facts [[:policy :policy/subject :print]
                                  [:probe-coll :ctx/total 100]]
                                 {:rule :seed/print :stratum 0}))
        res (rules/step-rules nil wm0 {:main db :branch db} rs nil :any)
        wm (:wm res)
        multi-on? (boolean (rules/wm-scalar wm :policy :policy/annotate-multi-facet?))]
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

(defn- preview-starter-rules []
  (filterv #(= "rule.preview" (namespace (:seon.rule/id %))) (rseed/starter-rules)))

(defn preview-rules
  "Declarative `:rule.preview/*` rules projected from `resources/seon/rules.edn` (via `seon.rule/load-rules`)."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000
   :cost/bytes 131072}
  []
  (filterv #(= "rule.preview" (namespace (:seon.rule/id %)))
           (rules/load-rules nil (preview-starter-rules))))

(defn- load-preview-rules [db]
  (let [fb (preview-starter-rules)]
    (filterv #(= "rule.preview" (namespace (:seon.rule/id %)))
             (rules/load-rules db fb))))

(defn compute-print-policy
"Pure Stage 2 helper: evaluates `explicit-rules` (or defaults) once via `seon.rule/step-rules`."
{:malli/schema [:=> [:cat [:maybe [:vector :map]]] :map]
 :cost/ticks 5000000
 :cost/bytes 2097152}
[explicit-rules]
(let [rs (if (and (sequential? explicit-rules) (every? valid-rule-map? explicit-rules))
           (vec explicit-rules)
           (preview-rules))]
  (eval-rules-into-policy nil rs)))

(def ^:private default-nil-policy
  (eval-rules-into-policy nil (preview-rules)))

(defn derive-print-policy
  "Stage 2: Runs `:rule.preview/*` rules from `db` once per call (LRU-cached by tx) to derive O(1) print policy."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map] [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector :map]]] :map]]
   :cost/ticks 5000000
   :cost/bytes 2097152}
  ([db] (derive-print-policy db nil))
  ([db explicit-rules]
   (cond
     (and (nil? db) (nil? explicit-rules))
     default-nil-policy

     (or explicit-rules
         (instance? datahike.db.AsOfDB db) (instance? datahike.db.HistoricalDB db))
     (eval-rules-into-policy db (if (and (sequential? explicit-rules) (every? valid-rule-map? explicit-rules))
                                  (vec explicit-rules)
                                  (load-preview-rules db)))

     :else
     (let [ck [(get-in db [:config :store :id]) (get-in db [:config :branch] :main) (:max-tx db)]]
       (store/lru-get! policy-cache 16 ck #(eval-rules-into-policy db (load-preview-rules db)))))))