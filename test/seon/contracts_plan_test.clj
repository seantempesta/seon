(ns seon.contracts-plan-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.contracts-fixture :as fixture]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.schema :as schema]))

(defn- missing-note-id-names-the-key-and-the-docstring-example
  [connection handle routing]
  (let [[saved refusal] (fixture/submit connection handle routing
                                        "(my.note/add! {:my.note/content \"A verified observation.\"})")
        shown (:seon.eval/shown saved)]
    (is ((schema/projection-validator (schema/handed-projection)
                                      :seon.instrument/contract-error) refusal))
    (doseq [fragment ["my.note/add! refused request at [:my.note/id]"
                      "missing :my.note/id" "Fix: Supply :my.note/id with a string"
                      "Example: (my.note/add!" "A verified observation."]]
      (is (str/includes? shown fragment) shown))))

(deftest installed-contract-refusal-names-the-run5-failing-coordinate
  (fixture/with-agent
    (fn [connection handle routing]
      (let [[installed] (fixture/submit connection handle routing fixture/run5-definition)]
        (is (nil? (:seon.cluster.eval/error installed)))
        (is (:seon.fn/spec (db/pull @connection [:seon.fn/spec]
                                    [:seon.fn/sym "my.agents.juniper/largest-customer"]))))
      (let [[saved refusal] (fixture/submit connection handle routing fixture/run5-lazy-call)
            message (:seon.error/message refusal "")]
        (is ((schema/projection-validator (schema/handed-projection)
                                          :seon.instrument/contract-error) refusal))
        (doseq [fragment ["refused argument 0 (0-based) at []" "expected a vector" "got a lazy sequence" "Fix: Convert the sequence with vec"]]
          (is (str/includes? message fragment) message)
          (is (str/includes? (:seon.eval/shown saved) fragment) (:seon.eval/shown saved)))
        (is (map? (:seon.error/doc refusal))))
      (let [[saved value] (fixture/submit connection handle routing
                                  (str/replace fixture/run5-lazy-call "(largest-customer order-rows)"
                                               "(largest-customer (vec order-rows))"))]
        (is (nil? (:seon.cluster.eval/error saved)))
        (is (= {:customer "Ada" :total 115} value))))))

(defn- run7-reader-refusal-uses-the-shared-grammar
  [connection handle routing]
  (let [[saved refusal] (fixture/submit connection handle routing
                                        "(my.plan/current! {:my.plan/item/id \"juniper/define\"})")
        shown (:seon.eval/shown saved)]
    (is (= :seon.sci.reader/unreadable (:seon.error/kind refusal)))
    (doseq [fragment ["my.plan/current! refused source at"
                      "expected readable Clojure source"
                      ":my.plan/item/id" "Fix: Use :my.plan.item/id." "Example:"]]
      (is (str/includes? shown fragment) shown))))

(defn- run11-about-refusal-names-the-attribute-and-reference-shape
  [connection handle routing]
  (let [[saved refusal] (fixture/submit connection handle routing
                         "(my.note/add! {:my.note/id \"probe\" :my.note/content \"Observed.\" :my.note/about \"largest-customer-original\"})")
        shown (:seon.eval/shown saved)]
    (is ((schema/projection-validator (schema/handed-projection)
                                      :seon.instrument/contract-error) refusal))
    (doseq [fragment ["my.note/add! refused request at [:my.note/about]"
                      "an entity id or a lookup ref" "[:my.note/id" "Fix:" "largest-customer-original"]]
      (is (str/includes? shown fragment) shown))
    (is (= 1 (count (get-in refusal [:seon.error/data :seon.error/problems]))))))

(defn- unresolved-symbol-shows-the-correction-without-the-evidence-map
  [connection handle routing]
  (let [[saved refusal] (fixture/submit connection handle routing "my.web/no-such-fetch")
        shown (:seon.eval/shown saved)]
    (is (= :seon.sci.eval/evaluation-failed (:seon.error/kind refusal)))
    (is (str/includes? shown "my.web/no-such-fetch") shown)
    (is (str/includes? shown "Fix: Define or require this symbol.") shown)
    (is (< (tokens/estimate shown) 150) shown)
    (is (not (str/includes? shown "diagnostic-evidence")) shown)))

(deftest refusal-grammar-survives-real-evaluation
  (let [installations (atom 0)
        acquisitions (atom 0)
        executed (atom [])
        install fixture/install-orders!
        acquire fixture/with-grammar-agent
        cases [[:note-id missing-note-id-names-the-key-and-the-docstring-example]
               [:reader run7-reader-refusal-uses-the-shared-grammar]
               [:about run11-about-refusal-names-the-attribute-and-reference-shape]
               [:symbol unresolved-symbol-shows-the-correction-without-the-evidence-map]]]
    (with-redefs [fixture/install-orders!
                  (fn [& args] (swap! installations inc) (apply install args))
                  fixture/with-grammar-agent
                  (fn [body] (swap! acquisitions inc) (acquire body))]
      (fixture/with-grammar-agent
       (fn [connection ctx routing]
         (doseq [[case-id verify!] cases]
           (verify! connection ctx routing)
           (swap! executed conj case-id)))))
    (is (= [:note-id :reader :about :symbol] @executed))
    (is (= 1 @acquisitions))
    (is (zero? @installations)
        "Refusal grammar needs no order schemas or order data.")))
