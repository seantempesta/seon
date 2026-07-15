(ns seon.dev.process
  "Process graph, identity, readiness, and drain mechanics for Seon."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.dev.artifact :as artifact]
            [seon.dev.state :as state]
            [seon.launch :as launch])
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

(def ^:private unpublished-client-digest
  "unpublished-client-watcher-flush")

(def external-dependency-schema
  [:map {:closed true}
   [:seon.dev.process/id qualified-keyword?]
   [:seon.dev.process/owner-process-dir :string]
   [:seon.dev.process/readiness qualified-keyword?]
   [:seon.dev.process/artifact-digest :string]])

(def process-spec-schema
  [:map
   [:seon.dev.process/id qualified-keyword?]
   [:seon.dev.process/argv [:vector {:min 1} :string]]
   [:seon.dev.process/environment [:map-of :string :string]]
   [:seon.dev.process/dependencies [:vector qualified-keyword?]]
   [:seon.dev.process/external-dependencies {:optional true}
    [:vector external-dependency-schema]]
   [:seon.dev.process/http-port-file {:optional true} :string]
   [:seon.dev.process/readiness qualified-keyword?]
   [:seon.dev.process/ready-timeout-ms [:int {:min 1}]]
   [:seon.dev.process/bootstrap-digest {:optional true}
    [:re #"[0-9a-f]{64}"]]
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
  (let [descriptor (:seon.dev.config/launch-descriptor config)
        autonomous?
        (true? (get-in descriptor
                       [::launch/runtime :seon.client/launch-capability
                        :seon.client/autonomous?]))
        process-dir
        (if (and (= pod-id id) (not autonomous?))
          (get-in descriptor [::launch/process ::launch/process-dir])
          (:seon.dev.config/process-dir config))]
    (str (fs/path process-dir
                  "processes" (str (id-name id) ".edn")))))

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
  (let [descriptor (:seon.dev.config/launch-descriptor config)
        log-dir (or (get-in descriptor [::launch/process ::launch/log-dir])
                    (:seon.dev.config/log-dir config))]
    (fs/path log-dir (id-name id))))

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

(defn- watcher-build-ids [config]
  (let [client-build-id
        (keyword (:seon.dev.config/client-build-id config))]
    (case (:seon.dev.config/artifact-flavor config)
      :seon.dev.artifact.flavor/default [client-build-id :test]
      :seon.dev.artifact.flavor/acme [client-build-id]
      (throw
        (ex-info "Unknown artifact flavor for the managed Shadow watcher."
                 {:seon.dev.config/artifact-flavor
                  (:seon.dev.config/artifact-flavor config)})))))

(defn- extra-cljs-watch-args [config]
  (let [environment (:seon.dev.config/environment config)
        build-ids (mapv name (watcher-build-ids config))
        watch-args (into ["-M:cljs" "watch"] build-ids)
        source (get environment "SEON_EXTRA_SRC")
        preload (get environment "SEON_EXTRA_PRELOAD")
        config-merge
        (cond-> {}
          (and (not (str/blank? source)) (not (str/blank? preload)))
          (assoc :devtools {:preloads [(symbol preload)]}))]
    (cond-> []
      (not (str/blank? source))
      (into ["-Sdeps" (pr-str {:deps {'seon.extra/src {:local/root source}}})])

      true
      (into watch-args)

      (seq config-merge)
      (into ["--config-merge" (pr-str config-merge)]))))

(defn- watcher-spec [config artifact-digest]
  {:seon.dev.process/id watcher-id
   :seon.dev.process/argv (into ["clj"] (extra-cljs-watch-args config))
   :seon.dev.process/environment (:seon.dev.config/environment config)
   :seon.dev.process/dependencies []
   :seon.dev.process/readiness :seon.dev.process.readiness/watcher
   :seon.dev.process/ready-timeout-ms 300000
   :seon.dev.process/artifact-digest artifact-digest})

(defn specs
  "Derive the complete development process graph from one manifest."
  [config manifest]
  (let [environment (:seon.dev.config/environment config)
        descriptor (:seon.dev.config/launch-descriptor config)
        descriptor-runtime (::launch/runtime descriptor)
        descriptor-writer (::launch/writer-owner descriptor)
        descriptor-process (::launch/process descriptor)
        selected-artifact
        [(::launch/artifact-flavor descriptor-runtime)
         (::launch/client-build-id descriptor-runtime)]
        configured-artifact
        [(:seon.dev.config/artifact-flavor config)
         (:seon.dev.config/client-build-id config)]
        _ (when-not (= configured-artifact selected-artifact)
            (throw
             (ex-info "The launch descriptor selects another artifact."
                      {:seon.dev.process/configured-artifact configured-artifact
                       :seon.dev.process/selected-artifact selected-artifact})))
        autonomous?
        (true? (get-in descriptor
                       [::launch/runtime :seon.client/launch-capability
                        :seon.client/autonomous?]))
        runtime-root (:seon.dev.artifact/runtime-root manifest)
        pod-environment (cond->
                          (assoc
                           environment
                           "SEON_LAUNCH_DESCRIPTOR" (pr-str descriptor)
                           "SEON_REQ_SOCK"
                           (::launch/request-socket-path descriptor-writer)
                           "SEON_PUB_SOCK"
                           (::launch/publish-socket-path descriptor-writer)
                           "SEON_WRITER_REPL_PORT_FILE"
                           (::launch/writer-repl-port-file descriptor-writer)
                           "SEON_PROC_DIR"
                           (::launch/process-dir descriptor-process)
                           "SEON_LOG_DIR"
                           (::launch/log-dir descriptor-process)
                           "SEON_PORT"
                           (str (::launch/http-port descriptor-process))
                           "SEON_PORT_FILE"
                           (::launch/http-port-file descriptor-process))
                          runtime-root (assoc "SEON_RUNTIME_ROOT" runtime-root))
        java (get environment "JAVA_CMD" "java")
        pod-spec
        (cond->
          {:seon.dev.process/id pod-id
           :seon.dev.process/argv
           ["node" (:seon.dev.config/client-output config)]
           :seon.dev.process/environment pod-environment
           :seon.dev.process/dependencies
           (if autonomous? [watcher-id writer-id] [])
           :seon.dev.process/http-port-file
           (::launch/http-port-file descriptor-process)
           :seon.dev.process/readiness :seon.dev.process.readiness/pod
           :seon.dev.process/ready-timeout-ms 120000
           :seon.dev.process/artifact-digest
           (:seon.dev.artifact/application-digest manifest)}
          runtime-root
          (assoc :seon.dev.process/bootstrap-digest
                 (:seon.dev.artifact/bootstrap-digest manifest))

          (not autonomous?)
          (assoc :seon.dev.process/external-dependencies
                  [{:seon.dev.process/id watcher-id
                   :seon.dev.process/owner-process-dir
                   (::launch/writer-process-dir descriptor-writer)
                   :seon.dev.process/readiness
                   :seon.dev.process.readiness/watcher
                   :seon.dev.process/artifact-digest
                   (:seon.dev.artifact/client-digest manifest)}
                  {:seon.dev.process/id writer-id
                   :seon.dev.process/owner-process-dir
                   (::launch/writer-process-dir descriptor-writer)
                   :seon.dev.process/readiness
                   :seon.dev.process.readiness/writer
                   :seon.dev.process/artifact-digest
                   (:seon.dev.artifact/writer-digest manifest)}]))
        spec-map
        {watcher-id
         (watcher-spec config (:seon.dev.artifact/client-digest manifest))

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

     pod-id pod-spec}
        spec-map (if autonomous? spec-map {pod-id pod-spec})]
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
  ;; Runtime shutdown has already terminated Babashka's future executor, so
  ;; cleanup hooks cannot safely reach `babashka.process/sh`. The direct JDK
  ;; boundary is synchronous, bounded, and needs no process-local executor.
  (let [argv ["/bin/kill" signal "--" (str "-" pid)]
        builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                  (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
        child (.start builder)]
    {:exit (.waitFor child)}))

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

(defn- tcp-ready? [port]
  (and (pos-int? port)
       (try
         (with-open [socket (java.net.Socket.)]
           (.connect socket (java.net.InetSocketAddress.
                              "127.0.0.1" (int port)) 500))
         true
         (catch Throwable _ false))))

(defn- http-ready? [port]
  (try
    (let [connection ^HttpURLConnection
          (.openConnection
            (URL. (str "http://127.0.0.1:" port "/_seon/ready")))]
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

(defn- build-ready? [text build-id]
  (let [prefix (str "[:" (name build-id) "] ")
        completed (last-index text (str prefix "Build completed."))
        failed (last-index text (str prefix "Build failure:"))
        compiling (last-index text (str prefix "Compiling ..."))]
    (and (not (neg? completed)) (> completed failed) (> completed compiling))))

(defn- watcher-ready? [config record]
  (let [text (tail-text (:seon.dev.process/log record))]
    (every? #(build-ready? text %) (watcher-build-ids config))))

(defn- current-client-ready? [config spec]
  (if-let [expected (:seon.dev.process/artifact-digest spec)]
    (try
      (= expected (artifact/current-client-digest config))
      (catch Throwable _ false))
    true))

(defn- runtime-bootstrap-ready? [spec]
  (if-let [expected (:seon.dev.process/bootstrap-digest spec)]
    (let [runtime-root (get-in spec [:seon.dev.process/environment
                                     "SEON_RUNTIME_ROOT"])]
      (and runtime-root
           (fs/directory? (fs/path runtime-root "out/bootstrap"))
           (try
             (= expected (artifact/digest-paths runtime-root ["out/bootstrap"]))
             (catch Throwable _ false))))
    true))

(defn- pod-ready? [config spec record]
  (let [port-file (or (:seon.dev.process/http-port-file spec)
                      (:seon.dev.config/http-port-file config))]
    (and (runtime-bootstrap-ready? spec)
         (fs/regular-file? port-file)
         (some-> (slurp port-file) str/trim parse-long http-ready?))))

(defn ready?
  "Probe readiness for the current process lifetime."
  [config spec record]
  (and (= :seon.dev.process.status/alive (process-status record))
       (case (:seon.dev.process/readiness spec)
         :seon.dev.process.readiness/process true
         :seon.dev.process.readiness/watcher
         (and (watcher-ready? config record)
              (current-client-ready? config spec))
         :seon.dev.process.readiness/writer (writer-ready? config)
         :seon.dev.process.readiness/pod (pod-ready? config spec record)
         false)))

(defn- readiness-failure [config record]
  (let [log (:seon.dev.process/log record)
        text (tail-text log)
        build-failures
        (mapv #(str "[:" (name %) "] Build failure:")
              (watcher-build-ids config))]
    (or (some->> (str/split-lines text)
                 (filter #(or (some (fn [needle]
                                      (str/includes? % needle))
                                    build-failures)
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
        (readiness-failure config record)
        (throw (ex-info "A Seon process failed before readiness."
                        {:seon.dev.process/id (:seon.dev.process/id spec)
                         :seon.dev.process/failure
                         (readiness-failure config record)
                         :seon.dev.process/log (:seon.dev.process/log record)}))
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 200) (recur))
        :else
        (throw (ex-info "Timed out waiting for Seon process readiness."
                        {:seon.dev.process/id (:seon.dev.process/id spec)
                         :seon.dev.process/log (:seon.dev.process/log record)}))))))

(defn- wait-watcher-flush! [config spec record]
  (let [deadline (+ (System/currentTimeMillis)
                    (:seon.dev.process/ready-timeout-ms spec))]
    (loop []
      (cond
        (and (= :seon.dev.process.status/alive (process-status record))
             (watcher-ready? config record))
        record

        (readiness-failure config record)
        (throw (ex-info "The managed watcher failed before its first flush."
                        {:seon.dev.process/id watcher-id
                         :seon.dev.process/failure
                         (readiness-failure config record)
                         :seon.dev.process/log
                         (:seon.dev.process/log record)}))

        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 200) (recur))

        :else
        (throw (ex-info "Timed out waiting for the managed watcher's first flush."
                        {:seon.dev.process/id watcher-id
                         :seon.dev.process/log
                         (:seon.dev.process/log record)}))))))

(defn- accepting-unmanaged? [config id]
  (case id
    :seon.dev.process/watcher
    (let [cache-root (:seon.dev.config/shadow-cache-root config)
          path (when cache-root (fs/path cache-root "nrepl.port"))
          port (when (fs/regular-file? path)
                 (some-> (slurp (str path)) str/trim parse-long))]
      (tcp-ready? port))
    :seon.dev.process/writer
    (let [request-socket (:seon.dev.config/request-socket config)
          file (:seon.dev.config/writer-repl-port-file config)]
      (or
      (and request-socket
      (try
        (with-open [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
          (.connect channel (UnixDomainSocketAddress/of
                              request-socket)))
        true
        (catch Throwable _ false)))
      (let [published (when (and file (fs/regular-file? file))
                        (some-> (slurp file) str/trim parse-long))]
        (tcp-ready? (or published
                        (:seon.dev.config/writer-repl-port config))))))
    :seon.dev.process/pod
    (boolean (when-let [port (or (get-in config
                                         [:seon.dev.config/launch-descriptor
                                          ::launch/process ::launch/http-port])
                                 (:seon.dev.config/http-port config))]
               (tcp-ready? port)))
    false))

(defn ownership-conflicts
  "Process doors held without a matching live operator record."
  ([config] (ownership-conflicts config target-processes))
  ([config owned-processes]
   (if-not (:seon.dev.config/process-dir config)
     []
     (->> owned-processes
          (filter (fn [id]
                    (let [record (read-process config id)]
                      (and (not= :seon.dev.process.status/alive
                                 (process-status record))
                           (accepting-unmanaged? config id)))))
          vec))))

(defn- readiness-paths
  [config id]
  (case id
    :seon.dev.process/writer
    (->> [(:seon.dev.config/request-socket config)
          (:seon.dev.config/publish-socket config)
          (:seon.dev.config/writer-repl-port-file config)]
         (filterv string?))
    :seon.dev.process/pod
    (if-let [path
             (or (get-in config [:seon.dev.config/launch-descriptor
                                 ::launch/process ::launch/http-port-file])
                 (:seon.dev.config/http-port-file config))]
      [path]
      [])
    []))

(defn- clear-readiness! [config id]
  (doseq [path (readiness-paths config id)]
    (fs/delete-if-exists path)))

(defn- prepare-readiness! [config id]
  (doseq [path (readiness-paths config id)]
    (fs/create-dirs (fs/parent path))))

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

(defn converged?
  "True when one managed process already has the exact spec and is ready."
  [config spec]
  (let [record (read-process config (:seon.dev.process/id spec))]
    (boolean
     (and (= :seon.dev.process.status/alive (process-status record))
          (same-process-spec? spec record)
          (ready? config spec record)))))

(defn- external-dependency-ready?
  [config dependency]
  (let [owner-config
        (assoc config :seon.dev.config/process-dir
               (:seon.dev.process/owner-process-dir dependency))
        id (:seon.dev.process/id dependency)
        record (read-process owner-config id)
        descriptor (:seon.dev.config/launch-descriptor config)
        writer-owner (::launch/writer-owner descriptor)
        probe-config
        (assoc config
               :seon.dev.config/request-socket
               (::launch/request-socket-path writer-owner)
               :seon.dev.config/publish-socket
               (::launch/publish-socket-path writer-owner)
               :seon.dev.config/writer-repl-port-file
               (::launch/writer-repl-port-file writer-owner))]
    (and (= :seon.dev.process.status/alive (process-status record))
         (= (:seon.dev.process/artifact-digest dependency)
            (:seon.dev.process/artifact-digest record))
         (case (:seon.dev.process/readiness dependency)
           :seon.dev.process.readiness/watcher
           (and (watcher-ready? probe-config record)
                (current-client-ready?
                 probe-config
                 {:seon.dev.process/artifact-digest
                  (:seon.dev.process/artifact-digest dependency)}))
           :seon.dev.process.readiness/writer
           (writer-ready? probe-config)
           false))))

(defn- external-dependency-status
  [config dependency]
  (let [owner-config
        (assoc config :seon.dev.config/process-dir
               (:seon.dev.process/owner-process-dir dependency))
        id (:seon.dev.process/id dependency)
        record (read-process owner-config id)
        recorded-state (process-status record)]
    (cond->
      {:seon.dev.process/status recorded-state
       :seon.dev.process/ready?
       (boolean (external-dependency-ready? config dependency))
       :seon.dev.process/owner-process-dir
       (:seon.dev.process/owner-process-dir dependency)
       :seon.dev.process/readiness
       (:seon.dev.process/readiness dependency)
       :seon.dev.process/artifact-digest
       (:seon.dev.process/artifact-digest dependency)}
      record
      (assoc :seon.dev.process/pid (:seon.dev.process/pid record)
             :seon.dev.process/start-instant
             (:seon.dev.process/start-instant record)
             :seon.dev.process/recorded-artifact-digest
             (:seon.dev.process/artifact-digest record)))))

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
  ([config spec] (ensure! config spec (fn [_ spawn!] (spawn!))))
  ([config spec start-owned!]
   (let [id (:seon.dev.process/id spec)
        unavailable
        (->> (:seon.dev.process/external-dependencies spec)
             (remove #(external-dependency-ready? config %))
             (mapv :seon.dev.process/id))
        _ (when (seq unavailable)
            (throw
             (ex-info "A required external process owner is unavailable."
                      {:seon.dev.process/id id
                       :seon.dev.process/unavailable-external-processes
                       unavailable})))
         record (read-process config id)]
     (if (converged? config spec)
       record
       (do
         (when record (stop! config id))
         (when (accepting-unmanaged? config id)
           (throw (ex-info "An unmanaged listener blocks the Seon process."
                           {:seon.dev.process/id id})))
         (clear-readiness! config id)
         (prepare-readiness! config id)
         (let [started
               (start-owned! id #(spawn-detached! config spec))]
           (wait-ready! config spec started)))))))

(defn prepare-watcher!
  "Start the sole client-output owner and await its first complete flush."
  {:malli/schema
   [:=> [:catn [:config map?] [:start-owned! fn?]] process-record-schema]}
  [config start-owned!]
  (let [spec (validate!
              process-spec-schema
              (watcher-spec config unpublished-client-digest)
              "The prepared watcher specification is invalid."
              :seon.dev.process/explanation)
        record (read-process config watcher-id)]
    (when record (stop! config watcher-id))
    (when (accepting-unmanaged? config watcher-id)
      (throw (ex-info "An unmanaged Shadow watcher blocks client publication."
                      {:seon.dev.process/id watcher-id})))
    (let [started (start-owned! watcher-id #(spawn-detached! config spec))]
      (wait-watcher-flush! config spec started))))

(defn admit-watcher-artifact!
  "Bind a prepared watcher lifetime to its exact published client digest."
  {:malli/schema
   [:=>
    [:catn [:config map?] [:client-digest [:re #"[0-9a-f]{64}"]]]
    process-record-schema]}
  [config client-digest]
  (let [record (read-process config watcher-id)
        actual (artifact/current-client-digest config)]
    (when-not (and record
                   (= :seon.dev.process.status/alive (process-status record))
                   (= unpublished-client-digest
                      (:seon.dev.process/artifact-digest record))
                   (watcher-ready? config record)
                   (= client-digest actual))
      (throw
       (ex-info "The managed watcher cannot admit the published client artifact."
                {:seon.dev.process/id watcher-id
                 :seon.dev.process/expected-client-digest client-digest
                 :seon.dev.process/actual-client-digest actual
                 :seon.dev.process/recorded-artifact-digest
                 (:seon.dev.process/artifact-digest record)})))
    (write-process!
     config watcher-id
     (assoc record :seon.dev.process/artifact-digest client-digest))))

(defn- unwind-owned! [ownerships]
  (let [failures
        (reduce
         (fn [acc ownership]
           (try
             ((:seon.dev.process/release! ownership))
             acc
             (catch Throwable error
               (conj acc {:seon.dev.process/id
                          (:seon.dev.process/id ownership)
                          :seon.dev.process/message (ex-message error)
                          :seon.dev.process/data (ex-data error)}))))
         []
         (reverse ownerships))]
    (when (seq failures)
      (throw
       (ex-info "Failed to unwind every startup resource acquired by this invocation."
                {:seon.dev.process/unwind-failures failures})))
    nil))

(defn with-startup-ownership
  "Run one startup transition with signal-safe ownership of new resources."
  [config transition]
  (let [monitor (Object.)
        state (atom {:seon.dev.process/shutting-down? false
                     :seon.dev.process/ownerships []
                     :seon.dev.process/unwind-claimed? false})
        unwind-result (promise)
        acquire-owned!
        (fn acquire-owned!
          ([id acquire!]
           (acquire-owned! id acquire! #(stop! config id)))
          ([id acquire! release!]
           (locking monitor
             (when (:seon.dev.process/shutting-down? @state)
               (throw
                (ex-info "Seon startup was interrupted before resource acquisition."
                         {:seon.dev.process/id id})))
             ;; Acquisition, exact publication, and inverse registration are
             ;; one phase. Shutdown either closes admission first or waits for
             ;; a complete identity whose inverse can run.
             (swap! state update :seon.dev.process/ownerships conj
                    {:seon.dev.process/id id
                     :seon.dev.process/release! release!})
             (acquire!))))
        claim-unwind!
        #(locking monitor
           (let [{:seon.dev.process/keys [unwind-claimed? ownerships]} @state]
             (swap! state assoc :seon.dev.process/shutting-down? true)
             (when-not unwind-claimed?
               (swap! state assoc :seon.dev.process/unwind-claimed? true)
               ownerships)))
        unwind!
        #(if-some [ownerships (claim-unwind!)]
           (try
             (unwind-owned! ownerships)
             (deliver unwind-result {:seon.dev.process/unwound? true})
             (catch Throwable error
               (deliver unwind-result {:seon.dev.process/unwind-error error})
               (throw error)))
           (when-let [error (:seon.dev.process/unwind-error @unwind-result)]
             (throw error)))
        runtime (Runtime/getRuntime)
        shutdown-hook
        (Thread.
         (fn []
           (try
             (unwind!)
             (catch Throwable error
               (binding [*out* *err*]
                 (println (str "Failed to unwind interrupted Seon startup: "
                               (ex-message error)))
                 (when-let [data (not-empty (ex-data error))]
                   (prn data))))))
         "seon-startup-process-unwind")]
    (.addShutdownHook runtime shutdown-hook)
    (try
      (transition acquire-owned!)
      (catch Throwable error
        (try
          (unwind!)
          (catch Throwable cleanup
            (let [combined
                  (ex-info
                   "Seon startup failed and cleanup was incomplete."
                   {:seon.dev.process/startup-message (ex-message error)
                    :seon.dev.process/startup-data (ex-data error)
                    :seon.dev.process/unwind-message (ex-message cleanup)
                    :seon.dev.process/unwind-data (ex-data cleanup)}
                   error)]
              (.addSuppressed combined cleanup)
              (throw combined))))
        (throw error))
      (finally
        (try
          (.removeShutdownHook runtime shutdown-hook)
          (catch IllegalStateException _))))))

(defn status
  "Derive process and application health from live probes."
  [config manifest]
  (let [spec-map (specs config manifest)
        ordered (start-order spec-map)
        descriptor (:seon.dev.config/launch-descriptor config)
        descriptor-runtime (::launch/runtime descriptor)
        descriptor-database (::launch/database descriptor)
        descriptor-process (::launch/process descriptor)
        processes
        (into {}
              (map (fn [id]
                     (let [record (read-process config id)
                           spec (get spec-map id)
                           recorded-state (process-status record)
                           foreign? (and (not= :seon.dev.process.status/alive
                                               recorded-state)
                                         (accepting-unmanaged? config id))
                           process-state (if foreign?
                                           :seon.dev.process.status/foreign
                                           recorded-state)]
                       [id (cond->
                             {:seon.dev.process/status process-state
                              :seon.dev.process/ready?
                              (boolean (and record
                                            (same-process-spec? spec record)
                                            (ready? config spec record)))}
                             record
                             (assoc
                               :seon.dev.process/pid
                               (:seon.dev.process/pid record)
                               :seon.dev.process/start-instant
                               (:seon.dev.process/start-instant record)
                               :seon.dev.process/environment-digest
                               (:seon.dev.process/environment-digest record)
                               :seon.dev.process/artifact-digest
                               (:seon.dev.process/artifact-digest record)
                               :seon.dev.process/log
                               (:seon.dev.process/log record)))])))
              ordered)
        external-dependencies
        (into {}
              (mapcat
               (fn [id]
                 (map (fn [dependency]
                        [(:seon.dev.process/id dependency)
                         (external-dependency-status config dependency)])
                      (:seon.dev.process/external-dependencies
                       (get spec-map id))))
               ordered))
        foreign? (seq (ownership-conflicts config ordered))
        all-ready?
        (and (every? (fn [[_ value]]
                       (and (= :seon.dev.process.status/alive
                               (:seon.dev.process/status value))
                            (:seon.dev.process/ready? value)))
                     processes)
             (every? (fn [[_ value]]
                       (and (= :seon.dev.process.status/alive
                               (:seon.dev.process/status value))
                            (:seon.dev.process/ready? value)))
                     external-dependencies))
        pod-ready (get-in processes [pod-id :seon.dev.process/ready?])
        port (when (and pod-ready
                        (fs/regular-file?
                         (::launch/http-port-file descriptor-process)))
               (some-> (slurp (::launch/http-port-file descriptor-process))
                       str/trim parse-long))
        writer-ready (get-in processes [writer-id :seon.dev.process/ready?])
        writer-port (when (and writer-ready
                               (fs/regular-file?
                                 (:seon.dev.config/writer-repl-port-file config)))
                      (some-> (slurp
                                (:seon.dev.config/writer-repl-port-file config))
                              str/trim parse-long))
        watcher-ready (get-in processes [watcher-id :seon.dev.process/ready?])
        shadow-port-file (fs/path (:seon.dev.config/shadow-cache-root config)
                                  "nrepl.port")
        shadow-port (when (and watcher-ready (fs/regular-file? shadow-port-file))
                      (some-> (slurp (str shadow-port-file)) str/trim parse-long))
        artifact (select-keys manifest
                              [:seon.dev.artifact/flavor
                               :seon.dev.artifact/client-build-id
                               :seon.dev.artifact/application-digest
                               :seon.dev.artifact/writer-digest
                               :seon.dev.artifact/client-digest
                               :seon.dev.artifact/bootstrap-digest
                               :seon.dev.artifact/runtime-root])]
    {:seon.dev.target/status (cond
                               foreign? :seon.dev.target.status/ownership-conflict
                               all-ready? :seon.dev.target.status/ready
                               (some #(= :seon.dev.process.status/alive
                                         (:seon.dev.process/status %))
                                     (vals processes))
                               :seon.dev.target.status/degraded
                               :else :seon.dev.target.status/down)
     :seon.dev.target/name :seon.dev.target/development
     :seon.dev.target/cluster-name
     (::launch/runtime-cluster descriptor-runtime)
     :seon.dev.target/database-path
     (:seon.db.protocol/database-path descriptor-database)
     :seon.dev.target/artifact artifact
     :seon.dev.target/processes processes
     :seon.dev.target/external-dependencies external-dependencies
     :seon.dev.target/endpoints
     (cond-> {:seon.dev.endpoint/cljs-build-id
              (::launch/client-build-id descriptor-runtime)}
       port (assoc :seon.dev.endpoint/web
                   (str "http://127.0.0.1:" port))
       writer-port (assoc :seon.dev.endpoint/clj
                          (str "127.0.0.1:" writer-port))
       shadow-port (assoc :seon.dev.endpoint/cljs
                          (str "127.0.0.1:" shadow-port)))
     :seon.dev.target/url (when port (str "http://127.0.0.1:" port))}))
