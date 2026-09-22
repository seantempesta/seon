(ns seon.cluster.process
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

(defn progress-atom?
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value] (instance? clojure.lang.IAtom value))

(defn current-identity
  "This JVM's `(pid, start-instant)` identity.

  Refuses when the platform cannot publish the start instant: a pid
  without its generation is not safe ownership evidence."
  {:malli/schema [:=> [:cat] :seon.cluster.process/identity]}
  []
  (let [handle (java.lang.ProcessHandle/current)
        start (.startInstant (.info handle))]
    (when-not (.isPresent start)
      (throw
       (ex-info
        "current-identity refused the process start instant: expected the JVM generation timestamp, but ProcessHandle supplied none. Fix: run on a platform that exposes ProcessHandle startInstant."
        {:seon.error/at (java.util.Date.)
          :seon.error/layer :seon.cluster/process
          :seon.error/operation `current-identity
          :seon.error/message
          "current-identity refused the process start instant: expected the JVM generation timestamp, but ProcessHandle supplied none. Fix: run on a platform that exposes ProcessHandle startInstant."
          :seon.error/member :seon.boot/start-instant
          :seon.error/expected :process-generation-timestamp
          :seon.cluster.process/start-instant-unavailable (.pid handle)
          :seon.boot/pid (.pid handle)})))
    {:seon.boot/pid (.pid handle)
     :seon.boot/start-instant (java.util.Date/from (.get start))}))

(defn live?
  "True exactly when `identity` names a currently live JVM generation.

  A recycled pid whose current start instant differs is dead from the
  recorded owner's perspective. Platform lookup failures are absence,
  not exceptions escaping a liveness derivation."
  {:malli/schema [:=> [:cat :seon.cluster.process/identity] :boolean]}
  [{:seon.boot/keys [pid start-instant]}]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (boolean
       (when (.isPresent optional)
         (let [handle (.get optional)
               start (.startInstant (.info handle))]
           (and (.isAlive handle)
                (.isPresent start)
                (= (inst-ms start-instant)
                   (.toEpochMilli ^java.time.Instant (.get start))))))))
    (catch Throwable _
      false)))

(defn process-start-instant
  "Return the OS start instant for a live PID."
  {:malli/schema [:=> [:cat :seon.boot/pid] [:or :nil :seon.boot/start-instant]]}
  [pid]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (when (and (.isPresent optional) (.isAlive ^java.lang.ProcessHandle (.get optional)))
        (let [instant (.startInstant (.info (.get optional)))]
          (when (.isPresent instant) (java.util.Date/from (.get instant))))))
    (catch Throwable _ nil)))

(defn process-identity-alive?
  "True when a PID still has the recorded OS start instant."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{:seon.boot/keys [pid start-instant]}]
  (and (integer? pid)
       (inst? start-instant)
       (= start-instant (process-start-instant pid))))

(defn matching-process-handle
  {:malli/schema [:=> [:cat [:map [:seon.boot/pid :seon.boot/pid] [:seon.boot/start-instant [:or :nil :seon.boot/start-instant]]]] [:or :nil [:fn seon.cluster.process/process-handle?]]]}
  [record]
  (let [optional (java.lang.ProcessHandle/of
                  (long (:seon.boot/pid record)))]
    (when (.isPresent optional)
      (let [handle (.get optional)]
        (when (= (:seon.boot/start-instant record)
                 (process-start-instant (.pid handle)))
          handle)))))


(def ^:private subprocess-cleanup-ms 10000)


(defn- subprocess-remaining-ms
  {:malli/schema [:=> [:cat :int] [:int {:min 0}]]}
  [deadline-ns]
  (max 0 (long (/ (- deadline-ns (System/nanoTime)) 1000000))))

(defn- await-subprocess-value
  "Generic future-or-value join; the caller owns both result and identity sentinel."
  {:malli/schema [:=> [:cat :seon.schema/value :int :seon.schema/value] :seon.schema/value]}
  [value deadline-ns timeout-value]
  (if (future? value)
    (deref value (subprocess-remaining-ms deadline-ns) timeout-value)
    value))

(defn- subprocess-identity
  {:malli/schema [:=> [:cat [:fn seon.cluster.process/process-handle?]] [:map [:seon.boot/pid :seon.boot/pid] [:seon.boot/start-instant [:or :nil :seon.boot/start-instant]]]]}
  [^java.lang.ProcessHandle handle]
  {:seon.boot/pid (.pid handle)
   :seon.boot/start-instant (process-start-instant (.pid handle))})

(defn- same-subprocess-handle
  {:malli/schema [:=> [:cat [:map [:seon.boot/pid :seon.boot/pid] [:seon.boot/start-instant [:or :nil :seon.boot/start-instant]]]] [:or :nil [:fn seon.cluster.process/process-handle?]]]}
  [{:seon.boot/keys [pid] :as process-identity}]
  (let [candidate (matching-process-handle process-identity)]
    (when (and candidate (= pid (.pid ^java.lang.ProcessHandle candidate)))
      candidate)))

(defn- subprocess-tree-identities
  {:malli/schema [:=> [:cat [:fn seon.cluster.process/process-handle?]] [:vector [:map [:seon.boot/pid :seon.boot/pid] [:seon.boot/start-instant [:or :nil :seon.boot/start-instant]]]]]}
  [^java.lang.ProcessHandle root]
  (with-open [descendant-stream (.descendants root)]
    (into [(subprocess-identity root)]
          (map subprocess-identity)
          (iterator-seq (.iterator descendant-stream)))))

(defn- terminate-subprocess!
  {:malli/schema [:=> [:cat [:map [:proc [:fn seon.cluster.process/process?]]] [:vector [:map [:seon.boot/pid :seon.boot/pid] [:seon.boot/start-instant [:or :nil :seon.boot/start-instant]]]]] :boolean]}
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
                  java.util.concurrent.TimeUnit/MILLISECONDS)
            (catch java.util.concurrent.TimeoutException _ nil)
            (catch java.util.concurrent.ExecutionException error (throw (.getCause error)))))))
    (not-any? same-subprocess-handle identities)))

(defn run-process!
  "Run one foreign argv under a deadline, or an explicitly declared event-silence bound.
  A supplied progress atom names phase events; ordinary output is not progress."
  {:malli/schema [:=> [:cat [:map
                 [:seon.operator.subprocess/argv [:vector {:min 1} :string]]
                 [:seon.operator.subprocess/deadline-ms [:int {:min 1}]]
                 [:seon.operator.subprocess/directory {:optional true} :string]
                 [:seon.operator.subprocess/extra-env {:optional true} [:map-of :string :string]]
                 [:seon.operator.subprocess/input {:optional true} :string]
                 [:seon.operator.subprocess/merge-error? {:optional true} :boolean]
                 [:seon.operator.subprocess/output-file {:optional true} :string]
                 [:seon.operator.subprocess/event-silence-ms {:optional true} [:int {:min 1}]]
                 [:seon.operator.subprocess/progress {:optional true} [:fn seon.cluster.process/progress-atom?]]
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
    silence-ms :seon.operator.subprocess/event-silence-ms
    progress :seon.operator.subprocess/progress
    observe-output! :seon.operator.subprocess/observe-output!
    :as request}]
  (when-not (and (vector? argv) (seq argv) (every? string? argv)
                 (integer? deadline-ms) (pos? deadline-ms))
    (throw
     (ex-info "Supply nonempty argv and a positive process deadline."
              {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.operator/lifecycle
         :seon.error/operation 'seon.cluster.process/run-process!
         :seon.error/message "Supply nonempty argv and a positive process deadline."
         :seon.error/offending request
         :seon.error/member :seon.operator.subprocess/deadline-ms
         :seon.error/expected "a positive deadline and nonempty argv"
         :seon.operator.subprocess/deadline-member :seon.operator.subprocess/deadline-ms})))
  (when (and silence-ms (not (and (pos-int? silence-ms) progress observe-output!)))
    (throw (ex-info "Supply a progress atom and output observer for the silence bound."
                    {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.operator/lifecycle
         :seon.error/operation 'seon.cluster.process/run-process!
         :seon.error/message "Supply a progress atom and output observer for the silence bound."
         :seon.error/offending request
         :seon.error/member :seon.operator.subprocess/progress
         :seon.error/expected "a phase observation for the silence bound"
         :seon.operator.subprocess/progress-member :seon.operator.subprocess/progress})))
  (let [deadline-ns (+ (System/nanoTime) (* 1000000 (long deadline-ms)))
        last-progress (atom (System/nanoTime))
        watch-key (Object.)
        _ (when silence-ms (add-watch progress watch-key
                             (fn [_ _ _ _] (reset! last-progress (System/nanoTime)))))
        options (cond-> {:out (if output-file :write :string)
                         :err (if merge-error? :out :string)
                         :shutdown process/destroy-tree}
                  directory (assoc :dir directory)
                  extra-env (assoc :extra-env extra-env)
                  (some? input) (assoc :in input)
                  output-file (assoc :out-file output-file))
        options (if observe-output! (dissoc (assoc options :out :stream) :out-file) options)
        process-record (try (process/process argv options)
                            (catch Throwable failure
                              (when silence-ms (remove-watch progress watch-key))
                              (throw failure)))
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
        completed? (try
                     (loop []
                       (let [deadline (if silence-ms
                                        (+ @last-progress (* 1000000 (long silence-ms)))
                                        deadline-ns)]
                         (cond (.waitFor child (subprocess-remaining-ms deadline) java.util.concurrent.TimeUnit/MILLISECONDS) true
                               (and silence-ms (> @last-progress (- deadline (* 1000000 (long silence-ms))))) (recur)
                               :else false)))
                     (finally (when silence-ms (remove-watch progress watch-key))))
        output-deadline (if silence-ms (+ @last-progress (* 1000000 (long silence-ms))) deadline-ns)
        output (when completed?
                 (cond streamed-output (await-subprocess-value streamed-output output-deadline timeout-value)
                       output-file ""
                       :else (await-subprocess-value (:out process-record) output-deadline timeout-value)))
        error-output (when completed?
                       (if merge-error?
                         ""
                         (await-subprocess-value (:err process-record)
                                                 output-deadline timeout-value)))
        phase (cond
                (not completed?) :process-exit
                (identical? timeout-value output) :stdout
                (identical? timeout-value error-output) :stderr
                :else nil)]
    (if phase
      (let [reaped? (terminate-subprocess! process-record identities)]
        (throw
         (ex-info
          "The process and its output must finish within the declared bound."
          {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.operator/lifecycle
         :seon.error/operation 'seon.cluster.process/run-process!
         :seon.error/message "The process and its output must finish within the declared bound."
         :seon.error/offending argv
         :seon.error/member :seon.operator.subprocess/deadline-ms
         :seon.error/expected deadline-ms

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
