(ns seon.agent.turn
  "Define portable turn, timing, and model-attempt connections."
  (:require
    [seon.ai.attempt]
    [seon.db.id :as db.id]
    [seon.schema :as schema]))

(schema/register!
  ::id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! ::at [:inst {:seon.db/index true}])
(schema/register!
  ::status
  [:enum :running :done :error :interrupted])
(schema/register!
  ::phase
  [:enum :rendered :attempt-open :reply-ready
   :evaling :evaled :published])
(schema/register! ::run :seon.db/ref)
(schema/register! ::scheduled? :boolean)
(schema/register! ::rendered-tx :seon.db/ref)
(schema/register! ::prompt-blob :seon.db/ref)
(schema/register! ::reply-blob :seon.db/ref)
(schema/register! ::error :string)
(schema/register! ::usage-estimated? :boolean)
(schema/register!
  ::evals
  [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! ::llm-attempts
                  [:set {:seon.db/component true} :seon.db/ref])
(schema/register! ::duration-ns [:int {:min 0}])
(schema/register!
  ::timings
  [:vector {:seon.db/component true} :seon.db/ref])

;; Process-local turn coordinates share the owning vocabulary but are not
;; database attributes.
(schema/register! ::current-id :string)
(schema/register! ::id-of-turn :string)

(schema/register!
  :seon.agent.turn.timing/name
  [:enum
   :run-admission-transaction-call
   :turn-transaction-call
   :context-derivation
   :provider-request-response
   :model-envelope-overhead
   :reply-derivation
   :plan-transaction-call
   :eval-admission-transaction-call
   :eval
   :eval-terminal-transaction-call
   :publish-transaction-call])
(schema/register!
  :seon.agent.turn.timing/ordinal
  [:int {:min 0}])
(schema/register!
  :seon.agent.turn.timing/duration-ns
  [:int {:min 0}])
(schema/register!
  :seon.agent.turn.timing/transaction
  :seon.db/ref)
(schema/register!
  :seon.agent.turn.timing
  [:map {:seon.db/entity true}
   [:seon.agent.turn.timing/name
    :seon.agent.turn.timing/name]
   [:seon.agent.turn.timing/ordinal
    :seon.agent.turn.timing/ordinal]
   [:seon.agent.turn.timing/duration-ns
    :seon.agent.turn.timing/duration-ns]
   [:seon.agent.turn.timing/transaction
    {:optional true} :seon.agent.turn.timing/transaction]])

(schema/register!
  :seon.agent.turn
  [:map {:seon.db/entity true}
   [::id ::id]
   [::at ::at]
   [::status ::status]
   [::phase {:optional true} ::phase]
   [::run {:optional true} ::run]
   [::scheduled? {:optional true} ::scheduled?]
   [::rendered-tx {:optional true} ::rendered-tx]
   [::prompt-blob {:optional true} ::prompt-blob]
   [::reply-blob {:optional true} ::reply-blob]
   [::error {:optional true} ::error]
   [::usage-estimated? {:optional true} ::usage-estimated?]
   [::evals {:optional true} ::evals]
   [::llm-attempts {:optional true} ::llm-attempts]
   [::duration-ns {:optional true} ::duration-ns]
   [::timings {:optional true} ::timings]])
