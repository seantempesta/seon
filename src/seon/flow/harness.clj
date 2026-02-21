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
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.flow.harness.channel :as channel]
            [seon.flow.msg :as msg]
            [seon.flow.pool :as pool]
            [seon.flow.trace :as trace]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
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
   (let [ns-str (::namespace state)
         trace (or (::msg/trace-id msg) (::msg/id msg))]
     (case input-id
       ;; Incoming cross-ns request
       :seon.flow.in/request
       (if (>= (::pending state) (::queue-cap state))
         ;; Overload: return error immediately + emit event
         (do (log/warn "Harness overload" {:trace-id trace :ns ns-str :fn (::msg/fn msg) :pending (::pending state) :queue-cap (::queue-cap state) :event :overload})
             (future (trace/persist-event! {::trace/trace-id trace ::trace/ns ns-str ::trace/fn (::msg/fn msg) ::trace/event :overload}))
             [state
              {:seon.flow.out/reply [(make-overload-reply msg ns-str)]
               :seon.flow.out/event [(make-event ns-str :overload
                                                 :seon.flow.harness/queue-cap (::queue-cap state)
                                                 :seon.flow.harness/pending (::pending state))]}])
         ;; Forward to agent JVM
         (do (log/debug "Harness forwarding request" {:trace-id trace :ns ns-str :fn (::msg/fn msg) :from-ns (::msg/from-ns msg) :pending (inc (::pending state)) :event :forward})
             (future (trace/persist-event! {::trace/trace-id trace ::trace/ns ns-str ::trace/fn (::msg/fn msg) ::trace/event :forward}))
             [(update state ::pending inc)
              {:seon.flow.out/jvm-request [msg]}]))

       ;; Reply from agent JVM
       :seon.flow.in/jvm-reply
       (let [error? (not= (::msg/status msg) :ok)
             state' (-> state
                        (update ::pending dec)
                        (cond-> error? (update ::error-count inc)))
             event-kind (if error? :error :ok)]
         (if error?
           (do (log/warn "Harness received error reply" {:trace-id trace :ns ns-str :status (::msg/status msg) :error-type (::msg/error-type msg) :duration-ms (::msg/duration-ms msg) :event :error})
               (future (trace/persist-event! {::trace/trace-id trace ::trace/ns ns-str ::trace/fn (::msg/fn msg) ::trace/event :error ::trace/status (::msg/status msg) ::trace/elapsed-ms (::msg/duration-ms msg) ::trace/error-message (::msg/error-message msg)})))
           (do (log/debug "Harness received ok reply" {:trace-id trace :ns ns-str :duration-ms (::msg/duration-ms msg) :event :ok})
               (future (trace/persist-event! {::trace/trace-id trace ::trace/ns ns-str ::trace/fn (::msg/fn msg) ::trace/event :end ::trace/status :ok ::trace/elapsed-ms (::msg/duration-ms msg)}))))
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
        _ (log/info "Starting namespace JVM" {:ns namespace :timeout-ms timeout :event :start})
        ;; 1. Acquire JVM from pool
        jvm (pool/acquire! pool {::pool/namespace (symbol namespace)
                                 ::pool/timeout-ms timeout})
        _ (when-not jvm
            (log/error "Failed to acquire JVM from pool" {:ns namespace :timeout-ms timeout :event :error})
            (throw (ex-info "Failed to acquire JVM from pool"
                            {::namespace namespace ::timeout-ms timeout})))
        nrepl-port (::pool/port jvm)
        ;; 2. Start TCP server on random port
        tcp-server (channel/start-server! {::channel/port 0})
        bridge-port (::channel/port tcp-server)
        _ (log/info "Namespace JVM acquired" {:ns namespace :nrepl-port nrepl-port :bridge-port bridge-port})]
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
      (log/info "Namespace JVM started" {:ns namespace :nrepl-port nrepl-port :bridge-port bridge-port :event :end})
      {::in-ports  {:seon.flow.in/jvm-reply (::channel/in-ch tcp-server)}
       ::out-ports {:seon.flow.out/jvm-request (::channel/out-ch tcp-server)}
       ::jvm jvm
       ::tcp-server tcp-server
       ::pool pool
       ::namespace namespace}
      (catch Exception e
        ;; Cleanup on failure
        (log/error "Failed to start namespace JVM" {:ns namespace :nrepl-port nrepl-port :bridge-port bridge-port :event :error :error (.getMessage e)})
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
  [{::keys [jvm tcp-server pool namespace]}]
  (log/info "Stopping namespace JVM" {:ns namespace :event :stop})
  (when tcp-server
    (try ((::channel/close! tcp-server)) (catch Exception _)))
  (when (and pool jvm)
    (try (pool/release! pool jvm) (catch Exception _)))
  (log/info "Namespace JVM stopped" {:ns namespace :event :stopped}))

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
    (db/transact! conn [{:ctx/namespace namespace
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
