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

   Connection setup is an explicit dependency of `ensure-db!`, not a global
   callback registry. The writer assembles one fixed initializer at boot and
   passes it on every open. A failed initializer releases the new connection
   and leaves no half-initialized registry entry."
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
                   [::path {:optional true} ::path]
                   [::initialize-connection! [:fn fn?]]])

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

;; The snapshot is an in-memory test seam over opaque connection resources.
;; Connection initialization is immutable boot composition and therefore is
;; deliberately not snapshot state.
(schema/register! ::snapshot
                  [:map
                   [::registry :map]])

(schema/register! ::snapshot-registry-request [:map])
(schema/register! ::snapshot-registry-response [:map [::snapshot ::snapshot]])

(schema/register! ::restore-registry-request [:map [::snapshot ::snapshot]])
(schema/register! ::restore-registry-response [:map [::restored? :boolean]])

;; --- Conn resolution (wire-server per-request routing) ---------------------
;;
;; The wire envelope optionally carries `db-name`. The request-server resolves
;; the conn here BEFORE calling handle-op. On success → {::conn ::db-name}; on
;; failure (unknown db-name) → {::error-kind "not-found" ::error <msg>}; when
;; absent → {::unresolved? true} so the caller can use its ambient conn.
(schema/register! ::resolve-conn-request
                  [:map
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

;;; --- Public API ------------------------------------------------------------

(defn ensure-db!
  "Idempotent. If `db-name` is already registered, return its entry
   unchanged. Otherwise create the db on disk (if needed), connect,
   and register. Concurrent callers on the same db-name converge:
   exactly one create-and-connect runs; all callers receive the
   same entry.

   `backend` defaults to `:file`. `path` is optional; the store
   layer derives a default from `db-name` when absent.

   `::initialize-connection!` is the writer's fixed boot-composed initializer.
   It runs exactly once for a newly opened connection, before publication. A
   failure releases the connection and is rethrown; no broken entry survives."
  {:malli/schema [:=> [:cat ::ensure-db!-request] ::ensure-db!-response]}
  [{::keys [db-name backend path initialize-connection!]
    :or {backend :file}}]
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
            (let [entry (create-entry! db-name backend path)
                  conn  (::conn entry)]
              (try
                (initialize-connection! conn db-name)
                (swap! !registry assoc db-name entry)
                entry
                (catch Throwable throwable
                  (try (d/release conn) (catch Throwable _))
                  (throw throwable))))))))

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

(defn resolve-conn
  "Resolve a wire request's target conn by `::db-name`.

   A registered name returns `{::conn <conn> ::db-name <name>}`. An unknown
   name returns a typed `not-found` value. When the name is absent, return
   `{::unresolved? true}` so the caller can use its ambient conn."
  {:malli/schema [:=> [:cat ::resolve-conn-request] ::resolve-conn-response]}
  [{::keys [db-name]}]
  (cond
    db-name
    (if-let [conn (some-> @!registry (get db-name) ::conn)]
      {::conn conn ::db-name db-name}
      {::error-kind "not-found"
       ::error (str "unknown db-name: " db-name)})

    :else
    {::unresolved? true}))

;;; --- Test seam -------------------------------------------------------------

(defn ^:no-doc snapshot-registry
  "Capture the live connection registry for isolated test restoration."
  {:malli/schema [:=> [:cat ::snapshot-registry-request] ::snapshot-registry-response]}
  [{}]
  {::snapshot {::registry @!registry}})

(defn ^:no-doc restore-registry!
  "Replace the connection registry from an isolated test snapshot.

   Releases connections added since capture, then restores the exact opaque
   registry entries. Initializer functions are boot dependencies, not registry
   state, so they are neither captured nor restored."
  {:malli/schema [:=> [:cat ::restore-registry-request] ::restore-registry-response]}
  [{::keys [snapshot]}]
  (locking !registry
    (let [{::keys [registry]} snapshot
          current     @!registry
          extra-names (set/difference (set (keys current))
                                      (set (keys registry)))]
      (doseq [n extra-names]
        (when-let [{::keys [conn]} (get current n)]
          (try (d/release conn) (catch Throwable _))))
      (reset! !registry registry)
      {::restored? true})))
