(ns seon.task
  "Tasks as data (TASK-CHAIN §1, the target model): one entity per task on main, its
  desired state written by root; runs and proof results are observed state, written
  by their observer (root's executor) when the event happens. There is no chain entity:
  `:seon.task/needs` is the one dependency edge.

  This namespace is the one writer of those facts (tx-data builders; root transacts
  them) and the one reader (pull shapes for the reconciler and the agent). From sessions R1
  and M1's proto.agents.task; the attributes are derived from resources/seon/schemas (session D1)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [clojure.string :as str] [clojure.edn :as edn] [seon.merge :as pm] [seon.db :as pdb] [seon.edn :as sedn]
            [seon.print :as print] [seon.ref :as ref]))

;; ---- identity --------------------------------------------------------------------

(defn entity-tx
  "Make entity e state exactly want on attributes attrs: per attribute, the values e holds
  that want lacks retracted and the ones it lacks asserted, so an entity that already states
  want writes nothing. In want a set is a card-many attribute's values, any other value one
  value, nil none; a ref is an entity id (a tempid or lookup ref is always asserted)."
  {:malli/schema [:=> [:cat :seon.host/db :int [:sequential :keyword] :map] [:vector :any]]}
  [db e attrs want]
  (vec (for [a attrs
             :let [v (get want a) new (cond (set? v) v (some? v) #{v} :else #{})
                   old (into #{} (map :v) (d/datoms db :eavt e a))]
             tx (concat (for [x old :when (not (new x))] [:db/retract e a x])
                        (for [x new :when (not (old x))] [:db/add e a x]))]
         tx)))

(defn task-id
  "The task's handle, ns/name: its identity, derived from its namespace and name."
  {:malli/schema [:=> [:cat :seon.task/ns :seon.task/name] :seon.task/id]}
  [ns-sym nm] (str ns-sym "/" nm))

(defn default-branch
  "The branch a task's runs check out when it names none: task-<ns>-<name>."
  {:malli/schema [:=> [:cat :seon.task/id] :seon.task/branch]}
  [id] (keyword (str "task-" (str/replace id #"[^A-Za-z0-9-]" "-"))))

;; ---- the writer ------------------------------------------------------------------

(defn- cited-text
  "The text a declaration cites from: its plan and its instructions."
  {:malli/schema [:=> [:cat :seon.task/declaration] :string]}
  [decl] (str (:seon.task/plan decl) "\n" (:seon.task/instructions decl)))

(defn- references-of
  "The :seon.task/references a declaration states: the names its text cites (seon.ref/names),
  derived and never authored; absent when it cites none."
  {:malli/schema [:=> [:cat :seon.task/declaration] :map]}
  [decl]
  (let [ns (set (ref/names (cited-text decl)))] (if (seq ns) {:seon.task/references ns} {})))

(defn citation-problems
  "Each citation of decls' text that resolves to nothing on db, as {:task :name :problem
  :unresolved}: answered beside the write, never an error (docs/references.md)."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.task/declaration]] [:vector :map]]}
  [db decls]
  (vec (for [decl decls
             c (ref/references db (cited-text decl))
             :when (not (ref/names-something? db c))]
         {:task (task-id (:seon.task/ns decl) (:seon.task/name decl))
          :name (or (:seon.ref/name c) (:seon.ref/path c)) :problem :unresolved})))

(defn- cycle-in
  "A task id on a needs cycle among decls (plus the needs already on main, which cannot
  reach a new task), or nil."
  {:malli/schema [:=> [:cat [:map-of :seon.task/id :seon.task/needs]] [:maybe :seon.task/id]]}
  [graph]
  (let [visit (fn visit [seen id]
                (cond (contains? seen id) id
                      :else (some #(visit (conj seen id) %) (get graph id))))]
    (some #(visit #{} %) (keys graph))))

(defn- invalid
  {:malli/schema [:=> [:cat :symbol :string] :map]}
  [op m] {:seon.error/type :seon.task/refused :seon.error/operation op :seon.error/message m})

(defn- needs-problem
  "Why graph (task id -> needs) cannot be written: a need known? does not hold, or a cycle;
  nil when neither."
  {:malli/schema [:=> [:cat [:map-of :seon.task/id :seon.task/needs] ifn?] [:maybe :string]]}
  [graph known?]
  (if-let [missing (seq (for [[_ ns] graph n ns :when (not (known? n))] n))]
    (str "needs name no task: " (str/join ", " missing))
    (some->> (cycle-in graph) (str "needs form a cycle through "))))

(defn create-tx
  "The transaction writing decls (task declarations) as held tasks: each gets its id and
  its branch; needs refer by id, to a task of this write or one already on db. A cycle
  or a need that names no task is the :seon.task/refused error."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.task/declaration]]
                  [:or [:vector :map] [:map [:seon.error/type [:= :seon.task/refused]] [:seon.error/message :string]]]]}
  [db decls]
  (let [ids (mapv #(task-id (:seon.task/ns %) (:seon.task/name %)) decls)
        graph (zipmap ids (map #(set (:seon.task/needs %)) decls))]
    (if-let [p (needs-problem graph #(or (graph %) (pdb/eid db :seon.task/id %)))]
      (invalid 'seon.task/create-tx p)
      (mapv (fn [id {:seon.task/keys [config needs evidence] :as decl}]
              (cond-> (-> decl (dissoc :seon.task/config :seon.task/needs :seon.task/evidence)
                          (assoc :db/id id :seon.task/id id :seon.task/execution :held)
                          (update :seon.task/branch #(or % (default-branch id))))
                (seq needs) (assoc :seon.task/needs (mapv #(if (graph %) % [:seon.task/id %]) needs))
                evidence (assoc :seon.task/evidence evidence)
                (seq config) (merge config)
                true (merge (references-of decl))))
            ids decls))))

(defn execution-tx
  "Set the desired execution of tasks ids (:running is root's release, run!)."
  {:malli/schema [:=> [:cat [:sequential :seon.task/id] :seon.task/execution] [:vector :map]]}
  [ids execution]
  (mapv (fn [id] {:seon.task/id id :seon.task/execution execution}) ids))

(defn env-tx
  "Change task id's desired environment (branch, model, max-turns): a running run in
  another environment is restarted clean by the reconciler."
  {:malli/schema [:=> [:cat :seon.task/id [:map [:branch {:optional true} :keyword] [:model {:optional true} :string]
                                           [:max-turns {:optional true} pos-int?]]] [:vector :map]]}
  [id {:keys [branch model max-turns]}]
  [(cond-> {:seon.task/id id}
     branch (assoc :seon.task/branch branch) model (assoc :seon.task/model model)
     max-turns (assoc :seon.config.agent/max-turns max-turns))])

(defn earlier-agents-tx
  "A new boot's root is no longer the root of the agents an earlier boot started: their link to
  root retracted (their processes ended with that boot; history keeps that root started them), so
  root's agents are this boot's (root's walk, :seon.agent/_root)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :any]]}
  [db] (vec (for [[a r] (d/q '[:find ?a ?r :where [?r :seon.root/id :root] [?a :seon.agent/root ?r]] db)]
              [:db/retract a :seon.agent/root r])))

(defn boot-tx
  "Root's boot: its id for this process lifetime and its max-running config key (merges at once,
  :seon.config.merge/max-running, is written with the config in force, seon.config/facts)."
  {:malli/schema [:=> [:cat :string pos-int?] [:vector :map]]}
  [boot max-running]
  [{:seon.root/id :root :seon.root/boot boot :seon.config.agent/max-running max-running}])

(defn run-id
  "Task id's nth run's id: runs are numbered from 1, in the order they start."
  {:malli/schema [:=> [:cat :seon.task/id pos-int?] :seon.run/id]}
  [id n] (str id "#" n))

(defn agent-key
  "Agent id's key in root's boot: its entity's identity (:seon.agent/key), one per agent per
  boot, since an id like :a3 starts again each boot."
  {:malli/schema [:=> [:cat :string :keyword] :string]}
  [boot id] (str boot "/" (name id)))

(def ^:private agent-attrs
  "Each member of root's view of an agent and the attribute recording it (agent-tx)."
  {:status :seon.agent/status :pid :seon.agent/pid :mem-mb :seon.agent/mem-mb :running :seon.agent/running
   :task :seon.agent/description :branch :seon.agent/branch :forked-ms :seon.agent/forked-ms :ended-ms :seon.agent/ended-ms})

(defn agent-tx
  "What root observes of agent id in its boot, as facts on the agent's entity: the root that
  started it, and each member m holds of root's view (status, pid, memory, the form running,
  its task's id or description, branch, when it forked and ended); a member m holds as nil is
  no longer true, and retracted (the form running, once it returned). Root writes it when its
  view of the agent changes (seon.root/record-agents)."
  {:malli/schema [:=> [:cat :string :keyword :map] [:vector :any]]}
  [boot id m]
  (let [k (agent-key boot id)
        v (fn [member x] (case member :running (:form x) (:mem-mb :forked-ms) (double x) x))]
    (into [(into {:seon.agent/key k :seon.agent/id id :seon.agent/root [:seon.root/id :root]}
                 (for [[member a] agent-attrs :let [x (some->> (get m member) (v member))] :when (some? x)] [a x]))]
          (for [[member a] agent-attrs :when (and (contains? m member) (nil? (get m member)))]
            [:db.fn/retractAttribute [:seon.agent/key k] a]))))

(defn message-tx
  "Message mid from from to run aid, its body: the one message fact, written by root on main
  and by the turn that takes it on its branch (one entity: both ids are content ids)."
  {:malli/schema [:=> [:cat :string :string [:maybe :seon.run/id] :any] [:vector :map]]}
  [mid from aid body]
  ;; the run its own entity map: only a top-level map takes its content id (Datahike's
  ;; content-tempids); its tempid is its id, so every ref to it in one transaction is one entity
  (cond-> [(cond-> {:seon.message/id mid :seon.message/from from :seon.message/body body}
             aid (assoc :seon.message/to aid))]
    aid (conj {:db/id aid :seon.run/id aid})))

(defn run-start-tx
  "A run begins: root observed it spawn agent id agent in its boot (nil: root refused the
  spawn, and no agent exists), given the task's accumulated plan. The run refs the agent's
  entity, named by its key in that boot (seon.task/agent-key) and holding its id, so a read of
  the run names its agent before root has recorded anything else of it."
  {:malli/schema [:=> [:cat :seon.run/id :seon.task/id :string [:maybe :keyword] :seon.task/env :int :seon.task/plan] [:vector :map]]}
  [aid task boot agent {:keys [branch model max-turns]} start-ms plan]
  (let [k (when agent (agent-key boot agent))]
    (filterv some?
             [(cond-> {:seon.run/id aid :seon.run/task [:seon.task/id task] :seon.run/status :running
                       :seon.run/boot boot :seon.run/start-ms start-ms
                       :seon.run/branch branch :seon.run/max-turns max-turns :seon.run/plan plan}
                k (assoc :seon.run/agent k)
                model (assoc :seon.run/model model))
              ;; the agent its own entity map, so it takes its content id (Datahike's content-tempids);
              ;; its tempid is its key, unique in a transaction that starts several runs
              (when k {:db/id k :seon.agent/key k :seon.agent/id agent})])))

(defn status-tx
  "Move run aid from status `from` to `to` (Datahike's compare-and-swap: a racing
  observer that already moved it leaves it), with the observed members m."
  {:malli/schema [:=> [:cat :seon.host/db :seon.run/id :seon.run/status :seon.run/status :map] [:vector :any]]}
  [db aid from to m]
  (let [e (pdb/eid db :seon.run/id aid)]
    (into [[:db/cas e :seon.run/status from to]]
          (when (seq m) [(assoc m :db/id e)]))))

(defn end-members
  "The observed members of a run's end (proto.agents/end), as attributes: a count
  nobody measured (no end written, a reply without usage) is absent, never 0."
  {:malli/schema [:=> [:cat :seon.turn/end :int :int] :map]}
  [{:keys [outcome turns model-calls tokens results uncontracted reason conflicts unmerged changed deferred caused]} end-ms basis-t]
  (cond-> {:seon.run/outcome outcome :seon.run/basis-t basis-t :seon.run/end-ms end-ms
           :seon.run/results (vec (for [[t s] results] {:seon.proof/test t :seon.proof/status s}))}
    turns (assoc :seon.run/turns turns)
    model-calls (assoc :seon.run/model-calls model-calls)
    tokens (assoc :seon.run/tokens tokens)
    (seq uncontracted) (assoc :seon.run/uncontracted (vec uncontracted))
    (seq conflicts) (assoc :seon.run/conflicts (mapv pm/conflict-tx conflicts))
    (seq unmerged) (assoc :seon.run/unmerged (vec unmerged))
    (seq changed) (assoc :seon.run/changed (vec changed))
    (seq deferred) (assoc :seon.run/deferred (vec deferred))
    (seq caused) (assoc :seon.run/caused (vec caused))
    reason (assoc :seon.run/reason reason)))

(defn carry-tx
  "The task content (seon.merge/carried-platform-attrs) branch changed since base, carried
  onto main (DECISIONS, A task edited on two live branches is a conflict for root (D4)): a member
  the branch changed and main did not overwrites
  main's, so the next agents start from it; one main also changed since base, to another
  value, is a conflict for root (never resolved here). Tasks main does not hold are left.
  Answers {:tx :conflicts [{:task :attr :base :ours :theirs}]}. Cost: the carried datoms of
  the three dbs, read once."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] [:map [:tx [:vector :any]] [:conflicts :seon.run/task-conflicts]]]}
  [base branch main]
  (let [vs (fn [db] (into {} (for [a (sort pm/carried-platform-attrs) dt (d/datoms db :aevt a)] [[(:e dt) a] (:v dt)])))
        b (vs base) o (vs branch) m (vs main)]
    (reduce (fn [acc [e a :as k]]
              (let [id (:seon.task/id (d/entity branch e)) bv (b k) ov (o k) mv (m k)]
                (cond (or (nil? id) (nil? (pdb/eid main :seon.task/id id)) (= mv ov)) acc
                      (= mv bv) (update acc :tx conj (if (some? ov) [:db/add [:seon.task/id id] a ov] [:db/retract [:seon.task/id id] a mv]))
                      :else (update acc :conflicts conj {:task id :attr a :base bv :ours ov :theirs mv}))))
            {:tx [] :conflicts []}
            (sort (for [k (into (set (keys b)) (keys o)) :when (not= (b k) (o k))] k)))))

(defn onto-tx
  "The facts that put task id as main holds it, and every task it needs (transitively) that
  branch does not hold, onto branch: a kept branch resumed for a run starts from main's
  task. Root released it after holding it (seon.reconcile/hold-tx), so main's holds the
  failure and evidence the last run left, root's edits, and the branch's own carried at
  its run's end (carry-tx); or the branch was forked before main held the task or its
  needs. Another task the branch holds is left as the branch has it. Empty when the branch
  holds them all as main does. Cost: the closure's tasks, pulled once each."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.task/id] [:vector :map]]}
  [main branch id]
  (let [pull #(d/pull %1 '[* {:seon.task/needs [:seon.task/id]}] [:seon.task/id %2])
        held? #(and (pdb/eid branch :seon.task/id %)
                    (or (not= id %) (= (dissoc (pull main %) :db/id) (dissoc (pull branch %) :db/id))))
        closure (loop [todo [id] seen #{}]
                  (if-let [[x & more] (seq todo)]
                    (if (or (seen x) (held? x))
                      (recur more seen)
                      (recur (into (vec more) (map :seon.task/id (:seon.task/needs (d/pull main '[{:seon.task/needs [:seon.task/id]}] [:seon.task/id x]))))
                             (conj seen x)))
                    seen))]
    (vec (for [x (sort closure)
               :let [t (pull main x)]]
           (cond-> (-> t (dissoc :db/id :seon.task/needs) (assoc :db/id x))
             (seq (:seon.task/needs t))
             (assoc :seon.task/needs (mapv (fn [{n :seon.task/id}] (if (closure n) n [:seon.task/id n])) (:seon.task/needs t))))))))

;; ---- the reader ------------------------------------------------------------------

(def ^:private task-pull '[* {:seon.task/needs [:seon.task/id]}])

(defn- task-value
  "A pulled task's own members (needs as a set of ids, proofs and references sets), without
  the declaration its file stated (load-tx's to compare)."
  {:malli/schema [:=> [:cat :seon.task/pulled] :seon.task/own]}
  [t]
  (cond-> (-> t (dissoc :db/id :seon.task/declared)
              (update :seon.task/needs #(set (map :seon.task/id %)))
              (update :seon.task/proofs set)
              (update :seon.task/kind #(or % :work)))
    (:seon.task/references t) (update :seon.task/references set)))

(def placeholder-assertion
  "The assertion seon.context writes into a proof's test that does not exist yet (its :proof-unknown
  `do`); seon.problems/uncited-tasks reads a proof still holding it as a placeholder."
  "write the assertion: the behaviour, on data")

(defn accumulate
  "Task id's accumulated plan and proofs over its needs (owner: a task
  inherits its prerequisites' plan and proofs and adds its own, so a refinement only
  tightens): {:plan text :proofs set}. own: id -> own task value."
  {:malli/schema [:=> [:cat ifn? :seon.task/id] [:map [:plan :string] [:proofs :seon.task/proofs]]]}
  [own id]
  (let [order (fn order [acc id]
                ;; prerequisites first, each once (a diamond reaches one twice)
                (if (some #{id} acc) acc
                    (conj (reduce order acc (sort (:seon.task/needs (own id)))) id)))
        chain (filter own (order [] id))]
    ;; a merge task's plan is about merging: it is its own, never a later task's (T5b: a work
    ;; agent was told to merge); its proofs flow on like every task's
    {:plan (str/join "\n\n" (distinct (remove str/blank? (map #(:seon.task/plan (own %))
                                                               (remove #(and (not= % id) (= :merge (:seon.task/kind (own %)))) chain)))))
     :proofs (into #{} (mapcat #(:seon.task/proofs (own %))) chain)}))

(defn tasks
  "Every task on db, by id, each with its :seon.task/accumulated plan and proofs."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :seon.task/id :seon.task/task]]}
  [db]
  (let [own (into {} (for [t (d/q '[:find [(pull ?e pattern) ...] :in $ pattern :where [?e :seon.task/id]] db task-pull)]
                       [(:seon.task/id t) (task-value t)]))]
    (into {} (for [[id t] own] [id (assoc t :seon.task/accumulated (accumulate own id))]))))

(defn task
  "Task id on db with its :seon.task/accumulated plan and proofs, or nil (reads id and
  its prerequisites only)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:maybe :seon.task/task]]}
  [db id]
  (let [own (fn [i] (some-> (d/pull db task-pull [:seon.task/id i]) task-value))]
    ;; the value names its schema, so a REPL shows it by its declared render (seon.render/printed)
    (some-> (own id) (assoc :seon.task/accumulated (accumulate own id)) (vary-meta assoc :seon.render/schema :seon.task/task))))

(def ^:private run-pull
  '[* {:seon.run/task [:seon.task/id]} {:seon.run/agent [:seon.agent/id]}
    {:seon.run/results [:seon.proof/test :seon.proof/status]}
    {:seon.run/deferred-results [:seon.proof/test :seon.proof/status]}
   {:seon.run/conflicts [:seon.merge/key :seon.merge/kind :seon.merge/base :seon.merge/ours :seon.merge/theirs]}])

(defn- run-value
  "A pulled run as :seon.run/run."
  {:malli/schema [:=> [:cat :seon.run/pulled] :seon.run/run]}
  [a]
  (let [{:seon.run/keys [branch model max-turns results uncontracted conflicts unmerged]} a]
    (cond-> (-> a (dissoc :db/id :seon.run/branch :seon.run/model :seon.run/max-turns :seon.run/results
                          :seon.run/uncontracted :seon.run/conflicts :seon.run/unmerged)
                (update :seon.run/task :seon.task/id)
                (cond-> (:seon.run/agent a) (update :seon.run/agent :seon.agent/id))
                (assoc :seon.run/env {:branch branch :model model :max-turns max-turns}))
      results (assoc :seon.run/results (into {} (map (juxt :seon.proof/test :seon.proof/status)) results))
      uncontracted (assoc :seon.run/uncontracted (set uncontracted))
      unmerged (assoc :seon.run/unmerged (vec (sort unmerged)))
      (:seon.run/changed a) (update :seon.run/changed set)
      (:seon.run/deferred a) (update :seon.run/deferred set)
      (:seon.run/deferred-results a) (update :seon.run/deferred-results #(into {} (map (juxt :seon.proof/test :seon.proof/status)) %))
      (:seon.run/caused a) (update :seon.run/caused set)
      conflicts (assoc :seon.run/conflicts
                       (vec (sort-by (comp str :key) (map pm/conflict-value conflicts)))))))

(defn runs
  "Every run on db, by id: each entity its start recorded (run-start-tx: :seon.run/status among
  it). A run a branch only names by reference (a turn's, a message's: an entity holding its id
  alone, so the ref resolves to main's entity) is no run record on that branch."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :seon.run/id :seon.run/run]]}
  [db]
  (into {} (for [a (d/q '[:find [(pull ?e pattern) ...] :in $ pattern :where [?e :seon.run/status]] db run-pull)]
             [(:seon.run/id a) (run-value a)])))

(def finished
  "Run statuses that make its task done: a run's lifecycle sets are declared once, on
  :seon.run/status's schema, and read once, here (seon.main-test/a-runs-lifecycle-is-read-once)."
  (:seon.status/finished (contract/properties :seon.run/status)))

(def judged "Run statuses the done rule judges (ended or dead)." (:seon.status/judged (contract/properties :seon.run/status)))

(def failed "Run statuses of a death (its agent died or was lost)." (:seon.status/failed (contract/properties :seon.run/status)))

(defn done?
  "Has task entity te a run that finished (finished)? One AVET seek of its runs, then each one's
  status; the reconciler's index answers the same from its own facts (seon.reconcile/task-state)."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db te]
  (boolean (some #(finished (:seon.run/status (d/pull db [:seon.run/status] (:e %))))
                 (d/datoms db :avet :seon.run/task te))))

(defn running
  "The runs running on db (status :running), each as `run` answers it: one AVET seek of the status,
  never a read of every run."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.run/run]]}
  [db]
  (mapv #(run-value (d/pull db run-pull (:e %))) (d/datoms db :avet :seon.run/status :running)))

(defn run
  "Run aid on db, or nil: nil too where db only names it by reference (runs)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.run/id] [:maybe :seon.run/run]]}
  [db aid]
  (let [a (d/pull db run-pull [:seon.run/id aid])]
    (when (:seon.run/status a) (run-value a))))

(defn root
  "Root's boot and config keys on db: {:boot :max-running :max-merging :gate-checkout}, nil
  before the first boot."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe [:map [:boot :string] [:max-running :int] [:max-merging {:optional true} :int]
                                                     [:gate-checkout {:optional true} :string]]]]}
  [db]
  (when-let [r (d/pull db [:seon.root/boot :seon.config.agent/max-running :seon.config.merge/max-running :seon.config.source/gate-checkout] [:seon.root/id :root])]
    (cond-> {:boot (:seon.root/boot r) :max-running (:seon.config.agent/max-running r)}
      (:seon.config.merge/max-running r) (assoc :max-merging (:seon.config.merge/max-running r))
      (:seon.config.source/gate-checkout r) (assoc :gate-checkout (:seon.config.source/gate-checkout r)))))

(defn entity-value
  "The task, run or root that entity e of db is now, as the reconciler's index holds it:
  [:task own-value] (no accumulation), [:run run-value], [:root (root db)], or nil
  when e is none of them (retracted, another kind, or a run a branch only names). One pull of e."
  {:malli/schema [:=> [:cat :seon.host/db :int]
                  [:maybe [:or [:tuple [:= :task] :seon.task/own] [:tuple [:= :run] :seon.run/run] [:tuple [:= :root] :map]]]]}
  [db e]
  (let [k (d/pull db [:seon.task/id :seon.run/status :seon.root/id] e)]
    (cond (:seon.task/id k) [:task (task-value (d/pull db task-pull e))]
          ;; a run its start recorded (runs): a branch's stub, its id alone, is none
          (:seon.run/status k) [:run (run-value (d/pull db run-pull e))]
          (:seon.root/id k) [:root (root db)])))

(defn entity-ids
  "Every task, run and root entity of db (the reconciler's list, before it watches)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :int]]}
  [db]
  (vec (concat (map :e (d/datoms db :aevt :seon.root/id)) (map :e (d/datoms db :aevt :seon.task/id))
               (map :e (d/datoms db :aevt :seon.run/id)))))

;; ---- task files: declarations as reviewable data -----------------------------------
;; resources/seon/tasks/<ns>.edn holds one vector of :seon.task/declaration entries (without
;; :seon.task/ns: the file's name is the owning namespace). Read with clojure.edn, nothing
;; evaluated. Loading reconciles the files into main: a new entry is created held; an entry
;; equal to the declaration main remembers (:seon.task/declared) writes nothing, so what main
;; learned since (the improved plan) stays; a changed entry writes what it states and
;; retracts what it dropped; an entry no file declares any more is cancelled and forgets its
;; declaration, so declaring it again revives it. Execution is root's (my.root/run!), except
;; that revival.

(def ^:private stated-attributes
  "The task attributes a declaration states (its members, config flattened), as stored."
  [:seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/branch :seon.task/model
   :seon.task/kind :seon.task/evidence :seon.task/references
   :seon.config.agent/max-turns :seon.config.merge/max-conflicts :seon.config.merge/max-merge-turns])

(defn- file-refused
  {:malli/schema [:=> [:cat :symbol :string] :map]}
  [ns-sym m] (invalid 'seon.task/file-declarations (str "task file " ns-sym ".edn: " m)))

(defn file-declarations
  "The declarations of task file text, owned by namespace ns-sym (the file's name), each
  with its :seon.task/ns; or the :seon.task/refused error when the text is not exactly one
  EDN vector of declarations."
  {:malli/schema [:=> [:cat :symbol :string]
                  [:or [:vector :seon.task/declaration] [:map [:seon.error/type [:= :seon.task/refused]] [:seon.error/message :string]]]]}
  [ns-sym text]
  (let [r (java.io.PushbackReader. (java.io.StringReader. text))
        forms (try (vec (take-while #(not= ::eof %) (repeatedly #(edn/read {:eof ::eof} r))))
                   (catch Exception e (ex-message e)))]
    (cond
      (string? forms) (file-refused ns-sym (str "not readable EDN: " forms))
      (not (and (= 1 (count forms)) (vector? (first forms)))) (file-refused ns-sym "not exactly one vector of declarations")
      :else (let [ds (mapv #(if (map? %) (assoc % :seon.task/ns ns-sym) %) (first forms))]
              (if-let [bad (first (remove #(contract/valid? :seon.task/declaration %) ds))]
                (file-refused ns-sym (str "an entry is not a :seon.task/declaration: " (print/text bad (:part print/budgets))))
                ds)))))

(defn load-tx
  "The transaction reconciling task files into main db. files: {owning-ns declarations or
  error}, every file read (a file not given is not judged). Answers the tx-data, or the
  first error: a file's own, or needs that name no task or form a cycle."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :symbol :any]] [:or [:vector :any] [:map [:seon.error/type :keyword]]]]}
  [db files]
  (if-let [r (first (filter :seon.error/type (vals files)))]
    r
    (let [decls (into {} (for [ds (vals files) d ds] [(task-id (:seon.task/ns d) (:seon.task/name d)) d]))
          owners (set (keys files))
          stored (into {} (d/q '[:find ?id ?s :where [?e :seon.task/declared ?s] [?e :seon.task/id ?id]] db))
          all (tasks db)
          new (vec (for [[id d] decls :when (not (all id))] d))
          changed (vec (for [[id d] decls :when (and (all id) (not= d (some-> (stored id) sedn/decode)))] [id d]))
          removed (vec (for [[id _] stored :when (and (not (decls id)) (owners (:seon.task/ns (all id))))] id))
          graph (merge (into {} (for [[id t] all] [id (:seon.task/needs t)]))
                       (into {} (for [[id d] decls] [id (set (:seon.task/needs d))])))
          declared (fn [id d] {:seon.task/id id :seon.task/declared (sedn/encode d)})]
      (if-let [p (needs-problem graph graph)]
        (file-refused (first owners) p)
        (vec (concat
               (when (seq new) (create-tx db new))
               (for [d new] (declared (task-id (:seon.task/ns d) (:seon.task/name d)) d))
               (mapcat (fn [[id d]]
                         ;; what it states now: members, config flattened, references derived, branch
                         ;; defaulted, needs as entity ids (a task new in this write by its tempid, its id)
                         (let [want (-> (merge (dissoc d :seon.task/ns :seon.task/name :seon.task/config) (:seon.task/config d) (references-of d))
                                        (update :seon.task/branch #(or % (default-branch id)))
                                        (assoc :seon.task/needs (into #{} (map #(or (pdb/eid db :seon.task/id %) %)) (:seon.task/needs d))))]
                           (cond-> (conj (entity-tx db (pdb/eid db :seon.task/id id) (conj stated-attributes :seon.task/needs) want) (declared id d))
                             ;; declared again after its removal: revived
                             (and (nil? (stored id)) (= :cancelled (:seon.task/execution (all id))))
                             (conj {:seon.task/id id :seon.task/execution :held}))))
                       changed)
               (mapcat (fn [id] [{:seon.task/id id :seon.task/execution :cancelled}
                                 [:db/retract [:seon.task/id id] :seon.task/declared (stored id)]])
                       removed)))))))
