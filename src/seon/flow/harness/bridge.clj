(ns seon.flow.harness.bridge
  "Agent JVM bridge step-fn for flow-routed namespace isolation.

   Runs inside the agent JVM as a mini-flow process. Receives function
   call requests from the orchestrator via TCP (as ::flow/in-ports),
   executes the function locally, and returns reply envelopes via TCP
   (as ::flow/out-ports)."
  (:require [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
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
        base     {::msg/id       id
                  ::msg/version  1
                  ::msg/type     :reply
                  ::msg/from-ns  (or namespace "")}
        base     (cond-> base trace-id (assoc ::msg/trace-id trace-id))]
    (if-let [the-var (resolve-fn fn)]
      (try
        (let [result   (apply the-var args)
              dur-ms   (quot (- (System/nanoTime) start-ns) 1000000)]
          ;; Verify result is EDN-serializable via round-trip
          (try
            (edn/read-string (pr-str result))
            (assoc base
                   ::msg/status :ok
                   ::msg/value result
                   ::msg/duration-ms dur-ms)
            (catch Exception e
              (assoc base
                     ::msg/status :error
                     ::msg/error-type :serialization
                     ::msg/error-class (.getName (class e))
                     ::msg/error-message (str "Result not EDN-serializable: " (.getMessage e))
                     ::msg/duration-ms (quot (- (System/nanoTime) start-ns) 1000000)))))
        (catch Exception e
          (let [dur-ms (quot (- (System/nanoTime) start-ns) 1000000)]
            (assoc base
                   ::msg/status :error
                   ::msg/error-type :execution
                   ::msg/error-class (.getName (class e))
                   ::msg/error-message (.getMessage e)
                   ::msg/error-data (ex-data e)
                   ::msg/duration-ms dur-ms))))
      ;; Var not found
      (assoc base
             ::msg/status :error
             ::msg/error-type :not-found
             ::msg/error-message (str "Function not found: " fn)
             ::msg/duration-ms (quot (- (System/nanoTime) start-ns) 1000000)))))

;;; ---------------------------------------------------------------------------
;;; Step Function
;;; ---------------------------------------------------------------------------

(defn bridge-step
  "Agent JVM bridge step-fn.

   Receives requests from orchestrator via TCP, executes local functions,
   returns reply envelopes. Designed for use with core.async.flow.

   Params:
     ::namespace   - Namespace this bridge serves
     ::bridge-port - TCP port to connect to orchestrator

   In-ports (from TCP):
     :seon.flow.in/request - Incoming function call requests

   Out-ports (to TCP):
     :seon.flow.out/reply - Outgoing reply envelopes"
  ;; describe
  ([]
   {:params {::namespace   "Namespace to serve"
             ::bridge-port "TCP port for orchestrator connection"}
    :workload :io})

  ;; init
  ([{::keys [namespace bridge-port] :as args}]
   ;; For MVP, channels are injected externally (via test or harness setup).
   ;; When used with real TCP, the harness wires channel/connect! results
   ;; as ::flow/in-ports and ::flow/out-ports before starting the flow.
   {::namespace (or namespace "unknown")
    ::bridge-port bridge-port})

  ;; transition
  ([state transition]
   (case transition
     ::flow/resume state
     ::flow/pause  state
     ::flow/stop   state
     state))

  ;; transform
  ([state input-id msg]
   (case input-id
     :seon.flow.in/request
     (let [reply (execute-local msg state)]
       [state {:seon.flow.out/reply [reply]}])
     [state nil])))
