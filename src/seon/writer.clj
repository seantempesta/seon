(ns seon.writer
  "The writer: the only d/transact, d/branch! and merge caller, with group commit (drain
  the in-port, one Datahike commit per branch, one flush). From proto/flow f2-final
  proto.control's writer (session F2; writer design A, the session design papers in git history)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.lib :as lib]
            [seon.program :as program] [seon.merge :as pm] [seon.union :as union] [seon.problems :as problems] [seon.source :as source] [seon.doc :as doc] [seon.contract :as contract] [seon.sock :as sock] [seon.step :as step] [seon.config :as cfg] [seon.task :as task]
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
  "Main moved from prev to t, by one stored commit: its readers (the reconciler, the test
  service, the vectors and render procs) get the notice {:t :prev :ops :commit :ns}, the commit
  to make the db from when they take it (d/commit-as-db), never a db value that a queue or a
  slow reader would keep alive (docs/machine.md#what-a-process-owns-in-memory); root loads the program change (a
  restarted fork server forks from root). The fork server hears of it per stored commit
  (stored!). Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.db/t :seon.db/t :seon.db/resolved-tx :seon.host/db] :seon.writer/state]}
  [st o prev t ops db]
  (step/out! o :main {:t t :prev prev :ops ops :commit (d/commit-id @(:conn st)) :ns (profile/now-ns)})
  (when-let [errs (seq (program/load-changes! db ops {:tests? false}))]
    (step/out! o :events (step/event {:type :load-errors :errors (vec errs)})))
  ;; a config entry moved: main's root entity says so with the next commit (docs/config.md#facts-on-main)
  (let [st (union-caught-up st (pdb/conn-main (:conn st)) db)]
    (if-let [{c :config srcs :sources} (cfg/moved db ops)] (update st :pend (fnil conj []) {:tx-data (task/config-tx c srcs)}) st)))

(defn- mark-tx
  "The tx-data that moves main's content mark (:seon.system/content-t) past every t db, main before the commit, holds, so a
  commit that moves the content never shares a mark with one before it (docs/storage.md#mains-content-mark)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.db/tx-data]}
  [db] [{:seon.system/id :root :seon.system/content-t (inc (:max-tx db))}])

(defn- mark!
  "The report of main's content mark moved, applied on the connection after ops (resolved datoms applied and not yet
  committed, from db, main before them) when they move main's content (seon.merge/moves-content?), else nil: the commit
  that stores them carries it (docs/storage.md#mains-content-mark). A mark the connection refuses throws, so no commit is
  made without it."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.db/resolved-tx :seon.host/db] [:maybe :map]]}
  [st ops db]
  (when (pm/moves-content? ops)
    (let [r (dw/apply! (:conn st) {:tx-data (mark-tx db) :tx-meta (pdb/tx-meta (pdb/conn-main (:conn st)))})]
      (if (map? r) r (throw r)))))

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
  "seon.merge/delta-three-way of branch b against main, from their merge base, as [head result base]. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.db/branch] [:tuple :seon.host/db :seon.merge/result :seon.host/db]]}
  [{:keys [conn]} b]
  (let [head (pdb/head conn b) base (pdb/branch-base conn (pdb/conn-main conn) b)]
    [head (pm/delta-three-way base head @conn) base]))

(defn- written
  "What a merge of changes ours writes back, read on db (main with them merged): :files, the source
  files (namespaces' and schema resources') they touch, and :unfiled, the changed forms of
  namespaces with no file, which no write-back can write. The octopus merge's write-back and the
  merge gate's candidate are both this (docs/merging.md#a-merge-runs-what-the-change-reaches)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/changes] [:map [:files [:vector :string]] [:unfiled :map]]]}
  [db ours] {:files (source/changed-files db ours) :unfiled (source/unfiled-forms db ours)})

(defn- refused
  "The writer refusing a request, as the one error map (:seon.writer/refused); members
  describe the refused answer."
  {:malli/schema [:=> [:cat :string :map] :seon.writer/refused]}
  [why members] (contract/error :seon.writer/refused #'seon.writer/refused why members))

(defn- unanalyzable
  "The refusal when a merge candidate's Clojure files, text ({path text}), don't analyze on db, the merged value, or nil: the import's own analysis, in this process, at the cost of the files that changed. See docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]]] [:maybe :seon.writer/refused]]}
  [db text]
  ;; docs/failure-points.md: a form of the candidate that can't be analyzed is this answer, and the merge doesn't happen
  (try (source/refresh-tx db (into {} (filter #(clojure.string/ends-with? (key %) ".clj")) text)) nil
       (catch Throwable e (refused (str "the merged tree doesn't analyze: " (ex-message e)) {}))))

(defn- merge-refused
  "Why branch head cannot merge into main now by its program (behind, a mutation, an uncontracted function), or nil. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.merge/result] [:maybe :map]]}
  [main head r]
  (let [uncontracted (pm/uncontracted (:ours r))]
    (cond
      (seq (:theirs r))
      (contract/error :seon.merge/behind #'seon.writer/merge-refused
                      (str "main changed " (count (:theirs r)) " key(s) since the branch's merge base: merge main into the branch first")
                      {:tx (:max-tx main) :behind (count (:theirs r))
                       :seon.error/fix "A merge task merges main into the branch (my.branch/merge-main!), resolves any conflict, and its proofs run on the combined program."})
      (seq (pm/mutations main (:ours r)))
      (let [ms (pm/mutations main (:ours r))]
        (contract/error :seon.merge/mutation #'seon.writer/merge-refused
                        (str "the branch's program " (str/join "; " (for [m ms] (str (sedn/encode (:defines m))
                                                                                    (if (:call m) (str " calls " (:call m)) (str " defines " (:inline-def m) " inside a function body"))))))
                        {:tx (:max-tx main) :mutations ms
                         :seon.error/fix "Every definition happens once, where it is defined: vary behaviour through arguments or the environment, and define at the top level, not inside a function."}))
      (seq uncontracted)
      (contract/error :seon.merge/uncontracted #'seon.writer/merge-refused
                      (str "the branch carries " (count uncontracted) " function(s) without a contract")
                      {:tx (:max-tx main) :uncontracted uncontracted
                       :seon.error/fix "Declare each function's contract ({:malli/schema [:=> [:cat ...] ...]}) and merge again."}))))

(defn- dangling-refused
  "The refusal of a merge whose program, merged into main, leaves a reference naming nothing (seon.merge/dangling: a removed
  definition another form calls), or nil. behind? when an earlier member of the octopus merge was taken: merged main first, the
  branch's own candidate says whose it is."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :boolean] [:maybe :map]]}
  [main merged behind?]
  (when-let [ds (seq (pm/dangling main merged))]
    (let [said (str/join "; " (for [{:keys [ref form]} ds] (str form " calls " ref ", which the merge removes")))]
      (if behind?
        (contract/error :seon.merge/behind #'seon.writer/dangling-refused
                        (str "with an earlier member of the octopus merge, " said ": merge main into the branch first") {:tx (:max-tx main) :behind (count ds)})
        (contract/error :seon.merge/dangling #'seon.writer/dangling-refused said {:tx (:max-tx main) :dangling (vec ds)})))))

(defn- member-applied!
  "Branch head merged onto db as the merge writes it, pending on main's connection: {:db :reps} or {:refused :reps}. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db :seon.host/db :seon.host/db :seon.host/db :boolean] [:map [:reps [:vector :map]]]]}
  [conn main db base head behind?]
  (let [p (pm/plan base db head)]
    (if-let [why (pm/refusal p)]
      {:refused why :reps []}
      (let [a (pm/apply-groups! conn db (pdb/tx-meta (pdb/conn-main conn)) (:groups p))]
        (if-let [{:keys [tx exception]} (:refused a)]
          (assoc a :refused (pm/refusal tx exception))
          (if-let [why (dangling-refused main (:db a) behind?)] (assoc a :refused why) a))))))

(defn- judged-members
  "Each of branches judged and applied in order as a member of an octopus merge, or {:retry b :refused why}. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.writer/state [:vector :seon.db/branch]] [:or [:map [:members [:vector :map]]] [:map [:retry :seon.db/branch] [:refused :map]]]]}
  [st branches]
  (let [conn (:conn st)
        main @conn]
    (reduce (fn [{:keys [taken db] :as acc} b]
              (let [have? (pdb/branch? conn b)
                    [head r base] (when have? (branch-merge st b))
                    why-not (if have? (merge-refused main head r) (refused (str "no branch " b) {}))
                    why-not (or why-not
                                (when (some taken (keys (:ours r)))
                                  (contract/error :seon.merge/behind #'seon.writer/judged-members
                                                  "another member of the octopus merge changes the same definitions: merge main into the branch first"
                                                  {:tx (:max-tx main) :behind (count (filter taken (keys (:ours r))))})))
                    a (when-not why-not (member-applied! conn main db base head (boolean (seq taken))))
                    why-not (or why-not (:refused a))]
                (if (and why-not (seq (:reps a)))
                  (do (dw/abandon-pending! conn) (reduced {:retry b :refused why-not}))
                  (-> acc (update :members conj {:branch b :r r :head head :base base :reps (:reps a []) :refused why-not})
                      (cond-> (nil? why-not) (-> (update :taken into (keys (:ours r))) (assoc :db (:db a))))))))
            {:taken #{} :db main :members []} branches)))

(defn- committed!
  "The applied members committed with one transaction of main's own a member, the last holding the mark and the parents. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.host/db [:sequential :map] [:vector :map]] [:tuple :seon.writer/state :map]]}
  [st o main ok members]
  ;; git: each member's head becomes a parent, so its merge base moves to it; each member's parent is the commit its
  ;; merge was computed from, not its head when this commits
  (let [conn (:conn st)
        own (vec (for [[i m] (map-indexed vector ok) :let [last? (= i (dec (count ok)))]]
                   (let [meta {:tx-meta (pdb/tx-meta (pdb/conn-main conn) {:seon.tx/merged (:branch m)})}
                         rep (if last?
                               (dw/apply! conn (assoc meta :parents (set (map (comp d/commit-id :head) ok)) :tx-data (mark-tx main)) {:op 'merge!})
                               (dw/apply! conn (assoc meta :tx-data [])))]
                     (if (map? rep) rep (throw rep)))))
        db (:db (dw/commit-pending! conn))
        t (:max-tx db)
        ops (into (vec (mapcat pdb/report->tx (mapcat :reps ok))) (mapcat pdb/report->tx own))
        _ (stored! st o ops)]
    [(main-commit st o (:max-tx main) t ops db)
     {:tx t :datoms (count ops)
      ;; :files, the source files (namespaces' and schema resources') its changes touch: what a write-back rewrites;
      ;; :unfiled, the changed forms of namespaces with no file, which no write-back can write
      :results (mapv #(or (:refused %) (merge {:merged? true :tx t :keys (count (:ours (:r %)))} (written db (:ours (:r %))))) members)}]))

(defn- pending-refused
  "The refusal of a merge op that finds transactions pending on main's connection, or nil. See docs/storage.md#durability-is-continuous-group-commit."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword] [:maybe :seon.writer/pending]]}
  [conn op]
  (when (dw/pending? conn)
    (contract/error :seon.writer/pending #'seon.writer/pending-refused
                    (str "transactions are pending on main's connection when " (name op) " starts: nothing is written or abandoned")
                    {:op op})))

(defn- octopus-merge
  "Merge branches into main in one commit, every member a parent. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile [:vector :seon.db/branch]] [:tuple :seon.writer/state :map]]}
  [st o branches]
  (let [conn (:conn st)
        main @conn]
    (if-let [e (pending-refused conn :octopus-merge)]
      [st e]
    (try
      (let [judged (judged-members st branches)
            ok (remove :refused (:members judged))]
        (cond
          ;; nothing was committed: the others merge without the refused member, its refusal in its place
          (:retry judged)
          (let [i (first (keep-indexed #(when (= (:retry judged) %2) %1) branches))
                [st r] (octopus-merge st o (into (subvec branches 0 i) (subvec branches (inc i))))]
            [st (update r :results #(-> (subvec % 0 i) (conj (:refused judged)) (into (subvec % i))))])
          (not-any? (comp seq :reps) ok)
          [st {:results (mapv #(or (:refused %) {:merged? true :tx (:max-tx main) :datoms 0 :keys 0}) (:members judged))}]
          :else (committed! st o main ok (:members judged))))
      (finally (when (dw/pending? conn) (dw/abandon-pending! conn)))))))

(defn- answer
  "The answer r for dest, as data on :to-api."
  {:malli/schema [:=> [:cat :seon.host/volatile [:maybe :map] :any] :nil]}
  [o dest r] (when dest (step/out! o :to-api {:dest dest :r r})))

(defn- commit-batch
  "Group commit of root's writes to main: every queued transaction applied in arrival order (datahike.writer/apply!), main's
  content mark after them when they moved it (`mark!`), then one Datahike commit (datahike.writer/commit-pending!). A
  transaction that fails its apply is answered refused and the others commit. A commit the store refuses (its codec has no
  encoding for a value; main stays as it was, Datahike moves it only after the write) is abandoned and retried one
  transaction at a time, so only the one that carried the value is answered refused. Answers st."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile] :seon.writer/state]}
  [st o]
  (if-let [ms (seq (:pend st))]
    (let [conn (:conn st)
          before @conn
          reps (mapv (fn [m] (dw/apply! conn {:tx-data (:tx-data m) :tx-meta (pdb/tx-meta (pdb/conn-main conn))})) ms)
          ok (filter (comp map? second) (map vector ms reps))
          ops (vec (mapcat (comp pdb/report->tx second) ok))
          c (when (seq ok)
              (try (let [mk (mark! st ops before)] (assoc (dw/commit-pending! conn) :mark mk))
                   (catch Throwable e e)))
          refuse (fn [m rep] (answer o (:dest m) (or (when (:seon.error/type (ex-data rep)) (ex-data rep)) (refused (step/error-msg rep) {}))))]
      (cond
        (empty? ok)
        (do (doseq [[m rep] (map vector ms reps)] (refuse m rep)) (dissoc st :pend))
        (map? c)
        ;; one stored commit, one notice: the last report's t, every report's ops in order, the mark's after them
        (let [ops (into ops (some-> (:mark c) pdb/report->tx))
              t (:max-tx (:db c))]
          (stored! st o ops)
          (doseq [[m rep] (map vector ms reps)]
            (if (map? rep) (answer o (:dest m) {:ok true :t (:max-tx (:db-after rep)) :datoms (count (pdb/report->tx rep))}) (refuse m rep)))
          ;; what main-commit queued (a moved config's facts) is this batch's next commit
          (commit-batch (main-commit (-> st (dissoc :pend) (update :commits inc)) o (:max-tx before) t ops (:db c)) o))
        :else
        (do (dw/abandon-pending! conn)
            (if (next ms)
              (reduce (fn [st m] (commit-batch (assoc st :pend [m]) o)) (dissoc st :pend) ms)
              (do (refuse (first ms) c) (dissoc st :pend))))))
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
            ;; :from a branch's name, or a commit's id (a branch from a point: seon.system/back!), which no main qualifies
            (keyword? (:from m)) (update :from #(pdb/on-main main %))
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
      ;; with :commit C (a session restored at that point of its branch, docs/storage.md#compiled-code), the branch is made
      ;; from commit C and not from main's head, and is resumed from its first form: the agent loads its program at C over main's image
      :spawn (let [{:keys [agent branch commit]} m
                   resumed? (exists? branch)
                   ;; a fork is an outside effect: what it reads from the store (its session's entries,
                   ;; seon.agent/undelivered) is synced first (docs/storage.md#durability-is-continuous-group-commit), the
                   ;; batch this op just committed included (:dirty is settled only after the drain)
                   _ (when (:durable? st) (pdb/sync! (:cfg st)))
                   gone (when (and commit (not resumed?))
                          ;; the named edge (docs/failure-points.md): datahike's branch! throws :commit-not-found before it writes anything
                          (try (d/branch! conn commit branch) nil
                               (catch clojure.lang.ExceptionInfo e
                                 (if (= :commit-not-found (:type (ex-data e))) e (throw e)))))]
               (if (or gone (and commit resumed?))
                 (reply st (refused (if gone
                                      (str "commit " commit " is gone from the store (collected): no session can be restored at it")
                                      (str "branch " branch " exists: a restore at commit " commit " makes a new branch"))
                                    {:branch branch :commit commit}))
                 (let [resumed? (boolean (or resumed? commit))]
                   (when-not resumed? (d/branch! conn main branch))
                   ;; main's commit and t, which this branch starts at and the image it forks from holds
                   (step/out! o :to-fork-server {:op :fork :agent agent :branch branch :task (:task m) :ns (:ns m) :fd (:fd m) :key (:key m)
                                                  :cfg (:cfg st) :resumed resumed?
                                                  :commit (d/commit-id @conn) :t (:max-tx @conn)})
                   (reply st {:ok true :t (:max-tx @conn) :resumed resumed?}))))
      ;; a session's files as a branch of main, keyed by their tree (seon.system/prove-tree!): made once, from main's head, with
      ;; the catch-up of the checkout written on it; a second asker of one tree finds it made (:exists), never makes it again
      :branch-for-tree (reply st (if (exists? (:branch m))
                                   {:ok true :exists true}
                                   (do (d/branch! conn main (:branch m))
                                       (when (seq (:tx m))
                                         (let [bc (pdb/connect (assoc (:cfg st) :branch (:branch m)))]
                                           (try (d/transact bc (vec (:tx m))) (finally (d/release bc)))))
                                       {:ok true :exists false})))
      ;; a tree's branch dropped once no proof of it runs (seon.system/prove-tree!; the owner's ruling, 2026-11-11): only a tree-<id>
      ;; branch, never another; one already gone answers so
      :drop-tree-branch (reply st (cond (not (pdb/tree-branch? (:branch m))) (refused (str "not a tree's branch: " (:branch m)) {})
                                        (exists? (:branch m)) (do (d/delete-branch! conn (:branch m)) {:ok true :dropped true})
                                        :else {:ok true :dropped false}))
      :clone-branch (do (d/branch! conn (:from m) (:branch m))
                        (reply st {:ok true :t (:max-tx (pdb/head conn (:branch m))) :cfg (:cfg st)}))
      :octopus-merge (let [[st r] (octopus-merge st o (vec (:branches m)))] (reply st r))
      ;; the merge gate's candidate: branch b merged over main as the octopus merge would merge it, applied to
      ;; a value and never committed, and the files that merge would write, with their text as it holds them
      ;; (written, source/files): what the gate tests is what the merge writes, schema resources included
      :merge-candidate (reply st (cond
                                   (pending-refused conn :merge-candidate) (pending-refused conn :merge-candidate)
                                   (exists? (:branch m))
                                   ;; what the merge would write (member-applied!, as the octopus merge applies it), applied on main's
                                   ;; connection and abandoned, never committed: nothing is pending when an op runs (writer-one)
                                   (let [[head r base] (branch-merge st (:branch m))
                                         a (try (member-applied! conn @conn @conn base head false)
                                                (finally (dw/abandon-pending! conn)))
                                         db (:db a)]
                                     (or (:refused a)
                                         (let [w (written db (:ours r)) text (doc/files db (:files w))]
                                           (or (unanalyzable db text) (assoc w :ok true :text text)))))
                                   :else (refused (str "no branch " (:branch m)) {})))
      ;; one derivation (seon.merge/three-way) for the dashboard, the done rule and the judgment of a merge up
      :branch-diff (reply st (if (exists? (:branch m))
                               (let [[head r base] (branch-merge st (:branch m))]
                                 {:ok true :db head :base base :base-t (:max-tx base)
                                  :ours (vec (sort-by str (keys (:ours r)))) :theirs (count (:theirs r))
                                  :conflicts (count (:conflicts r))
                                  :uncontracted (pm/uncontracted (:ours r))
                                  :caused (vec (problems/caused base head (:ours r)))
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
      ;; branch the last one saw is gone, and recorded on main through the batch; then root's
      ;; heap collected in full, which returns its free pages past the reserve to the OS
      ;; (docs/machine.md#what-a-process-owns-in-memory)
      :collect (let [r (pdb/collect! conn (cfg/value :seon.config.store/sole-writer?))]
                 (reply (if (:skipped r) st (commit-batch (update st :pend (fnil conj []) {:tx-data [{:seon.system/id :root :seon.system/collected r}]}) o))
                        (do (lib/non-static-live!) {:ok true :collected r})))
      (reply st (refused (str "unknown op " op) {})))))

(defn- writer-one
  "One request into the batch: root's :tx on main joins the batch; any other op first
  commits the batch (it reads committed heads), then runs. Only the op itself is caught (an
  interop edge, Datahike, refused: its answer is the error, as data): the batch's commit is
  outside the catch, so a refused op never hands back a state from before a commit Datahike made."
  {:malli/schema [:=> [:cat :seon.writer/state :seon.host/volatile :seon.writer/request] :seon.writer/state]}
  [st o {:keys [op] :as m}]
  (if (= op :tx)
    ;; the batch's transact checks it (seon.db/transact-checked): a refusal is answered to its requester alone
    (update st :pend (fnil conj []) m)
    (let [st (commit-batch st o)]
      (try (other-op st o m)
           (catch Throwable e
             ;; with the store's map use: a full map (MDB_MAP_FULL) shows itself against its size
             (answer o (:dest m) (refused (step/error-msg e) (cond-> {} (pdb/map-use (:cfg st)) (assoc :store (pdb/map-use (:cfg st))))))
             (dissoc st :pend))))))

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
  {:op :drained} goes out. Continuous group commit (DECISIONS, Durability is continuous group
  commit): answers
  leave when the store has committed their batch (LMDB's commit, unsynced: every process reads it); a dirty store with no notice out tells the
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
  ([args]
   ;; main's content mark moves at the writer's start: what a boot wrote before it ran (a seed, a catch-up) bears no mark of
   ;; its own, so no branch's base shares the mark main carries from here (docs/storage.md#mains-content-mark)
   (d/transact (:conn args) {:tx-data (mark-tx @(:conn args)) :tx-meta (pdb/tx-meta (pdb/conn-main (:conn args)))})
   (assoc args :tx-count 0 :commits 0 :batches 0
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
