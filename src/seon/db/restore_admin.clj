(ns seon.db.restore-admin
  "Closed data exchanged by the restore operator and no-listener writer."
  (:require [malli.core :as m]
            [seon.db.coordinate :as coordinate]
            [seon.db.restore-admin.schema]
            [seon.dev.restore :as restore]
            [seon.launch :as launch]
            [seon.schema :as schema]))

(schema/register! ::intent ::restore/intent)
(schema/register!
 ::request
 [:map {:closed true}
  [::intent ::intent]])

(defn result-base
  "Project immutable result identity from one validated restore intent."
  {:malli/schema [:=> [:cat ::intent] ::result-base]}
  [intent]
  {::intent-id (::restore/intent-id intent)
   ::plan-digest (::restore/plan-digest intent)
   ::pre-restore-main-coordinate
   (get-in intent
           [::restore/pre-restore-main-descriptor
            ::launch/database ::coordinate/coordinate])
   ::selected-target-coordinate
   (get-in intent
           [::restore/selected-target-descriptor
            ::launch/database ::coordinate/coordinate])
   ::prepared-target-coordinate (::restore/prepared-target-coordinate intent)
   ::undo-coordinate (::restore/undo-coordinate intent)})

(defn valid-result?
  "True when `value` is one complete restore-admin result variant."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (m/validate ::result value))

(defn explain-result
  "Explain a value that failed the closed restore-admin result contract."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [value]
  (m/explain ::result value))

(defn success-result?
  "True for either proved and released convergence variant."
  {:malli/schema [:=> [:cat ::result] :boolean]}
  [result]
  (contains? result ::outcome))
