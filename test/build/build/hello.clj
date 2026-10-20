(ns build.hello
  "bin/build's smoke binary: the pinned runtime compiles Datahike, opens an in-memory db,
  transacts and queries it."
  (:require [datahike.api :as d]))

(defn -main [& _]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :read :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data [{:name "hello"} {:name "datahike"}]})
      (println "hello:" (sort (d/q '[:find [?n ...] :where [_ :name ?n]] @conn)))
      (d/release conn))))
