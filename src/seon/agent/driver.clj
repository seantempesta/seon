(ns seon.agent.driver
  "Synchronous, database-driven execution of one run's committed forms.

   A reply becomes one ordered form plan in one fenced transaction. Each form
   then follows the same durable fold:

   running receipt -> SCI evaluation -> terminal receipt plus admitted values.

   This namespace contains no process loop and opens no database connection.
   Its caller supplies the one writer operation and immutable database values."
  (:require [seon.agent.lifecycle :as lifecycle]
            [seon.agent.message :as message]
            [seon.agent.run.core :as run]
            [seon.content-hash :as content-hash]
            [seon.eval.receipt :as receipt]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]))

(schema/register! :seon.agent.run/plan-digest :string)
(schema/register! :seon.agent.run/forms
                  [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! :seon.agent.run.form/id
                  [:string {:seon.db/identity true}])
(schema/register! :seon.agent.run.form/run :seon.db/ref)
(schema/register! :seon.agent.run.form/ordinal :seon.eval/ordinal)
(schema/register! :seon.agent.run.form/source :string)

(schema/register!
 ::form
 [:map {:closed true}
  [:seon.agent.run.form/id :seon.agent.run.form/id]
  [:seon.agent.run.form/run :seon.agent.run.form/run]
  [:seon.agent.run.form/ordinal :seon.agent.run.form/ordinal]
  [:seon.agent.run.form/source :seon.agent.run.form/source]])

(schema/register!
 ::plan-request
 [:map {:closed true}
  [:seon.agent/id :string]
  [:seon.agent.run/id :string]
  [:seon.agent.run/claim-epoch :seon.eval/claim-epoch]
  [:seon.agent.run/plan-digest :seon.agent.run/plan-digest]
  [::sources [:vector :string]]])

(schema/register!
 ::form-request
 [:map {:closed true}
  [:seon.agent/id :string]
  [:seon.agent.run/id :string]
  [:seon.agent.run/claim-epoch :seon.eval/claim-epoch]
  [:seon.agent.turn/id :string]
  [:seon.eval/at :inst]
  [:seon.eval/ordinal :seon.eval/ordinal]
  [:seon.eval/total :seon.eval/total]
  [:seon.eval/source :string]
  [:seon.eval/ns :symbol]
  [:seon.sci.interrupt/time-limit-ms :seon.sci.interrupt/time-limit-ms]])

(defn form-id
  "Identity of one form in a run's committed ordered plan."
  {:malli/schema
   [:=> [:catn [::run-id :string]
                [::ordinal :seon.eval/ordinal]]
    :seon.agent.run.form/id]}
  [run-id ordinal]
  (pr-str [run-id ordinal]))

(defn message-id
  "Compact deterministic identity of the message emitted by one receipt."
  {:malli/schema
   [:=> [:catn [::run-id :string]
                [::ordinal :seon.eval/ordinal]
                [::claim-epoch :seon.eval/claim-epoch]]
    :seon.agent.message/id]}
  [run-id ordinal claim-epoch]
  (str "m"
       (subs
        (content-hash/sha-256
         (receipt/receipt-id run-id ordinal claim-epoch))
        0 11)))

(defn lifecycle-tx-data
  "Interpret one lifecycle disposition as ordinary transaction data."
  [{agent-id :seon.agent/id
    run-id :seon.agent.run/id
    claim-epoch :seon.agent.run/claim-epoch
    ordinal :seon.eval/ordinal
    at :seon.eval/at}
   value]
  (case (::lifecycle/disposition value)
    :completed
    (let [result (::lifecycle/result value)]
      (into
       (run/finish-tx-data agent-id run-id claim-epoch :completed at)
       [{:seon.agent.run/id run-id
         :seon.agent.run/result result}
        {:seon.agent.message/id
         (message-id run-id ordinal claim-epoch)
         :seon.agent.message/from [:seon.agent/id agent-id]
         :seon.agent.message/to [message/user-ref]
         :seon.agent.message/content result
         :seon.agent.message/at at
         :seon.agent.message/hops 0
         :seon.agent.message/origin :agent}]))

    :wait
    (run/release-tx-data agent-id run-id claim-epoch)

    nil
    []

    (throw
     (ex-info
      "The lifecycle disposition is not supported by the run driver."
      {:seon.error/kind :user-input
       ::lifecycle/disposition (::lifecycle/disposition value)}))))

(defn plan-tx-data
  "Atomically install exactly one complete ordered form plan.

   The absent-to-digest CAS makes concurrent replies mutually exclusive. The
   losing transaction cannot splice any of its forms into the winning plan."
  {:malli/schema [:=> [:catn [::request ::plan-request]] :seon.db/tx-data]}
  [{agent-id :seon.agent/id
    run-id :seon.agent.run/id
    claim-epoch :seon.agent.run/claim-epoch
    plan-digest :seon.agent.run/plan-digest
    sources ::sources}]
  (let [run-ref [:seon.agent.run/id run-id]]
    (into
     (run/run-fence agent-id run-id claim-epoch)
     [[:db.fn/cas run-ref :seon.agent.run/plan-digest nil plan-digest]
      {:seon.agent.run/id run-id
       :seon.agent.run/forms
       (mapv
        (fn [ordinal source]
          {:seon.agent.run.form/id (form-id run-id ordinal)
           :seon.agent.run.form/run run-ref
           :seon.agent.run.form/ordinal ordinal
           :seon.agent.run.form/source source})
        (range)
        sources)}])))

(defn ordered-forms
  "Return the committed forms in their explicit durable order."
  {:malli/schema [:=> [:catn [::forms [:sequential ::form]]]
                  [:vector ::form]]}
  [forms]
  (vec (sort-by :seon.agent.run.form/ordinal forms)))

(defn next-form
  "Return the first form lacking a terminal receipt, or nil when complete."
  {:malli/schema
   [:=> [:catn [::forms [:sequential ::form]]
                [::receipts [:sequential :map]]]
    [:or ::form :nil]]}
  [forms receipts]
  (let [forms (ordered-forms forms)
        ordinal (receipt/next-ordinal (count forms) receipts)]
    (get forms ordinal)))

(defn- terminal-receipt-data
  [eval-id status value record]
  (into
   (receipt/terminal-tx-data
    {:seon.eval/id eval-id
     :seon.eval/status status})
   [(cond->
     {:seon.eval/id eval-id
      :seon.eval/result-edn (pr-str value)
      :seon.eval/duration-ms (:seon.eval/duration-ms record)
      :seon.eval/fn-entries (:seon.eval/fn-entries record)
      :seon.eval/allocated-bytes (:seon.eval/allocated-bytes record)}
      (= :error status)
      (assoc :seon.eval/error
             (or (:seon.error/message value)
                 "Evaluation failed.")))]))

(defn- transaction-error-data
  [eval-id exception]
  (into
   (receipt/terminal-tx-data
    {:seon.eval/id eval-id
     :seon.eval/status :error})
   [{:seon.eval/id eval-id
     :seon.eval/error "The evaluated value was not admitted."
     :seon.eval/error-data
     (pr-str
      {:seon.error/kind :transaction
       :seon.error/message (or (ex-message exception)
                               (.getName (class exception)))})}]))

(defn- commit!
  [transact! tx-data]
  (let [result (transact! tx-data)]
    (if (:seon.error/message result)
      (throw
       (ex-info (:seon.error/message result)
                {:seon.error/value result}))
      result)))

(defn execute-form!
  "Execute one already-committed form.

   `transact!` is the sole writer operation. A rejected result transaction is
   followed by a terminal error-receipt transaction containing no agent value,
   so a bad value cannot wedge the run or be retried forever."
  {:malli/schema
   [:=> [:catn [::transact! 'fn?]
                [::evaluate! 'fn?]
                [::admit-value 'fn?]
                [::request ::form-request]]
    :map]}
  [transact! evaluate! admit-value
   {agent-id :seon.agent/id
    run-id :seon.agent.run/id
    claim-epoch :seon.agent.run/claim-epoch
    turn-id :seon.agent.turn/id
    at :seon.eval/at
    ordinal :seon.eval/ordinal
    total :seon.eval/total
    source :seon.eval/source
    eval-ns :seon.eval/ns
    time-limit-ms :seon.sci.interrupt/time-limit-ms
    :as request}]
  (let [eval-id (receipt/receipt-id run-id ordinal claim-epoch)
        fence (run/run-fence agent-id run-id claim-epoch)
        running-report
        (commit!
         transact!
         (into
          fence
          (receipt/start-tx-data
           {:seon.agent.turn/id turn-id
            :seon.agent.run/id run-id
            :seon.eval/at at
            :seon.eval/ordinal ordinal
            :seon.eval/total total
            :seon.eval/claim-epoch claim-epoch
            :seon.eval/source source
            :seon.eval/narration ""
            :seon.eval/ns eval-ns})))]
    (try
      (let [evaluation
            (evaluate!
             {:seon.sci.eval/source source
              :seon.sci.interrupt/time-limit-ms time-limit-ms})
            value (::sci.eval/value evaluation)
            record (::sci.eval/record evaluation)
            status (if (sci.eval/error? value) :error :done)
            admitted (if (= :done status)
                       (admit-value request value)
                       [])
            terminal-report
            (commit!
             transact!
             (into fence
                   (concat
                    (terminal-receipt-data eval-id status value record)
                    admitted)))]
        {:seon.agent.driver/running-report running-report
         :seon.agent.driver/terminal-report terminal-report
         :seon.eval/id eval-id
         :seon.eval/status status
         :seon.sci.eval/value value
         :seon.sci.eval/record record})
      (catch Throwable exception
        (let [error-report
              (commit!
               transact!
               (into fence
                     (transaction-error-data eval-id exception)))]
          {:seon.agent.driver/running-report running-report
           :seon.agent.driver/terminal-report error-report
           :seon.eval/id eval-id
           :seon.eval/status :error
           :seon.sci.eval/value
           {:seon.error/kind :transaction
            :seon.error/message "The evaluated value was not admitted."}
           :seon.sci.eval/record {}})))))

(defn evaluate!
  "The production SCI evaluation operation used by the synchronous driver."
  {:malli/schema [:=> [:cat ::sci.eval/evaluate-request]
                  ::sci.eval/evaluation]}
  [request]
  (sci.eval/evaluate request))
