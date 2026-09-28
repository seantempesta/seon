;; Replay recorded outcomes on disposable branches; no test requests.
(require '[seon.db :as db] '[seon.test.runner :as runner])
(require '[seon.test.runner :as runner])
(def execution-members @#'runner/execution-members)
(def worker-identity @#'runner/worker-identity)
(def execution-read @#'runner/execution-read)
(def execution-refusal! @#'runner/execution-refusal!)
(def report-entity @#'runner/report-entity)
(defn complete-members-after
  "Accept immutable member outcomes for an admitted run or its exact writer claim.

  Called by record-tx after the existing blob preparation. Only failing
  outcomes produce reports, keyed by the captured content signature. Program
  rows are never created. A later observation of termination may add its
  transaction without changing the recorded outcome. A member's explicit
  termination observation overrides the completion's default."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/completion]
                  :seon.store/transaction-data]}
  [database {run :seon.test.run/provenance results :seon.test.runner/results
             worker :seon.test.member/worker claim :seon.test.member/claim-tx
             terminated? :seon.test.run/terminated? reach :seon.test/reach-digests}]
  (let [run-id (:seon.test.run/id run)
        operation 'seon.test.runner/record-tx
        members (into {} (map (juxt :seon.test.member/symbol identity))
                      (execution-members database run-id
                                         (mapv (comp symbol :seon.test/sym) results)))
        worker-id (when worker (:db/id (worker-identity database worker)))
        claim-id (when claim (:db/id (execution-read (db/pull database [:db/id] claim))))
        forms (:seon.schema.projection/forms (db/carried-projection database))
        provenance-attributes (mapv first (filter vector? (rest (get forms :seon.test.run/provenance))))
        recorded-run (execution-read
                      (db/pull database
                               (conj (vec (remove #{:seon.test.run/callers-at-head} provenance-attributes))
                                     [:seon.test.run/callers-at-head :limit nil])
                               [:seon.test.run/id run-id]))
        recorded-run (cond-> recorded-run
                       (:seon.test.run/callers-at-head recorded-run)
                       (update :seon.test.run/callers-at-head set))
        _ (when (not= (select-keys run provenance-attributes)
                      (dissoc recorded-run :db/id))
            (execution-refusal! operation run-id :seon.test.run/immutable
                                recorded-run run))
        report-attributes (mapv first (drop 2 (get forms :seon.test.report/report)))
        _ (when (empty? report-attributes)
            (execution-refusal! operation run-id :seon.test/population-unknown
                                :seon.test.report/report :absent))
        outcome-keys [:seon.test.member/pass-count :seon.test.member/fail-count
                      :seon.test.member/error-count :seon.test.member/began?
                      :seon.test.member/ended? :seon.test.member/error]
        prepared
        (mapv
         (fn [result]
           (let [test-symbol (symbol (:seon.test/sym result))
                 member (get members test-symbol)
                 reports
                 (mapv
                  (fn [failure]
                    (let [signature (:seon.test/failure-identity failure)
                          _ (when-not signature
                              (execution-refusal! operation run-id :seon.test/population-unknown
                                                  :captured-claim-signature failure))
                          path (when-let [file (:seon.test.failure/file failure)]
                                 (:seon.fn.file/relative-path
                                  (execution-read
                                   (db/pull database [:seon.fn.file/relative-path] file))))]
                      (report-entity report-attributes test-symbol
                                  (cond-> failure
                                    path (assoc :seon.test.failure/reported-file path)))))
                  (:seon.test/failures result))
                 outcome (cond-> {:seon.test.member/pass-count (:seon.test/pass-count result)
                          :seon.test.member/fail-count (:seon.test/fail-count result)
                          :seon.test.member/error-count (:seon.test/error-count result)
                          :seon.test.member/began? (:seon.test.member/began? result)
                          :seon.test.member/ended? (:seon.test.member/ended? result)}
                           (:seon.test.member/error result)
                           (assoc :seon.test.member/error
                                  (execution-read
                                   (db/pull database [:db/id] (:seon.test.member/error result)))))]
             (when (or (not member)
                       (and (or worker claim (:seon.test.member/claim-tx member))
                            (or (not worker-id) (not claim-id)
                                (not= worker-id (get-in member [:seon.test.member/worker :db/id]))
                                (not= claim-id (get-in member [:seon.test.member/claim-tx :db/id])))))
               (execution-refusal! operation run-id :seon.test/claim-replaced
                                   {:seon.test.member/worker worker
                                    :seon.test.member/claim-tx claim}
                                   (or member :absent)))
             (when (or (not (boolean? (:seon.test.member/began? result)))
                       (not (boolean? (:seon.test.member/ended? result)))
                       (and (pos? (+ (:seon.test/fail-count result)
                                     (:seon.test/error-count result)))
                            (empty? reports)))
               (execution-refusal! operation run-id :seon.test/population-unknown
                                   :complete-outcome-events result))
             {:seon.test.member/value member :seon.test.member/outcome outcome
              :seon.test.run/terminated? (true? (get result :seon.test.run/terminated? terminated?))
              :seon.test.report/values reports})) results)
        _ (when (not= (count results) (count (set (map :seon.test/sym results))))
            (execution-refusal! operation run-id :seon.test.run/immutable
                                :one-outcome-per-member results))
        reports (group-by :seon.test.report/id (mapcat :seon.test.report/values prepared))
        report-tx
        (into []
              (keep (fn [[report-id variants]]
                      (let [wanted (first variants)
                            previous (execution-read
                                      (db/pull database (into [:db/id] report-attributes)
                                               [:seon.test.report/id report-id]))]
                        (when (or (not (apply = variants))
                                  (and (:db/id previous)
                                       (not= wanted (dissoc previous :db/id))))
                          (execution-refusal! operation run-id :seon.test/report-conflict
                                              wanted (or previous variants)))
                        (when-not (:db/id previous) wanted)))) reports)]
    (into report-tx
          (mapcat
           (fn [{member :seon.test.member/value outcome :seon.test.member/outcome
                 terminated? :seon.test.run/terminated?
                 reports :seon.test.report/values}]
             (let [eid (:db/id member)
                   report-ids (set (map :seon.test.report/id reports))
                   old-report-ids (set (execution-read
                                       (db/q '[:find [?id ...] :in $ ?member
                                               :where [?member :seon.test.member/failures ?report]
                                                      [?report :seon.test.report/id ?id]]
                                             database eid)))
                   completed? (:seon.test.member/completed-tx member)]
               (when (and completed?
                          (or (not= outcome (select-keys member outcome-keys))
                              (not= report-ids old-report-ids)))
                 (execution-refusal! operation run-id :seon.test.run/immutable
                                     (select-keys member outcome-keys) outcome))
               (cond-> []
                 (not completed?)
                 (conj (cond-> (assoc outcome :db/id eid
                                      :seon.test.member/completed-tx "datomic.tx")
                         (seq report-ids)
                         (assoc :seon.test.member/failures
                                (set (map #(vector :seon.test.report/id %) report-ids)))
                         ;; The content this execution tested: reuse compares it
                         ;; with the current reach, on any branch or lineage.
                         (string? (get reach (:seon.test.member/symbol member)))
                         (assoc :seon.test.member/reach-digest
                                (get reach (:seon.test.member/symbol member)))))
                 (and terminated? (not (:seon.test.member/terminated-tx member)))
                 (conj [:db/add eid :seon.test.member/terminated-tx "datomic.tx"]))))
           prepared))))


;; Evaluate this one form in an MCP JVM scratch namespace. It executes no tests.
;; Every admission/completion is a replay of real recorded evidence on owned branches.
(do
  (require '[seon.cluster.boot :as boot] '[seon.cluster.store :as store]
           '[seon.cluster.registry :as registry] '[seon.db :as db]
           '[seon.test :as sut] '[seon.test.runner :as runner] '[seon.id :as id]
           '[datahike.index.interface :as idx] '[konserve.impl.defaults :as defaults]
           '[clojure.java.io :as io])
  (let [held (:seon.store/store (#'boot/selected-instance "default"))
        prefix (str "astra-exit-observation-" (id/id))
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
                                      :seon.test.member/began? true :seon.test.member/ended? true :seon.test.run/terminated? false)))
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
                 parts [(subvec results 0 n)]
                 started (System/nanoTime)]
             (try
               (let [writes (mapv (fn [rs]
                                    (let [recorded (db/transact! conn [[:db.fn/call (if batched? complete-members-after runner/complete-members) (completion rs)]])]
                                      (assert (:db-after recorded)) (assert (= (set (map (juxt :seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count) rs)) (set (db/q '[:find ?s ?p ?f ?e :in $ ?run :where [?r :seon.test.run/id ?run] [?r :seon.test.run/members ?m] [?m :seon.test.member/completed-tx] [?m :seon.test.member/symbol ?s] [?m :seon.test.member/pass-count ?p] [?m :seon.test.member/fail-count ?f] [?m :seon.test.member/error-count ?e]] (db/db conn) (:seon.test.run/id provenance)))))
                                      (assoc (mark (db/db conn) seen) :exits (count (filter #(and (= :seon.test.member/terminated-tx (:a %)) (:added %)) (:tx-data recorded))) :completions (count (filter #(and (= :seon.test.member/completed-tx (:a %)) (:added %)) (:tx-data recorded)))))) parts)]
                 {:n n :batched? batched? :commits (count writes) :storage (apply merge-with + writes)
                  :ms (/ (- (System/nanoTime) started) 1e6)})
               (finally (store/release-branch! conn)
                        (registry/retire-branch! {:seon.store/store held :seon.store/branch b})))))
         [[1 false] [1 true] [10 false] [10 true] [100 false] [100 true]]))
      (finally (store/release-branch! connection)
               (registry/retire-branch! {:seon.store/store held :seon.store/branch branch})))))
