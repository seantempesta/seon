(ns seon.proof-reuse-test
  "A task's red proof is current at its reach: the block shows it red, and an eval request that changes nothing it
  reaches answers it from its record."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.context :as context]
            [seon.cluster.host :as host]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.task :as task]
            [seon.turn :as turn]
            [seon.test-support :as test-support]))

(defn- evaluate
  "The parsed MCP reply of `code` evaluated as `agent-id` through `seon.mcp/call`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj" "arguments" {"code" code "agent" agent-id "cluster" running}}))))

(defn- executions
  "How many recorded executions of `test-symbol` `database` holds: members that ran, not answered from a record."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym] :int]}
  [database test-symbol]
  (count (db/q '[:find ?m :in $ ?s
                 :where [?m :seon.test.member/symbol ?s] [?m :seon.test.member/completed-tx]
                        (not [?m :seon.test.member/reused-run])]
               database test-symbol)))

(defn- assigned-chain!
  "Writes the one-task chain `entry` on `connection` through the task writer and assigns it to `agent-id`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :seon.db/connection :map] :nil]}
  [running agent-id connection entry]
  (let [entities (#'task/chain-entities [entry])
        handle (task/task-handle (:seon.task/ns entry) (:seon.task/name entry))]
    (when-not (db/pull (db/db connection) [:db/id] [:seon.agent/id "root"])
      (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                                 (:seon.cluster/name (test-support/execution-handle nil))]]))
    (test-support/transacted! connection [[:db.fn/call #'task/adopt-tasks-tx entities #{handle} {}]])
    (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                   :seon.task/assigned handle :seon.task/agent [:seon.agent/id agent-id]
                   :seon.task/branch (get-in (db/db connection) [:config :branch])})
    nil))

(deftest a-red-proof-is-current-and-answered-from-its-record
  ;; lane block-sections and outside-2 (`docs/research/agent-platform/2026-10-14/outside-2.md` For root 1): a task's
  ;; red proof read `:pending` in the block, and every eval request ran it again though nothing it reaches changed.
  ;; The step is the eval request's own (`seon.turn/test-step`), called for a request that changed nothing
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (assigned-chain! running agent-id connection
                      {:seon.task/ns 'tq.pr :seon.task/name "only" :seon.task/plan "Implement it."
                       :seon.task/instructions "Make the row green." :seon.task/proofs #{'tq.pr-test/rows}})
     (evaluate running agent-id (str "(ns tq.pr-test (:require [clojure.test :refer [deftest is]]))\n"
                                     "(deftest rows (is (= 1 2) \"one row\"))"))
     (let [cluster (test-support/running-cluster running)
           ran (executions (db/db connection) 'tq.pr-test/rows)
           shown #(into {} (map (juxt :seon.test/sym :seon.context/proof-status))
                        (:seon.context/proof-list (context/proofs (db/db connection) 'tq.pr/only)))
           before (shown)
           steps (mapv (fn [_] (#'turn/test-step cluster agent-id nil)) (range 2))]
       (is (pos? ran) "the defining request ran the proof")
       (is (= [nil nil] steps) "a request that changed nothing runs no test")
       (is (= ran (executions (db/db connection) 'tq.pr-test/rows)) "nor records another execution of the proof")
       (is (= {'tq.pr-test/rows :red} before) "the block shows the proof red, at its current reach")
       (is (= before (shown)) "and still after those requests")))))
