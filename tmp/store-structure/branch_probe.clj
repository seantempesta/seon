;; store-structure-opus: the same run-commit shapes as probe.clj, on a disposable
;; branch of default's live store, so every attribute appends into its real range.
;; Bytes = new node files reachable from the six indexes after each commit
;; (index.interface/-mark with one shared seen set) + the commit record file.
;; Loaded in default's JVM by eval_clj :jvm; raw Datahike on the disposable branch.
(require '[datahike.api :as d]
         '[datahike.index.interface :as di]
         '[konserve.impl.defaults :as kd]
         '[clojure.java.io :as io])

(def store-dir "/Users/sean/src/seon/data/store")

(defn file-bytes [k]
  (let [f (io/file store-dir (kd/key->store-key k))] (if (.exists f) (.length f) 0)))

(def index-keys [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])

(defn new-nodes!
  "Addresses reachable from `db`'s indexes that `seen` did not hold, by index."
  [db seen]
  (into {} (for [k index-keys] [k (vec (di/-mark (get db k) seen))])))

(defn run-data [variant i refs]
  (let [{:keys [agent-e ns-e runtime-e process]} refs
        {:keys [tx-refs? eval-at? author? process? stamp-index?]} variant
        now (java.util.Date.)
        turn-id (str "store-structure-probe-" i)
        stamp-ns (if stamp-index? "seon.db.transaction" "probe.tx")]
    [(cond-> {:db/id "turn" :seon.turn/id turn-id
              :seon.turn/reply-size (+ 40 (mod (* 37 i) 900))
              :seon.turn/agent agent-e :seon.turn/starting-ns ns-e}
       tx-refs? (assoc :seon.turn/opened-tx :db/current-tx :seon.turn/closed-tx :db/current-tx))
     [:db/add runtime-e :seon.runtime/turns "turn"]
     (cond-> {:seon.cluster.eval/id (str turn-id "-0") :seon.cluster.eval/run "turn"
              :seon.cluster.eval/ordinal 0
              :seon.cluster.eval/source (str "(inc " i ")") :seon.cluster.eval/ns ns-e
              :seon.eval/shown (str (inc i)) :seon.eval/duration-ms (mod i 7)}
       eval-at? (assoc :seon.cluster.eval/at now)
       author? (assoc :seon.cluster.eval/author :system))
     (cond-> {:db/id :db/current-tx
              (keyword stamp-ns "operation") 'seon.turn/record-evaluated-call
              (keyword stamp-ns "agent") "root"}
       process? (assoc (keyword stamp-ns "process") process))]))

(def current {:tx-refs? true :eval-at? true :author? true :process? true :stamp-index? true})

(def variants
  (array-map :current current
             :no-process (assoc current :process? false)
             :stamps-unindexed (assoc current :stamp-index? false)
             :all+unindexed (assoc current :process? false :tx-refs? false :eval-at? false
                                   :author? false :stamp-index? false)))

(defn measure! [conn refs commits]
  (let [cfg (:config @conn)
        from (:branch cfg)
        seen (atom #{})
        _ (new-nodes! @conn seen)
        rows
        (vec
         (for [[label variant] variants]
           (let [branch (keyword (str "store-structure-probe-" (name label)))
                 _ (d/branch! conn from branch)
                 bconn (d/connect (assoc cfg :branch branch))]
             (try
               (when-not (:stamp-index? variant)
                 (d/transact bconn [{:db/ident :probe.tx/process :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                                    {:db/ident :probe.tx/operation :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one}
                                    {:db/ident :probe.tx/agent :db/valueType :db.type/string :db/cardinality :db.cardinality/one}]))
               ;; one warm commit, then measured ones
               (d/transact bconn (run-data variant 0 refs))
               (new-nodes! @bconn seen)
               (let [per (vec (for [i (range 1 (inc commits))]
                                (let [t0 (System/nanoTime)
                                      report @(d/transact! bconn {:tx-data (run-data variant i refs)})
                                      ms (/ (- (System/nanoTime) t0) 1e6)
                                      nodes (new-nodes! (:db-after report) seen)
                                      record (file-bytes (d/commit-id (:db-after report)))]
                                  {:ms ms :record record
                                   :attrs (count (distinct (map :a (:tx-data report))))
                                   :nodes (update-vals nodes count)
                                   :node-bytes (reduce + (map file-bytes (apply concat (vals nodes))))})))
                     n (double commits)
                     mean #(/ (reduce + (map % per)) n)]
                 {:variant label :attrs (:attrs (first per))
                  :nodes (/ (Math/round (* 10 (mean #(reduce + (vals (:nodes %)))))) 10.0)
                  :by-index (into {} (for [k index-keys] [k (/ (Math/round (* 10 (mean #(get-in % [:nodes k])))) 10.0)]))
                  :node-bytes (long (mean :node-bytes))
                  :record-bytes (long (mean :record))
                  :commit-ms (/ (Math/round (* 10 (mean :ms))) 10.0)})
               (finally
                 (d/release bconn)
                 (d/delete-branch! conn branch))))))]
    rows))
