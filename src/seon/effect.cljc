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
  (:require [seon.agent.message :as message]
            [seon.schema :as schema]
            ; loads the :seon.capability/op-id registration this envelope
            ; references; the implementation calls seon.db anyway
            [seon.db :as db]))

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

(def ^:dynamic *request-context*
  "The executing form's lexical effect context."
  nil)

(defn- error-value?
  [value]
  (and (map? value)
       (string? (:seon.error/message value))))

(defn- failure
  [message kind]
  {:seon.error/message message
   :seon.error/kind kind})

(defn- operation
  [key fallback]
  (or (get *request-context* key) fallback))

(defn- message-result
  [op-id args]
  (let [agent-id (:seon.agent/id *request-context*)
        request
        (cond-> {:seon.agent.message/content
                 (:seon.agent.message/content args)
                 :seon.agent.message/to
                 (mapv (fn [recipient]
                         [:seon.agent/id recipient])
                       (:seon.agent.message/to args))
                 :seon.capability/op-id op-id}
          agent-id
          (assoc :seon.agent.message/from
                 [:seon.agent/id agent-id]))
        result ((operation ::message! message/message!) request)]
    (if (error-value? result)
      result
      {:seon.effect/family :message
       :seon.capability/op-id op-id
       :seon.effect/value
       (select-keys result
                    [:seon.agent.message/id
                     :seon.agent.message/hops])})))

(defn- query-result
  [op-id args]
  (let [query (:my.db/q args)
        inputs (:my.db/inputs args)
        query! (operation ::query db/query)
        result (apply query! query inputs)]
    (if (error-value? result)
      result
      {:seon.effect/family :db
       :seon.capability/op-id op-id
       :seon.effect/value result})))

(defn- transaction-basis
  [report]
  (or (get-in report [:db-after :as-of])
      (get-in report [:db-after :t])))

(defn- transaction-result
  [op-id args]
  (let [transact! (operation ::transact! db/transact!)
        report
        (transact!
         {:seon.db/tx-data (:my.db/tx-data args)
          :seon.capability/op-id op-id})]
    (if (error-value? report)
      report
      {:seon.effect/family :db
       :seon.capability/op-id op-id
       :seon.effect/value
       (cond->
        {:seon.db/t (transaction-basis report)
         :seon.capability/op-id op-id}
         (:seon.capability/replayed? report)
         (assoc :seon.capability/replayed? true))})))

(defn- dispatch
  [{family :seon.effect/family
    args :seon.effect/args
    op-id :seon.capability/op-id}]
  (case family
    :message (message-result op-id args)
    :db (cond
          (contains? args :my.db/q)
          (query-result op-id args)

          (contains? args :my.db/tx-data)
          (transaction-result op-id args)

          :else
          (failure "The database effect request names no supported operation."
                   :seon.effect/invalid-db-operation))
    (failure (str "Effect family " family " is not implemented.")
             :seon.effect/not-implemented)))

(defn request!
  "Perform one guarded effect request.
  The single entry point for every my.* tool: records the request
  identity before the effect runs, performs it
  through the family's one owner, and returns the result envelope.
  Errors are values; the ceiling is at-least-once."
  {:malli/schema [:=> [:cat ::request] ::result]}
  [request]
  (try
    (cond
      (not (schema/valid-candidate-value? ::request request))
      (failure "The effect request envelope is invalid."
               :seon.effect/invalid-request)

      :else
      (if-let [op-id (or (:seon.capability/op-id request)
                         (:seon.capability/op-id *request-context*))]
        (let [result (dispatch (assoc request :seon.capability/op-id op-id))]
          (if (schema/valid-candidate-value? ::result result)
            result
            (failure "The effect family returned an invalid result envelope."
                     :seon.effect/invalid-result)))
        {:seon.error/message
         (str "seon.effect/request! is not implemented; the step-1 "
              "implementation lane activates this contract. Requested family: "
              (:seon.effect/family request))
         :seon.error/kind :seon.effect/not-implemented}))
    (catch #?(:clj Throwable :cljs :default) exception
      (failure (or #?(:clj (ex-message exception)
                      :cljs (.-message exception))
                   "The effect request failed.")
               :seon.effect/request-failed))))
