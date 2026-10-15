(ns seon.pass-cost-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.task :as task]
            [seon.test :as test]
            [seon.cost-support :as cost-support]
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
           stages (:seon.cost/stages (cost-support/bound-cost running agent-id reply))]
       (is (= [3] (mapv :seon.repl/value (support/mcp-replies reply))))
       (is (zero? (profile/calls-of measured 'seon.test/request-changes)))
       (is (< (get stages :test-step 0.0) 5.0))))))

(deftest a-current-red-proof-does-not-repeat-the-test-step
  (support/with-running-agent
   (fn [running agent-id connection]
     (support/declare-proof! connection 'pass.cost-proof 'pending 'pass.cost/read)
     (let [id (:seon.task/id
               (task/create! {:seon.db/connection connection :seon.agent/id agent-id
                              :seon.task/ns 'pass.cost :seon.task/name "read"
                              :seon.task/plan "Read the held task."}))
           assigned (task/assign! {:seon.agent/context-source (support/running-cluster running)
                                   :seon.db/connection connection :seon.task/assigned id
                                   :seon.task/agent [:seon.agent/id agent-id]
                                   :seon.task/branch (get-in (db/db connection) [:config :branch])})
           request (fn [code] (json/write-str {"name" "eval_clj"
                                                "arguments" {"code" code "agent" agent-id "cluster" running}}))]
       (is (not (:seon.error/type assigned)))
       (mcp/call (request "(seon.context/current-state)"))
       (is (false? (test/verified? (db/db connection) 'pass.cost-proof/pending)))
       (is (= [] (test/stale (db/db connection) ['pass.cost-proof/pending])))
       (doseq [code ["(seon.context/current-state)" "(+ 1 2)"]]
         (let [measured (profile/measure #(json/read-str (mcp/call (request code))))
               reply (:seon.profile/value measured)
               stages (:seon.cost/stages (cost-support/bound-cost running agent-id reply))]
           (is (false? (get reply "isError")))
           (is (zero? (profile/calls-of measured 'seon.test/select)))
           (is (< (get stages :test-step 0.0) 5.0))
           (is (< (:seon.profile/ms measured) 1000.0))))))))
