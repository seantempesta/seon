(ns seon.flow.pool
  "Pre-warmed JVM pool for instant agent startup.

   Maintains a pool of idle agent JVMs with Clojure + deps loaded.
   When a task arrives, assigns a warm JVM by sending code via nREPL.

   ## Concurrency Model

   Uses a `LinkedBlockingQueue` for idle JVMs -- `poll` is naturally thread-safe,
   so two threads cannot grab the same JVM. An atom tracks all JVMs (active + idle)
   keyed by port for status reporting and lifecycle management.

   ## Auto-Replenishment

   When a JVM is acquired from the pool, a replacement is immediately spawned in
   the background (via `future`). The pool maintains its target size of idle JVMs.

   ## Health Checks

   A `ScheduledExecutorService` periodically pings each idle JVM via nREPL.
   Unresponsive JVMs are removed and replaced.

   ## Integrant Component

   Register as `:seon.flow/pool` in system.edn. Depends on `:seon.db.datalevin/server`
   so the server is available when agents start. Supports `suspend-key!`/`resume-key`
   to keep pool alive during `(reset)`.

   Usage:
     (def pool (create-pool! {::size 3 ::base-port 7900}))

     ;; Get a warm JVM and load namespace code into it
     (def agent (acquire! pool {::namespace 'seon.trading.signals
                                ::forms ['(defn ema [period data] ...)]}))
     ;; => {::port 7901 ::pid 12345 ::namespace 'seon.trading.signals}

     ;; Return JVM to pool (resets namespace) or dispose
     (release! pool agent)
     (dispose! pool agent)

     ;; Shut down entire pool
     (shutdown! pool)"
  (:require [clojure.edn :as edn]
            [clojure.java.process :as process]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [integrant.core :as ig]
            [nrepl.core :as nrepl]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net InetSocketAddress Socket]
           [java.util.concurrent LinkedBlockingQueue ScheduledExecutorService
                                 Executors TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Constants
;;; ---------------------------------------------------------------------------

(def ^:const default-pool-size 3)
(def ^:const default-base-port 7900)
(def ^:const ready-timeout-ms 15000)
(def ^:const nrepl-eval-timeout-ms 10000)
(def ^:const health-check-interval-ms 30000)
(def ^:const health-check-timeout-ms 5000)
(def ^:const stale-check-timeout-ms
  "TCP connect timeout when checking for stale agent JVMs on startup.
   Short because we're connecting to localhost." 500)
(def ^:const agent-port-min 7900)
(def ^:const agent-port-max 7999)

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::port
                  [:int {:min 7900 :max 7999
                         :description "nREPL port for agent JVM (reserved range)"}])

(schema/register! ::pid
                  [:int {:min 0 :description "OS process ID of agent JVM"}])

(schema/register! ::session-id
                  [:string {:min 4 :max 6
                            :pattern "^[A-Za-z0-9]{4,6}$"
                            :description "Base62 session ID assigned to a claimed JVM"}])

(schema/register! ::status
                  [:enum :idle :active :spawning
                   {:description "Agent JVM lifecycle status"}])

(schema/register! ::namespace
                  [:or :symbol :string
                   {:description "Clojure namespace assigned to agent JVM"}])

(schema/register! ::size
                  [:int {:min 1 :max 50
                         :description "Target number of warm JVMs in pool"}])

(schema/register! ::base-port
                  [:int {:min 7900 :max 7999
                         :description "Starting port for agent JVM range"}])

(schema/register! ::datalevin-uri
                  [:string {:description "Datalevin connection URI for agent JVM"}])

(schema/register! ::setup-ms
                  [:int {:min 0 :description "Milliseconds to set up namespace"}])

(schema/register! ::total
                  [:int {:min 0 :description "Total JVMs in pool"}])

(schema/register! ::idle
                  [:int {:min 0 :description "Idle JVMs available for acquisition"}])

(schema/register! ::active
                  [:int {:min 0 :description "JVMs currently assigned to agents"}])

(schema/register! ::warming?
                  [:boolean {:description "True while pool is still spawning initial JVMs"}])

(schema/register! ::timeout-ms
                  [:int {:min 0
                         :description "Timeout in ms for blocking acquire. 0 = non-blocking."}])

;;; ---------------------------------------------------------------------------
;;; Process spawning
;;; ---------------------------------------------------------------------------

(defn- kill-process!
  "Destroy an agent JVM process."
  [^Process proc]
  (.destroy proc))

(defn- spawn-agent-jvm!
  "Spawn an agent JVM on the given port. Blocks until AGENT_READY signal
   or timeout. Returns {::port ::pid ::process ...} or throws.

   When datalevin-uri is provided, passes it to the agent JVM via --datalevin-uri."
  [port & {:keys [datalevin-uri]}]
  (let [args (cond-> ["clojure" "-M:agent"
                       "--port" (str port)
                       "--namespace" "seon.pool.warm"]
               datalevin-uri (into ["--datalevin-uri" datalevin-uri]))
        ^Process proc (apply process/start {:dir "."} args)
        stdout (BufferedReader. (InputStreamReader. (process/stdout proc)))
        ready? (promise)
        reader-thread (future
                        (try
                          (loop []
                            (when-let [line (.readLine stdout)]
                              (log/debug "Agent JVM stdout" {:port port :line line})
                              (when (.contains ^String line "AGENT_READY")
                                (deliver ready? true))
                              (recur)))
                          (catch Exception e
                            (log/error "Agent JVM reader error"
                                       {:port port :error (.getMessage e)}))))]
    (if (deref ready? ready-timeout-ms nil)
      (let [pid (.pid proc)]
        (log/info "Agent JVM ready" {:port port :pid pid})
        {::port port
         ::pid pid
         ::process proc
         ::reader reader-thread
         ::status :idle
         ::datalevin-uri datalevin-uri})
      (do
        (.destroy proc)
        (throw (ex-info "Agent JVM failed to start within timeout"
                        {:port port :timeout-ms ready-timeout-ms}))))))

;;; ---------------------------------------------------------------------------
;;; nREPL communication
;;; ---------------------------------------------------------------------------

(defn nrepl-eval!
  "Evaluate code on an agent JVM via nREPL. Returns the result value string
   or throws on error."
  [port code]
  (with-open [conn (nrepl/connect :port port)]
    (let [client (nrepl/client conn nrepl-eval-timeout-ms)
          responses (nrepl/message client {:op "eval" :code code})
          errors (keep :err responses)
          ex (first (keep :ex responses))
          values (keep :value responses)]
      (when (or ex (seq errors))
        (throw (ex-info "nREPL eval error"
                        {:port port
                         :code code
                         :ex ex
                         :errors (vec errors)})))
      (last values))))

(defn- setup-namespace!
  "Create a namespace on the agent JVM and eval forms into it."
  [port ns-sym forms]
  (let [ns-str (str ns-sym)
        setup-code (str "(do"
                        " (create-ns '" ns-str ")"
                        " (in-ns '" ns-str ")"
                        " (clojure.core/refer-clojure)"
                        " (require '[malli.core :as m])"
                        " (require '[clojure.core.async :as async])"
                        " (require '[cheshire.core :as json])"
                        " :ok)")]
    (nrepl-eval! port setup-code)
    (doseq [form forms]
      (nrepl-eval! port (pr-str form)))
    ns-str))

(defn- reset-namespace!
  "Remove the assigned namespace from an agent JVM, returning it to idle."
  [port ns-sym]
  (let [code (str "(do"
                  " (remove-ns '" (str ns-sym) ")"
                  " (in-ns 'seon.pool.warm)"
                  " :reset)")]
    (nrepl-eval! port code)))

;;; ---------------------------------------------------------------------------
;;; Pool internals
;;; ---------------------------------------------------------------------------

(defn- build-datalevin-uri
  "Build a Datalevin URI for an agent JVM.

   Format: dtlv://datalevin:datalevin@127.0.0.1:8898/agent-{port}"
  [datalevin-port agent-port]
  (when datalevin-port
    (format "dtlv://datalevin:datalevin@127.0.0.1:%d/agent-%d"
            datalevin-port agent-port)))

(defn- allocate-port!
  "Atomically allocate the next available port from the pool.
   Uses swap-vals! to ensure two concurrent callers never get the same port."
  [pool-state]
  (let [[old-state _] (swap-vals! pool-state update ::next-port inc)]
    (::next-port old-state)))

(defn- spawn-and-enqueue!
  "Spawn a new agent JVM and add it to the pool. Returns the JVM map on success,
   nil on failure. Thread-safe -- called from background futures.

   When `remaining-warmup` atom is provided (during initial pool creation),
   decrements it after enqueuing. When it reaches zero, sets ::warming? false
   on the pool state and logs that the pool is ready."
  [pool-state ^LinkedBlockingQueue idle-queue datalevin-port
   & {:keys [remaining-warmup]}]
  (let [port (allocate-port! pool-state)]
    (try
      (let [uri (build-datalevin-uri datalevin-port port)
            jvm (spawn-agent-jvm! port :datalevin-uri uri)]
        ;; Track in all-jvms map
        (swap! pool-state assoc-in [::all-jvms port] jvm)
        ;; Add to idle queue
        (.put idle-queue jvm)
        (log/debug "JVM spawned and enqueued" {:port port})
        ;; Track warmup completion
        (when remaining-warmup
          (let [remaining (swap! remaining-warmup dec)]
            (when (zero? remaining)
              (swap! pool-state assoc ::warming? false)
              (log/info "Pool ready" {:idle (.size idle-queue)
                                       :total (count (::all-jvms @pool-state))}))))
        jvm)
      (catch Exception e
        (log/error "Failed to spawn agent JVM"
                   {:port port :error (.getMessage e)})
        ;; Still decrement remaining on failure so warming eventually completes
        (when remaining-warmup
          (let [remaining (swap! remaining-warmup dec)]
            (when (zero? remaining)
              (swap! pool-state assoc ::warming? false)
              (log/info "Pool ready (some JVMs failed to spawn)"
                        {:idle (.size idle-queue)
                         :total (count (::all-jvms @pool-state))}))))
        nil))))

(defn- replenish-pool!
  "Spawn replacement JVMs in the background to maintain target pool size.
   Non-blocking -- spawns via future."
  [pool-state ^LinkedBlockingQueue idle-queue]
  (let [{::keys [target-size datalevin-port shutdown?]} @pool-state
        current-idle (.size idle-queue)]
    (when (and (not shutdown?)
               (< current-idle target-size))
      (let [deficit (- target-size current-idle)]
        (log/debug "Replenishing pool" {:deficit deficit :current-idle current-idle})
        (dotimes [_ deficit]
          (future
            (try
              (spawn-and-enqueue! pool-state idle-queue datalevin-port)
              (catch Exception e
                (log/error "Replenishment failed" {:error (.getMessage e)})))))))))

;;; ---------------------------------------------------------------------------
;;; Health checks
;;; ---------------------------------------------------------------------------

(defn- health-check-jvm!
  "Ping a single idle JVM. Returns true if healthy, false otherwise."
  [port]
  (try
    (let [result (nrepl-eval! port ":ok")]
      (= result ":ok"))
    (catch Exception _
      false)))

(defn- run-health-checks!
  "Check health of all idle JVMs. Removes unhealthy ones and triggers replenishment."
  [pool-state ^LinkedBlockingQueue idle-queue]
  (when-not (::shutdown? @pool-state)
    (let [idle-jvms (vec (.toArray idle-queue))]
      (doseq [jvm idle-jvms]
        (when-not (health-check-jvm! (::port jvm))
          (log/warn "Unhealthy JVM detected, removing" {:port (::port jvm)})
          ;; Remove from idle queue
          (.remove idle-queue jvm)
          ;; Kill the process
          (try
            (kill-process! (::process jvm))
            (catch Exception _))
          ;; Remove from tracking
          (swap! pool-state update ::all-jvms dissoc (::port jvm))))
      ;; Replenish if needed
      (replenish-pool! pool-state idle-queue))))

(defn- start-health-checker!
  "Start periodic health checks using a ScheduledExecutorService."
  [pool-state idle-queue]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleAtFixedRate scheduler
                          ^Runnable (fn []
                                      (try
                                        (run-health-checks! pool-state idle-queue)
                                        (catch Exception e
                                          (log/error "Health check error"
                                                     {:error (.getMessage e)}))))
                          health-check-interval-ms
                          health-check-interval-ms
                          TimeUnit/MILLISECONDS)
    scheduler))

(defn- stop-health-checker!
  "Stop the health check scheduler."
  [^ScheduledExecutorService scheduler]
  (when scheduler
    (.shutdown scheduler)
    (try
      (.awaitTermination scheduler 5 TimeUnit/SECONDS)
      (catch InterruptedException _
        (.shutdownNow scheduler)))))

;;; ---------------------------------------------------------------------------
;;; Stale process cleanup
;;; ---------------------------------------------------------------------------

(defn- port-bound?
  "Check if a port is currently bound by attempting a TCP connection.
   Returns true if something is listening on the port, false otherwise."
  [port]
  (try
    (with-open [socket (Socket.)]
      (.connect socket (InetSocketAddress. "127.0.0.1" (int port))
                stale-check-timeout-ms)
      true)
    (catch Exception _
      false)))

(defn- find-pid-on-port
  "Find the PID of the process listening on the given port using lsof.
   Returns the PID string or nil if not found."
  [port]
  (try
    (let [{:keys [exit out]} (shell/sh "lsof" "-ti" (str ":" port))]
      (when (zero? exit)
        (let [pid (str/trim out)]
          (when (seq pid)
            ;; lsof can return multiple PIDs (one per line), take the first
            (first (str/split-lines pid))))))
    (catch Exception e
      (log/debug "lsof failed" {:port port :error (.getMessage e)})
      nil)))

(defn- kill-pid!
  "Kill a process by PID. Returns true if kill succeeded."
  [pid-str]
  (try
    (let [{:keys [exit]} (shell/sh "kill" pid-str)]
      (zero? exit))
    (catch Exception e
      (log/debug "kill failed" {:pid pid-str :error (.getMessage e)})
      false)))

(defn cleanup-stale-agents!
  "Clean up stale agent JVM processes from a previous Seon session.

   Checks each port in the agent range (base-port to base-port + size - 1)
   for bound sockets. If a port is bound, finds and kills the process.
   Only checks ports in the 7900-7999 safety range.

   Returns the count of stale processes cleaned up."
  [base-port size]
  (let [ports (range base-port (+ base-port size))
        ;; Safety: only kill processes on ports in agent range
        safe-ports (filter #(and (>= % agent-port-min)
                                 (<= % agent-port-max))
                           ports)]
    (when (< (count safe-ports) (count ports))
      (log/warn "Some ports outside agent range, skipping"
                {:requested (count ports) :safe (count safe-ports)}))
    (reduce
     (fn [cleaned port]
       (if (port-bound? port)
         (if-let [pid (find-pid-on-port port)]
           (do
             (log/warn "Killed stale agent JVM"
                       {:port port :pid pid})
             (kill-pid! pid)
             (inc cleaned))
           (do
             (log/warn "Port bound but could not find PID"
                       {:port port})
             cleaned))
         cleaned))
     0
     safe-ports)))

;;; ---------------------------------------------------------------------------
;;; Pool management (public API)
;;; ---------------------------------------------------------------------------

(defn create-pool!
  "Create a pre-warmed JVM pool. Spawns `size` agent JVMs concurrently in
   background futures -- returns immediately without blocking.

   Cleans up any stale agent JVM processes from a previous crash before
   spawning new JVMs.

   Options:
     ::size           - Number of warm JVMs (default 3)
     ::base-port      - Starting port number (default 7900)
     ::datalevin-port - Datalevin server port for agent connections (optional)

   Returns pool map containing:
     ::state       - Atom with pool bookkeeping (includes ::warming? flag)
     ::idle-queue  - LinkedBlockingQueue of idle JVMs
     ::scheduler   - ScheduledExecutorService for health checks

   The pool is usable immediately but may have no idle JVMs until warming
   completes (~2-3s). Check (::warming? @(::state pool)) to see if the
   pool is still starting up."
  [{::keys [size base-port datalevin-port]
    :or {size default-pool-size
         base-port default-base-port}}]
  (log/info "Creating agent pool" {:size size :base-port base-port
                                    :datalevin-port datalevin-port})
  ;; Clean up stale agent JVMs from previous crash
  (let [cleaned (cleanup-stale-agents! base-port size)]
    (when (pos? cleaned)
      (log/info "Cleaned up stale agent JVMs" {:count cleaned})))
  (let [idle-queue (LinkedBlockingQueue.)
        pool-state (atom {::all-jvms {}
                          ::session->port {}
                          ::target-size size
                          ::next-port base-port
                          ::datalevin-port datalevin-port
                          ::shutdown? false
                          ::warming? true})
        ;; Track how many JVMs still need to finish spawning
        remaining-warmup (atom size)
        ;; Spawn all JVMs concurrently in background -- do NOT block
        _ (doall
            (for [_ (range size)]
              (future
                (try
                  (spawn-and-enqueue! pool-state idle-queue datalevin-port
                                      :remaining-warmup remaining-warmup)
                  (catch Exception e
                    (log/error "Spawn failed during warmup"
                               {:error (.getMessage e)}))))))
        scheduler (start-health-checker! pool-state idle-queue)
        pool {::state pool-state
              ::idle-queue idle-queue
              ::scheduler scheduler}]
    (log/info "Pool warming in background" {:size size})
    pool))

(defn pool-warming?
  "Returns true if the pool is still spawning its initial JVMs."
  [pool]
  (boolean (::warming? @(::state pool))))

(defn await-warm
  "Block until the pool finishes warming (all initial JVMs spawned).
   Returns true if pool became warm within timeout-ms, false on timeout.
   Polls every 100ms."
  [pool timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (not (pool-warming? pool)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 100) (recur))))))

(declare dispose!)

(defn- activate-jvm!
  "Set up a JVM from the idle queue for active use. Loads namespace and forms,
   updates tracking, triggers replenishment. Returns agent handle map."
  [state ^LinkedBlockingQueue idle-queue jvm namespace forms]
  (let [port (::port jvm)
        start (System/nanoTime)
        _ (setup-namespace! port namespace (or forms []))
        elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
    (log/info "Acquired agent" {:port port
                                 :namespace namespace
                                 :setup-ms (long elapsed-ms)})
    ;; Update tracking to active status
    (swap! state update-in [::all-jvms port]
           assoc ::status :active ::namespace namespace)
    ;; Auto-replenish in background
    (replenish-pool! state idle-queue)
    ;; Return agent handle
    (assoc jvm ::status :active
                ::namespace namespace
                ::setup-ms (long elapsed-ms))))

(defn acquire!
  "Acquire a warm JVM from the pool and load namespace code into it.

   By default, non-blocking: returns nil immediately if no JVM available.
   When ::timeout-ms is provided, blocks up to that many milliseconds
   waiting for a JVM to become available.

   Options:
     ::namespace  - Namespace symbol to create
     ::forms      - Vector of forms to eval in the namespace (optional)
     ::timeout-ms - Milliseconds to wait for a JVM (optional, nil = non-blocking)

   Returns agent handle map or nil if pool exhausted / timeout."
  [pool {::keys [namespace forms timeout-ms]}]
  (let [{::keys [idle-queue state]} pool
        jvm (if timeout-ms
              (do
                (log/debug "Waiting for agent JVM" {:timeout-ms timeout-ms})
                (.poll ^LinkedBlockingQueue idle-queue
                       (long timeout-ms) TimeUnit/MILLISECONDS))
              (.poll ^LinkedBlockingQueue idle-queue))]
    (if-not jvm
      (do (log/warn "Pool exhausted, no idle JVMs available"
                    (cond-> {}
                      timeout-ms (assoc :timeout-ms timeout-ms)))
          ;; Trigger replenishment even when exhausted
          (replenish-pool! state idle-queue)
          nil)
      (activate-jvm! state idle-queue jvm namespace forms))))

(defn acquire!!
  "Acquire a warm JVM from the pool, blocking indefinitely until one is
   available. Uses LinkedBlockingQueue.take which blocks until an element
   is available.

   WARNING: This will block forever if the pool is shut down or no JVMs
   are ever returned. Prefer acquire! with ::timeout-ms for production use.

   Options:
     ::namespace - Namespace symbol to create
     ::forms     - Vector of forms to eval in the namespace (optional)

   Returns agent handle map (never nil)."
  [pool {::keys [namespace forms]}]
  (let [{::keys [idle-queue state]} pool]
    (log/debug "Blocking acquire, waiting indefinitely for agent JVM")
    (let [jvm (.take ^LinkedBlockingQueue idle-queue)]
      (activate-jvm! state idle-queue jvm namespace forms))))

(defn release!
  "Return an agent JVM to the pool. Resets the namespace so it can be reused."
  [pool {::keys [port namespace]}]
  (let [{::keys [idle-queue state]} pool]
    (try
      (reset-namespace! port namespace)
      ;; Update tracking back to idle
      (swap! state update-in [::all-jvms port]
             assoc ::status :idle ::namespace nil)
      ;; Return to idle queue
      (.put ^LinkedBlockingQueue idle-queue
            (assoc (get-in @state [::all-jvms port])
                   ::status :idle ::namespace nil))
      (log/info "Released agent back to pool" {:port port})
      (catch Exception e
        (log/warn "Failed to reset JVM, disposing" {:port port :error (.getMessage e)})
        (dispose! pool {::port port})))))

(defn dispose!
  "Kill an agent JVM and spawn a replacement in the background."
  [pool {::keys [port]}]
  (let [{::keys [idle-queue state]} pool
        jvm (get-in @state [::all-jvms port])]
    (when jvm
      ;; Remove from idle queue if present
      (.remove ^LinkedBlockingQueue idle-queue jvm)
      ;; Kill the process
      (try
        (kill-process! (::process jvm))
        (catch Exception _))
      ;; Remove from tracking
      (swap! state update ::all-jvms dissoc port)
      (log/info "Disposed agent JVM" {:port port})
      ;; Spawn replacement in background
      (replenish-pool! state idle-queue))))

;;; ---------------------------------------------------------------------------
;;; Session-based claiming (Track 2: Unified Agent Runtime)
;;; ---------------------------------------------------------------------------

(defn claim!
  "Claim an idle JVM for a session. Assigns session-id, sets up namespace,
   and injects *ctx* via nREPL eval. Returns JVM handle with session-id,
   or nil if no JVM available within timeout.

   Options:
     ::session-id  - Required. 4-char hex session ID
     ::namespace   - Required. Namespace symbol to create
     ::forms       - Optional. Vector of forms to eval
     ::timeout-ms  - Optional. Ms to wait for idle JVM (default: 30000)
     ::ctx-value   - Optional. Map to inject as *ctx* value

   Returns agent handle map with ::session-id, ::port, etc. or nil."
  [pool {::keys [session-id namespace forms timeout-ms ctx-value]}]
  (let [timeout-ms (or timeout-ms 30000)
        handle (acquire! pool {::namespace namespace
                               ::forms forms
                               ::timeout-ms timeout-ms})]
    (when handle
      (let [port (::port handle)]
        ;; Track session-id on the JVM
        (swap! (::state pool) (fn [s]
                                (-> s
                                    (assoc-in [::all-jvms port ::session-id] session-id)
                                    (assoc-in [::session->port session-id] port))))
        ;; Inject *ctx* via nREPL eval if ctx-value provided
        ;; Filter out non-serializable values (live connections, atoms, etc.)
        ;; The agent JVM creates its own connections during setup.
        ;; We serialize ctx as EDN string and read it on the agent side
        ;; to avoid syntax-quote issues with symbols/special values.
        (when ctx-value
          (let [ns-sym namespace
                serializable-ctx (reduce-kv
                                  (fn [acc k v]
                                    (try
                                      (let [s (pr-str v)]
                                        (edn/read-string s)
                                        (assoc acc k v))
                                      (catch Exception _
                                        (log/debug "Stripping non-serializable ctx key for nREPL transfer" {:key k :type (type v)})
                                        acc)))
                                  {}
                                  ctx-value)
                ctx-edn (pr-str serializable-ctx)
                code (str "(do (intern '" ns-sym " '*ctx*"
                          " (atom (clojure.edn/read-string " (pr-str ctx-edn) ")))"
                          " (.setDynamic (resolve (symbol \"" ns-sym "\" \"*ctx*\")) true)"
                          " :ok)")]
            (nrepl-eval! port code)))
        (assoc handle ::session-id session-id)))))

(defn get-jvm-by-session
  "Look up a JVM by session-id. Returns JVM map or nil."
  [pool session-id]
  (let [state @(::state pool)]
    (when-let [port (get-in state [::session->port session-id])]
      (get-in state [::all-jvms port]))))

(defn release-session!
  "Release a claimed JVM back to the pool by session-id.
   Clears session tracking, resets namespace, returns JVM to idle."
  [pool session-id]
  (if-let [jvm (get-jvm-by-session pool session-id)]
    (let [port (::port jvm)
          ns-sym (::namespace jvm)]
      ;; Clear session tracking
      (swap! (::state pool) (fn [s]
                              (-> s
                                  (update-in [::all-jvms port] dissoc ::session-id)
                                  (update ::session->port dissoc session-id))))
      ;; Delegate to existing release!
      (release! pool {::port port ::namespace ns-sym})
      (log/info "Released session from pool" {:session-id session-id :port port})
      true)
    (do
      (log/warn "No JVM found for session" {:session-id session-id})
      false)))

(defn pool-status
  "Return current pool status.

   Response keys:
     ::total    - Total tracked JVMs (active + idle)
     ::idle     - Number of idle JVMs available
     ::active   - Number of actively assigned JVMs
     ::warming? - True if pool is still spawning initial JVMs
     ::jvms     - Vector of per-JVM status maps"
  [pool]
  (let [{::keys [idle-queue state]} pool
        state-val @state
        all-jvms (vals (::all-jvms state-val))
        idle-count (.size ^LinkedBlockingQueue idle-queue)]
    {::total (count all-jvms)
     ::idle idle-count
     ::active (- (count all-jvms) idle-count)
     ::warming? (boolean (::warming? state-val))
     ::jvms (mapv #(select-keys % [::port ::pid ::status ::namespace ::datalevin-uri])
                  all-jvms)}))

(defn shutdown!
  "Shut down the entire pool, killing all JVMs and stopping the health checker."
  [pool]
  (log/info "Shutting down pool")
  (let [{::keys [idle-queue state scheduler]} pool]
    ;; Mark as shutting down to prevent replenishment
    (swap! state assoc ::shutdown? true)
    ;; Stop health checker
    (stop-health-checker! scheduler)
    ;; Kill all JVMs
    (doseq [[_ jvm] (::all-jvms @state)]
      (try
        (kill-process! (::process jvm))
        (catch Exception _)))
    ;; Clear state
    (.clear ^LinkedBlockingQueue idle-queue)
    (swap! state assoc ::all-jvms {})
    (log/info "Pool shut down")))

;;; ---------------------------------------------------------------------------
;;; Integrant Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.flow/pool
  [_ {:keys [size base-port datalevin-server enabled?]
      :or {size default-pool-size
           base-port default-base-port
           enabled? true}}]
  (if enabled?
    (let [datalevin-port (when datalevin-server (:port datalevin-server))]
      (log/info "Starting agent pool component" {:size size :base-port base-port
                                                  :datalevin-port datalevin-port})
      (create-pool! {::size size
                     ::base-port base-port
                     ::datalevin-port datalevin-port}))
    (do
      (log/info "Agent pool disabled for this profile")
      nil)))

(defmethod ig/halt-key! :seon.flow/pool
  [_ pool]
  (when pool
    (log/info "Stopping agent pool component")
    (shutdown! pool)))

;; Keep pool alive during (reset) -- JVMs are expensive to spawn
(defmethod ig/suspend-key! :seon.flow/pool [_ pool] pool)

(defmethod ig/resume-key :seon.flow/pool
  [key opts old-opts old-pool]
  (if (and (= (:size opts) (:size old-opts))
           (= (:base-port opts) (:base-port old-opts)))
    old-pool
    (do (ig/halt-key! key old-pool)
        (ig/init-key key opts))))

;;; ---------------------------------------------------------------------------
;;; REPL exploration
;;; ---------------------------------------------------------------------------

(comment
  ;; Create a pool of 2 warm JVMs
  (def pool (create-pool! {::size 2 ::base-port 7900}))

  ;; Check status
  (pool-status pool)

  ;; Acquire and load code
  (def agent1 (acquire! pool {::namespace 'seon.trading.signals
                              ::forms ['(defn ema [period data]
                                          (let [k (/ 2.0 (inc period))]
                                            (reduce (fn [prev x]
                                                      (+ (* k x) (* (- 1 k) prev)))
                                                    (first data)
                                                    (rest data))))
                                       '(def schema
                                          [:map
                                           [:period :int]
                                           [:data [:vector :double]]])]}))

  ;; Test the loaded code
  (nrepl-eval! (::port agent1) "(ema 3 [1.0 2.0 3.0 4.0 5.0])")

  ;; Release back to pool
  (release! pool agent1)

  ;; Create pool with Datalevin support
  (def pool (create-pool! {::size 2 ::base-port 7900 ::datalevin-port 8898}))

  ;; Shut down
  (shutdown! pool)
  )
