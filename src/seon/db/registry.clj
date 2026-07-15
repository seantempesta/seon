(ns seon.db.registry
  "The database server's live connection registry.

   The process-local atom maps `{database-name -> entry}`
   where each entry retains the live connection, stable
   `{database-id, branch}` attachment, backend, and optional path. The current
   coordinate is always derived from the live Datahike value.

   One database-server JVM hosts every cluster database. A database-name is a
   logical route (`:default`, `:acme`, or a branch route), not physical
   identity. The writer resolves every database-scoped request explicitly from
   this map. There is no ambient fallback or second open path.

   ## Idempotent semantics

   Logical names and attachments form a bijection. `ensure-database!` on an
   existing database-name returns the same connection, while a second name for
   its attachment is rejected. Main `:db` may create a database. Non-main
   branches are open-only and must be present in Datahike's durable branch
   roster before connect. `release-database!` on an absent name is a no-op returning
   `{::released? false}`. A failed release retains the registered identity and
   its failure; the same process never reclassifies unproved exclusive release
   as success. `delete-database!` additionally deletes the durable database.
   Concurrent ensures on the same name race exactly once —
   losers see the winner's conn.

   Connection setup is an explicit dependency of `ensure-database!`, not a global
   callback registry. The writer assembles one fixed initializer at boot and
   passes it on every open. A failed initializer releases the new connection
   and leaves no half-initialized registry entry."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.db.coordinate :as coordinate]
            [seon.db.id :as id]
            [seon.schema :as schema]
            [seon.db.backend :as backend]
            [taoensso.timbre :as log]))

;;; --- Schemas ---------------------------------------------------------------

(schema/register! ::database-name :seon.db.backend/database-name)
(schema/register! ::backend :seon.db.backend/backend)
(schema/register! ::path :seon.db.backend/path)
(schema/register! ::initial-tx :seon.db.backend/initial-tx)
(schema/register! ::attachment ::coordinate/attachment)
(schema/register! ::coordinate ::coordinate/coordinate)
(schema/register! ::release-error :string)

;; A live conn is an opaque clojure.lang.IAtom2 (datahike connection
;; type). We don't constrain its shape; the registry hands it out as-is.
(schema/register! ::conn 'some?)

(schema/register! ::entry
                  [:map
                   [::conn ::conn]
                   [::attachment ::attachment]
                   [::backend ::backend]
                   [::path {:optional true} ::path]
                   [::release-error {:optional true} ::release-error]])

(schema/register! ::ensure-database!-request
                  [:map
                   [::database-name ::database-name]
                   [::backend {:optional true} ::backend]
                   [::path {:optional true} ::path]
                   [::attachment {:optional true} ::attachment]
                   [::initial-tx {:optional true} ::initial-tx]
                   [::initialize-connection! 'fn?]])

(schema/register! ::entry-view
                  [:map
                   [::database-name ::database-name]
                   [::conn ::conn]
                   [::attachment ::attachment]
                   [::coordinate ::coordinate]
                   [::backend ::backend]
                   [::path {:optional true} ::path]
                   [::release-error {:optional true} ::release-error]])

(schema/register! ::ensure-database!-response ::entry-view)

(schema/register! ::release-database!-response
                  [:map
                   [::database-name ::database-name]
                   [::released? :boolean]
                   [::release-error {:optional true} ::release-error]])

(schema/register! ::delete-database!-response
                  [:map
                   [::released? :boolean]
                   [::deleted? :boolean]
                   [::error {:optional true} :string]])

;; Legacy physical-copy fork point: a transaction id inside the selected
;; source database. Public historical identities must be resolved complete
;; coordinates before this third-party boundary is reached.
(schema/register! ::at :int)

(schema/register! ::fork-database!-request
                  [:map
                   [::database-name ::database-name]
                   [::fork-database-name ::database-name]
                   [::at {:optional true} ::at]
                   [::path {:optional true} ::path]])

(schema/register! ::fork-database!-response
                  [:map
                   [::forked? :boolean]
                   [::database-name {:optional true} ::database-name]
                   [::basis-t {:optional true} :int]
                   [::path {:optional true} ::path]
                   [::error {:optional true} :string]])

(schema/register! ::database-summary
                  [:map
                   [::database-name ::database-name]
                   [::attachment ::attachment]
                   [::coordinate ::coordinate]
                   [::backend ::backend]
                   [::path {:optional true} ::path]
                   [::release-error {:optional true} ::release-error]])

(schema/register! ::list-databases-request [:map])
(schema/register! ::list-databases-response
                  [:map [::databases [:vector ::database-summary]]])

(schema/register! ::connection-request [:map [::database-name ::database-name]])
(schema/register! ::connection-response
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

;; --- Explicit per-request routing ------------------------------------------
(schema/register! ::resolve-connection-request
                  [:map [::database-name ::database-name]])

(schema/register! ::resolve-connection-response
                  [:map
                   [::conn {:optional true} ::conn]
                   [::database-name {:optional true} ::database-name]
                   [::attachment {:optional true} ::attachment]
                   [::coordinate {:optional true} ::coordinate]
                   [::error-kind {:optional true}
                    [:enum :seon.db.registry.error/not-found]]
                   [::error {:optional true} :string]])

;;; --- Registry --------------------------------------------------------------

(defonce ^:private !registry
  ;; {database-name -> entry-map}
  (atom {}))

(defn- current-coordinate
  [{::keys [conn]}]
  (coordinate/resolved (d/db conn)))

(defn- entry-view
  [database-name entry]
  (assoc entry
         ::database-name database-name
         ::coordinate (current-coordinate entry)))

(defn- summary
  "Public view of an entry, sans live conn."
  [database-name {::keys [attachment backend path release-error] :as entry}]
  (cond-> {::database-name database-name
           ::attachment attachment
           ::coordinate (current-coordinate entry)
           ::backend backend}
    path (assoc ::path path)
    release-error (assoc ::release-error release-error)))

(defn- backend-request
  [{::keys [database-name backend path attachment initial-tx]}]
  (cond-> {::backend/database-name database-name
           ::backend/backend backend}
    path (assoc ::backend/path path)
    attachment (assoc ::coordinate/attachment attachment)
    (seq initial-tx) (assoc ::backend/initial-tx initial-tx)))

(defn- fail-attachment!
  [message data]
  (throw
   (ex-info message
            (assoc data :seon.error/kind
                   :seon.db.registry.error/attachment-conflict))))

(defn- validate-route-bijection!
  [registry database-name attachment backend-kind path]
  (when-let [[other-name _]
             (some (fn [[registered-name entry]]
                     (when (and (not= registered-name database-name)
                                (= attachment (::attachment entry)))
                       [registered-name entry]))
                   registry)]
    (fail-attachment!
     "The requested database attachment already has a logical route."
     {::database-name database-name
      ::attachment attachment
      ::existing-database-name other-name}))
  (let [database-id (::coordinate/database-id attachment)]
    (doseq [[registered-name entry] registry
            :let [registered-attachment (::attachment entry)
                  registered-id (::coordinate/database-id registered-attachment)
                  registered-backend (::backend entry)
                  registered-path (::path entry)]]
      (when (and (= database-id registered-id)
                 (or (not= backend-kind registered-backend)
                     (not= path registered-path)))
        (fail-attachment!
         "Routes for one physical database disagree on backend configuration."
         {::database-name database-name
          ::attachment attachment
          ::backend backend-kind
          ::path path
          ::existing-database-name registered-name
          ::existing-backend registered-backend
          ::existing-path registered-path}))
      (when (and (= :file backend-kind)
                 (= :file registered-backend)
                 (= path registered-path)
                 (not= database-id registered-id))
        (fail-attachment!
         "One durable backend path cannot name two physical databases."
         {::database-name database-name
          ::attachment attachment
          ::path path
          ::existing-database-name registered-name
          ::existing-attachment registered-attachment})))))

(defn- validate-existing-route!
  [database-name entry attachment backend-kind path]
  (when-not (= [attachment backend-kind path]
               [(::attachment entry) (::backend entry) (::path entry)])
    (fail-attachment!
     "A logical database name cannot change its registered attachment."
     {::database-name database-name
      ::attachment attachment
      ::backend backend-kind
      ::path path
      ::existing-attachment (::attachment entry)
      ::existing-backend (::backend entry)
      ::existing-path (::path entry)}))
  (entry-view database-name entry))

(defn- branch-source
  [registry attachment]
  (let [database-id (::coordinate/database-id attachment)]
    (some (fn [[_ entry]]
            (when (= database-id
                     (::coordinate/database-id (::attachment entry)))
              (::conn entry)))
          registry)))

(defn- open-entry!
  "Open and validate one exact Datahike attachment."
  [registry database-name backend-kind path attachment initial-tx]
  (let [request (backend-request
                 {::database-name database-name
                  ::backend backend-kind
                  ::path path
                  ::attachment attachment
                  ::initial-tx initial-tx})
        cfg (backend/datahike-config request)
        branch (::coordinate/branch attachment)]
    (if (= :db branch)
      (do
        (when path
          (backend/ensure-parent-dir! {::backend/path path}))
        (when-not (d/database-exists? cfg)
          (d/create-database cfg)))
      (let [source (branch-source registry attachment)]
        (when-not source
          (fail-attachment!
           "A non-main branch requires a registered physical database."
           {::database-name database-name ::attachment attachment}))
        (when-not (contains? (d/branches source) branch)
          (fail-attachment!
           "The requested branch is absent from Datahike's durable branch roster."
           {::database-name database-name
            ::attachment attachment
            ::available-branches (d/branches source)}))))
    (let [conn (d/connect (id/allocation-connect-config cfg))]
      (try
        (id/assert-allocation-writer! conn)
        (let [actual (coordinate/attachment (coordinate/resolved (d/db conn)))]
          (when-not (= attachment actual)
            (fail-attachment!
             "Datahike connected a different database attachment."
             {::database-name database-name
              ::attachment attachment
              ::actual-attachment actual})))
        (cond-> {::conn conn
                 ::attachment attachment
                 ::backend backend-kind}
          path (assoc ::path path))
        (catch Throwable throwable
          (try (d/release conn) (catch Throwable _))
          (throw throwable))))))

;;; --- Public API ------------------------------------------------------------

(defn ensure-database!
  "Ensure one logical database route.

   If `database-name` is already registered, return its current view
   unchanged. Otherwise create the db on disk (if needed), connect,
   and register. Concurrent callers on the same database-name converge:
   exactly one create-and-connect runs; all callers receive the
   same entry.

   `backend` defaults to `:file`. `path` is optional; the backend adapter
   derives a default from `database-name` when absent.

   `::initialize-connection!` is the writer's fixed boot-composed initializer.
   It runs exactly once for a newly opened connection, before publication. A
   failure releases the connection and is rethrown; no broken entry survives."
  {:malli/schema [:=> [:cat ::ensure-database!-request] ::ensure-database!-response]}
  [{::keys [database-name backend path attachment initial-tx
            initialize-connection!]
    :or {backend :file}}]
  (let [request (backend-request
                 {::database-name database-name
                  ::backend backend
                  ::path path
                  ::attachment attachment
                  ::initial-tx initial-tx})
        facts (backend/backend-facts request)
        attachment* (::coordinate/attachment facts)
        backend-path (::backend/path facts)]
    (if-let [entry (get @!registry database-name)]
      (validate-existing-route! database-name entry attachment* backend backend-path)
      (locking !registry
        (if-let [entry (get @!registry database-name)]
          (validate-existing-route! database-name entry attachment* backend backend-path)
          (let [registry @!registry]
            (validate-route-bijection!
             registry database-name attachment* backend backend-path)
            (let [entry (open-entry! registry database-name backend backend-path
                                     attachment* initial-tx)
                  conn (::conn entry)]
              (try
                (initialize-connection! conn database-name)
                (swap! !registry assoc database-name entry)
                (entry-view database-name entry)
                (catch Throwable throwable
                  (try (d/release conn) (catch Throwable _))
                  (throw throwable))))))))))

(defn release-database!
  "Release the registered conn for `database-name` and drop the entry.
   Does NOT delete on-disk data — callers control persistence.
   Idempotent: releasing an absent name returns `{::released? false}`
   without throwing.

   A Datahike release failure is terminal for this process: retain the entry
   and error so lifecycle callers cannot mistake an already-invalid connection
   for proved exclusive release on retry."
  {:malli/schema [:=> [:cat [:map [::database-name ::database-name]]]
                  ::release-database!-response]}
  [{::keys [database-name]}]
  (locking !registry
    (if-let [{::keys [conn release-error]} (get @!registry database-name)]
      (if release-error
        {::database-name database-name
         ::released? false
         ::release-error release-error}
        (try
          (d/release conn)
          (swap! !registry dissoc database-name)
          {::database-name database-name ::released? true}
          (catch Throwable throwable
            (let [message (.toString throwable)]
              (log/error throwable "release-database!: connection release failed"
                         {::database-name database-name})
              (swap! !registry assoc-in [database-name ::release-error] message)
              {::database-name database-name
               ::released? false
               ::release-error message}))))
      {::database-name database-name ::released? false})))

(defn delete-database!
  "Release `database-name`'s conn, drop its entry, and DELETE its database.

   The destructive half of the cluster lifecycle (`release-database!` +
   `datahike.api/delete-database` over the entry's stored backend/path) —
   `bin/seon cluster destroy` reaches this through the temporary loopback REPL;
   it is never agent-exposed. Idempotent: an absent name returns
   `{::released? false ::deleted? false}`. A delete failure after
   a successful remove is reported in `::error`, never thrown — the
   supervisor's directory wipe is the backstop."
  {:malli/schema [:=> [:cat [:map [::database-name ::database-name]]]
                  ::delete-database!-response]}
  [{::keys [database-name]}]
  (locking !registry
    (if-let [{::keys [attachment backend path]}
             (get @!registry database-name)]
      (cond
        (not= :db (::coordinate/branch attachment))
        {::released? false ::deleted? false
         ::error "delete-database! only accepts the physical database's :db route"}

        (some (fn [[registered-name entry]]
                (and (not= registered-name database-name)
                     (= (::coordinate/database-id attachment)
                        (::coordinate/database-id (::attachment entry)))))
              @!registry)
        {::released? false ::deleted? false
         ::error "delete-database! requires every branch route to be released first"}

        :else
        (let [{::keys [released? release-error]}
              (release-database! {::database-name database-name})]
          (if-not released?
            {::released? false ::deleted? false
             ::error (or release-error "database connection was not released")}
            (let [cfg (backend/datahike-config
                       (cond-> {::backend/database-name database-name
                                ::backend/backend backend
                                ::coordinate/attachment attachment}
                         path (assoc ::backend/path path)))]
              (try
                (d/delete-database cfg)
                {::released? true ::deleted? true}
                (catch Throwable t
                  (log/warn t "delete-database!: delete-database failed after remove"
                            {::database-name database-name ::path path})
                  {::released? true ::deleted? false
                   ::error (str (.getMessage t))}))))))
      {::released? false ::deleted? false})))

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

(defn fork-database!
  "Fork a registered database at basis-t `::at` into an independent database.

   Wraps `datahike.api/fork-database`: copies the source backend at the
   Konserve layer, points the fork's head at the commit whose `:max-tx`
   equals `::at` (absent = the current head), and mints the fork's own
   deterministic database identity (`backend/datahike-config` on
   `::fork-database-name`).
   The fork is fully writable and byte-faithful as of the fork point —
   eids/tx-eids are identical. A bare stored basis-t is not a portable
   identity across that boundary; callers must resolve and carry a complete
   database coordinate before selecting or reconstructing history.

   The selected point predates any later forensic/error-record datom; the
   fork is the snapshot the failure arose from, not the later snapshot that
   already contains its record.

   The fork is NOT registered here — the fork pod's own boot database ensure
   registers/connects it, the same one creation path `cluster create`
   uses, so the end state is indistinguishable from a normal cluster.
   `::path` defaults via the backend adapter (cluster callers pass
   `data/clusters/<fork-database-name>/db` explicitly).

   Copy-while-live: the source keeps taking writes during the copy
   (fork-point commits are immutable, so this is normally safe); the
   fork is VERIFIED after the copy (head at `::at` + a full history
   index scan) and re-forked once on a torn copy. Operator-facing
   (`bin/seon cluster fork` via the 7891 REPL) — never agent-exposed.
   Errors return as values: `{::forked? false ::error msg}`."
  {:malli/schema [:=> [:cat ::fork-database!-request] ::fork-database!-response]}
  [{::keys [database-name fork-database-name at path]}]
  (let [entry (get @!registry database-name)]
    (cond
      (nil? entry)
      {::forked? false
       ::error (str "source database-name not registered: " database-name
                    " — ensure-database! it first (a cluster's db registers when"
                    " its pod boots; the ambient cluster is always registered)")}

      (= database-name fork-database-name)
      {::forked? false ::error "fork-database-name must differ from the source database-name"}

      (contains? @!registry fork-database-name)
      {::forked? false
       ::error (str "fork-database-name already registered: " fork-database-name)}

      :else
      (let [{::keys [attachment backend] src-path ::path} entry
            src-cfg (backend/datahike-config
                     (cond-> {::backend/database-name database-name
                              ::backend/backend backend
                              ::coordinate/attachment attachment}
                       src-path (assoc ::backend/path src-path)))
            tgt-cfg (backend/datahike-config
                     (cond-> {::backend/database-name fork-database-name
                              ::backend/backend :file}
                       path (assoc ::backend/path path)))
            backend-path (::backend/path
                          (backend/backend-facts
                           (cond-> {::backend/database-name fork-database-name
                                    ::backend/backend :file}
                             path (assoc ::backend/path path))))
            fork-once! (fn []
                         (backend/ensure-parent-dir!
                          {:seon.db.backend/path backend-path})
                         (d/fork-database src-cfg tgt-cfg
                                          (cond-> {} at (assoc :at at)))
                         (fork-verify! tgt-cfg at))]
        (try
          (if (d/database-exists? tgt-cfg)
            {::forked? false
             ::error (str "fork target database already exists: " backend-path)}
            (let [bt (try
                       (fork-once!)
                       (catch Throwable t
                         ;; Torn copy (source written to mid-copy) or a
                         ;; transient store error — wipe the partial target
                         ;; and retry ONCE; a second failure surfaces.
                         (log/warn t "fork-database!: first fork attempt failed — retrying once"
                                   {::database-name database-name ::fork-database-name fork-database-name ::at at})
                         (try (d/delete-database tgt-cfg) (catch Throwable _))
                         (fork-once!)))]
              {::forked? true
               ::database-name fork-database-name
               ::basis-t (long bt)
               ::path backend-path}))
          (catch Throwable t
            {::forked? false
             ::error (str "fork failed: " (.getMessage t)
                          " " (pr-str (ex-data t)))}))))))

(defn lookup-connection
  "Return `{::conn <conn>}` if `database-name` is registered, else `{}`.
   Does NOT auto-create — callers must `ensure-database!` first."
  {:malli/schema [:=> [:cat ::connection-request] ::connection-response]}
  [{::keys [database-name]}]
  (if-let [conn (some-> @!registry (get database-name) ::conn)]
    {::conn conn}
    {}))

(defn list-databases
  "Return `{::databases [...]}` — one summary per registered database.
   Live conns are NOT exposed (use `lookup-connection`). Order is unspecified."
  {:malli/schema [:=> [:cat ::list-databases-request] ::list-databases-response]}
  [{}]
  {::databases (mapv (fn [[database-name entry]] (summary database-name entry))
                    @!registry)})

(defn resolve-connection
  "Resolve one explicitly named database connection.

   A registered name returns its stable attachment and freshly derived current
   coordinate with the live connection. An unknown name returns a typed
   not-found value."
  {:malli/schema [:=> [:cat ::resolve-connection-request] ::resolve-connection-response]}
  [{::keys [database-name]}]
  (if-let [entry (get @!registry database-name)]
    (entry-view database-name entry)
    {::error-kind :seon.db.registry.error/not-found
     ::error (str "unknown database-name: " database-name)}))

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
