(do
(require '[datahike.index.interface :as idx] '[konserve.impl.defaults :as defaults])
(let [base (db/db (boot/connection "default"))
      held (:seon.store/store (#'boot/selected-instance "default"))
      projection (db/carried-projection base)
      shapes (program/shapes-in projection)
      identities (mapv #(vector :seon.fn.file/relative-path %) (take 100 (sort (db/q '[:find [?p ...] :where [_ :seon.fn.file/relative-path ?p]] base))))
      rows (index/published-index-entities base identities)
      desired (mapv #(assoc % :seon.fn.file/derivation-digest (id/digest 64 ["astra-derivation-only" (:seon.fn.file/relative-path %)])) rows)
      mark (fn [database seen]
             (let [nodes (reduce (fn [out a] (into out (idx/-mark (get database a) seen))) #{} [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
                   files (filter #(.isFile %) (map #(io/file (:seon.store/dir held) (defaults/key->store-key %)) nodes))]
               {:nodes (count nodes) :bytes (reduce + (map #(.length %) files))}))
      initial (let [seen (atom #{})] (mark base seen) @seen)]
  (mapv (fn [n]
          (mapv (fn [candidate?]
                  (let [branch (keyword (str "astra-derivation-adoption-" (id/id)))
                        _ (registry/branch! {:seon.store/store held :seon.store/branch branch :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity base))})
                        conn (store/open-branch! held branch)]
                    (try
                      (let [start (System/nanoTime)
                            report (db/call-with-custody {:seon.db/connection conn}
                                     #(db/transact! conn [[:db.fn/call
                                                           (fn [database]
                                                             (schema/call-with-projection projection
                                                               (fn [] (if candidate?
                                                                        (reconcile-after shapes database (subvec desired 0 n) (subvec identities 0 n) true)
                                                                        (#'index/reconcile-tx-in shapes database (subvec desired 0 n) (subvec identities 0 n))))))]]))
                            ms (/ (- (System/nanoTime) start) 1e6)
                            _ (assert (:db-after report))
                            after (index/published-index-entities (:db-after report) (subvec identities 0 n))
                            comparable (fn [rs] (mapv #(dissoc % :seon.fn.file/derivation-digest) rs))]
                        (assert (= (comparable (subvec rows 0 n)) (comparable after)))
                        {:n n :candidate? candidate? :ms ms :commits 1
                         :changed-identities (count (index/report-identities report))
                         :datoms (count (:tx-data report))
                         :storage (mark (:db-after report) (atom initial))})
                      (finally (store/release-branch! conn) (registry/retire-branch! {:seon.store/store held :seon.store/branch branch})))))
                [false true]))
        [1 10 100])))
