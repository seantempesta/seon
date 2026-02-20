(ns seon.health
  "System health checks and monitoring.

   Provides comprehensive health checking for all system components:
   - nREPL server availability
   - Agent registry status
   - Resource utilization (ports, sessions, channels)

   ## Usage

   ```clojure
   ;; Quick health check (for load balancers)
   (quick-check {})
   ;; => {:seon.health/status :healthy, :seon.health/timestamp #inst \"...\"}

   ;; Deep health check (for debugging)
   (deep-check {})
   ;; => {:seon.health/status :healthy
   ;;     :seon.health/checks {:nrepl {:ok true}, ...}
   ;;     :seon.health/resources {:agents 2, :ports 3, :sessions 2}}
   ```"
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [seon.ai.agent :as agent]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.net Socket InetSocketAddress]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::status
                  [:enum {:description "System health status"}
                   :healthy :degraded :unhealthy])

(schema/register! ::check-result
                  [:map {:description "Individual health check result"}
                   [:ok :boolean]
                   [:latency-ms {:optional true} [:int {:min 0}]]
                   [:error {:optional true} :string]
                   [:details {:optional true} [:map-of :keyword :any]]])

(schema/register! ::checks
                  [:map-of :keyword ::check-result])

(schema/register! ::resources
                  [:map {:description "Resource counts"}
                   [:agents :int]
                   [:ports :int]
                   [:sessions :int]
                   [:nrepl-servers :int]])

(schema/register! ::quick-check-request
                  [:map])

(schema/register! ::quick-check-response
                  [:map
                   [::status ::status]
                   [::timestamp inst?]
                   [::checks {:optional true} ::checks]])

(schema/register! ::deep-check-request
                  [:map])

(schema/register! ::deep-check-response
                  [:map
                   [::status ::status]
                   [::timestamp inst?]
                   [::checks ::checks]
                   [::resources ::resources]])

;;; ---------------------------------------------------------------------------
;;; Component Health Checks (Internal)
;;; ---------------------------------------------------------------------------

(defn- check-port
  "Check if a port is accepting connections."
  ([port] (check-port port 1000))
  ([port timeout-ms]
   (let [start (System/currentTimeMillis)]
     (try
       (with-open [socket (Socket.)]
         (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) timeout-ms)
         {:ok true
          :latency-ms (- (System/currentTimeMillis) start)})
       (catch Exception e
         {:ok false
          :latency-ms (- (System/currentTimeMillis) start)
          :error (.getMessage e)})))))

(defn- check-nrepl
  "Check main nREPL server health (port 7888)."
  []
  (let [nrepl-port-file (java.io.File. ".nrepl-port")]
    (if (.exists nrepl-port-file)
      (try
        (let [port (parse-long (slurp nrepl-port-file))]
          (check-port port))
        (catch Exception e
          {:ok false
           :error (str "Failed to read .nrepl-port: " (.getMessage e))}))
      {:ok false
       :error ".nrepl-port file not found"})))

(defn- check-resources
  "Get current resource utilization."
  []
  (try
    (require 'seon.orchestrator.nrepl)
    (require 'seon.orchestrator.session)
    (let [nrepl-ns (find-ns 'seon.orchestrator.nrepl)
          session-ns (find-ns 'seon.orchestrator.session)
          ;; Access the atoms via resolve
          port-registry @(ns-resolve nrepl-ns 'port-registry)
          servers @(ns-resolve nrepl-ns 'servers)
          session-registry @(ns-resolve session-ns 'session-registry)
          running-agents (agent/agents {})]
      {:agents (count running-agents)
       :ports (count @port-registry)
       :sessions (count @session-registry)
       :nrepl-servers (count @servers)})
    (catch Exception e
      (log/warn e "Failed to check resources")
      {:agents 0
       :ports 0
       :sessions 0
       :nrepl-servers 0})))

(defn- check-agents
  "Check agent subsystem health."
  []
  (try
    (let [running-agents (agent/agents {})
          running-count (count running-agents)]
      {:ok true
       :details {:running running-count}})
    (catch Exception e
      {:ok false
       :error (.getMessage e)})))

;;; ---------------------------------------------------------------------------
;;; Aggregate Health Status
;;; ---------------------------------------------------------------------------

(defn- determine-status
  "Determine overall health status from individual checks.

   - :healthy - All checks pass
   - :degraded - Some non-critical checks fail
   - :unhealthy - Critical checks fail"
  [checks]
  (let [all-ok? (every? :ok (vals checks))]
    (if all-ok? :healthy :degraded)))

(defn quick-check
  "Quick health check suitable for load balancers and monitoring.

   Quick check for load balancers. Checks nREPL availability.

   Request keys: (none)

   Response keys:
     ::status - :healthy, :degraded, or :unhealthy
     ::timestamp - When the check was performed
     ::checks - Map of component -> result (only if not healthy)

   Example:
     (quick-check {})"
  {:malli/schema [:=> [:cat ::quick-check-request] ::quick-check-response]}
  [{}]
  (let [nrepl-check (check-nrepl)
        status (if (:ok nrepl-check) :healthy :degraded)]
    (cond-> {::status status
             ::timestamp (java.util.Date.)}
      (not= status :healthy)
      (assoc ::checks {:nrepl nrepl-check}))))

(defn deep-check
  "Comprehensive health check for debugging and monitoring.

   Checks all components:
   - Main nREPL server
   - Agent subsystem
   - All namespace nREPL servers

   Request keys: (none)

   Response keys:
     ::status - :healthy, :degraded, or :unhealthy
     ::timestamp - When the check was performed
     ::checks - Map of component -> check-result
     ::resources - Current resource utilization

   Example:
     (deep-check {})"
  {:malli/schema [:=> [:cat ::deep-check-request] ::deep-check-response]}
  [{}]
  (let [nrepl-check (check-nrepl)
        agents-check (check-agents)
        resources (check-resources)
        ;; Check all namespace nREPL servers
        nrepl-servers-check (try
                              (require 'seon.orchestrator.nrepl)
                              (let [list-servers (resolve 'seon.orchestrator.nrepl/list-namespace-servers)
                                    servers (list-servers)]
                                (if (empty? servers)
                                  {:ok true :details {:count 0}}
                                  (let [port-checks (for [{:keys [port session-id]} servers]
                                                      [session-id (check-port port 500)])
                                        failed (filter (fn [[_ r]] (not (:ok r))) port-checks)]
                                    (if (empty? failed)
                                      {:ok true :details {:count (count servers)}}
                                      {:ok false
                                       :error (str (count failed) " of " (count servers) " servers unreachable")
                                       :details {:failed (into {} failed)}}))))
                              (catch Exception e
                                {:ok false :error (.getMessage e)}))
        checks {:nrepl nrepl-check
                :agents agents-check
                :nrepl-servers nrepl-servers-check}
        status (determine-status checks)]
    {::status status
     ::timestamp (java.util.Date.)
     ::checks checks
     ::resources resources}))

;;; ---------------------------------------------------------------------------
;;; Cleanup Functions (for Phase 3)
;;; ---------------------------------------------------------------------------

(defn- find-orphaned-ports
  "Find ports in the registry that are no longer listening."
  []
  (try
    (require 'seon.orchestrator.nrepl)
    (let [port-registry @(ns-resolve (find-ns 'seon.orchestrator.nrepl) 'port-registry)]
      (for [[session-id port] @port-registry
            :let [result (check-port port 200)]
            :when (not (:ok result))]
        {:session-id session-id
         :port port
         :error (:error result)}))
    (catch Exception e
      (log/warn e "Failed to find orphaned ports")
      [])))

(defn- find-listening-orphans
  "Find ports in the agent range (7889-7999) that are listening but NOT in registry.
  These are truly orphaned nREPL servers that survived a reset."
  []
  (try
    (require 'seon.orchestrator.nrepl)
    (let [nrepl-ns (find-ns 'seon.orchestrator.nrepl)
          ;; port-range is an atom, need double deref
          {:keys [base max]} @@(ns-resolve nrepl-ns 'port-range)
          port-registry @(ns-resolve nrepl-ns 'port-registry)
          servers @(ns-resolve nrepl-ns 'servers)
          registered-ports (set (concat (vals @port-registry)
                                        (map :port (vals @servers))))]
      (for [port (range base (inc max))
            :let [result (check-port port 100)]
            :when (and (:ok result)
                       (not (contains? registered-ports port)))]
        {:port port
         :status :listening-but-unregistered}))
    (catch Exception e
      (log/warn e "Failed to find listening orphans")
      [])))

(defn- find-orphaned-servers
  "Find nREPL servers in the registry but not accepting connections."
  []
  (try
    (require 'seon.orchestrator.nrepl)
    (let [servers @(ns-resolve (find-ns 'seon.orchestrator.nrepl) 'servers)]
      (for [[session-id {:keys [port]}] @servers
            :let [result (check-port port 200)]
            :when (not (:ok result))]
        {:session-id session-id
         :port port
         :error (:error result)}))
    (catch Exception e
      (log/warn e "Failed to find orphaned servers")
      [])))

;; Request/response schemas for cleanup
(schema/register! ::cleanup-orphaned-resources-request
                  [:map])

(schema/register! ::ports-released
                  [:int {:min 0 :description "Number of stale port registry entries released"}])

(schema/register! ::servers-removed
                  [:int {:min 0 :description "Number of stale server registry entries removed"}])

(schema/register! ::orphans-killed
                  [:int {:min 0 :description "Number of orphaned nREPL servers killed by port"}])

(schema/register! ::cleanup-error
                  [:map
                   [:type [:enum :port :server :orphan]]
                   [:port {:optional true} :int]
                   [:session-id {:optional true} :string]
                   [:error :string]])

(schema/register! ::cleanup-errors
                  [:vector ::cleanup-error])

(schema/register! ::cleanup-orphaned-resources-response
                  [:map
                   [::ports-released ::ports-released]
                   [::servers-removed ::servers-removed]
                   [::orphans-killed ::orphans-killed]
                   [::cleanup-errors ::cleanup-errors]])

(defn- kill-port!
  "Kill any process listening on a port. Returns true if successful."
  [port]
  (try
    (let [result (shell/sh "lsof" "-ti" (str ":" port))]
      (when (zero? (:exit result))
        (let [pids (str/split-lines (str/trim (:out result)))]
          (doseq [pid pids]
            (when-not (str/blank? pid)
              (log/info "Killing orphaned nREPL process" {:port port :pid pid})
              (shell/sh "kill" "-9" pid)))
          true)))
    (catch Exception e
      (log/warn e "Failed to kill process on port" {:port port})
      false)))

(defn cleanup-orphaned-resources!
  "Clean up orphaned resources from previous crashes or resets.

   This function handles three types of orphans:
   1. Registry entries pointing to dead ports (stale entries)
   2. nREPL servers listening but not in registry (survived reset)
   3. Both types are cleaned up to restore a consistent state

   Request keys: (none)

   Response keys:
     ::ports-released - Number of stale port registry entries released
     ::servers-removed - Number of stale server registry entries removed
     ::orphans-killed - Number of orphaned nREPL servers killed by port
     ::cleanup-errors - Vector of error maps

   Example:
     (cleanup-orphaned-resources! {})"
  {:malli/schema [:=> [:cat ::cleanup-orphaned-resources-request] ::cleanup-orphaned-resources-response]}
  [{}]
  (log/info "Cleaning up orphaned resources")
  (let [errors (atom [])

        ;; 1. Clean up stale registry entries (registered but not listening)
        orphaned-ports (find-orphaned-ports)
        _ (doseq [{:keys [session-id port]} orphaned-ports]
            (try
              (log/info "Releasing stale port registry entry" {:session-id session-id :port port})
              ((resolve 'seon.orchestrator.nrepl/release-port!) session-id)
              (catch Exception e
                (swap! errors conj {:type :port :session-id session-id :port port :error (.getMessage e)}))))

        ;; 2. Clean up stale server entries
        orphaned-servers (find-orphaned-servers)
        _ (doseq [{:keys [session-id port]} orphaned-servers]
            (try
              (log/info "Removing stale server registry entry" {:session-id session-id :port port})
              (let [servers-atom (ns-resolve (find-ns 'seon.orchestrator.nrepl) 'servers)]
                (swap! @servers-atom dissoc session-id))
              (catch Exception e
                (swap! errors conj {:type :server :session-id session-id :port port :error (.getMessage e)}))))

        ;; 3. Kill truly orphaned servers (listening but not in registry)
        listening-orphans (find-listening-orphans)
        killed-count (atom 0)
        _ (doseq [{:keys [port]} listening-orphans]
            (try
              (when (kill-port! port)
                (swap! killed-count inc))
              (catch Exception e
                (swap! errors conj {:type :orphan :port port :error (.getMessage e)}))))]

    {::ports-released (count orphaned-ports)
     ::servers-removed (count orphaned-servers)
     ::orphans-killed @killed-count
     ::cleanup-errors @errors}))

(comment
  ;; REPL exploration

  ;; Quick check
  (quick-check {})

  ;; Deep check
  (deep-check {})

  ;; Check individual components
  (check-nrepl)
  (check-agents)
  (check-resources)

  ;; Find orphans
  (find-orphaned-ports)
  (find-orphaned-servers)

  ;; Clean up
  (cleanup-orphaned-resources! {})

  nil)
