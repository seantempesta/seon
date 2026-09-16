;; Evaluate require and def through MCP JVM mode on default. Make one source
;; edit and await bin/seon init --dev default --changed <path>, then evaluate
;; the final let form. No store, cluster, or test runner is created here.
(require 'seon.operator 'seon.db 'datahike.api 'clojure.java.io 'clojure.string)

(def adoption-volume-before
  (let [connection (seon.operator/connection "default")
        source (datahike.api/branch-as-db connection :current-src)]
    (try
      {:seon.probe/source-commit (datahike.api/commit-id source)
       :seon.probe/source-t (:max-tx source)
       :seon.probe/cluster-t (:max-tx (seon.db/db connection))
       :seon.probe/files
       (into {}
             (for [file (file-seq (clojure.java.io/file "data/store"))
                   :when (and (.isFile file)
                              (clojure.string/ends-with? (.getName file) ".ksv"))]
               [(.getPath file) [(.lastModified file) (.length file)]]))}
      (finally (datahike.api/release-materialized-db source)))))

(let [connection (seon.operator/connection "default")
      cluster (seon.db/db connection)
      source (datahike.api/branch-as-db connection :current-src)]
  (try
    (let [source-commit (datahike.api/commit-id source)
          adopted (:seon.source/commit-id
                   (seon.db/pull cluster [:seon.source/commit-id]
                                 [:seon.cluster/name "default"]))
          source-datoms (filter #(> (:tx %) (:seon.probe/source-t adoption-volume-before))
                                (datahike.api/datoms (datahike.api/history source) :eavt))
          cluster-datoms (filter #(> (:tx %) (:seon.probe/cluster-t adoption-volume-before))
                                 (datahike.api/datoms (datahike.api/history cluster) :eavt))
          changed-files
          (for [file (file-seq (clojure.java.io/file "data/store"))
                :when (and (.isFile file)
                           (clojure.string/ends-with? (.getName file) ".ksv"))
                :let [entry [(.lastModified file) (.length file)]]
                :when (not= entry (get (:seon.probe/files adoption-volume-before)
                                       (.getPath file)))]
            (.length file))
          result
          {:seon.probe/source-before (:seon.probe/source-commit adoption-volume-before)
           :seon.probe/source-after source-commit
           :seon.probe/adopted adopted
           :seon.probe/converged? (= source-commit adopted)
           :seon.probe/source-transactions (- (:max-tx source) (:seon.probe/source-t adoption-volume-before))
           :seon.probe/source-datoms (count source-datoms)
           :seon.probe/source-datoms-by-transaction
           (into (sorted-map)
                 (for [[tx datoms] (group-by :tx source-datoms)]
                   [tx (frequencies (map :a datoms))]))
           :seon.probe/cluster-transactions (- (:max-tx cluster) (:seon.probe/cluster-t adoption-volume-before))
           :seon.probe/cluster-datoms (count cluster-datoms)
           :seon.probe/cluster-datoms-by-attribute (frequencies (map :a cluster-datoms))
           :seon.probe/objects (count changed-files)
           :seon.probe/object-bytes (reduce + 0 changed-files)}]
      (spit "tmp/adoption-volume-measurement.edn" (pr-str result))
      result)
    (finally (datahike.api/release-materialized-db source))))
