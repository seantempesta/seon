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
            [datahike.connections :as connections]
            [datahike.store :as datahike.store]
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

; a cluster branch name in the one store's roster
(schema/register! :seon.store/branch :keyword)

(schema/register!
 :seon.store/branch-connection
 [:fn 'seon.cluster.store/connection?])

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

;;; The path IS the store identity twice over — the Konserve store id
;;; and the lock file both derive from it — so the canonical form is
;;; taken ONCE here and every other derivation reads it. Two spellings
;;; of one physical directory must never become two locks on one store.
(defn- canonical-path
  "The one physical spelling of `store-dir`."
  [store-dir]
  (.getCanonicalPath (io/file store-dir)))

(defn lock-file
  "The store's lock-file path.
  A sibling of the CANONICAL store directory (`<canonical>.lock`) so it
  never enters Konserve's key namespace, and so every spelling of one
  physical directory (relative, `./`-prefixed, absolute) yields the ONE
  lock file — two spellings must never hold two locks on one store.
  One derivation — no other code builds this path."
  {:malli/schema [:=> [:cat :seon.store/dir] :seon.store/lock-file]}
  [store-dir]
  (str (canonical-path store-dir) ".lock"))

(defn datahike-configuration
  "The one Datahike configuration for a cluster store.
  `:file` backend at the CANONICAL path of `store-dir` (the store id
  derives from the path, so every spelling of one physical directory
  must be the ONE store), write-time schema flexibility. Pure data —
  the single place the configuration shape lives."
  {:malli/schema [:=> [:cat :seon.store/dir] [:map]]}
  [store-dir]
  (let [path (canonical-path store-dir)]
    {:store {:backend :file
             :path path
             ; Konserve requires a UUID store id, and Datahike keys its
             ; connection registry, schema caches, and GC guard on it.
             ; The id must therefore be a pure function of the canonical
             ; path, or a reopen — or another spelling of the same
             ; directory — would present itself as a different store.
             :id (java.util.UUID/nameUUIDFromBytes
                  (.getBytes ^String path "UTF-8"))}
     :writer {:backend :self}
     :schema-flexibility :write}))

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
;;; `.tryLock` return nil.
;;;
;;; A flock is held by the PROCESS, so this process's own holdings are
;;; the half the OS cannot express, and they must be answered before a
;;; second descriptor is ever opened. Java's FileLock is implemented
;;; with fcntl, whose close(2) semantics drop EVERY lock the process
;;; holds on a file as soon as ANY descriptor to it is closed. Opening a
;;; second channel to a lock file we already hold and then closing it —
;;; the obvious way to answer OverlappingFileLockException — therefore
;;; unlocks the store at the OS level while `.isValid` still reports
;;; true, and a foreign JVM walks in (falsified live: parent holds,
;;; parent refuses its own second open, child JVM then ACQUIRES). This
;;; table is the process's own holdings; it is not a second fence.
(defonce ^:private held-flocks (atom {}))

(defn- acquire-flock!
  "Acquire the exclusive flock on `lock-path`, or nil when it is held."
  [lock-path]
  (locking held-flocks
    (when-not (contains? @held-flocks lock-path)
      (let [file (io/file lock-path)
            _ (some-> (.getParentFile file) (.mkdirs))
            channel (FileChannel/open
                     (.toPath file)
                     (into-array OpenOption [StandardOpenOption/CREATE
                                             StandardOpenOption/WRITE]))
            lock (try
                   (.tryLock channel)
                   (catch OverlappingFileLockException _
                     ; unreachable while this table is the one opener;
                     ; if it ever happens some other code in this JVM
                     ; holds the file, so LEAK the descriptor rather
                     ; than close it and drop the process's fence
                     ::foreign-channel-in-this-jvm)
                   (catch Throwable failure
                     (.close channel)
                     (throw failure)))]
        (cond
          (keyword? lock) nil
          lock (do (swap! held-flocks assoc lock-path lock) lock)
          ; no lock of ours on this file, so closing drops nothing
          :else (do (.close channel) nil))))))

(defn- release-flock!
  "Release the flock held at `lock-path` and close its channel."
  [lock-path ^FileLock lock]
  (locking held-flocks
    (swap! held-flocks dissoc lock-path)
    (let [channel (.channel lock)]
      (try
        (when (.isValid lock)
          (.release lock))
        (finally
          ; the descriptor closes even on a failed release, and closing
          ; it drops the fcntl lock anyway — the fence never outlives a
          ; release attempt
          (.close channel)))))
  nil)

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
  "Open (creating if absent) the ONE physical store this process owns,
  fenced, on its main branch. B2 revision: one closed map argument;
  under branch-per-cluster the store is PER PROCESS ROOT and every
  cluster is a branch of it.
  Order is the contract: acquire the non-blocking exclusive flock on
  `(lock-file store-dir)` FIRST — a held lock refuses immediately
  ({::rule ::held-elsewhere}, including a second open in this same
  process) — then existence-check, then create or verify: a store whose
  genesis is incomplete (`:db` present, `:branches` missing — the
  first-create kill window) is deleted and recreated; a complete store
  is connected and its main branch verified readable. Returns the store
  value; the flock descriptor stays held inside it until
  `release-store!`."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [:seon.store/dir :seon.store/dir]]]
                  :seon.store/store]}
  [{store-dir :seon.store/dir}]
  ; one physical spelling for the whole lifecycle: the fence, the store
  ; id, the genesis probe, and the returned value all name one directory
  (let [dir (canonical-path store-dir)
        lock-path (lock-file dir)
        lock (or (acquire-flock! lock-path)
                 (refuse! ::held-elsewhere
                          (str "the store at " dir
                               " is held by a live process")
                          {::dir dir ::lock-file lock-path}))]
    (try
      (let [configuration (datahike-configuration dir)
            created? (cond
                       (not (d/database-exists? configuration))
                       (do (create-store! dir configuration) true)

                       (genesis-complete? dir)
                       false

                       ; :db without :branches — killed mid-genesis, so
                       ; nothing durable ever existed. Recreate.
                       :else
                       (do (create-store! dir configuration) true))
            connection (d/connect configuration)]
        ; readiness is a COMPLETE connection over a COMPLETE store: the
        ; main branch must be readable before this value escapes
        (try
          (d/db connection)
          (catch Throwable failure
            (d/release connection)
            (throw failure)))
        {:seon.store/dir dir
         :seon.store/lock-file lock-path
         :seon.store/connection connection
         :seon.store/lock lock
         :seon.store/created? created?})
      (catch Throwable failure
        (release-flock! lock-path lock)
        (throw failure)))))

(defn release-store!
  "Release the store: Datahike release first, then the flock.
  THE FENCE OUTLIVES A FAILED RELEASE: when the Datahike release
  throws, the flock is NOT released and the error propagates loudly —
  a live connection behind a dropped fence is the two-writers loss and
  must be unrepresentable. Only a successful Datahike release frees the
  flock. Idempotent — releasing a released store is a no-op returning
  nil. After a successful release the same process (or any other) may
  open the store again."
  {:malli/schema [:=> [:cat :seon.store/store] :nil]}
  [store]
  ; the flock's own validity IS the released? fact — no second flag, and
  ; nothing in the (closed, immutable) store value has to change
  (let [^FileLock lock (:seon.store/lock store)]
    (when (.isValid lock)
      (d/release (:seon.store/connection store))
      (release-flock! (:seon.store/lock-file store) lock)))
  nil)

(defonce ^:private branch-open-monitor (Object.))

(defn open-branch!
  "A connection to one branch of this already-open, flock-held store.
  The branch must exist in the roster — creation belongs to the one
  branch-lifecycle owner (seon.cluster.registry, B2). Refuses
  `::branch-absent` (not in the roster) and `::branch-already-open`
  (this process already holds a connection to that branch — Datahike
  would reference-count a second connect into the SAME connection,
  silently giving two cluster instances one writer)."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch]
                  :seon.store/branch-connection]}
  [store branch]
  (locking branch-open-monitor
    (let [main-connection (:seon.store/connection store)
          configuration (assoc (datahike-configuration
                                (:seon.store/dir store))
                               :branch branch)
          connection-id (datahike.store/connection-id configuration)]
      (when-not (contains? (d/branches main-connection) branch)
        (refuse! ::branch-absent
                 (str "branch " branch " is absent from the store roster")
                 {::dir (:seon.store/dir store)
                  ::branch branch}))
      (when (contains? @connections/*connections* connection-id)
        (refuse! ::branch-already-open
                 (str "branch " branch
                      " already has a connection in this process")
                 {::dir (:seon.store/dir store)
                  ::branch branch}))
      (d/connect configuration))))
