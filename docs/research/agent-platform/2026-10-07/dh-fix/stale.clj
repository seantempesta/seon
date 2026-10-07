;; Stale plan probe: a plan made on a 10-run store, run on a 50,000-run store
;; with the same schema. Fresh connection, store cache 1, plan cached.
(ns stale
  (:require [datahike.api :as d] [datahike.query :as q] [datahike.lru :as lru]))

(def scratch (System/getProperty "dhfix.scratch"))
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn alloc [] (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx (.threadId (Thread/currentThread))))

(def schema [{:db/ident :turn/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
             {:db/ident :eval/run :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
             {:db/ident :eval/ordinal :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
             {:db/ident :eval/source :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
             {:db/ident :eval/shown :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(defn run-tx [r]
  (cons {:db/id (str "run-" r) :turn/id (str "run-" r)}
        (for [o (range 3)] (cond-> {:eval/run (str "run-" r) :eval/ordinal o :eval/source (str "(+ " o ")")}
                             (even? r) (assoc :eval/shown (str o))))))

(defn cfg [runs]
  {:store {:backend :file :path (str scratch "/stale-" runs)
           :id (java.util.UUID/nameUUIDFromBytes (.getBytes (str "stale-" runs) "UTF-8"))}
   :schema-flexibility :write :keep-history? false :store-cache-size 1})

(defn ensure-store! [runs]
  (let [c (cfg runs)]
    (when-not (d/database-exists? c)
      (d/create-database c)
      (let [conn (d/connect c)]
        (try (d/transact conn schema)
             (doseq [part (partition-all 1000 (mapcat run-tx (range runs)))] (d/transact conn (vec part)))
             (finally (d/release conn)))))
    c))

(def receipts '[:find ?run-id ?ordinal ?source ?result :in $ [?run-id ...] :where
                [?run :turn/id ?run-id] [?form :eval/run ?run] [?form :eval/ordinal ?ordinal] [?form :eval/source ?source]
                [?receipt :eval/run ?run] [?receipt :eval/ordinal ?ordinal] [(get-else $ ?receipt :eval/shown "absent") ?result]])
(def scalar '[:find ?ordinal ?source :in $ ?run-id :where
              [?run :turn/id ?run-id] [?form :eval/run ?run] [?form :eval/ordinal ?ordinal] [?form :eval/source ?source]])

(defn q* [db query args] (binding [q/*query-result-cache?* false] (apply d/q query db args)))

(defn measure [c query args]
  (let [conn (d/connect c)]
    (try
      (let [db @conn stats (-> db :store :storage :stats) r0 (:reads @stats) a0 (alloc) t0 (System/nanoTime)
            res (q* db query args)]
        {:ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e5)) 10.0) :bytes (- (alloc) a0)
         :reads (- (:reads @stats) r0) :rows (count res)})
      (finally (d/release conn)))))

(defn with-db [c f] (let [conn (d/connect c)] (try (f @conn) (finally (d/release conn)))))

(defn median [xs k] (nth (sort (map k xs)) (quot (count xs) 2)))
(defn summary [xs] {:ms (median xs :ms) :bytes (median xs :bytes) :reads (median xs :reads) :rows (:rows (first xs))})

(defn run-probe []
  (let [small (ensure-store! 10) big (ensure-store! 50000)]
    (vec (for [[label query args] [[:receipts receipts [["run-5"]]] [:scalar scalar ["run-5"]]]]
           (do (vreset! @#'q/plan-cache (lru/lru 100))
               (with-db small #(q* % query args))
               (let [stale (summary (repeatedly 3 #(measure big query args)))]
                 (vreset! @#'q/plan-cache (lru/lru 100))
                 (with-db big #(q* % query args))
                 {:shape label :plan-from-small stale
                  :fresh (summary (repeatedly 3 #(measure big query args)))}))))))
