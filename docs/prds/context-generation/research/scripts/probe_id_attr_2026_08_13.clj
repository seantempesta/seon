(require '[clojure.java.io :as io] '[clojure.edn :as edn] '[clojure.pprint :as pp]
         '[seon.schema.internal :as internal])
(defn show [l v] (println (str "\n;; === " l " ===")) (pp/pprint v))

(def schemas
  (->> (file-seq (io/file "resources/seon/schemas"))
       (filter #(.endsWith (.getName %) ".edn"))
       (map #(edn/read-string {:default (fn [_ v] v)} (slurp %)))
       (apply merge)))

(show "total registered schema keys" (count schemas))

(def entity-kinds
  (into (sorted-map)
        (keep (fn [[k v]]
                (when-let [id (internal/derive-entity-id-attr schemas v)] [k id])))
        schemas))
(show "DECLARED entity kinds -> derived :seon.entity/id-attr" entity-kinds)

(show "id-attr of :my.message/inbox-entry (the canonical read surface's row)"
      (internal/derive-entity-id-attr schemas (:my.message/inbox-entry schemas)))
(show "id-attr of :seon.cluster.message/message (the STORED entity)"
      (internal/derive-entity-id-attr schemas (:seon.cluster.message/message schemas)))
(show "count of declared entity kinds vs total keys"
      {:entity-kinds (count entity-kinds) :total (count schemas)
       :fraction (double (/ (count entity-kinds) (count schemas)))})

;;; Can identity be DERIVED for a projection row whose entries ALIAS an
;;; entity id-attr?  (:my.message/id is literally :seon.cluster.message/id)
(def id-attrs (set (vals entity-kinds)))

(defn resolve-alias
  "Follow keyword->keyword schema aliases to their terminal registered key."
  [k]
  (loop [k k seen #{}]
    (let [v (get schemas k)]
      (if (and (keyword? v) (not (seen v))) (recur v (conj seen k)) k))))

(show "alias chain of :my.message/id"
      (take 5 (iterate #(get schemas % %) :my.message/id)))
(show "resolve-alias :my.message/id" (resolve-alias :my.message/id))
(show "is it a declared entity id-attr?" (contains? id-attrs (resolve-alias :my.message/id)))

(defn derived-row-identity
  "Entry key of a :map schema whose value schema aliases a declared entity id-attr."
  [v]
  (when (and (vector? v) (= :map (first v)))
    (->> (rest v)
         (filter vector?)
         (keep (fn [[k & more]]
                 (let [value-schema (last more)]
                   (when (and (keyword? value-schema)
                              (contains? id-attrs (resolve-alias value-schema))
                              (not (some map? more)))   ; not {:optional true}
                     k))))
         (sort-by str) first)))

(show "derived-row-identity :my.message/inbox-entry"
      (derived-row-identity (:my.message/inbox-entry schemas)))
(show "coverage: how many :map schemas gain an identity this way but have no id-attr?"
      (let [gained (into (sorted-map)
                         (keep (fn [[k v]]
                                 (when (and (nil? (internal/derive-entity-id-attr schemas v))
                                            (derived-row-identity v))
                                   [k (derived-row-identity v)])))
                         schemas)]
        {:count (count gained) :sample (into (sorted-map) (take 15 gained))}))
