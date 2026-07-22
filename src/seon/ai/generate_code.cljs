(ns seon.ai.generate-code
  "Orchestrate durable goal-driven code generation over ordinary plans.

   This namespace composes existing plan, message, generated-id, and reactive
   mechanisms. Provider selection remains launch data on specialized agents;
   this layer never branches on a provider."
  (:require
    [clojure.string :as str]
    [my.plan :as plan]
    [my.plan.generation :as generation]
    [seon.agent :as agent]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.ns-name :as ns-name]
    [seon.agent.message :as message]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.embed :as embed]
    [seon.log :as log]
    [seon.reactive :as reactive]
    [seon.repl.parse.repair :as candidates]
    [seon.schema :as schema]
    [seon.schema.form :as schema.form]))

(schema/register! ::claimed? :boolean)
(schema/register! ::claim-request
  [:map {:closed true}
   [::db/db :seon.db/db]
   [:my.plan/id :my.plan/id]
   [:seon.agent/id :seon.agent/id]
   [:seon.agent.message/from :seon.agent.message/from]
   [:seon.agent.message/content :seon.agent.message/content]])
(schema/register! ::claim-response
  [:or
   [:map
    [::claimed? ::claimed?]
    [:my.plan/id :my.plan/id]
    [:seon.agent.message/id {:optional true} :seon.agent.message/id]
    [:my.plan/claim {:optional true} :my.plan/claim]]
   [:map [:seon.error/message :string]]])
(schema/register! ::observe-request
  [:map {:closed true}
   [::db/db :seon.db/db]
   [:my.plan/id :my.plan/id]
   [::notify 'fn?]])
(schema/register! ::observe-response
  [:or :my.plan/id [:map [:seon.error/message :string]]])
(schema/register! ::unobserve-request
  [:map {:closed true} [:my.plan/id :my.plan/id]])
(schema/register! ::root-state ::generation/generated-root-state)
(schema/register! ::dispatch-request
  [:map {:closed true}
   [:seon.agent/id :seon.agent/id]
   [::root-state ::root-state]
   [:seon.config/model-variant {:optional true} :seon.config/model-variant]])
(schema/register! ::dispatch-response [:vector ::claim-response])
(schema/register! ::scheduler-request
  [:map {:closed true}
   [::db/db :seon.db/db]
   [:my.plan/id :my.plan/id]
   [:seon.agent/id :seon.agent/id]
   [:seon.config/model-variant {:optional true} :seon.config/model-variant]])
(schema/register! ::restore-schedulers-request
  [:map {:closed true}
   [::db/db :seon.db/db]
   [:seon.config/model-variant {:optional true} :seon.config/model-variant]])
(schema/register! ::restore-schedulers-response
  [:or [:vector :my.plan/id] [:map [:seon.error/message :string]]])

(schema/register! ::ranked-namespace
  [:map {:closed true}
   [:seon.ns/name :symbol]
   [:seon.embed/distance :seon.embed/distance]])
(schema/register! ::ranked [:vector ::ranked-namespace])
(schema/register! ::limit [:int {:min 1}])
(schema/register! ::ranked-request
  [:map {:closed true}
   [::db/db {:optional true} :seon.db/db]
   [:my.plan/goal :my.plan/goal]
   [:my.plan/description {:optional true} :my.plan/description]
   [:my.plan/expect {:optional true} :my.plan/expect]
   [::limit {:optional true} ::limit]])
(schema/register! ::compact-selection-request
  [:map {:closed true}
   [::ranked ::ranked]
   [:seon.agent.ctx.namespaces/full-source
    {:optional true} :seon.agent.ctx.namespaces/full-source]])
(schema/register! ::reconcile-request
  [:map {:closed true}
   [:seon.agent/id :seon.agent/id]
   [::ranked ::ranked]])
(schema/register! ::generate-request
  [:map {:closed true}
   [:my.plan/goal :my.plan/goal]
   [:my.plan/description {:optional true} :my.plan/description]
   [:my.plan/expect {:optional true} :my.plan/expect]
   [:seon.agent/id {:optional true} :seon.agent/id]])
(schema/register! ::generate-response
  [:or
   [:map {:closed true}
    [:my.plan/ok? [:= true]]
    [:my.plan/id :my.plan/id]
    [:seon.agent/id :seon.agent/id]]
   [:map {:closed true}
    [:my.plan/ok? [:= false]]
    [:my.plan/error :my.plan/error]
    [:my.plan/id {:optional true} :my.plan/id]]])

(defn- root-key [root-id]
  [::root root-id])

(defn- generation-failure
  [message]
  {:my.plan/ok? false :my.plan/error message})

(defn- plan-key?
  [key]
  (boolean
   (when-let [key-namespace (namespace key)]
     (or (= "my.plan" key-namespace)
         (str/starts-with? key-namespace "my.plan.")))))

(defn- generate-request-key-failure
  [request]
  (let [accepted
        (into #{}
              (keep #(when (vector? %) (first %)))
              (schema.form/map-entries
               (schema/schema-definition ::generate-request)))]
    (when-let [bad
               (->> (keys request)
                    (filter plan-key?)
                    (remove accepted)
                    first)]
      (let [suggestions
            (->> (candidates/rank-candidates
                  (name bad)
                  (mapv name (filter #(= "my.plan" (namespace %)) accepted)))
                 (mapv (fn [{candidate :seon.repl.parse.repair/to}]
                         (str ":my.plan/" candidate))))]
        (generation-failure
         (str "generate-code!: unknown key " bad
              (when (seq suggestions)
                (str " — did you mean " (str/join " or " suggestions) "?"))
              " Accepted my.plan keys: "
              (str/join " " (sort (filter #(= "my.plan" (namespace %))
                                           accepted)))
              "."))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; Embedding-ranked namespace augmentation — the one optional seon.embed
;;; path over the existing function-source corpus. Ranked hits only augment
;;; the assigned agent's existing :namespaces block; disabled, empty, or
;;; failed retrieval leaves deterministic selection untouched.
;;; ───────────────────────────────────────────────────────────────────────

(def ^:private ranked-function-hit-count
  "Function hits requested from the one embedding index per ranking."
  48)

(def ^:private default-ranked-namespace-limit
  "Ranked namespace candidates kept after grouping function hits."
  16)

(defn- usable-hit-namespace
  "The renderable canonical namespace symbol of one function hit, or nil.

   Usable rows carry a symbol `:seon.ns/name` through `:seon.fn/ns`. Test and
   `.internal` namespaces never enter ranked compact density."
  [{entity :seon.embed/entity}]
  (let [ns-symbol (get-in entity [:seon.fn/ns :seon.ns/name])]
    (when (and (symbol? ns-symbol)
               (ns-name/included-ns? ns-symbol))
      ns-symbol)))

(defn- best-hit-per-namespace
  "Group usable rows by namespace, keeping each namespace's best distance."
  [rows]
  (vals
   (reduce
    (fn [best {ns-symbol :seon.ns/name :as row}]
      (if-let [current (get best ns-symbol)]
        (if (< (:seon.embed/distance row) (:seon.embed/distance current))
          (assoc best ns-symbol row)
          best)
        (assoc best ns-symbol row)))
    {}
    rows)))

(defn ^:async ^:no-doc ranked-namespaces!
  "Namespace candidates ranked by their best goal-embedding function hit.

   Searches the one existing `:seon.fn/source` embedding corpus with the plan
   goal, description, and expectation. Hits group by canonical namespace
   symbol; each namespace ranks by its best (smallest) distance with the
   namespace name as the deterministic tie-break. Embedding disabled, an
   error envelope, or zero usable hits all return `[]` so deterministic
   selection stays unchanged."
  {:malli/schema [:=> [:cat ::ranked-request] ::ranked]}
  [{database ::db/db goal :my.plan/goal
    description :my.plan/description expect :my.plan/expect
    limit ::limit}]
  (if-not (embed/enabled?)
    []
    (let [query (->> [goal description expect]
                     (remove #(or (nil? %) (str/blank? %)))
                     (str/join "\n"))
          result
          (await
           (embed/search-pull
            (cond-> {:seon.embed/query query
                     :seon.embed/k ranked-function-hit-count
                     :seon.embed/pull-pattern
                     [:seon.fn/sym {:seon.fn/ns [:seon.ns/name]}]}
              database (assoc :seon.db/db database))))]
      (if (:seon/error result)
        []
        (->> (:seon.embed/hits result)
             (keep (fn [{distance :seon.embed/distance :as hit}]
                     (when-let [ns-symbol (usable-hit-namespace hit)]
                       {:seon.ns/name ns-symbol
                        :seon.embed/distance distance})))
             best-hit-per-namespace
             (sort-by (juxt :seon.embed/distance
                            #(str (:seon.ns/name %))))
             (take (or limit default-ranked-namespace-limit))
             vec)))))

(defn ranked-compact-selection
  "Exact compact namespace selection from ranked candidates.

   Exact full selections win over compact: a namespace already pinned in the
   block's `full-source` presence-set never re-enters compact density."
  {:malli/schema [:=> [:cat ::compact-selection-request]
                  :seon.agent.ctx.namespaces/compact]}
  [{ranked ::ranked full-source :seon.agent.ctx.namespaces/full-source}]
  (let [full (set full-source)]
    (into [] (comp (map :seon.ns/name) (remove full)) ranked)))

(defn ^:async ^:no-doc reconcile-ranked-namespaces!
  "Replace one agent's exact ranked compact set on its namespaces block.

   The assignment projection REPLACES the previous compact presence-set and
   preserves every other namespaces-block dial. Empty ranked evidence writes
   nothing, so deterministic and configured selection stay authoritative.
   Resolves to true only when the replacement committed."
  {:malli/schema [:=> [:cat ::reconcile-request] :boolean]}
  [{agent-id :seon.agent/id ranked ::ranked}]
  (if (empty? ranked)
    false
    (let [agent (await (db/pull
                        {::db/pull-pattern '[{:seon.agent/ctx [*]}]
                         ::db/ref [:seon.agent/id agent-id]
                         ::db/max-work 100000
                         ::db/max-results 2048
                         ::db/max-result-weight 262144}))
          block (when-not (:seon.error/message agent)
                  (some #(when (= :namespaces (:seon.agent.ctx/name %)) %)
                        (map ctx/decode-block (:seon.agent/ctx agent))))]
      (if (nil? block)
        (do (log/warn!
             {:seon.log/source ::reconcile-ranked-namespaces
              :seon.log/message
              (str "ranked namespace reconcile skipped — agent " agent-id
                   " has no :namespaces block"
                   (when-let [m (:seon.error/message agent)] (str ": " m)))})
            false)
        (let [compact
              (ranked-compact-selection
               {::ranked ranked
                :seon.agent.ctx.namespaces/full-source
                (vec (:seon.agent.ctx.namespaces/full-source block))})
              installed
              (await
               (db/with-agent agent-id
                 #(ctx/install!
                   (assoc (dissoc block :db/id)
                          :seon.agent.ctx.namespaces/compact compact))))]
          (when (false? (:seon.agent.ctx/ok? installed))
            (log/warn!
             {:seon.log/source ::reconcile-ranked-namespaces
              :seon.log/message
              (str "ranked namespace reconcile failed for " agent-id " — "
                   (:seon.agent.ctx/error installed))}))
          (true? (:seon.agent.ctx/ok? installed)))))))

(defn- claim-transaction-builder
  [message-transaction step-id]
  (let [build-message
        (:seon.agent.message/transaction-builder message-transaction)]
    (fn [ids]
      (let [message-id (get ids :seon.agent.message/id)
            message-request (build-message ids)]
        (update message-request ::db/tx-data
                (fn [message-data]
                  (into [[:db.fn/cas [:my.plan/id step-id]
                          :my.plan/claim nil message-id]]
                        (concat message-data
                                [{:my.plan/id step-id
                                  :my.plan/message
                                  [:seon.agent.message/id message-id]}]))))))))

(defn- claim-race-result
  [step-id allocation-error]
  (-> (db/db)
      (.then
       (fn [database]
         (if (:seon.error/message database)
           allocation-error
           (-> (db/pull
                {::db/db database
                 ::db/pull-pattern [:my.plan/claim]
                 ::db/ref [:my.plan/id step-id]})
               (.then
                (fn [step]
                  (if-let [claim (:my.plan/claim step)]
                    {::claimed? false
                     :my.plan/id step-id
                     :my.plan/claim claim}
                    allocation-error)))))))))

(defn ^:async ^:no-doc claim-namespace-step!
  "Claim one namespace step with its ordinary assignment message."
  {:malli/schema [:=> [:cat ::claim-request] ::claim-response]}
  [{database ::db/db
    step-id :my.plan/id
    worker-id :seon.agent/id
    from :seon.agent.message/from
    content :seon.agent.message/content}]
  (let [message-transaction
        (await
         (message/message-transaction-for
          database
          {:seon.agent.message/from from
           :seon.agent.message/to [[:seon.agent/id worker-id]]
           :seon.agent.message/content content}))]
    (if (:seon.error/message message-transaction)
      message-transaction
      (let [allocation
            (await
             (db.id/allocate!
              {::db/db database
               ::db.id/allocations
               (:seon.agent.message/allocations message-transaction)
               ::db.id/transaction-builder
               (claim-transaction-builder message-transaction step-id)}))]
        (if (:seon.error/message allocation)
          (await (claim-race-result step-id allocation))
          {::claimed? true
           :my.plan/id step-id
           :seon.agent.message/id
           (get-in allocation [::db.id/ids :seon.agent.message/id])})))))

(defn ^:async ^:no-doc observe-root!
  "Observe one generated-code root through its stable plan projection."
  {:malli/schema [:=> [:cat ::observe-request] ::observe-response]}
  [{database ::db/db root-id :my.plan/id notify ::notify}]
  (await
   (reactive/observe!
    {::reactive/key (root-key root-id)
     ::reactive/consumer-key root-id
     ::reactive/compute
     (fn [current-database]
       (db/with-read-evidence
        #(plan/generated-root-state
          {::db/db current-database :my.plan/id root-id})))
     ::reactive/notify notify
     ::reactive/db database})))

(defn ^:async ^:no-doc unobserve-root!
  "Release the generated-code observer for one plan root."
  {:malli/schema [:=> [:cat ::unobserve-request] :boolean]}
  [{root-id :my.plan/id}]
  (await
   (reactive/unobserve!
    {::reactive/key (root-key root-id)
     ::reactive/consumer-key root-id})))

(defn- assignment-content
  [root-id {:my.plan/keys [id] namespace :seon.ns/name}]
  (str "Implement and verify generated-code namespace " namespace
       " for plan " (pr-str root-id) ". The durable assignment step is "
       (pr-str id) "; use its current generated-code context and ordinary "
       "REPL forms."))

(defn ^:async ^:private ensure-and-claim!
  [coordinator-id root-id model-variant ranked step]
  (let [worker
        (await
         (agent/ensure-namespace-agent!
          (cond->
           {:seon.agent/id coordinator-id
            :seon.agent/namespace (:seon.ns/name step)
            :seon.agent/purpose
            (str "Implement and verify " (:seon.ns/name step))}
            model-variant
            (assoc :seon.config/model-variant model-variant))))]
    (if (:seon.error/message worker)
      worker
      (let [_ (when (seq ranked)
                (await
                 (reconcile-ranked-namespaces!
                  {:seon.agent/id (:seon.agent/id worker)
                   ::ranked ranked})))
            database (await (db/db))]
        (if (:seon.error/message database)
          database
          (await
           (claim-namespace-step!
            {::db/db database
             :my.plan/id (:my.plan/id step)
             :seon.agent/id (:seon.agent/id worker)
             :seon.agent.message/from [:seon.agent/id coordinator-id]
             :seon.agent.message/content
             (assignment-content root-id step)})))))))

(defn ^:async ^:private ranked-for-root!
  "The root's ranked namespace candidates, or [] outside the embed gate."
  [root-id]
  (if-not (embed/enabled?)
    []
    (let [database (await (db/db))]
      (if (:seon.error/message database)
        []
        (let [root (await
                    (db/pull
                     {::db/db database
                      ::db/pull-pattern [:my.plan/goal :my.plan/description
                                         :my.plan/expect]
                      ::db/ref [:my.plan/id root-id]}))]
          (if (or (:seon.error/message root)
                  (nil? (:my.plan/goal root)))
            []
            (await
             (ranked-namespaces!
              (assoc (select-keys root [:my.plan/goal :my.plan/description
                                        :my.plan/expect])
                     ::db/db database)))))))))

(defn ^:async ^:no-doc dispatch-root-state!
  "Ensure and atomically assign every namespace in one ready frontier."
  {:malli/schema [:=> [:cat ::dispatch-request] ::dispatch-response]}
  [{coordinator-id :seon.agent/id
    root-state ::root-state
    model-variant :seon.config/model-variant}]
  (let [root-id (:my.plan/id root-state)
        ready (::generation/ready-steps root-state)
        ranked (if (seq ready) (await (ranked-for-root! root-id)) [])
        promises
        (mapv #(ensure-and-claim! coordinator-id root-id model-variant
                                  ranked %)
              ready)]
    (if (seq promises)
      (vec (await (js/Promise.all (into-array promises))))
      [])))

(defn- terminal-root?
  [root-state]
  (or (contains? #{:done :blocked} (:my.plan/status root-state))
      (true? (get-in root-state [:my.plan/progress :my.plan/done?]))
      (true? (:my.plan/blocked? root-state))))

(defn- compact-failure
  [failure]
  (when failure
    (let [handles
          (select-keys
           (:seon.error/data failure)
           [:my.plan/id :seon.agent/id :seon.agent.message/id
            :seon.agent.run/id :seon.agent.turn/id :seon.eval/ids])]
      (cond-> {:seon.error/message
               (or (:seon.error/message failure) (pr-str failure))}
        (:seon.error/kind failure)
        (assoc :seon.error/kind (:seon.error/kind failure))
        (seq handles) (assoc :seon.error/data handles)))))

(defn- terminal-result-content
  [root-id terminal-status root-state failure]
  (pr-str
   (cond->
    {:my.plan/id root-id
     :my.plan/status terminal-status
     :my.plan/progress
     (or (:my.plan/progress root-state)
         {:my.plan/done 0 :my.plan/total 0})
     :my.plan/steps
     (mapv #(select-keys % [:my.plan/id :seon.ns/name :my.plan/status])
           (::generation/namespace-steps root-state))}
     failure
     (assoc :my.plan/error (:seon.error/message failure)
            :seon.error/data (compact-failure failure)))))

(defn ^:async ^:private finish-root!
  [{root-id :my.plan/id
    root-state ::root-state
    terminal-status :my.plan/status
    failure :seon.error/data}]
  (let [database (await (db/db))]
    (if (:seon.error/message database)
      database
      (await
       (plan/commit-generated-terminal!
        {::db/db database
         :my.plan/id root-id
         :my.plan/status terminal-status
         :seon.agent.message/content
         (terminal-result-content root-id terminal-status
                                  root-state failure)})))))

(defn ^:async ^:private finish-and-release!
  [root-id terminal-status root-state failure]
  (let [result
        (await
         (finish-root!
          (cond-> {:my.plan/id root-id
                   ::root-state root-state
                   :my.plan/status terminal-status}
            failure (assoc :seon.error/data failure))))]
    (if (or (:seon.error/message result)
            (false? (:my.plan/ok? result)))
      result
      (do
        (await (unobserve-root! {:my.plan/id root-id}))
        result))))

(defn ^:async ^:private root-notify
  [root-id coordinator-id model-variant root-state]
  (cond
    (:seon.error/message root-state)
    (await (finish-and-release! root-id :blocked root-state root-state))

    (terminal-root? root-state)
    (await
     (finish-and-release!
      root-id
      (if (or (= :blocked (:my.plan/status root-state))
              (:my.plan/blocked? root-state))
        :blocked
        :done)
      root-state nil))

    :else
    (try
      (let [dispatched
            (await
             (dispatch-root-state!
              (cond->
               {:seon.agent/id coordinator-id
                ::root-state root-state}
                model-variant
                (assoc :seon.config/model-variant model-variant))))
            failure (some :seon.error/message dispatched)
            failure-row (some #(when (:seon.error/message %) %) dispatched)]
        (if failure
          (await
           (finish-and-release! root-id :blocked root-state failure-row))
          dispatched))
      (catch :default error
        (await
         (finish-and-release!
          root-id :blocked root-state
          {:seon.error/message (or (ex-message error) (str error))}))))))

(defn ^:async ^:no-doc start-root-scheduler!
  "Install one root-scoped generated-code scheduler observer."
  {:malli/schema [:=> [:cat ::scheduler-request] ::observe-response]}
  [{database ::db/db
    root-id :my.plan/id
    coordinator-id :seon.agent/id
    model-variant :seon.config/model-variant}]
  (await
   (observe-root!
    {::db/db database
     :my.plan/id root-id
     ::notify #(root-notify root-id coordinator-id model-variant %)})))

(defn ^:async ^:no-doc restore-root-schedulers!
  "Replace process-local observers for every durable generated-code root."
  {:malli/schema [:=> [:cat ::restore-schedulers-request]
                  ::restore-schedulers-response]}
  [{database ::db/db model-variant :seon.config/model-variant}]
  (let [candidates (await (plan/generated-root-candidates {::db/db database}))]
    (if (:seon.error/message candidates)
      candidates
      (loop [remaining candidates
             restored []]
        (if-let [{root-id :my.plan/id
                  coordinator-id :seon.agent/id} (first remaining)]
          (let [root-state
                (await
                 (plan/generated-root-state
                  {::db/db database :my.plan/id root-id}))]
            (if (:seon.error/message root-state)
              root-state
              (do
                (await (unobserve-root! {:my.plan/id root-id}))
                (if (terminal-root? root-state)
                  (recur (next remaining) restored)
                  (let [result
                        (await
                         (start-root-scheduler!
                          (cond->
                           {::db/db database
                            :my.plan/id root-id
                            :seon.agent/id coordinator-id}
                            model-variant
                            (assoc :seon.config/model-variant model-variant))))]
                    (if (:seon.error/message result)
                      result
                      (recur (next remaining) (conj restored root-id))))))))
          restored)))))

;;; ───────────────────────────────────────────────────────────────────────
;;; The public generation entry — one durable generated root, its planning
;;; assignment message, and the root-scoped scheduler. `seon.ai/generate-code!`
;;; is the agent-facing wrapper over this composition.
;;; ───────────────────────────────────────────────────────────────────────

(defn- planning-content
  "The planner's ordinary assignment message for one generation request."
  [{goal :my.plan/goal
    description :my.plan/description
    expect :my.plan/expect}]
  (str "Design and generate the complete ClojureScript for this goal in one "
       "ordinary REPL reply.\nGoal: " goal
       (when description (str "\nDescription: " description))
       (when expect (str "\nExpect: " expect))
       "\nDeclare every namespace with a real (ns my.… (:require …)) form, "
       "colocate schema/register! forms with the namespace that owns the "
       "data, give public functions correct :malli/schema entries, and "
       "include behavioral deftests that prove the expectation."))

(defn- root-transaction-builder
  "One atomic transaction: assignment message, generated root, and claim."
  [message-transaction request caller-id planner-id created-at]
  (let [build-message
        (:seon.agent.message/transaction-builder message-transaction)
        {goal :my.plan/goal
         description :my.plan/description
         expect :my.plan/expect} request]
    (fn [ids]
      (let [message-id (get ids :seon.agent.message/id)
            root-id (get ids :my.plan/id)
            message-request (build-message ids)]
        (update message-request ::db/tx-data
                (fn [message-data]
                  (conj (vec message-data)
                        (cond->
                         {:my.plan/id root-id
                          :my.plan/title goal
                          :my.plan/goal goal
                          :my.plan/status :open
                          :my.plan/created-at created-at
                          :my.plan/agent [:seon.agent/id planner-id]
                          :my.plan/from [:seon.agent/id caller-id]
                          :my.plan/message
                          [:seon.agent.message/id message-id]
                          :my.plan/claim message-id}
                          description (assoc :my.plan/description description)
                          expect (assoc :my.plan/expect expect)))))))))

(defn ^:async ^:private commit-generation-root!
  "Commit the planner assignment and durable root in one transaction."
  [request caller-id planner-id]
  (let [database (await (db/db))]
    (if (:seon.error/message database)
      (generation-failure
       (str "generate-code!: database read failed — "
            (:seon.error/message database)))
      (let [message-transaction
            (await
             (message/message-transaction-for
              database
              {:seon.agent.message/from [:seon.agent/id caller-id]
               :seon.agent.message/to [[:seon.agent/id planner-id]]
               :seon.agent.message/content (planning-content request)}))]
        (if (:seon.error/message message-transaction)
          (generation-failure
           (str "generate-code!: planner assignment failed — "
                (:seon.error/message message-transaction)))
          (let [allocation
                (await
                 (db.id/allocate!
                  {::db/db database
                   ::db.id/allocations
                   (into (vec (:seon.agent.message/allocations
                               message-transaction))
                         [{::db.id/key :my.plan/id
                           ::db.id/identity-attr :my.plan/id}])
                   ::db.id/transaction-builder
                   (root-transaction-builder
                    message-transaction request caller-id planner-id
                    (js/Date.))}))]
            (if (:seon.error/message allocation)
              (generation-failure
               (str "generate-code!: root commit failed — "
                    (:seon.error/message allocation)))
              {:my.plan/ok? true
               :my.plan/id (get-in allocation [::db.id/ids :my.plan/id])})))))))

(defn ^:async ^:no-doc start-generation!
  "Create one durable generated root with its planner and scheduler.

   The composition behind `seon.ai/generate-code!`: launch one planning
   agent under the named `:planning` model variant, reconcile its ranked
   namespace context, commit the generated root with its addressed planning
   assignment and claim in one transaction, then install the root-scoped
   execution scheduler. The compact terminal result later arrives as an
   ordinary addressed message from the planner to the caller."
  {:malli/schema [:=> [:cat ::generate-request] ::generate-response]}
  [{goal :my.plan/goal caller-id :seon.agent/id :as request}]
  (or
   (generate-request-key-failure request)
   (let [caller-id (or caller-id (db/current-agent-id))]
     (cond
       (or (nil? goal) (str/blank? goal))
       (generation-failure
        "generate-code!: blank :my.plan/goal refused — state the outcome.")

       (nil? caller-id)
       (generation-failure
        (str "generate-code!: no :seon.agent/id resolved — call from inside "
             "an agent turn (the boundary fills in you)."))

       :else
       (let [planner
             (await
              (agent/start!
               {:seon.agent/purpose (str "Plan generated code: " goal)
                :seon.config/model-variant :planning}))]
         (if (:seon.error/message planner)
           (generation-failure
            (str "generate-code!: planner launch failed — "
                 (:seon.error/message planner)))
           (let [planner-id (:seon.agent/id planner)
                 ranked
                 (await
                  (ranked-namespaces!
                   (select-keys request [:my.plan/goal :my.plan/description
                                         :my.plan/expect])))
                 _ (when (seq ranked)
                     (await
                      (reconcile-ranked-namespaces!
                       {:seon.agent/id planner-id ::ranked ranked})))
                 committed
                 (await
                  (commit-generation-root! request caller-id planner-id))]
             (if (false? (:my.plan/ok? committed))
               committed
               (let [root-id (:my.plan/id committed)
                     fresh (await (db/db))
                     scheduler
                     (if (:seon.error/message fresh)
                       fresh
                       (await
                        (start-root-scheduler!
                         {::db/db fresh
                          :my.plan/id root-id
                          :seon.agent/id planner-id
                          :seon.config/model-variant :execution})))]
                 (if (:seon.error/message scheduler)
                   (assoc
                    (generation-failure
                     (str "generate-code!: root " (pr-str root-id)
                          " committed but its scheduler failed — "
                          (:seon.error/message scheduler)
                          ". Restart recovery restores it."))
                    :my.plan/id root-id)
                   {:my.plan/ok? true
                    :my.plan/id root-id
                    :seon.agent/id planner-id}))))))))))
