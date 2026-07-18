(ns seon.db.restore.schema
  "Portable schemas for durable completed-restore facts."
  (:require [seon.db.branch :as branch]
            [seon.db.id.schema]
            [seon.dev.restore.schema]
            [seon.schema :as schema]))

(schema/register!
 :seon.db.restore/id
 [:and {:seon.db/identity true
        :seon.db.id/generator :seon.db.id.generator/compact}
  :seon.db.id/compact-value])
(schema/register!
 :seon.db.restore/generated-id
 [:and :string [:re "^[a-z][a-z0-9]{11}$"]])
(schema/register!
 :seon.db.restore/plan-digest
 [:string {:min 64 :max 64 :seon.db/identity true}])
(schema/register! :seon.db.restore/db-name :keyword)
(schema/register! :seon.db.restore/database-id ::branch/store-id)
(schema/register! :seon.db.restore/from-branch :keyword)
(schema/register! :seon.db.restore/from-commit-id :uuid)
(schema/register! :seon.db.restore/from-t :int)
(schema/register! :seon.db.restore/to-branch :keyword)
(schema/register! :seon.db.restore/to-commit-id :uuid)
(schema/register! :seon.db.restore/to-t :int)
(schema/register! :seon.db.restore/forced-commit-id :uuid)
(schema/register! :seon.db.restore/undo-branch :keyword)
(schema/register! :seon.db.restore/target-branch :keyword)
(schema/register! :seon.db.restore/core-overlay-digest :string)
(schema/register! :seon.db.restore/config-overlay-digest :string)

(def ^:private payload-fields
  [[:seon.db.restore/db-name :seon.db.restore/db-name]
   [:seon.db.restore/database-id :seon.db.restore/database-id]
   [:seon.db.restore/from-branch :seon.db.restore/from-branch]
   [:seon.db.restore/from-commit-id :seon.db.restore/from-commit-id]
   [:seon.db.restore/from-t :seon.db.restore/from-t]
   [:seon.db.restore/to-branch :seon.db.restore/to-branch]
   [:seon.db.restore/to-commit-id :seon.db.restore/to-commit-id]
   [:seon.db.restore/to-t :seon.db.restore/to-t]
   [:seon.db.restore/forced-commit-id :seon.db.restore/forced-commit-id]
   [:seon.db.restore/undo-branch :seon.db.restore/undo-branch]
   [:seon.db.restore/target-branch :seon.db.restore/target-branch]
   [:seon.db.restore/core-overlay-digest {:optional true}
    :seon.db.restore/core-overlay-digest]
   [:seon.db.restore/config-overlay-digest {:optional true}
    :seon.db.restore/config-overlay-digest]])

(schema/register!
 :seon.db.restore/completion-claim
 (into [:map {:closed true}]
       (cons [:seon.db.restore/plan-digest
              :seon.dev.restore/plan-digest]
             payload-fields)))

(schema/register!
 :seon.db.restore/current-completion
 (into [:map {:closed true :seon.db/entity true}]
       (concat [[:seon.db.restore/id :seon.db.restore/generated-id]
                [:seon.db.restore/plan-digest
                 :seon.dev.restore/plan-digest]]
               payload-fields)))

;; Rows written before plan-digest allocation remain historical inputs for
;; undo. They are never valid claims for a new completion publication.
(schema/register!
 :seon.db.restore/legacy-completion
 (into [:map {:closed true :seon.db/entity true}]
       (cons [:seon.db.restore/id :seon.db.restore/id]
             payload-fields)))

(schema/register!
 :seon.db.restore/completion
 [:or :seon.db.restore/current-completion
  :seon.db.restore/legacy-completion])
