; MCP JVM probe: prototype finite query-pull dependencies, verify deletion,
; and restore both entering Datahike Vars in finally. No dependency file edit.
(require 'seon.test-support 'seon.operator 'seon.db 'datahike.api 'datahike.query)
(let [names '[extract-find-pull-source-deps query-dependency-plan]
      originals (into {} (map (fn [n] [(ns-resolve 'datahike.query n)
                                       @(ns-resolve 'datahike.query n)]) names))
      query '[:find (pull ?e [:db/id :seon.message/content]) . :in $ ?e]
      database (seon.db/db (seon.operator/connection "default"))
      before (datahike.query/query-dependency-plan query database 1)]
  (try
    (binding [*ns* (the-ns 'datahike.query)]
      (doseq [form '[
(defn- extract-find-pull-source-deps
  [find-clause bindings source-databases]
  (let [find-elements (dpip/find-elements find-clause)]
    (reduce
     (fn [dependencies element]
       (if (instance? Pull element)
         (let [source (parsed-source-symbol (:source element) '$)
               pattern (dependency-argument-value (:pattern element) bindings)
               attributes
               (if (sequential? pattern)
                 (:attribute-dependencies
                  (dpa/compile-pull-plan (get source-databases source) pattern))
                 :all)]
           (merge-source-attr-deps dependencies
                                   (source-attr-deps source attributes)))
         dependencies))
     {}
     find-elements)))

(defn query-dependency-plan
  "Return an execution-aware dependency plan partitioned by parsed database
   source. The function parses query and rule inputs but does not execute or
   retain any database value."
  [query-input & input-args]
  (try
    (let [{:keys [query args]} (normalize-q-input query-input input-args)
          parsed-query (memoized-parse-query query)
          environment (parsed-input-environment parsed-query args)
          source-positions
          (into {} (map (juxt :datahike.query.source/symbol
                              :datahike.query.source/argument-position))
                (parsed-source-bindings parsed-query))
          source-databases
          (into {} (keep (fn [[source position]]
                          (let [database (nth args position nil)]
                            (when (dbu/db? database) [source database]))))
                source-positions)
          dependencies
          (merge-source-attr-deps
           (parsed-clauses-source-deps (:qwhere parsed-query) environment '$ #{})
           (extract-find-pull-source-deps (:qfind parsed-query)
                                         (:bindings environment)
                                         source-databases))]
      {:datahike.query.dependency/sources
       (into []
             (keep (fn [[source attributes]]
                     (when-let [position (get source-positions source)]
                       {:datahike.query.source/symbol source
                        :datahike.query.source/argument-position position
                        :datahike.query.source/attributes attributes})))
             dependencies)})
    (catch Throwable _
      :all)))
]] (eval form)))
    (let [nested (datahike.query/query-dependency-plan
                  '[:find (pull ?a [:seon.agent/id
                                   {:seon.agent/runtime
                                    [:db/id {:seon.runtime/turns [:seon.turn/id]}]}])
                    :where [?a :seon.agent/id]] database)
          result
          (seon.test-support/with-database
           (fn [connection]
             (datahike.api/transact connection [{:seon.message/id "cold-page-query-deletion"}])
             (let [eid (datahike.api/q '[:find ?e . :where [?e :seon.message/id "cold-page-query-deletion"]] @connection)
                   plan (datahike.query/query-dependency-plan query @connection eid)
                   initial (datahike.query/q-with-evidence query @connection eid)
                   _ (datahike.api/transact connection [[:db.fn/retractEntity eid]])
                   cached (datahike.query/q-with-evidence query @connection eid)
                   uncached (binding [datahike.query/*query-result-cache?* false]
                              (datahike.api/q query @connection eid))]
               {:cold-page-kills/eid eid
                :cold-page-kills/plan plan
                :cold-page-kills/initial (:datahike.query/result initial)
                :cold-page-kills/cached (:datahike.query/result cached)
                :cold-page-kills/uncached uncached
                :cold-page-kills/cache-outcome (get-in cached [:datahike.query/cache-evidence :datahike.cache/outcome])
                :cold-page-kills/equal? (= (:datahike.query/result cached) uncached)})))]
      {:cold-page-kills/old-plan before :cold-page-kills/nested-plan nested
       :cold-page-kills/deletion result})
    (finally (doseq [[v f] originals] (alter-var-root v (constantly f))))))
