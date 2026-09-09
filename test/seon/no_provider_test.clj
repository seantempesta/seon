(ns seon.no-provider-test
  (:require [clojure.test :refer [deftest is]]
            [seon.ai :as ai]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]


            [seon.config :as config]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest terminal-provider-refusal-records-one-fault-and-defers-reopening
  (support/with-database
   (fn [connection]
     (let [cluster-name "provider-refusal"
           agent-id "refused-agent"
           credential "SEON_TERMINAL_REFUSAL_VERIFIED_ABSENT"
           now (java.util.Date.)]
       (assert (nil? (System/getenv credential)))
       (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name cluster-name})
       (support/seed-cluster! connection cluster-name)
       (db/transact! connection
                     (agent/creation-tx
                      {:seon.agent/id agent-id
                       :seon.ns/name 'my.agents.refused
                       :seon.cluster/name cluster-name}))
       (db/transact! connection
                     [{:seon.agent/id agent-id
                       :seon.agent/settings
                       {:seon.config.ai/api-key-variable credential}}
                      {:seon.cluster.message/id "refusal-trigger"
                       :seon.cluster.message/to [:seon.agent/id agent-id]
                       :seon.cluster.message/content "Take one turn."
                       :seon.cluster.message/at now}])
       (let [cluster (support/cluster-handle
                      {:seon.db/connection connection
                       :seon.cluster/name cluster-name
                       :seon.db.process/id "refusal-process"
                       :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                       :seon.config.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                       :seon.config/on-core-error :record
                       :seon.sci.admit/caps (config/result-caps (config/defaults))
                       :seon.config.error/recurrence-limit 3
                       :seon.config.message/max-chain 8})
             request {:seon.agent/id agent-id}
             step! #(turn/turn {:seon.turn.loop/cluster cluster
                                :seon.turn.work/next %} now)]
         (is (nil? (:seon.config.ai/no-provider
                    (ai/agent-overlay @connection agent-id))))
         (is (nil? (:seon.config.ai/no-provider
                    (config/effective @connection cluster-name))))
         (is (= :open (:seon.turn.work/situation
                        (turn/next-agent-work @connection request))))
         (step! (turn/next-agent-work @connection request))
         (is (= :call (:seon.turn.work/situation
                        (turn/next-agent-work @connection request))))
         (step! (turn/next-agent-work @connection request))
         (is (= [:seon.ai/no-credential]
                (db/q '[:find [?kind ...] :where
                        [_ :seon.error/kind ?kind]] @connection)))
         (is (= 1 (db/q '[:find (count ?attempt) . :where
                         [?attempt :seon.ai.attempt/id]] @connection)))
         (is (= 1 (db/q '[:find (count ?turn) . :where
                         [?turn :seon.turn/closed-at]] @connection)))
         (is (nil? (turn/next-agent-work @connection request)))
         (is (false? (turn/more-agent-work? @connection request)))
         (is (seq (turn/unanswered-wakes @connection agent-id {})))
         (db/transact! connection
                       [{:seon.cluster.message/id "new-outside-trigger"
                         :seon.cluster.message/to [:seon.agent/id agent-id]
                         :seon.cluster.message/content "Configuration repaired; try again."
                         :seon.cluster.message/at now}])
         (is (= :open (:seon.turn.work/situation
                        (turn/next-agent-work @connection request)))))))))

(deftest settings-select-a-real-virtual-turn-without-provider-attempts
  (support/with-database
   (fn [connection]
     (let [cluster-name "no-provider-probe"
           agent-id "no-provider-agent"
           turn-id "no-provider-turn"
           now (java.util.Date. 0)
           applied (config/apply! {:seon.db/connection connection
                                    :seon.boot/cluster-name cluster-name})
           seeded (support/seed-cluster! connection cluster-name)
           created (db/transact!
                    connection
                    (agent/creation-tx
                     {:seon.agent/id agent-id
                      :seon.ns/name 'my.agents.no-provider
                      :seon.cluster/name cluster-name}))
           written (db/transact!
                    connection
                    [{:seon.agent/id agent-id
                      :seon.agent/settings {:seon.config.ai/no-provider true}}
                     {:seon.cluster.message/id "no-provider-message"
                      :seon.cluster.message/to [:seon.agent/id agent-id]
                      :seon.cluster.message/content "Take a virtual turn."
                      :seon.cluster.message/at now}])
           opened (db/transact! connection
                    (turn/open-tx
                     {:seon.turn/id turn-id
                      :seon.turn/agent [:seon.agent/id agent-id]
                      :seon.turn/trigger [:seon.cluster.message/id "no-provider-message"]
                      :seon.turn/opened-at now}))
           cluster (support/cluster-handle
                    {:seon.db/connection connection
                     :seon.cluster/name cluster-name
                     :seon.db.process/id "no-provider-process"
                     :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                     :seon.config.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                     :seon.config/on-core-error :record
                     :seon.sci.admit/caps (config/result-caps (config/defaults))
                     :seon.config.error/recurrence-limit 3
                     :seon.config.message/max-chain 8})
           report (turn/turn
                   {:seon.turn.loop/cluster cluster
                    :seon.turn.work/next
                    {:seon.turn.work/situation :call
                     :seon.agent/id agent-id
                     :seon.turn/id turn-id}} now)
           database @connection]
       (doseq [result [applied seeded created written opened report]]
         (is (not (:seon.error/kind result)) (pr-str result)))
       (is (true? (:seon.config.ai/no-provider (ai/agent-overlay database agent-id))))
       (is (= "(+ 1 1)" (:seon.turn/reply
                         (db/pull database '[*] [:seon.turn/id turn-id]))))
       (is (= ["2"] (db/q '[:find [?value ...]
                              :where [_ :seon.eval/value ?value]] database)))
       (is (empty? (db/q '[:find [?attempt ...]
                           :where [?attempt :seon.ai.attempt/id]] database)))
       (is (empty? (db/q '[:find [?error ...]
                           :where [?error :seon.error/id]] database))
           (pr-str (db/q '[:find [(pull ?error [:seon.error/kind :seon.error/message]) ...]
                            :where [?error :seon.error/id]] database)))))))
