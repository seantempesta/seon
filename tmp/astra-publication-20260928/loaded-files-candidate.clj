(do
(require '[seon.cluster :as cluster] '[seon.cluster.source :as source] '[seon.cluster.boot :as boot] '[seon.sci.eval :as sci.eval] '[datahike.api :as d])
(def launched-source-digests @#'cluster/launched-source-digests)
(defn loaded-files-after
  "The bytes the code this JVM runs came from, which a publication's seal reads
  its entities through (`selected-analyzer-digest`): the files the commit its
  last adoption loaded publishes (`sci.eval/loaded-identity`); before any
  adoption, the files launch loaded; none when the loaded program is unknown
  (an adoption failed mid-reload) or the adopted commit is another store's (a
  fixture's root), so every reached declaration names another analyzer.

  Costs: one indexed read of the adopted commit's file digests; no projection
  and no program-wide read."
  {:malli/schema [:=> [:cat :seon.store/store] :seon.source/relative-file-digests]}
  [store]
  (let [loaded @sci.eval/loaded-identity
        adopted (:seon.source/commit-id loaded)]
    (cond
      (not adopted) (if (seq loaded) {} launched-source-digests)
      :else
      (if-let [database (source/commit-database store adopted)]
        (try
          (into {} (d/q '[:find ?path ?digest
                          :where [?file :seon.fn.file/relative-path ?path]
                                 [?file :seon.fn.file/digest ?digest]]
                        database))
          (finally (d/release-materialized-db database)))
        {}))))

{:candidate-ready true})
