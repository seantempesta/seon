(ns build
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'seon/seon)
(def version
  ;; A released build SDK intentionally has no .git directory. Runtime
  ;; compatibility is carried by sdk.edn/release.edn, so the ordinary jar's
  ;; display version may fall back without making Git a consumer dependency.
  (format "0.1.%s"
          (or (System/getenv "SEON_BUILD_REVISION_COUNT")
              (when (.exists (java.io.File. ".git"))
                (b/git-count-revs nil))
              0)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; The standalone artifact and the source-launched writer are the same program.
;; :writer replaces the broad project graph and owns the maintained forks,
;; dependency-owned secondary-index source, JVM flags, and writer-only
;; dependencies in one basis.
(def writer-aliases [:writer])

;; The build owns this exact JDK because compiled classes and an AppCDS archive
;; are JVM-version specific. The development operator selects the same JDK.
(def writer-java-command
  "/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home/bin/java")

(def writer-jvm-options
  ["--add-modules" "jdk.incubator.vector"
   "--enable-native-access=ALL-UNNAMED"
   "--sun-misc-unsafe-memory-access=allow"
   "-XX:+UseG1GC" "-Xmx512m"])

(def writer-aot-class-dir "target/seon-writer-aot-classes")
(def jvm-aot-class-dir "target/seon-jvm-aot-classes")

(defn- checked-process!
  [params message]
  (let [result (b/process params)]
    (when-not (zero? (:exit result))
      (throw (ex-info message
                      {:exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    result))

(defn- aot-load-order
  "Discover one main's source-loaded namespace closure in dependency order."
  [basis root]
  (let [trace-dir "target/seon-jvm-aot-traces"
        trace-file (str trace-dir "/" (munge (str root)) ".log")
        classpath (str/join
                   java.io.File/pathSeparator
                   (remove #(contains? #{writer-aot-class-dir jvm-aot-class-dir}
                                       (str %))
                           (:classpath-roots basis)))
        expression
        (str "(do (require '" root ") "
             "(prn (into {} (map (juxt #(munge (str %)) identity) "
             "(loaded-libs)))) (shutdown-agents))")
        _ (.mkdirs (java.io.File. trace-dir))
        result (checked-process!
                {:command-args (into [writer-java-command
                                      (str "-Xlog:class+load=info:file=" trace-file
                                           ":uptime,level,tags")]
                                     (concat writer-jvm-options
                                             ["-cp" classpath "clojure.main"
                                              "-e" expression]))
                 :out :capture :err :capture}
                (str "AOT namespace discovery failed for " root))
        munged-namespaces
        (edn/read-string (last (str/split-lines (:out result))))
        class-names (->> (str/split-lines (slurp trace-file))
                         (keep (fn [line]
                                 (second
                                  (re-find
                                   #"\[class,load ?\] ([A-Za-z0-9_.$-]+)\$eval\d+\$loading__.*source: __JVM_DefineClass__"
                                   line))))
                         distinct)]
    (vec (reverse (keep munged-namespaces class-names)))))

(defn- compile-aot!
  "Compile a measured JVM main closure into one reusable class cache."
  [basis class-dir roots]
  (b/delete {:path class-dir})
  (doseq [root roots]
    (let [ns-compile (aot-load-order basis root)]
      (when-not (seq ns-compile)
        (throw (ex-info "AOT namespace discovery produced no source namespaces."
                        {:root root})))
      (b/compile-clj {:basis basis
                      :class-dir class-dir
                      :ns-compile ns-compile
                      :java-cmd writer-java-command
                      :java-opts writer-jvm-options}))))

(defn- writer-aot!
  "Build writer and shared JVM class caches from their measured closures."
  [writer-basis]
  (compile-aot! writer-basis writer-aot-class-dir
                ['seon.db.server 'seon.embed.preflight])
  ;; SCI's host closure is not AOT-admissible yet (`copy-vars` asserts during
  ;; compilation). Keep this experiment to the measured writer closure.
  (b/delete {:path jvm-aot-class-dir})
  (b/copy-dir {:src-dirs [writer-aot-class-dir]
               :target-dir jvm-aot-class-dir})
  writer-aot-class-dir)

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

;; ---------------------------------------------------------------------------
;; writer-uber — the self-contained embedding-backed database-server uberjar.
;;
;; This is the artifact a third party runs with NO Seon source checkout:
;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
;;        -XX:+UseG1GC -Xmx2g -jar target/seon-database-server-standalone.jar --preflight
;;
;; It is built from the same complete `:writer` basis as the source launcher.
;; That exact basis bakes EVERYTHING the consumer would otherwise have to
;; assemble themselves into one jar:
;;   - the datahike Git dependency's `src-secondary` Proximum source;
;;   - the datahike fork's prep output (no `clojure -X:deps prep`);
;;   - the Proximum Git dependency, google-genai jars, and SHA-pinned forks.
;; The only things the jar can't bake (consumer must supply): Java 22+, the JVM
;; vector flags, `SEON_EMBED`, and `GEMINI_API_KEY`. `--preflight` makes the
;; absence of any of those a LOUD non-zero exit (see seon.embed.preflight).
;;
;; LOCAL bindings (own basis + own class-dir), NOT the file-level globals — so
;; this artifact's build cannot race/clobber the source `jar` target (C19).

(defn- writer-uber!
  [aot?]
  (let [writer-basis (b/create-basis {:project "deps.edn"
                                      :aliases writer-aliases})
        server-class-dir "target/database-server-classes"
        server-uber-file (if aot?
                           "target/seon-database-server-aot.jar"
                           "target/seon-database-server-standalone.jar")]
    (b/delete {:path server-class-dir})
    (b/delete {:path server-uber-file})
    ;; Seon src → class-dir → jar. Datahike's `src-secondary` arrives through
    ;; the writer dependency basis. Konserve's real version resource comes
    ;; from its pinned dependency and is included by the uber dependency basis.
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir server-class-dir})
    (b/javac {:basis writer-basis
              :src-dirs ["java"]
              :class-dir server-class-dir
              :javac-opts ["--release" "11"]})
    (when aot?
      ;; Source stays in the jar for dynamic `requiring-resolve` fallbacks.
      ;; `javac` owns its class directory, so install the Clojure cache after it.
      (writer-aot! writer-basis)
      (b/copy-dir {:src-dirs [jvm-aot-class-dir]
                   :target-dir server-class-dir}))
    (b/uber {:class-dir server-class-dir
             :uber-file server-uber-file
             :basis writer-basis
             :main 'seon.DatabaseServerMain})
    (println "writer-uber → " server-uber-file)))

(defn writer-uber [_]
  (writer-uber! false))

(defn writer-uber-aot [_]
  (writer-uber! true))
