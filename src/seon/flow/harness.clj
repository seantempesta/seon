(ns seon.flow.harness
  "Orchestrator-side flow process for a single namespace.

   Sits in the orchestrator's flow topology and routes cross-namespace
   function call requests to an agent JVM via TCP, returning replies.

   Tracks pending request count for backpressure. When the queue is at
   capacity, returns a typed :overload error immediately.

   Ports:
     Flow ins:
       :seon.flow.in/request    - Cross-namespace call requests
     Flow outs:
       :seon.flow.out/reply     - Responses (ok, error, overload)
       :seon.flow.out/error     - Error reports (subset of replies)
       :seon.flow.out/event     - Observability events
     In-ports (from TCP / agent JVM):
       :seon.flow.in/jvm-reply  - Replies from agent JVM
     Out-ports (to TCP / agent JVM):
       :seon.flow.out/jvm-request - Requests forwarded to agent JVM"
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [seon.flow.harness.channel :as channel]
            [seon.flow.msg :as msg]
            [seon.flow.pool :as pool]
            [seon.schema :as schema])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::namespace
  [:string {:min 1 :description "Namespace this harness manages"}])

(schema/register! ::queue-cap
  [:int {:min 1 :description "Max pending requests before overload (default 32)"}])

(schema/register! ::pending
  [:int {:min 0 :description "Current number of pending requests"}])

(schema/register! ::error-count
  [:int {:min 0 :description "Cumulative error count"}])

;;; ---------------------------------------------------------------------------
;;; Event Construction
;;; ---------------------------------------------------------------------------

(defn- make-event
  "Build an observability event envelope."
  [ns-str event-kind & {:as extra-payload}]
  (cond-> {::msg/id (random-uuid)
           ::msg/version 1
           ::msg/type :event
           ::msg/event-kind event-kind
           ::msg/from-ns ns-str
           ::msg/created-at (Instant/now)}
    (seq extra-payload) (assoc ::msg/payload extra-payload)))

(defn- make-overload-reply
  "Build an overload error reply for the given request."
  [request ns-str]
  (cond-> {::msg/id (::msg/id request)
           ::msg/version 1
           ::msg/type :reply
           ::msg/status :overload
           ::msg/error-type :overload
           ::msg/error-message "Namespace queue at capacity"
           ::msg/from-ns ns-str
           ::msg/duration-ms 0}
    (::msg/trace-id request) (assoc ::msg/trace-id (::msg/trace-id request))))

;;; ---------------------------------------------------------------------------
;;; Step Function
;;; ---------------------------------------------------------------------------

(defn namespace-step
  "Orchestrator-side namespace process step-fn.

   Routes cross-namespace requests to an agent JVM via TCP and returns
   replies. Tracks pending count for backpressure.

   Params:
     ::namespace - Namespace string this process manages
     ::queue-cap - Max pending requests (default 32)

   For MVP, the TCP server and JVM channels are injected externally
   via ::flow/in-ports and ::flow/out-ports in the init args map.
   Real pool integration deferred to Step 5.

   In-ports (from TCP):
     :seon.flow.in/jvm-reply - Replies from agent JVM

   Out-ports (to TCP):
     :seon.flow.out/jvm-request - Requests forwarded to agent JVM"
  ;; describe
  ([]
   {:ins {:seon.flow.in/request "Cross-namespace call requests"}
    :outs {:seon.flow.out/reply "Responses to callers"
           :seon.flow.out/error "Error reports"
           :seon.flow.out/event "Observability events"}
    :params {::namespace "Namespace string"
             ::queue-cap "Max pending requests (default 32)"}
    :workload :io})

  ;; init
  ([{::keys [namespace queue-cap] :as args}]
   (let [ns-str (or namespace "unknown")
         cap (or queue-cap 32)
         ;; Allow external injection of in-ports/out-ports for testing
         in-ports (::in-ports args)
         out-ports (::out-ports args)]
     (cond-> {::namespace ns-str
              ::queue-cap cap
              ::pending 0
              ::error-count 0}
       in-ports (assoc :clojure.core.async.flow/in-ports in-ports)
       out-ports (assoc :clojure.core.async.flow/out-ports out-ports))))

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop state
     :clojure.core.async.flow/pause state
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id msg]
   (let [ns-str (::namespace state)]
     (case input-id
       ;; Incoming cross-ns request
       :seon.flow.in/request
       (if (>= (::pending state) (::queue-cap state))
         ;; Overload: return error immediately + emit event
         [state
          {:seon.flow.out/reply [(make-overload-reply msg ns-str)]
           :seon.flow.out/event [(make-event ns-str :overload
                                             :seon.flow.harness/queue-cap (::queue-cap state)
                                             :seon.flow.harness/pending (::pending state))]}]
         ;; Forward to agent JVM
         [(update state ::pending inc)
          {:seon.flow.out/jvm-request [msg]}])

       ;; Reply from agent JVM
       :seon.flow.in/jvm-reply
       (let [error? (not= (::msg/status msg) :ok)
             state' (-> state
                        (update ::pending dec)
                        (cond-> error? (update ::error-count inc)))
             event-kind (if error? :error :ok)]
         [state'
          (cond-> {:seon.flow.out/reply [msg]
                   :seon.flow.out/event [(make-event ns-str event-kind)]}
            error? (assoc :seon.flow.out/error [msg]))])

       ;; Unknown input
       [state nil]))))

;;; ---------------------------------------------------------------------------
;;; Production JVM Init (Pool Integration)
;;; ---------------------------------------------------------------------------

(defn start-namespace-jvm!
  "Acquire a pool JVM, start TCP bridge, load bridge code via nREPL.

   Returns a map compatible with namespace-step init (in-ports/out-ports)
   plus handles for cleanup.

   Request keys:
     ::pool       - JVM pool (from Integrant system)
     ::namespace  - Namespace string this harness manages
     ::timeout-ms - Pool acquire timeout in ms (default 30000)

   Returns:
     ::in-ports   - {jvm-reply channel} for namespace-step
     ::out-ports  - {jvm-request channel} for namespace-step
     ::jvm        - Pool JVM handle (for release)
     ::tcp-server - TCP server handle (for cleanup)
     ::pool       - Pool reference (for release)"
  [{::keys [pool namespace timeout-ms]}]
  (let [timeout (or timeout-ms 30000)
        ;; 1. Acquire JVM from pool
        jvm (pool/acquire! pool {::pool/namespace (symbol namespace)
                                 ::pool/timeout-ms timeout})
        _ (when-not jvm
            (throw (ex-info "Failed to acquire JVM from pool"
                            {::namespace namespace ::timeout-ms timeout})))
        nrepl-port (::pool/port jvm)
        ;; 2. Start TCP server on random port
        tcp-server (channel/start-server! {::channel/port 0})
        bridge-port (::channel/port tcp-server)]
    (try
      ;; 3. Load bridge code into agent JVM via nREPL
      ;;    Agent JVM has src/ on classpath so we can require directly
      (pool/nrepl-eval! nrepl-port
        (pr-str
         '(do
            (require '[clojure.core.async :as async])
            (require '[seon.flow.harness.channel :as channel])
            (require '[seon.flow.harness.bridge :as bridge])
            :ok)))

      ;; 4. Connect agent JVM back to our TCP server and start request loop
      (pool/nrepl-eval! nrepl-port
        (str "(do"
             " (def ^:private bridge-tcp"
             "   (channel/connect! {::channel/host \"localhost\""
             "                      ::channel/port " bridge-port "}))"
             " (def ^:private bridge-ns \"" namespace "\")"
             " (def ^:private bridge-loop"
             "   (future"
             "     (loop []"
             "       (when-let [request (async/<!! (::channel/in-ch bridge-tcp))]"
             "         (let [reply (bridge/execute-local request"
             "                       {::bridge/namespace bridge-ns})]"
             "           (async/>!! (::channel/out-ch bridge-tcp) reply))"
             "         (recur)))))"
             " :bridge-started)"))

      ;; Return channels + handles
      {::in-ports  {:seon.flow.in/jvm-reply (::channel/in-ch tcp-server)}
       ::out-ports {:seon.flow.out/jvm-request (::channel/out-ch tcp-server)}
       ::jvm jvm
       ::tcp-server tcp-server
       ::pool pool
       ::namespace namespace}
      (catch Exception e
        ;; Cleanup on failure
        ((::channel/close! tcp-server))
        (pool/release! pool jvm)
        (throw (ex-info "Failed to start namespace JVM"
                        {::namespace namespace
                         ::nrepl-port nrepl-port
                         ::bridge-port bridge-port}
                        e))))))

(defn stop-namespace-jvm!
  "Shut down a namespace JVM started by start-namespace-jvm!.

   Closes TCP connection, releases JVM back to pool.

   Request keys:
     ::jvm        - Pool JVM handle
     ::tcp-server - TCP server handle
     ::pool       - Pool reference"
  [{::keys [jvm tcp-server pool]}]
  (when tcp-server
    (try ((::channel/close! tcp-server)) (catch Exception _)))
  (when (and pool jvm)
    (try (pool/release! pool jvm) (catch Exception _))))

;;; ---------------------------------------------------------------------------
;;; *ctx* Persistence
;;; ---------------------------------------------------------------------------

(defn- serializable?
  "Test if a value can round-trip through EDN."
  [v]
  (try
    (let [s (pr-str v)]
      (edn/read-string s)
      true)
    (catch Exception _
      false)))

(defn- filter-serializable
  "Filter a map to only serializable key-value pairs. Logs warnings for skipped keys.
   Always strips ::conn (runtime handle)."
  [m]
  (reduce-kv
   (fn [acc k v]
     (cond
       (= k ::conn) acc
       (serializable? v) (assoc acc k v)
       :else (do (log/warn "Skipping non-serializable key in *ctx*" {:key k})
                 acc)))
   {}
   m))

(defn persist-ctx!
  "Save serializable *ctx* data to Datalevin. Warns on non-serializable values.

   Request keys:
     ::ctx       - Required. Atom or map containing the ctx data
     ::namespace - Required. Namespace string for isolation
     ::conn      - Required. Datalevin connection for storage

   Returns:
     The filtered data that was persisted."
  [{::keys [ctx namespace conn]}]
  (let [data (if (instance? clojure.lang.Atom ctx) @ctx ctx)
        filtered (filter-serializable data)
        edn-str (pr-str filtered)]
    (d/transact! conn [{:ctx/namespace namespace
                        :ctx/data edn-str
                        :ctx/updated-at (Instant/now)}])
    filtered))

(defn load-ctx!
  "Load *ctx* from Datalevin. Returns the deserialized data map, or nil if not found.

   Request keys:
     ::namespace - Required. Namespace string for isolation
     ::conn      - Required. Datalevin connection for storage

   Returns:
     The deserialized ctx data map, or nil if no data stored for this namespace."
  [{::keys [namespace conn]}]
  (let [results (d/q '[:find ?data ?updated
                        :in $ ?ns
                        :where
                        [?e :ctx/namespace ?ns]
                        [?e :ctx/data ?data]
                        [?e :ctx/updated-at ?updated]]
                     @conn namespace)]
    (when (seq results)
      (let [latest (->> results
                        (sort-by second)
                        last
                        first)]
        (edn/read-string latest)))))
