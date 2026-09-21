(require '[clojure.set :as set] '[datahike.api :as d]
         '[seon.cluster :as cluster] '[seon.cluster.source :as source]
         '[seon.db :as db] '[seon.operator.runtime :as runtime])

(fn [cluster-name output]
  (let [instance (get @runtime/running-instances cluster-name)
        store (:seon.store/store instance)
        published (source/current store)
        database (source/database store (:seon.source/commit-id published))]
    (try
      (let [inputs (:seon.source/relative-file-digests (cluster/source-snapshot))
            stored (source/stored-path-digests database (vec (keys inputs)))
            analysis (set (db/q '[:find [?path ...]
                                 :where [?declaration :seon.fn/file ?file]
                                        [?file :seon.fn.file/relative-path ?path]] database))
            result {:seon.source/commit-id (:seon.source/commit-id published)
                    :seon.source/input-count (count inputs)
                    :seon.source/stored-count (count stored)
                    :seon.source/missing-paths (vec (sort (set/difference (set (keys inputs)) (set (keys stored)))))
                    :seon.source/digests-agree? (= inputs stored)
                    :seon.source/analysis-file-count (count analysis)
                    :seon.source/derived-entry-present?
                    (boolean (db/pull database [:seon.fn.file/digest]
                                      [:seon.fn.file/relative-path "resources/seon/schemas"]))}]
        (spit output (pr-str result))
        result)
      (finally (d/release-materialized-db database)))))
