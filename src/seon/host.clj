(ns seon.host
  "Serve the execution protocol from the JVM agent host over one UDS socket.

   One host process serves one cluster: N sci contexts over one shared base
   ([[seon.host.context]]), speaking the SAME message semantics the Bun
   execution child speaks today so the pod cannot tell hosts from children
   (sci-execution-runtime design §9 step 1). Only the transport differs —
   children ride Bun IPC strings; the host serves length-prefixed
   Transit-over-UDS frames through `seon.db.transport.uds`'s codec.

   MESSAGE CONTRACT (the conformance baseline, inventoried from
   `seon.execution` + `seon.execution.host` + `seon.execution.runtime`):

   pod -> host (parent messages):
   1. startup — the FIRST frame on a session (children receive it as
      argv[2]): protocol-version 3, agent-id, artifact-digest,
      shadow-build-id, database-selection (socket-path + database-name +
      backend + advanced? flag). No `:seon.execution/message` key.
   2. invoke — message/protocol-version/agent-id/invocation-id, the pinned
      `:seon.db/db` value, function-identity (function-symbol plus EITHER
      artifact-digest for compiled entrypoints OR source-digest for
      authored functions), arguments vector, ABSOLUTE deadline-ms,
      result-limit-bytes, optional run-fence.
   3. cancel — message/protocol-version/invocation-id.
   4. shutdown — message/protocol-version.

   host -> pod (child messages):
   1. ready — echoes agent-id, shadow-build-id, artifact-digest, carries
      the runtime version string and the session's resolved `:seon.db/db`.
   2. result — invocation-id, the invoke's `:seon.db/db`, result value,
      result-bytes (optional read-evidence).
   3. error — invocation-id (\"startup\" before ready), optional db, one
      `:seon.error/message`/`kind`/`data` error value.
   4. stopped — the shutdown acknowledgement.

   Semantics preserved: one active invocation per session; a second invoke
   errors `:core-bug`; an invoke naming another agent errors `:core-bug`;
   an elapsed deadline errors `:agent`; timeout mid-eval settles the
   invocation with the timeout error; cancel settles the active invocation
   with the canceled error and ends the session (a child exits there);
   shutdown cancels, parks the context, sends stopped, and closes; results
   are bounded ordinary wire values.

   Documented divergences (favorable, from the B1/C1 evidence): sci's
   in-process interrupt actually stops sync runaways, so a timeout or
   cancel never poisons the process — the agent's context survives in the
   host and the timeout error carries no `child-retired?` claim.

   Eval batches RECORD (U4): each executed form commits a `:running`
   receipt with a managed `:seon.eval/id` before it runs, terminalizes
   behind the receipt's CAS fence with the frozen outcome, and tees
   `:seon.fn`/`:seon.ns`/`:seon.schema` rows through the one corpus
   mechanism (`seon.host.record` builds the exact child-tee data;
   `seon.host.context` owns the writer round-trips). A fresh context
   fork replays the agent's home-ns corpus defs.

   Seams still recorded (deliberately unbuilt here):
   - these message schemas are the JVM projection of `seon.execution`'s
     contract; promoting that namespace to `.cljc` at cutover moves them;
   - `ready`'s runtime-version field is named `bun-version` by the child
     schema — renaming rides the same promotion;
   - the host trusts its JVM classpath instead of hashing a Bun artifact,
     so it echoes the startup's declared artifact identity;
   - the run-fence CAS, ALS print capture, preflight repair, and the
     render-ai result skeleton remain child-path behavior (roadmap U4
     honest limits);
   - render-prompt!/render-agent-view! stay pod-served by design (the pod
     keeps rendering); routing them here answers with a steering error."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.db.protocol :as db.protocol]
            [sci.core :as sci]
            [sci.ctx-store]
            [seon.db.transport.uds :as uds]
            [seon.host.context :as context]
            [seon.host.graduate :as graduate]
            [seon.host.record :as record]
            [seon.render.value :as render.value]
            [seon.schema :as schema])
  (:import [java.io File OutputStream]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]
           [java.util.concurrent ExecutorService Executors
            ScheduledExecutorService TimeUnit]))

(set! *warn-on-reflection* true)

(def protocol-version 3)
(def maximum-invocation-ms (* 10 60 1000))

(def invoke-message :seon.execution.message/invoke)
(def cancel-message :seon.execution.message/cancel)
(def shutdown-message :seon.execution.message/shutdown)
(def ready-message :seon.execution.message/ready)
(def result-message :seon.execution.message/result)
(def error-message :seon.execution.message/error)
(def stopped-message :seon.execution.message/stopped)
(def value-sample-message :seon.execution.message/value-sample)
(def value-sample-result-message :seon.execution.message/value-sample-result)
(def value-sample-error-message :seon.execution.message/value-sample-error)

;; JVM projection of the `seon.execution` wire contract (seam above).
(schema/register! ::protocol-version [:= protocol-version])
(schema/register! ::agent-id [:string {:min 1}])
(schema/register! ::invocation-id [:string {:min 1}])
(schema/register! ::function-symbol :qualified-symbol)
(schema/register! ::digest [:re "^[0-9a-f]{64}$"])
(schema/register!
 ::function-identity
 [:or
  [:map {:closed true}
   [:seon.execution/function-symbol ::function-symbol]
   [:seon.execution/source-digest ::digest]]
  [:map {:closed true}
   [:seon.execution/function-symbol ::function-symbol]
   [:seon.execution/artifact-digest ::digest]]])
(schema/register!
 ::database-selection
 [:map
  [:seon.db/socket-path [:string {:min 1}]]
  [:seon.db/database-name [:string {:min 1}]]])
(schema/register!
 ::startup
 [:map
  [:seon.execution/protocol-version ::protocol-version]
  [:seon.execution/agent-id ::agent-id]
  [:seon.execution/artifact-digest ::digest]
  [:seon.execution/shadow-build-id [:string {:min 1}]]
  [:seon.execution/database-selection ::database-selection]])
(schema/register!
 ::invoke
 [:map
  [:seon.execution/message [:= invoke-message]]
  [:seon.execution/protocol-version ::protocol-version]
  [:seon.execution/agent-id ::agent-id]
  [:seon.execution/invocation-id ::invocation-id]
  [:seon.db/db :seon.db/db]
  [:seon.execution/function-identity ::function-identity]
  [:seon.execution/arguments [:vector :any]]
  [:seon.execution/deadline-ms [:int {:min 0}]]
  [:seon.execution/result-limit-bytes [:int {:min 1}]]
  [:seon.execution/run-fence {:optional true}
   [:map-of :qualified-keyword :any]]])
(schema/register!
 ::cancel
 [:map
  [:seon.execution/message [:= cancel-message]]
  [:seon.execution/protocol-version ::protocol-version]
  [:seon.execution/invocation-id ::invocation-id]])
(schema/register!
 ::shutdown
 [:map
  [:seon.execution/message [:= shutdown-message]]
  [:seon.execution/protocol-version ::protocol-version]])
(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::eval-threads [:int {:min 1}])
(schema/register!
 ::start-request
 [:map {:closed true}
  [::socket-path ::socket-path]
  [::context/writer-socket-path ::context/writer-socket-path]
  [::context/database-name ::context/database-name]
  [::context/backend {:optional true} ::context/backend]
  [::context/database-path {:optional true} ::context/database-path]
  [::eval-threads {:optional true} ::eval-threads]])
(schema/register! ::server 'some?)
(schema/register! ::contexts 'some?)
(schema/register! ::base ::context/base)
(schema/register!
 ::host
 [:map
  [::server ::server]
  [::base ::base]
  [::contexts ::contexts]
  [::projection-state ::context/projection-state]])

(def ^:private default-eval-threads 10)

(defn drill-value
  "Project a host value against the exact retained committed generation."
  {:malli/schema [:=> [:catn [::host ::host]
                       [::value :seon.render.value/value]
                       [::request :seon.render.value/value]]
                  :seon.render.value/drill-result]}
  [host value request]
  (let [admitted
        (context/current-committed-projection (::projection-state host))]
    (if-let [fault (:seon/error admitted)]
      {:seon.render.value/ok? false
       :seon/error
       {:seon.error/message "Schema-aware value browsing is unavailable."
        :seon.error/kind :core-bug}}
      (render.value/drill-value (::context/projection admitted)
                                value request))))

(defn- now-ms [] (System/currentTimeMillis))

(defn- error-value
  ([message kind] (error-value message kind nil))
  ([message kind data]
   (cond-> {:seon.error/message message :seon.error/kind kind}
     (seq data) (assoc :seon.error/data data))))

(defn- error-frame
  ([invocation-id error] (error-frame invocation-id error nil))
  ([invocation-id error database]
   (cond-> {:seon.execution/message error-message
            :seon.execution/protocol-version protocol-version
            :seon.execution/invocation-id invocation-id
            :seon.execution/error error}
     database (assoc :seon.db/db database))))

(defn- result-frame
  [invocation-id database value result-bytes]
  {:seon.execution/message result-message
   :seon.execution/protocol-version protocol-version
   :seon.execution/invocation-id invocation-id
   :seon.db/db database
   :seon.execution/result value
   :seon.execution/result-bytes result-bytes})

(declare send-frame! retained-live-entry retained-live-value)

(defn- sample-error-frame
  ([sample message] (sample-error-frame sample message :core-bug))
  ([sample message kind]
  {:seon.execution/message value-sample-error-message
   :seon.execution/protocol-version protocol-version
   :seon.execution/agent-id (:seon.execution/agent-id sample)
   :seon.execution/request-id (:seon.execution/request-id sample)
   :seon.execution/error (error-value message kind)}))

(defn- unavailable-drill-result [projection request miss]
  (let [root-request (assoc request
                            :seon.render.value/path []
                            :seon.render.value/offset 0)
        rendered (render.value/drill-value projection miss root-request)]
    (if (:seon.render.value/ok? rendered)
      (-> rendered
          (assoc :seon.render.value/availability :unavailable
                 :seon.render.value/recompute? true)
          (assoc-in [:seon.render.value/projection :seon.render.value/path]
                    (:seon.render.value/path request))
          (assoc-in [:seon.render.value/projection :seon.render.value/offset]
                    (:seon.render.value/offset request)))
      rendered)))

(defn- serve-value-sample! [host session sample]
  (let [request (select-keys sample
                             [:seon.render.value/path
                              :seon.render.value/offset
                              :seon.render.value/effective-limits])]
    (cond
      (some? @(::active session))
      (send-frame! session
                   (sample-error-frame sample
                                       "The execution host already has active work."))

      (not= (:seon.execution/agent-id @(::startup session))
            (:seon.execution/agent-id sample))
      (send-frame! session
                   (sample-error-frame sample
                                       "The value sample names another agent."))

      (not (render.value/admitted-drill-request? request))
      (send-frame! session
                   (sample-error-frame sample
                                       "The value sample request is invalid or over budget."))

      :else
      (let [admitted (context/current-committed-projection
                      (::projection-state host))
            projection (::context/projection admitted)
            retained (retained-live-entry
                      session (:seon.execution/eval-id sample))
            found? (::found? retained)
            trusted-limits (::limits retained)
            metadata-invalid? (and found?
                                   (or
                                    (not (db.protocol/database-value?
                                          (::database retained)))
                                    (not (render.value/effective-limits-within?
                                          trusted-limits trusted-limits))))
            policy-refused? (and found?
                                 (not metadata-invalid?)
                                 (not (render.value/effective-limits-within?
                                       (:seon.render.value/effective-limits request)
                                       trusted-limits)))
            result (cond
                     metadata-invalid?
                     nil
                     (:seon/error admitted)
                     {:seon.render.value/ok? false
                      :seon/error {:seon.error/message
                                   "Schema-aware value browsing is unavailable."
                                   :seon.error/kind :core-bug}}
                     policy-refused?
                     (render.value/sampling-policy-refusal)
                     found?
                     (render.value/drill-value
                      projection
                      (retained-live-value session
                                           (:seon.execution/eval-id sample))
                     request)
                     :else
                     (unavailable-drill-result projection request
                                               (::value retained)))
            limits (:seon.render.value/effective-limits request)]
        (if metadata-invalid?
          (send-frame! session
                       (sample-error-frame
                        sample render.value/sampling-policy-unavailable-message
                        :seon.runtime/unavailable))
          (if (render.value/bounded-drill-result? result limits)
          (send-frame! session
                       {:seon.execution/message value-sample-result-message
                        :seon.execution/protocol-version protocol-version
                        :seon.execution/agent-id
                        (:seon.execution/agent-id sample)
                        :seon.execution/request-id
                        (:seon.execution/request-id sample)
                        :seon.render.value/result result})
          (send-frame! session
                       (sample-error-frame
                        sample "The value sample result exceeded its bounds."))))))))

(defn- valid-value-sample? [message]
  ;; JVM projection of the portable closed request: exact outer keys and
  ;; scalar correlation here; the one total drill predicate owns every path
  ;; and realization-work rule on both runtimes. This avoids registering a
  ;; second JVM-only schema graph for the same frame.
  (let [request (select-keys message
                             [:seon.render.value/path
                              :seon.render.value/offset
                              :seon.render.value/effective-limits])]
    (and (= 8 (count message))
         (every? #(contains? message %)
                 [:seon.execution/message
                  :seon.execution/protocol-version
                  :seon.execution/agent-id
                  :seon.execution/request-id
                  :seon.execution/eval-id
                  :seon.render.value/path
                  :seon.render.value/offset
                  :seon.render.value/effective-limits])
         (render.value/admitted-drill-request? request)
         (= value-sample-message (:seon.execution/message message))
         (= protocol-version (:seon.execution/protocol-version message))
         (every? #(and (string? %) (seq %))
                 ((juxt :seon.execution/agent-id
                        :seon.execution/request-id
                        :seon.execution/eval-id)
                  message)))))

(defn- safe-sample-correlation [session message]
  (let [startup-agent (:seon.execution/agent-id @(::startup session))
        agent-id (:seon.execution/agent-id message)
        request-id (:seon.execution/request-id message)]
    {:seon.execution/agent-id
     (if (and (string? agent-id) (seq agent-id)) agent-id startup-agent)
     :seon.execution/request-id
     (if (and (string? request-id) (seq request-id)) request-id "invalid")}))

(defn- send-frame!
  "Write one frame on the session under its write lock."
  [session message]
  (locking (::write-lock session)
    (uds/write-frame! ^OutputStream (::output session) message))
  nil)

(defn- bounded-result
  "Return `{::ok? true ::value ::result-bytes}` or a bounded error value.

   Mirrors `seon.execution/bounded-result`: the value must encode as
   Transit and fit the invocation's byte limit; failures are `:agent`
   error values, never throws."
  [value result-limit]
  (let [encoded (try {::bytes (uds/encode {:seon.execution/value value})}
                     (catch Throwable throwable
                       {::encode-error (.getMessage throwable)}))]
    (if-let [^bytes payload (::bytes encoded)]
      (let [byte-count (alength payload)]
        (if (<= byte-count result-limit)
          {::ok? true ::value value ::result-bytes byte-count}
          {::ok? false
           ::error (error-value
                    "The function result exceeded its byte limit."
                    :agent
                    {:seon.execution/result-bytes byte-count
                     :seon.execution/result-limit-bytes result-limit})}))
      {::ok? false
       ::error (error-value
                "The function returned a value that cannot cross IPC."
                :agent
                {::encode-error (::encode-error encoded)})})))

;;; Eval serving

(defn- agent-home-ns
  "The deterministic home-ns symbol for an agent id.

   Mirrors `seon.agent.home/home-ns` (the pod-side owner of the
   derivation): `(agent-home-ns \"seon\") => 'my.agent.seon`."
  [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn- built-in-var-refusal?
  "True when SCI refused an eval-side root mutation of a shared var."
  [^Throwable throwable]
  (let [causes (take-while some? (iterate ex-cause throwable))
        structural-data (some (fn [cause]
                                (let [data (ex-data cause)]
                                  (when (contains? data :var) data)))
                              causes)]
    (if structural-data
      (let [shared-var (:var structural-data)]
        (boolean
         (and (instance? sci.lang.Var shared-var)
              (:sci/built-in (meta shared-var)))))
      (boolean
       (some #(re-find #"^Built-in var #'[^ ]+ is read-only\.$"
                       (or (.getMessage ^Throwable %) ""))
             causes)))))

(defn- eval-error-value
  "Classify one SCI eval throwable into the standard agent error value."
  [^Throwable throwable home-ns]
  (let [message (str (first (str/split-lines
                             (str (.getMessage throwable)))))]
    (if (built-in-var-refusal? throwable)
      (error-value
       (str "That name is a shared built-in and is read-only. Define your "
            "own function in your home namespace `" home-ns "` instead.")
       :agent)
      (error-value message :agent))))

(defn- entry-source [entry]
  (or (:seon.repl/eval-source entry) (:seon.repl/source entry)))

(defn- wire-safe-value
  "Keep a transit-encodable value; project anything else to its print form.

   sci vars (every `def`'s return) and other host objects cannot cross the
   protocol; their envelope keeps `:seon.eval/value-display` instead."
  [envelope]
  (if-not (contains? envelope :seon.eval/value)
    envelope
    (let [value (:seon.eval/value envelope)]
      (try
        (uds/encode {::probe value})
        envelope
        (catch Throwable _
          (-> envelope
              (dissoc :seon.eval/value)
              (assoc :seon.eval/value-display (pr-str value))))))))

(defn- admitted-retained-value
  "Apply the one portable bounded live-result admission policy."
  [value]
  (render.value/admit-retained-value value))

(defn- retain-live-value!
  "Retain one managed eval value in oldest-first bounded session state."
  [session eval-id value limits database]
  (swap! (::live-values session)
         (fn [{::keys [order values]}]
           (let [order (conj (vec (remove #{eval-id} order)) eval-id)
                 values (assoc values eval-id
                               {::value (admitted-retained-value value)
                                ::limits limits
                                ::database database})
                 over (max 0 (- (count order)
                                render.value/retained-value-cap))
                 evicted (subvec order 0 over)
                 kept (subvec order over)]
             {::order kept ::values (apply dissoc values evicted)})))
  nil)

(defn- retained-live-entry [session eval-id]
  (let [values (::values @(::live-values session))]
    (if (contains? values eval-id)
      (merge {::found? true}
             (select-keys (get values eval-id) [::limits ::database]))
      {::found? false
       ::value
       {:seon.eval/ok? false
        :seon.error/message
        (str "eval " eval-id " isn't live — its bounded result slot was "
             "evicted or belonged to a prior process. Re-run the form to recompute it.")}})))

(defn- retained-live-value [session eval-id]
  (get-in @(::live-values session) [::values eval-id ::value]))

(def ^:private sampling-policy-query
  '[:find [?path-segments ?path-bytes ?realized ?depth ?string ?shape ?items]
    :in $ ?id
    :where
    [?config :seon.config/id ?id]
    [?config :seon.config.render/value-max-path-segments ?path-segments]
    [?config :seon.config.render/value-max-path-bytes ?path-bytes]
    [?config :seon.config.render/value-max-realized-items ?realized]
    [?config :seon.config.render/value-max-depth ?depth]
    [?config :seon.config.render/value-max-string ?string]
    [?config :seon.config.render/value-shape-sample ?shape]
    [?config :seon.config.render/value-max-items ?items]])

(defn- acquire-sampling-policy! [writer database]
  (let [row (context/query-writer-at! writer database
                                      sampling-policy-query ["cluster"])
        limits (when (and (vector? row) (= 7 (count row)))
                 (zipmap
                  [:seon.config.render/value-max-path-segments
                   :seon.config.render/value-max-path-bytes
                   :seon.config.render/value-max-realized-items
                   :seon.config.render/value-max-depth
                   :seon.config.render/value-max-string
                   :seon.config.render/value-shape-sample
                   :seon.render.value/page-size]
                  row))]
    (if (and limits (render.value/effective-limits-within? limits limits))
      limits
      (throw (ex-info "The invocation database lacks a complete value-sampling policy."
                      {:seon.error/kind :core-bug})))))

(defn- eval-form!
  "Evaluate one prepared source in the agent context; every outcome a value.

   `::var-meta` (a returned sci var's metadata, the tee's projection
   input) is host-internal and stripped before the envelope crosses the
   protocol."
  [ctx home-ns source]
  (try
    (let [value (sci/eval-string* ctx source)]
      (cond-> (assoc (sci.ctx-store/with-ctx ctx
                       (wire-safe-value {:seon.eval/ok? true
                                         :seon.eval/value value}))
                     ::live-value value)
        (instance? sci.lang.Var value)
        (assoc ::var-meta (meta value))))
    (catch Throwable throwable
      (let [message (str (first (str/split-lines
                                 (str (.getMessage throwable)))))
            interrupted? (boolean (re-find #"deadline exceeded|interrupt"
                                           message))]
        {:seon.eval/ok? false
         :seon.eval/interrupted? interrupted?
         :seon/error (eval-error-value throwable home-ns)}))))

(defn- read-error-envelope [entry]
  {:seon.eval/ok? false
   :seon/error (error-value
                (str "The form could not be read: "
                     (or (:seon.repl/message entry) "read error"))
                :agent)})

(defn- batch-summary
  [ids results]
  (let [evaluated (remove :seon.eval/skipped? results)]
    {:seon.eval/ids ids
     :seon.eval/n-ok (count (filter :seon.eval/ok? evaluated))
     :seon.eval/n-fail (count (remove :seon.eval/ok? evaluated))
     :seon.host/results (vec results)}))

(defn- declared-next-ns
  "The ns an executed source moves the batch to, when it moves it.

   An explicit `(ns X …)` or `(in-ns 'X)` as the FIRST form advances the
   fold; ordinary forms cannot move the REPL namespace."
  [forms]
  (let [form (first forms)]
    (cond
      (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (second form)

      (and (seq? form) (= 'in-ns (first form))
           (seq? (second form)) (= 'quote (first (second form)))
           (symbol? (second (second form))))
      (second (second form))

      :else nil)))

(defn- eval-batch-result
  "Serve `seon.execution.runtime/eval-batch!` over sci WITH recording.

   Each executed form records through the one corpus mechanism: a
   `:running` receipt with a managed `:seon.eval/id` commits BEFORE the
   form runs (the durable execution boundary — no receipt, no run), and
   one terminal transaction carries the CAS fence, the frozen eval row,
   and every program-graph row the form tees (`:seon.fn` for a single
   defn, `:seon.ns` + require edges for an ns declaration,
   `:seon.schema` for registrations detected by registry diff). The
   batch evals in the request's starting ns so defs land in the agent's
   home namespace, not scratch `user`. Recording engages only when the
   request names its owning turn; receiptless probes stay engine-only
   with empty `:seon.eval/ids`."
  [session {parsed :seon.eval/parsed
            starting-ns :seon.eval/starting-ns
            turn-id :seon.agent.turn/id-of-turn}
   sampling-limits database]
  (let [ctx (::ctx session)
        writer (::writer session)
        agent-id (:seon.execution/agent-id @(::startup session))
        record? (boolean (and writer turn-id agent-id))
        batch-ns (or starting-ns 'user)]
    (when-not (contains? record/transient-ns-syms batch-ns)
      (context/ensure-context-ns! ctx batch-ns))
    (loop [entries (vec (or parsed []))
           current-ns batch-ns
           ids []
           results []]
      (if (empty? entries)
        (batch-summary ids results)
        (let [entry (first entries)
              kind (:seon.repl/kind entry)]
          (if-not (contains? #{:form :read} kind)
            ;; comment/prose entries evaluate and record nothing.
            (recur (rest entries) current-ns ids
                   (conj results {:seon.eval/ok? true
                                  :seon.eval/skipped? true}))
            (let [source (or (entry-source entry) "")
                  narration (or (:seon.repl/narration entry) "")
                  at (java.util.Date.)
                  start-ms (now-ms)
                  started (when record?
                            (context/start-eval-receipt!
                             writer
                             {:seon.agent.turn/id turn-id
                              :seon.eval/at at
                              :seon.eval/source source
                              :seon.eval/narration narration
                              :seon.eval/ns current-ns
                              :seon.agent/id agent-id}))]
              (if (and record? (:seon/error started))
                ;; The receipt is the durable execution boundary: a form
                ;; whose receipt cannot commit never runs.
                (recur (rest entries) current-ns ids
                       (conj results {:seon.eval/ok? false
                                      :seon/error (:seon/error started)}))
                (let [schemas-before (schema/snapshot)
                      raw-envelope
                      (if (= :form kind)
                        (eval-form! ctx (agent-home-ns agent-id)
                                    (str "(in-ns '" current-ns ")\n"
                                         source))
                        (read-error-envelope entry))
                      ok? (boolean (:seon.eval/ok? raw-envelope))
                      ;; A failed eval must not leave half a registration:
                      ;; restore the exact prior registry, as the child does.
                      _ (when (and (= :form kind) (not ok?))
                          (schema/restore! schemas-before))
                      new-schema-keys (if ok?
                                        (schema/changed-keys schemas-before)
                                        #{})
                      forms (if (= :form kind)
                              (record/read-forms
                               {::record/source source
                                ::record/ns-sym current-ns})
                              [])
                      var-meta (::var-meta raw-envelope)
                      live-value (::live-value raw-envelope)
                      envelope (dissoc raw-envelope ::var-meta ::live-value)
                      eval-id (:seon.eval/id started)
                      ;; An interrupted form leaves the worker's interrupt
                      ;; status set, which would kill the writer channel's
                      ;; NIO calls mid-record. The form is settled; clear
                      ;; the flag so the terminal receipt can commit. The
                      ;; envelope's interrupted? flag still ends the batch
                      ;; and run-invocation! settles the timeout/cancel.
                      _ (when (:seon.eval/interrupted? envelope)
                          (Thread/interrupted))
                      recorded
                      (when (and record? eval-id)
                        (context/record-eval-terminal!
                         writer
                         {:seon.eval/id eval-id
                          ::context/envelope envelope
                          ::context/at at
                          ::context/duration-ms (- (now-ms) start-ms)
                          ::context/source source
                          ::context/narration narration
                          ::context/ns-sym current-ns
                          ::context/agent-id agent-id
                          ::context/forms forms
                          ::context/var-meta var-meta
                          ::context/new-schema-keys new-schema-keys}))
                      projection-change?
                      (true? (::context/projection-changed? recorded))
                      projection-refresh
                      (when projection-change?
                        (context/refresh-committed-projection!
                          writer (::projection-state session)
                          (get-in recorded [:db-after :t])))
                      _ (when (:seon/error projection-refresh)
                          (throw
                            (ex-info
                              (get-in projection-refresh
                                      [:seon/error :seon.error/message])
                              {:seon.error/kind :core-bug
                               :seon.host/projection-error
                               (:seon/error projection-refresh)})))
                      ids (if (and recorded (:seon.db/ok? recorded))
                            (conj ids eval-id)
                            ids)
                      _ (when (and ok? recorded (:seon.db/ok? recorded))
                          (retain-live-value! session eval-id live-value
                                              sampling-limits database))
                      envelope (if (and recorded
                                        (not (:seon.db/ok? recorded)))
                                 ;; The outcome could not become durable —
                                 ;; surface it on the envelope as data.
                                 (assoc envelope ::record-error
                                        (:seon/error recorded))
                                 envelope)
                      next-ns (or (when ok? (declared-next-ns forms))
                                  current-ns)]
                  (if (:seon.eval/interrupted? envelope)
                    (batch-summary ids (conj results envelope))
                    (recur (rest entries) next-ns ids
                           (conj results envelope))))))))))))

(defn- interrupted-batch?
  [result]
  (boolean (some :seon.eval/interrupted? (:seon.host/results result))))

;;; Invocation dispatch

(defn- settle!
  "Send one terminal frame for the active invocation exactly once."
  [session token message]
  (let [active (::active session)]
    (when (compare-and-set! active token nil)
      (send-frame! session message)
      true)))

(defn- run-invocation!
  "Execute one claimed invocation on the calling pool thread."
  [session token invocation]
  ;; Cancellation revokes this exact invocation generation before touching
  ;; its Future. If the pool won the FutureTask start race, it still cannot
  ;; acquire policy, create receipts, evaluate, or record after settlement.
  (when (identical? token @(::active session))
    (let [{invocation-id :seon.execution/invocation-id
           database :seon.db/db
           identity-value :seon.execution/function-identity
           arguments :seon.execution/arguments
           result-limit :seon.execution/result-limit-bytes} invocation
          function-symbol (:seon.execution/function-symbol identity-value)
          compiled? (contains? identity-value
                               :seon.execution/artifact-digest)
          worker (Thread/currentThread)
          remaining (min maximum-invocation-ms
                         (max 1 (- (:seon.execution/deadline-ms invocation)
                                   (now-ms))))
          watchdog ^ScheduledExecutorService (::watchdog session)
          deadline-task (.schedule watchdog
                                   ^Runnable #(.interrupt worker)
                                   (long remaining) TimeUnit/MILLISECONDS)
          outcome
          (try
            (cond
            (and compiled?
                 (not= (:seon.execution/artifact-digest identity-value)
                       (get-in @(::startup session)
                               [:seon.execution/artifact-digest])))
            {::error (error-value
                      "The compiled function identity is not trusted by this artifact."
                      :core-bug)}

            (not compiled?)
            ;; TODO SEAM (U2): authored invocation = corpus acquisition +
            ;; source-digest verification + context load, through the one
            ;; program-graph mechanism `seon.execution` owns today.
            {::error (error-value
                      "Authored function invocation is not yet served by the JVM host."
                      :core-bug
                      {:seon.execution/function-symbol function-symbol})}

            (= function-symbol 'seon.execution.runtime/eval-batch!)
            (let [sampling-limits (acquire-sampling-policy!
                                   (::writer session) database)
                  result (binding [context/*agent-id*
                                   (:seon.execution/agent-id
                                    @(::startup session))]
                           (eval-batch-result session (first arguments)
                                              sampling-limits database))]
              (if (and (interrupted-batch? result)
                       @(::cancel-requested? session))
                {::error (error-value "The invocation was canceled." :agent)}
                (if (interrupted-batch? result)
                  {::error (error-value "The invocation timed out." :agent)}
                  {::value result})))

            :else
            ;; render-prompt!/render-agent-view! remain pod-served: the
            ;; host serves EVAL; the pod keeps rendering (design §1).
            {::error (error-value
                      (str "The JVM host does not serve " function-symbol
                           "; prompt and view rendering stay on the pod.")
                      :core-bug)})
            (catch Throwable throwable
              {::error (error-value
                        (str (first (str/split-lines
                                     (str (.getMessage throwable)))))
                        (or (:seon.error/kind (ex-data throwable)) :agent))})
            (finally
              (.cancel deadline-task false)
              (Thread/interrupted)))]
      (settle!
       session token
       (if-let [error (::error outcome)]
         (error-frame invocation-id error database)
         (let [bounded (bounded-result (::value outcome) result-limit)]
           (if (::ok? bounded)
             (result-frame invocation-id database (::value bounded)
                           (::result-bytes bounded))
             (error-frame invocation-id (::error bounded) database))))))))

(defn- begin-invocation!
  [session invocation]
  (let [{invocation-id :seon.execution/invocation-id
         agent-id :seon.execution/agent-id
         database :seon.db/db} invocation
        startup @(::startup session)
        remaining (- (:seon.execution/deadline-ms invocation) (now-ms))]
    (cond
      (not= (:seon.execution/agent-id startup) agent-id)
      (send-frame! session
                   (error-frame invocation-id
                                (error-value
                                 "The invocation names another agent."
                                 :core-bug)
                                database))

      (some? @(::active session))
      (send-frame!
       session
       (error-frame invocation-id
                    (error-value
                     "The execution child already has an active invocation."
                     :core-bug)
                    database))

      (not (pos? remaining))
      (send-frame! session
                   (error-frame invocation-id
                                (error-value
                                 "The invocation deadline has elapsed."
                                 :agent)
                                database))

      :else
      (let [token {::invocation invocation ::started-at (now-ms)}]
        (reset! (::active session) token)
        (reset! (::cancel-requested? session) false)
        (let [worker-holder (promise)
              submitted
              (.submit ^ExecutorService (::eval-pool session)
                       ^Runnable
                       (fn []
                         (deliver worker-holder (Thread/currentThread))
                         (run-invocation! session token invocation)))]
          (reset! (::active-run session)
                  {::future submitted ::worker worker-holder}))))))

(defn- cancel-active!
  "Settle a matching active invocation with the canceled error value."
  [session invocation-id]
  (when-let [token @(::active session)]
    (when (= invocation-id
             (get-in token [::invocation :seon.execution/invocation-id]))
      (reset! (::cancel-requested? session) true)
      (when (settle! session token
                     (error-frame
                      invocation-id
                      (error-value "The invocation was canceled." :agent)
                      (get-in token [::invocation :seon.db/db])))
        (when-let [{::keys [worker future]} @(::active-run session)]
          (if (realized? worker)
            (.interrupt ^Thread @worker)
            (.cancel ^java.util.concurrent.Future future false))
          ;; Bound the wait so a wedged native call cannot wedge the reader.
          (try (.get ^java.util.concurrent.Future future
                     2000 TimeUnit/MILLISECONDS)
               (catch Throwable _ nil))))
      true)))

(defn- shutdown-session!
  "Cancel active work, park the agent context, acknowledge, and close."
  [session]
  (when-let [token @(::active session)]
    (cancel-active!
     session
     (get-in token [::invocation :seon.execution/invocation-id])))
  ;; Park = drop: restore forks the base and replays defs from the corpus.
  (when-let [agent-id (:seon.execution/agent-id @(::startup session))]
    (swap! (::contexts session) dissoc agent-id))
  (reset! (::live-values session) {::order [] ::values {}})
  (send-frame! session {:seon.execution/message stopped-message
                        :seon.execution/protocol-version protocol-version})
  nil)

;;; Session lifecycle

(defn- invalid-message-frame [message]
  (error-frame (or (:seon.execution/invocation-id message) "invalid")
               (error-value "The parent sent an invalid execution message."
                            :core-bug)))

(defn- startup-error
  [session message]
  (send-frame! session (error-frame "startup"
                                    (error-value message :core-bug)))
  nil)

(defn- accept-startup!
  "Validate the session's first frame and answer ready, or refuse."
  [session host startup]
  (let [selection (:seon.execution/database-selection startup)]
    (cond
      (not (schema/valid-candidate-value? ::startup startup))
      (startup-error session "The execution child startup identity is invalid.")

      (not= (::context/database-name host)
            (:seon.db/database-name selection))
      (startup-error session
                     "The startup names another cluster database.")

      :else
      (let [head (context/resolve-head! (::writer host))]
        (if (:seon/error head)
          (startup-error session
                         (get-in head [:seon/error :seon.error/message]))
          (let [agent-id (:seon.execution/agent-id startup)
                existing? (contains? @(::contexts session) agent-id)
                ctx (-> (swap! (::contexts session)
                               (fn [contexts]
                                 (if (contains? contexts agent-id)
                                   contexts
                                   (assoc contexts agent-id
                                          (context/fork-context
                                           (::base host))))))
                        (get agent-id))]
            ;; Restore = fork the shared base + replay the agent's corpus
            ;; defs (design §2): a context is a cache of database facts,
            ;; so a fresh fork rebuilds the agent's home namespace from
            ;; its recorded `:seon.fn/source` rows. Replay failures are
            ;; values; a failed corpus read leaves an honest empty
            ;; context rather than refusing the session.
            (when-not existing?
              (binding [context/*agent-id* agent-id]
                (context/restore-context-defs!
                 (::writer host) ctx (agent-home-ns agent-id)))
              (context/install-registered-wrappers!
               {::context/registry (get-in host [::base ::context/registry])
                ::context/ctx ctx
                ::context/lib (agent-home-ns agent-id)}))
            (reset! (::startup session) startup)
            (send-frame!
             session
             {:seon.execution/message ready-message
              :seon.execution/protocol-version protocol-version
              :seon.execution/agent-id agent-id
              ;; The child schema names this field bun-version; the host
              ;; reports its JVM runtime there (rename rides the .cljc
              ;; promotion seam).
              :seon.execution/bun-version
              (str "jvm-" (System/getProperty "java.version"))
              :seon.execution/shadow-build-id
              (:seon.execution/shadow-build-id startup)
              :seon.execution/artifact-digest
              (:seon.execution/artifact-digest startup)
              :seon.db/db head})
            (assoc session ::ctx ctx)))))))

(defn- serve-session!
  "Run one pod session: startup handshake, then the message loop."
  [host ^SocketChannel channel]
  (let [input (Channels/newInputStream channel)
        output (Channels/newOutputStream channel)
        session {::channel channel
                 ::input input
                 ::output output
                 ::write-lock (Object.)
                 ::startup (atom nil)
                 ::active (atom nil)
                 ::active-run (atom nil)
                 ::cancel-requested? (atom false)
                 ::live-values (atom {::order [] ::values {}})
                 ::contexts (::contexts host)
                 ::writer (::writer host)
                 ::projection-state (::projection-state host)
                 ::eval-pool (::eval-pool host)
                 ::watchdog (::watchdog host)}]
    (try
      (let [startup (uds/read-frame input)]
        (when-let [ready-session
                   (and (map? startup)
                        (accept-startup! session host startup))]
          (loop []
            (let [message (uds/read-frame input)]
              (when (map? message)
                (case (:seon.execution/message message)
                  :seon.execution.message/invoke
                  (do (if (schema/valid-candidate-value? ::invoke message)
                        (begin-invocation! ready-session message)
                        (send-frame! ready-session
                                     (invalid-message-frame message)))
                      (recur))

                  :seon.execution.message/cancel
                  ;; A child process exits after cancel; the host ends the
                  ;; SESSION while the agent's context survives in-process.
                  (do (cancel-active!
                       ready-session
                       (:seon.execution/invocation-id message))
                      nil)

                  :seon.execution.message/value-sample
                  (do (if (valid-value-sample? message)
                        (serve-value-sample! host ready-session message)
                        (let [safe-sample
                              (safe-sample-correlation ready-session message)]
                          (send-frame!
                           ready-session
                           (sample-error-frame
                            safe-sample
                            "The parent sent an invalid value sample."))))
                      (recur))

                  :seon.execution.message/shutdown
                  (shutdown-session! ready-session)

                  (do (send-frame!
                       ready-session
                       (if (contains? message :seon.execution/request-id)
                         (sample-error-frame
                          (safe-sample-correlation ready-session message)
                          "The parent sent an invalid value sample.")
                         (invalid-message-frame message)))
                      (recur))))))))
      (catch Throwable _ nil)
      (finally
        (reset! (::live-values session) {::order [] ::values {}})
        (try (.close channel) (catch Throwable _))))))

(defn start!
  "Start the agent host: shared base, contexts, and the UDS acceptor."
  {:malli/schema [:=> [:cat ::start-request] ::host]}
  [{::keys [socket-path eval-threads]
    :as request}]
  (let [writer (context/writer-session
                (select-keys request [::context/writer-socket-path
                                      ::context/database-name
                                      ::context/backend
                                      ::context/database-path]))
        acquired-projection (context/acquire-committed-projection! writer)
        _ (when (:seon/error acquired-projection)
            (context/close-session! writer)
            (throw
              (ex-info
                (get-in acquired-projection
                        [:seon/error :seon.error/message])
                {:seon.error/kind :core-bug
                 :seon.host/projection-error
                 (:seon/error acquired-projection)})))
        projection-state (atom acquired-projection)
        base (context/build-base! writer)
        graduation-report
        (graduate/rebuild!
         {::context/base base
          ::context/registry (::context/registry base)
          ::context/writer writer})
        contexts (atom {})
        eval-pool (Executors/newFixedThreadPool
                   (int (or eval-threads default-eval-threads)))
        watchdog (Executors/newScheduledThreadPool 2)
        _ (try (.delete (File. ^String socket-path)) (catch Throwable _))
        address (UnixDomainSocketAddress/of ^String socket-path)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        host (merge writer
                    {::writer writer
                     ::server server
                     ::base base
                     ::projection-state projection-state
                     ::graduation-report graduation-report
                     ::contexts contexts
                     ::eval-pool eval-pool
                     ::watchdog watchdog
                     ::socket-path socket-path})
        acceptor
        (Thread.
         ^Runnable
         (fn []
           (try
             (loop []
               (let [channel (.accept server)]
                 (doto (Thread. ^Runnable #(serve-session! host channel)
                                (str "seon-host-session-"
                                     (::context/database-name writer)))
                   (.setDaemon true)
                   (.start))
                 (recur)))
             (catch Throwable _ nil)))
         "seon-host-acceptor")]
    (.bind server address)
    (.setDaemon acceptor true)
    (.start acceptor)
    (assoc host ::acceptor acceptor)))

(defn stop!
  "Stop the host acceptor and release its pools and socket."
  {:malli/schema [:=> [:cat ::host] :nil]}
  [{::keys [server eval-pool watchdog socket-path writer]}]
  (try (.close ^ServerSocketChannel server) (catch Throwable _))
  (.shutdownNow ^ExecutorService eval-pool)
  (.shutdownNow ^ScheduledExecutorService watchdog)
  (when writer (context/close-session! writer))
  (when socket-path
    (try (.delete (File. ^String socket-path)) (catch Throwable _)))
  nil)

(defn -main
  "Run one agent host from an EDN configuration argument until killed.

   Usage:
     clojure -M:writer:host -m seon.host \\
       '{:seon.host/socket-path \"tmp/seon-host.sock\"
         :seon.host.context/writer-socket-path
         \"tmp/seon-cluster-default-req.sock\"
         :seon.host.context/database-name \"default\"}'"
  [& [configuration]]
  (let [request (edn/read-string configuration)
        host (start! request)
        report (get-in host [::base ::context/report])]
    (println (str "HOST READY " (::socket-path request)
                  " base-loaded=" (::context/loaded report)
                  "/" (::context/pure-blocks report)
                  " base-failed=" (::context/failed report)
                  " base-excluded=" (::context/excluded report)))
    (flush)
    @(promise)))
