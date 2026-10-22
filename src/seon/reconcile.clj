(ns seon.reconcile
  "The one reconciler (sessions R1 and M1's proto.agents.reconcile): a pure function of main's
  db value (desired state: the tasks; observed state: attempts, their ends and proof
  results, root's boot) to the minimal actions that close the gap, and the done rule
  `done-refusals` (one function, used by root to judge and by the agent to know when to
  stop). `reconciler-proc` is the root proc that runs it on main's commits and carries the
  actions out as requests to the api, each action's facts written by the writer."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.sock :as sock] [seon.step :as step]
            [seon.task :as task] [seon.template :as tpl] [seon.proofs :as proofs] [seon.merge :as pm] [seon.source :as source]
            [datahike.api :as d] [clojure.string :as str] [clojure.core.async.flow :as flow] [seon.ref :as ref]
            [seon.config :as cfg] [seon.program :as program]))

(def default-max-turns
  "Turns an attempt may take when its task sets no :seon.config.agent/max-turns: the config's."
  (cfg/value :seon.config.agent/max-turns))

(def default-max-running
  "Parallel attempts when main's root entity states no :seon.config.agent/max-running: the config's."
  (cfg/value :seon.config.agent/max-running))

(def default-max-merging
  "Merge attempts at once when main's root entity states no :seon.config.merge/max-running:
  the config's (1: octopus merges stay off, DECISIONS). They run together only when the
  definitions they change are disjoint (an octopus merge); 1 is git's one-at-a-time merge queue."
  (cfg/value :seon.config.merge/max-running))

(def merge-defaults
  "The merge limits' defaults, when the task sets none (:seon.config.merge/*): the config's."
  {:max-conflicts (cfg/value :seon.config.merge/max-conflicts) :max-merge-turns (cfg/value :seon.config.merge/max-merge-turns)})

(def default-budget
  "Refinement chains a task's failures may attach when it declares no :seon.task/budget: the config's."
  (cfg/value :seon.config.task/budget))

(defn refine?
  "Has task t budget left for one more refinement chain (its generation below its budget)?"
  {:malli/schema [:=> [:cat :seon.task/own] :boolean]}
  [t] (< (:seon.task/generation t 0) (:seon.task/budget t default-budget)))

(defn merge-limits
  "Task t's merge limits: its own :seon.config.merge/* over the defaults."
  {:malli/schema [:=> [:cat :seon.task/own] :seon.reconcile/merge-limits]}
  [t]
  (into {} (for [[k v] merge-defaults] [k (get t (keyword "seon.config.merge" (name k)) v)])))

;; ---- the view: one read of main ---------------------------------------------------

(defn desired-env
  "The environment task t's attempts must run in."
  {:malli/schema [:=> [:cat :seon.task/own] :seon.task/env]}
  [t]
  {:branch (:seon.task/branch t) :model (:seon.task/model t)
   :max-turns (or (:seon.config.agent/max-turns t) default-max-turns)})

;; ---- the done rule -----------------------------------------------------------------

(defn done-refusals
  "Why an attempt's measured facts do not make its task done; empty means done. Refused:
  no proofs (never proven); a proof not passing (red), or with no result (unknown: it
  never ran); a regression (a test green on main, the
  proofs of merged tasks, red here); a stray red (a red test that is no task's open
  proof); a contract gap (a function the attempt defined without a contract); an
  unmerged entry on the branch (a merge conflict not resolved). A :design task readies
  the interface and plan for its implement step, so its own proofs may stay red but not
  unknown (each written and run), and it is refused :no-design until its plan differs from
  the one its attempt began with (or that one is unknown)."
  {:malli/schema [:=> [:cat :seon.reconcile/done-input] [:vector :seon.reconcile/refusal]]}
  [{:keys [proofs results main-green allowed-red uncontracted unmerged kind plan plan-before]}]
  (let [red? (fn [t] (#{:fail :error} (get results t)))
        design? (= :design kind)]
    (vec (concat
           (when (and (empty? proofs) (not design?)) [{:refusal :no-proofs}])
           (when (and design? (or (nil? plan-before) (= plan plan-before))) [{:refusal :no-design}])
           ;; a proof with no result never ran: unknown, not red (v1's reading); a design
           ;; leaves its proofs red, never unknown (the implement step needs them written)
           (for [p (sort-by str proofs) :let [s (get results p)] :when (if design? (nil? s) (not= :pass s))]
             {:refusal (if s :proof-red :proof-unknown) :symbol p})
           (for [t (sort-by str main-green) :when (and (not (proofs t)) (red? t))] {:refusal :regression :symbol t})
           (for [t (sort-by str (keys results))
                 :when (and (red? t) (not (proofs t)) (not (main-green t)) (not (allowed-red t)))]
             {:refusal :stray-red :symbol t})
           (for [s (sort-by str uncontracted)] {:refusal :contract-gap :symbol s})
           (for [k (sort unmerged)] {:refusal :unmerged :key k})))))

(defn hairy
  "Is a merge too hairy to keep resolving, from measured facts: more conflicts than the
  config key; main deleted a var the task's plan or proofs reference; more model turns without
  green than the config key. Answers {:rule :reason} or nil. The merge agent stops early on it;
  the reconciler decides from the attempt's facts alone."
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

(defn refusals-text
  "One line naming each refusal."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/refusal]] :string]}
  [rs] (str/join "; " (map #(str (name (:refusal %)) (some->> (or (:symbol %) (:key %)) (str " "))) rs)))

;; ---- the incremental reconciler: an index watched through main's tx reports --------------
;; k8s's informer + W1's engine: list once (index), then each commit's resolved datoms name the
;; task, attempt and root entities it touched (observe); only those are re-read (one pull
;; each) and only the tasks they reach are re-derived: the task itself, the tasks of its
;; attempts, and the tasks that need it (the reverse :needs index), transitively for a needs
;; change. `actions` derives the same actions as the full view from the index alone; it walks
;; only the active attempts (running, ended, dead) and the head of the ready set, so a pass
;; costs what changed plus what is in flight, never the number of tasks.

(def ^:private watched-ns
  #{"seon.task" "seon.attempt" "seon.root" "seon.config.agent" "seon.config.merge"})

(def empty-index
  "The index of a db with no tasks."
  {:root nil :tasks {} :keys {} :needed-by {} :acc {} :depth {} :claims {}
   :attempts {} :by-task {} :done {} :ready (sorted-set) :ready-key {}
   :live #{} :reap #{} :kill {} :judge #{} :merging #{}})

(def finished "Attempt statuses that make its task done (:seon.attempt/status)." (get-in contract/forms [:seon.attempt/status 1 :seon.status/finished]))
(def ^:private judged (get-in contract/forms [:seon.attempt/status 1 :seon.status/judged]))

(defn- live-in?
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.attempt/attempt] :boolean]}
  [idx a] (and (= :running (:seon.attempt/status a)) (= (:boot (:root idx)) (:seon.attempt/boot a))))

(defn- done-info
  "{:any? (merged or accepted) :merged-t (the earliest merge, or nil)} of task id."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:map [:any? :boolean] [:merged-t [:maybe :int]]]]}
  [idx id]
  (let [as (map (:attempts idx) (get-in idx [:by-task id]))
        ms (keep #(when (= :merged (:seon.attempt/status %)) (:seon.attempt/merged-t % 0)) as)]
    {:any? (boolean (some #(finished (:seon.attempt/status %)) as))
     :merged-t (when (seq ms) (apply min ms))}))

(defn- idx-done? {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id] (boolean (get-in idx [:done id :any?])))

(defn- idx-ready?
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] :boolean]}
  [idx id]
  (let [t (get-in idx [:tasks id])]
    (boolean (and t (= :running (:seon.task/execution t)) (not (idx-done? idx id)) (nil? (:seon.task/stuck t))
                  (every? #(idx-done? idx %) (:seon.task/needs t))
                  (not-any? #(let [a (get-in idx [:attempts %])] (or (live-in? idx a) (judged (:seon.attempt/status a))))
                            (get-in idx [:by-task id]))))))

(defn- refresh-done
  "Re-derive task id's done state from its attempts; answers [idx flipped?]."
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

(defn- put-attempt
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.attempt/id [:maybe :seon.attempt/attempt]] :seon.reconcile/index]}
  [idx aid a]
  (let [old (get-in idx [:attempts aid])
        tid (or (:seon.attempt/task a) (:seon.attempt/task old))]
    (-> idx
        (assoc :attempts (if a (assoc (:attempts idx) aid a) (dissoc (:attempts idx) aid)))
        (update-in [:by-task tid] (fnil (if a conj disj) #{}) aid))))

(defn- topo
  "ids and every task that needs one of them, transitively, each after its needs among them."
  {:malli/schema [:=> [:cat :seon.reconcile/index [:set :seon.task/id]] [:vector :seon.task/id]]}
  [idx ids]
  (let [all (loop [front (vec ids) seen (set ids)]
              (if-let [[x & more] (seq front)]
                (let [ns (remove seen (get-in idx [:needed-by x]))] (recur (into (vec more) ns) (into seen ns)))
                seen))
        deg (into {} (for [x all] [x (count (filter all (get-in idx [:tasks x :seon.task/needs])))]))]
    (loop [q (vec (sort (filter #(zero? (deg %)) all))) deg deg out []]
      (if-let [[x & more] (seq q)]
        (let [nb (filter all (get-in idx [:needed-by x]))
              deg (reduce #(update %1 %2 dec) deg nb)]
          (recur (into (vec more) (sort (filter #(zero? (deg %)) nb))) deg (conj out x)))
        out))))

(defn- classify
  "Attempt aid's place in the sets actions reads: live, reap, kill (with the reason), judge
  (ended or dead), merging (a merge task's attempt live or ended)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.attempt/id] :seon.reconcile/index]}
  [idx aid]
  (let [a (get-in idx [:attempts aid])
        t (get-in idx [:tasks (:seon.attempt/task a)])
        st (:seon.attempt/status a)
        live? (and a (live-in? idx a))
        kill (when live?
               (cond (= :cancelled (:seon.task/execution t)) :cancelled
                     (= :held (:seon.task/execution t)) :held
                     (not= (desired-env t) (:seon.attempt/env a)) :env-changed))
        mark (fn [idx k on?] (update idx k (if on? conj disj) aid))]
    (-> idx
        (mark :live live?)
        (mark :reap (and a (= :running st) (not live?)))
        (update :kill #(if kill (assoc % aid kill) (dissoc % aid)))
        (mark :judge (boolean (judged st)))
        (mark :merging (and a (= :merge (:seon.task/kind t)) (or live? (= :ended st)))))))

(defn- settle
  "Re-derive after entities changed: tasks whose needs or proofs changed (and the tasks that
  need them, transitively) get depth and accumulated proofs again in dependency order; each
  dirty task's done state is re-derived (it depends on its own attempts only); then readiness
  of the dirty tasks and of the tasks needing one whose done state flipped."
  {:malli/schema [:=> [:cat :seon.reconcile/index [:set :seon.task/id] [:set :seon.task/id] [:set :seon.attempt/id]] :seon.reconcile/index]}
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
  (let [es (into #{} (keep (fn [[_ e a]] (when (and (keyword? a) (watched-ns (namespace a))) e))) ops)]
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
                       :attempt (let [aid (or (:seon.attempt/id v) oid)
                                      tid (or (:seon.attempt/task v) (get-in idx [:attempts aid :seon.attempt/task]))]
                                  [(-> idx (put-attempt aid v) (update :keys #(if v (assoc % e [:attempt aid]) (dissoc % e))))
                                   nc (conj dirty tid) all? (conj touched aid)])
                       :root [(-> idx (assoc :root v) (assoc-in [:keys e] [:root :root]))
                              nc dirty (or all? (not= (:boot (:root idx)) (:boot v))) touched]
                       [idx nc dirty all? touched])))
            [idx nc dirty all? touched] (reduce step [idx #{} #{} false #{}] (sort es))]
        ;; a new boot changes which attempts are live: every task is re-derived (once per boot)
        (settle idx nc (if all? (set (keys (:tasks idx))) dirty) touched)))))

(defn index
  "The index of db, listed: observe of every task, attempt and root entity at once."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (observe empty-index db (for [e (task/entity-ids db)] [:db/add e :seon.task/id nil])))

(defn done-input
  "The done rule's input for attempt results of task id, observed against main at t basis
  (nil: main as idx holds it), over the index: the regression and allowed-red sets restricted to the tests
  that ran (the only ones done-refusals asks about), each test looked up by its claimants; for
  a :design task, its accumulated plan now and plan-before, the one its attempt began with
  (:seon.attempt/plan; nil: unknown)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id :seon.proof/results [:set :qualified-symbol] [:sequential :string] [:maybe :int] [:maybe :seon.task/plan]] :seon.reconcile/done-input]}
  [idx id results uncontracted unmerged basis plan-before]
  (let [by? (fn [x] (when-let [mt (get-in idx [:done x :merged-t])] (or (nil? basis) (<= mt basis))))
        claim (fn [t] (disj (get-in idx [:claims t] #{}) id))
        kind (get-in idx [:tasks id :seon.task/kind] :work)]
    (cond-> {:proofs (get-in idx [:acc id] #{})
             :kind kind
             :results results
             :main-green (into #{} (filter #(some by? (claim %))) (keys results))
             :allowed-red (into #{} (filter #(some (complement by?) (claim %))) (keys results))
             :uncontracted uncontracted
             :unmerged (vec unmerged)}
      (= :design kind) (assoc :plan (:plan (task/accumulate (:tasks idx) id)))
      (and (= :design kind) plan-before) (assoc :plan-before plan-before))))

(defn- merge-keys
  "The program keys merge task id merges: what the attempts on its branch measured as changed
  (:seon.attempt/changed of its own attempts and of the tasks it needs on the same branch),
  or nil when no attempt there measured it (unknown: it overlaps every merge)."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:maybe [:set :string]]]}
  [idx id]
  (let [br (get-in idx [:tasks id :seon.task/branch])
        ts (cons id (filter #(= br (get-in idx [:tasks % :seon.task/branch])) (get-in idx [:tasks id :seon.task/needs])))
        cs (keep #(:seon.attempt/changed (get-in idx [:attempts %])) (mapcat #(get-in idx [:by-task %]) ts))]
    (when (seq cs) (into #{} cat cs))))

(defn actions
  "The actions for index idx, in the order root executes them (the full view's rule): reap,
  kill, judge, then start ready tasks up to max-running, merges up to max-merging. Reads the
  index's sets: its cost is the attempts to reap, kill and judge plus the starts."
  {:malli/schema [:=> [:cat :seon.reconcile/index] :seon.reconcile/actions]}
  [idx]
  (let [root (or (:root idx) {:boot "" :max-running default-max-running :max-merging default-max-merging})
        att (:attempts idx)
        reaps (for [aid (sort (:reap idx))] {:op :reap :attempt aid})
        kills (for [[aid r] (sort-by key (:kill idx))] {:op :kill :attempt aid :reason r})
        ;; a failed attempt (refused by the done rule, or its agent died) attaches a
        ;; refinement chain while its task has budget; spent, the task is stuck (bail)
        failed (fn [aid id t problem] {:op (if (refine? t) :attach :bail) :attempt aid :task id :problem problem})
        judged (for [aid (sort (:judge idx))
                     :let [a (att aid) id (:seon.attempt/task a) t (get-in idx [:tasks id])
                           merge? (= :merge (:seon.task/kind t))]]
                 (cond
                   (not= :running (:seon.task/execution t)) {:op :close :attempt aid}
                   (= :dead (:seon.attempt/status a))
                   (failed aid id t (str "its agent died" (some->> (:seon.attempt/reason a) (str ": "))))
                   :else
                   (let [rs (done-refusals (done-input idx id (:seon.attempt/results a {}) (:seon.attempt/uncontracted a #{})
                                                           (:seon.attempt/unmerged a []) (:seon.attempt/basis-t a) (:seon.attempt/plan a)))]
                     (cond
                       ;; root's ruling (2026-10-22): the process tests the branch's change
                       ;; reaches gate its merge like proofs, run on a checkout (:gate, seon.fx/run-tests)
                       (and (empty? rs) merge?)
                       (let [deferred (sort-by str (:seon.attempt/deferred a #{})) dres (:seon.attempt/deferred-results a)
                             red (for [t deferred :let [s (get dres t)] :when (not= :pass s)] [t (or s :unknown)])]
                         (cond (empty? deferred) {:op :ready-to-merge :attempt aid :task id}
                               (nil? dres) {:op :gate :attempt aid :task id :tests (vec deferred)}
                               (empty? red) {:op :ready-to-merge :attempt aid :task id}
                               :else (failed aid id t (str "attempt " aid "'s deferred tests, run on a checkout at its merge, are not green: "
                                                           (str/join ", " (for [[t s] red] (str t " " (name s))))))))
                       (empty? rs) {:op :accept :attempt aid :task id}
                       :else
                       (failed aid id t (str "attempt " aid " ended " (name (:seon.attempt/outcome a :error))
                                             (some->> (:seon.attempt/reason a) (str " (") (#(str % ")")))
                                             ": " (refusals-text rs)))))))
        ;; the octopus merge (bors, GitHub's merge queue): green merge attempts merge together in
        ;; one commit once no merge attempt is still running, so every member merged the same
        ;; main; an octopus merge closes when its first member ends (no new member joins until it
        ;; merges, so merging cannot starve)
        ready (filter #(= :ready-to-merge (:op %)) judged)
        live-merge? (some (:live idx) (:merging idx))
        ;; one gate at a time, while no merge is in flight (it runs on the merges branch's head)
        gates (when-not live-merge? (take 1 (filter #(= :gate (:op %)) judged)))
        judged (remove #(#{:ready-to-merge :gate} (:op %)) judged)
        octopus (when (and (seq ready) (not live-merge?))
                [{:op :octopus-merge :attempt (:attempt (first ready)) :members (mapv #(select-keys % [:attempt :task]) ready)}])
        closed? (some #(= :ended (get-in idx [:attempts % :seon.attempt/status])) (:merging idx))
        room (max 0 (- (:max-running root default-max-running) (- (count (:live idx)) (count (:kill idx)))))
        merge? (fn [id] (= :merge (get-in idx [:tasks id :seon.task/kind])))
        merge-room (max 0 (- (:max-merging root default-max-merging) (count (:merging idx))))
        ;; a merge joins only when what it changes is disjoint from every merge in flight;
        ;; one whose changes are unknown runs alone; conflicting merges serialize
        flying (map #(merge-keys idx (get-in idx [:attempts % :seon.attempt/task])) (:merging idx))
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
        spawns (for [id starts :let [t (get-in idx [:tasks id])]]
                 {:op :spawn :task id :env (desired-env t)
                  :attempt (str id "#" (inc (count (get-in idx [:by-task id]))))})]
    (vec (concat reaps kills judged gates octopus spawns))))

;; ---- stuck: observed, never held ------------------------------------------------------

(defn blocks
  "The released tasks not done that need task id, transitively, sorted."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.task/id] [:vector :seon.task/id]]}
  [idx id]
  (loop [front [id] seen #{}]
    (if-let [[x & more] (seq front)]
      (let [ns (remove seen (get-in idx [:needed-by x]))]
        (recur (into (vec more) ns) (into seen ns)))
      (vec (sort (filter #(and (= :running (get-in idx [:tasks % :seon.task/execution])) (not (idx-done? idx %))) seen))))))

(defn stuck
  "Why released work is not done, as root causes: each task whose stuck fact holds (its
  budget spent), each task whose latest attempt root refused to spawn (its reason: the
  branch is held, or too many live agents), and each task not released (cancelled, held)
  that released work needs, with the released tasks it blocks. Read once at quiescence: one
  pass over the tasks."
  {:malli/schema [:=> [:cat :seon.reconcile/index] [:vector :seon.reconcile/stuck-row]]}
  [idx]
  (vec (for [[id t] (sort-by key (:tasks idx))
             :when (not (idx-done? idx id))
             :let [s (:seon.task/stuck t)
                   ex (:seon.task/execution t)
                   ;; attempts are numbered in order (actions' spawns), so the latest is the count's
                   latest (get-in idx [:attempts (str id "#" (count (get-in idx [:by-task id])))])
                   refused (when (= :refused (:seon.attempt/status latest)) latest)
                   bs (blocks idx id)]
             :when (if (= :running ex) (or s refused) (seq bs))]
         (cond-> {:task id :cause (cond s (:cause s) refused (:seon.attempt/reason refused) :else (name ex)) :blocks bs}
           s (assoc :attempt (:attempt s))
           (and refused (not s)) (assoc :attempt (:seon.attempt/id refused))))))

(defn stuck-tx
  "Task id is stuck: attempt aid failed with its budget spent. The problem and the derived
  fact (the cause and what it blocks); the task stays released, its execution the owner's."
  {:malli/schema [:=> [:cat :seon.reconcile/index :seon.attempt/id :seon.task/id :string] [:vector :map]]}
  [idx aid id problem]
  [{:seon.task/id id :seon.task/problem problem
    :seon.task/stuck {:attempt aid :cause problem :blocks (blocks idx id)}}])

(defn quiescent
  "Main's last quiescence report on db ({:t :stuck}, written by the reconciler proc), or nil."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.root/quiescent]]}
  [db]
  (:seon.root/quiescent (d/pull db [:seon.root/quiescent] [:seon.root/id :root])))

(defn last-commit
  "The merges commit of db's last merged attempt (the write-back's sha), or nil."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :string]]}
  [db]
  (some->> (d/q '[:find ?t ?c :where [?e :seon.attempt/commit ?c] [?e :seon.attempt/merged-t ?t]] db) (sort-by first) last second))

(defn generation
  "The next generation on db: the last merges commit the :build effect built or refused
  ({:commit :checkout :binary :ms} or {:commit :ms :error}, written by my.root/build!), or
  nil."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.root/generation]]}
  [db]
  (:seon.root/generation (d/pull db [:seon.root/generation] [:seon.root/id :root])))

;; ---- queries, over one index (list it once: (index db)) -----------------------------

(defn reconcile
  "The actions that move db's observed state toward its desired state (index, then actions)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.reconcile/actions]}
  [db] (actions (index db)))

(defn running
  "The attempts running now (in this boot), by id."
  {:malli/schema [:=> [:cat :seon.reconcile/index] [:set :seon.attempt/id]]}
  [idx] (:live idx))

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
          (some #(= :running (get-in idx [:attempts % :seon.attempt/status])) (get-in idx [:by-task id])) :running
          (seq (blocked-by idx id)) :blocked
          (= :held x) (if (get-in idx [:tasks id :seon.task/failure]) :failed :held)
          :else :open)))

;; ---- what an attempt's agent wrote on its branch ----

(defn pull-end
  "The end attempt aid's turn loop wrote on branch db ({:outcome :reason :turns :tokens},
  and the error that ended it and its waste as data), or {}."
  {:malli/schema [:=> [:cat :seon.host/db :seon.attempt/id] :map]}
  [db aid]
  (into {} (for [[k v] (d/pull db [:seon.end/outcome :seon.end/reason :seon.end/turns :seon.end/tokens :seon.end/error :seon.end/waste]
                                [:seon.end/attempt aid])]
              [(keyword (name k)) v])))

(defn- error-text
  "Error map e as an attempt's stored error (:seon.attempt/error): the one error map, its
  protocol members (a test count, :ok, :merged?) left out."
  {:malli/schema [:=> [:cat :map] :map]}
  [e] (dissoc e :pass :fail :error :reports :merged? :ok :tx :applied :conflicts))

;; ---- the reconciler proc: actions carried out as requests, every answer as data ----------
;; Root's executor (R1's proto.agents.root, a thread per attempt) as one step-fn: main's
;; commits arrive through a sliding buffer of one (a reconciler reads main's latest db, so a
;; commit it missed is covered by the next); each action is a flight, a short chain of
;; requests to the api (spawn, eval, tests, the writer's reads and writes, kill), each answer
;; arriving on :answers keyed by its request id. An action whose attempt is in flight is not
;; started twice.

(defn- cas-tx
  "Move attempt aid's status from -> to (Datahike's compare-and-swap, by lookup ref: a racing
  observer that already moved it leaves it), with the observed members m."
  {:malli/schema [:=> [:cat :seon.attempt/id :seon.attempt/status :seon.attempt/status :map] [:vector :any]]}
  [aid from to m]
  (cond-> [[:db/cas [:seon.attempt/id aid] :seon.attempt/status from to]]
    (seq m) (conj (assoc m :seon.attempt/id aid))))

(defn- request
  "Send req to the api as this proc's request: the answer comes back on :answers tagged by a
  new request id, which then names step of attempt aid's flight. Answers [st output]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.attempt/id :seon.reconcile/step :map] [:tuple :seon.reconcile/state :map]]}
  [st aid step req] (step/ask st [:reconciler :answers] [aid step] req))

(defn- write
  "A write on main through the writer, as step of aid's flight."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.attempt/id :seon.reconcile/step [:sequential :any]] [:tuple :seon.reconcile/state :map]]}
  [st aid step tx] (request st aid step {:op :write :req {:op :tx :branch :db :tx-data (vec tx)}}))

(defn- start
  "Begin action act's flight. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.reconcile/action] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st {:keys [op attempt] :as act}]
  (let [;; a kill reaches a live attempt whose observer chain is in flight: that flight stays
        ;; and sees its agent die
        killing? (and (= :kill op) (get-in st [:flights attempt]))
        st (if killing? (assoc-in st [:flights attempt :killing] true) (assoc-in st [:flights attempt] {:act act}))
        a (task/attempt (:db st) attempt)
        agent (:seon.attempt/agent a)
        one (fn [[st o]] [st [o]])]
    (case op
      :spawn (one (request st attempt :spawned {:op :spawn :opts {:branch (get-in act [:env :branch]) :task (:task act) :attempt attempt}}))
      :reap (one (write st attempt :done (cas-tx attempt :running :lost {:seon.attempt/end-ms (System/currentTimeMillis) :seon.attempt/reason "root restarted"})))
      :close (one (write st attempt :done (cas-tx attempt :ended :closed {})))
      :accept (one (write st attempt :done (cas-tx attempt :ended :done {})))
      :kill (let [[st o1] (request st attempt :ignored {:op :kill :id agent})
                  [st o2] (write st attempt (if killing? :ignored :done) (cas-tx attempt :running :killed {:seon.attempt/end-ms (System/currentTimeMillis)
                                                                                    :seon.attempt/reason (name (:reason act))}))]
              [st [o1 o2]])
      ;; budget spent: the task is stuck, observed; its release stays the owner's
      :bail (one (write st attempt :done (into (cas-tx attempt (:seon.attempt/status a) :bailed {})
                                               (stuck-tx (:idx st) attempt (:task act) (:problem act)))))
      ;; a merge task's branch into main: clean by construction when the branch holds main's
      ;; program head; behind: its task runs again, a new agent
      ;; an octopus merge: every member's branch into main in one commit (each holds main's
      ;; program head, the members' changes are disjoint); a member found behind or refused
      ;; is answered alone
      :octopus-merge (let [bs (mapv #(get-in (task/attempt (:db st) (:attempt %)) [:seon.attempt/env :branch]) (:members act))]
                     (one (request (assoc-in st [:flights attempt :branches] bs) attempt :octopus-merged
                                   {:op :write :req {:op :octopus-merge :branches bs :data-attrs #{}}})))
      :attach (one (write st attempt :done (tpl/attach-tx (:db st) act)))
      ;; the merge gate: the candidate's files read from its branch, then its deferred tests
      ;; run on a checkout of the merges head with them (seon.fx/run-tests)
      :gate (one (request st attempt :gate-read
                          {:op :write :req {:op :branch-db :branch (get-in (task/attempt (:db st) attempt) [:seon.attempt/env :branch])}})))))

(defn- judge-tx
  "Attempt aid's end on main from MEASURED facts: the end its agent wrote on the branch,
  root's own proof results, the branch's merge entries and unmerged keys, the contract gaps
  against main; the task content the branch changed carried to main, or its conflicts with
  main's recorded on the attempt for root (seon.task/carry-tx); and a failure it marked
  (my.task/fail!) carried with the task held."
  {:malli/schema [:=> [:cat :seon.reconcile/state :map :seon.proof/results [:map [:db :seon.host/db] [:base :seon.host/db]] [:set :string]] [:vector :any]]}
  [st {:keys [act run-problems worked-error]} results {:keys [db base base-t]} changed]
  (let [main (:db st)
        aid (:attempt act) id (:task act)
        end (pull-end db aid)
        mp (pm/program main #{})
        unc (into #{} (for [[[k sym] e] (pm/program db #{})
                            :when (and (= :var k) (= :fn (:kind e)) (nil? (:contract e)) (not= e (get mp [k sym])))]
                        sym))
        carried (task/carry-tx base db main)
        failure (:seon.task/failure (task/task db id))]
    (into (cas-tx aid :running :ended
                    ;; spend is what the end measured: no end (died, killed, never reached
                    ;; seon.turn/end!) is outcome :no-end with its turns, calls and tokens absent
                    (merge (task/end-members (cond-> (merge (select-keys end [:turns :model-calls :tokens])
                                                      {:outcome (:outcome end :no-end) :results results :uncontracted unc
                                                       :conflicts (mapv #(dissoc % :main-t :resolved?) (pm/entries db))
                                                       :unmerged (mapv #(sedn/encode (:key %)) (pm/unmerged db (pm/program db #{})))
                                                       :changed changed
                                                       :deferred (:deferred (program/test-split db (program/changed-vars base db)
                                                                                                (:proofs (:seon.task/accumulated (task/task main id)))))})
                                        (:reason end) (assoc :reason (:reason end))
                                        (not (:outcome end)) (assoc :reason (if worked-error
                                                                             (str "seon.turn/work! threw: " (:seon.error/message worked-error))
                                                                             "the agent wrote no end")))
                                      (System/currentTimeMillis) (or base-t (:max-tx main)))
                    ;; the problems measured at its end (session D2): red assertions, cost verdicts,
                    ;; the error that ended it, its waste
                    (cond-> {}
                      (seq (:failures run-problems)) (assoc :seon.attempt/failures (:failures run-problems))
                      (seq (:costs run-problems)) (assoc :seon.attempt/costs (:costs run-problems))
                      (or (:error end) worked-error) (assoc :seon.attempt/error (error-text (or (:error end) worked-error)))
                      (seq (:waste end)) (assoc :seon.attempt/waste (:waste end))
                      (seq (:conflicts carried)) (assoc :seon.attempt/task-conflicts (:conflicts carried)))))
            (concat (:tx carried)
                    ;; marked failed in this attempt (my.task/fail!): the failure on main and the
                    ;; task held for root, so the judge closes the attempt (no refinement)
                    (when (= aid (:attempt failure)) [{:seon.task/id id :seon.task/failure failure :seon.task/execution :held}])))))

(defn- dead-tx
  "Attempt aid's death on main: the end its agent wrote on its branch before it exited (its
  reason, and its error as the cause), beside e, the death root observed (:seon.agent/died:
  exit status, stderr tail, last op; or :seon.root/no-agent). A silent death wrote no end."
  {:malli/schema [:=> [:cat :seon.attempt/id :seon.error/base :map] [:vector :any]]}
  [aid e end]
  (cas-tx aid :running :dead {:seon.attempt/end-ms (System/currentTimeMillis)
                              :seon.attempt/reason (if (:reason end) (str (:reason end) " (" (:seon.error/message e) ")") (:seon.error/message e))
                              :seon.attempt/error (error-text (cond-> e (:error end) (assoc :cause (:error end))))}))

(defn- advance
  "Attempt aid's flight takes answer r of its step. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state :seon.attempt/id :seon.reconcile/step :any] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st aid step r]
  (let [{:keys [act agent] :as f} (get-in st [:flights aid])
        done (fn [st] [(update st :flights dissoc aid) []])
        one (fn [[st o]] [st [o]])
        ;; its agent died: the one error map root answered with (:seon.agent/died: exit
        ;; status, last op, stderr tail; or :seon.root/no-agent), and the end the agent wrote
        ;; on its branch before it exited, read from the branch
        dead (fn [st e] (one (request (assoc-in st [:flights aid :died] e) aid :dead-read
                                      {:op :write :req {:op :branch-db :branch (get-in act [:env :branch])}})))]
    (case step
      :ignored [st []]
      :done (done st)
      :dead-read (one (write st aid :done (dead-tx aid (:died f) (if (:db r) (pull-end (:db r) aid) {}))))
      :spawned (if-let [ag (:id r)]
                 (one (write (assoc-in st [:flights aid :agent] ag) aid :started
                             (task/attempt-start-tx aid (:task act) (:boot st) ag (:env act) (System/currentTimeMillis)
                                                   (:plan (task/accumulate (get-in st [:idx :tasks]) (:task act))))))
                 ;; root refused it (the branch is held, or too many live agents): the refusal
                 ;; is the attempt, written on main with root's reason; the task starts again,
                 ;; as its next attempt, once main moves for another reason (:refused, pass)
                 (one (write (update st :refused (fnil conj #{}) (:task act)) aid :done
                             [(let [now (System/currentTimeMillis)]
                                (-> (first (task/attempt-start-tx aid (:task act) (:boot st) nil (:env act) now
                                                                  (:plan (task/accumulate (get-in st [:idx :tasks]) (:task act)))))
                                    (assoc :seon.attempt/status :refused :seon.attempt/end-ms now
                                           :seon.attempt/reason (:seon.error/message r) :seon.attempt/error (error-text r))))])))
      ;; an outside agent works this attempt (seon.server): no turn loop; it hands it back
      :started (if (= "outside" (get-in act [:env :model]))
                 (done st)
                 (one (request st aid :worked
                               {:op :eval :id agent
                                :forms (sedn/encode (list 'seon.turn/work! (list 'quote (cond-> {:task (:task act) :attempt aid :max-turns (get-in act [:env :max-turns])}
                                                                                       (get-in act [:env :model]) (assoc :model (get-in act [:env :model]))))))})))
      ;; the turn loop answered: died (no live agent), or judge it from measured facts
      ;; work! that threw was answered by the agent's REPL (the process lives): its error
      ;; names the attempt's end when the loop wrote none
      :worked (if (= -1 (:id (first r)))
                (dead st (:err (first r)))
                (one (request (cond-> st (= :err (:tag (first r))) (assoc-in [:flights aid :worked-error] (:err (first r))))
                              aid :tested {:op :tests :id agent})))
      :tested (if (contract/error? r)
                (dead st r)
                (one (request (-> st (assoc-in [:flights aid :results] (proofs/results r))
                                  (assoc-in [:flights aid :run-problems] {:failures (proofs/failures r) :costs (proofs/costs r)}))
                              aid :read {:op :write :req {:op :branch-db :branch (get-in act [:env :branch])}})))
      ;; what the branch changed since its merge base (three-way's :ours), measured for the octopus merge
      :read (one (request (assoc-in st [:flights aid :branch-db] r) aid :diffed
                          {:op :write :req {:op :branch-diff :branch (get-in act [:env :branch])}}))
      ;; every attempt is a new agent: none is reused
      ;; root stops it because its attempt ended: its view says :ended, not :killed
      :diffed (let [[st o1] (request st aid :ignored {:op :kill :id agent :ended true})
                    [st o2] (write st aid :done (judge-tx st f (:results f) (:branch-db f) (into #{} (map sedn/encode) (:ours r))))]
                [st [o1 o2]])
      ;; each member's answer, as one write: merged at the octopus merge's t, behind (its merge task
      ;; runs again, a new agent), or refused: a failed attempt like any other (a refinement
      ;; chain while the budget lasts, else stuck)
      ;; the merged members' namespaces that are Seon's own source are written back: main's
      ;; files after the octopus merge, committed on the merges branch (:git), the sha recorded on
      ;; each merged attempt (the self-improvement loop: the next generation builds it)
      :octopus-merged (let [merged (keep (fn [[m x]] (when (:merged? x) [m x])) (map vector (:members act) (:results r)))
                     paths (vec (sort (distinct (mapcat (comp :files second) merged))))
                     back? (and (:source st) (seq paths))]
                 (one (write (cond-> st back? (assoc-in [:flights aid :writeback] {:files paths :members (mapv first merged)}))
                             aid (if back? :write-back :done)
                           (vec (mapcat (fn [{:keys [attempt task]} r]
                                          (let [problem (str "merge refused: " (:seon.error/message r))]
                                            (cond
                                              (:merged? r) (cas-tx attempt :ended :merged {:seon.attempt/merged-t (long (:tx r))})
                                              (= :seon.merge/behind (:seon.error/type r)) (cas-tx attempt :ended :behind {:seon.attempt/reason (:seon.error/message r)
                                                                                                                          :seon.attempt/error (error-text r)})
                                              (refine? (get-in st [:idx :tasks task]))
                                              (conj (tpl/attach-tx (:db st) {:attempt attempt :task task :problem problem})
                                                    {:seon.attempt/id attempt :seon.attempt/error (error-text r)})
                                              :else (into (cas-tx attempt :ended :bailed {:seon.attempt/error (error-text r)})
                                                          (stuck-tx (:idx st) attempt task problem)))))
                                        (:members act) (:results r))))))
      ;; the gate's candidate: the files of the namespaces the branch changed, as it holds them
      :gate-read (let [nss (sort (distinct (for [k (:seon.attempt/changed (task/attempt (:db st) aid))
                                                  :let [[kind x] (read-string k)]
                                                  :when (#{:var :form} kind)]
                                              (symbol (first (str/split (str x) #"/" 2))))))
                       files (source/files (:db r) (vec (keep #(:ns/file (d/entity (:db r) [:ns/name %])) nss)))]
                   (one (request st aid :gated {:op :fx :kind :tests :req {:repo (:root (:source st)) :branch (:branch (:source st))
                                                                          :files files :tests (:tests act)}})))
      ;; each deferred test's status on the attempt; the next judgment lands it or refuses it
      :gated (let [rs (if (contract/error? r) (zipmap (:tests act) (repeat :error)) (get-in r [:value :results]))]
               (one (write st aid :done (cond-> [{:seon.attempt/id aid
                                                  :seon.attempt/deferred-results (vec (for [[t s] rs] {:seon.proof/test t :seon.proof/status s}))}]
                                          (contract/error? r) (conj {:seon.attempt/id aid :seon.attempt/error (error-text r)})))))
      :write-back (one (request st aid :main-read {:op :write :req {:op :branch-db :branch :db}}))
      :main-read (let [{:keys [members] paths :files} (:writeback f)
                       files (source/files (:db r) paths)]
                   (if (empty? files)
                     (done st)
                     (one (request st aid :committed
                                   {:op :fx :kind :git :req {:dir (:root (:source st)) :branch (:branch (:source st)) :files files
                                                             :message (source/commit-message members)}}))))
      ;; the sha on each merged attempt; the merge already runs where main's program runs
      ;; (seon.program/load-changes!), so nothing is built (BUILD plan P4)
      :committed (let [sha (get-in r [:value :sha])
                       m (cond sha {:seon.attempt/commit sha}
                               (contract/error? r) {:seon.attempt/commit-error (:seon.error/message r)})]
                   (if m
                     (one (write st aid :done (vec (for [{:keys [attempt]} (:members (:writeback f))] (assoc m :seon.attempt/id attempt)))))
                     (done st))))))

(defn- quiet
  "Report quiescence positively, once per quiet spell: when no flight is in the air and no
  attempt is live after a pass that started nothing, write main's :seon.root/quiescent (the
  t judged, and the stuck rows), and ask the writer to collect the store (:collect: it sweeps
  only when a branch is gone, and root refuses it while an agent is live). Their answers are
  not awaited (no flight), and the commits they make touch only root, which does not end the
  spell. Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state [:vector :map]] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st outs]
  (if (or (:quiet st) (seq outs) (seq (:flights st)) (seq (:live (:idx st))))
    [st outs]
    (let [fact {:t (:t st) :stuck (stuck (:idx st))}
          [st q] (step/ask st [:reconciler :answers] nil {:op :write :req {:op :tx :branch :db :tx-data [{:seon.root/id :root :seon.root/quiescent fact}]}})
          [st c] (step/ask st [:reconciler :answers] nil {:op :write :req {:op :collect}})]
      [(assoc st :quiet true :quiescent fact) [q c]])))

(defn- pass
  "Reconcile main's latest db and start each action whose attempt is not in flight; spawns
  wait while earlier spawns have not written their attempt yet (max-running counts facts),
  and a task root refused waits for main to move for another reason (if nothing has
  changed, asking again is refused again); then quiescence, when nothing is left to do.
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :seon.reconcile/state] [:tuple :seon.reconcile/state [:vector :map]]]}
  [st]
  (let [early (count (filter #(and (= :spawn (get-in % [:act :op])) (not (:agent %))) (vals (:flights st))))
        acts (remove #(let [f (get-in st [:flights (:attempt %)])]
                        (or (and f (or (not= :kill (:op %)) (:killing f)))
                            (and (= :spawn (:op %)) (contains? (:refused st) (:task %)))))
                     (actions (:idx st)))
        acts (concat (remove #(= :spawn (:op %)) acts) (drop early (filter #(= :spawn (:op %)) acts)))]
    (apply quiet (reduce (fn [[st outs] act] (let [[st o] (start st act)] [st (into outs o)])) [st []] acts))))

(defn reconciler-proc
  "The reconciler. In :main (main's commits {:t :ops :db}, a sliding buffer of one) and :answers (the
  api's answers to its requests). Out :to-api (its requests, as {:op :request ...}).
  Args: :boot (this root's boot id); in :judge (outside agents' attempts handed back).
  Starts deepest-first (finish chains under way)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:boot "root's boot id" :judge "outside agents' attempts to judge (external)"}
       :ins {:main "main's commits" :answers "the api's answers"} :outs {:to-api "requests"}
       :workload :io
       :ping-map-fn (fn [st] {:seen (:seen st 0) :lists (:lists st 0) :t (:t st) :flights (count (:flights st)) :pending (count (:pending st))
                              :quiet (boolean (:quiet st)) :stuck (count (:stuck (:quiescent st)))})})
  ([args] (assoc (dissoc args :judge) :seen 0 :rid 0 :flights {} :pending {} ::flow/in-ports {:judge (:judge args)}))
  ([st _] st)
  ([st in m]
   (case in
     ;; each commit's datoms move the index (observe); a commit the sliding buffer dropped
     ;; (main's t is not the next one) lists main again (index), k8s's relist after a gap
     :main (let [db (:db m) t (:max-tx db)
                 next? (and (:idx st) (= t (inc (:t st))))
                 idx (if next? (observe (:idx st) db (:ops m)) (index db))
                 ;; a commit touching tasks, attempts or config keys ends a quiet spell (root's own
                 ;; quiescent fact does not)
                 work? (or (not next?) (some (fn [[_ _ a]] (and (keyword? a) (watched-ns (namespace a)) (not= "seon.root" (namespace a)))) (:ops m)))
                 ;; a commit holding only refused attempts (the refusals' own writes) changes
                 ;; nothing that refused them; any other work lets a refused task ask again
                 refusals? (and next? (every? (fn [[_ e a]] (or (not (and (keyword? a) (watched-ns (namespace a))))
                                                               (= :refused (get-in idx [:attempts (second (get-in idx [:keys e])) :seon.attempt/status]))))
                                              (:ops m)))
                 st (assoc st :db db :t t :idx idx :seen (inc (:seen st)) :lists (cond-> (:lists st 0) (not= t (inc (:t st -2))) inc)
                           :quiet (boolean (and (:quiet st) (not work?)))
                           :refused (if (and work? (not refusals?)) #{} (:refused st #{})))
                 [st outs] (pass st)]
             [st {:to-api (into [{:op :stat :k :main-seen-us :v (/ (- (profile/now-ns) (:ns m)) 1000.0)}] outs)}])
     ;; an outside agent handed its attempt back: judged as a turn loop's end is
     :judge (let [{:keys [attempt task env agent]} m
                  st (assoc-in st [:flights attempt] {:act {:op :spawn :attempt attempt :task task :env env} :agent agent})
                  [st o] (request st attempt :tested {:op :tests :id agent})]
              [st {:to-api [o]}])
     :answers (let [[st [aid step]] (step/answered st (:tag m))
                    [st outs] (if aid (advance st aid step (:r m)) [st []])
                    ;; a flight that ended may free its attempt for the next action
                    [st more] (if (and aid (not (get-in st [:flights aid])) (:db st)) (pass st) [st []])]
                [st {:to-api (into outs more)}]))))
