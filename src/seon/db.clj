(ns seon.db
  "Seon's database API. All database access goes through here.

   Agents use this instead of d/transact! directly.
   Reads and writes route through the infrastructure flow's reader/writer
   processes for serialized access, deadlock prevention, and observability.

   Write API:
   - `transact!` -- takes a db-name keyword (`:seon`, `:seon.runtime`)
     and tx-data. Routes through the flow writer.

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
   d/update-schema to add it."
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
                                        " Either add :db/valueType to the Malli schema properties"
                                        " or include " attr " in the module's hardcoded datalevin-schema.")
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
   In tests, bind *direct-mode* to bypass the flow."
  {:malli/schema [:function
                  [:=> [:cat :keyword [:sequential :any]] :any]
                  [:=> [:cat :keyword [:sequential :any] [:maybe :map]] :any]]}
  ([db-name tx-data]
   (transact! db-name tx-data nil))
  ([db-name tx-data tx-meta]
   (let [attrs (extract-tx-attrs tx-data)
         conn (resolve-conn db-name)]
     (validate-attrs! attrs)
     (validate-values! tx-data)
     (ensure-schema! conn attrs))
   (when tx-meta
     (log/debug "tx-meta provided but not yet supported via flow writer" {:tx-meta tx-meta}))
   (write! db-name tx-data)))

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
  (if *direct-mode*
    (with-retry db-name
      (fn [db] (apply d/q datalog-query db inputs)))
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
  (if *direct-mode*
    (with-retry db-name
      (fn [db] (d/pull db selector eid)))
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
  (if *direct-mode*
    (with-retry db-name
      (fn [db] (d/pull-many db selector eids)))
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
  (if *direct-mode*
    (with-retry db-name
      (fn [db] (d/entity db eid)))
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
