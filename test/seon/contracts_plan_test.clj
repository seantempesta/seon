(ns seon.contracts-plan-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.contracts-fixture :as fixture]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]))

(deftest missing-note-id-names-the-key-and-the-docstring-example
  (fixture/with-agent
    (fn [connection handle routing]
      (let [[saved refusal] (fixture/submit connection handle routing
                                            "(my.note/add! {:my.note/content \"A verified observation.\"})")
            shown (:seon.eval/shown saved)]
        (is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))
        (doseq [fragment ["my.note/add! refused request at [:my.note/id]"
                          "missing :my.note/id" "Fix: Supply :my.note/id with a string"
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
        (doseq [fragment ["my.plan/current! refused source at"
                          "expected readable Clojure source"
                          ":my.plan/item/id" "Fix: Use :my.plan.item/id." "Example:"]]
          (is (str/includes? shown fragment) shown))))))

(deftest run11-about-refusal-names-the-attribute-and-reference-shape
  (fixture/with-agent
    (fn [connection handle routing]
      (let [[saved refusal] (fixture/submit connection handle routing
                             "(my.note/add! {:my.note/id \"probe\" :my.note/content \"Observed.\" :my.note/about \"largest-customer-original\"})")
            shown (:seon.eval/shown saved)]
        (is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))
        (doseq [fragment ["my.note/add! refused request at [:my.note/about]"
                          "an entity id or a lookup ref" "[:my.note/id" "Fix:" "largest-customer-original"]]
          (is (str/includes? shown fragment) shown))
        (is (= 1 (count (get-in refusal [:seon.error/data :seon.error/problems]))))))))

(deftest unresolved-symbol-shows-the-correction-without-the-evidence-map
  (fixture/with-agent
    (fn [connection handle routing]
      (let [[saved refusal] (fixture/submit connection handle routing "my.web/no-such-fetch")
            shown (:seon.eval/shown saved)]
        (is (= :seon.sci.eval/evaluation-failed (:seon.error/kind refusal)))
        (is (str/includes? shown "my.web/no-such-fetch") shown)
        (is (str/includes? shown "Fix: Define or require this symbol.") shown)
        (is (< (tokens/estimate shown) 150) shown)
        (is (not (str/includes? shown "diagnostic-evidence")) shown)))))
