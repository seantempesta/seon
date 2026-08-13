(require '[datahike.api :as d])
(import '[org.replikativ.persistent_sorted_set PersistentSortedSet ANode Branch Leaf])
(def cfg {:store {:backend :memory :id (random-uuid)}
          :keep-history? true :schema-flexibility :write})
(d/delete-database cfg) (d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :m/id :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :m/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                  {:db/ident :m/read? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}])
(d/transact conn (vec (for [i (range 20000)] {:m/id (str "m" i) :m/text (str "t" i) :m/read? false})))
(def db0 @conn)
(def basis-t (:max-tx db0))
(d/transact conn [{:m/id "m5" :m/read? true}])
(def db1 @conn)

(defn nodes [^PersistentSortedSet pss]
  (let [acc (atom [])]
    (letfn [(walk [^ANode n depth]
              (swap! acc conj [depth n])
              (when (instance? Branch n)
                (dotimes [i (.len ^Branch n)]
                  (walk (.child ^Branch n nil i) (inc depth)))))]
      (walk (.root pss) 0))
    @acc))
(def n0 (nodes (:eavt db0)))
(def n1 (nodes (:eavt db1)))
(println :node-count-db0 (count n0) :db1 (count n1))
(def set0 (into #{} (map (fn [[_ n]] (System/identityHashCode n))) n0))
(def shared (count (filter (fn [[_ n]] (contains? set0 (System/identityHashCode n))) n1)))
(println :identical-nodes-shared shared :of (count n1)
         :fraction (double (/ shared (count n1))))
(println :root-identical (identical? (.root ^PersistentSortedSet (:eavt db0))
                                     (.root ^PersistentSortedSet (:eavt db1))))
;; history scan scaling
(defn scan [db t] (count (into [] (filter #(> (:tx %) t)) (d/datoms (d/history db) :eavt))))
(dotimes [_ 2] (scan db1 basis-t))
(let [s (System/nanoTime) r (scan db1 basis-t)]
  (println :changed-datoms r :scan-ms (/ (- (System/nanoTime) s) 1e6)
           :history-size (count (:temporal-eavt db1))))
