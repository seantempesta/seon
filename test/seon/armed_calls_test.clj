(ns seon.armed-calls-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.instrument/hot-internals-pay-armed-validation}}
  a-tiny-contract-counts-only-its-invocations
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code]
                  (let [reply (json/read-str
                               (mcp/call (json/write-str
                                          {"name" "eval_clj"
                                           "arguments" {"cluster" running "agent" agent-id "code" code}})))]
                    (support/mcp-reply reply)))]
       (call "(defn pf {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
       (doseq [[code expected calls]
               [["(pf 41)" 42 1]
                ["(mapv pf (range 10))" (vec (range 1 11)) 10]
                ["(reduce + (map pf (range 100)))" 5050 100]]]
         (is (= {:seon.repl/value expected :seon.repl/calls calls}
                (select-keys (call code) [:seon.repl/value :seon.repl/calls]))))))))
