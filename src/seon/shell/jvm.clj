(ns seon.shell.jvm
  "Protected JVM implementation of foreground shell execution."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [seon.await :as await]
            [seon.blob :as blob]
            [seon.cluster.process :as cluster.process]
            [seon.effect :as effect]
            [seon.error.refusal :as error]
            [seon.fs.jvm]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.env :as env]
            [seon.sci.kernel :as kernel])
  (:import [java.io InputStream OutputStream]
           [java.lang Thread$Builder$OfVirtual]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat]
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
      (seon.error.refusal/diagnostic (java.util.Date.) :my.shell/execution 'seon.shell.jvm/cwd-path
       {:seon.error/message "The child working directory is outside filesystem policy."
        :seon.error/offending cwd
        :my.shell/refused-cwd cwd
        :seon.error/expected :my.fs/directory?
        :my.shell/cwd-observation stat})

      (not (:my.fs/directory? stat))
      (seon.error.refusal/diagnostic (java.util.Date.) :my.shell/execution 'seon.shell.jvm/cwd-path
       {:seon.error/message "The child working directory must be a no-follow directory."
        :seon.error/offending cwd
        :my.shell/refused-cwd cwd
        :seon.error/member :my.shell/cwd
        :seon.error/expected :my.fs/directory?
        :my.shell/cwd-observation stat})

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
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.config/effective]
                  [:map-of :string :seon.schema/value]]}
  [projection effective]
  (let [registry (:seon.schema.projection/registry projection)]
    (into {}
          (keep
           (fn [[config-key value]]
             (when-let [environment-name
                        (:seon.shell/environment
                         (some-> (mr/schema registry config-key) m/properties))]
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
          {:seon.error/layer :shell
           :seon.error/operation ::capture-completion
           :seon.error/expected ::task-result
           :seon.error/offending ::pending
           :seon.await/subject {:seon.shell.jvm/thread-name (.getName ^Thread thread)
            :seon.shell.jvm/thread-id (.threadId ^Thread thread)}
             :seon.error/member member}
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
      (seon.error.refusal/diagnostic (java.util.Date.) :my.shell/execution 'seon.shell.jvm/write-array!
       {:seon.error/message "Child stdin exceeds the configured byte ceiling; reduce the input or raise that bound."
        :seon.error/offending (alength octets)
        :my.shell/stdin-byte-limit limit
        :my.shell/observed-stdin-bytes (alength octets)
        :seon.error/member :my.shell/stdin
        :seon.error/expected :seon.config.shell/stdin-max-bytes}))))
  (.write output octets))

(defn- copy-blob-stdin!
  [connection ^OutputStream output content-digest limit]
  (let [digester (MessageDigest/getInstance "SHA-256")]
    (loop [offset 0]
      (when (> offset limit)
        (throw
         (ex-info
          "Child stdin exceeds the configured byte ceiling."
          (seon.error.refusal/diagnostic (java.util.Date.) :my.shell/execution 'seon.shell.jvm/copy-blob-stdin!
           {:seon.error/message "Child stdin exceeds the configured byte ceiling; reduce the input or raise that bound."
        :seon.error/offending offset
        :my.shell/stdin-byte-limit limit
        :my.shell/observed-stdin-bytes offset
        :seon.error/member :my.shell/stdin
        :seon.error/expected :seon.config.shell/stdin-max-bytes}))))
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
                (seon.error.refusal/diagnostic (java.util.Date.) :my.shell/execution 'seon.shell.jvm/copy-blob-stdin!
                 {:seon.error/message "The stdin blob is unavailable."
        :seon.error/offending content-digest
        :my.shell/stdin-blob-digest content-digest
        :my.shell/stdin-blob-offset offset
        :seon.error/member :seon.blob/digest
        :seon.error/expected :seon.blob/content}))))
            (when-not (= content-digest actual)
              (throw
               (ex-info
                "The stdin blob is unavailable or failed verification."
                (seon.error.refusal/diagnostic (java.util.Date.) :my.shell/execution 'seon.shell.jvm/copy-blob-stdin!
                 {:seon.error/message "The stdin blob failed digest verification."
        :seon.error/offending content-digest
        :my.shell/stdin-blob-digest content-digest
        :my.shell/stdin-blob-offset offset
        :seon.error/member :seon.blob/digest
        :seon.error/expected :seon.blob/content})))))
          (let [read-count (alength ^bytes octets)]
            (when (zero? read-count)
              (throw
               (ex-info
                "The stdin blob reader made no progress."
                (seon.error.refusal/diagnostic (java.util.Date.) :my.shell/execution 'seon.shell.jvm/copy-blob-stdin!
                 {:seon.error/message "The stdin blob reader made no progress."
        :seon.error/offending content-digest
        :my.shell/stdin-blob-digest content-digest
        :my.shell/stdin-blob-offset offset
        :seon.error/member :seon.blob/digest
        :seon.error/expected :seon.blob/content}))))
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

;; A descendant is only visible while its parent lives, so the wait samples
;; the tree; one sample costs ~0.4 ms (2026-09-24), under 1% of a core at 50 ms.
(def ^:private descendant-sample-ms 50)

(defn- await-exit
  "Wait for the child, bounded by whichever limit ends first, sampling its
  tree into `captured` so descendants orphaned by the root's exit stay known.

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
  [^Process child time-limit-ms captured]
  (let [remaining (kernel/deadline-remaining-ms)
        evaluation-first? (and remaining (< remaining (long time-limit-ms)))
        wait-ms (if evaluation-first? (max 0 remaining) (long time-limit-ms))
        deadline-ns (+ (System/nanoTime) (* 1000000 (long wait-ms)))]
    (loop []
      (vswap! captured #(into [] (distinct)
                              (concat % (cluster.process/subprocess-tree-identities
                                         (.toHandle child)))))
      (let [left-ms (quot (- deadline-ns (System/nanoTime)) 1000000)
            exited? (try
                      (.get (.onExit child) (max 0 (min left-ms descendant-sample-ms))
                            TimeUnit/MILLISECONDS)
                      true
                      (catch TimeoutException _ false))]
        (cond
          exited? :exited
          (> left-ms descendant-sample-ms) (recur)
          (or evaluation-first? (kernel/deadline-reached?)) :evaluation-limit
          :else :shell-limit)))))

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
  [projection request effective cwd]
  (let [connection (:seon.db/connection effect/*request-context*)
        argv (:my.shell/argv request)
        process-record
        (process/process
         argv
         {:dir (str cwd)
          :extra-env (environment-overrides projection effective)
          :shutdown process/destroy-tree})
        ^Process child (:proc process-record)
        captured (volatile! [])
        grace-ms (:seon.config.shell/termination-grace-ms effective)
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
                                     effective)
                                    captured)]
        (if (= :exited disposition)
          (let [_ (cluster.process/terminate-subprocess! process-record @captured grace-ms grace-ms)
                evidence (finish-evidence connection stdout-task stderr-task
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
            (cluster.process/terminate-subprocess! process-record @captured grace-ms grace-ms)
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
                 (seon.error.refusal/diagnostic (java.util.Date.) :my.shell/execution 'seon.shell.jvm/execute
                  {:seon.error/message (if (= :evaluation-limit disposition)
          "The process was terminated at the evaluation deadline."
          "The process was terminated at the configured shell deadline.")
        :seon.error/offending argv
        :my.shell/terminated-pid (.pid child)
        :my.shell/limiting-config-key (if (= :evaluation-limit disposition)
          :seon.config.eval/time-limit-ms :seon.config.shell/time-limit-ms)
        :seon.error/member :my.shell/argv
        :seon.error/expected "process exit before its deadline"
        :seon.error/data (merge {:my.shell/argv argv :my.shell/cwd (:my.shell/cwd request)} evidence)})
                 :seon.effect/disposition :interrupted))))))
      (catch InterruptedException interrupted
        (cluster.process/terminate-subprocess! process-record @captured grace-ms grace-ms)
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
        (execute (:seon.schema/projection (env/of effect/*request-context*))
                 request effective cwd)
        (catch InterruptedException interrupted
          (throw interrupted))
        (catch Throwable error
          (let [observed (ex-data error)]
            (if (or (:my.shell/stdin-byte-limit observed)
                    (:my.shell/stdin-blob-digest observed))
              observed
              (error/diagnostic
       (cond-> {:seon.error/at (java.util.Date.)
        :seon.error/layer :my.shell/execution
        :seon.error/operation 'seon.shell.jvm/run
        :seon.error/message "The foreground process could not be completed; inspect the captured cause."
        :seon.error/offending request
        :my.shell/failed-argv (:my.shell/argv request)
        :seon.error/throwable error
        :seon.error/member :my.shell/argv
        :seon.error/expected :my.shell/run-result}
         (ex-data error) (assoc :my.shell/process-exception-data (ex-data error)))))))))))
