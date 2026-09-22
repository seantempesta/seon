; Run: clojure -M:test <this-file> <absent-scratch-store-path>
(require '[clojure.java.io :as io]
         '[seon.cluster :as cluster]
         '[seon.cluster.store :as store]
         '[seon.cluster.instruction :as instruction]
         '[seon.db :as db]
         '[seon.schema :as schema]
         '[seon.schema.edn :as edn])
(let [path (first *command-line-args*)
      _ (assert (and path (not (.exists (io/file path)))) "Supply an absent scratch store path.")
      p (schema/declaration-projection (edn/packaged-forms))
      opened (store/open-store! {:seon.store/dir path})]
  (try
    (schema/call-with-projection
     p
     (fn []
       (let [c (:seon.store/connection-object opened)
             started (System/nanoTime)
             _ (cluster/accrete-schema-population! c "population-proof" false)
             report (db/transact! c {:tx-data (instruction/seed-rows)
                                     :tx-meta {:seon.db/process [:seon.db.process/id cluster/boot-process-identity]}})
             elapsed (/ (- (System/nanoTime) started) 1e6)
             database (db/db c)
             tx (db/q '[:find ?tx . :in $ ?id
                        :where [?p :seon.db.process/id ?id]
                        [?s :seon.cluster.instruction/id :getting-started ?tx]
                        [?tx :seon.db/process ?p]]
                      database cluster/boot-process-identity)
             result {:population-ms elapsed :committed? (some? (:db-after report))
                     :population-tx tx
                     :schema (db/pull database [:db/valueType] [:db/ident :seon.db/process])}]
         (prn result)
         (assert (:committed? result))
         (assert (pos-int? tx)))))
    (finally (store/release-store! opened))))
(shutdown-agents)
