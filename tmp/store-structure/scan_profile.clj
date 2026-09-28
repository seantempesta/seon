;; store-structure-opus: where a history scan's time goes under the change.
;; Run: clojure -M tmp/store-structure/scan_profile.clj
(require '[datahike.api :as d]
         '[datahike.index.interface :as di]
         '[datahike.datom :as dd]
         '[datahike.query.execute])

(defn median-ms [f]
  (dotimes [_ 10] (f))
  (let [xs (sort (repeatedly 21 #(let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6))))]
    (/ (Math/round (* 100 (nth xs 10))) 100.0)))

(let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true :schema-flexibility :write
           :index-config {:branching-factor 128 :diff-buf-size 32}}
      _ (d/create-database cfg)
      conn (d/connect cfg)
      _ (d/transact conn [{:db/ident :x/id :db/valueType :db.type/long :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
                          {:db/ident :x/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                          {:db/ident :x/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
      n 20000
      _ (doseq [c (partition-all 1000 (range n))]
          (d/transact conn (vec (for [i c] {:x/id i :x/name (str "e" i) :x/n 0}))))
      _ (doseq [round (range 1 4) c (partition-all 1000 (range 0 n 3))]
          (d/transact conn (vec (for [i c] {:x/id i :x/n round}))))
      db @conn
      h (d/history db)
      from (dd/datom 0 :x/n nil 0) to (dd/datom Long/MAX_VALUE :x/n nil Long/MAX_VALUE)
      fms @(resolve 'datahike.query.execute/fast-merge-scan)
      drain (fn [s] (let [l (java.util.ArrayList.)] (doseq [x s] (.add l x)) l))]
  (prn {:query-ms (median-ms #(d/q '[:find ?e ?v ?tx ?added :where [?e :x/n ?v ?tx ?added]] h))
        :query-count-ms (median-ms #(d/q '[:find (count ?e) . :where [?e :x/n ?v ?tx ?added]] h))
        :fast-merge-scan-ms (median-ms #(fms (:aevt db) from to (:temporal-aevt db) :aevt db :x/n))
        :drain-both-ms (median-ms #(do (drain (di/-slice (:aevt db) from to :aevt)) (drain (di/-slice (:temporal-aevt db) from to :aevt))))
        :drain-temporal-ms (median-ms #(drain (di/-slice (:temporal-aevt db) from to :aevt)))
        :datoms-history-ms (median-ms #(count (d/datoms h :aevt :x/n)))})
  (d/release conn)
  (shutdown-agents)
  (System/exit 0))
