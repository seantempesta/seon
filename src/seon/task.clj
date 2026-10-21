(ns seon.task
  "Tasks as data (TASK-CHAIN §1, the target model): one entity per task on main, its
  desired state written by root; attempts and proof results are observed state, written
  by their observer (root's executor) when the event happens. There is no chain entity:
  `:seon.task/needs` is the one dependency edge.

  This namespace is the one writer of those facts (tx-data builders; root transacts
  them) and the one reader (pull shapes for the reconciler and the agent). From lanes R1
  and M1's proto.agents.task; the attributes are derived from resources/seon/schemas (lane D1)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [clojure.string :as str] [clojure.edn :as edn] [seon.merge :as pm]
            [seon.print :as print] [seon.ref :as ref]))

;; ---- identity --------------------------------------------------------------------

(defn eid
  "The entity id of the entity whose unique attribute a has value v on db, or nil: one seek
  in a's AVET index, never a query (a Datalog query here cost ~95 us and
  100 KB at 100 tasks, planned on every call)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword :any] [:maybe :int]]}
  [db a v] (:e (first (d/datoms db :avet a v))))

(defn task-id
  "The task's handle, ns/name: its identity, derived from its namespace and name."
  {:malli/schema [:=> [:cat :seon.task/ns :seon.task/name] :seon.task/id]}
  [ns-sym nm] (str ns-sym "/" nm))

(defn default-branch
  "The branch a task's attempts check out when it names none: task-<ns>-<name>."
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
  :unresolved}: answered beside the write, never a refusal (docs/references.md)."
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

(defn create-tx
  "The transaction writing decls (task declarations) as held tasks: each gets its id and
  its branch; needs refer by id, to a task of this write or one already on db. A cycle
  or a need that names no task is the :seon.task/refused error."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.task/declaration]]
                  [:or [:vector :map] [:map [:seon.error/type [:= :seon.task/refused]] [:seon.error/message :string]]]]}
  [db decls]
  (let [ids (mapv #(task-id (:seon.task/ns %) (:seon.task/name %)) decls)
        new (set ids)
        graph (zipmap ids (map #(set (:seon.task/needs % #{})) decls))
        missing (seq (for [[_ ns] graph n ns :when (and (not (new n)) (nil? (eid db :seon.task/id n)))] n))
        cyc (cycle-in graph)
        refused (fn [m] {:seon.error/type :seon.task/refused :seon.error/operation 'seon.task/create-tx
                         :seon.error/message m})]
    (cond
      missing (refused (str "needs name no task: " (str/join ", " missing)))
      cyc (refused (str "needs form a cycle through " cyc))
      :else
      (mapv (fn [id {:seon.task/keys [config needs evidence] :as decl}]
              (cond-> (-> decl (dissoc :seon.task/config :seon.task/needs :seon.task/evidence)
                          (assoc :db/id id :seon.task/id id :seon.task/execution :held)
                          (update :seon.task/branch #(or % (default-branch id))))
                (seq needs) (assoc :seon.task/needs (mapv #(if (new %) % [:seon.task/id %]) needs))
                evidence (assoc :seon.task/evidence (binding [*print-length* nil *print-level* nil] (pr-str evidence)))
                (seq config) (merge config)
                true (merge (references-of decl))))
            ids decls))))

(defn execution-tx
  "Set the desired execution of tasks ids (:running is root's release, run!)."
  {:malli/schema [:=> [:cat [:sequential :seon.task/id] :seon.task/execution] [:vector :map]]}
  [ids execution]
  (mapv (fn [id] {:seon.task/id id :seon.task/execution execution}) ids))

(defn env-tx
  "Change task id's desired environment (branch, model, max-turns): a running attempt in
  another environment is restarted clean by the reconciler."
  {:malli/schema [:=> [:cat :seon.task/id [:map [:branch {:optional true} :keyword] [:model {:optional true} :string]
                                           [:max-turns {:optional true} pos-int?]]] [:vector :map]]}
  [id {:keys [branch model max-turns]}]
  [(cond-> {:seon.task/id id}
     branch (assoc :seon.task/branch branch) model (assoc :seon.task/model model)
     max-turns (assoc :seon.config.agent/max-turns max-turns))])

(defn boot-tx
  "Root's boot: its id for this process lifetime and its max-running dial (merges at once,
  :seon.config.merge/max-running, is written with the config in force, seon.config/facts)."
  {:malli/schema [:=> [:cat :string pos-int?] [:vector :map]]}
  [boot max-running]
  [{:seon.root/id :root :seon.root/boot boot :seon.config.agent/max-running max-running}])

(defn attempt-start-tx
  "An attempt begins: root observed it spawn its agent."
  {:malli/schema [:=> [:cat :seon.attempt/id :seon.task/id :string :keyword :seon.task/env :int] [:vector :map]]}
  [aid task boot agent {:keys [branch model max-turns]} start-ms]
  [(cond-> {:seon.attempt/id aid :seon.attempt/task [:seon.task/id task] :seon.attempt/status :running
            :seon.attempt/boot boot :seon.attempt/agent agent :seon.attempt/start-ms start-ms
            :seon.attempt/branch branch :seon.attempt/max-turns max-turns}
     model (assoc :seon.attempt/model model))])

(defn status-tx
  "Move attempt aid from status `from` to `to` (Datahike's compare-and-swap: a racing
  observer that already moved it leaves it), with the observed members m."
  {:malli/schema [:=> [:cat :seon.host/db :seon.attempt/id :seon.attempt/status :seon.attempt/status :map] [:vector :any]]}
  [db aid from to m]
  (let [e (eid db :seon.attempt/id aid)]
    (into [[:db/cas e :seon.attempt/status from to]]
          (when (seq m) [(assoc m :db/id e)]))))

(defn conflict-members
  "Conflicts (:seon.merge/conflict values) as the attempt's component members."
  {:malli/schema [:=> [:cat [:sequential :seon.merge/conflict]] [:vector :map]]}
  [conflicts] (mapv pm/conflict-tx conflicts))

(defn end-members
  "The observed members of an attempt's end (proto.agents/end), as attributes."
  {:malli/schema [:=> [:cat :seon.turn/end :int :int] :map]}
  [{:keys [outcome turns model-calls tokens results uncontracted reason conflicts unmerged changed]} end-ms basis-t]
  (cond-> {:seon.attempt/outcome outcome :seon.attempt/basis-t basis-t :seon.attempt/turns turns :seon.attempt/model-calls model-calls
           :seon.attempt/tokens tokens :seon.attempt/end-ms end-ms
           :seon.attempt/results (vec (for [[t s] results] {:seon.proof/test t :seon.proof/status s}))}
    (seq uncontracted) (assoc :seon.attempt/uncontracted (vec uncontracted))
    (seq conflicts) (assoc :seon.attempt/conflicts (conflict-members conflicts))
    (seq unmerged) (assoc :seon.attempt/unmerged (vec unmerged))
    (seq changed) (assoc :seon.attempt/changed (vec changed))
    reason (assoc :seon.attempt/reason reason)))

(defn plan-tx
  "The task's plan as its attempt left it (read from the branch by root's judge), carried
  to main: the plan keeps improving; the next agents start from it."
  {:malli/schema [:=> [:cat :seon.task/id :seon.task/plan] [:vector :map]]}
  [id plan] [{:seon.task/id id :seon.task/plan plan}])

;; ---- the reader ------------------------------------------------------------------

(def ^:private task-pull
  [:seon.task/id :seon.task/ns :seon.task/name :seon.task/plan :seon.task/instructions :seon.task/proofs
   :seon.task/branch :seon.task/model :seon.task/execution :seon.task/problem
   :seon.task/kind :seon.task/evidence :seon.task/generation :seon.task/budget :seon.task/stuck
   :seon.config.agent/max-turns :seon.config.merge/max-conflicts :seon.config.merge/max-merge-turns
   {:seon.task/needs [:seon.task/id]}])

(defn- task-value
  "A pulled task's own members (needs as a set of ids, proofs a set)."
  {:malli/schema [:=> [:cat :seon.task/pulled] :seon.task/own]}
  [t]
  (cond-> (-> t (update :seon.task/needs #(set (map :seon.task/id %)))
              (update :seon.task/proofs set)
              (update :seon.task/kind #(or % :work)))
    (:seon.task/evidence t) (update :seon.task/evidence edn/read-string)
    (:seon.task/stuck t) (update :seon.task/stuck edn/read-string)))

(defn- accumulate
  "Task id's accumulated plan and proofs over its needs (owner, 2026-10-20: a task
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
  (when (eid db :seon.task/id id)
    (let [own (fn [i] (when (eid db :seon.task/id i) (task-value (d/pull db task-pull [:seon.task/id i]))))]
      (assoc (own id) :seon.task/accumulated (accumulate own id)))))

(def ^:private attempt-pull
  [:seon.attempt/id {:seon.attempt/task [:seon.task/id]} :seon.attempt/status :seon.attempt/boot :seon.attempt/agent
   :seon.attempt/pid :seon.attempt/start-ms :seon.attempt/end-ms :seon.attempt/branch :seon.attempt/model
   :seon.attempt/max-turns :seon.attempt/outcome :seon.attempt/reason :seon.attempt/turns :seon.attempt/model-calls
   :seon.attempt/tokens :seon.attempt/merged-t :seon.attempt/basis-t :seon.attempt/uncontracted
   :seon.attempt/unmerged :seon.attempt/changed
   {:seon.attempt/results [:seon.proof/test :seon.proof/status]}
   {:seon.attempt/conflicts [:seon.merge/key :seon.merge/kind :seon.merge/base :seon.merge/ours :seon.merge/theirs]}])

(defn- attempt-value
  "A pulled attempt as :seon.attempt/attempt."
  {:malli/schema [:=> [:cat :seon.attempt/pulled] :seon.attempt/attempt]}
  [a]
  (let [{:seon.attempt/keys [branch model max-turns results uncontracted conflicts unmerged]} a]
    (cond-> (-> a (dissoc :seon.attempt/branch :seon.attempt/model :seon.attempt/max-turns :seon.attempt/results
                          :seon.attempt/uncontracted :seon.attempt/conflicts :seon.attempt/unmerged)
                (update :seon.attempt/task :seon.task/id)
                (assoc :seon.attempt/env {:branch branch :model model :max-turns max-turns}))
      results (assoc :seon.attempt/results (into {} (map (juxt :seon.proof/test :seon.proof/status)) results))
      uncontracted (assoc :seon.attempt/uncontracted (set uncontracted))
      unmerged (assoc :seon.attempt/unmerged (vec (sort unmerged)))
      (:seon.attempt/changed a) (update :seon.attempt/changed set)
      conflicts (assoc :seon.attempt/conflicts
                       (vec (sort-by (comp str :key)
                                     (for [c conflicts]
                                       (cond-> {:key (edn/read-string (:seon.merge/key c)) :kind (:seon.merge/kind c)}
                                         (:seon.merge/base c) (assoc :base (edn/read-string (:seon.merge/base c)))
                                         (:seon.merge/ours c) (assoc :ours (edn/read-string (:seon.merge/ours c)))
                                         (:seon.merge/theirs c) (assoc :theirs (edn/read-string (:seon.merge/theirs c)))))))))))

(defn attempts
  "Every attempt on db, by id."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :seon.attempt/id :seon.attempt/attempt]]}
  [db]
  (into {} (for [a (d/q '[:find [(pull ?e pattern) ...] :in $ pattern :where [?e :seon.attempt/id]] db attempt-pull)]
             [(:seon.attempt/id a) (attempt-value a)])))

(defn attempt
  "Attempt aid on db, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :seon.attempt/id] [:maybe :seon.attempt/attempt]]}
  [db aid]
  (when (eid db :seon.attempt/id aid) (attempt-value (d/pull db attempt-pull [:seon.attempt/id aid]))))

(defn root
  "Root's boot and dials on db: {:boot :max-running :max-merging}, nil before the first boot."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe [:map [:boot :string] [:max-running :int] [:max-merging {:optional true} :int]]]]}
  [db]
  (when-let [r (and (eid db :seon.root/id :root)
                    (d/pull db [:seon.root/boot :seon.config.agent/max-running :seon.config.merge/max-running] [:seon.root/id :root]))]
    (cond-> {:boot (:seon.root/boot r) :max-running (:seon.config.agent/max-running r)}
      (:seon.config.merge/max-running r) (assoc :max-merging (:seon.config.merge/max-running r)))))

(defn entity-value
  "The task, attempt or root that entity e of db is now, as the reconciler's index holds it:
  [:task own-value] (no accumulation), [:attempt attempt-value], [:root (root db)], or nil
  when e is none of them (retracted, or another kind). One pull of e."
  {:malli/schema [:=> [:cat :seon.host/db :int]
                  [:maybe [:or [:tuple [:= :task] :seon.task/own] [:tuple [:= :attempt] :seon.attempt/attempt] [:tuple [:= :root] :map]]]]}
  [db e]
  (let [k (d/pull db [:seon.task/id :seon.attempt/id :seon.root/id] e)]
    (cond (:seon.task/id k) [:task (task-value (d/pull db task-pull e))]
          (:seon.attempt/id k) [:attempt (attempt-value (d/pull db attempt-pull e))]
          (:seon.root/id k) [:root (root db)])))

(defn entity-ids
  "Every task, attempt and root entity of db (the reconciler's list, before it watches)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :int]]}
  [db]
  (vec (concat (map :e (d/datoms db :aevt :seon.root/id)) (map :e (d/datoms db :aevt :seon.task/id))
               (map :e (d/datoms db :aevt :seon.attempt/id)))))

;; ---- task files: declarations as reviewable data -----------------------------------
;; resources/seon/tasks/<ns>.edn holds one vector of :seon.task/declaration entries (without
;; :seon.task/ns: the file's name is the owning namespace). Read with clojure.edn, nothing
;; evaluated. Loading reconciles the files into main: a new entry is created held; an entry
;; equal to the declaration main remembers (:seon.task/declared) writes nothing, so what main
;; learned since (the improved plan) stays; a changed entry writes what it states and
;; retracts what it dropped; an entry no file declares any more is cancelled and forgets its
;; declaration, so declaring it again revives it. Execution is root's (my.root/run!), except
;; that revival. Each work task also gets its merge task (seon.template/with-merge), derived at
;; load and never written in the file, so a done task's change reaches main; it is declared,
;; changed and cancelled with its work task.

(def ^:private stated-attributes
  "The task attributes a declaration states (its members, config flattened), as stored."
  [:seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/branch :seon.task/model
   :seon.task/kind :seon.task/evidence :seon.task/generation :seon.task/budget :seon.task/references
   :seon.config.agent/max-turns :seon.config.merge/max-conflicts :seon.config.merge/max-merge-turns])

(defn- file-refusal
  {:malli/schema [:=> [:cat :symbol :string] :map]}
  [ns-sym m]
  {:seon.error/type :seon.task/refused :seon.error/operation 'seon.task/file-declarations
   :seon.error/message (str "task file " ns-sym ".edn: " m)})

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
      (string? forms) (file-refusal ns-sym (str "not readable EDN: " forms))
      (not (and (= 1 (count forms)) (vector? (first forms)))) (file-refusal ns-sym "not exactly one vector of declarations")
      :else (let [ds (mapv #(if (map? %) (assoc % :seon.task/ns ns-sym) %) (first forms))]
              (if-let [bad (first (remove #(contract/valid? :seon.task/declaration %) ds))]
                (file-refusal ns-sym (str "an entry is not a :seon.task/declaration: " (print/text bad (:part print/budgets))))
                ds)))))

(defn- update-tx
  "Make task id state exactly declaration decl: each stated attribute asserted or, when decl
  no longer states it, retracted; needs as the declared set."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id :seon.task/declaration] [:vector :any]]}
  [db id decl]
  (let [e (eid db :seon.task/id id)
        now (d/pull db (conj stated-attributes {:seon.task/needs [:seon.task/id]}) e)
        want (cond-> (merge (dissoc decl :seon.task/ns :seon.task/name :seon.task/config :seon.task/needs) (:seon.task/config decl)
                            (references-of decl))
               true (update :seon.task/branch #(or % (default-branch id)))
               (:seon.task/evidence decl) (update :seon.task/evidence #(binding [*print-length* nil *print-level* nil] (pr-str %))))
        old-needs (set (map :seon.task/id (:seon.task/needs now)))
        new-needs (set (:seon.task/needs decl))]
    (vec (concat
           (for [a stated-attributes
                 :let [v (get want a) old (get now a)]
                 :when (not= (if (set? v) v v) (if (coll? old) (set old) old))
                 tx (concat (when (some? old) (if (coll? old) (for [x old] [:db/retract e a x]) [[:db/retract e a old]]))
                            (when (some? v) (if (set? v) (for [x v] [:db/add e a x]) [[:db/add e a v]])))]
             tx)
           (for [n (remove new-needs old-needs)] [:db/retract e :seon.task/needs [:seon.task/id n]])
           (for [n (remove old-needs new-needs)] [:db/add e :seon.task/needs [:seon.task/id n]])))))

(defn load-tx
  "The transaction reconciling task files into main db. files: {owning-ns declarations or
  refusal}, every file read (a file not given is not judged). Answers the tx-data, or the
  first refusal: a file's own, or needs that name no task or form a cycle."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :symbol :any]] [:or [:vector :any] [:map [:seon.error/type :keyword]]]]}
  [db files]
  (if-let [r (first (filter :seon.error/type (vals files)))]
    r
    (let [;; each work task is followed by its merge task (seon.template/with-merge: the one
          ;; derivation; resolved at the call because seon.template requires this namespace)
          decls (into {} (for [d ((requiring-resolve 'seon.template/with-merge) (into [] cat (vals files)))]
                           [(task-id (:seon.task/ns d) (:seon.task/name d)) d]))
          owners (set (keys files))
          stored (into {} (d/q '[:find ?id ?s :where [?e :seon.task/declared ?s] [?e :seon.task/id ?id]] db))
          all (tasks db)
          new (vec (for [[id d] decls :when (not (all id))] d))
          changed (vec (for [[id d] decls :when (and (all id) (not= d (some-> (stored id) edn/read-string)))] [id d]))
          removed (vec (for [[id _] stored :when (and (not (decls id)) (owners (:seon.task/ns (all id))))] id))
          missing (seq (for [[_ d] decls n (:seon.task/needs d) :when (not (or (decls n) (all n)))] n))
          graph (merge (into {} (for [[id t] all] [id (:seon.task/needs t)]))
                       (into {} (for [[id d] decls] [id (set (:seon.task/needs d))])))
          declared (fn [id d] {:seon.task/id id :seon.task/declared (binding [*print-length* nil *print-level* nil] (pr-str d))})]
      (cond
        missing (file-refusal (first owners) (str "needs name no task: " (str/join ", " missing)))
        (cycle-in graph) (file-refusal (first owners) (str "needs form a cycle through " (cycle-in graph)))
        :else
        (vec (concat
               (when (seq new) (create-tx db new))
               (for [d new] (declared (task-id (:seon.task/ns d) (:seon.task/name d)) d))
               (mapcat (fn [[id d]] (cond-> (conj (update-tx db id d) (declared id d))
                                      ;; declared again after its removal: revived
                                      (and (nil? (stored id)) (= :cancelled (:seon.task/execution (all id))))
                                      (conj {:seon.task/id id :seon.task/execution :held})))
                       changed)
               (mapcat (fn [id] [{:seon.task/id id :seon.task/execution :cancelled}
                                 [:db/retract [:seon.task/id id] :seon.task/declared (stored id)]])
                       removed)))))))
