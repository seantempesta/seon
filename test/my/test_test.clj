(ns my.test-test
  "Agent-authored tests resolve their admitted source with their cluster's custody."
  (:require [seon.schema]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.id :as id]
            [seon.program :as program]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Acquire the agent's canonical SCI program before two owned requests."
           :seon.test/long-ms 300000}
  an-agents-own-test-reaches-its-cluster-through-the-elided-arity
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "own-tests"
                           {:seon.config.ai/no-provider true
                            :seon.test/check-time-limit-ms 120000})
     (support/transacted!
      connection
      [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                                  (db/db connection))
        :seon.source/test-input-digest (id/digest 64 [::owned-test-inputs])}])
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
                      :seon.sci.admit/caps (config/result-caps config/defaults)
                      :seon.sci.eval/time-limit-ms 120000
                      :seon.config/on-core-error :panic})
           row (when (:seon.program/row declared)
                 (program/declaration-row (seon.schema/handed-projection) (:seon.program/row declared) :all :agent))]
       (is (nil? (:seon.cluster.eval/error declared)) (pr-str declared))
       (is (map? row) (pr-str declared))
       (support/transacted! connection [row])
       (evaluation/install-evaluated-rows!
        {:seon.sci.eval/ctx base :seon.sci.eval/agent-ctx ctx
         :seon.db/db (db/db connection)
         :seon.sci.eval/installations
         [{:seon.program/row row :seon.sci.eval/evaluation declared}]})
       (let [first-result (first (support/agent-value ctx "(my.test/run)" 'my.agents.owner))
             second-result (first (support/agent-value ctx "(my.test/run)" 'my.agents.owner))
             database (db/db connection)
             confidence [:seon.test.run/basis-t :seon.test.run/program-digest
                         :seon.test.run/input-digest]]
         (is (= 'my.agents.owner/my-cluster-is-reachable (:seon.test/sym first-result))
             (pr-str first-result))
         (is (= [1 0 0] (mapv first-result [:seon.test/pass-count
                                           :seon.test/fail-count :seon.test/error-count])))
         (is (true? (:seon.test/unchanged second-result)) (pr-str second-result))
         (is (= 3 (count (select-keys second-result confidence))))
         (is (= (select-keys first-result confidence) (select-keys second-result confidence)))
         (is (= 2 (count (db/q '[:find [?run ...] :where
                                 [?cluster :seon.cluster/name "own-tests"]
                                 [?run :seon.test.run/cluster ?cluster]] database))))
         (is (= 1 (count (db/q '[:find [?member ...] :where
                                 [?cluster :seon.cluster/name "own-tests"]
                                 [?run :seon.test.run/cluster ?cluster]
                                 [?run :seon.test.run/members ?member]] database))))
         (is (= 1 (count (db/q '[:find [?member ...] :where
                                 [?cluster :seon.cluster/name "own-tests"]
                                 [?run :seon.test.run/cluster ?cluster]
                                 [?run :seon.test.run/covered-by ?member]] database)))))))))
