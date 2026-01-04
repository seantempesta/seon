(ns seon.orchestrator.nrepl
  "Manages per-namespace nREPL servers for agent isolation.

  Provides the infrastructure for running multiple nREPL servers within a single JVM,
  each bound to a specific namespace with injected agent context (*ctx*).

  ## Architecture

  Each namespace can have its own nREPL server on a unique port. When an agent
  connects to a namespace's nREPL, it gets:
  - `*ns*` bound to the target namespace
  - `*ctx*` bound to an atom containing the agent's context

  This enables:
  - Concurrent evaluations without blocking
  - Namespace-scoped state isolation
  - Easy access to namespace-specific resources (db, render-fn, etc.)

  ## Usage

  ```clojure
  ;; Start a namespace nREPL
  (def trading-repl (start-namespace-nrepl! {:namespace 'seon.trading
                                              :db (get-db)
                                              :render-fn (make-renderer)}))
  ;; => {:server ... :port 7889 :ctx #atom{...} :namespace seon.trading :status :running}

  ;; Agent connects to port 7889 and gets:
  @*ctx*
  ;; => {:seon.agent/namespace seon.trading
  ;;     :seon.agent/db <connection>
  ;;     :seon.agent/render-fn #fn
  ;;     :seon.agent/nrepl-port 7889
  ;;     :seon.agent/started-at #inst \"...\"}

  ;; Stop when done
  (stop-namespace-nrepl! 'seon.trading)
  ```

  ## Port Allocation

  Ports are allocated starting from 7889 (one after the main nREPL at 7888).
  The system tracks which ports are in use and finds the next available one."
  (:require [clojure.string :as str]
            [nrepl.middleware :refer [set-descriptor!]]
            [nrepl.middleware.session :as session]
            [nrepl.server :as nrepl]
            [taoensso.timbre :as log])
  (:import [java.net ServerSocket]))

;;; ---------------------------------------------------------------------------
;;; Dynamic Var for Agent Context
;;; ---------------------------------------------------------------------------

(def ^:dynamic *ctx*
  "Agent context atom. Available in all sessions on namespace nREPL servers.

  Contains:
  - :seon.agent/namespace - The Clojure namespace symbol (read-only)
  - :seon.agent/db - XTDB connection for this namespace (if provided)
  - :seon.agent/render-fn - Function to render UI updates (if provided)
  - :seon.agent/nrepl-port - The port this nREPL is running on
  - :seon.agent/started-at - When this server was started
  - :seon.agent/worktree - Path to git worktree (if provided)

  Agents can swap! their own data into the ctx - only the :seon.agent/* keys
  are reserved for system use."
  nil)

;;; ---------------------------------------------------------------------------
;;; Port Management
;;; ---------------------------------------------------------------------------

(def ^:private base-port
  "First port to try for namespace nREPLs. Main nREPL is at 7888."
  7889)

(def ^:private max-port
  "Maximum port number to try."
  7999)

;; Map of namespace -> allocated port
(defonce ^:private port-registry (atom {}))

(defn- port-available?
  "Check if a port is available for binding."
  [port]
  (try
    (with-open [_ (ServerSocket. port)]
      true)
    (catch java.net.BindException _
      false)))

(defn- find-available-port
  "Find the next available port starting from base-port.

  Args:
    exclude-ports - Set of ports to skip (already allocated but maybe not bound yet)

  Returns:
    Available port number

  Throws:
    ex-info if no ports available in range"
  ([] (find-available-port #{}))
  ([exclude-ports]
   (loop [port base-port]
     (cond
       (> port max-port)
       (throw (ex-info "No available ports in range"
                       {:base-port base-port
                        :max-port max-port
                        :allocated (vals @port-registry)}))

       (contains? exclude-ports port)
       (recur (inc port))

       (port-available? port)
       port

       :else
       (recur (inc port))))))

;; Lock for thread-safe port allocation
(defonce ^:private port-lock (Object.))

(defn allocate-port!
  "Allocate a port for a namespace.

  If the namespace already has a port allocated, returns that port.
  Otherwise finds the next available port and registers it.

  Thread-safe - uses locking to prevent race conditions.

  Args:
    namespace - Namespace symbol

  Returns:
    Port number"
  [namespace]
  (locking port-lock
    (if-let [existing (get @port-registry namespace)]
      existing
      (let [allocated-ports (set (vals @port-registry))
            port (find-available-port allocated-ports)]
        (swap! port-registry assoc namespace port)
        (log/debug "Allocated port for namespace" {:namespace namespace :port port})
        port))))

(defn release-port!
  "Release a port allocation for a namespace.

  Args:
    namespace - Namespace symbol"
  [namespace]
  (when-let [port (get @port-registry namespace)]
    (swap! port-registry dissoc namespace)
    (log/debug "Released port for namespace" {:namespace namespace :port port})))

(defn get-allocated-port
  "Get the port allocated to a namespace, or nil if none.

  Args:
    namespace - Namespace symbol

  Returns:
    Port number or nil"
  [namespace]
  (get @port-registry namespace))

;;; ---------------------------------------------------------------------------
;;; Custom Middleware for Context Injection
;;; ---------------------------------------------------------------------------

(defn make-context-middleware
  "Create middleware that injects *ctx* and *ns* into nREPL sessions.

  The middleware intercepts 'clone' responses to inject bindings into
  newly created sessions. This ensures the bindings are present BEFORE
  any eval operations use the session.

  Args:
    ctx-atom - The atom to bind to *ctx*
    target-ns - The namespace symbol to bind to *ns*

  Returns:
    nREPL middleware function"
  [ctx-atom target-ns]
  ;; Ensure the namespace exists with clojure.core referred
  (let [ensure-ns (fn []
                    ;; First try to require the namespace (if it exists on classpath)
                    (try
                      (require target-ns)
                      (catch java.io.FileNotFoundException _
                        ;; Namespace doesn't exist on classpath - create it
                        (log/debug "Creating namespace" {:namespace target-ns}))
                      (catch Exception e
                        (log/warn "Could not require namespace"
                                  {:namespace target-ns :error (.getMessage e)})))
                    ;; Get or create the namespace
                    (if-let [ns-obj (find-ns target-ns)]
                      ns-obj
                      ;; Create namespace with clojure.core referred
                      (binding [*ns* (create-ns target-ns)]
                        (refer-clojure)
                        *ns*)))]
    (fn wrap-context [handler]
      (fn [{:keys [session] :as msg}]
        ;; Inject bindings when we see a session atom that either:
        ;; 1. Doesn't have *ctx* yet, OR
        ;; 2. Has a DIFFERENT *ctx* (from a different server/namespace)
        ;; This happens after session middleware has created/retrieved the session
        (when (and (instance? clojure.lang.Atom session)
                   (not (identical? (get @session #'*ctx*) ctx-atom)))
          (let [ns-obj (ensure-ns)]
            (swap! session assoc
                   #'*ns* ns-obj
                   #'*ctx* ctx-atom)))
        (handler msg)))))

;; Set up the descriptor for the middleware
;; This tells nREPL where in the middleware chain to place it
(defn- set-context-descriptor!
  "Set the nREPL middleware descriptor for context injection.

  The middleware must run:
  - After 'session' (to have access to the session atom)
  - Before 'eval' (to set bindings before evaluation)"
  [middleware-var]
  (set-descriptor! middleware-var
    {:requires #{#'session/session}  ; Run after session middleware (var reference)
     :expects #{"eval"}}))           ; Run before eval middleware (op name)

;;; ---------------------------------------------------------------------------
;;; Server Registry
;;; ---------------------------------------------------------------------------

;; Map of namespace -> server info
;; Each entry contains:
;; - :server - The nREPL server object
;; - :port - Port the server is bound to
;; - :ctx - The context atom for this namespace
;; - :namespace - The namespace symbol
;; - :status - :running or :stopped
(defonce ^:private servers (atom {}))

(defn namespace-server-running?
  "Check if a namespace has a running nREPL server.

  Args:
    namespace - Namespace symbol

  Returns:
    Boolean"
  [namespace]
  (contains? @servers namespace))

;;; ---------------------------------------------------------------------------
;;; Server Lifecycle
;;; ---------------------------------------------------------------------------

(defn start-namespace-nrepl!
  "Start an nREPL server for a namespace with injected context.

  Creates a new nREPL server bound to an available port. All sessions on this
  server will have *ctx* bound to an atom containing the agent context, and
  *ns* bound to the target namespace.

  Options:
    :namespace - The Clojure namespace symbol (required)
    :db        - XTDB connection for this namespace (optional)
    :render-fn - Function to render UI updates (optional)
    :worktree  - Path to git worktree (optional)
    :port      - Port to bind to (auto-assigned if not specified)

  Returns:
    Map with :server, :port, :ctx, :namespace, :status on success
    Map with :status :error or :port-conflict on failure

  Throws:
    ex-info if namespace already has a server"
  [{:keys [namespace db render-fn worktree port] :as opts}]
  (when-not namespace
    (throw (ex-info "namespace is required" {:opts opts})))

  (when (namespace-server-running? namespace)
    (throw (ex-info "Namespace already has an nREPL server running"
                    {:namespace namespace
                     :port (get-allocated-port namespace)})))

  (try
    ;; Create the context atom
    (let [ctx-atom (atom {:seon.agent/namespace namespace
                          :seon.agent/db db
                          :seon.agent/render-fn render-fn
                          :seon.agent/worktree worktree
                          :seon.agent/started-at (java.util.Date.)})

          ;; Create the middleware function for this ctx/namespace
          ctx-middleware (make-context-middleware ctx-atom namespace)

          ;; Allocate port
          port (or port (allocate-port! namespace))

          ;; Create a var-like wrapper so we can set the descriptor
          ;; We use alter-meta! on a var to attach the descriptor
          middleware-var (intern *ns* (gensym (str "ctx-middleware-" namespace "-")))
          _ (alter-var-root middleware-var (constantly ctx-middleware))
          _ (set-context-descriptor! middleware-var)

          ;; Start server with the custom handler
          ;; We add our middleware to the default handler chain
          handler (nrepl/default-handler middleware-var)
          server (nrepl/start-server :port port :handler handler)

          result {:server server
                  :port (:port server)
                  :ctx ctx-atom
                  :namespace namespace
                  :status :running
                  :middleware-var middleware-var}]

      ;; Register
      (swap! servers assoc namespace result)

      ;; Update ctx with the actual port (might differ if auto-assigned)
      (swap! ctx-atom assoc :seon.agent/nrepl-port (:port server))

      (log/info "Started namespace nREPL server"
                {:namespace namespace
                 :port (:port server)})

      result)

    (catch java.net.BindException e
      (release-port! namespace)
      (log/warn "Port conflict starting namespace nREPL"
                {:namespace namespace :port port :error (.getMessage e)})
      {:status :port-conflict
       :namespace namespace
       :error (str "Port " port " already in use")})

    (catch Exception e
      (release-port! namespace)
      (log/error "Error starting namespace nREPL"
                 {:namespace namespace :error (.getMessage e)})
      {:status :error
       :namespace namespace
       :error (.getMessage e)})))

(defn stop-namespace-nrepl!
  "Stop the nREPL server for a namespace.

  Closes the server socket and all active connections, cleans up the port
  allocation, and removes the server from the registry.

  Args:
    namespace - Namespace symbol

  Returns:
    Map with :status :stopped, :namespace, :port on success
    nil if no server was running"
  [namespace]
  (when-let [{:keys [server port middleware-var]} (get @servers namespace)]
    (log/info "Stopping namespace nREPL server"
              {:namespace namespace :port port})

    ;; Stop the server (closes socket and connections)
    (nrepl/stop-server server)

    ;; Clean up the middleware var
    (when middleware-var
      (ns-unmap *ns* (symbol (name (.sym middleware-var)))))

    ;; Release port and remove from registry
    (release-port! namespace)
    (swap! servers dissoc namespace)

    {:status :stopped
     :namespace namespace
     :port port}))

(defn stop-all-namespace-nrepls!
  "Stop all running namespace nREPL servers.

  Returns:
    Sequence of stop results"
  []
  (doall
   (for [namespace (keys @servers)]
     (stop-namespace-nrepl! namespace))))

;;; ---------------------------------------------------------------------------
;;; Server Query Functions
;;; ---------------------------------------------------------------------------

(defn list-namespace-servers
  "List all running namespace nREPL servers.

  Returns:
    Sequence of maps with :namespace, :port, :status, :started-at"
  []
  (for [[ns {:keys [port status ctx]}] @servers]
    {:namespace ns
     :port port
     :status status
     :started-at (:seon.agent/started-at @ctx)}))

(defn get-namespace-server
  "Get information about a namespace's nREPL server.

  Args:
    namespace - Namespace symbol

  Returns:
    Server info map or nil if not running"
  [namespace]
  (get @servers namespace))

(defn get-namespace-ctx
  "Get the context atom for a namespace's nREPL server.

  Args:
    namespace - Namespace symbol

  Returns:
    The ctx atom or nil if no server running"
  [namespace]
  (:ctx (get @servers namespace)))

;;; ---------------------------------------------------------------------------
;;; Integrant Component
;;; ---------------------------------------------------------------------------
;;; The Integrant methods are defined in system.clj to avoid circular deps.
;;; See :seon.orchestrator/namespace-nrepls component.

(comment
  ;; REPL exploration

  ;; Start a namespace nREPL
  (def trading-repl
    (start-namespace-nrepl! {:namespace 'seon.trading}))

  ;; Check what's running
  (list-namespace-servers)

  ;; Get the ctx for a namespace
  (when-let [ctx (get-namespace-ctx 'seon.trading)]
    @ctx)

  ;; Stop a namespace nREPL
  (stop-namespace-nrepl! 'seon.trading)

  ;; Stop all
  (stop-all-namespace-nrepls!)

  ;; Port management
  (allocate-port! 'seon.test)
  (release-port! 'seon.test)
  (get-allocated-port 'seon.trading)

  nil)
