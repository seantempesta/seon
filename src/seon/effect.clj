(ns seon.effect
  "The one system-side owner for declared capability requests.

  A request identity is `[run-id form-ordinal effect-ordinal]`. Its receipt is
  committed before the protected JVM handler runs on the process-root `:io`
  executor; terminal data is bounded and committed once. Recovery interrupts
  an open receipt and never dispatches it again."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.sci.admit :as admit]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.util Date]
           [java.util.concurrent ExecutionException FutureTask]
           [java.util.concurrent Executor]))

(def ^:dynamic *context*
  "The current evaluation's durable identity and projection controls."
  nil)

(schema.edn/load! {})

(def ^:private reach-rules
  '[[(reachable ?function ?target)
     [?function :seon.fn/calls ?target]]
    [(reachable ?function ?target)
     [?function :seon.fn/calls ?called]
     (reachable ?called ?target)]])

(defn capabilities
  "Query capability-owner symbols reachable from `function-symbol`."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol]
                  [:set :seon.fn/sym]]}
  [database function-symbol]
  (let [root (db/pull database
                      [:db/id :seon.fn/sym :seon.effect/capability]
                      [:seon.fn/sym (str function-symbol)])
        reached
        (db/q '[:find [?owner-symbol ...]
                :in $ % ?root
                :where
                (reachable ?root ?owner)
                [?owner :seon.effect/capability]
                [?owner :seon.fn/sym ?owner-symbol]]
              database reach-rules (:db/id root))]
    (cond-> (set reached)
      (:seon.effect/capability root)
      (conj (:seon.fn/sym root)))))

(defn- flat-error
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- owner-symbol
  [owner]
  (cond
    (var? owner)
    (let [owner-meta (meta owner)]
      (symbol (str (ns-name (:ns owner-meta)))
              (str (:name owner-meta))))

    (sci.utils/var? owner)
    (sci/var->symbol owner)

    :else nil))

(defn- accepts-request?
  [database owner-sym request]
  (schema/function-accepts-in?
   (schema/projection-from-database database)
   owner-sym
   [request]))

(defn- admitted-value
  [value]
  (admit/admit-value
   {:seon.sci.admit/value value
    :seon.sci.admit/interrupt-fn (constantly nil)
    :seon.sci.admit/caps (:seon.sci.admit/caps *context*)
    :seon.config/on-core-error (:seon.config/on-core-error *context*)}))

(defn open-call
  "Open one never-before-recorded effect identity inside the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.effect/open-request]
                  [:vector :seon.schema/value]]}
  [database request]
  (if (db/pull database [:db/id] [:seon.effect/id (:seon.effect/id request)])
    (throw
     (ex-info
      "This effect identity was already recorded and will not be dispatched again."
      {:seon.error/kind :seon.effect/already-recorded
       :seon.error/message
       "This effect request was already recorded and was not dispatched again."
       :seon.error/data {:seon.effect/id (:seon.effect/id request)}}))
    [request]))

(defn settle-call
  "Settle one open effect receipt exactly once inside the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.effect/settle-request]
                  [:vector :seon.schema/value]]}
  [database request]
  (let [receipt (db/pull database '[*]
                         [:seon.effect/id (:seon.effect/id request)])]
    (cond
      (nil? receipt)
      (throw
       (ex-info "The effect receipt does not exist."
                {:seon.error/kind :seon.effect/missing-receipt}))

      (or (:seon.effect/result-edn receipt)
          (:seon.effect/interrupted-at receipt))
      (throw
       (ex-info "The effect receipt is already terminal."
                {:seon.error/kind :seon.effect/already-settled}))

      :else
      (cond->
       [[:db/add (:db/id receipt) :seon.effect/result-edn
         (:seon.effect/result-edn request)]
        [:db/add (:db/id receipt) :seon.effect/result-size
         (:seon.effect/result-size request)]
        [:db/add (:db/id receipt) :seon.effect/duration-ms
         (:seon.effect/duration-ms request)]
        [:db/add (:db/id receipt) :seon.effect/settled-at
         (:seon.effect/settled-at request)]]
        (:seon.effect/result-blob request)
        (conj [:db/add (:db/id receipt) :seon.effect/result-blob
               (:seon.effect/result-blob request)])

        (:seon.effect/notify receipt)
        (into [[:db/retract (:db/id receipt) :seon.effect/notify
                (:db/id (:seon.effect/notify receipt))]
               [:db/add (:db/id receipt) :seon.effect/to
                (:db/id (:seon.effect/notify receipt))]])))))

(defn interrupt-call
  "Mark one open effect receipt interrupted exactly once inside the writer."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:map
          [:seon.effect/id :seon.effect/id]
          [:seon.effect/interrupted-at :seon.effect/interrupted-at]]]
    [:vector :seon.schema/value]]}
  [database request]
  (let [receipt (db/pull database '[*]
                         [:seon.effect/id (:seon.effect/id request)])]
    (cond
      (nil? receipt)
      (throw
       (ex-info "The effect receipt does not exist."
                {:seon.error/kind :seon.effect/missing-receipt}))

      (or (:seon.effect/result-edn receipt)
          (:seon.effect/interrupted-at receipt))
      (throw
       (ex-info "The effect receipt is already terminal."
                {:seon.error/kind :seon.effect/already-settled}))

      :else
      (cond->
       [[:db/add (:db/id receipt) :seon.effect/interrupted-at
         (:seon.effect/interrupted-at request)]]
        (:seon.effect/notify receipt)
        (into [[:db/retract (:db/id receipt) :seon.effect/notify
                (:db/id (:seon.effect/notify receipt))]
               [:db/add (:db/id receipt) :seon.effect/to
                (:db/id (:seon.effect/notify receipt))]])))))

(defn interruption-stamps
  "Transaction data interrupting every open receipt for `run-eid`."
  {:malli/schema [:=> [:cat :seon.db/database-value :int :inst]
                  [:vector :seon.schema/value]]}
  [database run-eid now]
  (into []
        (mapcat
         (fn [receipt-eid]
           (let [notify-eid
                 (some-> (db/pull database
                                  [{:seon.effect/notify [:db/id]}]
                                  receipt-eid)
                         :seon.effect/notify :db/id)]
             (cond-> [[:db/add receipt-eid :seon.effect/interrupted-at now]]
               notify-eid
               (into [[:db/retract receipt-eid :seon.effect/notify notify-eid]
                      [:db/add receipt-eid :seon.effect/to notify-eid]])))))
        (db/q '[:find [?receipt ...]
                :in $ ?run
                :where
                [?receipt :seon.effect/run ?run]
                (not [?receipt :seon.effect/result-edn])
                (not [?receipt :seon.effect/interrupted-at])]
              database run-eid)))

(defn- dispatch
  [handler request effective]
  (let [executor (:io ((requiring-resolve
                        'seon.operator.runtime/root-executors)))
        task (FutureTask. ^java.util.concurrent.Callable
                          (bound-fn [] (handler request effective)))]
    (.execute ^Executor executor task)
    (.get task)))

(def ^:private byte-array-class (class (byte-array 0)))

(defn- stored-result
  [connection threshold raw-value admitted-result]
  (let [result-edn (admit/canonical-edn admitted-result)
        octets
        (if (instance? byte-array-class raw-value)
          raw-value
          (.getBytes ^String result-edn StandardCharsets/UTF_8))
        blob-backed?
        (or (instance? byte-array-class raw-value)
            (and threshold (> (alength ^bytes octets) threshold)))
        stored
        (when blob-backed?
          (blob/put-binary!
           connection (ByteArrayInputStream. ^bytes octets)))]
    (cond->
     {:seon.effect/result-edn result-edn
      :seon.effect/result-size (alength ^bytes octets)}
      stored
      (assoc :seon.effect/result-blob (:seon.blob/digest stored)))))

(defn- settle-value!
  [connection effect-id opened-at threshold raw-value]
  (let [admitted-result (admitted-value raw-value)
        result (:seon.sci.admit/value admitted-result)
        settled-at (Date.)
        request
        (merge
         {:seon.effect/id effect-id
          :seon.effect/settled-at settled-at
          :seon.effect/duration-ms
          (max 0 (- (.getTime settled-at) (.getTime opened-at)))}
         (stored-result connection threshold raw-value result))]
    {:seon.effect/value result
     :seon.effect/transaction
     (db/transact!
      connection
      [[:db.fn/call #'settle-call request]])}))

(defn- interrupt!
  [connection effect-id]
  (let [interrupted-at (Date.)]
    {:seon.effect/value
     (flat-error :seon.effect/interrupted
                 "The effect handler was interrupted."
                 {:seon.effect/id effect-id})
     :seon.effect/transaction
     (db/transact!
      connection
      [[:db.fn/call #'interrupt-call
        {:seon.effect/id effect-id
         :seon.effect/interrupted-at interrupted-at}]])}))

(defn- handler-failure
  [owner-sym]
  (flat-error :seon.effect/handler-failed
              "The capability handler failed."
              {:seon.fn/sym (str owner-sym)}))

(defn- settle-background-terminal!
  [connection effect-id owner-sym opened-at threshold terminal]
  (if-let [throwable (::flow/throwable terminal)]
    (if (instance? InterruptedException throwable)
      (interrupt! connection effect-id)
      (settle-value! connection effect-id opened-at threshold
                     (handler-failure owner-sym)))
    (settle-value! connection effect-id opened-at threshold
                   (::flow/value terminal))))

(defn- request*
  [owner request execution]
  (let [owner-sym (owner-symbol owner)]
     (cond
       (nil? *context*)
       (flat-error :seon.effect/no-evaluation-context
                   "Capability requests require a current run form."
                   {})

       (nil? owner-sym)
       (flat-error :seon.effect/invalid-owner
                   "Capability requests must pass their own Var."
                   {})

       :else
       (let [connection (:seon.store/branch-connection *context*)
             effect-ordinal (swap! (:seon.effect/counter *context*) inc)
             database @connection
             owner-row
             (db/pull database
                      [:db/id :seon.fn/sym :seon.fn/spec
                       :seon.effect/capability]
                      [:seon.fn/sym (str owner-sym)])
             handler-symbol (:seon.effect/capability owner-row)
             handler (some-> handler-symbol requiring-resolve deref)]
         (cond
           (nil? handler-symbol)
           (flat-error
            :seon.effect/undeclared-owner
            "Declare :seon.effect/capability on the capability owner."
            {:seon.fn/sym (str owner-sym)})

           (nil? handler)
           (flat-error
            :seon.effect/unavailable-handler
            "The declared capability handler is unavailable."
            {:seon.fn/sym (str owner-sym)})

           (not (accepts-request? database owner-sym request))
           (flat-error
            :seon.effect/invalid-request
            "The capability request does not satisfy its owner contract."
            {:seon.fn/sym (str owner-sym)})

           :else
           (let [projected-request (admitted-value request)]
             (if (:seon.sci.admit/capped? projected-request)
               (flat-error
                :seon.effect/request-too-large
                "The capability request exceeds the configured value bounds."
                {:seon.fn/sym (str owner-sym)})
               (let [background? (:seon.effect/background? execution)
                     effect-id
                     (pr-str [(:seon.cluster.run/id *context*)
                              (:seon.cluster.run.form/ordinal *context*)
                              effect-ordinal])
                     result-ref [:seon.effect/id effect-id]
                     opened-at (Date.)
                     open-request
                     (cond->
                      {:seon.effect/id effect-id
                       :seon.effect/run
                       [:seon.cluster.run/id
                        (:seon.cluster.run/id *context*)]
                       :seon.effect/owner [:seon.fn/sym (str owner-sym)]
                       :seon.effect/form-ordinal
                       (:seon.cluster.run.form/ordinal *context*)
                       :seon.effect/ordinal effect-ordinal
                       :seon.effect/request-edn
                       (admit/canonical-edn
                        (:seon.sci.admit/value projected-request))
                       :seon.effect/opened-at opened-at}
                       background?
                       (assoc :seon.effect/notify
                              [:seon.cluster.agent/id
                               (:seon.cluster.agent/id *context*)]))
                     opened
                     (db/transact!
                      connection
                      [[:db.fn/call #'open-call open-request]])]
                 (if (:seon.error/kind opened)
                   opened
                   (let [effective
                         (config/effective
                          @connection (:seon.boot/cluster-name *context*))
                         threshold
                         (:seon.config.eval.result/blob-threshold effective)]
                     (if background?
                       (do
                         (flow/submit!
                          (:seon.flow/work-launcher *context*)
                          {::flow/submission-id effect-id
                           ::flow/workload :io
                           ::flow/work-fn
                           (fn [_]
                             (handler
                              (:seon.sci.admit/value projected-request)
                              effective))
                           ::flow/complete!
                           (fn [terminal]
                             (settle-background-terminal!
                              connection effect-id owner-sym opened-at threshold
                              terminal))})
                         result-ref)
                       (let [outcome
                             (try
                               (settle-value!
                                connection effect-id opened-at threshold
                                (dispatch
                                 handler
                                 (:seon.sci.admit/value projected-request)
                                 effective))
                               (catch InterruptedException _
                                 (interrupt! connection effect-id))
                               (catch ExecutionException _
                                 (settle-value!
                                  connection effect-id opened-at threshold
                                  (handler-failure owner-sym)))
                               (catch Throwable _
                                 (settle-value!
                                  connection effect-id opened-at threshold
                                  (handler-failure owner-sym))))]
                         (if (:seon.error/kind
                              (:seon.effect/transaction outcome))
                           (:seon.effect/transaction outcome)
                           (:seon.effect/value outcome))))))))))))))

(defn request!
  "Validate, record, dispatch, bound, and settle one capability request."
  {:malli/schema
   [:function
    [:=> [:cat :seon.schema/value :seon.schema/value]
     :seon.schema/value]
    [:=> [:cat :seon.schema/value :seon.schema/value
          :seon.effect/execution-options]
     :seon.schema/value]]}
  ([owner request]
   (request* owner request {}))
  ([owner request execution]
   (request* owner request execution)))

(defn context-suffix
  "Render background and duration feedback for one agent after stable context."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
    :string]}
  [database agent-id]
  (let [agent-row
        (db/pull database
                 [:db/id {:seon.cluster.agent/run
                          [:seon.cluster.run/id
                           {:seon.cluster.run/background-results
                            [:seon.effect/id :seon.effect/result-edn
                             :seon.effect/result-blob
                             :seon.effect/result-size
                             :seon.effect/interrupted-at]}]}]
                 [:seon.cluster.agent/id agent-id])
        pending
        (->> (db/q
              '[:find ?id ?owner
                :in $ ?agent
                :where
                [?receipt :seon.effect/notify ?agent]
                [?receipt :seon.effect/id ?id]
                [?receipt :seon.effect/owner ?owner-eid]
                [?owner-eid :seon.fn/sym ?owner]]
              database (:db/id agent-row))
             (sort-by first))
        results
        (->> (get-in agent-row [:seon.cluster.agent/run
                            :seon.cluster.run/background-results])
             (sort-by :seon.effect/id))
        threshold
        (db/q
         '[:find ?threshold .
           :where
           [_ :seon.config.effect/long-call-ms ?threshold]]
         database)
        durations
        (when threshold
          (->> (db/q
                '[:find ?id ?duration ?owner
                  :in $ ?agent ?threshold
                  :where
                  [?run :seon.cluster.run/agent ?agent]
                  [?receipt :seon.effect/run ?run]
                  [?receipt :seon.effect/id ?id]
                  [?receipt :seon.effect/duration-ms ?duration]
                  [(>= ?duration ?threshold)]
                  [?receipt :seon.effect/owner ?owner-eid]
                  [?owner-eid :seon.fn/sym ?owner]
                  (not [?receipt :seon.effect/to])]
                database (:db/id agent-row) threshold)
               (sort-by first)))]
    (str/join
     "\n"
     (concat
      [";; Background work: use (my.background/await result-ref note) as the last form to wait, or retain the ref and keep working."]
      (map (fn [[id owner]]
             (str ";; background pending " id " · " owner))
           pending)
      (map (fn [result]
             (str ";; background result " (:seon.effect/id result) " · "
                  (or (:seon.effect/result-edn result)
                      (str "interrupted at "
                           (:seon.effect/interrupted-at result)))))
           results)
      (map (fn [[id duration owner]]
             (str ";; foreground effect " owner " took " duration
                  "ms · consider my.background/background next time · " id))
           durations)))))
