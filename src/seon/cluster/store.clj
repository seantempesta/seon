(ns seon.cluster.store
  "Owns Datahike stores, branch connections, and transaction admission.

  `open-store!` canonicalizes a store directory and acquires its
  non-blocking exclusive flock before checking or creating the
  database. It recreates an incomplete genesis, verifies the main
  branch is readable, and returns the connected store with the flock
  held until `release-store!`. A live holder is refused rather than
  displaced.

  `open-branch!` opens one existing roster branch and refuses a second
  connection to that branch in the process. Datahike's `:self` writer
  owns transaction serialization. `transact!` returns transaction
  reports or classified error values, with the configured core-error
  mode deciding whether an unclassified failure also panics."
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [datahike.connections :as connections]
            [datahike.db.utils :as db.utils]
            [datahike.store :as datahike.store]
            [konserve.core :as k]
            [konserve.filestore :as filestore]
            [clojure.test.check.generators :as gen]
            [seon.error :as error]
            [seon.fs :as fs]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn])
  (:import [java.nio.channels FileChannel FileLock OverlappingFileLockException]
           [java.nio.file OpenOption StandardOpenOption]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(defn connection?
  "True for a live (unreleased) Datahike connection.
  Grounded in the fork's own connection spec: a datahike.connector
  Connection whose wrapped state is not `:released`
  (reference-code/datahike/src/datahike/connector.cljc:104)."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (instance? datahike.connector.Connection value)
       (some? (:wrapped-atom value))
       (not= @(:wrapped-atom value) :released)))

(defn connection-object?
  "True for a Datahike connection, live or RELEASED.
  A different question from `connection?`, and the difference is the
  point: a started instance holds a connection that will outlive its
  own liveness, and `stop!` must accept exactly that value — its
  docstring promises idempotence, so the second call necessarily
  receives a released one. Requiring liveness there made the contract
  forbid the case the function exists to handle, which instrumentation
  found on its first run. Liveness stays required where work is done
  through it (`transact!`, the loop handle, the wake listener)."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? datahike.connector.Connection value))

(defn file-lock?
  "True for a held java.nio.channels.FileLock."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (instance? java.nio.channels.FileLock value)
       (.isValid ^java.nio.channels.FileLock value)))

(defn database-value?
  "True for any Datahike database value."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (db.utils/db? value))

(schema/register-core-predicate! 'seon.cluster.store/connection-object?
                                 connection-object?)

(schema/register-core-predicate! 'seon.cluster.store/connection?
                                 connection?)
(schema/register-core-predicate! 'seon.cluster.store/file-lock?
                                 file-lock?)
(schema/register-core-predicate! 'seon.cluster.store/database-value?
                                 database-value?)

(defn- fresh-connection
  []
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}]
    (d/create-database configuration)
    (d/connect configuration)))

(defn- fresh-file-lock
  []
  (let [lock-file
        (io/file "tmp/schema-generator" (str (random-uuid) ".lock"))
        _ (.mkdirs (.getParentFile lock-file))
        channel
        (FileChannel/open
         (.toPath lock-file)
         (into-array
          OpenOption
          [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
        lock (.tryLock channel)]
    (or lock
        (throw
         (ex-info
          "The file-lock generator could not acquire its fresh lock."
          {:seon.error/kind :core-bug
           ::lock-file (.getPath lock-file)})))))

(def connection-generator
  ;; Generated lifecycles may release their connection, so every sample owns
  ;; a new one rather than mutating a singleton used by later samples.
  (gen/fmap (fn [_] (fresh-connection)) (gen/return nil)))
(def file-lock-generator
  ;; Each generated store lifecycle owns and releases its lock. Reusing one
  ;; singleton made later samples invalid after the first generated stop!.
  (gen/fmap (fn [_] (fresh-file-lock)) (gen/return nil)))
(def database-value-generator
  (gen/fmap
   (fn [variant]
     (let [database @(fresh-connection)]
       (case variant
         :current database
         :as-of (d/as-of database (:max-tx database))
         :since (d/since database 0)
         :history (d/history database))))
   (gen/elements [:current :as-of :since :history])))

(schema.edn/load! {})

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
  "The creation configuration for a cluster store.
  `:file` backend at the CANONICAL path of `store-dir` (the store id
  derives from the path, so every spelling of one physical directory
  must be the ONE store), fused persistent-set roots with a 256-entry
  diff buffer, retained history by the settled default policy, and
  write-time schema flexibility. Fusion and index settings are
  creation-only; reopen configurations omit them so Datahike adopts the
  stored values."
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
     ; Rulings #23/#40: ordinary stores retain history. Scratch/eval stores
     ; may be non-temporal only after their separate creation + reader
     ; mechanism is proven; do not inherit this decision from Datahike.
     :keep-history? true
     :fuse-index-roots? true
     :index-config {:diff-buf-size 256}
     :schema-flexibility :write}))

(defn- open-configuration
  "A configuration that adopts the store's creation-time settings."
  [creation-configuration]
  (dissoc creation-configuration :fuse-index-roots? :index-config))

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
  (fs/delete-recursively! store-dir store-dir)
  (d/create-database configuration)
  (when-not (genesis-complete? store-dir)
    (refuse! ::initialization-incomplete
             (str "the store at " store-dir
                  " has no branch roster after creation")
             {::dir store-dir}))
  nil)

(defn open-store!
  "Open the one fenced physical store this process owns.
  Creates it when absent and opens its main branch. B2 revision: one closed map argument;
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
      (let [creation-configuration (datahike-configuration dir)
            created? (cond
                       (not (d/database-exists? creation-configuration))
                       (do (create-store! dir creation-configuration) true)

                       (genesis-complete? dir)
                       false

                       ; :db without :branches — killed mid-genesis, so
                       ; nothing durable ever existed. Recreate.
                       :else
                       (do (create-store! dir creation-configuration) true))
            connection (d/connect (if created?
                                    creation-configuration
                                    (open-configuration
                                     creation-configuration)))]
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
          configuration (assoc (open-configuration
                                (datahike-configuration
                                 (:seon.store/dir store)))
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

;;; ---------------------------------------------------------------------------
;;; Writing — ACCRETION (drafted 2026-07-27, N3 package 2, from n3-plan §8)
;;;
;;; New functions only; nothing above this line changed. The store owns
;;; "how we write", so the one transaction wrapper and its refusal
;;; classification live here rather than in a second owner.
;;;
;;; THE ISSUE'S CONCLUSION WAS WRONG, and probe D shows why: the
;;; transition's own ex-data is INTACT at the third link of the cause
;;; chain. `throwable-promise`'s deref
;;; (`reference-code/datahike/src/datahike/tools.cljc:93-107`) wraps the
;;; ExecutionException in a fresh ex-info with empty data — it WRAPS, it
;;; does not discard. Datahike's own aborts (`:transact/cas`,
;;; `:transact/schema`) are equally distinguishable by value. No fork
;;; change is required; the issue note is corrected at seal.
;;; ---------------------------------------------------------------------------

(defn- panic-on-core-error?
  "True when this branch's config says development panics (R41).
  Read straight off the connection's own database value: there is one
  config singleton per cluster branch, so the dial needs no cluster name
  to find. A database with no config yet is a database before boot
  finished — it records rather than panics, because the dial has not
  been applied, not because the failure is acceptable."
  [connection]
  (= :panic
     (d/q '[:find ?mode .
            :where [_ :seon.config/on-core-error ?mode]]
          @connection)))

(defn- jdk-integers->long
  "Change exactly `java.lang.Integer` values to `java.lang.Long`."
  [tx-data]
  (walk/postwalk
   (fn [value]
     (if (instance? Integer value)
       (long value)
       value))
   tx-data))

(defn transact!
  "Commit tx-data. Returns a value on success AND on refusal; never throws.
  Nothing throws into the run loop, so this is the one door every write
  goes through. Before Datahike sees the transaction, this boundary walks
  its data and changes exactly `java.lang.Integer` values to Long; other
  numeric classes stay untouched and Datahike continues to refuse them.
  Four outcomes, four shapes:

  - committed → the transaction report;
  - OUR transition refused → the transition's own map, verbatim, under
    `{:seon.error/kind :seon.cluster.run/refused ::rule … ::transition …}`
    — a caller can branch on the exact rule it predicted, which is what
    makes a fence test honest rather than green-for-the-wrong-reason;
  - datahike aborted (`:transact/cas`, `:transact/schema`, …) →
    `{:seon.error/kind :seon.db/rejected :seon.error/data <its own data>}`;
  - nothing classifiable → `{:seon.error/kind :seon.db/unknown-failure …}`
    and, on the `:seon.config/on-core-error` dial, dev PANICS. An
    unclassifiable transaction failure is a bug, not a condition, and
    absorbing it is how a typo passes for a fence.

  `tx-data` is a VECTOR or Datahike's own argument map (`:tx-data` +
  `:tx-meta`, its vocabulary, not ours). The map form was already on the
  live critical path — the run loop's `:open` branch carries the trigger
  as transaction metadata — while this contract said vector, and it
  passed only because Datahike's `STransactions` spec accidentally
  admits a map (`spec.cljc:66-67`: every map entry is a `coll?`). The
  contract now says what the callers do; instrumentation would have
  caught it, which is the whole argument for instrumentation.

  The cause-chain walk this uses lives in `seon.error/refusal`: it is
  about throwables, not stores, and moving it there made the dependency
  one-way (`store -> error`) so the error owner can stay pure."
  {:malli/schema [:=> [:cat :seon.store/branch-connection
                       :seon.store/transaction]
                  [:or [:map] :seon.error/value]]}
  [connection tx-data]
  (try
    (d/transact connection
                (schema.datahike/encode-transaction
                 (jdk-integers->long tx-data)))
    (catch Throwable throwable
      (let [data (error/refusal throwable)]
        (cond
          ;; OUR transition: its own map, verbatim. The caller branches
          ;; on the exact rule it predicted.
          (some? (:seon.error/kind data))
          data

          ;; datahike's own abort, distinguishable BY VALUE
          (some? (:error data))
          {:seon.error/kind :seon.db/rejected
           :seon.error/message (or (ex-message throwable) "transaction rejected")
           :seon.error/data data}

          ;; nothing classifiable. This is a bug, not a condition —
          ;; absorbing it is how a typo passes for a fence — so it is
          ;; loud by the one dial rather than by judgement here.
          :else
          (let [failure {:seon.error/kind :seon.db/unknown-failure
                         :seon.error/message
                         (or (ex-message throwable)
                             (.getName (class throwable)))
                         :seon.error/data (or data {})}]
            (when (panic-on-core-error? connection)
              (throw (ex-info (:seon.error/message failure) failure throwable)))
            failure))))))
