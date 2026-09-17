(ns my.test-test
  "Agent-authored tests resolve their admitted source with their cluster's custody."
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.program :as program]
            [seon.sci.eval :as evaluation]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest an-agents-own-test-reaches-its-cluster-through-the-elided-arity
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "own-tests"
                           {:seon.config.ai/no-provider true
                            :seon.test/check-time-limit-ms 120000})
     (support/transacted! connection
                          [{:seon.agent/id "owner"
                            :seon.agent/namespace {:seon.ns/name 'my.agents.owner}}])
     (let [base (support/fork-cluster-ctx connection)
           _ (evaluation/acquire! {:seon.sci.eval/ctx base :seon.db/db (db/db connection)})
           ctx (:seon.sci.eval/ctx
                (evaluation/fork-for-turn
                 {:seon.sci.eval/ctx base :seon.db/db (db/db connection)
                  :seon.agent/id "owner"}))
           scoped (env/scope (env/of ctx) {:seon.agent/id "owner"})
           _ (env/replace-environment! (get ctx env/state-carrier) scoped)
           source "(clojure.test/deftest my-cluster-is-reachable (clojure.test/is (= \"own-tests\" (:seon.cluster/name (seon.db/pull (seon.db/db) [:seon.cluster/name] [:seon.cluster/name \"own-tests\"])))))"
           declared (evaluation/evaluate
                     {:seon.sci.eval/ctx ctx
                      :seon.db/db (db/db connection)
                      :seon.cluster.eval/source source
                      :seon.cluster.eval/ns [:seon.ns/name 'my.agents.owner]
                      :seon.sci.admit/caps (config/result-caps (config/defaults))
                      :seon.sci.eval/time-limit-ms 120000
                      :seon.config/on-core-error :panic})
           row (when (:seon.program/row declared)
                 (program/declaration-row (:seon.program/row declared) :all :agent))]
       (is (nil? (:seon.cluster.eval/error declared)) (pr-str declared))
       (is (map? row) (pr-str declared))
       (support/transacted! connection [row])
       (evaluation/install-evaluated-rows!
        {:seon.sci.eval/ctx base :seon.sci.eval/agent-ctx ctx
         :seon.db/db (db/db connection)
         :seon.sci.eval/installations
         [{:seon.program/row row :seon.sci.eval/evaluation declared}]})
       (let [executions (atom 0)
             capture runner/run-vars!]
         (with-redefs [runner/run-vars! (fn [vars custody]
                                         (swap! executions inc)
                                         (capture vars custody))]
       (let [checked (support/agent-value
                      ctx
                      "(my.test/check {:seon.test/changed ['my.agents.owner/my-cluster-is-reachable]})"
                      'my.agents.owner)
             results (support/agent-value ctx "(my.test/run)" 'my.agents.owner)]
         (is (= ["my.agents.owner/my-cluster-is-reachable"]
                (:seon.test/passed checked)) (pr-str checked))
         (is (vector? results) (pr-str results))
         (let [result (first results)]
           (is (= "my.agents.owner/my-cluster-is-reachable" (:seon.test/sym result))
               (pr-str results))
           (is (= [1 0 0] (mapv result [:seon.test/pass-count
                                        :seon.test/fail-count
                                        :seon.test/error-count]))
               (pr-str result))
           (is (true? (:seon.test/unchanged result)) (pr-str result))
           (is (= 1 @executions) "The first check executed; the following request reused it.")
           (let [recorded-t (:seon.test/recorded-basis-t result)
                 before (db/basis-t (db/db connection))
                 repeated (first (support/agent-value ctx "(my.test/run)" 'my.agents.owner))]
             (is (= recorded-t (:seon.test/recorded-basis-t repeated)))
             (is (= before (db/basis-t (db/db connection))) "Reuse writes no transaction.")
             (is (= 1 @executions))
             ;; The transaction's own provenance advances :t without a program change.
             (support/transacted! connection [])
             (let [current-t (db/basis-t (db/db connection))
                   unchanged (first (support/agent-value ctx "(my.test/run)" 'my.agents.owner))
                   explicit (str "(my.test/run {:seon.test/run-basis-t " current-t "})")
                   fresh (first (support/agent-value ctx explicit 'my.agents.owner))
                   replay (first (support/agent-value ctx explicit 'my.agents.owner))]
               (is (> current-t before))
               (is (true? (:seon.test/unchanged unchanged)))
               (is (= recorded-t (:seon.test/recorded-basis-t unchanged)))
               (is (nil? (:seon.test/unchanged fresh)) (pr-str fresh))
               (is (= current-t (:seon.test/run-basis-t fresh)))
               (is (true? (:seon.test/unchanged replay)) (pr-str replay))
               (is (= 2 @executions) "An explicit newer basis executed exactly once.")
               (let [changed (evaluation/evaluate
                              {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                               :seon.cluster.eval/source
                               "(clojure.test/deftest my-cluster-is-reachable (clojure.test/is false))"
                               :seon.cluster.eval/ns [:seon.ns/name 'my.agents.owner]
                               :seon.sci.admit/caps (config/result-caps (config/defaults))
                               :seon.sci.eval/time-limit-ms 120000 :seon.config/on-core-error :panic})
                     changed-row (program/declaration-row (:seon.program/row changed) :all :agent)]
                 (is (nil? (:seon.cluster.eval/error changed)) (pr-str changed))
                 (support/transacted! connection [changed-row])
                 (evaluation/install-evaluated-rows!
                  {:seon.sci.eval/ctx base :seon.sci.eval/agent-ctx ctx
                   :seon.db/db (db/db connection)
                   :seon.sci.eval/installations
                   [{:seon.program/row changed-row :seon.sci.eval/evaluation changed}]})
                 (let [red (first (support/agent-value ctx "(my.test/run)" 'my.agents.owner))
                       repeated-red (first (support/agent-value ctx "(my.test/run)" 'my.agents.owner))]
                   (is (= 1 (:seon.test/fail-count red)) (pr-str red))
                   (is (= 1 (:seon.test/fail-count repeated-red)) (pr-str repeated-red))
                   (is (nil? (:seon.test/unchanged red)))
                   (is (nil? (:seon.test/unchanged repeated-red)))
                   (is (= 4 @executions) "Changed programs execute; red requests never reuse.")))))))))))))
