(ns ablation.inspect-episode
  "Print one drive's episode runs compactly: forms, receipt outcomes, attempts.

  Deliberately narrow — the full receipt pull carries read-evidence and is
  unreadable; this selects only the fields that say what the agent did."
  (:require [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]))

(defn -main
  "Print compact per-run forms, receipt outcomes, and attempt facts."
  [& [root cluster-name]]
  (let [opened (store/open-store! {:seon.store/dir (str root "/store")})
        branch (registry/cluster-branch cluster-name)
        connection (store/open-branch! opened branch)
        database @connection]
    (try
      (println "=== runs ===")
      (doseq [run (sort-by :db/id
                           (db/q '[:find [(pull ?run [:db/id
                                                      :seon.cluster.run/id
                                                      {:seon.cluster.run/trigger [*]}
                                                      :seon.cluster.run/opened-at
                                                      :seon.cluster.run/starting-ns
                                                      :seon.cluster.run/closed-at
                                                      :seon.cluster.run/interrupted-at
                                                      :seon.cluster.run/error]) ...]
                                   :where [?run :seon.cluster.run/id _]]
                                 database))]
        (println (pr-str run)))
      (println "=== forms ===")
      (doseq [[run ordinal source]
              (sort (db/q '[:find ?run ?ordinal ?source
                            :where
                            [?form :seon.cluster.run.form/run ?run-entity]
                            [?run-entity :seon.cluster.run/id ?run]
                            [?form :seon.cluster.run.form/ordinal ?ordinal]
                            [?form :seon.cluster.run.form/source ?source]]
                          database))]
        (println (str "-- " run " #" ordinal))
        (println source))
      (println "=== receipt outcomes ===")
      (doseq [receipt (sort-by :db/id
                               (db/q '[:find [(pull ?receipt
                                                    [:db/id
                                                     :seon.cluster.eval/id
                                                     :seon.cluster.eval/ordinal
                                                     :seon.cluster.eval/result-edn
                                                     {:seon.cluster.eval/error [*]}]) ...]
                                       :where [?receipt :seon.cluster.eval/ordinal _]]
                                     database))]
        (println (pr-str receipt)))
      (println "=== replies ===")
      (doseq [reply (sort-by :db/id
                             (db/q '[:find [(pull ?reply [*]) ...]
                                     :where [?reply :seon.cluster.reply/text _]]
                                   database))]
        (println (pr-str reply)))
      (println "=== attempts ===")
      (doseq [attempt (sort-by :db/id
                               (db/q '[:find [(pull ?attempt
                                                    [:db/id
                                                     :seon.ai/model
                                                     :seon.ai.attempt/id
                                                     :seon.ai.attempt/usage-edn
                                                     :seon.ai.attempt/finish-reason
                                                     :seon.ai.attempt/reasoning-size
                                                     {:seon.ai.attempt/error [*]}
                                                     {:seon.ai.attempt/truncation [*]}]) ...]
                                       :where [?attempt :seon.ai.attempt/run _]]
                                     database))]
        (println (pr-str attempt)))
      (finally
        (store/release-branch! connection)
        (store/release-store! opened))))
  (shutdown-agents))
