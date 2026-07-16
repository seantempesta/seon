(ns seon.dev.config
  "Host configuration for the Seon development operator."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.launch :as launch]))

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
   [:seon.dev.config/http-port [:int {:min 0 :max 65535}]]
   [:seon.dev.config/http-port-file :string]
   [:seon.dev.config/writer-repl-port [:int {:min 0 :max 65535}]]
   [:seon.dev.config/writer-repl-port-file :string]
   [:seon.dev.config/artifact-flavor
    [:enum :seon.dev.artifact.flavor/default
     :seon.dev.artifact.flavor/acme]]
   [:seon.dev.config/client-build-id :string]
   [:seon.dev.config/execution-build-id :string]
   [:seon.dev.config/shadow-cache-root :string]
   [:seon.dev.config/client-output :string]
   [:seon.dev.config/execution-output :string]
   [:seon.dev.config/writer-output :string]
   [:seon.dev.config/artifact-manifest :string]
   [:seon.dev.config/launch-descriptor :seon.launch/descriptor]])

(defn- validate-configuration! [configuration]
  (when-not (m/validate configuration-schema configuration)
    (throw (ex-info "The derived Seon host configuration is invalid."
                    {:seon.dev.config/explanation
                     (mapv #(select-keys % [:path :in :type])
                           (:errors (m/explain configuration-schema
                                               configuration)))})))
  configuration)

(defn select-launch-descriptor
  "Select one validated launch descriptor without changing artifact identity."
  {:malli/schema
   [:=>
    [:catn
     [:seon.dev.config/configuration configuration-schema]
     [:seon.dev.config/launch-descriptor :seon.launch/descriptor]]
    configuration-schema]}
  [configuration descriptor]
  (when-not (m/validate :seon.launch/descriptor descriptor)
    (throw
     (ex-info "The selected launch descriptor is invalid."
              {:seon.dev.config/launch-descriptor descriptor})))
  (let [configured
        [(:seon.dev.config/artifact-flavor configuration)
         (:seon.dev.config/client-build-id configuration)
         (:seon.dev.config/execution-build-id configuration)
         (:seon.dev.config/execution-output configuration)]
        selected
        [(get-in descriptor [::launch/runtime ::launch/artifact-flavor])
         (get-in descriptor [::launch/runtime ::launch/client-build-id])
         (get-in descriptor [::launch/runtime ::launch/execution-build-id])
         (get-in descriptor [::launch/runtime ::launch/execution-output])]]
    (when-not (= configured selected)
      (throw
       (ex-info "The launch descriptor selects another artifact."
                {:seon.dev.config/configured-artifact configured
                 :seon.dev.config/selected-artifact selected})))
    (validate-configuration!
     (assoc configuration :seon.dev.config/launch-descriptor descriptor))))

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

(def artifact-configuration-schema
  [:map
   [:seon.dev.config/artifact-flavor
    [:enum :seon.dev.artifact.flavor/default
     :seon.dev.artifact.flavor/acme]]
   [:seon.dev.config/client-build-id :string]
   [:seon.dev.config/execution-build-id :string]
   [:seon.dev.config/shadow-cache-root :string]
   [:seon.dev.config/client-output :string]
   [:seon.dev.config/execution-output :string]
   [:seon.dev.config/artifact-manifest-name :string]])

(def ^:private artifact-flavors
  {"default"
   {:seon.dev.config/artifact-flavor :seon.dev.artifact.flavor/default
    :seon.dev.config/client-build-id "client"
    :seon.dev.config/execution-build-id "execution"
    :seon.dev.config/shadow-cache-root ".shadow-cljs"
    :seon.dev.config/client-output "out/client/main.js"
    :seon.dev.config/execution-output "out/execution/main.js"
    :seon.dev.config/artifact-manifest-name "artifact.edn"}
   "acme"
   {:seon.dev.config/artifact-flavor :seon.dev.artifact.flavor/acme
    :seon.dev.config/client-build-id "acme-client"
    :seon.dev.config/execution-build-id "acme-execution"
    :seon.dev.config/shadow-cache-root "tmp/shadow/acme"
    :seon.dev.config/client-output "out-acme/client/main.js"
    :seon.dev.config/execution-output "out-acme/execution/main.js"
    :seon.dev.config/artifact-manifest-name "artifact-acme.edn"}})

(defn- root-path [root path]
  (let [path (fs/path path)]
    (str (fs/normalize (if (fs/absolute? path) path (fs/path root path))))))

(defn artifact-configuration
  "Artifact coordinates selected by an explicit flavor."
  {:malli/schema
   [:=> [:cat :string [:map-of :string :string]]
    artifact-configuration-schema]}
  [root environment]
  (let [flavor-name (get environment "SEON_ARTIFACT_FLAVOR" "default")
        target (or (get artifact-flavors flavor-name)
                   (throw
                     (ex-info "Unknown Seon artifact flavor."
                              {:seon.dev.config/artifact-flavor flavor-name
                               :seon.dev.config/known-artifact-flavors
                               (vec (sort (keys artifact-flavors)))})))
        expected-output (root-path root (:seon.dev.config/client-output target))
        expected-execution-output
        (root-path root (:seon.dev.config/execution-output target))
        configured-output (some->> (get environment "SEON_CLIENT_OUT")
                                   (root-path root))]
    (when (and configured-output (not= expected-output configured-output))
      (throw
        (ex-info "The client output does not match the selected artifact flavor."
                 {:seon.dev.config/artifact-flavor
                  (:seon.dev.config/artifact-flavor target)
                  :seon.dev.config/expected-client-output expected-output
                  :seon.dev.config/configured-client-output configured-output})))
    (-> target
        (update :seon.dev.config/shadow-cache-root #(root-path root %))
        (assoc :seon.dev.config/client-output expected-output
               :seon.dev.config/execution-output
               expected-execution-output))))

(defn artifact-configurations
  "Artifact coordinates for every supported development flavor."
  {:malli/schema
   [:=> [:cat :string] [:vector artifact-configuration-schema]]}
  [root]
  (mapv #(artifact-configuration root {"SEON_ARTIFACT_FLAVOR" %})
        (sort (keys artifact-flavors))))

(defn shadow-environment
  "Select the artifact flavor's Shadow server cache before JVM startup."
  {:malli/schema
   [:=> [:cat [:map-of :string :string] artifact-configuration-schema]
    [:map-of :string :string]]}
  [environment artifact]
  (if (= :seon.dev.artifact.flavor/default
         (:seon.dev.config/artifact-flavor artifact))
    environment
    (let [configured (get environment "SHADOW_CLJS")
          override (if (str/blank? configured)
                     {}
                     (edn/read-string {:default tagged-literal} configured))]
      (when-not (map? override)
        (throw (ex-info "SHADOW_CLJS must contain an EDN configuration map."
                        {:seon.dev.config/shadow-cljs configured})))
      (assoc environment "SHADOW_CLJS"
             (pr-str (assoc override :cache-root
                            (:seon.dev.config/shadow-cache-root artifact)))))))

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
                         (command-result ["/usr/libexec/java_home" "-v" "26"])]
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
  (if-let [home (some (fn [home]
                        (when (= 26 (java-major (fs/path home "bin/java"))) home))
                      (java-home-candidates environment))]
    (str (fs/canonicalize home))
    (throw (ex-info "Seon requires JDK 26; install it before starting."
                    {:seon.dev.config/required-java-major 26}))))

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
        artifact (artifact-configuration root environment)
        environment (shadow-environment environment artifact)
        cluster-dir (get environment "SEON_CLUSTER_DIR"
                         (str (fs/path root "data/clusters/default")))
        cluster-name (str (fs/file-name cluster-dir))
        proc-dir (get environment "SEON_PROC_DIR"
                      (str (fs/path root "tmp/seon-operator")))
        log-dir (get environment "SEON_LOG_DIR"
                     (str (fs/path root "logs/operator")))
        req-sock (get environment "SEON_REQ_SOCK"
                      (str (fs/path root "tmp/seon-cluster-default-req.sock")))
        port-file (get environment "SEON_PORT_FILE"
                       (str (fs/path root "tmp/seon-port")))
        writer-port-file
        (get environment "SEON_WRITER_REPL_PORT_FILE"
             (str (fs/path root (str "tmp/seon-writer-repl-port-" cluster-name))))
        environment (assoc environment
                      "SEON_CLUSTER_DIR" cluster-dir
                      "SEON_REQ_SOCK" req-sock
                      "SEON_PORT_FILE" port-file
                      "SEON_WRITER_REPL_PORT_FILE" writer-port-file
                      "SEON_FS_ROOT" root
                      "SEON_FS_READ_ONLY" "1")
        http-port (parse-long (get environment "SEON_PORT" "7890"))
        launch-descriptor
        (launch/default-descriptor
         {::launch/cluster-dir cluster-dir
          ::launch/artifact-flavor
          (:seon.dev.config/artifact-flavor artifact)
          ::launch/client-build-id
          (:seon.dev.config/client-build-id artifact)
          ::launch/execution-build-id
          (:seon.dev.config/execution-build-id artifact)
          ::launch/execution-output
          (:seon.dev.config/execution-output artifact)
          ::launch/request-socket-path req-sock
          ::launch/writer-repl-port-file writer-port-file
          ::launch/process-dir proc-dir
          ::launch/log-dir log-dir
          ::launch/http-port http-port
          ::launch/http-port-file port-file})]
    (validate-configuration!
      (merge
        (dissoc artifact :seon.dev.config/artifact-manifest-name)
        {:seon.dev.config/root root
         :seon.dev.config/source-checkout?
         (and (fs/regular-file? (fs/path root "deps.edn"))
              (fs/regular-file? (fs/path root "shadow-cljs.edn")))
         :seon.dev.config/environment environment
         :seon.dev.config/process-dir proc-dir
         :seon.dev.config/log-dir log-dir
         :seon.dev.config/cluster-dir cluster-dir
         :seon.dev.config/cluster-name cluster-name
         :seon.dev.config/request-socket req-sock
         :seon.dev.config/http-port http-port
         :seon.dev.config/http-port-file port-file
         :seon.dev.config/writer-repl-port
         (parse-long (get environment "SEON_WRITER_REPL_PORT" "0"))
         :seon.dev.config/writer-repl-port-file writer-port-file
         :seon.dev.config/writer-output
         (str (fs/path root "target/seon-database-server-standalone.jar"))
         :seon.dev.config/artifact-manifest
         (str (fs/path proc-dir
                       (:seon.dev.config/artifact-manifest-name artifact)))
         :seon.dev.config/launch-descriptor launch-descriptor}))))
