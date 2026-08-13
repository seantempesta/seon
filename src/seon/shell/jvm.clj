(ns seon.shell.jvm
  "Protected JVM implementation of foreground shell execution."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [seon.blob :as blob]
            [seon.effect :as effect]
            [seon.fs.jvm]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form]
            [seon.sci.kernel :as kernel])
  (:import [java.io InputStream OutputStream]
           [java.lang ProcessHandle Thread$Builder$OfVirtual]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util HexFormat Optional]
           [java.util.concurrent TimeUnit TimeoutException]))

(set! *warn-on-reflection* true)

(def ^:private io-buffer-bytes 65536)
(def ^:private empty-digest
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(defn- flat-error
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- error-value
  [error kind message data]
  (let [classified (ex-data error)]
    (if (and (keyword? (:seon.error/kind classified))
             (string? (:seon.error/message classified)))
      (flat-error (:seon.error/kind classified)
                  (:seon.error/message classified)
                  (:seon.error/data classified))
      (flat-error kind message data))))

(defn- strict-utf8
  [octets]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (str (.decode decoder (ByteBuffer/wrap ^bytes octets)))))

(defn- octet-values
  [octets]
  (mapv #(bit-and 0xff %) ^bytes octets))

(defn- cwd-path
  [cwd effective]
  (let [stat (#'seon.fs.jvm/stat {:my.fs/path cwd} effective)]
    (cond
      (:seon.error/kind stat)
      (flat-error :my.shell/cwd-refused
                  "The child working directory is outside filesystem policy."
                  {:my.shell/cwd cwd
                   :my.shell/filesystem-error (:seon.error/kind stat)})

      (not (:my.fs/directory? stat))
      (flat-error :my.shell/cwd-refused
                  "The child working directory is not a no-follow directory."
                  {:my.shell/cwd cwd})

      :else
      (let [working-root
            (fs/normalize
             (fs/absolutize (:seon.config.fs/working-root effective)))
            candidate (fs/path cwd)]
        (fs/normalize
         (if (.isAbsolute candidate)
           candidate
           (fs/path working-root candidate)))))))

(defn- environment-overrides
  [effective]
  ;; ONE declaration population for the whole effective config. Asking
  ;; `schema/schema-definition` per key read and merged all 152 schema
  ;; resources per key — 65 complete classpath populations, ~1 s, to answer a
  ;; question about one map (2026-08-07).
  (let [forms (schema/declaration-population)]
    (into {}
          (keep
           (fn [[config-key value]]
             (when-let [environment-name
                        (:seon.shell/environment
                         (schema.form/attr-form-properties
                          (schema/schema-definition forms config-key)))]
               [environment-name value])))
          effective)))

(defn- virtual-task
  [name f]
  (let [result (promise)
        builder ^Thread$Builder$OfVirtual (Thread/ofVirtual)
        thread
        (.start
         (.name builder ^String name (long 0))
         ^Runnable
         (fn []
           (deliver result
                    (try
                      {:seon.shell.jvm/value (f)}
                      (catch Throwable error
                        {:seon.shell.jvm/error error})))))]
    {:seon.shell.jvm/thread thread
     :seon.shell.jvm/result result}))

(defn- task-result
  [{thread :seon.shell.jvm/thread result :seon.shell.jvm/result}]
  (.join ^Thread thread)
  (let [terminal @result]
    (if-let [error (:seon.shell.jvm/error terminal)]
      (throw error)
      (:seon.shell.jvm/value terminal))))

(defn- capture-task
  [connection name ^InputStream input]
  (virtual-task
     name
   (fn []
     (with-open [stream input]
       (blob/stage-binary! connection stream)))))

(defn- write-array!
  [^OutputStream output ^bytes octets limit]
  (when (> (alength octets) limit)
    (throw
     (ex-info
      "Child stdin exceeds the configured byte ceiling."
      {:seon.error/kind :my.shell/stdin-limit
       :seon.error/message
       "Child stdin exceeds the configured byte ceiling."
       :seon.error/data {:seon.config.shell/stdin-max-bytes limit} :my.shell/stdin-limit true})))
  (.write output octets))

(defn- copy-blob-stdin!
  [connection ^OutputStream output content-digest limit]
  (let [digester (MessageDigest/getInstance "SHA-256")]
    (loop [offset 0]
      (when (> offset limit)
        (throw
         (ex-info
          "Child stdin exceeds the configured byte ceiling."
          {:seon.error/kind :my.shell/stdin-limit
           :seon.error/message
           "Child stdin exceeds the configured byte ceiling."
           :seon.error/data {:seon.config.shell/stdin-max-bytes limit} :my.shell/stdin-limit true})))
      (let [remaining (- limit offset)
            requested (int (min io-buffer-bytes (inc remaining)))
            octets (blob/read-chunk connection content-digest offset requested)]
        (if (nil? octets)
          (let [actual (.formatHex (HexFormat/of) (.digest digester))]
            (when (and (= empty-digest content-digest)
                       (nil? (blob/get connection content-digest)))
              (throw
               (ex-info
                "The stdin blob is unavailable."
                {:seon.error/kind :my.shell/blob-unavailable
                 :seon.error/message "The stdin blob is unavailable."
                 :seon.error/data {:seon.blob/digest content-digest} :my.shell/blob-unavailable true})))
            (when-not (= content-digest actual)
              (throw
               (ex-info
                "The stdin blob is unavailable or failed verification."
                {:seon.error/kind :my.shell/blob-unavailable
                 :seon.error/message
                 "The stdin blob is unavailable or failed verification."
                 :seon.error/data {:seon.blob/digest content-digest
                                   :seon.blob/actual-digest actual} :my.shell/blob-unavailable true}))))
          (let [read-count (alength ^bytes octets)]
            (when (zero? read-count)
              (throw
               (ex-info
                "The stdin blob reader made no progress."
                {:seon.error/kind :my.shell/blob-unavailable
                 :seon.error/message
                 "The stdin blob reader made no progress."
                 :seon.error/data {:seon.blob/digest content-digest} :my.shell/blob-unavailable true})))
            (.update digester ^bytes octets)
            (.write output ^bytes octets)
            (recur (+ offset read-count))))))))

(defn- stdin-task
  [connection ^OutputStream output stdin limit]
  (virtual-task
   "seon-shell-stdin-"
   (fn []
     (with-open [stream output]
       (cond
         (nil? stdin) nil

         (contains? stdin :my.shell/stdin-text)
         (write-array!
          stream
          (.getBytes ^String (:my.shell/stdin-text stdin)
                     StandardCharsets/UTF_8)
          limit)

         (contains? stdin :my.shell/stdin-bytes)
         (write-array!
          stream
          (byte-array (map unchecked-byte (:my.shell/stdin-bytes stdin)))
          limit)

         :else
         (copy-blob-stdin! connection stream (:seon.blob/digest stdin)
                           limit))))))

(defn- handle-identity
  [^ProcessHandle handle]
  (let [started (.startInstant (.info handle))]
    {:seon.shell.jvm/pid (.pid handle)
     :seon.shell.jvm/started
     (when (.isPresent ^Optional started) (.get ^Optional started))
     :seon.shell.jvm/handle handle}))

(defn- process-descendants
  [^ProcessHandle root]
  (with-open [stream (.descendants root)]
    (mapv handle-identity (iterator-seq (.iterator stream)))))

(defn- same-process?
  [{:seon.shell.jvm/keys [^ProcessHandle handle started]}]
  (and (.isAlive handle)
       (let [current (.startInstant (.info handle))]
         (= started
            (when (.isPresent ^Optional current)
              (.get ^Optional current))))))

(defn- force-exact!
  [identities]
  (doseq [identity (reverse (vec (distinct identities)))]
    (when (same-process? identity)
      (.destroyForcibly ^ProcessHandle (:seon.shell.jvm/handle identity)))))

(defn- terminate-tree!
  [process-record grace-ms]
  (let [^Process child (:proc process-record)
        root (.toHandle child)
        before (into [(handle-identity root)] (process-descendants root))]
    (process/destroy-tree process-record)
    (when-not (.waitFor child (long grace-ms) TimeUnit/MILLISECONDS)
      (let [current (if (.isAlive root) (process-descendants root) [])]
        (force-exact! (into before current))
        (.destroyForcibly child)
        (.get (.onExit child) (long grace-ms) TimeUnit/MILLISECONDS)))
    nil))

(defn- await-exit
  "Wait for the child, bounded by whichever limit ends first.

  Two limits govern a foreground child and only one of them used to be
  observed. `:seon.config.shell/time-limit-ms` is the shell's own bound; the
  ARM's deadline is the evaluation's, and it is the one that admitted this
  work. Waiting only on the shell's limit is why an interrupted run left
  `sleep 300` alive 29 s after its 4 s evaluation limit fired: the eval thread
  was parked in a host call, so SCI's interrupt had no interpreted entrance to
  reach, and the handler was waiting on a limit nobody had reached.

  Returns `:exited`, `:shell-limit`, or `:evaluation-limit` — a disposition,
  never a bare boolean, because the caller must say which limit ended the
  child."
  [^Process child time-limit-ms]
  (let [remaining (kernel/deadline-remaining-ms)
        evaluation-first? (and remaining (< remaining (long time-limit-ms)))
        wait-ms (if evaluation-first? (max 0 remaining) (long time-limit-ms))]
    (try
      (.get (.onExit child) wait-ms TimeUnit/MILLISECONDS)
      :exited
      (catch TimeoutException _
        (if (or evaluation-first? (kernel/deadline-reached?))
          :evaluation-limit
          :shell-limit)))))

(defn- output-descriptor
  [connection captured effective]
  (let [size (:seon.blob/size captured)
        content-digest (:seon.blob/digest captured)
        inline-limit (:seon.config.shell/inline-output-bytes effective)
        preview-limit (:seon.config.shell/preview-bytes effective)
        inline? (<= size inline-limit)
        retained-length (if inline? size (min size preview-limit))
        retained (blob/read-staged-chunk captured 0 retained-length)
        decoded (try (strict-utf8 retained)
                     (catch java.nio.charset.CharacterCodingException _ nil))
        base {:my.shell.output/bytes size
              :my.shell.output/digest content-digest
              :my.shell.output/preview-complete? inline?}]
    (if inline?
      (if (string? decoded)
        (assoc base :my.shell.output/text decoded)
        (assoc base :my.shell.output/octet-values (octet-values retained)))
      (cond-> (assoc base :my.shell.output/blob content-digest)
        (string? decoded) (assoc :my.shell.output/preview decoded)))))

(defn- finish-evidence
  [connection stdout-task stderr-task effective]
  (let [stdout (task-result stdout-task)
        stderr (task-result stderr-task)]
    {:my.shell/stdout (output-descriptor connection stdout effective)
     :my.shell/stderr (output-descriptor connection stderr effective)
     :seon.blob/staged-writes [stdout stderr]}))

(defn- execute
  [request effective cwd]
  (let [connection (:seon.db/connection effect/*request-context*)
        argv (:my.shell/argv request)
        process-record
        (process/process
         argv
         {:dir (str cwd)
          :extra-env (environment-overrides effective)
          :shutdown process/destroy-tree})
        ^Process child (:proc process-record)
        stdout-task
        (capture-task connection "seon-shell-stdout-" (:out process-record))
        stderr-task
        (capture-task connection "seon-shell-stderr-" (:err process-record))
        input-task
        (stdin-task connection (:in process-record) (:my.shell/stdin request)
                    (:seon.config.shell/stdin-max-bytes effective))]
    (try
      (let [disposition (await-exit child
                                    (:seon.config.shell/time-limit-ms
                                     effective))]
        (if (= :exited disposition)
          (let [evidence (finish-evidence connection stdout-task stderr-task
                                          effective)]
            (task-result input-task)
            (merge {:my.shell/argv argv
                    :my.shell/cwd (:my.shell/cwd request)
                    :my.shell/exit (.exitValue child)}
                   evidence))
          ;; Both limits reap the tree before this frame goes away — the
          ;; handler owns its resource, so no arm of this function can return
          ;; while its child is still alive. The `:interrupted` disposition is
          ;; what stamps `:seon.effect/interrupted-at` on the receipt, so the
          ;; process and the receipt terminate together or not at all.
          (do
            (terminate-tree! process-record
                             (:seon.config.shell/termination-grace-ms
                              effective))
            (let [evidence (finish-evidence connection stdout-task stderr-task
                                            effective)]
              (try
                (task-result input-task)
                (catch Throwable _))
              (assoc
               (flat-error
                :my.shell/time-limit
                (if (= :evaluation-limit disposition)
                  (str "The foreign process was terminated when its "
                       "evaluation reached its time limit.")
                  "The foreign process exceeded its configured time limit.")
                (merge {:my.shell/argv argv
                        :my.shell/cwd (:my.shell/cwd request)}
                       evidence))
               :seon.effect/disposition :interrupted)))))
      (catch InterruptedException interrupted
        (terminate-tree! process-record
                         (:seon.config.shell/termination-grace-ms effective))
        (.interrupt (Thread/currentThread))
        (throw interrupted)))))

(defn- run
  {:malli/schema
   [:=> [:cat :my.shell/run-request :seon.config/effective]
    [:or :my.shell/run-result :seon.error/value]]}
  [request effective]
  (let [cwd (cwd-path (:my.shell/cwd request) effective)]
    (if (:seon.error/kind cwd)
      cwd
      (try
        (execute request effective cwd)
        (catch InterruptedException interrupted
          (throw interrupted))
        (catch Throwable error
          (error-value error :my.shell/start-failed
                       "The foreground process could not be completed."
                       {:my.shell/argv (:my.shell/argv request)
                        :my.shell/cwd (:my.shell/cwd request)}))))))
