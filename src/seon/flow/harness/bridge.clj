(ns seon.flow.harness.bridge
  "Agent JVM bridge step-fn for flow-routed namespace isolation.

   Runs inside the agent JVM as a mini-flow process. Receives function
   call requests from the orchestrator via TCP (as ::flow/in-ports),
   executes the function locally, and returns reply envelopes via TCP
   (as ::flow/out-ports).

   Also provides a reverse channel for cross-namespace calls: agent code
   calls remote functions via `remote-call!`, which sends a request to the
   orchestrator and blocks until a reply arrives."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [seon.flow.msg :as msg]
            [seon.schema :as schema])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::namespace
  [:string {:min 1 :description "Namespace this bridge serves"}])

(schema/register! ::bridge-port
  [:int {:min 1 :max 65535 :description "TCP port for orchestrator connection"}])

(schema/register! ::remote-call-timeout-ms
  [:int {:min 1 :description "Timeout for remote cross-namespace calls (default 10000ms)"}])

;;; ---------------------------------------------------------------------------
;;; Reverse Channel — Cross-Namespace Remote Calls
;;; ---------------------------------------------------------------------------

;; Pending promises for remote calls, keyed by request-id.
;; Used by remote-call! (registers) and bridge-step (delivers on reply).
(defonce pending-remote-promises (atom {}))

(defn remote-call!
  "Blocking remote function call through the reverse channel.

   Called by proxy functions in the agent JVM. Sends a request to the
   orchestrator via the bridge's out-port, waits for a reply on the
   bridge's in-port (delivered by bridge-step transform).

   Request keys:
     ::request-ch  - Channel to send requests on (bridge's reverse out-port)
     ::msg/to-ns   - Target namespace string
     ::msg/fn      - Fully qualified function name string
     ::msg/args    - Vector of arguments
     ::msg/from-ns - This namespace string
     ::remote-call-timeout-ms - Timeout in ms (default 10000)

   Returns the ::msg/value from the reply on success.
   Throws ex-info on timeout or remote error."
  [{::keys [request-ch remote-call-timeout-ms] :as req}]
  (let [timeout-ms (or remote-call-timeout-ms 10000)
        to-ns      (::msg/to-ns req)
        fn-name    (::msg/fn req)
        args       (::msg/args req)
        from-ns    (::msg/from-ns req)
        request-id (random-uuid)
        p          (promise)
        request    {::msg/id         request-id
                    ::msg/version    1
                    ::msg/type       :request
                    ::msg/from-ns    (or from-ns "unknown")
                    ::msg/to-ns      to-ns
                    ::msg/fn         fn-name
                    ::msg/args       (or args [])
                    ::msg/created-at (Instant/now)}]
    ;; Register promise BEFORE sending
    (swap! pending-remote-promises assoc request-id p)
    (log/debug "Remote call start" {:trace-id request-id :fn fn-name :to-ns to-ns :from-ns from-ns :event :start})
    (let [start-ms (System/currentTimeMillis)]
      (try
        ;; Send request to orchestrator via reverse channel
        (when-not (async/>!! request-ch request)
          (throw (ex-info "Reverse channel closed"
                          {::msg/status :error
                           ::msg/error-type :execution
                           ::msg/id request-id})))
        ;; Wait for reply
        (let [reply (deref p timeout-ms ::timed-out)]
          (if (= reply ::timed-out)
            (do
              (swap! pending-remote-promises dissoc request-id)
              (log/warn "Remote call timeout" {:trace-id request-id :fn fn-name :to-ns to-ns :elapsed-ms (- (System/currentTimeMillis) start-ms) :event :timeout})
              (throw (ex-info "Remote call timed out"
                              {::msg/status :timeout
                               ::msg/error-type :timeout
                               ::msg/id request-id
                               ::msg/to-ns to-ns
                               ::msg/fn fn-name})))
            ;; Got a reply
            (let [elapsed (- (System/currentTimeMillis) start-ms)]
              (case (::msg/status reply)
                :ok (do (log/debug "Remote call ok" {:trace-id request-id :fn fn-name :to-ns to-ns :elapsed-ms elapsed :event :end})
                        (::msg/value reply))
                (do (log/warn "Remote call error" {:trace-id request-id :fn fn-name :to-ns to-ns :elapsed-ms elapsed :event :error :status (::msg/status reply) :error-message (::msg/error-message reply)})
                    (throw (ex-info (or (::msg/error-message reply)
                                        (str "Remote call failed: " (::msg/status reply)))
                                    (select-keys reply [::msg/status ::msg/error-type
                                                        ::msg/error-class ::msg/error-message
                                                        ::msg/error-data ::msg/id
                                                        ::msg/duration-ms]))))))))
        (catch Exception e
          (swap! pending-remote-promises dissoc request-id)
          (throw e))))))

;;; ---------------------------------------------------------------------------
;;; Function Execution
;;; ---------------------------------------------------------------------------

(defn- resolve-fn
  "Resolve a fully-qualified function name to a var.
   Returns the var or nil if not found."
  [fn-name]
  (try
    (let [sym (symbol fn-name)]
      (requiring-resolve sym))
    (catch Exception _
      nil)))

(defn execute-local
  "Execute a local function by fully-qualified name.

   Looks up the var, calls it with the provided args, times execution,
   and returns a ::msg/reply envelope.

   Request keys:
     ::msg/id      - Request ID (echoed in reply)
     ::msg/fn      - Fully qualified function name
     ::msg/args    - Vector of arguments
     ::msg/from-ns - Requesting namespace

   Returns ::msg/reply envelope with ::msg/status :ok or :error."
  [{::msg/keys [id fn args from-ns trace-id]}
   {::keys [namespace]}]
  (let [start-ns (System/nanoTime)
        trace    (or trace-id id)
        base     {::msg/id       id
                  ::msg/version  1
                  ::msg/type     :reply
                  ::msg/from-ns  (or namespace "")}
        base     (cond-> base trace-id (assoc ::msg/trace-id trace-id))]
    (log/debug "Execute local start" {:trace-id trace :fn fn :ns namespace :from-ns from-ns :args-count (count args) :event :start})
    (if-let [the-var (resolve-fn fn)]
      (try
        (let [result   (apply the-var args)
              dur-ms   (quot (- (System/nanoTime) start-ns) 1000000)]
          ;; Verify result is EDN-serializable via round-trip
          (try
            (edn/read-string (pr-str result))
            (log/debug "Execute local ok" {:trace-id trace :fn fn :ns namespace :elapsed-ms dur-ms :event :end})
            (assoc base
                   ::msg/status :ok
                   ::msg/value result
                   ::msg/duration-ms dur-ms)
            (catch Exception e
              (let [dur-ms' (quot (- (System/nanoTime) start-ns) 1000000)]
                (log/warn "Execute local serialization error" {:trace-id trace :fn fn :ns namespace :elapsed-ms dur-ms' :event :error :error-type :serialization})
                (assoc base
                       ::msg/status :error
                       ::msg/error-type :serialization
                       ::msg/error-class (.getName (class e))
                       ::msg/error-message (str "Result not EDN-serializable: " (.getMessage e))
                       ::msg/duration-ms dur-ms')))))
        (catch Exception e
          (let [dur-ms (quot (- (System/nanoTime) start-ns) 1000000)]
            (log/warn "Execute local execution error" {:trace-id trace :fn fn :ns namespace :elapsed-ms dur-ms :event :error :error-type :execution :error-message (.getMessage e)})
            (assoc base
                   ::msg/status :error
                   ::msg/error-type :execution
                   ::msg/error-class (.getName (class e))
                   ::msg/error-message (.getMessage e)
                   ::msg/error-data (ex-data e)
                   ::msg/duration-ms dur-ms))))
      ;; Var not found
      (let [dur-ms (quot (- (System/nanoTime) start-ns) 1000000)]
        (log/warn "Execute local not found" {:trace-id trace :fn fn :ns namespace :elapsed-ms dur-ms :event :error :error-type :not-found})
        (assoc base
               ::msg/status :error
               ::msg/error-type :not-found
               ::msg/error-message (str "Function not found: " fn)
               ::msg/duration-ms dur-ms)))))

;;; ---------------------------------------------------------------------------
;;; Step Function
;;; ---------------------------------------------------------------------------

(defn bridge-step
  "Agent JVM bridge step-fn.

   Receives requests from orchestrator via TCP, executes local functions,
   returns reply envelopes. Also handles reverse-channel replies for
   cross-namespace calls initiated by proxy functions.

   Params:
     ::namespace   - Namespace this bridge serves
     ::bridge-port - TCP port to connect to orchestrator

   In-ports (from TCP):
     :seon.flow.in/request - Incoming function call requests
     :seon.flow.in/reply   - Replies for cross-ns calls (reverse channel)

   Out-ports (to TCP):
     :seon.flow.out/reply   - Outgoing reply envelopes
     :seon.flow.out/request - Outgoing cross-ns call requests (reverse channel)"
  ;; describe
  ([]
   {:params {::namespace   "Namespace to serve"
             ::bridge-port "TCP port for orchestrator connection"}
    :workload :io})

  ;; init
  ([{::keys [namespace bridge-port] :as args}]
   {::namespace (or namespace "unknown")
    ::bridge-port bridge-port})

  ;; transition
  ([state transition]
   (case transition
     ::flow/resume state
     ::flow/pause  state
     ::flow/stop   (do
                     ;; Deliver error to any pending remote promises
                     (doseq [[_id p] @pending-remote-promises]
                       (deliver p {::msg/status :error
                                   ::msg/error-type :timeout
                                   ::msg/error-message "Bridge stopped"}))
                     (reset! pending-remote-promises {})
                     state)
     state))

  ;; transform
  ([state input-id msg]
   (case input-id
     :seon.flow.in/request
     (do (log/debug "Bridge received request" {:trace-id (or (::msg/trace-id msg) (::msg/id msg)) :fn (::msg/fn msg) :from-ns (::msg/from-ns msg)})
         (let [reply (execute-local msg state)]
           [state {:seon.flow.out/reply [reply]}]))

     ;; Reverse channel: deliver reply to waiting remote-call! promise
     :seon.flow.in/reply
     (let [request-id (::msg/id msg)]
       (log/debug "Bridge received reverse reply" {:trace-id (or (::msg/trace-id msg) request-id) :status (::msg/status msg)})
       (when-let [p (get @pending-remote-promises request-id)]
         (swap! pending-remote-promises dissoc request-id)
         (deliver p msg))
       [state nil])

     [state nil])))
