;; Run with MCP eval_clj in JVM mode on default.
(require '[datahike.api :as d] '[seon.db :as db] '[seon.operator :as operator])
(let [connection (operator/connection "default")
      task-id "root/maintenance/blob-retention"
      database (db/db connection)
      [schedule-id expression]
      (db/q '[:find [?schedule ?expression] :in $ ?id
              :where [?task :seon.schedule.task/id ?id]
              [?task :seon.schedule.task/schedule ?schedule]
              [?schedule :seon.schedule/expression ?expression]] database task-id)
      backstop (db/q '[:find ?bound . :where
                       [_ :seon.config.operator/event-silence-backstop-ms ?bound]] database)
      completion (promise)
      listener (random-uuid)
      observe (fn [database]
                (when-let [result
                           (first
                            (db/q '[:find ?id ?at ?before ?after ?deleted
                                    :in $ ?task-id
                                    :where
                                    [?task :seon.schedule.task/id ?task-id]
                                    [?r :seon.maintenance.receipt/task ?task]
                                    [?r :seon.maintenance.receipt/id ?id]
                                    [?r :seon.maintenance.receipt/completed-at ?at]
                                    [?r :seon.maintenance.receipt/result ?result]
                                    [?result :seon.blob.retention/bytes-before ?before]
                                    [?result :seon.blob.retention/bytes-after ?after]
                                    [?result :seon.blob.retention/deleted-count ?deleted]]
                                  database task-id))]
                  (deliver completion result)))]
  (assert (and schedule-id expression backstop) "The task and bound must exist.")
  (d/listen connection listener #(observe (:db-after %)))
  (try
    (observe (db/db connection))
    (when-not (realized? completion)
      (let [report (db/transact! connection
                                [{:db/id schedule-id
                                  :seon.schedule/expression "*/1 * * * *"}])]
        (assert (:db-after report) (pr-str report))))
    (let [result (deref completion backstop ::timeout)]
      (assert (not= ::timeout result) "Scheduled retention did not complete within the declared bound.")
      {:seon.schedule.task/id task-id :seon.blob.retention/scheduled-proof result})
    (finally
      (d/unlisten connection listener)
      (db/transact! connection [{:db/id schedule-id :seon.schedule/expression expression}]))))
