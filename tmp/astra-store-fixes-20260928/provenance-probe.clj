(do
 (require '[seon.db :as db] '[seon.cluster.boot :as boot] '[seon.schema :as schema]
          '[seon.schema.datahike :as bridge] '[seon.cluster.process :as process]
          '[seon.fn :as functions] '[seon.test.accretion :as accretion]
          '[seon.cluster.store :as store] '[seon.cluster.registry :as registry]
          '[seon.id :as id] '[datahike.index.interface :as idx]
          '[konserve.impl.defaults :as defaults] '[clojure.java.io :as io])
 (let [held (:seon.store/store (#'boot/selected-instance "default"))
       original (db/db (boot/connection "default"))
       forms {:seon.db.transaction/process [:string {:min 1 :seon.db/index true}]
              :seon.db.transaction/operation [:qualified-symbol {:seon.db/index true}]
              :seon.db.transaction/agent [:string {:min 1 :seon.db/index true}]}
       projection (schema/build-projection (merge (:seon.schema.projection/forms (db/carried-projection original)) forms))
       rows (mapv #(accretion/schema-row-in projection %) (schema/canonical-schema-entities projection forms))
       parent (keyword (str "astra-provenance-" (id/id)))
       _ (registry/branch! {:seon.store/store held :seon.store/branch parent
                            :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity original))})
       conn (store/open-branch! held parent)]
   (try
     (let [admission (into (bridge/malli->datahike-schema-in projection (vec (keys forms)))
                           (functions/reconcile-tx original rows []))
           seeded (db/transact! conn admission)
           _ (assert (:db-after seeded))
           base (db/db conn)
           findings (vec (take 100 (sort (db/q '[:find [?id ...] :where [_ :seon.lint/id ?id]] base))))
           agent (first (db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]] base))
           provenance (cond-> {:db/id :db/current-tx
                               :seon.db.transaction/process (process/id (process/current-identity))
                               :seon.db.transaction/operation 'scratch.astra.store/provenance-probe}
                        agent (assoc :seon.db.transaction/agent agent))
           mark (fn [database seen]
                  (let [nodes (reduce (fn [out a] (into out (idx/-mark (get database a) seen))) #{}
                                      [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
                        files (filter #(.isFile %) (map #(io/file (:seon.store/dir held) (defaults/key->store-key %)) nodes))]
                    {:nodes (count nodes) :bytes (reduce + (map #(.length %) files))}))
           initial (let [seen (atom #{})] (mark base seen) @seen)]
       {:admission-datoms (count (:tx-data seeded))
        :measurements
        (mapv
         (fn [n]
           (let [results
                 (mapv
                  (fn [candidate?]
                    (let [branch (keyword (str "astra-provenance-write-" (id/id)))
                          _ (registry/branch! {:seon.store/store held :seon.store/branch branch
                                               :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity base))})
                          child (store/open-branch! held branch)]
                      (try
                        (let [tx (mapv (fn [finding] [:db/add [:seon.lint/id finding] :seon.lint/row
                                                     (inc (:seon.lint/row (db/pull base [:seon.lint/row] [:seon.lint/id finding])))])
                                       (subvec findings 0 n))
                              start (System/nanoTime)
                              report (db/transact! child (if candidate?
                                                    (stamp-transaction base
                                                                       (merge {:tx-data tx} (dissoc provenance :db/id :seon.db.transaction/process))
                                                                       nil (process/id (process/current-identity)))
                                                    tx))
                              ms (/ (- (System/nanoTime) start) 1e6)
                              _ (assert (:db-after report))]
                          {:n n :candidate? candidate? :commits 1 :ms ms :datoms (count (:tx-data report))
                           :storage (mark (:db-after report) (atom initial))
                           :answer (db/pull-many (:db-after report) [:seon.lint/id :seon.lint/row]
                                                 (mapv #(vector :seon.lint/id %) (subvec findings 0 n)))})
                        (finally (store/release-branch! child)
                                 (registry/retire-branch! {:seon.store/store held :seon.store/branch branch})))))
                  [false true])]
             (assert (apply = (map :answer results)))
             (mapv #(dissoc % :answer) results)))
         [1 10 100])})
     (finally (store/release-branch! conn)
              (registry/retire-branch! {:seon.store/store held :seon.store/branch parent})))))
