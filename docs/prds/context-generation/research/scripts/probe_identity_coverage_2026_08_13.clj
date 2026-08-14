(require '[seon.schema :as schema] '[seon.schema.internal :as internal]
         '[clojure.java.io :as io] '[clojure.edn :as edn])
(def schemas
  (->> (file-seq (io/file "resources/seon/schemas"))
       (filter #(.endsWith (.getName %) ".edn"))
       (map (comp edn/read-string slurp))
       (apply merge)))
(println :total-keys (count schemas))
(def id-attrs (into #{} (filter #(internal/identity-attr? schemas %)) (keys schemas)))
(println :identity-attr?-count (count id-attrs))
(println :identity-attrs (vec (sort (map str id-attrs))))
(def entity-kinds (into #{} (keep (fn [[k v]] (when (internal/derive-entity-id-attr schemas v) k))) schemas))
(println :entity-flagged-kinds (count entity-kinds))
(def map-id (into {} (keep (fn [[k v]]
                             (when-let [e (#'internal/map-identity-entry-key schemas v)] [k e])))
                  schemas))
(println :maps-with-derivable-identity-entry (count map-id))
(println :inbox-entry (get map-id :my.message/inbox-entry))
(println :cluster-message (get map-id :seon.cluster.message/message))
