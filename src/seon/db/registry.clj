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
   passes it on every open. The initializer receives one request describing
   the exact attachment and whether the open is main or branch-observational.
   A failed initializer releases the new connection and leaves no
   half-initialized registry entry."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [datahike.index.audit :as index-audit]
            [seon.db.coordinate :as coordinate]
            [seon.db.id :as id]
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
(schema/register! ::attachment ::coordinate/attachment)
(schema/register! ::coordinate ::coordinate/coordinate)
(schema/register! ::release-error :string)
(schema/register! ::initialization-error :string)
(schema/register! ::entry-state
                  [:enum :seon.db.registry.entry/ready
                   :seon.db.registry.entry/cleanup-required])
(schema/register! ::open-intent
                  [:enum :seon.db.registry.open/main
                   :seon.db.registry.open/branch])
(schema/register! ::source-coordinate ::coordinate)
(schema/register! ::expected-source-head ::coordinate)
(schema/register! ::expected-target-head ::coordinate)
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
(schema/register! ::main-coordinate ::coordinate)
(schema/register! ::branch-coordinates [:map-of :keyword ::coordinate])
(schema/register! ::pre-restore-main-coordinate ::coordinate)
(schema/register! ::selected-target-coordinate ::coordinate)
(schema/register! ::prepared-target-coordinate ::coordinate)
(schema/register! ::undo-coordinate ::coordinate)
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
                   [::attachment ::attachment]
                   [::backend ::backend]
                   [::path {:optional true} ::path]
                   [::release-error {:optional true} ::release-error]
                   [::initialization-error {:optional true}
                    ::initialization-error]])

(schema/register! ::ensure-database!-request
                  [:map
                   [::database-name ::database-name]
                   [::backend {:optional true} ::backend]
                   [::path {:optional true} ::path]
                   [::attachment {:optional true} ::attachment]
                   [::initial-tx {:optional true} ::initial-tx]
                   [::initialize-connection! 'fn?]])

(schema/register! ::initialize-connection-request
                  [:map
                   [::conn ::conn]
                   [::database-name ::database-name]
                   [::attachment ::attachment]
                   [::open-intent ::open-intent]])

(schema/register! ::entry-view
                  [:map
                   [::database-name ::database-name]
                   [::conn ::conn]
                   [::attachment ::attachment]
                   [::coordinate ::coordinate]
                   [::backend ::backend]
                   [::entry-state ::entry-state]
                   [::path {:optional true} ::path]
                   [::release-error {:optional true} ::release-error]
                   [::initialization-error {:optional true}
                    ::initialization-error]])

(schema/register! ::ensure-database!-response ::entry-view)

(schema/register!
 ::observe-database-lifecycle-request
 [:map {:closed true}
  [::database-name ::database-name]])
(schema/register!
 ::observe-database-lifecycle-response
 [:map {:closed true}
  [::database-name ::database-name]
  [::main-coordinate ::main-coordinate]
  [::branch-coordinates ::branch-coordinates]
  [::branch-roster ::branch-roster]])

(schema/register!
 ::create-branch!-request
 [:map
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::source-coordinate ::source-coordinate]
  [::expected-source-head ::expected-source-head]
  [::target-branch ::target-branch]
  [::initialize-connection! 'fn?]])
(schema/register!
 ::create-branch!-response
 [:map
  [::target-database-name ::target-database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::backend ::backend]
  [::path {:optional true} ::path]
  [::created? ::created?]
  [::adopted? ::adopted?]])

(schema/register!
 ::release-attachment!-request
 [:map
  [::target-database-name ::target-database-name]
  [::attachment ::attachment]
  [::expected-target-head ::expected-target-head]])
(schema/register!
 ::release-attachment!-response
 [:map
  [::target-database-name ::target-database-name]
  [::attachment ::attachment]
  [::released? :boolean]])

(schema/register!
 ::delete-branch!-request
 [:map
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::attachment ::attachment]
  [::expected-target-head ::expected-target-head]])
(schema/register!
 ::delete-branch!-response
 [:map
  [::target-database-name ::target-database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::released? :boolean]
  [::deleted? ::deleted?]])

(schema/register!
 ::admin-restore-main!-request
 [:map {:closed true}
  [::database-name ::database-name]
  [::backend ::backend]
  [::path {:optional true} ::path]
  [::pre-restore-main-coordinate ::pre-restore-main-coordinate]
  [::selected-target-coordinate ::selected-target-coordinate]
  [::prepared-target-coordinate ::prepared-target-coordinate]
  [::undo-coordinate ::undo-coordinate]
  [::expected-branch-roster ::expected-branch-roster]
  [::validate-db! ::validate-db!]])
(schema/register!
 ::admin-restore-main!-response
 [:map {:closed true}
  [::admin-outcome ::admin-outcome]
  [::pre-restore-main-coordinate ::pre-restore-main-coordinate]
  [::selected-target-coordinate ::selected-target-coordinate]
  [::prepared-target-coordinate ::prepared-target-coordinate]
  [::undo-coordinate ::undo-coordinate]
  [::coordinate ::coordinate]
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
                   [::attachment ::attachment]
                   [::coordinate ::coordinate]
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
                  [:map [::database-name ::database-name]])

(schema/register! ::resolve-connection-response
                  [:map
                   [::conn {:optional true} ::conn]
                   [::database-name {:optional true} ::database-name]
                   [::attachment {:optional true} ::attachment]
                   [::coordinate {:optional true} ::coordinate]
                   [::error-kind {:optional true}
                    [:enum :seon.db.registry.error/not-found
                     :seon.db.registry.error/cleanup-required]]
                   [::error {:optional true} :string]])

;;; --- Registry --------------------------------------------------------------

(defonce ^:private !registry
  ;; {database-name -> entry-map}
  (atom {}))

(defn- current-coordinate
  [{::keys [conn]}]
  (coordinate/resolved (d/db conn)))

(defn- cleanup-required?
  [entry]
  (boolean (or (::release-error entry)
               (::initialization-error entry))))

(defn- derived-entry-state
  [entry]
  (if (cleanup-required? entry)
    :seon.db.registry.entry/cleanup-required
    :seon.db.registry.entry/ready))

(defn- entry-view
  [database-name entry]
  (assoc entry
         ::database-name database-name
         ::entry-state (derived-entry-state entry)
         ::coordinate (current-coordinate entry)))

(defn- summary
  "Public view of an entry, sans live conn."
  [database-name {::keys [attachment backend path release-error
                          initialization-error]
                  :as entry}]
  (cond-> {::database-name database-name
           ::attachment attachment
           ::coordinate (current-coordinate entry)
           ::backend backend
           ::entry-state (derived-entry-state entry)}
    path (assoc ::path path)
    release-error (assoc ::release-error release-error)
    initialization-error (assoc ::initialization-error initialization-error)))

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
  (if (cleanup-required? entry)
    (throw
     (ex-info "A failed database open still requires resource cleanup."
              {:seon.error/kind :seon.db.registry.error/cleanup-required
               ::database-name database-name
               ::attachment (::attachment entry)
               ::coordinate (current-coordinate entry)
               ::release-error (::release-error entry)
               ::initialization-error (::initialization-error entry)}))
    (entry-view database-name entry)))

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
    (let [conn (d/connect (id/allocation-connect-config cfg))
          entry (cond-> {::conn conn
                         ::attachment attachment
                         ::backend backend-kind}
                  path (assoc ::path path))]
      (try
        (id/assert-allocation-writer! conn)
        (let [actual (coordinate/attachment (coordinate/resolved (d/db conn)))]
          (when-not (= attachment actual)
            (fail-attachment!
             "Datahike connected a different database attachment."
             {::database-name database-name
              ::attachment attachment
              ::actual-attachment actual})))
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
                   ::attachment attachment
                   ::coordinate (current-coordinate retained)
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
                  conn (::conn entry)
                  branch (::coordinate/branch attachment*)
                  open-intent (if (= :db branch)
                                :seon.db.registry.open/main
                                :seon.db.registry.open/branch)
                  coordinate-before (current-coordinate entry)]
              (try
                (initialize-connection!
                 {::conn conn
                  ::database-name database-name
                  ::attachment attachment*
                  ::open-intent open-intent})
                (when (and (= :seon.db.registry.open/branch open-intent)
                           (not= coordinate-before (current-coordinate entry)))
                  (throw
                   (ex-info
                    "A non-main branch initializer changed its database head."
                    {:seon.error/kind
                     :seon.db.registry.error/branch-initializer-wrote
                     ::database-name database-name
                     ::attachment attachment*
                     ::coordinate-before coordinate-before
                     ::coordinate-after (current-coordinate entry)})))
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
                           ::attachment attachment*
                           ::coordinate (current-coordinate retained)
                           ::initialization-error (.toString throwable)
                           ::release-error (.toString release-throwable)}
                          throwable)))))
                  (throw throwable))))))))))

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
        ::attachment (::attachment entry)
        ::coordinate (current-coordinate entry)
        ::release-error (::release-error entry)})

      :else entry)))

(defn- require-attachment!
  [label expected actual data]
  (when-not (= expected actual)
    (lifecycle-fail!
     :seon.db.protocol.error/attachment-mismatch
     (str label " belongs to a different database attachment.")
     (assoc data ::expected-attachment expected ::actual-attachment actual))))

(defn- require-head!
  [kind label expected actual data]
  (when-not (= expected actual)
    (lifecycle-fail!
     kind
     (str label " changed before the lifecycle operation.")
     (assoc data ::expected-coordinate expected ::coordinate actual))))

(defn- observe-native-branch-coordinates!
  [database-name connection attachment roster]
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
             (let [resolved (coordinate/resolved db-value)
                   expected-attachment
                   (assoc attachment ::coordinate/branch branch)]
               (require-attachment!
                "Observed native branch" expected-attachment
                (coordinate/attachment resolved)
                {::database-name database-name
                 ::target-branch branch
                 ::coordinate resolved})
               [branch resolved])))
         (sort roster))))

(defn observe-database-lifecycle
  "Observe every exact native branch head from the registered main route."
  {:malli/schema
   [:=> [:cat ::observe-database-lifecycle-request]
    ::observe-database-lifecycle-response]}
  [{::keys [database-name]}]
  (locking !registry
    (let [entry (require-ready-entry! database-name)
          attachment (::attachment entry)
          connection (::conn entry)]
      (when-not (= :db (::coordinate/branch attachment))
        (lifecycle-fail!
         :seon.db.protocol.error/attachment-mismatch
         "Database lifecycle observation requires the main :db route."
         {::database-name database-name
          ::attachment attachment}))
      (locking connection
        (let [roster-before (set (d/branches connection))
              coordinates-before
              (observe-native-branch-coordinates!
               database-name connection attachment roster-before)
              roster-middle (set (d/branches connection))
              branch-coordinates
              (observe-native-branch-coordinates!
               database-name connection attachment roster-before)
              roster-after (set (d/branches connection))
              main-coordinate (get branch-coordinates :db)]
          (when-not (= roster-before roster-middle roster-after)
            (lifecycle-fail!
             :seon.db.protocol.error/stale-branch-roster
             "The native branch roster changed during lifecycle observation."
             {::database-name database-name
              ::expected-branch-roster roster-before
              ::branch-roster roster-after}))
          (when-not (= coordinates-before branch-coordinates)
            (lifecycle-fail!
             :seon.db.protocol.error/stale-target-head
             "A native branch head changed during lifecycle observation."
             {::database-name database-name
              ::expected-coordinate coordinates-before
              ::coordinate branch-coordinates}))
          (when-not (and (contains? roster-before :db)
                         main-coordinate
                         (= roster-before (set (keys branch-coordinates))))
            (lifecycle-fail!
             :seon.db.protocol.error/stale-branch-roster
             "Database lifecycle observation is incomplete."
             {::database-name database-name
              ::branch-roster roster-before
              ::branch-coordinates branch-coordinates}))
          {::database-name database-name
           ::main-coordinate main-coordinate
           ::branch-coordinates branch-coordinates
           ::branch-roster roster-before})))))

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
  [source-entry target-database-name target-branch attachment cause]
  (when-let [target-entry (get @!registry target-database-name)]
    (let [{::keys [released? release-error]}
          (release-database! {::database-name target-database-name})]
      (when-not released?
        (lifecycle-fail!
         :seon.db.protocol.error/cleanup-required
         "Branch open failed and target connection cleanup is unproved."
         {::target-database-name target-database-name
          ::attachment attachment
          ::coordinate (current-coordinate target-entry)
          ::release-error release-error
          ::initialization-error (.toString cause)}))))
  (try
    (d/delete-branch! (::conn source-entry) target-branch)
    (catch Throwable cleanup-throwable
      (lifecycle-fail!
       :seon.db.protocol.error/cleanup-required
       "Branch open failed and target branch cleanup is unproved."
       {::target-database-name target-database-name
        ::attachment attachment
        ::release-error (.toString cleanup-throwable)
        ::initialization-error (.toString cause)})))
  (when (contains? (d/branches (::conn source-entry)) target-branch)
    (lifecycle-fail!
     :seon.db.protocol.error/cleanup-required
     "Target branch remained in the durable roster after cleanup."
     {::target-database-name target-database-name
      ::attachment attachment
      ::initialization-error (.toString cause)})))

(defn- branch-result
  [target-database-name attachment coordinate backend-kind path created?]
  (cond-> {::target-database-name target-database-name
           ::attachment attachment
           ::coordinate coordinate
           ::backend backend-kind
           ::created? created?
           ::adopted? (not created?)}
    path (assoc ::path path)))

(defn- reconcile-existing-target!
  [{::keys [target-database-name target-branch source-connection attachment
            backend path]
    target-entry ::entry}]
  (let [actual-coordinate (current-coordinate target-entry)]
    (require-attachment!
     "Existing target route" attachment (::attachment target-entry)
     {::target-database-name target-database-name})
    (when-not (contains? (d/branches source-connection) target-branch)
      (lifecycle-fail!
       :seon.db.protocol.error/branch-missing
       "The existing target route is absent from the durable branch roster."
       {::target-database-name target-database-name
        ::attachment attachment}))
    (when-not (and (= backend (::backend target-entry))
                   (= path (::path target-entry)))
      (lifecycle-fail!
       :seon.db.protocol.error/duplicate-route
       "The target route uses different physical database coordinates."
       {::target-database-name target-database-name
        ::attachment attachment}))
    (branch-result target-database-name attachment actual-coordinate
                   backend path false)))

(defn- retained-source-db!
  [source-connection source-attachment source-coordinate]
  (let [commit-db
        (call-datahike-lifecycle!
         #(d/commit-as-db source-connection
                          (::coordinate/commit-id source-coordinate))
         {::source-coordinate source-coordinate})]
    (when-not commit-db
      (lifecycle-fail!
       :seon.db.protocol.error/missing-commit
       "The requested source commit is not retained."
       {::source-coordinate source-coordinate}))
    (let [commit-coordinate (coordinate/resolved commit-db)]
      (require-attachment!
       "Retained source commit" source-attachment
       (coordinate/attachment commit-coordinate)
       {::source-coordinate source-coordinate
        ::coordinate commit-coordinate})
      (when-not (= (::coordinate/commit-id source-coordinate)
                   (::coordinate/commit-id commit-coordinate))
        (lifecycle-fail!
         :seon.db.protocol.error/missing-commit
         "The retained commit did not resolve to the requested id."
         {::source-coordinate source-coordinate
          ::coordinate commit-coordinate}))
      (when-not (= (::coordinate/t source-coordinate)
                   (::coordinate/t commit-coordinate))
        (lifecycle-fail!
         :seon.db.protocol.error/cut-not-branchable
         "The requested temporal cut is not the containing commit head."
         {::source-coordinate source-coordinate
          ::coordinate commit-coordinate})))
    commit-db))

(defn create-branch!
  "Create or adopt one exact native branch and publish its logical route."
  {:malli/schema [:=> [:cat ::create-branch!-request]
                  ::create-branch!-response]}
  [{::keys [source-database-name target-database-name source-coordinate
            expected-source-head target-branch initialize-connection!]}]
  (locking !registry
    (let [source-entry (require-ready-entry! source-database-name)
          source-attachment (::attachment source-entry)
          source-connection (::conn source-entry)
          target-attachment
          (assoc source-attachment ::coordinate/branch target-branch)
          expected-target-coordinate
          (assoc source-coordinate ::coordinate/branch target-branch)
          backend-kind (::backend source-entry)
          path (::path source-entry)]
      (when (= :db target-branch)
        (lifecycle-fail!
         :seon.db.protocol.error/protected-main-branch
         "The main :db branch cannot be a lifecycle target."
         {::target-branch target-branch}))
      (require-attachment!
       "Source coordinate" source-attachment
       (coordinate/attachment source-coordinate)
       {::source-coordinate source-coordinate})
      (require-attachment!
       "Expected source head" source-attachment
       (coordinate/attachment expected-source-head)
       {::expected-source-head expected-source-head})
      (if-let [target-entry (get @!registry target-database-name)]
        (locking source-connection
          (reconcile-existing-target!
           {::target-database-name target-database-name
            ::target-branch target-branch
            ::source-connection source-connection
            ::attachment target-attachment
            ::backend backend-kind
            ::path path
            ::entry target-entry}))
        (do
          (when (some (fn [[_ entry]]
                        (= target-attachment (::attachment entry)))
                      @!registry)
            (lifecycle-fail!
             :seon.db.protocol.error/duplicate-attachment
             "The target database attachment already has a logical route."
             {::target-database-name target-database-name
              ::attachment target-attachment}))
          (locking source-connection
            (let [existing?
                  (contains? (d/branches source-connection) target-branch)
                  _ (when-not existing?
                      (require-head!
                       :seon.db.protocol.error/stale-source-head
                       "Source head" expected-source-head
                       (current-coordinate source-entry)
                       {::source-database-name source-database-name}))
                  commit-db
                  (retained-source-db! source-connection source-attachment
                                       source-coordinate)
                  _ (when-not existing?
                      (call-datahike-lifecycle!
                       #(d/branch! source-connection
                                   (::coordinate/commit-id source-coordinate)
                                   target-branch)
                       {::target-branch target-branch}))]
              (try
                (let [target-db
                      (d/branch-as-db source-connection target-branch)
                      target-coordinate (coordinate/resolved target-db)
                      _ (when (and existing?
                                   (not= expected-target-coordinate
                                         target-coordinate))
                          (lifecycle-fail!
                           :seon.db.protocol.error/branch-exists
                           "The target branch exists at a different coordinate."
                           {::expected-coordinate expected-target-coordinate
                            ::coordinate target-coordinate}))
                      _ (when-not (same-ordered-values?
                                   (d/datoms commit-db :eavt)
                                   (d/datoms target-db :eavt))
                          (lifecycle-fail!
                           :seon.db.protocol.error/initializer
                           "The target branch primary datoms differ from its source commit."
                           {::source-coordinate source-coordinate
                            ::coordinate target-coordinate}))
                      entry
                      (ensure-database!
                       (cond-> {::database-name target-database-name
                                ::backend backend-kind
                                ::attachment target-attachment
                                ::initialize-connection!
                                initialize-connection!}
                         path (assoc ::path path)))
                      actual-coordinate (::coordinate entry)]
                  (when-not (= expected-target-coordinate actual-coordinate)
                    (lifecycle-fail!
                     :seon.db.protocol.error/initializer
                     "The opened target branch moved from its exact fork point."
                     {::expected-coordinate expected-target-coordinate
                      ::coordinate actual-coordinate}))
                  (branch-result target-database-name target-attachment
                                 actual-coordinate backend-kind path
                                 (not existing?)))
                (catch Throwable throwable
                  (when-not existing?
                    (delete-unpublished-branch!
                     source-entry target-database-name target-branch
                     target-attachment throwable))
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
  (let [branch (::coordinate/branch expected)
        db-value (d/branch-as-db connection branch)]
    (when-not db-value
      (lifecycle-fail!
       :seon.db.protocol.error/branch-missing
       (str label " is absent from durable storage.")
       {::coordinate expected}))
    (let [actual (coordinate/resolved db-value)]
      (require-head!
       :seon.db.protocol.error/stale-target-head
       label expected actual {::coordinate expected})
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
  (let [actual (coordinate/resolved main-db)
        target-secondary-roots (secondary-roots target-db)
        main-secondary-roots (secondary-roots main-db)]
    (and (= (::coordinate/database-id expected-main)
            (::coordinate/database-id actual))
         (= :db (::coordinate/branch actual))
         (= (::coordinate/t selected-target) (::coordinate/t actual))
         (not= (::coordinate/commit-id expected-main)
               (::coordinate/commit-id actual))
         (= #{(::coordinate/commit-id selected-target)}
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
  [{::keys [database-name backend path pre-restore-main-coordinate
            selected-target-coordinate prepared-target-coordinate
            undo-coordinate expected-branch-roster validate-db!]}]
  (let [main-attachment (coordinate/attachment pre-restore-main-coordinate)
        database-id (::coordinate/database-id main-attachment)
        config (backend/datahike-config
                (cond-> {::backend/database-name database-name
                         ::backend/backend backend
                         ::coordinate/attachment main-attachment}
                  path (assoc ::backend/path path)))
        required-branches
        #{:db
          (::coordinate/branch prepared-target-coordinate)
          (::coordinate/branch undo-coordinate)}]
    (when-not (= :db (::coordinate/branch pre-restore-main-coordinate))
      (lifecycle-fail!
       :seon.db.protocol.error/attachment-mismatch
       "Restore administration requires the main :db branch."
       {::pre-restore-main-coordinate pre-restore-main-coordinate}))
    (when-not (and (= database-id
                      (::coordinate/database-id selected-target-coordinate)
                      (::coordinate/database-id prepared-target-coordinate)
                      (::coordinate/database-id undo-coordinate))
                   (= (::coordinate/commit-id selected-target-coordinate)
                      (::coordinate/commit-id prepared-target-coordinate))
                   (= (::coordinate/t selected-target-coordinate)
                      (::coordinate/t prepared-target-coordinate))
                   (= (::coordinate/commit-id pre-restore-main-coordinate)
                      (::coordinate/commit-id undo-coordinate))
                   (= (::coordinate/t pre-restore-main-coordinate)
                      (::coordinate/t undo-coordinate))
                   (every? expected-branch-roster required-branches))
      (lifecycle-fail!
       :seon.db.protocol.error/attachment-mismatch
       "Restore administration coordinates are not one prepared transition."
       {::pre-restore-main-coordinate pre-restore-main-coordinate
        ::selected-target-coordinate selected-target-coordinate
        ::prepared-target-coordinate prepared-target-coordinate
        ::undo-coordinate undo-coordinate
        ::expected-branch-roster expected-branch-roster}))
    (when-not (d/database-exists? config)
      (lifecycle-fail!
       :seon.db.protocol.error/not-found
       "Restore administration never creates a missing database."
       {::database-name database-name
        ::attachment main-attachment}))
    (let [force-called? (atom false)
          {::keys [admin-outcome force-invoked?]}
          (with-admin-connection
            config
            force-called?
            (fn [connection]
              (require-branch-roster! connection expected-branch-roster)
              (let [target-db
                    (branch-db-at! connection prepared-target-coordinate
                                   "Prepared target head")
                    _ (branch-db-at! connection undo-coordinate "Undo head")
                    main-db (d/branch-as-db connection :db)
                    actual-main (coordinate/resolved main-db)]
                (cond
                  (= pre-restore-main-coordinate actual-main)
                  (do
                    (validate-db! target-db)
                    (reset! force-called? true)
                    (call-datahike-lifecycle!
                     #(d/force-branch!
                       target-db :db
                       #{(::coordinate/commit-id selected-target-coordinate)}
                       {:expected-current-commit
                        (::coordinate/commit-id pre-restore-main-coordinate)})
                     {::pre-restore-main-coordinate
                      pre-restore-main-coordinate
                      ::selected-target-coordinate
                      selected-target-coordinate
                      ::force-invoked? true})
                    {::admin-outcome :seon.db.registry.admin/applied
                     ::force-invoked? true})

                  (desired-main? validate-db! pre-restore-main-coordinate
                                 selected-target-coordinate target-db main-db)
                  {::admin-outcome
                   :seon.db.registry.admin/already-applied
                   ::force-invoked? false}

                  :else
                  (lifecycle-fail!
                   :seon.db.protocol.error/restore-divergence
                   "Main is neither the expected nor the prepared restore value."
                   {::pre-restore-main-coordinate
                    pre-restore-main-coordinate
                    ::coordinate actual-main})))))
          final
          (try
            (with-admin-connection
              config
              force-called?
              (fn [connection]
                (let [roster (require-branch-roster!
                              connection expected-branch-roster)
                      target-db
                      (branch-db-at! connection prepared-target-coordinate
                                     "Prepared target head")
                      _ (branch-db-at! connection undo-coordinate "Undo head")
                      main-db (d/branch-as-db connection :db)
                      coordinate (coordinate/resolved main-db)]
                  (when-not
                    (desired-main? validate-db! pre-restore-main-coordinate
                                   selected-target-coordinate target-db main-db)
                    (lifecycle-fail!
                     :seon.db.protocol.error/restore-divergence
                     "Forced main failed complete restore read-back."
                     {::pre-restore-main-coordinate
                      pre-restore-main-coordinate
                      ::coordinate coordinate}))
                  {::coordinate coordinate
                   ::branch-roster roster})))
            (catch clojure.lang.ExceptionInfo throwable
              (throw
               (ex-info (.getMessage throwable)
                        (assoc (ex-data throwable)
                               ::force-invoked? @force-called?)
                        throwable))))]
      (merge
       {::admin-outcome admin-outcome
        ::pre-restore-main-coordinate pre-restore-main-coordinate
        ::selected-target-coordinate selected-target-coordinate
        ::prepared-target-coordinate prepared-target-coordinate
        ::undo-coordinate undo-coordinate
        ::force-invoked? force-invoked?
        ::admin-connection-state
        :seon.db.restore-admin.connection/released}
       final))))

(defn release-attachment!
  "Release one exact logical attachment without deleting its native branch."
  {:malli/schema [:=> [:cat ::release-attachment!-request]
                  ::release-attachment!-response]}
  [{::keys [target-database-name attachment expected-target-head]}]
  (locking !registry
    (if-let [entry (get @!registry target-database-name)]
      (do
        (require-attachment!
         "Release target" attachment (::attachment entry)
         {::target-database-name target-database-name})
        (require-head!
         :seon.db.protocol.error/stale-target-head
         "Target head" expected-target-head (current-coordinate entry)
         {::target-database-name target-database-name})
        (let [{::keys [released? release-error]}
              (release-database! {::database-name target-database-name})]
          (when (and (not released?) release-error)
            (lifecycle-fail!
             :seon.db.protocol.error/release
             "The target database connection release is unproved."
             {::target-database-name target-database-name
              ::attachment attachment
              ::release-error release-error}))
          {::target-database-name target-database-name
           ::attachment attachment
           ::released? released?}))
      {::target-database-name target-database-name
       ::attachment attachment
       ::released? false})))

(defn delete-branch!
  "Release and delete one exact non-main native branch."
  {:malli/schema [:=> [:cat ::delete-branch!-request]
                  ::delete-branch!-response]}
  [{::keys [source-database-name target-database-name attachment
            expected-target-head]}]
  (locking !registry
    (let [source-entry (require-ready-entry! source-database-name)
          source-attachment (::attachment source-entry)
          source-connection (::conn source-entry)
          branch (::coordinate/branch attachment)]
      (when (= :db branch)
        (lifecycle-fail!
         :seon.db.protocol.error/protected-main-branch
         "The main :db branch cannot be deleted."
         {::attachment attachment}))
      (when-not (= (::coordinate/database-id source-attachment)
                   (::coordinate/database-id attachment))
        (lifecycle-fail!
         :seon.db.protocol.error/attachment-mismatch
         "The target branch does not belong to the source physical database."
         {::attachment attachment ::source-attachment source-attachment}))
      (require-attachment!
       "Expected target head" attachment
       (coordinate/attachment expected-target-head)
       {::expected-target-head expected-target-head})
      (locking source-connection
        (let [target-entry (get @!registry target-database-name)
              released?
              (if target-entry
                (do
                  (require-attachment!
                   "Delete target route" attachment (::attachment target-entry)
                   {::target-database-name target-database-name})
                  (require-head!
                   :seon.db.protocol.error/stale-target-head
                   "Target head" expected-target-head
                   (current-coordinate target-entry)
                   {::target-database-name target-database-name})
                  (::released?
                   (release-attachment!
                    {::target-database-name target-database-name
                     ::attachment attachment
                     ::expected-target-head expected-target-head})))
                false)
              branches (d/branches source-connection)]
          (when-not (contains? branches branch)
            (lifecycle-fail!
             :seon.db.protocol.error/branch-missing
             "The target branch is absent from the durable roster."
             {::attachment attachment}))
          (let [target-coordinate
                (coordinate/resolved
                 (d/branch-as-db source-connection branch))]
            (require-head!
             :seon.db.protocol.error/stale-target-head
             "Target head" expected-target-head target-coordinate
             {::target-database-name target-database-name})
            (call-datahike-lifecycle!
             #(d/delete-branch! source-connection branch)
             {::attachment attachment})
            (when (contains? (d/branches source-connection) branch)
              (lifecycle-fail!
               :seon.db.protocol.error/cleanup-required
               "The target branch remained in the durable roster after deletion."
               {::attachment attachment}))
            {::target-database-name target-database-name
             ::attachment attachment
             ::coordinate (current-coordinate source-entry)
             ::released? released?
             ::deleted? true}))))))

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

   A registered name returns its stable attachment and freshly derived current
   coordinate with the live connection. An unknown name returns a typed
   not-found value."
  {:malli/schema [:=> [:cat ::resolve-connection-request] ::resolve-connection-response]}
  [{::keys [database-name]}]
  (if-let [entry (get @!registry database-name)]
    (if-not (cleanup-required? entry)
      (entry-view database-name entry)
      {::error-kind :seon.db.registry.error/cleanup-required
       ::error (str "database open cleanup is unproved: " database-name)})
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
