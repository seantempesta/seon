(ns seon.health
  "System health checks and monitoring.

   Provides comprehensive health checking for all system components:
   - nREPL server availability
   - Agent registry status
   - Pool JVM status
   - Resource utilization

   ## Usage

   ```clojure
   ;; Health check
   (check {})
   ;; => {:seon.health/status :healthy
   ;;     :seon.health/checks {:nrepl {:ok true}, ...}
   ;;     :seon.health/resources {:agents 2, :pool-jvms 3, :sessions 2}}
   ```"
  (:require [clojure.string :as str]
            [seon.ai.agent :as agent]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.net Socket InetSocketAddress]
           [java.util.concurrent Executors TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Startup Phase Tracking
;;; ---------------------------------------------------------------------------
;;; An atom that core.clj updates during two-phase startup.
;;; Possible values: :phase-1, :phase-2, :ready, :degraded

(defonce startup-phase (atom :phase-1))

(defn set-startup-phase!
  "Set the current startup phase. Called by seon.core during init."
  [phase]
  (reset! startup-phase phase))

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
                   [:mode {:optional true} [:enum :adopted :started :not-running]]
                   [:port {:optional true} [:int {:min 1 :max 65535}]]
                   [:details {:optional true} [:map-of :keyword :any]]])

(schema/register! ::checks
                  [:map-of :keyword ::check-result])

(schema/register! ::resources
                  [:map {:description "Resource counts"}
                   [:agents :int]
                   [:pool-jvms :int]
                   [:sessions :int]])

(schema/register! ::check-request
                  [:map])

(schema/register! ::startup-phase
                  [:enum {:description "Current startup phase"}
                   :phase-1 :phase-2 :ready :degraded])

(schema/register! ::check-response
                  [:map
                   [::status ::status]
                   [::timestamp inst?]
                   [::checks ::checks]
                   [::resources ::resources]
                   [::startup-phase {:optional true} ::startup-phase]])

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

(defn- get-system
  "Get the current Integrant system, or nil."
  []
  (try
    (require 'integrant.repl.state)
    @(resolve 'integrant.repl.state/system)
    (catch Exception _ nil)))

(defn- component-mode
  "Determine the mode of a component: :adopted, :started, or :not-running."
  [component]
  (cond
    (nil? component) :not-running
    (:adopted? component) :adopted
    :else :started))

;; Legacy external-DB-process probes deleted in chunk M-1 (2026-05-15).
;; The Integrant keys those probes targeted were removed from system.edn,
;; so the check always reported :not-running and flipped the system to
;; :unhealthy on every boot. Datahike runs in-process per namespace and
;; reports its health via :seon.db/flow; no separate external probe needed.

(defn- check-resources
  "Get current resource utilization."
  []
  (try
    (let [list-fn (requiring-resolve 'seon.orchestrator.session/list-agent-sessions)
          session-ns (find-ns 'seon.orchestrator.session)
          running-agents (agent/agents {})
          pool-jvms (try
                      (require 'seon.flow.pool)
                      (let [pool-ns (find-ns 'seon.flow.pool)]
                        (if-let [pool-var (ns-resolve session-ns 'agent-pool)]
                          (if-let [pool @@pool-var]
                            (let [status ((ns-resolve pool-ns 'pool-status) pool)]
                              (:seon.flow.pool/total status))
                            0)
                          0))
                      (catch Exception _ 0))]
      {:agents (count running-agents)
       :pool-jvms pool-jvms
       :sessions (count (list-fn {}))})
    (catch Exception e
      (log/warn e "Failed to check resources")
      {:agents 0
       :pool-jvms 0
       :sessions 0})))

(defn- check-http
  "Check HTTP server health (port 8080)."
  []
  (assoc (check-port 8080 1000) :port 8080 :mode :started))

(defn- check-caddy
  "Check Caddy reverse proxy health (port 3030)."
  []
  (let [system (get-system)
        caddy-component (:seon.web/caddy system)
        mode (component-mode caddy-component)
        tcp-check (check-port 3030 500)]
    (assoc tcp-check :port 3030 :mode mode)))

(defn- check-tailwind
  "Check Tailwind watcher status."
  []
  (let [system (get-system)
        tw-component (:seon.web/tailwind system)
        mode (component-mode tw-component)
        alive? (or (:adopted? tw-component)
                   (and (:process tw-component)
                        (.isAlive ^Process (:process tw-component))))]
    {:ok (boolean alive?) :mode mode}))

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
      (if-let [pool-var (ns-resolve session-ns 'agent-pool)]
        (if-let [pool @@pool-var]
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
;;; Operational Health Checks (beyond port connectivity)
;;; ---------------------------------------------------------------------------

(defn- check-runtime-persisted
  "Check that runtime instances are registered in memory."
  []
  (try
    (require 'seon.runtime)
    (let [instances-fn (resolve 'seon.runtime/instances)
          instances (instances-fn {})
          count-instances (count instances)]
      {:ok (pos? count-instances)
       :details {:instance-count count-instances}})
    (catch Exception e
      {:ok false
       :error (.getMessage e)})))

;;; ---------------------------------------------------------------------------
;;; Aggregate Health Status
;;; ---------------------------------------------------------------------------

(defn- determine-status
  "Determine overall health status from individual checks and startup phase.

   - :healthy - All checks pass and system is ready
   - :degraded - Some non-critical checks fail, or startup phase is degraded
   - :unhealthy - Critical checks fail

   M-1 (2026-05-15): the legacy external-DB critical-key check was deleted.
   The datahike flow runs in-process per namespace, so the health story now
   derives entirely from :seon.db/flow + the other component checks below.
   No critical-key check currently produces `:unhealthy`; the gradient is
   `:healthy → :degraded` only. A future commit reinstates a critical-key
   check (likely `:flow` reading the :seon.db/flow component status) once
   the runtime/AI migrations expose a probe surface."
  [checks]
  (let [phase @startup-phase
        critical-keys []
        critical-checks (select-keys checks critical-keys)
        critical-down? (and (seq critical-checks)
                            (some (fn [[_k v]] (and (map? v) (not (:ok v))))
                                  critical-checks))
        all-ok? (every? :ok (vals checks))]
    (cond
      critical-down? :unhealthy
      (= phase :degraded) :degraded
      all-ok? :healthy
      :else :degraded)))

(defn check
  "Comprehensive health check for debugging and monitoring.

   Checks all services with mode reporting:
   - nREPL server (started)
   - HTTP server (started)
   - Caddy reverse proxy (adopted/started)
   - Tailwind watcher (adopted/started)
   - Agent subsystem
   - Pool JVM status

   M-1 (2026-05-15): the legacy external-DB check was deleted; datahike now
   runs in-process per namespace and reports via :seon.db/flow.

   Request keys: (none)

   Response keys:
     ::status - :healthy, :degraded, or :unhealthy
     ::timestamp - When the check was performed
     ::checks - Map of service -> check-result (includes :mode and :port)
     ::resources - Current resource utilization
     ::startup-phase - Current startup phase

   Example:
     (check {})"
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{}]
  (let [nrepl-check (check-nrepl)
        http-check (check-http)
        caddy-check (check-caddy)
        tailwind-check (check-tailwind)
        agents-check (check-agents)
        pool-check (check-pool)
        resources (check-resources)
        ;; Operational checks only after Phase 2 (DB and flow are up)
        phase @startup-phase
        operational? (contains? #{:ready :degraded} phase)
        checks (cond-> {:nrepl nrepl-check
                         :http http-check
                         :caddy caddy-check
                         :tailwind tailwind-check
                         :agents agents-check
                         :pool pool-check}
                 operational? (assoc :runtime-persisted (check-runtime-persisted)))
        status (determine-status checks)]
    {::status status
     ::timestamp (java.util.Date.)
     ::checks checks
     ::resources resources
     ::startup-phase @startup-phase}))

;;; ---------------------------------------------------------------------------
;;; Readiness Gate (called at startup)
;;; ---------------------------------------------------------------------------

(defn readiness-gate
  "Run post-init readiness checks. Returns a map of check results.
   Called by core.clj between Phase 2 completion and 'System ready' log.

   Checks:
     :runtime-persisted - Are runtime instances registered?

   Scanner and pool are NOT required for readiness (background work)."
  []
  (let [checks {:runtime-persisted (check-runtime-persisted)}
        all-pass? (every? :ok (vals checks))
        failed (into {} (filter (fn [[_k v]] (not (:ok v))) checks))]
    {:all-pass? all-pass?
     :checks checks
     :failed failed}))

;;; ---------------------------------------------------------------------------
;;; Post-Start Observation
;;; ---------------------------------------------------------------------------

(defonce ^:private post-start-scheduler (atom nil))

(defn- run-post-start-check!
  "Re-run readiness checks and log any degradation at WARN."
  [label]
  (try
    (let [{:keys [all-pass? failed]} (readiness-gate)]
      (if all-pass?
        (log/info (str "Post-start check (" label "): all operational checks pass"))
        (log/warn (str "Post-start check (" label "): degradation detected")
                  {:failed-checks (keys failed)
                   :details failed})))
    (catch Exception e
      (log/warn e (str "Post-start check (" label ") failed with exception")))))

(defn start-post-start-observation!
  "Schedule background re-checks at 30s and 60s after startup.
   Logs WARN if any operational check degrades."
  []
  (let [scheduler (Executors/newSingleThreadScheduledExecutor)]
    (reset! post-start-scheduler scheduler)
    (.schedule scheduler
              ^Runnable (fn [] (run-post-start-check! "30s"))
              30 TimeUnit/SECONDS)
    (.schedule scheduler
              ^Runnable (fn [] (run-post-start-check! "60s")
                          ;; Shut down the scheduler after the last check
                          (.shutdown scheduler)
                          (reset! post-start-scheduler nil))
              60 TimeUnit/SECONDS)))

;;; ---------------------------------------------------------------------------
;;; Startup Summary
;;; ---------------------------------------------------------------------------

(defn log-startup-summary!
  "Log a clean summary of all services after startup completes."
  []
  (let [system (get-system)
        caddy (:seon.web/caddy system)
        tw (:seon.web/tailwind system)
        pool-check (check-pool)
        pool-detail (:details pool-check)
        mode-str (fn [component] (if (:adopted? component) "adopted" "started"))
        lines (cond-> ["  nREPL      :7888  (started)"
                        "  HTTP       :8080  (started)"]
                caddy
                (conj (str "  Caddy      :3030  (" (mode-str caddy) ")"))
                tw
                (conj (str "  Tailwind           (" (mode-str tw) ")"))
                pool-detail
                (conj (str "  Pool        "
                           (:idle pool-detail 0) "/" (:total pool-detail 0)
                           "   (started)")))]
    (log/info (str "System ready:\n" (str/join "\n" lines)))))

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
                    (cleanup!))
                  (catch Exception e
                    (swap! errors conj {:type :pool :error (.getMessage e)})
                    0))]
    {::stale-agents-cleaned cleaned
     ::cleanup-errors @errors}))

(comment
  ;; REPL exploration

  ;; Health check
  (check {})

  ;; Check individual components
  (check-nrepl)
  (check-agents)
  (check-pool)
  (check-resources)

  ;; Clean up
  (cleanup-orphaned-resources! {})

  nil)
