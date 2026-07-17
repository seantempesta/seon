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

;; Portable allocation data. These shapes live below both `seon.db` and
;; `seon.db.id` so the database facade can load the wire/result contracts
;; without loading the Bun candidate-generation implementation.
(schema/register! :seon.db.id/identity-attr :qualified-keyword)
(schema/register! :seon.db.id/key :qualified-keyword)
(schema/register! :seon.db.id/value :seon.db/id)
(schema/register!
 :seon.db.id/allocation
 [:map
  [:seon.db.id/key :seon.db.id/key]
  [:seon.db.id/identity-attr :qualified-keyword]])
(schema/register!
 :seon.db.id/allocations
 [:vector {:min 1} :seon.db.id/allocation])
(schema/register! :seon.db.id/transaction-builder 'fn?)
(schema/register! :seon.db.id/candidate-key :seon.db.id/key)
(schema/register!
 :seon.db.id/lookup-ref
 [:tuple :seon.db.id/identity-attr :seon.db/lookup-ref-value])
(schema/register!
 :seon.db.id/dependent-identity
 [:map
  [:seon.db.id/candidate-key :seon.db.id/candidate-key]
  [:seon.db.id/lookup-ref :seon.db.id/lookup-ref]])
(schema/register!
 :seon.db.id/dependent-identities
 [:vector {:min 1} :seon.db.id/dependent-identity])
(schema/register!
 :seon.db.id/dependent-lookup-refs
 [:vector {:min 1} :seon.db.id/lookup-ref])
(schema/register!
 :seon.db.id/generated-candidate
 [:map
  [:seon.db.id/key :seon.db.id/key]
  [:seon.db.id/identity-attr :seon.db.id/identity-attr]
  [:seon.db.id/value :seon.db.id/value]
  [:seon.db.id/dependent-lookup-refs
   {:optional true} :seon.db.id/dependent-lookup-refs]])
(schema/register!
 :seon.db.id/generated-candidates
 [:vector {:min 1} :seon.db.id/generated-candidate])
(schema/register!
 :seon.db.id/generator-policies
 [:map-of :seon.db.id/identity-attr :seon.db.id/generator])
(schema/register!
 :seon.db.id/ids
 [:map-of :seon.db.id/key :seon.db.id/value])
(schema/register! :seon.db.id/eids [:map-of :seon.db.id/key :int])
(schema/register! :seon.db.id/recovered-commit? :boolean)
(schema/register! :seon.db.id/attempts [:int {:min 1 :max 16}])
(schema/register!
 :seon.db.id/allocate-request
 [:map
  [:seon.db.id/allocations :seon.db.id/allocations]
 [:seon.db.id/transaction-builder :seon.db.id/transaction-builder]
  [:seon.db/db {:optional true} :map]
  [:seon.db.id/generator-policies
   {:optional true} :seon.db.id/generator-policies]])
(schema/register!
 :seon.db.id/allocate-response
 [:or
  [:map
   [:seon.db.id/ids :seon.db.id/ids]
   [:seon.db.id/eids :seon.db.id/eids]
   [:seon.db.id/recovered-commit?
    {:optional true} :seon.db.id/recovered-commit?]]
  [:map
   [:seon.error/message :string]
   [:seon.error/kind :keyword]
   [:seon.error/data :map]]])
