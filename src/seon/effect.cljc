(ns seon.effect
  "The one system-side owner every agent-facing tool call enters.

  CONTRACT LAYER ONLY (owner ruling 2026-07-26: Fable authors schemas and
  contracts; a sol lane implements until the pending properties go green).
  `request!` is declared with its complete contract and returns an honest
  not-implemented error value until the step-1 implementation lands.

  Grounding: the request identity is `:seon.capability/op-id`, the replay
  identity `seon.db/transact!` already accepts; capability classification
  is the existing `:seon.capability/effect` fn metadata in the db owner;
  results follow the message owner's idiom — a concise domain map or a
  flat error value, never a raw report and never a throw."
  (:require [seon.schema :as schema]
            ; loads the :seon.capability/op-id registration this envelope
            ; references; the implementation calls seon.db anyway
            [seon.db]))

;;; The effect family — the closed architectural set from the plan's
;;; construct 5. A new family is an architecture decision, never a row
;;; appended casually; each family's args schema is registered by its
;;; own my.* surface and joined here through the `:multi` dispatch.

(schema/register! ::family
                  [:enum :db :blob :fs :shell :web :message :llm])

(defn ordinary-request-value?
  "True for a value admissible inside an effect request or result.
  Admissible means realized, finite data — never a function, promise, or
  lazy seq.
  Lazy sequences are refused by construction; realization is the
  admission boundary's job, not the consumer's."
  [v]
  (or (nil? v) ; absent-vs-nil is enforced at the map level, not per leaf
      (string? v) (number? v) (boolean? v)
      (keyword? v) (symbol? v) (uuid? v) (inst? v)
      (and (map? v)
           (every? ordinary-request-value? (keys v))
           (every? ordinary-request-value? (vals v)))
      (and (or (vector? v) (set? v) (list? v))
           (every? ordinary-request-value? v))))

(schema/register-core-predicate!
 'seon.effect/ordinary-request-value?
 ordinary-request-value?)

(schema/register!
 ::args
 [:fn {:error/message "must be realized, finite, ordinary data"
       :gen/schema [:map-of :keyword :string]}
  'seon.effect/ordinary-request-value?])
(schema/register!
 ::value
 [:fn {:error/message "must be realized, finite, ordinary data"
       :gen/schema [:map-of :keyword :string]}
  'seon.effect/ordinary-request-value?])

;;; The request envelope. Provenance is injected by the run loop from the
;;; executing receipt — (run, ordinal, epoch) — so an effect is always
;;; attributable to the form that requested it; agent code never supplies
;;; provenance itself.

(schema/register!
 ::request
 [:map {:closed true}
  [:seon.effect/family ::family]
  [:seon.effect/args ::args]
  [:seon.capability/op-id {:optional true} :seon.capability/op-id]])

;;; The result envelope: a concise domain value or a flat error, never a
;;; throw (nothing throws into the agent loop). `::value` is absent —
;;; never nil — when the effect produces no value. At-least-once is the
;;; honest delivery ceiling: `:seon.capability/op-id` is what makes a retry a
;;; replay instead of a second effect.

(schema/register!
 ::result
 [:or
  [:map {:closed true}
   [:seon.effect/family ::family]
   [:seon.capability/op-id :seon.capability/op-id]
   [:seon.effect/value {:optional true} ::value]]
  [:map
   [:seon.error/message :string]
   [:seon.error/kind {:optional true} :qualified-keyword]]])

(defn request!
  "Perform one guarded effect request.
  The single entry point for every my.* tool: records the request
  identity before the effect runs, performs it
  through the family's one owner, and returns the result envelope.
  Errors are values; the ceiling is at-least-once."
  {:malli/schema [:=> [:cat ::request] ::result]}
  [request]
  {:seon.error/message
   (str "seon.effect/request! is not implemented; the step-1 "
        "implementation lane activates this contract. Requested family: "
        (:seon.effect/family request))
   :seon.error/kind :seon.effect/not-implemented})
