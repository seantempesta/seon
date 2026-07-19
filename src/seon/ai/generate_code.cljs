(ns seon.ai.generate-code
  "Orchestrate durable goal-driven code generation over ordinary plans.

   This namespace composes existing plan, message, generated-id, and reactive
   mechanisms. Provider selection remains launch data on specialized agents;
   this layer never branches on a provider."
  (:require
    [my.plan :as plan]
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
     ::db/db database})))

(defn ^:async ^:no-doc unobserve-root!
  "Release the generated-code observer for one plan root."
  {:malli/schema [:=> [:cat ::unobserve-request] :boolean]}
  [{root-id :my.plan/id}]
  (await
   (reactive/unobserve!
    {::reactive/key (root-key root-id)
     ::reactive/consumer-key root-id})))
