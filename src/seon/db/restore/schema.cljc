(ns seon.db.restore.schema
  "Portable schemas for durable completed-restore facts."
  (:require [seon.db.coordinate :as coordinate]
            [seon.db.id.schema]
            [seon.schema :as schema]))

(schema/register!
 :seon.db.restore/id
 [:and {:seon.db/identity true
        :seon.db.id/generator :seon.db.id.generator/compact}
  :seon.db.id/compact-value])
(schema/register! :seon.db.restore/db-name :keyword)
(schema/register! :seon.db.restore/database-id ::coordinate/database-id)
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

(schema/register!
 :seon.db.restore/completion
 [:map {:closed true :seon.db/entity true}
  [:seon.db.restore/id :seon.db.restore/id]
  [:seon.db.restore/db-name :seon.db.restore/db-name]
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
