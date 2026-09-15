(ns seon.contracts-plan-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.contracts-fixture :as fixture]
            [seon.db :as db]))

(deftest installed-contract-refusal-names-the-run5-failing-coordinate
  (fixture/with-agent
    (fn [connection handle routing]
      (let [[installed] (fixture/submit connection handle routing fixture/run5-definition)]
        (is (nil? (:seon.cluster.eval/error installed)))
        (is (:seon.fn/spec (db/pull @connection [:seon.fn/spec]
                                    [:seon.fn/sym "my.agents.juniper/largest-customer"]))))
      (let [[saved refusal] (fixture/submit connection handle routing fixture/run5-lazy-call)
            message (:seon.error/message refusal "")]
        (is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))
        (doseq [fragment ["argument 0 (0-based)" "schema path [0]" "expected :vector" "got LazySeq"]]
          (is (str/includes? message fragment) message)
          (is (str/includes? (:seon.eval/shown saved) fragment) (:seon.eval/shown saved)))
        (is (map? (:seon.error/doc refusal))))
      (let [[saved value] (fixture/submit connection handle routing
                                  (str/replace fixture/run5-lazy-call "(largest-customer order-rows)"
                                               "(largest-customer (vec order-rows))"))]
        (is (nil? (:seon.cluster.eval/error saved)))
        (is (= {:customer "Ada" :total 115} value))))))
