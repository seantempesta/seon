(ns r3.oracle
  "The reconciler as a full recompute, kept as r3.inc-test's oracle: `reconcile-view` of
  `view` (one read of main) gives the actions the incremental `seon.reconcile` must match,
  and `done-explain` the done rule."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.task :as task] [clojure.string :as str]
            [seon.config :as cfg]))

(def default-max-turns
  "Turns a run may take when its task sets no :seon.config.agent/max-turns: the config's."
  (cfg/value :seon.config.agent/max-turns))

(def default-max-running
  "Parallel runs when root's boot set no :seon.config.agent/max-running: the config's."
  (cfg/value :seon.config.agent/max-running))

(def default-max-merging
  "Merge runs at once when root's boot set no :seon.config.merge/max-running: one,
  git's merge queue: the config's."
  (cfg/value :seon.config.merge/max-running))


;; ---- the view: one read of main ---------------------------------------------------

(defn view
  "Everything the reconciler reads from db, once: tasks, runs (grouped by task),
  root's boot and config keys."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [as (vals (task/runs db))]
    {:tasks (task/tasks db)
     :runs (group-by :seon.run/task as)
     :root (or (task/root db) {:boot "" :max-running default-max-running :max-merging default-max-merging})}))

(defn- live?
  "Is run a running in this boot of root (its agent is root's to observe)?"
  {:malli/schema [:=> [:cat :map :seon.run/run] :boolean]}
  [v a] (and (= :running (:seon.run/status a)) (= (:boot (:root v)) (:seon.run/boot a))))

(defn- done-in?
  "Is task id done: a merge task merged into main (:merged), another accepted on its
  branch (:done)?"
  {:malli/schema [:=> [:cat :map :seon.task/id] :boolean]}
  [v id] (boolean (some #(#{:merged :done} (:seon.run/status %)) (get-in v [:runs id]))))

(defn desired-env
  "The environment task t's runs must run in."
  {:malli/schema [:=> [:cat :seon.task/task] :seon.task/env]}
  [t]
  {:branch (:seon.task/branch t) :model (:seon.task/model t)
   :max-turns (or (:seon.config.agent/max-turns t) default-max-turns)})

;; ---- the done rule -----------------------------------------------------------------

(defn done-explain
  "Why a run's measured facts do not make its task done; empty means done. Refused:
  no proofs (never proven); a proof not passing; a regression (a test green on main, the
  proofs of merged tasks, red here); a stray red (a red test that is no task's open
  proof); a contract gap (a function the run defined without a contract); an
  unmerged entry on the branch (a merge conflict not resolved). A :design task readies
  the interface for its implement step, so its own proofs may stay red."
  {:malli/schema [:=> [:cat :seon.reconcile/done-input] [:vector :seon.reconcile/not-done]]}
  [{:keys [proofs results main-green allowed-red uncontracted unmerged kind]}]
  (let [red? (fn [t] (#{:fail :error} (get results t)))
        design? (= :design kind)]
    (vec (concat
           (when (and (empty? proofs) (not design?)) [{:reason :no-proofs}])
           (when-not design?
             (for [p (sort-by str proofs) :let [s (get results p)] :when (not= :pass s)] {:reason (if s :proof-red :proof-unknown) :symbol p}))
           (for [t (sort-by str main-green) :when (and (not (proofs t)) (red? t))] {:reason :regression :symbol t})
           (for [t (sort-by str (keys results))
                 :when (and (red? t) (not (proofs t)) (not (main-green t)) (not (allowed-red t)))]
             {:reason :stray-red :symbol t})
           (for [s (sort-by str uncontracted)] {:reason :contract-gap :symbol s})
           (for [k (sort unmerged)] {:reason :unmerged :key k})))))

(defn- done-by?
  "Was task id merged into main at or before main's t `basis` (nil: at any t)?"
  {:malli/schema [:=> [:cat :map :seon.task/id [:maybe :int]] :boolean]}
  [v id basis]
  (boolean (some #(and (= :merged (:seon.run/status %)) (or (nil? basis) (<= (:seon.run/merged-t % 0) basis)))
                 (get-in v [:runs id]))))

(defn done-input
  "The done rule's input for run results of task id, observed against main at t
  `basis` (nil: main as the view holds it): proofs of tasks done by then must stay green;
  proofs of the others may be red."
  {:malli/schema [:=> [:cat :map :seon.task/id :seon.proof/results [:set :qualified-symbol] [:sequential :string] [:maybe :int]] :seon.reconcile/done-input]}
  [v id results uncontracted unmerged basis]
  (let [others (vals (dissoc (:tasks v) id))
        done (filter #(done-by? v (:seon.task/id %) basis) others)
        open (remove #(done-by? v (:seon.task/id %) basis) others)]
    {:proofs (get-in v [:tasks id :seon.task/accumulated :proofs] #{})
     :kind (get-in v [:tasks id :seon.task/kind] :work)
     :results results
     :main-green (into #{} (mapcat (comp :proofs :seon.task/accumulated)) done)
     :allowed-red (into #{} (mapcat (comp :proofs :seon.task/accumulated)) open)
     :uncontracted uncontracted
     :unmerged (vec unmerged)}))

(defn explain-text
  "One line naming each reason."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/not-done]] :string]}
  [rs] (str/join "; " (map #(str (name (:reason %)) (some->> (or (:symbol %) (:key %)) (str " "))) rs)))

;; ---- queries ------------------------------------------------------------------------

(defn- blocked-in
  {:malli/schema [:=> [:cat :map :seon.task/id] [:set :seon.task/id]]}
  [v id] (into #{} (remove #(done-in? v %)) (get-in v [:tasks id :seon.task/needs])))

(defn- depth
  "A task's depth in the needs DAG (0: no needs), the order runs start in."
  {:malli/schema [:=> [:cat :map :seon.task/id] :int]}
  [v id]
  (let [ns (get-in v [:tasks id :seon.task/needs])]
    (if (empty? ns) 0 (inc (apply max (map #(depth v %) ns))))))

(defn- ready?
  "Should task t have a run running: released, not done, every need done, no run
  live or awaiting judgement."
  {:malli/schema [:=> [:cat :map :seon.task/task] :boolean]}
  [v t]
  (let [id (:seon.task/id t) as (get-in v [:runs id])]
    (and (= :running (:seon.task/execution t))
         (not (done-in? v id))
         (empty? (blocked-in v id))
         (not-any? #(or (live? v %) (#{:ended :dead} (:seon.run/status %))) as))))

(defn- should-run-in
  {:malli/schema [:=> [:cat :map] [:vector :seon.task/id]]}
  [v]
  (->> (vals (:tasks v)) (filter #(ready? v %)) (map :seon.task/id) (sort-by (juxt #(- (depth v %)) identity)) vec))

(defn- kill-reason
  "Why live run a must stop, or nil: its task is no longer released, or its desired
  environment differs from the one it runs in."
  {:malli/schema [:=> [:cat :map :seon.run/run] [:maybe [:enum :cancelled :held :env-changed]]]}
  [v a]
  (let [t (get-in v [:tasks (:seon.run/task a)])]
    (cond (= :cancelled (:seon.task/execution t)) :cancelled
          (= :held (:seon.task/execution t)) :held
          (not= (desired-env t) (:seon.run/env a)) :env-changed)))

(defn- to-kill-in
  {:malli/schema [:=> [:cat :map] [:vector [:tuple :seon.run/id :keyword]]]}
  [v]
  (vec (for [a (sort-by :seon.run/id (apply concat (vals (:runs v))))
             :when (live? v a)
             :let [r (kill-reason v a)] :when r]
         [(:seon.run/id a) r])))

;; ---- the reconciler -------------------------------------------------------------------

(defn reconcile-view
  "The actions for view v, in the order root executes them: reap runs a previous root
  left running; kill live runs whose task changed; judge ended runs from their
  measured facts (no reason: a merge task merges into main, another task is accepted on
  its branch; a failed merge attaches design -> implement -> test -> merge; anything else
  bails to root); bail tasks whose agent died; start ready tasks up to max-running. A
  merge root found behind leaves its task ready: its next run is a new agent."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [v]
  (let [all (sort-by :seon.run/id (apply concat (vals (:runs v))))
        reaps (for [a all :when (and (= :running (:seon.run/status a)) (not (live? v a)))]
                {:op :reap :run (:seon.run/id a)})
        kills (for [[aid r] (to-kill-in v)] {:op :kill :run aid :reason r})
        judged (for [a all
                     :when (#{:ended :dead} (:seon.run/status a))
                     :let [id (:seon.run/task a) aid (:seon.run/id a)
                           t (get-in v [:tasks id])
                           merge? (= :merge (:seon.task/kind t))]]
                 (cond
                   (not= :running (:seon.task/execution t)) {:op :close :run aid}
                   (= :dead (:seon.run/status a))
                   {:op :hold :run aid :task id :problem (str "its agent died" (some->> (:seon.run/reason a) (str ": ")))}
                   :else
                   (let [rs (done-explain (done-input v id (:seon.run/results a {}) (:seon.run/uncontracted a #{})
                                                       (:seon.run/unmerged a []) (:seon.run/basis-t a)))]
                     (cond
                       (and (empty? rs) merge?) {:op :merge :run aid :task id}
                       (empty? rs) {:op :accept :run aid :task id}
                       ;; a refused run: its task is held for root (no automatic retry)
                       :else
                       {:op :hold :run aid :task id
                        :problem (str "run " aid " ended" (some->> (:seon.run/outcome a) name (str " "))
                                      (some->> (:seon.run/reason a) (str " (") (#(str % ")")))
                                      ": " (explain-text rs))}))))
        live-after (- (count (filter #(live? v %) all)) (count kills))
        room (max 0 (- (:max-running (:root v) default-max-running) live-after))
        ;; git's merge queue: a merge run running, or ended and awaiting root's merge,
        ;; holds the queue, so the main a merge agent merged last is the main root merges into
        merge? (fn [id] (= :merge (get-in v [:tasks id :seon.task/kind])))
        merging (count (filter #(and (merge? (:seon.run/task %))
                                     (or (live? v %) (= :ended (:seon.run/status %))))
                               all))
        merge-room (max 0 (- (:max-merging (:root v) default-max-merging) merging))
        starts (loop [[id & more] (should-run-in v) n 0 m 0 acc []]
                 (cond (or (nil? id) (>= n room)) acc
                       (merge? id) (if (< m merge-room) (recur more (inc n) (inc m) (conj acc id)) (recur more n m acc))
                       :else (recur more (inc n) m (conj acc id))))
        spawns (for [id starts
                     :let [t (get-in v [:tasks id])]]
                 {:op :spawn :task id :env (desired-env t)
                  :run (str id "#" (inc (count (get-in v [:runs id]))))})]
    (vec (concat reaps kills judged spawns))))
