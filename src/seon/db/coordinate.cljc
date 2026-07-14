(ns seon.db.coordinate
  "Portable identity for one database attachment and one immutable point.

   A numeric Datahike transaction id is only ordered inside one lineage. The
   complete point therefore carries database id, branch, commit id, and `t`.
   Logical database names remain routing data in `seon.db.protocol`; they are
   not database identity."
  (:require
   [datahike.api :as d]
   [datahike.db.interface :as dbi]
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

(defn resolved
  "Resolve one complete point from a committed Datahike database value.

   Temporal wrapper values intentionally fail: Datahike `as-of` does not carry
   the selected commit id. Historical selectors must resolve through the
   maintained commit graph before calling this projection."
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
    point))

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
