(ns seon.db.restore-admin.schema
  "Portable closed result schemas for the no-listener restore writer."
  (:require [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.dev.restore.schema]
            [seon.schema :as schema]))

(schema/register! :seon.db.restore-admin/intent-id
                  :seon.dev.restore/intent-id)
(schema/register! :seon.db.restore-admin/plan-digest
                  :seon.dev.restore/plan-digest)
(schema/register! :seon.db.restore-admin/pre-restore-main-coordinate
                  ::coordinate/coordinate)
(schema/register! :seon.db.restore-admin/selected-target-coordinate
                  ::coordinate/coordinate)
(schema/register! :seon.db.restore-admin/prepared-target-coordinate
                  ::coordinate/coordinate)
(schema/register! :seon.db.restore-admin/undo-coordinate
                  ::coordinate/coordinate)
(schema/register! :seon.db.restore-admin/forced-main-coordinate
                  ::coordinate/coordinate)
(schema/register! :seon.db.restore-admin/branch-roster [:set :keyword])
(schema/register! :seon.db.restore-admin/force-invoked? :boolean)
(schema/register!
 :seon.db.restore-admin/connection-state
 [:enum :seon.db.restore-admin.connection/not-opened
  :seon.db.restore-admin.connection/released
  :seon.db.restore-admin.connection/cleanup-unproved])
(schema/register! :seon.db.restore-admin/error-kind ::protocol/error-kind)
(schema/register! :seon.db.restore-admin/error [:string {:min 1 :max 4096}])
(schema/register! :seon.db.restore-admin/effect-state
                  [:enum :seon.db.restore-admin.effect/unknown])
(schema/register! :seon.db.restore-admin/outcome
                  [:enum :seon.db.restore-admin.outcome/applied
                   :seon.db.restore-admin.outcome/already-applied])

(schema/register!
 :seon.db.restore-admin/result-base
 [:map {:closed true}
  [:seon.db.restore-admin/intent-id :seon.db.restore-admin/intent-id]
  [:seon.db.restore-admin/plan-digest :seon.db.restore-admin/plan-digest]
  [:seon.db.restore-admin/pre-restore-main-coordinate
   :seon.db.restore-admin/pre-restore-main-coordinate]
  [:seon.db.restore-admin/selected-target-coordinate
   :seon.db.restore-admin/selected-target-coordinate]
  [:seon.db.restore-admin/prepared-target-coordinate
   :seon.db.restore-admin/prepared-target-coordinate]
  [:seon.db.restore-admin/undo-coordinate
   :seon.db.restore-admin/undo-coordinate]])

(def ^:private released
  [:= :seon.db.restore-admin.connection/released])

(defn- success-schema [outcome force-invoked?]
  [:map {:closed true}
   [:seon.db.restore-admin/intent-id :seon.db.restore-admin/intent-id]
   [:seon.db.restore-admin/plan-digest :seon.db.restore-admin/plan-digest]
   [:seon.db.restore-admin/outcome [:= outcome]]
   [:seon.db.restore-admin/pre-restore-main-coordinate
    :seon.db.restore-admin/pre-restore-main-coordinate]
   [:seon.db.restore-admin/selected-target-coordinate
    :seon.db.restore-admin/selected-target-coordinate]
   [:seon.db.restore-admin/prepared-target-coordinate
    :seon.db.restore-admin/prepared-target-coordinate]
   [:seon.db.restore-admin/undo-coordinate
    :seon.db.restore-admin/undo-coordinate]
   [:seon.db.restore-admin/forced-main-coordinate
    :seon.db.restore-admin/forced-main-coordinate]
   [:seon.db.restore-admin/branch-roster
    :seon.db.restore-admin/branch-roster]
   [:seon.db.restore-admin/force-invoked? [:= force-invoked?]]
   [:seon.db.restore-admin/connection-state released]])

(schema/register!
 :seon.db.restore-admin/applied-result
 (success-schema :seon.db.restore-admin.outcome/applied true))
(schema/register!
 :seon.db.restore-admin/already-applied-result
 (success-schema :seon.db.restore-admin.outcome/already-applied false))
(schema/register!
 :seon.db.restore-admin/success-result
 [:or :seon.db.restore-admin/applied-result
  :seon.db.restore-admin/already-applied-result])

(schema/register!
 :seon.db.restore-admin/rejected-result
 [:map {:closed true}
  [:seon.db.restore-admin/intent-id :seon.db.restore-admin/intent-id]
  [:seon.db.restore-admin/plan-digest :seon.db.restore-admin/plan-digest]
  [:seon.db.restore-admin/error-kind :seon.db.restore-admin/error-kind]
  [:seon.db.restore-admin/error :seon.db.restore-admin/error]
  [:seon.db.restore-admin/pre-restore-main-coordinate
   :seon.db.restore-admin/pre-restore-main-coordinate]
  [:seon.db.restore-admin/selected-target-coordinate
   :seon.db.restore-admin/selected-target-coordinate]
  [:seon.db.restore-admin/prepared-target-coordinate
   :seon.db.restore-admin/prepared-target-coordinate]
  [:seon.db.restore-admin/undo-coordinate
   :seon.db.restore-admin/undo-coordinate]
  [:seon.db.restore-admin/forced-main-coordinate {:optional true}
   :seon.db.restore-admin/forced-main-coordinate]
  [:seon.db.restore-admin/branch-roster {:optional true}
   :seon.db.restore-admin/branch-roster]
  [:seon.db.restore-admin/force-invoked?
   :seon.db.restore-admin/force-invoked?]
  [:seon.db.restore-admin/connection-state
   :seon.db.restore-admin/connection-state]])

(schema/register!
 :seon.db.restore-admin/invalid-request-result
 [:map {:closed true}
  [:seon.db.restore-admin/error-kind :seon.db.restore-admin/error-kind]
  [:seon.db.restore-admin/error :seon.db.restore-admin/error]
  [:seon.db.restore-admin/force-invoked? [:= false]]
  [:seon.db.restore-admin/connection-state
   [:= :seon.db.restore-admin.connection/not-opened]]])

(schema/register!
 :seon.db.restore-admin/unknown-effect-result
 [:map {:closed true}
  [:seon.db.restore-admin/intent-id :seon.db.restore-admin/intent-id]
  [:seon.db.restore-admin/plan-digest :seon.db.restore-admin/plan-digest]
  [:seon.db.restore-admin/error-kind :seon.db.restore-admin/error-kind]
  [:seon.db.restore-admin/error :seon.db.restore-admin/error]
  [:seon.db.restore-admin/pre-restore-main-coordinate
   :seon.db.restore-admin/pre-restore-main-coordinate]
  [:seon.db.restore-admin/selected-target-coordinate
   :seon.db.restore-admin/selected-target-coordinate]
  [:seon.db.restore-admin/prepared-target-coordinate
   :seon.db.restore-admin/prepared-target-coordinate]
  [:seon.db.restore-admin/undo-coordinate
   :seon.db.restore-admin/undo-coordinate]
  [:seon.db.restore-admin/effect-state
   [:= :seon.db.restore-admin.effect/unknown]]
  [:seon.db.restore-admin/connection-state
   [:= :seon.db.restore-admin.connection/cleanup-unproved]]])

(schema/register!
 :seon.db.restore-admin/result
 [:or :seon.db.restore-admin/success-result
  :seon.db.restore-admin/rejected-result
  :seon.db.restore-admin/invalid-request-result
  :seon.db.restore-admin/unknown-effect-result])
