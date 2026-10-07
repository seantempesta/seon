(let [store (:seon.store/store (get @seon.operator.runtime/running-instances "probe"))
      pub (seon.cluster.source/current store)
      database (seon.cluster.source/database store (:seon.source/commit-id pub))
      projection (seon.db/carried-projection database)
      directory (seon.fs/source-directory)
      paths #{"src/my/background.clj"}
      declared (set (keys (:seon.schema.projection/forms projection)))
      inputs (into {} (seon.db/q '[:find ?p ?d :where [?f :seon.fn.file/relative-path ?p] [?f :seon.fn.file/digest ?d]] database))
      loaded (#'seon.cluster/loaded-files store)
      artifacts (#'seon.fn/analyzed-files {:seon.fn/root directory :seon.fn/roots ["src" "test"] :seon.fn/changed-paths paths :seon.source/previous-database database})
      entities (into [] (mapcat :seon.fn.file/rows) artifacts)
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      measure (fn [label f]
                (let [m (seon.profile/begin) b (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean (.threadId (Thread/currentThread))) t (System/nanoTime) v (f) ms (/ (- (System/nanoTime) t) 1e6) n (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean (.threadId (Thread/currentThread))) b) p (seon.profile/explain m 5)]
                  {:label label :ms ms :bytes n :size (count v) :calls (:seon.profile/calls p)}))]
  (try
    (seon.schema/call-with-projection projection
      (fn []
        (mapv (fn [[label f]] (measure label f))
          [[:capability #(doto (#'seon.fn/assert-capability-contracts! artifacts database #{}))]
           [:analyzer-digest #(#'seon.cluster/selected-analyzer-digest database paths entities inputs loaded)]
           [:schema-keys #(#'seon.cluster/stored-derivation-schema-keys database declared)]
           [:kondo #(seon.fn.analyzer/analyze {:seon.fn.analyzer/sources {(str directory "/src/my/background.clj") (slurp (str directory "/src/my/background.clj"))}})]])))
    (finally (datahike.api/release-materialized-db database))))
