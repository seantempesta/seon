(require '[datahike.api :as d] '[seon.cluster.source :as source]
         '[seon.fn :as functions] '[seon.fs :as fs]
         '[seon.operator.runtime :as runtime] '[seon.schema.edn :as schema.edn] '[seon.schema :as schema])

; Run in the owned cluster's prepl; this reads one published value and writes evidence only.
(fn [cluster-name output]
  (let [store (:seon.store/store (get @runtime/running-instances cluster-name))
        published (source/current store)]
    (schema/call-with-projection
     (schema/declaration-projection (schema.edn/packaged-forms))
     (fn []
       (let [start (System/nanoTime)
             database (d/commit-as-db (:seon.store/connection-object store) (:seon.source/commit-id published))
             acquired (System/nanoTime)]
         (try
           (let [projection (schema/projection-from-database database)
                 projected (System/nanoTime)
                 calls (atom {})
                 names '[seon.fn/published-index-rows seon.fn/file-identities
                         seon.fn/declaration-digests seon.fn/manifest-data
                         seon.fn.signature/declaration-metadata]
                 wrappers (into {} (map (fn [name]
                                          (let [candidate (requiring-resolve name)
                                                original @candidate]
                                            [candidate (fn [& arguments]
                                                         (let [start (System/nanoTime)]
                                                           (try (apply original arguments)
                                                                (finally
                                                                  (swap! calls update name
                                                                         (fn [[count elapsed]]
                                                                           [(inc (or count 0))
                                                                            (+ (or elapsed 0) (/ (- (System/nanoTime) start) 1e6))]))))))]))) names)
                 manifest (with-redefs-fn wrappers #(functions/database-manifest
                           (vary-meta database assoc :seon.schema/projection projection)
                           (fs/source-directory) functions/source-roots ["src/my/note.clj"]))
                 selected (System/nanoTime)
                 result {:seon.source/materialize-ms (/ (- acquired start) 1e6)
                         :seon.source/projection-ms (/ (- projected acquired) 1e6)
                         :seon.source/selected-artifacts-ms (/ (- selected projected) 1e6)
                         :seon.source/function-contract-count (count (:seon.schema.projection/function-contracts projection))
                         :seon.source/selected-file-count (count (:seon.fn.manifest/artifacts manifest))
                         :seon.source/read-calls @calls}]
             (spit output (pr-str result))
             result)
           (finally (d/release-materialized-db database))))))))
