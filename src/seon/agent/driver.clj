(ns seon.agent.driver
  "Synchronous, database-driven execution of one run's committed forms.

   A reply becomes one ordered form plan in one fenced transaction. Each form
   then follows the same durable fold:

   running receipt -> SCI evaluation -> terminal receipt plus admitted values.

   This namespace contains no process loop and opens no database connection.
   Its caller supplies the one writer operation and immutable database values."
  (:require [seon.ai.core :as ai]
            [seon.agent.lifecycle :as lifecycle]
            [seon.agent.message :as message]
            [seon.agent.run.core :as run]
            [seon.config.resolve :as config.resolve]
            [seon.content-hash :as content-hash]
            [seon.db.host :as db.host]
            [seon.eval.receipt :as receipt]
            [seon.repl.parse :as repl.parse]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [taoensso.timbre :as log])
  (:import [java.lang ProcessHandle]
           [java.util Date]
           [java.util.concurrent.atomic AtomicBoolean]))

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
    turn-id :seon.agent.turn/id
    ordinal :seon.eval/ordinal
    at :seon.eval/at}
   value]
  (case (::lifecycle/disposition value)
    :completed
    (let [result (::lifecycle/result value)]
      (into
       (run/finish-tx-data agent-id run-id claim-epoch :completed at)
       [{:seon.agent.turn/id turn-id
         :seon.agent.turn/status :done}
        {:seon.agent.run/id run-id
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

(def ^:private pending-message-query
  '[:find ?message-id ?agent-id ?content ?at
    :where
    [?message :seon.agent.message/id ?message-id]
    [?message :seon.agent.message/origin :human]
    [?message :seon.agent.message/to ?agent]
    [?agent :seon.agent/id ?agent-id]
    [?message :seon.agent.message/content ?content]
    [?message :seon.agent.message/at ?at]
    (not [?run :seon.agent.run/cause ?message])])

(defn- compact-id [prefix value]
  (str prefix (subs (content-hash/sha-256 value) 0 11)))

(defn- transact! [database-functions tx-data]
  ((get database-functions 'transact!)
   {:seon.db/tx-data (vec tx-data)}))

(defn- pending-messages [database-functions]
  ((get database-functions 'query)
   {:seon.db/query pending-message-query
    :datahike.resource/max-work 100000
    :datahike.resource/max-results 64
    :datahike.resource/max-result-weight 131072}))

(defn- pull-one [database-functions pattern ref]
  ((get database-functions 'pull)
   {:seon.db/pull-pattern pattern
    :seon.db/ref ref
    :datahike.resource/max-work 100000
    :datahike.resource/max-results 2048
    :datahike.resource/max-result-weight 262144}))

(defn open-run-tx-data
  "Build the idempotent run row and its atomic idle-agent attachment."
  [process-id message-id agent-id at lease-until]
  (let [run-id (compact-id "r" message-id)
        run-ref [:seon.agent.run/id run-id]]
    {:seon.agent.driver/create-tx-data
     [{:seon.agent.run/id run-id
       :seon.agent.run/agent [:seon.agent/id agent-id]
       :seon.agent.run/started-at at
       :seon.agent.run/status :open}]
     :seon.agent.driver/attach-tx-data
     [[:db.fn/cas [:seon.agent/id agent-id] :seon.agent/run nil run-ref]
      {:seon.agent.run/id run-id
       :seon.agent.run/cause [:seon.agent.message/id message-id]
       :seon.agent.run/process process-id
       :seon.agent.run/claim-epoch 1
       :seon.agent.run/lease-until lease-until}]}))

(defn- open-run!
  [database-functions process-id message-id agent-id at lease-until]
  (let [run-id (compact-id "r" message-id)
        transactions
        (open-run-tx-data process-id message-id agent-id at lease-until)
        created
        (transact!
         database-functions
         (:seon.agent.driver/create-tx-data transactions))]
    (when-not (:seon.error/message created)
      (let [attached
            (transact!
             database-functions
             (:seon.agent.driver/attach-tx-data transactions))]
        (when-not (:seon.error/message attached)
          {:seon.agent.run/id run-id
           :seon.agent.run/claim-epoch 1})))))

(defn- model-request
  [database-functions agent-id content]
  (let [configuration
        (pull-one database-functions
                  (ai/config-pull-pattern)
                  config.resolve/cluster-config-lookup-ref)
        agent
        (pull-one database-functions
                  (ai/agent-config-pull-pattern)
                  [:seon.agent/id agent-id])
        attempt-time-limit-ms
        (or (:seon.ai/agent-attempt-timeout-ms agent) 60000)]
    {:seon.ai/system-prompt
     (str "Return only Clojure forms. Finish with "
          "(seon.agent.lifecycle/complete \"your concise reply\").")
     :seon.ai/ctx content
     :seon.ai/stream? false
     :seon.ai/reply-evaluation :batch
     :seon.ai/request-timeout-ms attempt-time-limit-ms
     :seon.ai/config-resolution
     (ai/resolved-config-from-rows
      ai/shipped-defaults configuration agent attempt-time-limit-ms)}))

(defn- reply-sources [reply]
  (let [program
        (repl.parse/parse-program
         reply
         {:seon.repl/current-ns 'user
          :seon.repl/strip-fences? true})]
    (when (empty? (:seon.repl/errors program))
      (mapv :seon.repl/source (:seon.repl/eval-entries program)))))

(defn- close-error!
  [database-functions agent-id run-id claim-epoch turn-id at message]
  (transact!
   database-functions
   (into
    (run/finish-tx-data agent-id run-id claim-epoch :error at)
    [{:seon.agent.turn/id turn-id
      :seon.agent.turn/status :error
      :seon.agent.turn/error message}])))

(defn- process-message!
  [database-functions llm-transport! process-id
   [message-id agent-id content at]]
  (let [now (Date.)
        lease-until (Date. (+ (.getTime now) 120000))]
    (when-let [{run-id :seon.agent.run/id
                claim-epoch :seon.agent.run/claim-epoch}
               (open-run! database-functions process-id message-id agent-id
                          at lease-until)]
      (let [turn-id (compact-id "t" run-id)
            turn-ref [:seon.agent.turn/id turn-id]
            _ (transact!
               database-functions
               (into
                (run/run-fence agent-id run-id claim-epoch)
                [{:seon.agent.turn/id turn-id
                  :seon.agent.turn/run [:seon.agent.run/id run-id]
                  :seon.agent.turn/at now
                  :seon.agent.turn/status :running}]))
            response (llm-transport!
                      (model-request database-functions agent-id content))
            reply (:seon.ai/text response)
            sources (when (string? reply) (reply-sources reply))]
        (if-not (seq sources)
          (close-error!
           database-functions agent-id run-id claim-epoch turn-id (Date.)
           (or (get-in response [:seon.ai/error :seon.ai/msg])
               "The model reply contained no executable forms."))
          (do
            (transact!
             database-functions
             (plan-tx-data
              {:seon.agent/id agent-id
               :seon.agent.run/id run-id
               :seon.agent.run/claim-epoch claim-epoch
               :seon.agent.run/plan-digest
               (content-hash/sha-256 (pr-str sources))
               ::sources sources}))
            (loop [ordinal 0]
              (when (< ordinal (count sources))
                (let [result
                      (execute-form!
                       #(transact! database-functions %)
                       evaluate!
                       lifecycle-tx-data
                       {:seon.agent/id agent-id
                        :seon.agent.run/id run-id
                        :seon.agent.run/claim-epoch claim-epoch
                        :seon.agent.turn/id turn-id
                        :seon.eval/at (Date.)
                        :seon.eval/ordinal ordinal
                        :seon.eval/total (count sources)
                        :seon.eval/source (nth sources ordinal)
                        :seon.eval/ns 'user
                        :seon.sci.interrupt/time-limit-ms 60000})]
                  (when (and (not= :error (:seon.eval/status result))
                             (not= :completed
                                   (::lifecycle/disposition
                                    (:seon.sci.eval/value result))))
                    (recur (inc ordinal))))))))))))

(defn start!
  "Start the database-interest-driven JVM run driver."
  [writer database-functions llm-transport!]
  (let [scanning? (AtomicBoolean. false)
        process-id (str "host-" (.pid (ProcessHandle/current)))
        scan-body!
        (fn []
          (doseq [message (pending-messages database-functions)]
            (Thread/startVirtualThread
             (fn []
               (try
                 (process-message! database-functions llm-transport!
                                   process-id message)
                 (catch Throwable throwable
                   (log/error throwable
                              "JVM run driver message processing failed"
                              {:seon.agent.message/id (first message)})))))))
        scan!
        (fn scan! []
          (when (.compareAndSet scanning? false true)
            (Thread/startVirtualThread
             (fn []
               (try
                 (scan-body!)
                 (catch Throwable throwable
                   (log/error throwable "JVM run driver scan failed"))
                 (finally
                   (.set scanning? false)))))))]
    (let [listener
          (db.host/listen!
           writer
            {:seon.db/key ::messages
            :seon.db/datom-patterns
            [{:seon.db/a :seon.agent.message/to}
             {:seon.db/a :seon.agent.run/lease-until}]
            :seon.db/handler (fn [_] (scan!))})]
      (scan-body!)
      listener)))
