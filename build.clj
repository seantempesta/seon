(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'seon/seon)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; The standalone artifact and the source-launched writer are the same program.
;; :writer replaces the broad project graph and owns the maintained forks,
;; secondary-index source, JVM flags, and writer-only dependencies in one basis.
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

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'seon.core}))

;; ---------------------------------------------------------------------------
;; writer-uber — the self-contained embedding-backed WIRE-SERVER uberjar.
;;
;; This is the artifact a third party runs with NO Seon source checkout:
;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
;;        -XX:+UseG1GC -Xmx2g -jar target/seon-wire-server-standalone.jar --preflight
;;
;; It is built from the same complete `:writer` basis as the source launcher
;; (NOT the default `basis` the `uber` target above uses — that one is the
;; PAUSED JVM app `seon.core`, with no embeddings classpath). Building from
;; that exact basis bakes EVERYTHING the consumer would otherwise have to
;; assemble themselves into one jar:
;;   - the datahike fork's `src-secondary` Proximum source;
;;   - the datahike fork's prep output (no `clojure -X:deps prep`);
;;   - the proximum + google-genai maven jars + the SHA-pinned forks.
;; The only things the jar can't bake (consumer must supply): Java 22+, the JVM
;; vector flags, `SEON_EMBED`, and `GEMINI_API_KEY`. `--preflight` makes the
;; absence of any of those a LOUD non-zero exit (see seon.embed.preflight).
;;
;; LOCAL bindings (own basis + own class-dir), NOT the file-level globals — so
;; this artifact's build cannot race/clobber the `uber`/`jar` targets (C19).

(defn writer-uber [_]
  (let [writer-basis (b/create-basis {:project "deps.edn"
                                      :aliases writer-aliases})
        wclass-dir   "target/wire-classes"
        wuber-file   "target/seon-wire-server-standalone.jar"]
    (b/delete {:path wclass-dir})
    (b/delete {:path wuber-file})
    ;; src + resources + the :writer src-secondary Proximum source →
    ;; class-dir → jar. Konserve's real version resource comes from its
    ;; pinned dependency and is included by the uber dependency basis.
    (b/copy-dir {:src-dirs ["src" "resources"
                            "reference-code/datahike/src-secondary"]
                 :target-dir wclass-dir})
    ;; AOT the secondary-index impl + the embed + boot namespaces. AOT itself
    ;; does NOT register :proximum (that swap! runs when boot.clj REQUIRES the
    ;; proximum ns at runtime); it only precompiles bytecode and surfaces a
    ;; missing optional dep at build time instead of first-call.
    (b/compile-clj {:basis writer-basis
                    :src-dirs ["src" "reference-code/datahike/src-secondary"]
                    :class-dir wclass-dir
                    :ns-compile '[datahike.index.secondary.proximum
                                  seon.embed
                                  seon.embed.preflight
                                  seon.server.boot]})
    (b/uber {:class-dir wclass-dir
             :uber-file wuber-file
             :basis writer-basis
             :main 'seon.server.boot})    ; boot.clj has (:gen-class)
    (println "writer-uber → " wuber-file)))
