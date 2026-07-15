(ns seon.dev.process
  "Process graph, identity, readiness, and drain mechanics for Seon."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.dev.artifact :as artifact]
            [seon.dev.config :as config]
            [seon.dev.state :as state]
            [seon.db.protocol :as db.protocol]
            [seon.launch :as launch]
            [seon.runtime.lifecycle :as runtime.lifecycle])
  (:import [java.io ByteArrayOutputStream PushbackReader RandomAccessFile
            StringReader]
           [java.net HttpURLConnection StandardProtocolFamily URL
            UnixDomainSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]
           [java.time Instant]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def watcher-id :seon.dev.process/watcher)
(def writer-id :seon.dev.process/writer)
(def pod-id :seon.dev.process/pod)
(def target-processes [watcher-id writer-id pod-id])

(def ^:private legacy-containment-shutdown-grace-ms 2500)

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
   [:seon.dev.process/shutdown-grace-ms [:int {:min 1}]]
   [:seon.dev.process/bootstrap-digest {:optional true}
    [:re #"[0-9a-f]{64}"]]
   [:seon.dev.process/artifact-digest :string]])

(def containment-schema
  [:map {:closed true}
   [:seon.dev.process.containment/generation uuid?]
   [:seon.dev.process.containment/owner-pid pos-int?]
   [:seon.dev.process.containment/owner-start-instant :string]
   [:seon.dev.process.containment/anchor-pid pos-int?]
   [:seon.dev.process.containment/anchor-start-instant :string]
   [:seon.dev.process.containment/process-group pos-int?]
   [:seon.dev.process.containment/workload-pid pos-int?]
   [:seon.dev.process.containment/workload-start-instant :string]
   [:seon.dev.process.containment/shutdown-grace-ms [:int {:min 1}]]
   [:seon.dev.process.containment/control-socket :string]
   [:seon.dev.process.containment/adoption-path :string]
   [:seon.dev.process.containment/result-path :string]
   [:seon.dev.process.containment/application-result-path
    {:optional true} :string]])

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
   [:seon.dev.process/log :string]
   [:seon.dev.process/containment {:optional true} containment-schema]])

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

(defn- decode-process-record [record]
  (let [containment (:seon.dev.process/containment record)
        grace-key :seon.dev.process.containment/shutdown-grace-ms]
    (if (and (map? containment) (not (contains? containment grace-key)))
      (assoc-in record [:seon.dev.process/containment grace-key]
                legacy-containment-shutdown-grace-ms)
      record)))

(defn read-process
  "Read one atomically published managed-process record."
  [config id]
  (when-let [record (state/read-edn (state-file config id))]
    (validate! process-record-schema (decode-process-record record)
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
   :seon.dev.process/shutdown-grace-ms 2500
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
           :seon.dev.process/shutdown-grace-ms 5000
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
      :seon.dev.process/shutdown-grace-ms 30000
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
  ;; This boundary exists only to retire a live exact pre-containment record.
  ;; Every new generation drains through its retained anchor instead.
  (let [argv ["/bin/kill" signal "--" (str "-" pid)]
        builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                  (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
        child (.start builder)]
    {:exit (.waitFor child)}))

(defn- group-present? [pid]
  (zero? (:exit (group-command "-0" pid))))

(defn- containment-identity [containment role]
  {:seon.dev.process/pid
   (get containment (keyword "seon.dev.process.containment"
                             (str (name role) "-pid")))
   :seon.dev.process/start-instant
   (get containment (keyword "seon.dev.process.containment"
                             (str (name role) "-start-instant")))})

(defn- containment-live? [record]
  (when-let [containment (:seon.dev.process/containment record)]
    (let [adoption
          (try
            (json/parse-string
             (slurp (:seon.dev.process.containment/adoption-path containment))
             true)
            (catch Throwable _ nil))]
      (and (= (str (:seon.dev.process.containment/generation containment))
              (:generation adoption))
           (= "adopted" (:status adoption))
           (= (:seon.dev.process/pid record)
              (:seon.dev.process.containment/owner-pid containment))
           (= (:seon.dev.process/start-instant record)
              (:seon.dev.process.containment/owner-start-instant containment))
           (= (:seon.dev.process.containment/anchor-pid containment)
              (:seon.dev.process.containment/process-group containment))
           (every? state/process-identity-alive?
                   (map #(containment-identity containment %)
                        [:owner :anchor :workload]))
           (not (fs/exists?
                 (:seon.dev.process.containment/result-path containment)))))))

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
       (containment-live? record)
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

(declare abort-unpublished-containment! adopt-containment!)

(defn- spawn-detached! [config spec]
  (let [id (:seon.dev.process/id spec)
        instance (str (random-uuid))
        generation (random-uuid)
        log (log-file config id instance)
        containment-dir
        (fs/path (fs/parent (state-file config id)) "containment"
                 (id-name id) (str generation))
        descriptor-path (str (fs/path containment-dir "descriptor.json"))
        control-socket
        (str (fs/path (:seon.dev.config/root config) "tmp" "seon-containment"
                      (str generation ".sock")))
        result-path (str (fs/path containment-dir "result.json"))
        application-result-path
        (when (= writer-id id)
          (str (fs/path containment-dir "application.edn")))
        launch-environment
        (cond-> (assoc (:seon.dev.process/environment spec)
                       "SEON_PROCESS_GENERATION" (str generation))
          application-result-path
          (assoc "SEON_APPLICATION_RESULT_PATH" application-result-path))
        helper (str (fs/path (:seon.dev.config/root config)
                             "script/seon/dev/detach.py"))
        _ (do (fs/create-dirs containment-dir)
              (fs/create-dirs (fs/parent control-socket)))
        argv (into ["python3" helper "launch"
                    (:seon.dev.config/root config) (str log) (str generation)
                    descriptor-path control-socket result-path
                    (str (:seon.dev.process/shutdown-grace-ms spec))]
                   (:seon.dev.process/argv spec))
        result (process/sh {:continue true
                            :out :string
                            :err :string
                            :env launch-environment
                            :cmd argv})
        launch-result
        (when (zero? (:exit result))
          (try (json/parse-string (str/trim (:out result)) true)
               (catch Throwable _ nil)))
        owner-pid (:owner_pid launch-result)
        anchor-pid (:anchor_pid launch-result)
        workload-pid (:workload_pid launch-result)
        process-group (:process_group launch-result)
        adoption-path (:adoption_path launch-result)]
    (try
      (when-not (and launch-result
                     (= (str generation) (:generation launch-result))
                     (every? pos-int?
                             [owner-pid anchor-pid workload-pid process-group])
                     (= anchor-pid process-group)
                     (= control-socket (:control_socket launch-result))
                     (= result-path (:result_path launch-result))
                     (= application-result-path
                        (:application_result_path launch-result))
                     (= (:seon.dev.process/shutdown-grace-ms spec)
                        (:shutdown_grace_ms launch-result))
                     (string? adoption-path))
        (throw (ex-info "Failed to launch a detached Seon process."
                        {:seon.dev.process/id id
                         :seon.dev.process/error (str/trim (:err result))})))
      (let [start-instants
            (loop [attempt 0]
              (let [values (mapv state/process-start-instant
                                 [owner-pid anchor-pid workload-pid])]
                (if (every? string? values)
                  values
                  (when (< attempt 50)
                    (Thread/sleep 10)
                    (recur (inc attempt))))))]
        (when-not start-instants
          (throw
           (ex-info "Containment exited before its identity was recorded."
                    {:seon.dev.process/id id
                     :seon.dev.process/owner-pid owner-pid
                     :seon.dev.process/anchor-pid anchor-pid
                     :seon.dev.process/workload-pid workload-pid})))
        (let [[owner-start anchor-start workload-start] start-instants
              record
              {:seon.dev.process/id id
               :seon.dev.process/pid owner-pid
               :seon.dev.process/start-instant owner-start
               :seon.dev.process/process-group process-group
               :seon.dev.process/argv (:seon.dev.process/argv spec)
               :seon.dev.process/environment-digest
               (environment-digest (:seon.dev.process/environment spec))
               :seon.dev.process/artifact-digest
               (:seon.dev.process/artifact-digest spec)
               :seon.dev.process/target :seon.dev.target/development
               :seon.dev.process/started-at (str (Instant/now))
               :seon.dev.process/log (str log)
               :seon.dev.process/containment
               {:seon.dev.process.containment/generation generation
                :seon.dev.process.containment/owner-pid owner-pid
                :seon.dev.process.containment/owner-start-instant owner-start
                :seon.dev.process.containment/anchor-pid anchor-pid
                :seon.dev.process.containment/anchor-start-instant anchor-start
                :seon.dev.process.containment/process-group process-group
                :seon.dev.process.containment/workload-pid workload-pid
                :seon.dev.process.containment/workload-start-instant
                workload-start
                :seon.dev.process.containment/shutdown-grace-ms
                (:seon.dev.process/shutdown-grace-ms spec)
                :seon.dev.process.containment/control-socket control-socket
                :seon.dev.process.containment/adoption-path adoption-path
                :seon.dev.process.containment/result-path result-path}}
              record
              (cond-> record
                application-result-path
                (assoc-in [:seon.dev.process/containment
                           :seon.dev.process.containment/application-result-path]
                          application-result-path))]
          (write-process! config id record)
          (adopt-containment! record)
          record))
      (catch Throwable error
        (when launch-result
          (abort-unpublished-containment! id launch-result)
          (when (= (str generation)
                   (some-> (read-process config id)
                           :seon.dev.process/containment
                           :seon.dev.process.containment/generation str))
            (clear-process! config id))
          (fs/delete-tree containment-dir {:force true}))
        (throw error)))))

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

(defn- monotonic-ms [] (quot (System/nanoTime) 1000000))

(defn- phase-deadline [operation-deadline phase-budget-ms]
  (min (or operation-deadline Long/MAX_VALUE)
       (+ (monotonic-ms) phase-budget-ms)))

(defn- socket-line!
  ([path request] (socket-line! path request nil))
  ([path request operation-deadline]
   (let [deadline (phase-deadline operation-deadline 2000)
         address (UnixDomainSocketAddress/of path)]
     (when-not (pos? (- deadline (monotonic-ms)))
       (throw (ex-info "The containment control deadline expired."
                       {:seon.dev.process/deadline-ms deadline})))
     (with-open [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
       (.configureBlocking channel false)
       (.connect channel address)
       (loop []
         (when-not (.finishConnect channel)
           (when (>= (monotonic-ms) deadline)
             (throw
              (ex-info
               "Timed out connecting to containment owner."
               {:seon.dev.process.containment/control-socket path})))
           (Thread/sleep 10)
           (recur)))
       (let [output (ByteBuffer/wrap
                     (.getBytes (str request "\n") StandardCharsets/UTF_8))]
        (loop []
          (when (.hasRemaining output)
            (.write channel output)
            (when (>= (monotonic-ms) deadline)
              (throw (ex-info "Timed out writing to containment owner."
                              {:seon.dev.process.containment/control-socket path})))
            (recur))))
      (let [input (ByteBuffer/allocate 512)]
        (loop []
          (let [read-count (.read channel input)]
            (cond
              (pos? read-count)
              (let [text (String. (.array input) 0 (.position input)
                                  StandardCharsets/UTF_8)]
                (if (str/includes? text "\n")
                  (first (str/split-lines text))
                  (recur)))
              (neg? read-count) nil
              (>= (monotonic-ms) deadline)
              (throw (ex-info "Timed out awaiting containment acknowledgement."
                              {:seon.dev.process.containment/control-socket path}))
              :else (do (Thread/sleep 10) (recur))))))))))

(defn- terminal-result [containment]
  (let [path (:seon.dev.process.containment/result-path containment)]
    (when (and path (fs/regular-file? path))
      (try (json/parse-string (slurp path) true)
           (catch Throwable _ nil)))))

(defn- matching-terminal? [containment result]
  (and (= (str (:seon.dev.process.containment/generation containment))
          (:generation result))
       (= "drained" (:status result))
       (or (not (contains? result :trigger))
           (contains? #{"requested" "workload-exit"} (:trigger result)))
       (= -9 (:anchor_exit result))))

(defn- normalized-terminal [containment result]
  (cond->
   {:seon.dev.process.containment/generation
    (:seon.dev.process.containment/generation containment)
    :seon.dev.process.containment/status
    :seon.dev.process.containment.status/drained
    :seon.dev.process.containment/anchor-exit (:anchor_exit result)}
    (contains? result :trigger)
    (assoc :seon.dev.process.containment/trigger
           (case (:trigger result)
             "requested" :seon.dev.process.containment.trigger/requested
             "workload-exit"
             :seon.dev.process.containment.trigger/workload-exit))))

(defn- read-single-edn [text]
  (with-open [reader (PushbackReader. (StringReader. text))]
    (let [eof (Object.)
          options {:eof eof
                   :readers {}
                   :default
                   (fn [tag _]
                     (throw (ex-info "Tagged literals are not terminal data."
                                     {:seon.dev.process/tag tag})))}
          value (edn/read options reader)]
      (when (identical? eof value)
        (throw (ex-info "The application terminal result is blank." {})))
      (when-not (identical? eof (edn/read options reader))
        (throw (ex-info "The application terminal result has trailing data."
                        {})))
      value)))

(defn- writer-application-evidence [containment terminal]
  (when (:seon.dev.process.containment/application-result-path containment)
    (let [capture (:application_result terminal)
          capture-status (:status capture)
          capture-status-keyword
          (case capture-status
            "captured" :seon.dev.process.application-capture/captured
            "missing" :seon.dev.process.application-capture/missing
            "read-error" :seon.dev.process.application-capture/read-error
            "oversized" :seon.dev.process.application-capture/oversized
            "invalid-utf8" :seon.dev.process.application-capture/invalid-utf8
            :seon.dev.process.application-capture/missing-capture)
          capture-error
          (case capture-status
            "missing" :seon.dev.process.application-error/missing
            "read-error" :seon.dev.process.application-error/read-error
            "oversized" :seon.dev.process.application-error/oversized
            "invalid-utf8" :seon.dev.process.application-error/invalid-utf8
            :seon.dev.process.application-error/missing-capture)
          capture-metadata
          (cond->
            {:seon.dev.process.application-capture/status
             capture-status-keyword}
            (and (string? (:sha256 capture))
                 (re-matches #"[0-9a-f]{64}" (:sha256 capture)))
            (assoc :seon.dev.process.application-capture/sha256
                   (:sha256 capture))
            (string? (:edn capture))
            (assoc :seon.dev.process.application-capture/bytes
                   (alength (.getBytes ^String (:edn capture)
                                      StandardCharsets/UTF_8)))
            (string? (:error capture))
            (assoc :seon.dev.process.application-capture/error
                   (subs (:error capture)
                         0 (min 4096 (count (:error capture))))))
          failure
          (fn [reason]
            {:seon.dev.process/application-error reason
             :seon.dev.process/application-capture capture-metadata})]
      (if-not (= "captured" capture-status)
        (failure capture-error)
        (let [text (:edn capture)
              digest (:sha256 capture)]
          (cond
            (not= digest (sha256-text text))
            (failure :seon.dev.process.application-error/digest-mismatch)

            :else
            (try
              (let [value (read-single-edn text)
                    generation
                    (str (:seon.dev.process.containment/generation containment))]
                (cond
                  (not (db.protocol/valid-writer-terminal-result? value))
                  (failure :seon.dev.process.application-error/invalid-schema)

                  (not= generation (:seon.db.terminal/generation value))
                  (failure
                   :seon.dev.process.application-error/generation-mismatch)

                  :else
                  {:seon.dev.process/application-result value}))
              (catch Throwable _
                (failure
                 :seon.dev.process.application-error/malformed-edn)))))))))

(defn- await-terminal!
  ([record] (await-terminal! record nil))
  ([record operation-deadline]
   (let [containment (:seon.dev.process/containment record)
         deadline
         (phase-deadline
          operation-deadline
          (+ (:seon.dev.process.containment/shutdown-grace-ms containment)
             10000))]
     (loop []
       (let [result (terminal-result containment)
             owner-alive? (state/process-identity-alive?
                           (containment-identity containment :owner))]
         (cond
           (and (matching-terminal? containment result) (not owner-alive?))
           result

           (>= (monotonic-ms) deadline)
           (throw
            (ex-info
             "Containment drain did not produce a matching terminal result."
             {:seon.dev.process/status
              :seon.dev.process.status/containment-uncertain
              :seon.dev.process/id (:seon.dev.process/id record)
              :seon.dev.process/containment containment
              :seon.dev.process/terminal-result result}))

           :else (do (Thread/sleep 25) (recur))))))))

(defn- adopt-containment! [record]
  (let [containment (:seon.dev.process/containment record)
        generation
        (str (:seon.dev.process.containment/generation containment))
        response
        (socket-line!
         (:seon.dev.process.containment/control-socket containment)
         (str "adopt " generation))]
    (when-not (= (str "adopted " generation) response)
      (throw
       (ex-info "Containment owner rejected managed-record adoption."
                {:seon.dev.process/id (:seon.dev.process/id record)
                 :seon.dev.process/response response})))
    (when-not (containment-live? record)
      (throw
       (ex-info "Containment adoption was not published for this generation."
                {:seon.dev.process/id (:seon.dev.process/id record)
                 :seon.dev.process/containment containment})))
    record))

(defn- abort-unpublished-containment! [id launch-result]
  (let [generation (parse-uuid (:generation launch-result))
        owner-pid (:owner_pid launch-result)
        owner-start (state/process-start-instant owner-pid)
        containment
        {:seon.dev.process.containment/generation generation
         :seon.dev.process.containment/owner-pid owner-pid
         :seon.dev.process.containment/owner-start-instant owner-start
         :seon.dev.process.containment/control-socket
         (:control_socket launch-result)
         :seon.dev.process.containment/result-path (:result_path launch-result)
         :seon.dev.process.containment/shutdown-grace-ms
         (:shutdown_grace_ms launch-result)}
        record {:seon.dev.process/id id
                :seon.dev.process/containment containment}]
    (when owner-start
      (try
        (socket-line! (:seon.dev.process.containment/control-socket containment)
                      (str "drain " generation))
        (catch Throwable _)))
    (await-terminal! record)))

(defn- drain-containment!
  ([record] (drain-containment! record nil))
  ([record operation-deadline]
   (let [containment (:seon.dev.process/containment record)
         existing (terminal-result containment)]
     (when-not containment
       (throw
        (ex-info "Managed process has no generation-bound containment owner."
                 {:seon.dev.process/status
                  :seon.dev.process.status/containment-uncertain
                  :seon.dev.process/id (:seon.dev.process/id record)})))
     (if (matching-terminal? containment existing)
       (await-terminal! record operation-deadline)
       (do
         (when-not (state/process-identity-alive?
                    (containment-identity containment :owner))
           (throw
            (ex-info "Containment owner disappeared without a terminal result."
                     {:seon.dev.process/status
                      :seon.dev.process.status/containment-uncertain
                      :seon.dev.process/id (:seon.dev.process/id record)
                      :seon.dev.process/containment containment})))
         (let [generation
               (str (:seon.dev.process.containment/generation containment))
               response
               (socket-line!
                (:seon.dev.process.containment/control-socket containment)
                (str "drain " generation)
                operation-deadline)]
           (when-not (= (str "accepted " generation) response)
             (throw
              (ex-info "Containment owner rejected the drain generation."
                       {:seon.dev.process/status
                        :seon.dev.process.status/containment-uncertain
                        :seon.dev.process/id (:seon.dev.process/id record)
                        :seon.dev.process/response response}))))
         (await-terminal! record operation-deadline))))))

(defn- retire-live-legacy!
  ([record] (retire-live-legacy! record nil))
  ([record operation-deadline]
   (let [pid (:seon.dev.process/pid record)
         group (:seon.dev.process/process-group record)]
    (when-not (and (= pid group)
                   (state/process-identity-alive? record))
      (throw
       (ex-info "A legacy process record cannot be retired safely."
                {:seon.dev.process/status
                 :seon.dev.process.status/containment-uncertain
                 :seon.dev.process/id (:seon.dev.process/id record)
                 :seon.dev.process/pid pid
                 :seon.dev.process/process-group group})))
    ;; The exact live session leader pins this legacy PGID. Use one immediate
    ;; hard inverse; a grace interval could let the leader exit and make a
    ;; later numeric group signal ambiguous.
    (when-not (zero? (:exit (group-command "-KILL" group)))
      (throw
       (ex-info "The live legacy process group rejected retirement."
                {:seon.dev.process/status
                 :seon.dev.process.status/containment-uncertain
                 :seon.dev.process/id (:seon.dev.process/id record)
                 :seon.dev.process/process-group group})))
    (let [deadline (phase-deadline operation-deadline 5000)]
      (loop []
        (when (and (group-present? group) (< (monotonic-ms) deadline))
          (Thread/sleep 10)
          (recur))))
    (when (group-present? group)
      (throw
       (ex-info "The legacy process group did not become absent after retirement."
                {:seon.dev.process/status
                 :seon.dev.process.status/containment-uncertain
                 :seon.dev.process/id (:seon.dev.process/id record)}))))))

(def ^:private no-process-expectation
  ::no-process-expectation)

(defn stop!
  "Drain one exact containment generation and return its terminal evidence."
  ([config id] (stop! config id no-process-expectation nil))
  ([config id expected-record] (stop! config id expected-record nil))
  ([config id expected-record operation-deadline]
   (let [record (read-process config id)]
     (when (and (not= no-process-expectation expected-record)
                (not= expected-record record))
       (throw
        (ex-info "The selected process generation changed before drain."
                 {:seon.dev.process/status
                  :seon.dev.process.status/containment-uncertain
                  :seon.dev.process/id id
                  :seon.dev.process/expected-record expected-record
                  :seon.dev.process/current-record record})))
     (when (and (nil? record) (accepting-unmanaged? config id))
       (throw
        (ex-info "A process door is accepting without a managed record."
                 {:seon.dev.process/status
                  :seon.dev.process.status/containment-uncertain
                  :seon.dev.process/id id})))
     (let [result
          (when record
            (if-let [containment (:seon.dev.process/containment record)]
              (let [terminal (drain-containment! record operation-deadline)]
                (merge
                 {:seon.dev.process/id id
                  :seon.dev.process/terminal
                  (normalized-terminal containment terminal)}
                 (writer-application-evidence containment terminal)))
              (do
                (retire-live-legacy! record operation-deadline)
                {:seon.dev.process/id id
                 :seon.dev.process/legacy-retired? true})))]
      (when record
        (clear-process! config id)
        (when-let [result-path
                   (get-in record [:seon.dev.process/containment
                                   :seon.dev.process.containment/result-path])]
          (fs/delete-tree (fs/parent result-path) {:force true})))
      ;; A missing/reused state record is not authority over a live listener.
      ;; Preserve its breadcrumb so the next reconciliation can report the
      ;; ownership conflict instead of unlinking evidence it does not own.
      (when-not (accepting-unmanaged? config id)
        (clear-readiness! config id))
       result))))

(def ^:private quiesce-path "/_seon/operator/quiesce")
(def ^:private lifecycle-response-limit (* 1024 1024))
(def ^:private default-turn-timeout-ms 900000)
(def ^:private lifecycle-reserve-ms 120000)
(def ^:private operations
  #{:seon.dev.process.operation/down
    :seon.dev.process.operation/restart
    :seon.dev.process.operation/rebuild-readers
    :seon.dev.process.operation/rebuild-writer
    :seon.dev.process.operation/reset
    :seon.dev.process.operation/retained-close
    :seon.dev.process.operation/retained-restart})

(def clean-or-force-request-schema
  [:map {:closed true}
   [:seon.dev.process/configuration config/configuration-schema]
   [:seon.dev.process/operation (into [:enum] operations)]
   [:seon.dev.process/targets
    [:set {:min 1} (into [:enum] target-processes)]]])

(def ^:private containment-terminal-schema
  [:map {:closed true}
   [:seon.dev.process.containment/generation uuid?]
   [:seon.dev.process.containment/status
    [:= :seon.dev.process.containment.status/drained]]
   [:seon.dev.process.containment/trigger {:optional true}
    [:enum :seon.dev.process.containment.trigger/requested
     :seon.dev.process.containment.trigger/workload-exit]]
   [:seon.dev.process.containment/anchor-exit :int]])

(def ^:private application-capture-schema
  [:map {:closed true}
   [:seon.dev.process.application-capture/status
    [:enum :seon.dev.process.application-capture/captured
     :seon.dev.process.application-capture/missing
     :seon.dev.process.application-capture/read-error
     :seon.dev.process.application-capture/oversized
     :seon.dev.process.application-capture/invalid-utf8
     :seon.dev.process.application-capture/missing-capture]]
   [:seon.dev.process.application-capture/sha256 {:optional true}
    [:re #"[0-9a-f]{64}"]]
   [:seon.dev.process.application-capture/bytes {:optional true}
    [:int {:min 0 :max 1048576}]]
   [:seon.dev.process.application-capture/error {:optional true}
    [:string {:max 4096}]]])

(def ^:private stop-result-schema
  [:map {:closed true}
   [:seon.dev.process/id (into [:enum] target-processes)]
   [:seon.dev.process/classification
    [:enum :seon.dev.process.classification/clean
     :seon.dev.process.classification/forced
     :seon.dev.process.classification/absent]]
   [:seon.dev.process/terminal {:optional true} containment-terminal-schema]
   [:seon.dev.process/application-result {:optional true}
    [:or ::runtime.lifecycle/quiesce-response
     ::db.protocol/writer-terminal-result]]
   [:seon.dev.process/application-error {:optional true} qualified-keyword?]
   [:seon.dev.process/application-capture {:optional true}
    application-capture-schema]
   [:seon.dev.process/application-error-message {:optional true}
    [:string {:max 4096}]]
   [:seon.dev.process/legacy-retired? {:optional true} :boolean]
   [:seon.dev.process/reason {:optional true} qualified-keyword?]])

(def clean-or-force-result-schema
  [:map {:closed true}
   [:seon.dev.process/operation (into [:enum] operations)]
   [:seon.dev.process/classification
    [:enum :seon.dev.process.classification/clean
     :seon.dev.process.classification/forced
     :seon.dev.process.classification/absent]]
   [:seon.dev.process/budget-ms [:int {:min 1}]]
   [:seon.dev.process/elapsed-ms [:int {:min 0}]]
   [:seon.dev.process/results [:vector {:min 1} stop-result-schema]]])

(defn- selected-turn-timeout-ms [config]
  (let [configured
        (some-> (get-in config [:seon.dev.config/environment
                                "SEON_TURN_TIMEOUT_MS"])
                parse-long)]
    (if (and configured (pos-int? configured))
      configured
      default-turn-timeout-ms)))

(defn- remaining-ms [deadline]
  (max 0 (- deadline (monotonic-ms))))

(defn- read-bounded-text [stream]
  (when stream
    (with-open [input stream
                output (ByteArrayOutputStream.)]
      (let [buffer (byte-array 8192)]
        (loop [total 0]
          (let [read-count (.read input buffer)]
            (when (pos? read-count)
              (let [next-total (+ total read-count)]
                (when (> next-total lifecycle-response-limit)
                  (throw (ex-info "The lifecycle response exceeded its bound."
                                  {:seon.dev.process/response-bytes
                                   next-total})))
                (.write output buffer 0 read-count)
                (recur next-total)))))
        (.toString output StandardCharsets/UTF_8)))))

(defn- pod-port-file [config]
  (or (get-in config [:seon.dev.config/launch-descriptor
                      ::launch/process ::launch/http-port-file])
      (:seon.dev.config/http-port-file config)))

(defn- read-port [path]
  (when (and path (fs/regular-file? path))
    (with-open [file (RandomAccessFile. (str path) "r")]
      (let [length (.length file)]
        (when (> length 32)
          (throw (ex-info "The managed pod port file is oversized."
                          {:seon.dev.process/http-port-file path
                           :seon.dev.process/port-file-bytes length})))
        (let [content (byte-array (int length))]
          (.readFully file content)
          (String. content StandardCharsets/UTF_8))))))

(defn- bounded-quiesce-post! [config deadline]
  (let [port-file (pod-port-file config)
        port (some-> (read-port port-file) str/trim parse-long)]
    (when-not (pos-int? port)
      (throw (ex-info "The managed pod has no descriptor-owned HTTP port."
                      {:seon.dev.process/http-port-file port-file})))
    (let [remaining (remaining-ms deadline)]
      (when-not (pos? remaining)
        (throw (ex-info "The lifecycle response deadline expired."
                        {:seon.dev.process/deadline-ms deadline})))
      (let [connection ^HttpURLConnection
            (.openConnection
             (URL. (str "http://127.0.0.1:" port quiesce-path)))]
        (try
          (.setRequestMethod connection "POST")
          (.setConnectTimeout connection (int (min 1000 remaining)))
          (.setReadTimeout connection (int (min Integer/MAX_VALUE remaining)))
          (.setInstanceFollowRedirects connection false)
          (.setDoOutput connection true)
          (.setFixedLengthStreamingMode connection 0)
          (.setRequestProperty connection "Content-Type"
                               "application/edn; charset=utf-8")
          (.setRequestProperty connection "Accept" "application/edn")
          (with-open [output (.getOutputStream connection)] (.flush output))
          (let [read-budget (remaining-ms deadline)
                _ (when-not (pos? read-budget)
                    (throw (ex-info "The lifecycle read deadline expired."
                                    {:seon.dev.process/deadline-ms deadline})))
                _ (.setReadTimeout
                   connection (int (min Integer/MAX_VALUE read-budget)))
                status (.getResponseCode connection)
                stream (if (< status 400)
                         (.getInputStream connection)
                         (.getErrorStream connection))
                text (or (read-bounded-text stream) "")
                value (read-single-edn text)]
            (when-not (contains? #{200 409 500 503} status)
              (throw (ex-info "The lifecycle endpoint returned an invalid status."
                              {:seon.dev.process/http-status status})))
            (when-not (m/validate ::runtime.lifecycle/quiesce-response value)
              (throw (ex-info "The lifecycle endpoint returned invalid data."
                              {:seon.dev.process/http-status status
                               :seon.dev.process/response value})))
            (when-not (= (= 200 status)
                         (true? (:seon.client/quiesced? value)))
              (throw
               (ex-info "The lifecycle status contradicts its typed result."
                        {:seon.dev.process/http-status status
                         :seon.dev.process/response value})))
            value)
          (finally (.disconnect connection)))))))

(defn- pod-application-evidence [config record deadline]
  (if-not (containment-live? record)
    {:seon.dev.process/application-error
     :seon.dev.process.application-error/ownership-changed}
    (try
      (let [expected-generation
            (str (get-in record [:seon.dev.process/containment
                                 :seon.dev.process.containment/generation]))
            first-response (bounded-quiesce-post! config deadline)
            _ (when-not (= expected-generation
                           (::runtime.lifecycle/process-generation
                            first-response))
                (throw
                 (ex-info "The pod lifecycle result crossed generations."
                          {:seon.dev.process/expected-generation
                           expected-generation
                           :seon.dev.process/response first-response})))
            response
            (if (and (false? (:seon.client/quiesced? first-response))
                     (containment-live? record)
                     (pos? (remaining-ms deadline)))
              (bounded-quiesce-post! config deadline)
              first-response)
            _ (when-not (= expected-generation
                           (::runtime.lifecycle/process-generation response))
                (throw
                 (ex-info "The retried pod result crossed generations."
                          {:seon.dev.process/expected-generation
                           expected-generation
                           :seon.dev.process/response response})))]
        {:seon.dev.process/application-result response})
      (catch Throwable error
        {:seon.dev.process/application-error
         :seon.dev.process.application-error/quiesce-failed
         :seon.dev.process/application-error-message
         (subs (str error) 0 (min 4096 (count (str error))))}))))

(defn- clean-writer-result? [result]
  (let [application (:seon.dev.process/application-result result)
        response (:seon.db.terminal/stop-response application)]
    (and (true? (:seon.db.terminal/completed? application))
         (true? (:seon.db.server/stopped? response))
         (every? :seon.db.registry/released?
                 (:seon.db.server/release-results response)))))

(defn- classify-stop-result [id stop-result application-evidence]
  (if-not stop-result
    {:seon.dev.process/id id
     :seon.dev.process/classification
     :seon.dev.process.classification/absent}
    (let [result (merge stop-result application-evidence)
          trigger (get-in result [:seon.dev.process/terminal
                                  :seon.dev.process.containment/trigger])
          clean?
          (and (= :seon.dev.process.containment.trigger/requested trigger)
               (case id
                 :seon.dev.process/watcher true
                 :seon.dev.process/pod
                 (true? (get-in result [:seon.dev.process/application-result
                                        :seon.client/quiesced?]))
                 :seon.dev.process/writer (clean-writer-result? result)
                 false))]
      (cond->
        (assoc result :seon.dev.process/classification
               (if clean?
                 :seon.dev.process.classification/clean
                 :seon.dev.process.classification/forced))
        (not clean?)
        (assoc :seon.dev.process/reason
               (cond
                 (:seon.dev.process/legacy-retired? result)
                 :seon.dev.process.reason/legacy-retirement
                 (= :seon.dev.process.containment.trigger/workload-exit trigger)
                 :seon.dev.process.reason/unexpected-exit
                 (:seon.dev.process/application-error result)
                 (:seon.dev.process/application-error result)
                 :else :seon.dev.process.reason/incomplete-application))))))

(defn- stop-selected! [configuration deadline ordered]
  (loop [remaining ordered
         results []]
    (if-let [id (first remaining)]
      (let [result
            (try
              (let [record (read-process configuration id)
                    application-evidence
                    (when (and (= pod-id id) record)
                      (pod-application-evidence configuration record deadline))
                    stop-result (stop! configuration id record deadline)]
                (classify-stop-result id stop-result application-evidence))
              (catch Throwable error
                (throw
                 (ex-info
                  "A managed process could not prove containment absence."
                  (assoc (ex-data error)
                         :seon.dev.process/classification
                         :seon.dev.process.classification/containment-uncertain
                         :seon.dev.process/results results
                         :seon.dev.process/id id)
                  error))))]
        (recur (next remaining) (conj results result)))
      results)))

(defn clean-or-force!
  "Stop selected processes in dependency-safe order with honest evidence."
  {:malli/schema [:=> [:cat clean-or-force-request-schema]
                  clean-or-force-result-schema]}
  [request]
  (validate! clean-or-force-request-schema request
             "The clean-or-force request is invalid."
             :seon.dev.process/explanation)
  (let [{:seon.dev.process/keys [configuration operation targets]} request
        started (monotonic-ms)
        budget (+ (selected-turn-timeout-ms configuration)
                  lifecycle-reserve-ms)
        deadline (+ started budget)
        selected (set targets)
        ordered (filterv selected [pod-id writer-id watcher-id])
        results (stop-selected! configuration deadline ordered)
        classifications (set (map :seon.dev.process/classification results))
        classification
        (cond
          (contains? classifications :seon.dev.process.classification/forced)
          :seon.dev.process.classification/forced
          (= #{:seon.dev.process.classification/absent} classifications)
          :seon.dev.process.classification/absent
          :else :seon.dev.process.classification/clean)]
    {:seon.dev.process/operation operation
     :seon.dev.process/classification classification
     :seon.dev.process/budget-ms budget
     :seon.dev.process/elapsed-ms (- (monotonic-ms) started)
     :seon.dev.process/results results}))

(defn- require-startable-absence! [config id record]
  (when record
    (throw
     (ex-info
      "Refusing to replace a managed process without clean-or-force evidence."
      {:seon.dev.process/id id
       :seon.dev.process/status
       :seon.dev.process.status/managed-process-present
       :seon.dev.process/recorded-status (process-status record)
       :seon.dev.process/required-transition
       :seon.dev.process.transition/clean-or-force})))
  (when (accepting-unmanaged? config id)
    (throw
     (ex-info "An unmanaged listener blocks the Seon process."
              {:seon.dev.process/id id})))
  nil)

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
         (require-startable-absence! config id record)
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
    (require-startable-absence! config watcher-id record)
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

(defn- reported-process-status [record]
  (let [recorded (process-status record)
        containment (:seon.dev.process/containment record)]
    (cond
      (nil? record) recorded
      (nil? containment)
      (if (= :seon.dev.process.status/alive recorded)
        :seon.dev.process.status/legacy-live
        :seon.dev.process.status/containment-uncertain)
      (containment-live? record) :seon.dev.process.status/alive
      (and (matching-terminal? containment (terminal-result containment))
           (not (state/process-identity-alive?
                 (containment-identity containment :owner))))
      :seon.dev.process.status/drained
      :else :seon.dev.process.status/containment-uncertain)))

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
                           process-state
                           (if foreign?
                             :seon.dev.process.status/foreign
                             (reported-process-status record))]
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
