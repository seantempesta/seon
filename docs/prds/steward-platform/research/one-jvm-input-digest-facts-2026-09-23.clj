(require '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[datahike.api :as d] '[seon.cluster :as cluster]
         '[seon.cluster.source :as source] '[seon.db :as db]
         '[seon.operator.runtime :as runtime])

(fn [root cluster-name output]
  (let [instance (get @runtime/running-instances cluster-name)
        store (:seon.store/store instance)
        current (source/current store)
        database (source/database store (:seon.source/commit-id current))]
    (try
      (let [artifact (edn/read-string
                      (slurp (cluster/source-artifact-file (str (io/file root "data" "clusters")))))
            stored (into {} (db/q '[:find ?path ?digest
                                    :where [?e :seon.fn.file/relative-path ?path]
                                    [?e :seon.fn.file/digest ?digest]] database))
            inputs (:seon.source/relative-file-digests artifact)
            missing (vec (sort (remove #(contains? stored %) (keys inputs))))
            report {:seon.source/commit-id (:seon.source/commit-id current)
                    :seon.source/input-count (count inputs)
                    :seon.source/stored-count (count stored)
                    :seon.source/missing-count (count missing)
                    :seon.source/missing-paths missing
                    :seon.source/code-digest-agrees?
                    (= (get inputs "src/my/note.clj") (get stored "src/my/note.clj"))}]
        (spit output (pr-str report))
        (dissoc report :seon.source/missing-paths))
      (finally (d/release-materialized-db database)))))
