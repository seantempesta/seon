(ns flow.store
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.eval :as eval]))

(defn cfg [path]
  {:store {:backend :file :path path
           :id (java.util.UUID/nameUUIDFromBytes (.getBytes ^String path))}
   :schema-flexibility :write :keep-history? false
   :index :datahike.index/persistent-set})

(def defaults
  {:config/id "singleton"
   :config/compute-permits (long (.availableProcessors (Runtime/getRuntime)))
   :config/time-limit-ms 500
   :config/allocation-limit-bytes (* 64 1024 1024)
   :config/lease-ms 60000})

(defn fresh!
  "Create a store, install schema and the config singleton, open the compute
   semaphore from the CONFIG FACT (never a hardcoded count)."
  ([path] (fresh! path {}))
  ([path overrides]
   (let [c (cfg path)]
     (when (d/database-exists? c) (d/delete-database c))
     (d/create-database c)
     (let [conn (d/connect c)]
       (d/transact conn {:tx-data driver/schema})
       (d/transact conn {:tx-data [(merge defaults overrides)]})
       (eval/open! (:config/compute-permits (driver/config (d/db conn))))
       conn))))

(defn reopen! [path]
  (let [conn (d/connect (cfg path))]
    (eval/open! (:config/compute-permits (driver/config (d/db conn))))
    conn))
