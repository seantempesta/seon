(ns seon.render.agent-world-test
  "An agent's pages read the branch its own row names: an attempt agent's row lives on its chain's (or merge's)
  branch, never on main, so its page finds that branch through the branches main names for attempts and chains."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.render.web]
            [seon.render.web-test :as web-test]
            [seon.task :as task]
            [seon.test-support :as support]))

(deftest an-attempt-agent-page-reads-the-branch-its-row-names
  (#'web-test/with-server
   (fn [connection server _context]
     (let [store (:seon.store/store (support/execution-handle connection))
           {branch :seon.chain/branch} (task/chain! {:seon.db/connection connection :seon.store/store store}
                                                    [{:seon.task/ns 'tq.world :seon.task/name "design" :seon.task/plan "Design it."
                                                      :seon.task/proofs '#{tq.world/proof}}])
           agent-branch (deref #'seon.render.web/agent-branch)
           missing-agent (deref #'seon.render.web/missing-agent)]
       (try
         (registry/on-branch store branch
                             (fn [on-chain]
                               (support/transacted! on-chain (cluster.agent/creation-tx
                                                              {:seon.agent/id "attempt/world" :seon.cluster/name "web-test"
                                                               :seon.ns/name 'my.agents.world :seon.agent/branch branch}))
                               (support/transacted! on-chain
                                                    [{:seon.turn/id "world-run" :seon.turn/agent [:seon.agent/id "attempt/world"]
                                                      :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx"}
                                                     {:seon.cluster.eval/id "world-eval" :seon.cluster.eval/run [:seon.turn/id "world-run"]
                                                      :seon.cluster.eval/ordinal 0 :seon.cluster.eval/at (java.util.Date.)
                                                      :seon.eval/shown "42" :seon.cluster.eval/source "(+ 40 2)"}])))
         (is (= branch (agent-branch (db/db connection) "attempt/world"))
             "main holds no row for the attempt agent; the chain branch main names does")
         (is (= [true] (mapv #(str/includes? % "(+ 40 2)")
                             (web-test/texts (.body (#'web-test/fetch server "/agent/attempt%2Fworld")) "[data-turn=world-run]")))
             "the agent's page shows the run its branch holds")
         (is (= 200 (.statusCode (#'web-test/fetch server "/agent/attempt%2Fworld/debug"))) "its debug page answers")
         (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})
         (is (= {:status 404 :deleted [branch]}
                {:status (.statusCode (#'web-test/fetch server "/agent/attempt%2Fgone"))
                 :deleted (:seon.render.web/deleted-branches (missing-agent (db/db connection) "attempt/gone"))})
             "an agent no branch holds answers 404 with the deleted branches main names, as data")
         (finally
           (when (contains? (registry/branches store) branch)
             (registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))))))
