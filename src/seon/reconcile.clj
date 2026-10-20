(ns seon.reconcile
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
  (->> (vals (:tasks v)) (filter #(ready? v %)) (map :seon.task/id) (sort-by (juxt #(depth v %) identity)) vec))

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
  {:malli/schema [:=> [:cat :map] :seon.reconcile/actions]}
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
  {:malli/schema [:=> [:cat :seon.host/db] :seon.reconcile/actions]}
  [db] (reconcile-view (view db)))

;; ---- what an attempt's agent wrote on its branch ----

(defn pull-end
  "The end attempt aid's turn loop wrote on branch db ({:outcome :reason :turns :tokens}), or {}."
  {:malli/schema [:=> [:cat :seon.host/db :seon.attempt/id] :map]}
  [db aid]
  (let [e (d/q '[:find ?e . :in $ ?a :where [?e :seon.end/attempt ?a]] db aid)]
    (if e
      (let [m (d/pull db [:seon.end/outcome :seon.end/reason :seon.end/turns :seon.end/tokens] e)]
        (into {} (for [[k v] m] [(keyword (name k)) v])))
      {})))

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
  {:malli/schema [:=> [:cat :map :seon.attempt/id :keyword :map] [:tuple :map :map]]}
  [st aid step req]
  (let [rid (inc (:rid st))]
    [(-> st (assoc :rid rid) (assoc-in [:pending rid] [aid step]))
     {:op :request :req req :route [:reconciler :answers] :tag rid}]))

(defn- write
  "A write on main through the writer, as step of aid's flight."
  {:malli/schema [:=> [:cat :map :seon.attempt/id :keyword [:sequential :any]] [:tuple :map :map]]}
  [st aid step tx] (request st aid step {:op :write :req {:op :tx :branch :db :tx-data (vec tx)}}))

(defn- provider-for
  "The provider an attempt's env asks for: the proc's provider, its model the task's when set."
  {:malli/schema [:=> [:cat :map :seon.task/env] :seon.model/provider]}
  [st env]
  (cond-> (:provider st) (and (:model env) (= :openai-compat (:kind (:provider st)))) (assoc :model (:model env))))

(defn- start
  "Begin action act's flight. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :seon.reconcile/action] [:tuple :map [:vector :map]]]}
  [st {:keys [op attempt] :as act}]
  (let [;; a kill reaches a live attempt whose observer chain is in flight: that flight stays
        ;; and sees its agent die
        killing? (and (= :kill op) (get-in st [:flights attempt]))
        st (if killing? (assoc-in st [:flights attempt :killing] true) (assoc-in st [:flights attempt] {:act act}))
        a (task/attempt (:db st) attempt)
        agent (:seon.attempt/agent a)
        one (fn [[st o]] [st [o]])]
    (case op
      :spawn (one (request st attempt :spawned {:op :spawn :opts {:branch (get-in act [:env :branch]) :task (:task act)}}))
      :reap (one (write st attempt :done (cas-tx attempt :running :lost {:seon.attempt/end-ms (System/currentTimeMillis) :seon.attempt/reason "root restarted"})))
      :close (one (write st attempt :done (cas-tx attempt :ended :closed {})))
      :accept (one (write st attempt :done (cas-tx attempt :ended :done {})))
      :kill (let [[st o1] (request st attempt :ignored {:op :kill :id agent})
                  [st o2] (write st attempt (if killing? :ignored :done) (cas-tx attempt :running :killed {:seon.attempt/end-ms (System/currentTimeMillis)
                                                                                    :seon.attempt/reason (name (:reason act))}))]
              [st [o1 o2]])
      :bail (one (write st attempt :done (conj (cas-tx attempt (:seon.attempt/status a) :bailed {})
                                               {:seon.task/id (:task act) :seon.task/execution :held :seon.task/problem (:problem act)})))
      ;; a merge task's branch into main: clean by construction when the branch holds main's
      ;; program head; behind: its task runs again, a new agent
      :merge (one (request st attempt :merged {:op :write :req {:op :merge :branch (get-in a [:seon.attempt/env :branch])
                                                                 :agent agent :data-attrs #{}}}))
      :attach (one (write st attempt :done (tpl/attach-tx (:db st) act))))))

(defn- judge-tx
  "Attempt aid's end on main from MEASURED facts: the end its agent wrote on the branch,
  root's own proof results, the branch's merge entries and unmerged keys, the contract gaps
  against main, its improved plan carried to the task."
  {:malli/schema [:=> [:cat :map :map :seon.proof/results :map] [:vector :any]]}
  [st {:keys [act]} results {:keys [db base-t]}]
  (let [main (:db st)
        aid (:attempt act) id (:task act)
        end (pull-end db aid)
        mp (pm/program main #{})
        unc (into #{} (for [[[k sym] e] (pm/program db #{})
                            :when (and (= :var k) (= :fn (:kind e)) (nil? (:contract e)) (not (:flagged e)) (not= e (get mp [k sym])))]
                        sym))
        plan (:seon.task/plan (task/task db id))]
    (into (cas-tx aid :running :ended
                    (task/end-members (cond-> {:outcome (:outcome end :error) :turns (:turns end 0) :model-calls (:turns end 0)
                                               :tokens (:tokens end 0) :ms 0.0 :results results :uncontracted unc
                                               :conflicts (mapv #(dissoc % :main-t :resolved?) (pm/entries db))
                                               :unmerged (mapv #(pr-str (:key %)) (pm/unmerged db (pm/program db #{})))}
                                        (:reason end) (assoc :reason (:reason end))
                                        (not (:outcome end)) (assoc :reason "the agent wrote no end"))
                                      (System/currentTimeMillis) (or base-t (:max-tx main))))
            (when (and plan (not= plan (:seon.task/plan (task/task main id)))) (task/plan-tx id plan)))))

(defn- advance
  "Attempt aid's flight takes answer r of its step. Answers [st outputs]."
  {:malli/schema [:=> [:cat :map :seon.attempt/id :keyword :any] [:tuple :map [:vector :map]]]}
  [st aid step r]
  (let [{:keys [act agent] :as f} (get-in st [:flights aid])
        done (fn [st] [(update st :flights dissoc aid) []])
        one (fn [[st o]] [st [o]])
        dead (fn [st why] (one (write st aid :done (cas-tx aid :running :dead {:seon.attempt/end-ms (System/currentTimeMillis)
                                                                               :seon.attempt/reason why}))))]
    (case step
      :ignored [st []]
      :done (done st)
      :spawned (if-let [ag (:id r)]
                 (one (write (assoc-in st [:flights aid :agent] ag) aid :started
                             (task/attempt-start-tx aid (:task act) (:boot st) ag (:env act) (System/currentTimeMillis))))
                 (done st))
      :started (one (request st aid :worked
                             {:op :eval :id agent
                              :forms (pr-str (list 'seon.turn/work! (list 'quote {:task (:task act) :attempt aid :max-turns (get-in act [:env :max-turns])
                                                                                    :provider (provider-for st (:env act))})))}))
      ;; the turn loop answered: died (no live agent), or judge it from measured facts
      :worked (if (= -1 (:id (first r)))
                (dead st (str (:printed (first r))))
                (one (request st aid :tested {:op :tests :id agent :timeout-ms 30000})))
      :tested (one (request (assoc-in st [:flights aid :results] (proofs/results r)) aid :read
                            {:op :write :req {:op :branch-db :branch (get-in act [:env :branch])}}))
      ;; every attempt is a new agent: none is reused
      :read (let [[st o1] (request st aid :ignored {:op :kill :id agent})
                  [st o2] (write st aid :done (judge-tx st f (:results f) r))]
              [st [o1 o2]])
      :merged (cond
                (:merged? r) (one (write st aid :done (cas-tx aid :ended :merged {:seon.attempt/merged-t (long (:tx r))})))
                (= :seon.merge/behind (:seon.error/type r)) (one (write st aid :done (cas-tx aid :ended :behind {:seon.attempt/reason (:seon.error/message r)})))
                :else (one (write st aid :done (conj (cas-tx aid :ended :bailed {})
                                                     {:seon.task/id (:task act) :seon.task/execution :held
                                                      :seon.task/problem (str "merge refused: " (or (:seon.error/message r) (:error r)))})))))))

(defn- pass
  "Reconcile main's latest db and start each action whose attempt is not in flight; spawns
  wait while earlier spawns have not written their attempt yet (max-running counts facts).
  Answers [st outputs]."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :map]]]}
  [st]
  (let [early (count (filter #(and (= :spawn (get-in % [:act :op])) (not (:agent %))) (vals (:flights st))))
        acts (remove #(let [f (get-in st [:flights (:attempt %)])] (and f (or (not= :kill (:op %)) (:killing f)))) (reconcile (:db st)))
        acts (concat (remove #(= :spawn (:op %)) acts) (drop early (filter #(= :spawn (:op %)) acts)))]
    (reduce (fn [[st outs] act] (let [[st o] (start st act)] [st (into outs o)])) [st []] acts)))

(defn reconciler-proc
  "The reconciler. In :main (main's commits, a sliding buffer of one) and :answers (the
  api's answers to its requests). Out :to-api (its requests, as {:op :request ...}).
  Args: :boot (this root's boot id), :provider (the model provider attempts use)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:boot "root's boot id" :provider "the attempts' model provider"}
       :ins {:main "main's commits" :answers "the api's answers"} :outs {:to-api "requests"}
       :workload :io
       :ping-map-fn (fn [st] {:seen (:seen st 0) :t (some-> (:db st) :max-tx) :flights (count (:flights st)) :pending (count (:pending st))})})
  ([args] (assoc args :seen 0 :rid 0 :flights {} :pending {}))
  ([st _] st)
  ([st in m]
   (case in
     :main (let [st (assoc st :db (:db m) :seen (inc (:seen st)))
                 [st outs] (pass st)]
             [st {:to-api (into [{:op :stat :k :main-seen-us :v (/ (- (sock/now-ns) (:ns m)) 1000.0)}] outs)}])
     :answers (let [[aid step] (get-in st [:pending (:tag m)])
                    st (update st :pending dissoc (:tag m))
                    [st outs] (if aid (advance st aid step (:r m)) [st []])
                    ;; a flight that ended may free its attempt for the next action
                    [st more] (if (and aid (not (get-in st [:flights aid])) (:db st)) (pass st) [st []])]
                [st {:to-api (into outs more)}]))))
