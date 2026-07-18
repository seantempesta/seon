(ns seon.dev.artifact
  "Canonical build and content manifest for the Seon development runtime."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.dev.config :as config]
            [seon.dev.state :as state])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util.jar JarFile]))

(def current-version 8)

(def bun-identity-schema
  [:map {:closed true}
   [:seon.dev.artifact/bun-executable :string]
   [:seon.dev.artifact/bun-executable-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/bun-version [:re #"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"]]
   [:seon.dev.artifact/bun-revision [:re #"[0-9a-f]{40}"]]])

(def ^:private maintained-dependency-schema
  [:map
   [:seon.dev.artifact/dependency-library :symbol]
   [:seon.dev.artifact/dependency-git-url
    [:re #"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?"]]
   [:seon.dev.artifact/dependency-git-sha [:re #"[0-9a-f]{40}"]]])

(def artifact-manifest-schema
  [:map {:closed true}
   [:seon.dev.artifact/version [:= current-version]]
   [:seon.dev.artifact/published-at :string]
   [:seon.dev.artifact/flavor
    [:enum :seon.dev.artifact.flavor/default
     :seon.dev.artifact.flavor/acme]]
   [:seon.dev.artifact/client-build-id :string]
   [:seon.dev.artifact/execution-build-id :string]
   [:seon.dev.artifact/shadow-cache-root :string]
   [:seon.dev.artifact/client-output :string]
   [:seon.dev.artifact/execution-output :string]
   [:seon.dev.artifact/runtime-root :string]
   [:seon.dev.artifact/bun-executable :string]
   [:seon.dev.artifact/bun-executable-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/bun-version [:re #"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"]]
   [:seon.dev.artifact/bun-revision [:re #"[0-9a-f]{40}"]]
   [:seon.dev.artifact/source-input-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/maintained-dependencies
    [:vector {:min 6 :max 6} maintained-dependency-schema]]
   [:seon.dev.artifact/writer-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/client-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/execution-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/execution-runtime-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/bootstrap-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/css-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/application-digest [:re #"[0-9a-f]{64}"]]])

(def ^:private writer-cache-schema
  [:map
   [:seon.dev.writer-cache/version [:= 1]]
   [:seon.dev.writer-cache/input-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.writer-cache/writer-digest [:re #"[0-9a-f]{64}"]]])

(declare validate-maintained-dependencies!)

(defn- validate-manifest! [manifest]
  (when-not (m/validate artifact-manifest-schema manifest)
    (throw (ex-info "The canonical artifact manifest is invalid."
                    {:seon.dev.artifact/explanation
                     (mapv #(select-keys % [:path :in :type])
                           (:errors (m/explain artifact-manifest-schema
                                               manifest)))})))
  (validate-maintained-dependencies!
   (:seon.dev.artifact/maintained-dependencies manifest))
  manifest)

(defn- bytes->hex [byte-values]
  (apply str (map #(format "%02x" (bit-and 0xff %)) byte-values)))

(defn- update-text! [^MessageDigest digest value]
  (.update digest (.getBytes (str value) StandardCharsets/UTF_8))
  (.update digest (byte 0)))

(defn- update-stream! [^MessageDigest digest stream]
  (let [buffer (byte-array 65536)]
    (loop []
      (let [n-read (.read stream buffer)]
        (when (pos? n-read)
          (.update digest buffer 0 n-read)
          (recur))))))

(defn- file-digest [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (io/input-stream (str path))]
      (update-stream! digest stream))
    (bytes->hex (.digest digest))))

(defn- path-executable [environment executable-name]
  (let [path (get environment "PATH" (System/getenv "PATH"))
        directories (str/split (or path "") #":" -1)]
    (or (some (fn [directory]
                (let [candidate (fs/path (if (str/blank? directory)
                                           (System/getProperty "user.dir")
                                           directory)
                                         executable-name)]
                  (when (and (fs/regular-file? candidate)
                             (fs/executable? candidate))
                    (str (fs/canonicalize candidate)))))
              directories)
        (throw (ex-info "Bun is not resolvable from PATH."
                        {:seon.dev.artifact/executable executable-name})))))

(defn bun-identity!
  "Read Bun's exact executable, version, and full source revision."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.dev.config/environment {:optional true}
                [:map-of :string :string]]]]
    bun-identity-schema]}
  [config]
  (let [environment (:seon.dev.config/environment config)
        executable (path-executable environment "bun")
        result (process/shell
                {:out :string :err :string :continue true
                 :env environment}
                executable "-e"
                (str "process.stdout.write(JSON.stringify({"
                     "executable:process.execPath,"
                     "version:Bun.version,revision:Bun.revision}))"))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Bun identity inspection failed."
                      {:seon.dev.artifact/bun-executable executable
                       :seon.dev.artifact/exit (:exit result)
                       :seon.dev.artifact/error (str/trim (:err result))})))
    (let [{reported-executable "executable"
           version "version"
           revision "revision"} (json/parse-string (:out result))
          reported-executable (some-> reported-executable fs/canonicalize str)
          identity {:seon.dev.artifact/bun-executable executable
                    :seon.dev.artifact/bun-executable-digest
                    (file-digest executable)
                    :seon.dev.artifact/bun-version version
                    :seon.dev.artifact/bun-revision revision}]
      (when-not (= executable reported-executable)
        (throw (ex-info "Bun reported another executable identity."
                        {:seon.dev.artifact/bun-executable executable
                         :seon.dev.artifact/reported-executable
                         reported-executable})))
      (when-not (m/validate bun-identity-schema identity)
        (throw (ex-info "Bun reported an invalid identity."
                        {:seon.dev.artifact/explanation
                         (m/explain bun-identity-schema identity)})))
      identity)))

(defn bun-executable-current?
  "True when the manifest-bound Bun executable still has the published bytes."
  {:malli/schema [:=> [:cat bun-identity-schema] :boolean]}
  [identity]
  (let [executable (:seon.dev.artifact/bun-executable identity)]
    (and (string? executable)
         (fs/regular-file? executable)
         (fs/executable? executable)
         (= (:seon.dev.artifact/bun-executable-digest identity)
            (file-digest executable)))))

(defn- digest-values [values]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [value values] (update-text! digest value))
    (bytes->hex (.digest digest))))

(def ^:private maintained-dependency-selections
  [['org.replikativ/datahike
    [[:writer :replace-deps] [:cljs :override-deps]]]
   ['org.replikativ/konserve
    [[:writer :replace-deps] [:cljs :override-deps]]]
   ['org.replikativ/proximum
    [[:writer :replace-deps]]]
   ['thheller/shadow-cljs
    [[:cljs :extra-deps]]]
   ['org.replikativ/superv.async
    [[:cljs :override-deps]]]
   ['is.simm/partial-cps
    [[:cljs :override-deps]]]])

(defn- validate-maintained-dependencies! [coordinates]
  (let [expected (mapv first maintained-dependency-selections)
        actual (mapv :seon.dev.artifact/dependency-library coordinates)]
    (when-not (= expected actual)
      (throw
        (ex-info "The maintained dependency identity set is invalid."
                 {:seon.dev.artifact/dependency-expected expected
                  :seon.dev.artifact/dependency-actual actual})))
    coordinates))

(defn- command-output!
  [directory & command]
  (let [result (apply process/shell
                      {:dir (str directory)
                       :out :string
                       :err :string
                       :continue true}
                      command)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Maintained dependency Git inspection failed."
                      {:seon.dev.artifact/directory (str directory)
                       :seon.dev.artifact/command (vec command)
                       :seon.dev.artifact/exit (:exit result)
                       :seon.dev.artifact/error (str/trim (:err result))})))
    (str/trim (:out result))))

(defn- public-github-url [url]
  (cond
    (and (string? url)
         (re-matches #"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?"
                     url))
    url

    (and (string? url)
         (re-matches #"git@github\.com:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?"
                     url))
    (str "https://github.com/" (subs url (count "git@github.com:")))

    :else nil))

(defn- local-root-coordinate!
  [root local-root]
  (let [directory (fs/path root local-root)
        dirty (command-output! directory "git" "status" "--porcelain")
        url (public-github-url
             (command-output! directory "git" "remote" "get-url" "origin"))
        sha (command-output! directory "git" "rev-parse" "HEAD")]
    (when (seq dirty)
      (throw (ex-info "A maintained local dependency has uncommitted changes."
                      {:seon.dev.artifact/dependency-root (str directory)
                       :seon.dev.artifact/dependency-status
                       (str/split-lines dirty)})))
    {:git/url url :git/sha sha}))

(defn- git-coordinate! [root dependencies library alias dependency-section]
  (let [coordinate (get-in dependencies
                           [:aliases alias dependency-section library])
        identity (if-let [local-root (:local/root coordinate)]
                   (when root (local-root-coordinate! root local-root))
                   coordinate)
        git-url (public-github-url (:git/url identity))
        git-sha (:git/sha identity)]
    (when-not (and (map? coordinate)
                   (string? git-url)
                   (re-matches
                     #"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?"
                     git-url)
                   (string? git-sha)
                   (re-matches #"[0-9a-f]{40}" git-sha))
      (throw
        (ex-info "A maintained dependency lacks an exact public Git coordinate."
                 {:seon.dev.artifact/dependency-library library
                  :seon.dev.artifact/dependency-alias alias
                  :seon.dev.artifact/dependency-section dependency-section
                  :seon.dev.artifact/dependency-coordinate coordinate})))
    {:seon.dev.artifact/dependency-library library
     :seon.dev.artifact/dependency-git-url git-url
     :seon.dev.artifact/dependency-git-sha git-sha}))

(defn- maintained-dependencies-from
  ([dependencies] (maintained-dependencies-from nil dependencies))
  ([root dependencies]
   (mapv
     (fn [[library selections]]
       (let [coordinates
             (mapv (fn [[alias dependency-section]]
                     (git-coordinate! root dependencies library alias
                                      dependency-section))
                   selections)
             selected (first coordinates)]
         (when-not (apply = coordinates)
           (throw
             (ex-info "Maintained dependency aliases select different commits."
                      {:seon.dev.artifact/dependency-library library
                       :seon.dev.artifact/dependency-coordinates coordinates})))
         selected))
     maintained-dependency-selections)))

(defn- maintained-dependencies [root]
  (maintained-dependencies-from root
    (edn/read-string (slurp (str (fs/path root "deps.edn"))))))

(defn- regular-files [path]
  (let [path (fs/path path)]
    (cond
      (fs/regular-file? path) [path]
      (fs/directory? path) (->> (file-seq (io/file (str path)))
                                (filter #(.isFile ^java.io.File %))
                                (map fs/path)
                                sort)
      :else [])))

(defn digest-paths
  "Hash file paths and bytes in deterministic path order.

   Relative input paths are resolved against `root`; callers do not need to
   pre-normalize shell arguments into absolute paths."
  [root paths]
  (let [root (fs/absolutize (fs/path root))
        digest (MessageDigest/getInstance "SHA-256")
        resolve-path (fn [path]
                       (let [path (fs/path path)]
                         (if (fs/relative? path)
                           (fs/path root (str path))
                           path)))
        files (->> paths (map resolve-path) (mapcat regular-files) distinct sort)]
    (doseq [path files]
      (update-text! digest (str (fs/relativize root path)))
      (with-open [stream (io/input-stream (str path))]
        (update-stream! digest stream)))
    (bytes->hex (.digest digest))))

(def ^:private common-source-input-paths
  ["build.clj"
   "deps.edn"
   "shadow-cljs.edn"
   "package.json"
   "package-lock.json"
   "bin/fix-bootstrap-macros"
   "config"
   "externs"
   "guest-cljs"
   "java"
   "resources"
   "src"])

(defn source-input-digest
  "Hash the source and build inputs selected by an artifact flavor."
  [config]
  (let [environment (:seon.dev.config/environment config)
        extra-source (get environment "SEON_EXTRA_SRC")
        paths (cond-> common-source-input-paths
                (= :seon.dev.artifact.flavor/acme
                   (:seon.dev.config/artifact-flavor config))
                (conj "acme")

                (not (str/blank? extra-source))
                (conj extra-source))]
    (digest-values
     ["flavor" (:seon.dev.config/artifact-flavor config)
      "client-build-id" (:seon.dev.config/client-build-id config)
      "execution-build-id" (:seon.dev.config/execution-build-id config)
      "extra-preload" (get environment "SEON_EXTRA_PRELOAD")
      "source" (digest-paths (:seon.dev.config/root config) paths)])))

(defn current-client-digest
  "Hash the complete client closure at its flavor-owned coordinates."
  [config]
  (let [root (:seon.dev.config/root config)
        client-runtime
        (fs/path (:seon.dev.config/shadow-cache-root config)
                 "builds" (:seon.dev.config/client-build-id config)
                 "dev/out/cljs-runtime")]
    (digest-paths root [(:seon.dev.config/client-output config)
                        client-runtime])))

(defn current-execution-digest
  "Hash the exact flavor-owned execution artifact bytes."
  [config]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (io/input-stream
                       (:seon.dev.config/execution-output config))]
      (update-stream! digest stream))
    (bytes->hex (.digest digest))))

(defn- execution-runtime-path [config]
  (fs/path (:seon.dev.config/shadow-cache-root config)
           "builds" (:seon.dev.config/execution-build-id config)
           "dev/out/cljs-runtime"))

(defn current-execution-runtime-digest
  "Hash the imported JavaScript closure used by the execution entry file."
  [config]
  (digest-paths (:seon.dev.config/root config)
                [(execution-runtime-path config)]))

(defn- digest-jar [path]
  ;; Zip entry timestamps are packaging metadata. Hash the entry names and
  ;; bytes so rebuilding identical writer code retains one artifact identity.
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [jar (JarFile. (str path))]
      (doseq [entry (->> (enumeration-seq (.entries jar))
                         (remove #(.isDirectory ^java.util.jar.JarEntry %))
                         (sort-by #(.getName ^java.util.jar.JarEntry %)))]
        (update-text! digest (.getName ^java.util.jar.JarEntry entry))
        (with-open [stream (.getInputStream jar entry)]
          (update-stream! digest stream))))
    (bytes->hex (.digest digest))))

(defn current-writer-digest
  "Read the canonical semantic digest of the writer artifact on disk."
  {:malli/schema
   [:=> [:cat config/configuration-schema] [:re #"[0-9a-f]{64}"]]}
  [config]
  (let [path (:seon.dev.config/writer-output config)]
    (when-not (fs/regular-file? path)
      (throw (ex-info "The canonical writer artifact is absent."
                      {:seon.dev.artifact/path path})))
    (digest-jar path)))

(def current-output-digests-schema
  [:map {:closed true}
   [:seon.dev.artifact/bun-executable :string]
   [:seon.dev.artifact/bun-executable-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/bun-version :string]
   [:seon.dev.artifact/bun-revision [:re #"[0-9a-f]{40}"]]
   [:seon.dev.artifact/writer-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/client-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/execution-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/execution-runtime-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/bootstrap-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/css-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/application-digest [:re #"[0-9a-f]{64}"]]])

(defn- derive-application-digest
  [config bun maintained-dependencies writer-digest client-digest execution-digest
   execution-runtime-digest bootstrap-digest css-digest]
  (digest-values ["flavor" (:seon.dev.config/artifact-flavor config)
                  "bun-executable" (:seon.dev.artifact/bun-executable bun)
                  "bun-executable-digest"
                  (:seon.dev.artifact/bun-executable-digest bun)
                  "bun-version" (:seon.dev.artifact/bun-version bun)
                  "bun-revision" (:seon.dev.artifact/bun-revision bun)
                  "client-build-id" (:seon.dev.config/client-build-id config)
                  "client-output" (:seon.dev.config/client-output config)
                  "shadow-cache-root" (:seon.dev.config/shadow-cache-root config)
                  "writer" writer-digest
                  "maintained-dependencies" (pr-str maintained-dependencies)
                  "client" client-digest
                  "execution-build-id"
                  (:seon.dev.config/execution-build-id config)
                  "execution-output" (:seon.dev.config/execution-output config)
                  "execution" execution-digest
                  "execution-runtime" execution-runtime-digest
                  "bootstrap" bootstrap-digest
                  "css" css-digest]))

(defn current-output-digests
  "Hash every output that contributes to the application artifact identity."
  {:malli/schema
   [:=> [:cat config/configuration-schema] current-output-digests-schema]}
  [config]
  (let [root (:seon.dev.config/root config)
        bun (bun-identity! config)
        writer-digest (current-writer-digest config)
        maintained-dependencies (maintained-dependencies root)
        client-digest (current-client-digest config)
        execution-digest (current-execution-digest config)
        execution-runtime-digest (current-execution-runtime-digest config)
        bootstrap-digest (digest-paths root ["out/bootstrap"])
        css-digest (digest-paths root ["resources/public/css/output.css"])
        application-digest
        (derive-application-digest
         config bun maintained-dependencies writer-digest client-digest
         execution-digest execution-runtime-digest bootstrap-digest css-digest)]
    (merge bun
     {:seon.dev.artifact/writer-digest writer-digest
     :seon.dev.artifact/client-digest client-digest
     :seon.dev.artifact/execution-digest execution-digest
     :seon.dev.artifact/execution-runtime-digest execution-runtime-digest
     :seon.dev.artifact/bootstrap-digest bootstrap-digest
     :seon.dev.artifact/css-digest css-digest
      :seon.dev.artifact/application-digest application-digest})))

(defn read-manifest
  "Read the last atomically published artifact manifest."
  [config]
  (let [path (:seon.dev.config/artifact-manifest config)]
    (when (fs/regular-file? path)
      (validate-manifest! (edn/read-string (slurp path))))))

(defn current-manifest
  "Return the published manifest when its inputs and outputs still match."
  [config]
  (try
    (let [manifest (read-manifest config)
          output-digests (current-output-digests config)
          expected-identity
          {:seon.dev.artifact/flavor
           (:seon.dev.config/artifact-flavor config)
           :seon.dev.artifact/client-build-id
           (:seon.dev.config/client-build-id config)
           :seon.dev.artifact/execution-build-id
           (:seon.dev.config/execution-build-id config)
           :seon.dev.artifact/shadow-cache-root
           (:seon.dev.config/shadow-cache-root config)
           :seon.dev.artifact/client-output
           (:seon.dev.config/client-output config)}]
      (when (and manifest
                 (= expected-identity
                    (select-keys manifest (keys expected-identity)))
                 (= (:seon.dev.artifact/source-input-digest manifest)
                    (source-input-digest config))
                 (= output-digests
                    (select-keys manifest
                                 (keys output-digests))))
        manifest))
    (catch Throwable _ nil)))

(defn- atomic-spit! [path value]
  (let [path (fs/path path)
        temp (fs/path (str path "." (random-uuid) ".tmp"))]
    (fs/create-dirs (fs/parent path))
    (spit (str temp) (str (pr-str value) "\n"))
    (fs/move temp path {:replace-existing true :atomic-move true})
    value))

(defn- extra-cljs-args [config]
  (let [environment (:seon.dev.config/environment config)
        source (get environment "SEON_EXTRA_SRC")
        preload (get environment "SEON_EXTRA_PRELOAD")
        config-merge
        (cond-> {}
          (and (not (str/blank? source)) (not (str/blank? preload)))
          (assoc :devtools {:preloads [(symbol preload)]}))]
    (cond-> []
      (not (str/blank? source))
      (into ["-Sdeps" (pr-str {:deps {'seon.extra/src {:local/root source}}})])

      (seq config-merge)
      (into ["--config-merge" (pr-str config-merge)]))))

(defn cljs-command
  "Build a structured Shadow CLJS argv vector."
  [config action build-id]
  (let [extra-args (extra-cljs-args config)]
    (into ["clj"]
          (concat (take-while #(not= "--config-merge" %) extra-args)
                  ["-M:cljs" action build-id]
                  (drop-while #(not= "--config-merge" %) extra-args)))))

(defn- run-step! [config label argv]
  (println (str "▶ " label))
  (let [started (System/nanoTime)
        result (process/shell {:dir (:seon.dev.config/root config)
                               :env (:seon.dev.config/environment config)
                               :cmd argv})
        elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
    (println (str "  ● " label " (" elapsed-ms "ms)"))
    result))

(defn- capture-command! [config argv]
  (let [result (process/shell {:dir (:seon.dev.config/root config)
                               :env (:seon.dev.config/environment config)
                               :out :string
                               :err :string
                               :cmd argv})]
    (str (:out result) "\n" (:err result))))

(defn- build-lock-directory [config]
  (fs/path (:seon.dev.config/root config) "tmp/seon-artifact-build"))

(defn- writer-cache-path [config]
  (str (fs/path (build-lock-directory config) "writer.edn")))

(defn- writer-input-digest [config]
  (let [root (:seon.dev.config/root config)
        environment (:seon.dev.config/environment config)
        java-command (get environment "JAVA_CMD" "java")]
    (digest-values
      ["writer-cache-version" 1
       "local-inputs"
       (digest-paths root ["build.clj"
                           "deps.edn"
                           "java"
                           "src"])
       "clojure-cli" (capture-command! config ["clojure" "-Sdescribe"])
       "writer-classpath" (capture-command! config
                                             ["clojure" "-Spath" "-M:writer"])
       "writer-tree" (capture-command! config
                                        ["clojure" "-Stree" "-M:writer"])
       "java-command" java-command
       "java-runtime" (capture-command! config [java-command "-version"])])))

(defn- read-writer-cache [config]
  (try
    (let [cache (state/read-edn (writer-cache-path config))]
      (when (m/validate writer-cache-schema cache) cache))
    (catch Throwable _ nil)))

(defn- verified-writer-digest [config input-digest]
  (let [cache (read-writer-cache config)
        output (:seon.dev.config/writer-output config)]
    (when (and (= input-digest
                  (:seon.dev.writer-cache/input-digest cache))
               (fs/regular-file? output))
      (try
        (let [actual (digest-jar output)]
          (when (= actual (:seon.dev.writer-cache/writer-digest cache)) actual))
        (catch Throwable _ nil)))))

(defn- ensure-writer! [config]
  (let [input-digest (writer-input-digest config)]
    (if-let [writer-digest (verified-writer-digest config input-digest)]
      (do
        (println "  ● reuse canonical database server")
        writer-digest)
      (do
        (run-step! config "warm writer classpath" ["clojure" "-P" "-M:writer"])
        (run-step! config "build canonical database server"
                   ["clojure" "-T:build" "writer-uber"])
        (let [writer-digest (digest-jar
                              (:seon.dev.config/writer-output config))]
          (state/write-edn!
            (writer-cache-path config)
            {:seon.dev.writer-cache/version 1
             :seon.dev.writer-cache/input-digest input-digest
             :seon.dev.writer-cache/writer-digest writer-digest})
          writer-digest)))))

(defn- prepare-dependencies-unlocked! [config aliases]
  (let [extra-args (when (some #{:cljs} aliases)
                     (take-while #(not= "--config-merge" %)
                                 (extra-cljs-args config)))]
    (run-step! config
               (str "prepare " (str/join ", " (map name aliases))
                    " dependencies")
               (into ["clojure"]
                     (concat extra-args
                             ["-X:deps" "prep" ":aliases"
                              (pr-str aliases)])))
    {:seon.dev.artifact/prepared-aliases aliases}))

(defn- build-source! [config bun]
  ;; The caller already owns the checkout-wide artifact lock. Keep dependency
  ;; preparation inside that bracket without attempting to reacquire it.
  (prepare-dependencies-unlocked! config [:writer :cljs])
  (ensure-writer! config)
  (run-step! config "warm CLJS classpath" ["clojure" "-P" "-M:cljs"])
  ;; `--preflight` is explicitly the live embedding round-trip gate. Its
  ;; correct master-OFF result is exit 11, not a failed writer artifact.
  (when (get-in config [:seon.dev.config/environment "SEON_EMBED"])
    (run-step! config "preflight embedding-backed database server"
               [(get-in config [:seon.dev.config/environment "JAVA_CMD"] "java")
                "--add-modules" "jdk.incubator.vector"
                "--enable-native-access=ALL-UNNAMED"
                "-XX:+UseG1GC" "-Xmx2g" "-jar"
                (:seon.dev.config/writer-output config) "--preflight"]))
  (run-step! config "build self-host bootstrap"
             (cljs-command config "compile" "bootstrap"))
  (run-step! config "repair bootstrap macro metadata"
             [(str (fs/path (:seon.dev.config/root config)
                            "bin/fix-bootstrap-macros"))])
  (run-step! config "build web CSS"
             [(:seon.dev.artifact/bun-executable bun) "run" "css:build"]))

(defn- build-lock-configuration [config]
  ;; Default and downstream targets intentionally own different lifecycle
  ;; directories, but a source checkout publishes one writer jar, bootstrap,
  ;; and CSS output. Derive their build lock from the checkout, not the target.
  (assoc config :seon.dev.config/process-dir
         (str (build-lock-directory config))))

(defn- with-build-lock [config build]
  (state/with-lock (build-lock-configuration config)
                   :source-artifacts 1800000 build))

(defn prepare-dependencies!
  "Prepare selected dependency aliases under the checkout artifact lock.

   CLJS preparation includes the selected downstream source basis."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.dev.config/root :string]
               [:seon.dev.config/environment [:map-of :string :string]]]
         [:vector {:min 1} :keyword]]
    [:map [:seon.dev.artifact/prepared-aliases
           [:vector {:min 1} :keyword]]]]}
  [config aliases]
  (with-build-lock config #(prepare-dependencies-unlocked! config aliases)))

(def ^:private runtime-root-links ["src" "test" "guest-cljs" "resources"])

(defn- runtime-relative-path [config path]
  (let [root (fs/absolutize (fs/path (:seon.dev.config/root config)))
        path (fs/absolutize (fs/path path))
        relative (fs/relativize root path)]
    (when (str/starts-with? (str relative) "..")
      (throw (ex-info "A runtime artifact member is outside the checkout."
                      {:seon.dev.artifact/root (str root)
                       :seon.dev.artifact/path (str path)})))
    relative))

(defn- runtime-execution-output [config runtime-root]
  (fs/path runtime-root
           (runtime-relative-path config
                                  (:seon.dev.config/execution-output config))))

(defn- runtime-execution-runtime [config runtime-root]
  (fs/path runtime-root
           (runtime-relative-path config (execution-runtime-path config))))

(defn- verify-runtime-root!
  [config runtime-root bootstrap-digest execution-digest
   execution-runtime-digest]
  (let [actual-bootstrap (digest-paths runtime-root ["out/bootstrap"])
        execution-output (runtime-execution-output config runtime-root)
        actual-execution
        (current-execution-digest
         {:seon.dev.config/execution-output (str execution-output)})
        actual-execution-runtime
        (digest-paths runtime-root
                      [(runtime-relative-path config
                                              (execution-runtime-path config))])]
    (when-not (= bootstrap-digest actual-bootstrap)
      (throw (ex-info "An immutable runtime root has unexpected bootstrap bytes."
                      {:seon.dev.artifact/runtime-root (str runtime-root)
                       :seon.dev.artifact/expected bootstrap-digest
                       :seon.dev.artifact/actual actual-bootstrap})))
    (when-not (= execution-digest actual-execution)
      (throw (ex-info "An immutable runtime root has unexpected execution bytes."
                      {:seon.dev.artifact/runtime-root (str runtime-root)
                       :seon.dev.artifact/expected execution-digest
                       :seon.dev.artifact/actual actual-execution})))
    (when-not (= execution-runtime-digest actual-execution-runtime)
      (throw (ex-info "An immutable runtime root has unexpected imported JavaScript."
                      {:seon.dev.artifact/runtime-root (str runtime-root)
                       :seon.dev.artifact/expected execution-runtime-digest
                       :seon.dev.artifact/actual actual-execution-runtime})))
    (str runtime-root)))

(defn- publish-runtime-root!
  [config bootstrap-digest execution-digest execution-runtime-digest]
  (let [root (fs/path (:seon.dev.config/root config))
        parent (fs/path root "tmp/seon-runtime-artifacts")
        identity (str bootstrap-digest "-" execution-digest "-"
                      execution-runtime-digest)
        runtime-root (fs/path parent identity)]
    (if (fs/directory? runtime-root)
      (verify-runtime-root! config runtime-root bootstrap-digest
                            execution-digest execution-runtime-digest)
      (let [temporary (fs/path parent (str "." identity "."
                                                (random-uuid) ".tmp"))]
        (try
          (fs/create-dirs (fs/path temporary "out"))
          (fs/copy-tree (fs/path root "out/bootstrap")
                        (fs/path temporary "out/bootstrap"))
          (fs/create-dirs
           (fs/parent (runtime-execution-output config temporary)))
          (fs/copy (:seon.dev.config/execution-output config)
                   (runtime-execution-output config temporary))
          (fs/create-dirs
           (fs/parent (runtime-execution-runtime config temporary)))
          (fs/copy-tree (execution-runtime-path config)
                        (runtime-execution-runtime config temporary))
          ;; The bootstrap and complete execution closure are immutable runtime
          ;; members. Development source and assets remain explicit links until
          ;; downstream packaging publishes its bounded corpus.
          (doseq [relative runtime-root-links
                  :let [source (fs/path root relative)]
                  :when (fs/exists? source)]
            (fs/create-sym-link (fs/path temporary relative) source))
          (verify-runtime-root! config temporary bootstrap-digest
                                execution-digest execution-runtime-digest)
          (fs/create-dirs parent)
          (fs/move temporary runtime-root {:atomic-move true})
          (str runtime-root)
          (finally
            (when (fs/exists? temporary) (fs/delete-tree temporary))))))))

(defn- output-manifest [config bun]
  (let [root (:seon.dev.config/root config)
        writer (:seon.dev.config/writer-output config)
        bootstrap (fs/path root "out/bootstrap")
        css (fs/path root "resources/public/css/output.css")
        required [writer
                  (:seon.dev.config/client-output config)
                  (:seon.dev.config/execution-output config)
                  (execution-runtime-path config)
                  bootstrap css]
        missing (remove #(or (fs/regular-file? %) (fs/directory? %)) required)]
    (when (seq missing)
      (throw (ex-info "Canonical build did not publish every required output."
                      {:seon.dev.artifact/missing (mapv str missing)})))
    (let [writer-digest (digest-jar writer)
          maintained-dependencies (maintained-dependencies root)
          client-digest (current-client-digest config)
          execution-digest (current-execution-digest config)
          execution-runtime-digest (current-execution-runtime-digest config)
          bootstrap-digest (digest-paths root [bootstrap])
          css-digest (digest-paths root [css])
          runtime-root
          (publish-runtime-root! config bootstrap-digest execution-digest
                                 execution-runtime-digest)
          execution-output
          (str (runtime-execution-output config runtime-root))
          application-digest
          (derive-application-digest
           config bun maintained-dependencies writer-digest client-digest
           execution-digest execution-runtime-digest bootstrap-digest
           css-digest)]
      (validate-manifest!
        (merge bun
        {:seon.dev.artifact/version current-version
         :seon.dev.artifact/published-at (str (Instant/now))
         :seon.dev.artifact/flavor
         (:seon.dev.config/artifact-flavor config)
         :seon.dev.artifact/client-build-id
         (:seon.dev.config/client-build-id config)
         :seon.dev.artifact/execution-build-id
         (:seon.dev.config/execution-build-id config)
         :seon.dev.artifact/shadow-cache-root
         (:seon.dev.config/shadow-cache-root config)
         :seon.dev.artifact/client-output
         (:seon.dev.config/client-output config)
         :seon.dev.artifact/execution-output
         execution-output
         :seon.dev.artifact/runtime-root runtime-root
         :seon.dev.artifact/source-input-digest (source-input-digest config)
         :seon.dev.artifact/maintained-dependencies maintained-dependencies
         :seon.dev.artifact/writer-digest writer-digest
         :seon.dev.artifact/client-digest client-digest
         :seon.dev.artifact/execution-digest execution-digest
         :seon.dev.artifact/execution-runtime-digest execution-runtime-digest
         :seon.dev.artifact/bootstrap-digest bootstrap-digest
         :seon.dev.artifact/css-digest css-digest
         :seon.dev.artifact/application-digest application-digest})))))

(defn build!
  "Build and atomically publish one canonical artifact manifest.

   A source build delegates the client flush to `prepare-client!` because a
   Shadow watch worker injects process-specific devtools coordinates that a
   one-shot compile cannot reproduce. The checkout artifact lock remains held
   across that first watcher flush and manifest publication."
  ([config] (build! config nil))
  ([config prepare-client!]
   (if (:seon.dev.config/source-checkout? config)
     (with-build-lock
       config
       #(let [bun (bun-identity! config)]
          (when-not (fn? prepare-client!)
            (throw
             (ex-info "A source artifact build requires its managed watcher."
                      {:seon.dev.artifact/failure
                       :seon.dev.artifact.failure/missing-client-owner})))
          (build-source! config bun)
          ;; The long-lived watcher is the one client-output owner. Its first
          ;; completed flush happens under the same checkout-wide lock as the
          ;; remaining outputs and the one final manifest publication.
          (prepare-client!)
          (let [previous (try
                           (read-manifest config)
                           (catch Exception _ nil))
                _ (when-not (bun-executable-current? bun)
                    (throw (ex-info "Bun changed during artifact publication."
                                    bun)))
                manifest (output-manifest config bun)
                changed (cond-> #{}
                          (not= (:seon.dev.artifact/writer-digest previous)
                                (:seon.dev.artifact/writer-digest manifest))
                          (conj :seon.dev.artifact/writer)

                          (not= (:seon.dev.artifact/application-digest previous)
                                (:seon.dev.artifact/application-digest manifest))
                          (conj :seon.dev.artifact/application))]
            (atomic-spit! (:seon.dev.config/artifact-manifest config) manifest)
            (assoc manifest :seon.dev.artifact/changed changed))))
     (let [manifest (or (read-manifest config)
                        (throw (ex-info "Packaged Seon is missing its artifact manifest."
                                        {:seon.dev.artifact/path
                                         (:seon.dev.config/artifact-manifest config)})))]
       (assoc manifest :seon.dev.artifact/changed #{})))))
