(ns seon.pass-cost-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(deftest a-pass-reads-its-terminal-ordinal-once
  (support/with-running-agent
   (fn [running agent-id _connection]
     (doseq [n [1 10]]
       (let [request (json/write-str
                      {"name" "eval_clj"
                       "arguments" {"code" (str/join "\n" (repeat n "(+ 1 1)"))
                                    "agent" agent-id "cluster" running}})
             measured (profile/measure #(json/read-str (mcp/call request)))
             reply (:seon.profile/value measured)
             calls (profile/calls-of measured 'seon.turn/terminal-agent-ordinal)
             values (support/mcp-replies reply)]
         (is (false? (get reply "isError")))
         (is (= (vec (repeat n 2)) (mapv :seon.repl/value values)))
         (is (every? #(not (contains? % :seon.repl/diagnostics)) values))
         (is (= 1 calls)))))))

(deftest jvm-render-settings-are-acquired-once-per-pass
  (support/with-running-agent
   (fn [running agent-id _connection]
     (let [counts
           (mapv
            (fn [n]
              (let [request (json/write-str
                             {"name" "eval_clj"
                              "arguments" {"code" (str/join "\n" (repeat n "(+ 1 1)"))
                                           "mode" "jvm" "agent" agent-id "cluster" running}})
                    measured (profile/measure #(json/read-str (mcp/call request)))
                    reply (:seon.profile/value measured)
                    values (support/mcp-replies reply)]
                (is (false? (get reply "isError")))
                (is (= (vec (repeat n 2)) (mapv :seon.repl/value values)))
                (is (every? #(not (contains? % :seon.repl/diagnostics)) values))
                (profile/calls-of measured 'seon.config/effective)))
            [1 10])]
       (is (pos? (first counts)))
       (is (<= (second counts) (+ 2 (first counts))))))))

(deftest a-request-that-changed-nothing-runs-no-test-step
  (support/with-running-agent
   (fn [running agent-id _connection]
     (let [request (json/write-str {"name" "eval_clj"
                                    "arguments" {"code" "(+ 1 2)" "agent" agent-id "cluster" running}})
           _ (mcp/call request)
           measured (profile/measure #(json/read-str (mcp/call request)))
           reply (:seon.profile/value measured)
           stages (:seon.cost/stages (support/mcp-request reply))]
       (is (= [3] (mapv :seon.repl/value (support/mcp-replies reply))))
       (is (zero? (profile/calls-of measured 'seon.test/request-changes)))
       (is (< (get stages :test-step 0.0) 5.0))))))
