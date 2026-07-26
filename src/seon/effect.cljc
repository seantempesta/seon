(ns seon.effect
  "Effect replay identity and admission for capability owners.

  Not a wrapper layer and not a dispatcher: agents call the owning
  APIs directly (`seon.db/transact!`, `seon.agent.message/message!`).
  This namespace is the one place effect identity is DERIVED. The run
  loop binds `*request-context*` around each form execution; an owner
  performing a durable effect asks `next-op-id!` for its replay
  identity when the caller supplied none. The identity is
  `(run, form-ordinal, effect-ordinal)` — claim epoch is a run fence
  and never part of it — so re-execution after a crash derives the
  SAME identity and the owner's ledger replays the recorded result
  instead of repeating the effect. Errors are values; the ceiling is
  at-least-once."
  (:require [seon.schema :as schema]))

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

(defn invocation-counter?
  "True for the invocation-local effect counter (an atom)."
  [v]
  #?(:clj (instance? clojure.lang.IAtom v)
     :cljs (instance? cljs.core/Atom v)))

(schema/register-core-predicate!
 'seon.effect/ordinary-request-value?
 ordinary-request-value?)
(schema/register-core-predicate!
 'seon.effect/invocation-counter?
 invocation-counter?)

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

;;; The executing form's effect coordinates. The run loop injects one
;;; context per form execution; the counter is invocation-local
;;; coordination (a sanctioned atom) — never indexed, never persisted —
;;; reset by construction on every re-execution so a re-run derives the
;;; same ordinal sequence. Effect identity therefore assumes SEQUENTIAL
;;; effect issue within one form; a parallel eval surface needs its own
;;; identity ruling before it ships.

(schema/register!
 ::request-context
 [:map {:closed true}
  [::run-id [:string {:min 1}]]
  [::form-ordinal [:int {:min 0}]]
  [::effect-counter
   [:fn {:error/message "must be the invocation counter atom"
         :gen/schema [:= nil]}
    'seon.effect/invocation-counter?]]
  [:seon.agent/id {:optional true} [:string {:min 1}]]])

(def ^:dynamic *request-context*
  "The executing form's effect coordinates, or nil outside a run."
  nil)

(defn request-context
  "Build one form execution's effect context from its receipt coordinates."
  {:malli/schema [:=> [:catn [::run-id [:string {:min 1}]]
                       [::form-ordinal [:int {:min 0}]]]
                  ::request-context]}
  [run-id form-ordinal]
  {::run-id run-id
   ::form-ordinal form-ordinal
   ::effect-counter (atom -1)})

(defn op-id
  "Derive one effect's replay identity from its executing coordinates."
  {:malli/schema [:=> [:catn [::run-id [:string {:min 1}]]
                       [::form-ordinal [:int {:min 0}]]
                       [::effect-ordinal [:int {:min 0}]]]
                  [:string {:min 1}]]}
  [run-id form-ordinal effect-ordinal]
  (pr-str [run-id form-ordinal effect-ordinal]))

(defn next-op-id!
  "The next effect's replay identity, or nil outside a run.
  Nil means a system-side caller with no replay coordinates.
  Capability owners call this when a durable effect arrives without an
  explicit `:seon.capability/op-id`."
  {:malli/schema [:=> [:cat] [:maybe [:string {:min 1}]]]}
  []
  (when-let [{::keys [run-id form-ordinal effect-counter]}
             *request-context*]
    (op-id run-id form-ordinal (swap! effect-counter inc))))
