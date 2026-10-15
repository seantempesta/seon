(ns ^{:seon/babashka true} seon.cluster.process
  "The one owner of JVM process identity and liveness.

  A pid alone is not an identity because operating systems recycle it.
  Seon therefore identifies a process as `(pid, start-instant)` and
  compares both halves at millisecond precision, matching the platform
  projection stored in advertisements and ancestor scratch names."
  (:require [seon.error.refusal :as refusal]
            [clojure.java.io :as io]
            [babashka.process :as process]))



(defn process-handle?
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value] (instance? java.lang.ProcessHandle value))

(defn process?
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value] (instance? Process value))

(defn id
  "The provenance id of one `(pid, start-instant)` identity."
  {:malli/schema [:=> [:cat :seon.cluster.process/identity] :seon.db.process/id]}
  [identity]
  (str (:seon.boot/pid identity) "-" (inst-ms (:seon.boot/start-instant identity))))

(defn- start-instant-unavailable!
  "Refuse `pid`'s start instant as unknown: a live pid without its generation
  is not identity evidence, and unknown must never compare equal to anything."
  {:malli/schema [:=> [:cat :seon.boot/pid :symbol] :nil]}
  [pid operation]
  (let [message (str operation " refused the process start instant of pid " pid
                     ": expected the process generation timestamp, but ProcessHandle supplied none."
                     " Fix: run on a platform that exposes ProcessHandle startInstant.")]
    (throw
     (ex-info message
              (seon.error.refusal/diagnostic (java.util.Date.) :seon.cluster/process operation
               {:seon.error/message message
                :seon.error/member :seon.boot/start-instant
                :seon.error/expected :process-generation-timestamp
                :seon.cluster.process/start-instant-unavailable pid
                :seon.boot/pid pid})))))

(defn- handle-start-instant
  "The start instant of `handle`; nil only when that process is no longer alive.
  A live process whose instant the platform withholds refuses as unknown."
  {:malli/schema [:=> [:cat [:fn 'seon.cluster.process/process-handle?] :symbol]
                  [:or :nil :seon.boot/start-instant]]}
  [^java.lang.ProcessHandle handle operation]
  (let [start (.startInstant (.info handle))]
    (cond (.isPresent start) (java.util.Date/from (.get start))
          ;; The process exited between the liveness check and the info read.
          (not (.isAlive handle)) nil
          :else (start-instant-unavailable! (.pid handle) operation))))

(defn current-identity
  "This JVM's `(pid, start-instant)` identity.

  Refuses when the platform cannot publish the start instant: a pid
  without its generation is not safe ownership evidence."
  {:malli/schema [:=> [:cat] :seon.cluster.process/identity]}
  []
  (let [handle (java.lang.ProcessHandle/current)]
    {:seon.boot/pid (.pid handle)
     :seon.boot/start-instant (or (handle-start-instant handle `current-identity)
                                  (start-instant-unavailable! (.pid handle) `current-identity))}))

(defn process-start-instant
  "The OS start instant of `pid`; nil only when no live process has that pid.
  No platform failure is caught: an unreadable or withheld instant refuses,
  so an unknown generation never reads as a dead or matching process."
  {:malli/schema [:=> [:cat :seon.boot/pid] [:or :nil :seon.boot/start-instant]]}
  [pid]
  (let [optional (java.lang.ProcessHandle/of (long pid))]
    (when (and (.isPresent optional) (.isAlive ^java.lang.ProcessHandle (.get optional)))
      (handle-start-instant (.get optional) `process-start-instant))))

(defn live?
  "True exactly when `identity` names a currently live JVM generation.

  A recycled pid whose current start instant differs is dead from the
  recorded owner's perspective. A live pid whose instant the platform
  withholds refuses through `process-start-instant`: unknown is never dead."
  {:malli/schema [:=> [:cat :seon.cluster.process/identity] :boolean]}
  [{:seon.boot/keys [pid start-instant]}]
  (= (inst-ms start-instant) (some-> (process-start-instant pid) inst-ms)))

(defn process-identity-alive?
  "True when a PID still has the recorded OS start instant."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{:seon.boot/keys [pid start-instant]}]
  (and (integer? pid)
       (inst? start-instant)
       (= start-instant (process-start-instant pid))))

(defn matching-process-handle
  "The live handle whose generation is exactly `record`'s, or nil. The record
  must carry its start instant: an unknown generation can match no process."
  {:malli/schema [:=> [:cat :seon.cluster.process/identity] [:or :nil [:fn 'seon.cluster.process/process-handle?]]]}
  [record]
  (let [optional (java.lang.ProcessHandle/of
                  (long (:seon.boot/pid record)))]
    (when (.isPresent optional)
      (let [handle (.get optional)]
        (when (= (:seon.boot/start-instant record)
                 (process-start-instant (.pid handle)))
          handle)))))


(def ^:private subprocess-cleanup-ms 10000)
;; TERM grace before force; the operator bound already expired, so keep it short.
(def ^:private subprocess-grace-ms 1000)


(defn- subprocess-remaining-ms
  {:malli/schema [:=> [:cat :int] [:int {:min 0}]]}
  [deadline-ns]
  (max 0 (long (/ (- deadline-ns (System/nanoTime)) 1000000))))

(defn- join-output
  "Each capture task's outcome by `deadline-ns`: its value, the Throwable it
  ended with, or `timeout-value` while it still runs."
  {:malli/schema [:=> [:cat [:vector :seon.schema/value] :int :seon.schema/value] [:vector :seon.schema/value]]}
  [tasks deadline-ns timeout-value]
  (mapv #(if (future? %)
           (try (deref % (subprocess-remaining-ms deadline-ns) timeout-value)
                (catch java.util.concurrent.ExecutionException failure (.getCause failure)))
           %)
        tasks))

(defn- subprocess-identity
  "`handle`'s identity, or nil when it has already exited."
  {:malli/schema [:=> [:cat [:fn 'seon.cluster.process/process-handle?]] [:or :nil :seon.cluster.process/identity]]}
  [^java.lang.ProcessHandle handle]
  (when-let [start (handle-start-instant handle `subprocess-identity)]
    {:seon.boot/pid (.pid handle) :seon.boot/start-instant start}))

(defn- same-subprocess-handle
  {:malli/schema [:=> [:cat :seon.cluster.process/identity] [:or :nil [:fn 'seon.cluster.process/process-handle?]]]}
  [{:seon.boot/keys [pid] :as process-identity}]
  (let [candidate (matching-process-handle process-identity)]
    (when (and candidate (= pid (.pid ^java.lang.ProcessHandle candidate)))
      candidate)))

(defn subprocess-tree-identities
  "The `(pid, start-instant)` identities of `root` and its current descendants.
  A member that exited during the walk is absent. An exited root has no
  descendants: capture while it is alive."
  {:malli/schema [:=> [:cat [:fn 'seon.cluster.process/process-handle?]] [:vector :seon.cluster.process/identity]]}
  [^java.lang.ProcessHandle root]
  (with-open [descendant-stream (.descendants root)]
    (into (if-let [root-identity (subprocess-identity root)] [root-identity] [])
          (keep subprocess-identity)
          (iterator-seq (.iterator descendant-stream)))))

(defn- signal-pids!
  "Send `signal` (\"STOP\" or \"CONT\"; the JDK sends neither) to `pids` through the
  OS `kill`. A pid that exited meanwhile is not a failure: the next walk sees it gone."
  {:malli/schema [:=> [:cat :string [:sequential :seon.boot/pid]] :nil]}
  [signal pids]
  (when (seq pids)
    (let [kill (.start (doto (ProcessBuilder. ^java.util.List (into ["kill" (str "-" signal)] (map str) pids))
                         (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                         (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)))]
      (when-not (.waitFor kill subprocess-grace-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (.destroyForcibly kill)
        (throw (ex-info (str "kill -" signal " did not exit within its bound.")
                        {:seon.boot/pid (.pid kill) :seon.operator.subprocess/argv ["kill" signal]}))))))

(defn- freeze-tree!
  "SIGSTOP every live member of `identities` and its descendants, walking again
  until a walk finds no new member: a stopped process cannot fork, so nothing
  born during termination escapes the set. Returns the frozen set."
  {:malli/schema [:=> [:cat [:sequential :seon.cluster.process/identity]] [:vector :seon.cluster.process/identity]]}
  [identities]
  (loop [frozen [] walked (distinct identities)]
    (if-let [unseen (seq (remove (set frozen) walked))]
      (let [frozen (into frozen unseen)]
        (signal-pids! "STOP" (keep #(when (same-subprocess-handle %) (:seon.boot/pid %)) unseen))
        (recur frozen (distinct (mapcat #(some-> (same-subprocess-handle %) subprocess-tree-identities) frozen))))
      frozen)))

(defn terminate-subprocess!
  "Terminate every captured identity plus the root's current tree. Freeze the
  tree to a fixpoint, TERM each member and CONT it, wait up to `grace-ms` for
  all to exit, freeze again (a TERM handler may fork), force every frozen
  member, then await actual exit within `cleanup-ms`. Every signal first
  verifies `(pid, start-instant)`. Returns the identities still alive after
  `cleanup-ms`: empty only when every exit was observed. A survivor (a member
  this JVM may not signal, or one blocked in the kernel) is the caller's to
  keep owning, never ended."
  {:malli/schema [:=> [:cat [:map [:proc [:fn 'seon.cluster.process/process?]]] [:vector :seon.cluster.process/identity] [:int {:min 0}] [:int {:min 0}]] [:vector :seon.cluster.process/identity]]}
  [process-record launch-identities grace-ms cleanup-ms]
  (let [^Process child (:proc process-record)
        signal! (fn [identities destroy!]
                  (doseq [process-identity identities]
                    (when-let [handle (same-subprocess-handle process-identity)]
                      (destroy! handle))))
        await-all! (fn [identities bound-ms]
                     (let [deadline (+ (System/nanoTime) (* 1000000 (long bound-ms)))]
                       (doseq [process-identity identities]
                         (when-let [handle (same-subprocess-handle process-identity)]
                           (try
                             (.get (.onExit ^java.lang.ProcessHandle handle)
                                   (subprocess-remaining-ms deadline)
                                   java.util.concurrent.TimeUnit/MILLISECONDS)
                             (catch java.util.concurrent.TimeoutException _ nil)
                             (catch java.util.concurrent.ExecutionException error (throw (.getCause error))))))))
        frozen (freeze-tree! (concat launch-identities (subprocess-tree-identities (.toHandle child))))]
    (signal! frozen #(.destroy ^java.lang.ProcessHandle %))
    (signal-pids! "CONT" (keep #(when (same-subprocess-handle %) (:seon.boot/pid %)) frozen))
    (await-all! frozen grace-ms)
    (let [frozen (freeze-tree! frozen)]
      (signal! frozen #(.destroyForcibly ^java.lang.ProcessHandle %))
      (await-all! frozen cleanup-ms)
      (filterv same-subprocess-handle frozen))))

(defn subprocess-exit
  "A future completing when every one of `identities` has exited; an
  identity already gone contributes nothing."
  {:malli/schema [:=> [:cat [:vector :seon.cluster.process/identity]] :seon.flow/future]}
  [identities]
  (java.util.concurrent.CompletableFuture/allOf
   (into-array java.util.concurrent.CompletableFuture
               (keep #(when-let [^java.lang.ProcessHandle handle (same-subprocess-handle %)]
                         (.onExit handle))
                      identities))))

(defn run-process!
  "Run one foreign argv under its deadline."
  {:malli/schema [:=> [:cat [:map
                 [:seon.operator.subprocess/argv [:vector {:min 1} :string]]
                 [:seon.operator.subprocess/deadline-ms [:int {:min 1}]]
                 [:seon.operator.subprocess/directory {:optional true} :string]
                 [:seon.operator.subprocess/extra-env {:optional true} [:map-of :string :string]]
                 [:seon.operator.subprocess/input {:optional true} :string]
                 [:seon.operator.subprocess/merge-error? {:optional true} :boolean]
                 [:seon.operator.subprocess/output-file {:optional true} :string]
                 [:seon.operator.subprocess/observe-output! {:optional true} [:=> [:cat :string] :seon.schema/value]]]]
     [:map [:seon.operator.subprocess/argv [:vector :string]]
      [:seon.operator.subprocess/exit :int]
      [:seon.operator.subprocess/output :string]
      [:seon.operator.subprocess/error-output :string]]]}
  [{argv :seon.operator.subprocess/argv
    deadline-ms :seon.operator.subprocess/deadline-ms
    directory :seon.operator.subprocess/directory
    extra-env :seon.operator.subprocess/extra-env
    input :seon.operator.subprocess/input
    merge-error? :seon.operator.subprocess/merge-error?
    output-file :seon.operator.subprocess/output-file
    observe-output! :seon.operator.subprocess/observe-output!
    :as request}]
  (when-not (and (vector? argv) (seq argv) (every? string? argv)
                 (integer? deadline-ms) (pos? deadline-ms))
    (throw
     (ex-info "Supply nonempty argv and a positive process deadline."
              (seon.error.refusal/diagnostic (java.util.Date.) :seon.operator/lifecycle 'seon.cluster.process/run-process!
               {:seon.error/message "Supply nonempty argv and a positive process deadline."
         :seon.error/offending request
         :seon.error/member :seon.operator.subprocess/deadline-ms
         :seon.error/expected "a positive deadline and nonempty argv"
         :seon.operator.subprocess/deadline-member :seon.operator.subprocess/deadline-ms}))))
  (let [deadline-ns (+ (System/nanoTime) (* 1000000 (long deadline-ms)))
        options (cond-> {:out (if output-file :write :string)
                         :err (if merge-error? :out :string)
                         :shutdown process/destroy-tree}
                  directory (assoc :dir directory)
                  extra-env (assoc :extra-env extra-env)
                  (some? input) (assoc :in input)
                  output-file (assoc :out-file output-file))
        options (if observe-output! (dissoc (assoc options :out :stream) :out-file) options)
        process-record (process/process argv options)
        streamed-output (when observe-output!
                          (future
                            (with-open [reader (io/reader (:out process-record))
                                        writer (if output-file (io/writer output-file) (java.io.StringWriter.))]
                              (doseq [line (line-seq reader)]
                                (.write writer (str line "\n"))
                                (.flush writer)
                                (observe-output! line))
                              (if output-file "" (str writer)))))
        ^Process child (:proc process-record)
        root (.toHandle child)
        identities (subprocess-tree-identities root)
        timeout-value (Object.)
        completed? (.waitFor child (subprocess-remaining-ms deadline-ns) java.util.concurrent.TimeUnit/MILLISECONDS)
        output-deadline deadline-ns
        tasks [(cond streamed-output streamed-output output-file "" :else (:out process-record))
               (if merge-error? "" (:err process-record))]
        [output error-output] (when completed? (join-output tasks output-deadline timeout-value))
        missing? #(or (identical? timeout-value %) (instance? Throwable %))
        phase (cond
                (not completed?) :process-exit
                (missing? output) :stdout
                (missing? error-output) :stderr
                :else nil)]
    (if phase
      (let [survivors (terminate-subprocess! process-record identities
                                              subprocess-grace-ms subprocess-cleanup-ms)
            ;; Every capture is joined: termination closed its pipe, so only a blocked observer outlives this.
            joined (join-output tasks (+ (System/nanoTime) (* 1000000 subprocess-grace-ms)) timeout-value)
            unjoined (into [] (keep (fn [[stream outcome]] (when (identical? timeout-value outcome) stream)))
                           (map vector [:stdout :stderr] joined))
            [cause & suppressed] (filter #(instance? Throwable %) joined)
            ;; A root that exited before its launch capture has no recorded instant.
            root-start (some #(when (= (.pid child) (:seon.boot/pid %)) (:seon.boot/start-instant %))
                             identities)
            refusal (ex-info
                     "The process and its output must finish within the declared bound."
                     (seon.error.refusal/diagnostic (java.util.Date.) :seon.operator/lifecycle 'seon.cluster.process/run-process!
                      (cond-> {:seon.error/message "The process and its output must finish within the declared bound."
                               :seon.error/offending argv
                               :seon.error/member :seon.operator.subprocess/deadline-ms
                               :seon.error/expected deadline-ms
                               :seon.operator.subprocess/argv argv
                               :seon.operator.subprocess/deadline-ms deadline-ms
                               :seon.operator.subprocess/phase phase
                               :seon.operator.subprocess/pid (.pid child)
                               :seon.operator.subprocess/reaped? (empty? survivors)}
                        (seq survivors) (assoc :seon.operator.subprocess/survivors survivors)
                        (seq unjoined) (assoc :seon.operator.subprocess/unjoined unjoined)
                        root-start (assoc :seon.operator.subprocess/start-instant root-start)))
                     cause)]
        (doseq [failure suppressed] (.addSuppressed refusal failure))
        (throw refusal))
      {:seon.operator.subprocess/argv argv
       :seon.operator.subprocess/exit (.exitValue child)
       :seon.operator.subprocess/output output
       :seon.operator.subprocess/error-output error-output})))
