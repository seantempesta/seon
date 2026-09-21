(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.set :as set]
         '[datahike.api :as d] '[seon.cluster :as cluster] '[seon.cluster.source :as source]
         '[seon.db :as db] '[seon.fn :as functions] '[seon.id :as id]
         '[seon.program :as program] '[seon.operator.runtime :as runtime])

(fn [root cluster-name output]
  (let [store (:seon.store/store (get @runtime/running-instances cluster-name))
        database (source/database store (:seon.source/commit-id (source/current store)))]
    (try
      (let [manifest (:seon.fn/manifest
                      (edn/read-string (slurp (cluster/source-artifact-file
                                               (str (io/file root "data" "clusters"))))))
            artifact (first (filter #(= "src/my/note.clj" (:seon.fn.file/relative-path %))
                                    (:seon.fn.manifest/artifacts manifest)))
            expected (into {} (map (juxt program/row-identity identity)) (:seon.fn.file/rows artifact))
            actual (functions/published-index-rows database (:seon.fn.file/identities artifact))
            differences (into {}
                          (keep (fn [row]
                                  (let [identity (program/row-identity row)
                                        before (get expected identity)
                                        keys (set/union (set (keys before)) (set (keys row)))
                                        changed (into #{} (filter #(not= (get before %) (get row %))) keys)]
                                    (when (seq changed) [identity changed])))) actual)
            schema-digests (into {} (map (fn [[key form]] [[:seon.schema/key key] (id/digest 64 [form])]))
                                 (:seon.schema.projection/forms (db/carried-projection database)))
            prior-digests (:seon.fn.manifest/declaration-digests manifest)
            result {:seon.source/artifact-row-count (count expected)
                    :seon.source/database-row-count (count actual)
                    :seon.source/row-differences differences
                    :seon.source/schema-digests-agree? (= prior-digests schema-digests)
                    :seon.source/manifest-schema-count (count prior-digests)
                    :seon.source/database-schema-count (count schema-digests)}]
        (spit output (pr-str result))
        result)
      (finally (d/release-materialized-db database)))))
