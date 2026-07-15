(ns seon.db.restore-admin
  "Closed data exchanged by the restore operator and no-listener writer."
  (:require [malli.core :as m]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.dev.restore :as restore]
            [seon.launch :as launch]
            [seon.schema :as schema]))

(schema/register! ::intent ::restore/intent)
(schema/register! ::intent-id ::restore/intent-id)
(schema/register! ::plan-digest ::restore/plan-digest)
(schema/register! ::pre-restore-main-coordinate ::coordinate/coordinate)
(schema/register! ::selected-target-coordinate ::coordinate/coordinate)
(schema/register! ::prepared-target-coordinate ::coordinate/coordinate)
(schema/register! ::undo-coordinate ::coordinate/coordinate)
(schema/register! ::forced-main-coordinate ::coordinate/coordinate)
(schema/register! ::branch-roster [:set :keyword])
(schema/register! ::force-invoked? :boolean)
(schema/register!
 ::connection-state
 [:enum :seon.db.restore-admin.connection/not-opened
  :seon.db.restore-admin.connection/released
  :seon.db.restore-admin.connection/cleanup-unproved])
(schema/register! ::error-kind ::protocol/error-kind)
(schema/register! ::error [:string {:min 1 :max 4096}])
(schema/register!
 ::effect-state
 [:enum :seon.db.restore-admin.effect/unknown])
(schema/register!
 ::outcome
 [:enum :seon.db.restore-admin.outcome/applied
  :seon.db.restore-admin.outcome/already-applied])

(schema/register!
 ::request
 [:map {:closed true}
  [::intent ::intent]])
(schema/register!
 ::result-base
 [:map {:closed true}
  [::intent-id ::intent-id]
  [::plan-digest ::plan-digest]
  [::pre-restore-main-coordinate ::pre-restore-main-coordinate]
  [::selected-target-coordinate ::selected-target-coordinate]
  [::prepared-target-coordinate ::prepared-target-coordinate]
  [::undo-coordinate ::undo-coordinate]])
(schema/register!
 ::applied-result
 [:map {:closed true}
  [::intent-id ::intent-id]
  [::plan-digest ::plan-digest]
  [::outcome [:= :seon.db.restore-admin.outcome/applied]]
  [::pre-restore-main-coordinate ::pre-restore-main-coordinate]
  [::selected-target-coordinate ::selected-target-coordinate]
  [::prepared-target-coordinate ::prepared-target-coordinate]
  [::undo-coordinate ::undo-coordinate]
  [::forced-main-coordinate ::forced-main-coordinate]
  [::branch-roster ::branch-roster]
  [::force-invoked? [:= true]]
  [::connection-state
   [:= :seon.db.restore-admin.connection/released]]])
(schema/register!
 ::already-applied-result
 [:map {:closed true}
  [::intent-id ::intent-id]
  [::plan-digest ::plan-digest]
  [::outcome [:= :seon.db.restore-admin.outcome/already-applied]]
  [::pre-restore-main-coordinate ::pre-restore-main-coordinate]
  [::selected-target-coordinate ::selected-target-coordinate]
  [::prepared-target-coordinate ::prepared-target-coordinate]
  [::undo-coordinate ::undo-coordinate]
  [::forced-main-coordinate ::forced-main-coordinate]
  [::branch-roster ::branch-roster]
  [::force-invoked? [:= false]]
  [::connection-state
   [:= :seon.db.restore-admin.connection/released]]])
(schema/register!
 ::rejected-result
 [:map {:closed true}
  [::intent-id ::intent-id]
  [::plan-digest ::plan-digest]
  [::error-kind ::error-kind]
  [::error ::error]
  [::pre-restore-main-coordinate ::pre-restore-main-coordinate]
  [::selected-target-coordinate ::selected-target-coordinate]
  [::prepared-target-coordinate ::prepared-target-coordinate]
  [::undo-coordinate ::undo-coordinate]
  [::forced-main-coordinate {:optional true} ::forced-main-coordinate]
  [::branch-roster {:optional true} ::branch-roster]
  [::force-invoked? ::force-invoked?]
  [::connection-state ::connection-state]])
(schema/register!
 ::invalid-request-result
 [:map {:closed true}
  [::error-kind ::error-kind]
  [::error ::error]
  [::force-invoked? [:= false]]
  [::connection-state
   [:= :seon.db.restore-admin.connection/not-opened]]])
(schema/register!
 ::unknown-effect-result
 [:map {:closed true}
  [::intent-id ::intent-id]
  [::plan-digest ::plan-digest]
  [::error-kind ::error-kind]
  [::error ::error]
  [::pre-restore-main-coordinate ::pre-restore-main-coordinate]
  [::selected-target-coordinate ::selected-target-coordinate]
  [::prepared-target-coordinate ::prepared-target-coordinate]
  [::undo-coordinate ::undo-coordinate]
  [::effect-state [:= :seon.db.restore-admin.effect/unknown]]
  [::connection-state
   [:= :seon.db.restore-admin.connection/cleanup-unproved]]])
(schema/register!
 ::result
 [:or ::applied-result ::already-applied-result ::rejected-result
  ::invalid-request-result ::unknown-effect-result])

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
