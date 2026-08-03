(ns seon.effect
  "The one system-side owner for declared capability requests.

  A request identity is `[run-id form-ordinal effect-ordinal]`. Its receipt is
  committed before the protected JVM handler runs on the process-root `:io`
  executor; terminal data is bounded and committed once. Recovery interrupts
  an open receipt and never dispatches it again."
  (:require [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.admit :as admit]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.util Date]
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
      [[:db/add (:db/id receipt) :seon.effect/result-edn
        (:seon.effect/result-edn request)]
       [:db/add (:db/id receipt) :seon.effect/settled-at
        (:seon.effect/settled-at request)]])))

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
      [[:db/add (:db/id receipt) :seon.effect/interrupted-at
        (:seon.effect/interrupted-at request)]])))

(defn interruption-stamps
  "Transaction data interrupting every open receipt for `run-eid`."
  {:malli/schema [:=> [:cat :seon.db/database-value :int :inst]
                  [:vector :seon.schema/value]]}
  [database run-eid now]
  (into []
        (map (fn [receipt-eid]
               [:db/add receipt-eid :seon.effect/interrupted-at now]))
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

(defn request!
  "Validate, record, dispatch, bound, and settle one capability request."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value]
                  :seon.schema/value]}
  [owner request]
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
          (flat-error :seon.effect/invalid-request
                      "The capability request does not satisfy its owner contract."
                      {:seon.fn/sym (str owner-sym)})

          :else
          (let [projected-request (admitted-value request)]
            (if (:seon.sci.admit/capped? projected-request)
              (flat-error :seon.effect/request-too-large
                          "The capability request exceeds the configured value bounds."
                          {:seon.fn/sym (str owner-sym)})
              (let [effect-id
                    (pr-str [(:seon.cluster.run/id *context*)
                             (:seon.cluster.run.form/ordinal *context*)
                             effect-ordinal])
                    opened
                    (db/transact!
                     connection
                     [[:db.fn/call #'open-call
                       {:seon.effect/id effect-id
                        :seon.effect/run
                        [:seon.cluster.run/id
                         (:seon.cluster.run/id *context*)]
                        :seon.effect/owner
                        [:seon.fn/sym (str owner-sym)]
                        :seon.effect/form-ordinal
                        (:seon.cluster.run.form/ordinal *context*)
                        :seon.effect/ordinal effect-ordinal
                        :seon.effect/request-edn
                        (admit/canonical-edn
                         (:seon.sci.admit/value projected-request))
                        :seon.effect/opened-at (Date.)}]])]
                (if (:seon.error/kind opened)
                  opened
                  (let [effective
                        (config/effective
                         @connection (:seon.boot/cluster-name *context*))
                        handler-outcome
                        (try
                          {:seon.effect/value
                           (dispatch
                            handler
                            (:seon.sci.admit/value projected-request)
                            effective)}
                          (catch InterruptedException _
                            {:seon.effect/value
                             (flat-error :seon.effect/interrupted
                                         "The effect handler was interrupted."
                                         {:seon.effect/id effect-id})
                             :seon.effect/interrupted-at (Date.)})
                          (catch ExecutionException _
                            {:seon.effect/value
                             (flat-error :seon.effect/handler-failed
                                         "The capability handler failed."
                                         {:seon.fn/sym (str owner-sym)})})
                          (catch Throwable _
                            {:seon.effect/value
                             (flat-error :seon.effect/handler-failed
                                         "The capability handler failed."
                                         {:seon.fn/sym (str owner-sym)})}))
                        admitted-result
                        (admitted-value (:seon.effect/value handler-outcome))
                        result (:seon.sci.admit/value admitted-result)
                        terminal
                        (if-let [interrupted-at
                                 (:seon.effect/interrupted-at handler-outcome)]
                          (db/transact!
                           connection
                           [[:db.fn/call #'interrupt-call
                             {:seon.effect/id effect-id
                              :seon.effect/interrupted-at interrupted-at}]])
                          (db/transact!
                           connection
                           [[:db.fn/call #'settle-call
                             {:seon.effect/id effect-id
                              :seon.effect/result-edn
                              (admit/canonical-edn result)
                              :seon.effect/settled-at (Date.)}]]))]
                    (if (:seon.error/kind terminal) terminal result)))))))))))
