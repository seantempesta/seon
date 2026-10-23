(require '[seon.profile :as profile] '[datahike.api :as d] '[datahike.core :as dc] '[datahike.db :as ddb] '[datahike.datom :as dd])
(defmacro ph [label n & body]
  `(let [b0# (profile/alloc-bytes) r# (volatile! nil)
         k# (jolt.host/ticks-of #(vreset! r# (do ~@body)))]
     (println (format "%-52s ticks/unit %8.0f  bytes/unit %8.0f" ~label (/ k# (double ~n)) (/ (- (profile/alloc-bytes) b0#) (double ~n))))
     @r#))
(let [schema [{:db/ident :form/src :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
              {:db/ident :form/order :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]
      cfg {:store {:backend :memory :id (random-uuid)} :keep-history? false :schema-flexibility :write}
      _ (d/create-database cfg) conn (d/connect cfg) _ (d/transact conn schema) db @conn
      n 3000
      base (+ 100000 (:max-eid db))]
  (ph "tempid maps (2/ent)" (* 2 n) (d/db-with db (vec (for [i (range n)] {:db/id (str "t" i) :form/src (str "s" i) :form/order i}))))
  (ph "explicit eids [:db/add] (2/ent)" (* 2 n) (d/db-with db (vec (mapcat (fn [i] [[:db/add (+ base i) :form/src (str "s" i)] [:db/add (+ base i) :form/order i]]) (range n)))))
  (ph "explicit eids, one attr (1/ent)" n (d/db-with db (vec (for [i (range n)] [:db/add (+ base i) :form/order i]))))
  (ph "datoms via init-db/db-from-reader? (raw index conj)" (* 2 n)
      (let [ds (vec (mapcat (fn [i] [(dd/datom (+ base i) :form/src (str "s" i) 536870913) (dd/datom (+ base i) :form/order i 536870913)]) (range n)))]
        (reduce (fn [acc dt] (update acc :eavt conj dt)) {:eavt (:eavt db)} ds)))
  (d/release conn))
(System/exit 0)
