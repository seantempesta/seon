(ns core
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

(defn run-spike []
  (println "== libdatahike-WASM spike ==")
  (println "Creating database...")
  (when (d/database-exists? cfg)
    (d/delete-database cfg))
  (d/create-database cfg)
  (println "Connecting...")
  (let [conn (d/connect cfg)]
    (println "Installing schema...")
    (d/transact conn schema)
    (println "Transacting data...")
    (d/transact conn [{:name "Aria"     :version 1}
                      {:name "Seon"     :version 2}
                      {:name "Datahike" :version 3}])
    (println "Querying...")
    (let [result (d/q '[:find ?n ?v
                        :where [?e :name ?n] [?e :version ?v]]
                      @conn)]
      (println "Result:" (pr-str result))
      (println "Done.  Count:" (count result)))))

(defn -main [& _args]
  (try
    (run-spike)
    (catch Throwable t
      (println "FAILED:" (.getMessage t))
      (.printStackTrace t)
      (System/exit 1))))
