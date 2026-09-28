;; Evaluate this one form in an MCP JVM scratch namespace. It executes no tests.
;; Every admission/completion is a replay of real recorded evidence on owned branches.
(do
  (require '[seon.cluster.boot :as boot] '[seon.cluster.store :as store]
           '[seon.cluster.registry :as registry] '[seon.db :as db]
           '[seon.test :as sut] '[seon.test.runner :as runner] '[seon.id :as id]
           '[datahike.index.interface :as idx] '[konserve.impl.defaults :as defaults]
           '[clojure.java.io :as io])
  (let [held (:seon.store/store (#'boot/selected-instance "default"))
        prefix (str "astra-store-recording-" (id/id))
        branch (keyword prefix)
        _ (registry/branch! {:seon.store/store held :seon.store/branch branch
                            :seon.cluster.registry/from :cluster-default})
        connection (store/open-branch! held branch)]
    (try
      (let [database (db/db connection)
            names (vec (take 200 (sort (db/q '[:find [?s ...] :where [?m :seon.test.member/symbol ?s]] database))))
            results (->> (runner/latest-results (db/db (boot/connection "default")) names)
                         (filter #(and (pos? (:seon.test/pass-count % 0))
                                       (zero? (:seon.test/fail-count % 1)) (zero? (:seon.test/error-count % 1))))
                         (take 100)
                         (mapv #(assoc (select-keys % [:seon.test/sym :seon.test/pass-count
                                                       :seon.test/fail-count :seon.test/error-count])
                                      :seon.test.member/began? true :seon.test.member/ended? true)))
            _ (assert (= 100 (count results)))
            provenance (runner/provenance database)
            admitted (db/transact! connection
                       [[:db.fn/call sut/admit-run
                         {:seon.test.run/provenance provenance
                          :seon.test.run/cluster [:seon.cluster/name "default"]
                          :seon.test.run/input-digest (db/q '[:find ?d . :where [_ :seon.source/test-input-digest ?d]] database)
                          :seon.test.run/policy :named :seon.test.run/include-long? false
                          :seon.test.run/members (mapv #(hash-map :seon.test.member/symbol (:seon.test/sym %)
                                                                 :seon.test.member/reasons #{:named}) results)}]])
            _ (assert (:db-after admitted))
            commit (registry/connection-branch-commit-id connection branch)
            completion (fn [rs] {:seon.test.runner/results rs :seon.test.run/provenance provenance
                                  :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                                  :seon.test/run-at (:seon.test.run/at provenance) :seon.test.run/terminated? true})
            mark (fn [database seen]
                   (let [nodes (reduce (fn [out a] (into out (idx/-mark (get database a) seen))) #{}
                                       [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
                         files (filter #(.isFile %) (map #(io/file (:seon.store/dir held)
                                                                 (defaults/key->store-key %)) nodes))]
                     {:nodes (count nodes) :files (count files) :bytes (reduce + (map #(.length %) files))}))
            initial (let [seen (atom #{})] (mark (db/db connection) seen) @seen)]
        (mapv
         (fn [[n batched?]]
           (let [b (keyword (str prefix "-" n "-" batched?))
                 _ (registry/branch! {:seon.store/store held :seon.store/branch b :seon.cluster.registry/from commit})
                 conn (store/open-branch! held b) seen (atom initial)
                 parts (if batched? [(subvec results 0 n)] (mapv vector (subvec results 0 n)))
                 started (System/nanoTime)]
             (try
               (let [writes (mapv (fn [rs]
                                    (let [recorded (runner/commit-results! conn (completion rs))]
                                      (assert (vector? recorded))
                                      (mark (db/db conn) seen))) parts)]
                 {:n n :batched? batched? :commits (count writes) :storage (apply merge-with + writes)
                  :ms (/ (- (System/nanoTime) started) 1e6)})
               (finally (store/release-branch! conn)
                        (registry/retire-branch! {:seon.store/store held :seon.store/branch b})))))
         [[1 false] [1 true] [10 false] [10 true] [100 false] [100 true]]))
      (finally (store/release-branch! connection)
               (registry/retire-branch! {:seon.store/store held :seon.store/branch branch})))))
