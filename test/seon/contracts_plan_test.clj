(ns seon.contracts-plan-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.contracts-fixture :as fixture]
            [seon.db :as db]))

(deftest missing-note-id-names-the-key-and-the-docstring-example
  (fixture/with-agent
    (fn [connection handle routing]
      (let [[saved refusal] (fixture/submit connection handle routing
                                            "(my.note/add! {:my.note/content \"A verified observation.\"})")
            shown (:seon.eval/shown saved)]
        (is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))
        (doseq [fragment ["my.note/add! refused request at [:my.note/id]"
                          "missing :my.note/id" "Fix: Supply :my.note/id"
                          "Example: (my.note/add!" "A verified observation."]]
          (is (str/includes? shown fragment) shown))))))

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
        (doseq [fragment ["refused argument 0 (0-based) at []" "expected a vector" "got a lazy sequence" "Fix: Convert the sequence with vec"]]
          (is (str/includes? message fragment) message)
          (is (str/includes? (:seon.eval/shown saved) fragment) (:seon.eval/shown saved)))
        (is (map? (:seon.error/doc refusal))))
      (let [[saved value] (fixture/submit connection handle routing
                                  (str/replace fixture/run5-lazy-call "(largest-customer order-rows)"
                                               "(largest-customer (vec order-rows))"))]
        (is (nil? (:seon.cluster.eval/error saved)))
        (is (= {:customer "Ada" :total 115} value))))))

(deftest run7-reader-refusal-uses-the-shared-grammar
  (fixture/with-agent
    (fn [connection handle routing]
      (let [[saved refusal] (fixture/submit connection handle routing
                                            "(my.plan/current! {:my.plan/item/id \"juniper/define\"})")
            shown (:seon.eval/shown saved)]
        (is (= :seon.sci.reader/unreadable (:seon.error/kind refusal)))
        (doseq [fragment ["seon.sci.reader/read refused source at"
                          "expected readable Clojure source"
                          ":my.plan/item/id" "Fix:" "Example:"]]
          (is (str/includes? shown fragment) shown))))))
