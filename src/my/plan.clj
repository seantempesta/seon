(ns my.plan
  "My plan is my instructions. A step is done when it has :my.plan.item/completed-tx.

  Add a step by upserting the plan's identity. Use the next position in your
  plan (6 below); ids are (seon.id/id title 8).
  Example:
  (seon.db/transact!
    [{:my.plan/agent [:seon.agent/id \"juniper\"]
      :my.plan/steps [{:my.plan.item/id (seon.id/id \"Verify customer totals\" 8)
                       :my.plan.item/title \"Verify customer totals\"
                       :my.plan.item/done-when \"I have read the new total.\"
                       :my.plan.item/position 6}]}])

  Complete only after observing the result:
  (seon.db/transact!
    [[:db/add [:my.plan.item/id (seon.id/id \"Verify customer totals\" 8)]
      :my.plan.item/completed-tx \"datomic.tx\"]])

  Remove the step and its incoming refs:
  (seon.db/transact!
    [[:db.fn/retractEntity [:my.plan.item/id (seon.id/id \"Verify customer totals\" 8)]]])"
  (:require [seon.plan :as plan]))

(defn plan
  "Read my objective, ordered steps, current item, and derived work sets."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/component-view :seon.error/value]]}
  [request]
  (plan/plan request))

(defn item
  "Read the named plan item with its stored content and derived state."
  {:malli/schema [:=> [:cat :my.plan/item-request] [:or :my.plan/render-step :seon.error/value]]}
  [request]
  (plan/item request))

(defn items
  "Read every item in my plan in authored order, with state and completion criteria."
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/steps (:seon.db/db request) (:seon.agent/id request)))

(defn current
  "Read my current item; an empty map means none is selected."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/current-value :seon.error/value]]}
  [request]
  (plan/current (:seon.db/db request) (:seon.agent/id request)))

(defn ready
  "Read my open items whose dependencies and descendants are complete."
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/ready (:seon.db/db request) (:seon.agent/id request)))

(defn blocked
  "Read my open items that are waiting on dependencies."
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/blocked (:seon.db/db request) (:seon.agent/id request)))

(defn steps
  "Read all my items in authored order."
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/steps (:seon.db/db request) (:seon.agent/id request)))

(defn ready-subjects
  "Read the subjects connected to my ready items."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/intent-subjects :seon.error/value]]}
  [request]
  (plan/ready-subjects (:seon.db/db request) (:seon.agent/id request)))

(defn add!
  "Add an item; derive its id from the title and append its position. Return it."
  {:seon.fn/doc-order 0
   :malli/schema [:=> [:cat :my.plan/add-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/add! (dissoc request :seon.db/connection :seon.agent/id) (:seon.db/connection request) (:seon.agent/id request)))

(defn update!
  "Update the named item's title, description, or done-when and return it."
  {:seon.fn/doc-order 1
   :malli/schema [:=> [:cat :my.plan/update-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/update! (dissoc request :seon.db/connection :seon.agent/id)
                (:seon.db/connection request) (:seon.agent/id request)))

(defn complete!
  "Complete the named item now and return it. Verify its done-when first."
  {:seon.fn/doc-order 2
   :malli/schema [:=> [:cat :my.plan/complete-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/complete! (:my.plan.item/id request) (:seon.db/connection request) (:seon.agent/id request)))

(defn current!
  "Select the named open item as current and return it."
  {:seon.fn/doc-order 3
   :malli/schema [:=> [:cat :my.plan/current-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/start! (:my.plan.item/id request) (:seon.db/connection request) (:seon.agent/id request)))
