(ns r3.oracle
  "The reconciler as a full recompute, kept as r3.inc-test's oracle: `reconcile-view` of
  `view` (one read of main) gives the actions the incremental `seon.reconcile` must match.
  Judging is decided once, at an agent's end (seon.reconcile/judgment), and stored on its task: the
  oracle reads the stored answer, as `seon.reconcile/actions` does, and never derives the done rule."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.task :as task] [clojure.string :as str]
            [seon.config :as cfg]))

(def default-max-turns
  "Turns an agent may take when its task sets no :seon.config.agent/max-turns: the config's."
  (cfg/value :seon.config.agent/max-turns))

(def default-max-running
  "Parallel agents when root's boot set no :seon.config.agent/max-running: the config's."
  (cfg/value :seon.config.agent/max-running))

(def default-max-merging
  "Merge tasks at once when root's boot set no :seon.config.merge/max-running: one,
  git's merge queue: the config's."
  (cfg/value :seon.config.merge/max-running))

;; ---- the view: one read of main ---------------------------------------------------

(defn view
  "Everything the reconciler reads from db, once: tasks (their stored outcomes among them), agents,
  root's boot and config keys."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  {:tasks (task/tasks db)
   :agents (task/agents db)
   :root (or (task/root db) {:boot "" :max-running default-max-running :max-merging default-max-merging})})

(defn- agent-of
  {:malli/schema [:=> [:cat :map :seon.task/id] [:maybe :map]]}
  [v id] (get-in v [:agents (get-in v [:tasks id :seon.task/agent])]))

(defn- live?
  "Does task id's agent work it in this boot of root (its agent is root's to observe)?"
  {:malli/schema [:=> [:cat :map :seon.task/id] :boolean]}
  [v id]
  (boolean (and (= :running (get-in v [:tasks id :seon.task/outcome])) (= (:boot (:root v)) (:seon.agent/boot (agent-of v id))))))

(defn- idle?
  "Is task id's work parked on its live process in this boot (parked, or at its cap)?"
  {:malli/schema [:=> [:cat :map :seon.task/id] :boolean]}
  [v id]
  (boolean (and (#{:parked :cap} (get-in v [:tasks id :seon.task/outcome])) (= (:boot (:root v)) (:seon.agent/boot (agent-of v id))))))

(defn- done-in?
  "Is task id done: a merge task merged into main (:merged), another accepted on its
  branch (:done)?"
  {:malli/schema [:=> [:cat :map :seon.task/id] :boolean]}
  [v id] (boolean (#{:merged :done} (get-in v [:tasks id :seon.task/outcome]))))

(defn desired-env
  "The environment task t's agent must work in."
  {:malli/schema [:=> [:cat :seon.task/task] :seon.task/env]}
  [t]
  {:branch (:seon.task/branch t) :model (:seon.task/model t)
   :max-turns (or (:seon.config.agent/max-turns t) default-max-turns)})

;; ---- queries ------------------------------------------------------------------------

(defn- blocked-in
  {:malli/schema [:=> [:cat :map :seon.task/id] [:set :seon.task/id]]}
  [v id] (into #{} (remove #(done-in? v %)) (get-in v [:tasks id :seon.task/needs])))

(defn- depth
  "A task's depth in the needs DAG (0: no needs), the order tasks start in."
  {:malli/schema [:=> [:cat :map :seon.task/id] :int]}
  [v id]
  (let [ns (get-in v [:tasks id :seon.task/needs])]
    (if (empty? ns) 0 (inc (apply max (map #(depth v %) ns))))))

(defn- ready?
  "Should task t have an agent working: released, not done, every need done, none live or awaiting its merge,
  and, parked, more messages for it (none here: the generated histories write none) than it had taken."
  {:malli/schema [:=> [:cat :map :seon.task/task] :boolean]}
  [v t]
  (let [id (:seon.task/id t) o (:seon.task/outcome t)]
    (and (= :running (:seon.task/execution t))
         (not (done-in? v id))
         (empty? (blocked-in v id))
         (not (live? v id))
         (not (#{:ready :gate :cap} o))
         (or (not (#{:parked :told} o)) (> 0 (:seon.agent/taken (agent-of v id) 0))))))

(defn- should-run-in
  {:malli/schema [:=> [:cat :map] [:vector :seon.task/id]]}
  [v]
  (->> (vals (:tasks v)) (filter #(ready? v %)) (map :seon.task/id) (sort-by (juxt #(- (depth v %)) identity)) vec))

(defn- kill-reason
  "Why live task id's agent must stop, or nil: its task is no longer released, or its desired
  branch or model differs from the one it works in."
  {:malli/schema [:=> [:cat :map :seon.task/id] [:maybe [:enum :cancelled :held :env-changed]]]}
  [v id]
  (let [t (get-in v [:tasks id]) env (:seon.agent/env (agent-of v id))]
    (cond (= :cancelled (:seon.task/execution t)) :cancelled
          (= :held (:seon.task/execution t)) :held
          (not= (select-keys (desired-env t) [:branch :model]) (select-keys env [:branch :model])) :env-changed)))

;; ---- the reconciler -------------------------------------------------------------------

(defn reconcile-view
  "The actions for view v, in the order root executes them: reap tasks a previous root
  left running; kill live tasks whose release changed; close judged merges whose task is no longer
  released; start ready tasks up to max-running. The merge queue is the octopus merge's, which the
  incremental reconciler groups; here a ready merge is one :merge action per task."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [v]
  (let [ids (sort (keys (:tasks v)))
        reaps (for [id ids :when (and (= :running (get-in v [:tasks id :seon.task/outcome])) (not (live? v id)))]
                {:op :reap :task id})
        live-kills (for [id ids :when (live? v id) :let [r (kill-reason v id)] :when r] {:op :kill :task id :reason r})
        ;; a parked process stops when its task is cancelled or held, or works another branch
        idle-kills (for [id ids :when (idle? v id)
                         :let [t (get-in v [:tasks id])
                               r (cond (= :cancelled (:seon.task/execution t)) :cancelled
                                       (= :held (:seon.task/execution t)) :held
                                       (not= (:seon.task/branch t) (:branch (:seon.agent/env (agent-of v id)))) :env-changed)]
                         :when r]
                     {:op :kill :task id :reason r})
        kills (sort-by :task (concat live-kills idle-kills))
        judged (for [id ids :when (#{:ready :gate} (get-in v [:tasks id :seon.task/outcome]))
                     :let [t (get-in v [:tasks id])]]
                 (cond (not= :running (:seon.task/execution t)) {:op :close :task id}
                       (= :gate (:seon.task/outcome t)) {:op :gate :task id}
                       :else {:op :merge :task id}))
        live-after (- (count (filter #(live? v %) ids)) (count live-kills))
        room (max 0 (- (:max-running (:root v) default-max-running) live-after))
        ;; git's merge queue: a merge task working, or judged and awaiting root's merge,
        ;; holds the queue, so the main a merge agent merged last is the main root merges into
        merge? (fn [id] (= :merge (get-in v [:tasks id :seon.task/kind])))
        merging (count (filter #(and (merge? %) (or (live? v %) (#{:ready :gate} (get-in v [:tasks % :seon.task/outcome])))) ids))
        merge-room (max 0 (- (:max-merging (:root v) default-max-merging) merging))
        starts (loop [[id & more] (should-run-in v) n 0 m 0 acc []]
                 (cond (or (nil? id) (>= n room)) acc
                       (merge? id) (if (< m merge-room) (recur more (inc n) (inc m) (conj acc id)) (recur more n m acc))
                       :else (recur more (inc n) m (conj acc id))))
        spawns (for [id starts
                     :let [t (get-in v [:tasks id])]]
                 {:op :spawn :task id :env (desired-env t)})]
    (vec (concat reaps kills judged spawns))))
