(ns ablation.probe-completion
  "Print one drive's messages and its receipts for the contracted function.

  The in-drive grade asks for a receipt whose FORM carries the literal keyword
  `:seon.fn/spec`; a form that reaches the same keyword inside a quoted query
  does not satisfy it. This probe shows what the agent actually returned so the
  contract step can be graded from the settled reply instead."
  (:require [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]))

(defn -main
  "Print messages, and receipts with their run and result, for one drive root."
  [& [root cluster-name]]
  (let [opened (store/open-store! {:seon.store/dir (str root "/store")})
        branch (registry/cluster-branch cluster-name)
        connection (store/open-branch! opened branch)]
    (try
      (let [database (deref connection)]
        (println "=== messages ===")
        (doseq [message (sort-by :db/id
                                 (db/q '[:find [(pull ?message
                                                      [:db/id
                                                       :seon.cluster.message/id
                                                       :seon.cluster.message/text
                                                       :seon.cluster.message/at]) ...]
                                         :where [?message :seon.cluster.message/id _]]
                                       database))]
          (println (pr-str message)))
        (println "=== receipts for agent runs ===")
        (doseq [[run ordinal result]
                (sort (db/q '[:find ?run ?ordinal ?result
                              :where
                              [?receipt :seon.cluster.eval/run ?run-entity]
                              [?run-entity :seon.cluster.run/id ?run]
                              [?receipt :seon.cluster.eval/ordinal ?ordinal]
                              [?receipt :seon.cluster.eval/result-edn ?result]]
                            database))]
          (when-not (clojure.string/starts-with? (str run) "bootstrap:")
            (println (str "-- " run " #" ordinal))
            (println result))))
      (finally
        (store/release-branch! connection)
        (store/release-store! opened))))
  (shutdown-agents))
