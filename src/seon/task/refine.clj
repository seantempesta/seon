(ns seon.task.refine
  "Pure recursive task-refinement-as-`:seon.task/needs` engine:
   - Rule A (`:rule.trigger/unproven-task`) detects open unproven tasks -> stamps prerequisite `:refine/<task>`
     from `:template/refine-task-proofs` into `:seon.task/needs`.
   - Rule B (`:rule.trigger/spawn-unassigned-prereq`) sees the unassigned prerequisite -> spawns a Refiner on the
     parent worker's branch (`:merge-target` = parent agent) so red TDD proofs stay isolated from `:main`.
   - `evaluate-refinement-gate` mechanically verifies the parent task now has real compiling proof(s) + resolved refs."
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
                      ;; No active agent already assigned to this refinement task
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
                           "1. Search widely (`user/search`, `user/grep-src`, `user/ns-outline`, schemas) to find existing functions & schemas.\n2. Write failing `(deftest ...)` proof var(s) and Malli `[:=> {:examples [...]} [:cat In] Out]` contracts.\n3. Update the parent task with `:proofs`/`:tests` and backticked `:plan` references, then call `(user/ready!)`."))
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
            proof-syms (when eff-pe
                         (let [by-tests (when (dh-has? p-db :seon.task/tests)
                                          (keep #(when (symbol? (:v %)) (:v %)) (d/datoms p-db :eavt eff-pe :seon.task/tests)))
                               by-ref (fn [a]
                                        (when (dh-has? p-db a)
                                          (keep #(eav1 p-db (:v %) :seon.code.var/name) (d/datoms p-db :eavt eff-pe a))))]
                           (into (sorted-set) (concat by-tests (by-ref :seon.task/proofs) (by-ref :seon.task/test-vars)))))
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
            refine-ops (vec (mapcat (fn [[tid _]]
                                      (let [txd (instantiate-refinement-task-tx main-db tid)]
                                        [{:op :transact :tx-data txd :reason [:trigger/refine-task tid]}]))
                                    (sort-by (comp str first) refine-facts)))
            spawn-ops (vec (mapcat (fn [[rtid {:keys [parent-agent parent-task]}]]
                                     (let [rid (keyword "refine" (str (name parent-agent) "-" (name rtid)))]
                                       [{:op :transact :tx-data [{:seon.agent/id parent-agent :seon.agent/paused? true}]
                                         :reason [:trigger/pause-for-prereq parent-agent rtid]}
                                        {:op :spawn-agent :id rid :parent parent-agent :forked-from parent-agent
                                         :merge-target [:seon.agent/id parent-agent] :tasks [rtid] :auto-merge? true
                                         :config {:seon.config/max-tool-turns 8}
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
