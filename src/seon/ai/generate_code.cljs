(ns seon.ai.generate-code
  "Orchestrate durable goal-driven code generation over ordinary plans.

   This namespace composes existing plan, message, generated-id, and reactive
   mechanisms. Provider selection remains launch data on specialized agents;
   this layer never branches on a provider."
  (:require
    [my.plan :as plan]
    [seon.agent :as agent]
    [seon.agent.message :as message]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.reactive :as reactive]
    [seon.schema :as schema]))

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
(schema/register! ::root-state :my.plan/generated-root-state)
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

(defn- root-key [root-id]
  [::root root-id])

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
  [coordinator-id root-id model-variant step]
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
      (let [database (await (db/db))]
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

(defn ^:async ^:no-doc dispatch-root-state!
  "Ensure and atomically assign every namespace in one ready frontier."
  {:malli/schema [:=> [:cat ::dispatch-request] ::dispatch-response]}
  [{coordinator-id :seon.agent/id
    root-state ::root-state
    model-variant :seon.config/model-variant}]
  (let [root-id (:my.plan/id root-state)
        promises
        (mapv #(ensure-and-claim! coordinator-id root-id model-variant %)
              (:my.plan.internal/ready-steps root-state))]
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
           (:my.plan.internal/namespace-steps root-state))}
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
