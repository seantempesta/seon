(ns seon.health
  "System health checks and monitoring.

   Provides comprehensive health checking for all system components:
   - XTDB database connectivity and responsiveness
   - nREPL server availability
   - Agent registry status
   - Resource utilization (ports, sessions, channels)

   ## Usage

   ```clojure
   ;; Quick health check (for load balancers)
   (quick-check {:seon.health/node xtdb-node})
   ;; => {:seon.health/status :healthy, :seon.health/timestamp #inst \"...\"}

   ;; Deep health check (for debugging)
   (deep-check {:seon.health/node xtdb-node})
   ;; => {:seon.health/status :healthy
   ;;     :seon.health/checks {:xtdb {:ok true}, :nrepl {:ok true}, ...}
   ;;     :seon.health/resources {:agents 2, :ports 3, :sessions 2}}
   ```"
  (:require [seon.db.node :as db]
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

(schema/register! ::node
                  [:any {:description "XTDB node reference"}])

(schema/register! ::quick-check-request
                  [:map
                   [::node ::node]])

(schema/register! ::quick-check-response
                  [:map
                   [::status ::status]
                   [::timestamp inst?]
                   [::checks {:optional true} ::checks]])

(schema/register! ::deep-check-request
                  [:map
                   [::node ::node]])

(schema/register! ::deep-check-response
                  [:map
                   [::status ::status]
                   [::timestamp inst?]
                   [::checks ::checks]
                   [::resources ::resources]])

;;; ---------------------------------------------------------------------------
;;; Component Health Checks (Internal)
;;; ---------------------------------------------------------------------------

(defn- check-xtdb
  "Check XTDB node health by executing a simple query."
  [node]
  (let [start (System/currentTimeMillis)]
    (try
      (let [status (db/status node)
            latency (- (System/currentTimeMillis) start)]
        {:ok true
         :latency-ms latency
         :details {:latest-completed-tx (:latest-completed-tx status)}})
      (catch Exception e
        {:ok false
         :latency-ms (- (System/currentTimeMillis) start)
         :error (.getMessage e)}))))

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
   - :unhealthy - Critical checks fail (XTDB)"
  [checks]
  (let [xtdb-ok? (get-in checks [:xtdb :ok] false)
        all-ok? (every? :ok (vals checks))]
    (cond
      (not xtdb-ok?) :unhealthy
      (not all-ok?) :degraded
      :else :healthy)))

(defn quick-check
  "Quick health check suitable for load balancers and monitoring.

   Only checks critical components (XTDB) for speed.

   Request keys:
     ::node - Required. XTDB node to check

   Response keys:
     ::status - :healthy, :degraded, or :unhealthy
     ::timestamp - When the check was performed
     ::checks - Map of component -> result (only if not healthy)

   Example:
     (quick-check {::node xtdb-node})"
  {:malli/schema [:=> [:cat ::quick-check-request] ::quick-check-response]}
  [{::keys [node]}]
  (let [xtdb-check (check-xtdb node)
        status (if (:ok xtdb-check) :healthy :unhealthy)]
    (cond-> {::status status
             ::timestamp (java.util.Date.)}
      (not= status :healthy)
      (assoc ::checks {:xtdb xtdb-check}))))

(defn deep-check
  "Comprehensive health check for debugging and monitoring.

   Checks all components:
   - XTDB database
   - Main nREPL server
   - Agent subsystem
   - All namespace nREPL servers

   Request keys:
     ::node - Required. XTDB node to check

   Response keys:
     ::status - :healthy, :degraded, or :unhealthy
     ::timestamp - When the check was performed
     ::checks - Map of component -> check-result
     ::resources - Current resource utilization

   Example:
     (deep-check {::node xtdb-node})"
  {:malli/schema [:=> [:cat ::deep-check-request] ::deep-check-response]}
  [{::keys [node]}]
  (let [xtdb-check (check-xtdb node)
        nrepl-check (check-nrepl)
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
        checks {:xtdb xtdb-check
                :nrepl nrepl-check
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
                  [:map
                   [::node ::node]])

(schema/register! ::cleanup-orphaned-resources-response
                  [:map
                   [:ports-released :int]
                   [:servers-removed :int]
                   [:errors [:vector [:map-of :keyword :any]]]])

(defn cleanup-orphaned-resources!
  "Clean up orphaned resources from previous crashes.

   This function:
   1. Finds ports registered but not listening
   2. Releases those port allocations
   3. Removes stale server entries

   Request keys:
     ::node - Required. XTDB node

   Response keys:
     :ports-released - Number of ports released
     :servers-removed - Number of server entries removed
     :errors - Vector of error maps

   Example:
     (cleanup-orphaned-resources! {::node xtdb-node})"
  {:malli/schema [:=> [:cat ::cleanup-orphaned-resources-request] ::cleanup-orphaned-resources-response]}
  [{::keys [node]}]
  (log/info "Cleaning up orphaned resources")
  (let [_ node ; unused but required for consistency
        errors (atom [])

        ;; Clean up orphaned ports
        orphaned-ports (find-orphaned-ports)
        _ (doseq [{:keys [session-id port]} orphaned-ports]
            (try
              (log/info "Releasing orphaned port" {:session-id session-id :port port})
              ((resolve 'seon.orchestrator.nrepl/release-port!) session-id)
              (catch Exception e
                (swap! errors conj {:type :port :session-id session-id :error (.getMessage e)}))))

        ;; Clean up orphaned servers
        orphaned-servers (find-orphaned-servers)
        _ (doseq [{:keys [session-id port]} orphaned-servers]
            (try
              (log/info "Removing orphaned server entry" {:session-id session-id :port port})
              (let [servers-atom (ns-resolve (find-ns 'seon.orchestrator.nrepl) 'servers)]
                (swap! @servers-atom dissoc session-id))
              (catch Exception e
                (swap! errors conj {:type :server :session-id session-id :error (.getMessage e)}))))]

    {:ports-released (count orphaned-ports)
     :servers-removed (count orphaned-servers)
     :errors @errors}))

(comment
  ;; REPL exploration

  (require '[integrant.repl.state :as state])
  (def node (:seon/xtdb-node state/system))

  ;; Quick check
  (quick-check {::node node})

  ;; Deep check
  (deep-check {::node node})

  ;; Check individual components
  (check-xtdb node)
  (check-nrepl)
  (check-agents)
  (check-resources)

  ;; Find orphans
  (find-orphaned-ports)
  (find-orphaned-servers)

  ;; Clean up
  (cleanup-orphaned-resources! {::node node})

  nil)
