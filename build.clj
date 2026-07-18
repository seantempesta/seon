(ns build
  (:require [clojure.tools.build.api :as b]))

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

(defn writer-uber [_]
  (let [writer-basis (b/create-basis {:project "deps.edn"
                                      :aliases writer-aliases})
        server-class-dir "target/database-server-classes"
        server-uber-file "target/seon-database-server-standalone.jar"]
    (b/delete {:path server-class-dir})
    (b/delete {:path server-uber-file})
    ;; Seon src → class-dir → jar. Datahike's `src-secondary` arrives through
    ;; the writer dependency basis. Konserve's real version resource comes
    ;; from its pinned dependency and is included by the uber dependency basis.
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir server-class-dir})
    ;; Clojure AOT recursively recompiles loaded dependency sources and can
    ;; reorder captured-local slots across otherwise identical JVM builds.
    ;; The stable Java entry point defers the ordinary source `require` to
    ;; process start, preserving one server implementation and reproducible
    ;; artifact bytes.
    (b/javac {:basis writer-basis
              :src-dirs ["java"]
              :class-dir server-class-dir
              :javac-opts ["--release" "11"]})
    (b/uber {:class-dir server-class-dir
             :uber-file server-uber-file
             :basis writer-basis
             :main 'seon.DatabaseServerMain})
    (println "writer-uber → " server-uber-file)))
