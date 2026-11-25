(ns seon.task.refine
  "Pure recursive task-refinement-as-`:seon.task/needs` engine:
   - Rule A (`:rule.trigger/unproven-task`) detects open unproven tasks -> stamps prerequisite `:refine/<task>`
     from `:template/refine-task-proofs` into `:seon.task/needs`.
   - Rule B (`:rule.trigger/spawn-unassigned-prereq`) sees the unassigned prerequisite -> spawns a Refiner on the
     parent worker's branch (`:merge-target` = parent agent) so red TDD proofs stay isolated from `:main`.
   - `evaluate-refinement-gate` mechanically verifies the parent task now has real compiling proof(s) + resolved refs.
   - `expected-red-proof-vars` identifies newly authored parent proof `deftest`s allowed to fail RED on a Refiner branch."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.cite :as cite]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.store :as store]))

(defn- dh-has? [db a] (boolean (and db (contains? (:schema db) a))))

(defn- eid-of [db a v]
  (when (and (some? v) (dh-has? db a))
    (:e (first (d/datoms db :avet a v)))))

(defn- eav1 [db e a]
  (when (and (integer? e) (dh-has? db a))
    (:v (first (d/datoms db :eavt e a)))))

(defn refinement-task-id
  "Deterministic `:seon.task/id` for the prerequisite refinement task of `parent-task-id`."
  {:malli/schema [:=> {:examples [[[:seon.demo/add-cache] :refine/seon.demo-add-cache]]}
                  [:cat :qualified-keyword] :qualified-keyword]
   :cost/ticks 50000}
  [parent-task-id]
  (keyword "refine" (str (namespace parent-task-id) "-" (name parent-task-id))))

(defn task-has-proofs?
  "True when task `teid` has at least one proof in `:seon.task/proofs`, `:seon.task/test-vars`, or `:seon.task/tests`."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]
   :cost/ticks 200000}
  [db teid]
  (boolean
   (or (and (dh-has? db :seon.task/proofs) (first (d/datoms db :eavt teid :seon.task/proofs)))
       (and (dh-has? db :seon.task/test-vars) (first (d/datoms db :eavt teid :seon.task/test-vars)))
       (and (dh-has? db :seon.task/tests) (first (d/datoms db :eavt teid :seon.task/tests))))))

(defn unproven-task-rows
  "Pure indexed finder for `:rule.trigger/unproven-task`: returns `[agent-id task-id]` pairs where an active
   non-main agent (with `:seon.config/auto-refine-tasks?` true) has an open non-template task lacking proofs
   and lacking its prerequisite refinement task in `:seon.task/needs`."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector [:tuple :keyword :qualified-keyword]]]
   :cost/ticks 10000000}
  [main-db]
  (if-not (and main-db (dh-has? main-db :seon.agent/tasks) (dh-has? main-db :seon.task/id))
    []
    (vec
     (for [dt (d/datoms main-db :aevt :seon.agent/tasks)
           :let [ae (:e dt)
                 te (:v dt)
                 aid (eav1 main-db ae :seon.agent/id)
                 br (eav1 main-db ae :seon.agent/branch)
                 tid (eav1 main-db te :seon.task/id)]
           :when (and (qualified-keyword? aid)
                      (not= "example" (namespace aid))
                      (not= :main br)
                      (some? br)
                      (not (true? (eav1 main-db ae :seon.agent/archived?)))
                      (nil? (eav1 main-db ae :seon.agent/forked-from))
                      (nil? (eav1 main-db ae :seon.agent/merged-tx))
                      (qualified-keyword? tid)
                      (not (true? (eav1 main-db te :seon.task/done?)))
                      (not (true? (eav1 main-db te :seon.task/template?)))
                      (not= :template/refine-task-proofs (eav1 main-db te :seon.task/template))
                      (true? (store/config-val main-db aid :seon.config/auto-refine-tasks?))
                      (not (task-has-proofs? main-db te))
                      (let [rid (refinement-task-id tid)]
                        (not (some #(= rid (:v %)) (d/datoms main-db :eavt te :seon.task/needs)))))]
       [aid tid]))))

(defn unassigned-prereq-rows
  "Pure indexed finder for `:rule.trigger/spawn-unassigned-prereq`: returns `[parent-agent-id parent-task-id prereq-task-id]`
   where an open `:template/refine-task-proofs` prerequisite blocks an active parent task and has no live refiner agent yet."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector [:tuple :keyword :qualified-keyword :qualified-keyword]]]
   :cost/ticks 10000000}
  [main-db]
  (if-not (and main-db (dh-has? main-db :seon.task/template) (dh-has? main-db :seon.task/needs))
    []
    (vec
     (for [dt (d/datoms main-db :avet :seon.task/template :template/refine-task-proofs)
           :let [re (:e dt)
                 rtid (eav1 main-db re :seon.task/id)]
           :when (and (qualified-keyword? rtid)
                      (not (true? (eav1 main-db re :seon.task/done?)))
                      (not (true? (eav1 main-db re :seon.task/template?)))
                      (not (some (fn [adt]
                                   (let [ae (:e adt)]
                                     (and (not (true? (eav1 main-db ae :seon.agent/archived?)))
                                          (nil? (eav1 main-db ae :seon.agent/merged-tx)))))
                                 (d/datoms main-db :avet :seon.agent/tasks re))))
           pdt (d/datoms main-db :avet :seon.task/needs rtid)
           :let [pe (:e pdt)
                 ptid (eav1 main-db pe :seon.task/id)]
           :when (and (qualified-keyword? ptid) (not (true? (eav1 main-db pe :seon.task/done?))))
           adt (d/datoms main-db :avet :seon.agent/tasks pe)
           :let [ae (:e adt)
                 aid (eav1 main-db ae :seon.agent/id)
                 br (eav1 main-db ae :seon.agent/branch)]
           :when (and (qualified-keyword? aid)
                      (not= :main br) (some? br)
                      (not (true? (eav1 main-db ae :seon.agent/archived?)))
                      (nil? (eav1 main-db ae :seon.agent/merged-tx)))]
       [aid ptid rtid]))))

(defn instantiate-refinement-task-tx
  "Pure function stamping a concrete prerequisite refinement task `:refine/<parent>` from `:template/refine-task-proofs`
   and linking `{:seon.task/id parent-task-id :seon.task/needs #{refine-id}}`."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] [:vector :map]]
   :cost/ticks 5000000}
  [db parent-task-id]
  (let [pe (eid-of db :seon.task/id parent-task-id)
        pt (when pe (d/pull db '[:seon.task/id :seon.task/title :seon.task/plan :seon.task/scope] pe))
        tpl-e (eid-of db :seon.task/id :template/refine-task-proofs)
        tpl (when tpl-e (d/pull db '[:seon.task/plan :seon.task/refs :seon.task/ref-order] tpl-e))
        rid (refinement-task-id parent-task-id)
        title (str "Refine & author proofs for " (or (:seon.task/title pt) (name parent-task-id)))
        base-plan (str "Target parent task: `" parent-task-id "` — " (or (:seon.task/title pt) "") "\n"
                       (when-not (str/blank? (str (:seon.task/plan pt)))
                         (str "Initial request:\n" (:seon.task/plan pt) "\n\n"))
                       (or (:seon.task/plan tpl)
                           (str "1. Search widely (`user/search`, `user/grep-src`, `user/ns-outline`, schemas) to find existing functions & schemas.\n"
                                "2. Write compiling failing `(deftest ...)` proof var(s) and Malli `[:=> {:examples [...]} [:cat In] Out]` contracts (use a schema-conforming dummy stub for new fns so Malli checks stay green while the `deftest` fails RED).\n"
                                "3. Call `(user/update-task! {:id " parent-task-id " :proofs ['<test-ns>/<deftest>] :tests ['<test-ns>/<deftest>] :plan \"...\"})` citing real symbols/schemas/doc-atoms, then call `(user/ready!)`.")))
        cites (cite/resolve-doc-refs db (str title "\n" base-plan))
        tpl-refs (into #{} (keep #(if (map? %) (:db/id %) %)) (:seon.task/refs tpl))
        all-refs (into (cond-> tpl-refs pe (conj pe)) (:refs cites))
        all-order (vec (distinct (concat (when pe [[:seon.task/id parent-task-id]]) (:ordered cites) (:seon.task/ref-order tpl))))
        dh (:schema db)
        ent (cond-> {:seon.task/id rid
                     :seon.task/title title
                     :seon.task/plan base-plan
                     :seon.task/done? false
                     :seon.task/template :template/refine-task-proofs}
              (:seon.task/scope pt) (assoc :seon.task/scope (:seon.task/scope pt))
              (and (contains? dh :seon.task/refs) (seq all-refs)) (assoc :seon.task/refs all-refs)
              (and (contains? dh :seon.task/ref-order) (seq all-order)) (assoc :seon.task/ref-order all-order))]
    [ent {:seon.task/id parent-task-id :seon.task/needs #{rid}}]))

(defn- task-proof-syms [db teid]
  (when (and db teid)
    (let [by-tests (when (dh-has? db :seon.task/tests)
                     (keep #(when (qualified-symbol? (:v %)) (:v %)) (d/datoms db :eavt teid :seon.task/tests)))
          by-ref (fn [a]
                   (when (dh-has? db a)
                     (keep #(let [s (eav1 db (:v %) :seon.code.var/name)]
                              (when (qualified-symbol? s) s))
                           (d/datoms db :eavt teid a))))]
      (into (sorted-set) (concat by-tests (by-ref :seon.task/proofs) (by-ref :seon.task/test-vars))))))

(defn evaluate-refinement-gate
  "Mechanical proof gate for a `:template/refine-task-proofs` prerequisite task:
   verifies the parent task now has >= 1 existing proof `deftest` var linked AND >= 1 resolved graph reference."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :qualified-keyword] [:maybe :map]]
   :cost/ticks 5000000}
  [main-db branch-db refine-task-id]
  (let [bdb (or branch-db main-db)
        mdb (or main-db branch-db)
        re (or (eid-of bdb :seon.task/id refine-task-id) (eid-of mdb :seon.task/id refine-task-id))
        tpl (or (when re (eav1 bdb re :seon.task/template)) (when re (eav1 mdb re :seon.task/template)))]
    (when (= :template/refine-task-proofs tpl)
      (let [pe (or (when (dh-has? bdb :seon.task/needs) (:e (first (d/datoms bdb :avet :seon.task/needs refine-task-id))))
                   (when (dh-has? mdb :seon.task/needs) (:e (first (d/datoms mdb :avet :seon.task/needs refine-task-id)))))
            ptid (or (when pe (eav1 bdb pe :seon.task/id)) (when pe (eav1 mdb pe :seon.task/id)))
            b-pe (when ptid (eid-of bdb :seon.task/id ptid))
            p-db (if b-pe bdb mdb)
            eff-pe (or b-pe pe)
            proof-syms (or (task-proof-syms p-db eff-pe) (sorted-set))
            existing-proofs (filterv #(boolean (or (eid-of bdb :seon.code.var/name %) (eid-of mdb :seon.code.var/name %))) proof-syms)
            refs-count (if eff-pe
                         (let [stored (if (dh-has? p-db :seon.task/refs) (count (d/datoms p-db :eavt eff-pe :seon.task/refs)) 0)
                               plan (str (eav1 p-db eff-pe :seon.task/title) "\n" (eav1 p-db eff-pe :seon.task/plan))]
                           (max stored (count (:refs (cite/resolve-doc-refs bdb plan)))))
                         0)
            blockers (cond-> []
                       (nil? ptid)
                       (conj {:task refine-task-id :reason :refinement-missing-parent :rule :rule.gate/refinement-outcome
                              :detail {:message "No parent task links to this refinement task via :seon.task/needs."}})
                       (and ptid (empty? existing-proofs))
                       (conj {:task refine-task-id :reason :refinement-missing-proofs :rule :rule.gate/refinement-outcome
                              :detail {:parent ptid :proofs (vec proof-syms)
                                       :message (str "Parent task " ptid " still has no compiling proof deftest var linked in :seon.task/proofs or :seon.task/tests.")}})
                       (and ptid (zero? refs-count))
                       (conj {:task refine-task-id :reason :refinement-missing-refs :rule :rule.gate/refinement-outcome
                              :detail {:parent ptid
                                       :message (str "Parent task " ptid " has 0 resolved symbol/schema/doc references in :seon.task/plan or :seon.task/refs.")}}))]
        {:ok? (empty? blockers)
         :parent ptid
         :proofs existing-proofs
         :refs-count refs-count
         :blockers blockers}))))

(defn expected-red-proof-vars
  "Returns the set of newly authored parent-task proof `deftest` symbols allowed to fail RED on a
   `:template/refine-task-proofs` Refiner branch (never includes pre-existing `:main` test vars)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:set :qualified-symbol]]
   :cost/ticks 5000000}
  [main-db branch-db agent-id]
  (if-not (and main-db branch-db agent-id)
    #{}
    (let [task-ids (distinct
                    (concat
                     (when-let [ae (eid-of main-db :seon.agent/id agent-id)]
                       (keep #(eav1 main-db (:v %) :seon.task/id) (d/datoms main-db :eavt ae :seon.agent/tasks)))
                     (when-let [ae (eid-of branch-db :seon.agent/id agent-id)]
                       (keep #(eav1 branch-db (:v %) :seon.task/id) (d/datoms branch-db :eavt ae :seon.agent/tasks)))))
          ref-tids (filter (fn [tid]
                             (let [re (or (eid-of branch-db :seon.task/id tid) (eid-of main-db :seon.task/id tid))]
                               (= :template/refine-task-proofs
                                  (or (eav1 branch-db re :seon.task/template) (eav1 main-db re :seon.task/template)))))
                           task-ids)]
      (if (empty? ref-tids)
        #{}
        (into #{}
              (for [rtid ref-tids
                    :let [pe (or (when (dh-has? branch-db :seon.task/needs) (:e (first (d/datoms branch-db :avet :seon.task/needs rtid))))
                                 (when (dh-has? main-db :seon.task/needs) (:e (first (d/datoms main-db :avet :seon.task/needs rtid)))))
                          ptid (or (eav1 branch-db pe :seon.task/id) (eav1 main-db pe :seon.task/id))
                          b-pe (when ptid (eid-of branch-db :seon.task/id ptid))]
                    :when b-pe
                    sym (task-proof-syms branch-db b-pe)
                    :when (and (eid-of branch-db :seon.code.var/name sym)
                               (nil? (eid-of main-db :seon.code.var/name sym)))]
                sym))))))

(defn passed-non-template-tasks
  "Filters `passed-tasks` to concrete (non-template) `:seon.task/id` keywords eligible to be marked `:done? true`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe [:sequential :any]]] [:vector :qualified-keyword]]
   :cost/ticks 1000000}
  [db passed-tasks]
  (if-not (and db (seq passed-tasks))
    []
    (vec (filter (fn [tid]
                   (and (qualified-keyword? tid)
                        (when-let [te (eid-of db :seon.task/id tid)]
                          (not (true? (eav1 db te :seon.task/template?))))))
                 passed-tasks))))

(defn branch-update-task-tx
  "Builds branch-local Datahike tx-data for `(user/update-task! spec)` resolving `:proofs`, `:test-vars`,
   `:refs`, and `:ref-order` directly against `db` (so branch-only `deftest` vars resolve cleanly)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :map] [:vector :map]]
   :cost/ticks 5000000}
  [db tid {:keys [title plan scope needs tests proofs test-vars summary done? owner]}]
  (let [dh (or (:schema db) {})
        ext? (contains? dh :seon.task/owner)
        cur (when (eid-of db :seon.task/id tid)
              (d/pull db '[:seon.task/title :seon.task/plan] [:seon.task/id tid]))
        eff-title (if (string? title) title (:seon.task/title cur))
        eff-plan (if (string? plan) plan (:seon.task/plan cur))
        cites (when (or (string? title) (string? plan))
                (let [txt (str (or eff-title "") "\n" (or eff-plan ""))]
                  (when-not (str/blank? txt)
                    (cite/resolve-doc-refs db txt))))
        proof-eids (when (and (seq (concat proofs test-vars)) (contains? dh :seon.code.var/name))
                     (into #{}
                           (keep (fn [x]
                                   (cond
                                     (integer? x) x
                                     (and (vector? x) (= :seon.code.var/name (first x)))
                                     (eid-of db :seon.code.var/name (second x))
                                     (qualified-symbol? x) (eid-of db :seon.code.var/name x)
                                     :else nil)))
                           (concat proofs test-vars)))
        ent (cond-> {:seon.task/id tid}
              (string? title) (assoc :seon.task/title title)
              (string? plan) (assoc :seon.task/plan plan)
              scope (assoc :seon.task/scope scope)
              (some? done?) (assoc :seon.task/done? (boolean done?))
              (and ext? (string? summary)) (assoc :seon.task/summary summary)
              (and ext? owner (qualified-keyword? owner)) (assoc :seon.task/owner owner)
              (and (seq (:refs cites)) (contains? dh :seon.task/refs)) (assoc :seon.task/refs (:refs cites))
              (and (seq (:ordered cites)) (contains? dh :seon.task/ref-order)) (assoc :seon.task/ref-order (:ordered cites)))]
    (vec (remove nil?
                 [ent
                  (when (seq needs) {:seon.task/id tid :seon.task/needs (set needs)})
                  (when (seq tests) {:seon.task/id tid :seon.task/tests (set tests)})
                  (when (and (seq proof-eids) (contains? dh :seon.task/proofs))
                    {:seon.task/id tid :seon.task/proofs proof-eids})]))))

(defn ^:seon.flow/edge apply-update-task!
  "Executes `(user/update-task! spec)` cleanly for `agent-ctx`: transacts onto branch `conn` (resolving
   branch-only `:proofs`/`:refs`) and mirrors to `:main` via `tx-main-sync!` unless forked/proof-bearing."
  {:malli/schema [:=> [:cat :map :map :seon.host/fn :seon.host/fn] :map]
   :cost/ticks 10000000}
  [{:keys [conn branch agent-id]} spec refresh-fn tx-main-fn]
  (let [bdb @conn
        mdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb)
        pull-t #(some (fn [x]
                        (when-let [eid (if (map? x) (:db/id x) x)]
                          (:seon.task/id (d/pull %1 '[:seon.task/id] eid))))
                      (:seon.agent/tasks (d/pull %2 '[:seon.agent/tasks] [:seon.agent/id agent-id])))
        tid (or (:id spec) (pull-t bdb bdb) (pull-t mdb mdb) (pull-t mdb bdb))]
    (if-not tid
      {:ok? false :error "No active :seon.agent/tasks on your agent; pass {:id :ns/task ...} or create one via (user/create-task! ...)."}
      (let [ag-m (when (first (d/datoms mdb :avet :seon.agent/id agent-id))
                   (d/pull mdb '[:seon.agent/forked-from :seon.agent/merge-target] [:seon.agent/id agent-id]))
            branch-only? (and (some? branch) (not= branch :main)
                              (or (some? (:seon.agent/forked-from ag-m))
                                  (some? (:seon.agent/merge-target ag-m))
                                  (seq (:proofs spec))
                                  (seq (:test-vars spec))))
            seed-op (when (and (not= branch :main)
                               (not (first (d/datoms bdb :avet :seon.task/id tid)))
                               (first (d/datoms mdb :avet :seon.task/id tid)))
                      (dissoc (d/pull mdb '[:seon.task/id :seon.task/title :seon.task/plan :seon.task/scope :seon.task/done?] [:seon.task/id tid]) :db/id))
            _ (when seed-op (d/transact conn {:tx-data [seed-op]}))
            b-tx (when (not= branch :main) (branch-update-task-tx @conn tid spec))
            br (when (seq b-tx)
                 (let [r (d/transact conn {:tx-data b-tx})]
                   (when refresh-fn (refresh-fn (:db-after r) (:tx-data r)))
                   r))]
        (if branch-only?
          {:ok? true :task tid :branch branch :branch-only? true :tx (when br (:max-tx (:db-after br)))}
          (or (when tx-main-fn (tx-main-fn (assoc spec :op :update-task :id tid)))
              {:ok? true :task tid :branch branch :tx (when br (:max-tx (:db-after br)))}))))))

(defn compute-trigger-ops
  "General recursive `:rule.trigger/*` reconciler dispatcher:
   evaluates enabled `:seon.rule/category :trigger` rules via `seon.rule/step-rules` and turns asserted
   trigger facts into decoupled Datahike `:tx` ops (Rule A -> Data B -> commit -> Rule B -> Data C)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]
   :cost/ticks 15000000}
  [main-db]
  (let [all-rs (rules/load-rules main-db (rseed/starter-rules))
        trig-rs (filterv #(and (:seon.rule/enabled? % true) (= :trigger (:seon.rule/category %))) all-rs)]
    (if (empty? trig-rs)
      []
      (let [wm (:wm (rules/step-rules nil {:main main-db :branch main-db :code main-db :task main-db} {} trig-rs nil :any))
            refine-facts (for [[e a v] (:facts wm) :when (= :trigger/refine-task a)] [e v])
            spawn-facts  (for [[e a v] (:facts wm) :when (= :trigger/spawn-prereq a)] [e v])
            refine-ops (vec (mapcat (fn [[tid {:keys [agent]}]]
                                    (let [txd (instantiate-refinement-task-tx main-db tid)]
                                      [(cond-> {:op :transact :tx-data txd :reason [:trigger/refine-task tid]}
                                         agent (assoc :defer-agent agent))]))
                                    (sort-by (comp str first) refine-facts)))
            spawn-ops (vec (mapcat (fn [[rtid {:keys [parent-agent parent-task]}]]
                                     (let [rid (keyword "refine" (str (name parent-agent) "-" (name rtid)))]
                                       [{:op :transact :tx-data [{:seon.agent/id parent-agent :seon.agent/paused? true}]
                                        :reason [:trigger/pause-for-prereq parent-agent rtid] :defer-agent parent-agent}
                                        {:op :spawn-agent :id rid :parent parent-agent :forked-from parent-agent
                                         :merge-target [:seon.agent/id parent-agent] :tasks [rtid] :auto-merge? true
                                         :config {:seon.config/max-tool-turns 14}
                                         :goal (str "Refine & write failing proofs for " parent-task " (" rtid ")")}]))
                                   (sort-by (comp str first) spawn-facts)))]
        (into refine-ops spawn-ops)))))

(defn trigger-attrs-touched?
  "True when `ds` is nil or touches any task/agent/config attribute watched by `:rule.trigger/*`."
  {:malli/schema [:=> [:cat :seon.host/db :any] :boolean] :cost/ticks 500000}
  [main-db ds]
  (if (nil? ds)
    true
    (let [kws [:seon.task/id :seon.task/done? :seon.task/needs :seon.task/tests :seon.task/test-vars
               :seon.task/proofs :seon.task/template :seon.agent/tasks :seon.agent/id :seon.agent/archived?
               :seon.agent/merged-tx :seon.config/auto-refine-tasks?]
          attrs (into (set kws) (keep #(get-in main-db [:ident-ref-map %])) kws)]
      (boolean (some #(contains? attrs (:a %)) ds)))))

(defn defer-triggered-agents
  "Removes agent IDs with pending `:trigger/refine-task` or `:trigger/pause-for-prereq` ops from `cands` so workers never race ahead of their Refiner."
  {:malli/schema [:=> [:cat [:set :any] [:maybe [:sequential :map]]] [:set :any]]
   :cost/ticks 200000}
  [cands trigs]
  (if (or (empty? cands) (empty? trigs))
    cands
    (let [defer (into #{} (keep :defer-agent) trigs)]
      (if (empty? defer) cands (into #{} (remove defer) cands)))))

(defn aborted-refiner-cleanup-tx
  "When a `:template/refine-task-proofs` Refiner aborts without merging, marks its unfinished `:refine/*` prereq `:done? true` with abort summary so the parent worker is never permanently deadlocked."
  {:malli/schema [:=> [:cat :seon.host/db :map :string] [:vector :map]]
   :cost/ticks 2000000}
  [main-db ma reason-txt]
  (let [tids (keep :seon.task/id (:seon.agent/tasks ma))]
    (vec (keep (fn [tid]
                 (when-let [te (eid-of main-db :seon.task/id tid)]
                   (when (and (= :template/refine-task-proofs (eav1 main-db te :seon.task/template))
                              (not (true? (eav1 main-db te :seon.task/done?))))
                     (cond-> {:seon.task/id tid :seon.task/done? true}
                       (dh-has? main-db :seon.task/summary)
                       (assoc :seon.task/summary (str "Refinement aborted (" reason-txt "); unblocked parent worker.")))))) tids))))
