(ns seon.dev.artifact
  "Canonical build and content manifest for the Seon development runtime."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util.jar JarFile]))

(def artifact-manifest-schema
  [:map
   [:seon.dev.artifact/version [:= 2]]
   [:seon.dev.artifact/published-at :string]
   [:seon.dev.artifact/flavor
    [:enum :seon.dev.artifact.flavor/default
     :seon.dev.artifact.flavor/acme]]
   [:seon.dev.artifact/client-build-id :string]
   [:seon.dev.artifact/shadow-cache-root :string]
   [:seon.dev.artifact/client-output :string]
   [:seon.dev.artifact/writer-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/client-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/bootstrap-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/css-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/application-digest [:re #"[0-9a-f]{64}"]]])

(def ^:private legacy-artifact-manifest-schema
  [:map
   [:seon.dev.artifact/version [:= 1]]
   [:seon.dev.artifact/published-at :string]
   [:seon.dev.artifact/writer-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/client-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/bootstrap-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/css-digest [:re #"[0-9a-f]{64}"]]
   [:seon.dev.artifact/application-digest [:re #"[0-9a-f]{64}"]]])

(defn- validate-manifest! [manifest]
  (when-not (m/validate artifact-manifest-schema manifest)
    (throw (ex-info "The canonical artifact manifest is invalid."
                    {:seon.dev.artifact/explanation
                     (mapv #(select-keys % [:path :in :type])
                           (:errors (m/explain artifact-manifest-schema
                                               manifest)))})))
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

(defn- digest-values [values]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [value values] (update-text! digest value))
    (bytes->hex (.digest digest))))

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

(defn read-manifest
  "Read the last atomically published artifact manifest."
  [config]
  (let [path (:seon.dev.config/artifact-manifest config)]
    (when (fs/regular-file? path)
      (let [manifest (edn/read-string (slurp path))]
        (if (m/validate legacy-artifact-manifest-schema manifest)
          (if (= :seon.dev.artifact.flavor/default
                 (:seon.dev.config/artifact-flavor config))
            (validate-manifest!
              (assoc manifest
                     :seon.dev.artifact/version 2
                     :seon.dev.artifact/flavor
                     :seon.dev.artifact.flavor/default
                     :seon.dev.artifact/client-build-id "client"
                     :seon.dev.artifact/shadow-cache-root
                     (:seon.dev.config/shadow-cache-root config)
                     :seon.dev.artifact/client-output
                     (:seon.dev.config/client-output config)))
            (throw
              (ex-info "A legacy artifact manifest cannot identify this flavor."
                       {:seon.dev.artifact/path path
                        :seon.dev.artifact/flavor
                        (:seon.dev.config/artifact-flavor config)})))
          (validate-manifest! manifest))))))

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

(defn- build-source! [config]
  (run-step! config "prepare writer dependencies"
             ["clojure" "-X:deps" "prep" ":aliases" "[:writer]"])
  (run-step! config "prepare CLJS dependencies"
             ["clojure" "-X:deps" "prep" ":aliases" "[:cljs]"])
  (run-step! config "warm writer classpath" ["clojure" "-P" "-M:writer"])
  (run-step! config "warm CLJS classpath" ["clojure" "-P" "-M:cljs"])
  (run-step! config "build canonical database server"
             ["clojure" "-T:build" "writer-uber"])
  ;; `--preflight` is explicitly the live embedding round-trip gate. Its
  ;; correct master-OFF result is exit 11, not a failed writer artifact.
  (when (get-in config [:seon.dev.config/environment "SEON_EMBED"])
    (run-step! config "preflight embedding-backed database server"
               [(get-in config [:seon.dev.config/environment "JAVA_CMD"] "java")
                "--add-modules" "jdk.incubator.vector"
                "--enable-native-access=ALL-UNNAMED"
                "-XX:+UseG1GC" "-Xmx2g" "-jar"
                (:seon.dev.config/writer-output config) "--preflight"]))
  (run-step! config "build client"
             (cljs-command config "compile"
                           (:seon.dev.config/client-build-id config)))
  (run-step! config "build self-host bootstrap"
             (cljs-command config "compile" "bootstrap"))
  (run-step! config "repair bootstrap macro metadata"
             [(str (fs/path (:seon.dev.config/root config)
                            "bin/fix-bootstrap-macros"))])
  (run-step! config "build web CSS" ["npm" "run" "css:build"]))

(defn- output-manifest [config]
  (let [root (:seon.dev.config/root config)
        writer (:seon.dev.config/writer-output config)
        client-runtime
        (fs/path (:seon.dev.config/shadow-cache-root config)
                 "builds" (:seon.dev.config/client-build-id config)
                 "dev/out/cljs-runtime")
        bootstrap (fs/path root "out/bootstrap")
        css (fs/path root "resources/public/css/output.css")
        required [writer (:seon.dev.config/client-output config) bootstrap css]
        missing (remove #(or (fs/regular-file? %) (fs/directory? %)) required)]
    (when (seq missing)
      (throw (ex-info "Canonical build did not publish every required output."
                      {:seon.dev.artifact/missing (mapv str missing)})))
    (let [writer-digest (digest-jar writer)
          client-digest (digest-paths root [(:seon.dev.config/client-output config)
                                            client-runtime])
          bootstrap-digest (digest-paths root [bootstrap])
          css-digest (digest-paths root [css])
          application-digest
          (digest-values ["flavor" (:seon.dev.config/artifact-flavor config)
                          "client-build-id"
                          (:seon.dev.config/client-build-id config)
                          "client-output"
                          (:seon.dev.config/client-output config)
                          "shadow-cache-root"
                          (:seon.dev.config/shadow-cache-root config)
                          "client" client-digest
                          "bootstrap" bootstrap-digest
                          "css" css-digest])]
      (validate-manifest!
        {:seon.dev.artifact/version 2
         :seon.dev.artifact/published-at (str (Instant/now))
         :seon.dev.artifact/flavor
         (:seon.dev.config/artifact-flavor config)
         :seon.dev.artifact/client-build-id
         (:seon.dev.config/client-build-id config)
         :seon.dev.artifact/shadow-cache-root
         (:seon.dev.config/shadow-cache-root config)
         :seon.dev.artifact/client-output
         (:seon.dev.config/client-output config)
         :seon.dev.artifact/writer-digest writer-digest
         :seon.dev.artifact/client-digest client-digest
         :seon.dev.artifact/bootstrap-digest bootstrap-digest
         :seon.dev.artifact/css-digest css-digest
         :seon.dev.artifact/application-digest application-digest}))))

(defn build!
  "Build and atomically publish one canonical artifact manifest."
  [config]
  (if (:seon.dev.config/source-checkout? config)
    (do
      (build-source! config)
      (let [previous (read-manifest config)
            manifest (output-manifest config)
            changed (cond-> #{}
                      (not= (:seon.dev.artifact/writer-digest previous)
                            (:seon.dev.artifact/writer-digest manifest))
                      (conj :seon.dev.artifact/writer)

                      (not= (:seon.dev.artifact/application-digest previous)
                            (:seon.dev.artifact/application-digest manifest))
                      (conj :seon.dev.artifact/application))]
        (atomic-spit! (:seon.dev.config/artifact-manifest config) manifest)
        (assoc manifest :seon.dev.artifact/changed changed)))
    (let [manifest (or (read-manifest config)
                       (throw (ex-info "Packaged Seon is missing its artifact manifest."
                                       {:seon.dev.artifact/path
                                        (:seon.dev.config/artifact-manifest config)})))]
      (assoc manifest :seon.dev.artifact/changed #{}))))
