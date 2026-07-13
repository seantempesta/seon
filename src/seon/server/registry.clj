(ns seon.server.registry
  "The wire-server's cluster RUNTIME registry — atom of `{db-name -> entry}`
   where each entry is `{::conn <datahike-conn> ::backend kw ::path
   str-or-nil}`.

   ONE wire-server JVM hosts every cluster's db: a db-name here IS a
   cluster name (`:default`, `:acme`, an ephemeral bench cluster), and
   the wire-server resolves each request's conn from this registry. The
   ambient conn (`wire/-main`) is a registry entry like any other —
   there is no second open path. Direct `datahike.api/connect`, no flow;
   one process, in-memory atoms, no coordination overhead.

   ## Idempotent semantics

   `ensure-db!` on an existing db-name returns the same entry (identical?
   conn). `remove-db!` on an absent name is a no-op returning
   `{::removed? false}`. `delete-db!` additionally drops the database in
   its store. Concurrent ensures on the same name race exactly once —
   losers see the winner's conn.

   ## On-ensure-db extension point (the reactive seam)

   `ensure-db!` invokes a per-conn extension point — an atom of keyed
   `{::hook-key k ::hook-fn (fn [conn db-name])}` entries registered via
   `register-on-ensure-db-hook!` (idempotent by key: re-registration
   replaces in place) — after opening (and BEFORE handing back) a conn.
   The wire-server registers its own `::raw-broadcast` `d/listen!` here;
   the reactive engine registers its `::reactive` `d/listen!` here too,
   WITHOUT this ns (or `wire.clj`) requiring `seon.server.reactive`. See
   `docs/prds/agent-runtime/clusters-and-multi-db-wiring-2026-06-03.md` §5
   and the platform-review hook-mechanism section."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.db.id :as id]
            [seon.schema :as schema]
            [seon.server.store :as store]
            [taoensso.timbre :as log]))

;;; --- Schemas ---------------------------------------------------------------

(schema/register! ::db-name :seon.server.store/db-name)
(schema/register! ::backend :seon.server.store/backend)
(schema/register! ::path :seon.server.store/path)

;; A live conn is an opaque clojure.lang.IAtom2 (datahike connection
;; type). We don't constrain its shape; the registry hands it out as-is.
(schema/register! ::conn [:fn some?])

(schema/register! ::entry
                  [:map
                   [::conn ::conn]
                   [::backend ::backend]
                   [::path {:optional true} ::path]])

(schema/register! ::ensure-db!-request
                  [:map
                   [::db-name ::db-name]
                   [::backend {:optional true} ::backend]
                   [::path {:optional true} ::path]])

(schema/register! ::ensure-db!-response ::entry)

(schema/register! ::remove-db!-response
                  [:map [::removed? :boolean]])

(schema/register! ::delete-db!-response
                  [:map
                   [::removed? :boolean]
                   [::deleted? :boolean]
                   [::error {:optional true} :string]])

;; Fork point: a transaction id (basis-t / tx eid — datahike's :max-tx).
;; `:seon.error/at` carries exactly this value, so an error row's `at`
;; plugs straight into a fork request.
(schema/register! ::at :int)

(schema/register! ::fork-db!-request
                  [:map
                   [::db-name ::db-name]
                   [::fork-name ::db-name]
                   [::at {:optional true} ::at]
                   [::path {:optional true} ::path]])

(schema/register! ::fork-db!-response
                  [:map
                   [::forked? :boolean]
                   [::db-name {:optional true} ::db-name]
                   [::basis-t {:optional true} :int]
                   [::path {:optional true} ::path]
                   [::error {:optional true} :string]])

(schema/register! ::session-summary
                  [:map
                   [::db-name ::db-name]
                   [::backend ::backend]
                   [::path {:optional true} ::path]])

(schema/register! ::list-sessions-request [:map])
(schema/register! ::list-sessions-response
                  [:map [::sessions [:vector ::session-summary]]])

(schema/register! ::get-conn-request [:map [::db-name ::db-name]])
(schema/register! ::get-conn-response
                  [:map [::conn {:optional true} ::conn]])

;; On-ensure-db hook entries. `::hook-key` is the registration identity —
;; re-registering the same key REPLACES the fn in place (idempotent across
;; ns reloads, no defonce guard needed at call sites). The key doubles as
;; the conventional `d/listen!` key the hook installs (`::raw-broadcast`,
;; `:seon.server.boot/reactive`, ...).
(schema/register! ::hook-key :qualified-keyword)
(schema/register! ::hook-fn [:fn fn?])
(schema/register! ::hook-entry
                  [:map
                   [::hook-key ::hook-key]
                   [::hook-fn ::hook-fn]])

(schema/register! ::register-on-ensure-db-hook!-request ::hook-entry)
(schema/register! ::register-on-ensure-db-hook!-response
                  [:map [::hook-count :int]])

;; `db-name` is a keyword for registry-opened conns, but the wire-server's
;; ambient conn calls `run-on-ensure-db-hooks!` with its STRING name
;; (`wire/-main`). Spec'd as reality until that mismatch is unified.
(schema/register! ::hook-db-name [:or ::db-name :string])

;; The ONE snapshot shape (test seam) — registry map + agent map + the
;; on-ensure-db hook vector, all captured together. In-memory only (it
;; holds live conns/fns); never persisted, so there is no legacy on-disk
;; shape to migrate — the old bare-map / hookless reader branches are gone
;; (registry M22).
(schema/register! ::snapshot
                  [:map
                   [::registry :map]
                   [::agents :map]
                   [::hooks [:vector ::hook-entry]]])

(schema/register! ::snapshot-registry-request [:map])
(schema/register! ::snapshot-registry-response [:map [::snapshot ::snapshot]])

(schema/register! ::restore-registry-request [:map [::snapshot ::snapshot]])
(schema/register! ::restore-registry-response [:map [::restored? :boolean]])

;; Shared agent-id shape. Platform registers it ONCE here (during the
;; session→registry rename); the reactive engine and any other consumer
;; REFERENCE `:seon.agent/id` rather than re-registering. It is the
;; agent entity's identity attr and the registry's `{agent-id → db-name}`
;; key. A given agent joins exactly one cluster; a cluster has N agents.
;; See clusters-and-multi-db-wiring §"What to build" item 1 +
;; reactive-interface-platform-review coordination item 1.
(schema/register! :seon.agent/id [:string {:min 1 :seon.db/identity true}])

(schema/register! ::register-agent!-request
                  [:map
                   [:seon.agent/id :seon.agent/id]
                   [::db-name ::db-name]])

(schema/register! ::register-agent!-response
                  [:map [::registered? :boolean]])

(schema/register! ::unregister-agent!-request
                  [:map [:seon.agent/id :seon.agent/id]])

(schema/register! ::unregister-agent!-response
                  [:map [::unregistered? :boolean]])

(schema/register! ::resolve-agent-request
                  [:map [:seon.agent/id :seon.agent/id]])

(schema/register! ::resolve-agent-response
                  [:map
                   [::db-name {:optional true} ::db-name]
                   [::conn {:optional true} ::conn]])

(schema/register! ::list-agents-request [:map])
(schema/register! ::list-agents-response
                  [:map [::agents [:vector [:map
                                            [:seon.agent/id :seon.agent/id]
                                            [::db-name ::db-name]]]]])

;; --- Conn resolution (wire-server per-request routing) ---------------------
;;
;; The wire envelope optionally carries `agent-id` and/or `db-name`. The
;; request-server resolves the conn here BEFORE calling handle-op. Resolution
;; order: agent-id (→ db-name → conn), then explicit db-name (→ conn). On
;; success → {::conn ::db-name}; on failure (unknown agent-id/db-name) →
;; {::error-kind "not-found" ::error <msg>}; on neither key present →
;; {::unresolved? true} so the caller can fall back to its ambient conn
;; (single-DB back-compat).
(schema/register! ::resolve-conn-request
                  [:map
                   [:seon.agent/id {:optional true} :seon.agent/id]
                   [::db-name {:optional true} ::db-name]])

(schema/register! ::resolve-conn-response
                  [:map
                   [::conn {:optional true} ::conn]
                   [::db-name {:optional true} ::db-name]
                   [::unresolved? {:optional true} :boolean]
                   [::error-kind {:optional true} :string]
                   [::error {:optional true} :string]])

;;; --- Registry --------------------------------------------------------------

(defonce ^:private !registry
  ;; {db-name -> entry-map}
  (atom {}))

(defonce ^:private !agents
  ;; {agent-id -> db-name}
  ;;
  ;; A given agent joins exactly one session; a session has N agents.
  ;; This atom is the agent-id → db-name index. Looking up a conn for
  ;; an agent is `(get @!registry (get @!agents agent-id))`.
  (atom {}))

(defn- summary
  "Public view of an entry, sans live conn."
  [db-name {::keys [backend path]}]
  (cond-> {::db-name db-name
           ::backend backend}
    path (assoc ::path path)))

(defn- create-entry!
  "Build a datahike cfg, ensure the db exists, connect, return the
   new entry map. Side-effecting; called under the swap! winner's
   thread only."
  [db-name backend path]
  (let [cfg-req (cond-> {:seon.server.store/db-name db-name
                         :seon.server.store/backend backend}
                  path (assoc :seon.server.store/path path))
        cfg     (store/config-for cfg-req)
        ;; For file/sqlite, ensure the parent dir exists on disk
        ;; before create-database. :memory has no on-disk path.
        store-path (or (get-in cfg [:store :path])
                       (get-in cfg [:store :dbname]))]
    (when store-path
      (store/ensure-parent-dir! {:seon.server.store/path store-path}))
    (when-not (d/database-exists? cfg)
      (d/create-database cfg))
    (let [conn (d/connect (id/allocation-connect-config cfg))]
      (id/assert-allocation-writer! conn)
      (cond-> {::conn conn
               ::backend backend}
        store-path (assoc ::path store-path)))))

;;; --- On-ensure-db extension point ------------------------------------------
;;;
;;; A vector of `{::hook-key k ::hook-fn (fn [conn db-name])}` entries invoked
;;; by `ensure-db!` exactly once per newly-opened conn, after `d/connect` and
;;; before the entry is handed back. The wire-server registers its
;;; `::raw-broadcast` `d/listen!` here; the reactive engine registers its
;;; `::reactive` `d/listen!` here too. This is the seam that lets `wire.clj`
;;; install broadcast without requiring `seon.server.reactive`, and lets the
;;; reactive track plug in WITHOUT `wire.clj` (or this ns) requiring it.
;;; Hooks fire on the create-winner's thread, under the `!registry` lock —
;;; idempotent ensures fire hooks ONCE.
;;;
;;; Registration is KEY-BASED and idempotent: re-registering an existing
;;; `::hook-key` replaces the fn in place. Call sites register at ns load with
;;; NO defonce guard — every reload of the registering ns re-runs the
;;; registration, so the hook set self-heals even if this ns is reloaded and
;;; the vector were ever lost (the 2026-06-10 hook-loss bug: defonce guards in
;;; wire/boot blocked re-registration until JVM restart).

(defonce ^:private !on-ensure-db-hooks
  ;; vector of ::hook-entry, in first-registration order
  (atom []))

(defn register-on-ensure-db-hook!
  "Register a `(fn [conn db-name])` under `::hook-key`, invoked once per
   newly-opened conn by `ensure-db!`. Idempotent by key: re-registering an
   existing key replaces the fn in place (same position). Hooks run in
   first-registration order. A hook typically calls
   `(d/listen! conn <key> <callback>)` under its own distinct key
   (`::raw-broadcast`, `::reactive`, ...) — datahike fires every
   distinct-keyed listener. Returns `{::hook-count n}`."
  {:malli/schema [:=> [:cat ::register-on-ensure-db-hook!-request]
                  ::register-on-ensure-db-hook!-response]}
  [{::keys [hook-key hook-fn]}]
  (let [entry  {::hook-key hook-key ::hook-fn hook-fn}
        hooks' (swap! !on-ensure-db-hooks
                      (fn [hooks]
                        (if (some #(= hook-key (::hook-key %)) hooks)
                          (mapv #(if (= hook-key (::hook-key %)) entry %) hooks)
                          (conj hooks entry))))]
    {::hook-count (count hooks')}))

(defn ^:no-doc reset-on-ensure-db-hooks!
  "Test seam: drop all on-ensure-db hooks."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (reset! !on-ensure-db-hooks [])
  nil)

(defn run-on-ensure-db-hooks!
  "Fire every registered on-ensure-db hook for `conn`/`db-name`. `ensure-db!`
   calls this once per newly-opened registry conn (the ambient conn included —
   `wire/-main` opens it through the registry). Test fixtures that build a
   conn outside the registry call it directly to install the same listeners.
   A hook exception is caught so one bad hook can't wedge the caller, but it
   is LOGGED LOUDLY (never swallowed) — a failed hook means the conn is
   missing its listener/schema and downstream ops will misbehave."
  {:malli/schema [:=> [:catn
                       [::conn ::conn]
                       [::hook-db-name ::hook-db-name]]
                  :nil]}
  [conn db-name]
  (doseq [{::keys [hook-key hook-fn]} @!on-ensure-db-hooks]
    (try
      (hook-fn conn db-name)
      (catch Throwable t
        (log/error t "on-ensure-db hook failed — conn is missing this hook's listener/schema"
                   {::hook-key hook-key ::db-name db-name}))))
  nil)

;;; --- Public API ------------------------------------------------------------

(defn ensure-db!
  "Idempotent. If `db-name` is already registered, return its entry
   unchanged. Otherwise create the db on disk (if needed), connect,
   and register. Concurrent callers on the same db-name converge:
   exactly one create-and-connect runs; all callers receive the
   same entry.

   `backend` defaults to `:file`. `path` is optional; the store
   layer derives a default from `db-name` when absent."
  {:malli/schema [:=> [:cat ::ensure-db!-request] ::ensure-db!-response]}
  [{::keys [db-name backend path] :or {backend :file}}]
  ;; First check without locking — fast path for the common "already
  ;; registered" case. Avoids paying the create-entry! cost in the
  ;; swap! retry closure.
  (or (get @!registry db-name)
      ;; Slow path. Serialize the create so concurrent callers don't
      ;; both `d/connect` against the same store. swap!'s retry
      ;; semantics would re-run create-entry! on contention, which is
      ;; both wasteful and potentially unsafe (two concurrent
      ;; create-database calls). Use `locking` instead — cheap because
      ;; the fast-path check above means this only runs once per
      ;; db-name's lifetime.
      (locking !registry
        (or (get @!registry db-name)
            (let [entry (create-entry! db-name backend path)]
              (swap! !registry assoc db-name entry)
              ;; Fire the extension point ONCE for this newly-opened conn —
              ;; inside the lock + create branch, so an idempotent re-ensure
              ;; (fast path above) never re-runs hooks. This is where the
              ;; wire-server's ::raw-broadcast and the reactive engine's
              ;; ::reactive listeners get installed.
              (run-on-ensure-db-hooks! (::conn entry) db-name)
              entry)))))

(defn remove-db!
  "Release the registered conn for `db-name` and drop the entry.
   Does NOT delete on-disk data — callers control persistence.
   Idempotent: removing an absent name returns `{::removed? false}`
   without throwing."
  {:malli/schema [:=> [:cat [:map [::db-name ::db-name]]]
                  ::remove-db!-response]}
  [{::keys [db-name]}]
  (locking !registry
    (if-let [{::keys [conn]} (get @!registry db-name)]
      (do
        (try (d/release conn) (catch Throwable _))
        (swap! !registry dissoc db-name)
        ;; Drop any agent mappings that pointed at this db-name to
        ;; avoid dangling agent-id → db-name references.
        (swap! !agents
               (fn [m] (into {} (remove (fn [[_ d]] (= d db-name)) m))))
        {::removed? true})
      {::removed? false})))

(defn delete-db!
  "Release `db-name`'s conn, drop its entry, and DELETE its database.

   The destructive half of the cluster lifecycle (`remove-db!` +
   `datahike.api/delete-database` over the entry's stored backend/path) —
   `bin/seon cluster destroy` reaches this via the wire `remove-db` op or
   the 7891 REPL; it is never agent-exposed. Idempotent: an absent name
   returns `{::removed? false ::deleted? false}`. A delete failure after
   a successful remove is reported in `::error`, never thrown — the
   supervisor's directory wipe is the backstop."
  {:malli/schema [:=> [:cat [:map [::db-name ::db-name]]]
                  ::delete-db!-response]}
  [{::keys [db-name]}]
  (locking !registry
    (if-let [{::keys [backend path]} (get @!registry db-name)]
      (let [{::keys [removed?]} (remove-db! {::db-name db-name})
            cfg (store/config-for
                 (cond-> {:seon.server.store/db-name db-name
                          :seon.server.store/backend backend}
                   path (assoc :seon.server.store/path path)))]
        (try
          (d/delete-database cfg)
          {::removed? removed? ::deleted? true}
          (catch Throwable t
            (log/warn t "delete-db!: delete-database failed after remove"
                      {::db-name db-name ::path path})
            {::removed? removed? ::deleted? false
             ::error (str (.getMessage t))})))
      {::removed? false ::deleted? false})))

(defn- fork-verify!
  "Connect `cfg`, prove the fork is whole, release. Returns its basis-t.

   Two checks, both real reads: the head sits exactly at the fork point
   (`:max-tx` == `at`; skipped for a head fork), and a full history
   `:eavt` scan completes — the scan forces every index node to load,
   so a torn konserve copy (source written to mid-copy) surfaces HERE
   as a throw instead of later inside the fork pod. Throws on failure;
   the caller deletes the torn target and retries once."
  [cfg at]
  (let [conn (d/connect (id/allocation-connect-config cfg))]
    (id/assert-allocation-writer! conn)
    (try
      (let [db (d/db conn)
            bt (:max-tx db)]
        (when (and at (not= (long at) (long bt)))
          (throw (ex-info "fork head is not at the fork point"
                          {::at at ::basis-t bt})))
        ;; Force-load the whole index (history includes retractions).
        (count (d/datoms (d/history db) :eavt))
        bt)
      (finally
        (try (d/release conn) (catch Throwable _))))))

(defn fork-db!
  "Fork a registered db at basis-t `::at` into a NEW independent store.

   Wraps `datahike.api/fork-database`: copies the source store at the
   konserve layer, points the fork's head at the commit whose `:max-tx`
   equals `::at` (absent = the current head), and mints the fork's own
   deterministic store identity (`store/config-for` on `::fork-name`).
   The fork is fully writable and byte-faithful as of the fork point —
   eids/tx-eids identical, so every stored basis-t (`:seon.error/at`,
   `rendered-as-of`) means the same thing inside it.

   Semantics of `::at` for error forensics: `:seon.error/at` is the
   basis-t at the CATCH site — the db value the failing code SAW. The
   error datom itself was recorded in a LATER tx, so it does NOT exist
   inside its own fork; the fork is the snapshot the failure arose from,
   not the later snapshot that already contains its record.

   The fork is NOT registered here — the fork pod's own boot `ensure-db`
   registers/connects it, the same one creation path `cluster create`
   uses, so the end state is indistinguishable from a normal cluster.
   `::path` defaults via the store layer (cluster callers pass
   `data/clusters/<fork-name>/store` explicitly).

   Copy-while-live: the source keeps taking writes during the copy
   (fork-point commits are immutable, so this is normally safe); the
   fork is VERIFIED after the copy (head at `::at` + a full history
   index scan) and re-forked once on a torn copy. Supervisor-facing
   (`bin/seon cluster fork` via the 7891 REPL) — never agent-exposed.
   Errors return as values: `{::forked? false ::error msg}`."
  {:malli/schema [:=> [:cat ::fork-db!-request] ::fork-db!-response]}
  [{::keys [db-name fork-name at path]}]
  (let [entry (get @!registry db-name)]
    (cond
      (nil? entry)
      {::forked? false
       ::error (str "source db-name not registered: " db-name
                    " — ensure-db! it first (a cluster's db registers when"
                    " its pod boots; the ambient cluster is always registered)")}

      (= db-name fork-name)
      {::forked? false ::error "fork-name must differ from the source db-name"}

      (contains? @!registry fork-name)
      {::forked? false
       ::error (str "fork-name already registered: " fork-name)}

      :else
      (let [{::keys [backend] src-path ::path} entry
            src-cfg (store/config-for
                     (cond-> {:seon.server.store/db-name db-name
                              :seon.server.store/backend backend}
                       src-path (assoc :seon.server.store/path src-path)))
            tgt-cfg (store/config-for
                     (cond-> {:seon.server.store/db-name fork-name
                              :seon.server.store/backend :file}
                       path (assoc :seon.server.store/path path)))
            store-path (get-in tgt-cfg [:store :path])
            fork-once! (fn []
                         (store/ensure-parent-dir!
                          {:seon.server.store/path store-path})
                         (d/fork-database src-cfg tgt-cfg
                                          (cond-> {} at (assoc :at at)))
                         (fork-verify! tgt-cfg at))]
        (try
          (if (d/database-exists? tgt-cfg)
            {::forked? false
             ::error (str "fork target store already exists: " store-path)}
            (let [bt (try
                       (fork-once!)
                       (catch Throwable t
                         ;; Torn copy (source written to mid-copy) or a
                         ;; transient store error — wipe the partial target
                         ;; and retry ONCE; a second failure surfaces.
                         (log/warn t "fork-db!: first fork attempt failed — retrying once"
                                   {::db-name db-name ::fork-name fork-name ::at at})
                         (try (d/delete-database tgt-cfg) (catch Throwable _))
                         (fork-once!)))]
              {::forked? true
               ::db-name fork-name
               ::basis-t (long bt)
               ::path store-path}))
          (catch Throwable t
            {::forked? false
             ::error (str "fork failed: " (.getMessage t)
                          " " (pr-str (ex-data t)))}))))))

(defn get-conn
  "Return `{::conn <conn>}` if `db-name` is registered, else `{}`.
   Does NOT auto-create — callers must `ensure-db!` first."
  {:malli/schema [:=> [:cat ::get-conn-request] ::get-conn-response]}
  [{::keys [db-name]}]
  (if-let [conn (some-> @!registry (get db-name) ::conn)]
    {::conn conn}
    {}))

(defn list-sessions
  "Return `{::sessions [...]}` — one summary per registered session.
   Live conns are NOT exposed (use `get-conn`). Order is unspecified."
  {:malli/schema [:=> [:cat ::list-sessions-request] ::list-sessions-response]}
  [{}]
  {::sessions (mapv (fn [[db-name entry]] (summary db-name entry))
                    @!registry)})

;;; --- Agent registry -------------------------------------------------------
;;;
;;; The agent registry maps `:seon.agent/<id>` strings (or any opaque
;;; agent-id) to a `db-name` in `!registry`. Used by
;;; `seon.session/with-agent` to bind the right conn for MCP eval routing.

(defn register-agent!
  "Bind `:seon.agent/id` to `::db-name`. The db-name must already be
   registered via `ensure-db!`; otherwise this throws. Idempotent —
   re-registering the same agent-id to the same db-name is a no-op."
  {:malli/schema [:=> [:cat ::register-agent!-request]
                  ::register-agent!-response]}
  [{:seon.agent/keys [id] ::keys [db-name]}]
  (when-not (contains? @!registry db-name)
    (throw (ex-info "Cannot register agent: db-name not in registry"
                    {:seon.agent/id id ::db-name db-name})))
  (swap! !agents assoc id db-name)
  {::registered? true})

(defn unregister-agent!
  "Drop the agent-id mapping. Idempotent — returns
   `{::unregistered? false}` if the agent was not registered."
  {:malli/schema [:=> [:cat ::unregister-agent!-request]
                  ::unregister-agent!-response]}
  [{:seon.agent/keys [id]}]
  (if (contains? @!agents id)
    (do (swap! !agents dissoc id)
        {::unregistered? true})
    {::unregistered? false}))

(defn resolve-agent
  "Return `{::db-name <name> ::conn <conn>}` for the given agent-id,
   or `{}` if unknown / the underlying cluster has been removed."
  {:malli/schema [:=> [:cat ::resolve-agent-request]
                  ::resolve-agent-response]}
  [{:seon.agent/keys [id]}]
  (if-let [db-name (get @!agents id)]
    (if-let [conn (some-> @!registry (get db-name) ::conn)]
      {::db-name db-name ::conn conn}
      {::db-name db-name})
    {}))

(defn list-agents
  "Return `{::agents [...]}` — one map per registered agent. Order
   unspecified."
  {:malli/schema [:=> [:cat ::list-agents-request] ::list-agents-response]}
  [{}]
  {::agents (mapv (fn [[id db-name]]
                    {:seon.agent/id id ::db-name db-name})
                  @!agents)})

(defn resolve-conn
  "Resolve a wire request's target conn from the registry. Resolution order:

   1. `:seon.agent/id` present → `{agent-id → db-name → conn}`. Unknown
      agent-id (or its cluster removed) → `{::error-kind \"not-found\" ...}`.
   2. else `::db-name` present → `{db-name → conn}`. Unknown db-name →
      `{::error-kind \"not-found\" ...}`.
   3. else neither key → `{::unresolved? true}` — the caller falls back to
      its single ambient conn (single-DB back-compat / degenerate cluster).

   Success → `{::conn <conn> ::db-name <name>}`."
  {:malli/schema [:=> [:cat ::resolve-conn-request] ::resolve-conn-response]}
  [{:seon.agent/keys [id] ::keys [db-name]}]
  (cond
    id
    (if-let [resolved-name (get @!agents id)]
      (if-let [conn (some-> @!registry (get resolved-name) ::conn)]
        {::conn conn ::db-name resolved-name}
        {::error-kind "not-found"
         ::error (str "agent " id " maps to db-name " resolved-name
                      " which is not registered")})
      {::error-kind "not-found"
       ::error (str "unknown agent-id: " id)})

    db-name
    (if-let [conn (some-> @!registry (get db-name) ::conn)]
      {::conn conn ::db-name db-name}
      {::error-kind "not-found"
       ::error (str "unknown db-name: " db-name)})

    :else
    {::unresolved? true}))

;;; --- Test seam -------------------------------------------------------------

(defn ^:no-doc snapshot-registry
  "Test helper: capture the current registry + agent map + on-ensure-db
   hooks for restoration. Used by test fixtures to isolate test state.
   Hooks are included so a fixture that resets them for isolation puts
   the live JVM's hooks (::raw-broadcast, ::reactive, ...) back on
   restore instead of stranding an empty hook vector."
  {:malli/schema [:=> [:cat ::snapshot-registry-request] ::snapshot-registry-response]}
  [{}]
  {::snapshot {::registry @!registry
               ::agents @!agents
               ::hooks @!on-ensure-db-hooks}})

(defn ^:no-doc restore-registry!
  "Test helper: replace registry + agents + hooks with `::snapshot`.

   Releases any conns that were added since the snapshot was taken, then
   restores all three atoms — a fixture that resets hooks for isolation
   puts the live JVM's hooks (::raw-broadcast, ::reactive, ...) back.
   Speaks the ONE `::snapshot` shape `snapshot-registry` produces (M22:
   the legacy bare-map and hookless reader branches are deleted —
   snapshots are in-memory values, never persisted, so no old shape can
   arrive from disk)."
  {:malli/schema [:=> [:cat ::restore-registry-request] ::restore-registry-response]}
  [{::keys [snapshot]}]
  (locking !registry
    (let [{::keys [registry agents hooks]} snapshot
          current     @!registry
          extra-names (set/difference (set (keys current))
                                      (set (keys registry)))]
      (doseq [n extra-names]
        (when-let [{::keys [conn]} (get current n)]
          (try (d/release conn) (catch Throwable _))))
      (reset! !registry registry)
      (reset! !agents agents)
      (reset! !on-ensure-db-hooks hooks)
      {::restored? true})))
