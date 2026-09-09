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

  Completion is the presence of `:my.plan.item/completed-at`; ready, blocked,
  parent, depth, and state are queries over current facts, never stored."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.id :as id]
            [seon.print :as print]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schemas/my.plan.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Derivation over the component tree
;;; ---------------------------------------------------------------------------

(def rules
  "Datalog rules deriving ownership, readiness, and blockage from current facts."
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
     (descendant ?root ?step)]
    [(leaf ?step)
     [?step :my.plan.item/id]
     (not-join [?step] [?step :my.plan.item/steps _])]
    [(open-work ?step)
     [?step :my.plan.item/id]
     (not-join [?step] [?step :my.plan.item/completed-at _])
     (leaf ?step)]
    [(open-work ?step)
     (descendant ?step ?leaf)
     [?leaf :my.plan.item/id]
     (not-join [?leaf] [?leaf :my.plan.item/completed-at _])
     (leaf ?leaf)]
    [(blocked ?step)
     [?step :my.plan.item/needs ?dependency]
     (open-work ?dependency)]
    [(ready ?step)
     [?step :my.plan.item/id]
     (not-join [?step] [?step :my.plan.item/completed-at _])
     (leaf ?step)
     (not (blocked ?step))]
    [(ready ?step)
     [?step :my.plan.item/id]
     (not-join [?step] [?step :my.plan.item/completed-at _])
     (not (leaf ?step))
     (not (open-work ?step))
     (not (blocked ?step))]])

(def ^:private step-selector
  ;; The renderers consume this pull. Every reference in it is a stable
  ;; identity map, so no entity id can reach an output.
  '[:my.plan.item/id
    :my.plan.item/position
    :my.plan.item/title
    :my.plan.item/description
    :my.plan.item/expected-result
    :my.plan.item/completed-at
    :my.plan.item/about
    {:my.plan.item/needs [:my.plan.item/id]}
    {:my.plan.item/steps 8}])

(defn- error-value?
  [value]
  (and (map? value) (keyword? (:seon.error/kind value))))

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

(defn- agent-eid
  [database agent-id]
  (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.agent/id ?agent-id]]
        database agent-id))

(defn- plan-eid
  [database agent-entity]
  (db/q '[:find ?plan . :in $ ?agent
          :where [?agent :seon.agent/plan ?plan]]
        database agent-entity))

(defn- step-eid
  [database item-id]
  (db/q '[:find ?step .
          :in $ ?item-id
          :where [?step :my.plan.item/id ?item-id]]
        database item-id))

(defn- ref-eid
  [database reference]
  (some-> (db/entity database reference) :db/id))

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
          database (str token))

    :else
    (db/q '[:find ?subject .
            :in $ ?namespace
            :where [?subject :seon.ns/name ?namespace]]
          database token)))

(defn- resolve-subject!
  [database token]
  (or (subject-eid database token)
      (refuse! :my.plan/subject-not-found
               (str "Plan subject " (pr-str token) " does not exist.")
               {:my.plan.item/about token})))

(def ^:private owned-ids-query
  '[:find [?id ...]
    :in $ % ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    (owned ?agent ?step)
    [?step :my.plan.item/id ?id]])

(def ^:private ready-ids-query
  '[:find [?id ...]
    :in $ % ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    (owned ?agent ?step)
    [?step :my.plan.item/id ?id]
    (ready ?step)])

(def ^:private blocked-ids-query
  '[:find [?id ...]
    :in $ % ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    (owned ?agent ?step)
    [?step :my.plan.item/id ?id]
    (not-join [?step] [?step :my.plan.item/completed-at _])
    (blocked ?step)])

(defn- owned-ids
  [database agent-id]
  (let [ids (db/q owned-ids-query database rules agent-id)]
    (if (error-value? ids) ids (set ids))))

;;; ---------------------------------------------------------------------------
;;; Pulled tree to derived render steps
;;; ---------------------------------------------------------------------------

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
      (:my.plan.item/completed-at step) :completed
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
                     (let [needs (into []
                                       (map :my.plan.item/id)
                                       (sort-by :my.plan.item/id
                                                (:my.plan.item/needs node)))
                           step
                           (cond-> (dissoc node
                                           :my.plan.item/steps
                                           :my.plan.item/needs
                                           :db/id)
                             (seq needs) (assoc :my.plan/needs needs)
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
                   [(- (.getTime ^java.util.Date (:my.plan.item/completed-at step)))
                    (:my.plan.item/id step)])
                 (filter :my.plan.item/completed-at steps)))})

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
    (if (error-value? row) row (:seon.agent/plan row))))

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
      (error-value? agent-entity) agent-entity

      (nil? agent-entity)
      {:my.plan/agent-not-found true
       :seon.error/kind :my.plan/agent-not-found
       :seon.error/message (str "There is no agent named " (pr-str agent-id) ".")
       :seon.error/data {:seon.agent/id agent-id}}

      :else
      (let [pulled (agent-plan-pull database agent-id)
            ready-ids (db/q ready-ids-query database rules agent-id)
            blocked-ids (db/q blocked-ids-query database rules agent-id)
            values [pulled ready-ids blocked-ids]]
        (if-let [error (some #(when (error-value? %) %) values)]
          error
          (let [current-id (get-in pulled [:my.plan/current-step
                                           :my.plan.item/id])
                steps (derived-steps (:my.plan/steps pulled) current-id
                                     (set ready-ids) (set blocked-ids))
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
      (error-value? entity) entity

      (nil? entity)
      {:my.plan/not-found true
       :seon.error/kind :my.plan/not-found
       :seon.error/message (str "There is no plan step named "
                                (pr-str item-id) ".")
       :seon.error/data {:my.plan.item/id item-id}}

      :else
      (let [pulled (db/pull database step-selector entity)]
        (if (error-value? pulled)
          pulled
          (first (derived-steps [pulled] nil #{} #{})))))))

(defn items
  "Read plan steps in the exact supplied identity order."
  {:malli/schema
   [:=> [:catn [:request :my.plan/items-request]]
    [:or :my.plan/render-steps :seon.error/value]]}
  [{database :seon.db/db item-ids :my.plan/item-ids}]
  (let [pulled (db/pull-many database step-selector
                             (mapv (fn [id] [:my.plan.item/id id]) item-ids))]
    (if (error-value? pulled)
      pulled
      (into [] (map #(first (derived-steps [%] nil #{} #{}))) pulled))))

(defn- step-summary
  [step]
  (if (error-value? step)
    step
    (cond-> (assoc (select-keys step [:my.plan.item/id :my.plan.item/title
                                     :my.plan.item/completed-at :my.plan.item/about
                                     :my.plan/state])
                   :my.plan/needs (vec (:my.plan/needs step)))
      (:my.plan.item/expected-result step)
      (assoc :my.plan/done-when (:my.plan.item/expected-result step)))))

(defn current
  "Read your current step; an empty map means none is selected."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  [:or :my.plan/current-value :seon.error/value]]}
  [database agent-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})
        current-id (get-in view [:my.plan/current-step :my.plan.item/id])]
    (if (error-value? view)
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
    (if (error-value? view) view (mapv step-summary (:my.plan/blocked view)))))

(defn steps
  "Read your plan steps in their authored tree order."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [database agent-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})]
    (if (error-value? view) view (mapv step-summary (:my.plan/steps view)))))

(defn ready
  "Read your ready steps; complete one with my.plan/complete!."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.agent/id]
    [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [database agent-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})]
    (if (error-value? view) view (mapv step-summary (:my.plan/ready view)))))

(defn ready-subjects
  "List the resolved subject entities named by this agent's ready steps.

  Ready-step order and each authored subject-vector order are retained.
  Repeated resolved rows collapse at their first occurrence."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.agent/id]
    [:or :my.plan/intent-subjects :seon.error/value]]}
  [database agent-id]
  (let [steps (ready database agent-id)]
    (if (error-value? steps)
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
  (let [step (ref-eid database reference)]
    (when-not step
      (refuse! :my.plan/item-reference-not-found
               (str "Plan step reference " (pr-str reference)
                    " does not exist.")
               {member reference}))
    (when-not (db/q '[:find ?step .
                      :in $ % ?agent ?step
                      :where (owned ?agent ?step)]
                    database rules agent-entity step)
      (refuse! :my.plan/item-reference-not-owned
               (str "Plan step reference " (pr-str reference)
                    " is not in this agent's plan.")
               {member reference}))
    step))

(defn- next-position
  [database owner attribute]
  (inc (long (or (db/q '[:find (max ?position) .
                         :in $ ?owner ?attribute
                         :where [?owner ?attribute ?child]
                                [?child :my.plan.item/position ?position]]
                       database owner attribute) -1))))

(defn- add-step-call
  [database request]
  (let [item-id (:my.plan.item/id request)
        agent-id (:seon.agent/id request)
        agent-entity (agent-eid database agent-id)]
    (when-not agent-entity
      (refuse! :my.plan/agent-not-found
               (str "There is no agent named " (pr-str agent-id) ".")
               {:seon.agent/id agent-id}))
    (when (step-eid database item-id)
      (refuse! :my.plan/identity-exists
               (str "Plan step " (pr-str item-id) " already exists.")
               {:my.plan.item/id item-id}))
    (let [parent (when-some [reference (:my.plan/parent-step request)]
                   (owned-step-eid! database agent-entity reference
                                    :my.plan/parent-step))
          needs (into #{}
                      (map (fn [reference]
                             (or (ref-eid database reference)
                                 (refuse! :my.plan/dependency-not-found
                                          (str "Plan dependency "
                                               (pr-str reference)
                                               " does not exist.")
                                          {:my.plan.item/needs reference}))))
                      (:my.plan.item/needs request))
          _ (doseq [token (:my.plan.item/about request)]
              (resolve-subject! database token))
          existing-plan (plan-eid database agent-entity)
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
      (cond-> [step [:db/add owner attribute tempid]]
        (= plan-entity "new-agent-plan")
        (conj [:db/add agent-entity :seon.agent/plan plan-entity])
        (:my.plan/current? request)
        (conj [:db/add plan-entity :my.plan/current-step tempid])))))

(defn- complete-step-call
  [database request]
  (let [item-id (:my.plan.item/id request)
        agent-id (:seon.agent/id request)
        agent-entity (agent-eid database agent-id)
        plan-entity (plan-eid database agent-entity)
        step (step-eid database item-id)]
    (when-not step
      (refuse! :my.plan/not-found
               (str "There is no plan step named " (pr-str item-id) ".")
               {:my.plan.item/id item-id}))
    (when-not (db/q '[:find ?step .
                      :in $ % ?agent ?step
                      :where (owned ?agent ?step)]
                    database rules agent-entity step)
      (refuse! :my.plan/not-owned
               (str "Plan step " (pr-str item-id)
                    " is not in this agent's plan.")
               {:my.plan.item/id item-id
                :seon.agent/id agent-id}))
    (if (db/q '[:find ?completed-at .
                :in $ ?step
                :where [?step :my.plan.item/completed-at ?completed-at]]
              database step)
      []
      (cond->
       [[:db/add step :my.plan.item/completed-at
         (:my.plan.item/completed-at request)]]
        (= step (db/q '[:find ?current .
                        :in $ ?agent
                        :where [?agent :my.plan/current-step ?current]]
                      database plan-entity))
        (conj [:db/retract plan-entity :my.plan/current-step step])))))

(defn- changed-item
  [database agent-id item-id]
  (let [view (plan {:seon.db/db database :seon.agent/id agent-id})]
    (if (error-value? view) view
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
               (assoc :my.plan.item/id (id/digest 12 [agent-id (:my.plan.item/title step)]))
               (:my.plan/done-when step)
               (assoc :my.plan.item/expected-result (:my.plan/done-when step)))
        request (assoc (dissoc step :my.plan/done-when) :seon.agent/id agent-id)
        result (transact-plan! connection agent-id
                               [[:db.fn/call #'add-step-call request]])]
    (if (error-value? result)
      result
      (changed-item (:db-after result) agent-id (:my.plan.item/id step)))))

(defn complete!
  "Complete one owned step and clear it when it is this agent's current step."
  {:malli/schema
   [:=> [:cat :my.plan.item/id :my.plan.item/completed-at
         :seon.db/connection :seon.agent/id]
    [:or :my.plan/step-summary :seon.error/value]]}
  [item-id completed-at connection agent-id]
  (let [result
        (transact-plan! connection agent-id
                        [[:db.fn/call #'complete-step-call
                          {:my.plan.item/id item-id
                           :my.plan.item/completed-at completed-at
                           :seon.agent/id agent-id}]])]
    (if (error-value? result)
      result
      (changed-item (:db-after result) agent-id item-id))))

(defn- start-step-call
  [database agent-id item-id]
  (let [agent-entity (agent-eid database agent-id)
        step (step-eid database item-id)]
    (when-not (and step (contains? (owned-ids database agent-id) item-id))
      (refuse! :my.plan/not-owned "Select a step owned by this agent."
               {:my.plan.item/id item-id :seon.agent/id agent-id}))
    (when (db/q '[:find ?completed . :in $ ?step
                  :where [?step :my.plan.item/completed-at ?completed]]
                database step)
      (refuse! :my.plan/unusable-current-step "Select an open step."
               {:my.plan.item/id item-id}))
    [[:db/add (plan-eid database agent-entity) :my.plan/current-step step]]))

(defn start!
  "Select one of your steps as current and return that step."
  {:malli/schema [:=> [:cat :my.plan.item/id :seon.db/connection
                       :seon.agent/id]
                  [:or :my.plan/step-summary :seon.error/value]]}
  [item-id connection agent-id]
  (let [result (transact-plan! connection agent-id
                               [[:db.fn/call #'start-step-call agent-id item-id]])]
    (if (error-value? result)
      result
      (changed-item (:db-after result) agent-id item-id))))

(defn- update-step-call
  [database agent-id changes]
  (let [item-id (:my.plan.item/id changes)
        step (step-eid database item-id)]
    (when-not (and step (contains? (owned-ids database agent-id) item-id))
      (refuse! :my.plan/not-owned "Update a step owned by this agent."
               {:my.plan.item/id item-id :seon.agent/id agent-id}))
    (let [attributes (cond-> (select-keys changes [:my.plan.item/title
                                                  :my.plan.item/description])
                       (:my.plan/done-when changes)
                       (assoc :my.plan.item/expected-result (:my.plan/done-when changes)))]
      (if (seq attributes) [(assoc attributes :db/id step)] []))))

(defn update!
  "Update an owned item's title, description, or done-when; return the changed item."
  {:malli/schema [:=> [:cat :my.plan/update-fields :seon.db/connection :seon.agent/id]
                  [:or :my.plan/step-summary :seon.error/value]]}
  [changes connection agent-id]
  (let [result (transact-plan! connection agent-id
                               [[:db.fn/call #'update-step-call agent-id changes]])]
    (if (error-value? result) result
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
  (let [id (:my.plan.item/id entry)]
    (into []
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
           :my.plan.item/expected-result
           :my.plan.item/completed-at
           :my.plan.item/about])))

(defn- document-reference-id
  "The stable identity a `[:my.plan.item/id \"x\"]` reference names, or nil."
  [reference]
  (when (and (vector? reference)
             (= :my.plan.item/id (first reference)))
    (second reference)))

(def ^:private comparable-selector
  '[:my.plan.item/id
    :my.plan.item/position
    :my.plan.item/title
    :my.plan.item/description
    :my.plan.item/expected-result
    :my.plan.item/completed-at
    :my.plan.item/about
    {:my.plan.item/needs [:my.plan.item/id]}
    {:my.plan.item/steps [:my.plan.item/id]}])

(defn- comparable
  [position title description expected completed-at about parent needs]
  {:my.plan/position position
   :my.plan/title title
   :my.plan/description description
   :my.plan/expected expected
   :my.plan/completed-at completed-at
   :my.plan/about about
   :my.plan/parent parent
   :my.plan/needs needs})

(defn- stored-comparables
  "The current authored content of each owned step, keyed by identity."
  [database ids]
  (let [rows (db/pull-many database comparable-selector
                           (mapv (fn [id] [:my.plan.item/id id]) (sort ids)))
        rows (if (error-value? rows) [] rows)
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
                              (:my.plan.item/expected-result row)
                              (:my.plan.item/completed-at row)
                              (:my.plan.item/about row)
                              (get parents (:my.plan.item/id row))
                              (into #{} (map :my.plan.item/id)
                                    (:my.plan.item/needs row)))]))
          rows)))

(defn- entry-comparable
  [entry needs-by-id]
  (comparable (:my.plan.item/position entry)
              (:my.plan.item/title entry)
              (:my.plan.item/description entry)
              (:my.plan.item/expected-result entry)
              (:my.plan.item/completed-at entry)
              (:my.plan.item/about entry)
              (:my.plan/parent-id entry)
              (set (get needs-by-id (:my.plan.item/id entry)))))

(defn- compile-tree
  [database agent-id input]
  (let [agent-entity (agent-eid database agent-id)]
    (when-not agent-entity
      (refuse! :my.plan/agent-not-found
               (str "There is no agent named " (pr-str agent-id) ".")
               {:seon.agent/id agent-id}))
    (let [existing-plan (plan-eid database agent-entity)
          plan-entity (or existing-plan "new-agent-plan")
          stored-objective (:my.plan/objective (agent-plan-pull database agent-id))
          objective (:my.plan/objective input)
          entries (input-entries (:my.plan/steps input))
          _ (refuse-duplicate-identities! entries)
          _ (refuse-duplicate-positions! entries)
          wanted-ids (into #{} (map :my.plan.item/id) entries)
          existing (owned-ids database agent-id)
          existing (if (error-value? existing) #{} existing)]
      (doseq [entry entries
              :let [id (:my.plan.item/id entry)]]
        (when (and (step-eid database id) (not (contains? existing id)))
          (refuse! :my.plan/foreign-identity
                   (str "Plan step " (pr-str id)
                        " belongs to another agent's plan.")
                   {:my.plan.item/id id}))
        (doseq [token (:my.plan.item/about entry)]
          (resolve-subject! database token))
        (doseq [reference (:my.plan.item/needs entry)]
          (when-not (or (contains? wanted-ids (document-reference-id reference))
                        (ref-eid database reference))
            (refuse! :my.plan/dependency-not-found
                     (str "Plan dependency " (pr-str reference)
                          " does not exist.")
                     {:my.plan.item/needs reference}))))
      (let [needs-by-id
            (into {}
                  (map (fn [entry]
                         [(:my.plan.item/id entry)
                          (into []
                                (keep (fn [reference]
                                        (let [id (document-reference-id
                                                  reference)]
                                          (if (contains? wanted-ids id)
                                            id
                                            (db/q '[:find ?id .
                                                    :in $ ?step
                                                    :where
                                                    [?step :my.plan.item/id
                                                     ?id]]
                                                  database
                                                  (ref-eid database
                                                           reference))))))
                                (:my.plan.item/needs entry))]))
                  entries)
            _ (refuse-dependency-cycle! needs-by-id)
            current (get-in input [:my.plan/current-step :my.plan.item/id])
            _ (when current
                (let [entry (some #(when (= current (:my.plan.item/id %)) %)
                                  entries)]
                  (when (or (nil? entry) (:my.plan.item/completed-at entry))
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
                                    (into #{} (map step-ref) needs))))))
                  entries)
            retracted-ids (sort (remove wanted-ids existing))
            retractions (mapv (fn [id]
                                [:db.fn/retractEntity [:my.plan.item/id id]])
                              retracted-ids)
            scalars (into [] (mapcat #(scalar-retractions database %))
                          (filter #(contains? existing (:my.plan.item/id %))
                                  entries))
            agent-map
            (cond-> {:db/id plan-entity}
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
                                             entry needs-by-id)))))
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
          (if (error-value? result)
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

(defn- state-label
  [state]
  (case state
    :completed "Completed"
    :current "Current step"
    :blocked "Blocked"
    :ready "Ready"
    "Open"))

(defn- needs-text
  [step]
  (when-let [needs (seq (:my.plan/needs step))]
    (str " — waiting for "
         (str/join ", " (map pr-str needs)))))

(defn- step-line
  [number step]
  (str number " " (:my.plan.item/title step)
       " [" (:my.plan.item/id step) "] — "
       (state-word (:my.plan/state step))
       (needs-text step)
       (when-let [description (:my.plan.item/description step)]
         (str "\n" (str/join (repeat (count number) " ")) "   " description))
       (when-let [expected (:my.plan.item/expected-result step)]
         (str "\n" (str/join (repeat (count number) " "))
              "   Done when: " expected))))

(defn format-item-ai
  "Format one plan step as terminal text."
  {:malli/schema [:=> [:cat [:or :my.plan/render-step :seon.error/value]]
                  [:or :string :seon.error/value]]}
  [step]
  (if (error-value? step)
    step
    (str "Plan step " (step-line "1" step))))

(defn render-item-ai
  "Render source which reads and formats one plan step."
  {:malli/schema [:=> [:cat :my.plan/render-step] :seon.render/source]}
  [step]
  (pr-str
   (list `format-item-ai
         (list `item {:my.plan.item/id (:my.plan.item/id step)}))))

(defn render-item-html
  "Explain one step in an agent's plan and its expected outcome."
  {:malli/schema [:=> [:cat :my.plan/render-step] :seon.render/hiccup]}
  [step]
  (let [state (get step :my.plan/state :open)]
    [:article {:class (str "seon-family-entry my-plan-item is-"
                           (state-word state))}
     [:p {:class "my-plan-id"}
      [:span {:class "my-plan-state"} (state-label state)]
      [:code {:style {:color "var(--color-text-300)"}} (:my.plan.item/id step)]]
     [:h3 (:my.plan.item/title step)]
     (into (if (= :current state)
             [:details {:open true} [:summary "Details"]]
             [:details [:summary "Details"]])
           (remove nil?)
           [(when-let [description (:my.plan.item/description step)]
              [:p {:style {:color "var(--color-text-200)"}} description])
            (when-let [expected (:my.plan.item/expected-result step)]
              [:p {:class "my-plan-expected"}
               [:strong "Done when: "] expected])
            (when-let [parent (:my.plan/parent step)]
              [:p {:class "my-plan-relation"}
               [:strong "Part of "] [:code (:my.plan.item/id parent)]])
            (when-let [needs (seq (:my.plan/needs step))]
              [:p {:class "my-plan-relation"}
               [:strong "Waiting for "]
               (str/join ", " needs)])
            [:p {:class "my-plan-reference" :style {:color "var(--color-text-300)"}}
             [:strong "Reference "]
             [:code (pr-str [:my.plan.item/id (:my.plan.item/id step)])]]])]))

(defn format-ready-items-ai
  "Format a supplied ready plan frontier as terminal text."
  {:malli/schema [:=> [:cat [:or :my.plan/ready-items :seon.error/value]]
                  [:or :string :seon.error/value]]}
  [steps]
  (if (error-value? steps)
    steps
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
       (list 'seon.db/transact!
             [{:db/id [:seon.agent/id agent-id]
               :my.plan/current-step [:my.plan.item/id next-step]}])))))

(defn- current-title
  [view]
  (when-let [current (get-in view [:my.plan/current-step :my.plan.item/id])]
    (or (some #(when (= current (:my.plan.item/id %)) (:my.plan.item/title %))
              (:my.plan/steps view))
        current)))

(defn- section-ai
  [title steps]
  (str title " (" (count steps) ")"
       (when (seq steps)
         (str ":\n"
              (str/join "\n"
                        (map #(str "- " (:my.plan.item/title %)
                                   " [" (:my.plan.item/id %) "]"
                                   (needs-text %))
                             steps))))))

(defn format-plan-ai
  "Format this agent's whole plan as terminal text."
  {:malli/schema [:=> [:cat [:or :my.plan/component-view :seon.error/value]]
                  [:or :string :seon.error/value]]}
  [view]
  (if (error-value? view)
    view
    (let [steps (:my.plan/steps view)
          older (:my.plan/older-completions view)
          objective (:my.plan/objective view)]
      (str/join
       "\n\n"
       (cond->
        [(str "Plan for " (:seon.agent/id view)
              (when objective
                (str "\nObjective: " objective))
              (if-let [current (current-title view)]
                (str "\nCurrent step: " current)
                "\nCurrent step: none selected"))
         (if (seq steps)
           (str "Steps:\n"
                (str/join "\n" (map step-line (outline-numbers steps) steps)))
           "Steps: none yet.")
         (section-ai "Ready now" (:my.plan/ready view))
         (section-ai "Waiting" (:my.plan/blocked view))
         (section-ai "Recently finished" (:my.plan/recent-completions view))
         (str "Update the current step:\n" (update-example view))]
         older (conj (print/render-elision-ai older)))))))

(defn render-plan-ai
  "Read my complete plan as the instructions for what to do next."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_view]
  ";; I should follow my plan and verify the current step's completion criterion.\n(my.plan/items)")

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
    (if (error-value? view)
      view
      (let [steps (:my.plan/steps view)
            done (count (filter :my.plan.item/completed-at steps))]
        [:section {:class "seon-family-entry my-plan"}
         [:header [:p {:class "seon-kicker"} "Plan"]
          [:h3 (get view :my.plan/objective "No objective set")]]
         [:p [:strong "Current step: "]
          (or (current-title view) "None selected")]
         [:p (str done " of " (count steps) " steps completed")]
         [:progress {:value done :max (max 1 (count steps))
                     :aria-label "Plan progress"}]
         (if (seq steps)
           (into [:ol {:class "my-plan-steps"}]
                 (map (fn [step]
                        [:li {:style {:margin-left (str (* 1.25 (get step :my.plan/depth 0)) "rem")}}
                         (render-item-html step)]))
                 steps)
           [:p "No steps yet."])]))))
