(ns my.plan
  "The agent-linked authored plan graph and its derived current view.

  Plan items are ordinary database entities connected to their agent through
  `:my.plan.item/agent`; parent and needs refs express decomposition and
  dependencies. Completion is the presence of `:my.plan.item/completed-at`,
  while ready and blocked are queries over current facts."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.db :as db]
            [seon.print :as print]
            [seon.render.value :as render.value]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schemas/my.plan.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Authored graph derivation
;;; ---------------------------------------------------------------------------

(def rules
  "Datalog rules deriving readiness and blockage from current item facts."
  '[[(descendant ?ancestor ?node)
     [?node :my.plan.item/parent ?ancestor]]
    [(descendant ?ancestor ?node)
     [?middle :my.plan.item/parent ?ancestor]
     (descendant ?middle ?node)]
    [(leaf ?item)
     (not-join [?item] [?child :my.plan.item/parent ?item])]
    [(open-work ?item)
     [?item :my.plan.item/id]
     (not-join [?item] [?item :my.plan.item/completed-at _])
     (leaf ?item)]
    [(open-work ?item)
     (descendant ?item ?leaf)
     [?leaf :my.plan.item/id]
     (not-join [?leaf] [?leaf :my.plan.item/completed-at _])
     (leaf ?leaf)]
    [(blocked ?item)
     [?item :my.plan.item/needs ?dependency]
     (open-work ?dependency)]
    [(ready ?item)
     [?item :my.plan.item/id]
     (not-join [?item] [?item :my.plan.item/completed-at _])
     (leaf ?item)
     (not (blocked ?item))]
    [(ready ?item)
     [?item :my.plan.item/id]
     (not-join [?item] [?item :my.plan.item/completed-at _])
     (not (leaf ?item))
     (not (open-work ?item))
     (not (blocked ?item))]])

(def ^:private item-selector
  '[:my.plan.item/id
    :my.plan.item/title
    :my.plan.item/description
    :my.plan.item/completed-at
    :my.plan.item/expected-result
    {:my.plan.item/agent [:db/id]}
    {:my.plan.item/parent [:db/id :my.plan.item/id]}
    {:my.plan.item/needs [:db/id :my.plan.item/id]}
    :my.plan.item/about])

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

(defn- agent-eid
  [database agent-id]
  (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.cluster.agent/id ?agent-id]]
        database agent-id))

(defn- item-eid
  [database item-id]
  (db/q '[:find ?item .
          :in $ ?item-id
          :where [?item :my.plan.item/id ?item-id]]
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
      (refuse! ::subject-not-found
               (str "Plan subject " (pr-str token) " does not exist.")
               {:my.plan.item/about token})))

(defn- item-owner
  [database item]
  (db/q '[:find ?agent .
          :in $ ?item
          :where [?item :my.plan.item/agent ?agent]]
        database item))

(defn- owned-item-eid
  [database agent-entity item-reference member]
  (let [item (ref-eid database item-reference)]
    (when-not item
      (refuse! ::item-reference-not-found
               (str "Plan item reference " (pr-str item-reference)
                    " does not exist.")
               {member item-reference}))
    (when (not= agent-entity (item-owner database item))
      (refuse! ::item-reference-not-owned
               (str "Plan item reference " (pr-str item-reference)
                    " belongs to another agent.")
               {member item-reference}))
    item))

(defn- resolve-item-refs
  [database agent-entity request]
  (let [parent
        (when-some [reference (:my.plan.item/parent request)]
          (owned-item-eid database agent-entity reference :my.plan.item/parent))
        needs
        (into #{}
              (map #(owned-item-eid database agent-entity % :my.plan.item/needs))
              (:my.plan.item/needs request))]
    (doseq [token (:my.plan.item/about request)]
      (resolve-subject! database token))
    (cond-> request
      parent (assoc :my.plan.item/parent parent)
      (seq needs) (assoc :my.plan.item/needs needs))))

(defn- add-item-call
  [database request]
  (let [item-id (:my.plan.item/id request)
        agent-id (:seon.cluster.agent/id request)
        agent-entity (agent-eid database agent-id)]
    (when-not agent-entity
      (refuse! ::agent-not-found
               (str "There is no agent named " (pr-str agent-id) ".")
               {:seon.cluster.agent/id agent-id}))
    (when (item-eid database item-id)
      (refuse! ::identity-exists
               (str "Plan item " (pr-str item-id) " already exists.")
               {:my.plan.item/id item-id}))
    (let [resolved (resolve-item-refs database agent-entity request)
          tempid "new-plan-item"
          item
          (-> resolved
              (dissoc :seon.cluster.agent/id :my.plan/anchor?)
              (assoc :db/id tempid :my.plan.item/agent agent-entity))]
      (cond-> [item]
        (:my.plan/anchor? request)
        (conj [:db/add agent-entity :my.plan/anchor tempid])))))

(defn- complete-item-call
  [database request]
  (let [item-id (:my.plan.item/id request)
        agent-id (:seon.cluster.agent/id request)
        agent-entity (agent-eid database agent-id)
        item (item-eid database item-id)]
    (when-not item
      (refuse! ::not-found
               (str "There is no plan item named " (pr-str item-id) ".")
               {:my.plan.item/id item-id}))
    (when (not= agent-entity (item-owner database item))
      (refuse! ::not-owned
               (str "Plan item " (pr-str item-id)
                    " belongs to another agent.")
               {:my.plan.item/id item-id
                :seon.cluster.agent/id agent-id}))
    (if (db/q '[:find ?completed-at .
                :in $ ?item
                :where [?item :my.plan.item/completed-at ?completed-at]]
              database item)
      []
      (cond->
       [[:db/add item :my.plan.item/completed-at
         (:my.plan.item/completed-at request)]]
        (= item
               (db/q '[:find ?anchor .
                       :in $ ?agent
                       :where [?agent :my.plan/anchor ?anchor]]
                     database agent-entity))
        (conj [:db/retract agent-entity :my.plan/anchor item])))))

(defn- transact-plan!
  [connection agent-id tx-data]
  (db/transact!
   connection
   {:tx-data tx-data
    :tx-meta {:seon.db/user [:seon.cluster.agent/id agent-id]}}))

(defn- item-value
  [value]
  (let [entity
        (cond
          (and (map? value) (map? (:seon.render/value value)))
          (:seon.render/value value)

          (map? value) value

          :else nil)]
    (if-not entity
      value
      (cond-> (render.value/transacted entity)
        (find entity :my.plan.item/about)
        (assoc :my.plan.item/about (:my.plan.item/about entity))))))

(defn- item-row
  [database item]
  (let [row (db/pull database item-selector item)]
    (if (error-value? row) row (item-value row))))

(defn add!
  "Add one authored item after validating its agent and item refs."
  {:malli/schema
   [:=> [:cat :my.plan.item/add-request
         :seon.db/connection :seon.cluster.agent/id]
    [:or :my.plan.item/item :seon.error/value]]}
  [item connection agent-id]
  (let [request (assoc item :seon.cluster.agent/id agent-id)
        result
        (transact-plan!
         connection agent-id
         [[:db.fn/call #'add-item-call request]])]
    (if (error-value? result)
      result
      (item-row (:db-after result)
                [:my.plan.item/id (:my.plan.item/id item)]))))

(defn complete!
  "Complete one owned item and clear it when it is the current anchor."
  {:malli/schema
   [:=> [:cat :my.plan.item/id :my.plan.item/completed-at
         :seon.db/connection :seon.cluster.agent/id]
    [:or :my.plan.item/item :seon.error/value]]}
  [item-id completed-at connection agent-id]
  (let [result
        (transact-plan!
         connection agent-id
         [[:db.fn/call #'complete-item-call
           {:my.plan.item/id item-id
            :my.plan.item/completed-at completed-at
            :seon.cluster.agent/id agent-id}]])]
    (if (error-value? result)
      result
      (item-row (:db-after result) [:my.plan.item/id item-id]))))

;;; ---------------------------------------------------------------------------
;;; Whole-value authored reconciliation
;;; ---------------------------------------------------------------------------

(def ^:private authored-fields
  [:my.plan.item/title
   :my.plan.item/description
   :my.plan.item/expected-result
   :my.plan.item/about])

(defn- document-entries
  [tree]
  (letfn [(walk [parent-index nodes entries]
            (reduce
             (fn [result node]
               (let [index (count result)
                     children (:my.plan/children node)
                     entry (assoc (dissoc node :my.plan/children)
                                  ::index index
                                  ::parent-index parent-index)]
                 (walk index children (conj result entry))))
             entries
             nodes))]
    (walk nil tree [])))

(defn- current-authored-rows
  [database agent-id]
  (db/q '[:find [(pull ?item ?selector) ...]
          :in $ ?selector ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?item :my.plan.item/agent ?agent]]
        database item-selector agent-id))

(defn- current-by-id
  [rows]
  (into {} (map (juxt :my.plan.item/id identity)) rows))

(defn- parent-id
  [row]
  (some-> (:my.plan.item/parent row) :my.plan.item/id))

(defn- need-ids
  [row]
  (into #{} (map :my.plan.item/id) (:my.plan.item/needs row)))

(defn- candidate-ids
  [baseline wanted-parent-id title]
  (into []
        (comp
         (filter (fn [[_ row]]
                   (and (= wanted-parent-id (parent-id row))
                        (= title (:my.plan.item/title row)))))
         (map first))
        baseline))

(defn- resolve-entry-identities
  [entries baseline]
  (loop [index 0
         resolved []
         claimed #{}]
    (if (= index (count entries))
      resolved
      (let [entry (nth entries index)
            explicit-id (:my.plan.item/id entry)
            parent-index (::parent-index entry)
            resolved-parent (when (some? parent-index)
                              (:my.plan.item/id (nth resolved parent-index)))
            roots (when-not parent-index
                    (into []
                          (keep (fn [[id row]]
                                  (when (nil? (parent-id row)) id)))
                          baseline))
            candidates
            (cond
              explicit-id [explicit-id]
              (and (nil? parent-index) (= 1 (count roots))) roots
              parent-index
              (candidate-ids baseline resolved-parent
                             (:my.plan.item/title entry))
              :else [])]
        (when (and (nil? explicit-id) (> (count candidates) 1))
          (refuse! ::ambiguous-identity
                   (str "Plan item " (pr-str (:my.plan.item/title entry))
                        " is ambiguous; carry one of "
                        (pr-str (vec (sort candidates))) ".")
                   {:my.plan/candidates (vec (sort candidates))}))
        (let [resolved-id
              (or explicit-id
                  (when (= 1 (count candidates)) (first candidates))
                  (::allocated-id entry))]
          (when (claimed resolved-id)
            (refuse! ::duplicate-identity
                     (str "Plan item " (pr-str resolved-id)
                          " appears more than once.")
                     {:my.plan.item/id resolved-id}))
          (recur (inc index)
                 (conj resolved (assoc entry :my.plan.item/id resolved-id))
                 (conj claimed resolved-id)))))))

(defn- validate-resolved-entries!
  [database agent-entity all-by-id baseline entries]
  (let [labels (keep :my.plan/label entries)
        duplicate-label
        (some (fn [[label n]] (when (> n 1) label)) (frequencies labels))]
    (when duplicate-label
      (refuse! ::duplicate-label
               (str "Plan label " (pr-str duplicate-label)
                    " appears more than once.")
               {:my.plan/label duplicate-label}))
    (doseq [entry entries
            :let [id (:my.plan.item/id entry)
                  current (get all-by-id id)]]
      (when (and current (:my.plan.item/completed-at current))
        (refuse! ::completed-identity
                 (str "Completed plan item " (pr-str id)
                      " cannot be rewritten through plan!.")
                 {:my.plan.item/id id}))
      (when (and current (not (contains? baseline id)))
        (refuse! ::foreign-identity
                 (str "Plan item " (pr-str id)
                      " belongs to another agent.")
                 {:my.plan.item/id id}))
      (doseq [reference (:my.plan.item/needs entry)]
        (when-not (ref-eid database reference)
          (refuse! ::dependency-not-found
                   (str "Plan dependency " (pr-str reference)
                        " does not exist.")
                   {:my.plan.item/needs reference})))
      (doseq [token (:my.plan.item/about entry)]
        (resolve-subject! database token)))
    (let [known-labels (set labels)]
      (doseq [entry entries
              label (:my.plan/after entry)]
        (when-not (known-labels label)
          (refuse! ::unknown-label
                   (str "Plan dependency label " (pr-str label)
                        " does not exist in this document.")
                   {:my.plan/label label}))))
    agent-entity))

(defn- referenced-item-id
  [database agent-entity reference]
  (let [item (owned-item-eid database agent-entity reference
                             :my.plan.item/needs)]
    (db/q '[:find ?id .
            :in $ ?item
            :where [?item :my.plan.item/id ?id]]
          database item)))

(defn- desired-entry
  [database agent-entity entries labels entry]
  (let [id (:my.plan.item/id entry)
        parent-index (::parent-index entry)
        wanted-parent-id (when (some? parent-index)
                           (:my.plan.item/id (nth entries parent-index)))
        direct-needs
        (into #{}
              (map #(referenced-item-id database agent-entity %))
              (:my.plan.item/needs entry))
        labelled-needs (into #{} (map #(get labels %)) (:my.plan/after entry))]
    (cond-> {:my.plan.item/id id
             :my.plan.item/title (:my.plan.item/title entry)
             :my.plan.item/agent agent-entity}
      (:my.plan.item/description entry)
      (assoc :my.plan.item/description (:my.plan.item/description entry))

      (:my.plan.item/expected-result entry)
      (assoc :my.plan.item/expected-result
             (:my.plan.item/expected-result entry))

      wanted-parent-id (assoc :my.plan.item/parent wanted-parent-id)
      (seq (into direct-needs labelled-needs))
      (assoc :my.plan.item/needs (into direct-needs labelled-needs))
      (find entry :my.plan.item/about)
      (assoc :my.plan.item/about (:my.plan.item/about entry)))))

(defn- scalar-ops
  [id current desired]
  (mapcat
   (fn [attribute]
     (let [before (get current attribute)
           after (get desired attribute)]
       (cond
         (= before after) []
         (nil? after) [[:db/retract [:my.plan.item/id id] attribute]]
         :else [[:db/add [:my.plan.item/id id] attribute after]])))
   authored-fields))

(defn- ref-one-ops
  [target-ref id attribute before after]
  (if (= before after)
    []
    (cond-> []
      (and before (nil? after))
      (conj [:db/retract [:my.plan.item/id id] attribute])
      after
      (conj [:db/add [:my.plan.item/id id] attribute (target-ref after)]))))

(defn- ref-many-ops
  [target-ref id attribute before after]
  (concat
   (map (fn [value]
          [:db/retract [:my.plan.item/id id] attribute value])
        (sort-by pr-str (set/difference before after)))
   (map (fn [value]
          [:db/add [:my.plan.item/id id] attribute (target-ref value)])
        (sort-by pr-str (set/difference after before)))))

(defn- update-ops
  [target-ref current desired]
  (let [id (:my.plan.item/id desired)
        current-parent (parent-id current)
        desired-parent (:my.plan.item/parent desired)
        current-needs (need-ids current)
        desired-needs (into #{} (:my.plan.item/needs desired))]
    (vec
     (concat
      (scalar-ops id current desired)
      (ref-one-ops target-ref id :my.plan.item/parent
                   current-parent desired-parent)
      (ref-many-ops target-ref id :my.plan.item/needs
                    current-needs desired-needs)))))

(defn- compile-plan
  [database agent-id tree allocated-ids]
  (let [agent-entity (agent-eid database agent-id)]
    (when-not agent-entity
      (refuse! ::agent-not-found
               (str "There is no agent named " (pr-str agent-id) ".")
               {:seon.cluster.agent/id agent-id}))
    (let [raw (mapv #(assoc %1 ::allocated-id %2)
                    (document-entries tree) allocated-ids)
          all-rows
          (db/q '[:find [(pull ?item ?selector) ...]
                  :in $ ?selector
                  :where [?item :my.plan.item/id]]
                database item-selector)
          all-by-id (current-by-id all-rows)
          current-rows (current-authored-rows database agent-id)
          baseline
          (into {}
                (comp
                 (remove :my.plan.item/completed-at)
                 (map (juxt :my.plan.item/id identity)))
                current-rows)
          entries (resolve-entry-identities raw baseline)
          _ (validate-resolved-entries! database agent-entity all-by-id
                                        baseline entries)
          labels
          (into {}
                (keep (fn [entry]
                        (when-let [label (:my.plan/label entry)]
                          [label (:my.plan.item/id entry)])))
                entries)
          desired
          (mapv #(desired-entry database agent-entity entries labels %) entries)
          desired-by-id (current-by-id desired)
          new-ids (set/difference (set (keys desired-by-id))
                                  (set (keys baseline)))
          tempids (into {} (map-indexed (fn [index id]
                                          [id (str "plan-item-" index)]))
                        (sort new-ids))
          target-ref (fn [id]
                       (or (get tempids id) [:my.plan.item/id id]))
          additions
          (into []
                (map (fn [item]
                       (cond-> (assoc item :db/id
                                     (get tempids (:my.plan.item/id item)))
                         (:my.plan.item/parent item)
                         (update :my.plan.item/parent target-ref)

                         (:my.plan.item/needs item)
                         (update :my.plan.item/needs
                                 #(into #{} (map target-ref) %)))))
                (filter #(new-ids (:my.plan.item/id %)) desired))
          updates
          (into []
                (keep (fn [item]
                        (when-let [current (get baseline
                                                (:my.plan.item/id item))]
                          (let [operations (update-ops target-ref current item)]
                            (when (seq operations) operations)))))
                desired)
          retractions
          (into []
                (map (fn [id]
                       [:db.fn/retractEntity [:my.plan.item/id id]]))
                (sort (set/difference (set (keys baseline))
                                      (set (keys desired-by-id)))))]
      {::tx-data (vec (concat additions (mapcat identity updates) retractions))
       ::diff {:my.plan/added (count additions)
               :my.plan/changed (count updates)
               :my.plan/retracted (count retractions)}
       ::ids labels})))

(defn- flat-refusal
  [throwable]
  (let [data (ex-data throwable)]
    (if (:seon.error/kind data)
      data
      (throw throwable))))

(defn plan!
  "Reconcile one complete authored plan tree at an observed basis.

  This convenience compiler validates ownership and subject tokens, preserves
  completed identities, and commits one basis-fenced diff. Direct
  `seon.db/transact!` writes operate on the same item and agent attributes but
  do not run these helper checks."
  {:malli/schema
   [:=> [:cat :my.plan/tree :seon.db/database-value
         :seon.db/connection :seon.cluster.agent/id]
    [:or :my.plan/plan-result :seon.error/value]]}
  [tree database connection agent-id]
  (try
    (let [entries (document-entries tree)
          allocated-ids (mapv (fn [_] (str (random-uuid))) entries)
          compiled (compile-plan database agent-id tree allocated-ids)
          tx-data (::tx-data compiled)
          basis (db/basis-t database)]
      (if (empty? tx-data)
        {:my.plan/converged? true
         :my.plan/basis-t basis
         :my.plan/diff (::diff compiled)
         :my.plan/ids (::ids compiled)}
        (let [result
              (db/transact!
               connection
               {:tx-data tx-data
                :datahike/expected-basis-t basis
                :tx-meta
                {:seon.db/user [:seon.cluster.agent/id agent-id]}})]
          (if (error-value? result)
            result
            {:my.plan/converged? false
             :my.plan/basis-t basis
             :my.plan/diff (::diff compiled)
             :my.plan/ids (::ids compiled)}))))
    (catch clojure.lang.ExceptionInfo failure
      (flat-refusal failure))))

;;; ---------------------------------------------------------------------------
;;; Current reads — authored facts plus structurally separate derived arms
;;; ---------------------------------------------------------------------------

(def ^:private ready-query
  '[:find [?item ...]
    :in $ % ?agent-id
    :where
    [?agent :seon.cluster.agent/id ?agent-id]
    [?item :my.plan.item/agent ?agent]
    (ready ?item)])

(def ^:private blocked-query
  '[:find [?item ...]
    :in $ % ?agent-id
    :where
    [?agent :seon.cluster.agent/id ?agent-id]
    [?item :my.plan.item/agent ?agent]
    [?item :my.plan.item/id]
    (not-join [?item] [?item :my.plan.item/completed-at _])
    (blocked ?item)])

(defn- item-order
  [anchor item]
  [(if (= anchor (:my.plan.item/id item)) 0 1)
   (:my.plan.item/title item)
   (:my.plan.item/id item)])

(defn- items-for-eids
  [database anchor eids]
  (let [rows (mapv #(item-row database %) eids)]
    (if-let [error (some #(when (error-value? %) %) rows)]
      error
      (vec (sort-by #(item-order anchor %) rows)))))

(defn- anchor-id
  [database agent-id]
  (db/q '[:find ?item-id .
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?agent :my.plan/anchor ?item]
          [?item :my.plan.item/id ?item-id]]
        database agent-id))

(defn ready
  "Derive this agent's ready authored items from current facts."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.cluster.agent/id]
    [:or :my.plan/ready-items :seon.error/value]]}
  [database agent-id]
  (let [eids (db/q ready-query database rules agent-id)]
    (if (error-value? eids)
      eids
      (items-for-eids database (anchor-id database agent-id) eids))))

(defn ready-subjects
  "List the resolved subject entities named by this agent's ready items.

  Ready-item order and each authored subject-vector order are retained.
  Repeated resolved rows collapse at their first occurrence."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.cluster.agent/id]
    [:or :my.plan/intent-subjects :seon.error/value]]}
  [database agent-id]
  (let [items (ready database agent-id)]
    (if (error-value? items)
      items
      (try
        (into []
              (comp
               (mapcat :my.plan.item/about)
               (map #(resolve-subject! database %))
               (distinct))
              items)
        (catch clojure.lang.ExceptionInfo failure
          (flat-refusal failure))))))

(defn- message-obligations
  [database agent-id]
  (let [rows
        (db/q '[:find ?message ?id ?content
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?message :seon.cluster.message/to ?agent]
                [?message :seon.cluster.message/id ?id]
                [?message :seon.cluster.message/content ?content]
                (not-join [?message]
                  [?run :seon.cluster.run/trigger ?message])]
              database agent-id)]
    (if (error-value? rows)
      rows
      (mapv
       (fn [[message id content]]
         {:my.plan/obligation-source :message
          :my.plan/obligation-id id
          :my.plan/obligation-title content
          :my.plan/obligation-ref message})
       (sort-by second rows)))))

(defn- run-obligations
  [database agent-id]
  (let [rows
        (db/q '[:find ?run ?id
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?run :seon.cluster.run/agent ?agent]
                [?run :seon.cluster.run/id ?id]
                (not-join [?run] [?run :seon.cluster.run/closed-at _])]
              database agent-id)]
    (if (error-value? rows)
      rows
      (mapv
       (fn [[run id]]
         {:my.plan/obligation-source :run
          :my.plan/obligation-id id
          :my.plan/obligation-title (str "Finish open run " (pr-str id) ".")
          :my.plan/obligation-ref run})
       (sort-by second rows)))))

(defn- test-obligations
  [database agent-id]
  (let [rows
        (db/q '[:find ?test ?symbol ?failures ?errors
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?agent :seon.cluster.agent/namespace ?namespace]
                [?test :seon.test/ns ?namespace]
                [?test :seon.test/sym ?symbol]
                [?test :seon.test/fail-count ?failures]
                [?test :seon.test/error-count ?errors]
                [(+ ?failures ?errors) ?red]
                [(> ?red 0)]]
              database agent-id)]
    (if (error-value? rows)
      rows
      (mapv
       (fn [[test-entity test-symbol failures errors]]
         {:my.plan/obligation-source :test
          :my.plan/obligation-id test-symbol
          :my.plan/obligation-title
          (str "Fix " test-symbol " (" failures " failures, " errors " errors).")
          :my.plan/obligation-ref test-entity})
       (sort-by second rows)))))

(defn- obligations
  [database agent-id]
  (let [arms [(message-obligations database agent-id)
              (run-obligations database agent-id)
              (test-obligations database agent-id)]]
    (if-let [error (some #(when (error-value? %) %) arms)]
      error
      (into [] cat arms))))

(defn- completion-limit
  [database agent-id]
  (let [cluster-name
        (db/q '[:find ?cluster-name .
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?agent :seon.cluster.agent/cluster ?cluster]
                [?cluster :seon.cluster/name ?cluster-name]]
              database agent-id)
        effective (when cluster-name (config/effective database cluster-name))]
    (long
     (:seon.config.render.agent/max-children
      (if (and (map? effective) (not (error-value? effective)))
        effective
        (config/defaults))))))

(defn- completion-view
  [database agent-id]
  (let [rows
        (db/q '[:find ?item ?completed-at ?id
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?item :my.plan.item/agent ?agent]
                [?item :my.plan.item/id ?id]
                [?item :my.plan.item/completed-at ?completed-at]]
              database agent-id)]
    (if (error-value? rows)
      rows
      (let [ordered
            (sort-by
             (fn [[_ completed-at id]]
               [(- (.getTime ^java.util.Date completed-at)) id])
             rows)
            total (count ordered)
            limit (completion-limit database agent-id)
            recent-eids (mapv first (take limit ordered))
            recent (mapv #(item-row database %) recent-eids)
            omitted (- total (count recent))]
        (if-let [error (some #(when (error-value? %) %) recent)]
          error
          (cond-> {:my.plan/recent-completions recent}
            (pos? omitted)
            (assoc
             :my.plan/older-completions
             {:seon.print/face :seon.print/elided
              :seon.print/omitted omitted
              :seon.print/elision-unit :children
              :seon.render.data/total total
              :seon.render.data/path [:my.plan/recent-completions]
              :seon.render.data/next-offset (count recent)
              :seon.render.profile/id :seon.render.profile/agent
              :seon.print/requery-id [:seon.cluster.agent/id agent-id]})))))))

(defn plan
  "Read one agent's current obligations and authored plan facts.

  The returned collections are derived values, not attributes stored on the
  agent. Query or pull `:my.plan.item/agent` and its reverse directly to inspect
  the graph; `seon.db/transact!` creates and updates those ordinary facts."
  {:malli/schema
   [:=> [:catn [:database :seon.db/database-value]
               [:agent-id :seon.cluster.agent/id]]
    [:or :my.plan/view :seon.error/value]]}
  [database agent-id]
  (let [agent-entity (agent-eid database agent-id)]
    (if (error-value? agent-entity)
      agent-entity
      (if-not agent-entity
        {::agent-not-found true
         :seon.error/kind ::agent-not-found
         :seon.error/message
         (str "There is no agent named " (pr-str agent-id) ".")
         :seon.error/data {:seon.cluster.agent/id agent-id}}
        (let [anchor (anchor-id database agent-id)
              ready-eids (db/q ready-query database rules agent-id)
              blocked-eids (db/q blocked-query database rules agent-id)
              ready-items
              (if (error-value? ready-eids)
                ready-eids
                (items-for-eids database anchor ready-eids))
              blocked-items
              (if (error-value? blocked-eids)
                blocked-eids
                (items-for-eids database anchor blocked-eids))
              derived (obligations database agent-id)
              completions (completion-view database agent-id)
              values [ready-items blocked-items derived completions]]
          (if-let [error (some #(when (error-value? %) %) values)]
            error
            (cond->
             {:seon.cluster.agent/id agent-id
              :my.plan/obligations derived
              :my.plan/ready ready-items
              :my.plan/blocked blocked-items
              :my.plan/recent-completions
              (:my.plan/recent-completions completions)}
              anchor (assoc :my.plan/anchor [:my.plan.item/id anchor])
              (:my.plan/older-completions completions)
              (assoc :my.plan/older-completions
                     (:my.plan/older-completions completions)))))))))

;;; ---------------------------------------------------------------------------
;;; Declared AI and HTML projections
;;; ---------------------------------------------------------------------------

(defn- item-line
  [item]
  (str (pr-str (:my.plan.item/id item)) ": " (:my.plan.item/title item)
       (when-let [description (:my.plan.item/description item)]
         (str " — " description))
       (when-let [expected (:my.plan.item/expected-result item)]
         (str " — expect " expected))))

(defn format-item-ai
  "Format one authored plan item as terminal text."
  {:malli/schema [:=> [:cat :my.plan.item/item] :string]}
  [item]
  (str "Plan item " (item-line (item-value item))))

(defn render-item-ai
  "Render source which reads and formats one authored plan item."
  {:malli/schema [:=> [:cat :my.plan.item/item] :seon.render/ai]}
  [item]
  (pr-str
   (list `format-item-ai
         (list 'seon.db/pull item-selector
               [:my.plan.item/id
                (:my.plan.item/id (item-value item))]))))

(defn render-item-html
  "Render one authored plan item as Hiccup."
  {:malli/schema [:=> [:cat :my.plan.item/item] :seon.render/hiccup]}
  [item]
  (let [item (item-value item)]
    (cond-> [:article {:class "seon-family-entry my-plan-item"}
             [:h3 (:my.plan.item/title item)]
             [:p {:class "my-plan-id"} (pr-str (:my.plan.item/id item))]]
      (:my.plan.item/description item)
      (conj [:p (:my.plan.item/description item)])

      (:my.plan.item/expected-result item)
      (conj [:p {:class "my-plan-expected"}
             (str "Expected: " (:my.plan.item/expected-result item))]))))

(defn format-ready-items-ai
  "Format a supplied ready authored plan frontier as terminal text."
  {:malli/schema [:=> [:cat :my.plan/ready-items] :string]}
  [items]
  (if (seq items)
    (str "Ready authored work (" (count items) "):\n"
         (str/join "\n" (map #(str "- " (item-line %)) items)))
    "No authored work is ready."))

(defn render-ready-items-ai
  "Render source which reads and formats the supplied ready item selection."
  {:malli/schema [:=> [:cat :my.plan/ready-items] :seon.render/ai]}
  [items]
  (pr-str
   (list `format-ready-items-ai
         (list 'seon.db/pull-many item-selector
               (mapv (fn [item]
                       [:my.plan.item/id (:my.plan.item/id item)])
                     items)))))

(defn render-ready-items-html
  "Render the ready authored plan frontier as Hiccup."
  {:malli/schema [:=> [:cat :my.plan/ready-items] :seon.render/hiccup]}
  [items]
  (into [:section {:class "seon-family-entry my-plan-ready"}
         [:h3 (str "Ready authored work (" (count items) ")")]]
        (map render-item-html)
        items))

(defn- section-ai
  [title values line]
  (str title " (" (count values) ")"
       (when (seq values)
         (str ":\n" (str/join "\n" (map #(str "- " (line %)) values))))))

(defn- plan-introduction
  []
  (str "Items connect to this agent through :my.plan.item/agent; "
       ":my.plan.item/parent decomposes work, open :my.plan.item/needs refs "
       "block work, and :my.plan.item/completed-at presence completes it. "
       "For example: (seon.db/transact! [{:db/id "
       "[:my.plan.item/id \"item-id\"] :my.plan.item/title \"Updated title\"}]). "
       "These are ordinary facts that "
       "seon.db/q and seon.db/pull read and seon.db/transact! can create or "
       "update; the sections below are derived current state."))

(defn format-plan-ai
  "Format the current plan union as terminal text."
  {:malli/schema [:=> [:cat :my.plan/view] :string]}
  [view]
  (let [older (:my.plan/older-completions view)]
    (str/join
     "\n\n"
     (cond->
      [(str "Current plan for " (pr-str (:seon.cluster.agent/id view))
            (when-let [anchor (:my.plan/anchor view)]
              (str " — anchor " (pr-str anchor))))
       (plan-introduction)
       (section-ai
        "Derived obligations"
        (:my.plan/obligations view)
        (fn [obligation]
          (str (name (:my.plan/obligation-source obligation)) " "
               (pr-str (:my.plan/obligation-id obligation)) ": "
               (:my.plan/obligation-title obligation))))
       (section-ai "Ready authored work" (:my.plan/ready view) item-line)
       (section-ai "Blocked authored work" (:my.plan/blocked view) item-line)
       (section-ai "Recent completions"
                   (:my.plan/recent-completions view) item-line)]
       older (conj (print/render-elision-ai older))))))

(defn render-plan-ai
  "Render source which derives and formats the current plan union."
  {:malli/schema [:=> [:cat :my.plan/view] :seon.render/ai]}
  [view]
  (pr-str
   (list `format-plan-ai
         (list `plan (list 'seon.db/db)
               (:seon.cluster.agent/id view)))))

(defn- item-list-html
  [title items css-class]
  (into [:section {:class css-class}
         [:h3 (str title " (" (count items) ")")]]
        (map render-item-html)
        items))

(defn render-plan-html
  "Render the current plan union as bounded Hiccup."
  {:malli/schema [:=> [:cat :my.plan/view] :seon.render/hiccup]}
  [view]
  (let [derived-obligations (:my.plan/obligations view)
        older (:my.plan/older-completions view)]
    (cond->
     [:section {:class "seon-family-entry my-plan"}
      [:h2 (str "Current plan for " (pr-str (:seon.cluster.agent/id view)))]
      [:p (plan-introduction)]
      (when-let [anchor (:my.plan/anchor view)]
        [:p {:class "my-plan-anchor"} (str "Anchor " (pr-str anchor))])
      (into [:section {:class "my-plan-obligations"}
             [:h3 (str "Derived obligations (" (count derived-obligations) ")")]]
            (map
             (fn [obligation]
               [:p (str (name (:my.plan/obligation-source obligation)) " "
                        (pr-str (:my.plan/obligation-id obligation)) ": "
                        (:my.plan/obligation-title obligation))]))
            derived-obligations)
      (item-list-html "Ready authored work" (:my.plan/ready view)
                      "my-plan-ready")
      (item-list-html "Blocked authored work" (:my.plan/blocked view)
                      "my-plan-blocked")
      (item-list-html "Recent completions"
                      (:my.plan/recent-completions view)
                      "my-plan-completed")]
      older
      (conj [:p {:class "my-plan-elision"}
             (print/render-elision-ai older)]))))
