(ns seon.dev.process
  "Process graph, identity, readiness, and drain mechanics for Seon."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.dev.state :as state])
  (:import [java.io RandomAccessFile]
           [java.net HttpURLConnection StandardProtocolFamily URL
            UnixDomainSocketAddress]
           [java.nio.channels SocketChannel]
           [java.time Instant]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def watcher-id :seon.dev.process/watcher)
(def writer-id :seon.dev.process/writer)
(def pod-id :seon.dev.process/pod)
(def target-processes [watcher-id writer-id pod-id])

(def process-spec-schema
  [:map
   [:seon.dev.process/id qualified-keyword?]
   [:seon.dev.process/argv [:vector {:min 1} :string]]
   [:seon.dev.process/environment [:map-of :string :string]]
   [:seon.dev.process/dependencies [:vector qualified-keyword?]]
   [:seon.dev.process/readiness qualified-keyword?]
   [:seon.dev.process/ready-timeout-ms [:int {:min 1}]]
   [:seon.dev.process/artifact-digest :string]])

(def process-record-schema
  [:map
   [:seon.dev.process/id qualified-keyword?]
   [:seon.dev.process/pid pos-int?]
   [:seon.dev.process/start-instant :string]
   [:seon.dev.process/process-group pos-int?]
   [:seon.dev.process/argv [:vector {:min 1} :string]]
   [:seon.dev.process/environment-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.process/artifact-digest :string]
   [:seon.dev.process/target qualified-keyword?]
   [:seon.dev.process/started-at :string]
   [:seon.dev.process/log :string]])

(defn- validate! [schema value message explanation-key]
  (when-not (m/validate schema value)
    (throw (ex-info message
                    {explanation-key
                     (mapv #(select-keys % [:path :in :type])
                           (:errors (m/explain schema value)))})))
  value)

(defn- id-name [id] (name id))

(defn- sha256-text [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (str value) StandardCharsets/UTF_8))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(def ^:private managed-environment-prefixes
  ["SEON_" "GOOGLE_" "GEMINI_" "RUNPOD_" "ANTHROPIC_" "OPENAI_"
   "DEEPSEEK_"])

(def ^:private managed-environment-keys
  #{"HOME" "PATH" "JAVA_HOME" "JAVA_CMD" "NODE_PATH" "TMPDIR"})

(def ^:private operation-environment-keys
  #{"SEON_CONFIG"})

(defn- managed-environment [environment]
  (into (sorted-map)
        (filter (fn [[env-key _]]
                  (and (not (contains? operation-environment-keys env-key))
                       (or (contains? managed-environment-keys env-key)
                           (some #(str/starts-with? env-key %)
                                 managed-environment-prefixes)))))
        environment))

(defn- environment-digest [environment]
  (sha256-text (managed-environment environment)))

(defn- state-file [config id]
  (str (fs/path (:seon.dev.config/process-dir config)
                "processes" (str (id-name id) ".edn"))))

(defn read-process
  "Read one atomically published managed-process record."
  [config id]
  (when-let [record (state/read-edn (state-file config id))]
    (validate! process-record-schema record
               "A managed-process state record is invalid."
               :seon.dev.process/explanation)))

(defn- write-process! [config id value]
  (state/write-edn!
    (state-file config id)
    (validate! process-record-schema value
               "Refusing to publish an invalid managed-process record."
               :seon.dev.process/explanation)))

(defn- clear-process! [config id]
  (fs/delete-if-exists (state-file config id)))

(defn- log-directory [config id]
  (fs/path (:seon.dev.config/log-dir config) (id-name id)))

(defn- log-file [config id instance]
  (let [directory (log-directory config id)
        path (fs/path directory (str instance ".log"))]
    (fs/create-dirs directory)
    (spit (str path) "")
    (doseq [stale (->> (fs/list-dir directory "*.log")
                       (sort-by #(fs/last-modified-time %))
                       reverse
                       (drop 10))]
      (fs/delete-if-exists stale))
    path))

(defn current-log
  "Return the current lifetime log path for a managed process."
  [config id]
  (:seon.dev.process/log (read-process config id)))

(defn- extra-cljs-watch-args [config]
  (let [environment (:seon.dev.config/environment config)
        source (get environment "SEON_EXTRA_SRC")
        preload (get environment "SEON_EXTRA_PRELOAD")]
    (cond-> []
      (not (str/blank? source))
      (into ["-Sdeps" (pr-str {:deps {'seon.extra/src {:local/root source}}})])

      true
      (into ["-M:cljs" "watch" "client"])

      (and (not (str/blank? source)) (not (str/blank? preload)))
      (into ["--config-merge"
             (pr-str {:devtools {:preloads [(symbol preload)]}})]))))

(defn specs
  "Derive the complete development process graph from one manifest."
  [config manifest]
  (let [environment (:seon.dev.config/environment config)
        java (get environment "JAVA_CMD" "java")
        spec-map
        {watcher-id
     {:seon.dev.process/id watcher-id
      :seon.dev.process/argv (into ["clj"] (extra-cljs-watch-args config))
      :seon.dev.process/environment environment
      :seon.dev.process/dependencies []
      :seon.dev.process/readiness :seon.dev.process.readiness/watcher
      :seon.dev.process/ready-timeout-ms 300000
      :seon.dev.process/artifact-digest
      (:seon.dev.artifact/client-digest manifest)}

     writer-id
     {:seon.dev.process/id writer-id
      :seon.dev.process/argv
      [java "--add-modules" "jdk.incubator.vector"
       "--enable-native-access=ALL-UNNAMED" "-XX:+UseG1GC" "-Xmx2g"
       "-jar" (:seon.dev.config/writer-output config)
       "--backend" "file"
       "--db-name" (:seon.dev.config/cluster-name config)
       "--path" (str (fs/path (:seon.dev.config/cluster-dir config) "db"))
       "--req-sock" (:seon.dev.config/request-socket config)
       "--pub-sock" (:seon.dev.config/publish-socket config)
       "--repl-port" (str (:seon.dev.config/writer-repl-port config))
       "--repl-port-file" (:seon.dev.config/writer-repl-port-file config)]
      :seon.dev.process/environment environment
      :seon.dev.process/dependencies []
      :seon.dev.process/readiness :seon.dev.process.readiness/writer
      :seon.dev.process/ready-timeout-ms 180000
      :seon.dev.process/artifact-digest
      (:seon.dev.artifact/writer-digest manifest)}

     pod-id
     {:seon.dev.process/id pod-id
      :seon.dev.process/argv ["node" (:seon.dev.config/client-output config)]
      :seon.dev.process/environment environment
      :seon.dev.process/dependencies [watcher-id writer-id]
      :seon.dev.process/readiness :seon.dev.process.readiness/pod
      :seon.dev.process/ready-timeout-ms 120000
      :seon.dev.process/artifact-digest
         (:seon.dev.artifact/application-digest manifest)}}]
    (doseq [spec (vals spec-map)]
      (validate! process-spec-schema spec
                 "The derived process specification is invalid."
                 :seon.dev.process/explanation))
    spec-map))

(defn start-order
  "Topologically order process specifications by declared dependencies."
  [spec-map]
  (loop [remaining (set (keys spec-map))
         completed #{}
         ordered []]
    (if (empty? remaining)
      ordered
      (let [ready (->> remaining
                       (filter #(every? completed
                                        (:seon.dev.process/dependencies
                                          (get spec-map %))))
                       (sort-by name)
                       vec)]
        (when (empty? ready)
          (throw (ex-info "The Seon process dependency graph contains a cycle."
                          {:seon.dev.process/remaining remaining})))
        (recur (reduce disj remaining ready)
               (into completed ready)
               (into ordered ready))))))

(defn- process-status [record]
  (cond
    (nil? record) :seon.dev.process.status/absent
    (state/process-identity-alive? record) :seon.dev.process.status/alive
    (some? (state/process-start-instant (:seon.dev.process/pid record)))
    :seon.dev.process.status/reused
    :else :seon.dev.process.status/dead))

(defn- group-command [signal pid]
  (process/sh {:continue true :out :string :err :string
               :cmd ["/bin/kill" signal "--" (str "-" pid)]}))

(defn- group-alive? [pid]
  (zero? (:exit (group-command "-0" pid))))

(defn- writer-ready? [config]
  (let [socket (:seon.dev.config/request-socket config)]
    (and (fs/regular-file? (:seon.dev.config/writer-repl-port-file config))
         (fs/exists? socket)
         (try
           (with-open [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
             (.connect channel (UnixDomainSocketAddress/of socket)))
           true
           (catch Throwable _ false)))))

(defn- http-ready? [port]
  (try
    (let [connection ^HttpURLConnection
          (.openConnection (URL. (str "http://127.0.0.1:" port "/")))]
      (.setConnectTimeout connection 1000)
      (.setReadTimeout connection 1000)
      (.setInstanceFollowRedirects connection false)
      (let [status (.getResponseCode connection)]
        (.disconnect connection)
        (<= 200 status 399)))
    (catch Throwable _ false)))

(defn- last-index [text needle] (.lastIndexOf ^String text needle))

(defn- tail-text
  "Read at most the final 256 KiB of a process log."
  [path]
  (if-not (fs/regular-file? path)
    ""
    (with-open [file (RandomAccessFile. (str path) "r")]
      (let [length (.length file)
            start (max 0 (- length 262144))
            size (- length start)
            buffer (byte-array (int size))]
        (.seek file start)
        (.readFully file buffer)
        (String. buffer StandardCharsets/UTF_8)))))

(defn- watcher-ready? [record]
  (let [log (:seon.dev.process/log record)
        text (tail-text log)
        completed (last-index text "[:client] Build completed.")
        failed (last-index text "[:client] Build failure:")
        compiling (last-index text "[:client] Compiling ...")]
    (and (not (neg? completed)) (> completed failed) (> completed compiling))))

(defn- pod-ready? [config record]
  (let [port-file (:seon.dev.config/http-port-file config)
        log (:seon.dev.process/log record)]
    (and (fs/regular-file? port-file)
         (fs/regular-file? log)
         (str/includes? (tail-text log) "[seon.client] auto-boot ready")
         (some-> (slurp port-file) str/trim parse-long http-ready?))))

(defn ready?
  "Probe readiness for the current process lifetime."
  [config spec record]
  (and (= :seon.dev.process.status/alive (process-status record))
       (case (:seon.dev.process/readiness spec)
         :seon.dev.process.readiness/process true
         :seon.dev.process.readiness/watcher (watcher-ready? record)
         :seon.dev.process.readiness/writer (writer-ready? config)
         :seon.dev.process.readiness/pod (pod-ready? config record)
         false)))

(defn- readiness-failure [record]
  (let [log (:seon.dev.process/log record)
        text (tail-text log)]
    (or (some->> (str/split-lines text)
                 (filter #(or (str/includes? % "[:client] Build failure:")
                              (str/includes? % "auto-boot FAILED")
                              (str/includes? % "SEON-CORE-FAULT ")))
                 last)
        (when (not= :seon.dev.process.status/alive (process-status record))
          "process exited before readiness"))))

(defn wait-ready!
  "Wait until one process lifetime passes its direct readiness probe."
  [config spec record]
  (let [deadline (+ (System/currentTimeMillis)
                    (:seon.dev.process/ready-timeout-ms spec))]
    (loop []
      (cond
        (ready? config spec record) record
        (readiness-failure record)
        (throw (ex-info "A Seon process failed before readiness."
                        {:seon.dev.process/id (:seon.dev.process/id spec)
                         :seon.dev.process/failure (readiness-failure record)
                         :seon.dev.process/log (:seon.dev.process/log record)}))
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 200) (recur))
        :else
        (throw (ex-info "Timed out waiting for Seon process readiness."
                        {:seon.dev.process/id (:seon.dev.process/id spec)
                         :seon.dev.process/log (:seon.dev.process/log record)}))))))

(defn- accepting-unmanaged? [config id]
  (case id
    :seon.dev.process/writer
    (try
      (with-open [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
        (.connect channel (UnixDomainSocketAddress/of
                            (:seon.dev.config/request-socket config))))
      true
      (catch Throwable _ false))
    :seon.dev.process/pod (http-ready? (:seon.dev.config/http-port config))
    false))

(defn- clear-readiness! [config id]
  (case id
    :seon.dev.process/writer
    (doseq [path [(:seon.dev.config/request-socket config)
                  (:seon.dev.config/publish-socket config)
                  (:seon.dev.config/writer-repl-port-file config)]]
      (fs/delete-if-exists path))
    :seon.dev.process/pod
    (fs/delete-if-exists (:seon.dev.config/http-port-file config))
    nil))

(defn- spawn-detached! [config spec]
  (let [id (:seon.dev.process/id spec)
        instance (str (random-uuid))
        log (log-file config id instance)
        helper (str (fs/path (:seon.dev.config/root config)
                             "script/seon/dev/detach.py"))
        argv (into ["python3" helper (:seon.dev.config/root config) (str log)]
                   (:seon.dev.process/argv spec))
        result (process/sh {:continue true
                            :out :string
                            :err :string
                            :env (:seon.dev.process/environment spec)
                            :cmd argv})
        pid (some-> (:out result) str/trim parse-long)]
    (when-not (and (zero? (:exit result)) pid)
      (throw (ex-info "Failed to launch a detached Seon process."
                      {:seon.dev.process/id id
                       :seon.dev.process/error (str/trim (:err result))})))
    (let [start-instant
          (loop [attempt 0]
            (or (state/process-start-instant pid)
                (when (< attempt 50)
                  (Thread/sleep 10)
                  (recur (inc attempt)))))]
      (when-not start-instant
        (throw (ex-info "Process exited before its identity was recorded."
                        {:seon.dev.process/id id :seon.dev.process/pid pid})))
      (write-process!
        config id
        {:seon.dev.process/id id
         :seon.dev.process/pid pid
         :seon.dev.process/start-instant start-instant
         :seon.dev.process/process-group pid
         :seon.dev.process/argv (:seon.dev.process/argv spec)
         :seon.dev.process/environment-digest
         (environment-digest (:seon.dev.process/environment spec))
         :seon.dev.process/artifact-digest
         (:seon.dev.process/artifact-digest spec)
         :seon.dev.process/target :seon.dev.target/development
         :seon.dev.process/started-at (str (Instant/now))
         :seon.dev.process/log (str log)}))))

(defn- same-process-spec? [spec record]
  (and (= (:seon.dev.process/argv spec) (:seon.dev.process/argv record))
       (= (:seon.dev.process/artifact-digest spec)
          (:seon.dev.process/artifact-digest record))
       (= (environment-digest (:seon.dev.process/environment spec))
          (:seon.dev.process/environment-digest record))))

(defn- drain-group! [id pid]
  (when (and pid (group-alive? pid))
    (group-command "-TERM" pid)
    (loop [remaining 25]
      (when (and (pos? remaining) (group-alive? pid))
        (Thread/sleep 100)
        (recur (dec remaining))))
    (when (group-alive? pid)
      (group-command "-KILL" pid)
      (loop [remaining 50]
        (when (and (pos? remaining) (group-alive? pid))
          (Thread/sleep 100)
          (recur (dec remaining)))))
    (when (group-alive? pid)
      (throw (ex-info "A Seon process group survived SIGKILL."
                      {:seon.dev.process/id id
                       :seon.dev.process/process-group pid})))))

(defn stop!
  "Drain one exact managed process group and clear its state."
  [config id]
  (let [record (read-process config id)
        status (process-status record)
        pid (:seon.dev.process/process-group record)]
    (case status
      :seon.dev.process.status/absent nil
      :seon.dev.process.status/reused (clear-process! config id)
      :seon.dev.process.status/dead
      (if (and pid (group-alive? pid))
        (throw (ex-info
                 "Refusing to signal a process group whose recorded leader is dead."
                 {:seon.dev.process/id id
                  :seon.dev.process/process-group pid}))
        (clear-process! config id))
      :seon.dev.process.status/alive
      (do
        (drain-group! id pid)
        (clear-process! config id)))
    ;; A missing/reused state record is not authority over a live listener.
    ;; Preserve its breadcrumb so the next reconciliation can report the
    ;; ownership conflict instead of unlinking evidence it does not own.
    (when-not (accepting-unmanaged? config id)
      (clear-readiness! config id))
    nil))

(defn ensure!
  "Reconcile one process to its exact argv, environment, artifact, and health."
  [config spec]
  (let [id (:seon.dev.process/id spec)
        record (read-process config id)]
    (if (and (= :seon.dev.process.status/alive (process-status record))
             (same-process-spec? spec record)
             (ready? config spec record))
      record
      (do
        (when record (stop! config id))
        (when (accepting-unmanaged? config id)
          (throw (ex-info "An unmanaged listener blocks the Seon process."
                          {:seon.dev.process/id id})))
        (clear-readiness! config id)
        (let [started (spawn-detached! config spec)]
          (wait-ready! config spec started))))))

(defn status
  "Derive process and application health from live probes."
  [config manifest]
  (let [spec-map (specs config manifest)
        ordered (start-order spec-map)
        processes
        (into {}
              (map (fn [id]
                     (let [record (read-process config id)
                           spec (get spec-map id)
                           process-state (process-status record)]
                       [id {:seon.dev.process/status process-state
                            :seon.dev.process/pid (:seon.dev.process/pid record)
                            :seon.dev.process/ready?
                            (boolean (and record
                                          (same-process-spec? spec record)
                                          (ready? config spec record)))
                            :seon.dev.process/log (:seon.dev.process/log record)}])))
              ordered)
        all-ready? (every? (fn [[_ value]]
                             (and (= :seon.dev.process.status/alive
                                     (:seon.dev.process/status value))
                                  (:seon.dev.process/ready? value)))
                           processes)
        pod-ready (get-in processes [pod-id :seon.dev.process/ready?])
        port (when (and pod-ready
                        (fs/regular-file? (:seon.dev.config/http-port-file config)))
               (some-> (slurp (:seon.dev.config/http-port-file config))
                       str/trim parse-long))]
    {:seon.dev.target/status (if all-ready?
                               :seon.dev.target.status/ready
                               (if (some #(= :seon.dev.process.status/alive
                                             (:seon.dev.process/status %))
                                         (vals processes))
                                 :seon.dev.target.status/degraded
                                 :seon.dev.target.status/down))
     :seon.dev.target/name :seon.dev.target/development
     :seon.dev.target/processes processes
     :seon.dev.target/url (when port (str "http://127.0.0.1:" port))}))
