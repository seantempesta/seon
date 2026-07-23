(ns seon.eval.receipt
  "Build and inspect durable evaluation receipts.

   Pure helpers derive receipt state and build the transaction data that starts
   and terminalizes one receipt on either active runtime tier."
  (:require
    [seon.db.id :as db.id]
    [seon.schema :as schema]))

(schema/register!
  :seon.eval/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.eval/at :inst)
;; Wall-clock duration per form.
(schema/register! :seon.eval/duration-ms :int)
(schema/register! :seon.eval/narration :string)
(schema/register! :seon.eval/source :string)
(schema/register! :seon.eval/status
                  [:enum :running :done :error :interrupted])
(schema/register! :seon.eval/ok? :boolean)
(schema/register! :seon.eval/progress? :boolean)
(schema/register! :seon.eval/result-edn :string)
;; Captured println/prn output, present only when the form printed.
(schema/register! :seon.eval/output :string)
(schema/register! :seon.eval/error :string)
;; Structured instrumentation envelope stored as pr-str because the
;; Malli-to-Datahike bridge has no map value type.
(schema/register! :seon.eval/error-data :string)
;; The namespace the eval ended in.
(schema/register! :seon.eval/ns :symbol)
;; Optional direct ref to the agent whose scope produced the eval.
(schema/register! :seon.eval/agent :seon.db/ref)
(schema/register!
  :seon.agent.turn/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.agent.turn/evals
                  [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! ::receipt-state
                  [:enum :absent :running :done :error :interrupted])
(schema/register! ::start-request
                  [:map {:closed true}
                   [:seon.agent.turn/id ::db.id/compact-value]
                   [:seon.eval/id ::db.id/compact-value]
                   [:seon.eval/at :inst]
                   [:seon.eval/source :string]
                   [:seon.eval/narration :string]
                   [:seon.eval/ns :symbol]
                   [:seon.eval/agent {:optional true} :seon.db/ref]])
(schema/register! ::terminal-request
                  [:map {:closed true}
                   [:seon.eval/id ::db.id/compact-value]
                   [:seon.eval/status
                    [:enum :done :error :interrupted]]])

(defn receipt-state
  "Derive one eval receipt's state, including historical terminal rows."
  {:malli/schema [:=> [:catn [::eval-row :map]] ::receipt-state]}
  [eval-row]
  (or (:seon.eval/status eval-row)
      (when (contains? eval-row :seon.eval/ok?)
        (if (:seon.eval/ok? eval-row) :done :error))
      :absent))

(defn start-tx-data
  "Build the component transaction data that starts one eval receipt."
  {:malli/schema [:=> [:catn [::request ::start-request]] :seon.db/tx-data]}
  [{turn-id :seon.agent.turn/id
    eval-id :seon.eval/id
    at :seon.eval/at
    source :seon.eval/source
    narration :seon.eval/narration
    eval-ns :seon.eval/ns
    agent :seon.eval/agent}]
  [{:seon.agent.turn/id turn-id
    :seon.agent.turn/evals
    [(cond->
       {:seon.eval/id eval-id
        :seon.eval/status :running
        :seon.eval/at at
        :seon.eval/source source
        :seon.eval/narration narration
        :seon.eval/ns eval-ns}
       agent (assoc :seon.eval/agent agent))]}])

(defn terminal-tx-data
  "Build the CAS-fenced terminal transition for one running eval receipt."
  {:malli/schema [:=> [:catn [::request ::terminal-request]] :seon.db/tx-data]}
  [{eval-id :seon.eval/id status :seon.eval/status}]
  ;; Plain transaction data matching the agent-facing `seon.db/cas-assert`.
  [[:db.fn/cas [:seon.eval/id eval-id]
    :seon.eval/status :running :running]
   {:seon.eval/id eval-id
    :seon.eval/status status
    :seon.eval/ok? (= :done status)}])
