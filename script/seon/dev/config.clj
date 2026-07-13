(ns seon.dev.config
  "Host configuration for the Seon development operator."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [malli.core :as m]))

(def configuration-schema
  [:map
   [:seon.dev.config/root :string]
   [:seon.dev.config/source-checkout? :boolean]
   [:seon.dev.config/environment [:map-of :string :string]]
   [:seon.dev.config/process-dir :string]
   [:seon.dev.config/log-dir :string]
   [:seon.dev.config/cluster-dir :string]
   [:seon.dev.config/cluster-name :string]
   [:seon.dev.config/request-socket :string]
   [:seon.dev.config/publish-socket :string]
   [:seon.dev.config/http-port [:int {:min 0 :max 65535}]]
   [:seon.dev.config/http-port-file :string]
   [:seon.dev.config/writer-repl-port [:int {:min 0 :max 65535}]]
   [:seon.dev.config/writer-repl-port-file :string]
   [:seon.dev.config/client-output :string]
   [:seon.dev.config/writer-output :string]
   [:seon.dev.config/artifact-manifest :string]])

(defn- validate-configuration! [configuration]
  (when-not (m/validate configuration-schema configuration)
    (throw (ex-info "The derived Seon host configuration is invalid."
                    {:seon.dev.config/explanation
                     (mapv #(select-keys % [:path :in :type])
                           (:errors (m/explain configuration-schema
                                               configuration)))})))
  configuration)

(defn- unquote-value [value]
  (let [value (str/trim value)]
    (if (and (<= 2 (count value))
             (#{\' \"} (first value))
             (= (first value) (last value)))
      (subs value 1 (dec (count value)))
      value)))

(defn- dotenv-entry [line]
  (let [line (str/trim line)
        line (if (str/starts-with? line "export ")
               (subs line 7)
               line)]
    (when (and (not (str/blank? line))
               (not (str/starts-with? line "#")))
      (when-let [[_ env-key value] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)=(.*)" line)]
        [env-key (unquote-value value)]))))

(defn- dotenv [root]
  (let [path (fs/path root ".env")]
    (if (fs/regular-file? path)
      (into {} (keep dotenv-entry) (str/split-lines (slurp (str path))))
      {})))

(defn- command-result [argv]
  (try
    (process/sh {:continue true :out :string :err :string :cmd argv})
    (catch Throwable _
      {:exit 127 :out "" :err ""})))

(defn- java-major [java]
  (when (and java (fs/executable? java))
    (let [{:keys [exit out err]} (command-result [(str java) "-version"])
          text (str out err)]
      (when (zero? exit)
        (some-> (re-find #"version \"([0-9]+)" text) second parse-long)))))

(defn- java-home-candidates [environment]
  (let [configured (get environment "JAVA_HOME")
        mac-home (when (fs/executable? "/usr/libexec/java_home")
                   (let [{:keys [exit out]}
                         (command-result ["/usr/libexec/java_home" "-v" "25"])]
                     (when (zero? exit) (str/trim out))))
        globbed (concat
                  (when (fs/directory? "/usr/lib/jvm")
                    (map (comp str fs/parent fs/parent)
                         (fs/glob "/usr/lib/jvm" "*/bin/java")))
                  (let [sdk (fs/path (System/getProperty "user.home")
                                     ".sdkman/candidates/java")]
                    (when (fs/directory? sdk)
                      (map (comp str fs/parent fs/parent)
                           (fs/glob sdk "*/bin/java")))))]
    (distinct (remove str/blank? (concat [configured mac-home] globbed)))))

(defn- select-java-home [environment]
  (or (some (fn [home]
              (when (= 25 (java-major (fs/path home "bin/java"))) home))
            (java-home-candidates environment))
      (get environment "JAVA_HOME")))

(defn- child-environment [root]
  ;; The invoking environment wins over .env. The file is parsed as data and
  ;; is never executed as shell code.
  (let [environment (merge (dotenv root) (into {} (System/getenv)))
        java-home (select-java-home environment)
        environment (cond-> environment
                      java-home
                      (assoc "JAVA_HOME" java-home
                             "JAVA_CMD" (str (fs/path java-home "bin/java")))

                      java-home
                      (update "PATH" #(str (fs/path java-home "bin") ":" %))

                      true
                      (assoc "SEON_SHELL" (get environment "SEON_SHELL" "1")
                             "SEON_WEB" (get environment "SEON_WEB" "1")
                             ;; `bin/seon` is the source-checkout development
                             ;; operator. Render failures must surface at their
                             ;; first boundary here; a future packaged runtime
                             ;; can explicitly set this to 0 for graceful mode.
                             "SEON_RENDER_STRICT"
                             (get environment "SEON_RENDER_STRICT" "1")))
        embed (get environment "SEON_EMBED")]
    (cond-> environment
      (or (nil? embed) (str/blank? embed) (= "0" embed))
      (dissoc "SEON_EMBED"))))

(defn load!
  "Derive one immutable operator configuration from the host."
  [root]
  (let [root (str (fs/normalize (fs/absolutize root)))
        environment (child-environment root)
        cluster-dir (get environment "SEON_CLUSTER_DIR"
                         (str (fs/path root "data/clusters/default")))
        cluster-name (str (fs/file-name cluster-dir))
        proc-dir (get environment "SEON_PROC_DIR"
                      (str (fs/path root "tmp/seon-operator")))
        log-dir (get environment "SEON_LOG_DIR"
                     (str (fs/path root "logs/operator")))
        req-sock (get environment "SEON_REQ_SOCK"
                      (str (fs/path root "tmp/seon-cluster-default-req.sock")))
        pub-sock (get environment "SEON_PUB_SOCK"
                      (str (fs/path root "tmp/seon-cluster-default-pub.sock")))
        port-file (get environment "SEON_PORT_FILE"
                       (str (fs/path root "tmp/seon-port")))
        writer-port-file
        (get environment "SEON_WRITER_REPL_PORT_FILE"
             (str (fs/path root (str "tmp/seon-writer-repl-port-" cluster-name))))
        environment (assoc environment
                      "SEON_CLUSTER_DIR" cluster-dir
                      "SEON_REQ_SOCK" req-sock
                      "SEON_PUB_SOCK" pub-sock
                      "SEON_PORT_FILE" port-file
                      "SEON_WRITER_REPL_PORT_FILE" writer-port-file
                      "SEON_FS_ROOT" root
                      "SEON_FS_READ_ONLY" "1")]
    (validate-configuration!
      {:seon.dev.config/root root
       :seon.dev.config/source-checkout? (and (fs/regular-file? (fs/path root "deps.edn"))
                                               (fs/regular-file? (fs/path root "shadow-cljs.edn")))
       :seon.dev.config/environment environment
       :seon.dev.config/process-dir proc-dir
       :seon.dev.config/log-dir log-dir
       :seon.dev.config/cluster-dir cluster-dir
       :seon.dev.config/cluster-name cluster-name
       :seon.dev.config/request-socket req-sock
       :seon.dev.config/publish-socket pub-sock
       :seon.dev.config/http-port (parse-long (get environment "SEON_PORT" "7890"))
       :seon.dev.config/http-port-file port-file
       :seon.dev.config/writer-repl-port
       (parse-long (get environment "SEON_WRITER_REPL_PORT" "7891"))
       :seon.dev.config/writer-repl-port-file writer-port-file
       :seon.dev.config/client-output
       (get environment "SEON_CLIENT_OUT" (str (fs/path root "out/client/main.js")))
       :seon.dev.config/writer-output
       (str (fs/path root "target/seon-database-server-standalone.jar"))
       :seon.dev.config/artifact-manifest
       (str (fs/path proc-dir "artifact.edn"))})))
