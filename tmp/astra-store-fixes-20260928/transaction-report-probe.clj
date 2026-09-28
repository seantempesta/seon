(defn measure-report-renders [renderers]
  (let [held (:seon.store/store (#'boot/selected-instance "default"))
        base (db/db (boot/connection "default"))
        findings (vec (take 100 (sort (db/q '[:find [?id ...] :where [_ :seon.lint/id ?id]] base))))]
    (mapv
     (fn [n]
       (let [branch (keyword (str "astra-report-" (id/id)))
             _ (registry/branch! {:seon.store/store held :seon.store/branch branch
                                  :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity base))})
             conn (store/open-branch! held branch)]
         (try
           (let [rows (db/pull-many base [:seon.lint/id :seon.lint/row]
                                   (mapv #(vector :seon.lint/id %) (subvec findings 0 n)))
                 tx (mapv (fn [i row] (assoc (update row :seon.lint/row inc) :db/id (str "finding-" i))) (range n) rows)
                 report (db/transact! conn tx)
                 _ (assert (:db-after report))
                 answers (mapv
                          (fn [[label f]]
                            (let [reads (atom []) start (System/nanoTime)
                                  answer (binding [db/*read-evidence-sink* reads] (f report))
                                  ms (/ (- (System/nanoTime) start) 1e6)]
                              {:n n :label label :ms ms :answer answer
                               :operations (frequencies (map #(get-in % [:seon.db/read-request :seon.db/read-operation] :index) @reads))}))
                          renderers)]
             (assert (apply = (map :answer answers)))
             (mapv #(-> % (assoc :entities (count (:seon.db.report/entities (:answer %)))
                                 :tempids (count (:seon.db.report/tempids (:answer %))))
                          (dissoc :answer)) answers))
           (finally (store/release-branch! conn)
                    (registry/retire-branch! {:seon.store/store held :seon.store/branch branch})))))
     [1 10 100])))
