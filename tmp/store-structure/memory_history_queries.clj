;; store-structure-opus: history-query ms on a memory store written entirely by the
;; Datahike this JVM loads (steady state for that write rule); nothing touches disk.
;; Run: clojure -M tmp/store-structure/memory_history_queries.clj <label>
(require '[datahike.api :as d]
         '[datahike.db.interface :as dbi])

(let [label (first *command-line-args*)
      cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true :schema-flexibility :write
           :index-config {:branching-factor 128 :diff-buf-size 32}}
      _ (d/create-database cfg)
      conn (d/connect cfg)
      _ (d/transact conn [{:db/ident :x/id :db/valueType :db.type/long :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
                          {:db/ident :x/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                          {:db/ident :x/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                          {:db/ident :x/tags :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}])
      n 20000
      _ (doseq [chunk (partition-all 1000 (range n))]
          (d/transact conn (vec (for [i chunk] {:x/id i :x/name (str "e" i) :x/n 0 :x/tags :a}))))
      ;; three rounds of card-one updates on a third of the entities, one many-add
      _ (doseq [round (range 1 4) chunk (partition-all 1000 (range 0 n 3))]
          (d/transact conn (vec (for [i chunk] {:x/id i :x/n round}))))
      _ (doseq [chunk (partition-all 1000 (range 0 n 5))]
          (d/transact conn (vec (for [i chunk] [:db/add [:x/id i] :x/tags :b]))))
      db @conn
      h (d/history db)
      queries {:entity-group-history '[:find ?e ?s ?p ?tx ?added :where [?e :x/name ?s] [?e :x/n ?p ?tx ?added]]
               :card-one-scan-history '[:find ?e ?v ?tx ?added :where [?e :x/n ?v ?tx ?added]]
               :join-history '[:find ?s ?p :where [?e :x/name ?s] [?e :x/n ?p]]
               :many-history '[:find ?e ?t ?added :where [?e :x/tags ?t _ ?added]]}
      run (fn [q src]
            (dotimes [_ 5] (d/q q src))
            (let [runs (vec (repeatedly 15 #(let [t0 (System/nanoTime) r (d/q q src)]
                                              [(/ (- (System/nanoTime) t0) 1e6) r])))
                  r (second (peek runs))]
              {:n (count r) :hash (hash (set r))
               :median-ms (/ (Math/round (* 10 (nth (sort (map first runs)) 7))) 10.0)}))]
  (prn {:label label :temporal-eavt (count (seq (:temporal-eavt db)))
        ;; the read-evidence refresh: one since-over-history at a basis, the first
        ;; datom of each retained pattern (seon.db/index-evidence-current)
        :refresh (let [eavt (vec (d/datoms db :eavt))
                       sample (vec (take-nth (max 1 (quot (count eavt) 3000)) eavt))
                       patterns (vec (concat (map (fn [x] [(:e x) (:a x) nil]) sample)
                                             [[nil :x/n nil] [nil :x/name nil] [nil :x/tags nil]]))
                       check (fn [basis] (let [changes (d/since (d/history db) basis)]
                                           (mapv #(some-> (first (dbi/search changes %)) ((juxt :e :a :v :tx :added))) patterns)))
                       basis (- (:max-tx db) 3)]
                   (dotimes [_ 5] (check basis))
                   {:hash (hash (check basis))
                    :median-ms (let [xs (sort (repeatedly 15 #(let [t (System/nanoTime)] (check basis) (/ (- (System/nanoTime) t) 1e6))))]
                                 (/ (Math/round (* 10 (nth xs 7))) 10.0))})
        :history (into {} (for [[k q] queries] [k (run q h)]))
        :as-of-n (count (d/q '[:find ?e ?p :where [?e :x/n ?p]] (d/as-of db (- (:max-tx db) 10))))})
  (d/release conn)
  (shutdown-agents)
  (System/exit 0))
