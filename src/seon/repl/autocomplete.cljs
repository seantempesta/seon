(ns seon.repl.autocomplete
  "Curate durable turn examples for downstream autocomplete research."
  (:require
   [seon.db :as db]
   [seon.schema :as schema]))

(schema/register! ::rating [:enum :gold :good :excluded])
(schema/register! ::tag [:vector :keyword])
(schema/register! ::ok? :boolean)
(schema/register! ::error :string)

(schema/register!
 ::rate-request
 [:map
  [:seon.agent.turn/id :seon.agent.turn/id]
  [::rating ::rating]
  [::tag {:optional true} ::tag]])

(schema/register!
 ::rate-response
 [:map
  [::ok? ::ok?]
  [:seon.agent.turn/id {:optional true} :seon.agent.turn/id]
  [::error {:optional true} ::error]])

(defn ^:async rate!
  "Rate a durable turn example for downstream autocomplete research."
  {:malli/schema [:=> [:cat ::rate-request] ::rate-response]}
  [{turn-id :seon.agent.turn/id rating ::rating tags ::tag}]
  (let [database (await (db/db))]
    (if (:seon.error/message database)
      {::ok? false ::error (:seon.error/message database)}
      (let [turn (await (db/entity database [:seon.agent.turn/id turn-id]))]
        (cond
          (:seon.error/message turn)
          {::ok? false ::error (:seon.error/message turn)}

          (nil? (:seon.agent.turn/id turn))
          {::ok? false
           ::error (str "No turn " (pr-str turn-id)
                        "; pass a durable :seon.agent.turn/id.")}

          :else
          (let [result
                (await
                 (db/transact!
                  {:seon.db/db database
                   :seon.db/tx-data
                   [(cond-> {:seon.agent.turn/id turn-id
                             ::rating rating}
                      (seq tags) (assoc ::tag (vec tags)))]}))]
            (if (:seon.error/message result)
              {::ok? false ::error (:seon.error/message result)}
              {::ok? true :seon.agent.turn/id turn-id})))))))
