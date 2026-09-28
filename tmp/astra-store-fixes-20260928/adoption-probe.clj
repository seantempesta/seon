(require '[seon.db :as db] '[seon.cluster.boot :as boot]
         '[seon.cluster.store :as store] '[seon.cluster.registry :as registry]
         '[seon.id :as id] '[datahike.index.interface :as idx]
         '[konserve.impl.defaults :as defaults] '[clojure.java.io :as io])

(let [base (db/db (boot/connection "default"))
      held (:seon.store/store (#'boot/selected-instance "default"))
      subjects (vec (take 100 (sort (db/q '[:find [?s ...] :where [_ :seon.fn/sym ?s]] base))))
      mark (fn [database seen]
             (let [nodes (reduce (fn [out a] (into out (idx/-mark (get database a) seen))) #{}
                                 [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
                   files (filter #(.isFile %) (map #(io/file (:seon.store/dir held) (defaults/key->store-key %)) nodes))]
               {:nodes (count nodes) :bytes (reduce + (map #(.length %) files))}))
      initial (let [seen (atom #{})] (mark base seen) @seen)]
  (mapv
   (fn [n]
     (let [results
           (mapv
            (fn [candidate?]
              (let [branch (keyword (str "astra-adoption-" (id/id)))
                    _ (registry/branch! {:seon.store/store held :seon.store/branch branch
                                         :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity base))})
                    conn (store/open-branch! held branch)]
                (try
                  (let [record (cond-> {:db/id :db/current-tx
                                        :seon.test/adoption-cluster [:seon.cluster/name "default"]
                                        :seon.test/adoption-inputs #{"src/seon/cluster.clj"}}
                                 (not candidate?) (assoc :seon.test/adoption-identities
                                                        (set (map #(vector :seon.fn/sym %) (subvec subjects 0 n)))))
                        start (System/nanoTime)
                        report (db/transact! conn [record])
                        ms (/ (- (System/nanoTime) start) 1e6)
                        _ (assert (:db-after report))]
                    {:n n :candidate? candidate? :commits 1 :ms ms
                     :datoms (count (:tx-data report)) :storage (mark (:db-after report) (atom initial))
                     :answer (dissoc (db/pull (:db-after report)
                                            [:seon.test/adoption-cluster :seon.test/adoption-inputs]
                                            (get (:tempids report) :db/current-tx)) :db/id)})
                  (finally (store/release-branch! conn)
                           (registry/retire-branch! {:seon.store/store held :seon.store/branch branch})))))
            [false true])]
       (assert (apply = (map :answer results)))
       (mapv #(dissoc % :answer) results)))
   [1 10 100]))
