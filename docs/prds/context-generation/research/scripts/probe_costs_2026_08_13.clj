(require '[datahike.api :as d] '[clojure.data :as data] '[clojure.set :as set])
(def cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true :schema-flexibility :write})
(d/delete-database cfg) (d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :m/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :m/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                  {:db/ident :m/read? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}])
(d/transact conn (vec (for [i (range 5000)] {:m/id (str "m" i) :m/text (str "t" i) :m/read? (even? i)})))
(def db0 @conn) (def basis-t (:max-tx db0))
(d/transact conn [{:m/id "m5" :m/read? true}])
(def db @conn)
(defn inbox [database]
  (->> (d/q '[:find ?id ?t ?r :where [?e :m/id ?id] [?e :m/text ?t] [?e :m/read? ?r]] database)
       (mapv (fn [[id t r]] {:m/id id :m/text t :m/read? r}))))
(defn ms [f] (dotimes [_ 3] (f)) (let [s (System/nanoTime) v (f)] [(/ (- (System/nanoTime) s) 1e6) v]))
(let [[t1 _] (ms #(inbox db))] (println :single-execution-ms t1))
(let [[t2 _] (ms #(do (inbox (d/as-of db basis-t)) (inbox db)))] (println :double-execution-ms t2))
(let [[t3 r] (ms #(let [b (update-vals (group-by :m/id (inbox (d/as-of db basis-t))) first)
                        a (update-vals (group-by :m/id (inbox db)) first)]
                    (data/diff b a)))]
  (println :double-plus-diff-ms t3 :changed-keys (vec (keys (second r)))))
;; read-set approach
(def touched (atom (transient #{})))
(def rec (d/filter db (fn [_ dtm] (swap! touched conj! [(:e dtm) (:a dtm)]) true)))
(let [[t4 _] (ms #(do (reset! touched (transient #{})) (inbox rec)))]
  (println :recording-execution-ms t4 :read-set-size (count (persistent! @touched))))
;; cheap "did anything change" check using history scan over the read attributes
(let [[t5 v] (ms #(->> (d/datoms (d/history db) :eavt)
                       (filter (fn [dtm] (> (:tx dtm) basis-t)))
                       (mapv (juxt :e :a))))]
  (println :changed-pairs-scan-ms t5 :changed-pairs v))
