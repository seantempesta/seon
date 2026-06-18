(ns seon.db
  "Seon's database API. All database access goes through here.

   Agents use this instead of touching a datahike connection directly.
   Reads and writes route through the running `:seon.db/flow` datahike
   conn-process for the requested db-name. Cross-JVM relay (`seon.db.relay`)
   forwards ops from agent JVMs back to the orchestrator.

   Write API:
   - `transact!` -- takes a db-name keyword (e.g. `:seon`, `:seon.runtime`)
     and tx-data. Auto-stamps `:seon.db/namespace <db-name>` on every entity
     map (Decision 7 of the datahike-migration PRD). Vector tuples
     (`[:db/add ...]`, `[:db/retract ...]`) pass through unchanged.

   Read API (named convenience, takes db-name keyword):
   - `query` -- Datalog query
   - `pull-by-name` -- pull entity by selector and eid
   - `pull-many-by-name` -- pull multiple entities
   - `entity-by-name` -- get entity by eid

   Unregistered db-names raise `ex-info` with `:type :seon.db/unregistered-namespace`
   plus the list of currently-registered namespaces. All routes go through the
   datahike flow (`seon.db.datahike.flow`) or the cross-JVM relay; there is no
   fall-through to a raw store.

   Positional args are intentional for drop-in compatibility -- this is
   the one namespace where map-in/map-out does not apply."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

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
  "Returns true for datahike's own system attributes — the `:db/*` family
   AND the `:db.*` sub-namespaces (`:db.secondary/*`, `:db.entity/*`,
   `:db.valid/*`, …). None are validated against the seon Malli registry
   (see datahike's schema.cljc ::schema-attribute / ::secondary-index-attribute)."
  [k]
  (let [n (and (keyword? k) (namespace k))]
    (boolean (and n (or (= n "db") (str/starts-with? n "db."))))))

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

;;; --- Legacy dynamic vars (deprecated; preserved as no-op stubs) ---
;;;
;;; These vars predate the datahike migration (they steered the prior
;;; core's direct-mode and connection manager). They are no longer
;;; consulted by any active code path; they survive only so that the few
;;; remaining call sites that `(binding [db/*direct-mode* true] ...)` or
;;; `(binding [db/*conn-manager* ...] ...)` continue to compile. Both will
;;; be deleted once those call sites are ported off (M-3).

(def ^:dynamic *direct-mode*
  "Deprecated. No-op. Pre-migration this caused reads and writes to bypass
   the infrastructure flow and call the underlying store directly. There is
   no direct path now; the only routes are the datahike flow
   (`seon.db.datahike.flow`) and the cross-JVM relay. Binding has no effect."
  false)

(def ^:dynamic *conn-manager*
  "Deprecated. No-op. Pre-migration this overrode the connection manager
   used by the now-replaced direct path. Binding has no effect."
  nil)

;;; --- Infrastructure Flow Access ---

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
     2. `seon.db.datahike.system/current-flow` atom (set by `:seon.db/flow`
        init-key the moment the flow finishes building — visible to components
        that boot in the same `ig/init` pass, before `integrant.repl.state/system`
        has been populated)
     3. `:seon.db/flow` key in `integrant.repl.state/system` (post-boot fallback)
   Tolerates the system not being up — returns nil rather than throwing."
  []
  (or *datahike-flow*
      (try
        (when-let [a (requiring-resolve 'seon.db.datahike.system/current-flow)]
          (deref @a))
        (catch Exception _ nil))
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
   bound by a test fixture resolve to their internal conn-process."
  [db-name]
  (boolean
    (when-let [fs (get-datahike-flow)]
      (contains? (::dh-flow/pids fs) (resolve-db-name db-name)))))

(defn- registered-db-names
  "Return the set of db-names currently registered in the running datahike
   flow (or in `*datahike-flow*` if bound). Returns an empty set if no flow
   is up. Used for error reporting."
  []
  (set (some-> (get-datahike-flow) ::dh-flow/pids keys)))

(defn- throw-unregistered!
  "Throw a clear `:type :seon.db/unregistered-namespace` ex-info for a
   db-name that isn't in the running datahike flow. Operator-friendly
   failure mode — unregistered names fail loudly here instead of silently
   timing out further down the stack (per `remaining.md` smell #8)."
  [db-name op]
  (throw (ex-info (str "No conn-process for db-name " (pr-str db-name)
                       " — not registered in :seon.db/flow."
                       " Registered: " (pr-str (sort (registered-db-names))))
                  {:type :seon.db/unregistered-namespace
                   :db-name db-name
                   :op op
                   :registered (registered-db-names)})))

(defn resolve-conn
  "Deprecated. Pre-migration this resolved a db-name to a raw store
   connection. There is no raw conn handed out from `seon.db` now; callers
   that ask for one get `:type :seon.db/unregistered-namespace` so the
   failure is loud rather than silent. Replace usages with `transact!` /
   `query` / `pull-by-name` / `pull-many-by-name` / `entity-by-name` — they
   route through the datahike flow."
  [db-name]
  (throw-unregistered! db-name :resolve-conn))

(defn- stamp-namespace
  "Decision 7: walk tx-data and stamp `:seon.db/namespace <db-name>` on each
   entity map that doesn't already carry one. Vector tuples (`[:db/add ...]`,
   `[:db/retract ...]`) pass through unchanged — they address individual
   datoms, not whole entities."
  [db-name tx-data]
  (mapv (fn [datum]
          (if (and (map? datum)
                   (not (contains? datum :seon.db/namespace)))
            (assoc datum :seon.db/namespace db-name)
            datum))
        tx-data))

;;; --- Public API (positional; routes through `seon.db.datahike.flow`) ---

(defn transact!
  "Transact data into a named database via the running datahike flow.

   db-name is a keyword (e.g. :seon.runtime, :seon, or a namespace keyword).
   Validates attributes against the Malli registry, validates values against
   their Malli schemas, then dispatches to the flow's conn-process.

   Throws `:type :seon.db/unregistered-namespace` if `db-name` isn't owned
   by the running flow.

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
     (relay-request! :transact! db-name [tx-data])

     :else
     (throw-unregistered! db-name :transact!))))

(defn query
  "Query a named database. Routes through the running datahike flow.

   db-name -- :seon, :seon.runtime, or a namespace keyword
   datalog-query -- Datalog query
   inputs -- additional query inputs (sources, rules, etc.)

   Throws `:type :seon.db/unregistered-namespace` if `db-name` isn't owned
   by the running flow."
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

    :else
    (throw-unregistered! db-name :query)))

(defn pull-by-name
  "Pull an entity from a named database by selector and eid."
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

    :else
    (throw-unregistered! db-name :pull-by-name)))

(defn pull-many-by-name
  "Pull multiple entities from a named database."
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

    :else
    (throw-unregistered! db-name :pull-many-by-name)))

(defn entity-by-name
  "Get an entity from a named database by eid."
  {:malli/schema [:=> [:cat :keyword :any] :any]}
  [db-name eid]
  (cond
    (datahike-owned? db-name)
    (dh-request! {::dh-flow/flow (get-datahike-flow)
                  ::dh-flow/db-name (resolve-db-name db-name)
                  ::dh-flow/op :entity
                  ::dh-flow/args [eid]})

    (relay-active?)
    (relay-request! :entity-by-name db-name [eid])

    :else
    (throw-unregistered! db-name :entity-by-name)))

;;; --- Infrastructure Flow Coordination ---

(defn pause-writer!
  "Pause the infrastructure flow writer. Blocks until paused.
   Use before backups to ensure all writes are flushed."
  []
  (let [get-flow (requiring-resolve 'seon.runtime/get-flow)
        handle (get-flow {:seon.runtime/flow-id :seon.flow/infrastructure})]
    (when-let [fl (:flow handle)]
      ((requiring-resolve 'clojure.core.async.flow/pause) fl)
      ((requiring-resolve 'clojure.core.async.flow/ping) fl 5000)
      (log/info "Infrastructure writer paused"))))

(defn resume-writer!
  "Resume the infrastructure flow writer after backup completes."
  []
  (let [get-flow (requiring-resolve 'seon.runtime/get-flow)
        handle (get-flow {:seon.runtime/flow-id :seon.flow/infrastructure})]
    (when-let [fl (:flow handle)]
      ((requiring-resolve 'clojure.core.async.flow/resume) fl)
      (log/info "Infrastructure writer resumed"))))
