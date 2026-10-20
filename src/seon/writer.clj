(ns seon.writer
  "The writer: the only d/transact, d/branch! and merge caller, with group commit (drain
  the in-port, one Datahike commit per branch, one flush). From proto/flow f2-final
  proto.control's writer (lane F2; writer design A, notes/option3-F1-flow.md section 4)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.lib :as lib]
            [seon.program :as program] [seon.sock :as sock] [seon.step :as step] [seon.config :as cfg]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow]))

(defn- main-commit
  "Main moved to t: the fork server's replica gets the commit (FIFO with fork requests),
  the reconciler the report, and root loads the program change (a restarted fork server
  forks from root). Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/t :seon.db/resolved-tx :seon.host/db] :seon.writer/state]}
  [st o t ops db]
  (step/out! o :to-fork-server {:op :tx :t t :ns (lib/now-ns) :tx-data ops})
  (step/out! o :main {:t t :ops ops :db db :ns (sock/now-ns)})
  (when-let [errs (seq (program/load-changes! db ops))]
    (step/out! o :events (step/event {:type :load-errors :errors (vec errs)})))
  (update st :main-log conj {:t t :ops ops}))

(defn- merge-branch
  "Merge the branch's net change since its merge base into main. Conflicts leave it
  unmerged, as git does. (Lane M1 replaces this with the three-way program merge.)"
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.writer/branch :seon.db/branch] [:tuple :seon.writer/state :seon.writer/branch :map]]}
  [st o {:keys [base log] :as br} branch]
  (let [conn (:conn st)
        {:keys [tx-data touched]} (pdb/delta-tx base log)
        main @conn
        conflicts (pdb/conflicts base main touched)]
    (cond
      (seq conflicts) [st br {:merged? false :tx (:max-tx main) :conflicts conflicts}]
      (empty? tx-data) [st br {:merged? true :tx (:max-tx main) :datoms 0}]
      :else
      (let [rep (d/merge-db conn #{branch} tx-data)
            t (:max-tx (:db-after rep))]
        [(main-commit st o t (pdb/report->tx rep) (:db-after rep))
         (assoc br :base @(:conn br) :log [])
         {:merged? true :tx t :datoms (count tx-data)}]))))

(defn- merge-main-into
  "git merge main, on the branch (lane M1 replaces this with the three-way merge)."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.writer/branch] [:tuple :seon.writer/branch :map]]}
  [{:keys [main-log]} {:keys [conn log main-t] :as br}]
  (let [ops (into (vec (:main-pending br)) (comp (filter #(> (:t %) main-t)) (mapcat :ops)) main-log)
        db @conn
        touched-mine (set (map (fn [[_ [_ e a _]]] [e a]) (pdb/net-change db log)))
        conflicts (vec (distinct (for [[_ [_ e a _]] (pdb/net-change db ops) :when (touched-mine [e a])] {:e e :a a})))]
    (cond
      (seq conflicts) [br {:updated? false :conflicts conflicts}]
      (empty? ops) [br {:updated? true :datoms 0}]
      :else
      (let [rep (d/merge-db conn #{:db} ops)
            t (:max-tx (:db-after rep))
            rops (pdb/report->tx rep)]
        [(-> br (assoc :main-t (apply max main-t (map :t main-log))) (dissoc :main-pending))
         {:updated? true :t t :datoms (count rops) :sync {:op :sync :t t :tx-data rops}}]))))

(defn- realize
  "A branch recovered from the store carries no in-memory log: derive it from Datahike's
  commit graph (base = merge-base of the branch and main)."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.db/branch] :seon.writer/state]}
  [st b]
  (let [br (get-in st [:branches b])]
    (if-not (:recovered br)
      st
      (let [base (pdb/branch-base (:conn st) b)
            head @(:conn br)]
        (assoc-in st [:branches b]
                  (-> br (dissoc :recovered)
                      (assoc :base base :log (pdb/diff-ops base head)
                             :main-pending (pdb/diff-ops base (:boot-main st)))))))))

(defn- with-conn {:malli/schema [:=> [:cat :seon.writer/state [:maybe :seon.db/branch]] :seon.writer/state]}
  [st b]
  (let [st (if (and b (get-in st [:branches b]) (nil? (get-in st [:branches b :conn])))
             (assoc-in st [:branches b :conn] (d/connect (assoc (:cfg st) :branch b)))
             st)]
    (if (and b (get-in st [:branches b :recovered])) (realize st b) st)))

(defn- release-ended {:malli/schema [:=> [:cat :seon.writer/state [:maybe :seon.db/branch]] :seon.writer/state]}
  [st b]
  (let [br (get-in st [:branches b])]
    (if (and (:released br) (:conn br))
      (do (d/release (:conn br)) (update-in st [:branches b] dissoc :conn))
      st)))

(defn- touch {:malli/schema [:=> [:cat :seon.writer/state [:maybe :seon.db/branch]] :seon.writer/state]}
  [st b]
  (if (and b (get-in st [:branches b :conn]))
    (let [t (inc (:tick st 0)) old (get-in st [:branches b :used])]
      (-> st (assoc :tick t) (assoc-in [:branches b :used] t)
          (update :lru (fn [l] (assoc (if old (dissoc l old) (or l (sorted-map))) t b)))))
    st))

(defn- evict-lru {:malli/schema [:=> [:cat :seon.writer/state] :seon.writer/state]}
  [st]
  (loop [st st]
    (if (<= (count (:lru st)) cfg/OPEN-BRANCHES)
      st
      (let [[t b] (first (:lru st))
            br (get-in st [:branches b])
            st (update st :lru dissoc t)]
        (recur (if (and (:conn br) (= t (:used br)))
                 (do (d/release (:conn br)) (update-in st [:branches b] dissoc :conn :used))
                 st))))))

(defn- answer
  "The answer r for dest, as data on :to-api; sent after the batch's flush."
  {:malli/schema [:=> [:cat :seon.host/volatile [:maybe :map] :any] :nil]}
  [o dest r] (when dest (step/out! o :to-api {:dest dest :r r})))

(defn- commit-batch
  "Group commit: every queued transaction, one Datahike commit per branch
  (datahike.writer/transact-batch!), in arrival order. Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile] :seon.writer/state]}
  [st o]
  (reduce
    (fn [st [b ms reps]]
      (do
        (reduce
          (fn [st [m rep]]
            (if-not (map? rep)
              (do (answer o (:dest m) {:ok false :error (step/error-msg rep)}) st)
              (let [t (:max-tx (:db-after rep)) ops (pdb/report->tx rep)]
                (if (= b :db)
                  (do (answer o (:dest m) {:ok true :t t :datoms (count ops)})
                      (main-commit st o t ops (:db-after rep)))
                  (do (step/out! o :events (step/event {:type :tx :agent (:agent m) :branch b :t t :datoms (count ops)}))
                      (answer o (:dest m) {:ok true :t t :datoms (count ops) :tx-data ops
                                           :tempids (into {} (filter (comp string? key)) (:tempids rep))})
                      (update-in st [:branches b :log] into ops))))))
          (update st :commits inc) (map vector ms reps))))
    (dissoc st :pend :pend-order)
    (let [batch (fn [b] (let [ms (get-in st [:pend b])
                              conn (if (= b :db) (:conn st) (get-in st [:branches b :conn]))]
                          [b ms (dw/transact-batch! conn (mapv (fn [m] {:tx-data (:tx-data m)}) ms))]))]
      (if cfg/PAR-COMMIT
        ;; experiment (writer design B): each branch's batch on its own thread
        (mapv deref (mapv #(future (batch %)) (:pend-order st)))
        (map batch (:pend-order st))))))

(defn- other-op
  "Every request but :tx. Answers the new state."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.writer/request] :seon.writer/state]}
  [st o {:keys [op dest] :as m}]
  (let [conn (:conn st) cfg (:cfg st)
        st (cond-> st
             (not (#{:spawn :clone-branch :release} op)) (with-conn (:branch m))
             (= op :clone-branch) (with-conn (:from m)))
        reply (fn [st r] (answer o dest r) st)]
    (case op
      :release (let [br (get-in st [:branches (:branch m)])]
                 (when-let [c (:conn br)] (d/release c))
                 (reply (cond-> st br (update-in [:branches (:branch m)] #(-> % (dissoc :conn) (assoc :released true)))) {:ok true}))
      :spawn (let [{:keys [agent branch slot]} m
                   fork {:op :fork :agent agent :branch branch :ns (:ns m) :fd (:fd m)}]
               (if (get-in st [:branches branch])
                 ;; an existing branch: forked with main, then synced to the branch's head
                 (let [st (realize (with-conn st branch) branch)
                       head @(get-in st [:branches branch :conn])]
                   (step/out! o :to-fork-server fork)
                   (answer o {:agent agent} {:op :sync :resume true :t (:max-tx head) :tx-data (pdb/diff-ops @conn head)})
                   (reply (update-in st [:branches branch] dissoc :released) {:ok true :t (:max-tx head) :resumed true}))
                 ;; the branch, not yet a connection: one is opened at the branch's first
                 ;; op (with-conn), so a spawn costs d/branch! alone
                 (let [main @conn]
                   (d/branch! conn :db branch)
                   (step/out! o :to-fork-server fork)
                   (reply (assoc-in st [:branches branch] {:base main :log [] :main-t (:max-tx main)})
                          {:ok true :t (:max-tx main)}))))
      :clone-branch (let [{:keys [from branch]} m
                          parent (get-in st [:branches from])]
                      (d/branch! conn from branch)
                      (let [bconn (d/connect (assoc cfg :branch branch))]
                        (reply (assoc-in st [:branches branch] (assoc (select-keys parent [:base :log :main-t]) :conn bconn))
                               {:ok true :t (:max-tx @bconn)})))
      :merge-main (let [[br' r] (merge-main-into st (get-in st [:branches (:branch m)]))]
                    (reply (assoc-in st [:branches (:branch m)] br') r))
      :merge (if-let [br (get-in st [:branches (:branch m)])]
               (let [[st br' r] (merge-branch st o br (:branch m))]
                 (reply (assoc-in st [:branches (:branch m)] br') r))
               (reply st {:merged? false :error (str "no branch " (:branch m))}))
      :branch-diff (let [br (get-in st [:branches (:branch m)])]
                     (reply st (if br
                                 (let [{:keys [tx-data]} (pdb/delta-tx (:base br) (:log br))
                                       head @(:conn br)
                                       var-of (fn [e] (or (:var/name (d/entity head e)) (:var/name (d/entity (:base br) e))))]
                                   {:ok true :datoms (count tx-data)
                                    :vars (into (sorted-set) (keep (fn [[_ e a _]] (when (#{:var/src :var/name :var/doc :var/arity} a) (var-of e)))) tx-data)
                                    :notes (count (filter (fn [[op _ a _]] (and (= op :db/add) (= a :note/text))) tx-data))})
                                 {:ok false :error (str "no branch " (:branch m))})))
      :branch-info (let [br (get-in st [:branches (:branch m)])]
                     (reply st (if br
                                 {:ok true :datoms (count (d/datoms @(:conn br) :eavt)) :t (:max-tx @(:conn br)) :log (count (:log br))}
                                 {:ok false :error (str "no branch " (:branch m))})))
      (reply st {:ok false :error (str "unknown op " op)}))))

(defn- writer-one
  "One request into the batch: a :tx joins its branch's queue; any other op first commits
  the queue (it reads committed heads), then runs."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.writer/request] :seon.writer/state]}
  [st o {:keys [op] :as m}]
  (try
    (if (= op :tx)
      (let [b (:branch m)
            st (if (= b :db) st (with-conn st b))]
        (if (or (= b :db) (get-in st [:branches b]))
          (cond-> (update-in st [:pend b] (fnil conj []) m)
            (not (get-in st [:pend b])) (update :pend-order (fnil conj []) b))
          (do (answer o (:dest m) {:ok false :error (str "no branch " b)}) st)))
      (other-op (commit-batch st o) o m))
    (catch Throwable e
      ;; an interop edge (Datahike) refused: the request's answer is the error, as data
      (answer o (:dest m) {:ok false :merged? false :error (step/error-msg e)})
      (dissoc st :pend :pend-order))))

(defn- drain {:malli/schema [:=> [:cat :seon.host/chan :seon.writer/request] [:vector :seon.writer/request]]}
  [ch m]
  (loop [acc [m] k 1]
    (if (< k cfg/WRITER-BATCH)
      (if-let [y (a/poll! ch)] (recur (conj acc y) (inc k)) acc)
      acc)))

(defn writer-proc
  "The writer. In :in (every write request, from the :api and the agents' stand-ins; an
  external channel, so stand-ins in other graphs reach it). Out :to-fork-server (main's
  commits and forks, FIFO), :to-api (answers {:dest :r}, after the batch's flush, and
  {:op :writer :tx-count :p50-ms :commits}), :main (main's commits), :events.
  A closed :in is the end of the flow: everything taken is committed and flushed, then
  {:op :drained} goes out. Answers leave when their batch is committed; the flusher's
  window makes them durable (config FLUSH-MS), a :flush? request before its answer."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:conn "main's connection" :cfg "the store config" :to-writer "its in-port channel"}
       :outs {:to-fork-server "commits and forks, FIFO" :to-api "answers {:dest :r}" :main "main's commits"
              :to-flusher "the store became dirty"}
       ;; its own thread: a transaction is CPU and store work the fiber scheduler cannot see
       :workload :mixed
       :ping-map-fn (fn [st] {:tx-count (:tx-count st) :commits (:commits st) :flushes (:flushes st 0) :dirty (boolean (:dirty st)) :open-branches (count (:lru st)) :main-t (:max-tx @(:conn st))})})
  ([args] (assoc args :branches (into {} (for [b (:recovered args)] [b {:recovered true :released true}]))
                 :main-log [] :tx-count 0 :commits 0 :batches 0
                 ::flow/in-ports {:in (:to-writer args)} ::flow/out-ports {:events (:events args)}))
  ([st tr]
   (when (= tr ::flow/stop)
     (doseq [[_ br] (:branches st)] (when-let [c (:conn br)] (d/release c))))
   st)
  ([st _ m]
   (if (nil? m)
     ;; the in-port closed: what was taken is committed; flush it, then done flows on
     (do (when (and (:durable? st) (:dirty st)) (pdb/sync! (:cfg st)))
         [(assoc st :dirty false) {:to-api [{:op :drained :tx-count (:tx-count st)}]}])
     (let [ms (drain (:to-writer st) m)
           o (volatile! {})
           c0 (:commits st)
           t0 (lib/now-ns)
           flush-due? (some #(= :flush (:op %)) ms)
           reqs (remove #(= :flush (:op %)) ms)
           st (commit-batch (reduce #(writer-one %1 o %2) st reqs) o)
           commits (- (:commits st) c0)
           dirty (or (:dirty st) (pos? commits))
           ;; one flush covers every commit since the last: when the flusher's window ends, or
           ;; now for a write an outside effect follows (:flush?); otherwise the answers go
           ;; out unflushed and the flusher is told once that the store is dirty
           flush-now? (and (:durable? st) dirty (or flush-due? (some :flush? reqs)))
           _ (when flush-now? (pdb/sync! (:cfg st)))
           st (cond
                (not (:durable? st)) st
                flush-now? (-> st (assoc :dirty false :notified false) (update :flushes (fnil inc 0)))
                (and dirty (not (:notified st))) (do (step/out! o :to-flusher {:op :dirty :since (lib/now-ns)})
                                                     (assoc st :dirty true :notified true))
                :else (assoc st :dirty dirty))
           n-tx (count (filter #(= :tx (:op %)) reqs))
           st (-> (reduce (fn [st m] (-> st (release-ended (:branch m)) (release-ended (:from m)) (touch (:from m)) (touch (:branch m)))) st reqs)
                  evict-lru
                  (update :tx-count + n-tx) (update :batches inc))]
       (when (seq reqs)
         (step/out! o :to-api {:op :writer :tx-count (:tx-count st) :batch-ms (lib/ms-since t0) :n-tx n-tx :commits commits}))
       [st @o]))))

(defn flusher-proc
  "The group flush's clock: told once that the store is dirty ({:op :dirty :since ns}), it
  waits out the rest of the flush window (config FLUSH-MS) and asks the writer to flush
  ({:op :flush} on the writer's in-port). The wait blocks only this proc; notices that
  queued meanwhile are covered by the one flush."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:to-writer "the writer's in-port" :flush-ms "the window"}
       :ins {:in "dirty notices from the writer"} :outs {}
       :workload :io
       :ping-map-fn (fn [st] {:flushes-asked (:asked st 0) :flush-ms (:flush-ms st)})})
  ([args] (assoc args :asked 0 ::flow/out-ports {:to-writer (:to-writer args)}))
  ([st _] st)
  ([st _ m]
   (if (nil? m)
     [st nil]
     (let [left-ms (- (:flush-ms st) (quot (- (lib/now-ns) (:since m)) 1000000))]
       (when (pos? left-ms) (Thread/sleep (long left-ms)))
       [(update st :asked inc) {:to-writer [{:op :flush}]}]))))
