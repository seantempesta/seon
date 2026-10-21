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
    [head (pm/three-way (pm/program (pdb/branch-base conn (pdb/conn-main conn) b) data-attrs) (pm/program head data-attrs) (pm/program @conn data-attrs))]))

(defn- refused
  "The writer refusing a request, as the one error map (:seon.writer/refused) with the
  members its callers branch on (:ok false; :merged? false on a merge)."
  {:malli/schema [:=> [:cat :string :map] :seon.writer/refused]}
  [why members] (contract/error :seon.writer/refused 'seon.writer/writer-proc why (merge {:ok false} members)))

(defn- merge-refusal
  "Why branch head (its three-way r against main) cannot merge into main now (lane M1), or
  nil: main's program (and declared data) changed since the branch's merge base
  (:seon.merge/behind: a merge task merges main first); the branch has unmerged entries
  (:seon.merge/unmerged); it carries an uncontracted function (:seon.merge/uncontracted:
  contracts decide what is admitted)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.merge/result] [:maybe :map]]}
  [main head r]
  (let [unmerged (pm/unmerged head (pm/program head #{}))
        uncontracted (when contract/on?
                       (vec (sort-by str (for [[[k sym] [_ ov]] (:ours r)
                                               :when (and (= :var k) (= :fn (:kind ov)) (nil? (:contract ov)))]
                                           sym))))]
    (cond
      (seq (:theirs r))
      (contract/error :seon.merge/behind 'seon.writer/merge-branch
                      (str "main changed " (count (:theirs r)) " key(s) since the branch's merge base: merge main into the branch first")
                      {:merged? false :tx (:max-tx main) :behind (count (:theirs r))
                       :seon.error/fix "A merge task merges main into the branch (my.branch/merge-main!), resolves any conflict, and its proofs run on the combined program."})
      (seq unmerged)
      (contract/error :seon.merge/unmerged 'seon.writer/merge-branch
                      (str "the branch has " (count unmerged) " unmerged entr(ies)")
                      {:merged? false :tx (:max-tx main) :unmerged (mapv :key unmerged)})
      (seq uncontracted)
      (contract/error :seon.merge/uncontracted 'seon.writer/merge-branch
                      (str "the branch carries " (count uncontracted) " function(s) without a contract")
                      {:merged? false :tx (:max-tx main) :uncontracted uncontracted
                       :seon.error/fix "Declare each function's contract ({:malli/schema [:=> [:cat ...] ...]}) and merge again."}))))

(defn- merge-train
  "Merge branches into main in ONE commit (a merge train, lane R3; git's octopus merge, every
  member a parent): each member that holds main's program head and is not refused lands, in
  order; a member whose changes meet an earlier member's is behind (it merges main first).
  Members are judged against the same main, so the commit is each one's clean merge.
  Answers [st {:results [answer per branch, in order]}]."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile [:vector :seon.db/branch] :seon.merge/data-attrs] [:tuple :seon.writer/state :map]]}
  [st o branches data-attrs]
  (let [conn (:conn st)
        main @conn
        judged (reduce (fn [{:keys [taken] :as acc} b]
                         (let [have? (pdb/branch? conn b)
                               [head r] (when have? (branch-merge st b data-attrs))
                               refusal (if have? (merge-refusal main head r) (refused (str "no branch " b) {:merged? false}))
                               refusal (or refusal
                                           (when (some taken (keys (:ours r)))
                                             (contract/error :seon.merge/behind 'seon.writer/merge-train
                                                             "another member of the train changes the same definitions: merge main into the branch first"
                                                             {:merged? false :tx (:max-tx main) :behind (count (filter taken (keys (:ours r))))})))]
                           (-> acc (update :members conj {:branch b :r r :refusal refusal})
                               (cond-> (nil? refusal) (update :taken into (keys (:ours r)))))))
                       {:taken #{} :members []} branches)
        ok (remove :refusal (:members judged))
        changed (into {} (for [{:keys [r]} ok [k [_ ov]] (:ours r)] [k ov]))]
    (if (empty? changed)
      [st {:results (mapv #(or (:refusal %) {:merged? true :tx (:max-tx main) :datoms 0 :keys 0}) (:members judged))}]
      ;; git: each member's head becomes a parent, so its merge base moves to it
      (let [rep (d/merge-db conn (set (map :branch ok)) (pm/apply-tx main changed))
            t (:max-tx (:db-after rep))
            ops (pdb/report->tx rep)]
        [(main-commit st o t ops (:db-after rep))
         {:tx t :datoms (count ops)
          :results (mapv #(or (:refusal %) {:merged? true :tx t :keys (count (:ours (:r %)))}) (:members judged))}]))))

(defn- merge-branch
  "Merge the branch into main (git: only a branch that contains main's head; lane M1): a
  train of one. Answers [st result]."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/branch :seon.merge/data-attrs] [:tuple :seon.writer/state :map]]}
  [st o branch data-attrs]
  (let [[st r] (merge-train st o [branch] data-attrs)
        r1 (first (:results r))]
    [st (cond-> r1 (and (:merged? r1) (:datoms r)) (assoc :datoms (:datoms r)))]))

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
                (do (answer o (:dest m) (refused (step/error-msg rep) {})) st)
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
        main (pdb/conn-main conn)
        ;; every branch named in a request is root's name for it, under root's main
        m (cond-> m
            (:branch m) (update :branch #(pdb/on-main main %))
            (:from m) (update :from #(pdb/on-main main %))
            (:branches m) (update :branches #(mapv (fn [b] (pdb/on-main main b)) %)))
        exists? (fn [b] (pdb/branch? conn b))
        reply (fn [st r] (answer o dest r) st)]
    (case op
      ;; an agent process committed its branch: the store is dirty until the next flush
      :agent-commit (assoc st :dirty true)
      ;; the agent's branch (new: a pointer to main's head), then its fork: the agent opens
      ;; the branch itself and owns its writes (run-ahead, committed by the agent)
      :spawn (let [{:keys [agent branch]} m
                   resumed? (exists? branch)]
               (when-not resumed? (d/branch! conn main branch))
               (step/out! o :to-fork-server {:op :fork :agent agent :branch branch :ns (:ns m) :fd (:fd m)
                                              :cfg (:cfg st) :lease (:lease m) :resumed resumed?})
               (reply st {:ok true :t (:max-tx @conn) :resumed resumed?}))
      :clone-branch (do (d/branch! conn (:from m) (:branch m))
                        (reply st {:ok true :t (:max-tx (pdb/head conn (:branch m))) :cfg (:cfg st) :lease (:lease m)}))
      :merge-train (let [[st r] (merge-train st o (vec (:branches m)) (:data-attrs m #{}))] (reply st r))
      :merge (if (exists? (:branch m))
               (let [[st r] (merge-branch st o (:branch m) (:data-attrs m #{}))] (reply st r))
               (reply st (refused (str "no branch " (:branch m)) {:merged? false})))
      ;; one derivation (seon.merge/three-way) for the dashboard, the done rule and merges
      :branch-diff (reply st (if (exists? (:branch m))
                               (let [[head r] (branch-merge st (:branch m) (:data-attrs m #{}))]
                                 {:ok true :ours (vec (sort-by str (keys (:ours r)))) :theirs (count (:theirs r))
                                  :conflicts (count (:conflicts r))
                                  :unmerged (mapv :key (pm/unmerged head (pm/program head #{})))
                                  :vars (into (sorted-set) (keep (fn [[k s]] (when (= :var k) s))) (keys (:ours r)))})
                               (refused (str "no branch " (:branch m)) {})))
      ;; root's read of a branch (or main): its committed value from the store; :base-t is
      ;; main's t at the branch's merge base (what of main it holds)
      :branch-db (reply st (cond
                             (= main (:branch m)) {:ok true :db @conn}
                             (exists? (:branch m)) {:ok true :db (pdb/head conn (:branch m))
                                                    :base-t (:max-tx (pdb/branch-base conn main (:branch m)))}
                             :else (refused (str "no branch " (:branch m)) {})))
      :branch-info (reply st (if (exists? (:branch m))
                               (let [h (pdb/head conn (:branch m))] {:ok true :datoms (count (d/datoms h :eavt)) :t (:max-tx h)})
                               (refused (str "no branch " (:branch m)) {})))
      (reply st (refused (str "unknown op " op) {})))))

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
      ;; with the store's map use: a full map (MDB_MAP_FULL) shows itself against its size
      (answer o (:dest m) (refused (step/error-msg e) (cond-> {:merged? false} (pdb/map-use (:cfg st)) (assoc :store (pdb/map-use (:cfg st))))))
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
       ;; what changed, to root's view: a batch, or a flush (its count is shown, never pinged)
       (when (or (seq reqs) flush-now?)
         (step/out! o :to-api {:op :writer :tx-count (:tx-count st) :batch-ms (lib/ms-since t0) :n-tx n-tx :commits commits
                               :flushes (:flushes st 0)}))
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
