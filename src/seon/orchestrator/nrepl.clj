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

;; Default port range for namespace nREPLs
(def ^:private default-base-port 7889)
(def ^:private default-max-port 7999)

;; Configurable port range (allows tests to use different ports)
(defonce ^:private port-range (atom {:base default-base-port :max default-max-port}))

(defn set-port-range!
  "Set the port range for namespace nREPL servers.

   Useful for tests to avoid conflicts with dev server ports.

   Args:
     base - First port to try (e.g., 17889 for tests)
     max  - Maximum port number (e.g., 17999 for tests)

   Returns:
     The new port range map"
  [base max]
  (reset! port-range {:base base :max max}))

(defn reset-port-range!
  "Reset the port range to defaults (7889-7999).

   Call this in test teardown to restore production settings.

   Returns:
     The default port range map"
  []
  (reset! port-range {:base default-base-port :max default-max-port}))

(defn get-port-range
  "Get the current port range configuration.

   Returns:
     Map with :base and :max keys"
  []
  @port-range)

;; Map of session-id -> allocated port
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
  "Find the next available port starting from the configured base port.

  Args:
    exclude-ports - Set of ports to skip (already allocated but maybe not bound yet)

  Returns:
    Available port number

  Throws:
    ex-info if no ports available in range"
  ([] (find-available-port #{}))
  ([exclude-ports]
   (let [{:keys [base max]} @port-range]
     (loop [port base]
       (cond
         (> port max)
         (throw (ex-info "No available ports in range"
                         {:base-port base
                          :max-port max
                          :allocated (vals @port-registry)}))

         (contains? exclude-ports port)
         (recur (inc port))

         (port-available? port)
         port

         :else
         (recur (inc port)))))))

;; Lock for thread-safe port allocation
(defonce ^:private port-lock (Object.))

(defn allocate-port!
  "Allocate a port for a session.

  If the session already has a port allocated, returns that port.
  Otherwise finds the next available port and registers it.

  Thread-safe - uses locking to prevent race conditions.

  Args:
    session-id - Session ID string

  Returns:
    Port number"
  [session-id]
  (locking port-lock
    (if-let [existing (get @port-registry session-id)]
      existing
      (let [allocated-ports (set (vals @port-registry))
            port (find-available-port allocated-ports)]
        (swap! port-registry assoc session-id port)
        (log/debug "Allocated port for session" {:session-id session-id :port port})
        port))))

(defn release-port!
  "Release a port allocation for a session.

  Args:
    session-id - Session ID string"
  [session-id]
  (when-let [port (get @port-registry session-id)]
    (swap! port-registry dissoc session-id)
    (log/debug "Released port for session" {:session-id session-id :port port})))

(defn get-allocated-port
  "Get the port allocated to a session, or nil if none.

  Args:
    session-id - Session ID string

  Returns:
    Port number or nil"
  [session-id]
  (get @port-registry session-id))

;;; ---------------------------------------------------------------------------
;;; Custom Middleware for Context Injection
;;; ---------------------------------------------------------------------------

(defn make-context-middleware
  "Create middleware that injects *ctx* and *ns* into nREPL sessions.

  The middleware intercepts 'clone' responses to inject bindings into
  newly created sessions. This ensures the bindings are present BEFORE
  any eval operations use the session.

  *ctx* is interned directly in the target namespace so agents can use
  @*ctx* without qualification.

  Args:
    ctx-atom - The atom to bind to *ctx*
    target-ns - The namespace symbol to bind to *ns*

  Returns:
    nREPL middleware function"
  [ctx-atom target-ns]
  ;; Ensure the namespace exists with clojure.core referred and *ctx* interned
  (let [ensure-ns-and-ctx (fn []
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
                            (let [ns-obj (or (find-ns target-ns)
                                             (binding [*ns* (create-ns target-ns)]
                                               (refer-clojure)
                                               *ns*))]
                              ;; Intern *ctx* as a dynamic var in the target namespace
                              ;; This allows agents to use @*ctx* without qualification
                              (when-not (ns-resolve ns-obj '*ctx*)
                                (let [v (intern ns-obj '*ctx* nil)]
                                  (.setDynamic v true)))
                              ;; Return both the namespace and the *ctx* var
                              {:ns-obj ns-obj
                               :ctx-var (ns-resolve ns-obj '*ctx*)}))
        ;; Cache the setup (resolved once when middleware is created)
        setup (delay (ensure-ns-and-ctx))]
    (fn wrap-context [handler]
      (fn [{:keys [session] :as msg}]
        ;; Inject bindings when we see a session atom that either:
        ;; 1. Doesn't have our *ctx* var yet, OR
        ;; 2. Has a DIFFERENT *ctx* (from a different server/namespace)
        ;; This happens after session middleware has created/retrieved the session
        (let [{:keys [ns-obj ctx-var]} @setup]
          (when (and (instance? clojure.lang.Atom session)
                     (not (identical? (get @session ctx-var) ctx-atom)))
            (swap! session assoc
                   #'*ns* ns-obj
                   ctx-var ctx-atom)))
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

;; Map of session-id -> server info
;; Each entry contains:
;; - :server - The nREPL server object
;; - :port - Port the server is bound to
;; - :ctx - The context atom for this namespace
;; - :namespace - The namespace symbol
;; - :session-id - The session ID
;; - :status - :running or :stopped
(defonce ^:private servers (atom {}))

(defn session-server-running?
  "Check if a session has a running nREPL server.

  Args:
    session-id - Session ID string

  Returns:
    Boolean"
  [session-id]
  (contains? @servers session-id))

;;; ---------------------------------------------------------------------------
;;; Server Lifecycle
;;; ---------------------------------------------------------------------------

(defn start-namespace-nrepl!
  "Start an nREPL server for a session with injected context.

  Creates a new nREPL server bound to an available port. All sessions on this
  server will have *ctx* bound to an atom containing the agent context, and
  *ns* bound to the target namespace.

  Options:
    :session-id - The session ID string (required)
    :namespace  - The Clojure namespace symbol (required)
    :db         - XTDB connection for this namespace (optional)
    :render-fn  - Function to render UI updates (optional)
    :worktree   - Path to git worktree (optional)
    :port       - Port to bind to (auto-assigned if not specified)
    :ctx-atom   - Existing ctx atom to use (optional, creates new if not provided)
                  When provided, the atom is used directly. The caller is responsible
                  for initializing it with :seon.agent/* keys. This is used by
                  session.clj to inject persisted ctx atoms.

  Returns:
    Map with :server, :port, :ctx, :namespace, :session-id, :status on success
    Map with :status :error or :port-conflict on failure

  Throws:
    ex-info if session already has a server"
  [{:keys [session-id namespace db render-fn worktree port ctx-atom] :as opts}]
  (when-not session-id
    (throw (ex-info "session-id is required" {:opts opts})))
  (when-not namespace
    (throw (ex-info "namespace is required" {:opts opts})))

  (when (session-server-running? session-id)
    (throw (ex-info "Session already has an nREPL server running"
                    {:session-id session-id
                     :namespace namespace
                     :port (get-allocated-port session-id)})))

  (try
    ;; Use provided ctx-atom or create a new one
    (let [ctx-atom (or ctx-atom
                       (atom {:seon.agent/namespace namespace
                              :seon.agent/db db
                              :seon.agent/render-fn render-fn
                              :seon.agent/worktree worktree
                              :seon.agent/started-at (java.util.Date.)}))

          ;; Create the middleware function for this ctx/namespace
          ctx-middleware (make-context-middleware ctx-atom namespace)

          ;; Allocate port by session-id (allows multiple agents on same namespace)
          port (or port (allocate-port! session-id))

          ;; Create a var-like wrapper so we can set the descriptor
          ;; We use alter-meta! on a var to attach the descriptor
          middleware-var (intern *ns* (gensym (str "ctx-middleware-" session-id "-")))
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
                  :session-id session-id
                  :status :running
                  :middleware-var middleware-var}]

      ;; Register by session-id (not namespace)
      (swap! servers assoc session-id result)

      ;; Update ctx with the actual port (might differ if auto-assigned)
      ;; Only do this for internally-created atoms; externally provided atoms
      ;; (like persisted ctx) have reserved key protection
      (when-not (:ctx-atom opts)
        (swap! ctx-atom assoc :seon.agent/nrepl-port (:port server)))

      (log/info "Started namespace nREPL server"
                {:session-id session-id
                 :namespace namespace
                 :port (:port server)})

      result)

    (catch java.net.BindException e
      (release-port! session-id)
      (log/warn "Port conflict starting namespace nREPL"
                {:session-id session-id :namespace namespace :port port :error (.getMessage e)})
      {:status :port-conflict
       :session-id session-id
       :namespace namespace
       :error (str "Port " port " already in use")})

    (catch Exception e
      (release-port! session-id)
      (log/error "Error starting namespace nREPL"
                 {:session-id session-id :namespace namespace :error (.getMessage e)})
      {:status :error
       :session-id session-id
       :namespace namespace
       :error (.getMessage e)})))

(defn stop-namespace-nrepl!
  "Stop the nREPL server for a session.

  Closes the server socket and all active connections, cleans up the port
  allocation, and removes the server from the registry.

  Args:
    session-id - Session ID string

  Returns:
    Map with :status :stopped, :session-id, :namespace, :port on success
    nil if no server was running"
  [session-id]
  (when-let [{:keys [server port namespace middleware-var]} (get @servers session-id)]
    (log/info "Stopping namespace nREPL server"
              {:session-id session-id :namespace namespace :port port})

    ;; Stop the server (closes socket and connections)
    (nrepl/stop-server server)

    ;; Clean up the middleware var
    (when middleware-var
      (ns-unmap *ns* (symbol (name (.sym middleware-var)))))

    ;; Release port and remove from registry
    (release-port! session-id)
    (swap! servers dissoc session-id)

    {:status :stopped
     :session-id session-id
     :namespace namespace
     :port port}))

(defn stop-all-namespace-nrepls!
  "Stop all running namespace nREPL servers.

  Returns:
    Sequence of stop results"
  []
  (doall
   (for [session-id (keys @servers)]
     (stop-namespace-nrepl! session-id))))

;;; ---------------------------------------------------------------------------
;;; Server Query Functions
;;; ---------------------------------------------------------------------------

(defn list-namespace-servers
  "List all running namespace nREPL servers.

  Returns:
    Sequence of maps with :session-id, :namespace, :port, :status, :started-at"
  []
  (for [[session-id {:keys [namespace port status ctx]}] @servers]
    {:session-id session-id
     :namespace namespace
     :port port
     :status status
     :started-at (:seon.agent/started-at @ctx)}))

(defn get-session-server
  "Get information about a session's nREPL server.

  Args:
    session-id - Session ID string

  Returns:
    Server info map or nil if not running"
  [session-id]
  (get @servers session-id))

(defn get-session-ctx
  "Get the context atom for a session's nREPL server.

  Args:
    session-id - Session ID string

  Returns:
    The ctx atom or nil if no server running"
  [session-id]
  (:ctx (get @servers session-id)))

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
