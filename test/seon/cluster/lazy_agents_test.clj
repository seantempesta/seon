(ns seon.cluster.lazy-agents-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.cluster.message :as message]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schedule :as schedule]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Canonical fixture acquisition plus config, agent, maintenance and message transactions measured 5.208 s in isolation."
           :seon.test/long-ms 10000}
  idle-agents-wait-for-work-but-schedule-owners-need-their-timers
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "lazy-graphs")
     (support/transacted!
      connection
      (vec (mapcat (fn [[id namespace-name]]
                     (agent/creation-tx {:seon.agent/id id :seon.ns/name namespace-name
                                         :seon.cluster/name "lazy-graphs"}))
                   [["root" 'lazy.graph.root] ["idle" 'lazy.graph.idle]
                    ["waiting" 'lazy.graph.waiting]])))
     (let [candidates #{"root" "idle" "waiting"}]
       (is (empty? (#'agent/agents-to-arm (db/db connection) candidates)))
       (support/transacted! connection [[:db.fn/call #'schedule/root-maintenance-seed-call]])
       (is (= #{"root"} (#'agent/agents-to-arm (db/db connection) candidates)))
       (support/transacted!
        connection
        [[:db.fn/call #'message/inbound-tx
          {:seon.agent/id "waiting" :seon.message/inbound-content "Please inspect this."
           :seon.config.eval.result/max-string
           (:seon.config.eval.result/max-string config/defaults)}]])
       (is (= #{"root" "waiting"}
              (#'agent/agents-to-arm (db/db connection) candidates)))))))
