(ns my.plan
  "Read and maintain my objective and ordered plan steps.

  Use add!, update!, current! and complete! with request maps. A completed
  step has :my.plan.item/completed-tx. Verify its done-when before completion.

  Example:
  (my.plan/add! {:my.plan.item/id \"verify-total\"
                 :my.plan.item/title \"Verify the total\"
                 :my.plan.item/done-when \"I have read the computed total.\"})"
  (:require [seon.plan :as plan]))

(defn plan
  "Read my objective, ordered steps, current item, and derived work sets.

  Returns a plan map with :my.plan/steps, :my.plan/ready and :my.plan/blocked
  vectors, and :my.plan/current-step when selected.

  Example:
  (my.plan/plan)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/component-view :seon.error/value]]}
  [request]
  (plan/plan request))

(defn item
  "Read the named plan item with its stored content and derived state.

  Returns a map containing :my.plan.item/id, :my.plan.item/title and
  :my.plan/state, with completion criteria and relationships when present.
  Read after adding in a separate evaluation: reads keep that evaluation's
  immutable database snapshot.

  Example:
  (my.plan/add! {:my.plan.item/id \"inspect-step\"
                 :my.plan.item/title \"Inspect the saved step\"})
  (my.plan/item {:my.plan.item/id \"inspect-step\"})"
  {:malli/schema [:=> [:cat :my.plan/item-request] [:or :my.plan/render-step :seon.error/value]]}
  [request]
  (plan/item request))

(defn current
  "Read my current item; an empty map means none is selected.

  Returns a step map with :my.plan.item/id, :my.plan.item/title and
  :my.plan/state, or {} when no open step is selected.

  Example:
  (my.plan/current)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/current-value :seon.error/value]]}
  [request]
  (plan/current (:seon.db/db request) (:seon.agent/id request)))

(defn ready
  "Read my open items whose dependencies and descendants are complete.

  Returns a vector of step maps with :my.plan.item/id, :my.plan.item/title,
  :my.plan/state and :my.plan/needs; [] means no step is ready.

  Example:
  (my.plan/ready)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/ready (:seon.db/db request) (:seon.agent/id request)))

(defn blocked
  "Read my open items that are waiting on dependencies.

  Returns a vector of step maps; :my.plan/needs contains dependency ids.
  An empty vector means no step is blocked.

  Example:
  (my.plan/blocked)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/blocked (:seon.db/db request) (:seon.agent/id request)))

(defn steps
  "Read all my plan steps in authored tree order.

  Returns a vector of maps containing :my.plan.item/id, :my.plan.item/title,
  :my.plan/state and :my.plan/needs, plus authored completion criteria.

  Example:
  (my.plan/steps)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or [:vector :my.plan/step-summary] :seon.error/value]]}
  [request]
  (plan/steps (:seon.db/db request) (:seon.agent/id request)))

(defn ready-subjects
  "Read the subjects connected to my ready items.

  Returns a vector of resolved entity ids, in ready-step and subject order,
  with repeated subjects removed. An empty vector means no subjects.

  Example:
  (my.plan/ready-subjects)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/intent-subjects :seon.error/value]]}
  [request]
  (plan/ready-subjects (:seon.db/db request) (:seon.agent/id request)))

(defn add!
  "Add a plan step and return the saved step with its derived state.

  Supply :my.plan.item/title and optionally :my.plan.item/id and
  :my.plan.item/done-when. An omitted id derives from the title; the position
  appends to the plan. Returns :my.plan.item/id, :my.plan.item/title,
  :my.plan/state and :my.plan/needs, plus the supplied completion criterion.

  Example:
  (my.plan/add! {:my.plan.item/id \"verify-total\"
                 :my.plan.item/title \"Verify the total\"
                 :my.plan.item/done-when \"I have read the computed total.\"})"
  {:seon.fn/doc-order 0
   :malli/schema [:=> [:cat :my.plan/add-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/add! (dissoc request :seon.db/connection :seon.agent/id) (:seon.db/connection request) (:seon.agent/id request)))

(defn update!
  "Update the named item's title, description, or done-when and return it.

  Supply :my.plan.item/id first and the fields to change. Omitted fields
  remain unchanged. Returns the saved step map with :my.plan/state.

  Example:
  (let [step (my.plan/add! {:my.plan.item/id \"revise-step\"
                           :my.plan.item/title \"Verify the output\"})]
    (my.plan/update! {:my.plan.item/id (:my.plan.item/id step)
                      :my.plan.item/done-when \"The output matches the expected value.\"}))"
  {:seon.fn/doc-order 1
   :malli/schema [:=> [:cat :my.plan/update-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/update! (dissoc request :seon.db/connection :seon.agent/id)
                (:seon.db/connection request) (:seon.agent/id request)))

(defn complete!
  "Complete the named item and return it after its criterion is verified.

  Returns the step map with :my.plan/state :completed and
  :my.plan.item/completed-tx containing :db/txInstant. If it was current,
  that selection is cleared. Completing an already completed step is inert.

  Example:
  (let [step (my.plan/add! {:my.plan.item/id \"verify-arithmetic\"
                           :my.plan.item/title \"Verify arithmetic\"
                           :my.plan.item/done-when \"One plus one equals two.\"})]
    (when (= 2 (+ 1 1))
      (my.plan/complete! {:my.plan.item/id (:my.plan.item/id step)})))"
  {:seon.fn/doc-order 2
   :malli/schema [:=> [:cat :my.plan/complete-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/complete! (:my.plan.item/id request) (:seon.db/connection request) (:seon.agent/id request)))

(defn current!
  "Select the named open item as current and return it.

  Returns the step map with :my.plan/state :current. The step must belong
  to my plan and must not already be completed.

  Example:
  (let [step (my.plan/add! {:my.plan.item/id \"focus-step\"
                           :my.plan.item/title \"Inspect the output\"})]
    (my.plan/current! {:my.plan.item/id (:my.plan.item/id step)}))"
  {:seon.fn/doc-order 3
   :malli/schema [:=> [:cat :my.plan/current-request] [:or :my.plan/step-summary :seon.error/value]]}
  [request]
  (plan/start! (:my.plan.item/id request) (:seon.db/connection request) (:seon.agent/id request)))
