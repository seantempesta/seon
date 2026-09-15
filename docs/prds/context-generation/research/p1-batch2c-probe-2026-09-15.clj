; Disposable MCP JVM probe. No test runner, default writes, or lifecycle changes.
(let [database (seon.db/db (seon.operator/connection "default"))
      projection (seon.db/carried-projection database)
      configuration {:store {:backend :memory :id (random-uuid)}
                     :keep-history? true :schema-flexibility :write}
      state (seon.env/environment-state
             (seon.env/environment {:seon.boot/cluster-name "p1-disposable"
                                    :seon.schema/projection projection}))
      schema-tx (seon.schema.datahike/malli->datahike-schema-in
                 projection (seon.schema.datahike/database-attributes-in projection))]
  (datahike.api/create-database configuration)
  (let [connection (datahike.api/connect configuration)
        events (atom 0)
        completed (promise)]
    (try
      (datahike.api/transact connection schema-tx)
      (seon.db/carry-connection-projection-state! connection state)
      (datahike.api/transact connection [{:seon.effect/id "p1-settlement"}])
      (datahike.core/listen! connection ::settlement (fn [_] (swap! events inc)))
      (let [worker
            (Thread.
             ^Runnable
             (fn []
               (try
                 (let [result
                       (#'seon.effect/settle-value!
                        connection
                        {:seon.schema/projection projection
                         :seon.sci.admit/caps
                         (seon.config/result-caps (seon.config/defaults))
                         :seon.config/on-core-error :record}
                        "p1-settlement" (java.util.Date.) 65536
                        {:probe/value 7})
                       report (:seon.effect/transaction result)]
                   (deliver completed
                            {:probe/error (:seon.error/kind report)
                             :probe/report-carried
                             (boolean (some-> (:db-after report) seon.db/carried-projection))
                             :probe/result
                             (:seon.effect/result-edn
                              (seon.db/pull (seon.db/db connection)
                                            [:seon.effect/result-edn]
                                            [:seon.effect/id "p1-settlement"]))
                             :probe/events @events}))
                 (catch Throwable failure
                   (deliver completed {:probe/failure (ex-message failure) :probe/kind (:seon.error/kind (ex-data failure))})))))]
        (.start worker)
        (try
          (let [result (deref completed 15000 ::missing-event)]
            (when (= ::missing-event result)
              (throw (ex-info "Disposable settlement did not complete" {})))
            result)
          (finally
            (.interrupt worker)
            (.join worker 1000))))
      (finally
        (datahike.core/unlisten! connection ::settlement)
        (datahike.api/release connection)
        (datahike.api/delete-database configuration)))))
