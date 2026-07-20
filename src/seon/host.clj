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

   Seams recorded for U1.5/U2 (deliberately unbuilt here):
   - these message schemas are the JVM projection of `seon.execution`'s
     contract; promoting that namespace to `.cljc` at cutover moves them;
   - `ready`'s runtime-version field is named `bun-version` by the child
     schema — renaming rides the same promotion;
   - the host trusts its JVM classpath instead of hashing a Bun artifact,
     so it echoes the startup's declared artifact identity;
   - eval-batch database recording (eval rows, receipts, corpus tee, run
     fence) stays with `seon.eval`; the host returns empty
     `:seon.eval/ids` until that seam lands;
   - render-prompt!/render-agent-view! stay pod-served by design (the pod
     keeps rendering); routing them here answers with a steering error."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [sci.core :as sci]
            [seon.db.transport.uds :as uds]
            [seon.host.context :as context]
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
  [::contexts ::contexts]])

(def ^:private default-eval-threads 10)

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

(defn- eval-entry!
  "Evaluate one parsed entry in the agent context; every outcome a value."
  [ctx entry]
  (case (:seon.repl/kind entry)
    :form
    (try
      (wire-safe-value
       {:seon.eval/ok? true
        :seon.eval/value (sci/eval-string* ctx (entry-source entry))})
      (catch Throwable throwable
        (let [message (str (first (str/split-lines
                                   (str (.getMessage throwable)))))
              interrupted? (boolean (re-find #"deadline exceeded|interrupt"
                                             message))]
          {:seon.eval/ok? false
           :seon.eval/interrupted? interrupted?
           :seon/error (error-value message :agent)})))

    :read
    {:seon.eval/ok? false
     :seon/error (error-value
                  (str "The form could not be read: "
                       (or (:seon.repl/message entry) "read error"))
                  :agent)}

    ;; comment/prose entries evaluate nothing.
    {:seon.eval/ok? true :seon.eval/skipped? true}))

(defn- eval-batch-result
  "Serve `seon.execution.runtime/eval-batch!`'s engine layer over sci.

   Evaluates each parsed entry in order in the agent's context. The
   database-recording layer (eval rows, receipts, corpus tee, run fence)
   is `seon.eval`-owned and NOT rebuilt here — `:seon.eval/ids` stays
   empty until that recorded U2 seam lands; the per-form envelopes ride
   `:seon.host/results` so callers see every value."
  [ctx {parsed :seon.eval/parsed}]
  (let [results
        (reduce (fn [acc entry]
                  (let [envelope (eval-entry! ctx entry)]
                    (if (:seon.eval/interrupted? envelope)
                      (reduced (conj acc envelope))
                      (conj acc envelope))))
                []
                (or parsed []))
        evaluated (remove :seon.eval/skipped? results)]
    {:seon.eval/ids []
     :seon.eval/n-ok (count (filter :seon.eval/ok? evaluated))
     :seon.eval/n-fail (count (remove :seon.eval/ok? evaluated))
     :seon.host/results (vec results)}))

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
            (let [result (eval-batch-result (::ctx session)
                                            (first arguments))]
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
                      :agent)})
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
           (error-frame invocation-id (::error bounded) database)))))))

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
      (when-let [{::keys [worker future]} @(::active-run session)]
        (when (realized? worker) (.interrupt ^Thread @worker))
        ;; The worker settles the canceled error itself; bound the wait so
        ;; a wedged native call cannot wedge the reader.
        (try (.get ^java.util.concurrent.Future future
                   2000 TimeUnit/MILLISECONDS)
             (catch Throwable _ nil)))
      (settle! session token
               (error-frame invocation-id
                            (error-value "The invocation was canceled."
                                         :agent)
                            (get-in token [::invocation :seon.db/db])))
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
                ctx (-> (swap! (::contexts session)
                               (fn [contexts]
                                 (if (contains? contexts agent-id)
                                   contexts
                                   (assoc contexts agent-id
                                          (context/fork-context
                                           (::base host))))))
                        (get agent-id))]
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
                 ::contexts (::contexts host)
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

                  :seon.execution.message/shutdown
                  (shutdown-session! ready-session)

                  (do (send-frame! ready-session
                                   (invalid-message-frame message))
                      (recur))))))))
      (catch Throwable _ nil)
      (finally
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
        base (context/build-base! writer)
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
        host (start! request)]
    (println (str "HOST READY " (::socket-path request)
                  " base-loaded=" (get-in host [::base ::context/report
                                                ::context/loaded])
                  "/" (get-in host [::base ::context/report
                                    ::context/pure-blocks])))
    (flush)
    @(promise)))
