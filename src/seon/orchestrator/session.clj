(ns seon.orchestrator.session
  "Agent session management - the high-level API for agent lifecycle.

  Provides a simple, opaque session abstraction so agents don't need to know
  about XTDB, nREPL ports, or persistence mechanics.

  ## Overview

  Sessions tie together:
  - Isolated XTDB database (via multi-db)
  - Persisted ctx atom (via agent.ctx)
  - Dedicated nREPL server (via orchestrator.nrepl)

  Agents just receive a session ID and use it for all evals.

  ## Usage

  ```clojure
  ;; Orchestrator starts a session
  (def s (start-agent-session! {::node xtdb-node ::namespace 'seon.trading}))
  ;; => {::id \"acdb234f\" ::namespace 'seon.trading ::status :running ...}

  ;; Agent uses session ID for evals (via bin/agent-eval)
  ;; agent-eval acdb234f '(swap! *ctx* assoc :seon.trading/signals [...])'

  ;; Resume existing session (loads previous ctx state)
  (start-agent-session! {::node xtdb-node ::namespace 'seon.trading ::resume? true})

  ;; Stop session (flushes ctx, stops nREPL)
  (stop-agent-session! {::node xtdb-node ::id \"acdb234f\"})

  ;; List active sessions
  (list-agent-sessions {::node xtdb-node})
  ```"
  (:require [seon.agent.ctx :as ctx]
            [seon.db.multi :as multi]
            [seon.orchestrator.nrepl :as nrepl-multi]
            [seon.schema :as schema]
            [taoensso.timbre :as log]
            [xtdb.api :as xt])
  (:import [java.security SecureRandom]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; XTDB node - cannot be generated (requires real database)
(schema/register! ::node
                  [:any {:description "XTDB orchestrator node"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate XTDB node"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::id
                  [:string {:min 4 :max 4
                            :pattern "^[a-f0-9]{4}$"
                            :description "4-character hex session ID"}])

(schema/register! ::namespace
                  [:symbol {:description "Agent namespace symbol"}])

(schema/register! ::status
                  [:enum :running :stopped :error])

(schema/register! ::nrepl-port
                  [:int {:min 7889 :max 7999
                         :description "nREPL port for this session"}])

(schema/register! ::started-at
                  [inst? {:description "When the session was started"}])

(schema/register! ::stopped-at
                  [inst? {:description "When the session was stopped"}])

(schema/register! ::db-name
                  [:string {:description "XTDB database name"}])

(schema/register! ::resume?
                  [:boolean {:description "Whether to resume previous ctx state"}])

(schema/register! ::error
                  [:string {:description "Error message if session failed"}])

(schema/register! ::nrepl-session-id
                  [:string {:description "Persistent nREPL session ID for *1/*2/*3 and interrupt support"}])

;;; Observability schemas (Phase 4c)

(schema/register! ::last-activity-at
                  [inst? {:description "When the last eval completed"}])

(schema/register! ::eval-count
                  [:int {:min 0 :description "Total evals in this session"}])

(schema/register! ::current-eval
                  [:maybe [:map
                           [::code :string]
                           [::started-at inst?]]])

;;; Request/Response Schemas

(schema/register! ::datalevin-manager
                  [:any {:description "Datalevin connection manager (optional)"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate connection manager"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::start-agent-session-request
                  [:map
                   [::node ::node]
                   [::namespace ::namespace]
                   [::resume? {:optional true} ::resume?]
                   [::datalevin-manager {:optional true} ::datalevin-manager]])

(schema/register! ::start-agent-session-response
                  [:map
                   [::id ::id]
                   [::namespace ::namespace]
                   [::status ::status]
                   [::nrepl-port {:optional true} ::nrepl-port]
                   [::started-at {:optional true} ::started-at]
                   [::db-name {:optional true} ::db-name]
                   [::error {:optional true} ::error]])

(schema/register! ::stop-agent-session-request
                  [:map
                   [::node ::node]
                   [::id ::id]])

(schema/register! ::stop-agent-session-response
                  [:map
                   [::id ::id]
                   [::status ::status]
                   [::stopped-at {:optional true} ::stopped-at]
                   [::error {:optional true} ::error]])

(schema/register! ::get-agent-session-request
                  [:map
                   [::node ::node]
                   [::id ::id]])

(schema/register! ::get-agent-session-response
                  [:map
                   [::id {:optional true} ::id]
                   [::namespace {:optional true} ::namespace]
                   [::status {:optional true} ::status]
                   [::nrepl-port {:optional true} ::nrepl-port]
                   [::started-at {:optional true} ::started-at]
                   [::db-name {:optional true} ::db-name]
                   ;; Observability fields (Phase 4c)
                   [::last-activity-at {:optional true} ::last-activity-at]
                   [::eval-count {:optional true} ::eval-count]
                   [::current-eval {:optional true} ::current-eval]])

(schema/register! ::list-agent-sessions-request
                  [:map
                   [::node ::node]])

(schema/register! ::list-agent-sessions-response
                  [:vector ::get-agent-session-response])

(schema/register! ::get-session-port-request
                  [:map
                   [::node ::node]
                   [::id ::id]])

(schema/register! ::get-session-port-response
                  [:map
                   [::nrepl-port {:optional true} ::nrepl-port]
                   [::nrepl-session-id {:optional true} ::nrepl-session-id]])

(schema/register! ::recover-sessions-request
                  [:map
                   [::node ::node]])

(schema/register! ::recover-sessions-response
                  [:map
                   [::recovered-count :int]])

;;; ---------------------------------------------------------------------------
;;; Session ID Generation
;;; ---------------------------------------------------------------------------

(def ^:private secure-random (SecureRandom.))

(defn- generate-session-id
  "Generate a 4-character hex session ID."
  []
  (let [bytes (byte-array 2)]
    (.nextBytes secure-random bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

;;; ---------------------------------------------------------------------------
;;; Session Registry (in-memory for quick lookups)
;;; ---------------------------------------------------------------------------

;; Map of session-id -> session info (includes flush!/close! fns)
(defonce ^:private session-registry (atom {}))

;;; ---------------------------------------------------------------------------
;;; XTDB Session Storage
;;; ---------------------------------------------------------------------------

(defn- store-session!
  "Store session info in XTDB orchestrator database.
   Logs and continues on failure - session still works in-memory."
  [node session-info]
  (try
    (xt/execute-tx node
      [[:sql "INSERT INTO sessions (_id, session$id, session$namespace, session$nrepl_port, session$status, session$started_at, session$db_name) VALUES (?, ?, ?, ?, ?, ?, ?)"
        [(str "session-" (::id session-info))
         (::id session-info)
         (str (::namespace session-info))
         (::nrepl-port session-info)
         (name (::status session-info))
         (::started-at session-info)
         (::db-name session-info)]]])
    (catch Exception e
      (log/warn e "Failed to store session in XTDB, continuing with in-memory only"
                {:session-id (::id session-info)}))))

(defn- update-session-status!
  "Update session status in XTDB orchestrator database."
  [node session-id status stopped-at]
  (let [doc-id (str "session-" session-id)]
    (if stopped-at
      (xt/execute-tx node
        [[:sql "UPDATE sessions SET session$status = ?, session$stopped_at = ? WHERE _id = ?"
          [(name status) stopped-at doc-id]]])
      (xt/execute-tx node
        [[:sql "UPDATE sessions SET session$status = ? WHERE _id = ?"
          [(name status) doc-id]]]))))

(defn- load-session-from-db
  "Load session info from XTDB orchestrator database."
  [node session-id]
  (first (xt/q node
               ["SELECT * FROM sessions WHERE _id = ?" (str "session-" session-id)]
               {:key-fn :kebab-case-keyword})))

(defn- load-active-sessions-from-db
  "Load all active sessions from XTDB orchestrator database."
  [node]
  (xt/q node
        ["SELECT * FROM sessions WHERE session$status = 'running'"]
        {:key-fn :kebab-case-keyword}))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn start-agent-session!
  "Start a new agent session with isolated database, persisted ctx, and nREPL.

   Request keys:
     ::node      - Required. XTDB orchestrator node
     ::namespace - Required. The Clojure namespace symbol for the agent
     ::resume?   - Optional. If true, load previous ctx state (default: true)

   Response keys:
     ::id          - 8-char hex session ID
     ::namespace   - The namespace symbol
     ::status      - :running, :stopped, or :error
     ::nrepl-port  - Port for nREPL connection
     ::started-at  - When session was started
     ::db-name     - XTDB database name
     ::error       - Error message if failed

   Example:
     (start-agent-session! {::node xtdb-node ::namespace 'seon.trading})
     ;; From outside namespace:
     (session/start-agent-session! {::session/node xtdb-node
                                    ::session/namespace 'seon.trading})"
  {:malli/schema [:=> [:cat ::start-agent-session-request] ::start-agent-session-response]}
  [{::keys [node namespace resume? datalevin-manager]}]
  (let [resume? (if (nil? resume?) true resume?)
        session-id (generate-session-id)
        db-name (multi/namespace->db-name namespace)
        started-at (java.util.Date.)]

    (try
      ;; 1. Ensure XTDB database exists for this namespace
      (log/debug "Ensuring namespace database" {:namespace namespace :db-name db-name})
      (multi/ensure-namespace-db! node namespace)

      ;; 2. Create connection to namespace database
      (let [ns-conn (multi/create-namespace-connection node namespace)

            ;; 2b. Get Datalevin namespace connection if manager available
            dl-conn (when datalevin-manager
                      (try
                        (require 'seon.db.datalevin.conn)
                        ((resolve 'seon.db.datalevin.conn/get-namespace-conn!)
                         {:seon.db.datalevin.conn/manager datalevin-manager
                          :seon.db.datalevin.conn/namespace namespace})
                        (catch Exception e
                          (log/warn "Failed to get Datalevin namespace conn"
                                    {:namespace namespace :error (.getMessage e)})
                          nil)))]

        ;; 3. Create persisted ctx with the namespace connection
        ;; If resume? is true, make-persisted-ctx automatically loads latest state
        (log/debug "Creating persisted ctx" {:namespace namespace :resume? resume?
                                             :datalevin? (some? dl-conn)})
        (let [{::ctx/keys [atom flush! close!]}
              (ctx/make-persisted-ctx
               (cond-> {::ctx/db ns-conn
                        ::ctx/namespace namespace}
                 ;; Inject Datalevin connection as reserved key
                 dl-conn (assoc ::ctx/extra-reserved
                                {:seon.ns/conn dl-conn
                                 :seon.ns/session-id session-id
                                 :seon.ns/namespace (str namespace)})))

              ;; 4. Start namespace nREPL with the persisted ctx atom
              _ (log/debug "Starting namespace nREPL" {:session-id session-id :namespace namespace})
              nrepl-result (nrepl-multi/start-namespace-nrepl!
                            {:session-id session-id
                             :namespace namespace
                             :db ns-conn
                             :ctx-atom atom})

              _ (when-not (= :running (:status nrepl-result))
                  (throw (ex-info "Failed to start nREPL"
                                  {:result nrepl-result})))

              session-info {::id session-id
                            ::namespace namespace
                            ::status :running
                            ::nrepl-port (:port nrepl-result)
                            ::started-at started-at
                            ::db-name db-name
                            ;; Observability (Phase 4c)
                            ::last-activity-at started-at
                            ::eval-count 0
                            ::current-eval nil
                            ;; Internal - not returned but stored in registry
                            ::flush! flush!
                            ::close! close!
                            ::ns-conn ns-conn
                            ::ctx-atom atom}]

          ;; 5. Store in registry and XTDB
          (swap! session-registry assoc session-id session-info)
          (store-session! node session-info)

          (log/info "Started agent session"
                    {:session-id session-id
                     :namespace namespace
                     :port (:port nrepl-result)
                     :resumed? (and resume? (some? (::ctx/state (ctx/load-latest
                                                                  {::ctx/db ns-conn
                                                                   ::ctx/namespace namespace}))))})

          ;; Return public session info (without internal fns)
          (select-keys session-info [::id ::namespace ::status ::nrepl-port ::started-at ::db-name])))

      (catch Exception e
        (log/error "Failed to start agent session"
                   {:namespace namespace :error (.getMessage e)})
        {::id session-id
         ::namespace namespace
         ::status :error
         ::error (.getMessage e)}))))

(defn stop-agent-session!
  "Stop an agent session, flushing ctx and cleaning up resources.

   Request keys:
     ::node - Required. XTDB orchestrator node
     ::id   - Required. The session ID to stop

   Response keys:
     ::id         - The session ID
     ::status     - :stopped or :error
     ::stopped-at - When session was stopped
     ::error      - Error message if failed

   Example:
     (stop-agent-session! {::node xtdb-node ::id \"acdb234f\"})
     ;; From outside namespace:
     (session/stop-agent-session! {::session/node xtdb-node
                                   ::session/id \"acdb234f\"})"
  {:malli/schema [:=> [:cat ::stop-agent-session-request] ::stop-agent-session-response]}
  [{::keys [node id]}]
  (if-let [session (get @session-registry id)]
    (try
      (log/info "Stopping agent session" {:session-id id :namespace (::namespace session)})

      ;; 1. Flush pending ctx state
      (when-let [flush! (::flush! session)]
        (log/debug "Flushing ctx" {:session-id id})
        (flush!))

      ;; 2. Stop namespace nREPL (keyed by session-id, not namespace)
      (log/debug "Stopping nREPL" {:session-id id :namespace (::namespace session)})
      (nrepl-multi/stop-namespace-nrepl! id)

      ;; 3. Close persisted ctx (cleanup resources)
      (when-let [close! (::close! session)]
        (log/debug "Closing ctx" {:session-id id})
        (close!))

      ;; 4. Close namespace connection
      (when-let [conn (::ns-conn session)]
        (log/debug "Closing namespace connection" {:session-id id})
        (.close conn))

      ;; 5. Update registry and XTDB
      (swap! session-registry dissoc id)
      (let [stopped-at (java.util.Date.)]
        (update-session-status! node id :stopped stopped-at)

        (log/info "Stopped agent session" {:session-id id})

        {::id id
         ::status :stopped
         ::stopped-at stopped-at})

      (catch Exception e
        (log/error "Error stopping session" {:session-id id :error (.getMessage e)})
        {::id id
         ::status :error
         ::error (.getMessage e)}))

    ;; Session not found in registry
    (do
      (log/warn "Session not found" {:session-id id})
      {::id id
       ::status :error
       ::error "Session not found"})))

(def ^:private public-session-keys
  "Keys to include in public session info responses."
  [::id ::namespace ::status ::nrepl-port ::started-at ::db-name
   ;; Persistent nREPL session (for *1/*2/*3 and interrupt)
   ::nrepl-session-id
   ;; Observability (Phase 4c)
   ::last-activity-at ::eval-count ::current-eval])

(defn get-agent-session
  "Get information about a specific agent session.

   Request keys:
     ::node - Required. XTDB orchestrator node
     ::id   - Required. The session ID to look up

   Response keys:
     ::id               - Session ID (present if found)
     ::namespace        - Agent namespace
     ::status           - :running, :stopped, or :error
     ::nrepl-port       - nREPL port
     ::started-at       - When started
     ::db-name          - XTDB database name
     ::last-activity-at - When last eval completed (Phase 4c)
     ::eval-count       - Total evals in session (Phase 4c)
     ::current-eval     - Currently running eval info (Phase 4c)

   Returns an empty map if session not found.

   Example:
     (get-agent-session {::node xtdb-node ::id \"acdb234f\"})
     ;; From outside namespace:
     (session/get-agent-session {::session/node xtdb-node
                                 ::session/id \"acdb234f\"})"
  {:malli/schema [:=> [:cat ::get-agent-session-request] ::get-agent-session-response]}
  [{::keys [node id]}]
  ;; First check in-memory registry
  (if-let [session (get @session-registry id)]
    (select-keys session public-session-keys)
    ;; Fall back to XTDB (may be stopped session)
    (if-let [db-session (load-session-from-db node id)]
      {::id (:session-id db-session)
       ::namespace (symbol (:session-namespace db-session))
       ::status (keyword (:session-status db-session))
       ::nrepl-port (:session-nrepl-port db-session)
       ::started-at (:session-started-at db-session)
       ::db-name (:session-db-name db-session)}
      ;; Not found - return empty map
      {})))

(defn list-agent-sessions
  "List all active agent sessions.

   Request keys:
     ::node - Required. XTDB orchestrator node (unused but required for consistency)

   Response keys:
     Vector of session info maps, each containing:
       ::id, ::namespace, ::status, ::nrepl-port, ::started-at, ::db-name,
       ::last-activity-at, ::eval-count, ::current-eval

   Example:
     (list-agent-sessions {::node xtdb-node})
     ;; => [{::id \"acdb234f\" ::namespace 'seon.trading ...}]
     ;; From outside namespace:
     (session/list-agent-sessions {::session/node xtdb-node})"
  {:malli/schema [:=> [:cat ::list-agent-sessions-request] ::list-agent-sessions-response]}
  [{::keys [node]}]
  ;; Return from in-memory registry (only active sessions)
  ;; node is unused but required for API consistency
  (let [_ node]
    (vec (for [[_ session] @session-registry]
           (select-keys session public-session-keys)))))

(defn get-session-port
  "Get the nREPL port and session ID for a session. Used by bin/mcp-server.

   Request keys:
     ::node - Required. XTDB orchestrator node
     ::id   - Required. The 4-char session ID

   Response keys:
     ::nrepl-port       - Port number (nil if session not found/not running)
     ::nrepl-session-id - Persistent nREPL session ID (nil if not set)

   Example:
     (get-session-port {::node xtdb-node ::id \"a1b2\"})
     ;; From outside namespace:
     (session/get-session-port {::session/node xtdb-node
                                ::session/id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::get-session-port-request] ::get-session-port-response]}
  [{::keys [node id]}]
  (let [session (get-agent-session {::node node ::id id})]
    {::nrepl-port (::nrepl-port session)
     ::nrepl-session-id (::nrepl-session-id session)}))

;;; ---------------------------------------------------------------------------
;;; nREPL Session ID Management
;;; ---------------------------------------------------------------------------

(schema/register! ::set-nrepl-session-id-request
                  [:map
                   [::id ::id]
                   [::nrepl-session-id ::nrepl-session-id]])

(schema/register! ::set-nrepl-session-id-response
                  [:map
                   [::set :boolean]])

(defn set-nrepl-session-id!
  "Set the persistent nREPL session ID for a session.
   Called by MCP server after cloning the nREPL session.

   Request keys:
     ::id              - Required. The 4-char session ID
     ::nrepl-session-id - Required. The nREPL session ID from clone op

   Response keys:
     ::set - Whether the session ID was set (false if session not found)

   Example:
     (set-nrepl-session-id! {::id \"a1b2\" ::nrepl-session-id \"abc-123-def\"})"
  {:malli/schema [:=> [:cat ::set-nrepl-session-id-request] ::set-nrepl-session-id-response]}
  [{::keys [id nrepl-session-id]}]
  (if (contains? @session-registry id)
    (do
      (swap! session-registry assoc-in [id ::nrepl-session-id] nrepl-session-id)
      {::set true})
    {::set false}))

;;; ---------------------------------------------------------------------------
;;; Activity Tracking (Phase 4c)
;;; ---------------------------------------------------------------------------

(defn- record-eval-start* [session-id code]
  (swap! session-registry update session-id
         assoc ::current-eval {::code code
                               ::started-at (java.util.Date.)}))

(defn- record-eval-complete* [session-id]
  (swap! session-registry update session-id
         (fn [s]
           (-> s
               (assoc ::current-eval nil
                      ::last-activity-at (java.util.Date.))
               (update ::eval-count (fnil inc 0))))))

;;; Request/Response schemas for activity tracking

(schema/register! ::record-eval-start-request
                  [:map
                   [::id ::id]
                   [::code :string]])

(schema/register! ::record-eval-start-response
                  [:map
                   [::recorded :boolean]])

(schema/register! ::record-eval-complete-request
                  [:map
                   [::id ::id]])

(schema/register! ::record-eval-complete-response
                  [:map
                   [::recorded :boolean]])

(defn record-eval-start!
  "Record that an eval has started in a session.

   Request keys:
     ::id   - Required. The session ID
     ::code - Required. The code being evaluated

   Response keys:
     ::recorded - Whether the activity was recorded

   Example:
     (record-eval-start! {::id \"a1b2\" ::code \"(+ 1 2)\"})"
  {:malli/schema [:=> [:cat ::record-eval-start-request] ::record-eval-start-response]}
  [{::keys [id code]}]
  (if (contains? @session-registry id)
    (do
      (record-eval-start* id code)
      {::recorded true})
    {::recorded false}))

(defn record-eval-complete!
  "Record that an eval has completed in a session.

   Request keys:
     ::id - Required. The session ID

   Response keys:
     ::recorded - Whether the activity was recorded

   Example:
     (record-eval-complete! {::id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::record-eval-complete-request] ::record-eval-complete-response]}
  [{::keys [id]}]
  (if (contains? @session-registry id)
    (do
      (record-eval-complete* id)
      {::recorded true})
    {::recorded false}))

;;; ---------------------------------------------------------------------------
;;; Session Recovery (on system restart)
;;; ---------------------------------------------------------------------------

(defn recover-sessions!
  "Recover sessions from XTDB on system restart.

   This function:
   1. Loads sessions marked as 'running' from XTDB
   2. Marks them as 'stopped' (since nREPL servers are gone)

   Sessions can be resumed via start-agent-session! with ::resume? true.

   Called during system startup.

   Request keys:
     ::node - Required. XTDB orchestrator node

   Response keys:
     ::recovered-count - Number of sessions marked as stopped

   Example:
     (recover-sessions! {::node xtdb-node})
     ;; From outside namespace:
     (session/recover-sessions! {::session/node xtdb-node})"
  {:malli/schema [:=> [:cat ::recover-sessions-request] ::recover-sessions-response]}
  [{::keys [node]}]
  (log/info "Recovering sessions from previous run")
  (let [active-sessions (load-active-sessions-from-db node)
        now (java.util.Date.)]
    (doseq [session active-sessions]
      (log/info "Marking orphaned session as stopped"
                {:session-id (:session-id session)
                 :namespace (:session-namespace session)})
      (update-session-status! node (:session-id session) :stopped now))
    {::recovered-count (count active-sessions)}))

(comment
  ;; REPL exploration

  ;; First get the XTDB node
  (require '[user :refer [xtdb-node]])

  ;; Start a session
  (def s (start-agent-session! {::node (xtdb-node) ::namespace 'test.agent}))

  ;; Check session info
  (get-agent-session {::node (xtdb-node) ::id (::id s)})

  ;; List all sessions
  (list-agent-sessions {::node (xtdb-node)})

  ;; Get port for agent-eval
  (get-session-port {::node (xtdb-node) ::id (::id s)})

  ;; Stop the session
  (stop-agent-session! {::node (xtdb-node) ::id (::id s)})

  ;; Resume a session (loads previous ctx state)
  (start-agent-session! {::node (xtdb-node) ::namespace 'test.agent ::resume? true})

  nil)
