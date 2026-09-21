(ns seon.core-functions-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
            [seon.sci.eval :as evaluation]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(def run-two-definition
  "The exact stored run-2 source, including its empty-input contract mistake."
  "(defn largest-customer
  \"Given a seq of order rows, return the customer with the largest total.\"
  {:malli/schema [:=> [:cat [:vector :example/order-row]] [:map [:customer :example/customer] [:total :int]]]}
  [rows]
  (->> rows
       (group-by :example/customer)
       (map (fn [[customer orders]]
              {:customer customer
               :total (reduce + 0 (map :example/amount orders))}))
       (sort-by :total >)
       first))")

(deftest auto-check-checks-the-function-instead-of-refusing-its-own-arguments
  (support/with-database
    (fn [connection]
      (is (:db-after (db/transact! connection
                                   [{:seon.agent/id "core-functions"
                                     :seon.agent/namespace
                                     {:seon.ns/name 'fixture.core-functions}}])))
      (let [ctx (support/fork-cluster-ctx connection)
            _ (doseq [[schema-key form] [[:example/order :string]
                                         [:example/customer :string]
                                         [:example/amount :int]
                                         [:example/order-row
                                          [:map [:example/order :example/order]
                                           [:example/customer :example/customer]
                                           [:example/amount :example/amount]]]]]
                (let [result (evaluation/evaluate
                              {:seon.sci.eval/ctx ctx
                               :seon.db/db @connection :seon.db/connection connection
                               :seon.cluster.eval/ns [:seon.ns/name 'fixture.core-functions]
                               :seon.cluster.eval/source (pr-str (list 'seon.schema/register! schema-key form))
                               :seon.sci.eval/time-limit-ms 10000
                               :seon.sci.admit/caps (config/result-caps config/defaults)
                               :seon.config/on-core-error :panic})]
                  (is (= schema-key (:seon.sci.admit/value result)) (pr-str result))
                  (when-let [row (:seon.program/row result)]
                    (let [written (db/transact! connection [(program/canonical-row row)])]
                      (is (:db-after written) (pr-str written))
                      (evaluation/install-evaluated-rows!
                       {:seon.sci.eval/ctx ctx :seon.db/db @connection
                        :seon.sci.eval/installations
                        [{:seon.program/row row :seon.sci.eval/evaluation result}]})))))
            check (fn [source]
                    (let [candidate
                          (evaluation/evaluate-candidate
                           {:seon.sci.eval/ctx ctx :seon.db/db @connection
                            :seon.db/connection connection :seon.agent/id "core-functions"
                            :seon.cluster.eval/source source
                            :seon.test.accretion/gate-set []
                            :seon.sci.eval/time-limit-ms 10000
                            :seon.sci.admit/caps (config/result-caps config/defaults)
                            :seon.config/on-core-error :panic})]
                      (evaluation/auto-check-candidate
                       {:seon.sci.eval/ctx (:seon.test.accretion/candidate-ctx candidate)
                        :seon.db/db @connection
                        :seon.program/row (get-in candidate [:seon.test.accretion/evaluation :seon.program/row])
                        :seon.config.test/auto-check-cases 25
                        :seon.test.accretion/seed 424242
                        :seon.sci.eval/time-limit-ms 10000
                        :seon.sci.admit/caps (config/result-caps config/defaults)
                        :seon.config/on-core-error :panic})))
            original (check run-two-definition)
            valid (check "(defn row-count {:malli/schema [:=> [:cat [:vector :example/order-row]] :int]} [rows] (count rows))")]
        (is (= :failed (:seon.test.accretion/status original)) (pr-str original))
        (is (= [[]] (get-in original [:seon.test.accretion/failure :seon.test.accretion/arguments])))
        (is (= :passed (:seon.test.accretion/status valid)) (pr-str valid))
        (is (= 25 (:seon.test.accretion/executed-count valid)))
        (is (= "[:map [:customer :example/customer] [:total :int]]"
               (get-in original [:seon.test.accretion/failure :seon.test.accretion/expected])))
        (let [failure (ex-info "Checker invocation failed." {:core-functions/fault true})
              observed (support/preserving-instrumentation-state
                        (fn []
                          (with-redefs [kernel/invoke (fn [_] (throw failure))]
                            (try
                              (check run-two-definition)
                              ::unexpected-success
                              (catch clojure.lang.ExceptionInfo raised raised)))))]
          (is (identical? failure observed)
              "A checker fault must propagate, never become an auto-check skipped message."))))))
