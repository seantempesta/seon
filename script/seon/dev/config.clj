(ns seon.dev.config
  "Host configuration for the Seon development operator."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.config.resolve :as config.resolve]
            [seon.dev.config-manifest :as config-manifest]
            [seon.dev.release :as release]
            [seon.launch :as launch])
  (:import [java.nio.file Files StandardCopyOption]
           [java.time ZoneId]))

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
   [:seon.dev.config/host-eval-socket {:optional true} :string]
   [:seon.dev.config/http-port [:int {:min 0 :max 65535}]]
   [:seon.dev.config/http-port-file :string]
   [:seon.dev.config/writer-repl-port [:int {:min 0 :max 65535}]]
   [:seon.dev.config/writer-repl-port-file :string]
   [:seon.dev.config/writer-max-heap
    {:optional true} [:string {:min 2 :max 8}]]
   [:seon.dev.config/artifact-flavor
    :qualified-keyword]
   [:seon.dev.config/test-build? :boolean]
   [:seon.dev.config/client-build-id :string]
   [:seon.dev.config/shadow-cache-root :string]
   [:seon.dev.config/client-output :string]
   [:seon.dev.config/writer-output :string]
   [:seon.dev.config/bun-executable {:optional true} :string]
   [:seon.dev.config/runtime-assets {:optional true} :string]
   [:seon.dev.config/program-source {:optional true} :string]
   [:seon.dev.config/detach-helper {:optional true} :string]
   [:seon.dev.config/containment-socket-dir {:optional true} :string]
   [:seon.dev.config/artifact-manifest :string]
   [:seon.dev.config/launch-descriptor :seon.launch/descriptor]])

(defn writer-max-heap
  "Return the one bounded writer heap selected by the operator configuration."
  [configuration]
  (or (when-let [heap-mb (get-in configuration
                                  [:seon.dev.config/operational-envelope
                                   :seon.config.database.writer/jvm-heap-mb])]
        (str heap-mb "m"))
      (:seon.dev.config/writer-max-heap configuration)))

(defn claim-driver-heap-mb
  "Return the resolved JVM heap ceiling in MiB."
  [configuration]
  (get-in configuration
          [:seon.dev.config/resolved-configuration
           :seon.config.claim-driver/jvm-heap-mb]
          4096))

(defn claim-driver-pool-wait-timeout-ms
  "Return the resolved database-pool wait ceiling."
  [configuration]
  (get-in configuration
          [:seon.dev.config/resolved-configuration
           :seon.config.claim-driver/database-pool-wait-timeout-ms]
          110000))

(defn pod-boot-stall-timeout-ms
  "Return the resolved pod boot-stall ceiling in milliseconds.

   Each concrete progress observation resets the interval, so total healthy
   boot duration is unbounded. The 300000 ms default protects operator startup
   ownership from a wedged pod and is calibrated above the longest silent
   phase in the measured 2026-07-23 257-second fresh paged boot. Failure names
   the owning config key."
  [configuration]
  (or
   (get-in configuration
           [:seon.dev.config/resolved-configuration
            :seon.config.operator/pod-boot-stall-timeout-ms])
   (throw
    (ex-info
     "Missing required operator limit :seon.config.operator/pod-boot-stall-timeout-ms."
     {:seon.config/missing
      :seon.config.operator/pod-boot-stall-timeout-ms}))))

(defn- validate-configuration! [configuration]
  (when-let [heap (writer-max-heap configuration)]
    (when-not (re-matches #"[1-9][0-9]*[kKmMgG]" heap)
      (throw (ex-info "The writer maximum heap must be a positive JVM size."
                      {:seon.dev.config/writer-max-heap heap}))))
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
         (:seon.dev.config/client-build-id configuration)]
        selected
        [(get-in descriptor [::launch/runtime ::launch/artifact-flavor])
         (get-in descriptor [::launch/runtime ::launch/client-build-id])]]
    (when-not (= configured selected)
      (throw
       (ex-info "The launch descriptor selects another artifact."
                {:seon.dev.config/configured-artifact configured
                 :seon.dev.config/selected-artifact selected})))
    (validate-configuration!
     (assoc configuration :seon.dev.config/launch-descriptor descriptor))))

(defn- database-born?
  [configuration]
  (let [database-path
        (or (get-in configuration [:seon.dev.config/launch-descriptor
                                   ::launch/database
                                   :seon.db.protocol/database-path])
            (some-> (:seon.dev.config/cluster-dir configuration)
                    (fs/path "db")))]
    (boolean
     (and database-path
          (fs/directory? database-path)
          (some fs/regular-file? (fs/list-dir database-path))))))

(defn- atomic-write-edn! [path value]
  (let [target (fs/path path)
        parent (fs/parent target)
        _ (fs/create-dirs parent)
        temporary (Files/createTempFile parent ".seon-config-" ".edn"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (spit (str temporary) (pr-str value))
    (Files/move temporary target
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (str target)))

(defn- next-launch-generation
  [process-dir]
  (loop [generation (System/currentTimeMillis)]
    (if (fs/exists? (fs/path process-dir
                             (str "launch-envelope-" generation ".edn")))
      (recur (inc generation))
      generation)))

(defn- hardware-observations []
  (let [cores (.availableProcessors (Runtime/getRuntime))
        system-memory-bytes
        (if (= "Mac OS X" (System/getProperty "os.name"))
          (parse-long (str/trim (:out (process/shell {:out :string}
                                                     "sysctl" "-n" "hw.memsize"))))
          (some->> (slurp "/proc/meminfo")
                   (re-find #"(?m)^MemTotal:\s+(\d+)\s+kB$") second parse-long (* 1024)))
        fd-soft-limit
        (parse-long (str/trim (:out (process/shell {:out :string}
                                                   "sh" "-c" "ulimit -n"))))]
    {:seon.hardware/cores cores
     :seon.hardware/system-memory-bytes system-memory-bytes
     :seon.hardware/fd-soft-limit fd-soft-limit}))

(defn select-manifest
  "Resolve the boot manifest once and attach its launch references."
  [configuration config-path]
  (let [root (:seon.dev.config/root configuration)
        environment
        (cond-> (:seon.dev.config/environment configuration)
          (str/blank?
           (get (:seon.dev.config/environment configuration)
                "SEON_HOST_TIMEZONE"))
          (assoc "SEON_HOST_TIMEZONE" (str (ZoneId/systemDefault))))
        born? (database-born? configuration)
        explicit (when config-path
                   (let [path (fs/path config-path)]
                     (str (fs/normalize (if (fs/absolute? path) path (fs/path root path))))))
        inherited (get environment "SEON_CONFIG")
        retained-path (str (fs/path (:seon.dev.config/cluster-dir configuration)
                                    "config" "applied.edn"))
        retained? (and (nil? explicit) (nil? inherited) born?
                       (fs/regular-file? retained-path))
        selected-path (or explicit inherited
                          (when retained? retained-path)
                          (str (fs/path root "config/system.edn")))
        reconcile? (boolean (or explicit inherited (not born?)))
        _ (when-not (fs/regular-file? selected-path)
            (throw (ex-info "The selected Seon config manifest does not exist."
                            {:seon.config/path selected-path})))
        manifest (if retained?
                   (edn/read-string (slurp selected-path))
                   (config.resolve/read-manifest selected-path environment))
        _ (when-not (m/validate :seon.config/manifest manifest)
            (throw (ex-info "The resolved Seon config manifest is invalid."
                            {:seon.config/path selected-path
                             :seon.config/explanation
                             (m/explain :seon.config/manifest manifest)})))
        hardware (hardware-observations)
        process-dir (or (:seon.dev.config/process-dir configuration)
                        (str (fs/path root "tmp/seon-operator")))
        generation (next-launch-generation process-dir)
        envelope (config.resolve/resolve-envelope manifest hardware generation)
        file-contents
        (into {}
              (keep (fn [path]
                      (let [selected (fs/path root path)]
                        (when (fs/regular-file? selected)
                          ; bb's slurp rejects a raw java.nio Path; the file's
                          ; established idiom is (slurp (str ...)).
                          [path (slurp (str selected))]))))
              (config.resolve/render-context-file-paths manifest))
        singleton
        (config.resolve/resolve-config-singleton
         manifest environment hardware file-contents)
        manifest-path (str (fs/path process-dir "resolved-manifest.edn"))
        envelope-path
        (str (fs/path process-dir (str "launch-envelope-" generation ".edn")))
        manifest-text (pr-str manifest)
        manifest-sha-256 (config-manifest/digest manifest-text)
        initialization-page-rows
        (:seon.config.database.initialization/page-rows singleton)
        _ (atomic-write-edn! manifest-path manifest)
        _ (atomic-write-edn! envelope-path envelope)
        descriptor
        (when-let [base (:seon.dev.config/launch-descriptor configuration)]
          (-> base
              (assoc ::launch/resolved-manifest
                     {::launch/path manifest-path
                      ::launch/sha-256 manifest-sha-256
                      ::launch/reconcile-manifest? reconcile?})
              (assoc ::launch/operational-envelope envelope)
              launch/validate-descriptor))]
    (cond-> (-> configuration
                (assoc
                 :seon.dev.config/resolved-manifest manifest
                 :seon.dev.config/resolved-manifest-path manifest-path
                 :seon.dev.config/launch-envelope-path envelope-path
                 :seon.dev.config/operational-envelope envelope
                 :seon.dev.config/resolved-configuration singleton
                 :seon.dev.config/reconcile-manifest? reconcile?)
                (update :seon.dev.config/environment assoc
                        "SEON_RESOLVED_MANIFEST_PATH" manifest-path
                        "SEON_RESOLVED_MANIFEST_SHA_256" manifest-sha-256
                        "SEON_DB_INITIALIZATION_PAGE_ROWS"
                        (str initialization-page-rows)))
      descriptor
      (assoc :seon.dev.config/launch-descriptor descriptor)

      reconcile?
      (assoc-in [:seon.dev.config/environment "SEON_CONFIG"] selected-path))))

(defn publish-applied-manifest!
  "Publish the resolved manifest after its live operation proves complete."
  [configuration]
  (when (:seon.dev.config/reconcile-manifest? configuration)
    (let [target (fs/path (:seon.dev.config/cluster-dir configuration)
                          "config" "applied.edn")]
      (atomic-write-edn! target
                         (:seon.dev.config/resolved-manifest configuration))))
  configuration)

(defn delete-applied-manifest!
  "Delete the retained manifest when resetting its cluster application."
  [configuration]
  (fs/delete-if-exists
   (fs/path (:seon.dev.config/cluster-dir configuration)
            "config" "applied.edn"))
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

(def artifact-configuration-schema
  [:map {:closed true}
   [:seon.dev.config/artifact-flavor :qualified-keyword]
   [:seon.dev.config/test-build? :boolean]
   [:seon.dev.config/client-build-id :string]
   [:seon.dev.config/shadow-cache-root :string]
   [:seon.dev.config/client-output :string]
   [:seon.dev.config/artifact-manifest-name :string]])

(def ^:private default-artifact
  {:seon.dev.config/artifact-flavor :seon.dev.artifact.flavor/default
   :seon.dev.config/test-build? false
   :seon.dev.config/client-build-id "client"
   :seon.dev.config/shadow-cache-root ".shadow-cljs"
   :seon.dev.config/client-output "out/client/main.js"
   :seon.dev.config/artifact-manifest-name "artifact.edn"})

(defn- root-path [root path]
  (let [path (fs/path path)]
    (str (fs/normalize (if (fs/absolute? path) path (fs/path root path))))))

(defn artifact-configuration
  "Read one validated artifact descriptor for this operator."
  {:malli/schema
   [:=> [:cat :string [:map-of :string :string]]
    artifact-configuration-schema]}
  [root environment]
  (let [descriptor (some-> (get environment "SEON_ARTIFACT_DESCRIPTOR")
                           fs/path)
        descriptor (when descriptor
                     (if (fs/absolute? descriptor)
                       descriptor
                       (fs/path root descriptor)))
        target (if descriptor
                 (do
                   (when-not (fs/regular-file? descriptor)
                     (throw
                      (ex-info "The artifact descriptor does not exist."
                               {:seon.dev.config/artifact-descriptor
                                (str descriptor)})))
                   (edn/read-string (slurp (str descriptor))))
                 default-artifact)
        _ (when-not (m/validate artifact-configuration-schema target)
            (throw
             (ex-info "The artifact descriptor is invalid."
                      {:seon.dev.config/artifact-descriptor
                       (or (some-> descriptor str) :default)
                       :seon.dev.config/explanation
                       (m/explain artifact-configuration-schema target)})))
        expected-output (root-path root (:seon.dev.config/client-output target))
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
        (assoc :seon.dev.config/client-output expected-output))))

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

(defn- child-environment [root source-checkout?]
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
                             "SEON_RENDER_STRICT"
                             (get environment "SEON_RENDER_STRICT"
                                  (if source-checkout? "1" "0"))))
        embed (get environment "SEON_EMBED")]
    (cond-> environment
      (or (nil? embed) (str/blank? embed) (= "0" embed))
      (dissoc "SEON_EMBED"))))

(defn- source-checkout? [root]
  (and (fs/regular-file? (fs/path root "deps.edn"))
       (fs/regular-file? (fs/path root "shadow-cljs.edn"))))

(defn- package-configuration [root]
  (let [manifest-path (str (fs/path root "release.edn"))]
    (when-not (fs/regular-file? manifest-path)
      (throw (ex-info "The Seon release manifest does not exist."
                      {:seon.dev.config/release-manifest manifest-path})))
    (let [manifest (release/read-manifest! manifest-path)
          package-root (fs/canonicalize root)
          identity (:seon.dev.release/identity manifest)
          member-paths
          (into {}
                (map (juxt :seon.dev.release/member
                           :seon.dev.release/path))
                (:seon.dev.release/members manifest))
          member-path
          (fn [identity-key]
            (str (fs/path package-root
                          (get member-paths (get identity identity-key)))))]
      {:seon.dev.config/artifact-flavor
       :seon.dev.artifact.flavor/default
       :seon.dev.config/test-build? false
       :seon.dev.config/client-build-id "client"
       :seon.dev.config/shadow-cache-root root
       :seon.dev.config/client-output
       (member-path :seon.dev.release/pod-member)
       :seon.dev.config/writer-output
       (member-path :seon.dev.release/writer-member)
       :seon.dev.config/bun-executable
       (member-path :seon.dev.release/bun-member)
       :seon.dev.config/runtime-assets
       (member-path :seon.dev.release/runtime-assets-member)
       :seon.dev.config/program-source
       (member-path :seon.dev.release/program-source-member)
       :seon.dev.config/package-config
       (str (fs/path (member-path :seon.dev.release/config-member)
                     "selected.edn"))
       :seon.dev.config/detach-helper
       (member-path :seon.dev.release/detach-helper-member)
       :seon.dev.config/artifact-manifest manifest-path})))

(defn load!
  "Derive one immutable operator configuration from the host."
  [root]
  (let [root (str (fs/normalize (fs/absolutize root)))
        source-checkout? (source-checkout? root)
        package (when-not source-checkout? (package-configuration root))
        environment (child-environment root source-checkout?)
        packaged-brand-css (when-not source-checkout?
                             (fs/path (:seon.dev.config/runtime-assets package)
                                      "resources/public/seon-brand.css"))
        environment (cond-> environment
                      (and packaged-brand-css
                           (fs/regular-file? packaged-brand-css))
                      (assoc "SEON_BRAND_CSS" (str packaged-brand-css))

                      (not source-checkout?)
                      (assoc "SEON_CONFIG"
                             (:seon.dev.config/package-config package)))
        artifact (if source-checkout?
                   (artifact-configuration root environment)
                   (select-keys package
                                [:seon.dev.config/artifact-flavor
                                 :seon.dev.config/test-build?
                                 :seon.dev.config/client-build-id
                                 :seon.dev.config/shadow-cache-root
                                 :seon.dev.config/client-output]))
        environment (if source-checkout?
                      (shadow-environment environment artifact)
                      environment)
        ;; Env-supplied coordinates may arrive relative (bin/acme exports
        ;; SEON_PROC_DIR=tmp/proc-acme). Every downstream consumer — locks,
        ;; child cwds, socket binds — requires absolute paths, so resolve
        ;; against the operator root at the single load boundary.
        state-dir (if source-checkout?
                    root
                    (root-path root
                               (get environment "SEON_STATE_DIR"
                                    (str (fs/path (System/getProperty "user.home") ".seon")))))
        cluster-dir (root-path root
                               (get environment "SEON_CLUSTER_DIR"
                                    (str (fs/path state-dir "data/clusters/default"))))
        cluster-name (str (fs/file-name cluster-dir))
        proc-dir (root-path root
                            (get environment "SEON_PROC_DIR"
                                 (str (fs/path state-dir "tmp/seon-operator"))))
        writer-proc-dir (root-path root
                                   (get environment "SEON_WRITER_PROC_DIR" proc-dir))
        log-dir (root-path root
                           (get environment "SEON_LOG_DIR"
                                (str (fs/path state-dir "logs/operator"))))
        req-sock (root-path root
                            (get environment "SEON_REQ_SOCK"
                                 (str (fs/path state-dir "tmp/seon-cluster-default-req.sock"))))
        host-eval-socket
        (when source-checkout?
          (root-path root
                     (str (fs/path state-dir
                                   (str "tmp/seon-host-eval-" cluster-name ".sock")))))
        port-file (root-path root
                             (get environment "SEON_PORT_FILE"
                                  (str (fs/path state-dir "tmp/seon-port"))))
        writer-port-file
        (root-path root
                   (get environment "SEON_WRITER_REPL_PORT_FILE"
                        (str (fs/path state-dir
                                      (str "tmp/seon-writer-repl-port-" cluster-name)))))
        environment (assoc environment
                      "SEON_CLUSTER_DIR" cluster-dir
                      "SEON_REQ_SOCK" req-sock
                      "SEON_PORT_FILE" port-file
                      "SEON_WRITER_REPL_PORT_FILE" writer-port-file
                      "SEON_WRITER_PROC_DIR" writer-proc-dir
                      "SEON_FS_ROOT" root
                      "SEON_FS_READ_ONLY" "1"
                      "SEON_FS_LOCK" "1")
        http-port (parse-long (get environment "SEON_PORT" "7890"))
        launch-descriptor
        (launch/default-descriptor
         (cond-> {::launch/cluster-dir cluster-dir
          ::launch/artifact-flavor
          (:seon.dev.config/artifact-flavor artifact)
          ::launch/client-build-id
          (:seon.dev.config/client-build-id artifact)
          ::launch/request-socket-path req-sock
          ::launch/writer-repl-port-file writer-port-file
          ::launch/writer-process-dir writer-proc-dir
          ::launch/process-dir proc-dir
          ::launch/log-dir log-dir
          ::launch/http-port http-port
          ::launch/http-port-file port-file}
           host-eval-socket (assoc ::launch/eval-socket-path host-eval-socket)))]
    (validate-configuration!
      (cond-> (merge
        (dissoc artifact :seon.dev.config/artifact-manifest-name)
        package
        {:seon.dev.config/root root
         :seon.dev.config/source-checkout? source-checkout?
         :seon.dev.config/environment environment
         :seon.dev.config/process-dir proc-dir
         :seon.dev.config/log-dir log-dir
         :seon.dev.config/containment-socket-dir
         (str (fs/path state-dir "tmp/seon-containment"))
         :seon.dev.config/cluster-dir cluster-dir
         :seon.dev.config/cluster-name cluster-name
         :seon.dev.config/request-socket req-sock
         :seon.dev.config/http-port http-port
         :seon.dev.config/http-port-file port-file
         :seon.dev.config/writer-repl-port
         (parse-long (get environment "SEON_WRITER_REPL_PORT" "0"))
         :seon.dev.config/writer-repl-port-file writer-port-file
         :seon.dev.config/writer-output
         (or (:seon.dev.config/writer-output package)
             (str (fs/path root
                           "target/seon-database-server-aot.jar")))
         :seon.dev.config/artifact-manifest
         (or (:seon.dev.config/artifact-manifest package)
             (str (fs/path
                   proc-dir
                   (:seon.dev.config/artifact-manifest-name artifact))))
         :seon.dev.config/launch-descriptor launch-descriptor})
        host-eval-socket
        (assoc :seon.dev.config/host-eval-socket host-eval-socket)))))
