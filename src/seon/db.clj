(ns seon.db
  "Seon's database API. Drop-in replacement for datalevin.core.

   Agents use this instead of d/transact! directly.
   Reads pass through directly. Writes route through the infrastructure
   flow's writer process for serialized access, deadlock prevention,
   and observability.

   Two API levels:
   - Raw pass-through: `q`, `pull`, `pull-many`, `entity` -- take a db value
   - Named convenience: `query`, `pull-by-name`, `pull-many-by-name` -- take
     a db-name keyword (`:seon`, `:seon.runtime`, or namespace string),
     resolve connection via conn manager, handle staleness with retry

   `transact!` accepts either a keyword db-name (preferred) or a raw
   connection (deprecated). When given a keyword, the writer resolves
   and owns the connection itself, enabling retry on failure.

   Positional args are intentional for drop-in compatibility -- this is
   the one namespace where map-in/map-out does not apply."
  (:require [clojure.core.async.flow :as flow]
            [datalevin.core :as d]
            [seon.db.datalevin.conn :as conn]
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
                 (merge acc entry-schema)))
             {}
             missing)]
        (when (seq schema-update)
          (log/info "Auto-adding schema for attrs" {:attrs (keys schema-update)})
          (d/update-schema conn schema-update))))))

(def ^:dynamic *direct-write*
  "When true, write! bypasses the infrastructure flow and uses d/transact!
   directly. For tests only -- production must always route through flow.
   Bind to true in test fixtures that don't have a running infrastructure flow."
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

(defn- resolve-conn
  "Resolve a db-name keyword to a Datalevin connection via conn manager.
   Used by ensure-schema! and *direct-write* when caller passes a keyword."
  [db-name]
  (let [mgr (get-conn-manager)
        [get-fn _] (db-name->conn-args db-name)]
    (get-fn mgr)))

(defn- resolve-db
  "Resolve a db-name keyword to a Datalevin db value.
   Gets connection from conn manager and derefs it."
  [db-name]
  @(resolve-conn db-name))

;;; --- Write Routing ---

(defn- build-writer-payload
  "Build the writer payload map. When db-name-or-conn is a keyword,
   passes ::writer/db-name (string) so the writer resolves its own conn.
   When it's a connection object, passes ::writer/conn (deprecated path)."
  [db-name-or-conn tx-data]
  (let [base {:seon.db.datalevin.writer/tx-data tx-data}]
    (if (keyword? db-name-or-conn)
      (assoc base :seon.db.datalevin.writer/db-name (name db-name-or-conn))
      (assoc base :seon.db.datalevin.writer/conn db-name-or-conn))))

(defn- write!
  "Route a transaction through the infrastructure flow writer.
   db-name-or-conn is either a keyword (preferred, writer resolves conn)
   or a raw connection object (deprecated).
   Throws if the infrastructure flow is not running.
   In tests, bind *direct-write* to true to bypass the flow."
  ([db-name-or-conn tx-data]
   (write! db-name-or-conn tx-data nil))
  ([db-name-or-conn tx-data opts]
   (if *direct-write*
     (let [c (if (keyword? db-name-or-conn)
               (resolve-conn db-name-or-conn)
               db-name-or-conn)]
       (d/transact! c tx-data))
     (let [fl (get-infra-flow)]
       (when-not fl
         (throw (ex-info "Infrastructure flow not running -- cannot write"
                         {:fn "seon.db/write!"})))
       (let [pending (get-pending-promises)
             timeout-ms (or (:timeout-ms opts) 10000)
             request-id (random-uuid)
             p (promise)
             request {::msg/id request-id
                      ::msg/version 1
                      ::msg/type :request
                      ::msg/from-ns "seon.db"
                      ::msg/payload (build-writer-payload db-name-or-conn tx-data)
                      ::msg/created-at (Instant/now)}]
         ;; Register promise before injection
         (swap! pending assoc request-id p)
         (try
           (flow/inject fl [:seon.flow/writer :seon.flow.in/request] [request])
           (let [reply (deref p timeout-ms ::timed-out)]
             (if (= reply ::timed-out)
               (do
                 (swap! pending dissoc request-id)
                 (log/error "Write timed out" {:request-id request-id
                                               :timeout-ms timeout-ms
                                               :tx-count (count tx-data)})
                 (throw (ex-info "Write timed out"
                                 {::msg/status :timeout
                                  ::msg/id request-id
                                  :timeout-ms timeout-ms})))
               (case (::msg/status reply)
                 :ok (::msg/value reply)
                 (do
                   (log/error "Write failed via flow" {:request-id request-id
                                                       :status (::msg/status reply)
                                                       :error (::msg/error-message reply)
                                                       :duration-ms (::msg/duration-ms reply)})
                   (throw (ex-info (or (::msg/error-message reply)
                                       (str "Write failed: " (::msg/status reply)))
                                   (select-keys reply [::msg/status ::msg/error-type
                                                       ::msg/error-class ::msg/error-message
                                                       ::msg/id ::msg/duration-ms])))))))
           (catch Exception e
             (swap! pending dissoc request-id)
             (throw e))))))))

;;; --- Public API (positional, mirrors datalevin.core) ---

(defn transact!
  "Transact data into a database. First arg is either a db-name keyword
   (e.g. :seon.runtime) or a raw Datalevin connection (deprecated).

   When given a keyword, the writer resolves and owns the connection,
   enabling automatic retry on connection failure. Schema is checked
   via a temporary conn from the manager.

   Validates attributes, ensures schema, then routes through the
   infrastructure flow writer for serialized access.
   Throws if the infrastructure flow is not running."
  {:malli/schema [:function
                  [:=> [:cat :any [:sequential :any]] :any]
                  [:=> [:cat :any [:sequential :any] [:maybe :map]] :any]]}
  ([db-name-or-conn tx-data]
   (transact! db-name-or-conn tx-data nil))
  ([db-name-or-conn tx-data tx-meta]
   (let [attrs (extract-tx-attrs tx-data)
         conn-for-schema (if (keyword? db-name-or-conn)
                           (resolve-conn db-name-or-conn)
                           db-name-or-conn)]
     (validate-attrs! attrs)
     (ensure-schema! conn-for-schema attrs))
   (when tx-meta
     (log/debug "tx-meta provided but not yet supported via flow writer" {:tx-meta tx-meta}))
   (write! db-name-or-conn tx-data)))

(defn q
  "Query the database. Pass-through to datalevin.core/q."
  {:malli/schema [:=> [:cat :any [:* :any]] :any]}
  [query & inputs]
  (apply d/q query inputs))

(defn pull
  "Pull an entity by selector and eid. Pass-through to datalevin.core/pull."
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [db selector eid]
  (d/pull db selector eid))

(defn pull-many
  "Pull multiple entities. Pass-through to datalevin.core/pull-many."
  {:malli/schema [:=> [:cat :any :any [:sequential :any]] :any]}
  [db selector eids]
  (d/pull-many db selector eids))

(defn entity
  "Get an entity by eid. Pass-through to datalevin.core/entity."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [db eid]
  (d/entity db eid))

;;; --- Named Convenience API ---
;;;
;;; These functions resolve a db-name keyword to a connection via the
;;; conn manager, deref it to get a db value, and delegate to datalevin.
;;; On connection error, they reconnect and retry once.

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
  "Query a named database. Resolves connection by db-name, retries on staleness.

   db-name -- :seon, :seon.runtime, or a namespace keyword like :seon.trading
   datalog-query -- Datalog query (same as d/q first arg)
   inputs -- additional query inputs (sources, rules, etc.)

   Example:
     (query :seon.runtime '[:find ?e ?n :where [?e :seon.fn/name ?n]])
     (query :seon '[:find ?e :where [?e :name \"test\"]])"
  {:malli/schema [:=> [:cat :keyword :any [:* :any]] :any]}
  [db-name datalog-query & inputs]
  (with-retry db-name
    (fn [db] (apply d/q datalog-query db inputs))))

(defn pull-by-name
  "Pull an entity from a named database by selector and eid.

   db-name -- :seon, :seon.runtime, or a namespace keyword
   selector -- pull pattern (e.g., '[*] or '[:name :age])
   eid -- entity id or lookup ref

   Example:
     (pull-by-name :seon.runtime '[*] 1)"
  {:malli/schema [:=> [:cat :keyword :any :any] :any]}
  [db-name selector eid]
  (with-retry db-name
    (fn [db] (d/pull db selector eid))))

(defn pull-many-by-name
  "Pull multiple entities from a named database.

   db-name -- :seon, :seon.runtime, or a namespace keyword
   selector -- pull pattern
   eids -- collection of entity ids or lookup refs

   Example:
     (pull-many-by-name :seon.runtime '[:seon.fn/name] [1 2 3])"
  {:malli/schema [:=> [:cat :keyword :any [:sequential :any]] :any]}
  [db-name selector eids]
  (with-retry db-name
    (fn [db] (d/pull-many db selector eids))))

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
