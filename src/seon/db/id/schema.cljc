(ns seon.db.id.schema
  "Portable syntax and policy schemas for generated database identities."
  (:require [seon.schema :as schema]))

(def word-pattern-source "^[a-z0-9]+-[a-z0-9]+-[a-z0-9]+$")
(def compact-pattern-source "^[a-z][a-z0-9]{11}$")
(def word-pattern (re-pattern word-pattern-source))
(def compact-pattern (re-pattern compact-pattern-source))

;; The old schema accepted every 14-character string. Existing values remain
;; readable, but the generators only publish the current narrower syntax.
(schema/register! :seon.db.id/legacy-value [:string {:min 14 :max 14}])
(schema/register!
 :seon.db.id/word-value
 [:and :string [:re word-pattern-source]])
(schema/register!
 :seon.db.id/compact-value
 [:or :seon.db.id/legacy-value
  [:and :string [:re compact-pattern-source]]])
(schema/register!
 :seon.db.id/agent-value
 [:or [:= "root"] :seon.db.id/legacy-value :seon.db.id/word-value])
(schema/register!
 :seon.db/id
 [:or :seon.db.id/agent-value :seon.db.id/compact-value])
(schema/register!
 :seon.db.id/generator
 [:enum
  :seon.db.id.generator/human-readable
  :seon.db.id.generator/compact])
