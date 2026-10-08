(ns seon.competency-oracle-test
  "Each competency task's known solution turns its proofs from red to green through the MCP entrance."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test :as test]
            [seon.test-support :as support]))

(def oracles
  "Each task's known solution, evaluated by the agent."
  {'seon.competency/area-is-contracted
   "(ns comp.geo)
    (defn area
      {:malli/schema [:=> [:cat [:int {:min 1}] [:int {:min 1}]] [:int {:min 1}]]}
      [w h]
      (* w h))"})

(defn- proof-run
  "The result of running t1's proof by identity on `connection`'s branch."
  {:malli/schema [:=> [:cat :seon.db/connection :symbol] :map]}
  [connection _task]
  (test/run {:seon.test/execution (support/execution-handle connection)
             :seon.test/recording-connection connection
             :seon.test/policy :named
             :seon.test/identities #{'seon.competency-test/t1-area-is-contracted}}))

(deftest t1-goes-red-to-green
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [task 'seon.competency/area-is-contracted
           red (proof-run connection task)
           reply (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                           "arguments" {"code" (get oracles task)
                                                                        "agent" agent-id "cluster" running}})))
           green (proof-run connection task)]
       (is (false? (get reply "isError")))
       (is (= [false true] [(:seon.test/passed? red) (:seon.test/passed? green)])
           "red before the oracle, green after")))))

(deftest a-namespace-request-leaves-the-proofs-out
  (support/with-running-agent
   (fn [_running _agent-id connection]
     (let [result (test/run {:seon.test/execution (support/execution-handle connection)
                             :seon.test/recording-connection connection
                             :seon.test/policy :named
                             :seon.test/namespaces #{'seon.competency-test}})]
       (is (= [] (:seon.test/results result)) "no proof ran")
       (is (= ['seon.competency-test/t1-area-is-contracted]
              (mapv :seon.test/sym (:seon.test/long-excluded result))))))))
