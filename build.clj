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
(def writer-aliases [:writer :host])

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
(def writer-aot-cds-file "target/seon-database-server-aot.jsa")

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

(def writer-aot-namespaces
  (edn/read-string (slurp "resources/seon/dev/writer-aot-namespaces.edn")))

(def writer-aot-excluded-namespaces
  ;; `compile-clj` creates a DynamicClassLoader while compiling this closure.
  ;; AOTing core.async then makes its timer entry visible from both loaders,
  ;; which fails its Comparable cast during superv.async initialization. Keep
  ;; those namespaces source-loaded; the measured Datahike/server closure is
  ;; still cacheable and the exclusion is explicit rather than accidental.
  #{'clojure.core.async
    'clojure.core.async.impl.buffers
    'clojure.core.async.impl.channels
    'clojure.core.async.impl.dispatch
    'clojure.core.async.impl.exec.threadpool
    'clojure.core.async.impl.ioc-macros
    'clojure.core.async.impl.mutex
    'clojure.core.async.impl.protocols
    'clojure.core.async.impl.timers})

(defn- writer-aot!
  "Build writer and shared JVM class caches from their measured closures."
  [writer-basis]
  (let [observed (aot-load-order writer-basis 'seon.db.server)]
    (when-not (= (set writer-aot-namespaces) (set observed))
      (throw (ex-info "The explicit writer AOT closure is stale."
                      {:expected writer-aot-namespaces
                       :observed observed}))))
  (b/delete {:path writer-aot-class-dir})
  ;; Isolate the compiler itself: the build task's loader otherwise coexists
  ;; with AOTed core classes from the writer closure. The child still uses the
  ;; maintained tools.build `compile-clj` idiom and the pinned JDK.
  (checked-process!
   {:command-args
    ["clojure"
     "-Sdeps" "{:deps {io.github.clojure/tools.build {:mvn/version \"0.10.5\"}}}"
     "-M" "-e"
     (str "(require '[clojure.tools.build.api :as b]) "
          "(b/compile-clj {:basis (b/create-basis {:project \"deps.edn\" :aliases [:writer]}) "
          ":class-dir " (pr-str writer-aot-class-dir) " "
          ":ns-compile (quote "
          (pr-str (vec (remove writer-aot-excluded-namespaces
                                      writer-aot-namespaces))) ") "
          ":java-cmd " (pr-str writer-java-command) " "
          ":java-opts " (pr-str writer-jvm-options) "})")]
    :out :capture :err :capture}
   "Isolated writer AOT compilation failed")
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

(defn- write-writer-cds!
  [server-uber-file archive-file]
  (b/delete {:path archive-file})
  ;; AppCDS records class metadata against this exact jar and JDK. It is a
  ;; build output, never a mutable runtime cache.
  (checked-process!
   {:command-args (into [writer-java-command]
                        (concat writer-jvm-options
                                [(str "-XX:ArchiveClassesAtExit=" archive-file)
                                 "-cp" server-uber-file "clojure.main" "-e"
                                 (str "(do (require 'seon.db.server "
                                      "'seon.host 'seon.web.server) "
                                      "(shutdown-agents))")]))
    :out :capture :err :capture}
   "Writer AppCDS archive generation failed")
  archive-file)

(defn- writer-uber!
  [aot? cds?]
  (let [writer-basis (b/create-basis {:project "deps.edn"
                                      :aliases writer-aliases})
        server-class-dir (if aot?
                           jvm-aot-class-dir
                           "target/database-server-classes")
        server-uber-file (if aot?
                           "target/seon-database-server-aot.jar"
                           "target/seon-database-server-standalone.jar")]
    (b/delete {:path server-class-dir})
    (b/delete {:path server-uber-file})
    (when aot?
      (writer-aot! writer-basis))
    ;; Seon src → class-dir → jar. Datahike's `src-secondary` arrives through
    ;; the writer dependency basis. Konserve's real version resource comes
    ;; from its pinned dependency and is included by the uber dependency basis.
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir server-class-dir})
    (b/javac {:basis writer-basis
              :src-dirs ["java"]
              :class-dir server-class-dir
              :javac-opts ["--release" "11"]})
    ;; Source always ships for `requiring-resolve`; AOT classes coexist only
    ;; in the explicit experimental artifact.
    (b/uber {:class-dir server-class-dir
             :uber-file server-uber-file
             :basis writer-basis
             :main 'seon.DatabaseServerMain})
    (when cds?
      (write-writer-cds! server-uber-file writer-aot-cds-file))
    (println "writer-uber → " server-uber-file)))

(defn writer-uber
  "Build the canonical shared JVM AOT artifact."
  [_]
  (writer-uber! true false))

(defn writer-cds
  "Build one AppCDS archive against its final published JVM artifact path."
  [{:keys [jar archive]}]
  (write-writer-cds! (str jar) (str archive)))

(defn writer-uber-aot
  "Explicit writer AOT experiment; never invoked by the development operator."
  [_]
  (writer-uber! true false))

(defn writer-uber-aot-cds
  "Explicit writer AOT plus AppCDS experiment; never invoked by the operator."
  [_]
  (writer-uber! true true))
