(ns seon.writer
  "The writer: the only d/transact, d/branch! and merge caller, with group commit (drain
  the in-port, one Datahike commit per branch, one flush). From proto/flow f2-final
  proto.control's writer (session F2; writer design A, notes/archive/option3-F1-flow.md section 4)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.lib :as lib]
            [seon.program :as program] [seon.merge :as pm] [seon.union :as union] [seon.problems :as problems] [seon.source :as source] [seon.contract :as contract] [seon.sock :as sock] [seon.step :as step] [seon.config :as cfg]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [clojure.string :as str]))

(defn- union-caught-up
  "st with its union (seon.union), once one was asked for, holding branch b's datoms up to
  head: from the commit of b it holds, or from b's merge base with main for a branch it has
  not seen. O(Δ) per commit: the writer sees every main commit and every agent's commit
  notice, so U stays current with no poll. st as it is before anyone asked."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.db/branch :seon.host/db] :seon.writer/state]}
  [st b head]
  (if-let [un (:union st)]
    (let [conn (:conn st) main (pdb/conn-main conn)
          from (if-let [cid (get-in un [:applied b])] (d/commit-as-db conn cid) (pdb/branch-base conn main b))]
      (assoc st :union (union/catch-up un b from head)))
    st))

(defn- union-built
  "st with its union built (seon.union): main's history, and each branch under main from its
  merge base; as it is when built already."
  {:malli/schema [:=> [:cat :seon.writer/state] :seon.writer/state]}
  [st]
  (if (:union st)
    st
    (let [conn (:conn st) main (pdb/conn-main conn)]
      (assoc st :union (reduce (fn [un b] (union/catch-up un b (pdb/branch-base conn main b) (pdb/head conn b)))
                               (union/of-history (union/empty-union @conn) main @conn)
                               (map #(pdb/on-main main %) (pdb/of-main main (set (d/branches conn)))))))))

(defn- main-commit
  "Main moved to t: the reconciler gets the report, and root loads the program change (a
  restarted fork server forks from root). The fork server hears of it per stored commit
  (stored!). Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/t :seon.db/resolved-tx :map :seon.host/db] :seon.writer/state]}
  [st o t ops tx-meta db]
  (step/out! o :main {:t t :ops ops :db db :ns (profile/now-ns)})
  (when-let [errs (seq (program/load-changes! db ops))]
    (step/out! o :events (step/event {:type :load-errors :errors (vec errs)})))
  (union-caught-up st (pdb/conn-main (:conn st)) db))

(defn- stored!
  "Main's stored commit, made of ops (its transactions' resolved datoms, in order): the fork
  server's replica reads that commit from the store (d/commit-as-db; FIFO with fork
  requests) and loads the program they change, never replaying the datoms. A group commit
  is one stored commit, so one notice covers its batch. The env is MDB_NOSYNC: a committed
  write is readable by another process before the flusher's sync."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/resolved-tx] :nil]}
  [st o ops]
  (let [db @(:conn st)]
    (step/out! o :to-fork-server {:op :tx :t (:max-tx db) :commit (d/commit-id db) :ns (profile/now-ns) :tx-data ops})
    nil))

(defn- branch-merge
  "seon.merge/three-way of branch b against main, from their merge base over Datahike's
  commit graph (the branch point, or the main the branch last merged), the branch read
  from the store (its agent commits it). data-attrs: the data that merges beside the
  program (session M1)."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.db/branch :seon.merge/data-attrs] [:tuple :seon.host/db :seon.merge/result :seon.host/db]]}
  [{:keys [conn]} b data-attrs]
  (let [head (pdb/head conn b) base (pdb/branch-base conn (pdb/conn-main conn) b)]
    [head (pm/delta-three-way base head @conn data-attrs) base]))

(defn- refused
  "The writer refusing a request, as the one error map (:seon.writer/refused); members
  describe the refused answer."
  {:malli/schema [:=> [:cat :string :map] :seon.writer/refused]}
  [why members] (contract/error :seon.writer/refused 'seon.writer/writer-proc why members))

(defn- merge-refused
  "Why branch head (its three-way r against main) cannot merge into main now (session M1), or
  nil: main's program (and declared data) changed since the branch's merge base
  (:seon.merge/behind: a merge task merges main first); the branch has unmerged entries
  (:seon.merge/unmerged); its program calls intern, alter-var-root, eval or load-string,
  or defines inside a function body (:seon.merge/mutation, seon.merge/mutations); it
  carries an uncontracted function (:seon.merge/uncontracted: contracts decide what is
  admitted)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.merge/result] [:maybe :map]]}
  [main head r]
  (let [unmerged (pm/unmerged head)
        uncontracted (pm/uncontracted (:ours r))]
    (cond
      (seq (:theirs r))
      (contract/error :seon.merge/behind 'seon.writer/merge-branch
                      (str "main changed " (count (:theirs r)) " key(s) since the branch's merge base: merge main into the branch first")
                      {:tx (:max-tx main) :behind (count (:theirs r))
                       :seon.error/fix "A merge task merges main into the branch (my.branch/merge-main!), resolves any conflict, and its proofs run on the combined program."})
      (seq unmerged)
      (contract/error :seon.merge/unmerged 'seon.writer/merge-branch
                      (str "the branch has " (count unmerged) " unmerged entr(ies)")
                      {:tx (:max-tx main) :unmerged (mapv :key unmerged)})
      (seq (pm/mutations main (:ours r)))
      (let [ms (pm/mutations main (:ours r))]
        (contract/error :seon.merge/mutation 'seon.writer/merge-branch
                        (str "the branch's program " (str/join "; " (for [m ms] (str (sedn/encode (:defines m))
                                                                                    (if (:call m) (str " calls " (:call m)) (str " defines " (:inline-def m) " inside a function body"))))))
                        {:tx (:max-tx main) :mutations ms
                         :seon.error/fix "Every definition happens once, where it is defined: vary behaviour through arguments or the environment, and define at the top level, not inside a function."}))
      (seq uncontracted)
      (contract/error :seon.merge/uncontracted 'seon.writer/merge-branch
                      (str "the branch carries " (count uncontracted) " function(s) without a contract")
                      {:tx (:max-tx main) :uncontracted uncontracted
                       :seon.error/fix "Declare each function's contract ({:malli/schema [:=> [:cat ...] ...]}) and merge again."}))))

(defn- octopus-merge
  "Merge branches into main in ONE commit (an octopus merge, session R3, as git's, every
  member a parent): each member that holds main's program head and is not refused merges, in
  order; a member whose changes meet an earlier member's is behind (it merges main first).
  Members are judged against the same main, so the commit is each one's clean merge.
  Answers [st {:results [answer per branch, in order]}]."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile [:vector :seon.db/branch] :seon.merge/data-attrs] [:tuple :seon.writer/state :map]]}
  [st o branches data-attrs]
  (let [conn (:conn st)
        main @conn
        judged (reduce (fn [{:keys [taken] :as acc} b]
                         (let [have? (pdb/branch? conn b)
                               [head r base] (when have? (branch-merge st b data-attrs))
                               why-not (if have? (merge-refused main head r) (refused (str "no branch " b) {}))
                               why-not (or why-not
                                           (when (some taken (keys (:ours r)))
                                             (contract/error :seon.merge/behind 'seon.writer/octopus-merge
                                                             "another member of the octopus merge changes the same definitions: merge main into the branch first"
                                                             {:tx (:max-tx main) :behind (count (filter taken (keys (:ours r))))})))]
                           (-> acc (update :members conj {:branch b :r r :head head :base base :refused why-not})
                               (cond-> (nil? why-not) (update :taken into (keys (:ours r)))))))
                       {:taken #{} :members []} branches)
        ok (remove :refused (:members judged))
        changed (into {} (for [{:keys [r]} ok [k [_ ov]] (:ours r)] [k ov]))]
    (if (empty? changed)
      [st {:results (mapv #(or (:refused %) {:merged? true :tx (:max-tx main) :datoms 0 :keys 0}) (:members judged))}]
      ;; git: each member's head becomes a parent, so its merge base moves to it; each
      ;; member's own datoms for the keys it changed, as they are (seon.merge/merge-tx)
      (let [rep (d/merge-db conn (set (map :branch ok))
                            (vec (mapcat (fn [{:keys [r head base]}] (pm/merge-tx main base head (keys (:ours r)) data-attrs)) ok))
                            (pdb/tx-meta (pdb/conn-main conn)))
            t (:max-tx (:db-after rep))
            ops (pdb/report->tx rep)
            _ (stored! st o ops)]
        [(main-commit st o t ops (pdb/report->tx-meta rep) (:db-after rep))
         {:tx t :datoms (count ops)
          ;; :files, the source files (namespaces' and schema resources') its changes touch: what a write-back rewrites
          :results (mapv #(or (:refused %) {:merged? true :tx t :keys (count (:ours (:r %))) :files (source/changed-files (:db-after rep) (:ours (:r %)))}) (:members judged))}]))))

(defn- merge-branch
  "Merge the branch into main (git: only a branch that contains main's head; session M1): a
  octopus merge of one. Answers [st result]."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/branch :seon.merge/data-attrs] [:tuple :seon.writer/state :map]]}
  [st o branch data-attrs]
  (let [[st r] (octopus-merge st o [branch] data-attrs)
        r1 (first (:results r))]
    [st (cond-> r1 (and (:merged? r1) (:datoms r)) (assoc :datoms (:datoms r)))]))

(defn- answer
  "The answer r for dest, as data on :to-api."
  {:malli/schema [:=> [:cat :seon.host/volatile [:maybe :map] :any] :nil]}
  [o dest r] (when dest (step/out! o :to-api {:dest dest :r r})))

(defn- commit-batch
  "Group commit of root's writes to main: every queued transaction, one Datahike commit
  (datahike.writer/transact-batch!), in arrival order. A commit the store refuses (its codec
  has no encoding for a value; main stays as it was, Datahike moves it only after the write)
  is retried one transaction at a time, so only the one that carried the value is answered
  refused. Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile] :seon.writer/state]}
  [st o]
  (if-let [ms (seq (:pend st))]
    (let [reps (try (dw/transact-batch! (:conn st) (mapv (fn [m] {:tx-data (:tx-data m) :tx-meta (pdb/tx-meta (pdb/conn-main (:conn st)))}) ms))
                    (catch Throwable e e))]
      (cond
        (sequential? reps)
        (let [ok (filter (comp map? second) (map vector ms reps))]
          (when (seq ok) (stored! st o (vec (mapcat (comp pdb/report->tx second) ok))))
          (reduce (fn [st [m rep]]
                    (if-not (map? rep)
                      (do (answer o (:dest m) (refused (step/error-msg rep) {})) st)
                      (let [t (:max-tx (:db-after rep)) ops (pdb/report->tx rep)]
                        (answer o (:dest m) {:ok true :t t :datoms (count ops)})
                        (main-commit st o t ops (pdb/report->tx-meta rep) (:db-after rep)))))
                  (-> st (dissoc :pend) (update :commits inc))
                  (map vector ms reps)))
        (next ms) (reduce (fn [st m] (commit-batch (assoc st :pend [m]) o)) (dissoc st :pend) ms)
        :else (do (answer o (:dest (first ms)) (refused (step/error-msg reps) {})) (dissoc st :pend))))
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
      ;; answered once the store is clean (at the end of this step, or when the flush that covers
      ;; its commits comes back): the event that ends the last pending sync (seon.check/idle)
      :clean (update st :clean-waiters (fnil conj []) dest)
      ;; an agent process committed its branch: a commit the next flush covers
      :agent-commit (-> st (assoc :dirty true) (update :commits inc)
                        (cond-> (:union st) (union-caught-up (:branch m) (pdb/head conn (:branch m)))))
      ;; the union of every branch's history (seon.union), built when first asked and kept
      ;; current from then on by each commit this writer sees: query it with d/q over the
      ;; answer's :db, a (d/history U)
      :union (let [st (union-built st)] (reply st {:ok true :db (d/history (:db (:union st))) :branches (count (:applied (:union st)))}))
      ;; the agent's branch (new: a pointer to main's head), then its fork: the agent opens
      ;; the branch itself and owns its writes (run-ahead, committed by the agent), and holds
      ;; the task and run it works from its first form
      :spawn (let [{:keys [agent branch]} m
                   resumed? (exists? branch)]
               (when-not resumed? (d/branch! conn main branch))
               ;; main's commit and t, which this branch starts at and the image it forks from holds
               (step/out! o :to-fork-server {:op :fork :agent agent :branch branch :task (:task m) :run (:run m) :ns (:ns m) :fd (:fd m)
                                              :cfg (:cfg st) :resumed resumed?
                                              :commit (d/commit-id @conn) :t (:max-tx @conn)})
               (reply st {:ok true :t (:max-tx @conn) :resumed resumed?}))
      :clone-branch (do (d/branch! conn (:from m) (:branch m))
                        (reply st {:ok true :t (:max-tx (pdb/head conn (:branch m))) :cfg (:cfg st)}))
      :octopus-merge (let [[st r] (octopus-merge st o (vec (:branches m)) (:data-attrs m #{}))] (reply st r))
      :merge (if (exists? (:branch m))
               (let [[st r] (merge-branch st o (:branch m) (:data-attrs m #{}))] (reply st r))
               (reply st (refused (str "no branch " (:branch m)) {})))
      ;; one derivation (seon.merge/three-way) for the dashboard, the done rule and merges
      :branch-diff (reply st (if (exists? (:branch m))
                               (let [[head r base] (branch-merge st (:branch m) (:data-attrs m #{}))]
                                 {:ok true :db head :base base :base-t (:max-tx base)
                                  :ours (vec (sort-by str (keys (:ours r)))) :theirs (count (:theirs r))
                                  :conflicts (count (:conflicts r))
                                  :unmerged (mapv :key (pm/unmerged head)) :uncontracted (pm/uncontracted (:ours r))
                                  :caused (vec (problems/caused base head))
                                  :vars (into (sorted-set) (keep (fn [[k s]] (when (= :var k) s))) (keys (:ours r)))})
                               (refused (str "no branch " (:branch m)) {})))
      ;; root's read of a branch (or main): its committed value from the store; :base is the
      ;; db at the branch's merge base, :base-t main's t there (what of main it holds). A
      ;; branch named as a task or run names it (:task-calc-add) is read as the store
      ;; names it under this main (seon.db/on-main)
      :branch-db (let [b (pdb/on-main main (:branch m))]
                   (reply st (cond
                               (= main b) {:ok true :db @conn}
                               (exists? b) (let [base (pdb/branch-base conn main b)]
                                             {:ok true :db (pdb/head conn b) :base base :base-t (:max-tx base)})
                               :else (refused (str "no branch " b) {}))))
      ;; root's collection of the store (seon.db/collect!), asked at quiescence: swept when a
      ;; branch the last one saw is gone, and recorded on main through the batch
      :collect (let [r (pdb/collect! conn (cfg/value :seon.config.store/sole-writer?))]
                 (reply (if (:skipped r) st (commit-batch (update st :pend (fnil conj []) {:tx-data [{:seon.root/id :root :seon.root/collected r}]}) o))
                        {:ok true :collected r}))
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
      (answer o (:dest m) (refused (step/error-msg e) (cond-> {} (pdb/map-use (:cfg st)) (assoc :store (pdb/map-use (:cfg st))))))
      (dissoc st :pend))))

(defn- drain {:malli/schema [:=> [:cat :seon.host/chan :seon.writer/request] [:vector :seon.writer/request]]}
  [ch m]
  (loop [acc [m] k 1]
    (if (< k (cfg/value :seon.config.root/writer-batch))
      (if-let [y (a/poll! ch)] (recur (conj acc y) (inc k)) acc)
      acc)))

(defn writer-proc
  "The writer: root's own writes to main (group commit), d/branch!, and every merge into
  main. Each agent process writes its own branch (run-ahead, seon.agent) and tells it
  :agent-commit, so the one flush covers it. In :in (every write request, from the :api
  and the agent procs). Out :to-fork-server (main's
  commits and forks, FIFO), :to-api (answers {:dest :r}, after the batch's flush, and
  {:op :writer :tx-count :p50-ms :commits}), :main (main's commits), :events.
  A closed :in is the end of the flow: everything taken is committed and flushed, then
  {:op :drained} goes out. Continuous group commit (owner, 2026-10-21: no window): answers
  leave when their batch is committed in memory; a dirty store with no notice out tells the
  flusher {:op :dirty :commits n}, and its {:op :flushed :commits n} makes the store clean
  when n is still the commit count, else the flusher is told again. A :flush? request (an
  outside effect follows) is synced before its answer; a :clean request is answered once the
  store is clean, its flushes' notices before it."
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
           t0 (profile/now-ns)
           ;; the flusher's answers: the commit count its sync covered
           covered (some->> (seq (keep #(when (= :flushed (:op %)) (:commits %)) ms)) (apply max))
           reqs (remove #(= :flushed (:op %)) ms)
           st (commit-batch (reduce #(writer-one %1 o %2) st reqs) o)
           commits (- (:commits st) c0)
           st (cond-> st (pos? commits) (assoc :dirty true))  ; root's commits, or an agent's (:agent-commit)
           f0 (:flushes st 0)
           ;; now for a write an outside effect follows (:flush?), inline before its answer
           flush-now? (and (:durable? st) (:dirty st) (some :flush? reqs))
           _ (when flush-now? (pdb/sync! (:cfg st)))
           st (cond-> st
                flush-now? (-> (assoc :dirty false) (update :flushes (fnil inc 0)))
                covered (assoc :notified false)
                (and covered (:dirty st) (= covered (:commits st))) (-> (assoc :dirty false) (update :flushes (fnil inc 0))))
           ;; still dirty and no notice out: the flusher is told what is committed so far
           st (if (and (:durable? st) (:dirty st) (not (:notified st)))
                (do (step/out! o :to-flusher {:op :dirty :commits (:commits st) :since (profile/now-ns)})
                    (assoc st :notified true))
                st)
           n-tx (count (filter #(= :tx (:op %)) reqs))
           st (-> st (update :tx-count + n-tx) (update :batches inc))]
       ;; what changed, to root's view: a batch, or a flush (its count is shown, never pinged)
       (when (or (seq reqs) (not= f0 (:flushes st 0)))
         (step/out! o :to-api {:op :writer :tx-count (:tx-count st) :batch-ms (profile/ms-since t0) :n-tx n-tx :commits commits
                               :flushes (:flushes st 0)}))
       ;; a clean store answers who asked (after the flush's notice above, so it reaches the api
       ;; first); a store with no durability has nothing to sync
       (if (and (seq (:clean-waiters st)) (not (and (:durable? st) (:dirty st))))
         (do (doseq [d (:clean-waiters st)] (answer o d {:ok true :commits (:commits st) :flushes (:flushes st 0) :durable? (boolean (:durable? st))}))
             [(dissoc st :clean-waiters) @o])
         [st @o])))))

(defn flusher-proc
  "The group flush: parked until the writer says the store is dirty ({:op :dirty :commits
  n}), it syncs at once (seon.db/sync! on the store config; commits during the sync share
  the next one) and answers the writer {:op :flushed :commits n}, the commits it covered.
  An idle system never syncs."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:to-writer "the writer's in-port" :cfg "the store config"}
       :ins {:in "dirty notices from the writer"} :outs {}
       ;; its own thread: the env sync is a blocking foreign call
       :workload :mixed
       :ping-map-fn (fn [st] {:flushes (:flushes st 0)})})
  ([args] (assoc args :flushes 0 ::flow/out-ports {:to-writer (:to-writer args)}))
  ([st _] st)
  ([st _ m]
   (if (nil? m)
     [st nil]
     (do (pdb/sync! (:cfg st))
         [(update st :flushes inc) {:to-writer [{:op :flushed :commits (:commits m)}]}]))))
