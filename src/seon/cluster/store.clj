(ns seon.cluster.store
  "The store: one Datahike store per cluster, opened under the lifetime
  flock.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — the B1 rung,
  grounded in research/datahike-multistore-2026-07-27.md; every rule
  below carries file:line evidence there). The schemas and function
  contracts are SEALED: the implementation lane fills the stub bodies
  until test/seon/cluster/store_test.clj is green and may not loosen a
  schema or a test. Friction is reported, never resolved by weakening.

  The model:

  - One store component per canonical physical store; a JVM may host N
    concurrently; nothing obtains \"the\" store from an ambient
    singleton — callers hold the store value `open-store!` returns.
  - Exactly ONE live write connection per physical store, and clusters
    never share a store (O2/L6: two JVMs writing one store silently
    destroyed 40/40 commits). Datahike's own serialization stops at the
    connection boundary — its process registry and Konserve's lock
    atoms do NOT span two JVMs — so the fence is OURS: one non-blocking
    exclusive flock on the store's lock file, acquired BEFORE existence
    check, creation, or connect, held until final release. The lock
    file lives BESIDE the store directory (never inside Konserve's key
    namespace, which enumerates its files).
  - Datahike runs itself: the `:self` writer is a serial loop per
    connection with its own transaction/commit queues. This namespace
    never builds a writer, never queues across stores — it opens,
    verifies, and releases.
  - Readiness is a COMPLETELY opened connection over a COMPLETELY
    initialized store. The first-create kill window is real: `:db` can
    exist while `:branches` is missing. `open-store!` detects that
    state and repairs by RECREATE — provably mid-genesis means nothing
    durable existed, and clusters always reset to current code and
    pages, never migrate.
  - Errors refuse loudly as ex-info {:seon.error/kind
    :seon.cluster.store/refused, ::rule <which>}: a foreign flock, a
    store that fails initialization verification after repair, an
    already-open store in this process.

  Crash walk (kill -9 at any point):
  - before the flock: nothing exists, next boot proceeds;
  - after flock, before create: an empty/partial store dir with no
    `:db` — recreate;
  - mid-create (`:db` present, `:branches` missing) — detected,
    recreate;
  - after create/connect: a complete store; the OS released the dead
    process's flock, reopen proceeds cleanly and committed datoms are
    all present (the child-process falsifier proves exactly this);
  - a live holder is NEVER displaced: the flock refusal is immediate
    and loud, no waiting, no takeover."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [konserve.core :as k]
            [konserve.filestore :as filestore]
            [seon.schema :as schema])
  (:import [java.nio.channels FileChannel FileLock OverlappingFileLockException]
           [java.nio.file OpenOption StandardOpenOption]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(schema/register! :seon.store/dir [:string {:min 1}])
; the lock file BESIDE the store directory — derived, never configured
(schema/register! :seon.store/lock-file [:string {:min 1}])

(defn connection?
  "True for a live (unreleased) Datahike connection.
  Grounded in the fork's own connection spec: a datahike.connector
  Connection whose wrapped state is not `:released`
  (reference-code/datahike/src/datahike/connector.cljc:104)."
  [value]
  (and (instance? datahike.connector.Connection value)
       (some? (:wrapped-atom value))
       (not= @(:wrapped-atom value) :released)))

(defn file-lock?
  "True for a held java.nio.channels.FileLock."
  [value]
  (and (instance? java.nio.channels.FileLock value)
       (.isValid ^java.nio.channels.FileLock value)))

(schema/register-core-predicate! 'seon.cluster.store/connection?
                                 connection?)
(schema/register-core-predicate! 'seon.cluster.store/file-lock?
                                 file-lock?)

(schema/register!
 :seon.store/store
 [:map {:closed true}
  [:seon.store/dir :seon.store/dir]
  [:seon.store/lock-file :seon.store/lock-file]
  [:seon.store/connection [:fn 'seon.cluster.store/connection?]]
  [:seon.store/lock [:fn 'seon.cluster.store/file-lock?]]
  ; true when open-store! created (or recreated) the store this call
  [:seon.store/created? :boolean]])

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(defn lock-file
  "The store's lock-file path.
  A sibling of the CANONICAL store directory (`<canonical>.lock`) so it
  never enters Konserve's key namespace, and so every spelling of one
  physical directory (relative, `./`-prefixed, absolute) yields the ONE
  lock file — two spellings must never hold two locks on one store.
  One derivation — no other code builds this path."
  {:malli/schema [:=> [:cat :seon.store/dir] :seon.store/lock-file]}
  [store-dir]
  (str store-dir ".lock"))

(defn datahike-configuration
  "The one Datahike configuration for a cluster store.
  `:file` backend at the CANONICAL path of `store-dir` (the store id
  derives from the path, so every spelling of one physical directory
  must be the ONE store), write-time schema flexibility. Pure data —
  the single place the configuration shape lives."
  {:malli/schema [:=> [:cat :seon.store/dir] [:map]]}
  [store-dir]
  {:store {:backend :file
           :path store-dir
           ; Konserve requires a UUID store id, and Datahike keys its
           ; connection registry, schema caches, and GC guard on it. The
           ; id must therefore be a pure function of the path, or a
           ; reopen would present itself as a different store.
           :id (java.util.UUID/nameUUIDFromBytes
                (.getBytes ^String store-dir "UTF-8"))}
   :writer {:backend :self}
   :schema-flexibility :write})

;;; ---------------------------------------------------------------------------
;;; Lifecycle
;;; ---------------------------------------------------------------------------

(defn- refuse!
  "Refuse loudly with the one store error shape."
  [rule message data]
  (throw (ex-info message
                  (assoc data
                         :seon.error/kind ::refused
                         ::rule rule))))

;;; The flock. Non-blocking and exclusive: a foreign holder makes
;;; `.tryLock` return nil, a holder in THIS JVM makes it throw
;;; OverlappingFileLockException (a JVM holds one lock per file region
;;; for the whole process). Both are the same refusal.
(defn- acquire-flock!
  "Acquire the exclusive flock on `lock-path`, or nil when it is held."
  [lock-path]
  (let [file (io/file lock-path)]
    (some-> (.getParentFile file) (.mkdirs))
    (let [channel (FileChannel/open
                   (.toPath file)
                   (into-array OpenOption [StandardOpenOption/CREATE
                                           StandardOpenOption/WRITE]))
          lock (try
                 (.tryLock channel)
                 (catch OverlappingFileLockException _ nil)
                 (catch Throwable failure
                   (.close channel)
                   (throw failure)))]
      (if lock
        lock
        (do (.close channel) nil)))))

(defn- release-flock!
  "Release a held flock and close its channel."
  [^FileLock lock]
  (let [channel (.channel lock)]
    (when (.isValid lock)
      (.release lock))
    (.close channel)
    nil))

(defn- delete-store-directory!
  "Delete the store directory and everything under it."
  [store-dir]
  (let [root (io/file store-dir)]
    (when (.exists root)
      (doseq [entry (reverse (file-seq root))]
        (.delete ^java.io.File entry))))
  nil)

;;; Genesis writes the immutable commit, then the mutable `:db` branch
;;; head, then the `:branches` roster LAST. `database-exists?` reads only
;;; `:db`, so `:branches` presence is the one fact that separates a
;;; complete store from the first-create kill window.
(defn- genesis-complete?
  "True when the store's `:branches` roster was written."
  [store-dir]
  (let [konserve (filestore/connect-fs-store store-dir :opts {:sync? true})]
    (some? (k/get konserve :branches nil {:sync? true}))))

(defn- create-store!
  "Create a fresh store at `store-dir`, verifying genesis completed."
  [store-dir configuration]
  (delete-store-directory! store-dir)
  (d/create-database configuration)
  (when-not (genesis-complete? store-dir)
    (refuse! ::initialization-incomplete
             (str "the store at " store-dir
                  " has no branch roster after creation")
             {::dir store-dir}))
  nil)

(defn open-store!
  "Open (creating if absent) the one store at `store-dir`, fenced.
  Order is the contract: acquire the non-blocking exclusive flock on
  `(lock-file store-dir)` FIRST — a held lock refuses immediately
  ({::rule ::held-elsewhere}, including a second open in this same
  process) — then existence-check, then create or verify: a store whose
  genesis is incomplete (`:db` present, `:branches` missing — the
  first-create kill window) is deleted and recreated; a complete store
  is connected and its main branch verified readable. Returns the store
  value; the flock descriptor stays held inside it until
  `release-store!`."
  {:malli/schema [:=> [:cat :seon.store/dir] :seon.store/store]}
  [store-dir]
  (let [lock-path (lock-file store-dir)
        lock (or (acquire-flock! lock-path)
                 (refuse! ::held-elsewhere
                          (str "the store at " store-dir
                               " is held by a live process")
                          {::dir store-dir ::lock-file lock-path}))]
    (try
      (let [configuration (datahike-configuration store-dir)
            created? (cond
                       (not (d/database-exists? configuration))
                       (do (create-store! store-dir configuration) true)

                       (genesis-complete? store-dir)
                       false

                       ; :db without :branches — killed mid-genesis, so
                       ; nothing durable ever existed. Recreate.
                       :else
                       (do (create-store! store-dir configuration) true))
            connection (d/connect configuration)]
        ; readiness is a COMPLETE connection over a COMPLETE store: the
        ; main branch must be readable before this value escapes
        (try
          (d/db connection)
          (catch Throwable failure
            (d/release connection)
            (throw failure)))
        {:seon.store/dir store-dir
         :seon.store/lock-file lock-path
         :seon.store/connection connection
         :seon.store/lock lock
         :seon.store/created? created?})
      (catch Throwable failure
        (release-flock! lock)
        (throw failure)))))

(defn release-store!
  "Release the store: Datahike release first, then the flock.
  Idempotent — releasing a released store is a no-op returning nil.
  After release the same process (or any other) may open the store
  again."
  {:malli/schema [:=> [:cat :seon.store/store] :nil]}
  [store]
  ; the flock's own validity IS the released? fact — no second flag, and
  ; nothing in the (closed, immutable) store value has to change
  (let [^FileLock lock (:seon.store/lock store)]
    (when (.isValid lock)
      (d/release (:seon.store/connection store))
      (release-flock! lock)))
  nil)
