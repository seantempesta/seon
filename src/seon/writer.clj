(ns seon.writer
  "The writer: the only d/transact, d/branch! and merge caller, with group commit (drain
  the in-port, one Datahike commit per branch, one flush). From proto/flow f2-final
  proto.control's writer (lane F2; writer design A, notes/option3-F1-flow.md section 4)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.lib :as lib]
            [seon.program :as program] [seon.merge :as pm] [seon.contract :as contract] [seon.sock :as sock] [seon.step :as step] [seon.config :as cfg]
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
  st)

(defn- branch-merge
  "seon.merge/three-way of branch b against main, from their merge base over Datahike's
  commit graph (the branch point, or the main the branch last merged), the branch read
  from the store (its agent commits it). data-attrs: the data that merges beside the
  program (lane M1)."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.db/branch :seon.merge/data-attrs] [:tuple :seon.host/db :seon.merge/result]]}
  [{:keys [conn]} b data-attrs]
  (let [head (pdb/head conn b)]
    [head (pm/three-way (pm/program (pdb/branch-base conn b) data-attrs) (pm/program head data-attrs) (pm/program @conn data-attrs))]))

(defn- merge-branch
  "Merge the branch into main (git: only a branch that contains main's head; lane M1). Main's
  program (and declared data) unchanged since the branch's merge base: the branch's changes
  apply as one merge commit, clean by construction. Otherwise the branch is behind
  (:seon.merge/behind: a merge task merges main first). Refused also while the branch has
  unmerged entries (:seon.merge/unmerged) or carries an uncontracted function
  (:seon.merge/uncontracted: contracts decide what is admitted). Answers [st result]."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/branch :seon.merge/data-attrs] [:tuple :seon.writer/state :map]]}
  [st o branch data-attrs]
  (let [conn (:conn st)
        [head r] (branch-merge st branch data-attrs)
        main @conn
        unmerged (pm/unmerged head (pm/program head #{}))
        uncontracted (when contract/on?
                       (vec (sort-by str (for [[[k sym] [_ ov]] (:ours r)
                                               :when (and (= :var k) (= :fn (:kind ov)) (nil? (:contract ov)))]
                                           sym))))]
    (cond
      (seq (:theirs r))
      [st (contract/error :seon.merge/behind 'seon.writer/merge-branch
                          (str "main changed " (count (:theirs r)) " key(s) since the branch's merge base: merge main into the branch first")
                          {:merged? false :tx (:max-tx main) :behind (count (:theirs r))
                           :seon.error/fix "A merge task merges main into the branch (my.branch/merge-main!), resolves any conflict, and its proofs run on the combined program."})]
      (seq unmerged)
      [st (contract/error :seon.merge/unmerged 'seon.writer/merge-branch
                          (str "the branch has " (count unmerged) " unmerged entr(ies)")
                          {:merged? false :tx (:max-tx main) :unmerged (mapv :key unmerged)})]
      (seq uncontracted)
      [st (contract/error :seon.merge/uncontracted 'seon.writer/merge-branch
                          (str "the branch carries " (count uncontracted) " function(s) without a contract")
                          {:merged? false :tx (:max-tx main) :uncontracted uncontracted
                           :seon.error/fix "Declare each function's contract ({:malli/schema [:=> [:cat ...] ...]}) and merge again."})]
      (empty? (:ours r)) [st {:merged? true :tx (:max-tx main) :datoms 0 :keys 0}]
      :else
      (let [tx (pm/apply-tx main (into {} (for [[k [_ ov]] (:ours r)] [k ov])))
            ;; git: the branch head becomes a parent, so the merge base moves to it
            rep (d/merge-db conn #{branch} tx)
            t (:max-tx (:db-after rep))
            ops (pdb/report->tx rep)]
        [(main-commit st o t ops (:db-after rep))
         {:merged? true :tx t :datoms (count ops) :keys (count (:ours r))}]))))

(defn- answer
  "The answer r for dest, as data on :to-api."
  {:malli/schema [:=> [:cat :seon.host/volatile [:maybe :map] :any] :nil]}
  [o dest r] (when dest (step/out! o :to-api {:dest dest :r r})))

(defn- commit-batch
  "Group commit of root's writes to main: every queued transaction, one Datahike commit
  (datahike.writer/transact-batch!), in arrival order. Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile] :seon.writer/state]}
  [st o]
  (if-let [ms (seq (:pend st))]
    (reduce (fn [st [m rep]]
              (if-not (map? rep)
                (do (answer o (:dest m) {:ok false :error (step/error-msg rep)}) st)
                (let [t (:max-tx (:db-after rep)) ops (pdb/report->tx rep)]
                  (answer o (:dest m) {:ok true :t t :datoms (count ops)})
                  (main-commit st o t ops (:db-after rep)))))
            (-> st (dissoc :pend) (update :commits inc))
            (map vector ms (dw/transact-batch! (:conn st) (mapv (fn [m] {:tx-data (:tx-data m)}) ms))))
    st))

(defn- other-op
  "Every request but :tx. Answers the new state."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.writer/request] :seon.writer/state]}
  [st o {:keys [op dest] :as m}]
  (let [conn (:conn st)
        exists? (fn [b] (contains? (d/branches conn) b))
        reply (fn [st r] (answer o dest r) st)]
    (case op
      ;; an agent process committed its branch: the store is dirty until the next flush
      :agent-commit (assoc st :dirty true)
      ;; the agent's branch (new: a pointer to main's head), then its fork: the agent opens
      ;; the branch itself and owns its writes (run-ahead, committed by the agent)
      :spawn (let [{:keys [agent branch]} m
                   resumed? (exists? branch)]
               (when-not resumed? (d/branch! conn :db branch))
               (step/out! o :to-fork-server {:op :fork :agent agent :branch branch :ns (:ns m) :fd (:fd m)
                                              :cfg (:cfg st) :lease (:lease m) :resumed resumed?})
               (reply st {:ok true :t (:max-tx @conn) :resumed resumed?}))
      :clone-branch (do (d/branch! conn (:from m) (:branch m))
                        (reply st {:ok true :t (:max-tx (pdb/head conn (:branch m))) :cfg (:cfg st) :lease (:lease m)}))
      :merge (if (exists? (:branch m))
               (let [[st r] (merge-branch st o (:branch m) (:data-attrs m #{}))] (reply st r))
               (reply st {:merged? false :error (str "no branch " (:branch m))}))
      ;; one derivation (seon.merge/three-way) for the dashboard, the done rule and merges
      :branch-diff (reply st (if (exists? (:branch m))
                               (let [[head r] (branch-merge st (:branch m) (:data-attrs m #{}))]
                                 {:ok true :ours (vec (sort-by str (keys (:ours r)))) :theirs (count (:theirs r))
                                  :conflicts (count (:conflicts r))
                                  :unmerged (mapv :key (pm/unmerged head (pm/program head #{})))
                                  :vars (into (sorted-set) (keep (fn [[k s]] (when (= :var k) s))) (keys (:ours r)))})
                               {:ok false :error (str "no branch " (:branch m))}))
      ;; root's read of a branch (or main): its committed value from the store; :base-t is
      ;; main's t at the branch's merge base (what of main it holds)
      :branch-db (reply st (cond
                             (= :db (:branch m)) {:ok true :db @conn}
                             (exists? (:branch m)) {:ok true :db (pdb/head conn (:branch m))
                                                    :base-t (:max-tx (pdb/branch-base conn (:branch m)))}
                             :else {:ok false :error (str "no branch " (:branch m))}))
      :branch-info (reply st (if (exists? (:branch m))
                               (let [h (pdb/head conn (:branch m))] {:ok true :datoms (count (d/datoms h :eavt)) :t (:max-tx h)})
                               {:ok false :error (str "no branch " (:branch m))}))
      (reply st {:ok false :error (str "unknown op " op)}))))

(defn- writer-one
  "One request into the batch: root's :tx on main joins the batch; any other op first
  commits the batch (it reads committed heads), then runs."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.writer/request] :seon.writer/state]}
  [st o {:keys [op] :as m}]
  (try
    (if (= op :tx)
      (update st :pend (fnil conj []) m)
      (other-op (commit-batch st o) o m))
    (catch Throwable e
      ;; an interop edge (Datahike) refused: the request's answer is the error, as data
      (answer o (:dest m) {:ok false :merged? false :error (step/error-msg e)})
      (dissoc st :pend))))

(defn- drain {:malli/schema [:=> [:cat :seon.host/chan :seon.writer/request] [:vector :seon.writer/request]]}
  [ch m]
  (loop [acc [m] k 1]
    (if (< k cfg/WRITER-BATCH)
      (if-let [y (a/poll! ch)] (recur (conj acc y) (inc k)) acc)
      acc)))

(defn writer-proc
  "The writer: root's own writes to main (group commit), d/branch!, and every merge into
  main. Each agent process writes its own branch (run-ahead, seon.agent) and tells it
  :agent-commit, so the one flush covers it. In :in (every write request, from the :api
  and the agents' stand-ins). Out :to-fork-server (main's
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
       :ping-map-fn (fn [st] {:tx-count (:tx-count st) :commits (:commits st) :flushes (:flushes st 0) :dirty (boolean (:dirty st)) :main-t (:max-tx @(:conn st))})})
  ([args] (assoc args :tx-count 0 :commits 0 :batches 0
                 ::flow/in-ports {:in (:to-writer args)} ::flow/out-ports {:events (:events args)}))
  ([st tr]
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
           dirty (or (:dirty st) (pos? commits))  ; root's commits, or an agent's (:agent-commit)
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
           st (-> st (update :tx-count + n-tx) (update :batches inc))]
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
