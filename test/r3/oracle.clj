(ns r3.oracle
  "The one reconciler (lanes R1 and M1's proto.agents.reconcile): a pure function of main's
  db value (desired state: the tasks; observed state: attempts, their ends and proof
  results, root's boot) to the minimal actions that close the gap, and the done rule
  `done-refusals` (one function, used by root to judge and by the agent to know when to
  stop). `reconciler-proc` is the root proc that runs it on main's commits and carries the
  actions out as requests to the api, each action's facts written by the writer."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.sock :as sock] [seon.step :as step]
            [seon.task :as task] [seon.template :as tpl] [seon.proofs :as proofs] [seon.merge :as pm]
            [datahike.api :as d] [clojure.string :as str]))

(def default-max-turns
  "Turns an attempt may take when its task sets no :seon.config.agent/max-turns."
  8)

(def default-max-running
  "Parallel attempts when root's boot set no :seon.config.agent/max-running."
  4)

(def default-max-merging
  "Merge attempts at once when root's boot set no :seon.config.merge/max-running: one,
  git's merge queue."
  1)

(def merge-dials
  "The merge dials' defaults, when the task sets none (:seon.config.merge/*)."
  {:max-conflicts 3 :max-merge-turns 2 :max-attach 2})

(defn dials
  "Task t's merge dials: its own :seon.config.merge/* over the defaults."
  {:malli/schema [:=> [:cat :seon.task/task] :seon.reconcile/merge-dials]}
  [t]
  (into {} (for [[k v] merge-dials] [k (get t (keyword "seon.config.merge" (name k)) v)])))

;; ---- the view: one read of main ---------------------------------------------------

(defn view
  "Everything the reconciler reads from db, once: tasks, attempts (grouped by task),
  root's boot and dials."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [as (vals (task/attempts db))]
    {:tasks (task/tasks db)
     :attempts (group-by :seon.attempt/task as)
     :root (or (task/root db) {:boot "" :max-running default-max-running :max-merging default-max-merging})}))

(defn- live?
  "Is attempt a running in this boot of root (its agent is root's to observe)?"
  {:malli/schema [:=> [:cat :map :seon.attempt/attempt] :boolean]}
  [v a] (and (= :running (:seon.attempt/status a)) (= (:boot (:root v)) (:seon.attempt/boot a))))

(defn- done-in?
  "Is task id done: a merge task merged into main (:merged), another accepted on its
  branch (:done)?"
  {:malli/schema [:=> [:cat :map :seon.task/id] :boolean]}
  [v id] (boolean (some #(#{:merged :done} (:seon.attempt/status %)) (get-in v [:attempts id]))))

(defn desired-env
  "The environment task t's attempts must run in."
  {:malli/schema [:=> [:cat :seon.task/task] :seon.task/env]}
  [t]
  {:branch (:seon.task/branch t) :model (:seon.task/model t)
   :max-turns (or (:seon.config.agent/max-turns t) default-max-turns)})

;; ---- the done rule -----------------------------------------------------------------

(defn done-refusals
  "Why an attempt's measured facts do not make its task done; empty means done. Refused:
  no proofs (never proven); a proof not passing; a regression (a test green on main, the
  proofs of merged tasks, red here); a stray red (a red test that is no task's open
  proof); a contract gap (a function the attempt defined without a contract); an
  unmerged entry on the branch (a merge conflict not resolved). A :design task readies
  the interface for its implement step, so its own proofs may stay red."
  {:malli/schema [:=> [:cat :seon.reconcile/done-input] [:vector :seon.reconcile/refusal]]}
  [{:keys [proofs results main-green allowed-red uncontracted unmerged kind]}]
  (let [red? (fn [t] (#{:fail :error} (get results t)))
        design? (= :design kind)]
    (vec (concat
           (when (and (empty? proofs) (not design?)) [{:refusal :no-proofs}])
           (when-not design?
             (for [p (sort-by str proofs) :when (not= :pass (get results p :none))] {:refusal :proof-red :symbol p}))
           (for [t (sort-by str main-green) :when (and (not (proofs t)) (red? t))] {:refusal :regression :symbol t})
           (for [t (sort-by str (keys results))
                 :when (and (red? t) (not (proofs t)) (not (main-green t)) (not (allowed-red t)))]
             {:refusal :stray-red :symbol t})
           (for [s (sort-by str uncontracted)] {:refusal :contract-gap :symbol s})
           (for [k (sort unmerged)] {:refusal :unmerged :key k})))))

(defn references
  "The fully qualified symbols written in backticks in text, in order, once each (a
  task's references, as the context resolves them)."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :qualified-symbol]]}
  [text]
  (let [spans (take-nth 2 (rest (str/split (or text "") #"`" -1)))]
    (vec (distinct (for [s spans
                         :let [slash (str/index-of s "/")]
                         :when (and slash (pos? slash) (< slash (dec (count s)))
                                    (not-any? #(Character/isWhitespace ^char %) s))]
                     (symbol s))))))

(defn hairy
  "Is a merge too hairy to keep resolving, from measured facts: more conflicts than the
  dial; main deleted a var the task's plan or proofs reference; more model turns without
  green than the dial. Answers {:rule :reason} or nil. The merge agent stops early on it;
  the reconciler decides from the attempt's facts alone."
  {:malli/schema [:=> [:cat [:map [:conflicts [:sequential [:map [:key :any] [:kind :keyword]]]]
                             [:task [:map [:seon.task/accumulated [:map [:plan :string] [:proofs :seon.task/proofs]]]]]
                             [:merge-turns :int] [:max-conflicts :int] [:max-merge-turns :int]]]
                  [:maybe [:map [:rule [:enum :too-many-conflicts :deleted-by-them-referenced :merge-turns]] [:reason :string]]]]}
  [{:keys [conflicts task merge-turns max-conflicts max-merge-turns]}]
  (let [{:keys [plan proofs]} (:seon.task/accumulated task)
        refs (into (set proofs) (references plan))
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

(defn- done-by?
  "Was task id merged into main at or before main's t `basis` (nil: at any t)?"
  {:malli/schema [:=> [:cat :map :seon.task/id [:maybe :int]] :boolean]}
  [v id basis]
  (boolean (some #(and (= :merged (:seon.attempt/status %)) (or (nil? basis) (<= (:seon.attempt/merged-t % 0) basis)))
                 (get-in v [:attempts id]))))

(defn done-input
  "The done rule's input for attempt results of task id, observed against main at t
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

(defn refusals-text
  "One line naming each refusal."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/refusal]] :string]}
  [rs] (str/join "; " (map #(str (name (:refusal %)) (some->> (or (:symbol %) (:key %)) (str " "))) rs)))

;; ---- queries ------------------------------------------------------------------------

(defn running
  "The attempts running now (in this boot), by id."
  {:malli/schema [:=> [:cat :seon.host/db] [:set :seon.attempt/id]]}
  [db]
  (let [v (view db)]
    (into #{} (comp cat (filter #(live? v %)) (map :seon.attempt/id)) (vals (:attempts v)))))

(defn done?
  "Is task id done (merged into its target)?"
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] :boolean]}
  [db id] (done-in? (view db) id))

(defn- blocked-in
  {:malli/schema [:=> [:cat :map :seon.task/id] [:set :seon.task/id]]}
  [v id] (into #{} (remove #(done-in? v %)) (get-in v [:tasks id :seon.task/needs])))

(defn blocked-by
  "The needs of task id that are not done."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:set :seon.task/id]]}
  [db id] (blocked-in (view db) id))

(defn- depth
  "A task's depth in the needs DAG (0: no needs), the order attempts start in."
  {:malli/schema [:=> [:cat :map :seon.task/id] :int]}
  [v id]
  (let [ns (get-in v [:tasks id :seon.task/needs])]
    (if (empty? ns) 0 (inc (apply max (map #(depth v %) ns))))))

(defn- ready?
  "Should task t have an attempt running: released, not done, every need done, no attempt
  live or awaiting judgement."
  {:malli/schema [:=> [:cat :map :seon.task/task] :boolean]}
  [v t]
  (let [id (:seon.task/id t) as (get-in v [:attempts id])]
    (and (= :running (:seon.task/execution t))
         (not (done-in? v id))
         (empty? (blocked-in v id))
         (not-any? #(or (live? v %) (#{:ended :dead} (:seon.attempt/status %))) as))))

(defn- should-run-in
  {:malli/schema [:=> [:cat :map] [:vector :seon.task/id]]}
  [v]
  (->> (vals (:tasks v)) (filter #(ready? v %)) (map :seon.task/id) (sort-by (juxt #(- (depth v %)) identity)) vec))

(defn should-run
  "Tasks that should have an attempt running and have none, in start order (dependency
  depth, then id); max-running is not applied here."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.task/id]]}
  [db] (should-run-in (view db)))

(defn- kill-reason
  "Why live attempt a must stop, or nil: its task is no longer released, or its desired
  environment differs from the one it runs in."
  {:malli/schema [:=> [:cat :map :seon.attempt/attempt] [:maybe [:enum :cancelled :held :env-changed]]]}
  [v a]
  (let [t (get-in v [:tasks (:seon.attempt/task a)])]
    (cond (= :cancelled (:seon.task/execution t)) :cancelled
          (= :held (:seon.task/execution t)) :held
          (not= (desired-env t) (:seon.attempt/env a)) :env-changed)))

(defn- to-kill-in
  {:malli/schema [:=> [:cat :map] [:vector [:tuple :seon.attempt/id :keyword]]]}
  [v]
  (vec (for [a (sort-by :seon.attempt/id (apply concat (vals (:attempts v))))
             :when (live? v a)
             :let [r (kill-reason v a)] :when r]
         [(:seon.attempt/id a) r])))

(defn to-kill
  "Live attempts to stop, with why: [[attempt-id reason]]."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector [:tuple :seon.attempt/id :keyword]]]}
  [db] (to-kill-in (view db)))

;; ---- the reconciler -------------------------------------------------------------------

(defn reconcile-view
  "The actions for view v, in the order root executes them: reap attempts a previous root
  left running; kill live attempts whose task changed; judge ended attempts from their
  measured facts (no refusal: a merge task merges into main, another task is accepted on
  its branch; a failed merge attaches design -> implement -> test -> merge; anything else
  bails to root); bail tasks whose agent died; start ready tasks up to max-running. A
  merge root found behind leaves its task ready: its next attempt is a new agent."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [v]
  (let [all (sort-by :seon.attempt/id (apply concat (vals (:attempts v))))
        reaps (for [a all :when (and (= :running (:seon.attempt/status a)) (not (live? v a)))]
                {:op :reap :attempt (:seon.attempt/id a)})
        kills (for [[aid r] (to-kill-in v)] {:op :kill :attempt aid :reason r})
        judged (for [a all
                     :when (#{:ended :dead} (:seon.attempt/status a))
                     :let [id (:seon.attempt/task a) aid (:seon.attempt/id a)
                           t (get-in v [:tasks id])
                           merge? (= :merge (:seon.task/kind t))]]
                 (cond
                   (not= :running (:seon.task/execution t)) {:op :close :attempt aid}
                   (= :dead (:seon.attempt/status a))
                   {:op :bail :attempt aid :task id :problem (str "its agent died" (some->> (:seon.attempt/reason a) (str ": ")))}
                   :else
                   (let [rs (done-refusals (done-input v id (:seon.attempt/results a {}) (:seon.attempt/uncontracted a #{})
                                                       (:seon.attempt/unmerged a []) (:seon.attempt/basis-t a)))]
                     (cond
                       (and (empty? rs) merge?) {:op :merge :attempt aid :task id}
                       (empty? rs) {:op :accept :attempt aid :task id}
                       ;; a failed merge: new tasks attach through :needs, each a new agent
                       (and merge? (< (:seon.task/generation t 0) (:max-attach (dials t)))) {:op :attach :attempt aid :task id}
                       :else
                       {:op :bail :attempt aid :task id
                        :problem (str "attempt " aid " ended " (name (:seon.attempt/outcome a :error))
                                      (some->> (:seon.attempt/reason a) (str " (") (#(str % ")")))
                                      ": " (refusals-text rs))}))))
        live-after (- (count (filter #(live? v %) all)) (count kills))
        room (max 0 (- (:max-running (:root v) default-max-running) live-after))
        ;; git's merge queue: a merge attempt running, or ended and awaiting root's merge,
        ;; holds the queue, so the main a merge agent merged last is the main root merges into
        merge? (fn [id] (= :merge (get-in v [:tasks id :seon.task/kind])))
        merging (count (filter #(and (merge? (:seon.attempt/task %))
                                     (or (live? v %) (= :ended (:seon.attempt/status %))))
                               all))
        merge-room (max 0 (- (:max-merging (:root v) default-max-merging) merging))
        starts (loop [[id & more] (should-run-in v) n 0 m 0 acc []]
                 (cond (or (nil? id) (>= n room)) acc
                       (merge? id) (if (< m merge-room) (recur more (inc n) (inc m) (conj acc id)) (recur more n m acc))
                       :else (recur more (inc n) m (conj acc id))))
        spawns (for [id starts
                     :let [t (get-in v [:tasks id])]]
                 {:op :spawn :task id :env (desired-env t)
                  :attempt (str id "#" (inc (count (get-in v [:attempts id]))))})]
    (vec (concat reaps kills judged spawns))))

(defn reconcile
  "The actions that move main's observed state toward its desired state."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db] (reconcile-view (view db)))

