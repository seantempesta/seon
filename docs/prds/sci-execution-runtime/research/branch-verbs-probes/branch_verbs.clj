(ns env-probes.branch-verbs
  "Probes for the `my.branch` verb design under the seon.env model.

  Load-only JVM evaluation against an ISOLATED file store under `tmp/`.
  Never touches the shared default cluster or its operator root.

  Each probe returns plain data; `run` returns one
  `{:probe/verdict ...}` map covering all five questions:

  i.   enumerate the branches of one store;
  ii.  fork a branch, transact distinct datoms in each, read each head
       plus a pinned (`as-of`) value;
  iii. pass branch B's database value explicitly to a `seon.db` read while
       current resolution (`seon.db/*conn*`) names branch A — the explicit
       value must win;
  iv.  attempt a WRITE against a foreign branch's connection — the
       `seon.db/transact!` custody fence must refuse it as a flat error
       value, while reads stay open;
  v.   rough timings for a branch fork and for obtaining a foreign
       branch's head database value."
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [datahike.versioning :as versioning]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]))

(def ^:private store-dir "tmp/branch-probe-store")

(def ^:private label-schema
  [{:db/ident :probe/label
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(defn- delete-recursively!
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (.delete ^java.io.File child)))))

(defn- elapsed-ms
  [started-ns]
  (/ (double (- (System/nanoTime) started-ns)) 1e6))

(defn- timed
  "Run `thunk` `n` times, returning {:probe/samples-ms [...] :probe/median-ms x}."
  [n thunk]
  (let [samples (vec (for [i (range n)]
                       (let [started (System/nanoTime)]
                         (thunk i)
                         (elapsed-ms started))))
        sorted (vec (sort samples))]
    {:probe/samples-ms (mapv #(Double/parseDouble (format "%.3f" %)) samples)
     :probe/median-ms (Double/parseDouble
                       (format "%.3f" (nth sorted (quot (count sorted) 2))))}))

(defn- store-log
  "Walk one branch's commit history from a store-level handle only.

  `versioning/branch-history` requires a connection ATTACHED to the branch
  (it reads `(:branch (:config @conn))`). Root holds only the store's main
  connection, so its log walk is `branch-as-db` -> `parent-commit-ids` ->
  `commit-as-db`, all of which accept a connection, db value, or raw store."
  [conn-or-store branch]
  (loop [pending [(d/branch-as-db conn-or-store branch)]
         seen #{}
         walked []]
    (if-let [database (first pending)]
      (let [commit-id (d/commit-id database)]
        (if (seen commit-id)
          (recur (vec (rest pending)) seen walked)
          (let [parents (d/parent-commit-ids database)]
            (recur (into (vec (rest pending))
                         (keep #(d/commit-as-db conn-or-store %) parents))
                   (conj seen commit-id)
                   (conj walked {:datahike/commit-id commit-id
                                 :t (db/basis-t database)
                                 :datahike/parents (vec parents)})))))
      walked)))

(defn- labels
  "Every `:probe/label` value visible in `database`, sorted."
  [database]
  (vec (sort (db/q '[:find [?l ...] :where [_ :probe/label ?l]] database))))

(defn run
  "Run every branch-verb probe on a fresh isolated store; return the verdict."
  ([] (run {}))
  ([{:probe/keys [fork-samples head-samples]
     :or {fork-samples 10 head-samples 20}}]
   (delete-recursively! store-dir)
   (let [opened (store/open-store! {:seon.store/dir store-dir})
         main (:seon.store/connection-object opened)
         branch-connections (atom [])]
     (try
       ;; Schema lives on the main branch so both forks inherit it.
       (d/transact main label-schema)

       (let [roster-before (registry/roster opened)

             ;; --- (ii) fork two branches from :db -------------------------
             fork-a (registry/branch! {:seon.store/store opened
                                       :seon.cluster.registry/from :db
                                       :seon.store/branch :probe-a})
             fork-b (registry/branch! {:seon.store/store opened
                                       :seon.cluster.registry/from :db
                                       :seon.store/branch :probe-b})
             roster-after (registry/roster opened)

             conn-a (store/open-branch! opened :probe-a)
             conn-b (store/open-branch! opened :probe-b)
             _ (swap! branch-connections into [conn-a conn-b])

             report-a1 (d/transact conn-a [{:probe/label "a-first"}])
             pinned-a (:db-after report-a1)
             pinned-a-t (db/basis-t pinned-a)
             _ (d/transact conn-a [{:db/id [:probe/label "a-first"]
                                    :probe/label "a-second"}])
             _ (d/transact conn-b [{:probe/label "b-only"}])

             head-a (d/db conn-a)
             head-b (d/db conn-b)
             as-of-a (db/as-of head-a pinned-a-t)

             ;; Root's cross-branch read WITHOUT holding the branch's
             ;; connection: the main connection's store answers.
             foreign-head-b (d/branch-as-db main :probe-b)

             ;; --- log: the commit walk ------------------------------------
             history-a (async/<!! (versioning/branch-history conn-a))
             log-a (mapv (fn [database]
                           {:datahike/commit-id (d/commit-id database)
                            :t (db/basis-t database)})
                         history-a)

             ;; --- (iii) caller-wins over current resolution ---------------
             caller-wins
             (binding [db/*conn* conn-a]
               {:probe/current-label (labels (db/db))
                :probe/explicit-head-b-label (labels head-b)
                :probe/explicit-foreign-head-b-label (labels foreign-head-b)
                :probe/explicit-pinned-a-label (labels pinned-a)
                :probe/explicit-as-of-a-label (labels as-of-a)})

             ;; --- (iv) custody fence on a foreign WRITE -------------------
             foreign-write
             (binding [db/*conn* conn-a]
               (db/transact! conn-b [{:probe/label "smuggled"}]))
             own-write
             (binding [db/*conn* conn-a]
               (db/transact! conn-a [{:probe/label "a-third"}]))
             unbound-foreign-write
             (db/transact! conn-b [{:probe/label "unbound-write"}])

             ;; --- (vi) log/diff from the MAIN connection only --------------
             log-b (store-log main :probe-b)
             log-a-store (store-log main :probe-a)
             shared (set/intersection
                     (set (map :datahike/commit-id log-a-store))
                     (set (map :datahike/commit-id log-b)))
             fork-point (->> log-b
                             (filter #(shared (:datahike/commit-id %)))
                             (sort-by :t)
                             last)
             since-b (db/since (d/branch-as-db main :probe-b) (:t fork-point))
             since-a (db/since (d/branch-as-db main :probe-a) (:t fork-point))
             ;; `since` is a filtered view of CURRENT datoms whose tx > t, so
             ;; a value later replaced never appears. Retractions require the
             ;; history view.
             since-history-a
             (mapv (fn [datom]
                     {:a (:a datom) :v (:v datom) :added (:added datom)})
                   (d/datoms (d/since (d/history
                                       (d/branch-as-db main :probe-a))
                                      (:t fork-point))
                             :eavt))
             attached-history-a
             (try
               (mapv d/commit-id
                     (async/<!! (versioning/branch-history conn-a)))
               (catch Throwable failure (.getMessage failure)))

             ;; --- (v) timings ---------------------------------------------
             fork-timing
             (timed fork-samples
                    (fn [i]
                      (registry/branch!
                       {:seon.store/store opened
                        :seon.cluster.registry/from :db
                        :seon.store/branch (keyword (str "probe-timing-" i))})))
             head-timing
             (timed head-samples (fn [_] (d/branch-as-db main :probe-b)))

             roster-final (registry/roster opened)]

         {:probe/verdict
          {:probe/i-enumerate
           {:probe/roster-before roster-before
            :probe/roster-after roster-after
            :probe/fork-a fork-a
            :probe/fork-b fork-b
            :probe/pass (and (= #{:db} roster-before)
                             (= #{:db :probe-a :probe-b} roster-after))}

           :probe/ii-fork-and-pin
           {:probe/head-a-label (labels head-a)
            :probe/head-b-label (labels head-b)
            :probe/pinned-a-t pinned-a-t
            :probe/pinned-a-label (labels pinned-a)
            :probe/as-of-a-label (labels as-of-a)
            :probe/head-a-commit-id (d/commit-id head-a)
            :probe/head-b-commit-id (d/commit-id head-b)
            :probe/foreign-head-b-commit-id (d/commit-id foreign-head-b)
            :probe/log-a log-a
            :probe/pass (and (= ["a-second"] (labels head-a))
                             (= ["b-only"] (labels head-b))
                             (= ["a-first"] (labels pinned-a))
                             (= ["a-first"] (labels as-of-a))
                             (not= (d/commit-id head-a) (d/commit-id head-b))
                             (= (d/commit-id head-b)
                                (d/commit-id foreign-head-b))
                             (>= (count log-a) 2))}

           :probe/iii-caller-wins
           (assoc caller-wins
                  :probe/pass
                  (and (= ["a-second"] (:probe/current-label caller-wins))
                       (= ["b-only"] (:probe/explicit-head-b-label caller-wins))
                       (= ["b-only"]
                          (:probe/explicit-foreign-head-b-label caller-wins))
                       (= ["a-first"] (:probe/explicit-pinned-a-label caller-wins))
                       (= ["a-first"]
                          (:probe/explicit-as-of-a-label caller-wins))))

           :probe/iv-custody-fence
           {:probe/foreign-write-error-kind (:seon.error/kind foreign-write)
            :probe/foreign-write-message (:seon.error/message foreign-write)
            :probe/foreign-write-data (:seon.error/data foreign-write)
            :probe/own-write-tx (:tx own-write)
            :probe/unbound-foreign-write-committed?
            (some? (:db-after unbound-foreign-write))
            :probe/branch-b-label-after (labels (d/db conn-b))
            :probe/reads-open?
            (= ["b-only" "unbound-write"]
               (binding [db/*conn* conn-a]
                 (labels (d/branch-as-db main :probe-b))))
            :probe/pass
            (and (= :seon.db/foreign-connection
                    (:seon.error/kind foreign-write))
                 (some? (:tx own-write))
                 ;; Unbound (system) callers are deliberately NOT fenced.
                 (some? (:db-after unbound-foreign-write))
                 ;; the refused write left no datom on branch B
                 (not (some #{"smuggled"} (labels (d/db conn-b)))))}

           :probe/vi-log-and-diff
           {:probe/store-log-b log-b
            :probe/shared-ancestor-commits (vec shared)
            :probe/fork-point fork-point
            :probe/since-b-labels (labels since-b)
            :probe/since-a-labels (labels since-a)
            :probe/since-history-a since-history-a
            :probe/head-b-labels (labels (d/branch-as-db main :probe-b))
            :probe/attached-history-a-count
            (if (string? attached-history-a)
              attached-history-a
              (count attached-history-a))
            :probe/pass
            (and (seq log-b)
                 (some? fork-point)
                 ;; `since` from the fork point shows only what THIS branch
                 ;; added — the honest one-branch diff.
                 (= ["b-only" "unbound-write"] (labels since-b))
                 (= ["a-second" "a-third"] (labels since-a)))}

           :probe/v-timings
           {:probe/branch-fork fork-timing
            :probe/foreign-head-value head-timing
            :probe/roster-final-count (count roster-final)}}})
       (finally
         (doseq [conn @branch-connections]
           (try (d/release conn) (catch Throwable _ nil)))
         (store/release-store! opened)
         (delete-recursively! store-dir))))))
