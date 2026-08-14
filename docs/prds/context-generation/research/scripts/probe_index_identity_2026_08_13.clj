(require '[datahike.api :as d])
(def cfg {:store {:backend :memory :id (random-uuid)}
          :keep-history? true :schema-flexibility :write
          :attribute-refs? false})
(d/delete-database cfg)
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :m/id :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :m/text :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}
                  {:db/ident :m/read? :db/valueType :db.type/boolean
                   :db/cardinality :db.cardinality/one}])
(d/transact conn (vec (for [i (range 2000)]
                        {:m/id (str "m" i) :m/text (str "t" i) :m/read? false})))
(def db0 @conn)
(def basis-t (:max-tx db0))
(d/transact conn [{:m/id "m5" :m/read? true}])
(d/transact conn [[:db/retractEntity [:m/id "m7"]]])
(d/transact conn [{:m/id "m9999" :m/text "new" :m/read? false}])
(def db1 @conn)
(def ao (d/as-of db1 basis-t))

(println :as-of-record-type (type ao))
(println :as-of-origin-identical-to-db1 (identical? (:origin-db ao) db1))
(println :as-of-eavt-identical (identical? (:eavt (:origin-db ao)) (:eavt db1)))
(println :db0-eavt-identical-db1-eavt (identical? (:eavt db0) (:eavt db1)))
(println :db0-eavt-root-identical-db1-root
         (identical? (.root ^org.replikativ.persistent_sorted_set.PersistentSortedSet (:eavt db0))
                     (.root ^org.replikativ.persistent_sorted_set.PersistentSortedSet (:eavt db1))))
(println :eavt-count-db0 (count (:eavt db0)) :db1 (count (:eavt db1)))
(println :settings-db1 (org.replikativ.persistent-sorted-set/settings (:eavt db1)))
(println :temporal-eavt-count (count (:temporal-eavt db1)))

;; cost of the "changed since t" full history scan
(defn changed-since [db t]
  (into [] (comp (filter #(> (:tx %) t)) (map (juxt :e :a :v :tx :added)))
        (d/datoms (d/history db) :eavt)))
(dotimes [_ 2] (changed-since db1 basis-t))
(let [start (System/nanoTime)
      r (changed-since db1 basis-t)
      ms (/ (- (System/nanoTime) start) 1e6)]
  (println :history-scan-datoms (count r) :ms ms))
(let [start (System/nanoTime)
      r (vec (d/datoms (d/history db1) :eavt))
      ms (/ (- (System/nanoTime) start) 1e6)]
  (println :history-total-datoms (count r) :full-scan-ms ms))
;; seek in eavt by high eid (do recent entities cluster?)
(println :max-eid (:max-eid db1))
