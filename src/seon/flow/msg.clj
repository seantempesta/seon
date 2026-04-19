(ns seon.flow.msg
  "Message envelope schemas for flow wire protocol.

   Single source of truth for all messages between orchestrator and agent JVMs.
   All keys fully namespaced under `seon.flow.msg`.

   Wire format: length-prefixed Nippy (fast-freeze/fast-thaw). Nippy handles
   all JVM types natively — no tagged literals or custom print-methods needed.

   Dynamic validation: Three fields (::args, ::value, ::payload) use the
   :seon.flow/dynamic type instead of :any. Their content is validated
   dynamically at message boundaries using:
     - validate-fn-args!  — args validated against ::fn's :malli/schema input
     - validate-fn-value! — value validated against ::fn's :malli/schema output
     - validate-payload!  — each key validated against its registered schema"
  (:require [malli.core :as m]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Envelope Keys
;;; ---------------------------------------------------------------------------

(schema/register! ::id :uuid)
(schema/register! ::version [:= 1])
(schema/register! ::type [:enum :request :reply :error :event])
(schema/register! ::from-ns [:string {:min 1}])
(schema/register! ::to-ns [:string {:min 1}])
(schema/register! ::fn [:string {:min 1 :description "Fully qualified function name"}])
(schema/register! ::args [:vector :seon.flow/dynamic])
(schema/register! ::timeout-ms [:int {:min 1}])
(schema/register! ::reply-required? :boolean)
(schema/register! ::trace-id :uuid)
(schema/register! ::created-at :inst)
(schema/register! ::payload [:map-of :keyword :seon.flow/dynamic])

;;; ---------------------------------------------------------------------------
;;; Request
;;; ---------------------------------------------------------------------------

(schema/register! ::request
                  [:map
                   [::id ::id]
                   [::version ::version]
                   [::type [:= :request]]
                   [::from-ns ::from-ns]
                   [::to-ns ::to-ns]
                   [::fn ::fn]
                   [::args ::args]
                   [::reply-required? {:optional true} ::reply-required?]
                   [::timeout-ms {:optional true} ::timeout-ms]
                   [::trace-id {:optional true} ::trace-id]
                   [::created-at ::created-at]
                   [::payload {:optional true} ::payload]])

;;; ---------------------------------------------------------------------------
;;; Reply
;;; ---------------------------------------------------------------------------

(schema/register! ::status [:enum :ok :error :timeout :overload])
(schema/register! ::error-type [:enum :execution :timeout :overload :serialization :not-found])
(schema/register! ::error-class [:string {:description "Exception class name"}])
(schema/register! ::error-message :string)
(schema/register! ::error-data :map)
(schema/register! ::duration-ms [:int {:min 0}])
(schema/register! ::value :seon.flow/dynamic)

(schema/register! ::reply
                  [:map
                   [::id ::id]
                   [::version ::version]
                   [::type [:= :reply]]
                   [::status ::status]
                   [::from-ns ::from-ns]
                   [::value {:optional true} ::value]
                   [::error-type {:optional true} ::error-type]
                   [::error-class {:optional true} ::error-class]
                   [::error-message {:optional true} ::error-message]
                   [::error-data {:optional true} ::error-data]
                   [::duration-ms ::duration-ms]
                   [::trace-id {:optional true} ::trace-id]])

;;; ---------------------------------------------------------------------------
;;; Observability Events
;;; ---------------------------------------------------------------------------

(schema/register! ::event-kind [:enum :start :ok :error :overload :timeout :pause :resume :stop])

(schema/register! ::event
                  [:map
                   [::id ::id]
                   [::version ::version]
                   [::type [:= :event]]
                   [::event-kind ::event-kind]
                   [::from-ns ::from-ns]
                   [::created-at ::created-at]
                   [::payload {:optional true} ::payload]])
