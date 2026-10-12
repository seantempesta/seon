(ns seon.branch-schema-test
  "An agent's branch writes the attributes its cluster adopted after the branch forked."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- agent-value
  "The value of the last form of `code` evaluated as `agent-id` through `seon.mcp/call`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :seon.schema/value]}
  [running agent-id code]
  (->> (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                       "arguments" {"code" code "agent" agent-id "cluster" running}})))
            "content")
       (keep #(let [item (support/reply (get % "text"))]
                (when (and (map? item) (contains? item :seon.repl/value)) [(:seon.repl/value item)])))
       last
       first))

(deftest ^{:seon/tasks #{'seon.test/a-branch-forked-before-an-adoption-cannot-record-its-tests}
           :seon.test/long "Two eval requests of an own-branch agent, each with its automatic test step: about 3 s, as seon.test.interpreted-fixture-test's."
           :seon.test/long-ms 15000}
  a-branch-forked-before-an-adoption-records-its-test-runs
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "stale-schema-" (random-uuid))
           {branch :seon.db/connection} (support/own-branch-agent! running agent-id "(+ 1 1)")
           ;; The branch as one forked before the adoption that added the
           ;; attribute: no attribute, and no program row writing it.
           database (db/db branch)
           retracted (db/with-branch {:seon.db/connection branch}
                       #(db/transact! branch
                                      {:tx-data (into (mapv (fn [writer] [:db/retract writer :seon.fn/writes :seon.test.run/evaluator])
                                                            (db/q '[:find [?e ...] :where [?e :seon.fn/writes :seon.test.run/evaluator]] database))
                                                      (cluster/attribute-change-tx database [:seon.test.run/evaluator] {}))}))
           _ (is (:db-after retracted) "the branch dropped the attribute")
           _ (is (not (contains? (:schema (db/db branch)) :seon.test.run/evaluator)))
           _ (agent-value running agent-id (str "(ns stale.area (:require [clojure.test :refer [deftest is]]))\n"
                                                "(deftest recorded-test (is (= 1 1)))"))
           checked (agent-value running agent-id
                                "(select-keys (my.test/check {:seon.test/identities #{'stale.area/recorded-test} :seon.test/rerun? true}) [:seon.test/passed? :seon.test/executed-count])")]
       (is (= 1 (:seon.test/executed-count checked)))
       (is (true? (:seon.test/passed? checked)) "the run recorded its evaluator on the branch")))))

(deftest ^{:seon/tasks #{'seon.schema/a-schema-naming-a-branch-predicate-breaks-the-test-step}
           :seon.test/long "Four eval requests of an own-branch agent, each with its automatic test step, and one check."
           :seon.test/long-ms 20000}
  a-schema-naming-a-branch-function-is-refused-and-the-test-step-still-reads
  ;; A bare function symbol resolved as that function's contract where the
  ;; branch registered it, but not in the writer's schema-only projection, so
  ;; every later eval request on the branch refused :malli.core/invalid-schema.
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "function-schema-" (random-uuid))
           value #(agent-value running agent-id %)]
       (support/own-branch-agent! running agent-id "(ns fn-schema.area)")
       (value "(defn ok? {:malli/schema [:=> [:cat :any] :boolean]} [x] (int? x))")
       (is (= :seon.schema/function-reference
              (:seon.error/member (value "(seon.schema/register! :fn-schema.area/bare '[:map [:fn-schema.area/x fn-schema.area/ok?]])")))
           "a function named as a schema is refused")
       (value "(clojure.test/deftest reads-test (clojure.test/is (ok? 1)))")
       (is (= {:seon.test/passed? true :seon.test/executed-count 1}
              (value "(select-keys (my.test/check {:seon.test/identities #{'fn-schema.area/reads-test} :seon.test/rerun? true}) [:seon.test/passed? :seon.test/executed-count])"))
           "the branch's test step reads it")))))
