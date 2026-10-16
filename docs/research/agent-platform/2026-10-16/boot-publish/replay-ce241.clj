(let [base (:seon.store/store (get @seon.operator.runtime/running-instances "default"))
      held (seon.cluster.store/open-overlay! base)
      current (seon.cluster.source/database base #uuid "6ad1af85-f0dd-5dbd-ad03-00c1f9f6a478")
      prior (first (datahike.api/parent-commit-ids current))
      previous (seon.cluster.source/database base prior)
      output "/Users/sean/src/seon/tmp/boot-publish-20261016/replay-ce241.edn"
      files (fn [database] (into {} (seon.db/q '[:find ?p ?d :where [?e :seon.fn.file/relative-path ?p] [?e :seon.fn.file/digest ?d]] database)))
      before (files previous)
      after (files current)
      paths (vec (for [[p d] after :when (not= d (get before p))] p))
      began (System/nanoTime)]
  (try
    (seon.cluster.registry/retire-branch! {:seon.store/store held :seon.store/branch :current-src})
    (seon.cluster.registry/branch! {:seon.store/store held :seon.store/branch :current-src :seon.cluster.registry/from prior})
    (spit output (str (seon.render.value/core-text {:prior prior :paths paths}) "\n"))
    (let [result
          (with-bindings {#'seon.cluster/*source-progress!*
                          (fn [phase]
                            (spit output (str (seon.render.value/core-text [phase (/ (- (System/nanoTime) began) 1e6)]) "\n") :append true))}
            (seon.profile/measure
             #(seon.schema/call-with-projection (seon.schema/projection-from-database current) (fn [] (#'seon.cluster/full-source-refresh!
               "/Users/sean/src/seon/tmp/boot-publish-20261016" held
               (assoc (#'seon.cluster/publication-roots)
                      :seon.fn/root "/Users/sean/src/seon/tmp/boot-publish-20261016/ce241"
                      :seon.source/changed-paths paths
                      :seon.cluster/loaded-files (#'seon.cluster/loaded-files base)
                      :seon.source/branch :boot-publish-replay
                      :seon.fn.analyzer/cache-root "/Users/sean/src/seon/tmp/boot-publish-20261016/resolver"))))))
          report (-> result
                     (dissoc :seon.profile/called :seon.profile/ranked-by-calls)
                     (update :seon.profile/value select-keys [:seon.source/built? :seon.source/branch :seon.source/expected-commit-id]))]
      (spit output (str (seon.render.value/core-text report) "\n") :append true)
      (select-keys report [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/ranked-by-self :seon.profile/value]))
    (finally
      (datahike.api/release-materialized-db previous)
      (datahike.api/release-materialized-db current)
      (seon.cluster.store/release-store! held))))
