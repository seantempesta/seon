(let [store (:seon.store/store (get @seon.operator.runtime/running-instances "default"))
      before (seon.cluster.source/database store #uuid "6ac656fc-7c3d-504c-b7cd-f1355867baf3")
      after (seon.cluster.source/database store (:seon.source/commit-id (seon.cluster.source/current store)))
      query '[:find ?p ?d ?k :where [?f :seon.fn.file/relative-path ?p] [?f :seon.fn.file/digest ?d] [?f :seon.fn.file/derivation-digest ?k]]]
 (try (let [old (into {} (map (fn [[p d k]] [p [d k]])) (seon.db/q query before))
            current (seon.db/q query after)]
        {:files (count current) :bytes-changed (count (filter (fn [[p d _]] (not= d (first (get old p)))) current))
         :keys-changed (count (filter (fn [[p _ k]] (not= k (second (get old p)))) current))})
      (finally (datahike.api/release-materialized-db before) (datahike.api/release-materialized-db after))))
