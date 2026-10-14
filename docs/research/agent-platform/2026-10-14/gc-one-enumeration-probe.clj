;; Run with clojure.main in an isolated archive JVM; arguments: prepare|measure ROOT [KEYS].
;; ROOT is an owned disposable directory, never the development root.
(require '[clojure.java.io :as io]
         '[datahike.api :as d]
         '[konserve.core :as k]
         '[seon.cluster :as cluster]
         '[seon.cluster.registry :as registry]
         '[seon.maintenance :as maintenance]
         '[seon.profile :as profile]
         '[seon.schema :as schema]
         '[seon.schema.edn :as schema.edn])

(let [[mode root key-count] *command-line-args*
      path (str (io/file root "data" "store"))
      acquisition (profile/measure #(cluster/acquire-root-store! path))
      held (:seon.profile/value acquisition)]
  (prn {:acquisition (dissoc acquisition :seon.profile/value :seon.profile/called
                            :seon.profile/ranked-by-calls :seon.profile/ranked-by-self)})
  (try
    (if (= mode "prepare")
      (let [conn (:seon.store/connection-object held)
            store (:store @conn)]
        (d/transact conn
                    [{:db/ident :seon.config/cluster
                      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                     {:db/ident :seon.config.db/snapshot-window-ms
                      :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                     {:seon.config/cluster "gc-one-enumeration"
                      :seon.config.db/snapshot-window-ms 86400000}])
        (doseq [i (range (parse-long key-count))]
          (k/assoc store (str "orphan-" i) i {:sync? true}))
        (prn {:prepared-keys (count (k/keys store {:sync? true}))
              :commit (d/commit-id @conn)}))
      (let [request {:seon.operator/repository-root root
                     :seon.operator/managed-root root
                     :seon.config.maintenance/collect? true
                     :seon.operator.collect/dry-run? true}
            cutoff (registry/retained-before held (registry/retention-cutoff held))]
        (doseq [iteration (range 4)]
          (let [measured (profile/measure #(maintenance/collect! request))]
            (prn {:iteration iteration :cutoff cutoff
                  :measurement (dissoc measured :seon.profile/called
                                       :seon.profile/ranked-by-calls :seon.profile/ranked-by-self)})
            (flush)
            (assert (true? (:seon.operator.collect/complete? (:seon.profile/value measured))))))
        (let [inventory (registry/collect! held cutoff {:seon.operator.collect/dry-run? true})]
          (prn {:registry (dissoc inventory :seon.cluster.registry/mark-duration-ms
                                 :seon.cluster.registry/observed-keys)}))))
    (finally (cluster/release-root-store! path))))
(shutdown-agents)
