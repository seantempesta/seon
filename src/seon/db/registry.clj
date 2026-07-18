(ns seon.db.registry
  "The database server's live connection registry.

   The process-local atom maps `{database-name -> entry}`
   where each entry retains the live connection, stable
   Datahike `[store-id branch]` connection ID, backend, and optional path. The
   current
   branch-head is always derived from the live Datahike value.

   One database-server JVM hosts every cluster database. A database-name is a
   logical route (`:default`, `:acme`, or a branch route), not physical
   identity. The writer resolves every database-scoped request explicitly from
   this map. There is no ambient fallback or second open path.

   ## Idempotent semantics

   Logical names and connection-ids form a bijection. `ensure-database!` on an
   existing database-name returns the same connection, while a second name for
   its connection-id is rejected. Main `:db` may create a database. Non-main
   branches are open-only and must be present in Datahike's durable branch
   roster before connect. `release-database!` on an absent name is a no-op returning
   `{::released? false}`. A failed release retains the registered identity and
   its failure; the same process never reclassifies unproved exclusive release
   as success. `delete-database!` additionally deletes the durable database.
   Concurrent ensures on the same name race exactly once — losers see the
   winner's conn. Persistent transport connections acquire exact membership
   here while Datahike remains the reference-count authority. The first live
   connection may take the administrative ensure reference; sibling
   connections call Datahike connect once, duplicate acquire/close is a no-op,
   and only final release drains and removes the entry.

   Connection setup is an explicit dependency of `ensure-database!`, not a global
   callback registry. The writer assembles one fixed initializer at boot and
   passes it on every open. The initializer receives one request describing
   the exact connection-id and whether the open is main or branch-observational.
   A failed initializer releases the new connection and leaves no
   half-initialized registry entry."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [datahike.index.audit :as index-audit]
            [konserve.core :as k]
            [seon.db.branch :as branch]
            [seon.db.id :as id]
            [seon.db.restore :as db.restore]
            [seon.schema :as schema]
            [seon.db.backend :as backend]
            [taoensso.timbre :as log]))

;;; --- Schemas ---------------------------------------------------------------

(schema/register! ::database-name :seon.db.backend/database-name)
(schema/register! ::source-database-name ::database-name)
(schema/register! ::target-database-name ::database-name)
(schema/register! ::backend :seon.db.backend/backend)
(schema/register! ::path :seon.db.backend/path)
(schema/register! ::initial-tx :seon.db.backend/initial-tx)
(schema/register! ::connection-id ::branch/connection-id)
(schema/register! ::branch-head ::branch/head)
(schema/register! ::release-error :string)
(schema/register! ::initialization-error :string)
(schema/register! ::transport-connection 'some?)
(schema/register! ::transport-connections [:set ::transport-connection])
(schema/register!
 ::acquired-transport-connections-request
 [:map {:closed true}
  [::database-name ::database-name]
  [::connection-id ::connection-id]])
(schema/register!
 ::acquired-transport-connections-response
 [:map {:closed true}
  [::transport-connections ::transport-connections]])
(schema/register! ::ensured? :boolean)
(schema/register! ::acquired? :boolean)
(schema/register! ::release-completion 'some?)
(schema/register! ::drain! 'fn?)
(schema/register! ::entry-state
                  [:enum :seon.db.registry.entry/ready
                   :seon.db.registry.entry/releasing
                   :seon.db.registry.entry/cleanup-required])
(schema/register! ::open-intent
                  [:enum :seon.db.registry.open/main
                   :seon.db.registry.open/branch])
(schema/register! ::source-branch-head ::branch-head)
(schema/register! ::expected-source-head ::branch-head)
(schema/register! ::expected-target-head ::branch-head)
(schema/register! ::target-branch :keyword)
(schema/register! ::created? :boolean)
(schema/register! ::adopted? :boolean)
(schema/register! ::deleted? :boolean)
(schema/register! ::force-invoked? :boolean)
(schema/register!
 ::admin-connection-state
 [:enum :seon.db.restore-admin.connection/not-opened
  :seon.db.restore-admin.connection/released
  :seon.db.restore-admin.connection/cleanup-unproved])
(schema/register! ::expected-branch-roster [:set :keyword])
(schema/register! ::branch-roster [:set :keyword])
(schema/register! ::main-branch-head ::branch-head)
(schema/register! ::main-parent-commit-ids [:set :uuid])
(schema/register! ::branch-branch-heads [:map-of :keyword ::branch-head])
(schema/register! ::restore-completions [:vector ::db.restore/completion])
(schema/register! ::completed-restore-ids [:set ::db.restore/id])
(schema/register! ::restore-completion-branch-heads
                  [:map-of ::db.restore/id ::branch-head])
(schema/register! ::transaction-id ::branch/basis-t)
(schema/register! ::commit-id :uuid)
(schema/register! ::pre-restore-main-branch-head ::branch-head)
(schema/register! ::selected-target-branch-head ::branch-head)
(schema/register! ::prepared-target-branch-head ::branch-head)
(schema/register! ::undo-branch-head ::branch-head)
(schema/register! ::validate-db! 'fn?)
(schema/register! ::admin-outcome
                  [:enum :seon.db.registry.admin/applied
                   :seon.db.registry.admin/already-applied])

;; A live conn is an opaque clojure.lang.IAtom2 (datahike connection
;; type). We don't constrain its shape; the registry hands it out as-is.
(schema/register! ::conn 'some?)

(schema/register! ::entry
                  [:map
                   [::conn ::conn]
                   [::connection-id ::connection-id]
                   [::backend ::backend]
                   [::path {:optional true} ::path]
                   [::ensured? ::ensured?]
                   [::transport-connections ::transport-connections]
                   [::release-completion {:optional true}
                    ::release-completion]
                   [::release-error {:optional true} ::release-error]
                   [::initialization-error {:optional true}
                    ::initialization-error]])

(schema/register! ::ensure-database!-request
                  [:map
                   [::database-name ::database-name]
                   [::backend {:optional true} ::backend]
                   [::path {:optional true} ::path]
                   [::connection-id {:optional true} ::connection-id]
                   [::initial-tx {:optional true} ::initial-tx]
                   [::transport-connection {:optional true}
                    ::transport-connection]
                   [::initialize-connection! 'fn?]])

(schema/register! ::initialize-connection-request
                  [:map
                   [::conn ::conn]
                   [::database-name ::database-name]
                   [::connection-id ::connection-id]
                   [::open-intent ::open-intent]])

(schema/register! ::entry-view
                  [:map
                   [::database-name ::database-name]
                   [::conn ::conn]
                   [::connection-id ::connection-id]
                   [::branch-head ::branch-head]
                   [::backend ::backend]
                   [::entry-state ::entry-state]
                   [::path {:optional true} ::path]
                   [::release-error {:optional true} ::release-error]
                   [::initialization-error {:optional true}
                    ::initialization-error]])

(schema/register! ::ensure-database!-response ::entry-view)

(schema/register!
 ::acquire-database!-request
 [:map {:closed true}
  [::database-name ::database-name]
  [::connection-id {:optional true} ::connection-id]
  [::transport-connection ::transport-connection]])
(schema/register!
 ::acquire-database!-response
 [:map
  [::database-name ::database-name]
  [::connection-id ::connection-id]
  [::branch-head ::branch-head]
  [::backend ::backend]
  [::entry-state ::entry-state]
  [::path {:optional true} ::path]
  [::acquired? ::acquired?]])

(schema/register!
 ::release-database-acquisition!-request
 [:map {:closed true}
  [::database-name ::database-name]
  [::transport-connection ::transport-connection]
  [::drain! ::drain!]])
(schema/register!
 ::release-database-acquisition!-response
 [:map {:closed true}
  [::database-name ::database-name]
  [::released? :boolean]
  [::release-error {:optional true} ::release-error]])

(schema/register!
 ::observe-database-lifecycle-request
 [:map {:closed true}
  [::database-name ::database-name]])
(schema/register!
 ::observe-database-lifecycle-response
 [:map {:closed true}
  [::database-name ::database-name]
  [::main-branch-head ::main-branch-head]
  [::main-parent-commit-ids ::main-parent-commit-ids]
  [::branch-branch-heads ::branch-branch-heads]
  [::branch-roster ::branch-roster]
  [::restore-completions ::restore-completions]
  [::completed-restore-ids ::completed-restore-ids]
  [::restore-completion-branch-heads
   ::restore-completion-branch-heads]])

(schema/register!
 ::resolve-transaction-branch-head!-request
 [:map {:closed true}
  [::conn ::conn]
  [::main-branch-head ::main-branch-head]
  [::transaction-id ::transaction-id]])
(schema/register!
 ::commit-reachable?-request
 [:map {:closed true}
  [::conn ::conn]
  [::commit-id ::commit-id]])

(schema/register!
 ::create-branch!-request
 [:map
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::source-branch-head ::source-branch-head]
  [::expected-source-head ::expected-source-head]
  [::target-branch ::target-branch]
  [::initialize-connection! 'fn?]])
(schema/register!
 ::create-branch!-response
 [:map
  [::target-database-name ::target-database-name]
  [::connection-id ::connection-id]
  [::branch-head ::branch-head]
  [::backend ::backend]
  [::path {:optional true} ::path]
  [::created? ::created?]
  [::adopted? ::adopted?]])

(schema/register!
 ::release-connection-id!-request
 [:map
  [::target-database-name ::target-database-name]
  [::connection-id ::connection-id]
  [::expected-target-head ::expected-target-head]
  [::drain! ::drain!]])
(schema/register!
 ::release-connection-id!-response
 [:map
  [::target-database-name ::target-database-name]
  [::connection-id ::connection-id]
  [::released? :boolean]])

(schema/register!
 ::delete-branch!-request
 [:map
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::connection-id ::connection-id]
  [::expected-target-head ::expected-target-head]
  [::drain! ::drain!]])
(schema/register!
 ::delete-branch!-response
 [:map
  [::target-database-name ::target-database-name]
  [::connection-id ::connection-id]
  [::branch-head ::branch-head]
  [::released? :boolean]
  [::deleted? ::deleted?]])

(schema/register!
 ::admin-restore-main!-request
 [:map {:closed true}
  [::database-name ::database-name]
  [::backend ::backend]
  [::path {:optional true} ::path]
  [::pre-restore-main-branch-head ::pre-restore-main-branch-head]
  [::selected-target-branch-head ::selected-target-branch-head]
  [::prepared-target-branch-head ::prepared-target-branch-head]
  [::undo-branch-head ::undo-branch-head]
  [::expected-branch-roster ::expected-branch-roster]
  [::validate-db! ::validate-db!]])
(schema/register!
 ::admin-restore-main!-response
 [:map {:closed true}
  [::admin-outcome ::admin-outcome]
  [::pre-restore-main-branch-head ::pre-restore-main-branch-head]
  [::selected-target-branch-head ::selected-target-branch-head]
  [::prepared-target-branch-head ::prepared-target-branch-head]
  [::undo-branch-head ::undo-branch-head]
  [::branch-head ::branch-head]
  [::branch-roster ::branch-roster]
  [::force-invoked? ::force-invoked?]
  [::admin-connection-state ::admin-connection-state]])

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

(schema/register! ::database-summary
                  [:map
                   [::database-name ::database-name]
                   [::connection-id ::connection-id]
                   [::branch-head ::branch-head]
                   [::backend ::backend]
                   [::entry-state ::entry-state]
                   [::path {:optional true} ::path]
                   [::release-error {:optional true} ::release-error]
                   [::initialization-error {:optional true}
                    ::initialization-error]])

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
                  [:map {:closed true}
                   [::database-name ::database-name]
                   [::transport-connection {:optional true}
                    ::transport-connection]])

(schema/register! ::resolve-connection-response
                  [:map
                   [::conn {:optional true} ::conn]
                   [::database-name {:optional true} ::database-name]
                   [::connection-id {:optional true} ::connection-id]
                   [::branch-head {:optional true} ::branch-head]
                   [::error-kind {:optional true}
                    [:enum :seon.db.registry.error/not-found
                     :seon.db.registry.error/cleanup-required]]
                   [::error {:optional true} :string]])

;;; --- Registry --------------------------------------------------------------

(defonce ^:private !registry
  ;; {database-name -> entry-map}
  (atom {}))

(defn- current-branch-head
  [{::keys [conn]}]
  (branch/head (d/db conn)))

(defn- cleanup-required?
  [entry]
  (boolean (or (::release-error entry)
               (::initialization-error entry))))

(defn- derived-entry-state
  [entry]
  (cond
    (cleanup-required? entry)
    :seon.db.registry.entry/cleanup-required

    (::release-completion entry)
    :seon.db.registry.entry/releasing

    :else
    :seon.db.registry.entry/ready))

(defn- entry-view
  [database-name entry]
  (-> entry
      (dissoc ::ensured? ::transport-connections ::release-completion)
      (assoc ::database-name database-name
             ::entry-state (derived-entry-state entry)
             ::branch-head (current-branch-head entry))))

(defn- summary
  "Public view of an entry, sans live conn."
  [database-name {::keys [connection-id backend path release-error
                          initialization-error]
                  :as entry}]
  (cond-> {::database-name database-name
           ::connection-id connection-id
           ::branch-head (current-branch-head entry)
           ::backend backend
           ::entry-state (derived-entry-state entry)}
    path (assoc ::path path)
    release-error (assoc ::release-error release-error)
    initialization-error (assoc ::initialization-error initialization-error)))

(defn- backend-request
  [{::keys [database-name backend path connection-id initial-tx]}]
  (cond-> {::backend/database-name database-name
           ::backend/backend backend}
    path (assoc ::backend/path path)
    connection-id (assoc ::branch/connection-id connection-id)
    (seq initial-tx) (assoc ::backend/initial-tx initial-tx)))

(defn- entry-config
  [database-name {::keys [connection-id backend path]}]
  (id/allocation-connect-config
   (backend/datahike-config
    (backend-request
     {::database-name database-name
      ::backend backend
      ::path path
      ::connection-id connection-id}))))

(defn- fail-connection-id!
  [message data]
  (throw
   (ex-info message
            (assoc data :seon.error/kind
                   :seon.db.registry.error/connection-id-conflict))))

(defn- validate-route-bijection!
  [registry database-name connection-id backend-kind path]
  (when-let [[other-name _]
             (some (fn [[registered-name entry]]
                     (when (and (not= registered-name database-name)
                                (= connection-id (::connection-id entry)))
                       [registered-name entry]))
                   registry)]
    (fail-connection-id!
     "The requested database connection-id already has a logical route."
     {::database-name database-name
      ::connection-id connection-id
      ::existing-database-name other-name}))
  (let [store-id (first connection-id)]
    (doseq [[registered-name entry] registry
            :let [registered-connection-id (::connection-id entry)
                  registered-id (first registered-connection-id)
                  registered-backend (::backend entry)
                  registered-path (::path entry)]]
      (when (and (= store-id registered-id)
                 (or (not= backend-kind registered-backend)
                     (not= path registered-path)))
        (fail-connection-id!
         "Routes for one physical database disagree on backend configuration."
         {::database-name database-name
          ::connection-id connection-id
          ::backend backend-kind
          ::path path
          ::existing-database-name registered-name
          ::existing-backend registered-backend
          ::existing-path registered-path}))
      (when (and (= :file backend-kind)
                 (= :file registered-backend)
                 (= path registered-path)
                 (not= store-id registered-id))
        (fail-connection-id!
         "One durable backend path cannot name two physical databases."
         {::database-name database-name
          ::connection-id connection-id
          ::path path
          ::existing-database-name registered-name
          ::existing-connection-id registered-connection-id})))))

(defn- validate-existing-route!
  [database-name entry connection-id backend-kind path]
  (when-not (= [connection-id backend-kind path]
               [(::connection-id entry) (::backend entry) (::path entry)])
    (fail-connection-id!
     "A logical database name cannot change its registered connection-id."
     {::database-name database-name
      ::connection-id connection-id
      ::backend backend-kind
      ::path path
      ::existing-connection-id (::connection-id entry)
      ::existing-backend (::backend entry)
      ::existing-path (::path entry)}))
  (cond
    (cleanup-required? entry)
    (throw
     (ex-info "A failed database open still requires resource cleanup."
              {:seon.error/kind :seon.db.registry.error/cleanup-required
               ::database-name database-name
               ::connection-id (::connection-id entry)
               ::branch-head (current-branch-head entry)
               ::release-error (::release-error entry)
               ::initialization-error (::initialization-error entry)}))

    (::release-completion entry)
    (throw
     (ex-info "The database connection is being released."
              {:seon.error/kind :seon.db.registry.error/releasing
               ::database-name database-name
               ::connection-id (::connection-id entry)}))

    :else
    (entry-view database-name entry)))

(defn- branch-source
  [registry connection-id]
  (let [store-id (first connection-id)]
    (some (fn [[_ entry]]
            (when (= store-id
                     (first (::connection-id entry)))
              (::conn entry)))
          registry)))

(defn- ensure-existing-reference!
  [database-name entry connection-id backend-kind path]
  (validate-existing-route!
   database-name entry connection-id backend-kind path)
  (if (get entry ::ensured? true)
    (entry-view database-name entry)
    (let [acquired (d/connect (entry-config database-name entry))]
      (when-not (identical? (::conn entry) acquired)
        (try
          (d/release acquired)
          (catch Throwable _))
        (throw
         (ex-info "Datahike returned a different connection for one database route."
                  {:seon.error/kind
                   :seon.db.registry.error/connection-mismatch
                   ::database-name database-name
                   ::connection-id (::connection-id entry)})))
      (let [updated (assoc entry ::ensured? true)]
        (swap! !registry assoc database-name updated)
        (entry-view database-name updated)))))

(defn- open-entry!
  "Open and validate one exact Datahike connection-id."
  [registry database-name backend-kind path connection-id initial-tx]
  (let [request (backend-request
                 {::database-name database-name
                  ::backend backend-kind
                  ::path path
                  ::connection-id connection-id
                  ::initial-tx initial-tx})
        cfg (backend/datahike-config request)
        branch (second connection-id)]
    (if (= :db branch)
      (do
        (when path
          (backend/ensure-parent-dir! {::backend/path path}))
        (when-not (d/database-exists? cfg)
          (d/create-database cfg)))
      (let [source (branch-source registry connection-id)]
        (when-not source
          (fail-connection-id!
           "A non-main branch requires a registered physical database."
           {::database-name database-name ::connection-id connection-id}))
        (when-not (contains? (d/branches source) branch)
          (fail-connection-id!
           "The requested branch is absent from Datahike's durable branch roster."
           {::database-name database-name
            ::connection-id connection-id
            ::available-branches (d/branches source)}))))
    (let [conn (d/connect (id/allocation-connect-config cfg))
          entry (cond-> {::conn conn
                         ::connection-id connection-id
                         ::backend backend-kind
                         ::ensured? true
                         ::transport-connections #{}}
                  path (assoc ::path path))]
      (try
        (id/assert-allocation-writer! conn)
        (let [actual (branch/connection-id (branch/head (d/db conn)))]
          (when-not (= connection-id actual)
            (fail-connection-id!
             "Datahike connected a different database connection-id."
             {::database-name database-name
              ::connection-id connection-id
              ::actual-connection-id actual})))
        entry
        (catch Throwable throwable
          (try
            (d/release conn)
            (catch Throwable release-throwable
              (let [retained
                    (assoc entry
                           ::initialization-error (.toString throwable)
                           ::release-error (.toString release-throwable))]
                (swap! !registry assoc database-name retained)
                (throw
                 (ex-info
                  "Database open validation failed and connection cleanup is unproved."
                  {:seon.error/kind
                   :seon.db.registry.error/cleanup-required
                   ::database-name database-name
                   ::connection-id connection-id
                   ::branch-head (current-branch-head retained)
                   ::initialization-error (.toString throwable)
                   ::release-error (.toString release-throwable)}
                  throwable)))))
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
  [{::keys [database-name backend path connection-id initial-tx
            transport-connection
            initialize-connection!]
    :or {backend :file}}]
  (let [request (backend-request
                 {::database-name database-name
                  ::backend backend
                  ::path path
                  ::connection-id connection-id
                  ::initial-tx initial-tx})
        facts (backend/backend-facts request)
        connection-id* (::branch/connection-id facts)
        backend-path (::backend/path facts)]
    (if-let [entry (get @!registry database-name)]
      (if (or (get entry ::ensured? true)
              (and transport-connection
                   (some #(identical? transport-connection %)
                         (::transport-connections entry #{}))))
        (validate-existing-route!
         database-name entry connection-id* backend backend-path)
        (locking !registry
          (let [entry (get @!registry database-name)]
            (if (and transport-connection
                     (some #(identical? transport-connection %)
                           (::transport-connections entry #{})))
              (validate-existing-route!
               database-name entry connection-id* backend backend-path)
              (ensure-existing-reference!
               database-name entry connection-id* backend backend-path)))))
      (locking !registry
        (if-let [entry (get @!registry database-name)]
          (if (and transport-connection
                   (some #(identical? transport-connection %)
                         (::transport-connections entry #{})))
            (validate-existing-route!
             database-name entry connection-id* backend backend-path)
            (ensure-existing-reference!
             database-name entry connection-id* backend backend-path))
          (let [registry @!registry]
            (validate-route-bijection!
             registry database-name connection-id* backend backend-path)
            (let [entry (open-entry! registry database-name backend backend-path
                                     connection-id* initial-tx)
                  conn (::conn entry)
                  branch (second connection-id*)
                  open-intent (if (= :db branch)
                                :seon.db.registry.open/main
                                :seon.db.registry.open/branch)
                  branch-head-before (current-branch-head entry)]
              (try
                (initialize-connection!
                 {::conn conn
                  ::database-name database-name
                  ::connection-id connection-id*
                  ::open-intent open-intent})
                (when (and (= :seon.db.registry.open/branch open-intent)
                           (not= branch-head-before (current-branch-head entry)))
                  (throw
                   (ex-info
                    "A non-main branch initializer changed its database head."
                    {:seon.error/kind
                     :seon.db.registry.error/branch-initializer-wrote
                     ::database-name database-name
                     ::connection-id connection-id*
                     ::branch-head-before branch-head-before
                     ::branch-head-after (current-branch-head entry)})))
                (swap! !registry assoc database-name entry)
                (entry-view database-name entry)
                (catch Throwable throwable
                  (try
                    (d/release conn)
                    (catch Throwable release-throwable
                      (let [retained
                            (assoc entry
                                   ::initialization-error (.toString throwable)
                                   ::release-error (.toString release-throwable))]
                        (swap! !registry assoc database-name retained)
                        (throw
                         (ex-info
                          "Database initialization failed and connection cleanup is unproved."
                          {:seon.error/kind
                           :seon.db.registry.error/cleanup-required
                           ::database-name database-name
                           ::connection-id connection-id*
                           ::branch-head (current-branch-head retained)
                           ::initialization-error (.toString throwable)
                           ::release-error (.toString release-throwable)}
                          throwable)))))
                  (throw throwable))))))))))

(declare require-ready-entry!)

(defn- owns-transport-connection?
  [connections transport-connection]
  (boolean (some #(identical? transport-connection %) connections)))

(defn- remove-transport-connection
  [connections transport-connection]
  (into #{} (remove #(identical? transport-connection %)) connections))

(defn acquire-database!
  "Acquire one database for one live transport connection.

   The transport connection object remains process-local. Repeating the same
   acquisition is an idempotent no-op. The first connection takes ownership of
   an existing administrative ensure reference; later connections acquire one
   matching reference through Datahike's own connection registry. An optional
   expected connection-id is validated before either membership or connection
   acquisition; omitting it atomically acquires the route that is current while
   the registry lock is held."
  {:malli/schema [:=> [:cat ::acquire-database!-request]
                  ::acquire-database!-response]}
  [{::keys [database-name connection-id transport-connection]}]
  (locking !registry
    (let [entry (require-ready-entry! database-name)
          _ (when (and connection-id
                       (not= connection-id (::connection-id entry)))
              (fail-connection-id!
               "The requested database connection-id does not match its logical route."
               {::database-name database-name
                ::connection-id connection-id
                ::existing-connection-id (::connection-id entry)}))
          connections (::transport-connections entry #{})]
      (if (owns-transport-connection? connections transport-connection)
        (assoc (entry-view database-name entry) ::acquired? false)
        (let [transfer-ensure? (get entry ::ensured? true)
              acquired
              (when-not transfer-ensure?
                (d/connect (entry-config database-name entry)))]
          (when (and acquired (not (identical? (::conn entry) acquired)))
            (try
              (d/release acquired)
              (catch Throwable _))
            (throw
             (ex-info
              "Datahike returned a different connection for one database route."
              {:seon.error/kind
               :seon.db.registry.error/connection-mismatch
               ::database-name database-name
               ::connection-id (::connection-id entry)})))
          (let [updated
                (-> entry
                    (assoc ::ensured? false)
                    (update ::transport-connections (fnil conj #{})
                            transport-connection))]
            (swap! !registry assoc database-name updated)
            (assoc (entry-view database-name updated) ::acquired? true)))))))

(defn- retain-release-failure!
  [database-name completion entry throwable]
  (let [message (.toString throwable)]
    (log/error throwable "database connection release failed"
               {::database-name database-name})
    (locking !registry
      (when (identical? completion
                        (::release-completion (get @!registry database-name)))
        (swap! !registry assoc database-name
               (-> entry
                   (dissoc ::release-completion)
                   (assoc ::release-error message)))))
    (deliver completion {::database-name database-name
                         ::released? false
                         ::release-error message})
    {::database-name database-name
     ::released? false
     ::release-error message}))

(defn- finish-final-release!
  [database-name completion entry drain!]
  (try
    (drain! {::database-name database-name
             ::connection-id (::connection-id entry)})
    (d/release (::conn entry))
    (locking !registry
      (when (identical? completion
                        (::release-completion (get @!registry database-name)))
        (swap! !registry dissoc database-name)))
    (let [result {::database-name database-name ::released? true}]
      (deliver completion result)
      result)
    (catch Throwable throwable
      (retain-release-failure!
       database-name completion entry throwable))))

(defn release-database-acquisition!
  "Release one exact transport connection's acquisition of one database.

   Sibling connections retain their Datahike references. If this is the final
   reference, the entry is made unavailable before `drain!` and Datahike's
   final release run outside the registry-wide lock."
  {:malli/schema [:=> [:cat ::release-database-acquisition!-request]
                  ::release-database-acquisition!-response]}
  [{::keys [database-name transport-connection drain!]}]
  (let [action
        (locking !registry
          (if-let [entry (get @!registry database-name)]
            (let [connections (::transport-connections entry #{})]
              (cond
                (::release-completion entry)
                {::database-name database-name ::released? false}

                (not (owns-transport-connection?
                      connections transport-connection))
                {::database-name database-name ::released? false}

                :else
                (let [remaining (remove-transport-connection
                                 connections transport-connection)
                      updated (assoc entry ::transport-connections remaining)
                      final? (and (not (::ensured? updated))
                                  (empty? remaining))]
                  (if final?
                    (let [completion (promise)
                          releasing (assoc updated
                                           ::release-completion completion)]
                      (swap! !registry assoc database-name releasing)
                      {::final? true
                       ::completion completion
                       ::entry releasing})
                    (try
                      (d/release (::conn entry))
                      (swap! !registry assoc database-name updated)
                      {::database-name database-name ::released? true}
                      (catch Throwable throwable
                        (let [message (.toString throwable)]
                          (swap! !registry assoc database-name
                                 (assoc updated ::release-error message))
                          {::database-name database-name
                           ::released? false
                           ::release-error message})))))))
            {::database-name database-name ::released? false}))]
    (if (::final? action)
      (finish-final-release!
       database-name (::completion action) (::entry action) drain!)
      (select-keys action [::database-name ::released? ::release-error]))))

(defn- lifecycle-fail!
  [kind message data]
  (throw (ex-info message (assoc data :seon.error/kind kind))))

(declare release-database!)

(defn- require-ready-entry!
  [database-name]
  (let [entry (get @!registry database-name)]
    (cond
      (nil? entry)
      (lifecycle-fail!
       :seon.db.protocol.error/not-found
       "The requested database route is not registered."
       {::database-name database-name})

      (cleanup-required? entry)
      (lifecycle-fail!
       :seon.db.protocol.error/cleanup-required
       "The requested database route has unproved resource cleanup."
       {::database-name database-name
        ::connection-id (::connection-id entry)
        ::branch-head (current-branch-head entry)
        ::release-error (::release-error entry)})

      (::release-completion entry)
      (lifecycle-fail!
       :seon.db.registry.error/releasing
       "The requested database route is being released."
       {::database-name database-name
        ::connection-id (::connection-id entry)})

      :else entry)))

(defn- require-connection-id!
  [label expected actual data]
  (when-not (= expected actual)
    (lifecycle-fail!
     :seon.db.protocol.error/connection-id-mismatch
     (str label " belongs to a different database connection-id.")
     (assoc data ::expected-connection-id expected ::actual-connection-id actual))))

(defn- require-head!
  [kind label expected actual data]
  (when-not (= expected actual)
    (lifecycle-fail!
     kind
     (str label " changed before the lifecycle operation.")
     (assoc data ::expected-branch-head expected ::branch-head actual))))

(defn- observe-native-branch-branch-heads!
  [database-name connection connection-id roster]
  (into {}
        (map
         (fn [branch]
           (let [db-value (d/branch-as-db connection branch)]
             (when-not db-value
               (lifecycle-fail!
                :seon.db.protocol.error/branch-missing
                "A native branch disappeared during lifecycle observation."
                {::database-name database-name
                 ::branch-roster roster
                 ::target-branch branch}))
             (let [resolved (branch/head db-value)
                   expected-connection-id
                   (assoc connection-id 1 branch)]
               (require-connection-id!
                "Observed native branch" expected-connection-id
                (branch/connection-id resolved)
                {::database-name database-name
                 ::target-branch branch
                 ::branch-head resolved})
               [branch resolved])))
         (sort roster))))

(defn- branch-head-resolution-error!
  [kind message data]
  (lifecycle-fail! kind message data))

(defn- retained-stored-commit!
  [store commit-id]
  (or (k/get store commit-id nil {:sync? true})
      (branch-head-resolution-error!
       :seon.db.protocol.error/unsupported-history
       "Transaction-branch-head ancestry is incomplete."
       {::branch/commit-id commit-id})))

(defn commit-reachable?
  "True when `commit-id` is retained on the connection's current lineage."
  {:malli/schema [:=> [:cat ::commit-reachable?-request] :boolean]}
  [{::keys [conn commit-id]}]
  (let [db-value (d/db conn)
        store (:store db-value)
        head (d/commit-id db-value)]
    (loop [pending [head]
           visited #{}]
      (if-let [candidate (first pending)]
        (cond
          (= candidate commit-id) true
          (contains? visited candidate) (recur (next pending) visited)
          :else
          (if-let [stored (k/get store candidate nil {:sync? true})]
            (recur (into (vec (next pending))
                         (get-in stored [:meta :datahike/parents]))
                   (conj visited candidate))
            false))
        false))))

(defn- stored-branch-head
  [stored]
  {::branch/store-id (get-in stored [:config :store :id])
   ::branch/name (get-in stored [:config :branch])
   ::branch/commit-id (get-in stored [:meta :datahike/commit-id])
   ::branch/basis-t (:max-tx stored)})

(defn- transaction-origin?
  [store branch transaction stored]
  (when (and (= branch (get-in stored [:config :branch]))
             (= transaction (:max-tx stored)))
    (let [parents (mapv #(retained-stored-commit! store %)
                        (get-in stored [:meta :datahike/parents]))]
      ;; Datahike advances max-tx only for an ordinary transaction. Branch and
      ;; force metadata commits can repeat t, but then a direct parent already
      ;; carries that same t.
      (not-any? #(= transaction (:max-tx %)) parents))))

(defn- transaction-origin-candidates
  [store head branch transaction]
  (loop [pending [head]
         visited #{}
         candidates []]
    (if-let [stored (first pending)]
      (let [commit-id (get-in stored [:meta :datahike/commit-id])]
        (if (contains? visited commit-id)
          (recur (next pending) visited candidates)
          (let [basis-t (:max-tx stored)
                candidates (cond-> candidates
                             (transaction-origin?
                              store branch transaction stored)
                             (conj (stored-branch-head stored)))
                parents
                (if (>= basis-t transaction)
                  (mapv #(retained-stored-commit! store %)
                        (get-in stored [:meta :datahike/parents]))
                  [])]
            (recur (into (vec (next pending)) parents)
                   (conj visited commit-id)
                   candidates))))
      candidates)))

(defn resolve-transaction-branch-head!
  "Resolve one transaction's exact origin on the retained main lineage."
  {:malli/schema
   [:=> [:cat ::resolve-transaction-branch-head!-request] ::branch-head]}
  [{::keys [conn main-branch-head transaction-id]}]
  (let [current-db (d/db conn)
        current-branch-head (branch/head current-db)
        store (:store current-db)]
    (when (false? (get-in current-db [:config :commit-graph?] true))
      (branch-head-resolution-error!
       :seon.db.protocol.error/unsupported-history
       "Transaction-branch-head resolution requires retained commit history."
       {::main-branch-head main-branch-head
        ::transaction-id transaction-id}))
    (when-not (and (= :db (::branch/name current-branch-head))
                   (= :db (::branch/name main-branch-head)))
      (branch-head-resolution-error!
       :seon.db.protocol.error/connection-id-mismatch
       "Restore completion branch-heads resolve only on the live :db lineage."
       {::main-branch-head main-branch-head
        ::branch-head current-branch-head}))
    (when-not (= (branch/connection-id current-branch-head)
                 (branch/connection-id main-branch-head))
      (branch-head-resolution-error!
       :seon.db.protocol.error/connection-id-mismatch
       "The frozen head names a different database connection-id."
       {::main-branch-head main-branch-head
        ::branch-head current-branch-head}))
    (let [head (retained-stored-commit!
                store (::branch/commit-id main-branch-head))
          resolved-head (stored-branch-head head)]
      (when-not (= main-branch-head resolved-head)
        (branch-head-resolution-error!
         :seon.db.protocol.error/connection-id-mismatch
         "The retained commit does not resolve the frozen head branch-head."
         {::main-branch-head main-branch-head
          ::branch-head resolved-head}))
      (when-not
       (loop [pending [(::branch/commit-id current-branch-head)]
              visited #{}]
         (if-let [commit-id (first pending)]
           (cond
             (= commit-id (::branch/commit-id main-branch-head)) true
             (contains? visited commit-id)
             (recur (next pending) visited)
             :else
             (let [stored (retained-stored-commit! store commit-id)]
               (recur (concat (next pending)
                              (get-in stored [:meta :datahike/parents]))
                      (conj visited commit-id))))
           false))
        (branch-head-resolution-error!
         :seon.db.protocol.error/non-ancestor
         "The frozen head is not an ancestor of the current branch head."
         {::main-branch-head main-branch-head
          ::branch-head current-branch-head}))
      (let [candidates
            (transaction-origin-candidates
             store head (::branch/name main-branch-head) transaction-id)]
        (case (count candidates)
          1 (first candidates)
          0 (branch-head-resolution-error!
             :seon.db.protocol.error/not-found
             "No original commit for the transaction is reachable from the frozen head."
             {::main-branch-head main-branch-head
              ::transaction-id transaction-id})
          (branch-head-resolution-error!
           :seon.db.protocol.error/ambiguous-history
           "Several original commits match the transaction on this branch."
           {::main-branch-head main-branch-head
            ::transaction-id transaction-id
            :seon.db.registry/candidate-branch-heads candidates}))))))

(defn- durable-restore-completions!
  [database-name connection main-db main-branch-head]
  (let [ids (->> (d/q '[:find [?id ...]
                         :where
                         [?completion :seon.db.restore/id ?id]]
                       main-db)
                 sort
                 vec)
        transaction-rows
        (d/q '[:find ?id ?transaction
               :where
               [?completion :seon.db.restore/id ?id ?transaction true]]
             (d/history main-db))
        transactions-by-id
        (reduce
         (fn [result [completion-id transaction]]
           (update result completion-id (fnil conj #{}) transaction))
         {}
         transaction-rows)
        completions
        (mapv
         (fn [completion-id]
           (let [completion
                 (d/pull main-db db.restore/completion-attrs
                         [::db.restore/id completion-id])]
             (when-not
              (and (schema/valid-candidate-value?
                    ::db.restore/completion completion)
                   (= database-name (::db.restore/db-name completion))
                   (= (::branch/store-id main-branch-head)
                      (::db.restore/database-id completion)))
               (lifecycle-fail!
                :seon.db.protocol.error/restore-divergence
                "A durable restore completion disagrees with the observed main database."
                {::database-name database-name
                 ::main-branch-head main-branch-head
                 :seon.db.restore/id completion-id
                 :seon.db.restore/completion completion}))
             completion))
         ids)
        current-transactions
        (into {}
              (map
               (fn [[completion-id completion]]
                 (let [rows
                       (->> (d/q '[:find ?attribute ?transaction
                                   :in $ ?id
                                   :where
                                   [?completion :seon.db.restore/id ?id]
                                   [?completion ?attribute _ ?transaction]]
                                 main-db completion-id)
                            (sort-by (comp str first))
                            vec)
                       proof
                       (db.restore/publication-proof
                        {::db.restore/completion completion
                         ::db.restore/publication-rows rows})]
                   (when-not (::db.restore/ok? proof)
                     (lifecycle-fail!
                      :seon.db.protocol.error/restore-divergence
                      "A durable restore completion was not published atomically."
                      {::database-name database-name
                       ::main-branch-head main-branch-head
                       :seon.db.restore/id completion-id
                       :seon.db.restore/completion completion
                       :seon.db.restore/publication-rows rows}))
                   [completion-id (::db.restore/transaction proof)]))
              (map vector ids completions)))
        transaction-ts
        (into {}
              (map
               (fn [completion-id]
                 (let [transactions (get transactions-by-id completion-id)]
                   (when-not (and (= 1 (count transactions))
                                  (= (first transactions)
                                     (get current-transactions completion-id)))
                     (lifecycle-fail!
                      :seon.db.protocol.error/restore-divergence
                      "A durable restore completion no longer has its original publication transaction."
                      {::database-name database-name
                       ::main-branch-head main-branch-head
                       :seon.db.restore/id completion-id
                       :seon.db.restore/transaction-ids transactions
                       :seon.db.restore/current-transaction
                       (get current-transactions completion-id)}))
                   [completion-id (first transactions)]))
              ids))
        completion-branch-heads
        (into {}
              (map
               (fn [[completion-id transaction]]
                 [completion-id
                  (resolve-transaction-branch-head!
                   {::conn connection
                    ::main-branch-head main-branch-head
                    ::transaction-id transaction})]))
              transaction-ts)]
    (when-not (= (set ids) (set (keys transactions-by-id)))
      (lifecycle-fail!
       :seon.db.protocol.error/restore-divergence
       "Restore completion history disagrees with the current main database."
       {::database-name database-name
        ::main-branch-head main-branch-head
        ::completed-restore-ids (set ids)
        :seon.db.restore/historical-completion-ids
        (set (keys transactions-by-id))}))
    {::main-parent-commit-ids (set (or (d/parent-commit-ids main-db) []))
     ::restore-completions completions
     ::completed-restore-ids (set ids)
     ::restore-completion-branch-heads completion-branch-heads}))

(defn- observe-main-lifecycle-facts!
  [database-name connection connection-id expected-branch-head]
  (let [main-db (d/branch-as-db connection :db)]
    (when-not main-db
      (lifecycle-fail!
       :seon.db.protocol.error/branch-missing
       "The main branch disappeared during lifecycle observation."
       {::database-name database-name}))
    (let [main-branch-head (branch/head main-db)]
      (require-connection-id!
       "Observed main branch" connection-id (branch/connection-id main-branch-head)
       {::database-name database-name ::main-branch-head main-branch-head})
      (require-head!
       :seon.db.protocol.error/stale-target-head
       "Observed main branch" expected-branch-head main-branch-head
       {::database-name database-name})
      (durable-restore-completions!
       database-name connection main-db main-branch-head))))

(defn observe-database-lifecycle
  "Observe every exact native branch head from the registered main route."
  {:malli/schema
   [:=> [:cat ::observe-database-lifecycle-request]
    ::observe-database-lifecycle-response]}
  [{::keys [database-name]}]
  (locking !registry
    (let [entry (require-ready-entry! database-name)
          connection-id (::connection-id entry)
          connection (::conn entry)]
      (when-not (= :db (second connection-id))
        (lifecycle-fail!
         :seon.db.protocol.error/connection-id-mismatch
         "Database lifecycle observation requires the main :db route."
         {::database-name database-name
          ::connection-id connection-id}))
      (locking connection
        (let [roster-before (set (d/branches connection))
              branch-heads-before
              (observe-native-branch-branch-heads!
               database-name connection connection-id roster-before)
              main-facts-before
              (observe-main-lifecycle-facts!
               database-name connection connection-id (get branch-heads-before :db))
              roster-middle (set (d/branches connection))
              branch-branch-heads
              (observe-native-branch-branch-heads!
               database-name connection connection-id roster-before)
              main-facts
              (observe-main-lifecycle-facts!
               database-name connection connection-id (get branch-branch-heads :db))
              roster-after (set (d/branches connection))
              main-branch-head (get branch-branch-heads :db)]
          (when-not (= roster-before roster-middle roster-after)
            (lifecycle-fail!
             :seon.db.protocol.error/stale-branch-roster
             "The native branch roster changed during lifecycle observation."
             {::database-name database-name
              ::expected-branch-roster roster-before
              ::branch-roster roster-after}))
          (when-not (= branch-heads-before branch-branch-heads)
            (lifecycle-fail!
             :seon.db.protocol.error/stale-target-head
             "A native branch head changed during lifecycle observation."
             {::database-name database-name
              ::expected-branch-head branch-heads-before
              ::branch-head branch-branch-heads}))
          (when-not (= main-facts-before main-facts)
            (lifecycle-fail!
             :seon.db.protocol.error/stale-target-head
             "Main lifecycle facts changed during lifecycle observation."
             {::database-name database-name
              :seon.db.registry/expected-main-lifecycle-facts main-facts-before
              :seon.db.registry/main-lifecycle-facts main-facts}))
          (when-not (and (contains? roster-before :db)
                         main-branch-head
                         (= roster-before (set (keys branch-branch-heads))))
            (lifecycle-fail!
             :seon.db.protocol.error/stale-branch-roster
             "Database lifecycle observation is incomplete."
             {::database-name database-name
              ::branch-roster roster-before
              ::branch-branch-heads branch-branch-heads}))
          {::database-name database-name
           ::main-branch-head main-branch-head
           ::main-parent-commit-ids (::main-parent-commit-ids main-facts)
           ::branch-branch-heads branch-branch-heads
           ::branch-roster roster-before
           ::restore-completions (::restore-completions main-facts)
           ::completed-restore-ids (::completed-restore-ids main-facts)
           ::restore-completion-branch-heads
           (::restore-completion-branch-heads main-facts)})))))

(defn- datahike-lifecycle-kind
  [throwable]
  (case (:type (ex-data throwable))
    :commit-graph-disabled :seon.db.protocol.error/unsupported-history
    :commit-not-found :seon.db.protocol.error/missing-commit
    :branch-already-exists :seon.db.protocol.error/branch-exists
    :branch-does-not-exist :seon.db.protocol.error/branch-missing
    :cannot-delete-main-db-branch
    :seon.db.protocol.error/protected-main-branch
    :branch-has-active-connection :seon.db.protocol.error/active-branch
    :stale-branch-head :seon.db.protocol.error/stale-target-head
    :force-branch-readback-mismatch
    :seon.db.protocol.error/restore-divergence
    :invalid-force-branch-options :seon.db.protocol.error/protocol
    :seon.db.protocol.error/database))

(defn- call-datahike-lifecycle!
  [operation data]
  (try
    (operation)
    (catch clojure.lang.ExceptionInfo throwable
      (lifecycle-fail! (datahike-lifecycle-kind throwable)
                       (.getMessage throwable)
                       (merge data (ex-data throwable))))))

(defn- same-ordered-values?
  "Compare ordered values in lockstep without materializing either input."
  [left right]
  (loop [left-values (seq left)
         right-values (seq right)]
    (cond
      (nil? left-values) (nil? right-values)
      (nil? right-values) false
      (= (first left-values) (first right-values))
      (recur (next left-values) (next right-values))
      :else false)))

(defn- delete-unpublished-branch!
  [source-entry target-database-name target-branch connection-id cause]
  (when-let [target-entry (get @!registry target-database-name)]
    (let [{::keys [released? release-error]}
          (release-database! {::database-name target-database-name})]
      (when-not released?
        (lifecycle-fail!
         :seon.db.protocol.error/cleanup-required
         "Branch open failed and target connection cleanup is unproved."
         {::target-database-name target-database-name
          ::connection-id connection-id
          ::branch-head (current-branch-head target-entry)
          ::release-error release-error
          ::initialization-error (.toString cause)}))))
  (try
    (d/delete-branch! (::conn source-entry) target-branch)
    (catch Throwable cleanup-throwable
      (lifecycle-fail!
       :seon.db.protocol.error/cleanup-required
       "Branch open failed and target branch cleanup is unproved."
       {::target-database-name target-database-name
        ::connection-id connection-id
        ::release-error (.toString cleanup-throwable)
        ::initialization-error (.toString cause)})))
  (when (contains? (d/branches (::conn source-entry)) target-branch)
    (lifecycle-fail!
     :seon.db.protocol.error/cleanup-required
     "Target branch remained in the durable roster after cleanup."
     {::target-database-name target-database-name
      ::connection-id connection-id
      ::initialization-error (.toString cause)})))

(defn- branch-result
  [target-database-name connection-id branch-head backend-kind path created?]
  (cond-> {::target-database-name target-database-name
           ::connection-id connection-id
           ::branch-head branch-head
           ::backend backend-kind
           ::created? created?
           ::adopted? (not created?)}
    path (assoc ::path path)))

(defn- reconcile-existing-target!
  [{::keys [target-database-name target-branch source-connection connection-id
            backend path]
    target-entry ::entry}]
  (let [actual-branch-head (current-branch-head target-entry)]
    (require-connection-id!
     "Existing target route" connection-id (::connection-id target-entry)
     {::target-database-name target-database-name})
    (when-not (contains? (d/branches source-connection) target-branch)
      (lifecycle-fail!
       :seon.db.protocol.error/branch-missing
       "The existing target route is absent from the durable branch roster."
       {::target-database-name target-database-name
        ::connection-id connection-id}))
    (when-not (and (= backend (::backend target-entry))
                   (= path (::path target-entry)))
      (lifecycle-fail!
       :seon.db.protocol.error/duplicate-route
       "The target route uses different physical database branch-heads."
       {::target-database-name target-database-name
        ::connection-id connection-id}))
    (branch-result target-database-name connection-id actual-branch-head
                   backend path false)))

(defn- retained-source-db!
  [source-connection source-connection-id source-branch-head]
  (let [commit-db
        (call-datahike-lifecycle!
         #(d/commit-as-db source-connection
                          (::branch/commit-id source-branch-head))
         {::source-branch-head source-branch-head})]
    (when-not commit-db
      (lifecycle-fail!
       :seon.db.protocol.error/missing-commit
       "The requested source commit is not retained."
       {::source-branch-head source-branch-head}))
    (let [commit-branch-head (branch/head commit-db)]
      (require-connection-id!
       "Retained source commit" source-connection-id
       (branch/connection-id commit-branch-head)
       {::source-branch-head source-branch-head
        ::branch-head commit-branch-head})
      (when-not (= (::branch/commit-id source-branch-head)
                   (::branch/commit-id commit-branch-head))
        (lifecycle-fail!
         :seon.db.protocol.error/missing-commit
         "The retained commit did not resolve to the requested id."
         {::source-branch-head source-branch-head
          ::branch-head commit-branch-head}))
      (when-not (= (::branch/basis-t source-branch-head)
                   (::branch/basis-t commit-branch-head))
        (lifecycle-fail!
         :seon.db.protocol.error/cut-not-branchable
         "The requested temporal cut is not the containing commit head."
         {::source-branch-head source-branch-head
          ::branch-head commit-branch-head})))
    commit-db))

(defn create-branch!
  "Create or adopt one exact native branch and publish its logical route."
  {:malli/schema [:=> [:cat ::create-branch!-request]
                  ::create-branch!-response]}
  [{::keys [source-database-name target-database-name source-branch-head
            expected-source-head target-branch initialize-connection!]}]
  (locking !registry
    (let [source-entry (require-ready-entry! source-database-name)
          source-connection-id (::connection-id source-entry)
          source-connection (::conn source-entry)
          target-connection-id
          (assoc source-connection-id 1 target-branch)
          expected-target-branch-head
          (assoc source-branch-head ::branch/name target-branch)
          backend-kind (::backend source-entry)
          path (::path source-entry)]
      (when (= :db target-branch)
        (lifecycle-fail!
         :seon.db.protocol.error/protected-main-branch
         "The main :db branch cannot be a lifecycle target."
         {::target-branch target-branch}))
      (require-connection-id!
       "Source branch-head" source-connection-id
       (branch/connection-id source-branch-head)
       {::source-branch-head source-branch-head})
      (require-connection-id!
       "Expected source head" source-connection-id
       (branch/connection-id expected-source-head)
       {::expected-source-head expected-source-head})
      (if-let [target-entry (get @!registry target-database-name)]
        (locking source-connection
          (reconcile-existing-target!
           {::target-database-name target-database-name
            ::target-branch target-branch
            ::source-connection source-connection
            ::connection-id target-connection-id
            ::backend backend-kind
            ::path path
            ::entry target-entry}))
        (do
          (when (some (fn [[_ entry]]
                        (= target-connection-id (::connection-id entry)))
                      @!registry)
            (lifecycle-fail!
             :seon.db.protocol.error/duplicate-connection-id
             "The target database connection-id already has a logical route."
             {::target-database-name target-database-name
              ::connection-id target-connection-id}))
          (locking source-connection
            (let [existing?
                  (contains? (d/branches source-connection) target-branch)
                  _ (when-not existing?
                      (require-head!
                       :seon.db.protocol.error/stale-source-head
                       "Source head" expected-source-head
                       (current-branch-head source-entry)
                       {::source-database-name source-database-name}))
                  commit-db
                  (retained-source-db! source-connection source-connection-id
                                       source-branch-head)
                  _ (when-not existing?
                      (call-datahike-lifecycle!
                       #(d/branch! source-connection
                                   (::branch/commit-id source-branch-head)
                                   target-branch)
                       {::target-branch target-branch}))]
              (try
                (let [target-db
                      (d/branch-as-db source-connection target-branch)
                      target-branch-head (branch/head target-db)
                      _ (when (and existing?
                                   (not= expected-target-branch-head
                                         target-branch-head))
                          (lifecycle-fail!
                           :seon.db.protocol.error/branch-exists
                           "The target branch exists at a different branch-head."
                           {::expected-branch-head expected-target-branch-head
                            ::branch-head target-branch-head}))
                      _ (when-not (same-ordered-values?
                                   (d/datoms commit-db :eavt)
                                   (d/datoms target-db :eavt))
                          (lifecycle-fail!
                           :seon.db.protocol.error/initializer
                           "The target branch primary datoms differ from its source commit."
                           {::source-branch-head source-branch-head
                            ::branch-head target-branch-head}))
                      entry
                      (ensure-database!
                       (cond-> {::database-name target-database-name
                                ::backend backend-kind
                                ::connection-id target-connection-id
                                ::initialize-connection!
                                initialize-connection!}
                         path (assoc ::path path)))
                      actual-branch-head (::branch-head entry)]
                  (when-not (= expected-target-branch-head actual-branch-head)
                    (lifecycle-fail!
                     :seon.db.protocol.error/initializer
                     "The opened target branch moved from its exact fork point."
                     {::expected-branch-head expected-target-branch-head
                      ::branch-head actual-branch-head}))
                  (branch-result target-database-name target-connection-id
                                 actual-branch-head backend-kind path
                                 (not existing?)))
                (catch Throwable throwable
                  (when-not existing?
                    (delete-unpublished-branch!
                     source-entry target-database-name target-branch
                     target-connection-id throwable))
                  (throw throwable))))))))))

(defn- require-branch-roster!
  [connection expected]
  (let [actual (set (d/branches connection))]
    (when-not (= expected actual)
      (lifecycle-fail!
       :seon.db.protocol.error/stale-branch-roster
       "The durable branch roster changed before restore administration."
       {::expected-branch-roster expected
        ::branch-roster actual}))
    actual))

(defn- branch-db-at!
  [connection expected label]
  (let [branch (::branch/name expected)
        db-value (d/branch-as-db connection branch)]
    (when-not db-value
      (lifecycle-fail!
       :seon.db.protocol.error/branch-missing
       (str label " is absent from durable storage.")
       {::branch-head expected}))
    (let [actual (branch/head db-value)]
      (require-head!
       :seon.db.protocol.error/stale-target-head
       label expected actual {::branch-head expected})
      db-value)))

(defn- secondary-identifiers [db-value]
  (set (keys (:secondary-indices db-value))))

(defn- secondary-roots [db-value]
  (reduce-kv
   (fn [roots identifier index]
     (assoc roots identifier
            (try
              (index-audit/-merkle-root index)
              (catch Throwable _ nil))))
   {}
   (:secondary-indices db-value)))

(defn- desired-main?
  [validate-db! expected-main selected-target target-db main-db]
  (validate-db! target-db)
  (validate-db! main-db)
  (let [actual (branch/head main-db)
        target-secondary-roots (secondary-roots target-db)
        main-secondary-roots (secondary-roots main-db)]
    (and (= (::branch/store-id expected-main)
            (::branch/store-id actual))
         (= :db (::branch/name actual))
         (= (::branch/basis-t selected-target) (::branch/basis-t actual))
         (not= (::branch/commit-id expected-main)
               (::branch/commit-id actual))
         (= #{(::branch/commit-id selected-target)}
            (set (d/parent-commit-ids main-db)))
         (same-ordered-values? (d/datoms target-db :eavt)
                               (d/datoms main-db :eavt))
         (= (secondary-identifiers target-db)
            (secondary-identifiers main-db))
         (every? some? (vals target-secondary-roots))
         (= target-secondary-roots main-secondary-roots))))

(defn- with-admin-connection
  [config force-called? operation]
  (let [connection
        (try
          (d/connect (id/allocation-connect-config config))
          (catch Throwable throwable
            (throw
             (ex-info
              "Restore administration could not prove failed-connect cleanup."
              {:seon.error/kind :seon.db.protocol.error/cleanup-required
               ::admin-connection-state
               :seon.db.restore-admin.connection/cleanup-unproved
               ::force-invoked? @force-called?}
              throwable))))
        operation-result
        (try
          {::result (operation connection)}
          (catch Throwable throwable
            {::operation-error throwable}))
        release-error
        (try
          (d/release connection)
          nil
          (catch Throwable throwable throwable))]
    (cond
      release-error
      (throw
       (ex-info
        "Restore administration could not prove connection release."
        {:seon.error/kind :seon.db.protocol.error/cleanup-required
         ::admin-connection-state
         :seon.db.restore-admin.connection/cleanup-unproved
         ::force-invoked? @force-called?
         ::release-error (.toString release-error)
         ::initialization-error
         (some-> (::operation-error operation-result) .toString)}
        (or (::operation-error operation-result) release-error)))

      (::operation-error operation-result)
      (let [throwable (::operation-error operation-result)]
        (throw
         (ex-info
          (.getMessage ^Throwable throwable)
          (assoc (or (ex-data throwable) {})
                 ::admin-connection-state
                 :seon.db.restore-admin.connection/released
                 ::force-invoked? @force-called?)
          throwable)))

      :else
      (::result operation-result))))

(defn admin-restore-main!
  "Move main to one prepared branch head without publishing runtime resources."
  {:malli/schema [:=> [:cat ::admin-restore-main!-request]
                  ::admin-restore-main!-response]}
  [{::keys [database-name backend path pre-restore-main-branch-head
            selected-target-branch-head prepared-target-branch-head
            undo-branch-head expected-branch-roster validate-db!]}]
  (let [main-connection-id (branch/connection-id pre-restore-main-branch-head)
        database-id (first main-connection-id)
        config (backend/datahike-config
                (cond-> {::backend/database-name database-name
                         ::backend/backend backend
                         ::branch/connection-id main-connection-id}
                  path (assoc ::backend/path path)))
        required-branches
        #{:db
          (::branch/name prepared-target-branch-head)
          (::branch/name undo-branch-head)}]
    (when-not (= :db (::branch/name pre-restore-main-branch-head))
      (lifecycle-fail!
       :seon.db.protocol.error/connection-id-mismatch
       "Restore administration requires the main :db branch."
       {::pre-restore-main-branch-head pre-restore-main-branch-head}))
    (when-not (and (= database-id
                      (::branch/store-id selected-target-branch-head)
                      (::branch/store-id prepared-target-branch-head)
                      (::branch/store-id undo-branch-head))
                   (= (::branch/commit-id selected-target-branch-head)
                      (::branch/commit-id prepared-target-branch-head))
                   (= (::branch/basis-t selected-target-branch-head)
                      (::branch/basis-t prepared-target-branch-head))
                   (= (::branch/commit-id pre-restore-main-branch-head)
                      (::branch/commit-id undo-branch-head))
                   (= (::branch/basis-t pre-restore-main-branch-head)
                      (::branch/basis-t undo-branch-head))
                   (every? expected-branch-roster required-branches))
      (lifecycle-fail!
       :seon.db.protocol.error/connection-id-mismatch
       "Restore administration branch-heads are not one prepared transition."
       {::pre-restore-main-branch-head pre-restore-main-branch-head
        ::selected-target-branch-head selected-target-branch-head
        ::prepared-target-branch-head prepared-target-branch-head
        ::undo-branch-head undo-branch-head
        ::expected-branch-roster expected-branch-roster}))
    (when-not (d/database-exists? config)
      (lifecycle-fail!
       :seon.db.protocol.error/not-found
       "Restore administration never creates a missing database."
       {::database-name database-name
        ::connection-id main-connection-id}))
    (let [force-called? (atom false)
          {::keys [admin-outcome force-invoked?]}
          (with-admin-connection
            config
            force-called?
            (fn [connection]
              (require-branch-roster! connection expected-branch-roster)
              (let [target-db
                    (branch-db-at! connection prepared-target-branch-head
                                   "Prepared target head")
                    _ (branch-db-at! connection undo-branch-head "Undo head")
                    main-db (d/branch-as-db connection :db)
                    actual-main (branch/head main-db)]
                (cond
                  (= pre-restore-main-branch-head actual-main)
                  (do
                    (validate-db! target-db)
                    (reset! force-called? true)
                    (call-datahike-lifecycle!
                     #(d/force-branch!
                       target-db :db
                       #{(::branch/commit-id selected-target-branch-head)}
                       {:expected-current-commit
                        (::branch/commit-id pre-restore-main-branch-head)})
                     {::pre-restore-main-branch-head
                      pre-restore-main-branch-head
                      ::selected-target-branch-head
                      selected-target-branch-head
                      ::force-invoked? true})
                    {::admin-outcome :seon.db.registry.admin/applied
                     ::force-invoked? true})

                  (desired-main? validate-db! pre-restore-main-branch-head
                                 selected-target-branch-head target-db main-db)
                  {::admin-outcome
                   :seon.db.registry.admin/already-applied
                   ::force-invoked? false}

                  :else
                  (lifecycle-fail!
                   :seon.db.protocol.error/restore-divergence
                   "Main is neither the expected nor the prepared restore value."
                   {::pre-restore-main-branch-head
                    pre-restore-main-branch-head
                    ::branch-head actual-main})))))
          final
          (try
            (with-admin-connection
              config
              force-called?
              (fn [connection]
                (let [roster (require-branch-roster!
                              connection expected-branch-roster)
                      target-db
                      (branch-db-at! connection prepared-target-branch-head
                                     "Prepared target head")
                      _ (branch-db-at! connection undo-branch-head "Undo head")
                      main-db (d/branch-as-db connection :db)
                      branch-head (branch/head main-db)]
                  (when-not
                    (desired-main? validate-db! pre-restore-main-branch-head
                                   selected-target-branch-head target-db main-db)
                    (lifecycle-fail!
                     :seon.db.protocol.error/restore-divergence
                     "Forced main failed complete restore read-back."
                     {::pre-restore-main-branch-head
                      pre-restore-main-branch-head
                      ::branch-head branch-head}))
                  {::branch-head branch-head
                   ::branch-roster roster})))
            (catch clojure.lang.ExceptionInfo throwable
              (throw
               (ex-info (.getMessage throwable)
                        (assoc (ex-data throwable)
                               ::force-invoked? @force-called?)
                        throwable))))]
      (merge
       {::admin-outcome admin-outcome
        ::pre-restore-main-branch-head pre-restore-main-branch-head
        ::selected-target-branch-head selected-target-branch-head
        ::prepared-target-branch-head prepared-target-branch-head
        ::undo-branch-head undo-branch-head
        ::force-invoked? force-invoked?
        ::admin-connection-state
        :seon.db.restore-admin.connection/released}
       final))))

(defn release-connection-id!
  "Release one exact logical connection-id without deleting its native branch."
  {:malli/schema [:=> [:cat ::release-connection-id!-request]
                  ::release-connection-id!-response]}
  [{::keys [target-database-name connection-id expected-target-head drain!]}]
  (let [action
        (locking !registry
          (if-let [entry (get @!registry target-database-name)]
            (do
              (when (seq (::transport-connections entry))
                (lifecycle-fail!
                 :seon.db.protocol.error/database-in-use
                 "The target database is still acquired by live connections."
                 {::target-database-name target-database-name
                  ::connection-id (::connection-id entry)}))
              (require-connection-id!
               "Release target" connection-id (::connection-id entry)
               {::target-database-name target-database-name})
              (require-head!
               :seon.db.protocol.error/stale-target-head
               "Target head" expected-target-head (current-branch-head entry)
               {::target-database-name target-database-name})
              (let [completion (promise)
                    releasing (assoc entry ::release-completion completion)]
                (swap! !registry assoc target-database-name releasing)
                {::completion completion ::entry releasing}))
            nil))]
    (if action
      (let [result (finish-final-release!
                    target-database-name (::completion action)
                    (::entry action) drain!)]
        (when-let [release-error (::release-error result)]
          (lifecycle-fail!
           :seon.db.protocol.error/release
           "The target database connection release is unproved."
           {::target-database-name target-database-name
            ::connection-id connection-id
            ::release-error release-error}))
        {::target-database-name target-database-name
         ::connection-id connection-id
         ::released? (::released? result)})
      {::target-database-name target-database-name
       ::connection-id connection-id
       ::released? false})))

(defn delete-branch!
  "Release and delete one exact non-main native branch."
  {:malli/schema [:=> [:cat ::delete-branch!-request]
                  ::delete-branch!-response]}
  [{::keys [source-database-name target-database-name connection-id
            expected-target-head drain!]}]
  (locking !registry
    (let [source-entry (require-ready-entry! source-database-name)
          source-connection-id (::connection-id source-entry)
          source-connection (::conn source-entry)
          branch (second connection-id)]
      (when (= :db branch)
        (lifecycle-fail!
         :seon.db.protocol.error/protected-main-branch
         "The main :db branch cannot be deleted."
         {::connection-id connection-id}))
      (when-not (= (first source-connection-id)
                   (first connection-id))
        (lifecycle-fail!
         :seon.db.protocol.error/connection-id-mismatch
         "The target branch does not belong to the source physical database."
         {::connection-id connection-id ::source-connection-id source-connection-id}))
      (require-connection-id!
       "Expected target head" connection-id
       (branch/connection-id expected-target-head)
       {::expected-target-head expected-target-head})
      (locking source-connection
        (let [target-entry (get @!registry target-database-name)
              released?
              (if target-entry
                (do
                  (require-connection-id!
                   "Delete target route" connection-id (::connection-id target-entry)
                   {::target-database-name target-database-name})
                  (require-head!
                   :seon.db.protocol.error/stale-target-head
                   "Target head" expected-target-head
                   (current-branch-head target-entry)
                   {::target-database-name target-database-name})
                  (::released?
                   (release-connection-id!
                    {::target-database-name target-database-name
                     ::connection-id connection-id
                     ::expected-target-head expected-target-head
                     ::drain! drain!})))
                false)
              branches (d/branches source-connection)]
          (when-not (contains? branches branch)
            (lifecycle-fail!
             :seon.db.protocol.error/branch-missing
             "The target branch is absent from the durable roster."
             {::connection-id connection-id}))
          (let [target-branch-head
                (branch/head
                 (d/branch-as-db source-connection branch))]
            (require-head!
             :seon.db.protocol.error/stale-target-head
             "Target head" expected-target-head target-branch-head
             {::target-database-name target-database-name})
            (call-datahike-lifecycle!
             #(d/delete-branch! source-connection branch)
             {::connection-id connection-id})
            (when (contains? (d/branches source-connection) branch)
              (lifecycle-fail!
               :seon.db.protocol.error/cleanup-required
               "The target branch remained in the durable roster after deletion."
               {::connection-id connection-id}))
            {::target-database-name target-database-name
             ::connection-id connection-id
             ::branch-head (current-branch-head source-entry)
             ::released? released?
             ::deleted? true}))))))

(defn release-database!
  "Release the administrative reference for `database-name`.

   The entry is dropped when no live transport connection retains it; otherwise
   those connections continue to use their own Datahike references.
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
    (if-let [{::keys [conn release-error release-completion ensured?
                      transport-connections]
              :as entry}
             (get @!registry database-name)]
      (cond
        release-error
        {::database-name database-name
         ::released? false
         ::release-error release-error}

        release-completion
        {::database-name database-name ::released? false}

        (false? ensured?)
        {::database-name database-name ::released? false}

        :else
        (try
          (d/release conn)
          (if (seq transport-connections)
            (swap! !registry assoc database-name (assoc entry ::ensured? false))
            (swap! !registry dissoc database-name))
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
    (if-let [{::keys [connection-id backend path]}
             (get @!registry database-name)]
      (cond
        (not= :db (second connection-id))
        {::released? false ::deleted? false
         ::error "delete-database! only accepts the physical database's :db route"}

        (some (fn [[registered-name entry]]
                (and (not= registered-name database-name)
                     (= (first connection-id)
                        (first (::connection-id entry)))))
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
                                ::branch/connection-id connection-id}
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

(defn lookup-connection
  "Return `{::conn <conn>}` if `database-name` is registered, else `{}`.
   Does NOT auto-create — callers must `ensure-database!` first."
  {:malli/schema [:=> [:cat ::connection-request] ::connection-response]}
  [{::keys [database-name]}]
  (let [entry (get @!registry database-name)]
    (if (and entry (not (cleanup-required? entry)))
      {::conn (::conn entry)}
      {})))

(defn list-databases
  "Return `{::databases [...]}` — one summary per registered database.
   Live conns are NOT exposed (use `lookup-connection`). Order is unspecified."
  {:malli/schema [:=> [:cat ::list-databases-request] ::list-databases-response]}
  [{}]
  {::databases (mapv (fn [[database-name entry]] (summary database-name entry))
                    @!registry)})

(defn resolve-connection
  "Resolve one explicitly named database connection.

   A registered name returns its stable connection-id and freshly derived current
   branch-head with the live connection. An unknown name returns a typed
   not-found value."
  {:malli/schema [:=> [:cat ::resolve-connection-request] ::resolve-connection-response]}
  [{::keys [database-name transport-connection] :as request}]
  (if-let [entry (get @!registry database-name)]
    (if (and (not (cleanup-required? entry))
             (nil? (::release-completion entry))
             (or (not (contains? request ::transport-connection))
                 (owns-transport-connection?
                  (::transport-connections entry #{}) transport-connection)))
      (entry-view database-name entry)
      (if (cleanup-required? entry)
        {::error-kind :seon.db.registry.error/cleanup-required
         ::error (str "database open cleanup is unproved: " database-name)}
        {::error-kind :seon.db.registry.error/not-found
         ::error (str "database is not acquired by this connection: "
                      database-name)}))
    {::error-kind :seon.db.registry.error/not-found
     ::error (str "unknown database-name: " database-name)}))

(defn acquired-transport-connections
  "Snapshot the physical sessions acquiring one exact database connection-id."
  {:malli/schema
   [:=> [:cat ::acquired-transport-connections-request]
    ::acquired-transport-connections-response]}
  [{::keys [database-name connection-id]}]
  (locking !registry
    (let [entry (get @!registry database-name)]
      {::transport-connections
       (if (and entry
                (= connection-id (::connection-id entry))
                (not (cleanup-required? entry))
                (nil? (::release-completion entry)))
         (::transport-connections entry #{})
         #{})})))

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
