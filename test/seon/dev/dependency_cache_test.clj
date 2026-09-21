(ns seon.dev.dependency-cache-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev-cache :as dev-cache]
            [seon.dev.state :as state]
            [seon.cluster.process :as operator.process]
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
    (spit first-file
          (str "(ns cache-probe.first)\n"
               "(def value :first-loaded)\n"
               "(defn exhaust-soft-references! []\n"
               "  (let [held (java.util.ArrayList.)]\n"
               "    (try\n"
               "      (loop []\n"
               "        (.add held (byte-array (* 1024 1024)))\n"
               "        (recur))\n"
               "      (catch OutOfMemoryError _))\n"
               "    (.clear held)\n"
               "    (dotimes [_ 4]\n"
               "      (System/gc)\n"
               "      (System/runFinalization))))\n"
               "(defn lazy-value [] ((fn [] :first-lazy-loaded)))\n"))
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
        result
        (operator.process/run-process!
         {:seon.operator.subprocess/argv
          ["clojure" "-Sdeps"
           (pr-str {:paths [(.getCanonicalPath (io/file source-root))]})
           "-M" "-e" form]
          :seon.operator.subprocess/directory (.getPath project-root)
          :seon.operator.subprocess/deadline-ms 120000
          :seon.operator.subprocess/merge-error? true})
        output (:seon.operator.subprocess/output result)
        exit (:seon.operator.subprocess/exit result)]
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
     ["java" "-Xmx96m" "-XX:SoftRefLRUPolicyMSPerMB=0" "-cp"
      (str cache-path java.io.File/pathSeparator
           (System/getProperty "java.class.path"))
      "clojure.main" "-e"
      (pr-str
       '(do
          (require 'cache-probe.first)
          (println :first-loaded)
          (flush)
          (read-line)
          (cache-probe.first/exhaust-soft-references!)
          (require 'cache-probe.second)
          (prn [(cache-probe.first/lazy-value)
                cache-probe.second/value])
          (flush)))])
    (.directory project-root)
    (.redirectErrorStream true))))

(defn- process-record
  [^Process process cache-path]
  (let [start (.startInstant (.info (.toHandle process)))]
    (when-not (.isPresent start)
      (throw (ex-info "The cache probe process has no start instant." {})))
    {:seon.operator.process-record/generation (random-uuid)
     :seon.boot/pid (.pid process)
     :seon.boot/start-instant (java.util.Date/from (.get start))
     :seon.operator.process-record/cache-path cache-path}))

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
           "Preserves delayed AOT classes across refresh and heap pressure."}
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
                           (str (:seon.operator.process-record/generation record) ".edn"))]
              (state/write-edn! reference-file record)
              (let [second-file (io/file source-root "cache_probe/second.clj")]
                (spit second-file
                      "(ns cache-probe.second)\n(def value :second-rebuilt)\n")
                (.setLastModified second-file 1))
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
                (testing "the old cache remains loadable after maximal soft-reference pressure"
                  (is (= [:first-lazy-loaded :second-loaded]
                         (edn/read-string
                          (test-support/await-event!
                           (future (.readLine ^java.io.BufferedReader reader))
                           :lazy-classes-after-pressure-and-refresh)))))
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

;;; ---------------------------------------------------------------------------
;;; Dependency pins: the cache digest refuses an absent pin source
;;;
;;; A gate run root is a `git archive` extraction nested under the source
;;; repository. `git ls-files --stage -- reference-code` answers for the prefix
;;; it runs in, so such a root without its own work tree answers exit 0 with
;;; ZERO bytes — a pin set that digests identically no matter where every fork
;;; sits. `bin/test` records the source repository's pins into the root, and
;;; the derivation refuses when neither source states them.

(defn- pins-file-name
  []
  @(private-var 'dependency-pins-file))

(defn- snapshot-root!
  "A run-root-shaped directory: `deps.edn`, optional recorded pins, no work tree."
  [pins]
  (let [root (fresh-root)]
    (io/copy (io/file project-root "deps.edn") (io/file root "deps.edn"))
    (when pins
      (spit (io/file root (pins-file-name)) pins))
    root))

(defn- configuration-digest
  [root]
  ((private-var 'dependency-configuration-digest)
   (.getCanonicalPath (io/file root))))

(def ^:private probe-pins
  (str "160000 e11845bac78e1241bca0766ddc07d978bd63d74a 0\treference-code/datahike\n"
       "160000 fcbd8862800e638dc0f8f5521111f999279cbcd2 0\treference-code/sci\n"))

(def ^:private moved-probe-pins
  (str/replace probe-pins
               "e11845bac78e1241bca0766ddc07d978bd63d74a"
               "73afe782000000000000000000000000000000ff"))

(deftest a-snapshot-roots-recorded-pins-decide-its-dependency-cache-digest
  (let [pinned (snapshot-root! probe-pins)
        pinned-again (snapshot-root! probe-pins)
        moved (snapshot-root! moved-probe-pins)]
    (try
      (testing "the same recorded pins select the same immutable cache"
        (is (= (configuration-digest pinned)
               (configuration-digest pinned-again))))
      (testing "a moved fork commit selects a different cache"
        (is (not= (configuration-digest pinned)
                  (configuration-digest moved))))
      (finally
        (run! test-support/delete-recursively! [pinned pinned-again moved])))))

(deftest a-root-with-no-pin-source-refuses-instead-of-digesting-nothing
  (let [root (snapshot-root! nil)]
    (try
      (testing "the git query is silent, not empty-handed, in such a root"
        (is (nil? ((private-var 'git-dependency-pins)
                   (.getCanonicalPath root)))))
      (testing "the refusal is a typed value naming the root and both sources"
        (let [refusal (try (configuration-digest root)
                           ::no-refusal
                           (catch clojure.lang.ExceptionInfo failure
                             (ex-data failure)))]
          (is (= :seon.dev-cache/dependency-pins-unavailable
                 (:seon.error/kind refusal)))
          (is (= :seon.dev-cache/no-pin-source
                 (get-in refusal [:seon.error/data
                                  :seon.error/diagnostic-cause])))
          (is (= (.getCanonicalPath root)
                 (get-in refusal [:seon.error/data
                                  :seon.error/diagnostic-offending])))
          (is (= (pins-file-name)
                 (get-in refusal [:seon.error/data
                                  :seon.error/diagnostic-expected
                                  :seon.dev-cache/recorded-pins-file])))
          (is (false? (get-in refusal [:seon.error/data
                                       :seon.error/diagnostic-evidence
                                       :seon.dev-cache/recorded-pins-present])))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest a-recorded-snapshot-digests-exactly-as-the-checkout-it-came-from
  (let [checkout-pins ((private-var 'dependency-pins)
                       (.getCanonicalPath project-root))
        recorded (snapshot-root! checkout-pins)]
    (try
      (is (not (str/blank? checkout-pins)))
      (is (re-matches #"[0-9a-f]{64}" (configuration-digest project-root)))
      (testing "a recorded run root reuses the checkout's own dependency classes"
        (is (= (configuration-digest project-root)
               (configuration-digest recorded))))
      (finally
        (test-support/delete-recursively! recorded)))))
