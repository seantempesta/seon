(ns seon.effect
  "The one system-side owner for declared capability requests.

  A request identity is `[run-id form-ordinal effect-ordinal]`. Its receipt is
  committed before the protected JVM handler runs on the process-root `:io`
  executor; terminal data is bounded and committed once. Recovery interrupts
  an open receipt and never dispatches it again."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.ai.tokens :as tokens]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.print :as print]
            [seon.sci.admit :as admit]
            [seon.sci.kernel :as kernel]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.util Date]
           [java.util.concurrent ExecutionException FutureTask]
           [java.util.concurrent Executor]))

(def ^:dynamic *request-context*
  "The current evaluation's durable identity and projection controls."
  nil)

(schema.edn/load! {})

(defn- ref-attribute
  [database ref attribute]
  (when (and database (:db/id ref))
    (get (db/pull database [attribute] (:db/id ref)) attribute)))

(defn- receipt-state
  [unit]
  (cond
    (contains? unit :seon.effect/interrupted-at) :interrupted
    (contains? unit :seon.effect/result-edn) :returned
    :else :pending))

(defn- payload-preview
  [payload]
  (let [width (:seon.print/width (print/default-options))
        preview-tokens (max 1 (quot width tokens/chars-per-token))]
    (tokens/clip-str payload preview-tokens)))

(defn- payload-face
  [label payload]
  (str label " (~" (tokens/estimate payload) " tokens): "
       (payload-preview payload)))

(defn- receipt-identities
  [unit]
  (let [database (:seon.db/db unit)]
    {:owner (ref-attribute database
                           (:seon.effect/owner unit)
                           :seon.fn/sym)
     :run (ref-attribute database
                         (:seon.effect/run unit)
                         :seon.cluster.run/id)}))

(defn render-ai
  "`:seon.render/ai` — one effect receipt, derived from terminal attributes."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [id (:seon.effect/id unit)]
    (let [{:keys [owner run]} (receipt-identities unit)
          state (receipt-state unit)
          request (:seon.effect/request-edn unit)
          result (:seon.effect/result-edn unit)
          identity-line
          (str "Effect " (or owner id) " · run " (or run "unknown")
               ", form " (:seon.effect/form-ordinal unit)
               ", effect " (:seon.effect/ordinal unit) " · "
               (case state
                 :returned (if-some [duration
                                     (:seon.effect/duration-ms unit)]
                             (str "returned in " duration " ms.")
                             "returned.")
                 :interrupted (str "interrupted at "
                                   (pr-str (:seon.effect/interrupted-at unit))
                                   ".")
                 :pending (str "pending since "
                               (pr-str (:seon.effect/opened-at unit)) ".")))]
      (str identity-line
           (when request (str "\n" (payload-face "Request" request)))
           (when result
             (str "\n" (payload-face "Result" result)
                  (when-let [digest (:seon.effect/result-blob unit)]
                    (str " · blob digest " digest))))))))

(defn render-html
  "`:seon.render/html` — one readable effect-receipt card."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [id (:seon.effect/id unit)]
    (let [{:keys [owner run]} (receipt-identities unit)
          state (receipt-state unit)
          request (:seon.effect/request-edn unit)
          result (:seon.effect/result-edn unit)]
      (into
       [:article {:class "seon-family-entry seon-effect-receipt-entry"}
        [:h3 (str "Effect " (or owner id))]
        (into
         [:dl
          [:div [:dt "Run"] [:dd (str (or run "Unknown"))]]
          [:div [:dt "Form / effect"]
           [:dd (str (:seon.effect/form-ordinal unit) " / "
                     (:seon.effect/ordinal unit))]]
          [:div [:dt "Disposition"] [:dd (name state)]]]
         (when-let [duration (and (= :returned state)
                                  (:seon.effect/duration-ms unit))]
           [[:div [:dt "Duration"] [:dd (str duration " ms")]]]))]
       (concat
        (when request
          [[:details {:class "seon-effect-request"}
            [:summary (str "Request · approximately "
                           (tokens/estimate request) " tokens")]
            [:code (payload-preview request)]]])
        (when result
          [[:details {:class "seon-effect-result"}
            [:summary (str "Result · approximately "
                           (tokens/estimate result) " tokens")]
            [:code (payload-preview result)]]])
        (when-let [digest (:seon.effect/result-blob unit)]
          [[:p {:class "seon-effect-blob"}
            "Blob digest " [:code digest]]]))))))

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

(defn- admission
  "The value-admission dials this request settles under, read ONCE on the
  requesting thread.

  A background request settles on whichever thread ran its work, so the
  dials travel as data with the settlement rather than as a binding frame
  the far side hopes it inherited."
  []
  {:seon.sci.admit/caps (:seon.sci.admit/caps *request-context*)
   :seon.config/on-core-error (:seon.config/on-core-error *request-context*)})

(defn- admitted-value
  [dials value]
  (admit/admit-value
   (assoc dials
          :seon.sci.admit/value value
          :seon.sci.admit/interrupt-fn (constantly nil))))

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

        (seq (:seon.effect/content-blobs request))
        (into (map (fn [content-digest]
                     [:db/add (:db/id receipt) :seon.effect/content-blobs
                      content-digest])
                   (:seon.effect/content-blobs request)))

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

(defn- dispatching-environment
  "This request's environment, carrying the requesting thread's interrupt arm.

  The door is a thread hop like every other one and obeys the same rule: the
  arm is captured HERE, on the thread that asked, and adopted where the
  handler actually runs. Without it a capability request executes unarmed —
  the interpreted entrances it makes are attributed to nothing, so the
  evaluation's `:seon.eval/fn-entries` under-reports what its own request
  did, and `interrupt!` cannot reach the handler's thread at all. An unarmed
  requester carries no arm, which is ordinary system-side work and never a
  refusal."
  []
  (when-let [environment (env/of *request-context*)]
    (if-let [armed (kernel/current-arm)]
      (env/refuse-incomplete-environment!
       (env/scope environment {:seon.sci.kernel/arm armed}))
      environment)))

(defn- with-request-context
  "Run `work` with this request's context re-established FROM DATA.

  Flow conveys no bindings anywhere, by design, so a detached handler
  arrives on a worker thread with `*request-context*` and `seon.db/*conn*`
  at their root nil — which is why every background `my.shell/run` failed
  on a nil connection while the identical foreground command succeeded.
  The far side therefore REBUILDS the frame from the value its submission
  carried instead of hoping to inherit one.

  These two dynamic vars are named readers on the seon.env Phase 3 deletion
  list (`src/seon/shell/jvm.clj:290` is the one this repaired). When a
  handler takes its environment as an argument, this wrapper goes with
  them."
  [context work]
  (binding [*request-context* context
            db/*conn* (:seon.db/connection context)]
    (work)))

(defn- dispatch
  [handler request effective]
  (let [executor (:io ((requiring-resolve
                        'seon.operator.runtime/root-executors)))
        ;; Captured on THIS thread and closed over as data, so the executor
        ;; thread reads the arm from the crossing rather than from a binding
        ;; frame it happens to have inherited.
        carried-arm (:seon.sci.kernel/arm (dispatching-environment))
        task (FutureTask. ^java.util.concurrent.Callable
                          (bound-fn []
                            (kernel/adopt-arm
                             carried-arm
                             #(handler request effective))))]
    (.execute ^Executor executor task)
    (try
      (.get task)
      (catch InterruptedException interrupted
        ;; Waiting stopped, so the capability task must stop too. Capability
        ;; handlers own their resource-specific cleanup on interruption.
        (.cancel task true)
        (throw interrupted)))))

(def ^:private byte-array-class (class (byte-array 0)))

(defn- staged-result
  [connection threshold raw-value admitted-result]
  (let [result-edn (admit/canonical-edn admitted-result)
        octets
        (if (instance? byte-array-class raw-value)
          raw-value
          (.getBytes ^String result-edn StandardCharsets/UTF_8))
        blob-backed?
        (or (instance? byte-array-class raw-value)
            (and threshold (> (alength ^bytes octets) threshold)))
        staged
        (when blob-backed?
          (blob/stage-binary!
           connection (ByteArrayInputStream. ^bytes octets)))]
    {:seon.effect/stored-result
     (cond->
      {:seon.effect/result-edn result-edn
       :seon.effect/result-size (alength ^bytes octets)}
       staged
       (assoc :seon.effect/result-blob (:seon.blob/digest staged)))
     :seon.blob/staged-writes (cond-> [] staged (conj staged))}))

(defn- settle-value!
  ([connection dials effect-id opened-at threshold raw-value]
   (let [content-stages (if (map? raw-value)
                          (:seon.blob/staged-writes raw-value)
                          [])
         public-value (if (map? raw-value)
                        (dissoc raw-value :seon.blob/staged-writes)
                        raw-value)
         admitted-result (admitted-value dials public-value)
         result (:seon.sci.admit/value admitted-result)
         settled-at (Date.)
         staged-result (staged-result connection threshold public-value result)
         staged-writes (into (vec content-stages)
                             (:seon.blob/staged-writes staged-result))
         request
         (merge
          {:seon.effect/id effect-id
           :seon.effect/settled-at settled-at
           :seon.effect/duration-ms
           (max 0 (- (.getTime settled-at) (.getTime opened-at)))}
          (:seon.effect/stored-result staged-result)
          (when (seq content-stages)
            {:seon.effect/content-blobs
             (mapv :seon.blob/digest content-stages)}))]
     {:seon.effect/value result
      :seon.effect/transaction
      (blob/with-publication!
       connection staged-writes
       (fn []
         (db/transact!
          connection
          [[:db.fn/call #'settle-call request]])))})))

(defn- interrupt!
  ([connection effect-id]
   (interrupt! connection effect-id
               (flat-error :seon.effect/interrupted
                           "The effect handler was interrupted."
                           {:seon.effect/id effect-id})))
  ([connection effect-id value]
  (let [interrupted-at (Date.)]
    {:seon.effect/value value
     :seon.effect/transaction
     (db/transact!
      connection
      [[:db.fn/call #'interrupt-call
        {:seon.effect/id effect-id
         :seon.effect/interrupted-at interrupted-at}]])})))

(defn- handler-failure
  [owner-sym]
  (flat-error :seon.effect/handler-failed
              "The capability handler failed."
              {:seon.fn/sym (str owner-sym)}))

(defn- settle-background-terminal!
  ([connection dials effect-id owner-sym opened-at threshold terminal]
   (if-let [throwable (::flow/throwable terminal)]
     (if (instance? InterruptedException throwable)
       (interrupt! connection effect-id)
       (settle-value! connection dials effect-id opened-at threshold
                      (handler-failure owner-sym)))
     (settle-value! connection dials effect-id opened-at threshold
                    (::flow/value terminal)))))

(defn- background-time-limit
  "The milliseconds bounding ONE detached capability request.

  Config supplies the default and the submitting form's explicit
  `:seon.effect/time-limit-ms` WINS — the ordinary elide-for-default,
  pass-to-override rule, in either direction and with no clamp: an agent
  that knows its download needs an hour says so, and one that wants a probe
  cut in two seconds says that. Owner ruling 2026-08-08 night: \"config
  defaults and the agent can supply optional args for tighter or more open
  limits. Great defaults and easy and intuitive overrides.\"

  There is no third answer. Absence of both is a loud refusal rather than
  unbounded work, so a detached request that runs forever cannot be
  expressed — the state the ruling exists to make unrepresentable."
  [execution effective]
  (let [supplied (:seon.effect/time-limit-ms execution)
        configured (:seon.config.effect.background/time-limit-ms effective)]
    (cond
      (some? supplied)
      (if (and (int? supplied) (pos? supplied))
        supplied
        (flat-error
         :seon.effect/invalid-time-limit
         "A background time limit must be a positive number of milliseconds."
         {:seon.effect/time-limit-ms supplied}))

      (and (int? configured) (pos? configured))
      configured

      :else
      (flat-error
       :seon.effect/missing-background-time-limit
       (str "This cluster declares no "
            ":seon.config.effect.background/time-limit-ms, so a detached "
            "request cannot be bounded. Apply the config fact or pass "
            ":seon.effect/time-limit-ms.")
       {:seon.config.effect.background/time-limit-ms configured}))))

(defn- request*
  [owner request execution]
  (let [owner-sym (owner-symbol owner)]
     (cond
       (nil? *request-context*)
       (flat-error :seon.effect/no-evaluation-context
                   "Capability requests require a current run form."
                   {})

       (nil? owner-sym)
       (flat-error :seon.effect/invalid-owner
                   "Capability requests must pass their own Var."
                   {})

       :else
       (let [requesting-context *request-context*
             connection (:seon.db/connection *request-context*)
             ;; Read ONCE here, on the requesting thread. A background
             ;; request settles on whichever thread ran its work, so the
             ;; admission dials travel with that settlement as data instead
             ;; of being re-read from a binding frame the far side may not
             ;; have.
             dials (admission)
             effect-ordinal (swap! (:seon.effect/counter *request-context*) inc)
             database @connection
             owner-row
             (db/pull database
                      [:db/id :seon.fn/sym :seon.fn/spec
                       :seon.effect/capability]
                      [:seon.fn/sym (str owner-sym)])
             handler-symbol (:seon.effect/capability owner-row)
             handler (some-> handler-symbol requiring-resolve deref)
             effective
             (config/effective
              database (:seon.boot/cluster-name *request-context*))
             threshold (:seon.config.eval.result/blob-threshold effective)
             background? (:seon.effect/background? execution)
             ;; Resolved BEFORE the receipt is opened, so an unbounded
             ;; detached request is refused rather than recorded.
             background-limit
             (when background? (background-time-limit execution effective))]
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

           (:seon.error/kind background-limit)
           background-limit

           :else
           (let [projected-request (admitted-value dials request)]
             (if (:seon.sci.admit/capped? projected-request)
               (flat-error
                :seon.effect/request-too-large
                "The capability request exceeds the configured value bounds."
                {:seon.fn/sym (str owner-sym)})
               (let [effect-id
                     (pr-str [(:seon.cluster.run/id *request-context*)
                              (:seon.cluster.run.form/ordinal *request-context*)
                              effect-ordinal])
                     result-ref [:seon.effect/id effect-id]
                     opened-at (Date.)
                     open-request
                     (cond->
                      {:seon.effect/id effect-id
                       :seon.effect/run
                       [:seon.cluster.run/id
                        (:seon.cluster.run/id *request-context*)]
                       :seon.effect/owner [:seon.fn/sym (str owner-sym)]
                       :seon.effect/form-ordinal
                       (:seon.cluster.run.form/ordinal *request-context*)
                       :seon.effect/ordinal effect-ordinal
                       :seon.effect/request-edn
                       (admit/canonical-edn
                        (:seon.sci.admit/value projected-request))
                       :seon.effect/opened-at opened-at}
                       background?
                       (assoc :seon.effect/notify
                              [:seon.cluster.agent/id
                               (:seon.cluster.agent/id *request-context*)]))
                     opened
                     (db/transact!
                      connection
                      [[:db.fn/call #'open-call open-request]])]
                 (if (:seon.error/kind opened)
                   opened
                   (letfn [(settled [outcome]
                             (if (:seon.error/kind
                                  (:seon.effect/transaction outcome))
                               (:seon.effect/transaction outcome)
                               (:seon.effect/value outcome)))]
                     (if background?
                       ;; A FRESH arm, armed here at the submission. Detached
                       ;; work must not inherit the turn's deadline (that is
                       ;; what `my.background` is for), and it must not be
                       ;; unbounded either — so it carries its own limit, the
                       ;; config default unless this form named another.
                       (let [detached (kernel/detached-arm background-limit)]
                         (flow/submit!
                          (:seon.flow/work-launcher *request-context*)
                          {:seon.env/environment
                           (env/refuse-incomplete-environment!
                            (env/scope
                             (:seon.env/environment *request-context*)
                             {:seon.sci.kernel/arm detached}))
                           ::flow/submission-id effect-id
                           ::flow/workload :io
                           ::flow/work-fn
                           (fn [_]
                             (with-request-context
                               requesting-context
                               #(handler
                                 (:seon.sci.admit/value projected-request)
                                 effective)))
                           ::flow/complete!
                           (fn [terminal]
                             (kernel/release-arm! detached)
                             (settle-background-terminal!
                              connection dials effect-id owner-sym opened-at
                              threshold terminal))})
                         result-ref)
                       (let [outcome
                             (try
                               (let [handler-value
                                     (dispatch
                                      handler
                                      (:seon.sci.admit/value projected-request)
                                      effective)]
                                 (if (= :interrupted
                                        (:seon.effect/disposition
                                         handler-value))
                                   (interrupt!
                                    connection effect-id
                                    (dissoc handler-value
                                            :seon.effect/disposition))
                                   (settle-value!
                                    connection dials effect-id opened-at
                                    threshold handler-value)))
                               (catch InterruptedException _
                                 (interrupt! connection effect-id))
                               (catch ExecutionException _
                                 (settle-value!
                                  connection dials effect-id opened-at
                                  threshold (handler-failure owner-sym)))
                               (catch Throwable _
                                 (settle-value!
                                  connection dials effect-id opened-at
                                  threshold (handler-failure owner-sym))))]
                         (settled outcome)))))))))))))

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
        basis-instant
        (:db/txInstant
         (db/pull database [:db/txInstant] (db/basis-t database)))
        pending
        (->> (db/q
              '[:find ?id ?owner ?opened
                :in $ ?agent
                :where
                [?receipt :seon.effect/notify ?agent]
                [?receipt :seon.effect/id ?id]
                [?receipt :seon.effect/opened-at ?opened]
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
        previous-capture-basis
        (or
         (db/q
          '[:find (max ?basis) .
            :in $ ?agent
            :where
            [?run :seon.cluster.run/agent ?agent]
            [?capture :seon.context.capture/run ?run]
            [?capture :seon.context.capture/basis-t ?basis]]
          database (:db/id agent-row))
         0)
        durations
        (when threshold
          (->> (db/q
                '[:find ?id ?duration ?owner ?tx
                  :in $ ?agent ?threshold ?previous-capture
                  :where
                  [?run :seon.cluster.run/agent ?agent]
                  [?receipt :seon.effect/run ?run]
                  [?receipt :seon.effect/id ?id]
                  [?receipt :seon.effect/duration-ms ?duration ?tx]
                  [(> ?tx ?previous-capture)]
                  [(>= ?duration ?threshold)]
                  [?receipt :seon.effect/owner ?owner-eid]
                  [?owner-eid :seon.fn/sym ?owner]
                  (not [?receipt :seon.effect/to])]
                database (:db/id agent-row) threshold
                previous-capture-basis)
               (sort-by first)))]
    (str/join
     "\n"
     (concat
      [";; Background work: use (my.background/await result-ref note) as the last form to wait, or retain the ref and keep working."]
      (map (fn [[id owner opened]]
             (str ";; background pending " id " · " owner " · "
                  (max 0 (- (.getTime ^Date basis-instant)
                            (.getTime ^Date opened)))
                  "ms elapsed · " (pr-str [:seon.effect/id id])))
           pending)
      (map (fn [result]
             (str ";; background result " (:seon.effect/id result) " · "
                  (or (:seon.effect/result-edn result)
                      (str "interrupted at "
                           (:seon.effect/interrupted-at result)))))
           results)
      (map (fn [[id duration owner _tx]]
             (str ";; foreground effect " owner " took " duration
                  "ms · consider my.background/background next time · " id))
           durations)))))
