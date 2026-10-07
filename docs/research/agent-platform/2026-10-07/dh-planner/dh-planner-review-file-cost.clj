(require '[datahike.api :as d] '[datahike.query :as q] '[datahike.lru :as lru] '[clojure.edn :as edn] '[clojure.pprint :as pp])
(def scratch (str (first *command-line-args*) "/"))
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(def tid (.threadId (Thread/currentThread)))
(def schema [{:db/ident :turn/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
             {:db/ident :eval/run :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
             {:db/ident :eval/ordinal :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
             {:db/ident :eval/source :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
             {:db/ident :eval/shown :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
             {:db/ident :eval/nonunique :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/index true}
             {:db/ident :junk/a :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
             {:db/ident :junk/b :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
             {:db/ident :junk/c :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
(defn run-tx [r]
  (cons {:db/id (str "run-" r) :turn/id (str "run-" r)}
        (for [o (range 3)] (cond-> {:eval/run (str "run-" r) :eval/ordinal o :eval/source (str "(+ " o ")") :eval/nonunique (+ (* r 3) o)}
                            (even? r) (assoc :eval/shown (str o))))))
(def receipts '[:find ?run-id ?ordinal ?source ?result :in $ [?run-id ...] :where
                [?run :turn/id ?run-id] [?form :eval/run ?run] [?form :eval/ordinal ?ordinal] [?form :eval/source ?source]
                [?receipt :eval/run ?run] [?receipt :eval/ordinal ?ordinal] [(get-else $ ?receipt :eval/shown "absent") ?result]])
(def queries [[:receipts receipts [["run-5"]]]
              [:nonunique '[:find ?run-id ?source :in $ [?n ...] :where [?form :eval/nonunique ?n] [?form :eval/source ?source] [?form :eval/run ?run] [?run :turn/id ?run-id]] [[15 16 17]]]
              [:dense-nonunique '[:find ?run-id ?source :in $ [?n ...] :where [?form :eval/ordinal ?n] [?form :eval/source ?source] [?form :eval/run ?run] [?run :turn/id ?run-id]] [[0 1 2]]]
              [:seeded-group '[:find ?form ?ordinal :in $ [?form ...] [?id ...] :where [?run :turn/id ?id] [?form :eval/run ?run] [?form :eval/ordinal ?ordinal] [?form :eval/source ?source]] :forms]])
(defn measure [cfg query args]
  (vreset! @#'q/plan-cache (lru/lru 100))
  (let [conn (d/connect cfg)]
    (try (binding [q/*query-result-cache?* false] (apply d/q query @conn args))
         (finally (d/release conn))))
  (let [conn (d/connect cfg)]
    (try
      (let [stats (-> @conn :store :storage :stats) before (:reads @stats)
            alloc (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid) t (System/nanoTime)
            result (binding [q/*query-result-cache?* false] (apply d/q query @conn args))]
        {:reads (- (:reads @stats) before) :rows (count result)
         :ms (/ (- (System/nanoTime) t) 1e6)
         :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid) alloc)})
      (finally (d/release conn)))))
(doseq [runs [2000 8000]]
  (let [cfg-path (str scratch "file-" runs ".edn")
        cfg (if (.exists (java.io.File. cfg-path)) (edn/read-string (slurp cfg-path))
                (let [cfg {:store {:backend :file :path (str scratch "store-" runs) :id (random-uuid)} :schema-flexibility :write :keep-history? false :store-cache-size 1}]
                  (d/create-database cfg)
                  (let [conn (d/connect cfg)]
                    (try
                      (d/transact conn schema)
                      (doseq [part (partition-all 500 (mapcat run-tx (range runs)))] (d/transact conn (vec part)))
                      (doseq [part (partition-all 5000 (for [j (range (* 15 runs))] {:junk/a j :junk/b j :junk/c j}))] (d/transact conn (vec part)))
                      (finally (d/release conn))))
                  (spit cfg-path (with-out-str (pp/pprint cfg))) cfg))
        forms (let [conn (d/connect cfg)] (try (d/q '[:find [?e ...] :where [?e :eval/run]] @conn) (finally (d/release conn))))]
    (doseq [[label query args] queries]
      (let [args (if (= args :forms) [forms ["run-5"]] args)]
        (prn :runs runs :shape label :measures (mapv (fn [_] (measure cfg query args)) (range 3)))
        (flush)))))
(shutdown-agents)
