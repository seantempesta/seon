(ns seon.reconcile
  "The one reconciler (sessions R1 and M1's proto.agents.reconcile): a pure function of main's
  db value (desired state: the tasks; observed state: what root recorded on each task of its agent and how its
  work ended, proof results, root's boot) to the minimal actions that close the gap, and the done rule
  `done-explain` (one function, used by root to judge and by the agent to know when to
  stop). Root judges an agent's end once, when its facts are in hand (`judgment`), and stores the answer on the task
  (docs/reconcile.md#a-task-holds-how-its-work-ended). `reconciler-proc` is the root proc that runs the
  reconciler on main's commits and carries the actions out as requests to the api, each action's facts written
  by the writer."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.sock :as sock] [seon.step :as step]
            [seon.task :as task] [seon.proofs :as proofs] [seon.merge :as pm] [seon.program :as program] [seon.source :as source] [seon.doc :as doc]
            [datahike.api :as d] [seon.db :as pdb] [clojure.string :as str] [clojure.core.async.flow :as flow]
            [seon.config :as cfg] [seon.error :as err]))


(defn merge-limits
  "Task t's merge limits (:seon.config.merge/*), through the one resolver."
  {:malli/schema [:=> [:cat :seon.task/own] :seon.reconcile/merge-limits]}
  [t]
  {:max-conflicts (cfg/value :seon.config.merge/max-conflicts {:task t :model (:seon.task/model t)})
   :max-merge-turns (cfg/value :seon.config.merge/max-merge-turns {:task t :model (:seon.task/model t)})})

;; ---- the view: one read of main ---------------------------------------------------

(defn- outside?
  "Whether env, an agent's environment, is an outside agent's (seon.server's attach): no turn loop
  drives it, so it is handed back at :started and its refused end holds rather than taking a verdict."
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [env] (= "outside" (:model env)))

(defn desired-env
  "The environment task t's agent must work in; with prior, an agent's environment it goes on from,
  an outside agent's stays outside (seon.server's attach: the model and its :holder are the
  outside agent's, whatever the task's). Its :max-turns is the budget a new agent is made with
  (:seon.agent/max-turns), never compared with an agent's: raised, it is the agent's to keep (docs/turn.md#the-cap)."
  {:malli/schema [:function [:=> [:cat :seon.task/own] :seon.task/env] [:=> [:cat :seon.task/own [:maybe :map]] :seon.task/env]]}
  ([t] (desired-env t nil))
  ([t prior]
   (cond-> {:branch (:seon.task/branch t) :model (:seon.task/model t)
            :max-turns (cfg/value :seon.config.agent/max-turns {:task t :model (:seon.task/model t)})}
     (outside? prior) (merge (select-keys prior [:model :holder])))))

;; ---- the done rule -----------------------------------------------------------------

(defn done-explain
  "Why the facts measured at a task's end do not make it done; empty means done. Refused:
  no proofs (never proven); a proof not passing (red), or with no result (unknown: it
  never ran); a regression (a test green on main, the
  proofs of merged tasks, red here); a stray red (a red test that is no task's open
  proof); a test red on main too at the branch's merge base (main-red) is main's red, neither a regression
  nor a stray red: red before and red after, whatever the work did to it (docs/render.md#how-the-work-stands); a contract gap (a function its branch defined without a contract); an
  unmerged entry on the branch (a merge conflict not resolved); an open problem the
  branch's work made true (seon.problems/caused: a citation of a var it deleted). A :design task readies
  the interface and plan for its implement step, so its own proofs may stay red but not
  unknown (each written and run), and it is refused :no-design until its plan differs from
  the one its branch began with (its merge base's; or that one is unknown), and :no-proofs, as every task is, until
  it registers one (a design that only rewrote its plan was green at its first plan edit, its
  proofs named in prose and never written: the live agents' rounds). A deferred proof (a process test, run
  at the merge gate on a checkout, seon.reconcile's :gate) is neither red nor unknown
  here; while unrun it is the merge's open :unrun problem."
  {:malli/schema [:=> [:cat :seon.reconcile/done-input] [:vector :seon.reconcile/not-done]]}
  [{:keys [proofs results main-green allowed-red main-red uncontracted unmerged caused kind plan plan-before deferred behind]}]
  (let [red? (fn [t] (#{:fail :error} (get results t)))
        design? (= :design kind)]
    (vec (concat
           ;; judged before it has merged main: its proofs ran on a program main has moved from, so the
           ;; reasons below may be only that (a proof unknown, a regression); the fix comes first
           (when (some-> behind pos?) [{:reason :behind :behind behind}])
           (when (empty? proofs) [{:reason :no-proofs}])
           (when (and design? (or (nil? plan-before) (= plan plan-before))) [{:reason :no-design}])
           ;; a proof with no result never ran: unknown, not red (v1's reading); a design
           ;; leaves its proofs red, never unknown (the implement step needs them written)
           (for [p (sort-by str proofs) :let [s (get results p)] :when (and (not (contains? deferred p)) (if design? (nil? s) (not= :pass s)))]
             {:reason (if s :proof-red :proof-unknown) :symbol p})
           (for [t (sort-by str main-green) :when (and (not (proofs t)) (red? t) (not (contains? main-red t)))] {:reason :regression :symbol t})
           (for [t (sort-by str (keys results))
                 :when (and (red? t) (not (proofs t)) (not (main-green t)) (not (allowed-red t)) (not (contains? main-red t)))]
             {:reason :stray-red :symbol t})
           (for [s (sort-by str uncontracted)] {:reason :contract-gap :symbol s})
           (for [k (sort unmerged)] {:reason :unmerged :key k})
           (for [k (sort caused)] {:reason :open-problem :key k})))))

(defn hairy
  "Is a merge too hairy to keep resolving, from measured facts: more conflicts than the
  config key; main deleted a var the task references (refs: its proofs and the names its plan's
  cites hold, facts its write derived, seon.task/task-cites); more model turns without green than
  the config key. Answers {:rule :reason} or nil. The merge agent stops early on it; the
  reconciler decides from the run's facts alone, never re-reading the plan's text with a parser
  the agent may have changed (docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map)."
  {:malli/schema [:=> [:cat [:map [:conflicts [:sequential [:map [:key :any] [:kind :keyword]]]]
                             [:refs [:set :qualified-symbol]]
                             [:merge-turns :int] [:max-conflicts :int] [:max-merge-turns :int]]]
                  [:maybe [:map [:rule [:enum :too-many-conflicts :deleted-by-them-referenced :merge-turns]] [:reason :string]]]]}
  [{:keys [conflicts refs merge-turns max-conflicts max-merge-turns]}]
  (let [deleted (for [{:keys [key kind]} conflicts
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
  [rs] (str/join "; " (map #(str (name (:reason %)) (some->> (or (:symbol %) (:key %) (:behind %)) (str " "))) rs)))

;; ---- the incremental reconciler: an index watched through main's tx reports --------------
;; k8s's informer + W1's engine: list once (index), then each commit's resolved datoms name the
;; task, agent and root entities it touched (observe); only those are re-read (one pull
;; each) and only the tasks they reach are re-derived: the task itself, the tasks of its
;; agent (an agent holds two on a stage), and the tasks that need it (the reverse :needs index),
;; transitively for a needs change. `actions` derives the same actions as the full view from the
;; index alone; it walks only the tasks with an agent in flight (running, judged) and the head of the
;; ready set, so a pass costs what changed plus what is in flight, never the number of tasks.

(def ^:private watched-ns
  #{"seon.task" "seon.root" "seon.config.agent" "seon.config.merge" "seon.message"})

(def ^:private watched-attrs
  "The agent attributes the index reads (seon.task/agent-value-attrs): its boot, the messages it had taken and its environment.
  A start of a session's next task rewrites the environment, which a boot unchanged does not move."
  task/agent-value-attrs)

(defn- watched?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a] (boolean (and (keyword? a) (or (contains? watched-attrs a) (contains? watched-ns (namespace a))))))

(def park-outcomes
  "An agent's own ends that park its session instead of ending it (docs/turn.md#parked): a
  reply with no eval (waiting), its turn budget spent (cap). The task is written :parked (:cap, for the
  budget), its process stays, and the next message to the session wakes it. An error is not one: it holds the
  task with its cause, as any failed end does."
  #{:waiting :cap})

(defn- branch-messages
  "How many messages main holds to the agent on branch br: what a parked agent on br is woken
  by when it has taken fewer (:seon.agent/taken). O(the messages to br)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] :int]}
  [db br]
  (or (d/q '[:find (count ?m) . :in $ ?b :where [?m :seon.message/branch ?b]] db br) 0))

(def empty-index
  "The index of a db with no tasks."
  {:root nil :tasks {} :keys {} :needed-by {} :acc {} :depth {} :claims {}
   :agents {} :done {} :ready (sorted-set) :ready-key {}
   :live #{} :reap #{} :kill {} :judge #{} :merging #{} :msgs {}})

(def ^:private finished task/finished)
(def ^:private judged-outcomes task/judged)

(defn- task-agent
  "Task id's agent as the index holds it (the entity its start named, seon.task/start-tx), or nil before one."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe :seon.reconcile/agent]]}
  [idx id] (get-in idx [:agents (get-in idx [:tasks id :seon.task/agent])]))

(defn- live-in?
  "Whether task id's agent works it in root's boot now: the task is :running, started in this boot."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id]
  (boolean (and (= :running (get-in idx [:tasks id :seon.task/outcome])) (= (:boot (:root idx)) (:seon.agent/boot (task-agent idx id))))))

(defn- idle
  "Task id's agent when its work is parked on its live process: parked or at its cap in this boot, by an agent (the
  judge parks only a task whose process is kept, keeps?). A wake reuses it; a cancel, a hold or a new branch for its
  task stops it (classify: its process works only the branch it was forked on). nil otherwise."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe :seon.reconcile/agent]]}
  [idx id]
  (let [a (task-agent idx id)]
    (when (and a (#{:parked :cap} (get-in idx [:tasks id :seon.task/outcome])) (= (:boot (:root idx)) (:seon.agent/boot a)))
      a)))

(defn- keeps?
  "Whether a task of kind, whose agent ended with outcome, keeps its agent's process at its end (:diffed):
  the agent ended on a park outcome, and the task is no merge (a merge's agent ends its
  process; a whole task, which merges too (seon.task/merges?), keeps its process, since its
  own work is what parked). Its judge then parks it, or stops the kept process (stop-kept)."
  {:malli/schema [:=> [:cat [:maybe :keyword] [:maybe :keyword]] :boolean]}
  [kind outcome] (boolean (and (park-outcomes outcome) (not= :merge kind))))

(defn- done-info
  "{:any? (merged or accepted) :merged-t (when merged)} of task id."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:map [:any? :boolean] [:merged-t [:maybe :int]]]]}
  [idx id]
  (let [t (get-in idx [:tasks id])]
    {:any? (boolean (finished (:seon.task/outcome t)))
     :merged-t (when (= :merged (:seon.task/outcome t)) (:seon.task/merged-t t 0))}))

(defn- idx-done? {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id] (boolean (get-in idx [:done id :any?])))

(defn- idx-ready?
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id]
  (let [t (get-in idx [:tasks id]) o (:seon.task/outcome t)]
    (boolean (and t (= :running (:seon.task/execution t)) (not (idx-done? idx id))
                  (every? #(idx-done? idx %) (:seon.task/needs t))
                  (not (live-in? idx id)) (not (judged-outcomes o))
                  ;; at its cap an agent waits for its budget to be raised and its task released (my.root/run!), whatever comes
                  (not= :cap o)
                  ;; parked or told: ready again only when main holds more messages to its agent than it had taken
                  ;; (docs/turn.md#parked); judge-tx writes :seon.agent/taken at every judging, both count the messages
                  ;; to the agent's branch
                  (or (not (#{:parked :told} o))
                      (let [a (task-agent idx id)]
                        (> (get-in idx [:msgs (:branch (:seon.agent/env a))] 0) (:seon.agent/taken a 0))))))))

(defn- refresh-done
  "Re-derive task id's done state from its outcome; answers [idx flipped?]."
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

(defn- put-agent
  {:malli/schema [:=> [:cat :seon.reconcile/index :string [:maybe :seon.reconcile/agent]] :seon.reconcile/index]}
  [idx k a]
  (assoc idx :agents (if a (assoc (:agents idx) k a) (dissoc (:agents idx) k))))

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
  "Task id's place in the sets actions reads: live, reap, kill (with the reason), judge
  (ready or at its gate), merging (a merge task live or judged, a whole task judged)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :seon.reconcile/index]}
  [idx id]
  (let [t (get-in idx [:tasks id])
        a (task-agent idx id)
        o (:seon.task/outcome t)
        live? (and t (live-in? idx id))
        ;; its agent's process is parked, and stays until its task is cancelled or held (docs/turn.md#parked)
        parked? (and t (some? (idle idx id)))
        env (:seon.agent/env a)
        kill (cond
               live? (cond (= :cancelled (:seon.task/execution t)) :cancelled
                           (= :held (:seon.task/execution t)) :held
                           ;; its budget is the agent's own and not compared (desired-env)
                           (not= (select-keys (desired-env t env) [:branch :model]) (select-keys env [:branch :model])) :env-changed)
               parked? (cond (= :cancelled (:seon.task/execution t)) :cancelled
                             (= :held (:seon.task/execution t)) :held
                             (not= (:seon.task/branch t) (:branch env)) :env-changed))
        mark (fn [idx k on?] (update idx k (if on? conj disj) id))]
    (-> idx
        (mark :live live?)
        (mark :reap (and t (= :running o) (not live?)))
        (update :kill #(if kill (assoc % id kill) (dissoc % id)))
        (mark :judge (boolean (and t (judged-outcomes o))))
        ;; a merge task merges while it works; a whole one (its own work first) once it is judged
        (mark :merging (and t (case (:seon.task/kind t) :merge (or live? (judged-outcomes o)) :whole (boolean (judged-outcomes o)) false))))))

(defn- settle
  "Re-derive after entities changed: tasks whose needs or proofs changed (and the tasks that
  need them, transitively) get depth and accumulated proofs again in dependency order; each
  dirty task's done state is re-derived (it depends on its own outcome only); then readiness
  of the dirty tasks and of the tasks needing one whose done state flipped."
  {:malli/schema [:=> [:cat :seon.reconcile/index [:set :seon.task/id] [:set :seon.task/id]] :seon.reconcile/index]}
  [idx needs-changed dirty]
  (let [order (topo idx needs-changed)
        idx (reduce derive-needs idx order)
        dirty (into dirty order)
        [idx flipped] (reduce (fn [[idx fl] id] (let [[idx f?] (refresh-done idx id)] [idx (cond-> fl f? (conj id))])) [idx []] dirty)]
    (reduce classify
            (reduce refresh-ready idx (into dirty (mapcat #(get-in idx [:needed-by %])) flipped))
            dirty)))

(defn observe
  "The index after main's commit: ops are the commit's resolved datoms ([op e a v]), db the
  db after it. Re-reads only the entities the commit touched."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.host/db [:sequential :any]] :seon.reconcile/index]}
  [idx db ops]
  (let [es (into #{} (keep (fn [[_ e a]] (when (watched? a) e))) ops)
        ;; a message to a branch's agent: its count moves by one (a commit's op names the branch;
        ;; a diff's op may name only the message, so its branch is counted again), and the tasks
        ;; on that branch are re-derived (the session its agent works)
        msgs (keep (fn [[op e a v]] (when (= :seon.message/branch a)
                                      {:br (or v (:seon.message/branch (d/entity db e))) :d (when v (if (= :db/retract op) -1 1))}))
                   ops)
        idx (reduce (fn [idx {:keys [br d]}] (if d (update-in idx [:msgs br] (fnil + 0) d) (assoc-in idx [:msgs br] (branch-messages db br))))
                    idx (filter :br msgs))
        es (into es (for [br (into #{} (keep :br) msgs), [id t] (:tasks idx)
                          :when (= br (:seon.task/branch t))
                          :let [e (:db/id (d/entity db [:seon.task/id id]))] :when e]
                      e))]
    (if (empty? es)
      idx
      (let [step (fn [[idx nc dirty all?] e]
                   (let [[kind v :as ev] (task/entity-value db e)
                         [okind oid] (get-in idx [:keys e])]
                     (case (or kind okind)
                       :task (let [id (or (:seon.task/id v) oid)
                                   needs? (not= (get-in idx [:tasks id :seon.task/needs]) (:seon.task/needs v))]
                               [(-> idx (put-task id v) (update :keys #(if v (assoc % e [:task id]) (dissoc % e))))
                                (cond-> nc (or needs? (nil? (get-in idx [:acc id])) (not= (get-in idx [:tasks id :seon.task/proofs]) (:seon.task/proofs v))) (conj id))
                                (conj dirty id) all?])
                       ;; an agent's boot or taken count moved: the tasks that name it (an agent on a stage holds two) are re-derived
                       :agent (let [k (or (:seon.agent/key v) oid)]
                                [(-> idx (put-agent k v) (update :keys #(if v (assoc % e [:agent k]) (dissoc % e))))
                                 nc (into dirty (keep #(:seon.task/id (d/entity db %))) (task/tasks-of-agent db e)) all?])
                       :root [(-> idx (assoc :root v) (assoc-in [:keys e] [:root :root]))
                              nc dirty (or all? (not= (:boot (:root idx)) (:boot v)))]
                       [idx nc dirty all?])))
            [idx nc dirty all?] (reduce step [idx #{} #{} false] (sort es))]
        ;; a new boot changes which tasks are live: every task is re-derived (once per boot)
        (settle idx nc (if all? (set (keys (:tasks idx))) dirty))))))

(defn index
  "The index of db, listed: observe of every task, agent and root entity at once. Given idx,
  the index of db from, the index of db observed from it over the datoms of the watched
  attributes that differ (datahike.api/datoms-diff of each attribute's range, which skips the
  stored subtrees both share): the cost of the watched datoms and what changed, never of the
  program's (task seon.turn/context-render-observes-what-changed). Given moved too, the datoms
  that differ between from and db that the caller read already (one :eavt diff), the watched ones
  among them: no diff of its own, where each watched attribute's descent costs a fixed ~160 KB
  (docs/cost.md#a-diff-per-attribute-costs-a-descent-each)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map]
                  [:=> [:cat :seon.reconcile/index :seon.host/db :seon.host/db] :map]
                  [:=> [:cat :seon.reconcile/index :seon.host/db :seon.host/db [:sequential :seon.host/datom]] :map]]}
  ([db] (observe empty-index db (concat (for [e (task/entity-ids db)] [:db/add e :seon.task/id nil])
                                        (for [dt (d/datoms db :aevt :seon.message/branch)] [:db/add (:e dt) :seon.message/branch (:v dt)]))))
  ([idx from db]
   (index idx from db (for [a (sort (into #{} (filter watched?) (concat (keys (:schema from)) (keys (:schema db)))))
                            dt (apply concat (d/datoms-diff from db :aevt [a]))]
                        dt)))
  ([idx _from db moved]
   (observe idx db (for [dt moved :let [a (:a dt)] :when (watched? a)]
                     [:db/add (:e dt) a]))))

(defn done-input
  "The done rule's input for the results of task id's end, observed against main at t basis
  (nil: main as idx holds it), over the index: the regression and allowed-red sets restricted to the tests
  that ran (the only ones done-explain asks about), each test looked up by its claimants; for
  a :design task, its accumulated plan now and plan-before, the one its branch began with
  (its merge base's; nil: unknown); deferred, the process tests its end left to root's gate."
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
  "The program keys merge task id merges: what the tasks on its branch measured as changed
  (:seon.task/changed of its own and of the tasks it needs on the same branch),
  or nil when none measured it (unknown: it overlaps every merge)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe [:set :string]]]}
  [idx id]
  (let [br (get-in idx [:tasks id :seon.task/branch])
        ts (cons id (filter #(= br (get-in idx [:tasks % :seon.task/branch])) (get-in idx [:tasks id :seon.task/needs])))
        cs (keep #(:seon.task/changed (get-in idx [:tasks %])) ts)]
    (when (seq cs) (into #{} cat cs))))

;; ---- the end, judged once ---------------------------------------------------------------

(defn judgment
  "How task id's agent's end stands, decided once, where its facts are in hand (judge-tx; the gate's answer, gated),
  from the index and facts, a map of: :end (the agent's own end, {:outcome :reason}, or nil: it wrote none, :no-end), :died
  (the error an agent that died left), :execution (the task's now), and the done rule's measured input (:results :uncontracted
  :unmerged :caused :behind :plan-before :basis :deferred), :behinds (how many times the task was already judged :behind) and
  :told (the reasons last told, the task's own). Answers {:outcome the work's state next (:seon.task/outcome) :reasons the done
  rule's :hold a problem when the task is held for root :verdict the reasons to tell its agent when they are}; the reconciler's
  actions read the answer stored, never derive it again. See docs/reconcile.md#a-task-holds-how-its-work-ended."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id :map]
                  [:map [:outcome :seon.task/outcome] [:reasons [:vector :seon.reconcile/not-done]]
                   [:hold {:optional true} :string] [:verdict {:optional true} [:vector :seon.reconcile/not-done]]]]}
  [idx id {:keys [end died results uncontracted unmerged caused behind plan plan-before basis deferred behinds told]}]
  (let [t (get-in idx [:tasks id]) kind (:seon.task/kind t) merge? (task/merges? kind) oc (:outcome end) execution (:seon.task/execution t)
        outside (outside? (:seon.agent/env (task-agent idx id)))]
    (cond
      (not= :running execution) {:outcome :closed :reasons []}
      died {:outcome :dead :reasons [] :hold (str "its agent died" (some->> (:seon.error/message died) (str ": ")))}
      :else
      (let [rs (done-explain (cond-> (cond-> (assoc (done-input idx id results uncontracted unmerged basis plan-before (set deferred))
                                              :caused (set caused))
                                (and plan (= :design kind)) (assoc :plan plan))
                               ;; behind is the work's own to fix only when no merge task follows it: a merge task
                               ;; merges main into the branch and the gate tests what it writes (seon.template's
                               ;; merge-decl), so a green work task is never judged behind; a merge that main moves under is the merge
                               ;; task's, held at :seon.config.merge/max-behind (the :octopus-merged step). Told again, a green task behind a
                               ;; busy main was held at that bound (swarm held sw/s8 and sw/s9 so, 2026-10-31)
                               (and (some-> behind pos?) (not-any? #(= :merge (get-in idx [:tasks % :seon.task/kind])) (get-in idx [:needed-by id])))
                               (assoc :behind behind)))
            ended (str "ended" (some->> oc name (str " ")) (some->> (:reason end) (str " (") (#(str % ")"))) ": " (explain-text rs))]
        (cond
          ;; the process tests the branch's change reaches run before its merge, on a checkout (:gate, seon.fx/run-tests),
          ;; nobody asking (owner's ruling, 2026-10-27: process tests the change reaches run automatically at the judge)
          (and (empty? rs) merge?)
          (let [self (get-in idx [:root :started-by])
                ;; a test cannot be its own gate (:seon.root/started-by): left out, with no result recorded
                deferred (vec (sort-by str (remove #(= self (str %)) deferred)))]
            (cond (empty? deferred) {:outcome :ready :reasons rs}
                  ;; with no checkout to run them on (a root with no source), the merge is held with that reason, never merged with them unrun
                  (nil? (get-in idx [:root :gate-checkout]))
                  {:outcome :closed :reasons rs :deferred deferred
                   :hold (str "its change reaches process tests (" (str/join ", " deferred)
                              ") and this root has no checkout to run them on (:seon.config.source/gate-checkout, or a source root)")}
                  :else {:outcome :gate :reasons rs :deferred deferred}))
          (empty? rs) {:outcome :done :reasons rs}
          ;; it parked (waiting, out of turns): never a hold, the session waits for its next message (docs/turn.md#parked)
          (keeps? kind oc) {:outcome (if (= :cap oc) :cap :parked) :reasons rs}
          ;; its end refused by the done rule (the agent's own check, or root's facts that its branch did not show): words
          ;; to the agent, with what root judged on, which wakes the session; the same verdict twice holds. A whole task's
          ;; goes back to its own session too (it merges itself, so it is told why it could not); a merge task's holds,
          ;; and an outside agent's: it read the verdict as :done's answer, and no turn loop takes messages
          (and (not= :merge kind) (not outside) (#{:green :red} oc))
          (let [behind-only? (every? #(= :behind (:reason %)) rs)
                ;; behind alone is main moving under the branch, not the agent's news: the same count twice is two different
                ;; mains, so it is told again, merging main first, until :seon.config.merge/max-behind judgments of the task
                again? (and behind-only? (< behinds (cfg/value :seon.config.merge/max-behind)))]
            (if (and (= told rs) (not again?))
              {:outcome :closed :reasons rs :hold (str "the same verdict again: " (explain-text rs))}
              {:outcome (if behind-only? :behind :told) :reasons rs :verdict rs}))
          :else {:outcome :closed :reasons rs :hold ended})))))

(defn actions
  "The actions for index idx, in the order root executes them (the full view's rule): reap,
  kill, close, gate, then the octopus merge of the ready, then start ready tasks up to max-running,
  merges up to max-merging. Reads the index's sets and the stored answer of each judged task
  (`judgment` decided it once, at its end): its cost is the tasks to reap, kill and judge plus the starts."
  {:malli/schema [:=> [:cat :seon.reconcile/index] :seon.reconcile/actions]}
  [idx]
  (let [root (merge {:max-running (cfg/value :seon.config.agent/max-running) :max-merging (cfg/value :seon.config.merge/max-running)} (:root idx))
        reaps (for [id (sort (:reap idx))] {:op :reap :task id})
        kills (for [[id r] (sort-by key (:kill idx))] {:op :kill :task id :reason r})
        ;; a judged task (a merge ready, or at its gate); the rest of what judging decided was written at its end
        judged (for [id (sort (:judge idx))
                     :let [t (get-in idx [:tasks id])]]
                 (cond
                   (not= :running (:seon.task/execution t)) {:op :close :task id}
                   (= :gate (:seon.task/outcome t)) {:op :gate :task id :tests (vec (sort-by str (remove #(= (get-in idx [:root :started-by]) (str %)) (:seon.task/deferred t))))}
                   :else {:op :ready-to-merge :task id}))
        ;; the octopus merge (bors, GitHub's merge queue): ready merge tasks merge together in
        ;; one commit once no merge task is still working, so every member merged the same
        ;; main; an octopus merge closes when its first member is judged (no new member joins until it
        ;; merges, so merging cannot starve)
        ready (filter #(= :ready-to-merge (:op %)) judged)
        live-merge? (some (:live idx) (:merging idx))
        ;; one gate at a time, while no merge is in flight (it runs on the merges branch's head)
        gates (when-not live-merge? (take 1 (filter #(= :gate (:op %)) judged)))
        judged (remove #(#{:ready-to-merge :gate} (:op %)) judged)
        octopus (when (and (seq ready) (not live-merge?))
                [{:op :octopus-merge :task (:task (first ready)) :members (mapv #(select-keys % [:task]) ready)}])
        closed? (some #(judged-outcomes (get-in idx [:tasks % :seon.task/outcome])) (:merging idx))
        room (max 0 (- (:max-running root) (- (count (:live idx)) (count (filter (:live idx) (keys (:kill idx)))))))
        merge? (fn [id] (= :merge (get-in idx [:tasks id :seon.task/kind])))
        merge-room (max 0 (- (:max-merging root) (count (:merging idx))))
        ;; a merge joins only when what it changes is disjoint from every merge in flight;
        ;; one whose changes are unknown runs alone; conflicting merges serialize
        flying (map #(merge-keys idx %) (:merging idx))
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
        spawns (for [id starts :let [t (get-in idx [:tasks id]) p (idle idx id) prior (task-agent idx id)
                                     ;; work a root restart lost (or this pass reaps: an earlier boot's) was ended
                                     ;; by nobody: an outside agent stays outside
                                     lost? (or (= :lost (:seon.task/outcome t)) (contains? (:reap idx) id))
                                     env (desired-env t (when lost? (:seon.agent/env prior)))]]
                 ;; its process is idle (parked in this boot, on its branch): its next work is a wake on it, no fork
                 (if (and p (not (get-in idx [:kill id])))
                   {:op :wake :task id :env env :agent (:seon.agent/id p)}
                   {:op :spawn :task id :env env}))]
    (vec (concat reaps kills judged gates octopus spawns))))

;; ---- held for root, and the root causes at quiescence -----------------------------------

(defn blocks
  "The released tasks not done that need task id, transitively, sorted."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:vector :seon.task/id]]}
  [idx id]
  (vec (sort (filter #(and (= :running (get-in idx [:tasks % :seon.task/execution])) (not (idx-done? idx %)))
                     (disj (downstream idx #{id}) id)))))

(defn lost-write-back
  "The :seon.git/commit-failed error a merged task t holds (its write-back was refused and no commit
  has held its files since), or nil."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [t]
  (let [e (:seon.task/error t)]
    (when (and (= :merged (:seon.task/outcome t)) (= :seon.git/commit-failed (:seon.error/type e))) e)))

(defn root-causes
  "Why released work is not done, as root causes: each task held for root with a failure
  (its work refused, its agent dead, its merge refused, or marked failed: its reason),
  each task whose root refused to spawn (its reason: the branch is held, or
  too many live agents), each task whose agent parked its session (its outcome: it waits
  for a message, which wakes it), and each other task not released (cancelled, held) that released
  work needs, with the released tasks it blocks, and each merged task whose write-back failed (its
  :seon.git/commit-failed error: no commit holds its files). Read once at quiescence: one pass over the
  tasks."
  {:malli/schema [:=> [:cat :seon.reconcile/index] [:vector :seon.reconcile/root-cause]]}
  [idx]
  (vec (concat (for [[id t] (sort-by key (:tasks idx))
             :when (not (idx-done? idx id))
             :let [ex (:seon.task/execution t)
                   o (:seon.task/outcome t)
                   f (when (= :held ex) (:seon.task/failure t))
                   refused (when (= :refused o) t)
                   parked (when (#{:parked :told :cap} o) t)
                   stopped (or refused parked)
                   bs (blocks idx id)]
             :when (cond (= :running ex) stopped f true :else (seq bs))]
         (cond-> {:task id :cause (cond f (:reason f) refused (:seon.task/reason refused)
                                        parked (str "parked: " (name o) ", waiting for a message")
                                        :else (name ex))
                  :blocks bs}
           (:agent f) (assoc :agent (:agent f))
           (and stopped (:seon.task/agent t)) (assoc :agent (:seon.task/agent t))))
               ;; a merged task whose source files no commit holds: its write-back failed (my.root/write-back!)
               (for [[id t] (sort-by key (:tasks idx)) :let [e (lost-write-back t)] :when e]
                 {:task id :blocks []
                  :cause (str "write-back lost: " (:seon.error/message e) " [" (str/join ", " (:seon.git/files e)) "]; (my.root/write-back! sys [\"" id "\"]) writes it again")}))))

(defn commit-tx
  "Main's write of the :git effect's answer r for the tasks of members, which wanted paths written
  and gave the effect those in written: a sha is :seon.task/commit on each, clears the
  :seon.git/commit-failed error an earlier failure left (beside any note of forms that name no
  file, which stays), and holds a new one for the paths main had no text for (wanted, not written);
  a failure is that error for every wanted path, as the task's :seon.task/error (root-causes names it
  until a commit holds the paths), and its :effect row (seon.error/asked-tx), closed by the next
  commit's sha. Nil when r is neither. The reconciler's write-back and my.root/write-back! write it
  alike."
  {:malli/schema [:=> [:cat :seon.host/db :map [:sequential :map] [:sequential :string] [:sequential :string]] [:maybe [:vector :any]]]}
  [db r members paths written]
  (let [sha (get-in r [:value :sha])
        failed (fn [msg ps] (contract/error :seon.git/commit-failed #'seon.reconcile/commit-tx msg {:seon.git/files (vec (sort ps))}))
        missing (remove (set written) paths)
        error (cond (contract/error? r) (failed (:seon.error/message r) paths)
                    (and sha (seq missing)) (failed (str "main holds no text for " (str/join ", " (sort missing))) missing))]
    (when (or sha error)
      (err/asked-tx db r :seon.task/commit
                    (vec (mapcat (fn [{:keys [task]}]
                                   (let [old (:seon.task/error (d/pull db [:seon.task/error] [:seon.task/id task]))]
                                     (cond-> (if sha [{:seon.task/id task :seon.task/commit sha}] [])
                                       (and sha (= :seon.git/commit-failed (:seon.error/type old))) (conj [:db/retract [:seon.task/id task] :seon.task/error old])
                                       error (conj {:seon.task/id task :seon.task/error error}))))
                                 members))))))

(defn- unfiled-error
  "The commit-error of a merged member r (seon.writer/octopus-merge's result) for its changed forms
  that name no source file (:unfiled): each namespace and the definitions it changed."
  {:malli/schema [:=> [:cat [:map [:tx :int] [:unfiled [:map-of {:gen/max 3} :symbol [:vector {:gen/max 3} :seon.merge/def-key]]]]] :string]}
  [r]
  (str "merged at t " (:tx r) "; these changed forms name no source file to write back: "
       (str/join "; " (for [[n ds] (:unfiled r)] (str n " " (str/join ", " (map sedn/encode ds)))))))

(defn evidence
  "The measured evidence of task id held for root: its conflicts and unmerged keys (from its judging; none for a death), the
  proofs not passing in root's own test run (red), and the problem the reconciler stated (nil: none)."
  {:malli/schema [:=> [:cat :seon.task/id [:map [:conflicts {:optional true} [:maybe [:sequential :any]]] [:unmerged {:optional true} [:maybe [:or [:sequential :string] [:set :string]]]]
                                         [:red {:optional true} [:sequential :qualified-symbol]]] [:maybe :string]] :seon.task/evidence]}
  [id {:keys [conflicts unmerged red]} problem]
  (cond-> {:from id :conflicts (vec conflicts) :unmerged (vec unmerged) :red (vec (sort-by str red))}
    problem (assoc :problem problem)))

(defn verdict-body
  "A :verdict message's body: the done rule's reasons and the facts root judged on, as data
  the agent reads (its results, the tests deferred to the merge, what its
  branch left unmerged or caused, main's t), so what root saw and its branch did not is
  in front of it."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/not-done] :map] :string]}
  [reasons {:keys [results deferred unmerged caused basis]}]
  (sedn/encode (cond-> {:reasons reasons :results (or results {})}
                 (seq deferred) (assoc :deferred (set deferred) :deferred-results {})
                 (seq unmerged) (assoc :unmerged (vec unmerged))
                 (seq caused) (assoc :caused (set caused))
                 basis (assoc :main-t basis))))

(defn hold-tx
  "Task id's work failed (the done rule refused it, its agent died, or its merge was refused): its task held
  for root with the failure (problem, its agent) and the measured evidence (facts: conflicts, unmerged, red), exactly as
  my.task/fail!'s failure holds it, beside move, the write of its outcome (seon.task/outcome-tx, move-tx), in one write.
  Root decides: release it again (my.root/run!: the next agent continues its branch, from the evidence), edit it, or cancel it
  (DECISIONS, No automatic retries: a refused or failed end is held for root). The hold is a
  compare-and-swap from :running, so a cancel! that merged first refuses it (the task is
  then closed by the judged rule, its task not running)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id [:sequential :any] :string :map] [:vector :any]]}
  [db id move problem facts]
  (let [agent (some-> (d/entity db [:seon.task/id id]) :seon.task/agent :seon.agent/key)]
    (into (vec move)
          [[:db/cas [:seon.task/id id] :seon.task/execution :running :held]
           {:seon.task/id id :seon.task/failure (cond-> {:reason problem} agent (assoc :agent agent))
            :seon.task/evidence (evidence id facts problem)}])))

(defn quiescent
  "Main's last quiescence report on db ({:t :root-causes}, written by the reconciler proc), or nil."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.root/quiescent]]}
  [db]
  (:seon.root/quiescent (d/pull db [:seon.root/quiescent] [:seon.root/id :root])))

(defn last-commit
  "The merges commit of db's last merged task (the write-back's sha), or nil."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :string]]}
  [db]
  (some->> (d/q '[:find ?t ?c :where [?e :seon.task/commit ?c] [?e :seon.task/merged-t ?t]] db) (sort-by first) last second))

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
  on the task. The one derivation of a task's state."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :seon.task/state]}
  [idx id]
  (let [x (get-in idx [:tasks id :seon.task/execution])]
    (cond (= :cancelled x) :cancelled
          (idx-done? idx id) (if (get-in idx [:done id :merged-t]) :merged :done)
          (= :running (get-in idx [:tasks id :seon.task/outcome])) :running
          (seq (blocked-by idx id)) :blocked
          (= :held x) (if (get-in idx [:tasks id :seon.task/failure]) :failed :held)
          :else :open)))

;; ---- what an agent wrote on its branch ----

(defn pull-end
  "The end agent k's turn loop wrote on branch db ({:outcome :reason :error :waste}), or nil: it wrote none. An end names
  its turn (:seon.agent/ended-turn): one older than the agent's turn now (a wake that ran turns after it and died before
  it wrote its own) is the previous wake's, no end of this one (docs/turn.md#the-end)."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :map]]}
  [db k]
  (let [a (d/pull db [:seon.agent/outcome :seon.agent/reason :seon.agent/ended-turn :seon.agent/error :seon.agent/waste :seon.agent/turn] [:seon.agent/key k])]
    (when (and (:seon.agent/outcome a) (= (:seon.agent/ended-turn a) (:seon.agent/turn a 0)))
      (into {} (map (fn [[kw v]] [(keyword (name kw)) v])) (dissoc a :seon.agent/turn)))))

;; ---- the reconciler proc: actions carried out as requests, every answer as data ----------
;; Root's executor (R1's proto.agents.root, a thread per task) as one step-fn: main's
;; commits arrive in order through a blocking buffer (:seon.config.root/reconciler-buf: it reads
;; every commit's datoms); each action is a flight, a short chain of
;; requests to the api (spawn, eval, tests, the writer's reads and writes, kill), each answer
;; arriving on :answers keyed by its request id. A flight is its task's; an action whose task is in flight is not
;; started twice.

(def ^:private refusal-attrs
  "The attributes a refusal writes on its task (seon.task/refused-tx: the outcome, and the judged attributes it sets and retracts)."
  (conj (set task/judged-attrs) :seon.task/outcome))

(defn- request
  "Send req to the api as this proc's request: the answer comes back on :answers tagged by a
  new request id, which then names step of task id's flight. Answers [st output]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.task/id :seon.reconcile/step :map] [:tuple :seon.reconcile/state :map]]}
  [st id step req] (step/ask st [:reconciler :answers] [id step] req))

(defn- write
  "A write on main through the writer, as step of task id's flight."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.task/id :seon.reconcile/step [:sequential :any]] [:tuple :seon.reconcile/state :map]]}
  [st id step tx] (request st id step {:op :write :req {:op :tx :branch :db :tx-data (vec tx)}}))

(defn- agent-of
  "Task id's agent on db: {:key :id :branch}, or nil before one started it."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:maybe [:map [:key :string] [:id {:optional true} :keyword] [:branch {:optional true} :keyword]]]]}
  [db id]
  (when-let [a (:seon.task/agent (d/entity db [:seon.task/id id]))]
    {:key (:seon.agent/key a) :id (:seon.agent/id a) :branch (:seon.agent/branch a)}))

(defn- ended-ms-tx
  "The write that records task id's agent ended at ms (:seon.agent/ended-ms), or none when it has no agent."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id :int] [:vector :map]]}
  [db id ms]
  (if-let [{:keys [key]} (agent-of db id)] [{:seon.agent/key key :seon.agent/ended-ms ms}] []))

(defn- stop-kept
  "[st outputs] with task id's kept process stopped too (its work judged: root's view says :ended)."
  {:malli/schema [:=> [:cat [:tuple :seon.reconcile/state [:vector :map]] :seon.task/id :keyword] [:tuple :seon.reconcile/state [:vector :map]]]}
  [[st os] id agent]
  (let [[st o] (request st id :ignored {:op :kill :id agent :ended true})] [st (conj os o)]))

(defn- start
  "Begin action act's flight. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.reconcile/action] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st {:keys [op task] :as act}]
  (let [id task
        ;; a kill reaches a live task whose observer chain is in flight: that flight stays
        ;; and sees its agent die
        killing? (and (= :kill op) (get-in st [:flights id]))
        st (if killing? (assoc-in st [:flights id :killing] true) (assoc-in st [:flights id] {:act act}))
        db (:db st)
        from (get-in st [:idx :tasks id :seon.task/outcome])
        agent (:seon.agent/id (task-agent (:idx st) id))
        now (System/currentTimeMillis)
        one (fn [[st o]] [st [o]])]
    (case op
      :wake (let [ag (:agent act)]
              ;; the parked process's next work: written running, then work! (the :started step); synced
              ;; first, as a write an outside effect follows is (AGENTS section 5): work!'s first turn
              ;; reads the messages that woke it from the store (seon.agent/untaken-entries)
              (one (request (assoc-in st [:flights id :agent] ag) id :started
                            {:op :write :req {:op :tx :branch :db :flush? true
                                              :tx-data (task/start-tx db id (:boot st) ag (:env act) now (task/session-key db id))}})))
      :spawn (let [k (task/session-key db id)]
               ;; the session's agent continues (docs/turn.md#parked); kept on the flight for its start
               (one (request (cond-> st k (assoc-in [:flights id :key] k)) id :spawned
                             {:op :spawn :opts (cond-> {:branch (get-in act [:env :branch]) :task id} k (assoc :key k))})))
      :reap (one (write st id :done (into (task/move-tx id :running :lost {:seon.task/reason "root restarted"}) (ended-ms-tx db id now))))
      ;; a judged task whose execution is no longer running: closed
      :close (one (write st id :done (task/move-tx id from :closed {})))
      ;; a live task, or a parked one (its process idle) whose task was cancelled or held
      :kill (let [[st o1] (request st id :ignored {:op :kill :id agent})
                  [st o2] (write st id (if killing? :ignored :done)
                                 (into (task/move-tx id from :killed {:seon.task/reason (name (:reason act))}) (ended-ms-tx db id now)))]
              [st [o1 o2]])
      ;; a merge task's branch into main: clean by construction when the branch holds main's
      ;; program head; behind: its task runs again, the same agent
      ;; an octopus merge: every member's branch into main in one commit (each holds main's
      ;; program head, the members' changes are disjoint); a member found behind or refused
      ;; is answered alone
      :octopus-merge (let [bs (mapv #(:branch (:seon.agent/env (task-agent (:idx st) (:task %)))) (:members act))]
                       (one (request (assoc-in st [:flights id :branches] bs) id :octopus-merged
                                     {:op :write :req {:op :octopus-merge :branches bs :data-attrs #{}}})))
      ;; the merge gate (a change reaching process tests): the candidate's files read from its
      ;; branch, then its deferred tests run on a checkout of the merges head with them (seon.fx/run-tests)
      :gate (one (request st id :gate-read
                          {:op :write :req {:op :merge-candidate :branch (:branch (:seon.agent/env (task-agent (:idx st) id))) :data-attrs #{}}}))
      ;; an action the reconciler cannot start keeps no flight
      (throw (ex-info "no such action" {:op op})))))

(defn- work
  "[st [output]]: the agent's turn loop for task id of act, asked of agent: seon.turn/work!,
  quoted as data (a form sent to another process is no call in this one; a quoted name is no
  reference, seon.program/unit-account). Its budget is the agent's own on main (:seon.agent/max-turns), raised or not."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.task/id :map :keyword] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st id act agent]
  (let [k (:key (agent-of (:db st) id))
        max-turns (or (:seon.agent/max-turns (d/entity (:db st) [:seon.agent/key k])) (get-in act [:env :max-turns]))
        [st o] (request st id :worked
                        {:op :eval :id agent
                         :forms (sedn/encode (list 'seon.turn/work! (list 'quote (cond-> {:task (:task act) :max-turns max-turns}
                                                                                (get-in act [:env :model]) (assoc :model (get-in act [:env :model]))))))})]
    [st [o]]))

(defn- stage-boundary
  "The message that opens task id's stage of its session, or nil: when the session's agent worked
  another task before (the task its :seon.agent/task held before, history), the new
  task's plan and proofs, said once at the boundary (its whole task data is (my.task/data): the
  task data's render reaches the vector search, a process, which no reconciler test may), so the conversation above stays as it was
  sent (docs/turn.md#parked; the live agents' finding A1: the new stage's task data put at
  the top cached 1% of 33k tokens, and the agent took the design for its task)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:maybe :string]]}
  [db id]
  (let [k (:key (agent-of db id))
        ;; the stage opened by this start: the agent's task was replaced in the transaction that wrote its start time (seon.task/start-tx),
        ;; so a later start of the same task (a wake) sees the old replacement and opens no stage again
        started (when k (:tx (first (d/datoms db :eavt (:db/id (d/entity db [:seon.agent/key k])) :seon.agent/started-ms))))
        [prev-task] (when k (->> (d/q '[:find ?tid ?tx :in $ ?k :where [?a :seon.agent/key ?k] [?a :seon.agent/task ?t ?tx false] [?t :seon.task/id ?tid]] (d/history db) k)
                                 (filter #(= started (second %))) (sort-by second) last))]
    (when (and prev-task (not= prev-task id))
      (let [{:keys [plan proofs]} (:seon.task/accumulated (task/task db id))]
        (str prev-task " is done. Your task now is " id ", in this same session: what you did so far stays above.\n\n"
             "Its plan: " plan "\n\nIts proofs: " (str/join " " (sort (map str proofs)))
             "\n\nIts whole task as data (what it cites, its write set): (my.task/data)")))))

(defn- taken-messages
  "How many messages to the agent on branch br branch db holds: those its turns took (each written on the branch by the
  turn that took it). The other side of branch-messages' count: one seek for the branch's messages."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] :int]}
  [db br]
  (or (d/q '[:find (count ?m) . :in $ ?b :where [?m :seon.message/branch ?b]] db br) 0))

(defn- behinds
  "How many times task id was judged behind main (its outcome was written :behind: d/history), what max-behind bounds."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] :int]}
  [db id]
  (or (d/q '[:find (count ?tx) . :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/outcome :behind ?tx true]] (d/history db) id) 0))

(defn- judge
  "Task id's end judged on MEASURED facts and written on main, once: the end its agent wrote on the branch (pull-end),
  root's own proof results, the branch's merge entries, and diff, the writer's one read of the
  branch (:branch-diff: its head db, its merge base and main's t there, the keys it changed,
  its unmerged keys, its contract gaps); `judgment` decides from them what comes next (the outcome, the reasons the done rule gave,
  a verdict to tell, a hold for root) and the answer is stored on the task (:seon.task/outcome and the rest, retracting
  what this end did not set); the task content the branch changed carried to main, or its conflicts with main's recorded
  for root (seon.task/carry-tx); and a failure the agent marked (my.task/fail!) carried with the task held.
  Answers {:tx :outcome :kept?}: kept? when the agent's process is to stay."
  {:malli/schema [:=> [:cat :seon.reconcile/state :map :seon.proof/results [:map [:db :seon.host/db] [:base :seon.host/db] [:ours [:sequential :any]] [:unmerged [:sequential :any]] [:uncontracted [:sequential :qualified-symbol]]]] [:map [:tx [:vector :any]] [:outcome :seon.task/outcome]]]}
  [st {:keys [act run-problems worked-error deferred]} results {:keys [db base base-t] :as diff}]
  (let [main (:db st) idx (:idx st)
        id (:task act)
        t (get-in idx [:tasks id])
        {k :key branch :branch} (agent-of main id)
        end (when k (pull-end db k))
        carried (task/carry-tx base db main)
        failure (:seon.task/failure (task/task db id))
        turn (:seon.agent/turn (d/entity db [:seon.agent/key k]) 0)
        ;; marked failed by its agent at the turn its end came on (my.task/fail!): the failure on main and the task held for root
        marked? (and failure end (= k (:agent failure)) (= (:turn failure) (:ended-turn end)))
        kind (:seon.task/kind t)
        unmerged (mapv sedn/encode (:unmerged diff))
        j (judgment idx id {:end end :results results :uncontracted (set (:uncontracted diff)) :unmerged unmerged :caused (set (:caused diff))
                            :behind (when (pos? (:theirs diff 0)) (:theirs diff)) :basis (or base-t (:max-tx main)) :deferred (set deferred)
                            :plan (when (= :design kind) (some-> (task/task db id) :seon.task/accumulated :plan))
                            :plan-before (when (= :design kind) (some-> (task/task base id) :seon.task/accumulated :plan))
                            :behinds (behinds main id) :told (:seon.task/reasons t)})
        conflicts (mapv #(dissoc % :main-t :resolved?) (pm/entries db))
        reason (or (:reason end) (when-not end (if worked-error (str "seon.turn/work! threw: " (:seon.error/message worked-error)) "the agent wrote no end")))
        error (or (:error end) worked-error)
        now (System/currentTimeMillis)
        ;; what this end measured, each only when there is one to say (and what an earlier end set that this did not, retracted)
        want (cond-> {:seon.task/reasons (:reasons j)}
               reason (assoc :seon.task/reason reason)
               (seq conflicts) (assoc :seon.task/conflicts conflicts)
               (seq unmerged) (assoc :seon.task/unmerged (set unmerged))
               (seq (:ours diff)) (assoc :seon.task/changed (into #{} (map sedn/encode) (:ours diff)))
               (seq deferred) (assoc :seon.task/deferred (set deferred))
               (seq (:failures run-problems)) (assoc :seon.task/failures (:failures run-problems))
               (seq (:costs run-problems)) (assoc :seon.task/costs (:costs run-problems))
               error (assoc :seon.task/error error)
               (seq (:waste end)) (assoc :seon.task/waste (:waste end))
               (seq (:conflicts carried)) (assoc :seon.task/task-conflicts (:conflicts carried)))
        facts {:conflicts conflicts :unmerged unmerged :red (for [[tt s] results :when (not= :pass s)] tt)}
        outcome (if marked? :closed (:outcome j))
        move (if (and (:hold j) (not marked?))
               (hold-tx main id (task/outcome-tx main id :running outcome want) (:hold j) facts)
               (task/outcome-tx main id :running outcome want))
        tx (-> move
               ;; the messages to its agent's branch its turns had taken: main holding more wakes it once parked (idx-ready?),
               ;; whatever came between its last turn and now
               (into [{:seon.agent/key k :seon.agent/taken (taken-messages db branch) :seon.agent/ended-ms now}])
               (into (when (and (:verdict j) (not marked?))
                       (task/message-tx (str "v-" id "-" turn) :verdict "root" branch
                                        (verdict-body (:verdict j) {:results results :deferred deferred :unmerged unmerged :caused (:caused diff)
                                                                    :basis (or base-t (:max-tx main))}))))
               (into (:tx carried))
               ;; marked failed (my.task/fail!): the failure on main and the task held for root (a compare-and-swap from :running),
               ;; so the judge closes it (no retry); one root already cancelled or held keeps root's
               (into (when marked?
                       (cond-> [{:seon.task/id id :seon.task/failure failure}]
                         (= :running (:seon.task/execution (task/task main id)))
                         (conj [:db/cas [:seon.task/id id] :seon.task/execution :running :held])))))]
    {:tx tx :outcome outcome}))

(defn- dead-tx
  "Task id's agent's death on main: the end it wrote on its branch before it exited (its
  reason, and its error as the cause), beside e, the death root observed (:seon.agent/died:
  exit status, stderr tail, last op; or :seon.root/no-agent or :seon.root/ended). A silent death wrote no end. The task is
  held for root (the hold of `judgment`), or closed when it no longer runs."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.task/id :seon.error/base [:maybe :map]] [:vector :any]]}
  [st id e end]
  (let [main (:db st) now (System/currentTimeMillis)
        msg (if (:reason end) (str (:reason end) " (" (:seon.error/message e) ")") (:seon.error/message e))
        j (judgment (:idx st) id {:died (assoc e :seon.error/message msg)})
        want {:seon.task/reason msg :seon.task/error (cond-> e (:error end) (assoc :cause (:error end)))}
        move (if (:hold j) (hold-tx main id (task/outcome-tx main id :running :dead want) (:hold j) {}) (task/outcome-tx main id :running (:outcome j) want))]
    (into move (ended-ms-tx main id now))))

(defn- gated
  "Task id's gate answered: rs, each deferred test's status (the gate's run of them, or :error for each when it
  could not run: unread, or err the effect's error), written on the task with the decision `judgment` leaves to it: all
  passing, the merge is ready; else the task is held for root with the tests not green and why they could not run. A failed
  :tests effect is recorded here, with the results, in one transaction."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.task/id :map [:vector :map] [:maybe :map]] [:vector :any]]}
  [st id rs unread err]
  (let [main (:db st)
        red (for [[tt s] (sort-by (comp str key) rs) :when (not= :pass s)] [tt s])
        why (concat (some-> err :seon.error/message vector) (for [u unread] (str (:ns u) ": " (:reason u))))
        want (cond-> {:seon.task/deferred-results rs}
               (seq unread) (assoc :seon.task/gate-unread unread)
               err (assoc :seon.task/error err))
        move (if (empty? red)
               (task/move-tx id :gate :ready want)
               (hold-tx main id (task/move-tx id :gate :closed want)
                        (str "its deferred tests, run on a checkout at its merge, are not green: "
                             (str/join ", " (for [[tt s] red] (str tt " " (name s))))
                             ;; why they could not run, when the gate itself failed: the effect's error, or a changed
                             ;; namespace with no file on the branch (each test then reads :error)
                             (when (seq why) (str "; the gate could not run them: " (str/join "; " why))))
                        {:conflicts (:seon.task/conflicts (get-in st [:idx :tasks id])) :unmerged (:seon.task/unmerged (get-in st [:idx :tasks id]))}))]
    (if err (err/asked-tx main err :seon.task/deferred-results move) move)))

(defn- advance
  "Task id's flight takes answer r of its step. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.task/id :seon.reconcile/step :any] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st id step r]
  (let [{:keys [act agent] :as f} (get-in st [:flights id])
        done (fn [st] [(update st :flights dissoc id) []])
        one (fn [[st o]] [st [o]])
        now (System/currentTimeMillis)
        ;; its agent died: the one error map root answered with (:seon.agent/died: exit
        ;; status, last op, stderr tail; or :seon.root/no-agent or :seon.root/ended), and the end the agent wrote
        ;; on its branch before it exited, read from the branch
        dead (fn [st e] (one (request (assoc-in st [:flights id :died] e) id :dead-read
                                      {:op :write :req {:op :branch-db :branch (get-in act [:env :branch])}})))
        ;; the writer refused to read or diff its branch: its agent is stopped and the task
        ;; ends with the reason as its error, not judged on facts nobody measured
        refused (fn [st e] (let [[st o1] (request st id :ignored {:op :kill :id agent :ended true})
                                 [st o2] (write st id :done (into (task/move-tx id :running :closed {:seon.task/reason (:seon.error/message e) :seon.task/error e})
                                                                  (ended-ms-tx (:db st) id now)))]
                             [st [o1 o2]]))]
    (case step
      :ignored [st []]
      :done (done st)
      :dead-read (one (write st id :done (dead-tx st id (:died f) (let [k (:key (agent-of (:db st) id))] (when (and k (:db r)) (pull-end (:db r) k))))))
      ;; root answers a spawn at its agent's hello, or at the end the agent met first: a death,
      ;; a failed spawn or a stop (each names the agent) is this task's death, its start
      ;; written first; the flight keeps the error's base and exit status, which `dead` records
      :spawned (if-let [ag (or (:id r) (when (#{:seon.agent/died :seon.agent/failed :seon.root/no-agent :seon.root/ended} (:seon.error/type r)) (:agent r)))]
                 (one (write (cond-> (assoc-in st [:flights id :agent] ag)
                               (:seon.error/type r) (assoc-in [:flights id :died-at-spawn]
                                                              (select-keys r [:seon.error/type :seon.error/operation :seon.error/message :exit :agent])))
                             id :started
                             (task/start-tx (:db st) id (:boot st) ag (:env act) now (:key f))))
                 ;; root refused it (the branch is held, or too many live agents): the refused answer
                 ;; is the task's outcome, with root's reason; the task starts again
                 ;; once main moves for another reason (:refused, pass)
                 (one (write (update st :refused (fnil conj #{}) id) id :done (task/refused-tx (:db st) id r))))
      ;; an outside agent works this task (seon.server): no turn loop; it hands it back
      :started (cond
                 (:died-at-spawn f) (dead st (:died-at-spawn f))
                 (outside? (:env act))
                 (done st)
                 :else
                 ;; a session's next stage: the boundary is a :stage message, root's, a fact on main to this
                 ;; agent (written before it is pushed), taken by its first turn like any other (the
                 ;; conversation above it unchanged, its prefix cached; docs/turn.md#parked)
                 (if-let [body (stage-boundary (:db st) id)]
                   (one (request st id :briefed {:op :message :id agent :from :root :kind :stage :body body}))
                   (work st id act agent)))
      :briefed (if (contract/error? r) (dead st r) (work st id act agent))
      ;; the turn loop answered: died (no live agent), or judge it from measured facts
      ;; work! that threw was answered by the agent's REPL (the process lives): its error
      ;; names the task's end when the loop wrote none
      :worked (cond
                ;; a wake whose parked process had ended before the work! reached it (root's
                ;; view: ended or never there): the agent took nothing, so it is lost, not a death, and
                ;; the task starts again as a spawn, a load of the session (docs/turn.md#parked)
                (and (= :wake (:op act)) (#{:seon.root/ended :seon.root/no-agent} (:seon.error/type r)))
                (one (write st id :done (into (task/move-tx id :running :lost {:seon.task/reason (str "its parked process was gone: " (:seon.error/message r))})
                                              (ended-ms-tx (:db st) id now))))
                (contract/error? r) (dead st r)
                :else
                (one (request (cond-> st (:ex r) (assoc-in [:flights id :worked-error] (:ex r)))
                              id :tested {:op :judge :id agent})))
      :tested (if (contract/error? r)
                (dead st r)
                (one (request (-> st (assoc-in [:flights id :results] (proofs/results r))
                                  (assoc-in [:flights id :deferred] (:deferred r))
                                  (assoc-in [:flights id :run-problems] {:failures (proofs/failures r) :costs (proofs/costs r)}))
                              ;; the branch read once: its head, its merge base, and what it changed
                              ;; since (three-way's :ours, measured for the octopus merge)
                              id :diffed {:op :write :req {:op :branch-diff :branch (get-in act [:env :branch])}})))
      ;; root stops the agent because its work ended (its view says :ended, not :killed), unless
      ;; its session parked: then the process stays, idle, for the next message's wake
      ;; (docs/turn.md#parked: parking is work! returning)
      :diffed (if (contract/error? r)
                (refused st r)
                (let [{:keys [tx outcome]} (judge st f (:results f) r)
                      k (:key (agent-of (:db st) id))
                      kept? (keeps? (get-in st [:idx :tasks id :seon.task/kind]) (:outcome (when k (pull-end (:db r) k))))
                      parks? (#{:parked :cap} outcome)]
                  (cond (and kept? parks?) (one (write st id :done tx))
                        kept? (stop-kept (one (write st id :done tx)) id agent)
                        :else (let [[st o1] (request st id :ignored {:op :kill :id agent :ended true})
                                    [st o2] (write st id :done tx)]
                                [st [o1 o2]]))))
      ;; each member's answer, as one write: merged at the octopus merge's t, behind (its merge task
      ;; runs again, the same agent), or refused: a failed end like any other (held for root)
      ;; the merged members' namespaces that are Seon's own source are written back: main's
      ;; files after the octopus merge, committed on the merges branch (:git), the sha recorded on
      ;; each merged task (the self-improvement loop: the next generation builds it)
      :octopus-merged (let [merged (keep (fn [[m x]] (when (:merged? x) [m x])) (map vector (:members act) (:results r)))
                            paths (vec (sort (distinct (mapcat (comp :files second) merged))))
                            back? (and (:source st) (seq paths))]
                        (one (write (cond-> st back? (assoc-in [:flights id :writeback] {:files paths :members (vec (for [[m x] merged :when (seq (:files x))] m))}))
                                    id (if back? :write-back :done)
                                    (vec (mapcat (fn [{:keys [task]} r]
                                                   (let [n (behinds (:db st) task)
                                                         problem (if (= :seon.merge/behind (:seon.error/type r))
                                                                   (str "merge behind main " (inc n) " times (past :seon.config.merge/max-behind): " (:seon.error/message r))
                                                                   (str "merge refused: " (:seon.error/message r)))
                                                         t (get-in st [:idx :tasks task])]
                                                     (cond
                                                       (:merged? r) (task/move-tx task :ready :merged
                                                                                  (cond-> {:seon.task/merged-t (long (:tx r))}
                                                                                    ;; source it changed that names no file: no write-back writes it, so this
                                                                                    ;; names it (beside the sha of what one wrote, when it wrote some)
                                                                                    (and (:source st) (seq (:unfiled r)))
                                                                                    (assoc :seon.task/commit-error (unfiled-error r))))
                                                       ;; behind again and again: a merge that main moves under every time it runs is root's
                                                       ;; (:seon.config.merge/max-behind), never a rerun without end
                                                       (and (= :seon.merge/behind (:seon.error/type r)) (< n (cfg/value :seon.config.merge/max-behind)))
                                                       (task/move-tx task :ready :behind {:seon.task/reason (:seon.error/message r) :seon.task/error r})
                                                       :else (hold-tx (:db st) task (task/move-tx task :ready :closed {:seon.task/reason problem :seon.task/error r})
                                                                      problem {:conflicts (:seon.task/conflicts t) :unmerged (:seon.task/unmerged t)}))))
                                                 (:members act) (:results r))))))
      ;; the gate's candidate is the writer's: the files the octopus merge would write (seon.writer's :merge-candidate),
      ;; forms', tests' and schema resources' alike (a :seon.ref/block change left out would meet its new
      ;; seon.ref/scan in the checkout, and the seed would throw on scan's contract). A refused candidate is the gate's
      ;; answer, each deferred test :error with why it was refused: no files would test main as it is and vouch for nothing
      :gate-read (if (contract/error? r)
                   (one (write st id :done (gated st id (zipmap (:tests act) (repeat :error)) [] r)))
                   (let [files (:text r)
                         ;; a changed namespace with no file on the branch (made at the REPL, never
                         ;; written) cannot be put over the checkout: named, never dropped
                         unread (vec (for [n (sort (keys (:unfiled r)))]
                                       {:ns n :reason "no :ns/file on the branch: its forms were never written to a file"}))]
                     (one (request (assoc-in st [:flights id :gate-unread] unread) id :gated
                                   {:op :fx :kind :tests :req {:repo (get-in st [:idx :root :gate-checkout]) :branch (:branch (:source st))
                                                               :files files :tests (:tests act)}}))))
      ;; each deferred test's status on the task; a task over a tree missing a changed namespace (gate-unread)
      ;; vouches for none: each :error
      :gated (let [unread (:gate-unread f)
                   rs (if (or (contract/error? r) (seq unread)) (zipmap (:tests act) (repeat :error)) (get-in r [:value :results]))]
               (one (write st id :done (gated st id rs unread (when (contract/error? r) r)))))
      :write-back (one (request st id :main-read {:op :write :req {:op :branch-db :branch :db}}))
      :main-read (let [{:keys [members] paths :files} (:writeback f)
                       files (doc/files (:db r) paths)]
                   ;; paths main holds no text for: no commit, held on each member's task as a lost write-back
                   (if (empty? files)
                     (one (write st id :done (commit-tx (:db st) (contract/error :seon.root/no-source-text #'seon.reconcile/commit-tx
                                                                                 (str "main holds no text for " (str/join ", " paths)) {:paths (vec paths)})
                                                        members paths [])))
                     (one (request (assoc-in st [:flights id :writeback :written] (vec (keys files))) id :committed
                                   {:op :fx :kind :git :req {:dir (:root (:source st)) :branch (:branch (:source st)) :files files
                                                             :message (source/commit-message members)}}))))
      ;; the sha on each merged task; the merge already runs where main's program runs
      ;; (seon.program/load-changes!), so nothing is built (BUILD plan P4)
      :committed (if-let [tx (commit-tx (:db st) r (:members (:writeback f)) (:files (:writeback f)) (:written (:writeback f)))]
                   (one (write st id :done tx))
                   (done st)))))

(defn- quiet
  "Report quiescence positively, once per quiet spell: when no flight is in the air and no
  task is live after a pass that started nothing, write main's :seon.root/quiescent (the
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
        acts (remove #(let [f (get-in st [:flights (:task %)])]
                        (or (and f (or (not= :kill (:op %)) (:killing f)))
                            (and (= :spawn (:op %)) (contains? (:refused st) (:task %)))))
                     (actions (:idx st)))
        acts (concat (remove #(= :spawn (:op %)) acts) (drop early (filter #(= :spawn (:op %)) acts)))]
    ;; an action started is work: the quiet spell ends (and a wait waits for it)
    (apply quiet (reduce (fn [[st outs] act] (let [[st o] (start (assoc st :quiet false) act)] [st (into outs o)])) [st []] acts))))

(defn- behind?
  "Whether answer m is to a write on main that main's commits have not reached yet: the writer
  answers a write on main ({:ok true :t}, seon.writer/commit-batch: the one answer of that shape
  this proc asks for; a branch's write is answered otherwise) before its commit reaches this proc
  on :main, another in, so a step taking the answer would read a db older than its own write.
  Before main's first commit every answer waits for it: no step has a db to read yet."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [st m] (let [{:keys [ok t]} (:r m)] (boolean (or (nil? (:t st)) (and ok (int? t) (< (:t st) t))))))

(defn- take-answer
  "[st requests] with answer m taken by the flight step that asked it (advance), and the next
  pass when that ended its flight."
  {:malli/schema [:=> [:cat :seon.reconcile/state [:map [:tag :int] [:r :any]]] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st m]
  (let [st (cond-> st (= (:tag m) (:report st)) (dissoc :report))
        [st [aid step]] (step/answered st (:tag m))
        [st outs] (if aid (advance st aid step (:r m)) [st []])
        ;; a flight that ended may free its run for the next action
        [st more] (if (and aid (not (get-in st [:flights aid])) (:db st)) (pass st) [st []])]
    [st (into outs more)]))

(defn reconciler-proc
  "The reconciler. In :main (main's commit notices {:t :prev :ops :commit}, in order, a blocking buffer; it makes the db of the commit when it takes the notice) and :answers (the
  api's answers to its requests). Out :to-api (its requests, as {:op :request ...}).
  Args: :boot (this root's boot id), :conn (the store the commits are read from); in :judge (outside agents' runs handed back).
  Starts deepest-first (finish chains under way)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:boot "root's boot id"
                :judge "outside agents' tasks to judge {:op :judge :task :env :agent}, and waits on main {:op :quiet :after t :p promise}, each answered at the first quiet at or after t (external; seon.root/await-db)"}
       :ins {:main "main's commits" :answers "the api's answers"} :outs {:to-api "requests"}
       :workload :io
       :ping-map-fn (fn [st] {:seen (:seen st 0) :lists (:lists st 0) :t (:t st) :flights (count (:flights st)) :pending (count (:pending st)) :behind (count (:behind st))
                              :quiet (boolean (:quiet st)) :root-causes (count (:root-causes (:quiescent st)))})})
  ([args] (assoc (dissoc args :judge) :seen 0 :rid 0 :flights {} :pending {} :waits [] :behind []
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
     :main (let [db (pdb/at-commit (:conn st) (:commit m)) t (:max-tx db)
                 next? (and (:idx st) (= (:prev m) (:t st)))
                 idx (if next? (observe (:idx st) db (:ops m)) (index db))
                 ;; a commit touching tasks, runs or config keys ends a quiet spell (root's own
                 ;; quiescent fact does not)
                 work? (or (not next?) (some (fn [[_ _ a]] (and (keyword? a) (watched-ns (namespace a)) (not= "seon.root" (namespace a)))) (:ops m)))
                 ;; a commit holding only the writes of a refusal (the outcome and what it sets, seon.task/refused-tx) changes
                 ;; nothing that refused it; any other work, an edit of the refused task itself too, lets it ask again
                 only-refused? (and next? (every? (fn [[_ e a]] (or (not (and (keyword? a) (watched-ns (namespace a))))
                                                               (and (contains? refusal-attrs a)
                                                                    (= :refused (get-in idx [:tasks (second (get-in idx [:keys e])) :seon.task/outcome])))))
                                              (:ops m)))
                 st (assoc st :db db :t t :idx idx :seen (inc (:seen st)) :lists (cond-> (:lists st 0) (not= (:prev m) (:t st -2)) inc)
                           :quiet (boolean (and (:quiet st) (not work?)))
                           :refused (if (and work? (not only-refused?)) #{} (:refused st #{})))
                 [st outs] (pass st)
                 ;; the answers this commit's t reaches, held for it, taken in the order they came
                 [st outs] (reduce (fn [[st outs] a] (let [[st more] (take-answer st a)] [st (into outs more)]))
                                   [(update st :behind (fn [as] (filterv #(behind? st %) as))) outs]
                                   (remove #(behind? st %) (:behind st)))]
             [(answer-waits st) {:to-api (into [{:op :stat :k :main-seen-us :v (/ (- (profile/now-ns) (:ns m)) 1000.0)}] outs)}])
     ;; a wait on main (:op :quiet): answered now if the reconciler is quiet past its :after,
     ;; else held. Otherwise an outside agent handed its run back: judged as a turn loop's end is
     :judge (case (:op m)
              :quiet [(answer-waits (update st :waits (fnil conj []) (select-keys m [:after :p]))) {}]
              :judge (let [{:keys [task env agent]} m
                  ;; a flight: the quiet spell ends, and a wait behind this judge waits for it
                  st (-> st (assoc-in [:flights task] {:act {:op :spawn :task task :env env} :agent agent}) (assoc :quiet false))
                  [st o] (request st task :tested {:op :judge :id agent})]
              [st {:to-api [o]}]))
     ;; an answer to a write main has not reached waits for main's commit of it (taken by :main)
     :answers (if (behind? st m)
                [(update st :behind conj m) {}]
                (let [[st outs] (take-answer st m)]
                  [(answer-waits st) {:to-api outs}])))))
