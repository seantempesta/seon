(require '[datahike.api :as d]
         '[datahike.query :as query])

(def configuration
  {:store {:backend :memory :id (random-uuid)}
   :schema-flexibility :write
   :attribute-refs? false})

(def schema
  [{:db/ident :query-invalidation/id
    :db/valueType :db.type/long
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :query-invalidation/group
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :query-invalidation/value
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :query-invalidation/noise
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def benchmark-query
  '[:find (count ?entity) .
    :where
    [?entity :query-invalidation/group :target]
    [?entity :query-invalidation/value ?value]
    [(>= ?value 0)]])

(defn timed-query [database]
  (let [started (System/nanoTime)
        evidence (query/q-with-evidence benchmark-query database)]
    {:elapsed-us (/ (double (- (System/nanoTime) started)) 1000.0)
     :result (:datahike.query/result evidence)
     :cache-outcome
     (get-in evidence
             [:datahike.query/cache-evidence :datahike.cache/outcome])
     :dependencies (:datahike.query/attribute-dependencies evidence)}))

(defn percentile [values fraction]
  (let [ordered (vec (sort values))
        index (min (dec (count ordered))
                   (long (Math/floor (* fraction (count ordered)))))]
    (nth ordered index)))

(defn summarize [samples]
  (let [elapsed (mapv :elapsed-us samples)]
    {:samples (count samples)
     :p50-us (percentile elapsed 0.50)
     :p95-us (percentile elapsed 0.95)
     :min-us (apply min elapsed)
     :max-us (apply max elapsed)
     :outcomes (frequencies (map :cache-outcome samples))
     :raw-us elapsed}))

(d/create-database configuration)
(def connection (d/connect configuration))

(try
  (d/transact connection schema)
  (d/transact
   connection
   (mapv (fn [id]
           {:query-invalidation/id (long id)
            :query-invalidation/group (if (even? id) :target :other)
            :query-invalidation/value (long id)})
         (range 8000)))

  ;; Warm the JVM, parser, and planner before measuring the result cache.
  (dotimes [_ 12]
    (query/clear-query-cache!)
    (timed-query @connection))

  (let [basis-n @connection
        cold (mapv (fn [_]
                     (query/clear-query-cache!)
                     (timed-query basis-n))
                   (range 20))
        _ (query/clear-query-cache!)
        seed (timed-query basis-n)
        warm (mapv (fn [_] (timed-query basis-n)) (range 20))
        report (d/transact
                connection
                [{:query-invalidation/id 1
                  :query-invalidation/noise "unrelated change"}])
        basis-n+1 (:db-after report)
        inherited (mapv (fn [_] (timed-query basis-n+1)) (range 20))]
    (prn
     {:probe/version 1
      :probe/java (System/getProperty "java.version")
      :probe/entity-count 8000
      :probe/query benchmark-query
      :probe/basis-n-commit-id (d/commit-id basis-n)
      :probe/basis-n+1-commit-id (d/commit-id basis-n+1)
      :probe/seed seed
      :probe/cold (summarize cold)
      :probe/warm (summarize warm)
      :probe/n-to-n+1-first (first inherited)
      :probe/n-to-n+1 (summarize inherited)
      :probe/cache-metrics (query/query-cache-evidence)}))
  (finally
    (d/release connection)
    (d/delete-database configuration)
    (query/clear-query-cache!)))
