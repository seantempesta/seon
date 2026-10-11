(ns ^{:seon.test/platform "Cold-cache measurement and production Flow lifecycle in a disposable JVM."}
    seon.start-flow-benchmark-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.config :as config]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.task :as task]
            [seon.test-support :as support]
            [start-flow-benchmark :as benchmark]))

(def bounds
  {:cold {::benchmark/branch [25 4000000 10000]
          ::benchmark/world [1 30000 50]
          ::benchmark/sci [1300 2100000000 100000]
          ::benchmark/context [160 180000000 6000]
          ::benchmark/graph [5 800000 450]}
   :warm {::benchmark/branch [25 4000000 10000]
          ::benchmark/world [1 30000 50]
          ::benchmark/sci [45 45000000 2000]
          ::benchmark/context [30 20000000 5000]
          ::benchmark/graph [5 800000 450]}})

(deftest commit-to-paused-flow-costs
  (support/with-database
   (fn [connection]
     (let [agent-id "astra-flow-benchmark"]
       (support/transacted! connection (support/agent-tx (db/db connection) agent-id))
       (support/assign-task! connection agent-id)
       (let [handle (task/agent-task (db/db connection) agent-id)]
         (support/transacted! connection
                             [[:db/add [:seon.task/id handle] :seon.task/must-read 'seon.turn.world/world]
                              [:db/add [:seon.task/id handle] :seon.task/must-read 'seon.test.runner/reach-digests]]))
       (let [parent (support/execution-handle connection)
             commit (db/commit-id (db/db connection))]
         (benchmark/empty-cache!)
         (doseq [temperature [:cold :warm]]
           (let [sample (benchmark/sample! parent commit agent-id)]
             (is (seq (get-in sample [::benchmark/context-value :seon.context/proofs :seon.context/proof-list])))
             (is (= 2 (count (get-in sample [::benchmark/context-value :seon.context/must-reads]))))
             (is (= 3 (count (get-in sample [::benchmark/graph ::benchmark/states]))))
             (is (= #{:paused} (set (vals (get-in sample [::benchmark/graph ::benchmark/states])))))
             (when (= :warm temperature)
               (is (zero? (get-in sample [::benchmark/context ::benchmark/owners
                                         'seon.test.runner/reached-graph] 0))))
             (doseq [[part limits] (get bounds temperature)
                     [metric limit] (map vector [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/calls] limits)]
               (is (<= (get-in sample [part metric]) limit) (str temperature " " part " " metric)))
             (spit (str (System/getenv "SEON_CHECKOUT") "/tmp/astra-cache-1-2-" (name temperature) ".edn")
                   (value/text {:seon.render/value (dissoc sample ::benchmark/context-value)
                                :seon.render.value/root [::benchmark/sample temperature]
                                :seon.db/db (db/db connection)
                                :seon.render/profile (assoc (render/agent-render-profile config/defaults)
                                                            :seon.render.profile/token-budget 10000
                                                            :seon.render.profile/max-children 100
                                                            :seon.render.profile/max-depth 20)
                                :seon.render.value/options {:seon.render.value/structural? true}})))))))))
