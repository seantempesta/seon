(ns build
  "Build script for libdatahike-WASM spike.

   The problem
   ===========
   Under svm-wasm, SubstrateVM cannot define classes at runtime.  Clojure's
   `clojure.lang.RT.<clinit>` (which runs at runtime when --initialize-at-
   build-time is unreliable, as is the case under svm-wasm) calls
   `RT.doInit()` which loads `clojure.core` and `clojure.core.server`.
   If either of these falls through to .clj source compilation, the build
   crashes with:

     UnsupportedFeatureError: Classes cannot be defined at runtime ...
       Tried to define class: user$eval1

   What's already correct in deps.edn
   ==================================
   1. `-H:IncludeResources=.*\\.(properties|class)$` -- .clj/.cljc are
      excluded, so even if RT.load is called, no .clj source is in the
      resource heap to tempt the runtime compiler.
   2. `--features=clj_easy.graal_build_time.InitClojureClasses` -- scans
      `classes/` at build time and registers each AOT'd class for build-
      time initialization.
   3. The wasm-shim namespace requires `clojure.core.server`,
      `clojure.spec.alpha`, etc. to pull them into the AOT closure.

   Why a build.clj is still needed
   ===============================
   `clojure.lang.Compiler` is a no-op for namespaces that are ALREADY
   loaded in the build JVM.  On a fresh JVM:

     (clojure.core.protocols clojure.core.server clojure.edn clojure.instant
      clojure.java.io clojure.main clojure.spec.alpha clojure.spec.gen.alpha
      clojure.string clojure.uuid clojure.walk)

   are already in *loaded-libs* before our build script even starts.  So
   `(compile 'clojure.core.server)` does NOTHING -- it does not emit
   `clojure/core/server__init.class` into `classes/`.

   With no `clojure/core/server__init.class` in `classes/`, the graal-
   build-time Feature has nothing to register, and svm-wasm's analyzer
   doesn't recognize the string-driven `RT.doInit -> Class.forName(\"clojure.core.server__init\")`
   path as reachable.  At runtime, the class isn't in the registry; RT
   falls back to source compilation; boom.

   The fix: physically extract `__init.class` + companion classes for the
   pre-loaded bootstrap namespaces from their jars into `classes/`.

   Pipeline
   ========
   1. Clean `classes/`.
   2. AOT-compile the wasm-shim + user namespaces.  This drags in
      everything reachable from the user code that is NOT already in the
      build JVM's *loaded-libs*.
   3. Extract `__init.class` + helper classes for the bootstrap-loaded
      namespaces from their owning jars into `classes/` -- filling the
      gap left by step 2's no-op compiles.
   4. Invoke native-image with the same flags as the :native-image alias."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as ns-find]
            [clj.native-image :as cni])
  (:import (java.io File)
           (java.util.jar JarFile)
           (java.util.zip ZipEntry)))

;; -----------------------------------------------------------------------------
;; Namespaces whose __init.class + helper classes must be physically extracted
;; from their owning jars into classes/ because they are already in the build
;; JVM's *loaded-libs* before user-code compile runs (so `compile` is a no-op
;; for them).
;;
;; Source: `(deref @#'clojure.core/*loaded-libs*)` in a fresh `clojure` REPL
;; under deps.edn, minus those that we don't need at WASM runtime.
;;
;; Plus: `clojure.core` itself (the root, loaded before any compile can
;; intercept it) and `clojure.core_print` / `clojure.core_proxy` / etc. that
;; clojure.core (load)s eagerly during bootstrap.
;; -----------------------------------------------------------------------------

(def ^:private bootstrap-loaded-namespaces
  "Namespace symbols whose __init.class files must be extracted from jars.
   These are the namespaces already loaded in the build JVM before user
   compilation runs, so `(compile 'ns)` is a no-op for them."
  '[clojure.core
    clojure.core.protocols
    clojure.core.server
    clojure.core_deftype
    clojure.core_print
    clojure.core_proxy
    clojure.edn
    clojure.genclass
    clojure.gvec
    clojure.instant
    clojure.java.io
    clojure.main
    clojure.spec.alpha
    clojure.spec.gen.alpha
    clojure.string
    clojure.uuid
    clojure.walk])

(defn- ns-sym->path
  "Convert a namespace symbol to a path prefix for class lookup.
   `clojure.core.server` -> `clojure/core/server`
   `clojure.core_print`  -> `clojure/core_print`
   (Note: clojure.core ships compiled with the legacy underscored helper
   names like `core_proxy` rather than `core-proxy`; the namespace symbol
   here already uses the underscored form so no munging is needed.)"
  [ns-sym]
  (str/replace (str ns-sym) "." "/"))

(defn- class-matches-ns?
  "Is `entry-name` (e.g. `clojure/core/server$start_server.class`) one of the
   class files owned by `ns-path` (e.g. `clojure/core/server`)?

   We match `<path>.class`, `<path>__init.class`, and `<path>$<anything>.class`.
   We must NOT match `clojure/core_print.class` when scanning for
   `clojure/core` because they share a prefix -- so the boundary after the
   path must be `.`, `__init.`, or `$`."
  [ns-path entry-name]
  (and (str/ends-with? entry-name ".class")
       (or (= entry-name (str ns-path ".class"))
           (str/starts-with? entry-name (str ns-path "__init"))
           (str/starts-with? entry-name (str ns-path "$")))))

(defn- classpath-jars
  "Return the list of .jar paths on the current JVM classpath."
  []
  (->> (str/split (System/getProperty "java.class.path")
                  (re-pattern (str File/pathSeparatorChar)))
       (filter #(str/ends-with? % ".jar"))))

(defn- extract-class!
  "Copy ZipEntry `entry` from `jar` into `out-dir`."
  [^JarFile jar ^ZipEntry entry ^File out-dir]
  (let [out-file (io/file out-dir (.getName entry))]
    (io/make-parents out-file)
    (with-open [in  (.getInputStream jar entry)
                out (io/output-stream out-file)]
      (io/copy in out))))

(defn extract-bootstrap-classes!
  "Walk every jar on the classpath; for each bootstrap-loaded namespace,
   extract its `<path>.class`, `<path>__init.class`, and `<path>$*.class`
   files into `out-dir`.  Returns a {ns count} map for reporting."
  [out-dir]
  (let [out (io/file out-dir)
        counts (atom (zipmap bootstrap-loaded-namespaces (repeat 0)))]
    (.mkdirs out)
    (doseq [jar-path (classpath-jars)
            :let [jar-file (io/file jar-path)]
            :when (.exists jar-file)]
      (with-open [jar (JarFile. jar-file)]
        (doseq [^ZipEntry entry (enumeration-seq (.entries jar))
                :when (not (.isDirectory entry))
                :let [entry-name (.getName entry)]
                ns-sym bootstrap-loaded-namespaces
                :let [ns-path (ns-sym->path ns-sym)]
                :when (class-matches-ns? ns-path entry-name)]
          (extract-class! jar entry out)
          (swap! counts update ns-sym inc))))
    @counts))

;; -----------------------------------------------------------------------------
;; Native-image invocation
;; -----------------------------------------------------------------------------

(def ^:private native-image-args
  "Mirror the flags from the :native-image alias in deps.edn.  Kept in sync
   manually -- if you change one, change the other."
  ["--tool:svm-wasm"
   "--features=clj_easy.graal_build_time.InitClojureClasses"
   "--no-fallback"
   "--emit" "build-report" "-Os"
   "-H:IncludeLocales=en"
   "-H:IncludeResources=.*\\.(properties|class)$"
   "-H:Name=core"
   "-H:+AllowDeprecatedBuilderClassesOnImageClasspath"
   "-J--patch-module=org.graalvm.wrapped.google.guava=stub-classes"
   "-J--add-reads=org.graalvm.wrapped.google.guava=jdk.unsupported"])

(def ^:private main-ns
  "Build-time entry namespace (the wasm-shim, not the user's spike ns)."
  "seon.podhost.libdatahike.wasm-shim")

(defn build
  "Pre-AOT bootstrap-loaded Clojure namespaces, then invoke native-image.
   Invocable via `clojure -X:build build` once the :build alias is added."
  [_]
  (binding [*compile-path* "classes"]
    (let [classes-dir (io/file *compile-path*)]
      ;; 1. Clean classes/ ourselves.  We do NOT call clj.native-image/build
      ;;    because its prep-compile-path nukes classes/ AFTER we'd want our
      ;;    extracted bootstrap classes there.  We replicate cni/build's
      ;;    logic inline, with the extraction in the right place (between
      ;;    AOT compile and native-image invocation).
      (when (.exists classes-dir)
        (doseq [f (reverse (rest (file-seq classes-dir)))]
          (io/delete-file f true)))
      (.mkdirs classes-dir)

      ;; 2. AOT-compile the wasm-shim namespace + user namespaces.  This
      ;;    drags in everything reachable that ISN'T already in *loaded-libs*.
      (println "AOT-compiling user namespaces ...")
      (binding [*compile-files* true]
        ;; Match clj.native-image: compile main + every namespace found
        ;; in :paths.
        (let [deps-map (cni/merged-deps)
              src-namespaces (mapcat
                              (fn [p] (ns-find/find-namespaces-in-dir (io/file p)))
                              (:paths deps-map))]
          (doseq [ns-sym (distinct (cons (symbol main-ns) src-namespaces))]
            (println "  compile" ns-sym)
            (compile ns-sym))))

      ;; 3. Extract __init.class + helper classes for namespaces that were
      ;;    pre-loaded by the build JVM (so step 2's compile was a no-op
      ;;    for them).
      (println "Extracting bootstrap-loaded namespace classes from jars ...")
      (let [counts (extract-bootstrap-classes! classes-dir)]
        (doseq [[ns-sym n] (sort-by key counts)]
          (println (format "  %-32s %4d classes" ns-sym n)))
        (when (some zero? (vals counts))
          (binding [*out* *err*]
            (println "WARN: some bootstrap namespaces yielded zero classes:")
            (doseq [[ns-sym n] counts :when (zero? n)]
              (println "  -" ns-sym))
            (println "Check that the namespace name matches a jar entry."))))

      ;; 4. Invoke native-image with the same args as the :native-image alias.
      (println "Invoking native-image ...")
      (let [bin (cni/native-image-bin-path)]
        (when-not bin
          (binding [*out* *err*]
            (println "Could not find native-image; set GRAALVM_HOME")
            (System/exit 1)))
        (let [exit (cni/exec-native-image
                    bin
                    native-image-args
                    (cni/native-image-classpath)
                    (str/replace main-ns "-" "_"))]
          (shutdown-agents)
          (System/exit (int exit)))))))
