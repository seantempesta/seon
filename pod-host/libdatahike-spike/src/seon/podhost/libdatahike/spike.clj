(ns seon.podhost.libdatahike.spike
  "Step 4 of libdatahike-WASM: real datahike :memory conn at AOT time.

   With the core.async/dispatch substitution in subs-classes/, the writer's
   thread-pool dispatch becomes synchronous under WASM, removing the analyzer
   reachability into JavaMonitorQueuedSynchronizer.parkAndCheckInterrupt and
   array-instantiation reflection paths that blocked the previous attempt.

   At build/AOT time: d/create-database + d/transact populate the conn.
   At WASM runtime: only (d/q ... db) runs; the writer machinery has executed
   at build time and the resulting snapshot is in the image heap."
  (:require [datahike.api :as d])
  (:gen-class))

(def cfg
  {:store {:backend :memory
           :id #uuid "550e8400-e29b-41d4-a716-446655440000"}
   :schema-flexibility :write
   :keep-history? false})

(def schema
  [{:db/ident :name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(defonce db
  (do
    (when (d/database-exists? cfg)
      (d/delete-database cfg))
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn schema)
      (d/transact conn [{:name "Alpha"     :version 1}
                        {:name "Seon"     :version 2}
                        {:name "Datahike" :version 3}])
      @conn)))

(defn -main [& _args]
  (println "== libdatahike-WASM :memory-backend spike ==")
  (println "Querying baked-in db...")
  (let [result (d/q '[:find ?n ?v
                      :in $
                      :where [?e :name ?n] [?e :version ?v]]
                    db)]
    (println "Result:" (pr-str result))
    (println "Done.  Count:" (count result))))
