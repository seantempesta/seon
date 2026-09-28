(do
(def rendered-value #'db/rendered-value)
(def database-value? db/database-value?)
(def logical-datoms db/logical-datoms)
(def identity-attributes db/identity-attributes)
(def pull db/pull)
(def basis-t db/basis-t)
(defn render-transaction-candidate
  "A transaction as data: its transaction id and the bases before and after,
  how many facts it wrote, each changed entity by its identity with the
  attributes it changed, and its tempids resolved to identities. Offers the
  pull of the first changed entities. References only: identities and bases,
  never the databases' contents."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report] :seon.db.report/view]}
  [unit]
  (let [{database :db-after before :db-before tempids :tempids :as report} (rendered-value unit)
        transaction-data (if (database-value? database) (logical-datoms report) (:tx-data report))
        facts (remove #(= (:e %) (:tx %)) transaction-data)
        identities (when (and (seq facts) (database-value? database))
                     (vec (sort (identity-attributes database))))
        identity-of (fn [entity]
                      (when identities
                        (let [entity-map (merge (when (database-value? before)
                                                  (pull before identities entity))
                                                (pull database identities entity))]
                          (when-let [[attribute value] (first (sort-by (comp str key) (dissoc entity-map :db/id)))]
                            [attribute value]))))
        entities (mapv (fn [[entity datoms]]
                         (let [identity (identity-of entity)]
                           (assoc (if identity {:seon.db.report/identity identity} {:seon.db.report/entity entity})
                                  :seon.db.report/changed (vec (sort (distinct (map :a datoms)))))))
                       (sort-by key (group-by :e facts)))
        resolved (into (sorted-map)
                       (keep (fn [[tempid entity]]
                               (when (and (string? tempid) (not= "datomic.tx" tempid))
                                 (when-let [identity (identity-of entity)] [tempid identity]))))
                       tempids)
        tx (some :tx transaction-data)]
    (cond-> {:seon.db.report/facts (count facts) :seon.db.report/entities entities}
      tx (assoc :seon.db.report/tx tx)
      (database-value? before) (assoc :seon.db.report/basis-before (basis-t before))
      (database-value? database) (assoc :seon.db.report/basis-after (basis-t database))
      (seq resolved) (assoc :seon.db.report/tempids resolved)
      (some :seon.db.report/identity entities)
      (assoc :seon.render/operations
             (into [] (comp (keep :seon.db.report/identity) (take 3)
                            (map #(list 'seon.db/pull ''[*] %)))
                   entities)))))


:ready)