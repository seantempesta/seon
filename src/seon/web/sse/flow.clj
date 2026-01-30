(ns seon.web.sse.flow
  "Flow-based SSE infrastructure for code change propagation.

  This namespace implements a core.async.flow-based system for:
  1. Aggregating rapid code changes (debouncing)
  2. Tracking connected SSE clients
  3. Broadcasting updates to relevant clients

  Key concepts:
  - ChangeEvent: Raw code change events from clj-reload, dev hooks, etc.
  - ClientInfo: Connected SSE client metadata
  - AggregatedUpdate: Debounced, grouped updates ready for broadcast

  The flow topology:

    [changes] -> [aggregator] -> [broadcaster]
                       ^
    [register] -> [registry] -> (client tracking)
    [unregister] -/

  See docs/prds/namespace-ui/sse-flow-solution.md for design details."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [malli.core :as m]
            [org.httpkit.server :as hk]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Malli Schemas
;;; ---------------------------------------------------------------------------

(def ChangeEvent
  "Schema for code change events."
  [:map
   [:seon.sse/event-type [:enum :namespace-reload :file-change :manual-refresh]]
   [:seon.sse/namespace {:optional true} :symbol]
   [:seon.sse/file-path {:optional true} :string]
   [:seon.sse/timestamp inst?]])

(def ClientInfo
  "Schema for connected SSE client info."
  [:map
   [:seon.sse/client-id :uuid]
   [:seon.sse/connected-at inst?]
   [:seon.sse/page [:enum :dashboard :agents :agent-detail :logs :namespace]]
   [:seon.sse/page-params {:optional true} :map]
   [:seon.sse/http-channel :any]
   [:seon.sse/last-update-at {:optional true} inst?]])

(def AggregatedUpdate
  "Schema for debounced, aggregated updates."
  [:map
   [:seon.sse/namespaces [:set :symbol]]
   [:seon.sse/pages [:set :keyword]]
   [:seon.sse/timestamp inst?]])

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn namespace->page
  "Derive which page is affected by a namespace change.
  Returns a page keyword or nil if not mappable."
  [ns-sym]
  (when ns-sym
    (let [ns-str (str ns-sym)]
      (cond
        (re-find #"^seon\.trading\." ns-str) :dashboard
        (re-find #"^seon\.ai\." ns-str) :agents
        (re-find #"^seon\.web\.agents" ns-str) :agents
        (re-find #"^seon\.health\." ns-str) :dashboard
        (re-find #"^seon\.web\." ns-str) :dashboard
        :else nil))))

;;; ---------------------------------------------------------------------------
;;; Step Functions
;;; ---------------------------------------------------------------------------

(defn aggregator-step
  "Debounce and aggregate rapid code changes.

  Groups changes by namespace, waits for quiet period before emitting.
  Uses a timer-based approach: accumulates changes, emits when no new
  changes arrive within debounce-ms.

  Arities:
  0 - describe: returns {:ins :outs :params}
  1 - init: (arg-map) -> initial-state
  2 - transition: (state transition) -> state'
  3 - transform: (state input msg) -> [state' output-map]"
  ([]
   {:ins {:changes "Raw code change events"}
    :outs {:updates "Aggregated updates for broadcasting"}
    :params {:debounce-ms "Quiet period before emitting (default 50ms)"}
    :signal-select #{::flush-pending}})

  ([{:keys [debounce-ms] :or {debounce-ms 50}}]
   {:pending-namespaces #{}
    :pending-pages #{}
    :last-change-ms 0
    :debounce-ms debounce-ms})

  ([state transition]
   (case transition
     ::flow/stop
     (do
       (log/debug "Aggregator stopping" {:pending (:pending-namespaces state)})
       state)
     state))

  ([state input msg]
   (case input
     ;; Handle flush signal
     ::flush-pending
     (if (seq (:pending-namespaces state))
       [(assoc state :pending-namespaces #{} :pending-pages #{})
        {:updates [{:seon.sse/namespaces (:pending-namespaces state)
                    :seon.sse/pages (:pending-pages state)
                    :seon.sse/timestamp (java.time.Instant/now)}]}]
       [state nil])

     ;; Handle change events
     :changes
     (let [now (System/currentTimeMillis)
           ns-sym (:seon.sse/namespace msg)
           page (namespace->page ns-sym)
           elapsed (- now (:last-change-ms state))
           new-state (cond-> state
                       ns-sym (update :pending-namespaces conj ns-sym)
                       page (update :pending-pages conj page)
                       true (assoc :last-change-ms now))]
       ;; If enough time passed since last change, emit accumulated updates
       (if (and (> elapsed (:debounce-ms state))
                (seq (:pending-namespaces new-state)))
         ;; Quiet period passed - emit aggregated update
         [(assoc new-state :pending-namespaces #{} :pending-pages #{})
          {:updates [{:seon.sse/namespaces (:pending-namespaces new-state)
                      :seon.sse/pages (:pending-pages new-state)
                      :seon.sse/timestamp (java.time.Instant/now)}]}]
         ;; Still in burst - just accumulate
         [new-state nil]))

     ;; Unknown input
     [state nil])))

(defn registry-step
  "Track connected SSE clients.

  Maintains a registry of connected clients with their metadata.
  Supports ping for introspection."
  ([]
   {:ins {:register "New client connections"
          :unregister "Client disconnections"}
    :outs {}
    :signal-select #{::ping-clients}})

  ([_args]
   {:clients {}})

  ([state transition]
   (case transition
     ::flow/stop
     (do
       (log/info "Registry stopping, closing client connections"
                 {:client-count (count (:clients state))})
       ;; Close all client connections gracefully
       (doseq [[client-id client] (:clients state)]
         (try
           (when-let [ch (:seon.sse/http-channel client)]
             (hk/close ch))
           (catch Exception e
             (log/debug "Error closing client channel" {:client-id client-id :error (.getMessage e)}))))
       state)
     state))

  ([state input msg]
   (case input
     :register
     (let [client-id (:seon.sse/client-id msg)]
       (log/debug "Registering SSE client" {:client-id client-id :page (:seon.sse/page msg)})
       [(assoc-in state [:clients client-id] msg)
        {::flow/report [{:type :client-registered
                         :client-id client-id
                         :page (:seon.sse/page msg)}]}])

     :unregister
     (let [client-id (:seon.sse/client-id msg)]
       (log/debug "Unregistering SSE client" {:client-id client-id})
       [(update state :clients dissoc client-id)
        {::flow/report [{:type :client-unregistered
                         :client-id client-id}]}])

     ;; Handle ping signal - report all clients
     ::ping-clients
     [state {::flow/report [{:type :client-list
                             :clients (vals (:clients state))
                             :count (count (:clients state))}]}]

     ;; Unknown input
     [state nil])))

(defn broadcaster-step
  "Fan out updates to relevant connected clients.

  Receives aggregated updates and emits confirmation of broadcasts sent.
  The actual SSE sending is handled by the existing render-handler;
  this step tracks what was broadcast for observability."
  ([]
   {:ins {:updates "Aggregated updates to broadcast"}
    :outs {:sent "Confirmation of sent updates"}
    :params {}})

  ([_args]
   {:broadcast-count 0
    :last-broadcast-ms 0})

  ([state transition]
   (case transition
     ::flow/stop
     (do
       (log/debug "Broadcaster stopping" {:total-broadcasts (:broadcast-count state)})
       state)
     state))

  ([state input msg]
   (case input
     :updates
     (let [now (System/currentTimeMillis)
           pages (:seon.sse/pages msg)
           namespaces (:seon.sse/namespaces msg)]
       (log/debug "Broadcasting update" {:pages pages :namespaces namespaces})
       [(-> state
            (update :broadcast-count inc)
            (assoc :last-broadcast-ms now))
        {:sent [{:type :broadcast-sent
                 :pages pages
                 :namespaces namespaces
                 :timestamp (java.time.Instant/now)}]}])

     ;; Unknown input
     [state nil])))

;;; ---------------------------------------------------------------------------
;;; Flow Configuration
;;; ---------------------------------------------------------------------------

(defn make-flow-config
  "Create flow configuration with optional customizations.

  Options:
  - :debounce-ms - Aggregator debounce period (default 50)"
  [& {:keys [debounce-ms] :or {debounce-ms 50}}]
  {:procs
   {:aggregator
    {:proc (flow/process #'aggregator-step)
     :args {:debounce-ms debounce-ms}
     :chan-opts {:changes {:buf-or-n (async/sliding-buffer 100)}}}

    :registry
    {:proc (flow/process #'registry-step)
     :chan-opts {:register {:buf-or-n 10}
                 :unregister {:buf-or-n 10}}}

    :broadcaster
    {:proc (flow/process #'broadcaster-step)
     :chan-opts {:updates {:buf-or-n 10}}}}

   :conns
   [[[:aggregator :updates] [:broadcaster :updates]]]})

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defonce ^:private flow-state (atom nil))

(declare stop!)

(defn start!
  "Start the SSE flow.

  Options:
  - :debounce-ms - Aggregator debounce period (default 50)

  Returns map with :report-chan and :error-chan for monitoring.
  Safe to call multiple times - will stop existing flow first."
  [& {:keys [debounce-ms] :or {debounce-ms 50}}]
  (when @flow-state
    (log/info "Stopping existing flow before restart")
    (stop!))
  (let [config (make-flow-config :debounce-ms debounce-ms)
        fl (flow/create-flow config)
        chans (flow/start fl)]
    (reset! flow-state {:flow fl :chans chans})
    (flow/resume fl)
    (log/info "SSE Flow started" {:debounce-ms debounce-ms})
    chans))

(defn stop!
  "Stop the SSE flow.

  Returns nil. Safe to call multiple times."
  []
  (when-let [{:keys [flow]} @flow-state]
    (flow/stop flow)
    (reset! flow-state nil)
    (log/info "SSE Flow stopped"))
  nil)

(defn running?
  "Check if the flow is currently running."
  []
  (boolean @flow-state))

(defn ping
  "Introspect flow state.

  Returns map of process-id -> {:state ... :status ...}
  or nil if flow not running.

  Options:
  - :timeout-ms - How long to wait for responses (default 1000)"
  [& {:keys [timeout-ms] :or {timeout-ms 1000}}]
  (when-let [{:keys [flow]} @flow-state]
    (flow/ping flow :timeout-ms timeout-ms)))

(defn emit-change!
  "Emit a code change event into the flow.

  event - Map with keys:
    :seon.sse/event-type - :namespace-reload, :file-change, or :manual-refresh
    :seon.sse/namespace  - (optional) namespace symbol that changed
    :seon.sse/file-path  - (optional) path to changed file

  Returns future that completes when injection is done, or nil if flow not running."
  [{:seon.sse/keys [event-type namespace file-path] :as event}]
  (when-let [{:keys [flow]} @flow-state]
    (flow/inject flow [:aggregator :changes]
                 [(assoc event :seon.sse/timestamp (java.time.Instant/now))])))

(defn register-client!
  "Register a new SSE client connection.

  client-info - Map with keys:
    :seon.sse/client-id     - UUID for this client
    :seon.sse/connected-at  - Instant when connected
    :seon.sse/page          - Page keyword (:dashboard, :agents, etc.)
    :seon.sse/page-params   - (optional) page parameters
    :seon.sse/http-channel  - http-kit channel

  Returns future that completes when injection is done, or nil if flow not running."
  [client-info]
  (when-let [{:keys [flow]} @flow-state]
    (flow/inject flow [:registry :register] [client-info])))

(defn unregister-client!
  "Unregister a disconnecting SSE client.

  client-id - UUID of the client to unregister

  Returns future that completes when injection is done, or nil if flow not running."
  [client-id]
  (when-let [{:keys [flow]} @flow-state]
    (flow/inject flow [:registry :unregister] [{:seon.sse/client-id client-id}])))

(defn connected-clients
  "Get information about currently connected clients.

  Returns map with :state and :status from registry process,
  or nil if flow not running.

  Options:
  - :timeout-ms - How long to wait (default 1000)"
  [& {:keys [timeout-ms] :or {timeout-ms 1000}}]
  (when-let [{:keys [flow]} @flow-state]
    (flow/ping-proc flow :registry :timeout-ms timeout-ms)))

(defn flush-pending!
  "Force flush any pending aggregated changes.

  Useful for testing or when you need immediate broadcast.
  Returns future that completes when injection is done."
  []
  (when-let [{:keys [flow]} @flow-state]
    (flow/inject flow [::flow/cast ::flush-pending] [true])))

(defn get-channels
  "Get the report and error channels for monitoring.

  Returns {:report-chan ... :error-chan ...} or nil if not running."
  []
  (when-let [{:keys [chans]} @flow-state]
    chans))
