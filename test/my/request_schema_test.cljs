(ns my.request-schema-test
  "Completeness checks for agent-facing `my.*` request maps."
  (:require
    [cljs.test :refer [deftest is testing]]
    [malli.core :as m]
    [my.blob]
    [my.canvas]
    [my.data]
    [my.kb]
    [my.ns]
    [my.ui]
    [seon.render]
    [seon.schema :as schema]))

(def ^:private audited-request-schemas
  #{:my.blob/concat-request
    :my.blob/get-request
    :my.blob/intent-materialization-request
    :my.blob/materialization-request
    :my.blob/operator-materialize-request
    :my.blob/operator-observe-request
    :my.blob/put-request
    :my.blob/retained-observation-request
    :my.blob/stat-request
    :my.blob/text-request
    :my.canvas/button-request
    :my.canvas/canvas-request
    :my.canvas/form-request
    :my.canvas/input-request
    :my.canvas/save-request
    :my.canvas/select-request
    :my.canvas/show-request
    :my.canvas/state-request
    :my.canvas/toggle-request
    :my.canvas/view-request
    :my.data/group-request
    :my.data/reduce-request
    :my.data/rows-request
    :my.kb/recall-request
    :my.kb/remember-request
    :my.ns/functions-request
    :my.ns/selection-request
    :my.ui/badge-request
    :my.ui/bullets-request
    :my.ui/kv-table-request
    :my.ui/progress-request
    :my.ui/section-request
    :my.ui/status-line-request
    :my.ui/table-request})

(defn- request-schema-keys
  []
  (->> (keys (schema/registered-schemas))
       (filter #(let [definition (schema/schema-definition %)]
                  (and (re-find #"^my\.(blob|canvas|data|kb|ns|ui)$"
                                (or (namespace %) ""))
                       (.endsWith (name %) "-request")
                       (= :map (first definition)))))
       set))

(deftest every-audited-request-map-rejects-unknown-keys
  (testing "the audit inventory is complete for the owned namespaces"
    (is (= audited-request-schemas (request-schema-keys))))
  (doseq [schema-key audited-request-schemas]
    (let [definition (schema/schema-definition schema-key)
          explanation
          (schema/explain-candidate-value
            schema-key {:my.request-schema/typo true})]
      (is (true? (:closed (second definition)))
          (str schema-key " is a closed request map"))
      (is (some #(= ::m/extra-key (:type %)) (:errors explanation))
          (str schema-key " rejects unknown keys through Malli's map rule")))))
