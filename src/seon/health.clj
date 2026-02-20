(ns seon.health
  "System health checks and monitoring.

   Provides comprehensive health checking for all system components:
   - nREPL server availability
   - Agent registry status
   - Pool JVM status
   - Resource utilization

   ## Usage

   ```clojure
   ;; Quick health check (for load balancers)
   (quick-check {})
   ;; => {:seon.health/status :healthy, :seon.health/timestamp #inst \"...\"}

   ;; Deep health check (for debugging)
   (deep-check {})
   ;; => {:seon.health/status :healthy
   ;;     :seon.health/checks {:nrepl {:ok true}, ...}
   ;;     :seon.health/resources {:agents 2, :pool-jvms 3, :sessions 2}}
   ```"
  (:require [seon.ai.agent :as agent]
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
                   [:pool-jvms :int]
                   [:sessions :int]])

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
    (require 'seon.orchestrator.session)
    (let [session-ns (find-ns 'seon.orchestrator.session)
          session-registry @(ns-resolve session-ns 'session-registry)
          running-agents (agent/agents {})
          ;; Pool status via dynamic require
          pool-jvms (try
                      (require 'seon.flow.pool)
                      (let [pool-ns (find-ns 'seon.flow.pool)]
                        ;; Pool ref is stored in session module
                        (if-let [pool-atom (ns-resolve session-ns 'agent-pool)]
                          (if-let [pool @pool-atom]
                            (let [status ((ns-resolve pool-ns 'pool-status) pool)]
                              (:seon.flow.pool/total status))
                            0)
                          0))
                      (catch Exception _ 0))]
      {:agents (count running-agents)
       :pool-jvms pool-jvms
       :sessions (count @session-registry)})
    (catch Exception e
      (log/warn e "Failed to check resources")
      {:agents 0
       :pool-jvms 0
       :sessions 0})))

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

(defn- check-pool
  "Check agent pool health."
  []
  (try
    (require 'seon.flow.pool)
    (require 'seon.orchestrator.session)
    (let [session-ns (find-ns 'seon.orchestrator.session)
          pool-ns (find-ns 'seon.flow.pool)]
      (if-let [pool-atom (ns-resolve session-ns 'agent-pool)]
        (if-let [pool @pool-atom]
          (let [status ((ns-resolve pool-ns 'pool-status) pool)]
            {:ok true
             :details {:total (:seon.flow.pool/total status)
                       :idle (:seon.flow.pool/idle status)
                       :active (:seon.flow.pool/active status)
                       :warming? (:seon.flow.pool/warming? status)}})
          {:ok true :details {:total 0 :note "pool not initialized"}})
        {:ok true :details {:total 0 :note "pool atom not found"}}))
    (catch Exception e
      {:ok false :error (.getMessage e)})))

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
   - Pool JVM status

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
        pool-check (check-pool)
        resources (check-resources)
        checks {:nrepl nrepl-check
                :agents agents-check
                :pool pool-check}
        status (determine-status checks)]
    {::status status
     ::timestamp (java.util.Date.)
     ::checks checks
     ::resources resources}))

;;; ---------------------------------------------------------------------------
;;; Cleanup Functions
;;; ---------------------------------------------------------------------------

;; Request/response schemas for cleanup
(schema/register! ::cleanup-orphaned-resources-request
                  [:map])

(schema/register! ::stale-agents-cleaned
                  [:int {:min 0 :description "Number of stale pool JVM processes killed"}])

(schema/register! ::cleanup-errors
                  [:vector [:map
                            [:type [:enum :pool :session]]
                            [:port {:optional true} :int]
                            [:error :string]]])

(schema/register! ::cleanup-orphaned-resources-response
                  [:map
                   [::stale-agents-cleaned ::stale-agents-cleaned]
                   [::cleanup-errors ::cleanup-errors]])

(defn cleanup-orphaned-resources!
  "Clean up orphaned resources from previous crashes or resets.

   Delegates to pool/cleanup-stale-agents! which kills JVM processes
   that are bound to ports in the agent range but not tracked by the pool.

   Request keys: (none)

   Response keys:
     ::stale-agents-cleaned - Number of stale pool JVM processes killed
     ::cleanup-errors - Vector of error maps

   Example:
     (cleanup-orphaned-resources! {})"
  {:malli/schema [:=> [:cat ::cleanup-orphaned-resources-request] ::cleanup-orphaned-resources-response]}
  [{}]
  (log/info "Cleaning up orphaned resources")
  (let [errors (atom [])
        cleaned (try
                  (require 'seon.flow.pool)
                  (let [cleanup! (resolve 'seon.flow.pool/cleanup-stale-agents!)]
                    (cleanup! 7900 10))
                  (catch Exception e
                    (swap! errors conj {:type :pool :error (.getMessage e)})
                    0))]
    {::stale-agents-cleaned cleaned
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
  (check-pool)
  (check-resources)

  ;; Clean up
  (cleanup-orphaned-resources! {})

  nil)
