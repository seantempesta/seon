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
            [seon.db :as db]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.eval.receipt :as receipt]
            [seon.repl.parse :as repl.parse]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [taoensso.timbre :as log])
  (:import [java.lang ProcessHandle]
           [java.util Date]
           [java.util.concurrent.atomic AtomicBoolean]))

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
        {:seon.agent.message/id "seon.agent.driver/message"
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
      :seon.eval/duration-ms (:seon.eval/duration-ms record)}
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

(def ^:dynamic *nano-time*
  "Monotonic JVM clock, replaceable only by deterministic tests."
  #(System/nanoTime))

(defn- elapsed-ns
  [started-ns]
  (- (*nano-time*) started-ns))

(defn- timed
  [f]
  (let [started-ns (*nano-time*)
        value (f)]
    [value (elapsed-ns started-ns)]))

(defn- transaction-ref
  [report]
  (get-in report [:db-after :t]))

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
        [running-report running-duration-ns]
        (timed
         #(commit!
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
              :seon.eval/ns eval-ns}))))]
    (try
      (let [[evaluation eval-duration-ns]
            (timed
             #(evaluate!
               {:seon.sci.eval/source source
                :seon.sci.interrupt/time-limit-ms time-limit-ms}))
            value (::sci.eval/value evaluation)
            record (::sci.eval/record evaluation)
            status (if (sci.eval/error? value) :error :done)
            admitted (if (= :done status)
                       (admit-value request value)
                       [])
            [terminal-report terminal-duration-ns]
            (timed
             #(commit!
               transact!
               (into fence
                     (concat
                      (terminal-receipt-data eval-id status value record)
                      admitted))))]
        {:seon.agent.driver/running-report running-report
         :seon.agent.driver/terminal-report terminal-report
         :seon.agent.driver/running-duration-ns running-duration-ns
         :seon.agent.driver/eval-duration-ns eval-duration-ns
         :seon.agent.driver/terminal-duration-ns terminal-duration-ns
         :seon.agent.driver/published?
         (= :completed (::lifecycle/disposition value))
         :seon.eval/id eval-id
         :seon.eval/status status
         :seon.sci.eval/value value
         :seon.sci.eval/record record})
      (catch Throwable exception
        (let [[error-report terminal-duration-ns]
              (timed
               #(commit!
                 transact!
                 (into fence
                       (transaction-error-data eval-id exception))))]
          {:seon.agent.driver/running-report running-report
           :seon.agent.driver/terminal-report error-report
           :seon.agent.driver/running-duration-ns running-duration-ns
           :seon.agent.driver/terminal-duration-ns terminal-duration-ns
           :seon.agent.driver/published? false
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

(defn- allocated-transact!
  [allocate! database-functions tx-data]
  (if-let [message
           (some #(when (and (map? %)
                             (:seon.agent.message/id %))
                    %)
                 tx-data)]
    (let [database ((get database-functions 'db))]
      (allocate!
       {::db/db database
        ::db.id/allocations
        [{::db.id/key :seon.agent.message/id
          ::db.id/identity-attr :seon.agent.message/id}]
        ::db.id/transaction-builder
        (fn [ids]
          {::db/tx-data
           (mapv
            #(if (identical? % message)
               (assoc % :seon.agent.message/id
                      (get ids :seon.agent.message/id))
               %)
            tx-data)})}))
    (transact! database-functions tx-data)))

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
  "Build one allocated run row and its idle-agent pointer CAS."
  [run-id process-id message-id agent-id at lease-until]
  (let [run-ref [:seon.agent.run/id run-id]]
    [{:seon.agent.run/id run-id
      :seon.agent.run/agent [:seon.agent/id agent-id]
      :seon.agent.run/cause [:seon.agent.message/id message-id]
      :seon.agent.run/started-at at
      :seon.agent.run/status :open
      :seon.agent.run/process process-id
      :seon.agent.run/claim-epoch 1
      :seon.agent.run/lease-until lease-until}
     [:db.fn/cas [:seon.agent/id agent-id] :seon.agent/run nil run-ref]]))

(defn- open-run!
  [allocate! database-functions process-id message-id agent-id at lease-until]
  (let [database ((get database-functions 'db))
        allocation
        (allocate!
         {::db/db database
          ::db.id/allocations
          [{::db.id/key :seon.agent.run/id
            ::db.id/identity-attr :seon.agent.run/id}]
          ::db.id/transaction-builder
          (fn [ids]
            {::db/tx-data
             (open-run-tx-data
              (get ids :seon.agent.run/id)
              process-id message-id agent-id at lease-until)})})
        run-id (get-in allocation [::db.id/ids :seon.agent.run/id])]
    (when-not (:seon.error/message allocation)
      {:seon.agent.run/id run-id
       :seon.agent.run/claim-epoch 1
       :seon.agent.driver/transaction
       (transaction-ref allocation)})))

(defn- open-turn!
  [allocate! database-functions agent-id run-id claim-epoch at]
  (let [database ((get database-functions 'db))
        allocation
        (allocate!
         {::db/db database
          ::db.id/allocations
          [{::db.id/key :seon.agent.turn/id
            ::db.id/identity-attr :seon.agent.turn/id}]
          ::db.id/transaction-builder
          (fn [ids]
            {::db/tx-data
             (into
              (run/run-fence agent-id run-id claim-epoch)
              [{:seon.agent.turn/id
                (get ids :seon.agent.turn/id)
                :seon.agent.turn/run [:seon.agent.run/id run-id]
                :seon.agent.turn/at at
                :seon.agent.turn/status :running}])})})]
    (when-not (:seon.error/message allocation)
      {:seon.agent.turn/id
       (get-in allocation [::db.id/ids :seon.agent.turn/id])
       :seon.agent.driver/transaction
       (transaction-ref allocation)})))

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

(defn- timing-row
  [name ordinal duration-ns transaction]
  (cond->
   {:seon.agent.turn.timing/name name
    :seon.agent.turn.timing/ordinal ordinal
    :seon.agent.turn.timing/duration-ns duration-ns}
    transaction
    (assoc :seon.agent.turn.timing/transaction transaction)))

(defn- persist-turn-timings!
  [database-functions turn-id total-duration-ns timings]
  (commit!
   #(transact! database-functions %)
   [{:seon.agent.turn/id turn-id
     :seon.agent.turn/duration-ns total-duration-ns
     :seon.agent.turn/timings (vec timings)}]))

(defn- drive-sources!
  [allocate! database-functions agent-id run-id claim-epoch turn-id
   sources initial-timings]
  (loop [ordinal 0
         timings initial-timings]
    (when (< ordinal (count sources))
      (let [result
            (execute-form!
             #(allocated-transact! allocate! database-functions %)
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
              :seon.sci.interrupt/time-limit-ms 60000})
            eval-duration-ns (:seon.agent.driver/eval-duration-ns result)
            terminal-name
            (if (:seon.agent.driver/published? result)
              :publish-transaction-call
              :eval-terminal-transaction-call)
            next-timings
            (cond->
             (conj
              timings
              (timing-row
               :eval-admission-transaction-call ordinal
               (:seon.agent.driver/running-duration-ns result)
               (transaction-ref
                (:seon.agent.driver/running-report result))))
              eval-duration-ns
              (conj
               (timing-row :eval ordinal eval-duration-ns nil))
              true
              (conj
               (timing-row
                terminal-name ordinal
                (:seon.agent.driver/terminal-duration-ns result)
                (transaction-ref
                 (:seon.agent.driver/terminal-report result)))))
            continue?
            (and
             (not= :error (:seon.eval/status result))
             (not= :completed
                   (::lifecycle/disposition
                    (:seon.sci.eval/value result))))]
        (if continue?
          (recur (inc ordinal) next-timings)
          {:seon.agent.driver/result result
           :seon.agent.driver/timings next-timings})))))

(defn- process-message!
  [allocate! database-functions llm-transport! process-id
   [message-id agent-id content at _committed-at]]
  (let [driver-started-ns (*nano-time*)
        entered-at (Date.)
        lease-until (Date. (+ (.getTime entered-at) 120000))
        [opened-run run-duration-ns]
        (timed
         #(open-run! allocate! database-functions process-id message-id
                     agent-id at lease-until))]
    (when-let [{run-id :seon.agent.run/id
                claim-epoch :seon.agent.run/claim-epoch
                run-transaction :seon.agent.driver/transaction}
               opened-run]
      (let [[opened-turn turn-duration-ns]
            (timed
             #(open-turn! allocate! database-functions agent-id run-id
                          claim-epoch entered-at))
            turn-id (:seon.agent.turn/id opened-turn)
            turn-transaction (:seon.agent.driver/transaction opened-turn)
            [request context-duration-ns]
            (timed #(model-request database-functions agent-id content))
            [response model-envelope-duration-ns]
            (timed #(llm-transport! request))
            provider-duration-ns (:seon.ai/provider-duration-ns response)
            reply (:seon.ai/text response)
            [sources reply-duration-ns]
            (timed #(when (string? reply) (reply-sources reply)))]
        (if-not (seq sources)
          (close-error!
           database-functions agent-id run-id claim-epoch turn-id (Date.)
           (or (get-in response [:seon.ai/error :seon.ai/msg])
               "The model reply contained no executable forms."))
          (let [[plan-report plan-duration-ns]
                (timed
                 #(transact!
                   database-functions
                   (plan-tx-data
                    {:seon.agent/id agent-id
                     :seon.agent.run/id run-id
                     :seon.agent.run/claim-epoch claim-epoch
                     :seon.agent.run/plan-digest
                     (content-hash/sha-256 (pr-str sources))
                     ::sources sources})))]
            (if (run/error-value? plan-report)
              (do
                (let [close-report
                      (close-error!
                       database-functions agent-id run-id claim-epoch turn-id
                       (Date.) (:seon.error/message plan-report))]
                  (if (run/error-value? close-report)
                    close-report
                    plan-report)))
              (let [initial-timings
                    (cond->
                     [(timing-row :run-admission-transaction-call 0
                                  run-duration-ns run-transaction)
                      (timing-row :turn-transaction-call 0
                                  turn-duration-ns turn-transaction)
                      (timing-row :context-derivation 0
                                  context-duration-ns nil)]
                      provider-duration-ns
                      (conj
                       (timing-row :provider-request-response 0
                                   provider-duration-ns nil))
                      provider-duration-ns
                      (conj
                       (timing-row
                        :model-envelope-overhead 0
                        (- model-envelope-duration-ns provider-duration-ns)
                        nil))
                      true
                      (conj
                       (timing-row :reply-derivation 0
                                   reply-duration-ns nil))
                      true
                      (conj
                       (timing-row :plan-transaction-call 0
                                   plan-duration-ns
                                   (transaction-ref plan-report))))
                    driven
                    (drive-sources!
                     allocate! database-functions agent-id run-id claim-epoch
                     turn-id sources initial-timings)
                    result (:seon.agent.driver/result driven)
                    timings (:seon.agent.driver/timings driven)
                    total-duration-ns (elapsed-ns driver-started-ns)]
                (persist-turn-timings!
                 database-functions turn-id total-duration-ns timings)
                result))))))))

(defn start!
  "Start the database-interest-driven JVM run driver."
  [writer allocate! database-functions llm-transport!]
  (let [scanning? (AtomicBoolean. false)
        process-id (str "host-" (.pid (ProcessHandle/current)))
        scan-body!
        (fn []
          (doseq [message (pending-messages database-functions)]
            (Thread/startVirtualThread
             (fn []
               (try
                 (process-message! allocate! database-functions llm-transport!
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
