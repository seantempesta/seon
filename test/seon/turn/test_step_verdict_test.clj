(ns seon.turn.test-step-verdict-test
  "The automatic test step's verdict is over the merge's whole member list."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.db :as db]
            [seon.task :as task]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(defn- eval-request
  "The parsed MCP result of `code` evaluated as `agent-id` in the running cluster `running`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"code" code "agent" agent-id "cluster" running}}))))

(deftest ^{:seon.test/long "Two eval requests of an own-branch agent, each with its automatic test step: about 1 s each measured on default's JVM (agent teststep-verdict, 2026-10-14)."
           :seon.test/long-ms 15000}
  the-step-verdict-covers-every-test-of-the-member-list
  ;; g <- f <- f-is-int stops the nearest-test walk; f-same reaches g a level further through the helper same?. The
  ;; step runs the merge's member list (`seon.cluster.source/merge-members`), so its verdict claims a pass over both,
  ;; the list the done rule and the merge judge, with no partial count beside it.
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "step-task-proof-" (random-uuid))
           {connection :seon.db/connection}
           (support/own-branch-agent!
            running agent-id
            (str "(ns tv.proof (:require [clojure.test :refer [deftest is]]))\n"
                 "(defn- g \"Returns x.\" {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                 "(defn f \"Returns g of x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (g x))\n"
                 "(deftest f-is-int (is (int? (f 1))))\n"
                 "(defn- same? \"Whether f keeps x.\" {:malli/schema [:=> [:cat :int] :boolean]} [x] (= x (f x)))\n"
                 "(deftest f-same (is (same? 1)))\n"
                 "(deftest ^{:seon/tasks #{'tv.proof/keep}} keeps-f (is (= 1 (f 1))))"))
           ;; the agent holds a task whose proof reaches g too
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tv.proof
                                            :seon.task/name "keep" :seon.task/plan "Keep f."}))
           _ (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection
                            :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id]
                            :seon.task/branch (get-in (db/db connection) [:config :branch])})
           basis (db/basis-t (db/db connection))
           step (support/mcp-tests
                 (eval-request running agent-id
                               "(in-ns 'tv.proof)\n(defn- g \"Returns x plus zero.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 0))"))]
       (is (= {:seon.test/passed? true :seon.turn.loop/red []} (:seon.turn.loop/reaching step))
           "the verdict passes over the whole member list it ran, the one done and the merge judge")
       (is (= 1 (db/q '[:find (count ?r) . :in $ ?basis :where [?r :seon.test.run/id _ ?t] [(> ?t ?basis)]]
                      (db/db connection) basis))
           "the step is one test request: the member list, the branch's tests and the task's proof run together")))))

(deftest ^{:seon.test/long "An own-branch agent's setup and one changed definition, with their automatic test steps."
           :seon.test/long-ms 15000}
  a-request-keeps-the-record-of-an-unaffected-test
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "step-change-" (random-uuid))
           {connection :seon.db/connection}
           (support/own-branch-agent!
            running agent-id
            (str "(ns tv.change (:require [clojure.test :refer [deftest is]]))\n"
                 "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                 "(defn g {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                 "(deftest f-test (is (= 1 (f 1))))\n"
                 "(deftest g-test (is (= 1 (g 1))))"))
           results #(runner/current-results (db/db connection) ['tv.change/f-test 'tv.change/g-test] nil)
           before (results)
           basis (db/basis-t (db/db connection))
           _ (eval-request running agent-id "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 0))")
           after (results)]
       (is (every? #(= 1 (:seon.test/pass-count %)) (vals before)))
       (is (= (get-in before ['tv.change/g-test :seon.test/run])
              (get-in after ['tv.change/g-test :seon.test/run])))
       (is (not= (get-in before ['tv.change/f-test :seon.test/run])
                 (get-in after ['tv.change/f-test :seon.test/run])))
       (is (= #{'tv.change/f-test}
              (set (db/q '[:find [?s ...] :in $ ?basis :where
                           [?r :seon.test.run/selection-tx ?t] [(> ?t ?basis)]
                           [?r :seon.test.run/identities ?s]]
                         (db/db connection) basis))))))))
