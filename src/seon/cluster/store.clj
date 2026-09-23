(ns seon.cluster.store
  "Owns Datahike store, connection, and flock custody.

  `open-store!` canonicalizes a store directory and acquires its
  non-blocking exclusive flock before checking or creating the
  database. It recreates an incomplete genesis, verifies the main
  branch is readable, and returns the connected store with the flock
  held until `release-store!`. A live holder is refused rather than
  displaced.

  `open-branch!` opens one existing roster branch and refuses a second
  connection to that branch in the process. Datahike's `:self` writer
  owns transaction serialization; `seon.db/transact!` owns transaction
  admission and failure values."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.connections :as connections]
            [datahike.store :as datahike.store]
            [konserve.core :as k]
            [konserve.filestore :as filestore]
            [clojure.test.check.generators :as gen]
            [seon.db :as db]
            [seon.fault :as fault]
            [seon.fs :as fs]
            [seon.operator.runtime :refer [held-flocks]]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [taoensso.timbre :as log])
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
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (db/connection? value))

(defn connection-object?
  "True for a Datahike connection, live or RELEASED.
  A different question from `connection?`, and the difference is the
  point: a started instance holds a connection that will outlive its
  own liveness, and `stop!` must accept exactly that value — its
  docstring promises idempotence, so the second call necessarily
  receives a released one. Requiring liveness there made the contract
  forbid the case the function exists to handle, which instrumentation
  found on its first run. Liveness stays required where work is done
  through it (`seon.db/transact!`, the loop handle, the wake listener)."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (db/connection-object? value))

(defn file-lock-object?
  "True for a java.nio.channels.FileLock, HELD or released.
  The companion of `connection-object?`, and for the same reason: the
  store value records the flock this process opened, and `release-store!`
  derives released-ness from the lock's own validity inside its body —
  \"the flock's own validity IS the released? fact\". A shape contract that
  also demanded validity made a released store value unrepresentable, so
  the retained store inside a stopped `:seon.boot/instance` refused its
  own owner. Validity stays where it is decided: at the release itself."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? java.nio.channels.FileLock value))

(defn file-lock?
  "True for a held java.nio.channels.FileLock."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (instance? java.nio.channels.FileLock value)
       (.isValid ^java.nio.channels.FileLock value)))

(defn database-value?
  "True for any Datahike database value."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (db/database-value? value))

(schema/register-core-predicate! 'seon.cluster.store/connection-object?
                                 connection-object?)

(schema/register-core-predicate! 'seon.cluster.store/connection?
                                 connection?)
(schema/register-core-predicate! 'seon.cluster.store/file-lock?
                                 file-lock?)
(schema/register-core-predicate! 'seon.cluster.store/file-lock-object?
                                 file-lock-object?)
(schema/register-core-predicate! 'seon.cluster.store/database-value?
                                 database-value?)

(defn- fresh-file-lock
  {:malli/schema [:=> [:cat] [:fn seon.cluster.store/file-lock-object?]]}
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
          {:seon.cluster.store/file-lock-generator-failed (.getPath lock-file)
           :seon.error/message
           "The file-lock generator could not acquire its fresh lock."
           ::lock-file (.getPath lock-file)})))))

(def connection-generator db/connection-generator)
(def file-lock-generator
  ;; Each generated store lifecycle owns and releases its lock. Reusing one
  ;; singleton made later samples invalid after the first generated stop!.
  (gen/fmap (fn [_] (fresh-file-lock)) (gen/return nil)))
(def database-value-generator db/database-value-generator)

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
  diff buffer and a measured 4096-way branching factor, retained history by
  the settled default policy, and
  write-time schema flexibility. Fusion and index settings are
  creation-only; reopen configurations omit them so Datahike adopts the
  stored values, including `keep-history?`. Explicit reopen requests are
  checked by Datahike against its stored record."
  {:malli/schema
   [:function
    [:=> [:cat :seon.store/dir] [:map]]
    [:=> [:cat :seon.store/dir :boolean] [:map]]]}
  ([store-dir]
   (datahike-configuration store-dir true))
  ([store-dir keep-history?]
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
      :keep-history? keep-history?
      :fuse-index-roots? true
      :index-config {; The dependency's 512 default made the 208k-datom
                     ; publication flush 3,029 values, each paying Konserve's
                     ; file + directory force. 4,096 measured 357 without a
                     ; read regression; keep the evidence beside the tuning:
                     ; docs/seon/issues/complete-publication-takes-seventy-seconds.md
                     :branching-factor 4096
                     :diff-buf-size 256}
      :schema-flexibility :write})))

(defn- open-configuration
  "A configuration that adopts the store's creation-time settings."
  [creation-configuration]
  (dissoc creation-configuration :keep-history? :fuse-index-roots? :commit-graph? :index-config))

;;; ---------------------------------------------------------------------------
;;; Lifecycle
;;; ---------------------------------------------------------------------------

(defn- refuse!
  "Refuse loudly with the one store error shape."
  {:malli/schema [:=> [:cat :keyword :string :map] :nil]}
  [rule message data]
  (throw (ex-info message
                  (assoc data

                         ::refused rule
                         ::rule rule))))

;;; ---------------------------------------------------------------------------
;;; Destructive admission — the ONE rule for deleting a store or a managed root
;;; ---------------------------------------------------------------------------

;;; THE RULE. A recursive deletion is admitted only when
;;;
;;;   1. its authority root and its target are ABSOLUTE spellings — nil, "",
;;;      ".", and "data/clusters" name the process working directory, which is
;;;      never a deletion authority;
;;;   2. the canonical target lies under the canonical authority root; and
;;;   3. the target is outside the working directory's own `data/`, UNLESS the
;;;      caller declares that directory as the operator root this JVM was
;;;      launched to operate.
;;;
;;; `bin/seon [--root PATH]` declares that root on every child JVM
;;; (`-Dseon.operator.root`), so `bin/seon reset --force` still destroys the
;;; checkout's data deliberately. `bin/test` workers declare their own
;;; isolated run root (`src/seon/test/runner.clj:2441`) and `bin/test-fast`
;;; declares none, so no worker, fixture, or lane JVM can spell the
;;; developer's `data/store` at all. The declaration is an ARGUMENT, never a
;;; property read at the seam: admission is a pure decision over the values
;;; the caller holds.

(defn declared-operator-root
  "The canonical operator root THIS JVM was launched to operate, or nil.
  Read once at an owner's entry and handed to `admit-destructive-path!`."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (let [declared (System/getProperty "seon.operator.root")]
    (when-not (str/blank? declared)
      (canonical-path declared))))

(defn admit-destructive-path!
  "Admit one recursive deletion, or refuse BEFORE anything is deleted.

  Takes `{::root, ::target, ::declared-root}` and returns the canonical
  target path. `::root` is the caller's deletion authority, `::target` the
  path it wants removed, and `::declared-root` the operator root this JVM was
  launched to operate (`declared-operator-root`), absent when none was
  declared.

  THE RULE, refused as a typed store refusal naming the offending value:
  the root and the target must be absolute (nil, \"\", \".\" and any relative
  spelling resolve against the process working directory and are refused);
  the canonical target must lie under the canonical root; and a target
  inside the working directory's own `data/` is refused unless
  `::declared-root` is that working directory. So `bin/seon reset --force`
  destroys the checkout's data because its JVM declared that root, while a
  test worker, a fixture, or a lane JVM — which declare an isolated root or
  none — cannot construct the deletion."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The admission judges caller-supplied path values of any shape, including nil and relative strings, and refuses them by typed value."}]]
    :string]}
  [request]
  (fs/admit-destructive-path! request))

(defn log-deletion!
  "Record one admitted recursive deletion BEFORE it runs.

  Takes `{::root, ::targets, ::file-bytes, ::operation}` and logs the
  deletion authority, every canonical target, the bytes about to disappear,
  the calling frame, and this process's pid, so a future wipe names itself
  instead of leaving the recurring absence-of-signal. Returns the recorded
  report."
  {:malli/schema
   [:=> [:cat [:map
               [::root :string]
               [::targets [:vector :string]]
               [::file-bytes :int]
               [::operation :string]]]
    [:map
     [::root :string]
     [::targets [:vector :string]]
     [::file-bytes :int]
     [::operation :string]
     [::caller :string]
     [::pid :int]]]}
  [request]
  (fs/log-deletion! request))

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
(defn- acquire-flock!
  "Acquire the exclusive flock on `lock-path`.

  Returns the lock, `::held-by-this-process` when THIS JVM already holds
  it, or nil when another live process does. Those are different facts
  and the caller must not conflate them: reporting a self-collision as a
  foreign holder sends the reader hunting for an orphan process that
  does not exist, which is exactly what `init NAME --force` did after it
  had already destroyed the branch."
  [lock-path]
  (locking held-flocks
    (if (contains? @held-flocks lock-path)
      ::held-by-this-process
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
                     ::held-by-this-process)
                   (catch Throwable failure
                     (.close channel)
                     (throw failure)))]
        (cond
          (keyword? lock) lock
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

(defn- complete-store?
  "True when `store-dir` holds a store whose `:branches` roster is written."
  [store-dir]
  (let [dir (io/file store-dir)]
    (boolean
     (and (.isDirectory dir)
          (seq (.list dir))
          ;; konserve writes each key to a `.new` file and moves it with
          ;; ATOMIC_MOVE (reference-code/konserve/src/konserve/filestore.clj:307,
          ;; `:in-place? false` at :901), so a kill leaves the roster absent,
          ;; never half-written; absence reads as nil. A read that fails is
          ;; damage, not incompleteness, and propagates.
          (genesis-complete? (str store-dir))))))

(defn- create-store!
  "Create a fresh store at `store-dir`, verifying genesis completed.

  The deletion this performs is admitted by `admit-destructive-path!` and
  recorded by `log-deletion!` before it runs: an absolute target under a
  declared disposable root, never the working directory's own `data/` unless
  this JVM was launched to operate it. The authority also RE-DECIDES at the
  seam — a store whose `:branches` roster is present is complete and is never
  deleted here, whatever existence read the caller made earlier."
  [store-dir configuration]
  (let [target (admit-destructive-path!
                {::root store-dir
                 ::target store-dir
                 ::declared-root (declared-operator-root)})]
    (when (complete-store? target)
      (refuse! ::complete-store-not-recreated
               (str "refusing to recreate the store at " target
                    ": its branch roster is present, so creation would "
                    "delete durable branches")
               {::dir target}))
    (log-deletion!
     {::root target
      ::targets [target]
      ::file-bytes (long (or (:seon.operator.footprint/file-bytes
                              (fs/footprint target))
                             0))
      ::operation "seon.cluster.store/create-store!"})
    (fs/delete-recursively! target target))
  (d/create-database configuration)
  (when-not (genesis-complete? store-dir)
    (refuse! ::initialization-incomplete
             (str "the store at " store-dir
                  " has no branch roster after creation")
             {::dir store-dir}))
  nil)

(defn open-store!
  "Open the one fenced physical store this process owns.
  Creates it when absent and opens its main branch. B2 revision: one map argument;
  under branch-per-cluster the store is PER PROCESS ROOT and every
  cluster is a branch of it.
  Order is the contract: acquire the non-blocking exclusive flock on
  `(lock-file store-dir)` FIRST — a held lock refuses immediately, and
  the refusal says WHOSE hold it is: `{::rule ::held-elsewhere}` when
  another live process holds it, `{::rule ::held-by-this-process}` when
  this JVM already opened it and has not released it — then
  existence-check, then create or verify: a store whose
  genesis is incomplete (`:db` present, `:branches` missing — the
  first-create kill window) is deleted and recreated; a complete store
  is connected and its main branch verified readable. Returns the store
  value; the flock descriptor stays held inside it until
  `release-store!`."
  {:malli/schema [:=> [:cat [:map
                             [:seon.store/dir :seon.store/dir]
                             [:seon.store/destroy? {:optional true} :boolean]
                             [:seon.config.db/keep-history?
                              {:optional true}
                              :boolean]]]
                  :seon.store/store]}
  [{store-dir :seon.store/dir :as request}]
  ; one physical spelling for the whole lifecycle: the fence, the store
  ; id, the genesis probe, and the returned value all name one directory
  (let [dir (canonical-path store-dir)
        lock-path (lock-file dir)
        held (acquire-flock! lock-path)
        lock (cond
               (= ::held-by-this-process held)
               (refuse! ::held-by-this-process
                        (str "the store at " dir
                             " is already open in this process (pid "
                             (.pid (java.lang.ProcessHandle/current))
                             "); release it before opening it again")
                        {::dir dir ::lock-file lock-path})

               (nil? held)
               (refuse! ::held-elsewhere
                        (str "the store at " dir
                             " is held by another live process")
                        {::dir dir ::lock-file lock-path})

               :else held)]
    (try
      ;; Destruction is admitted only while this exact sibling lock is held,
      ;; before even the first database-exists? call can open the store.
      (when (:seon.store/destroy? request)
        (when (java.nio.file.Files/isSymbolicLink (.toPath (io/file store-dir)))
          (refuse! ::symbolic-store-target
                   "Destructive store acquisition refuses a symbolic store directory."
                   {::dir store-dir}))
        (let [target (admit-destructive-path!
                      {::root dir ::target dir
                       ::declared-root (declared-operator-root)})]
          (log-deletion! {::root dir ::targets [target]
                          ::file-bytes (:seon.operator.footprint/file-bytes
                                        (fs/footprint target))
                          ::operation "seon.cluster.store/open-store!"})
          (fs/delete-recursively! dir target)))
      (let [probe-configuration (datahike-configuration dir)
            exists? (d/database-exists? probe-configuration)
            complete? (and exists? (genesis-complete? dir))
            requested? (contains? request :seon.config.db/keep-history?)
            requested (:seon.config.db/keep-history? request)
            creation-configuration
            (datahike-configuration dir (if requested? requested true))
            created? (cond
                       (not exists?)
                       (do (create-store! dir creation-configuration) true)

                       complete?
                       false

                       ; :db without :branches — killed mid-genesis, so
                       ; nothing durable ever existed. Recreate with the
                       ; requested setting (or the settled default).
                       :else
                       (do (create-store! dir creation-configuration) true))
            connection (d/connect (if created?
                                    creation-configuration
                                    (cond-> (open-configuration creation-configuration)
                                      requested? (assoc :keep-history? requested))))]
        ; readiness is a COMPLETE connection over a COMPLETE store: the
        ; main branch must be readable before this value escapes
        (try
          (d/db connection)
          (catch Throwable failure
            (d/release connection)
            (throw failure)))
        {:seon.store/dir dir
         :seon.store/lock-file lock-path
         :seon.store/connection-object connection
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
      (d/release (:seon.store/connection-object store))
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
                  :seon.db/connection]}
  [store branch]
  (locking branch-open-monitor
    (let [main-connection (:seon.store/connection-object store)
          configuration (assoc (open-configuration
                                (datahike-configuration
                                 (:seon.store/dir store)
                                 (get-in @main-connection
                                         [:config :keep-history?])))
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

(defn release-branch!
  "Release one proof branch connection before its roster branch is retired.

  Datahike keeps a released Connection's metadata (`connections.cljc:124-127`),
  where `seon.db/carry-connection-projection-state!` put the cluster's state; a
  final release drops it, so a retained Connection object carries no world."
  {:malli/schema [:=> [:cat :seon.store/connection-object] :nil]}
  [connection]
  (d/release connection)
  (when (= :released @(:wrapped-atom connection))
    (alter-meta! (:wrapped-atom connection) dissoc :seon.sci.eval/projection-state))
  nil)

(defn- listener-failed!
  "Record one failed listener hand-off on the cluster world captured at install.

  Datahike already retired the failed registration by identity
  (`reference-code/datahike/src/datahike/writer.cljc`, `retire-listener!`), so
  this fault's own transaction cannot re-invoke it: no recursion. The stored
  fault names the listener, its retirement and the commit it failed on; the
  original is its cause. `fault!`'s panic (stored, or not stored when the
  write failed) is this boundary's declared case: no graph owns a listener,
  so it prints both throwables whole to stderr and returns, letting Datahike
  notify the remaining listeners."
  {:malli/schema [:=> [:cat :seon.env/environment :seon.db.process/id :map] :nil]}
  [environment process {:keys [listener-key exception tx-report retired?]}]
  (let [commit-id (get-in tx-report [:tx-meta :db/commitId])
        failure (ex-info (str "Datahike listener " listener-key " failed on commit " commit-id)
                         {::listener-key listener-key
                          ::listener-retired? retired?
                          ::commit-id commit-id
                          ::basis-t (db/basis-t (:db-after tx-report))}
                         exception)]
    (try
      (fault/fault! environment failure {:seon.error/layer :seon.db/listener
                                         :seon.error/operation `listener-failed!
                                         :seon.db.process/id process})
      (catch Throwable panic
        (binding [*out* *err*]
          (prn {:seon.error/message (ex-message panic)
                :seon.error/operation `listener-failed!
                :seon.fault/recorded (:seon.fault/recorded (ex-data panic))
                :seon.error/data {:panic (Throwable->map panic)
                                  :suppressed (mapv Throwable->map (.getSuppressed panic))
                                  :failure (Throwable->map failure)}})
          (flush))))
    nil))

(defn listen-failures!
  "Install the connection's listener-failure handler, capturing this cluster's world.
  The Datahike fork's `notify-listeners!` reads `:listener-failure` from the
  connection's meta, beside the `:listeners` registry `d/listen` swaps; one
  per connection, a later install replaces it."
  {:malli/schema [:=> [:cat :seon.env/environment :seon.db.process/id] :nil]}
  [environment process]
  (alter-meta! (:wrapped-atom (:seon.db/connection environment))
               assoc :listener-failure #(listener-failed! environment process %))
  nil)
