(ns seon.operator.state
  "Protected, non-database lifecycle records for managed operator roots.

  Claims live in the installation control root, outside every managed
  cluster/store/blob tree.  This namespace is deliberately available to both
  the Babashka launcher and the JVM operator; it is the one atomic-record
  mechanism for roots, processes, stores, and clusters."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io PushbackReader RandomAccessFile]
           [java.net InetSocketAddress Socket]
           [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption OpenOption StandardOpenOption]
           [java.time Instant]
           [java.util Date UUID]
           [java.util.concurrent ExecutionException TimeUnit TimeoutException]))

(def ^:private subprocess-cleanup-ms 10000)

(declare process-start-instant matching-process-handle)

(defn- subprocess-remaining-ms
  [deadline-ns]
  (max 0 (long (/ (- deadline-ns (System/nanoTime)) 1000000))))

(defn- await-subprocess-value
  [value deadline-ns timeout-value]
  (if (future? value)
    (deref value (subprocess-remaining-ms deadline-ns) timeout-value)
    value))

(defn- subprocess-identity
  [^java.lang.ProcessHandle handle]
  {:seon.boot/pid (.pid handle)
   :seon.boot/start-instant (process-start-instant (.pid handle))})

(defn- same-subprocess-handle
  [{:seon.boot/keys [pid] :as process-identity}]
  (let [candidate (matching-process-handle process-identity)]
    (when (and candidate (= pid (.pid ^java.lang.ProcessHandle candidate)))
      candidate)))

(defn- subprocess-tree-identities
  [^java.lang.ProcessHandle root]
  (with-open [descendant-stream (.descendants root)]
    (into [(subprocess-identity root)]
          (map subprocess-identity)
          (iterator-seq (.iterator descendant-stream)))))

(defn- terminate-subprocess!
  [process-record launch-identities]
  (let [^Process child (:proc process-record)
        identities (vec (distinct (concat launch-identities
                                          (subprocess-tree-identities
                                           (.toHandle child)))))]
    (process/destroy-tree process-record)
    (doseq [process-identity (reverse identities)]
      (when-let [handle (same-subprocess-handle process-identity)]
        (.destroyForcibly ^java.lang.ProcessHandle handle)))
    (let [cleanup-deadline
          (+ (System/nanoTime) (* 1000000 subprocess-cleanup-ms))]
      (doseq [process-identity identities]
        (when-let [handle (same-subprocess-handle process-identity)]
          (try
            (.get (.onExit ^java.lang.ProcessHandle handle)
                  (subprocess-remaining-ms cleanup-deadline)
                  TimeUnit/MILLISECONDS)
            (catch TimeoutException _ nil)
            (catch ExecutionException error (throw (.getCause error)))))))
    (not-any? same-subprocess-handle identities)))

(defn run-process!
  "Run one foreign argv process under one declared monotonic deadline."
  [{argv :seon.operator.subprocess/argv
    deadline-ms :seon.operator.subprocess/deadline-ms
    directory :seon.operator.subprocess/directory
    extra-env :seon.operator.subprocess/extra-env
    input :seon.operator.subprocess/input
    merge-error? :seon.operator.subprocess/merge-error?
    output-file :seon.operator.subprocess/output-file
    :as request}]
  (when-not (and (vector? argv) (seq argv) (every? string? argv)
                 (integer? deadline-ms) (pos? deadline-ms))
    (throw
     (ex-info "A foreign process requires argv and a positive deadline."
              {:seon.error/kind
               :seon.operator.subprocess/deadline-undeclared
               :seon.operator.subprocess/request request})))
  (let [deadline-ns (+ (System/nanoTime) (* 1000000 (long deadline-ms)))
        options (cond-> {:out (if output-file :write :string)
                         :err (if merge-error? :out :string)
                         :shutdown process/destroy-tree}
                  directory (assoc :dir directory)
                  extra-env (assoc :extra-env extra-env)
                  (some? input) (assoc :in input)
                  output-file (assoc :out-file output-file))
        process-record (process/process argv options)
        ^Process child (:proc process-record)
        root (.toHandle child)
        identities (subprocess-tree-identities root)
        timeout-value (Object.)
        completed? (.waitFor child (subprocess-remaining-ms deadline-ns)
                             TimeUnit/MILLISECONDS)
        output (when completed?
                 (if output-file
                   ""
                   (await-subprocess-value (:out process-record)
                                           deadline-ns timeout-value)))
        error-output (when completed?
                       (if merge-error?
                         ""
                         (await-subprocess-value (:err process-record)
                                                 deadline-ns timeout-value)))
        phase (cond
                (not completed?) :process-exit
                (identical? timeout-value output) :stdout
                (identical? timeout-value error-output) :stderr
                :else nil)]
    (if phase
      (let [reaped? (terminate-subprocess! process-record identities)]
        (throw
         (ex-info
          "A foreign process exceeded its declared deadline."
          {:seon.error/kind :seon.operator.subprocess/deadline-exceeded
           :seon.operator.subprocess/argv argv
           :seon.operator.subprocess/deadline-ms deadline-ms
           :seon.operator.subprocess/phase phase
           :seon.operator.subprocess/pid (.pid child)
           :seon.operator.subprocess/start-instant
           (:seon.boot/start-instant (first identities))
           :seon.operator.subprocess/reaped? reaped?})))
      {:seon.operator.subprocess/argv argv
       :seon.operator.subprocess/exit (.exitValue child)
       :seon.operator.subprocess/output output
       :seon.operator.subprocess/error-output error-output})))

(defn- instant-ms
  [instant]
  (when (inst? instant)
    (.getTime ^Date instant)))

(defn canonical-path
  [path]
  (.getCanonicalPath (java.io.File. (str path))))

(defn store-lock-path
  "The canonical sibling lock path for one store directory."
  {:malli/schema [:=> [:cat :seon.store/dir] :seon.store/lock-file]}
  [store-directory]
  (str (canonical-path store-directory) ".lock"))

(defn process-start-instant
  "Return the OS start instant for a live PID."
  [pid]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (when (.isPresent optional)
        (let [instant (.startInstant (.info (.get optional)))]
          (when (.isPresent instant) (Date/from (.get instant))))))
    (catch Throwable _ nil)))

(defn process-identity-alive?
  "True when a PID still has the recorded OS start instant."
  [{:seon.boot/keys [pid start-instant]}]
  (and (integer? pid)
       (inst? start-instant)
       (= start-instant (process-start-instant pid))))

(defn- matching-process-handle
  [record]
  (let [optional (java.lang.ProcessHandle/of
                  (long (:seon.boot/pid record)))]
    (when (.isPresent optional)
      (let [handle (.get optional)]
        (when (= (:seon.boot/start-instant record)
                 (process-start-instant (.pid handle)))
          handle)))))

(defn- recorded-process-absence
  [record]
  (let [optional (java.lang.ProcessHandle/of
                  (long (:seon.boot/pid record)))]
    (if (and (.isPresent optional)
             (.isAlive ^java.lang.ProcessHandle (.get optional)))
      :pid-reused
      :already-exited)))

(defn terminate-recorded-process!
  "Terminate only the exact recorded process identity, rechecking before KILL."
  [record silence-ms]
  (if-let [handle (matching-process-handle record)]
    (do
      (.destroy handle)
      (try
        (.get (.onExit handle) silence-ms TimeUnit/MILLISECONDS)
        (catch TimeoutException _ nil)
        (catch ExecutionException error (throw (.getCause error))))
      (if-let [remaining (matching-process-handle record)]
        (do
          (.destroyForcibly remaining)
          (try
            (.get (.onExit remaining) silence-ms TimeUnit/MILLISECONDS)
            (catch TimeoutException _ nil)
            (catch ExecutionException error (throw (.getCause error))))
          (when (matching-process-handle record)
            (throw
             (ex-info "The exact recorded process survived SIGKILL."
                      {:seon.error/kind :seon.operator/process-survived-sigkill
                       :seon.operator.process-record/generation
                       (:seon.operator.process-record/generation record)
                       :seon.boot/pid (:seon.boot/pid record)})))
          :sigkill)
        :sigterm))
    (recorded-process-absence record)))

(defn- graceful-stop!
  [advertisement silence-ms]
  (try
    (with-open [socket (Socket.)]
      (.connect socket
                (InetSocketAddress.
                 ^String (:seon.boot/prepl-host advertisement)
                 (int (:seon.boot/prepl-port advertisement)))
                silence-ms)
      (.setSoTimeout socket silence-ms)
      (with-open [writer (java.io.OutputStreamWriter.
                          (.getOutputStream socket)
                          java.nio.charset.StandardCharsets/UTF_8)
                  reader (PushbackReader.
                          (java.io.InputStreamReader.
                           (.getInputStream socket)
                           java.nio.charset.StandardCharsets/UTF_8))]
        (.write
         writer
         (str
          "(let [instances @(var-get (ns-resolve 'seon.cluster "
          "(symbol \"running-instances\")))] "
          "(doseq [[name instance] instances] "
          "(if (map? instance) (seon.cluster/stop! instance) "
          "(swap! (var-get (ns-resolve 'seon.cluster "
          "(symbol \"running-instances\"))) dissoc name))) "
          "(vec (sort (keys instances))))\n"))
        (.flush writer)
        (loop []
          (let [event (edn/read {:eof ::eof} reader)]
            (cond
              (= ::eof event) false
              (and (map? event) (= :ret (:tag event)))
              (not (:exception event))
              :else (recur))))))
    (catch Throwable _ false)))

(defn advertisement-identity-alive?
  "True when an advertisement still names its exact OS process."
  [advertisement]
  (process-identity-alive?
   (select-keys advertisement [:seon.boot/pid :seon.boot/start-instant])))

(defn current-process-identity
  []
  (let [handle (java.lang.ProcessHandle/current)
        start (.startInstant (.info handle))]
    {:seon.boot/pid (.pid handle)
     :seon.boot/start-instant
     (when (.isPresent start) (Date/from (.get start)))}))

(defn read-edn
  "Read one EDN state record when it exists."
  [path]
  (when (fs/regular-file? path)
    (edn/read-string (slurp (str path)))))

(defn- sync-path! [path options]
  (with-open [channel
              (FileChannel/open (fs/path path)
                                (into-array OpenOption options))]
    (.force channel true)))

(defn write-edn!
  "Durably replace one EDN state record by fsync + atomic rename."
  [path value]
  (let [path (fs/path path)
        parent (fs/parent path)
        temp (fs/path (str path "." (random-uuid) ".tmp"))]
    (fs/create-dirs parent)
    (try
      (spit (str temp) (str (pr-str value) "\n"))
      (sync-path! temp [StandardOpenOption/WRITE])
      (fs/move temp path {:replace-existing true :atomic-move true})
      (sync-path! parent [])
      value
      (finally
        (fs/delete-if-exists temp)))))

(defn delete-edn!
  "Durably delete one EDN state record when present."
  [path]
  (let [path (fs/path path)
        deleted? (fs/delete-if-exists path)]
    (when deleted?
      (sync-path! (fs/parent path) []))
    (boolean deleted?)))

(defn control-root
  "The one installation-owned authority outside managed cluster/store data."
  [repository-root]
  (fs/path (canonical-path repository-root) "data" "operator"))

(defn root-claim-id
  [managed-root]
  (UUID/nameUUIDFromBytes
   (.getBytes (canonical-path managed-root)
              java.nio.charset.StandardCharsets/UTF_8)))

(defn root-claim-path
  [repository-root managed-root]
  (fs/path (control-root repository-root) "claims" "roots"
           (str (root-claim-id managed-root) ".edn")))

(defn process-claim-directory
  [repository-root]
  (fs/path (control-root repository-root) "claims" "processes"))

(defn process-claim-path
  [repository-root generation]
  (fs/path (process-claim-directory repository-root) (str generation ".edn")))

(def lifecycle-lock-announce-ms
  "How long a lock wait stays silent before it announces itself."
  1000)

(def lifecycle-lock-repeat-ms
  "How often an announced lock wait repeats while it keeps waiting."
  5000)

(def lifecycle-lock-timeout-ms
  "The acquisition and hold bound explicitly selected by internal callers."
  900000)

(defn root-lifecycle-lock-path
  "The lifecycle lock of ONE operator root.

  Every operator root — the repository root and each isolated `--root` — owns
  its own file under its own control directory, so two isolated roots never
  contend. The installation control lock below is a different file and is
  taken only by cross-root work."
  [managed-root]
  (fs/path (control-root managed-root) "root-lifecycle.lock"))

(defn control-lock-path
  "The installation-wide control lock, for cross-root transitions only."
  [repository-root]
  (fs/path (control-root repository-root) "lifecycle.lock"))

(defn- lock-holder-path
  [lock-path]
  (fs/path (str lock-path ".holder.edn")))

(defn- try-file-lock
  [channel]
  (try
    (.tryLock channel)
    (catch Throwable error
      (if (= "java.nio.channels.OverlappingFileLockException"
             (.getName (class error)))
        nil
        (throw error)))))

(defonce ^:private lifecycle-lock-owners
  ;; A POSIX file lock belongs to the PROCESS, and closing ANY descriptor for
  ;; the file drops it. So a second thread in this JVM must never open its own
  ;; descriptor while a sibling thread holds the lock — its polling close
  ;; would silently release the holder's lock. Threads therefore claim the
  ;; path in this map first, and only the claiming thread opens a descriptor.
  (atom {}))

(defn- acquire-lock-slot!
  [lock-key]
  (loop []
    (let [owners @lifecycle-lock-owners]
      (if (contains? owners lock-key)
        false
        (or (compare-and-set! lifecycle-lock-owners owners
                              (assoc owners lock-key
                                     (.getName (Thread/currentThread))))
            (recur))))))

(defn- release-lock-slot!
  [lock-key]
  (swap! lifecycle-lock-owners dissoc lock-key))

(defn- open-locked-file
  "Open a descriptor holding the file lock as `[file channel]`, else nil.

  A descriptor is never left open: a refused lock or a failure while opening
  closes what it opened, so no later close can drop this process's lock."
  [lock-key]
  (let [file (RandomAccessFile. lock-key "rw")]
    (try
      (let [channel (.getChannel file)]
        (try
          (if (try-file-lock channel)
            [file channel]
            (do (.close channel) (.close file) nil))
          (catch Throwable error
            (.close channel)
            (throw error))))
      (catch Throwable error
        (.close file)
        (throw error)))))

(defn lock-holder
  "The recorded holder of one lifecycle lock, with its liveness, when present."
  [lock-path]
  (when-let [holder (read-edn (lock-holder-path lock-path))]
    (assoc holder
           :seon.operator.lock/holder-alive?
           (process-identity-alive?
            (select-keys holder
                         [:seon.boot/pid :seon.boot/start-instant])))))

(defn- instant-text
  [value]
  (if (inst? value)
    (str (.toInstant ^Date value))
    (str value)))

(defn- holder-sentence
  [lock-path]
  (if-let [holder (lock-holder lock-path)]
    (str "pid " (:seon.boot/pid holder)
         " (started " (instant-text (:seon.boot/start-instant holder)) ")"
         " running `" (:seon.operator.lock/command holder) "`"
         " since " (instant-text (:seon.operator.lock/acquired-at holder))
         (when-not (:seon.operator.lock/holder-alive? holder)
           (str " — that process is NOT alive, so the holder record is stale;"
                " the kernel already released its lock")))
    "a process that left no holder record beside the lock"))

(defn- declared-lock-timeout
  [request bound-key]
  (let [value (get request bound-key ::undeclared)]
    (when-not (and (integer? value) (pos? value))
      (throw
       (ex-info
        (str "The operator lifecycle lock requires a positive declared `"
             bound-key "` before acquisition.")
        {:seon.error/kind :seon.operator/lock-bound-undeclared
         :seon.operator.lock/bound bound-key
         :seon.operator.lock/value value
         :seon.operator.lock/request request :seon.operator/lock-bound-undeclared true})))
    value))

(defn- close-lifecycle-lock!
  [path held lock-key]
  (try
    (delete-edn! (lock-holder-path path))
    (finally
      ;; Closing the channel releases its FileLock. Babashka permits
      ;; FileChannel/close but intentionally does not expose FileLock/release
      ;; through SCI.
      (try
        (.close ^java.nio.channels.FileChannel (second held))
        (finally
          (try
            (.close ^RandomAccessFile (first held))
            (finally
              (release-lock-slot! lock-key))))))))

(defn- start-lock-held-transition!
  [path held lock-key holder transition]
  (let [completion (promise)
        completion-lock (Object.)
        run-transition (bound-fn [] (transition))
        worker
        (Thread.
         (fn []
           (let [outcome
                 (try
                   [::returned (run-transition)]
                   (catch Throwable failure [::failed failure]))]
             (locking completion-lock
               (try
                 (close-lifecycle-lock! path held lock-key)
                 (deliver completion outcome)
                 (catch Throwable cleanup-failure
                   (deliver completion [::failed cleanup-failure])))))))]
    (.setName worker
              (str "seon-lifecycle-lock-holder-"
                   (:seon.operator.lock/command holder)))
    ;; A timed-out CLI must not exit and release the kernel lock while its
    ;; transition still mutates state. `System/exit` remains process-wide and
    ;; stops both the transition and any dependency writers.
    (.setDaemon worker false)
    (.start worker)
    {:completion completion
     :completion-lock completion-lock}))

(defn with-lifecycle-lock!
  "Run one lifecycle transition under a named kernel-owned file lock.

  Acquisition and hold bounds are required before the first lock attempt.
  Waiting remains event-driven and loud. If a hold expires, the caller gets a
  typed fault while a non-daemon holder thread retains kernel custody until
  the transition is terminal; work is never interrupted and the lock is never
  released while a dependency writer may continue."
  [{path :seon.operator.lock/path
    command :seon.operator.lock/command
    :as request}
   transition]
  (let [acquisition-timeout-ms
        (declared-lock-timeout
         request :seon.operator.lock/acquisition-timeout-ms)
        hold-timeout-ms
        (declared-lock-timeout request :seon.operator.lock/hold-timeout-ms)
        started (System/currentTimeMillis)
        deadline (+ started acquisition-timeout-ms)
        lock-key (str path)
        waiter (assoc (current-process-identity)
                      :seon.operator.lock/path lock-key
                      :seon.operator.lock/command (str command)
                      :seon.operator.lock/waiting-since (Date. started)
                      :seon.operator.lock/acquisition-timeout-ms
                      acquisition-timeout-ms)]
    (fs/create-dirs (fs/parent path))
    (loop [announced-at nil]
      (let [outcome
            (when (acquire-lock-slot! lock-key)
              (let [held (try
                           (open-locked-file lock-key)
                           (catch Throwable error
                             (release-lock-slot! lock-key)
                             (throw error)))]
                (if held
                  (let [acquired-at (Date.)
                        holder (assoc (current-process-identity)
                                      :seon.operator.lock/path lock-key
                                      :seon.operator.lock/command (str command)
                                      :seon.operator.lock/acquired-at acquired-at
                                      :seon.operator.lock/hold-timeout-ms
                                      hold-timeout-ms
                                      :seon.operator.lock/hold-deadline
                                      (Date. (+ (.getTime acquired-at)
                                                hold-timeout-ms)))
                        {:keys [completion completion-lock]}
                        (try
                          (write-edn! (lock-holder-path path) holder)
                          (start-lock-held-transition!
                           path held lock-key holder transition)
                          (catch Throwable launch-failure
                            (close-lifecycle-lock! path held lock-key)
                            (throw launch-failure)))
                        timeout-value (Object.)
                        result (deref completion hold-timeout-ms timeout-value)]
                    (if (identical? timeout-value result)
                      (locking completion-lock
                        (if (realized? completion)
                          [::ran @completion]
                          (let [expired-at (Date.)
                                timed-out-holder
                                (assoc holder
                                       :seon.operator.lock/hold-expired-at
                                       expired-at)
                                record-failure
                                (try
                                  (write-edn! (lock-holder-path path)
                                              timed-out-holder)
                                  nil
                                  (catch Throwable failure failure))]
                            (throw
                             (ex-info
                              (str "Timed out holding the operator lifecycle lock "
                                   lock-key " for `" command "`.")
                              (cond->
                               {:seon.error/kind
                                :seon.operator/lock-hold-timeout
                                :seon.operator.lock/holder timed-out-holder
                                :seon.operator.lock/waiter waiter
                                :seon.operator.lock/expired-at expired-at
                                :seon.operator/lock-hold-timeout true}
                                record-failure
                                (assoc
                                 :seon.operator.lock/holder-record-failure
                                 (ex-message record-failure)))
                              record-failure)))))
                      [::ran result]))
                  (do
                    (release-lock-slot! lock-key)
                    nil))))]
        (if outcome
          (let [[disposition value] (second outcome)]
            (case disposition
              ::returned value
              ::failed (throw value)))
          (let [now (System/currentTimeMillis)
                waited (- now started)]
            (when (<= deadline now)
              (throw
               (ex-info
                (str "Timed out after " waited
                     " ms waiting for the operator lifecycle lock "
                     lock-key " held by " (holder-sentence path) ".")
                {:seon.error/kind :seon.operator/lock-acquisition-timeout
                 :seon.operator.lock/path lock-key
                 :seon.operator.lock/waited-ms waited
                 :seon.operator.lock/holder (lock-holder path)
                 :seon.operator.lock/waiter waiter :seon.operator/lock-acquisition-timeout true})))
            (let [announce?
                  (if announced-at
                    (<= lifecycle-lock-repeat-ms (- now announced-at))
                    (<= lifecycle-lock-announce-ms waited))]
              (when announce?
                (println
                 (str "! waiting " waited " ms for the operator lifecycle "
                      "lock " lock-key " — held by " (holder-sentence path)))
                (flush))
              (.sleep TimeUnit/MILLISECONDS 100)
              (recur (if announce? now announced-at)))))))))

(defn- with-control-lock*
  [repository-root command transition]
  (with-lifecycle-lock!
   {:seon.operator.lock/path (control-lock-path repository-root)
    :seon.operator.lock/command command
    :seon.operator.lock/acquisition-timeout-ms lifecycle-lock-timeout-ms
    :seon.operator.lock/hold-timeout-ms lifecycle-lock-timeout-ms}
   transition))

(defn with-control-lock!
  "Run one CROSS-ROOT transition under the installation control lock.

  This is the installation-wide lock and it serializes every root that takes
  it. Cross-root claims and reaping belong here directly. By the explicit
  keep-serial owner ruling, cleanup and refork also take it until measured
  four-worker contention justifies lock-custody transfer to a child JVM.
  Scheduled collection is not part of that exception: it takes the selected
  root's lifecycle lock only while acquiring store custody."
  [repository-root lock-request transition]
  (with-lifecycle-lock!
   (assoc lock-request
          :seon.operator.lock/path (control-lock-path repository-root))
   transition))

(defn claim-root-under-lock!
  "Publish a root claim while the caller holds the control lifecycle lock."
  [repository-root managed-root ephemeral-owner cluster-name]
  (let [root (canonical-path managed-root)
        path (root-claim-path repository-root root)
        claimed-at (Date.)
        previous (or (read-edn path) {})
        new-lifecycle? (contains? previous
                                  :seon.operator.claim/destroyed-at)
        previous (if new-lifecycle?
                   (dissoc previous
                           :seon.operator.claim/created-at
                           :seon.operator.claim/destroyed-at
                           :seon.operator.claim/cleanup
                           :seon.operator.claim/footprint
                           :seon.operator.claim/clusters)
                   previous)
        _ (when (and ephemeral-owner
                     (not (process-identity-alive? ephemeral-owner)))
            (throw
             (ex-info "The declared ephemeral root owner is not alive."
                      {:seon.error/kind
                       :seon.operator/ephemeral-owner-not-alive
                       :seon.operator/ephemeral-owner ephemeral-owner})))
        previous-creator (:seon.operator.claim/creator previous)
        creator (or ephemeral-owner (current-process-identity))
        creator-changed? (and previous-creator
                              (not= previous-creator creator))
        previous-creator-alive?
        (and creator-changed?
             (process-identity-alive? previous-creator))
        _ (when (and (not new-lifecycle?) previous-creator-alive?)
            (throw
             (ex-info "The managed root already has a different creator."
                      {:seon.error/kind
                       :seon.operator/root-creator-mismatch
                       :seon.operator.claim/creator previous-creator
                       :seon.operator.claim/requested-creator creator})))
        superseding? (and (not new-lifecycle?)
                          creator-changed?
                          (not previous-creator-alive?))
        creator (if (and previous-creator
                         (not new-lifecycle?)
                         (not superseding?))
                  previous-creator
                  creator)
        ephemeral? (if (or new-lifecycle?
                           superseding?
                           (nil? previous-creator))
                     (boolean ephemeral-owner)
                     (boolean (:seon.operator.claim/ephemeral? previous)))
        supersessions
        (cond-> (vec (:seon.operator.claim/supersessions previous))
          superseding?
          (conj {:seon.operator.claim/creator previous-creator
                 :seon.operator.claim/ephemeral?
                 (boolean (:seon.operator.claim/ephemeral? previous))
                 :seon.operator.claim/superseded-at claimed-at}))
        store-path (str (fs/path root "data" "store"))
        clusters (cond-> (set (:seon.operator.claim/clusters previous))
                   cluster-name (conj cluster-name))
        claim (merge previous
                     {:seon.operator.claim/id (root-claim-id root)
                      :seon.operator.claim/root root
                      :seon.operator.claim/repository-root
                      (canonical-path repository-root)
                      :seon.operator.claim/store
                      {:seon.store/backend :file
                       :seon.store/path store-path
                       :seon.store/id
                       (UUID/nameUUIDFromBytes
                        (.getBytes store-path
                                   java.nio.charset.StandardCharsets/UTF_8))}
                      :seon.operator.claim/ephemeral? (boolean ephemeral?)
                      :seon.operator.claim/reap-on-owner-exit?
                      (boolean ephemeral?)
                      :seon.operator.claim/creator creator
                      :seon.operator.claim/supersessions supersessions
                      :seon.operator.claim/clusters clusters
                      :seon.operator.claim/claimed-at claimed-at})]
    (write-edn! path claim)))

(defn claim-root!
  "Publish root/store/cluster intent before creating anything below the root."
  [repository-root managed-root ephemeral-owner cluster-name]
  (with-control-lock*
    repository-root
    "publish managed root claim"
    #(claim-root-under-lock! repository-root managed-root
                             ephemeral-owner cluster-name)))

(defn mark-root-created-under-lock!
  [repository-root managed-root]
  (let [path (root-claim-path repository-root managed-root)
            claim (or (read-edn path)
                      (throw (ex-info "The managed root has no external claim."
                                      {:seon.operator.claim/root
                                       (canonical-path managed-root)})))]
    (write-edn! path (assoc claim :seon.operator.claim/created-at (Date.)))))

(defn mark-root-created!
  [repository-root managed-root]
  (with-control-lock*
    repository-root
    "mark managed root created"
    #(mark-root-created-under-lock! repository-root managed-root)))

(defn write-process-claim!
  [repository-root record]
  (write-edn! (process-claim-path repository-root
                                  (:seon.operator.process-record/generation record))
              record))

(defn delete-process-claim!
  [repository-root generation]
  (delete-edn! (process-claim-path repository-root generation)))

(defn- process-record?
  [record]
  (and (map? record)
       (uuid? (:seon.operator.process-record/generation record))
       (pos-int? (:seon.boot/pid record))
       (inst? (:seon.boot/start-instant record))
       (string? (:seon.operator.process-record/root record))))

(defn- root-claim?
  [claim]
  (and (map? claim)
       (uuid? (:seon.operator.claim/id claim))
       (string? (:seon.operator.claim/root claim))
       (map? (:seon.operator.claim/creator claim))
       (pos-int? (get-in claim [:seon.operator.claim/creator :seon.boot/pid]))
       (inst? (get-in claim
                      [:seon.operator.claim/creator
                       :seon.boot/start-instant]))))

(defn root-claim
  "Read one exact external root claim, or nil when it is absent."
  [repository-root managed-root]
  (let [path (root-claim-path repository-root managed-root)
        claim (read-edn path)]
    (cond
      (nil? claim) nil
      (root-claim? claim) claim
      :else
      (throw
       (ex-info "The exact external root claim is invalid."
                {:seon.error/kind :seon.operator/unreadable-claim
                 :seon.operator.claim/path (str path)})))))

(defn- invalid-claim-error
  [path message record]
  (let [root (when (map? record)
               (or (:seon.operator.claim/root record)
                   (:seon.operator.process-record/root record)))
        absent-root? (and (string? root) (not (fs/exists? root)))
        data (cond-> {:seon.operator.claim/path (str path)
                      :seon.operator.claim/invalid-cause
                      (if absent-root?
                        :seon.operator.claim/absent-root
                        :seon.operator.claim/malformed-record)}
               (string? root) (assoc :seon.operator.claim/root root))]
    (cond->
     {:seon.error/kind :seon.operator/unreadable-claim
      :seon.error/message message
      :seon.error/data data
      :seon.operator.claim/path (str path)
      :seon.operator.claim/invalid-cause
      (:seon.operator.claim/invalid-cause data)}
      (string? root) (assoc :seon.operator.claim/root root))))

(defn- read-claim-records
  [directory valid?]
  (if-not (fs/directory? directory)
    {:records [] :errors []}
    (reduce
     (fn [result path]
       (try
         (let [record (read-edn path)]
           (if (valid? record)
             (update result :records conj record)
             (update result :errors conj
                     (invalid-claim-error
                      path "The external claim is invalid." record))))
         (catch Throwable error
           (update result :errors conj
                   (invalid-claim-error path (ex-message error) nil)))))
     {:records [] :errors []}
     (sort-by str (fs/list-dir directory)))))

(defn process-claims
  [repository-root]
  (read-claim-records (process-claim-directory repository-root)
                      process-record?))

(defn stop-recorded-process-under-lock!
  "Stop a freshly re-read exact process claim through graceful prepl then signal."
  [repository-root record advertisements silence-ms]
  (let [generation (:seon.operator.process-record/generation record)
        current (read-edn (process-claim-path repository-root generation))
        identity-keys [:seon.operator.process-record/generation
                       :seon.operator.process-record/root
                       :seon.boot/pid
                       :seon.boot/start-instant]]
    (when-not (= (select-keys record identity-keys)
                 (select-keys current identity-keys))
      (throw
       (ex-info "The external process claim changed before exact stop."
                {:seon.error/kind :seon.operator/process-claim-mismatch
                 :seon.operator.process-record/generation generation})))
    (let [advertisement
          (some
           (fn [observation]
             (let [candidate (:seon.operator.state/advertisement observation)]
               (when (and (= (:seon.boot/pid record)
                             (:seon.boot/pid candidate))
                          (= (:seon.boot/start-instant record)
                             (:seon.boot/start-instant candidate)))
                 candidate)))
           advertisements)
          graceful? (and advertisement
                         (process-identity-alive? record)
                         (graceful-stop! advertisement silence-ms))
          signal-path (terminate-recorded-process! record silence-ms)
          stop-path (if (and graceful? (= :already-exited signal-path))
                      :prepl
                      signal-path)]
      (when (process-identity-alive? record)
        (throw
         (ex-info "The exact recorded process remained alive after stop."
                  {:seon.error/kind :seon.operator/process-remained-alive
                   :seon.operator.process-record/generation generation
                   :seon.boot/pid (:seon.boot/pid record)})))
      (delete-process-claim! repository-root generation)
      {:seon.operator.process-record/generation generation
       :seon.boot/pid (:seon.boot/pid record)
       :seon.boot/start-instant (:seon.boot/start-instant record)
       :seon.operator.reap/stop-path stop-path})))

(defn root-claims
  [repository-root]
  (read-claim-records
   (fs/path (control-root repository-root) "claims" "roots")
   root-claim?))

(defn read-advertisement
  "Read one cluster advertisement as ordinary data when valid EDN exists."
  [managed-root cluster-name]
  (try
    (let [path (fs/path managed-root "data" "clusters" cluster-name "prepl.edn")
          value (read-edn path)]
      (when (map? value) value))
    (catch Throwable _ nil)))

(defn advertisement-observations
  "Observe every advertisement below one exact managed root."
  [managed-root]
  (let [root (canonical-path managed-root)
        directory (fs/path root "data" "clusters")]
    (if-not (fs/directory? directory)
      []
      (into
       []
       (comp
        (filter fs/directory?)
        (keep
         (fn [cluster-directory]
           (let [cluster-name (str (fs/file-name cluster-directory))
                 path (fs/path cluster-directory "prepl.edn")]
             (when (fs/regular-file? path)
               (let [advertisement (read-advertisement root cluster-name)]
                 {:seon.operator.state/name cluster-name
                  :seon.operator.state/root root
                  :seon.operator.state/path (str path)
                  :seon.operator.state/advertisement advertisement
                  :seon.operator.state/alive?
                  (boolean (advertisement-identity-alive? advertisement))}))))))
       (sort-by str (fs/list-dir directory))))))

(defn- optional-value
  [optional]
  (when (.isPresent optional)
    (.get optional)))

(defn- process-property
  [^java.lang.ProcessHandle handle property-name]
  (let [arguments (some-> (optional-value (.arguments (.info handle))) vec)
        prefix (str "-D" property-name "=")]
    (some
     (fn [argument]
       (when (and (string? argument) (str/starts-with? argument prefix))
         (subs argument (count prefix))))
     arguments)))

(defn observed-property-processes
  "Observe exact JVM identities that explicitly declare an operator root."
  []
  (with-open [processes (java.lang.ProcessHandle/allProcesses)]
    (->> (iterator-seq (.iterator processes))
         (keep
          (fn [^java.lang.ProcessHandle handle]
            (when-let [root (process-property handle "seon.operator.root")]
              (when-let [start (process-start-instant (.pid handle))]
                {:seon.operator.state/root (canonical-path root)
                 :seon.operator.state/generation
                 (when-let [generation
                            (process-property handle
                                              "seon.operator.generation")]
                   (try
                     (parse-uuid generation)
                     (catch Throwable _ nil)))
                 :seon.boot/pid (.pid handle)
                 :seon.boot/start-instant start}))))
         (sort-by :seon.boot/pid)
         vec)))

(defn- process-key
  [process]
  [(:seon.boot/pid process)
   (instant-ms (:seon.boot/start-instant process))])

(defn event-silence-backstop-ms
  [repository-root request]
  (or (:seon.config.operator/event-silence-backstop-ms request)
      (get (read-edn (fs/path repository-root "config" "default.edn"))
           :seon.config.operator/event-silence-backstop-ms)
      (throw (ex-info "The operator event-silence backstop is undeclared."
                      {:seon.error/kind
                       :seon.operator/missing-event-silence-backstop}))))

(defn- responsive-advertisement?
  [advertisement silence-ms]
  (try
    (with-open [socket (Socket.)]
      (.connect socket
                (InetSocketAddress.
                 ^String (:seon.boot/prepl-host advertisement)
                 (int (:seon.boot/prepl-port advertisement)))
                silence-ms)
      (.setSoTimeout socket silence-ms)
      (with-open [writer (java.io.OutputStreamWriter.
                          (.getOutputStream socket)
                          java.nio.charset.StandardCharsets/UTF_8)
                  reader (PushbackReader.
                          (java.io.InputStreamReader.
                           (.getInputStream socket)
                           java.nio.charset.StandardCharsets/UTF_8))]
        (.write writer ":seon.operator/process-census\n")
        (.flush writer)
        (loop []
          (let [event (edn/read {:eof ::eof} reader)]
            (cond
              (= ::eof event) false
              (and (map? event) (= :ret (:tag event)))
              (not (:exception event))
              :else (recur))))))
    (catch Throwable _ false)))

(defn census-observations
  "Derive claims, exact processes, and advertisements from one observation."
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root
    :as request}]
  (let [repository-root (canonical-path repository-root)
        managed-root (canonical-path managed-root)
        {roots :records root-errors :errors} (root-claims repository-root)
        {claims :records process-errors :errors} (process-claims repository-root)
        claimed-roots (into #{managed-root}
                            (map :seon.operator.claim/root)
                            roots)
        property-processes
        (filterv #(contains? claimed-roots (:seon.operator.state/root %))
                 (observed-property-processes))
        advertisements (into [] (mapcat advertisement-observations) claimed-roots)
        advertised-processes
        (into
         []
         (keep
          (fn [observation]
            (let [advertisement (:seon.operator.state/advertisement observation)]
              (when (and (:seon.operator.state/alive? observation)
                         (pos-int? (:seon.boot/pid advertisement))
                         (inst? (:seon.boot/start-instant advertisement)))
                {:seon.operator.state/root
                 (:seon.operator.state/root observation)
                 :seon.boot/pid (:seon.boot/pid advertisement)
                 :seon.boot/start-instant
                 (:seon.boot/start-instant advertisement)}))))
         advertisements)
        observed-by-key
        (into {}
              (map (juxt process-key identity))
              (concat advertised-processes property-processes))
        advertisements-by-key
        (group-by #(process-key (:seon.operator.state/advertisement %))
                  advertisements)
        silence-ms (event-silence-backstop-ms repository-root request)
        claim-observations
        (mapv
         (fn [claim]
           (let [identity (select-keys claim
                                       [:seon.boot/pid
                                        :seon.boot/start-instant])
                 exact-advertisements (get advertisements-by-key
                                           (process-key identity) [])
                 alive? (process-identity-alive? identity)]
             {:seon.operator.state/process-record claim
              :seon.operator.state/root
              (canonical-path (:seon.operator.process-record/root claim))
              :seon.boot/pid (:seon.boot/pid claim)
              :seon.boot/start-instant (:seon.boot/start-instant claim)
              :seon.operator.state/generation
              (:seon.operator.process-record/generation claim)
              :seon.operator.state/alive? (boolean alive?)
              :seon.operator.state/responsive?
              (boolean
               (and alive?
                    (some #(responsive-advertisement?
                            (:seon.operator.state/advertisement %)
                            silence-ms)
                          exact-advertisements)))
              :seon.operator.state/advertisements exact-advertisements}))
         (sort-by (juxt :seon.operator.process-record/root :seon.boot/pid)
                  claims))
        claimed-keys (into #{} (map process-key) claims)
        unclaimed (into []
                        (remove #(contains? claimed-keys (process-key %)))
                        (vals observed-by-key))]
    {:seon.operator.state/observed-at (Date.)
     :seon.operator.state/roots (vec (sort-by :seon.operator.claim/root roots))
     :seon.operator.state/processes claim-observations
     :seon.operator.state/unclaimed (vec (sort-by :seon.boot/pid unclaimed))
     :seon.operator.state/advertisements advertisements
     :seon.operator.state/claim-errors (into root-errors process-errors)}))

(defn- public-process-identity
  [process]
  (cond->
   {:seon.dev.process/pid (:seon.boot/pid process)
    :seon.dev.process/start-instant
    (str (.toInstant ^Date (:seon.boot/start-instant process)))
    :seon.dev.process/root (:seon.operator.state/root process)}
    (:seon.operator.state/generation process)
    (assoc :seon.dev.process/generation
           (:seon.operator.state/generation process))))

(defn process-census
  "Return one complete, exact-identity process census."
  [request]
  (let [observations (census-observations request)
        processes (:seon.operator.state/processes observations)
        process-values
        (mapv
         (fn [process]
           (assoc (public-process-identity process)
                  :seon.operator.process-census/alive?
                  (:seon.operator.state/alive? process)
                  :seon.operator.process-census/responsive?
                  (:seon.operator.state/responsive? process)
                  :seon.operator.process-census/advertisements
                  (mapv :seon.operator.state/name
                        (:seon.operator.state/advertisements process))))
         processes)
        roots
        (mapv
         (fn [claim]
           (let [creator (:seon.operator.claim/creator claim)]
             {:seon.operator.claim/id (:seon.operator.claim/id claim)
              :seon.operator.claim/root (:seon.operator.claim/root claim)
              :seon.operator.claim/creator
              {:seon.dev.process/pid (:seon.boot/pid creator)
               :seon.dev.process/start-instant
               (str (.toInstant ^Date (:seon.boot/start-instant creator)))}
              :seon.operator.claim/reap-on-owner-exit?
              (:seon.operator.claim/reap-on-owner-exit? claim)}))
         (:seon.operator.state/roots observations))
        errors (:seon.operator.state/claim-errors observations)]
    {:seon.operator.process-census/observed-at
     (:seon.operator.state/observed-at observations)
     :seon.operator.process-census/roots roots
     :seon.operator.process-census/processes process-values
     :seon.operator.process-census/dead
     (into [] (comp (remove :seon.operator.state/alive?)
                    (map public-process-identity)) processes)
     :seon.operator.process-census/unresponsive
     (into [] (comp (filter :seon.operator.state/alive?)
                    (remove :seon.operator.state/responsive?)
                    (map public-process-identity)) processes)
     :seon.operator.process-census/unclaimed
     (mapv public-process-identity (:seon.operator.state/unclaimed observations))
     :seon.operator.process-census/claim-errors errors
     :seon.operator.process-census/complete? (empty? errors)}))

(defn existence
  "Read claim files only. Never opens Datahike or a managed root database."
  [repository-root]
  (let [{roots :records root-errors :errors} (root-claims repository-root)
        {processes :records process-errors :errors}
        (process-claims repository-root)
        processes-by-root (group-by :seon.operator.process-record/root processes)]
    {:seon.operator/roots
     (mapv
      (fn [claim]
        (let [owned (get processes-by-root
                         (:seon.operator.claim/root claim) [])
              processes
              (mapv #(assoc % :seon.operator.claim/live?
                            (process-identity-alive? %))
                    owned)]
          (assoc claim
                 :seon.operator.claim/live?
                 (boolean (some :seon.operator.claim/live? processes))
                 :seon.operator.claim/processes processes)))
      (sort-by :seon.operator.claim/root roots))
     :seon.operator/claim-errors (into root-errors process-errors)}))

(defn filesystem-space
  "Observe usable and total filesystem space for the root's volume.
  Two statfs calls, never a directory walk — safe on any path, hot
  paths included. The boot low-space gate reads exactly this."
  [managed-root]
  (let [root-path (.normalize (.toAbsolutePath (fs/path managed-root)))
        no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        filesystem-file
        (.toFile (if (Files/exists root-path no-follow)
                   root-path
                   (.getParent root-path)))
        total (.getTotalSpace filesystem-file)
        usable (.getUsableSpace filesystem-file)]
    {:seon.operator.footprint/root (str root-path)
     :seon.operator.footprint/usable-bytes usable
     :seon.operator.footprint/total-bytes total
     :seon.operator.footprint/usable-ratio
     (if (pos? total) (/ (double usable) (double total)) 0.0)
     :seon.operator.footprint/observed-at (Date.)}))

(defn footprint
  "Observe allocated bytes and usable filesystem space without following links.
  The allocated-bytes half recursively stats every file under the root, so
  its cost is proportional to the tree — call it only on managed data roots
  (status, cleanup accounting, scheduled observation), never on a boot path
  and never on a repository checkout."
  [managed-root]
  (let [root-path (.normalize (.toAbsolutePath (fs/path managed-root)))
        no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        bytes
        (if-not (Files/exists root-path no-follow)
          0
          (letfn [(size-of [path]
                    (cond
                      (Files/isSymbolicLink path) 0
                      (Files/isDirectory path no-follow)
                      (with-open [children (Files/newDirectoryStream path)]
                        (reduce + 0 (map size-of (vec children))))
                      :else (Files/size path)))]
            (size-of root-path)))]
    (assoc (filesystem-space managed-root)
           :seon.operator.footprint/file-bytes bytes)))

(defn record-footprint-under-lock!
  "Record one observation while the caller holds the lifecycle lock."
  [repository-root managed-root]
  (let [path (root-claim-path repository-root managed-root)
        claim (or (read-edn path)
                  (throw (ex-info "The managed root has no external claim."
                                  {:seon.operator.claim/root
                                   (canonical-path managed-root)})))
        managed-data (fs/path managed-root "data")
        observation (footprint (if (fs/exists? managed-data)
                                 managed-data
                                 managed-root))]
    (write-edn! path
                (assoc claim :seon.operator.claim/footprint observation))
    observation))

(defn record-footprint!
  [repository-root managed-root]
  (with-control-lock*
    repository-root
    "record managed root footprint"
    #(record-footprint-under-lock! repository-root managed-root)))

(defn mark-root-destroyed-under-lock!
  [repository-root managed-root result]
  (let [path (root-claim-path repository-root managed-root)
            claim (or (read-edn path)
                      {:seon.operator.claim/id (root-claim-id managed-root)
                       :seon.operator.claim/root (canonical-path managed-root)})]
    (write-edn! path
                (assoc claim
                       :seon.operator.claim/destroyed-at (Date.)
                       :seon.operator.claim/cleanup result))))

(defn mark-root-destroyed!
  [repository-root managed-root result]
  (with-control-lock*
    repository-root
    "mark managed root destroyed"
    #(mark-root-destroyed-under-lock! repository-root managed-root result)))
