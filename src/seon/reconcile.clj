(ns seon.reconcile
  "The one reconciler (sessions R1 and M1's proto.agents.reconcile): a pure function of main's
  db value (desired state: the tasks; observed state: runs, their ends and proof
  results, root's boot) to the minimal actions that close the gap, and the done rule
  `done-explain` (one function, used by root to judge and by the agent to know when to
  stop). `reconciler-proc` is the root proc that runs it on main's commits and carries the
  actions out as requests to the api, each action's facts written by the writer."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.sock :as sock] [seon.step :as step]
            [seon.task :as task] [seon.proofs :as proofs] [seon.merge :as pm] [seon.program :as program] [seon.source :as source]
            [datahike.api :as d] [clojure.string :as str] [clojure.core.async.flow :as flow] [seon.ref :as ref]
            [seon.config :as cfg] [seon.error :as err]))


(defn merge-limits
  "Task t's merge limits (:seon.config.merge/*), through the one resolver."
  {:malli/schema [:=> [:cat :seon.task/own] :seon.reconcile/merge-limits]}
  [t]
  {:max-conflicts (cfg/value :seon.config.merge/max-conflicts {:task t :model (:seon.task/model t)})
   :max-merge-turns (cfg/value :seon.config.merge/max-merge-turns {:task t :model (:seon.task/model t)})})

;; ---- the view: one read of main ---------------------------------------------------

(defn desired-env
  "The environment task t's runs must run in."
  {:malli/schema [:=> [:cat :seon.task/own] :seon.task/env]}
  [t]
  {:branch (:seon.task/branch t) :model (:seon.task/model t)
   :max-turns (cfg/value :seon.config.agent/max-turns {:task t :model (:seon.task/model t)})})

;; ---- the done rule -----------------------------------------------------------------

(defn done-explain
  "Why a run's measured facts do not make its task done; empty means done. Refused:
  no proofs (never proven); a proof not passing (red), or with no result (unknown: it
  never ran); a regression (a test green on main, the
  proofs of merged tasks, red here); a stray red (a red test that is no task's open
  proof); a contract gap (a function the run defined without a contract); an
  unmerged entry on the branch (a merge conflict not resolved); an open problem the
  branch's work made true (seon.problems/caused: a citation of a var it deleted). A :design task readies
  the interface and plan for its implement step, so its own proofs may stay red but not
  unknown (each written and run), and it is refused :no-design until its plan differs from
  the one its run began with (or that one is unknown), and :no-proofs, as every task is, until
  it registers one (a design that only rewrote its plan was green at its first plan edit, its
  proofs named in prose and never written: the live agents' rounds). A deferred proof (a process test, run
  only when root asks: DECISIONS, Process tests are not automatic) is neither red nor unknown
  here; while unrun it is the merge's open :unrun problem."
  {:malli/schema [:=> [:cat :seon.reconcile/done-input] [:vector :seon.reconcile/not-done]]}
  [{:keys [proofs results main-green allowed-red uncontracted unmerged caused kind plan plan-before deferred]}]
  (let [red? (fn [t] (#{:fail :error} (get results t)))
        design? (= :design kind)]
    (vec (concat
           (when (empty? proofs) [{:reason :no-proofs}])
           (when (and design? (or (nil? plan-before) (= plan plan-before))) [{:reason :no-design}])
           ;; a proof with no result never ran: unknown, not red (v1's reading); a design
           ;; leaves its proofs red, never unknown (the implement step needs them written)
           (for [p (sort-by str proofs) :let [s (get results p)] :when (and (not (contains? deferred p)) (if design? (nil? s) (not= :pass s)))]
             {:reason (if s :proof-red :proof-unknown) :symbol p})
           (for [t (sort-by str main-green) :when (and (not (proofs t)) (red? t))] {:reason :regression :symbol t})
           (for [t (sort-by str (keys results))
                 :when (and (red? t) (not (proofs t)) (not (main-green t)) (not (allowed-red t)))]
             {:reason :stray-red :symbol t})
           (for [s (sort-by str uncontracted)] {:reason :contract-gap :symbol s})
           (for [k (sort unmerged)] {:reason :unmerged :key k})
           (for [k (sort caused)] {:reason :open-problem :key k})))))

(defn hairy
  "Is a merge too hairy to keep resolving, from measured facts: more conflicts than the
  config key; main deleted a var the task's plan or proofs reference; more model turns without
  green than the config key. Answers {:rule :reason} or nil. The merge agent stops early on it;
  the reconciler decides from the run's facts alone."
  {:malli/schema [:=> [:cat [:map [:conflicts [:sequential [:map [:key :any] [:kind :keyword]]]]
                             [:task [:map [:seon.task/accumulated [:map [:plan :string] [:proofs :seon.task/proofs]]]]]
                             [:merge-turns :int] [:max-conflicts :int] [:max-merge-turns :int]]]
                  [:maybe [:map [:rule [:enum :too-many-conflicts :deleted-by-them-referenced :merge-turns]] [:reason :string]]]]}
  [{:keys [conflicts task merge-turns max-conflicts max-merge-turns]}]
  (let [{:keys [plan proofs]} (:seon.task/accumulated task)
        refs (into (set proofs) (ref/names plan))
        deleted (for [{:keys [key kind]} conflicts
                      :when (and (= kind :deleted-by-them) (= :var (first key)) (refs (second key)))]
                  (second key))]
    (cond
      (> (count conflicts) max-conflicts)
      {:rule :too-many-conflicts :reason (str (count conflicts) " conflicts > :seon.config.merge/max-conflicts " max-conflicts)}
      (seq deleted)
      {:rule :deleted-by-them-referenced :reason (str "main deleted " (str/join ", " deleted) ", which the task references")}
      (> merge-turns max-merge-turns)
      {:rule :merge-turns :reason (str merge-turns " turns without green > :seon.config.merge/max-merge-turns " max-merge-turns)})))

(defn explain-text
  "One line naming each reason."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/not-done]] :string]}
  [rs] (str/join "; " (map #(str (name (:reason %)) (some->> (or (:symbol %) (:key %)) (str " "))) rs)))

;; ---- the incremental reconciler: an index watched through main's tx reports --------------
;; k8s's informer + W1's engine: list once (index), then each commit's resolved datoms name the
;; task, run and root entities it touched (observe); only those are re-read (one pull
;; each) and only the tasks they reach are re-derived: the task itself, the tasks of its
;; runs, and the tasks that need it (the reverse :needs index), transitively for a needs
;; change. `actions` derives the same actions as the full view from the index alone; it walks
;; only the active runs (running, ended, dead) and the head of the ready set, so a pass
;; costs what changed plus what is in flight, never the number of tasks.

(def ^:private watched-ns
  #{"seon.task" "seon.run" "seon.root" "seon.config.agent" "seon.config.merge" "seon.message"})

(def park-outcomes
  "A run's ends that park its session instead of ending it (design agent-loop section 1): a
  reply with no eval (waiting), its turn budget spent. Its run is written
  :parked, its process stays, and the next message to the session wakes it with a fresh
  budget. An error is not one: it holds the task with its cause, as any failed run does."
  #{:waiting :cap})

(defn- branch-messages
  "How many messages main holds to the runs on branch br: what a parked session on br is woken
  by when it has taken fewer (:seon.run/taken). O(the messages to br's runs)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] :int]}
  [db br]
  (or (d/q '[:find (count ?m) . :in $ ?b :where [?r :seon.run/branch ?b] [?m :seon.message/to ?r]] db br) 0))

(def empty-index
  "The index of a db with no tasks."
  {:root nil :tasks {} :keys {} :needed-by {} :acc {} :depth {} :claims {}
   :runs {} :by-task {} :done {} :ready (sorted-set) :ready-key {}
   :live #{} :reap #{} :kill {} :judge #{} :merging #{} :msgs {}})

(def ^:private finished task/finished)
(def ^:private judged task/judged)

(defn- live-in?
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.run/run] :boolean]}
  [idx a] (and (= :running (:seon.run/status a)) (= (:boot (:root idx)) (:seon.run/boot a))))

(defn- newest-run
  "Task id's newest run: runs are numbered in the order they start (actions' spawns), so it is
  the count's. nil before its first."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe :seon.run/run]]}
  [idx id] (let [n (count (get-in idx [:by-task id]))] (when (pos? n) (get-in idx [:runs (task/run-id id n)]))))

(defn- parked
  "Task id's newest run when it parked its session (its status :parked, written where it
  parked: the :park action), or nil."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe :seon.run/run]]}
  [idx id] (let [a (newest-run idx id)] (when (= :parked (:seon.run/status a)) a)))

(defn- idle
  "Task id's parked run whose process is idle now: parked in this boot, by an agent. A wake
  reuses it; a cancel, a hold or a new branch for its task stops it (classify: its process
  works only the branch it was forked on). nil otherwise."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe :seon.run/run]]}
  [idx id]
  (let [a (parked idx id)]
    (when (and a (:seon.run/agent a) (= (:boot (:root idx)) (:seon.run/boot a))) a)))

(defn- keeps?
  "Whether a run of a task of kind, ended with outcome, keeps its process at its end (:diffed):
  the agent ended it on a park outcome, and the task is no merge (a merge's run ends its
  process, as before; a whole task, which merges too (seon.task/merges?), keeps its process,
  since its own work is what parked). Its judge then parks it, or stops the kept process (stop-kept)."
  {:malli/schema [:=> [:cat [:maybe :keyword] [:maybe :keyword]] :boolean]}
  [kind outcome] (boolean (and (park-outcomes outcome) (not= :merge kind))))

(defn- done-info
  "{:any? (merged or accepted) :merged-t (the earliest merge, or nil)} of task id."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:map [:any? :boolean] [:merged-t [:maybe :int]]]]}
  [idx id]
  (let [as (map (:runs idx) (get-in idx [:by-task id]))
        ms (keep #(when (= :merged (:seon.run/status %)) (:seon.run/merged-t % 0)) as)]
    {:any? (boolean (some #(finished (:seon.run/status %)) as))
     :merged-t (when (seq ms) (apply min ms))}))

(defn- idx-done? {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id] (boolean (get-in idx [:done id :any?])))

(defn- idx-ready?
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id]
  (let [t (get-in idx [:tasks id])]
    (boolean (and t (= :running (:seon.task/execution t)) (not (idx-done? idx id))
                  (every? #(idx-done? idx %) (:seon.task/needs t))
                  (not-any? #(let [a (get-in idx [:runs %])] (or (live-in? idx a) (judged (:seon.run/status a))))
                            (get-in idx [:by-task id]))
                  ;; parked (its newest run :parked): ready again only when main holds
                  ;; more messages to its session's runs than the run had taken (design agent-loop section 2)
                  ;; judge-tx writes :seon.run/taken on every judged run, so a parked one has it; both
                  ;; count the messages to the runs on the parked run's branch
                  (let [p (parked idx id)]
                    (or (nil? p) (> (get-in idx [:msgs (get-in p [:seon.run/env :branch])] 0) (:seon.run/taken p))))))))

(defn- refresh-done
  "Re-derive task id's done state from its runs; answers [idx flipped?]."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:tuple :seon.reconcile/index :boolean]]}
  [idx id]
  (let [old (get-in idx [:done id :any?]) d (done-info idx id)]
    [(assoc-in idx [:done id] d) (not= old (:any? d))]))

(defn- refresh-ready
  "Move task id's key in the ready set to what its values say now."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :seon.reconcile/index]}
  [idx id]
  (let [idx (-> idx (update :ready disj (get-in idx [:ready-key id])) (update :ready-key dissoc id))
        ;; deepest first: finish the chains already under way before starting new ones
        k [(- (get-in idx [:depth id] 0)) id]]
    (if (idx-ready? idx id) (-> idx (update :ready conj k) (assoc-in [:ready-key id] k)) idx)))

(defn- derive-needs
  "Task id's depth and accumulated proofs from its needs' (already derived), its claims moved."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :seon.reconcile/index]}
  [idx id]
  (let [t (get-in idx [:tasks id])
        ns (filter (:tasks idx) (:seon.task/needs t))
        acc (into (set (:seon.task/proofs t)) (mapcat #(get-in idx [:acc %])) ns)
        old (get-in idx [:acc id] #{})
        claims (as-> (:claims idx) c
                 (reduce (fn [c p] (let [s (disj (get c p) id)] (if (seq s) (assoc c p s) (dissoc c p)))) c (remove acc old))
                 (reduce (fn [c p] (update c p (fnil conj #{}) id)) c (remove old acc)))]
    (assoc idx :acc (assoc (:acc idx) id acc) :claims claims
           :depth (assoc (:depth idx) id (if (seq ns) (inc (apply max (map #(get-in idx [:depth %] 0) ns))) 0)))))

(defn- put-task
  "Task value t (nil: gone) under id, with the reverse needs index moved."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id [:maybe :seon.task/own]] :seon.reconcile/index]}
  [idx id t]
  (let [old (get-in idx [:tasks id :seon.task/needs] #{})
        new (:seon.task/needs t #{})
        nb (as-> (:needed-by idx) nb
             (reduce (fn [nb n] (update nb n disj id)) nb (remove new old))
             (reduce (fn [nb n] (update nb n (fnil conj #{}) id)) nb (remove old new)))]
    (assoc idx :needed-by nb :tasks (if t (assoc (:tasks idx) id t) (dissoc (:tasks idx) id)))))

(defn- put-run
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.run/id [:maybe :seon.run/run]] :seon.reconcile/index]}
  [idx aid a]
  (let [old (get-in idx [:runs aid])
        tid (or (:seon.run/task a) (:seon.run/task old))]
    (-> idx
        (assoc :runs (if a (assoc (:runs idx) aid a) (dissoc (:runs idx) aid)))
        (update-in [:by-task tid] (fnil (if a conj disj) #{}) aid))))

(defn- downstream
  "ids and every task that needs one of them, transitively (seon.program/closure over :needed-by)."
  {:malli/schema [:=> [:cat :seon.reconcile/index [:set :seon.task/id]] [:set :seon.task/id]]}
  [idx ids] (into (set ids) (program/closure ids #(get-in idx [:needed-by %]))))

(defn- topo
  "ids and every task that needs one of them, transitively, each after its needs among them
  (seon.program/topo; ties by id)."
  {:malli/schema [:=> [:cat :seon.reconcile/index [:set :seon.task/id]] [:vector :seon.task/id]]}
  [idx ids]
  (let [all (downstream idx ids)]
    (program/topo (vec all) #(filter all (get-in idx [:tasks % :seon.task/needs])) identity)))

(defn- classify
  "Run aid's place in the sets actions reads: live, reap, kill (with the reason), judge
  (ended or dead), merging (a merge task's run live or ended)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.run/id] :seon.reconcile/index]}
  [idx aid]
  (let [a (get-in idx [:runs aid])
        t (get-in idx [:tasks (:seon.run/task a)])
        st (:seon.run/status a)
        live? (and a (live-in? idx a))
        ;; the newest run of its task, parked in this boot: its process is
        ;; parked, and stays until its task is cancelled or held (design agent-loop section 2)
        parked? (and a (= a (idle idx (:seon.run/task a))))
        kill (cond
               live? (cond (= :cancelled (:seon.task/execution t)) :cancelled
                           (= :held (:seon.task/execution t)) :held
                           (not= (desired-env t) (:seon.run/env a)) :env-changed)
               parked? (cond (= :cancelled (:seon.task/execution t)) :cancelled
                             (= :held (:seon.task/execution t)) :held
                             (not= (:seon.task/branch t) (get-in a [:seon.run/env :branch])) :env-changed))
        mark (fn [idx k on?] (update idx k (if on? conj disj) aid))]
    (-> idx
        (mark :live live?)
        (mark :reap (and a (= :running st) (not live?)))
        (update :kill #(if kill (assoc % aid kill) (dissoc % aid)))
        (mark :judge (boolean (judged st)))
        ;; a merge run merges while it runs; a whole run (its own work first) once it ends
        (mark :merging (and a (case (:seon.task/kind t) :merge (or live? (= :ended st)) :whole (= :ended st) false))))))

(defn- settle
  "Re-derive after entities changed: tasks whose needs or proofs changed (and the tasks that
  need them, transitively) get depth and accumulated proofs again in dependency order; each
  dirty task's done state is re-derived (it depends on its own runs only); then readiness
  of the dirty tasks and of the tasks needing one whose done state flipped."
  {:malli/schema [:=> [:cat :seon.reconcile/index [:set :seon.task/id] [:set :seon.task/id] [:set :seon.run/id]] :seon.reconcile/index]}
  [idx needs-changed dirty touched]
  (let [order (topo idx needs-changed)
        idx (reduce derive-needs idx order)
        dirty (into dirty order)
        [idx flipped] (reduce (fn [[idx fl] id] (let [[idx f?] (refresh-done idx id)] [idx (cond-> fl f? (conj id))])) [idx []] dirty)]
    (reduce classify
            (reduce refresh-ready idx (into dirty (mapcat #(get-in idx [:needed-by %])) flipped))
            (into touched (mapcat #(get-in idx [:by-task %])) dirty))))

(defn observe
  "The index after main's commit: ops are the commit's resolved datoms ([op e a v]), db the
  db after it. Re-reads only the entities the commit touched."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.host/db [:sequential :any]] :seon.reconcile/index]}
  [idx db ops]
  (let [es (into #{} (keep (fn [[_ e a]] (when (and (keyword? a) (watched-ns (namespace a))) e))) ops)
        ;; a message to a run: its branch's count moves by one (a commit's op names the run it
        ;; went to; a diff's op names only the message, so its branch is counted again), and the
        ;; tasks that can be that session's newest are re-derived: the run's task and the tasks
        ;; downstream of it on its branch (a session is a task and the same-branch tasks it needs)
        msgs (keep (fn [[op e a v]] (when (= :seon.message/to a)
                                      (let [r (d/entity db (or v (:db/id (:seon.message/to (d/entity db e)))))]
                                        {:br (:seon.run/branch r) :task (:seon.task/id (:seon.run/task r)) :d (when v (if (= :db/retract op) -1 1))})))
                   ops)
        idx (reduce (fn [idx {:keys [br d]}] (if d (update-in idx [:msgs br] (fnil + 0) d) (assoc-in idx [:msgs br] (branch-messages db br))))
                    idx (filter :br msgs))
        es (into es (for [{:keys [br task]} msgs :when (and br task), id (downstream idx #{task})
                          :when (= br (get-in idx [:tasks id :seon.task/branch]))
                          :let [e (:db/id (d/entity db [:seon.task/id id]))] :when e]
                      e))]
    (if (empty? es)
      idx
      (let [step (fn [[idx nc dirty all? touched] e]
                   (let [[kind v :as ev] (task/entity-value db e)
                         [okind oid] (get-in idx [:keys e])]
                     (case (or kind okind)
                       :task (let [id (or (:seon.task/id v) oid)
                                   needs? (not= (get-in idx [:tasks id :seon.task/needs]) (:seon.task/needs v))]
                               [(-> idx (put-task id v) (update :keys #(if v (assoc % e [:task id]) (dissoc % e))))
                                (cond-> nc (or needs? (nil? (get-in idx [:acc id])) (not= (get-in idx [:tasks id :seon.task/proofs]) (:seon.task/proofs v))) (conj id))
                                (conj dirty id) all? touched])
                       :run (let [aid (or (:seon.run/id v) oid)
                                      tid (or (:seon.run/task v) (get-in idx [:runs aid :seon.run/task]))]
                                  [(-> idx (put-run aid v) (update :keys #(if v (assoc % e [:run aid]) (dissoc % e))))
                                   nc (conj dirty tid) all? (conj touched aid)])
                       :root [(-> idx (assoc :root v) (assoc-in [:keys e] [:root :root]))
                              nc dirty (or all? (not= (:boot (:root idx)) (:boot v))) touched]
                       [idx nc dirty all? touched])))
            [idx nc dirty all? touched] (reduce step [idx #{} #{} false #{}] (sort es))]
        ;; a new boot changes which runs are live: every task is re-derived (once per boot)
        (settle idx nc (if all? (set (keys (:tasks idx))) dirty) touched)))))

(defn index
  "The index of db, listed: observe of every task, run and root entity at once. Given idx,
  the index of db from, the index of db observed from it over the datoms of the watched
  attributes that differ (datahike.api/datoms-diff of each attribute's range, which skips the
  stored subtrees both share): the cost of the watched datoms and what changed, never of the
  program's (task seon.turn/context-render-observes-what-changed)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map]
                  [:=> [:cat :seon.reconcile/index :seon.host/db :seon.host/db] :map]]}
  ([db] (observe empty-index db (concat (for [e (task/entity-ids db)] [:db/add e :seon.task/id nil])
                                        (for [dt (d/datoms db :aevt :seon.message/to)] [:db/add (:e dt) :seon.message/to (:v dt)]))))
  ([idx from db]
   (observe idx db (for [a (sort (into #{} (filter #(and (keyword? %) (watched-ns (namespace %)))) (concat (keys (:schema from)) (keys (:schema db)))))
                         dt (apply concat (d/datoms-diff from db :aevt [a]))]
                     [:db/add (:e dt) a]))))

(defn done-input
  "The done rule's input for run results of task id, observed against main at t basis
  (nil: main as idx holds it), over the index: the regression and allowed-red sets restricted to the tests
  that ran (the only ones done-explain asks about), each test looked up by its claimants; for
  a :design task, its accumulated plan now and plan-before, the one its run began with
  (:seon.run/plan; nil: unknown); deferred, the process tests its run left to root's gate."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id :seon.proof/results [:set :qualified-symbol] [:sequential :string] [:maybe :int] [:maybe :seon.task/plan] [:set :qualified-symbol]] :seon.reconcile/done-input]}
  [idx id results uncontracted unmerged basis plan-before deferred]
  (let [by? (fn [x] (when-let [mt (get-in idx [:done x :merged-t])] (or (nil? basis) (<= mt basis))))
        claim (fn [t] (disj (get-in idx [:claims t] #{}) id))
        kind (get-in idx [:tasks id :seon.task/kind] :work)]
    (cond-> {:proofs (get-in idx [:acc id] #{})
             :kind kind
             :results results
             :main-green (into #{} (filter #(some by? (claim %))) (keys results))
             :allowed-red (into #{} (filter #(some (complement by?) (claim %))) (keys results))
             :uncontracted uncontracted
             :unmerged (vec unmerged)
             :deferred deferred}
      (= :design kind) (assoc :plan (:plan (task/accumulate (:tasks idx) id)))
      (and (= :design kind) plan-before) (assoc :plan-before plan-before))))

(defn- merge-keys
  "The program keys merge task id merges: what the runs on its branch measured as changed
  (:seon.run/changed of its own runs and of the tasks it needs on the same branch),
  or nil when no run there measured it (unknown: it overlaps every merge)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe [:set :string]]]}
  [idx id]
  (let [br (get-in idx [:tasks id :seon.task/branch])
        ts (cons id (filter #(= br (get-in idx [:tasks % :seon.task/branch])) (get-in idx [:tasks id :seon.task/needs])))
        cs (keep #(:seon.run/changed (get-in idx [:runs %])) (mapcat #(get-in idx [:by-task %]) ts))]
    (when (seq cs) (into #{} cat cs))))

(defn actions
  "The actions for index idx, in the order root executes them (the full view's rule): reap,
  kill, judge, then start ready tasks up to max-running, merges up to max-merging. Reads the
  index's sets: its cost is the runs to reap, kill and judge plus the starts."
  {:malli/schema [:=> [:cat :seon.reconcile/index] :seon.reconcile/actions]}
  [idx]
  (let [root (merge {:max-running (cfg/value :seon.config.agent/max-running) :max-merging (cfg/value :seon.config.merge/max-running)} (:root idx))
        att (:runs idx)
        reaps (for [aid (sort (:reap idx))] {:op :reap :run aid})
        kills (for [[aid r] (sort-by key (:kill idx))] {:op :kill :run aid :reason r})
        ;; a failed run (refused by the done rule, or its agent died) holds its task for
        ;; root, which decides whether it runs again (DECISIONS, No automatic retries: a refused
        ;; or failed run is held for root)
        failed (fn [aid id problem] {:op :hold :run aid :task id :problem problem})
        judged (for [aid (sort (:judge idx))
                     :let [a (att aid) id (:seon.run/task a) t (get-in idx [:tasks id])
                           merge? (task/merges? (:seon.task/kind t))]]
                 (cond
                   (not= :running (:seon.task/execution t)) {:op :close :run aid}
                   (= :dead (:seon.run/status a))
                   (failed aid id (str "its agent died" (some->> (:seon.run/reason a) (str ": "))))
                   :else
                   (let [rs (done-explain (assoc (done-input idx id (:seon.run/results a {}) (:seon.run/uncontracted a #{})
                                                                 (:seon.run/unmerged a []) (:seon.run/basis-t a) (:seon.run/plan a)
                                                                 (set (:seon.run/deferred a)))
                                                      :caused (:seon.run/caused a #{})))]
                     (cond
                       ;; the process tests the branch's change reaches run before its merge
                       ;; only when root asked (:seon.task/gate?, my.root/gate!; DECISIONS,
                       ;; Process tests are not automatic), on a checkout (:gate, seon.fx/run-tests)
                       (and (empty? rs) merge?)
                       (let [deferred (sort-by str (:seon.run/deferred a #{})) dres (:seon.run/deferred-results a)
                             red (for [t deferred :let [s (get dres t)] :when (not= :pass s)] [t (or s :unknown)])]
                         ;; unasked, or with no checkout declared (root's ruling (a)),
                         ;; the merge goes on, its deferred tests unrun: an open :unrun problem
                         (cond (or (empty? deferred) (not (:seon.task/gate? t)) (nil? (get-in idx [:root :gate-checkout])))
                               {:op :ready-to-merge :run aid :task id}
                               (nil? dres) {:op :gate :run aid :task id :tests (vec deferred)}
                               (empty? red) {:op :ready-to-merge :run aid :task id}
                               :else (failed aid id (str "run " aid "'s deferred tests, run on a checkout at its merge, are not green: "
                                                           (str/join ", " (for [[t s] red] (str t " " (name s))))))))
                       (empty? rs) {:op :accept :run aid :task id}
                       ;; it parked (waiting, out of turns, no progress, a provider's error): never a
                       ;; hold, the session waits for its next message (design agent-loop section 1)
                       (keeps? (:seon.task/kind t) (:seon.run/outcome a)) {:op :park :run aid}
                       :else
                       (failed aid id (str "run " aid " ended" (some->> (:seon.run/outcome a) name (str " "))
                                             (some->> (:seon.run/reason a) (str " (") (#(str % ")")))
                                             ": " (explain-text rs)))))))
        ;; the octopus merge (bors, GitHub's merge queue): green merge runs merge together in
        ;; one commit once no merge run is still running, so every member merged the same
        ;; main; an octopus merge closes when its first member ends (no new member joins until it
        ;; merges, so merging cannot starve)
        ready (filter #(= :ready-to-merge (:op %)) judged)
        live-merge? (some (:live idx) (:merging idx))
        ;; one gate at a time, while no merge is in flight (it runs on the merges branch's head)
        gates (when-not live-merge? (take 1 (filter #(= :gate (:op %)) judged)))
        judged (remove #(#{:ready-to-merge :gate} (:op %)) judged)
        octopus (when (and (seq ready) (not live-merge?))
                [{:op :octopus-merge :run (:run (first ready)) :members (mapv #(select-keys % [:run :task]) ready)}])
        closed? (some #(= :ended (get-in idx [:runs % :seon.run/status])) (:merging idx))
        room (max 0 (- (:max-running root) (- (count (:live idx)) (count (filter (:live idx) (keys (:kill idx)))))))
        merge? (fn [id] (= :merge (get-in idx [:tasks id :seon.task/kind])))
        merge-room (max 0 (- (:max-merging root) (count (:merging idx))))
        ;; a merge joins only when what it changes is disjoint from every merge in flight;
        ;; one whose changes are unknown runs alone; conflicting merges serialize
        flying (map #(merge-keys idx (get-in idx [:runs % :seon.run/task])) (:merging idx))
        taken0 (when-not (some nil? flying) (into #{} cat flying))
        starts (loop [[[_ id] & more] (seq (:ready idx)) n 0 m 0 taken taken0 acc []]
                 (cond (or (nil? id) (>= n room)) acc
                       (merge? id) (let [ks (merge-keys idx id)
                                         ok (and (< m merge-room) (not closed?) taken
                                                 (if ks (not-any? taken ks) (and (zero? m) (empty? (:merging idx)))))]
                                     (if ok
                                       (recur more (inc n) (inc m) (when ks (into taken ks)) (conj acc id))
                                       (recur more n m taken acc)))
                       :else (recur more (inc n) m taken (conj acc id))))
        spawns (for [id starts :let [t (get-in idx [:tasks id]) p (idle idx id)
                                     run (task/run-id id (inc (count (get-in idx [:by-task id]))))]]
                 ;; its process is idle (parked in this boot, on its branch): its next run is a wake on it, no fork
                 (if (and p (not (get-in idx [:kill (:seon.run/id p)])))
                   {:op :wake :task id :env (desired-env t) :run run :agent (:seon.run/agent p)}
                   {:op :spawn :task id :env (desired-env t) :run run}))]
    (vec (concat reaps kills judged gates octopus spawns))))

;; ---- held for root, and the root causes at quiescence -----------------------------------

(defn blocks
  "The released tasks not done that need task id, transitively, sorted."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:vector :seon.task/id]]}
  [idx id]
  (vec (sort (filter #(and (= :running (get-in idx [:tasks % :seon.task/execution])) (not (idx-done? idx %)))
                     (disj (downstream idx #{id}) id)))))

(defn root-causes
  "Why released work is not done, as root causes: each task held for root with a failure
  (a run refused, its agent dead, its merge refused, or marked failed: its reason),
  each task whose latest run root refused to spawn (its reason: the branch is held, or
  too many live agents), each task whose latest run parked its session (its outcome: it waits
  for a message, which wakes it), and each other task not released (cancelled, held) that released
  work needs, with the released tasks it blocks. Read once at quiescence: one pass over the
  tasks."
  {:malli/schema [:=> [:cat :seon.reconcile/index] [:vector :seon.reconcile/root-cause]]}
  [idx]
  (vec (for [[id t] (sort-by key (:tasks idx))
             :when (not (idx-done? idx id))
             :let [ex (:seon.task/execution t)
                   f (when (= :held ex) (:seon.task/failure t))
                   ;; runs are numbered in order (actions' spawns), so the latest is the count's
                   latest (let [n (count (get-in idx [:by-task id]))] (when (pos? n) (get-in idx [:runs (task/run-id id n)])))
                   refused (when (= :refused (:seon.run/status latest)) latest)
                   parked (when (= :parked (:seon.run/status latest)) latest)
                   stopped (or refused parked)
                   bs (blocks idx id)]
             :when (cond (= :running ex) stopped f true :else (seq bs))]
         (cond-> {:task id :cause (cond f (:reason f) refused (:seon.run/reason refused)
                                        parked (str "parked: " (some-> (:seon.run/outcome parked) name) ", waiting for a message")
                                        :else (name ex))
                  :blocks bs}
           (:run f) (assoc :run (:run f))
           stopped (assoc :run (:seon.run/id stopped))))))

(defn- cas-tx
  "Move run aid's status from -> to (Datahike's compare-and-swap, by lookup ref: a racing
  observer that already moved it leaves it), with the observed members m."
  {:malli/schema [:=> [:cat :seon.run/id :seon.run/status :seon.run/status :map] [:vector :any]]}
  [aid from to m]
  (cond-> [[:db/cas [:seon.run/id aid] :seon.run/status from to]]
    (seq m) (conj (assoc m :seon.run/id aid))))

(defn evidence
  "The measured evidence of failed run a: its conflicts (read from the branch by root's
  judge), its unmerged keys, the proofs not passing in root's own test run, and the problem
  the reconciler stated (nil: none)."
  {:malli/schema [:=> [:cat :seon.run/run [:maybe :string]] :seon.task/evidence]}
  [a problem]
  (cond-> {:from (:seon.run/id a)
           :conflicts (vec (:seon.run/conflicts a []))
           :unmerged (vec (:seon.run/unmerged a []))
           :red (vec (sort-by str (for [[t s] (:seon.run/results a) :when (not= :pass s)] t)))}
    problem (assoc :problem problem)))

(defn hold-tx
  "Run a failed (the done rule refused it, its agent died, or its merge was refused):
  its task held for root with the failure (problem, the run) and the measured evidence,
  exactly as my.task/fail!'s failure holds it, and the run closed, in one write. Root
  decides: release it again (my.root/run!: the next run continues its branch, from the
  evidence), edit it, or cancel it (DECISIONS, No automatic retries: a refused or failed run is
  held for root). The hold is a
  compare-and-swap from :running, so a cancel! that merged first refuses it (the run is
  then closed by the judged rule, its task not running)."
  {:malli/schema [:=> [:cat :seon.run/run :string] [:vector :any]]}
  [a problem]
  (let [aid (:seon.run/id a) id (:seon.run/task a)]
    (into (cas-tx aid (:seon.run/status a) :closed {})
          [[:db/cas [:seon.task/id id] :seon.task/execution :running :held]
           {:seon.task/id id :seon.task/failure {:reason problem :run aid} :seon.task/evidence (evidence a problem)}])))

(defn quiescent
  "Main's last quiescence report on db ({:t :root-causes}, written by the reconciler proc), or nil."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.root/quiescent]]}
  [db]
  (:seon.root/quiescent (d/pull db [:seon.root/quiescent] [:seon.root/id :root])))

(defn last-commit
  "The merges commit of db's last merged run (the write-back's sha), or nil."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :string]]}
  [db]
  (some->> (d/q '[:find ?t ?c :where [?e :seon.run/commit ?c] [?e :seon.run/merged-t ?t]] db) (sort-by first) last second))

(defn generation
  "The next generation on db: the last merges commit the :build effect built or refused
  ({:commit :checkout :binary :ms} or {:commit :ms :error}, written by my.root/build!), or
  nil."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.root/generation]]}
  [db]
  (:seon.root/generation (d/pull db [:seon.root/generation] [:seon.root/id :root])))

;; ---- queries, over one index (list it once: (index db)) -----------------------------

(defn done?
  "Is task id done (merged into its target, or accepted on its branch)?"
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id] (idx-done? idx id))

(defn blocked-by
  "The needs of task id that are not done."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:set :seon.task/id]]}
  [idx id] (into #{} (remove #(idx-done? idx %)) (get-in idx [:tasks id :seon.task/needs])))

(defn task-state
  "What happened to task id (:seon.task/state), from the index; its desired execution stays
  on the task. The one derivation every task row reads (seon.render.proc/task-rows)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :seon.task/state]}
  [idx id]
  (let [x (get-in idx [:tasks id :seon.task/execution])]
    (cond (= :cancelled x) :cancelled
          (idx-done? idx id) (if (get-in idx [:done id :merged-t]) :merged :done)
          (some #(= :running (get-in idx [:runs % :seon.run/status])) (get-in idx [:by-task id])) :running
          (seq (blocked-by idx id)) :blocked
          (= :held x) (if (get-in idx [:tasks id :seon.task/failure]) :failed :held)
          :else :open)))

;; ---- what a run's agent wrote on its branch ----

(defn pull-end
  "The end run aid's turn loop wrote on branch db ({:outcome :reason :turns :tokens},
  and the error that ended it and its waste as data), or nil: it wrote none."
  {:malli/schema [:=> [:cat :seon.host/db :seon.run/id] [:maybe :map]]}
  [db aid]
  (some->> (d/pull db [:seon.end/outcome :seon.end/reason :seon.end/turns :seon.end/tokens :seon.end/error :seon.end/waste]
                   [:seon.end/run aid])
           (into {} (map (fn [[k v]] [(keyword (name k)) v])))))

;; ---- the reconciler proc: actions carried out as requests, every answer as data ----------
;; Root's executor (R1's proto.agents.root, a thread per run) as one step-fn: main's
;; commits arrive through a sliding buffer of one (a reconciler reads main's latest db, so a
;; commit it missed is covered by the next); each action is a flight, a short chain of
;; requests to the api (spawn, eval, tests, the writer's reads and writes, kill), each answer
;; arriving on :answers keyed by its request id. An action whose run is in flight is not
;; started twice.

(defn- request
  "Send req to the api as this proc's request: the answer comes back on :answers tagged by a
  new request id, which then names step of run aid's flight. Answers [st output]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.run/id :seon.reconcile/step :map] [:tuple :seon.reconcile/state :map]]}
  [st aid step req] (step/ask st [:reconciler :answers] [aid step] req))

(defn- write
  "A write on main through the writer, as step of aid's flight."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.run/id :seon.reconcile/step [:sequential :any]] [:tuple :seon.reconcile/state :map]]}
  [st aid step tx] (request st aid step {:op :write :req {:op :tx :branch :db :tx-data (vec tx)}}))

(defn- kept?
  "Whether ended run a's process was kept at its end (:diffed, keeps?): a judge that does not
  park it stops it."
  {:malli/schema [:=> [:cat :seon.reconcile/state [:maybe :map]] :boolean]}
  [st a] (and (= :ended (:seon.run/status a)) (keeps? (:seon.task/kind (task/task (:db st) (:seon.run/task a))) (:seon.run/outcome a))))

(defn- stop-kept
  "[st outputs] with run's kept process stopped too (its run ended: root's view says :ended)."
  {:malli/schema [:=> [:cat [:tuple :seon.reconcile/state [:vector :map]] :seon.run/id :keyword] [:tuple :seon.reconcile/state [:vector :map]]]}
  [[st os] run agent]
  (let [[st o] (request st run :ignored {:op :kill :id agent :ended true})] [st (conj os o)]))

(defn- start
  "Begin action act's flight. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.reconcile/action] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st {:keys [op run] :as act}]
  (let [;; a kill reaches a live run whose observer chain is in flight: that flight stays
        ;; and sees its agent die
        killing? (and (= :kill op) (get-in st [:flights run]))
        st (if killing? (assoc-in st [:flights run :killing] true) (assoc-in st [:flights run] {:act act}))
        a (task/run (:db st) run)
        agent (:seon.run/agent a)
        one (fn [[st o]] [st [o]])]
    (case op
      :wake (let [ag (:agent act)]
              ;; the parked process's next run: written running, then work! (the :started step)
              (one (write (assoc-in st [:flights run :agent] ag) run :started
                          (task/run-start-tx run (:task act) (:boot st) ag (:env act) (System/currentTimeMillis)
                                             (:plan (task/accumulate (get-in st [:idx :tasks]) (:task act)))
                                             (task/session-key (:db st) (:task act))))))
      :spawn (let [k (task/session-key (:db st) (:task act))]
               ;; the session's agent continues (design agent-loop section 1); kept on the flight for its run's start
               (one (request (cond-> st k (assoc-in [:flights run :key] k)) run :spawned
                             {:op :spawn :opts (cond-> {:branch (get-in act [:env :branch]) :task (:task act) :run run} k (assoc :key k))})))
      :reap (one (write st run :done (cas-tx run :running :lost {:seon.run/end-ms (System/currentTimeMillis) :seon.run/reason "root restarted"})))
      ;; the run parked its session: written once, here; its process stays (design agent-loop section 1)
      :park (one (write st run :done (cas-tx run :ended :parked {})))
      :close (cond-> (one (write st run :done (cas-tx run :ended :closed {})))
               (kept? st a) (stop-kept run agent))
      ;; a run that parked yet is judged done or held (its proofs green, or a failure it marked)
      ;; ends its session: its kept process is stopped (kept, :diffed)
      :accept (cond-> (one (write st run :done (cas-tx run :ended :done {})))
                (kept? st a) (stop-kept run agent))
      ;; a live run, or a parked one (its process idle) whose task was cancelled or held
      :kill (let [[st o1] (request st run :ignored {:op :kill :id agent})
                  [st o2] (write st run (if killing? :ignored :done) (cas-tx run (:seon.run/status a :running) :killed
                                                                             (cond-> {:seon.run/reason (name (:reason act))}
                                                                               (= :running (:seon.run/status a)) (assoc :seon.run/end-ms (System/currentTimeMillis)))))]
              [st [o1 o2]])
      ;; refused, dead or its merge refused: the task is held for root, with the evidence
      :hold (cond-> (one (write st run :done (hold-tx a (:problem act))))
              (kept? st a) (stop-kept run agent))
      ;; a merge task's branch into main: clean by construction when the branch holds main's
      ;; program head; behind: its task runs again, a new agent
      ;; an octopus merge: every member's branch into main in one commit (each holds main's
      ;; program head, the members' changes are disjoint); a member found behind or refused
      ;; is answered alone
      :octopus-merge (let [bs (mapv #(get-in (task/run (:db st) (:run %)) [:seon.run/env :branch]) (:members act))]
                     (one (request (assoc-in st [:flights run :branches] bs) run :octopus-merged
                                   {:op :write :req {:op :octopus-merge :branches bs :data-attrs #{}}})))
      ;; the merge gate (root asked: :seon.task/gate?): the candidate's files read from its
      ;; branch, then its deferred tests run on a checkout of the merges head with them (seon.fx/run-tests)
      :gate (one (request st run :gate-read
                          {:op :write :req {:op :branch-db :branch (get-in (task/run (:db st) run) [:seon.run/env :branch])}})))))

(defn- work
  "[st [output]]: the agent's turn loop for run aid of act, asked of agent: seon.turn/work!,
  quoted as data (a form sent to another process is no call in this one; a quoted name is no
  reference, seon.program/unit-account)."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.run/id :map :keyword] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st aid act agent]
  (let [[st o] (request st aid :worked
                        {:op :eval :id agent
                         :forms (sedn/encode (list 'seon.turn/work! (list 'quote (cond-> {:task (:task act) :run aid :max-turns (get-in act [:env :max-turns])}
                                                                                (get-in act [:env :model]) (assoc :model (get-in act [:env :model]))))))})]
    [st [o]]))

(defn- stage-boundary
  "The message that opens run aid's stage of its session, or nil: when the session's agent ran
  another task before (its newest earlier run, by the transaction that started it), the new
  task's plan and proofs, said once at the boundary (its whole task data is (my.task/data): the
  task data's render reaches the related search, a process, which no reconciler test may), so the conversation above stays as it was
  sent (design agent-loop section 1a; the live agents' finding A1: the new stage's task data put at
  the top cached 1% of 33k tokens, and the agent took the design for its task)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.run/id] [:maybe :string]]}
  [db aid]
  (let [task-of #(:seon.task/id (:seon.run/task (d/entity db [:seon.run/id %])))
        id (task-of aid)
        [_ prev] (->> (d/q '[:find ?tx ?id :in $ ?me :where [?x :seon.run/id ?me] [?x :seon.run/agent ?g ?mt] [?r :seon.run/agent ?g ?tx] [(< ?tx ?mt)] [?r :seon.run/id ?id]] db aid)
                      (sort-by first) last)
        prev-task (some-> prev task-of)]
    (when (and prev-task (not= prev-task id))
      (let [{:keys [plan proofs]} (:seon.task/accumulated (task/task db id))]
        (str prev-task " is done. Your task now is " id ", in this same session: what you did so far stays above.\n\n"
             "Its plan: " plan "\n\nIts proofs: " (str/join " " (sort (map str proofs)))
             "\n\nIts whole task as data (what it cites, its write set): (my.task/data)")))))

(defn- taken-messages
  "How many messages to the runs on run aid's branch (as main names them) branch db holds:
  those its session's turns took (each written on the branch by the turn that took it). The
  other side of branch-messages' count. One read of :seon.run/branch on main (its runs; the
  attribute is not indexed), then one seek per run's id on db and one per its messages: once a
  judged run."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.run/id] :int]}
  [main db aid]
  (let [ids (d/q '[:find [?id ...] :in $ ?a :where [?x :seon.run/id ?a] [?x :seon.run/branch ?b] [?r :seon.run/branch ?b] [?r :seon.run/id ?id]] main aid)]
    (or (d/q '[:find (count ?m) . :in $ [?id ...] :where [?r :seon.run/id ?id] [?m :seon.message/to ?r]] db ids) 0)))

(defn- judge-tx
  "Run aid's end on main from MEASURED facts: the end its agent wrote on the branch,
  root's own proof results, the branch's merge entries, and diff, the writer's one read of the
  branch (:branch-diff: its head db, its merge base and main's t there, the keys it changed,
  its unmerged keys, its contract gaps); the
  task content the branch changed carried to main, or its conflicts with main's recorded on
  the run for root (seon.task/carry-tx); and a failure it marked (my.task/fail!)
  carried with the task held."
  {:malli/schema [:=> [:cat :seon.reconcile/state :map :seon.proof/results [:map [:db :seon.host/db] [:base :seon.host/db] [:ours [:sequential :any]] [:unmerged [:sequential :any]] [:uncontracted [:sequential :qualified-symbol]]]] [:vector :any]]}
  [st {:keys [act run-problems worked-error deferred]} results {:keys [db base base-t] :as diff}]
  (let [main (:db st)
        aid (:run act) id (:task act)
        end (pull-end db aid)
        carried (task/carry-tx base db main)
        failure (:seon.task/failure (task/task db id))]
    (into (cas-tx aid :running :ended
                    ;; spend is what the end measured: no end (died, killed, never reached
                    ;; seon.turn/end!) is outcome :no-end with its turns, calls and tokens absent
                    (merge (task/end-members (cond-> (merge (select-keys end [:turns :model-calls :tokens])
                                                      {:outcome (:outcome end :no-end) :results results :uncontracted (set (:uncontracted diff)) :caused (set (:caused diff))
                                                       :conflicts (mapv #(dissoc % :main-t :resolved?) (pm/entries db))
                                                       :unmerged (mapv sedn/encode (:unmerged diff))
                                                       :changed (into #{} (map sedn/encode) (:ours diff))
                                                       :deferred (vec deferred)})
                                        (:reason end) (assoc :reason (:reason end))
                                        (not (:outcome end)) (assoc :reason (if worked-error
                                                                             (str "seon.turn/work! threw: " (:seon.error/message worked-error))
                                                                             "the agent wrote no end")))
                                      (System/currentTimeMillis) (or base-t (:max-tx main)))
                    ;; the problems measured at its end (session D2): red assertions, cost verdicts,
                    ;; the error that ended it, its waste
                    (cond-> {;; the messages to its session's runs (those on its branch) its branch had taken:
                             ;; main holding more (branch-messages) wakes it once parked (idx-ready?),
                             ;; whatever came between its last turn and now
                             :seon.run/taken (taken-messages main db aid)}
                      (seq (:failures run-problems)) (assoc :seon.run/failures (:failures run-problems))
                      (seq (:costs run-problems)) (assoc :seon.run/costs (:costs run-problems))
                      (or (:error end) worked-error) (assoc :seon.run/error (or (:error end) worked-error))
                      (seq (:waste end)) (assoc :seon.run/waste (:waste end))
                      (seq (:conflicts carried)) (assoc :seon.run/task-conflicts (:conflicts carried)))))
            (concat (:tx carried)
                    ;; marked failed in this run (my.task/fail!): the failure on main and the
                    ;; task held for root (a compare-and-swap from :running), so the judge closes
                    ;; the run (no retry); one root already cancelled or held keeps root's
                    (when (= aid (:run failure))
                      (cond-> [{:seon.task/id id :seon.task/failure failure}]
                        (= :running (:seon.task/execution (task/task main id)))
                        (conj [:db/cas [:seon.task/id id] :seon.task/execution :running :held])))))))

(defn- dead-tx
  "Run aid's death on main: the end its agent wrote on its branch before it exited (its
  reason, and its error as the cause), beside e, the death root observed (:seon.agent/died:
  exit status, stderr tail, last op; or :seon.root/no-agent or :seon.root/ended). A silent death wrote no end."
  {:malli/schema [:=> [:cat :seon.run/id :seon.error/base [:maybe :map]] [:vector :any]]}
  [aid e end]
  (cas-tx aid :running :dead {:seon.run/end-ms (System/currentTimeMillis)
                              :seon.run/reason (if (:reason end) (str (:reason end) " (" (:seon.error/message e) ")") (:seon.error/message e))
                              :seon.run/error (cond-> e (:error end) (assoc :cause (:error end)))}))

(defn- advance
  "Run aid's flight takes answer r of its step. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.run/id :seon.reconcile/step :any] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st aid step r]
  (let [{:keys [act agent] :as f} (get-in st [:flights aid])
        done (fn [st] [(update st :flights dissoc aid) []])
        one (fn [[st o]] [st [o]])
        ;; its agent died: the one error map root answered with (:seon.agent/died: exit
        ;; status, last op, stderr tail; or :seon.root/no-agent or :seon.root/ended), and the end the agent wrote
        ;; on its branch before it exited, read from the branch
        dead (fn [st e] (one (request (assoc-in st [:flights aid :died] e) aid :dead-read
                                      {:op :write :req {:op :branch-db :branch (get-in act [:env :branch])}})))
        ;; the writer refused to read or diff its branch: its agent is stopped and the run
        ;; ends with the reason as its error, not judged on facts nobody measured
        refused (fn [st e] (let [[st o1] (request st aid :ignored {:op :kill :id agent :ended true})
                                 [st o2] (write st aid :done (cas-tx aid :running :ended {:seon.run/end-ms (System/currentTimeMillis)
                                                                                          :seon.run/reason (:seon.error/message e) :seon.run/error e}))]
                             [st [o1 o2]]))]
    (case step
      :ignored [st []]
      :done (done st)
      :dead-read (one (write st aid :done (dead-tx aid (:died f) (some-> (:db r) (pull-end aid)))))
      ;; root answers a spawn at its agent's hello, or at the end the agent met first: a death,
      ;; a failed spawn or a stop (each names the agent) is this run's death, its row
      ;; started first; the flight keeps the error's base and exit status, which `dead` records
      :spawned (if-let [ag (or (:id r) (when (#{:seon.agent/died :seon.agent/failed :seon.root/no-agent :seon.root/ended} (:seon.error/type r)) (:agent r)))]
                 (one (write (cond-> (assoc-in st [:flights aid :agent] ag)
                               (:seon.error/type r) (assoc-in [:flights aid :died-at-spawn]
                                                              (select-keys r [:seon.error/type :seon.error/operation :seon.error/message :exit :agent])))
                             aid :started
                             (task/run-start-tx aid (:task act) (:boot st) ag (:env act) (System/currentTimeMillis)
                                                   (:plan (task/accumulate (get-in st [:idx :tasks]) (:task act)))
                                                   (:key f))))
                 ;; root refused it (the branch is held, or too many live agents): the refused answer
                 ;; is the run, written on main with root's reason; the task starts again,
                 ;; as its next run, once main moves for another reason (:refused, pass)
                 (one (write (update st :refused (fnil conj #{}) (:task act)) aid :done
                             [(let [now (System/currentTimeMillis)]
                                (-> (first (task/run-start-tx aid (:task act) (:boot st) nil (:env act) now
                                                                  (:plan (task/accumulate (get-in st [:idx :tasks]) (:task act)))))
                                    (assoc :seon.run/status :refused :seon.run/end-ms now
                                           :seon.run/reason (:seon.error/message r) :seon.run/error r)))])))
      ;; an outside agent works this run (seon.server): no turn loop; it hands it back
      :started (cond
                 (:died-at-spawn f) (dead st (:died-at-spawn f))
                 (= "outside" (get-in act [:env :model]))
                 (done st)
                 :else
                 ;; a session's next stage: the boundary is a message in the session, root's, taken by
                 ;; its first turn like any other (the conversation above it unchanged, its prefix cached)
                 (if-let [body (stage-boundary (:db st) aid)]
                   (one (request st aid :briefed {:op :message :id agent :from :root :body body}))
                   (work st aid act agent)))
      :briefed (if (contract/error? r) (dead st r) (work st aid act agent))
      ;; the turn loop answered: died (no live agent), or judge it from measured facts
      ;; work! that threw was answered by the agent's REPL (the process lives): its error
      ;; names the run's end when the loop wrote none
      :worked (cond
                ;; a wake whose parked process had ended before the work! reached it (root's
                ;; view: ended or never there): the run took nothing, so it is lost, not a death, and
                ;; the task starts again as a spawn, a load of the session (design agent-loop section 4)
                (and (= :wake (:op act)) (#{:seon.root/ended :seon.root/no-agent} (:seon.error/type r)))
                (one (write st aid :done (cas-tx aid :running :lost {:seon.run/end-ms (System/currentTimeMillis)
                                                                     :seon.run/reason (str "its parked process was gone: " (:seon.error/message r))})))
                (contract/error? r) (dead st r)
                :else
                (one (request (cond-> st (:ex r) (assoc-in [:flights aid :worked-error] (:ex r)))
                              aid :tested {:op :judge :id agent})))
      :tested (if (contract/error? r)
                (dead st r)
                (one (request (-> st (assoc-in [:flights aid :results] (proofs/results r))
                                  (assoc-in [:flights aid :deferred] (:deferred r))
                                  (assoc-in [:flights aid :run-problems] {:failures (proofs/failures r) :costs (proofs/costs r)}))
                              ;; the branch read once: its head, its merge base, and what it changed
                              ;; since (three-way's :ours, measured for the octopus merge)
                              aid :diffed {:op :write :req {:op :branch-diff :branch (get-in act [:env :branch])}})))
      ;; root stops the agent because its run ended (its view says :ended, not :killed), unless
      ;; the run parked its session: then the process stays, idle, for the next message's run
      ;; (design agent-loop section 2: parking is work! returning)
      :diffed (cond
                (contract/error? r) (refused st r)
                (keeps? (:seon.task/kind (task/task (:db st) (:task act))) (:outcome (pull-end (:db r) aid))) (one (write st aid :done (judge-tx st f (:results f) r)))
                :else (let [[st o1] (request st aid :ignored {:op :kill :id agent :ended true})
                            [st o2] (write st aid :done (judge-tx st f (:results f) r))]
                        [st [o1 o2]]))
      ;; each member's answer, as one write: merged at the octopus merge's t, behind (its merge task
      ;; runs again, a new agent), or refused: a failed run like any other (held for root)
      ;; the merged members' namespaces that are Seon's own source are written back: main's
      ;; files after the octopus merge, committed on the merges branch (:git), the sha recorded on
      ;; each merged run (the self-improvement loop: the next generation builds it)
      :octopus-merged (let [merged (keep (fn [[m x]] (when (:merged? x) [m x])) (map vector (:members act) (:results r)))
                     paths (vec (sort (distinct (mapcat (comp :files second) merged))))
                     back? (and (:source st) (seq paths))]
                 (one (write (cond-> st back? (assoc-in [:flights aid :writeback] {:files paths :members (mapv first merged)}))
                             aid (if back? :write-back :done)
                           (vec (mapcat (fn [{:keys [run]} r]
                                          (let [problem (str "merge refused: " (:seon.error/message r))]
                                            (cond
                                              (:merged? r) (cas-tx run :ended :merged {:seon.run/merged-t (long (:tx r))})
                                              (= :seon.merge/behind (:seon.error/type r)) (cas-tx run :ended :behind {:seon.run/reason (:seon.error/message r)
                                                                                                                          :seon.run/error r})
                                              :else (conj (hold-tx (task/run (:db st) run) problem)
                                                          {:seon.run/id run :seon.run/error r}))))
                                        (:members act) (:results r))))))
      ;; the gate's candidate: the files of the namespaces the branch changed, as it holds them
      :gate-read (let [nss (sort (distinct (for [k (:seon.run/changed (task/run (:db st) aid))
                                                  :let [[kind x] (read-string k)]
                                                  :when (#{:var :form} kind)]
                                              (symbol (first (str/split (str x) #"/" 2))))))
                       file (fn [n] (:ns/file (d/entity (:db r) [:ns/name n])))
                       ;; a changed namespace with no file on the branch (made at the REPL, never
                       ;; written) cannot be put over the checkout: named, never dropped
                       unread (vec (for [n nss :when (nil? (file n))]
                                     {:ns n :reason "no :ns/file on the branch: its forms were never written to a file"}))
                       files (source/files (:db r) (vec (keep file nss)))]
                   (one (request (assoc-in st [:flights aid :gate-unread] unread) aid :gated
                                 {:op :fx :kind :tests :req {:repo (get-in st [:idx :root :gate-checkout]) :branch (:branch (:source st))
                                                             :files files :tests (:tests act)}})))
      ;; each deferred test's status on the run; the next judgment merges it or holds it. A
      ;; run over a tree missing a changed namespace (gate-unread) vouches for none: each :error
      :gated (let [unread (:gate-unread f)
                   rs (if (or (contract/error? r) (seq unread)) (zipmap (:tests act) (repeat :error)) (get-in r [:value :results]))]
               ;; a failed :tests effect is recorded here, with the results, in one transaction
               (one (write st aid :done (err/asked-tx (:db st) r :seon.run/deferred-results
                                                      (cond-> [{:seon.run/id aid
                                                                :seon.run/deferred-results (vec (for [[t s] rs] {:seon.proof/test t :seon.proof/status s}))}]
                                                        (seq unread) (conj {:seon.run/id aid :seon.run/gate-unread unread})
                                                        (contract/error? r) (conj {:seon.run/id aid :seon.run/error r}))))))
      :write-back (one (request st aid :main-read {:op :write :req {:op :branch-db :branch :db}}))
      :main-read (let [{:keys [members] paths :files} (:writeback f)
                       files (source/files (:db r) paths)]
                   (if (empty? files)
                     (done st)
                     (one (request st aid :committed
                                   {:op :fx :kind :git :req {:dir (:root (:source st)) :branch (:branch (:source st)) :files files
                                                             :message (source/commit-message members)}}))))
      ;; the sha on each merged run; the merge already runs where main's program runs
      ;; (seon.program/load-changes!), so nothing is built (BUILD plan P4)
      :committed (let [sha (get-in r [:value :sha])
                       m (cond sha {:seon.run/commit sha}
                               (contract/error? r) {:seon.run/commit-error (:seon.error/message r)})]
                   ;; a failed commit is recorded here, its row closed by the next commit's sha
                   (if m
                     (one (write st aid :done (err/asked-tx (:db st) r :seon.run/commit
                                                            (vec (for [{:keys [run]} (:members (:writeback f))] (assoc m :seon.run/id run))))))
                     (done st))))))

(defn- quiet
  "Report quiescence positively, once per quiet spell: when no flight is in the air and no
  run is live after a pass that started nothing, write main's :seon.root/quiescent (the
  t judged, and the root causes), and ask the writer to collect the store (:collect: it sweeps
  only when a branch is gone, and root refuses it while an agent is live). Their answers are
  not awaited (no flight), and the commits they make touch only root, which does not end the
  spell. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state [:vector :map]] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st outs]
  (if (or (:quiet st) (seq outs) (seq (:flights st)) (seq (:live (:idx st))))
    [st outs]
    (let [fact {:t (:t st) :root-causes (root-causes (:idx st))}
          [st q] (step/ask st [:reconciler :answers] nil {:op :write :req {:op :tx :branch :db :tx-data [{:seon.root/id :root :seon.root/quiescent fact}]}})
          [st c] (step/ask st [:reconciler :answers] nil {:op :write :req {:op :collect}})]
      ;; :report, the report's request until its answer: a wait is answered once main holds it
      [(assoc st :quiet true :quiescent fact :report (:tag q)) [q c]])))

(defn- answer-waits
  "Deliver each held wait on main (seon.root/await-db, through :quiet) whose :after the
  reconciler has seen, {:quiet t}, once quiet: nothing in flight, nothing live, nothing left to
  start, so nothing root runs will commit again until something outside it does; and the spell's
  report committed (its write answered), so a wait reads it. A wait asked before the reconciler
  saw main's head waits for that commit's pass. Answers st (an answer can
  come before the first commit, so st may hold no :db yet)."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (if (and (:quiet st) (nil? (:report st)) (empty? (:flights st)) (empty? (:live (:idx st))) (seq (:waits st)))
    (let [{ready true later false} (group-by #(<= (:after %) (:t st)) (:waits st))]
      (doseq [w ready] (deliver (:p w) {:quiet (:t st)}))
      (assoc st :waits (vec later)))
    st))

(defn- pass
  "Reconcile main's latest db and start each action whose run is not in flight; spawns
  wait while earlier spawns have not written their run yet (max-running counts facts),
  and a task root refused waits for main to move for another reason (if nothing has
  changed, asking again is refused again); then quiescence, when nothing is left to do.
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st]
  (let [early (count (filter #(and (= :spawn (get-in % [:act :op])) (not (:agent %))) (vals (:flights st))))
        acts (remove #(let [f (get-in st [:flights (:run %)])]
                        (or (and f (or (not= :kill (:op %)) (:killing f)))
                            (and (= :spawn (:op %)) (contains? (:refused st) (:task %)))))
                     (actions (:idx st)))
        acts (concat (remove #(= :spawn (:op %)) acts) (drop early (filter #(= :spawn (:op %)) acts)))]
    ;; an action started is work: the quiet spell ends (and a wait waits for it)
    (apply quiet (reduce (fn [[st outs] act] (let [[st o] (start (assoc st :quiet false) act)] [st (into outs o)])) [st []] acts))))

(defn reconciler-proc
  "The reconciler. In :main (main's commits {:t :ops :db}, a sliding buffer of one) and :answers (the
  api's answers to its requests). Out :to-api (its requests, as {:op :request ...}).
  Args: :boot (this root's boot id); in :judge (outside agents' runs handed back).
  Starts deepest-first (finish chains under way)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:boot "root's boot id"
                :judge "outside agents' runs to judge {:op :judge :run :task :env :agent}, and waits on main {:op :quiet :after t :p promise}, each answered at the first quiet at or after t (external; seon.root/await-db)"}
       :ins {:main "main's commits" :answers "the api's answers"} :outs {:to-api "requests"}
       :workload :io
       :ping-map-fn (fn [st] {:seen (:seen st 0) :lists (:lists st 0) :t (:t st) :flights (count (:flights st)) :pending (count (:pending st))
                              :quiet (boolean (:quiet st)) :root-causes (count (:root-causes (:quiescent st)))})})
  ([args] (assoc (dissoc args :judge) :seen 0 :rid 0 :flights {} :pending {} :waits []
                 ::flow/in-ports {:judge (:judge args)}))
  ;; a stop answers every wait still held: root stopped, nothing will answer it
  ([st transition]
   (if (= transition ::flow/stop)
     (do (doseq [w (:waits st)] (deliver (:p w) {:stopped true})) (assoc st :waits []))
     st))
  ([st in m]
   (case in
     ;; each commit's datoms move the index (observe); a commit the sliding buffer dropped
     ;; (main's t is not the next one) lists main again (index), k8s's relist after a gap
     :main (let [db (:db m) t (:max-tx db)
                 next? (and (:idx st) (= t (inc (:t st))))
                 idx (if next? (observe (:idx st) db (:ops m)) (index db))
                 ;; a commit touching tasks, runs or config keys ends a quiet spell (root's own
                 ;; quiescent fact does not)
                 work? (or (not next?) (some (fn [[_ _ a]] (and (keyword? a) (watched-ns (namespace a)) (not= "seon.root" (namespace a)))) (:ops m)))
                 ;; a commit holding only refused runs (the refused runs' own writes) changes
                 ;; nothing that refused them; any other work lets a refused task ask again
                 only-refused? (and next? (every? (fn [[_ e a]] (or (not (and (keyword? a) (watched-ns (namespace a))))
                                                               (= :refused (get-in idx [:runs (second (get-in idx [:keys e])) :seon.run/status]))))
                                              (:ops m)))
                 st (assoc st :db db :t t :idx idx :seen (inc (:seen st)) :lists (cond-> (:lists st 0) (not= t (inc (:t st -2))) inc)
                           :quiet (boolean (and (:quiet st) (not work?)))
                           :refused (if (and work? (not only-refused?)) #{} (:refused st #{})))
                 [st outs] (pass st)]
             [(answer-waits st) {:to-api (into [{:op :stat :k :main-seen-us :v (/ (- (profile/now-ns) (:ns m)) 1000.0)}] outs)}])
     ;; a wait on main (:op :quiet): answered now if the reconciler is quiet past its :after,
     ;; else held. Otherwise an outside agent handed its run back: judged as a turn loop's end is
     :judge (case (:op m)
              :quiet [(answer-waits (update st :waits (fnil conj []) (select-keys m [:after :p]))) {}]
              :judge (let [{:keys [run task env agent]} m
                  ;; a flight: the quiet spell ends, and a wait behind this judge waits for it
                  st (-> st (assoc-in [:flights run] {:act {:op :spawn :run run :task task :env env} :agent agent}) (assoc :quiet false))
                  [st o] (request st run :tested {:op :judge :id agent})]
              [st {:to-api [o]}]))
     :answers (let [st (cond-> st (= (:tag m) (:report st)) (dissoc :report))
                    [st [aid step]] (step/answered st (:tag m))
                    [st outs] (if aid (advance st aid step (:r m)) [st []])
                    ;; a flight that ended may free its run for the next action
                    [st more] (if (and aid (not (get-in st [:flights aid])) (:db st)) (pass st) [st []])]
                [(answer-waits st) {:to-api (into outs more)}]))))
