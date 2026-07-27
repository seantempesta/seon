(ns seon.db.backend
  "Translate Seon database options into private Datahike/Konserve config.

   Each entry in `seon.db.registry` calls `(datahike.api/connect cfg)` with
   one of these maps. Two backends
   supported:

   - `:memory` — testing only; no disk artifacts.
   - `:file`   — durable file-tree Konserve.

   Default path layout (per `integration-architecture-2026-05-26.md` §3):

       data/clusters/<short>/db

   where `<short>` is the name portion of the database-name keyword. Caller may
   override via `::path`.

   A request may carry an explicit Datahike `[store-id branch]` connection ID. Without
   one, the adapter derives the historical per-name UUID and main `:db` branch.
   The returned map is Datahike's third-party config shape. Its literal
   unqualified `:store` key is intentionally confined to this adapter.

   `datahike-config` is a pure function — no filesystem side effects. The
   caller (the database registry) is responsible for invoking
   `ensure-parent-dir!` before `(datahike.api/create-database cfg)` when
   it actually opens the database. The base path is currently relative
   (`data/clusters/`)."
  (:require [clojure.string :as str]
            [seon.db.branch :as branch]
            [seon.db.protocol :as protocol]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.util UUID]))

;;; --- Schemas ---------------------------------------------------------------

(schema/register! ::database-name :keyword)
(schema/register! ::backend [:enum :memory :file])
(schema/register! ::path [:string {:min 1}])
(schema/register! ::initial-tx [:vector :map])

(schema/register! ::datahike-config-request
                  (into
                   [:map
                    [::database-name ::database-name]
                    [::backend ::backend]
                    [::path {:optional true} ::path]
                    [::branch/connection-id {:optional true}
                     ::branch/connection-id]
                    [::initial-tx {:optional true} ::initial-tx]]
                   (map (fn [[key value-schema]]
                          [key {:optional true} value-schema]))
                   protocol/writer-connection-key-schemas))

;; The returned cfg is an opaque datahike config map. We don't constrain
;; its shape here — datahike's own schema validates it at connect time.
(schema/register! ::datahike-config-response :map)

(schema/register!
 ::backend-facts
 [:map
  [::branch/connection-id ::branch/connection-id]
  [::path {:optional true} ::path]])

(schema/register! ::ensure-parent-dir-request
                  [:map [::path ::path]])

(schema/register! ::ensure-parent-dir-response
                  [:map [::created? :boolean]])

;;; --- Helpers (private) -----------------------------------------------------

(defn- name-segment
  "Filesystem-safe segment derived from a database-name keyword. `(name kw)`
   already drops the namespace portion, so `:seon.cluster/alice` and
   `:alice` both yield `\"alice\"`. Hostile chars are replaced with `_`."
  [database-name]
  (str/replace (name database-name) #"[^A-Za-z0-9._-]" "_"))

(defn- default-path
  "Default file-backend path for one database."
  [database-name]
  (str "data/clusters/" (name-segment database-name) "/db"))

(defn- bare-name?
  "True if `p` is a non-absolute path with no directory component — it
   would create Konserve data directly in the process CWD (the repo root).
   Stray bare paths (e.g. `:path \"Bh\"`) polluted the repo
   root for weeks; we re-root them under `data/`."
  [p]
  (let [f (File. ^String p)]
    (and (not (.isAbsolute f))
         (nil? (.getParent f)))))

(defn- harden-file-path
  "Guard against the repo-root-pollution footgun. A bare path (no
   directory component, not absolute) would plant data in CWD;
   re-root it under `data/clusters/` using the same layout as
   `default-path`. Paths that already carry a directory (`tmp/...`,
   `data/...`) or are absolute pass through unchanged."
  [p]
  (if (bare-name? p)
    (str "data/clusters/" p "/db")
    p))

(defn store-id
  "Return the deterministic Datahike store ID for a database name."
  {:malli/schema [:=> [:catn [::database-name ::database-name]] :uuid]}
  [database-name]
  (UUID/nameUUIDFromBytes (.getBytes (str database-name) "UTF-8")))

(defn backend-facts
  "Resolve the Datahike connection ID and optional durable path.

   This is the only public projection of backend identity. Callers never read
   Datahike's private `:store` map."
  {:malli/schema [:=> [:cat ::datahike-config-request] ::backend-facts]}
  [{::keys [database-name backend path]
    connection-id ::branch/connection-id}]
  (cond-> {::branch/connection-id
           (or connection-id [(store-id database-name) :db])}
    (= :file backend)
    (assoc ::path (harden-file-path (or path (default-path database-name))))))

(def ^:private base-cfg
  "Keys shared across all backends. Backend-specific code only fills in
   Datahike's private `:store` value."
  {:keep-history?      true
   :fuse-index-roots?  true
   :schema-flexibility :write})

;;; --- Public ----------------------------------------------------------------

(defn datahike-config
  "Build a Datahike config map for one database. Pure — no I/O.

   Required:
     ::database-name — keyword identifying the database.
     ::backend — :memory | :file.

   Optional:
     ::path                  — override the on-disk path; ignored for :memory.
     ::branch/connection-id — explicit physical database and native branch.

   This is the one translation site from Seon's writer-construction keys to
   Datahike's `:transaction-queue-size`, `:commit-queue-size`, and
   `:commit-wait-time` (milliseconds). With no supplied values, this function
   emits no `:writer` map, leaving Datahike's defaults authoritative.

   Datahike's `:self` writer serially transforms `db-before` to `db-after` into
   a commit queue. Its commit thread drains the offered queue as one batch,
   commits it, then waits `commit-wait-time` ms before the next drain. Offered
   load therefore grows batches; a nonzero wait trades transaction latency for
   larger batches rather than acting as a throughput knob.

   On a non-sole-tenant MacBook Pro M5 Max (18 cores, 128 GB, macOS 26.5.2),
   OpenJDK 26.0.1/G1 `-Xmx4g`, Datahike caf52685, and local APFS, 65,536
   concurrent callers over 131,072 fixed transactions reached 4,336.40 tx/s
   in nine commits. Doubling callers was 16.48% worse; 131,072 callers over
   262,144 transactions failed with `OutOfMemoryError` while the 120,000-entry
   transaction queue exceeded 90% (117,553 observed) and the commit queue
   exceeded 50% (60,006). At moderate concurrency the wall was APFS metadata
   fsync: 38,580 `jdk.FileForce` events in one 60 s JFR interval, p50 8.14 ms,
   with JVM CPU at 1.01% user / 5.20% system and zero
   `jdk.VirtualThreadPinned` events. Queue sizes are OOM guards;
   `commit-wait-time` is only the latency/batch trade."
  {:malli/schema [:=> [:cat ::datahike-config-request]
                  ::datahike-config-response]}
  [{::keys [database-name backend initial-tx] :as request}]
  (let [{connection-id ::branch/connection-id
         path ::path} (backend-facts request)
        [store-id branch] connection-id
        options (case backend
                  :memory {:backend :memory :id store-id}
                  :file   {:backend :file
                           :path path
                           :id store-id})
        writer
        (cond-> {}
          (contains? request
                     :seon.config.database.writer/transaction-queue-size)
          (assoc :transaction-queue-size
                 (:seon.config.database.writer/transaction-queue-size request))
          (contains? request
                     :seon.config.database.writer/commit-queue-size)
          (assoc :commit-queue-size
                 (:seon.config.database.writer/commit-queue-size request))
          (contains? request
                     :seon.config.database.writer/commit-wait-time-ms)
          (assoc :commit-wait-time
                 (:seon.config.database.writer/commit-wait-time-ms request)))]
    (cond-> (assoc base-cfg
                   :store options
                   :branch branch
                   :name (str database-name))
      (seq writer) (assoc :writer writer)
      (seq initial-tx) (assoc :initial-tx initial-tx))))

(defn ensure-parent-dir!
  "Side-effecting helper for callers about to call
   `(datahike.api/create-database cfg)`. Creates the parent directory of
   `::path` if it does not already exist. No-op for relative paths whose
   parent is `nil` (e.g. a path with no `/`). Returns
   `{::created? bool}` indicating whether `mkdirs` actually created
   anything (true) vs. the directory already existed (false). Throws if
   creation fails for a real reason (permission denied, file in the way)."
  {:malli/schema [:=> [:cat ::ensure-parent-dir-request]
                  ::ensure-parent-dir-response]}
  [{::keys [path]}]
  (let [f      (File. ^String path)
        parent (.getParentFile f)]
    (cond
      (nil? parent)
      {::created? false}

      (.exists parent)
      {::created? false}

      :else
      (let [ok? (.mkdirs parent)]
        (when-not ok?
          (throw (ex-info "Failed to create parent directory"
                          {::path path
                           ::parent (.getPath parent)})))
        {::created? true}))))
