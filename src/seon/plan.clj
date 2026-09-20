(ns seon.plan
  "One agent-owned plan: a component tree of steps and its derived current view.

  The agent owns one plan through `:seon.agent/plan`. That component holds
  its objective, current step, and root `:my.plan/steps`; each step owns its
  nested steps through
  `:my.plan.item/steps`. The component edge IS the ownership fact, so nothing
  stores an agent backlink or a parent backlink: a parent derives from the
  reverse component edge and ownership derives by following the edges down.
  `:my.plan.item/position` orders siblings, because a cardinality-many value is
  a set. `:my.plan/current-step` is an ordinary ref — currentness neither owns
  nor copies a step.

  Completion is the presence of `:my.plan.item/completed-tx`; ready, blocked,
  parent, depth, and state are queries over current facts, never stored."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [seon.db :as db]
            [seon.error :as error]
            [seon.id :as id]
            [seon.issue :as issue]
            [seon.repl :as repl]
            [seon.schema.edn :as schema.edn]
            [seon.sci.kernel :as sci.kernel]
            [seon.test :as seon.test]
            [seon.test.runner :as test.runner]))

;;; LOAD-CYCLE BOUNDARY. `seon.turn` requires `seon.plan`, and
;;; `seon.cluster.agent` requires `seon.turn`, so this namespace cannot
;;; require it back. One resolution, realized at first use, instead of a
;;; `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private cluster-agent-acquire-context!
  (delay (requiring-resolve 'seon.cluster.agent/acquire-context!)))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schemas/my.plan.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Derivation over the component tree
;;; ---------------------------------------------------------------------------

(def rules
  "Datalog rules deriving plan ownership from the component edges.

  Readiness, blockage, and open work are NOT rules: they are derived in
  Clojure from the same pulled component tree the renderers consume (see
  `open-work?`). A rule body that negates a variable bound only by the
  recursive `descendant` call is at the mercy of the query planner's
  cost-based ordering — once a database holds enough plan steps, the planner
  orders that negation before its binder and the whole derivation refuses
  with `Insufficient bindings`
  ([validation](reference-code/datahike/src/datahike/query/lower.cljc:1092),
  [ordering](reference-code/datahike/src/datahike/query/plan.cljc:1524))."
  '[[(descendant ?ancestor ?node)
     [?ancestor :my.plan.item/steps ?node]]
    [(descendant ?ancestor ?node)
     [?ancestor :my.plan.item/steps ?middle]
     (descendant ?middle ?node)]
    [(owned ?agent ?step)
     [?agent :seon.agent/plan ?plan]
     [?plan :my.plan/steps ?step]]
    [(owned ?agent ?step)
     [?agent :seon.agent/plan ?plan]
     [?plan :my.plan/steps ?root]
     (descendant ?root ?step)]])

(def ^:private step-selector
  ;; The renderers consume this pull. Every reference in it is a stable
  ;; identity map, so no entity id can reach an output.
  '[:my.plan.item/id
    :my.plan.item/position
    :my.plan.item/title
    :my.plan.item/description
    :my.plan.item/done-when
    :my.plan.item/done-query
    {:my.plan.item/subject [:db/id]}
    {:my.plan.item/completed-tx [:db/txInstant]}
    :my.plan.item/about
    (limit :my.plan.item/needs nil)
    {:my.plan.item/steps 8}])

(defn- refuse!
  [kind message data]
  (throw
   (ex-info message
            {kind true
             :seon.error/kind kind
             :seon.error/message message
             :seon.error/data data})))

(defn- flat-refusal
  [throwable]
  (let [data (ex-data throwable)]
    (if (:seon.error/kind data)
      data
      (throw throwable))))

(defn- read-result!
  "Return an ordinary database-read result or refuse with its error value."
  [result]
  (if (and (map? result)
           (contains? result :seon.error/at)
           (contains? result :seon.error/layer)
           (contains? result :seon.error/operation))
    (throw (ex-info (:seon.error/message result) result))
    result))

(defn- agent-eid
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.agent/id]
    [:or :int :nil :seon.db/invalid-read-error
     :seon.schema/missing-projection-error]]}
  [database agent-id]
  (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.agent/id ?agent-id]]
        database agent-id))

(defn- plan-eid
  {:malli/schema
   [:=> [:cat :seon.db/database-value [:or :int :nil]]
    [:or :int :nil :seon.db/invalid-read-error
     :seon.schema/missing-projection-error]]}
  [database agent-entity]
  (db/q '[:find ?plan . :in $ ?agent
          :where [?agent :seon.agent/plan ?plan]]
        database agent-entity))

(defn- step-eid
  {:malli/schema
   [:=> [:cat :seon.db/database-value :my.plan.item/id]
    [:or :int :nil :seon.db/invalid-read-error
     :seon.schema/missing-projection-error]]}
  [database item-id]
  (db/q '[:find ?step .
          :in $ ?item-id
          :where [?step :my.plan.item/id ?item-id]]
        database item-id))

(defn- ref-eid
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.schema/value]
    [:or :int :nil :seon.db/invalid-read-error
     :seon.schema/missing-projection-error]]}
  [database reference]
  (let [entity (db/entity database reference)]
    (if (and (map? entity)
             (contains? entity :seon.error/at)
             (contains? entity :seon.error/layer)
             (contains? entity :seon.error/operation)) entity (:db/id entity))))

(defn- subject-eid
  [database token]
  (cond
    (keyword? token)
    (db/q '[:find ?subject .
            :in $ ?token
            :where [?subject :seon.schema/key ?token]]
          database token)

    (namespace token)
    (db/q '[:find ?subject .
            :in $ ?function
            :where [?subject :seon.fn/sym ?function]]
          database token)

    :else
    (db/q '[:find ?subject .
            :in $ ?namespace
            :where [?subject :seon.ns/name ?namespace]]
          database token)))

(defn- resolve-subject!
  [database token]
  (let [subject (subject-eid database token)]
    (cond
      (and (map? subject)
           (contains? subject :seon.error/at)
           (contains? subject :seon.error/layer)
           (contains? subject :seon.error/operation)) (read-result! subject)
      subject subject
      :else
      (refuse! :my.plan/subject-not-found
               (str "Plan subject " (pr-str token) " does not exist.")
               {:my.plan.item/about token}))))

(def ^:private owned-ids-query
  '[:find [?id ...]
    :in $ % ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    (owned ?agent ?step)
    [?step :my.plan.item/id ?id]])

(defn- owned-ids
  [database agent-id]
  (let [ids (db/q owned-ids-query database rules agent-id)]
    (if (and (map? ids)
             (contains? ids :seon.error/at)
             (contains? ids :seon.error/layer)
             (contains? ids :seon.error/operation)) ids (set ids))))

;;; ---------------------------------------------------------------------------
;;; Pulled tree to derived render steps
;;; ---------------------------------------------------------------------------

(defn- tree-nodes
  "Every node of a pulled component tree, parents before their own children."
  [nodes]
  (into []
        (mapcat (fn [node]
                  (into [node] (tree-nodes (:my.plan.item/steps node)))))
        nodes))

(defn- open-work?
  "Does this pulled step still carry work?

  A leaf carries work while it has no `:my.plan.item/completed-tx`; a parent
  carries whatever its own steps carry. Completion is a fact; openness is
  this walk over the component tree the caller already pulled."
  [node]
  (let [children (:my.plan.item/steps node)]
    (if (seq children)
      (boolean (some open-work? children))
      (not (:my.plan.item/completed-tx node)))))

(defn- foreign-open-work
  "Open work by identity for dependencies outside this agent's pulled tree.

  Dependencies name step identities, including steps in another agent's plan.
  A missing prerequisite remains open work until its dependency is removed."
  [database item-ids]
  (reduce (fn [result item-id]
            (let [entity (step-eid database item-id)]
              (cond
                (and (map? entity)
                     (contains? entity :seon.error/at)
                     (contains? entity :seon.error/layer)
                     (contains? entity :seon.error/operation)) (reduced entity)
                (nil? entity) (assoc result item-id true)
                :else (let [row (db/pull database step-selector entity)]
                        (if (and (map? row)
                                 (contains? row :seon.error/at)
                                 (contains? row :seon.error/layer)
                                 (contains? row :seon.error/operation))
                          (reduced row)
                          (assoc result item-id (open-work? row)))))))
          {}
          item-ids))

(defn- derived-frontier
  "Ready and blocked identities for one pulled component tree.

  A step is blocked while any dependency it needs still carries open work,
  and ready while it is incomplete, unblocked, and either a leaf or a parent
  whose own steps are all complete."
  [database nodes]
  (let [open-by-id (into {} (map (juxt :my.plan.item/id open-work?)) nodes)
        foreign-ids (into #{}
                          (comp (mapcat :my.plan.item/needs)
                                (remove #(contains? open-by-id %)))
                          nodes)
        foreign (if (seq foreign-ids)
                  (foreign-open-work database foreign-ids)
                  {})]
    (if (and (map? foreign)
             (contains? foreign :seon.error/at)
             (contains? foreign :seon.error/layer)
             (contains? foreign :seon.error/operation))
      foreign
      (let [open? (fn [item-id]
                    (if (contains? open-by-id item-id)
                      (get open-by-id item-id)
                      (get foreign item-id true)))
            incomplete (into [] (remove :my.plan.item/completed-tx) nodes)
            blocked-ids (into #{}
                              (comp (filter (fn [node]
                                              (some open?
                                                    (:my.plan.item/needs node))))
                                    (map :my.plan.item/id))
                              incomplete)
            ready-ids (into #{}
                            (comp (remove #(contains? blocked-ids
                                                      (:my.plan.item/id %)))
                                  (filter #(or (empty? (:my.plan.item/steps %))
                                               (not (open-work? %))))
                                  (map :my.plan.item/id))
                            incomplete)]
        {:my.plan/ready ready-ids :my.plan/blocked blocked-ids}))))

(defn- sibling-order
  [step]
  [(long (get step :my.plan.item/position 0)) (:my.plan.item/id step)])

(defn- stable-reference
  [step]
  {:my.plan.item/id (:my.plan.item/id step)})

(defn- step-state
  [step current-id ready-ids blocked-ids]
  (let [id (:my.plan.item/id step)]
    (cond
      (:my.plan.item/completed-tx step) :completed
      (= current-id id) :current
      (contains? blocked-ids id) :blocked
      (contains? ready-ids id) :ready
      :else :open)))

(defn- derived-steps
  "Depth-first derived steps for one pulled component tree."
  [pulled current-id ready-ids blocked-ids]
  (letfn [(walk [nodes parent depth]
            (into []
                  (mapcat
                   (fn [node]
                     (let [needs (vec (sort (:my.plan.item/needs node)))
                           step
                           (cond-> (dissoc node
                                           :my.plan.item/steps
                                           :my.plan.item/needs
                                           :db/id)
                             (seq needs) (assoc :my.plan/needs needs)
                             (:my.plan.item/subject node)
                             (update :my.plan.item/subject :db/id)
                             parent (assoc :my.plan/parent parent)
                             true (assoc :my.plan/depth depth
                                         :my.plan/state
                                         (step-state node current-id
                                                     ready-ids blocked-ids)))]
                       (into [step]
                             (walk (:my.plan.item/steps node)
                                   (stable-reference node)
                                   (inc depth))))))
                  (sort-by sibling-order nodes)))]
    (walk pulled nil 0)))

(defn- completion-view
  [_database _agent-id steps]
  {:my.plan/recent-completions
   (vec (sort-by (fn [step]
                   [(- (.getTime ^java.util.Date (get-in step [:my.plan.item/completed-tx :db/txInstant])))
                    (:my.plan.item/id step)])
                 (filter :my.plan.item/completed-tx steps)))})

;;; ---------------------------------------------------------------------------
;;; Current reads
;;; ---------------------------------------------------------------------------

(defn- agent-plan-pull
  [database agent-id]
  (let [row (db/pull database
                     [{:seon.agent/plan
                       [:my.plan/objective
                        {:my.plan/current-step [:my.plan.item/id]}
                        {:my.plan/steps step-selector}]}]
                     [:seon.agent/id agent-id])]
    (if (and (map? row)
             (contains? row :seon.error/at)
             (contains? row :seon.error/layer)
             (contains? row :seon.error/operation)) row (:seon.agent/plan row))))

(defn plan
  "Read this agent's whole plan as one derived current value.

  The returned steps are the agent's component tree in authored order, each
  carrying its derived parent, dependencies, depth, and state. Ready, blocked,
  and completion collections are queries over the same facts, not attributes."
  {:malli/schema
   [:=> [:catn [:request :my.plan/request]]
    [:or :my.plan/component-view :seon.error/value]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [agent-entity (agent-eid database agent-id)]
    (cond
      (and (map? agent-entity)
           (contains? agent-entity :seon.error/at)
           (contains? agent-entity :seon.error/layer)
           (contains? agent-entity :seon.error/operation)) agent-entity

      (nil? agent-entity)
      {:my.plan/agent-not-found true
       :seon.error/kind :my.plan/agent-not-found
       :seon.error/message (str "There is no agent named " (pr-str agent-id) ".")
       :seon.error/data {:seon.agent/id agent-id}}

      :else
      (let [pulled (agent-plan-pull database agent-id)
            frontier (when-not (and (map? pulled)
                                    (contains? pulled :seon.error/at)
                                    (contains? pulled :seon.error/layer)
                                    (contains? pulled :seon.error/operation))
                       (derived-frontier database
                                         (tree-nodes (:my.plan/steps pulled))))
            ready-ids (:my.plan/ready frontier)
            blocked-ids (:my.plan/blocked frontier)
            values [pulled frontier]]
        (if-let [error (some #(when (and (map? %)
                                         (contains? % :seon.error/at)
                                         (contains? % :seon.error/layer)
                                         (contains? % :seon.error/operation)) %) values)]
          (update error :seon.error/data
                  #(assoc (or % {}) :seon.agent/id agent-id))
          (let [current-id (get-in pulled [:my.plan/current-step
                                           :my.plan.item/id])
                steps (derived-steps (:my.plan/steps pulled) current-id
                                     ready-ids blocked-ids)
                by-id (into {} (map (juxt :my.plan.item/id identity)) steps)
                completions (completion-view database agent-id steps)]
            (cond-> {:seon.agent/id agent-id
                     :my.plan/steps steps
                     :my.plan/ready (into [] (keep by-id) (sort ready-ids))
                     :my.plan/blocked (into [] (keep by-id) (sort blocked-ids))
                     :my.plan/recent-completions
                     (:my.plan/recent-completions completions)}
              (:my.plan/objective pulled)
              (assoc :my.plan/objective (:my.plan/objective pulled))
              current-id (assoc :my.plan/current-step
                                {:my.plan.item/id current-id})
              (:my.plan/older-completions completions)
              (assoc :my.plan/older-completions
                     (:my.plan/older-completions completions)))))))))

(defn item
  "Read one plan step, with its derived dependencies, by its stable identity."
  {:malli/schema
   [:=> [:catn [:request :my.plan/item-request]]
    [:or :my.plan/render-step :seon.error/value]]}
  [{database :seon.db/db item-id :my.plan.item/id}]
  (let [entity (step-eid database item-id)]
    (cond
      (and (map? entity)
           (contains? entity :seon.error/at)
           (contains? entity :seon.error/layer)
           (contains? entity :seon.error/operation)) entity

      (nil? entity)
      {:my.plan/not-found true
       :seon.error/kind :my.plan/not-found
       :seon.error/message (str "There is no plan step named "
                                (pr-str item-id) ".")
       :seon.error/data {:my.plan.item/id item-id}}

      :else
      (let [agent-id (db/q '[:find ?id . :in $ % ?step
                             :where (owned ?agent ?step)
                                    [?agent :seon.agent/id ?id]]
                           database rules entity)]
        (cond
          (and (map? agent-id)
               (contains? agent-id :seon.error/at)
               (contains? agent-id :seon.error/layer)
               (contains? agent-id :seon.error/operation)) agent-id
          agent-id
          (let [view (plan {:seon.db/db database :seon.agent/id agent-id})]
            (if (and (map? view)
                     (contains? view :seon.error/at)
                     (contains? view :seon.error/layer)
                     (contains? view :seon.error/operation))
              view
              (first (filter #(= item-id (:my.plan.item/id %))
                             (:my.plan/steps view)))))
          :else
          (let [pulled (db/pull database step-selector entity)]
            (if (and (map? pulled)
                     (contains? pulled :seon.error/at)
                     (contains? pulled :seon.error/layer)
                     (contains? pulled :seon.error/operation))
              pulled
              (first (derived-steps [pulled] nil #{} #{})))))))))

(defn items
  "Read plan steps in the exact supplied identity order."
  {:malli/schema
   [:=> [:catn [:request :my.plan/items-request]]
    [:or :my.plan/render-steps :seon.error/value]]}
  [{database :seon.db/db item-ids :my.plan/item-ids}]
  (reduce (fn [result item-id]
            (let [step (item {:seon.db/db database :my.plan.item/id item-id})]
              (if (and (map? step)
                       (contains? step :seon.error/at)
                       (contains? step :seon.error/layer)
                       (contains? step :seon.error/operation))
                (reduced step)
                (conj result step))))
          [] item-ids))

(defn- step-summary
  [step]
  (if (and (map? step)
           (contains? step :seon.error/at)
           (contains? step :seon.error/layer)
           (contains? step :seon.error/operation))
    step
    (assoc (select-keys step [:my.plan.item/id :my.plan.item/title
                                     :my.plan.item/done-when
                                     :my.plan.item/done-query :my.plan.item/subject
                                     :my.plan.item/completed-tx :my.plan.item/about
                                     :my.plan/state])
           :my.plan/needs (vec (:my.plan/needs step)))))

(defn current
  "Read your current step; an empty map means none is selected."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  [:or :my.plan/current-value :seon.error/value]]}
  [database agent-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})
        current-id (get-in view [:my.plan/current-step :my.plan.item/id])]
    (if (and (map? view)
             (contains? view :seon.error/at)
             (contains? view :seon.error/layer)
             (contains? view :seon.error/operation))
      view
      (or (some #(when (= current-id (:my.plan.item/id %)) (step-summary %))
                (:my.plan/steps view))
          {}))))

(defn blocked
  "Read your blocked steps and the step identities they need."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [database agent-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})]
    (if (and (map? view)
             (contains? view :seon.error/at)
             (contains? view :seon.error/layer)
             (contains? view :seon.error/operation)) view (mapv step-summary (:my.plan/blocked view)))))

(defn steps
  "Read your plan steps in their authored tree order."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [database agent-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})]
    (if (and (map? view)
             (contains? view :seon.error/at)
             (contains? view :seon.error/layer)
             (contains? view :seon.error/operation)) view (mapv step-summary (:my.plan/steps view)))))

(defn ready
  "Read your ready steps; complete one with my.plan/complete!."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.agent/id]
    [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [database agent-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})]
    (if (and (map? view)
             (contains? view :seon.error/at)
             (contains? view :seon.error/layer)
             (contains? view :seon.error/operation)) view (mapv step-summary (:my.plan/ready view)))))

(defn ready-subjects
  "List the resolved subject entities named by this agent's ready steps.

  Ready-step order and each authored subject-vector order are retained.
  Repeated resolved rows collapse at their first occurrence."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.agent/id]
    [:or :my.plan/intent-subjects :seon.error/value]]}
  [database agent-id]
  (let [steps (ready database agent-id)]
    (if (and (map? steps)
             (contains? steps :seon.error/at)
             (contains? steps :seon.error/layer)
             (contains? steps :seon.error/operation))
      steps
      (try
        (into []
              (comp
               (mapcat :my.plan.item/about)
               (map #(resolve-subject! database %))
               (distinct))
              steps)
        (catch clojure.lang.ExceptionInfo failure
          (flat-refusal failure))))))

;;; ---------------------------------------------------------------------------
;;; Writers — decisions inside the transaction, against current state
;;; ---------------------------------------------------------------------------

(defn- transact-plan!
  [connection agent-id tx-data]
  (db/transact!
   connection
   {:tx-data tx-data
    :tx-meta {:seon.db/user [:seon.agent/id agent-id]}}))

(defn- owned-step-eid!
  [database agent-entity reference member]
  (let [step (read-result! (ref-eid database reference))]
    (when-not step
      (refuse! :my.plan/item-reference-not-found
               (str "Plan step reference " (pr-str reference)
                    " does not exist.")
               {member reference}))
    (let [owned? (read-result!
                  (db/q '[:find ?step .
                          :in $ % ?agent ?step
                          :where (owned ?agent ?step)]
                        database rules agent-entity step))]
      (when-not owned?
        (refuse! :my.plan/item-reference-not-owned
                 (str "Plan step reference " (pr-str reference)
                      " is not in this agent's plan.")
                 {member reference})))
    step))

(defn- next-position
  [database owner attribute]
  (let [position (read-result!
                  (db/q '[:find (max ?position) .
                          :in $ ?owner ?attribute
                          :where [?owner ?attribute ?child]
                                 [?child :my.plan.item/position ?position]]
                        database owner attribute))]
    (inc (long (or position -1)))))

(defn- add-step-call
  [database request]
  (let [item-id (:my.plan.item/id request)
        agent-id (:seon.agent/id request)
        agent-entity (read-result! (agent-eid database agent-id))]
    (when-not agent-entity
      (refuse! :my.plan/agent-not-found
               (str "There is no agent named " (pr-str agent-id) ".")
               {:seon.agent/id agent-id}))
    (when (read-result! (step-eid database item-id))
      (refuse! :my.plan/identity-exists
               (str "Plan step " (pr-str item-id) " already exists.")
               {:my.plan.item/id item-id}))
    (let [parent (when-some [reference (:my.plan/parent-step request)]
                   (owned-step-eid! database agent-entity reference
                                    :my.plan/parent-step))
          needs (into #{}
                      (map (fn [reference]
                             (if (read-result! (step-eid database reference))
                               reference
                               (refuse! :my.plan/dependency-not-found
                                          (str "Plan dependency "
                                               (pr-str reference)
                                               " does not exist.")
                                          {:my.plan.item/needs reference}))))
                      (:my.plan.item/needs request))
          _ (doseq [token (:my.plan.item/about request)]
              (resolve-subject! database token))
          existing-plan (read-result! (plan-eid database agent-entity))
          plan-entity (or existing-plan "new-agent-plan")
          owner (or parent plan-entity)
          attribute (if parent :my.plan.item/steps :my.plan/steps)
          tempid "new-plan-step"
          step (cond-> (dissoc request
                               :seon.agent/id
                               :my.plan/parent-step
                               :my.plan/current?)
                 true (assoc :db/id tempid
                             :my.plan.item/position
                             (if (or parent existing-plan)
                               (next-position database owner attribute)
                               0))
                 (seq needs) (assoc :my.plan.item/needs needs))]
      (cond-> [step [:db/add owner attribute tempid]
               [:db/add plan-entity :my.plan/agent agent-entity]]
        (= plan-entity "new-agent-plan")
        (conj [:db/add agent-entity :seon.agent/plan plan-entity])
        (:my.plan/current? request)
        (conj [:db/add plan-entity :my.plan/current-step tempid])))))

(defn- query-deadline
  [database agent-id]
  (let [limit (or (db/q '[:find ?limit . :in $ ?agent-id
                          :where [?agent :seon.agent/id ?agent-id]
                                 [?agent :seon.agent/settings ?settings]
                                 [?settings :seon.config.eval/time-limit-ms ?limit]]
                        database agent-id)
                  (db/q '[:find ?limit .
                          :where [?cluster :seon.cluster/config ?config]
                                 [?config :seon.config.eval/time-limit-ms ?limit]] database))]
    (when-not (pos-int? limit)
      (refuse! :my.plan/missing-query-bound
               "Plan completion queries require the configured evaluation time limit."
               {:seon.agent/id agent-id}))
    (+ (System/nanoTime) (* 1000000 limit))))

(def issue-done-query
  "The issue owner's completion query, shared with issue assignment."
  issue/done-query)

(defn- stale-issue-tests
  "The agent's open-issue tests whose reach closure changed or that never ran.
  A test whose closure is unchanged since its recorded result is not re-run:
  that result already answers the completion query. A success ref that no
  longer names a test stays in the set, so the settlement still reports it."
  [database agent-id]
  (let [tests (db/q '[:find [?test ...] :in $ ?agent-id
                      :where [?a :seon.agent/id ?agent-id]
                      [?i :seon.issue/agent ?a]
                      (not [?i :seon.issue/resolved-tx])
                      [?i :seon.issue/tests ?test]] database agent-id)]
    (when (and (map? tests)
               (contains? tests :seon.error/at)
               (contains? tests :seon.error/layer)
               (contains? tests :seon.error/operation))
      (throw (ex-info (:seon.error/message tests) tests)))
    (let [named (mapv (fn [test-eid]
                        [test-eid (:seon.test/sym
                                   (db/pull database [:seon.test/sym] test-eid))])
                      (sort tests))
          stale (seon.test/stale
                 database (into [] (keep second) named))]
      (when (and (map? stale)
                 (contains? stale :seon.error/at)
                 (contains? stale :seon.error/layer)
                 (contains? stale :seon.error/operation))
        (throw (ex-info (:seon.error/message stale) stale)))
      (let [changed (set stale)]
        (filterv (fn [[_ test-symbol]]
                   (or (nil? test-symbol) (contains? changed test-symbol)))
                 named)))))

(defn run-issue-tests!
  "Run an open issue's STALE tests under one shared evaluation deadline.
  The ordinary turn's close is the one settlement that calls this. A close
  that changed nothing a test reaches runs nothing and leaves the recorded
  results standing. Results, including
  unavailable or expired tests, use the existing test writer."
  {:malli/schema [:=> [:cat :seon.turn.loop/cluster :seon.agent/id] :nil]}
  [cluster agent-id]
  (let [connection (:seon.db/connection cluster)
        database (db/db connection)
        pending (stale-issue-tests database agent-id)]
    (when (seq pending)
      (let [deadline (query-deadline database agent-id)
            ctx (@cluster-agent-acquire-context! cluster agent-id)]
        (doseq [[test-eid test-symbol] pending]
          (let [remaining-ms (quot (- deadline (System/nanoTime)) 1000000)
                database (db/db connection)
                provenance (test.runner/provenance database)
                result
                (cond
                  (:seon.error/at provenance) provenance
                  test-symbol
                  (let [qualified (symbol test-symbol)
                        test-var (sci/resolve ctx qualified)
                        metadata (meta test-var)
                        runnable
                        (sci/new-var
                         (symbol (name qualified)) nil
                         (assoc metadata
                           :name (symbol (name qualified))
                           :ns (or (:ns metadata)
                                   (sci/create-ns
                                    (symbol (namespace qualified))))
                           :test
                           (fn []
                             (let [remaining (quot (- deadline (System/nanoTime)) 1000000)]
                               (when-not (pos? remaining)
                                 (throw (ex-info "The issue test set exhausted its evaluation deadline."
                                                 {:seon.test/sym test-symbol})))
                               (when-not (ifn? (:test metadata))
                                 (throw (ex-info "The issue test has no runnable SCI Var."
                                                 {:seon.test/sym test-symbol})))
                               (if (var? test-var)
                                 ((:test metadata))
                                 (sci.kernel/with-arm
                                  ctx remaining
                                  (fn [_] ((:test metadata)))))))))]
                    (seon.test/run
                     runnable connection
                     {:seon.db/db database
                      :seon.db/connection connection
                      :seon.sci.eval/ctx ctx
                      :seon.test.run/cluster [:seon.cluster/name (:seon.cluster/name cluster)]
                      :seon.test.run/provenance provenance
                      :seon.test/remaining-ms (max 1 remaining-ms)}))
                  :else
                  (refuse! :seon.issue/not-a-test
                             "An issue success ref no longer identifies a test."
                             {:seon.agent/id agent-id :seon.db/ref test-eid}))]
            (when (and (map? result)
                       (contains? result :seon.error/at)
                       (contains? result :seon.error/layer)
                       (contains? result :seon.error/operation))
              (throw (ex-info (:seon.error/message result) result)))))))
    nil))

(defn- done-query-result
  [database step deadline]
  (let [subject (:my.plan.item/subject step)
        subject (if (map? subject) (:db/id subject) subject)
        issue? (and subject (:seon.issue/id (db/pull database [:seon.issue/id] subject)))
        query (if issue? issue-done-query (:my.plan.item/done-query step))
        request (if (and (map? query) (:query query)) query {:query query})
        result (db/q (assoc request
                           :args (cond-> [database] subject (conj subject))
                           :cancel (reify clojure.lang.IDeref
                                     (deref [_] (> (System/nanoTime) deadline)))))]
    (when (and (map? result)
               (contains? result :seon.error/at)
               (contains? result :seon.error/layer)
               (contains? result :seon.error/operation))
      (refuse! :my.plan/done-query-failed
               (str "Completion query failed: " (pr-str query) "; found " (pr-str result))
               {:my.plan.item/id (:my.plan.item/id step)
                :my.plan.item/done-query query :seon.db/result result}))
    result))

(defn- query-satisfied?
  [result]
  (if (coll? result) (boolean (seq result)) (boolean result)))

(defn- completion-tx
  [database plan-entity step]
  (let [subject (db/q '[:find ?subject . :in $ ?step
                        :where [?step :my.plan.item/subject ?subject]
                        [?subject :seon.issue/id]] database step)]
    (cond-> [[:db/add step :my.plan.item/completed-tx "datomic.tx"]]
      subject (conj [:db/add subject :seon.issue/resolved-tx "datomic.tx"])
      (= step (db/q '[:find ?current . :in $ ?plan
                      :where [?plan :my.plan/current-step ?current]] database plan-entity))
      (conj [:db/retract plan-entity :my.plan/current-step step]))))

(defn settle-call
  "Evaluate every open query-backed step and record first completion in this write."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  :seon.db/tx-data]}
  [database agent-id]
  (let [steps (db/q '[:find [?step ...] :in $ % ?agent-id
                      :where [?agent :seon.agent/id ?agent-id]
                             (owned ?agent ?step)
                             [?step :my.plan.item/done-query _]
                             (not [?step :my.plan.item/completed-tx _])]
                    database rules agent-id)]
    (if (empty? steps)
      []
      (let [deadline (query-deadline database agent-id)
            plan-entity (plan-eid database (agent-eid database agent-id))]
        (conj (into []
              (mapcat (fn [eid]
                        (let [step (db/pull database step-selector eid)]
                          (when (query-satisfied? (done-query-result database step deadline))
                            (completion-tx database plan-entity eid)))))
              steps)
              [:db.fn/call #'issue/exhaust-tx agent-id])))))

(defn- complete-step-call
  [database request]
  (let [item-id (:my.plan.item/id request)
        agent-id (:seon.agent/id request)
        agent-entity (read-result! (agent-eid database agent-id))
        plan-entity (read-result! (plan-eid database agent-entity))
        step (read-result! (step-eid database item-id))]
    (when-not step
      (refuse! :my.plan/not-found
               (str "There is no plan step named " (pr-str item-id) ".")
               {:my.plan.item/id item-id}))
    (when-not (read-result!
               (db/q '[:find ?step .
                       :in $ % ?agent ?step
                       :where (owned ?agent ?step)]
                     database rules agent-entity step))
      (refuse! :my.plan/not-owned
               (str "Plan step " (pr-str item-id)
                    " is not in this agent's plan.")
               {:my.plan.item/id item-id
                :seon.agent/id agent-id}))
    (let [row (db/pull database step-selector step)]
      (when-let [query (:my.plan.item/done-query row)]
        (let [result (done-query-result database row (query-deadline database agent-id))]
          (when-not (query-satisfied? result)
            (refuse! :my.plan/done-query-unsatisfied
                     (str "Plan step " (pr-str item-id) " is not complete: done-query "
                          (pr-str query) " found " (pr-str result) ".")
                     {:my.plan.item/id item-id :my.plan.item/done-query query
                      :seon.db/result result})))))
    (if (db/q '[:find ?completed-at .
                :in $ ?step
                :where [?step :my.plan.item/completed-tx ?completed-at]]
              database step)
      []
      (completion-tx database plan-entity step))))

(defn- changed-item
  [database agent-id item-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})]
    (if (and (map? view)
             (contains? view :seon.error/at)
             (contains? view :seon.error/layer)
             (contains? view :seon.error/operation)) view
        (step-summary (first (filter #(= item-id (:my.plan.item/id %))
                                    (:my.plan/steps view)))))))

(defn add!
  "Add one step to this agent's plan, at the root or under a named parent step."
  {:malli/schema
   [:=> [:cat :my.plan.item/add-request
         :seon.db/connection :seon.agent/id]
    [:or :my.plan/step-summary :seon.error/value]]}
  [step connection agent-id]
  (let [step (cond-> step
               (not (:my.plan.item/id step))
               (assoc :my.plan.item/id (id/id (:my.plan.item/title step))))
        request (assoc step :seon.agent/id agent-id)
        result (transact-plan! connection agent-id
                               [[:db.fn/call #'add-step-call request]])]
    (if (and (map? result)
             (contains? result :seon.error/at)
             (contains? result :seon.error/layer)
             (contains? result :seon.error/operation))
      result
      (changed-item (:db-after result) agent-id (:my.plan.item/id step)))))

(defn complete!
  "Complete one owned step and clear it when it is this agent's current step."
  {:malli/schema
   [:=> [:cat :my.plan.item/id :seon.db/connection :seon.agent/id]
    [:or :my.plan/step-summary :seon.error/value]]}
  [item-id connection agent-id]
  (let [result
        (transact-plan! connection agent-id
                        [[:db.fn/call #'complete-step-call
                          {:my.plan.item/id item-id
                           :seon.agent/id agent-id}]])]
    (if (and (map? result)
             (contains? result :seon.error/at)
             (contains? result :seon.error/layer)
             (contains? result :seon.error/operation))
      result
      (changed-item (:db-after result) agent-id item-id))))

(defn- start-step-call
  [database agent-id item-id]
  (let [agent-entity (read-result! (agent-eid database agent-id))
        step (read-result! (step-eid database item-id))
        ids (read-result! (owned-ids database agent-id))]
    (when-not (and step (contains? ids item-id))
      (refuse! :my.plan/not-owned "Select a step owned by this agent."
               {:my.plan.item/id item-id :seon.agent/id agent-id}))
    (when (db/q '[:find ?completed . :in $ ?step
                  :where [?step :my.plan.item/completed-tx ?completed]]
                database step)
      (refuse! :my.plan/unusable-current-step "Select an open step."
               {:my.plan.item/id item-id}))
    [[:db/add (read-result! (plan-eid database agent-entity))
      :my.plan/current-step step]]))

(defn start!
  "Select one of your steps as current and return that step."
  {:malli/schema [:=> [:cat :my.plan.item/id :seon.db/connection
                       :seon.agent/id]
                  [:or :my.plan/step-summary :seon.error/value]]}
  [item-id connection agent-id]
  (let [result (transact-plan! connection agent-id
                               [[:db.fn/call #'start-step-call agent-id item-id]])]
    (if (and (map? result)
             (contains? result :seon.error/at)
             (contains? result :seon.error/layer)
             (contains? result :seon.error/operation))
      result
      (changed-item (:db-after result) agent-id item-id))))

(defn- update-step-call
  [database agent-id changes]
  (let [item-id (:my.plan.item/id changes)
        step (read-result! (step-eid database item-id))
        ids (read-result! (owned-ids database agent-id))]
    (when-not (and step (contains? ids item-id))
      (refuse! :my.plan/not-owned "Update a step owned by this agent."
               {:my.plan.item/id item-id :seon.agent/id agent-id}))
    (let [attributes (select-keys changes [:my.plan.item/title
                                          :my.plan.item/description
                                          :my.plan.item/done-when
                                          :my.plan.item/done-query :my.plan.item/subject])]
      (if (seq attributes) [(assoc attributes :db/id step)] []))))

(defn update!
  "Update an owned item's title, description, or done-when; return the changed item."
  {:malli/schema [:=> [:cat :my.plan/update-fields :seon.db/connection :seon.agent/id]
                  [:or :my.plan/step-summary :seon.error/value]]}
  [changes connection agent-id]
  (let [result (transact-plan! connection agent-id
                               [[:db.fn/call #'update-step-call agent-id changes]])]
    (if (and (map? result)
             (contains? result :seon.error/at)
             (contains? result :seon.error/layer)
             (contains? result :seon.error/operation)) result
        (changed-item (:db-after result) agent-id (:my.plan.item/id changes)))))

;;; ---------------------------------------------------------------------------
;;; Whole-tree reconciliation
;;; ---------------------------------------------------------------------------

(defn- input-entries
  "Flatten authored nested transaction data into positioned entries."
  [nodes]
  (letfn [(walk [nodes parent-id depth entries]
            (reduce
             (fn [result [index node]]
               (let [entry (-> (dissoc node :my.plan.item/steps)
                               (assoc :my.plan/parent-id parent-id
                                      :my.plan/depth depth
                                      :my.plan.item/position
                                      (long (get node :my.plan.item/position
                                                 index))))]
                 (walk (:my.plan.item/steps node)
                       (:my.plan.item/id node)
                       (inc depth)
                       (conj result entry))))
             entries
             (map-indexed vector nodes)))]
    (walk nodes nil 0 [])))

(defn- refuse-duplicate-identities!
  [entries]
  (doseq [[id occurrences] (frequencies (map :my.plan.item/id entries))]
    (when (> occurrences 1)
      (refuse! :my.plan/duplicate-identity
               (str "Plan step " (pr-str id)
                    " appears more than once in this plan.")
               {:my.plan.item/id id}))))

(defn- refuse-duplicate-positions!
  [entries]
  (doseq [[[parent-id position] occurrences]
          (frequencies (map (juxt :my.plan/parent-id :my.plan.item/position) entries))]
    (when (> occurrences 1)
      (refuse! :my.plan/duplicate-position
               (str "Two sibling steps claim position " position
                    (if parent-id
                      (str " under " (pr-str parent-id) ".")
                      " at the plan root."))
               {:my.plan.item/position position
                :my.plan/parent-step parent-id}))))

(defn- refuse-dependency-cycle!
  [needs-by-id]
  (let [visit
        (fn visit [id trail seen]
          (cond
            (contains? trail id)
            (refuse! :my.plan/dependency-cycle
                     (str "Plan dependencies form a cycle through "
                          (pr-str id) ".")
                     {:my.plan.item/id id})

            (contains? seen id) seen

            :else
            (let [trail (conj trail id)]
              (conj (reduce (fn [seen dependency]
                              (visit dependency trail seen))
                            seen
                            (get needs-by-id id))
                    id))))]
    (reduce (fn [seen id] (visit id #{} seen)) #{} (keys needs-by-id))))

(defn- entry-tx-map
  [entry tempid]
  (cond-> (dissoc entry :my.plan/parent-id :my.plan/depth :my.plan.item/needs)
    true (assoc :db/id tempid)))

(defn- scalar-retractions
  [database entry]
  (let [id (:my.plan.item/id entry)
        retained (set (:my.plan.item/needs entry))
        previous (read-result!
                  (db/q '[:find [?dependency ...]
                          :in $ ?id
                          :where [?step :my.plan.item/id ?id]
                                 [?step :my.plan.item/needs ?dependency]]
                        database id))]
    (into (mapv (fn [dependency]
                  [:db/retract [:my.plan.item/id id]
                   :my.plan.item/needs dependency])
                (remove retained previous))
          (keep (fn [attribute]
                  (when (and (not (contains? entry attribute))
                             (db/q '[:find ?value .
                                     :in $ ?id ?attribute
                                     :where
                                     [?step :my.plan.item/id ?id]
                                     [?step ?attribute ?value]]
                                   database id attribute))
                    [:db/retract [:my.plan.item/id id] attribute])))
          [:my.plan.item/description
           :my.plan.item/done-when
           :my.plan.item/done-query :my.plan.item/subject
           :my.plan.item/completed-tx
           :my.plan.item/about])))

(def ^:private comparable-selector
  '[:my.plan.item/id
    :my.plan.item/position
    :my.plan.item/title
    :my.plan.item/description
    :my.plan.item/done-when
    :my.plan.item/done-query
    {:my.plan.item/subject [:db/id]}
    :my.plan.item/completed-tx
    :my.plan.item/about
    (limit :my.plan.item/needs nil)
    {:my.plan.item/steps [:my.plan.item/id]}])

(defn- comparable
  [position title description expected completed-at about parent needs done-query subject]
  {:my.plan/position position
   :my.plan/title title
   :my.plan/description description
   :my.plan/expected expected
   :my.plan/completed-at completed-at
   :my.plan/about about
   :my.plan/parent parent
   :my.plan/needs needs
   :my.plan.item/done-query done-query
   :my.plan.item/subject subject})

(defn- stored-comparables
  "The current authored content of each owned step, keyed by identity."
  [database ids]
  (let [rows (db/pull-many database comparable-selector
                           (mapv (fn [id] [:my.plan.item/id id]) (sort ids)))
        rows (if (and (map? rows)
                      (contains? rows :seon.error/at)
                      (contains? rows :seon.error/layer)
                      (contains? rows :seon.error/operation)) [] rows)
        parents (into {}
                      (mapcat (fn [row]
                                (map (fn [child]
                                       [(:my.plan.item/id child)
                                        (:my.plan.item/id row)])
                                     (:my.plan.item/steps row))))
                      rows)]
    (into {}
          (map (fn [row]
                 [(:my.plan.item/id row)
                  (comparable (:my.plan.item/position row)
                              (:my.plan.item/title row)
                              (:my.plan.item/description row)
                              (:my.plan.item/done-when row)
                              (:my.plan.item/completed-tx row)
                              (:my.plan.item/about row)
                              (get parents (:my.plan.item/id row))
                              (set (:my.plan.item/needs row))
                              (:my.plan.item/done-query row)
                              (get-in row [:my.plan.item/subject :db/id]))]))
          rows)))

(defn- entry-comparable
  [database entry needs-by-id]
  (comparable (:my.plan.item/position entry)
              (:my.plan.item/title entry)
              (:my.plan.item/description entry)
              (:my.plan.item/done-when entry)
              (:my.plan.item/completed-tx entry)
              (:my.plan.item/about entry)
              (:my.plan/parent-id entry)
              (set (get needs-by-id (:my.plan.item/id entry)))
              (:my.plan.item/done-query entry)
              (when-let [subject (:my.plan.item/subject entry)]
                (read-result! (ref-eid database subject)))))

(defn- compile-tree
  [database agent-id input]
  (let [agent-entity (read-result! (agent-eid database agent-id))]
    (when-not agent-entity
      (refuse! :my.plan/agent-not-found
               (str "There is no agent named " (pr-str agent-id) ".")
               {:seon.agent/id agent-id}))
    (let [existing-plan (read-result! (plan-eid database agent-entity))
          plan-entity (or existing-plan "new-agent-plan")
          stored-objective (:my.plan/objective
                            (read-result! (agent-plan-pull database agent-id)))
          objective (:my.plan/objective input)
          entries (input-entries (:my.plan/steps input))
          _ (refuse-duplicate-identities! entries)
          _ (refuse-duplicate-positions! entries)
          wanted-ids (into #{} (map :my.plan.item/id) entries)
          existing (read-result! (owned-ids database agent-id))]
      (doseq [entry entries
              :let [id (:my.plan.item/id entry)]]
        (when (and (read-result! (step-eid database id))
                   (not (contains? existing id)))
          (refuse! :my.plan/foreign-identity
                   (str "Plan step " (pr-str id)
                        " belongs to another agent's plan.")
                   {:my.plan.item/id id}))
        (doseq [token (:my.plan.item/about entry)]
          (resolve-subject! database token))
        (doseq [reference (:my.plan.item/needs entry)]
          (when-not (or (contains? wanted-ids reference)
                        (read-result! (step-eid database reference)))
            (refuse! :my.plan/dependency-not-found
                     (str "Plan dependency " (pr-str reference)
                          " does not exist.")
                     {:my.plan.item/needs reference}))))
      (let [needs-by-id
            (into {}
                  (map (fn [entry]
                         [(:my.plan.item/id entry)
                          (vec (sort (:my.plan.item/needs entry)))]))
                  entries)
            _ (refuse-dependency-cycle! needs-by-id)
            current (get-in input [:my.plan/current-step :my.plan.item/id])
            _ (when current
                (let [entry (some #(when (= current (:my.plan.item/id %)) %)
                                  entries)]
                  (when (or (nil? entry) (:my.plan.item/completed-tx entry))
                    (refuse! :my.plan/unusable-current-step
                             (str "Current step " (pr-str current)
                                  " is not an open step of this plan.")
                             {:my.plan/current-step current}))))
            tempids (into {}
                          (map-indexed (fn [index entry]
                                         [(:my.plan.item/id entry)
                                          (str "plan-step-" index)]))
                          entries)
            step-ref (fn [id] (get tempids id [:my.plan.item/id id]))
            children (reduce (fn [result entry]
                               (update result (:my.plan/parent-id entry)
                                       (fnil conj [])
                                       (step-ref (:my.plan.item/id entry))))
                             {}
                             entries)
            step-maps
            (into []
                  (map (fn [entry]
                         (let [id (:my.plan.item/id entry)
                               nested (get children id)
                               needs (get needs-by-id id)]
                           (cond-> (entry-tx-map entry (step-ref id))
                             (seq nested)
                             (assoc :my.plan.item/steps (set nested))
                             (seq needs)
                             (assoc :my.plan.item/needs
                                    (set needs))))))
                  entries)
            retracted-ids (sort (remove wanted-ids existing))
            retractions (mapv (fn [id]
                                [:db.fn/retractEntity [:my.plan.item/id id]])
                              retracted-ids)
            scalars (into [] (mapcat #(scalar-retractions database %))
                          (filter #(contains? existing (:my.plan.item/id %))
                                  entries))
            agent-map
            (cond-> {:db/id plan-entity :my.plan/agent agent-entity}
              objective (assoc :my.plan/objective objective)
              (seq (get children nil))
              (assoc :my.plan/steps (set (get children nil)))
              current (assoc :my.plan/current-step (step-ref current)))
            clear-current
            (when (and existing-plan (not current)
                       (db/q '[:find ?current .
                               :in $ ?agent
                               :where [?agent :my.plan/current-step ?current]]
                             database plan-entity))
              [[:db/retract plan-entity :my.plan/current-step]])
            added (count (remove #(contains? existing (:my.plan.item/id %))
                                 entries))
            stored (stored-comparables database existing)
            changed (count
                     (filter (fn [entry]
                               (let [id (:my.plan.item/id entry)]
                                 (and (contains? stored id)
                                      (not= (get stored id)
                                            (entry-comparable
                                             database entry needs-by-id)))))
                             entries))]
        {:my.plan/tx-data (vec (concat step-maps
                                (when (or objective (seq entries))
                                  [agent-map [:db/add agent-entity :seon.agent/plan plan-entity]])
                                (when (and stored-objective (nil? objective))
                                  [[:db/retract plan-entity :my.plan/objective]])
                                scalars (or clear-current []) retractions))
         :my.plan/converged? (and (= objective stored-objective)
                           (zero? added)
                           (zero? changed)
                           (zero? (count retractions))
                           (empty? scalars)
                           (nil? clear-current)
                           (= current
                              (when existing-plan (db/q '[:find ?id .
                                      :in $ ?agent
                                      :where
                                      [?agent :my.plan/current-step ?step]
                                      [?step :my.plan.item/id ?id]]
                                    database existing-plan))))
         :my.plan/diff {:my.plan/added added
                 :my.plan/changed changed
                 :my.plan/retracted (count retractions)}}))))

(defn plan!
  "Reconcile one complete authored plan tree at an observed basis.

  The authored input is ordinary nested transaction data using the stored keys;
  vector order supplies any absent `:my.plan.item/position`. The compiler
  refuses duplicate identities, duplicate sibling positions, a step owned by
  another agent's plan, a dependency cycle, and a current step that is not an
  open step of this plan, then commits one basis-fenced transaction. Steps the
  document omits are retracted, and Datahike's component retraction removes the
  steps they own."
  {:malli/schema
   [:=> [:cat :my.plan/component-input :seon.db/database-value
         :seon.db/connection :seon.agent/id]
    [:or :my.plan/plan-result :seon.error/value]]}
  [input database connection agent-id]
  (try
    (let [compiled (compile-tree database agent-id input)
          basis (db/basis-t database)]
      (if (:my.plan/converged? compiled)
        {:my.plan/converged? true
         :my.plan/basis-t basis
         :my.plan/diff (:my.plan/diff compiled)}
        (let [result
              (db/transact!
               connection
               {:tx-data (:my.plan/tx-data compiled)
                :datahike/expected-basis-t basis
                :tx-meta {:seon.db/user [:seon.agent/id agent-id]}})]
          (if (and (map? result)
                   (contains? result :seon.error/at)
                   (contains? result :seon.error/layer)
                   (contains? result :seon.error/operation))
            result
            {:my.plan/converged? false
             :my.plan/basis-t basis
             :my.plan/diff (:my.plan/diff compiled)}))))
    (catch clojure.lang.ExceptionInfo failure
      (flat-refusal failure))))

;;; ---------------------------------------------------------------------------
;;; Declared AI and HTML projections
;;; ---------------------------------------------------------------------------

(defn- outline-numbers
  "Outline numbers for depth-first steps, e.g. 1, 1.1, 1.2, 2."
  [steps]
  (first
   (reduce
    (fn [[numbers counters] step]
      (let [depth (long (get step :my.plan/depth 0))
            counters (into (vec (take depth counters))
                           [(inc (long (get counters depth 0)))])]
        [(conj numbers (cond-> (str/join "." counters)
                         (zero? depth) (str ".")))
         counters]))
    [[] []]
    steps)))

(defn- state-word
  [state]
  (case state
    :completed "completed"
    :current "current"
    :blocked "blocked"
    :ready "ready"
    "open"))

(defn- step-line
  [number step]
  (let [completed (get-in step [:my.plan.item/completed-tx :db/txInstant])
        state (if completed :completed (:my.plan/state step))]
    (str number " " (state-word state) " — " (:my.plan.item/title step)
         (when completed (str " — " (.toInstant ^java.util.Date completed)))
         (when (and (= :current state) (:my.plan.item/done-when step))
           (str "\n   Done when: " (:my.plan.item/done-when step)))
         (when (and (= :current state) (:my.plan.item/done-query step))
           (str "\n   done-query: " (pr-str (:my.plan.item/done-query step))
                (when-let [subject (:my.plan.item/subject step)]
                  (str "\n   subject: " (pr-str subject))))))))

(defn- refusal-line
  "One typed line for a refusal that reached a plan projection.

  A refusal is data, so it is READ here and said plainly: what was asked
  for, whose it is, which refusal, and its message. A projection never
  hands an agent a bare exception message where its instructions belong."
  [subject value]
  (str subject " unavailable"
       (when-let [agent-id (get-in value [:seon.error/data :seon.agent/id])]
         (str " for " (pr-str agent-id)))
       " — " (pr-str (:seon.error/kind value)) ": "
       (:seon.error/message value)))

(defn format-item-ai
  "Format one plan step as terminal text."
  {:malli/schema [:=> [:cat [:or :my.plan/render-step :seon.error/value]]
                  [:or :string :seon.error/value]]}
  [step]
  (if (and (map? step)
           (contains? step :seon.error/at)
           (contains? step :seon.error/layer)
           (contains? step :seon.error/operation))
    (refusal-line "Plan step" step)
    (str "Plan step [" (:my.plan.item/id step) "] "
         (step-line (str (inc (get step :my.plan.item/position 0))) step))))

(defn render-item-ai
  "Render a returned plan step through the same compact step formatter."
  {:malli/schema [:=> [:cat :my.plan/render-step] :string]}
  [step]
  (format-item-ai step))

(defn- item-html
  [step titles]
  (let [state (get step :my.plan/state :open)]
    [:article {:class (str "seon-family-entry my-plan-item is-"
                           (state-word state))}
     [:p {:class "my-plan-status"}
      [:span {:class "my-plan-state"}
       [:span {:aria-hidden "true"} "●"] " "
       (case state :completed "done" :current "current" :blocked "blocked" "pending")]
      (when-let [at (get-in step [:my.plan.item/completed-tx :db/txInstant])]
        (let [instant (.toInstant ^java.util.Date at)
              local (.atZone instant (java.time.ZoneId/systemDefault))]
          [:time {:datetime (str instant) :title (str local)}
           (.format local (java.time.format.DateTimeFormatter/ofPattern "MMM d, HH:mm"))]))]
     [:h3 (:my.plan.item/title step)]
     (when-let [description (:my.plan.item/description step)] [:p description])
     (when-let [expected (:my.plan.item/done-when step)]
       (let [criterion [:p {:class "my-plan-expected"} [:strong "Done when: "] expected]]
         (if (= :completed state)
           [:details {:data-preserve-attr "open"} [:summary "Completion criterion"] criterion]
           criterion)))
     (when (= :blocked state)
       (when-let [needs (seq (:my.plan/needs step))]
         [:p {:class "my-plan-relation"} [:strong "Blocked on: "]
          (str/join ", " (map #(get titles % %) needs))]))]))

(defn render-item-html
  "Show a step's state, completion time, and expected outcome."
  {:malli/schema [:=> [:cat :my.plan/render-step] :seon.render/hiccup]}
  [step]
  (item-html step
             (when-let [database (:seon.db/db step)]
               (into {} (map (juxt :my.plan.item/id :my.plan.item/title))
                     (db/pull-many database [:my.plan.item/id :my.plan.item/title]
                                   (mapv #(vector :my.plan.item/id %) (:my.plan/needs step)))))))

(defn format-ready-items-ai
  "Format a supplied ready plan frontier as terminal text."
  {:malli/schema [:=> [:cat [:or :my.plan/ready-items :seon.error/value]]
                  [:or :string :seon.error/value]]}
  [steps]
  (if (and (map? steps)
           (contains? steps :seon.error/at)
           (contains? steps :seon.error/layer)
           (contains? steps :seon.error/operation))
    (refusal-line "Ready work" steps)
    (if (seq steps)
      (str "Ready work (" (count steps) "):\n"
           (str/join "\n" (map #(str "- " (:my.plan.item/title %)
                                     " [" (:my.plan.item/id %) "]")
                               steps)))
      "No plan step is ready.")))

(defn render-ready-items-ai
  "Render source which reads and formats the supplied ready step selection."
  {:malli/schema [:=> [:cat :my.plan/ready-items] :seon.render/source]}
  [steps]
  (pr-str
   (list `format-ready-items-ai
         (list `items {:my.plan/item-ids (mapv :my.plan.item/id steps)}))))

(defn render-ready-items-html
  "Render the ready plan frontier as Hiccup."
  {:malli/schema [:=> [:cat :my.plan/ready-items] :seon.render/hiccup]}
  [steps]
  (into [:section {:class "seon-family-entry my-plan-ready"}
         [:h3 (str "Ready work (" (count steps) ")")]]
        (map render-item-html)
        steps))

(defn- update-example
  "One executable form updating this plan, using real stable identities."
  [view]
  (let [agent-id (:seon.agent/id view)
        current (get-in view [:my.plan/current-step :my.plan.item/id])
        other? #(not= current (:my.plan.item/id %))
        next-step (or (some :my.plan.item/id
                            (filter other? (:my.plan/ready view)))
                      (some :my.plan.item/id
                            (filter other? (:my.plan/blocked view)))
                      (some :my.plan.item/id
                            (filter other? (:my.plan/steps view)))
                      (some :my.plan.item/id (:my.plan/steps view)))]
    (if-not next-step
      (str "(my.plan/add! {:my.plan.item/id \"" agent-id "/first-step\""
           " :my.plan.item/title \"My first step\"} )")
      (pr-str
       (list 'my.plan/current! {:my.plan.item/id next-step})))))

(defn- current-title
  [view]
  (when-let [current (get-in view [:my.plan/current-step :my.plan.item/id])]
    (or (some #(when (= current (:my.plan.item/id %)) (:my.plan.item/title %))
              (:my.plan/steps view))
        current)))

(defn format-plan-ai
  "Show the current criterion and one line per other step as readable data."
  {:malli/schema [:=> [:cat [:or :my.plan/component-view :seon.error/value]]
                  [:or :string :seon.error/value]]}
  [view]
  (if (and (map? view)
           (contains? view :seon.error/at)
           (contains? view :seon.error/layer)
           (contains? view :seon.error/operation))
    (refusal-line "Plan" view)
    (let [steps (:my.plan/steps view)
          current-id (get-in view [:my.plan/current-step :my.plan.item/id])
          lines (mapv (fn [number step]
                        [(:my.plan.item/id step) (step-line number step)])
                      (outline-numbers steps) steps)
          current (some #(when (= current-id (first %)) (second %)) lines)]
      (str "{:seon.agent/id " (pr-str (:seon.agent/id view))
           (when-let [objective (:my.plan/objective view)]
             (str "\n :my.plan/objective " (pr-str objective)))
           (when current
             (str "\n :seon.plan/current-line "
                  (pr-str (str "[" current-id "] " current))))
           "\n :seon.plan/step-lines {"
           (str/join "\n                   "
                     (map (fn [[id line]] (str (pr-str id) " " (pr-str line)))
                          (remove #(= current-id (first %)) lines)))
           "}\n :seon.plan/update-example " (pr-str (update-example view)) "}"))))

(defn render-plan-ai
  "Read my plan and teach the completing call for its selected step."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (let [database (:seon.db/db unit)
        component (or (:seon.render/value unit) unit)
        selected (:my.plan/current-step component)
        agent-id (:seon.agent/id unit)
        derivation (when (and database agent-id)
                     (plan {:seon.db/db database :seon.agent/id agent-id}))
        current-id (or (when (map? selected) (:my.plan.item/id selected))
                       (when (and database selected)
                         (:my.plan.item/id
                          (db/pull database [:my.plan.item/id]
                                   (if (map? selected) (:db/id selected) selected))))
                       (get-in derivation [:my.plan/current-step
                                           :my.plan.item/id]))]
    (if (and (map? derivation)
             (contains? derivation :seon.error/at)
             (contains? derivation :seon.error/layer)
             (contains? derivation :seon.error/operation))
      ;; The derivation refused. The agent still reads a typed line through
      ;; this plan's own AI pair — never a bare exception message where its
      ;; instructions belong — and the refusal stays a `:seon.error` value.
      (str ";; My plan could not be derived from current facts, so it is not"
           " shown; the refusal below names it.\n"
           (repl/source-text (list 'seon.plan/format-plan-ai
                                   (list 'seon.plan/plan {}))))
      (str ";; My plan is my instructions. "
           (if current-id
             (str "After seeing the result and verifying the criterion, complete this step with "
                  "(my.plan/complete! {:my.plan.item/id " (pr-str current-id) "}). ")
             "No step is selected. ")
           "my.plan/current! selects; completing clears the selection.\n"
           (repl/source-text (list 'seon.plan/plan {}))))))

(defn render-plan-html
  "Show the objective, current focus, progress, and every step with its state."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.error/value]]}
  [unit]
  (let [database (:seon.db/db unit)
        component (or (:seon.render/value unit) unit)
        agent-id (or (:seon.agent/id unit)
                     (when (and database (:db/id component))
                       (db/q '[:find ?id . :in $ ?plan
                               :where [?agent :seon.agent/plan ?plan]
                                      [?agent :seon.agent/id ?id]]
                             database (:db/id component))))
        view (if (and database agent-id)
               (plan {:seon.db/db database :seon.agent/id agent-id})
               component)]
    (if (and (map? view)
             (contains? view :seon.error/at)
             (contains? view :seon.error/layer)
             (contains? view :seon.error/operation))
      view
      (let [steps (:my.plan/steps view)
            done (count (filter :my.plan.item/completed-tx steps))]
        [:section {:class "seon-family-entry my-plan"}
         [:header [:p {:class "seon-kicker"} "Plan"]
          [:h3 (get view :my.plan/objective "No objective set")]]
         [:p {:class "my-plan-progress"} [:strong "Current step: "]
          (or (current-title view) "None selected")]
         [:p {:class "my-plan-progress"} (str done " of " (count steps) " steps completed")]
         [:progress {:value done :max (max 1 (count steps))
                     :aria-label "Plan progress"}]
         (if (seq steps)
           (let [titles (into {} (map (juxt :my.plan.item/id :my.plan.item/title)) steps)]
             (into [:ol {:class "my-plan-steps"}]
                   (map (fn [step]
                          [:li {:style {:margin-left (str (* 0.75 (get step :my.plan/depth 0)) "rem")}}
                           (item-html step titles)]))
                   steps))
           [:p "No steps yet."])]))))
