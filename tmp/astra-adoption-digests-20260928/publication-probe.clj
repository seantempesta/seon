(let [base (db/db (boot/connection "default"))
      held (:seon.store/store (#'boot/selected-instance "default"))
      projection (db/carried-projection base) shapes (program/shapes-in projection)
      identities (mapv #(vector :seon.fn.file/relative-path %) (take 100 (sort (db/q '[:find [?p ...] :where [_ :seon.fn.file/relative-path ?p]] base))))
      before (index/published-index-entities base identities)
      rows (-> (mapv #(assoc % :seon.fn.file/derivation-digest (id/digest 64 ["adoption-publication" (:seon.fn.file/relative-path %)])) before)
               (assoc-in [0 :seon.fn.file/digest] (id/digest 64 ["adoption-new-content"])))
      branches (mapv #(keyword (str "astra-derivation-" % "-" (id/id))) ["publication" "adoption"])]
  (doseq [branch branches] (registry/branch! {:seon.store/store held :seon.store/branch branch :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity base))}))
  (let [[published adopted :as connections] (mapv #(store/open-branch! held %) branches)]
    (try
      (let [publication (db/call-with-custody {:seon.db/connection published}
                          #(db/transact! published [[:db.fn/call (fn [database] (reconcile-after shapes database rows identities false))]]))
            _ (assert (:db-after publication))
            source-rows (index/published-index-entities (:db-after publication) identities)
            report (db/call-with-custody {:seon.db/connection adopted}
                     #(db/transact! adopted [[:db.fn/call (fn [database] (reconcile-after shapes database source-rows identities true))]]))
            _ (assert (:db-after report))
            after (index/published-index-entities (:db-after report) identities)]
        (assert (= rows source-rows))
        (assert (= (first rows) (first after)))
        (assert (= (subvec before 1) (subvec after 1)))
        {:published-identities (count (index/report-identities publication))
         :adopted-identities (count (index/report-identities report))
         :changed-file-adopted? true :derivation-only-files-unchanged 99})
      (finally (doseq [c connections] (store/release-branch! c))
               (doseq [b branches] (registry/retire-branch! {:seon.store/store held :seon.store/branch b}))))))
