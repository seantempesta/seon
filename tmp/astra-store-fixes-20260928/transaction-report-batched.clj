(do (def pull-many db/pull-many)
(defn render-transaction-batched
  "A transaction's identities, changed attributes, tempids and bases as data.
  Pull each distinct entity once per database value, sharing the identities
  between changed-entity rows and tempids. No database contents are returned."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report]
    [:or :seon.db.report/view :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [unit]
  (let [{database :db-after before :db-before tempids :tempids :as report} (rendered-value unit)
        transaction-data (if (database-value? database) (logical-datoms report) (:tx-data report))
        facts (filterv #(not= (:e %) (:tx %)) transaction-data)
        grouped (group-by :e facts)
        temporary (into {} (filter (fn [[k _]] (and (string? k) (not= "datomic.tx" k)))) tempids)
        ids (vec (sort (into (set (keys grouped)) (vals temporary))))
        selector (when (and (seq ids) (database-value? database))
                   (vec (sort (identity-attributes database))))
        read-rows (fn [basis]
                    (if (= 1 (count ids))
                      (let [row (pull basis selector (first ids))]
                        (if (or (:seon.db/invalid-read row) (:datahike.budget/name row)) row [row]))
                      (pull-many basis selector ids)))
        old-rows (if (and selector (database-value? before)) (read-rows before) [])
        new-rows (if (and selector (vector? old-rows)) (read-rows database) [])]
    (cond
      (not (vector? old-rows)) old-rows
      (not (vector? new-rows)) new-rows
      :else
      (let [rows (merge-with merge (zipmap ids old-rows) (zipmap ids new-rows))
            priority (sort-by str selector)
            identities (into {} (keep (fn [[entity row]]
                                        (when-let [identity (some #(find row %) priority)]
                                          [entity (vec identity)]))) rows)
            entities (mapv (fn [[entity datoms]]
                             (assoc (if-let [identity (get identities entity)]
                                      {:seon.db.report/identity identity} {:seon.db.report/entity entity})
                                    :seon.db.report/changed (vec (sort (distinct (map :a datoms))))))
                           (sort-by key grouped))
            resolved (into (sorted-map) (keep (fn [[tempid entity]]
                                               (when-let [identity (get identities entity)] [tempid identity]))) temporary)
            tx (some :tx transaction-data)]
        (cond-> {:seon.db.report/facts (count facts) :seon.db.report/entities entities}
          tx (assoc :seon.db.report/tx tx)
          (database-value? before) (assoc :seon.db.report/basis-before (basis-t before))
          (database-value? database) (assoc :seon.db.report/basis-after (basis-t database))
          (seq resolved) (assoc :seon.db.report/tempids resolved)
          (some :seon.db.report/identity entities)
          (assoc :seon.render/operations
                 (into [] (comp (keep :seon.db.report/identity) (take 3)
                                (map #(list 'seon.db/pull ''[*] %))) entities)))))))


:ready)