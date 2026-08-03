(ns seon.dev.dependency-cache-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev-cache :as dev-cache]
            [seon.dev.state :as state]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private project-root
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(defn- fresh-root
  []
  (let [root (io/file project-root "tmp" "dependency-cache-test"
                      (str (random-uuid)))]
    (.mkdirs root)
    root))

(defn- private-var
  [sym]
  (or (ns-resolve 'dev-cache sym)
      (throw (ex-info "The dependency-cache test seam is absent."
                      {:seon.dev-cache/symbol sym}))))

(defn- source-row
  [source-root namespace-symbol]
  (let [path (str (-> (str namespace-symbol)
                      (str/replace "." "/")
                      (str/replace "-" "_"))
                  ".clj")
        file (io/file source-root path)]
    {:seon.dev-cache/namespace namespace-symbol
     :seon.dev-cache/source-url (str (.toURL (.toURI file)))}))

(defn- write-probe-sources!
  [source-root]
  (let [first-file (io/file source-root "cache_probe/first.clj")
        second-file (io/file source-root "cache_probe/second.clj")]
    (.mkdirs (.getParentFile first-file))
    (spit first-file "(ns cache-probe.first)\n(def value :first-loaded)\n")
    (spit second-file "(ns cache-probe.second)\n(def value :second-loaded)\n")
    ;; Loader classes must be strictly newer than source under RT/load.
    (.setLastModified first-file 1)
    (.setLastModified second-file 1)
    [(source-row source-root 'cache-probe.first)
     (source-row source-root 'cache-probe.second)]))

(defn- compile-probes!
  [source-root rows staging]
  (let [form
        (pr-str
         `(binding [*compile-path* ~(.getCanonicalPath (io/file staging))]
            ~@(map (fn [row]
                     `(compile '~(:seon.dev-cache/namespace row)))
                   rows)))
        process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["clojure" "-Sdeps"
            (pr-str {:paths [(.getCanonicalPath (io/file source-root))]})
            "-M" "-e" form])
          (.directory project-root)
          (.redirectErrorStream true)))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "The cache probe namespaces did not compile."
                      {:seon.dev-cache/exit exit
                       :seon.dev-cache/output output})))
    rows))

(defn- start-lazy-loader!
  [cache-path]
  (.start
   (doto
    (ProcessBuilder.
     ^java.util.List
     ["java" "-cp"
      (str cache-path java.io.File/pathSeparator
           (System/getProperty "java.class.path"))
      "clojure.main" "-e"
      (pr-str
       '(do
          (require 'cache-probe.first)
          (println :first-loaded)
          (flush)
          (read-line)
          (require 'cache-probe.second)
          (println cache-probe.second/value)
          (flush)))])
    (.directory project-root)
    (.redirectErrorStream true))))

(defn- process-record
  [^Process process cache-path]
  (let [start (.startInstant (.info (.toHandle process)))]
    (when-not (.isPresent start)
      (throw (ex-info "The cache probe process has no start instant." {})))
    {:seon.dev.process/generation (random-uuid)
     :seon.dev.process/pid (.pid process)
     :seon.dev.process/start-instant (str (.get start))
     :seon.dev.process/cache-path cache-path}))

(defn- directory-state
  [root]
  (into (sorted-map)
        (comp
         (filter #(.isFile ^java.io.File %))
         (map (fn [file]
                [(str (.relativize (.toPath (io/file root))
                                   (.toPath ^java.io.File file)))
                 [(.length ^java.io.File file)
                  (.lastModified ^java.io.File file)]])))
        (file-seq (io/file root))))

(deftest ^{:seon.test/long
           "Publishes a second real loader directory while a child uses the first."}
  refresh-preserves-a-recorded-jvms-exact-cache-directory
  (let [root (fresh-root)
        source-root (io/file root "source")
        cache-root (io/file root "cache")
        staging-root (io/file root "staging")
        selection-file (io/file root "current.edn")
        references-root (io/file root "processes")
        lock-file (io/file root "cache.lock")
        result-file (io/file root "result.edn")
        rows (write-probe-sources! source-root)
        project-digest (atom "project-a")
        child (atom nil)]
    (try
      (with-redefs-fn
        {(private-var 'cache-root) (.getCanonicalPath cache-root)
         (private-var 'staging-root) (.getCanonicalPath staging-root)
         (private-var 'selection-file) (.getCanonicalPath selection-file)
         (private-var 'process-reference-root)
         (.getCanonicalPath references-root)
         (private-var 'lock-file) (.getCanonicalPath lock-file)
         (private-var 'result-file) (.getCanonicalPath result-file)
         (private-var 'project-digest) #(deref project-digest)
         (private-var 'run-build!)
         (fn [_basis staging]
           (compile-probes! source-root rows staging))}
        (fn []
          (let [first-cache (:seon.dev-cache/path (dev-cache/refresh nil))
                before (directory-state first-cache)
                process (start-lazy-loader! first-cache)
                reader (io/reader (.getInputStream process))
                writer (io/writer (.getOutputStream process))]
            (reset! child process)
            (is (= ":first-loaded"
                   (test-support/await-event!
                    (future (.readLine ^java.io.BufferedReader reader))
                    :first-loader-ready)))
            (let [record (process-record process first-cache)
                  reference-file
                  (io/file references-root
                           (str (:seon.dev.process/generation record) ".edn"))]
              (state/write-edn! reference-file record)
              (reset! project-digest "project-b")
              (let [second-cache
                    (:seon.dev-cache/path (dev-cache/refresh nil))
                    reaped (dev-cache/reap nil)]
                (testing "refresh publishes a distinct immutable directory"
                  (is (not= first-cache second-cache))
                  (is (.isDirectory (io/file first-cache)))
                  (is (= before (directory-state first-cache))))
                (testing "recorded process identity protects the exact old path"
                  (is (= 1 (:seon.dev-cache/live-processes reaped)))
                  (is (not (some #{first-cache}
                                 (:seon.dev-cache/reaped reaped)))))
                (.write ^java.io.Writer writer "load-second\n")
                (.flush ^java.io.Writer writer)
                (is (= ":second-loaded"
                       (test-support/await-event!
                        (future (.readLine ^java.io.BufferedReader reader))
                        :second-loader-after-refresh)))
                (is (.waitFor process 10 TimeUnit/SECONDS))
                (let [after-exit (dev-cache/reap nil)]
                  (is (some #{first-cache}
                            (:seon.dev-cache/reaped after-exit)))
                  (is (.isDirectory (io/file second-cache)))))))))
      (finally
        (when-let [^Process process @child]
          (when (.isAlive process)
            (.destroyForcibly process)
            (.waitFor process 10 TimeUnit/SECONDS)))
        (test-support/delete-recursively! root)))))
