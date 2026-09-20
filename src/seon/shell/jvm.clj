(ns seon.shell.jvm
  "Protected JVM implementation of foreground shell execution."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [seon.await :as await]
            [seon.blob :as blob]
            [seon.effect :as effect]
            [seon.error.refusal :as error]
            [seon.fs.jvm]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form]
            [seon.sci.kernel :as kernel])
  (:import [java.io InputStream OutputStream]
           [java.lang ProcessHandle Thread$Builder$OfVirtual]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat Optional]
           [java.util.concurrent TimeUnit TimeoutException]))

(set! *warn-on-reflection* true)

(def ^:private io-buffer-bytes 65536)
(def ^:private empty-digest
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

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
      ;; PRD 1.3 debt: seon.fs.jvm/stat still declares :seon.error/value.
      (and (:seon.error/at stat) (:seon.error/layer stat)
           (:seon.error/operation stat))
      (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/cwd-path
        :seon.error/message "The child working directory is outside filesystem policy."
        :seon.error/offending cwd
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/cwd-path
        :seon.error/diagnostic-member :my.shell/cwd
        :seon.error/diagnostic-expected :my.fs/directory?
        :seon.error/diagnostic-offending cwd
        :seon.error/diagnostic-cause stat
        :seon.error/diagnostic-evidence stat
        :my.shell/refused-cwd cwd})

      (not (:my.fs/directory? stat))
      (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/cwd-path
        :seon.error/message "The child working directory must be a no-follow directory."
        :seon.error/offending cwd
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/cwd-path
        :seon.error/diagnostic-member :my.shell/cwd
        :seon.error/diagnostic-expected :my.fs/directory?
        :seon.error/diagnostic-offending cwd
        :seon.error/diagnostic-cause :my.shell/not-directory
        :seon.error/diagnostic-evidence stat
        :my.shell/refused-cwd cwd})

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
  [thread-name f]
  (let [result (promise)
        builder ^Thread$Builder$OfVirtual (Thread/ofVirtual)
        thread
        (.start
         (.name builder ^String thread-name (long 0))
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
  {:malli/schema [:=> [:cat :map :map :qualified-keyword]
                  [:or :nil :seon.blob/staged-write :seon.await/timeout-error]]}
  [{thread :seon.shell.jvm/thread result :seon.shell.jvm/result}
   effective member]
  (let [terminal
        (await/await!
         {:seon.await/bound
          {:seon.await/config-attribute :seon.config.eval/time-limit-ms
           :seon.await/config-value
           (:seon.config.eval/time-limit-ms effective)}
          :seon.await/diagnostic
          {:seon.error/diagnostic-layer :shell
           :seon.error/diagnostic-operation ::capture-completion
           :seon.error/diagnostic-member member
           :seon.error/diagnostic-expected ::task-result
           :seon.error/diagnostic-offending ::pending
           :seon.error/diagnostic-evidence
           {:seon.shell.jvm/thread-name (.getName ^Thread thread)
            :seon.shell.jvm/thread-id (.threadId ^Thread thread)}}
          :seon.await/blocking-deref result})]
    (if (:seon.await/elapsed-ms terminal)
      (do
        (.interrupt ^Thread thread)
        terminal)
      (if-let [error (:seon.shell.jvm/error terminal)]
        (throw error)
        (:seon.shell.jvm/value terminal)))))

(defn- capture-task
  [connection thread-name ^InputStream input]
  (virtual-task
     thread-name
   (fn []
     (with-open [stream input]
       (blob/stage-binary! connection stream)))))

(defn- write-array!
  [^OutputStream output ^bytes octets limit]
  (when (> (alength octets) limit)
    (throw
     (ex-info
      "Child stdin exceeds the configured byte ceiling."
      (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/write-array!
        :seon.error/message "Child stdin exceeds the configured byte ceiling; reduce the input or raise that bound."
        :seon.error/offending (alength octets)
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/write-array!
        :seon.error/diagnostic-member :my.shell/stdin
        :seon.error/diagnostic-expected :seon.config.shell/stdin-max-bytes
        :seon.error/diagnostic-offending (alength octets)
        :seon.error/diagnostic-cause :my.shell/stdin-limit
        :seon.error/diagnostic-evidence (alength octets)
        :my.shell/stdin-byte-limit limit
        :my.shell/observed-stdin-bytes (alength octets)}))))
  (.write output octets))

(defn- copy-blob-stdin!
  [connection ^OutputStream output content-digest limit]
  (let [digester (MessageDigest/getInstance "SHA-256")]
    (loop [offset 0]
      (when (> offset limit)
        (throw
         (ex-info
          "Child stdin exceeds the configured byte ceiling."
          (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/copy-blob-stdin!
        :seon.error/message "Child stdin exceeds the configured byte ceiling; reduce the input or raise that bound."
        :seon.error/offending offset
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/copy-blob-stdin!
        :seon.error/diagnostic-member :my.shell/stdin
        :seon.error/diagnostic-expected :seon.config.shell/stdin-max-bytes
        :seon.error/diagnostic-offending offset
        :seon.error/diagnostic-cause :my.shell/stdin-limit
        :seon.error/diagnostic-evidence offset
        :my.shell/stdin-byte-limit limit
        :my.shell/observed-stdin-bytes offset}))))
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
                (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/copy-blob-stdin!
        :seon.error/message "The stdin blob is unavailable."
        :seon.error/offending content-digest
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/copy-blob-stdin!
        :seon.error/diagnostic-member :seon.blob/digest
        :seon.error/diagnostic-expected :seon.blob/content
        :seon.error/diagnostic-offending content-digest
        :seon.error/diagnostic-cause :my.shell/missing-blob
        :seon.error/diagnostic-evidence content-digest
        :my.shell/stdin-blob-digest content-digest
        :my.shell/stdin-blob-offset offset}))))
            (when-not (= content-digest actual)
              (throw
               (ex-info
                "The stdin blob is unavailable or failed verification."
                (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/copy-blob-stdin!
        :seon.error/message "The stdin blob failed digest verification."
        :seon.error/offending content-digest
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/copy-blob-stdin!
        :seon.error/diagnostic-member :seon.blob/digest
        :seon.error/diagnostic-expected :seon.blob/content
        :seon.error/diagnostic-offending content-digest
        :seon.error/diagnostic-cause :my.shell/digest-mismatch
        :seon.error/diagnostic-evidence content-digest
        :my.shell/stdin-blob-digest content-digest
        :my.shell/stdin-blob-offset offset})))))
          (let [read-count (alength ^bytes octets)]
            (when (zero? read-count)
              (throw
               (ex-info
                "The stdin blob reader made no progress."
                (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/copy-blob-stdin!
        :seon.error/message "The stdin blob reader made no progress."
        :seon.error/offending content-digest
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/copy-blob-stdin!
        :seon.error/diagnostic-member :seon.blob/digest
        :seon.error/diagnostic-expected :seon.blob/content
        :seon.error/diagnostic-offending content-digest
        :seon.error/diagnostic-cause :my.shell/stalled-input
        :seon.error/diagnostic-evidence content-digest
        :my.shell/stdin-blob-digest content-digest
        :my.shell/stdin-blob-offset offset}))))
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
  (doseq [process-identity (reverse (vec (distinct identities)))]
    (when (same-process? process-identity)
      (.destroyForcibly ^ProcessHandle (:seon.shell.jvm/handle process-identity)))))

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
  [_connection captured effective]
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
  {:malli/schema [:=> [:cat :seon.db/connection :map :map :map]
                  [:or [:map [:my.shell/stdout :my.shell.output/value]
                             [:my.shell/stderr :my.shell.output/value]
                             [:seon.blob/staged-writes [:vector :seon.blob/staged-write]]]
                   :seon.await/timeout-error]]}
  [connection stdout-task stderr-task effective]
  (let [stdout (task-result stdout-task effective ::stdout)]
    (if (:seon.await/elapsed-ms stdout)
      stdout
      (let [stderr (task-result stderr-task effective ::stderr)]
        (if (:seon.await/elapsed-ms stderr)
          stderr
          {:my.shell/stdout (output-descriptor connection stdout effective)
           :my.shell/stderr (output-descriptor connection stderr effective)
           :seon.blob/staged-writes [stdout stderr]})))))

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
                                          effective)
                input (task-result input-task effective ::stdin)]
            (cond
              (:seon.await/elapsed-ms evidence) evidence
              (:seon.await/elapsed-ms input) input
              :else
              (merge {:my.shell/argv argv
                      :my.shell/cwd (:my.shell/cwd request)
                      :my.shell/exit (.exitValue child)}
                     evidence)))
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
                                            effective)
                  input
                  (try
                    (task-result input-task effective ::stdin)
                    (catch Throwable _ nil))]
              (cond
                (:seon.await/elapsed-ms evidence) evidence
                (:seon.await/elapsed-ms input) input
                :else
                (assoc
                 (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/execute
        :seon.error/message (if (= :evaluation-limit disposition)
          "The process was terminated at the evaluation deadline."
          "The process was terminated at the configured shell deadline.")
        :seon.error/offending argv
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/execute
        :seon.error/diagnostic-member :my.shell/argv
        :seon.error/diagnostic-expected "process exit before its deadline"
        :seon.error/diagnostic-offending argv
        :seon.error/diagnostic-cause :my.shell/time-limit
        :seon.error/diagnostic-evidence evidence
        :my.shell/terminated-pid (.pid child)
        :my.shell/limiting-config-key
        (if (= :evaluation-limit disposition)
          :seon.config.eval/time-limit-ms :seon.config.shell/time-limit-ms)
        :seon.error/data (merge {:my.shell/argv argv :my.shell/cwd (:my.shell/cwd request)} evidence)})
                 :seon.effect/disposition :interrupted))))))
      (catch InterruptedException interrupted
        (terminate-tree! process-record
                         (:seon.config.shell/termination-grace-ms effective))
        (.interrupt (Thread/currentThread))
        (throw interrupted)))))

(defn- ^{:clj-kondo/ignore [:unused-private-var]} run
  {:malli/schema
   [:=> [:cat :my.shell/run-request :seon.config/effective]
    [:or :my.shell/run-result :my.shell/cwd-refused-error
     :my.shell/stdin-limit-error :my.shell/blob-unavailable-error
     :my.shell/time-limit-error :my.shell/start-failed-error
     :seon.await/timeout-error]]}
  [request effective]
  (let [cwd (cwd-path (:my.shell/cwd request) effective)]
    (if (:my.shell/refused-cwd cwd)
      cwd
      (try
        (execute request effective cwd)
        (catch InterruptedException interrupted
          (throw interrupted))
        (catch Throwable error
          (let [observed (ex-data error)]
            (if (or (:my.shell/stdin-byte-limit observed)
                    (:my.shell/stdin-blob-digest observed))
              observed
              (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/run
        :seon.error/message "The foreground process could not be completed; inspect the captured cause."
        :seon.error/offending request
        :seon.error/diagnostic-layer :my.shell/execution
        :seon.error/diagnostic-operation 'seon.shell.jvm/run
        :seon.error/diagnostic-member :my.shell/argv
        :seon.error/diagnostic-expected :my.shell/run-result
        :seon.error/diagnostic-offending request
        :seon.error/diagnostic-cause error
        :seon.error/diagnostic-evidence (ex-data error)
        :my.shell/failed-argv (:my.shell/argv request)}))))))))
