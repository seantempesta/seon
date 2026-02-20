(ns seon.orchestrator.session
  "Agent session management - the high-level API for agent lifecycle.

  Provides a simple, opaque session abstraction so agents don't need to know
  about Datalevin, nREPL ports, or persistence mechanics.

  ## Overview

  Sessions tie together:
  - Datalevin persistence for session metadata
  - Persisted ctx atom (via agent.ctx)
  - Pool JVM with nREPL (via flow.pool)

  Agents just receive a session ID and use it for all evals.

  ## Usage

  ```clojure
  ;; Orchestrator starts a session
  (def s (start-agent-session! {::namespace 'seon.trading}))
  ;; => {::id \"acdb\" ::namespace 'seon.trading ::status :running ...}

  ;; Agent uses session ID for evals (via bin/agent-eval)
  ;; agent-eval acdb '(swap! *ctx* assoc :seon.trading/signals [...])'

  ;; Resume existing session (loads previous ctx state)
  (start-agent-session! {::namespace 'seon.trading ::resume? true})

  ;; Stop session (flushes ctx, releases pool JVM)
  (stop-agent-session! {::id \"acdb\"})

  ;; List active sessions
  (list-agent-sessions {})
  ```"
  (:require [datalevin.core :as d]
            [seon.agent.ctx :as ctx]
            [seon.db.datalevin.conn :as conn]
            [seon.flow.pool :as pool]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.security SecureRandom]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

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
                  [:string {:description "Database name for the namespace"}])

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

(schema/register! ::pool
                  [:any {:description "Agent JVM pool (optional)"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate pool"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::start-agent-session-request
                  [:map
                   [::namespace ::namespace]
                   [::resume? {:optional true} ::resume?]
                   [::datalevin-manager {:optional true} ::datalevin-manager]
                   [::pool {:optional true} ::pool]])

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
                   [::id ::id]])

(schema/register! ::stop-agent-session-response
                  [:map
                   [::id ::id]
                   [::status ::status]
                   [::stopped-at {:optional true} ::stopped-at]
                   [::error {:optional true} ::error]])

(schema/register! ::get-agent-session-request
                  [:map
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
                  [:map])

(schema/register! ::list-agent-sessions-response
                  [:vector ::get-agent-session-response])

(schema/register! ::get-session-port-request
                  [:map
                   [::id ::id]])

(schema/register! ::get-session-port-response
                  [:map
                   [::nrepl-port {:optional true} ::nrepl-port]
                   [::nrepl-session-id {:optional true} ::nrepl-session-id]])

(schema/register! ::recover-sessions-request
                  [:map])

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
;;; Pool reference (set via init! from Integrant)
;;; ---------------------------------------------------------------------------

(defonce ^:private agent-pool (atom nil))

;;; ---------------------------------------------------------------------------
;;; Datalevin Session Storage
;;; ---------------------------------------------------------------------------

(def ^:private dl-schema
  "Datalevin schema for orchestrator sessions."
  {:orch.session/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :orch.session/namespace  {:db/valueType :db.type/string}
   :orch.session/nrepl-port {:db/valueType :db.type/long}
   :orch.session/status     {:db/valueType :db.type/string}
   :orch.session/started-at {:db/valueType :db.type/instant}
   :orch.session/stopped-at {:db/valueType :db.type/instant}
   :orch.session/db-name    {:db/valueType :db.type/string}})

;; Connection manager (set via init! from Integrant)
(defonce ^:private dl-mgr (atom nil))

(defn init!
  "Initialize orchestrator sessions with a Datalevin connection manager
   and optional agent pool.
   Called by Integrant during system startup."
  [mgr & {:keys [pool]}]
  (reset! dl-mgr mgr)
  (when pool
    (reset! agent-pool pool))
  (log/info "Orchestrator sessions initialized"
            {:connection-manager (some? mgr) :pool (some? pool)}))

(defn- get-dl-conn
  "Get Datalevin connection for orchestrator sessions.
   Returns nil if connection manager not initialized."
  []
  (when-let [mgr @dl-mgr]
    (try
      (conn/get-namespace-conn! {::conn/manager mgr
                                 ::conn/namespace 'seon.orchestrator
                                 ::conn/schema dl-schema})
      (catch Exception e
        (log/debug "Datalevin conn unavailable for orchestrator sessions" {:error (.getMessage e)})
        nil))))

(defn- store-session!
  "Store session info in Datalevin orchestrator database.
   Logs and continues on failure - session still works in-memory."
  [_node session-info]
  (try
    (when-let [conn (get-dl-conn)]
      (d/transact! conn [{:orch.session/id         (::id session-info)
                           :orch.session/namespace  (str (::namespace session-info))
                           :orch.session/nrepl-port (long (::nrepl-port session-info))
                           :orch.session/status     (name (::status session-info))
                           :orch.session/started-at (::started-at session-info)
                           :orch.session/db-name    (::db-name session-info)}]))
    (catch Exception e
      (log/warn e "Failed to store session in Datalevin, continuing with in-memory only"
                {:session-id (::id session-info)}))))

(defn- update-session-status!
  "Update session status in Datalevin orchestrator database."
  [_node session-id status stopped-at]
  (try
    (when-let [conn (get-dl-conn)]
      (let [entity (cond-> {:orch.session/id session-id
                             :orch.session/status (name status)}
                     stopped-at (assoc :orch.session/stopped-at stopped-at))]
        (d/transact! conn [entity])))
    (catch Exception e
      (log/warn "Failed to update session status in Datalevin" {:error (.getMessage e)}))))

(defn- load-session-from-db
  "Load session info from Datalevin orchestrator database."
  [_node session-id]
  (try
    (when-let [conn (get-dl-conn)]
      (let [results (d/q '[:find (pull ?e [*])
                            :in $ ?sid
                            :where [?e :orch.session/id ?sid]]
                          @conn session-id)]
        (when-let [entity (ffirst results)]
          {:session-id         (:orch.session/id entity)
           :session-namespace  (:orch.session/namespace entity)
           :session-nrepl-port (:orch.session/nrepl-port entity)
           :session-status     (:orch.session/status entity)
           :session-started-at (:orch.session/started-at entity)
           :session-stopped-at (:orch.session/stopped-at entity)
           :session-db-name    (:orch.session/db-name entity)})))
    (catch Exception e
      (log/warn "Failed to load session from Datalevin" {:error (.getMessage e)})
      nil)))

(defn- load-active-sessions-from-db
  "Load all active sessions from Datalevin orchestrator database."
  [_node]
  (try
    (when-let [conn (get-dl-conn)]
      (let [results (d/q '[:find (pull ?e [*])
                            :where
                            [?e :orch.session/id _]
                            [?e :orch.session/status "running"]]
                          @conn)]
        (mapv (fn [[entity]]
                {:session-id         (:orch.session/id entity)
                 :session-namespace  (:orch.session/namespace entity)
                 :session-status     (:orch.session/status entity)
                 :session-started-at (:orch.session/started-at entity)})
              results)))
    (catch Exception e
      (log/warn "Failed to load active sessions from Datalevin" {:error (.getMessage e)})
      [])))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn start-agent-session!
  "Start a new agent session with isolated database, persisted ctx, and pool JVM.

   Request keys:
     ::namespace - Required. The Clojure namespace symbol for the agent
     ::resume?   - Optional. If true, load previous ctx state (default: true)
     ::pool      - Optional. Agent pool (falls back to init!-provided pool)

   Response keys:
     ::id          - 4-char hex session ID
     ::namespace   - The namespace symbol
     ::status      - :running, :stopped, or :error
     ::nrepl-port  - Port for nREPL connection
     ::started-at  - When session was started
     ::db-name     - Database name
     ::error       - Error message if failed

   Example:
     (start-agent-session! {::namespace 'seon.trading})
     ;; From outside namespace:
     (session/start-agent-session! {::session/namespace 'seon.trading})"
  [{::keys [namespace resume? datalevin-manager pool]}]
  (let [resume? (if (nil? resume?) true resume?)
        pool (or pool @agent-pool)
        session-id (generate-session-id)
        db-name (str namespace)
        started-at (java.util.Date.)]

    (try
      ;; 1. Get Datalevin namespace connection if manager available
      (let [ns-conn nil  ;; No XTDB namespace connection needed
            dl-conn (when datalevin-manager
                      (try
                        (conn/get-namespace-conn!
                         {::conn/manager datalevin-manager
                          ::conn/namespace namespace})
                        (catch Exception e
                          (log/warn "Failed to get Datalevin namespace conn"
                                    {:namespace namespace :error (.getMessage e)})
                          nil)))]

        ;; 3. Create persisted ctx with the namespace connection
        (log/debug "Creating persisted ctx" {:namespace namespace :resume? resume?
                                             :datalevin? (some? dl-conn)})
        (let [{::ctx/keys [atom flush! close!]}
              (ctx/make-persisted-ctx
               (cond-> {::ctx/db ns-conn
                        ::ctx/namespace namespace}
                 dl-conn (assoc ::ctx/extra-reserved
                                {:seon.ns/conn dl-conn
                                 :seon.ns/session-id session-id
                                 :seon.ns/namespace (str namespace)})))

              ;; 4. Claim a pool JVM and inject *ctx*
              _ (log/debug "Claiming pool JVM" {:session-id session-id :namespace namespace})
              ctx-value @atom
              jvm-handle (when pool
                           (pool/claim! pool
                                        {::pool/session-id session-id
                                         ::pool/namespace namespace
                                         ::pool/ctx-value ctx-value}))

              ;; If no pool available, fail
              _ (when (and pool (nil? jvm-handle))
                  (throw (ex-info "No pool JVM available"
                                  {:session-id session-id :namespace namespace})))

              nrepl-port (when jvm-handle (::pool/port jvm-handle))

              session-info {::id session-id
                            ::namespace namespace
                            ::status :running
                            ::nrepl-port nrepl-port
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
                            ::ctx-atom atom
                            ::pool pool}]

          ;; 5. Store in registry and Datalevin
          (swap! session-registry assoc session-id session-info)
          (store-session! nil session-info)

          (log/info "Started agent session"
                    {:session-id session-id
                     :namespace namespace
                     :port nrepl-port
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
  "Stop an agent session, flushing ctx and releasing the pool JVM.

   Request keys:
     ::id   - Required. The session ID to stop

   Response keys:
     ::id         - The session ID
     ::status     - :stopped or :error
     ::stopped-at - When session was stopped
     ::error      - Error message if failed

   Example:
     (stop-agent-session! {::id \"acdb\"})
     ;; From outside namespace:
     (session/stop-agent-session! {::session/id \"acdb\"})"
  [{::keys [id]}]
  (if-let [session (get @session-registry id)]
    (try
      (log/info "Stopping agent session" {:session-id id :namespace (::namespace session)})

      ;; 1. Flush pending ctx state
      (when-let [flush! (::flush! session)]
        (log/debug "Flushing ctx" {:session-id id})
        (flush!))

      ;; 2. Release pool JVM back to pool
      (when-let [pool (::pool session)]
        (log/debug "Releasing pool JVM" {:session-id id})
        (pool/release-session! pool id))

      ;; 3. Close persisted ctx (cleanup resources)
      (when-let [close! (::close! session)]
        (log/debug "Closing ctx" {:session-id id})
        (close!))

      ;; 4. Update registry and Datalevin
      (swap! session-registry dissoc id)
      (let [stopped-at (java.util.Date.)]
        (update-session-status! nil id :stopped stopped-at)

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
     ::id   - Required. The session ID to look up

   Response keys:
     ::id               - Session ID (present if found)
     ::namespace        - Agent namespace
     ::status           - :running, :stopped, or :error
     ::nrepl-port       - nREPL port
     ::started-at       - When started
     ::db-name          - Database name
     ::last-activity-at - When last eval completed (Phase 4c)
     ::eval-count       - Total evals in session (Phase 4c)
     ::current-eval     - Currently running eval info (Phase 4c)

   Returns an empty map if session not found.

   Example:
     (get-agent-session {::id \"acdb\"})
     ;; From outside namespace:
     (session/get-agent-session {::session/id \"acdb\"})"
  {:malli/schema [:=> [:cat ::get-agent-session-request] ::get-agent-session-response]}
  [{::keys [id]}]
  ;; First check in-memory registry
  (if-let [session (get @session-registry id)]
    (select-keys session public-session-keys)
    ;; Fall back to Datalevin (may be stopped session)
    (if-let [db-session (load-session-from-db nil id)]
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
     (none - empty map for consistency)

   Response keys:
     Vector of session info maps, each containing:
       ::id, ::namespace, ::status, ::nrepl-port, ::started-at, ::db-name,
       ::last-activity-at, ::eval-count, ::current-eval

   Example:
     (list-agent-sessions {})
     ;; => [{::id \"acdb\" ::namespace 'seon.trading ...}]"
  {:malli/schema [:=> [:cat ::list-agent-sessions-request] ::list-agent-sessions-response]}
  [_request]
  ;; Return from in-memory registry (only active sessions)
  (vec (for [[_ session] @session-registry]
         (select-keys session public-session-keys))))

(defn get-session-port
  "Get the nREPL port and session ID for a session. Used by bin/mcp-server.

   Request keys:
     ::id   - Required. The 4-char session ID

   Response keys:
     ::nrepl-port       - Port number (nil if session not found/not running)
     ::nrepl-session-id - Persistent nREPL session ID (nil if not set)

   Example:
     (get-session-port {::id \"a1b2\"})
     ;; From outside namespace:
     (session/get-session-port {::session/id \"a1b2\"})"
  {:malli/schema [:=> [:cat ::get-session-port-request] ::get-session-port-response]}
  [{::keys [id]}]
  (let [session (get-agent-session {::id id})]
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
  "Recover sessions from Datalevin on system restart.

   This function:
   1. Loads sessions marked as 'running' from Datalevin
   2. Marks them as 'stopped' (since pool JVMs are gone)

   Sessions can be resumed via start-agent-session! with ::resume? true.

   Called during system startup.

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::recovered-count - Number of sessions marked as stopped

   Example:
     (recover-sessions! {})"
  {:malli/schema [:=> [:cat ::recover-sessions-request] ::recover-sessions-response]}
  [_request]
  (log/info "Recovering sessions from previous run")
  (let [active-sessions (load-active-sessions-from-db nil)
        now (java.util.Date.)]
    (doseq [session active-sessions]
      (log/info "Marking orphaned session as stopped"
                {:session-id (:session-id session)
                 :namespace (:session-namespace session)})
      (update-session-status! nil (:session-id session) :stopped now))
    {::recovered-count (count active-sessions)}))

(comment
  ;; REPL exploration

  ;; Start a session
  (def s (start-agent-session! {::namespace 'test.agent}))

  ;; Check session info
  (get-agent-session {::id (::id s)})

  ;; List all sessions
  (list-agent-sessions {})

  ;; Get port for agent-eval
  (get-session-port {::id (::id s)})

  ;; Stop the session
  (stop-agent-session! {::id (::id s)})

  ;; Resume a session (loads previous ctx state)
  (start-agent-session! {::namespace 'test.agent ::resume? true})

  nil)
