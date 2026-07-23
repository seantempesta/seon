(ns seon.agent.shell.leaf
  "Implement the JVM subprocess leaf for the portable shell capability."
  (:require
   [clojure.java.io :as io]
   [seon.agent.fs.leaf :as fs]
   [seon.agent.shell.core :as core]
   [seon.ai.tokens :as tokens])
  (:import
   (java.io ByteArrayOutputStream InputStream)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent TimeUnit)))

(def max-output-bytes 2000000)
(def max-exited-jobs 32)
(defonce ^:private !jobs (atom {}))
(def ^:dynamic *granted?* nil)

(defn- granted?
  []
  (if (some? *granted?*)
    *granted?*
    (let [value (System/getenv "SEON_SHELL")]
      (boolean (and value (not= "" value) (not= "0" value))))))

(defn grants
  "Return whether the JVM host grants subprocess execution."
  []
  {:seon.agent.shell/granted? (granted?)})

(defn- cwd-error
  [cwd]
  (when cwd
    (let [result (fs/stat {:seon.agent.fs/path cwd})]
      (cond
        (not (:seon.agent.fs/ok? result))
        (core/fail
         (str ":seon.agent.shell/cwd " (pr-str cwd)
              " is not usable — seon.agent.fs said: "
              (:seon.agent.fs/error result)))

        (not (:seon.agent.fs/dir? result))
        (core/fail
         (str ":seon.agent.shell/cwd " (pr-str cwd)
              " is not a directory."))))))

(defn- process-builder
  [{:seon.subprocess/keys [cmd cwd]}]
  (cond-> (ProcessBuilder. ^java.util.List cmd)
    cwd (.directory (io/file cwd))))

(defn- capture-stream
  [^InputStream stream]
  (let [buffer (byte-array 8192)
        output (ByteArrayOutputStream.)
        truncated? (volatile! false)]
    (loop []
      (let [read (.read stream buffer)]
        (when (pos? read)
          (let [remaining (- max-output-bytes (.size output))
                accepted (min read (max 0 remaining))]
            (when (pos? accepted) (.write output buffer 0 accepted))
            (when (< accepted read) (vreset! truncated? true)))
          (recur))))
    {:content (.toString output StandardCharsets/UTF_8)
     :truncated? @truncated?}))

(defn- capture-task
  [stream]
  (let [result (promise)
        thread (-> (Thread/ofVirtual)
                   (.name "seon-shell-capture-" 0)
                   (.start ^Runnable
                           #(deliver result
                                     (try
                                       (capture-stream stream)
                                       (catch Throwable throwable
                                         {:content ""
                                          :truncated? false
                                          :error throwable})))))]
    {:thread thread :result result}))

(defn- finish-capture
  [{:keys [^Thread thread result]}]
  (.join thread)
  @result)

(defn- write-stdin!
  [^Process process stdin]
  (with-open [writer (.getOutputStream process)]
    (when (some? stdin)
      (.write writer (.getBytes ^String stdin StandardCharsets/UTF_8))
      (.flush writer))))

(defn- execute
  [{:seon.subprocess/keys [stdin timeout-ms] :as request}]
  (let [process (.start (process-builder request))
        out-task (capture-task (.getInputStream process))
        err-task (capture-task (.getErrorStream process))]
    (write-stdin! process stdin)
    (try
      (let [finished? (.waitFor process (long timeout-ms)
                                TimeUnit/MILLISECONDS)
            timed-out? (not finished?)]
        (when timed-out?
          (.destroy process)
          (when-not (.waitFor process 250 TimeUnit/MILLISECONDS)
            (.destroyForcibly process)
            (.waitFor process)))
        (let [out (finish-capture out-task)
              err (finish-capture err-task)]
          (core/ran-envelope
           (if (.isAlive process) core/killed-exit (.exitValue process))
           (:content out)
           (:content err)
           timed-out?
           (or (:truncated? out) (:truncated? err)))))
      (catch InterruptedException interrupted
        (.destroyForcibly process)
        (.interrupt (Thread/currentThread))
        (core/fail
         "shell invocation was interrupted by the invocation watchdog"
         {:seon.agent.shell/timed-out? true})))))

(defn run
  "Run one argv request synchronously on the invocation thread."
  ([request] (run request nil))
  ([request configuration]
   (let [subprocess-request
         (core/run-request request configuration max-output-bytes)]
     (cond
       (not (granted?)) (core/ungranted)
       (false? (:seon.agent.shell/ok? subprocess-request)) subprocess-request
       (cwd-error (:seon.subprocess/cwd subprocess-request))
       (cwd-error (:seon.subprocess/cwd subprocess-request))
       :else
       (try
         (execute subprocess-request)
         (catch Throwable throwable
           (core/fail (str "could not run subprocess: "
                           (or (ex-message throwable) throwable)))))))))

(defn py-run
  "Run Python source through the frozen child specialization."
  ([request] (py-run request nil))
  ([request configuration]
   (let [specialized (core/py-request request)]
     (if (false? (:seon.agent.shell/ok? specialized))
       specialized
       (run specialized configuration)))))

(defn- runtime-ms
  [job]
  (max 0
       (- (or (:seon.agent.shell/ended-ms job) (System/currentTimeMillis))
          (:seon.agent.shell/started-ms job))))

(defn- prune-finished
  [jobs]
  (let [finished (->> jobs
                      vals
                      (remove #(= :running (:seon.agent.shell/state %)))
                      (sort-by :seon.agent.shell/ended-ms))
        excess (- (count finished) max-exited-jobs)]
    (if (pos? excess)
      (apply dissoc jobs (map :seon.agent.shell/job-id (take excess finished)))
      jobs)))

(defn run-bg!
  "Start one process and retain its bounded output in the JVM leaf."
  [{:seon.agent.shell/keys [cmd args cwd stdin] :as request}]
  (cond
    (not (granted?)) (core/ungranted)
    (cwd-error cwd) (cwd-error cwd)
    :else
    (try
      (let [job-id (str "job-" (subs (str (random-uuid)) 0 8))
            process (.start (process-builder request))
            out-task (capture-task (.getInputStream process))
            err-task (capture-task (.getErrorStream process))
            started-ms (System/currentTimeMillis)]
        (write-stdin! process stdin)
        (swap! !jobs assoc job-id
               {:seon.agent.shell/job-id job-id
                :seon.agent.shell/cmd cmd
                :seon.agent.shell/args (vec (or args []))
                :seon.agent.shell/cwd cwd
                :seon.agent.shell/process process
                :seon.agent.shell/out-task out-task
                :seon.agent.shell/err-task err-task
                :seon.agent.shell/started-ms started-ms
                :seon.agent.shell/state :running})
        (.start
         (Thread/ofVirtual)
         ^Runnable
         (fn []
           (let [exit (.waitFor process)
                 out (finish-capture out-task)
                 err (finish-capture err-task)]
             (swap! !jobs
                    (fn [jobs]
                      (-> jobs
                          (update job-id
                                  (fn [job]
                                    (when job
                                      (assoc job
                                             :seon.agent.shell/state
                                             (if (= :stopped
                                                    (:seon.agent.shell/state job))
                                               :stopped :exited)
                                             :seon.agent.shell/exit exit
                                             :seon.agent.shell/out (:content out)
                                             :seon.agent.shell/err (:content err)
                                             :seon.agent.shell/out-truncated?
                                             (:truncated? out)
                                             :seon.agent.shell/err-truncated?
                                             (:truncated? err)
                                             :seon.agent.shell/ended-ms
                                             (System/currentTimeMillis)))))
                          prune-finished))))))
        {:seon.agent.shell/ok? true
         :seon.agent.shell/job-id job-id
         :seon.agent.shell/state :running
         :seon.agent.shell/cmd cmd})
      (catch Throwable throwable
        (core/fail (str "could not start subprocess: "
                        (or (ex-message throwable) throwable)))))))

(defn- current-job
  [job-id]
  (get @!jobs job-id))

(defn- unknown-job
  [job-id]
  (core/fail
   (str "no background job " (pr-str job-id)
        " — it never started, was pruned, or the host restarted.")))

(defn- job-summary
  [job]
  (cond-> {:seon.agent.shell/job-id (:seon.agent.shell/job-id job)
           :seon.agent.shell/state (:seon.agent.shell/state job)
           :seon.agent.shell/cmd (:seon.agent.shell/cmd job)
           :seon.agent.shell/runtime-ms (runtime-ms job)
           :seon.agent.shell/out-tokens
           (tokens/estimate (or (:seon.agent.shell/out job) ""))
           :seon.agent.shell/err-tokens
           (tokens/estimate (or (:seon.agent.shell/err job) ""))}
    (some? (:seon.agent.shell/exit job))
    (assoc :seon.agent.shell/exit (:seon.agent.shell/exit job))))

(defn list-jobs
  "List process-local JVM background jobs newest first."
  []
  {:seon.agent.shell/ok? true
   :seon.agent.shell/jobs
   (->> @!jobs vals
        (sort-by (comp - :seon.agent.shell/started-ms))
        (mapv job-summary))})

(defn job-status
  "Return one JVM background job's current envelope."
  [{:seon.agent.shell/keys [job-id]}]
  (if-let [job (current-job job-id)]
    (assoc (job-summary job)
           :seon.agent.shell/ok? true
           :seon.agent.shell/job-id job-id)
    (unknown-job job-id)))

(defn job-output
  "Page captured JVM background output from a stable character cursor."
  [{:seon.agent.shell/keys [job-id stream since]}]
  (if-let [job (current-job job-id)]
    (let [stream (or stream :out)
          content (str (get job (if (= :err stream)
                                  :seon.agent.shell/err
                                  :seon.agent.shell/out)
                            ""))
          truncated? (boolean
                      (get job (if (= :err stream)
                                 :seon.agent.shell/err-truncated?
                                 :seon.agent.shell/out-truncated?)))]
      (cond-> (merge {:seon.agent.shell/ok? true
                      :seon.agent.shell/job-id job-id
                      :seon.agent.shell/state (:seon.agent.shell/state job)
                      :seon.agent.shell/stream stream
                      :seon.agent.shell/tokens (tokens/estimate content)
                      :seon.agent.shell/truncated? truncated?
                      :seon.agent.shell/runtime-ms (runtime-ms job)}
                     (core/slice-since content since))
        (some? (:seon.agent.shell/exit job))
        (assoc :seon.agent.shell/exit (:seon.agent.shell/exit job))))
    (unknown-job job-id)))

(defn job-stop!
  "Stop one JVM background process without adding a supervisor."
  [{:seon.agent.shell/keys [job-id]}]
  (if-let [job (current-job job-id)]
    (do
      (when (= :running (:seon.agent.shell/state job))
        (swap! !jobs assoc-in [job-id :seon.agent.shell/state] :stopped)
        (.destroy ^Process (:seon.agent.shell/process job)))
      {:seon.agent.shell/ok? true
       :seon.agent.shell/job-id job-id
       :seon.agent.shell/state
       (:seon.agent.shell/state (current-job job-id))})
    (unknown-job job-id)))

(def public-functions
  {'grants grants
   'run run
   'py-run py-run
   'run-bg! run-bg!
   'list-jobs list-jobs
   'job-status job-status
   'job-output job-output
   'job-stop! job-stop!})
