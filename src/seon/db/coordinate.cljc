(ns seon.db.coordinate
  "Portable identity for one database attachment and one immutable point.

   A numeric Datahike transaction id is only ordered inside one lineage. The
   complete point therefore carries database id, branch, commit id, and `t`.
   Logical database names remain routing data in `seon.db.protocol`; they are
   not database identity."
  (:require
   #?@(:bb []
       :clj [[datahike.api :as d]
             [datahike.constants :as constants]
             [datahike.db.interface :as dbi]]
       :default [])
   [seon.schema :as schema]))

(schema/register! ::database-id :uuid)
(schema/register! ::branch :keyword)
(schema/register! ::commit-id :uuid)
(schema/register! ::t [:int {:min 0}])

(schema/register!
 ::attachment
 [:map {:closed true}
  [::database-id ::database-id]
  [::branch ::branch]])

(schema/register!
 ::coordinate
 [:map {:closed true}
  [::database-id ::database-id]
  [::branch ::branch]
  [::commit-id ::commit-id]
  [::t ::t]])

;; A Datahike immutable database value is an opaque third-party record.
(schema/register! ::db-value :any)

(schema/register! ::target-t ::t)
(schema/register!
 ::at-request
 [:map {:closed true}
  [::db-value ::db-value]
  [::attachment {:optional true} ::attachment]
  [::target-t ::target-t]])

#?(:bb nil
   :clj
   (defn resolved
     "Resolve one complete point from a committed Datahike database value.

      Temporal wrapper values intentionally fail: Datahike `as-of` does not carry
      an independently selected commit id. Pin the containing committed database
      value first, then use `at` for a temporal cut within it."
     {:malli/schema [:=> [:catn [::db-value ::db-value]] ::coordinate]}
     [db]
     (let [point
           {::database-id (get-in db [:config :store :id])
            ::branch (get-in db [:config :branch])
            ::commit-id (d/commit-id db)
            ::t (dbi/-max-tx db)}]
       (when-not (schema/valid-candidate-value? ::coordinate point)
         (throw
          (ex-info "The database value has no complete resolved coordinate."
                   {::coordinate point
                    :seon.error/kind :core-bug})))
       point)))

(defn attachment
  "Project the stable database/branch attachment from one resolved point."
  {:malli/schema [:=> [:catn [::coordinate ::coordinate]] ::attachment]}
  [coordinate]
  (select-keys coordinate [::database-id ::branch]))

(defn same-attachment?
  "True when two resolved points belong to the same database branch."
  {:malli/schema
   [:=>
    [:catn
     [::left ::coordinate]
     [::right ::coordinate]]
    :boolean]}
  [left right]
  (= (attachment left) (attachment right)))

#?(:bb nil
   :clj
   (defn at
     "Identify temporal cut `t` within one immutable containing commit."
     {:malli/schema [:=> [:cat ::at-request] ::coordinate]}
     [{::keys [db-value target-t] attachment* ::attachment}]
     (let [resolved-container (resolved db-value)
           _ (when (and attachment*
                        (not= (::database-id attachment*)
                              (::database-id resolved-container)))
               (throw
                (ex-info "The attachment names a different physical database."
                         {::attachment attachment*
                          ::coordinate resolved-container
                          :seon.error/kind :invalid-database-coordinate})))
           container (merge resolved-container attachment*)
           max-t (::t container)]
       (when-not (<= constants/tx0 target-t max-t)
         (throw
          (ex-info "The temporal cut is outside its containing commit."
                   {::target-t target-t
                    ::coordinate container
                    :seon.error/kind :invalid-database-coordinate})))
       (when (and (> target-t constants/tx0)
                  (empty? (d/datoms db-value :eavt target-t :db/txInstant)))
         (throw
          (ex-info "The temporal cut is not an exact committed transaction."
                   {::target-t target-t
                    ::coordinate container
                    :seon.error/kind :invalid-database-coordinate})))
       (assoc container ::t target-t))))
