(require '[seon.profile :as profile] '[seon.test-store :as ts] '[datahike.api :as d] '[org.replikativ.persistent-sorted-set :as pss])
(defmacro ph [label n & body]
  `(let [b0# (profile/alloc-bytes) r# (volatile! nil)
         k# (jolt.host/ticks-of #(vreset! r# (do ~@body)))]
     (println (format "%-50s ticks/unit %8.0f  bytes/unit %8.0f" ~label (/ k# (double ~n)) (/ (- (profile/alloc-bytes) b0#) (double ~n))))
     @r#))
(let [n 6000 xs (vec (shuffle (range n)))]
  (ph "pss persistent conj (shuffled longs)" n (reduce conj (pss/sorted-set) xs))
  (ph "pss transient conj! (shuffled longs)" n (persistent! (reduce conj! (transient (pss/sorted-set)) xs)))
  (ph "pss transient conj! (ascending longs)" n (persistent! (reduce conj! (transient (pss/sorted-set)) (range n)))))
(let [schema [{:db/ident :form/src :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
              {:db/ident :form/order :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]
      tx (fn [] (vec (for [i (range 3000)] {:db/id (str "t" i) :form/src (str "(defn f" i ")") :form/order i})))]
  (doseq [hist [false true]]
    (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? hist :schema-flexibility :write}
          _ (d/create-database cfg) conn (d/connect cfg) _ (d/transact conn schema) db @conn]
      (ph (str "mem db-with plain 2/ent, history " hist) 6000 (d/db-with db (tx)))
      (d/release conn))))
(let [db0 (ts/test-db)]
  (ph "test-db db-with plain 2/ent" 6000 (d/db-with db0 (vec (for [i (range 3000)] {:db/id (str "t" i) :form/src (str "(defn f" i ")") :form/order i})))))
(System/exit 0)
