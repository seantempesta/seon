(ns seon.server.store
  "Build datahike config maps for the wire-server's per-session DBs.

   Path B (no flow): each session in `seon.server.registry/registry` calls
   `(datahike.api/connect cfg)` with one of these maps. Two backends
   supported:

   - `:memory` — testing only; no disk artifacts. Stable per-name id-uuid
     so the same `db-name` yields the same in-memory store across calls.
   - `:file`   — durable file-tree konserve.

   Default path layout (per `integration-architecture-2026-05-26.md` §3):

       data/sessions/<short>/store

   where `<short>` is the name portion of the db-name keyword. Caller may
   override via `::path`.

   Config shape is the wire-server's per-session datahike store config.

   `config-for` is a pure function — no filesystem side effects. The
   caller (the session registry, Wave 2) is responsible for invoking
   `ensure-parent-dir!` before `(datahike.api/create-database cfg)` when
   it actually opens the store. The base path is currently relative
   (`data/sessions/`); making it configurable is Wave 5 work."
  (:require [clojure.string :as str]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.util UUID]))

;;; --- Schemas ---------------------------------------------------------------

(schema/register! ::db-name :keyword)
(schema/register! ::backend [:enum :memory :file])
(schema/register! ::path [:string {:min 1}])

(schema/register! ::config-for-request
                  [:map
                   [::db-name ::db-name]
                   [::backend ::backend]
                   [::path {:optional true} ::path]])

;; The returned cfg is an opaque datahike config map. We don't constrain
;; its shape here — datahike's own schema validates it at connect time.
(schema/register! ::config-for-response :map)

(schema/register! ::ensure-parent-dir-request
                  [:map [::path ::path]])

(schema/register! ::ensure-parent-dir-response
                  [:map [::created? :boolean]])

;;; --- Helpers (private) -----------------------------------------------------

(defn- name-segment
  "Filesystem-safe segment derived from a db-name keyword. `(name kw)`
   already drops the namespace portion, so `:seon.cluster/alice` and
   `:alice` both yield `\"alice\"`. Hostile chars are replaced with `_`."
  [db-name]
  (str/replace (name db-name) #"[^A-Za-z0-9._-]" "_"))

(defn- default-path
  "Default file-store path for a session database."
  [db-name]
  (str "data/sessions/" (name-segment db-name) "/store"))

(defn- bare-name?
  "True if `p` is a non-absolute path with no directory component — it
   would create its konserve store directly in the process CWD (the repo
   root). Stray bare-path stores (e.g. `:path \"Bh\"`) polluted the repo
   root for weeks; we re-root them under `data/`."
  [p]
  (let [f (File. ^String p)]
    (and (not (.isAbsolute f))
         (nil? (.getParent f)))))

(defn- harden-file-path
  "Guard against the repo-root-pollution footgun. A bare path (no
   directory component, not absolute) would plant the store in CWD;
   re-root it under `data/sessions/` using the same layout as
   `default-path`. Paths that already carry a directory (`tmp/...`,
   `data/...`) or are absolute pass through unchanged."
  [p]
  (if (bare-name? p)
    (str "data/sessions/" p "/store")
    p))

(defn- name->uuid
  "Deterministic UUID derived from a db-name keyword. konserve uses a
   `:id` UUID to namespace stored data; deriving from the db-name means
   re-opening the same name lands on the same store."
  [db-name]
  (UUID/nameUUIDFromBytes (.getBytes (str db-name) "UTF-8")))

(def ^:private base-cfg
  "Keys shared across all backends. Backend-specific code only fills
   in `:store`."
  {:keep-history?      true
   :schema-flexibility :write})

;;; --- Public ----------------------------------------------------------------

(defn config-for
  "Build a datahike config map for a session DB. Pure — no I/O.

   Required:
     ::db-name — keyword identifying the session (e.g. :seon.cluster/alice).
     ::backend — :memory | :file.

   Optional:
     ::path    — override the default on-disk path. Ignored for :memory."
  {:malli/schema [:=> [:cat ::config-for-request] ::config-for-response]}
  [{::keys [db-name backend path]}]
  (let [id   (name->uuid db-name)
        nm   (str db-name)
        store (case backend
                :memory {:backend :memory :id id}
                :file   {:backend :file
                         :path    (harden-file-path (or path (default-path db-name)))
                         :id      id})]
    (assoc base-cfg :store store :name nm)))

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
