(ns seon.flow.topology
  "Reply router and topology wiring for flow-routed namespace isolation.

   The reply-router is a flow step-fn that receives reply envelopes from
   namespace steps and delivers them to waiting callers via promises.

   `request!` is the blocking entry point: it creates a promise, injects
   a request into the flow, and derefs the promise with a timeout.

   `build-topology!` wires namespace steps + reply router into a flow.
   It also starts relay go-loops for cross-namespace calls from agent JVMs."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [seon.db.datalevin.writer :as writer]
            [seon.flow.harness :as harness]
            [seon.flow.msg :as msg]
            [seon.flow.status :as status]
            [seon.runtime :as runtime]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::timeout-ms
                  [:int {:min 1 :description "Request timeout in milliseconds"}])

(schema/register! ::target-ns
                  [:string {:min 1 :description "Target namespace for the request"}])

(schema/register! ::pid
                  [:keyword {:description "Direct process ID for flow injection (e.g. :seon.flow/writer)"}])

;;; ---------------------------------------------------------------------------
;;; Sink Step Functions
;;; ---------------------------------------------------------------------------

(defn event-sink-step
  "Consumes observability events from namespace steps.

   Maintains a vector of recent events for observability purposes.
   Events are received on :seon.flow.out/event and stored in memory.

   No outputs - this is a terminal sink."
  ;; describe
  ([]
   {:ins {:seon.flow.out/event "Observability events from namespace steps"}
    :outs {}
    :workload :io})

  ;; init
  ([_args]
   {::recent-events []
    ::max-events 100})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop state
     :clojure.core.async.flow/pause state
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id event]
   (case input-id
     :seon.flow.out/event
     (let [events (::recent-events state)
           events' (if (< (count events) (::max-events state))
                     (conj events event)
                     (conj (subvec events 1) event))]
       [(assoc state ::recent-events events') nil])
     [state nil])))

(defn error-sink-step
  "Consumes error replies from namespace steps.

   Maintains a vector of recent errors for observability and debugging.
   Errors are received on :seon.flow.out/error and stored in memory.

   No outputs - this is a terminal sink."
  ;; describe
  ([]
   {:ins {:seon.flow.out/error "Error replies from namespace steps"}
    :outs {}
    :workload :io})

  ;; init
  ([_args]
   {::recent-errors []
    ::max-errors 100})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop state
     :clojure.core.async.flow/pause state
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id error]
   (case input-id
     :seon.flow.out/error
     (let [errors (::recent-errors state)
           errors' (if (< (count errors) (::max-errors state))
                     (conj errors error)
                     (conj (subvec errors 1) error))]
       [(assoc state ::recent-errors errors') nil])
     [state nil])))

;;; ---------------------------------------------------------------------------
;;; Reply Router Step Function
;;; ---------------------------------------------------------------------------

;; Global atom of request-id -> promise. Shared between reply-router
;; (which delivers) and request! (which registers).
;; Global because flow/inject is the mechanism for sending requests,
;; but promises must be registered before injection.
(defonce pending-promises (atom {}))

(defn reply-router-step
  "Flow step-fn that delivers reply envelopes to waiting callers.

   Receives replies on :seon.flow.in/reply, looks up the corresponding
   promise by ::msg/id, and delivers it. Unmatched replies are logged
   and dropped.

   No outs needed - delivery is via promise side-effect."
  ;; describe
  ([]
   {:ins {:seon.flow.in/reply "Reply envelopes from namespace steps"}
    :outs {}
    :workload :io})

  ;; init
  ([_args]
   {::delivered 0
    ::unmatched 0})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop state
     :clojure.core.async.flow/pause state
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id msg]
   (case input-id
     :seon.flow.in/reply
     (let [request-id (::msg/id msg)]
       (if-let [p (get @pending-promises request-id)]
         (do
           (swap! pending-promises dissoc request-id)
           (deliver p msg)
           [(update state ::delivered inc) nil])
         ;; No matching promise - stale or duplicate reply
         [(update state ::unmatched inc)
          {::flow/report [{:type :unmatched-reply
                           :request-id request-id
                           :from-ns (::msg/from-ns msg)}]}]))
     ;; Unknown input
     [state nil])))

;;; ---------------------------------------------------------------------------
;;; request! — Blocking cross-namespace call
;;; ---------------------------------------------------------------------------

(defn request!
  "Blocking cross-namespace call through flow. Returns value or throws.

   Request keys:
     ::flow       - The flow object (from build-topology!)
     ::target-ns  - Target namespace string
     ::fn         - Fully qualified function name string
     ::args       - Vector of arguments
     ::timeout-ms - Timeout in ms (default 10000)
     ::from-ns    - Caller namespace string (default \"orchestrator\")
     ::trace-id   - Optional trace UUID

   Returns the ::msg/value from the reply on success.

   Throws ex-info on:
     :timeout   - No reply within timeout-ms
     :error     - Target function threw an exception
     :overload  - Target namespace queue at capacity
     :not-found - Target function not found"
  [{::keys [flow target-ns fn args timeout-ms from-ns trace-id]
    :or {timeout-ms 10000 from-ns "orchestrator"}}]
  (let [request-id (random-uuid)
        p (promise)
        request (cond-> {::msg/id request-id
                         ::msg/version 1
                         ::msg/type :request
                         ::msg/from-ns from-ns
                         ::msg/to-ns target-ns
                         ::msg/fn fn
                         ::msg/args (or args [])
                         ::msg/created-at (Instant/now)}
                  trace-id (assoc ::msg/trace-id trace-id))
        ;; Derive the process id from target namespace
        pid (keyword "ns" target-ns)]
    ;; Register promise BEFORE injection
    (swap! pending-promises assoc request-id p)
    (try
      ;; Inject request into the target namespace process
      (flow/inject flow [pid :seon.flow.in/request] [request])
      ;; Wait for reply
      (let [reply (deref p timeout-ms ::timed-out)]
        (if (= reply ::timed-out)
          ;; Clean up and throw timeout
          (do
            (swap! pending-promises dissoc request-id)
            (throw (ex-info "Request timed out"
                            {::msg/status :timeout
                             ::msg/error-type :timeout
                             ::msg/id request-id
                             ::target-ns target-ns
                             ::fn fn
                             ::timeout-ms timeout-ms})))
          ;; Got a reply
          (case (::msg/status reply)
            :ok (::msg/value reply)
            ;; All error statuses throw
            (throw (ex-info (or (::msg/error-message reply)
                                (str "Request failed: " (::msg/status reply)))
                            (select-keys reply [::msg/status ::msg/error-type
                                                ::msg/error-class ::msg/error-message
                                                ::msg/error-data ::msg/id
                                                ::msg/duration-ms]))))))
      (catch Exception e
        ;; Clean up promise on any exception (including inject failure)
        (swap! pending-promises dissoc request-id)
        (throw e)))))

;;; ---------------------------------------------------------------------------
;;; Cross-Namespace Relay
;;; ---------------------------------------------------------------------------

(defn- start-cross-ns-relay!
  "Start a relay that forwards cross-ns requests from an agent JVM to the topology.

   Reads requests from `reverse-request-ch` (sent by agent proxy functions),
   calls `request!` to route them through the flow, and sends the reply
   back on `reverse-reply-ch` (delivered to the agent's bridge).

   Returns the go channel (for cleanup)."
  [flow reverse-request-ch reverse-reply-ch]
  (async/go-loop []
    (when-let [req (async/<! reverse-request-ch)]
      (let [target-ns (::msg/to-ns req)
            fn-name   (::msg/fn req)
            args      (::msg/args req)
            request-id (::msg/id req)]
        ;; Run the blocking request! call on a thread
        (async/thread
          (let [reply (try
                        (let [result (request! {::flow      flow
                                                ::target-ns target-ns
                                                ::fn        fn-name
                                                ::args      args
                                                ::timeout-ms 10000
                                                ::from-ns   (::msg/from-ns req)})]
                          {::msg/id        request-id
                           ::msg/version   1
                           ::msg/type      :reply
                           ::msg/status    :ok
                           ::msg/value     result
                           ::msg/from-ns   (or target-ns "")
                           ::msg/duration-ms 0})
                        (catch Exception e
                          (let [data (ex-data e)]
                            {::msg/id            request-id
                             ::msg/version       1
                             ::msg/type          :reply
                             ::msg/status        (or (::msg/status data) :error)
                             ::msg/error-type    (or (::msg/error-type data) :execution)
                             ::msg/error-message (.getMessage e)
                             ::msg/from-ns       (or target-ns "")
                             ::msg/duration-ms   0})))]
            (async/>!! reverse-reply-ch reply))))
      (recur))))

;;; ---------------------------------------------------------------------------
;;; Cycle Detection
;;; ---------------------------------------------------------------------------

(defn- dfs-cycles
  "DFS to detect cycles in directed graph. Returns vector of cycle paths, or nil if none.
   Each path is a vector of node values representing the cycle."
  [graph]
  (let [visited (atom #{})
        rec-stack (atom #{})
        cycles (atom [])]
    (letfn [(visit [node path]
              (swap! visited conj node)
              (swap! rec-stack conj node)

              ;; Visit each neighbor
              (doseq [neighbor (get graph node #{})]
                (cond
                  ;; Found a cycle: neighbor is in recursion stack
                  (contains? @rec-stack neighbor)
                  (let [cycle-start-idx (.indexOf path neighbor)
                        cycle (if (>= cycle-start-idx 0)
                                (-> (subvec path cycle-start-idx) (conj neighbor))
                                [neighbor])]
                    (swap! cycles conj cycle))

                  ;; Not visited yet: continue DFS
                  (not (contains? @visited neighbor))
                  (visit neighbor (conj path neighbor))))

              (swap! rec-stack disj node))]

      ;; Run DFS from each unvisited node
      (doseq [node (keys graph)]
        (when (not (contains? @visited node))
          (visit node [node]))))

    (when (seq @cycles) @cycles)))

(defn detect-cycles
  "Detect circular dependencies in namespace proxy configs.

   Returns nil if no cycles found, or a vector of cycle paths if found.
   Each cycle path is a vector of namespace strings.
   E.g., [[\"seon.health.lifting\" \"seon.health.nutrition\" \"seon.health.lifting\"]]

   Namespace configs should have optional ::proxy-targets key with a set of
   namespace strings that this namespace proxies to. Missing or empty set means
   no proxy calls."
  [namespace-configs]
  (let [;; Build directed graph: namespace -> set of namespaces it proxies to
        graph
        (into {}
              (map (fn [[ns-str config]]
                     [ns-str (or (::proxy-targets config) #{})])
                   namespace-configs))]
    (dfs-cycles graph)))

;;; ---------------------------------------------------------------------------
;;; build-topology! — Wire namespace steps + reply router
;;; ---------------------------------------------------------------------------

(defn build-topology!
  "Build and start a flow topology with namespace steps and reply router.

   Request keys:
     ::namespaces - Map of namespace-string -> config map
                    Each config can have:
                      ::harness/queue-cap - Queue cap override
                      ::harness/in-ports  - External in-port channels
                      ::harness/out-ports - External out-port channels
                      ::proxy-targets     - Optional set of namespace strings this
                                           namespace proxies to (for cycle detection)
                    For cross-namespace relay (reverse channel):
                      ::reverse-request-ch - Channel agent JVM sends cross-ns requests on
                      ::reverse-reply-ch   - Channel to send cross-ns replies back to agent JVM

   Throws ex-info with :cycle-detected if circular dependencies found.

   Returns map with:
     ::flow        - The flow object (pass to request!, stop-topology!)
     ::chans       - {:report-chan ... :error-chan ...}
     ::relays      - Go channels for cross-ns relays (for cleanup)

   Example:
     (build-topology!
       {::namespaces {\"seon.test.beta\"
                      {::harness/queue-cap 8
                       ::harness/in-ports {:seon.flow.in/jvm-reply reply-ch}
                       ::harness/out-ports {:seon.flow.out/jvm-request req-ch}}}})"
  [{::keys [namespaces]}]
  ;; Detect cycles at build time
  (when-let [cycles (detect-cycles namespaces)]
    (let [cycle-strs (map (fn [cycle]
                            (str/join " \u2192 " cycle))
                          cycles)]
      (throw (ex-info "Circular dependency detected in namespace proxies"
                      {::cycle-detected true
                       ::cycles cycles
                       ::cycle-descriptions cycle-strs}))))

  (let [;; Build process definitions
        ns-procs
        (into {}
              (map (fn [[ns-str config]]
                     (let [pid (keyword "ns" ns-str)
                           ;; Strip relay channels and proxy-targets from config before passing to harness
                           harness-config (dissoc config ::reverse-request-ch ::reverse-reply-ch ::proxy-targets)]
                       [pid {:proc (flow/process #'harness/namespace-step)
                             :args (merge {::harness/namespace ns-str}
                                          harness-config)}])))
              namespaces)

        ;; Reply router process
        router-proc
        {:seon.flow/reply-router
         {:proc (flow/process #'reply-router-step)}}

        ;; Sink processes for event and error outputs
        sink-procs
        {:seon.flow/event-sink
         {:proc (flow/process #'event-sink-step)}
         :seon.flow/error-sink
         {:proc (flow/process #'error-sink-step)}}

        ;; Build connections: reply -> reply-router, events -> event-sink, errors -> error-sink
        conns
        (into []
              (concat
                ;; Each namespace's reply output -> reply router
               (mapv (fn [[ns-str _]]
                       (let [pid (keyword "ns" ns-str)]
                         [[pid :seon.flow.out/reply]
                          [:seon.flow/reply-router :seon.flow.in/reply]]))
                     namespaces)
                ;; Each namespace's event output -> event sink
               (mapv (fn [[ns-str _]]
                       (let [pid (keyword "ns" ns-str)]
                         [[pid :seon.flow.out/event]
                          [:seon.flow/event-sink :seon.flow.out/event]]))
                     namespaces)
                ;; Each namespace's error output -> error sink
               (mapv (fn [[ns-str _]]
                       (let [pid (keyword "ns" ns-str)]
                         [[pid :seon.flow.out/error]
                          [:seon.flow/error-sink :seon.flow.out/error]]))
                     namespaces)))

        config {:procs (merge ns-procs router-proc sink-procs)
                :conns conns}
        fl (flow/create-flow config)
        chans (flow/start fl)]
    (flow/resume fl)

    ;; Start cross-ns relays for namespaces that have reverse channels
    (let [relays
          (into []
                (keep (fn [[_ns-str config]]
                        (let [req-ch (::reverse-request-ch config)
                              rep-ch (::reverse-reply-ch config)]
                          (when (and req-ch rep-ch)
                            (start-cross-ns-relay! fl req-ch rep-ch)))))
                namespaces)
          label (or (::label (meta namespaces))
                    (str "topology-" (count namespaces) "ns"))
          flow-id (keyword "seon.flow" label)
          result {::flow fl
                  ::chans chans
                  ::relays relays
                  ::flow-id flow-id}]
      ;; Register in runtime flow registry
      (runtime/register-flow! {::runtime/flow-id flow-id
                               ::runtime/flow fl
                               ::runtime/chans chans
                               ::runtime/label label})
      ;; Start error drain for status collection
      (when-let [error-chan (:error-chan chans)]
        (status/start-error-drain! {::status/id flow-id
                                    ::status/error-chan error-chan}))
      result)))

(defn stop-topology!
  "Stop a running topology. Snapshots state before stopping. Returns nil."
  [{::keys [flow flow-id label]}]
  (when flow
    ;; Snapshot state before stopping: pause -> snapshot -> stop
    (try
      (flow/pause flow)
      (flow/ping flow :timeout-ms 5000)
      (let [snap-label (or label (when flow-id (name flow-id)))]
        (when snap-label
          (runtime/snapshot-topology! {::runtime/flow flow
                                       ::runtime/label snap-label
                                       ::runtime/reason :shutdown})))
      (catch Throwable t
        (log/warn "Failed to snapshot topology before stop" {:error (.getMessage t)})))
    (flow/stop flow)
    ;; Clean up any lingering promises
    (doseq [[_id p] @pending-promises]
      (deliver p {::msg/status :error
                  ::msg/error-type :timeout
                  ::msg/error-message "Topology stopped"}))
    (reset! pending-promises {}))
  ;; Unregister from runtime flow registry and stop error drain
  (when flow-id
    (status/stop-error-drain! {::status/id flow-id})
    (runtime/unregister-flow! {::runtime/flow-id flow-id}))
  nil)

;;; ---------------------------------------------------------------------------
;;; build-infrastructure! — Infrastructure flow (writer + reply-router + sinks)
;;; ---------------------------------------------------------------------------

(defn build-infrastructure!
  "Build and start the infrastructure flow.

   The infrastructure flow handles cross-cutting concerns that are shared
   across all namespace flows: database writes and reply routing.

   Processes:
     :seon.flow/writer       - infra-writer-step (multi-DB via connection manager)
     :seon.flow/reply-router - reply-router-step (delivers promises)
     :seon.flow/event-sink   - event-sink-step (observability)
     :seon.flow/error-sink   - error-sink-step (error accumulation)

   Connections:
     writer reply  -> reply-router
     writer error  -> error-sink

   Request keys:
     ::connection-manager - Datalevin connection manager (Integrant component)

   Returns:
     {::flow fl ::flow-id :seon.flow/infrastructure}"
  [{::keys [connection-manager]}]
  (let [flow-id :seon.flow/infrastructure
        config {:procs {:seon.flow/writer
                        {:proc (flow/process #'writer/infra-writer-step)
                         :args {::writer/connection-manager connection-manager}}
                        :seon.flow/reply-router
                        {:proc (flow/process #'reply-router-step)}
                        :seon.flow/event-sink
                        {:proc (flow/process #'event-sink-step)}
                        :seon.flow/error-sink
                        {:proc (flow/process #'error-sink-step)}}
                :conns [;; writer reply -> reply-router
                        [[:seon.flow/writer :seon.flow.out/reply]
                         [:seon.flow/reply-router :seon.flow.in/reply]]
                        ;; writer error -> error-sink
                        [[:seon.flow/writer :seon.flow.out/error]
                         [:seon.flow/error-sink :seon.flow.out/error]]]}
        fl (flow/create-flow config)
        chans (flow/start fl)]
    (flow/resume fl)
    ;; Register in runtime
    (runtime/register-flow! {::runtime/flow-id flow-id
                             ::runtime/flow fl
                             ::runtime/chans chans
                             ::runtime/label "infrastructure"})
    ;; Start error drain
    (when-let [error-chan (:error-chan chans)]
      (status/start-error-drain! {::status/id flow-id
                                  ::status/error-chan error-chan}))
    {::flow fl
     ::flow-id flow-id
     ::chans chans}))
