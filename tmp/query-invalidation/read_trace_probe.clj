(require '[clojure.set :as set]
         '[datahike.api :as d]
         '[seon.render.agent :as agent])

(def configuration
  {:store {:backend :memory :id (random-uuid)}
   :schema-flexibility :write
   :attribute-refs? false})

(def schema
  [{:db/ident :seon.cluster.agent/id
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.cluster.agent/namespace
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.cluster.agent/run
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.ns/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :query-invalidation/noise
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :query-invalidation/absent
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def original-pull d/pull)
(def original-q d/q)
(def read-pairs (atom #{}))

(defn record-pulled! [database lookup value]
  (when-let [entity (or (:db/id value)
                        (:db/id (original-pull database [:db/id] lookup)))]
    (swap! read-pairs into
           (keep (fn [[attribute _]]
                   (when (keyword? attribute) [entity attribute])))
           value))
  value)

(defn traced-pull [database selector lookup]
  (record-pulled! database lookup (original-pull database selector lookup)))

(defn traced-q [& arguments]
  ;; A result-level wrapper cannot recover which E/A datoms a Datalog query
  ;; inspected. Returning the value unchanged is the honest positive trace.
  (apply original-q arguments))

(defn render-header [database]
  (agent/agent-header-html
   {:seon.db/db database
    :seon.cluster.agent/id "agent-1"}))

(defn trace-render [database]
  (reset! read-pairs #{})
  (let [value (with-redefs [d/pull traced-pull d/q traced-q]
                (render-header database))]
    {:value value :reads @read-pairs}))

(defn report-pairs [report]
  (into #{}
        (map (fn [datom] [(:e datom) (:a datom)]))
        (:tx-data report)))

(defn wake? [reads report]
  (boolean (seq (set/intersection reads (report-pairs report)))))

(defn time-ns [iterations f]
  (let [started (System/nanoTime)]
    (dotimes [_ iterations] (f))
    (- (System/nanoTime) started)))

(defn median [values]
  (nth (vec (sort values)) (quot (count values) 2)))

(d/create-database configuration)
(def connection (d/connect configuration))

(try
  (d/transact connection schema)
  (let [initial-report
        (d/transact connection
                    [{:db/id "namespace"
                      :seon.ns/name "my.agents.trace"}
                     {:seon.cluster.agent/id "agent-1"
                      :seon.cluster.agent/namespace "namespace"}
                     {:query-invalidation/noise "initial"}])
        database (:db-after initial-report)
        traced (trace-render database)
        agent-eid (:db/id
                   (original-pull database [:db/id]
                                  [:seon.cluster.agent/id "agent-1"]))
        namespace-eid
        (get-in (original-pull database
                               [:seon.cluster.agent/namespace]
                               [:seon.cluster.agent/id "agent-1"])
                [:seon.cluster.agent/namespace :db/id])
        heard (atom nil)
        listener-key ::probe
        _ (d/listen connection listener-key #(reset! heard %))
        touched (d/transact connection
                            [[:db/add agent-eid
                              :seon.cluster.agent/id "agent-1-changed"]])
        touched-listener-report @heard
        _ (reset! heard nil)
        untouched (d/transact connection
                              [{:query-invalidation/noise "changed"}])
        untouched-listener-report @heard
        _ (reset! heard nil)
        query-read-but-untraced
        (d/transact connection
                    [[:db/add namespace-eid :seon.ns/name
                      "my.agents.trace.changed"]])
        query-listener-report @heard
        _ (reset! heard nil)
        negative-before
        (original-q
         '[:find ?entity
           :where [?entity :query-invalidation/absent "appeared"]]
         (:db-after query-read-but-untraced))
        negative-read-set #{}
        negative-change
        (d/transact connection
                    [[:db/add agent-eid
                      :query-invalidation/absent "appeared"]])
        negative-listener-report @heard
        iterations 400
        _ (dotimes [_ 200] (render-header database))
        base-samples
        (mapv (fn [_] (time-ns iterations #(render-header database)))
              (range 12))
        traced-samples
        (mapv
         (fn [_]
           (time-ns iterations
                    #(do (reset! read-pairs #{})
                         (with-redefs [d/pull traced-pull d/q traced-q]
                           (render-header database)))))
         (range 12))
        base-per-render-us
        (mapv #(/ (double %) iterations 1000.0) base-samples)
        traced-per-render-us
        (mapv #(/ (double %) iterations 1000.0) traced-samples)]
    (d/unlisten connection listener-key)
    (prn
     {:probe/version 1
      :probe/render-value (:value traced)
      :probe/read-pairs (:reads traced)
      :probe/touched
      {:decision (wake? (:reads traced) touched-listener-report)
       :report-pairs (report-pairs touched-listener-report)}
      :probe/untouched
      {:decision (wake? (:reads traced) untouched-listener-report)
       :report-pairs (report-pairs untouched-listener-report)}
      :probe/query-read-but-untraced
      {:decision (wake? (:reads traced) query-listener-report)
       :report-pairs (report-pairs query-listener-report)
       :output-changed? (not= (:value traced)
                              (:value
                               (trace-render
                                (:db-after query-read-but-untraced))))}
      :probe/negative-dependency
      {:before negative-before
       :after (original-q
               '[:find ?entity
                 :where [?entity :query-invalidation/absent "appeared"]]
               (:db-after negative-change))
       :decision (wake? negative-read-set negative-listener-report)}
      :probe/overhead
      {:iterations iterations
       :sample-count 12
       :base-per-render-us base-per-render-us
       :traced-per-render-us traced-per-render-us
       :base-p50-us (median base-per-render-us)
       :traced-p50-us (median traced-per-render-us)
       :p50-ratio (/ (median traced-per-render-us)
                     (median base-per-render-us))}}))
  (finally
    (d/release connection)
    (d/delete-database configuration)))
