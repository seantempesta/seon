(ns seon.pass-waste-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(deftest an-ordinary-pass-does-not-construct-storability-refusals
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call! (fn [code]
                   (json/read-str
                    (mcp/call (json/write-str
                               {"name" "eval_clj" "arguments"
                                {"code" code "agent" agent-id "cluster" running}}))))]
       (call! "(defn successor {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
       (let [mark (profile/begin)
             result (call! "(successor 41)")
             measured (profile/explain mark 100000)
             calls (reduce (fn [counts row]
                             (update counts (:seon.profile/sym row) (fnil + 0) (:seon.profile/calls row)))
                           {} (:seon.profile/by-calls measured))
             reply (support/mcp-reply result)]
         (is (false? (get result "isError")))
         (is (= 42 (:seon.repl/value reply)))
         (is (not (contains? reply :seon.repl/diagnostics)))
         (is (zero? (get calls 'seon.schema.datahike/attribute-declaration 0))))))))
