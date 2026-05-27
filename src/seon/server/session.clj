(ns seon.server.session
  "Path B session registry — atom of `{db-name -> entry}` where each
   entry is `{::conn <datahike-conn> ::backend kw ::path str-or-nil
   ::pub-chan core.async-chan-or-nil}`.

   Direct `datahike.api/connect`. No flow. The wire-server (Wave 4)
   will look up conns here on every request. Multiple sessions, one
   process, all in-memory atoms — no coordination overhead.

   Idempotent semantics: `ensure-db!` on an existing db-name returns
   the same entry (identical? conn). `remove-db!` on an absent name
   is a no-op returning `{::removed? false}`. Concurrent ensures on
   the same name race exactly once — losers see the winner's conn.

   See `docs/prds/agent-runtime/integration-architecture-2026-05-26.md`
   §1.5 (Path B) and §5 (session registration). Lifecycle shape lifted
   from `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:74-77`
   (V2 PoC), generalized from one conn to N.

   The `::pub-chan` slot is wired for Wave 4 (broadcast subscription)
   but populated as `nil` in Wave 2. Tx-event broadcast is not this
   ns's job."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.server.store :as store]))

;;; --- Schemas ---------------------------------------------------------------

(schema/register! ::db-name :seon.server.store/db-name)
(schema/register! ::backend :seon.server.store/backend)
(schema/register! ::path :seon.server.store/path)

;; A live conn is an opaque clojure.lang.IAtom2 (datahike connection
;; type). We don't constrain its shape; the registry hands it out as-is.
(schema/register! ::conn [:fn some?])

;; Optional core.async pub-chan reserved for Wave 4 broadcast wiring.
;; Always nil in Wave 2; Wave 4 will narrow this to a real channel
;; type check via `instance?` once the broadcast wiring lands.
(schema/register! ::pub-chan [:fn nil?])

(schema/register! ::entry
                  [:map
                   [::conn ::conn]
                   [::backend ::backend]
                   [::path {:optional true} ::path]
                   [::pub-chan {:optional true} ::pub-chan]])

(schema/register! ::ensure-db!-request
                  [:map
                   [::db-name ::db-name]
                   [::backend {:optional true} ::backend]
                   [::path {:optional true} ::path]])

(schema/register! ::ensure-db!-response ::entry)

(schema/register! ::remove-db!-response
                  [:map [::removed? :boolean]])

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

(schema/register! ::snapshot-registry-request [:map])
(schema/register! ::snapshot-registry-response [:map [::snapshot :map]])

(schema/register! ::restore-registry-request [:map [::snapshot :map]])
(schema/register! ::restore-registry-response [:map [::restored? :boolean]])

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
    (let [conn (d/connect cfg)]
      (cond-> {::conn conn
               ::backend backend
               ::pub-chan nil}
        store-path (assoc ::path store-path)))))

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
        {::removed? true})
      {::removed? false})))

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

;;; --- Test seam -------------------------------------------------------------

(defn ^:no-doc snapshot-registry
  "Test helper: capture the current registry for restoration. Used by
   `session_test.clj`'s fixture to isolate test state."
  {:malli/schema [:=> [:cat ::snapshot-registry-request] ::snapshot-registry-response]}
  [{}]
  {::snapshot @!registry})

(defn ^:no-doc restore-registry!
  "Test helper: replace registry contents with `::snapshot`. Releases
   any conns that were added since the snapshot was taken."
  {:malli/schema [:=> [:cat ::restore-registry-request] ::restore-registry-response]}
  [{::keys [snapshot]}]
  (locking !registry
    (let [current @!registry
          extra-names (set/difference (set (keys current))
                                      (set (keys snapshot)))]
      (doseq [n extra-names]
        (when-let [{::keys [conn]} (get current n)]
          (try (d/release conn) (catch Throwable _))))
      (reset! !registry snapshot)
      {::restored? true})))
