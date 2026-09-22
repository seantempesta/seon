(ns seon.contracts-install-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.contracts-fixture :as fixture]
            [seon.db :as db]
            [seon.sci.eval :as evaluation]))

(def run5-function-literal
  "Exact refused run-5 definition, read from default on 2026-09-15."
  "(defn largest-customer
  {:malli/schema [:=> [:cat [:and [:vector :example/order-row]
                              [:fn {:error/message \"rows must be non-empty\"} (fn [r] (seq r))]]]
                  [:map [:customer :example/customer] [:total :example/amount]]]}
  [rows]
  (->> rows
       (group-by :example/customer)
       (map (fn [[c rs]] {:customer c :total (reduce + (map :example/amount rs))}))
       (sort-by :total >)
       first))")

(deftest function-literal-contract-is-refused-before-printing-or-installing
  (fixture/with-agent
   (fn [connection ctx _]
     (let [function-symbol 'my.agents.juniper/largest-customer
           rejected (evaluation/evaluate-for-install
                     (fixture/request connection ctx run5-function-literal))
           value (:seon.sci.admit/value rejected)
           message (:seon.error/message value "")]
       (doseq [fragment ["contracts are data" "registered predicate schema" "quoted symbol"]]
         (is (str/includes? message fragment) message)
         (is (str/includes? (:seon.eval/shown rejected) fragment)))
       (is (not (str/includes? (:seon.eval/shown rejected) "#object")))
       (is (nil? (:seon.program/row rejected)))
       (is (nil? (sci/resolve ctx function-symbol)))
       (is (nil? (:seon.fn/spec (db/pull @connection [:seon.fn/spec]
                                        [:seon.fn/sym (str function-symbol)]))))
     (fixture/submit connection ctx nil fixture/run5-definition)
     (let [function-symbol 'my.agents.juniper/largest-customer
           accepted @(sci/resolve ctx function-symbol)
           rejected (evaluation/evaluate-for-install
                     (fixture/request connection ctx run5-function-literal))]
       (is (identical? accepted @(sci/resolve ctx function-symbol)))
       (is (= fixture/run5-definition
              (:seon.fn/source (db/pull @connection [:seon.fn/source]
                                       [:seon.fn/sym (str function-symbol)])))))
     (let [result (evaluation/evaluate-for-install
                   (fixture/request connection ctx
                    "(defn positive-value {:malli/schema [:=> [:cat [:fn 'clojure.core/pos-int?]] :int]} [x] x)"))]
       (is (not (str/includes? (:seon.eval/shown result) "#object"))))))))
