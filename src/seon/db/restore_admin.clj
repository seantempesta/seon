(ns seon.db.restore-admin
  "Exchange closed restore data with the no-listener writer.

   This administrative boundary validates restore requests and results for the
   isolated writer path; normal database service traffic uses the protocol."
  (:require [malli.core :as m]
            [seon.db.branch :as branch]
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
   ::pre-restore-main-branch-head
   (get-in intent
           [::restore/pre-restore-main-descriptor
            ::launch/database ::branch/head])
   ::selected-target-branch-head
   (get-in intent
           [::restore/selected-target-descriptor
            ::launch/database ::branch/head])
   ::prepared-target-branch-head (::restore/prepared-target-branch-head intent)
   ::undo-branch-head (::restore/undo-branch-head intent)})

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
