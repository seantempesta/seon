(ns seon.eval.receipt
  "Build and inspect durable evaluation receipts.

   Pure helpers derive receipt state and build the transaction data that starts
   and terminalizes one receipt on either active runtime tier."
  (:require
    [seon.agent.turn]
    [seon.db.id :as db.id]
    [seon.schema :as schema]))

(schema/register!
  :seon.eval/id
  [:string {:seon.db/identity true}])
(schema/register! :seon.eval/at :inst)
;; Zero-based form ordinal and the complete form count frozen before execution.
;; Both ride the running receipt so a kill cannot erase "form 3 of 7".
(schema/register! :seon.eval/ordinal [:int {:min 0}])
(schema/register! :seon.eval/total [:int {:min 1}])
(schema/register! :seon.eval/run :seon.db/ref)
(schema/register! :seon.eval/claim-epoch [:int {:min 1}])
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
  :seon.eval
  [:map {:seon.db/entity true
         :seon.render/ai 'seon.render.handlers.eval/render-ai
         :seon.render/html 'seon.render.handlers.eval/render-html}
   [:seon.eval/id :seon.eval/id]
   [:seon.eval/source :seon.eval/source]
   [:seon.eval/ok? :seon.eval/ok?]
   [:seon.eval/at :seon.eval/at]
   [:seon.eval/run {:optional true} :seon.eval/run]
   [:seon.eval/ordinal {:optional true} :seon.eval/ordinal]
   [:seon.eval/total {:optional true} :seon.eval/total]
   [:seon.eval/claim-epoch {:optional true} :seon.eval/claim-epoch]
   [:seon.eval/status {:optional true} :seon.eval/status]
   [:seon.eval/agent {:optional true} :seon.eval/agent]
   [:seon.eval/duration-ms {:optional true} :seon.eval/duration-ms]
   [:seon.eval/narration {:optional true} :seon.eval/narration]
   [:seon.eval/ns {:optional true} :seon.eval/ns]
   [:seon.eval/progress? {:optional true} :seon.eval/progress?]
   [:seon.eval/result-edn {:optional true} :seon.eval/result-edn]
   [:seon.eval/output {:optional true} :seon.eval/output]
   [:seon.eval/error {:optional true} :seon.eval/error]
   [:seon.eval/error-data {:optional true} :seon.eval/error-data]
   [:seon.render/full? {:optional true} :seon.render/full?]])
(schema/register! ::receipt-state
                  [:enum :absent :running :done :error :interrupted])
(schema/register! ::start-request
                  [:map {:closed true}
                   [:seon.agent.turn/id ::db.id/compact-value]
                   [:seon.agent.run/id :string]
                   [:seon.eval/at :inst]
                   [:seon.eval/ordinal :seon.eval/ordinal]
                   [:seon.eval/total :seon.eval/total]
                   [:seon.eval/claim-epoch :seon.eval/claim-epoch]
                   [:seon.eval/source :string]
                   [:seon.eval/narration :string]
                   [:seon.eval/ns :symbol]
                   [:seon.eval/agent {:optional true} :seon.db/ref]])
(schema/register! ::terminal-request
                  [:map {:closed true}
                   [:seon.eval/id :seon.eval/id]
                   [:seon.eval/status
                    [:enum :done :error :interrupted]]])

(defn receipt-id
  "Deterministic identity for one run ordinal attempted in one claim epoch."
  {:malli/schema
   [:=> [:catn [::run-id :string]
                [::ordinal :seon.eval/ordinal]
                [::claim-epoch :seon.eval/claim-epoch]]
    :seon.eval/id]}
  [run-id ordinal claim-epoch]
  (pr-str [run-id ordinal claim-epoch]))

(defn terminal-status?
  "True when an eval receipt status proves the attempt has settled."
  {:malli/schema [:=> [:catn [::status :seon.eval/status]] :boolean]}
  [status]
  (contains? #{:done :error :interrupted} status))

(defn next-ordinal
  "First ordinal without a terminal receipt."
  {:malli/schema
   [:=> [:catn [::total :seon.eval/total]
                [::receipts [:sequential :map]]]
    :seon.eval/ordinal]}
  [total receipts]
  (let [terminal-ordinals
        (into #{}
              (comp
               (filter #(terminal-status? (:seon.eval/status %)))
               (map :seon.eval/ordinal))
              receipts)]
    (or (first (remove terminal-ordinals (range total)))
        total)))

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
    run-id :seon.agent.run/id
    at :seon.eval/at
    ordinal :seon.eval/ordinal
    total :seon.eval/total
    claim-epoch :seon.eval/claim-epoch
    source :seon.eval/source
    narration :seon.eval/narration
    eval-ns :seon.eval/ns
    agent :seon.eval/agent}]
  (let [eval-id (receipt-id run-id ordinal claim-epoch)]
    [{:seon.agent.turn/id turn-id
      :seon.agent.turn/evals
      #{(cond->
        {:seon.eval/id eval-id
         :seon.eval/status :running
         :seon.eval/at at
         :seon.eval/run [:seon.agent.run/id run-id]
         :seon.eval/ordinal ordinal
         :seon.eval/total total
         :seon.eval/claim-epoch claim-epoch
         :seon.eval/source source
         :seon.eval/narration narration
         :seon.eval/ns eval-ns}
        agent (assoc :seon.eval/agent agent))}}]))

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
