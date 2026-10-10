(ns seon.competency-oracle-test
  "Each competency task's known solution turns its proofs from red to green through the MCP entrance,
  and root may assign both of its wordings while the proof is red."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.competency :as competency]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.task :as task]
            [seon.test :as test]
            [seon.test-support :as support]))

(defn- agent-eval!
  "The parsed MCP result of `code` evaluated as `agent-id` in the cluster `running`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"code" code "agent" agent-id "cluster" running}}))))

(defn- assigned
  "What `seon.task/assign!` answers for `task-sym` given to `agent-id`: the assignment, or its refusal."
  {:malli/schema [:=> [:cat :string :seon.db/connection :seon.agent/id :seon.task/sym] :map]}
  [running connection agent-id task-sym]
  (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection
                 :seon.task/assigned (db/q '[:find ?id . :in $ ?sym :where [?t :seon.task/sym ?sym] [?t :seon.task/id ?id]]
                                           (db/db connection) task-sym)
                 :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])}))

(defn- proofs-passed?
  "Whether the proofs the automatic test step ran after `code`, evaluated as `agent-id`, all passed."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] [:maybe :boolean]]}
  [running agent-id code]
  (let [result (agent-eval! running agent-id code)]
    (is (false? (get result "isError")))
    (get-in (support/mcp-tests result) [:seon.turn.loop/proofs :seon.test/passed?])))

(defn- red-to-green
  "Assert that root assigns `prose` and any other wordings while their shared proof is red, and that the task's oracle,
  evaluated by the agent through MCP, turns that proof green in the eval request's automatic test step."
  {:malli/schema [:=> [:cat :seon.task/sym [:* :seon.task/sym]] :nil]}
  [prose & others]
  (support/with-running-agent
   (fn [running agent-id connection]
     (is (= (repeat (inc (count others)) nil)
            (map #(:seon.task/refused (assigned running connection agent-id %)) (cons prose others)))
         "root assigns every wording while the proof is red")
     (let [red (proofs-passed? running agent-id (get competency/setups prose "(ns user)"))
           green (proofs-passed? running agent-id (get competency/oracles prose))]
       (is (= [false true] [red green]) "red before the oracle, green in the oracle's own step"))))
  nil)

(deftest t1-goes-red-to-green
  (red-to-green 'seon.competency/area-is-contracted 'seon.competency/area-is-contracted-typed))

(deftest t2-goes-red-to-green
  (red-to-green 'seon.competency/quantity-schema-then-function-prose 'seon.competency/quantity-schema-then-function-typed))

(deftest t3-goes-red-to-green
  (red-to-green 'seon.competency/slug-has-a-passing-test-prose 'seon.competency/slug-has-a-passing-test-typed))

(deftest t5-goes-red-to-green
  (red-to-green 'seon.competency/slug-removes-edge-hyphens))

(deftest t4-goes-red-to-green
  (red-to-green 'seon.competency/app-calls-util-through-an-alias-prose
                'seon.competency/app-calls-util-through-an-alias-typed))

(deftest a-namespace-request-leaves-the-proofs-out
  (support/with-running-agent
   (fn [_running _agent-id connection]
     (let [result (test/run {:seon.test/execution (support/execution-handle connection)
                             :seon.test/recording-connection connection
                             :seon.test/policy :named
                             :seon.test/namespaces #{'seon.competency-test}})]
       (is (= [] (:seon.test/results result)) "no proof ran")
       (is (= #{'seon.competency-test/t1-area-is-contracted 'seon.competency-test/t2-quantity-schema-then-function
                'seon.competency-test/t3-slug-has-a-passing-test 'seon.competency-test/t4-app-calls-util-through-an-alias
                'seon.competency-test/t5-slug-removes-edge-hyphens}
              (set (map :seon.test/sym (:seon.test/long-excluded result)))))))))
