(ns seon.effect
  "The one system-side owner for declared capability requests.

  A request identity derives from turn, form ordinal, and effect ordinal.
  Its receipt is
  committed before the protected JVM handler runs on the process-root `:io`
  executor; terminal data is bounded and committed once. Recovery interrupts
  an open receipt and never dispatches it again."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.ai.tokens :as tokens]
            [seon.await :as await]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.fs :as fs]
            [seon.program :as program]
            [seon.sci.admit :as admit]
            [seon.sci.kernel :as kernel]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.util Date]
           [java.util.concurrent ExecutionException FutureTask]
           [java.util.concurrent Executor]))

;;; OPTIONAL LATE DEPENDENCY. `seon.operator.runtime` lives in
;;; `resources/seon/operator/runtime.clj`, off the source path, and is loaded
;;; by the operator, not by this namespace. Resolved once at first use rather
;;; than on every dispatch (AGENTS §2.1).
(defonce ^:private operator-runtime-root-executors
  (delay (requiring-resolve 'seon.operator.runtime/root-executors)))

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

(defn- payload-face
  [label payload]
  (str label " (~" (tokens/estimate payload) " tokens): " payload))

(defn- receipt-identities
  [unit]
  (let [database (:seon.db/db unit)]
    {:owner (ref-attribute database
                           (:seon.effect/owner unit)
                           :seon.fn/sym)
     :run (ref-attribute database
                         (:seon.effect/run unit)
                         :seon.turn/id)}))

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
            [:code request]]])
        (when result
          [[:details {:class "seon-effect-result"}
            [:summary (str "Result · approximately "
                           (tokens/estimate result) " tokens")]
            [:code result]]])
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
  {kind true
   :seon.error/kind kind
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
  (let [projection (or (db/carried-projection database)
                       (:seon.schema/projection request)
                       (:seon.schema/projection (env/of request)))]
    (if projection
      (schema/function-accepts-in? projection owner-sym [request])
      (let [failure (db/projection-fallback 'seon.effect/accepts-request?)]
        (throw (ex-info (:seon.error/message failure) failure))))))

(defn- admission
  [request-context]
  (merge (select-keys request-context
                      [:seon.db/db :seon.sci.eval/ctx :seon.schema/projection
                       :seon.env/environment :seon.sci.eval/projection-state])
         {:seon.sci.admit/caps (:seon.sci.admit/caps request-context)
          :seon.config/on-core-error (:seon.config/on-core-error request-context)}))

(defn- admitted-value
  [dials value]
  (admit/admit-value
   (assoc dials
          :seon.sci.admit/value value
          :seon.sci.admit/interrupt-fn (constantly nil))))

(defn- declared-datoms
  "The entries of `arguments` this database already holds as attributes.

  A capability request and its result are validated maps of fully
  namespaced keys, and the schema that validates them also says which of
  those keys are facts: an attribute installed in this branch becomes a
  datom on the effect entity, everything else stays in the canonical EDN
  and the blob that already carry it. Nothing here is per-capability — a
  capability declared tomorrow records its arguments the day its schema
  marks them, with no writer change. Map-valued keys are left to the EDN:
  no capability declares a component argument, and inventing a second
  spelling for one here would be the per-capability mapping this replaces."
  [database arguments]
  (when (map? arguments)
    (into {}
          (filter (fn [[attribute value]]
                    (and (qualified-keyword? attribute)
                         (not (map? value))
                         (db/attribute-installed? database attribute))))
          arguments)))

(defn- evaluation-eid
  "The evaluation entity this request was made from, resolved at the writer.

  The identity is `seon.id/evaluation` of the run and the form ordinal, so
  nothing crosses the writer boundary that the writer cannot re-derive. A
  host or fixture request made outside a recorded evaluation resolves to
  nothing and records no ref; `:seon.effect/run` and
  `:seon.effect/form-ordinal` still name it."
  [database receipt]
  (let [ordinal (:seon.effect/form-ordinal receipt)
        turn-id (:seon.turn/id (db/pull database [:seon.turn/id]
                                        (:seon.effect/run receipt)))]
    (when (and turn-id (some? ordinal))
      (:db/id (db/pull database [:db/id]
                       [:seon.cluster.eval/id
                        (id/evaluation turn-id ordinal)])))))

(defn- capability-fn-eid
  "The handler declaration that runs this request, read off its owner."
  [database receipt]
  (some-> (db/pull database [{:seon.fn/capability-fn [:db/id]}]
                   (:seon.effect/owner receipt))
          :seon.fn/capability-fn
          :db/id))

(defn open-call
  "Open one never-before-recorded effect identity inside the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.effect/open-request]
                  [:vector :seon.schema/value]]}
  [database request]
  (let [receipt (:seon.effect/receipt request)]
    (if (db/pull database [:db/id] [:seon.effect/id (:seon.effect/id receipt)])
      (throw
       (ex-info
        "This effect identity was already recorded and will not be dispatched again."
        {:seon.error/kind :seon.effect/already-recorded
         :seon.error/message
         "This effect request was already recorded and was not dispatched again."
         :seon.error/data {:seon.effect/id (:seon.effect/id receipt)} :seon.effect/already-recorded true}))
      (let [evaluation (evaluation-eid database receipt)
            capability-fn (capability-fn-eid database receipt)]
        [(cond-> (merge receipt
                        (declared-datoms database
                                         (:seon.effect/arguments request)))
           evaluation (assoc :seon.effect/eval evaluation)
           capability-fn (assoc :seon.effect/capability-fn capability-fn))]))))

(defn- write-back-adds
  "The write-back provenance a handler reported, resolved at the writer.

  The span is a fact about this effect and is recorded whatever the path
  is. The file ref and the declaration ref are answers only the program
  graph can give, so they are derived HERE, against the database that owns
  them, never pre-read on the handler's thread. A path outside the indexed
  program records no file; a span inside no declaration records no
  program, and `seon.program/declaration-at` names that position rather
  than answering nil."
  [database effect-eid provenance]
  (let [span (:seon.effect/form-span provenance)
        path (:seon.effect/file provenance)
        file (when path
               (db/pull database [:db/id] [:seon.fn.file/relative-path
                                         (fs/relative-path (fs/source-directory) path)]))
        declarations
        (when (and file span)
          (db/q '[:find [(pull ?declaration [:db/id :seon.fn/form-span]) ...]
                  :in $ ?file
                  :where [?declaration :seon.fn/file ?file]]
                database (:db/id file)))
        declaration (when (seq declarations)
                      (program/declaration-at declarations (first span)))]
    (cond-> []
      span (conj [:db/add effect-eid :seon.effect/form-span span])
      file (conj [:db/add effect-eid :seon.effect/file (:db/id file)])
      (and declaration (not (:seon.error/kind declaration)))
      (conj [:db/add effect-eid :seon.effect/program (:db/id declaration)]))))

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
                {:seon.error/kind :seon.effect/missing-receipt :seon.effect/missing-receipt true}))

      (or (:seon.effect/result-edn receipt)
          (:seon.effect/interrupted-at receipt))
      (throw
       (ex-info "The effect receipt is already terminal."
                {:seon.error/kind :seon.effect/already-settled :seon.effect/already-settled true}))

      :else
      (cond->
       (into [[:db/add (:db/id receipt) :seon.effect/result-edn
               (:seon.effect/result-edn request)]
              [:db/add (:db/id receipt) :seon.effect/result-size
               (:seon.effect/result-size request)]
              [:db/add (:db/id receipt) :seon.effect/duration-ms
               (:seon.effect/duration-ms request)]
              [:db/add (:db/id receipt) :seon.effect/settled-at
               (:seon.effect/settled-at request)]]
             (into (write-back-adds database (:db/id receipt)
                                    (:seon.effect/provenance request))
                   (map (fn [[attribute value]]
                          [:db/add (:db/id receipt) attribute value]))
                   (declared-datoms database
                                    (:seon.effect/arguments request))))
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
                {:seon.error/kind :seon.effect/missing-receipt :seon.effect/missing-receipt true}))

      (or (:seon.effect/result-edn receipt)
          (:seon.effect/interrupted-at receipt))
      (throw
       (ex-info "The effect receipt is already terminal."
                {:seon.error/kind :seon.effect/already-settled :seon.effect/already-settled true}))

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

  The effect boundary is a thread hop like every other one and obeys the same rule: the
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

  THE SCHEMA PROJECTION IS PART OF THAT FRAME. A detached handler that
  resolves a declaration — `seon.sci.eval/build-base-ctx` is one, and every
  armed contract it compiles is another — asks for the projection state, and
  on a flow thread that had none it refused with
  `:seon.schema/missing-projection`. The foreground path never showed it,
  because `bound-fn` carries the requesting thread's whole frame; the
  detached path carries only what its submission names, so the projection
  state is named here alongside the connection. A submission that carried
  none still runs, and the refusal it meets names the missing projection
  rather than pretending one.

  These two dynamic vars are named readers on the seon.env Phase 3 deletion
  list (`src/seon/shell/jvm.clj:290` is the one this repaired). When a
  handler takes its environment as an argument, this wrapper goes with
  them."
  [context work]
  (binding [*request-context* context
            db/*conn* (:seon.db/connection context)]
    (if-let [projection-state (:seon.sci.eval/projection-state context)]
      (schema/call-with-projection-state projection-state work)
      (work))))

(defn- dispatch
  [handler owner-sym effect-id request effective]
  (let [executor (:io (@operator-runtime-root-executors))
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
      (let [result
            (await/await!
             {:seon.await/bound
              {:seon.await/config-attribute :seon.config.eval/time-limit-ms
               :seon.await/config-value
               (:seon.config.eval/time-limit-ms effective)}
              :seon.await/diagnostic
              {:seon.error/diagnostic-layer :effect
               :seon.error/diagnostic-operation ::handler-completion
               :seon.error/diagnostic-member
               {:seon.effect/id effect-id
                :seon.fn/sym (str owner-sym)}
               :seon.error/diagnostic-expected ::handler-result
               :seon.error/diagnostic-offending ::pending
               :seon.error/diagnostic-evidence
               {:seon.effect/id effect-id
                :seon.turn/id
                (:seon.turn/id *request-context*)}}
              :seon.await/future task})]
        (when (:seon.error/kind result)
          (.cancel task true))
        result)
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
         ;; System-side keys a handler reports to the writer. They are
         ;; removed from the value the agent sees for the same reason
         ;; `:seon.blob/staged-writes` is: they address the database, not
         ;; the caller.
         provenance (when (map? raw-value)
                      (:seon.effect/provenance raw-value))
         public-value (if (map? raw-value)
                        (dissoc raw-value :seon.blob/staged-writes
                                :seon.effect/provenance)
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
          (when provenance {:seon.effect/provenance provenance})
          (when (and (map? result) (nil? (:seon.error/kind result)))
            {:seon.effect/arguments result})
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

(defn- background-settlement-request
  "Capture everything background settlement needs on the requesting thread."
  [request-context effect-id owner-sym opened-at threshold]
  (merge
   (select-keys request-context
                [:seon.db/connection
                 :seon.db/db :seon.sci.eval/ctx :seon.schema/projection
                 :seon.env/environment :seon.sci.eval/projection-state
                 :seon.sci.admit/caps
                 :seon.config/on-core-error])
   {:seon.effect/id effect-id
    :seon.effect/opened-at opened-at
    ::owner-sym owner-sym
    ::result-blob-threshold threshold}))

(defn- settle-background-terminal!
  [{connection :seon.db/connection
    effect-id :seon.effect/id
    opened-at :seon.effect/opened-at
    owner-sym ::owner-sym
    threshold ::result-blob-threshold
    :as settlement-request}
   terminal]
  (let [dials (admission settlement-request)]
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
       (let [connection (:seon.db/connection *request-context*)
             database (db/db connection)
             requesting-context (assoc *request-context* :seon.db/db database)
             ;; Read ONCE here, on the requesting thread. A background
             ;; request settles on whichever thread ran its work, so the
             ;; admission dials travel with that settlement as data instead
             ;; of being re-read from a binding frame the far side may not
             ;; have.
             dials (admission requesting-context)
             effect-ordinal (swap! (:seon.effect/counter *request-context*) inc)
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
             ;; A REQUEST ADMISSION THAT KEPT NOTHING IS A REFUSAL, NOT A
             ;; DISPATCH. The retired `:seon.sci.admit/capped?` key answers
             ;; nil now, so this branch stopped firing and an oversized
             ;; request was dispatched with a nil request whose stored
             ;; `:seon.effect/request-edn` was the string "nil" (measured
             ;; 2026-09-07, research/verify-storage-bound-2026-09-07.md B3).
             ;; The bound is the declared storage bound this admission was
             ;; handed, and the refusal names it and the bytes reached.
             (if-some [marker (admit/missing-marker projected-request)]
               (flat-error
                :seon.effect/request-too-large
                (str "The capability request was not admitted under "
                     :seon.config.eval.result/max-bytes
                     " and was refused rather than dispatched.")
                (merge {:seon.fn/sym (str owner-sym)
                        :seon.config.eval.result/max-bytes
                        (:seon.config.eval.result/max-bytes
                         (:seon.sci.admit/caps dials))}
                       marker))
               (let [effect-id
                     (id/digest 12 [::id (:seon.turn/id *request-context*)
                                    (:seon.cluster.eval/ordinal *request-context*)
                                    effect-ordinal])
                     result-ref [:seon.effect/id effect-id]
                     opened-at (Date.)
                     open-request
                     (cond->
                      {:seon.effect/id effect-id
                       :seon.effect/run
                       [:seon.turn/id
                        (:seon.turn/id *request-context*)]
                       :seon.effect/owner [:seon.fn/sym (str owner-sym)]
                       :seon.effect/form-ordinal
                       (:seon.cluster.eval/ordinal *request-context*)
                       :seon.effect/ordinal effect-ordinal
                       :seon.effect/request-edn
                       (admit/canonical-edn
                        (:seon.sci.admit/value projected-request))
                       :seon.effect/opened-at opened-at}
                       background?
                       (assoc :seon.effect/notify
                              [:seon.agent/id
                               (:seon.agent/id *request-context*)]))
                     opened
                     (db/transact!
                      connection
                      [[:db.fn/call #'open-call
                        (cond-> {:seon.effect/receipt open-request}
                          (map? (:seon.sci.admit/value projected-request))
                          (assoc :seon.effect/arguments
                                 (:seon.sci.admit/value projected-request)))]])]
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
                       (let [detached (kernel/detached-arm background-limit)
                             settlement-request
                             (background-settlement-request
                              requesting-context effect-id owner-sym opened-at
                              threshold)]
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
                              settlement-request terminal))})
                         result-ref)
                       (let [outcome
                             (try
                               (let [handler-value
                                    (dispatch
                                      handler
                                      owner-sym
                                      effect-id
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
   [:function [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The effect boundary reports malformed owners and requests as values; the resolved capability's own declared contract validates the heterogeneous request.", :gen/elements [nil false 0 "" :k [] {}]}] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The effect boundary reports malformed owners and requests as values; the resolved capability's own declared contract validates the heterogeneous request.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The effect boundary reports malformed owners and requests as values; the resolved capability's own declared contract validates the heterogeneous request.", :gen/elements [nil false 0 "" :k [] {}]}] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The effect boundary reports malformed owners and requests as values; the resolved capability's own declared contract validates the heterogeneous request.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.effect/execution-options] :seon.schema/value]]}
  ([owner request]
   (request* owner request {}))
  ([owner request execution]
   (request* owner request execution)))
