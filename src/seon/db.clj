(ns seon.db
  "Seon's database API. All database access goes through here.

   Agents use this instead of d/transact! directly.
   Reads and writes route through the infrastructure flow's reader/writer
   processes for serialized access, deadlock prevention, and observability.

   Phase 2 of the datahike migration adds a dispatch layer in front of every
   public op. For each call:
     1. Look up the running `:seon.db/flow` Integrant component.
     2. If it owns a conn-process for the given db-name, route through the
        datahike flow (`seon.db.datahike.flow/request!`).
     3. Otherwise fall through to the legacy datalevin path (unchanged).
   The datahike path auto-stamps `:seon.db/namespace <db-name>` on every
   entity map in tx-data (Decision 7 of the datahike-migration PRD). Vector
   tuples (`[:db/add ...]`, `[:db/retract ...]`) are left alone.

   Write API:
   - `transact!` -- takes a db-name keyword (`:seon`, `:seon.runtime`)
     and tx-data. Routes through datahike flow or datalevin flow writer.

   Read API (named convenience, takes db-name keyword):
   - `query` -- Datalog query
   - `pull-by-name` -- pull entity by selector and eid
   - `pull-many-by-name` -- pull multiple entities
   - `entity-by-name` -- get entity by eid

   Connection resolution:
   - `resolve-conn` -- resolve db-name to raw Datalevin connection

   Positional args are intentional for drop-in compatibility -- this is
   the one namespace where map-in/map-out does not apply."
  (:require [clojure.core.async.flow :as flow]
            [datalevin.core :as d]
            [malli.core :as m]
            [seon.db.datalevin.conn :as conn]
            [seon.db.datalevin.reader :as reader]
            [seon.db.schema :as db-schema]
            [seon.flow.msg :as msg]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;; Lazy alias for `seon.db.datahike.flow` — keywords like ::dh-flow/pids resolve
;; at read time, but the namespace is loaded only when first used (via
;; requiring-resolve below). This breaks a load cycle:
;;   seon.db → seon.db.datahike.flow → seon.flow.topology → seon.flow.harness
;;     → seon.flow.trace → seon.db
(create-ns 'seon.db.datahike.flow)
(alias 'dh-flow 'seon.db.datahike.flow)

(defn- dh-request!
  "Resolve and call seon.db.datahike.flow/request! lazily to avoid the load cycle."
  [req]
  ((requiring-resolve 'seon.db.datahike.flow/request!) req))

;;; --- Cross-JVM Relay (agent JVMs) ---
;;;
;;; When this JVM is an agent (no local datahike flow) and `seon.db.relay`
;;; has connected back to the orchestrator, route ops through the relay
;;; instead of throwing. Resolution is lazy so loading `seon.db` does not
;;; pull `seon.db.relay` (and its core.async + nippy deps) on the
;;; orchestrator boot path. The orchestrator never sets `*relay-active?*`,
;;; so it never reaches the relay branch.

(defn- relay-active?
  "True if this JVM has a live `seon.db.relay` connection back to an
   orchestrator. Lazily resolves the var; returns false if the ns hasn't
   been loaded."
  []
  (when-let [v (resolve 'seon.db.relay/*relay-active?*)]
    (boolean @v)))

(defn- relay-request!
  "Route through `seon.db.relay/request!`. Caller has already ensured
   `relay-active?` is true."
  [op db-name args]
  ((requiring-resolve 'seon.db.relay/request!)
   {:seon.db.relay/op op
    :seon.db.relay/db-name db-name
    :seon.db.relay/args (vec args)}))

;;; --- Internal ---

(defn- system-attr?
  "Returns true for :db/* system attributes that should not be validated
   against the Malli registry."
  [k]
  (and (keyword? k)
       (= "db" (namespace k))))

(defn- extract-tx-attrs
  "Extract all attribute keywords from tx-data.
   Handles both map entities and vector tuples [op e a v]."
  [tx-data]
  (into #{}
        (mapcat (fn [datum]
                  (cond
                    (map? datum) (keys datum)
                    (and (vector? datum) (>= (count datum) 3))
                    [(nth datum 2)]
                    :else nil)))
        tx-data))

(defn- validate-attrs!
  "Ensure all non-system attrs in tx-data are registered in seon.schema.
   Throws ex-info if any unregistered attr is found."
  [attrs]
  (let [domain-attrs (remove system-attr? attrs)
        unregistered (into [] (remove schema/registered?) domain-attrs)]
    (when (seq unregistered)
      (throw (ex-info (str "Unregistered attributes in transaction: " (pr-str unregistered)
                           ". Register them with seon.schema/register! first.")
                      {:unregistered unregistered})))))

(defn- truncate-value
  "Truncate a value's string representation for error messages."
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 100)
      (str (subs s 0 97) "...")
      s)))

(defn- validate-entity-values!
  "Validate each attribute value in an entity map against its registered Malli schema.
   Skips :db/* system attributes and unregistered attributes (caught by validate-attrs!).
   Throws ex-info on the first validation failure with a clear error message."
  [entity]
  (doseq [[attr val] entity]
    (when-not (system-attr? attr)
      (when (schema/registered? attr)
        (when-not (m/validate attr val)
          (throw (ex-info (str "Malli validation failed for " attr
                               ": expected " (pr-str (schema/schema-definition attr))
                               ", got " (truncate-value val))
                          {:attr attr
                           :expected-schema (schema/schema-definition attr)
                           :actual-value val
                           :malli-explanation (m/explain attr val)})))))))

(defn- validate-values!
  "Validate all entity maps in tx-data against their Malli schemas.
   Vector tuples ([:db/add ...], [:db/retract ...]) are skipped.
   Uses m/validate (fast boolean) first, only calls m/explain on failure."
  [tx-data]
  (doseq [datum tx-data]
    (when (map? datum)
      (validate-entity-values! datum))))

(defn- ensure-schema!
  "For each domain attr, check if it exists in the Datalevin schema for conn.
   If missing, derive the Datalevin type from the Malli definition and call
   d/update-schema to add it. The bridge reads :seon.db/* properties directly
   from the registered schema — no property extraction needed here."
  [conn attrs]
  (let [current-schema (d/schema conn)
        domain-attrs (remove system-attr? attrs)
        missing (remove #(contains? current-schema %) domain-attrs)]
    (when (seq missing)
      (let [schema-update
            (reduce
             (fn [acc attr]
               (let [malli-def (schema/schema-definition attr)
                     entry-schema (db-schema/malli-map->datalevin-schema
                                   [:map [attr malli-def]])]
                 (when-not (contains? entry-schema attr)
                   (throw (ex-info (str "Cannot derive Datalevin type for attribute " attr
                                        " (Malli type: " (pr-str malli-def) ")."
                                        " Add :seon.db/identity or :seon.db/unique to the Malli schema properties,"
                                        " or ensure the type is mappable to a Datalevin type.")
                                   {:attr attr :malli-def malli-def})))
                 (merge acc entry-schema)))
             {}
             missing)]
        (when (seq schema-update)
          (log/info "Auto-adding schema for attrs" {:attrs (keys schema-update)})
          (d/update-schema conn schema-update))))))

(def ^:dynamic *direct-mode*
  "When true, reads and writes bypass the infrastructure flow and use
   Datalevin directly. For tests only -- production must always route
   through flow. Bind to true in test fixtures that don't have a running
   infrastructure flow."
  false)

;;; --- Infrastructure Flow Access ---
;;;
;;; These functions access the infrastructure flow via requiring-resolve
;;; to avoid a circular dependency: seon.db -> seon.flow.topology -> seon.runtime -> seon.db

(defn- get-infra-flow
  "Get the infrastructure flow object from the runtime flow registry.
   Uses requiring-resolve to break the circular dep with seon.flow.topology.
   Returns the flow object or nil if not running."
  []
  (let [get-flow (requiring-resolve 'seon.runtime/get-flow)
        handle (get-flow {:seon.runtime/flow-id :seon.flow/infrastructure})]
    (when handle
      (:flow handle))))

(defn- get-pending-promises
  "Get the pending-promises atom from seon.flow.topology.
   Uses requiring-resolve to break the circular dep."
  []
  @(requiring-resolve 'seon.flow.topology/pending-promises))

;;; --- Connection Resolution ---
;;;
;;; Used by both the Named Convenience API and write! when given a keyword.

(def ^:dynamic *conn-manager*
  "Dynamic var for overriding the connection manager in tests.
   When nil (default), resolves from Integrant system."
  nil)

(def ^:dynamic *datahike-flow*
  "Dynamic var for overriding the datahike flow in tests.
   When nil (default), resolves via `get-datahike-flow`, which reads
   `:seon.db/flow` from the running Integrant system. Bind in a test fixture
   to a flow-state map returned by
   `seon.db.datahike.flow/build-datahike-flow!`.

   A bound flow-state may carry an optional
   `:seon.db.datahike.flow/aliases` map (logical db-name -> internal db-name)
   so test fixtures can route a logical name like `:seon.runtime` through an
   isolated, gensym-suffixed conn-process. `datahike-owned?` and the four
   public fns resolve through the alias map before dispatch; entities are
   still stamped with the caller's logical db-name (Decision 7)."
  nil)

(defn- get-datahike-flow
  "Return the datahike flow-state map (`::dh-flow/flow`, `::dh-flow/pids`, ...)
   if running, else nil.

   Resolution order:
     1. `*datahike-flow*` dynamic var (tests)
     2. `:seon.db/flow` key in `integrant.repl.state/system`
   Tolerates the system not being up — returns nil rather than throwing."
  []
  (or *datahike-flow*
      (try
        (let [sys (deref (requiring-resolve 'integrant.repl.state/system))]
          (get sys :seon.db/flow))
        (catch Exception _ nil))))

(defn- resolve-db-name
  "Resolve a caller-supplied `db-name` through the running datahike flow's
   `::aliases` map (if any). Test fixtures populate `::aliases` so a logical
   name like `:seon.runtime` maps to a gensym-suffixed internal name. Outside
   a fixture, `::aliases` is absent and the input is returned unchanged."
  [db-name]
  (let [fs (get-datahike-flow)
        aliases (::dh-flow/aliases fs)]
    (or (get aliases db-name) db-name)))

(defn- datahike-owned?
  "Return true if the running datahike flow owns a conn-process for `db-name`.
   Returns false if there is no flow, or the flow's `::pids` map doesn't
   contain the db-name. Honors the flow's `::aliases` map so logical db-names
   bound by a test fixture resolve to their internal conn-process. Callers use
   this to decide between the datahike route and the legacy datalevin route."
  [db-name]
  (boolean
    (when-let [fs (get-datahike-flow)]
      (contains? (::dh-flow/pids fs) (resolve-db-name db-name)))))

(defn- stamp-namespace
  "Decision 7: walk tx-data and stamp `:seon.db/namespace <db-name>` on each
   entity map that doesn't already carry one. Vector tuples (`[:db/add ...]`,
   `[:db/retract ...]`) pass through unchanged — they address individual
   datoms, not whole entities.

   Called only on the datahike route; the legacy datalevin path is left alone
   for the final switchover."
  [db-name tx-data]
  (mapv (fn [datum]
          (if (and (map? datum)
                   (not (contains? datum :seon.db/namespace)))
            (assoc datum :seon.db/namespace db-name)
            datum))
        tx-data))

(defn- get-conn-manager
  "Get the connection manager from *conn-manager* or the running Integrant system.
   Throws if neither is available."
  []
  (or *conn-manager*
      (if-let [sys-var (try @(requiring-resolve 'integrant.repl.state/system)
                            (catch Exception _ nil))]
        (or (:seon.db.datalevin/connections sys-var)
            (throw (ex-info "Connection manager not available -- is the system running?"
                            {:component :seon.db.datalevin/connections})))
        (throw (ex-info "Integrant system not running" {})))))

(defn- db-name->conn-args
  "Convert a db-name keyword to [get-fn reconnect-db].
   get-fn takes a conn manager and returns a connection atom."
  [db-name]
  [#(conn/get-conn! {::conn/manager % ::conn/db db-name})
   db-name])

(defn resolve-conn
  "Resolve a db-name keyword to a Datalevin connection via conn manager.
   Used by ensure-schema!, *direct-mode*, and callers that need a raw conn
   for libraries not yet migrated to the db-name API (e.g. seon.ctx)."
  [db-name]
  (let [mgr (get-conn-manager)
        [get-fn _] (db-name->conn-args db-name)]
    (get-fn mgr)))

(defn- resolve-db
  "Resolve a db-name keyword to a Datalevin db value.
   Gets connection from conn manager and derefs it."
  [db-name]
  @(resolve-conn db-name))

;;; --- Flow Routing (shared by read! and write!) ---

(defn- flow-request!
  "Send a request through the infrastructure flow and wait for a reply.
   target is the flow process+input pair, e.g. [:seon.flow/writer :seon.flow.in/request].
   payload is the domain-specific payload map.
   opts may contain :timeout-ms (default 10000).
   Returns the ::msg/value from the reply on success.
   Throws on timeout or error status."
  [target payload opts]
  (let [fl (get-infra-flow)]
    (when-not fl
      (throw (ex-info "Infrastructure flow not running"
                      {:fn "seon.db/flow-request!" :target target})))
    (let [pending (get-pending-promises)
          timeout-ms (or (:timeout-ms opts) 10000)
          request-id (random-uuid)
          p (promise)
          request {::msg/id request-id
                   ::msg/version 1
                   ::msg/type :request
                   ::msg/from-ns "seon.db"
                   ::msg/payload payload
                   ::msg/created-at (Instant/now)}]
      (swap! pending assoc request-id p)
      (try
        (flow/inject fl target [request])
        (let [reply (deref p timeout-ms ::timed-out)]
          (if (= reply ::timed-out)
            (do
              (swap! pending dissoc request-id)
              (log/error "Flow request timed out" {:request-id request-id
                                                   :timeout-ms timeout-ms
                                                   :target target})
              (throw (ex-info "Flow request timed out"
                              {::msg/status :timeout
                               ::msg/id request-id
                               :timeout-ms timeout-ms})))
            (case (::msg/status reply)
              :ok (::msg/value reply)
              (do
                (log/error "Flow request failed" {:request-id request-id
                                                  :status (::msg/status reply)
                                                  :error (::msg/error-message reply)
                                                  :duration-ms (::msg/duration-ms reply)})
                (throw (ex-info (or (::msg/error-message reply)
                                    (str "Flow request failed: " (::msg/status reply)))
                                (select-keys reply [::msg/status ::msg/error-type
                                                    ::msg/error-class ::msg/error-message
                                                    ::msg/id ::msg/duration-ms])))))))
        (catch Exception e
          (swap! pending dissoc request-id)
          (throw e))))))

;;; --- Write Routing ---

(defn- build-writer-payload
  "Build the writer payload map. db-name is a keyword; passes
   ::writer/db-name (string) so the writer resolves its own conn."
  [db-name tx-data]
  {:seon.db.datalevin.writer/tx-data tx-data
   :seon.db.datalevin.writer/db-name (name db-name)})

(defn- write!
  "Route a transaction through the infrastructure flow writer.
   db-name is a keyword (e.g. :seon, :seon.runtime).
   Throws if the infrastructure flow is not running.
   In tests, bind *direct-mode* to true to bypass the flow."
  ([db-name tx-data]
   (write! db-name tx-data nil))
  ([db-name tx-data opts]
   (if *direct-mode*
     (d/transact! (resolve-conn db-name) tx-data)
     (flow-request! [:seon.flow/writer :seon.flow.in/request]
                    (build-writer-payload db-name tx-data)
                    opts))))

;;; --- Read Routing ---

(defn- read!
  "Route a read query through the infrastructure flow reader.
   query-fn is one of :q, :pull, :pull-many, :entity.
   db-name is a keyword (e.g. :seon, :seon.runtime).
   args is a vector of arguments for the query function.
   Throws if the infrastructure flow is not running."
  [query-fn db-name args]
  (flow-request! [:seon.flow/reader :seon.flow.in/request]
                 {::reader/query-fn query-fn
                  ::reader/db-name (name db-name)
                  ::reader/args args}
                 nil))

;;; --- Public API (positional, mirrors datalevin.core) ---

(defn transact!
  "Transact data into a named database via the infrastructure flow writer.

   db-name is a keyword (e.g. :seon.runtime, :seon, or a namespace keyword).
   The writer resolves and owns the connection, enabling automatic retry
   on connection failure.

   Validates attributes against the Malli registry, validates values against
   their Malli schemas, auto-adds missing Datalevin schema, then routes
   through the flow writer for serialized access.
   In tests, bind *direct-mode* to bypass the flow.

   opts map (optional):
     :timeout-ms - Flow request timeout in ms (default 10000)"
  {:malli/schema [:function
                  [:=> [:cat :keyword [:sequential :any]] :any]
                  [:=> [:cat :keyword [:sequential :any] [:maybe :map]] :any]]}
  ([db-name tx-data]
   (transact! db-name tx-data nil))
  ([db-name tx-data opts]
   (cond
     (datahike-owned? db-name)
     ;; Datahike route: auto-stamp :seon.db/namespace on each entity map,
     ;; validate attrs + values against Malli, and dispatch to the flow's
     ;; conn-process. Schema is installed on the datahike side at :init, so
     ;; no ensure-schema! here. The stamp uses the caller's logical db-name
     ;; (semantic identity), while dispatch uses the resolved internal name
     ;; so test fixtures can alias logical -> gensym'd db-names.
     (let [stamped (stamp-namespace db-name tx-data)
           attrs (extract-tx-attrs stamped)]
       (validate-attrs! attrs)
       (validate-values! stamped)
       (dh-request!
         (cond-> {::dh-flow/flow (get-datahike-flow)
                  ::dh-flow/db-name (resolve-db-name db-name)
                  ::dh-flow/op :transact!
                  ::dh-flow/args [stamped]}
           (:timeout-ms opts) (assoc ::dh-flow/timeout-ms (:timeout-ms opts)))))

     (relay-active?)
     ;; Agent JVM: route to the orchestrator over TCP. The orchestrator runs
     ;; the same `transact!` (which validates and dispatches to datahike).
     (relay-request! :transact! db-name [tx-data])

     :else
     ;; Legacy datalevin route
     (let [attrs (extract-tx-attrs tx-data)
           conn (resolve-conn db-name)]
       (validate-attrs! attrs)
       (validate-values! tx-data)
       (ensure-schema! conn attrs)
       (write! db-name tx-data opts)))))

;;; --- Named Convenience API ---
;;;
;;; These functions resolve a db-name keyword to a connection via the
;;; conn manager. In direct mode, they resolve locally and retry on
;;; connection error. In flow mode, they route through the reader.

(defn- connection-error?
  "Check if an exception indicates a connection/server error."
  [^Throwable e]
  (let [msg (str (.getMessage e) " " (some-> (.getCause e) (.getMessage)))]
    (boolean
     (or (re-find #"(?i)connection refused" msg)
         (re-find #"(?i)connection reset" msg)
         (re-find #"(?i)broken pipe" msg)
         (re-find #"(?i)closed" msg)
         (re-find #"(?i)not connected" msg)
         (re-find #"(?i)timeout" msg)
         (instance? java.net.ConnectException e)
         (instance? java.net.ConnectException (.getCause e))))))

(defn- with-retry
  "Execute f with a db resolved from db-name. On connection error,
   reconnect and retry once."
  [db-name f]
  (try
    (f (resolve-db db-name))
    (catch Exception e
      (if (connection-error? e)
        (do
          (log/warn "Connection error on" db-name "- reconnecting and retrying"
                    {:error (.getMessage e)})
          (let [mgr (get-conn-manager)
                [_ reconnect-ns] (db-name->conn-args db-name)]
            (conn/reconnect! {::conn/manager mgr ::conn/db reconnect-ns})
            (f (resolve-db db-name))))
        (throw e)))))

(defn query
  "Query a named database. Routes through the infrastructure flow reader,
   or uses direct connection with retry when *direct-mode* is true.

   db-name -- :seon, :seon.runtime, or a namespace keyword like :seon.trading
   datalog-query -- Datalog query (same as d/q first arg)
   inputs -- additional query inputs (sources, rules, etc.)

   Example:
     (query :seon.runtime '[:find ?e ?n :where [?e :seon.fn/name ?n]])
     (query :seon '[:find ?e :where [?e :name \"test\"]])"
  {:malli/schema [:=> [:cat :keyword :any [:* :any]] :any]}
  [db-name datalog-query & inputs]
  (cond
    (datahike-owned? db-name)
    (dh-request! {::dh-flow/flow (get-datahike-flow)
                       ::dh-flow/db-name (resolve-db-name db-name)
                       ::dh-flow/op :q
                       ::dh-flow/args (into [datalog-query] inputs)})

    (relay-active?)
    (relay-request! :query db-name (into [datalog-query] inputs))

    *direct-mode*
    (with-retry db-name
      (fn [db] (apply d/q datalog-query db inputs)))

    :else
    (read! :q db-name (into [datalog-query] inputs))))

(defn pull-by-name
  "Pull an entity from a named database by selector and eid.
   Routes through the infrastructure flow reader, or direct with retry.

   db-name -- :seon, :seon.runtime, or a namespace keyword
   selector -- pull pattern (e.g., '[*] or '[:name :age])
   eid -- entity id or lookup ref

   Example:
     (pull-by-name :seon.runtime '[*] 1)"
  {:malli/schema [:=> [:cat :keyword :any :any] :any]}
  [db-name selector eid]
  (cond
    (datahike-owned? db-name)
    (dh-request! {::dh-flow/flow (get-datahike-flow)
                       ::dh-flow/db-name (resolve-db-name db-name)
                       ::dh-flow/op :pull
                       ::dh-flow/args [selector eid]})

    (relay-active?)
    (relay-request! :pull-by-name db-name [selector eid])

    *direct-mode*
    (with-retry db-name
      (fn [db] (d/pull db selector eid)))

    :else
    (read! :pull db-name [selector eid])))

(defn pull-many-by-name
  "Pull multiple entities from a named database.
   Routes through the infrastructure flow reader, or direct with retry.

   db-name -- :seon, :seon.runtime, or a namespace keyword
   selector -- pull pattern
   eids -- collection of entity ids or lookup refs

   Example:
     (pull-many-by-name :seon.runtime '[:seon.fn/name] [1 2 3])"
  {:malli/schema [:=> [:cat :keyword :any [:sequential :any]] :any]}
  [db-name selector eids]
  (cond
    (datahike-owned? db-name)
    (dh-request! {::dh-flow/flow (get-datahike-flow)
                       ::dh-flow/db-name (resolve-db-name db-name)
                       ::dh-flow/op :pull-many
                       ::dh-flow/args [selector eids]})

    (relay-active?)
    (relay-request! :pull-many-by-name db-name [selector eids])

    *direct-mode*
    (with-retry db-name
      (fn [db] (d/pull-many db selector eids)))

    :else
    (read! :pull-many db-name [selector eids])))

(defn entity-by-name
  "Get an entity from a named database by eid.
   Routes through the infrastructure flow reader, or direct with retry.

   db-name -- :seon, :seon.runtime, or a namespace keyword
   eid -- entity id or lookup ref

   Example:
     (entity-by-name :seon.runtime 1)"
  {:malli/schema [:=> [:cat :keyword :any] :any]}
  [db-name eid]
  (cond
    (datahike-owned? db-name)
    (dh-request! {::dh-flow/flow (get-datahike-flow)
                       ::dh-flow/db-name (resolve-db-name db-name)
                       ::dh-flow/op :entity
                       ::dh-flow/args [eid]})

    *direct-mode*
    (with-retry db-name
      (fn [db] (d/entity db eid)))

    :else
    (read! :entity db-name [eid])))

;;; --- Infrastructure Flow Coordination ---

(defn pause-writer!
  "Pause the infrastructure flow writer. Blocks until paused.
   Use before backups to ensure all writes are flushed."
  []
  (let [fl (get-infra-flow)]
    (flow/pause fl)
    (flow/ping fl 5000)
    (log/info "Infrastructure writer paused")))

(defn resume-writer!
  "Resume the infrastructure flow writer after backup completes."
  []
  (let [fl (get-infra-flow)]
    (flow/resume fl)
    (log/info "Infrastructure writer resumed")))
