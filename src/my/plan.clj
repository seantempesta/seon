(ns my.plan
  "The calling agent’s plan protocol. Each operation takes one request map."
  (:require [seon.plan :as plan]))

(defn plan
  "Read plan from my plan."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/component-view :seon.error/value]]}
  [request]
  (plan/plan request))

(defn item
  "Read item from my plan."
  {:malli/schema [:=> [:cat :my.plan/item-request] [:or :my.plan/render-step :seon.error/value]]}
  [request]
  (plan/item request))

(defn items
  "Read items from my plan."
  {:malli/schema [:=> [:cat :my.plan/items-request] [:or :my.plan/render-steps :seon.error/value]]}
  [request]
  (plan/items request))

(defn current
  "Read my plan’s current."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/current-value :seon.error/value]]}
  [request]
  (plan/current (:seon.db/db request) (:seon.agent/id request)))

(defn ready
  "Read my plan’s ready."
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/ready (:seon.db/db request) (:seon.agent/id request)))

(defn blocked
  "Read my plan’s blocked."
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/blocked (:seon.db/db request) (:seon.agent/id request)))

(defn steps
  "Read my plan’s steps."
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/steps (:seon.db/db request) (:seon.agent/id request)))

(defn ready-subjects
  "Read my plan’s ready-subjects."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/intent-subjects :seon.error/value]]}
  [request]
  (plan/ready-subjects (:seon.db/db request) (:seon.agent/id request)))

(defn add!
  "Change one owned plan item and return it."
  {:malli/schema [:=> [:cat :my.plan/add-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/add! (dissoc request :seon.db/connection :seon.agent/id) (:seon.db/connection request) (:seon.agent/id request)))

(defn complete!
  "Change one owned plan item and return it."
  {:malli/schema [:=> [:cat :my.plan/complete-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/complete! (:my.plan.item/id request) (get request :my.plan.item/completed-at (java.util.Date.)) (:seon.db/connection request) (:seon.agent/id request)))

(defn start!
  "Change one owned plan item and return it."
  {:malli/schema [:=> [:cat :my.plan/start-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/start! (:my.plan.item/id request) (:seon.db/connection request) (:seon.agent/id request)))
