(ns seon.session
  "Agent session ENTITY layer — the canonical session-as-datom record.

  Owns the `:seon.session/*` entity schema + the `start-agent-session!` /
  `stop-agent-session!` / `list-agent-sessions` lifecycle. Persists to the
  `:seon.orchestrator` datahike DB via `seon.db/transact!` + `query` (Path
  A — goes through `:seon.db/flow`). Live JVM-only handles (atoms, pool
  refs) live in a small in-process map keyed by session-id; these are
  resource handles the DB can't store.

  **Renamed 2026-05-27** from `seon.orchestrator.session` (Wave 3a). The
  richer 609-LOC orchestrator version replaced the older 472-LOC
  `seon.session` from before the migration.

  ## Sibling NS: `seon.server.session`

  `seon.server.session` (Wave 2, Path B) is a SEPARATE namespace and
  separate concern. It owns the in-process atom REGISTRY of
  `{db-name -> {::conn ::backend ::path ::pub-chan}}` that the wire-
  server uses to route wasm-guest requests to the right datahike conn.
  No flow, no persistent entity — it's the live runtime mapping.

  - **`seon.session`** (this ns)        → entity (datoms in `:seon.orchestrator`)
  - **`seon.server.session`** (Wave 2)  → runtime (atom of live conns)

  Don't confuse them. Both can exist for the same logical session: the
  ENTITY records its identity + lifecycle metadata; the REGISTRY holds
  the live conn for wire-server routing.

  ## Usage

  ```clojure
  (def s (start-agent-session! {::namespace 'seon.trading}))
  ;; => {::id \"acdb\" ::namespace 'seon.trading ::status :running ...}

  (start-agent-session! {::namespace 'seon.trading ::resume? true})
  (stop-agent-session! {::id \"acdb\"})
  (list-agent-sessions {})
  ```"
  (:require [seon.ctx :as ctx]
            [seon.db :as db]
            [seon.db.schema :as db-schema]
            [seon.flow.pool :as pool]
            [seon.runtime :as runtime]
            [seon.schema :as schema]
            [seon.server.session :as server.session]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Per-agent dynamic bindings (V2 — MCP eval routing by `:seon.agent/<id>`)
;;; ---------------------------------------------------------------------------
;;;
;;; `seon.session/with-agent` binds these for the duration of the body so a
;;; bare eval like `(d/transact @*conn* ...)` routes to the right session's
;;; datahike conn without the caller knowing the db-name. Used by
;;; `bin/mcp-server`'s `:seon.agent/<id>` dispatch branch.

(def ^:dynamic *conn*
  "Datahike conn for the agent's session, bound by `with-agent`. Nil at
   the top level of the master REPL — only bound inside `with-agent`."
  nil)

(def ^:dynamic *current-agent-id*
  "Opaque agent-id (e.g. the `<id>` part of `:seon.agent/<id>`), bound
   by `with-agent`. Nil at the top level."
  nil)

(schema/register! ::resolve-agent-conn-request
                  [:map [::agent-id :seon.server.session/agent-id]])

(schema/register! ::resolve-agent-conn-response
                  [:map [::conn :seon.server.session/conn]])

(defn resolve-agent-conn
  "Look up the datahike conn for `::agent-id` via
   `seon.server.session/resolve-agent`. Throws clearly if the agent is
   unknown or its session has been removed. Used by `with-agent`; broken
   out so the error path is easy to test."
  {:malli/schema [:=> [:cat ::resolve-agent-conn-request]
                  ::resolve-agent-conn-response]}
  [{::keys [agent-id]}]
  (let [{db-name :seon.server.session/db-name
         conn    :seon.server.session/conn}
        (server.session/resolve-agent
         {:seon.server.session/agent-id agent-id})]
    (cond
      (nil? db-name)
      (throw (ex-info (str "Unknown agent-id: " (pr-str agent-id)
                           ". Register via seon.server.session/register-agent! first.")
                      {:agent-id agent-id
                       :known-agents (mapv :seon.server.session/agent-id
                                           (:seon.server.session/agents
                                            (server.session/list-agents {})))}))
      (nil? conn)
      (throw (ex-info (str "Agent " (pr-str agent-id) " points at db-name "
                           (pr-str db-name) " but no conn is registered.")
                      {:agent-id agent-id :db-name db-name}))
      :else {::conn conn})))

(defmacro with-agent
  "Bind `*conn*` and `*current-agent-id*` to the agent's session for the
   duration of `body`. Resolves `agent-id` → db-name → conn via the
   `seon.server.session` registry. Throws clearly if the agent-id is not
   registered.

   Used by `bin/mcp-server` to scope `:seon.agent/<id>` evals; safe to
   call directly from any REPL.

   ```clojure
   (with-agent \"a1b2c3d4e5f6a7\"
     (d/transact *conn* [{:db/ident :hello/world}]))
   ```"
  [agent-id & body]
  `(let [agent-id# ~agent-id
         conn#     (::conn (resolve-agent-conn {::agent-id agent-id#}))]
     (binding [*conn*             conn#
               *current-agent-id* agent-id#]
       ~@body)))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::id
                  [:string {:min 4 :max 6
                            :pattern "^[A-Za-z0-9]{4,6}$"
                            :seon.db/identity true
                            :description "Base62 session ID, 4-6 chars"}])

(schema/register! ::namespace
                  [:or {:seon.db/value-type :db.type/string
                        :description "Agent namespace — accepts symbol or string at the API boundary; coerced to string for persistence via `->ns-string`."}
                   [:string {:min 1}]
                   :symbol])

(schema/register! ::status
                  [:enum :running :stopped :error])

(schema/register! ::nrepl-port
                  [:int {:min 7889 :max 7999
                         :description "nREPL port for this session"}])

(schema/register! ::started-at
                  [:inst {:description "When the session was started"}])

(schema/register! ::stopped-at
                  [:inst {:description "When the session was stopped"}])

(schema/register! ::db-name
                  [:string {:description "Database name for the namespace"}])

(schema/register! ::backend
                  [:enum {:description "Datahike store backend for session DB."}
                   :memory :file :sqlite])

(schema/register! ::path
                  [:string {:description "On-disk path for :file/:sqlite backends. Nil for :memory."
                            :min 1}])

(schema/register! ::resume?
                  [:boolean {:description "Whether to resume previous ctx state"}])

(schema/register! ::error
                  [:string {:description "Error message if session failed"}])

(schema/register! ::nrepl-session-id
                  [:string {:description "Persistent nREPL session ID for *1/*2/*3 and interrupt support"}])

;;; Observability schemas (Phase 4c)

(schema/register! ::last-activity-at
                  [:inst {:description "When the last eval completed"}])

(schema/register! ::eval-count
                  [:int {:min 0 :description "Total evals in this session"}])

(schema/register! ::current-eval
                  [:maybe [:map
                           [::code :string]
                           [::started-at inst?]]])

;;; Request/Response Schemas

(schema/register! ::pool
                  [:any {:description "Agent JVM pool (optional)"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate pool"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::start-agent-session-request
                  [:map
                   [::namespace ::namespace]
                   [::resume? {:optional true} ::resume?]
                   [::pool {:optional true} ::pool]])

(schema/register! ::start-agent-session-response
                  [:map
                   [::id ::id]
                   [::namespace ::namespace]
                   [::status ::status]
                   [::nrepl-port {:optional true} [:maybe ::nrepl-port]]
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
                   [::nrepl-port {:optional true} [:maybe ::nrepl-port]]
                   [::started-at {:optional true} ::started-at]
                   [::db-name {:optional true} ::db-name]
                   ;; Persistent nREPL session (for *1/*2/*3 and interrupt)
                   [::nrepl-session-id {:optional true} ::nrepl-session-id]
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
                   [::nrepl-port {:optional true} [:maybe ::nrepl-port]]
                   [::nrepl-session-id {:optional true} [:maybe ::nrepl-session-id]]])

(schema/register! ::recover-sessions-request
                  [:map])

(schema/register! ::recover-sessions-response
                  [:map
                   [::recovered-count :int]])

;;; ---------------------------------------------------------------------------
;;; Persisted Entity Schema (datahike)
;;; ---------------------------------------------------------------------------

(def session-entity-schema
  "Malli :map schema for an orchestrator session row. Installed on the
   `:seon.orchestrator` datahike DB via `:seon.db/flow`'s `:namespace-schemas`
   (mirror of the entry there)."
  [:map
   [::id ::id]
   [::namespace ::namespace]
   [::status ::status]
   [::nrepl-port {:optional true} ::nrepl-port]
   [::started-at {:optional true} ::started-at]
   [::stopped-at {:optional true} ::stopped-at]
   [::db-name {:optional true} ::db-name]
   [::nrepl-session-id {:optional true} ::nrepl-session-id]
   [::last-activity-at {:optional true} ::last-activity-at]
   [::eval-count {:optional true} ::eval-count]])

(db-schema/register-entity-schema! "seon.orchestrator/session" session-entity-schema)

;;; ---------------------------------------------------------------------------
;;; Session ID Generation
;;; ---------------------------------------------------------------------------

(defn- generate-session-id
  "Generate a 6-character hex session ID.

   Delegates to seon.runtime/generate-id for unified ID generation
   with collision checking."
  []
  (::runtime/id (runtime/generate-id {})))

;;; ---------------------------------------------------------------------------
;;; Live state (per-JVM, ephemeral)
;;; ---------------------------------------------------------------------------
;;;
;;; Holds resource handles that don't fit in datahike: `::ctx-atom`, `::pool`,
;;; `::ns-db-name`, and the transient `::current-eval` map. Keyed by session-id.
;;; All persistent fields live in the `:seon.orchestrator` datahike DB.

(defonce ^:private live-state (atom {}))

(defn- live [id] (get @live-state id))

;;; ---------------------------------------------------------------------------
;;; Pool reference (set via init! from Integrant)
;;; ---------------------------------------------------------------------------

(defonce ^:private agent-pool (atom nil))

;;; ---------------------------------------------------------------------------
;;; Datahike helpers
;;; ---------------------------------------------------------------------------

(defn- pull-row
  "Pull the full session row from datahike. Returns nil when not found."
  [id]
  (try
    (let [row (db/pull-by-name :seon.orchestrator '[*] [::id id])]
      (when (and row (::id row)) row))
    (catch Exception _ nil)))

(defn- running-rows
  "Query datahike for all sessions with :running status. Returns a vector of
   row maps (no live `::current-eval` merged in)."
  []
  (try
    (let [eids (->> (db/query :seon.orchestrator
                              '[:find ?e
                                :where [?e :seon.session/status :running]])
                    (map first))]
      (vec (keep #(db/pull-by-name :seon.orchestrator '[*] %) eids)))
    (catch Exception _ [])))

;;; ---------------------------------------------------------------------------
;;; Initialization
;;; ---------------------------------------------------------------------------

(defn init!
  "Initialize orchestrator sessions with optional agent pool.
   Called by Integrant during system startup."
  [_mgr & {:keys [pool]}]
  (when pool
    (reset! agent-pool pool))
  (log/info "Orchestrator sessions initialized" {:pool (some? pool)}))

;;; ---------------------------------------------------------------------------
;;; Lifecycle Hooks
;;; ---------------------------------------------------------------------------

(defn after-ns-reload
  "Called by clj-reload after reloading. Re-wires agent-pool from Integrant system."
  []
  (try
    (require 'integrant.repl.state)
    (when-let [sys @(resolve 'integrant.repl.state/system)]
      (when-let [{:keys [pool]} (:seon.orchestrator/sessions sys)]
        (when pool
          (reset! agent-pool pool))))
    (catch Exception e
      (log/debug "Could not re-wire agent-pool from Integrant" {:error (.getMessage e)}))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn- ->ns-string
  "Coerce a namespace input (symbol or string) to its canonical string form."
  [ns-input]
  (cond
    (string? ns-input) ns-input
    (symbol? ns-input) (str ns-input)
    :else (str ns-input)))

(defn- public-view
  "Strip DB-internal keys and merge the live `::current-eval` (if any) into a
   persisted row, producing a public response map."
  [row]
  (let [id (::id row)
        live-eval (some-> (live id) ::current-eval)]
    (cond-> (dissoc row :db/id)
      live-eval (assoc ::current-eval live-eval))))

(defn start-agent-session!
  "Start a new agent session, persisting the row to `:seon.orchestrator` and
   stashing live JVM-only handles in the in-process live-state map.

   Request keys:
     ::namespace - Required. Clojure namespace (symbol or string).
     ::resume?   - Optional. If true, load previous ctx state (default: true).
     ::pool      - Optional. Agent pool (falls back to init!-provided pool).

   Response keys: ::id ::namespace ::status ::nrepl-port ::started-at ::db-name
   (or ::error on failure)."
  {:malli/schema [:=> [:cat ::start-agent-session-request] ::start-agent-session-response]}
  [{::keys [namespace resume? pool] :as request}]
  (let [resume? (if (nil? resume?) true resume?)
        pool (if (contains? request ::pool) pool @agent-pool)
        ns-string (->ns-string namespace)
        session-id (generate-session-id)
        db-name ns-string
        started-at (java.util.Date.)]
    (try
      (let [ctx-atom (ctx/create!
                      {::ctx/instance-id session-id
                       ::ctx/namespace namespace
                       ::ctx/db-name nil
                       ::ctx/persist? false
                       ::ctx/sse-push? false
                       ::ctx/validate? true
                       ::ctx/debounce-ms 1000
                       ::ctx/reserved-keys {:seon.agent/namespace namespace
                                            :seon.agent/db nil}})
            ctx-value @ctx-atom
            jvm-handle (when pool
                         (pool/claim! pool
                                      {::pool/session-id session-id
                                       ::pool/namespace namespace
                                       ::pool/ctx-value ctx-value}))
            _ (when (and pool (nil? jvm-handle))
                (throw (ex-info "No pool JVM available"
                                {:session-id session-id :namespace ns-string})))
            nrepl-port (when jvm-handle (::pool/port jvm-handle))
            persisted (cond-> {::id session-id
                               ::namespace ns-string
                               ::status :running
                               ::started-at started-at
                               ::db-name db-name
                               ::last-activity-at started-at
                               ::eval-count 0}
                        nrepl-port (assoc ::nrepl-port nrepl-port))]
        (db/transact! :seon.orchestrator [persisted])
        (swap! live-state assoc session-id
               {::ctx-atom ctx-atom
                ::pool pool})
        (runtime/register! (cond-> {::runtime/namespace ns-string
                                    ::runtime/status :running
                                    ::runtime/location :external
                                    ::runtime/session-id session-id
                                    ::runtime/started-at started-at}
                             nrepl-port (assoc ::runtime/nrepl-port nrepl-port)))
        (log/info "Started agent session"
                  {:session-id session-id :namespace ns-string :port nrepl-port})
        {::id session-id
         ::namespace ns-string
         ::status :running
         ::nrepl-port nrepl-port
         ::started-at started-at
         ::db-name db-name})
      (catch Exception e
        (log/error "Failed to start agent session"
                   {:namespace ns-string :error (.getMessage e)})
        {::id session-id
         ::namespace ns-string
         ::status :error
         ::error (.getMessage e)}))))

(defn stop-agent-session!
  "Stop an agent session, flushing ctx, releasing the pool JVM, and marking
   the row :stopped in datahike.

   Request keys: ::id

   Response keys: ::id ::status ::stopped-at (or ::error)."
  {:malli/schema [:=> [:cat ::stop-agent-session-request] ::stop-agent-session-response]}
  [{::keys [id]}]
  (let [row (pull-row id)
        live-entry (live id)]
    (if (or row live-entry)
      (try
        (log/info "Stopping agent session"
                  {:session-id id :namespace (::namespace row)})
        (when-let [p (::pool live-entry)]
          (log/debug "Releasing pool JVM" {:session-id id})
          (pool/release-session! p id))
        (log/debug "Destroying ctx" {:session-id id})
        (ctx/destroy! {::ctx/instance-id id})
        (let [stopped-at (java.util.Date.)]
          (db/transact! :seon.orchestrator
                        [{::id id ::status :stopped ::stopped-at stopped-at}])
          (swap! live-state dissoc id)
          (when-let [ns-string (::namespace row)]
            (runtime/unregister! {::runtime/namespace ns-string}))
          (log/info "Stopped agent session" {:session-id id})
          {::id id ::status :stopped ::stopped-at stopped-at})
        (catch Exception e
          (log/error "Error stopping session" {:session-id id :error (.getMessage e)})
          {::id id ::status :error ::error (.getMessage e)}))
      (do
        (log/warn "Session not found" {:session-id id})
        {::id id ::status :error ::error "Session not found"}))))

(def ^:private public-session-keys
  "Keys to include in public session info responses."
  [::id ::namespace ::status ::nrepl-port ::started-at ::db-name
   ::nrepl-session-id ::last-activity-at ::eval-count ::current-eval])

(defn get-agent-session
  "Get information about a specific running agent session.
   Returns an empty map if not found or stopped."
  {:malli/schema [:=> [:cat ::get-agent-session-request] ::get-agent-session-response]}
  [{::keys [id]}]
  (if-let [row (pull-row id)]
    (if (= :running (::status row))
      (select-keys (public-view row) public-session-keys)
      {})
    {}))

(defn list-agent-sessions
  "List all running agent sessions."
  {:malli/schema [:=> [:cat ::list-agent-sessions-request] ::list-agent-sessions-response]}
  [{}]
  (vec (for [row (running-rows)]
         (select-keys (public-view row) public-session-keys))))

(defn get-session-port
  "Get the nREPL port and persistent nREPL session ID for a session."
  {:malli/schema [:=> [:cat ::get-session-port-request] ::get-session-port-response]}
  [{::keys [id]}]
  (let [session (get-agent-session {::id id})]
    {::nrepl-port (::nrepl-port session)
     ::nrepl-session-id (::nrepl-session-id session)}))

;;; ---------------------------------------------------------------------------
;;; External-session bridge (used by `seon.session/launch!`)
;;; ---------------------------------------------------------------------------

(schema/register! ::register-external-session-request
                  [:map
                   [::id ::id]
                   [::namespace ::namespace]
                   [::nrepl-port {:optional true} ::nrepl-port]
                   [::started-at {:optional true} ::started-at]
                   [::db-name {:optional true} ::db-name]])

(schema/register! ::register-external-session-response
                  [:map [::registered :boolean]])

(schema/register! ::unregister-external-session-request
                  [:map [::id ::id]])

(schema/register! ::unregister-external-session-response
                  [:map [::unregistered :boolean]])

(defn register-external-session!
  "Register a session that was launched outside `start-agent-session!`
   (e.g. by `seon.session/launch!`) so MCP eval routing can find it.
   Persists a `:running` row to `:seon.orchestrator`."
  {:malli/schema [:=> [:cat ::register-external-session-request]
                  ::register-external-session-response]}
  [{::keys [id namespace nrepl-port started-at db-name]}]
  (let [now (java.util.Date.)
        ns-string (->ns-string namespace)
        row (cond-> {::id id
                     ::namespace ns-string
                     ::status :running
                     ::started-at (or started-at now)
                     ::db-name (or db-name ns-string)
                     ::last-activity-at now
                     ::eval-count 0}
              nrepl-port (assoc ::nrepl-port nrepl-port))]
    (db/transact! :seon.orchestrator [row])
    {::registered true}))

(defn unregister-external-session!
  "Mark an externally-launched session as :stopped in `:seon.orchestrator`.
   No-op when the row does not exist."
  {:malli/schema [:=> [:cat ::unregister-external-session-request]
                  ::unregister-external-session-response]}
  [{::keys [id]}]
  (if (pull-row id)
    (do
      (db/transact! :seon.orchestrator
                    [{::id id
                      ::status :stopped
                      ::stopped-at (java.util.Date.)}])
      {::unregistered true})
    {::unregistered false}))

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
  "Set the persistent nREPL session ID for a session in datahike."
  {:malli/schema [:=> [:cat ::set-nrepl-session-id-request] ::set-nrepl-session-id-response]}
  [{::keys [id nrepl-session-id]}]
  (if (pull-row id)
    (do
      (db/transact! :seon.orchestrator
                    [{::id id ::nrepl-session-id nrepl-session-id}])
      {::set true})
    {::set false}))

;;; ---------------------------------------------------------------------------
;;; Activity Tracking (Phase 4c)
;;; ---------------------------------------------------------------------------
;;;
;;; `::current-eval` is transient and stays in live-state. `::eval-count` and
;;; `::last-activity-at` persist to datahike.

(schema/register! ::record-eval-start-request
                  [:map
                   [::id ::id]
                   [::code :string]])

(schema/register! ::record-eval-start-response
                  [:map [::recorded :boolean]])

(schema/register! ::record-eval-complete-request
                  [:map [::id ::id]])

(schema/register! ::record-eval-complete-response
                  [:map [::recorded :boolean]])

(defn record-eval-start!
  "Record that an eval has started. The transient `::current-eval` lives in
   the in-process live-state."
  {:malli/schema [:=> [:cat ::record-eval-start-request] ::record-eval-start-response]}
  [{::keys [id code]}]
  (if (pull-row id)
    (do
      (swap! live-state assoc-in [id ::current-eval]
             {::code code ::started-at (java.util.Date.)})
      {::recorded true})
    {::recorded false}))

(defn record-eval-complete!
  "Record that an eval has completed. Clears `::current-eval` from live-state
   and bumps `::eval-count` + `::last-activity-at` in datahike."
  {:malli/schema [:=> [:cat ::record-eval-complete-request] ::record-eval-complete-response]}
  [{::keys [id]}]
  (if-let [row (pull-row id)]
    (do
      (swap! live-state update id dissoc ::current-eval)
      (db/transact! :seon.orchestrator
                    [{::id id
                      ::eval-count (inc (or (::eval-count row) 0))
                      ::last-activity-at (java.util.Date.)}])
      {::recorded true})
    {::recorded false}))

;;; ---------------------------------------------------------------------------
;;; Session Recovery (on system restart)
;;; ---------------------------------------------------------------------------

(defn recover-sessions!
  "Recover sessions on system restart.

   Queries the runtime registry for external running instances (which are
   orphaned since pool JVMs are gone after restart) and marks them as stopped.

   Sessions can be resumed via start-agent-session! with ::resume? true.

   Called during system startup.

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::recovered-count - Number of sessions marked as stopped

   Example:
     (recover-sessions! {})"
  {:malli/schema [:=> [:cat ::recover-sessions-request] ::recover-sessions-response]}
  [{}]
  (log/info "Recovering sessions from previous run")
  (let [all-instances (runtime/instances {})
        orphaned (->> all-instances
                      (filter #(and (= :external (::runtime/location %))
                                    (= :running (::runtime/status %)))))]
    (doseq [inst orphaned]
      (log/info "Marking orphaned session as stopped"
                {:namespace (::runtime/namespace inst)
                 :session-id (::runtime/session-id inst)})
      (runtime/unregister! {::runtime/namespace (::runtime/namespace inst)}))
    {::recovered-count (count orphaned)}))

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
