(ns dev-cache
  "Refreshes the source-preferred development class cache."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.io RandomAccessFile]
           [java.nio.file AtomicMoveNotSupportedException FileAlreadyExistsException
            Files StandardCopyOption]
           [java.security MessageDigest]))

(def cache-root "target/dev-dependency-classes")
(def staging-root "target/dev-dependency-classes.next")
(def result-file "target/dev-dependency-cache-result.edn")
(def selection-file "target/dev-dependency-cache-current.edn")
(def process-reference-root "target/dev-dependency-cache-processes")
(def lock-file "target/dev-dependency-cache.lock")
(def manifest-file "META-INF/seon-dev-cache.edn")
(def cache-version 3)

(defn- canonical-file
  [path]
  (.getCanonicalFile (io/file path)))

(defn- cached-path?
  [path]
  (.startsWith (.toPath (canonical-file path))
               (.toPath (canonical-file cache-root))))

(defn- uncached-classpath
  [basis]
  (->> (:classpath-roots basis)
       (remove cached-path?)
       (map #(.getCanonicalPath (canonical-file %)))
       distinct
       vec))

(defn- dependency-containers
  [basis]
  (let [files (->> (:libs basis)
                   vals
                   (mapcat :paths)
                   (map canonical-file)
                   distinct)]
    {:directories (->> files
                       (filter #(.isDirectory ^java.io.File %))
                       (map #(.getCanonicalPath ^java.io.File %))
                       sort vec)
     :archives (->> files
                    (filter #(.isFile ^java.io.File %))
                    (map #(.getCanonicalPath ^java.io.File %))
                    set)}))

(defn- discovery-form
  [{:keys [directories archives]} result]
  `(do
     (require '[clojure.java.io :as io]
              '[clojure.string :as str])
     (let [dependency-roots#
           (mapv #(.toPath (.getCanonicalFile (io/file %)))
                 ~directories)
           dependency-archives# (set ~archives)
           within-root?#
           (fn [file#]
             (let [path# (.toPath (.getCanonicalFile (io/file file#)))]
               (boolean (some #(.startsWith path# %) dependency-roots#))))
           root-for#
           (fn [lib#]
             (-> (str lib#) clojure.core/munge
                 (str/replace "." "/")))
           dependency-source?#
           (fn [url#]
             (case (.getProtocol url#)
               "file" (within-root?# (.toURI url#))
               "jar" (let [connection# (.openConnection url#)]
                       (contains?
                        dependency-archives#
                        (.getCanonicalPath
                         (.getCanonicalFile
                          (io/file (.toURI
                                    (.getJarFileURL connection#)))))))
               false))
           source-row#
           (fn [lib#]
             (let [root# (root-for# lib#)
                   url# (or (io/resource (str root# ".clj"))
                            (io/resource (str root# ".cljc")))
                   class-url# (io/resource (str root# "__init.class"))]
               (when (and url# (dependency-source?# url#)
                          (or (= "file" (.getProtocol url#))
                              (nil? class-url#)))
                 {:seon.dev-cache/namespace lib#
                  :seon.dev-cache/source-url (str url#)})))]
       (let [loads# (atom [])
             original-load# @#'clojure.core/load]
         (with-redefs [clojure.core/load
                       (fn [& paths#]
                         (let [result# (apply original-load# paths#)]
                           (swap! loads# into paths#)
                           result#))]
           (require 'seon.artifact))
         (let [loaded# (clojure.core/loaded-libs)
               by-root# (into {} (map (juxt root-for# identity)) loaded#)
               rows# (->> @loads#
                          (map #(str/replace-first % #"^/" ""))
                          (keep by-root#)
                          (keep source-row#)
                          (reduce
                           (fn [state# row#]
                             (let [lib# (:seon.dev-cache/namespace row#)
                                   seen# (:seen state#)
                                   rows# (:rows state#)]
                               (if (contains? seen# lib#)
                                 state#
                                 {:seen (conj seen# lib#)
                                  :rows (conj rows# row#)})))
                           {:seen #{} :rows []})
                          :rows)]
           (when (empty? rows#)
           (throw (ex-info "The loaded dependency closure is empty." {})))
           (spit ~result (str (pr-str rows#) "\n")))))))

(defn- compile-form
  [rows staging]
  `(binding [*compile-path* ~staging]
     (doseq [namespace-name# ~(mapv (comp str :seon.dev-cache/namespace)
                                    rows)]
       (compile (symbol namespace-name#)))))

(defn- run-child!
  [basis form failure-message rejected-path]
  (let [classpath (str/join java.io.File/pathSeparator
                            (uncached-classpath basis))
        command (into ["java"]
                      (concat (:jvm-opts basis)
                              ["-cp" classpath "clojure.main"
                               "-e" (pr-str form)]))
        process (b/process {:command-args command
                            :out :capture
                            :err :capture})]
    (when-not (zero? (:exit process))
      (throw
       (ex-info failure-message
                {:seon.dev-cache/exit (:exit process)
                 :seon.dev-cache/out (:out process)
                 :seon.dev-cache/err (:err process)
                 :seon.dev-cache/rejected rejected-path})))
    process))

(defn- run-build!
  [basis staging-dir]
  (let [staging (.getCanonicalPath (canonical-file staging-dir))
        result (.getCanonicalPath (canonical-file result-file))
        discovery (discovery-form (dependency-containers basis) result)]
    (run-child! basis discovery
                "Development dependency-cache discovery failed."
                staging)
    (when-not (.isFile (io/file result))
      (throw
       (ex-info "Development dependency-cache discovery wrote no result."
                {:seon.dev-cache/result result})))
    (let [rows (edn/read-string (slurp result))]
      (run-child! basis (compile-form rows staging)
                  "Development dependency-cache compilation failed."
                  staging)
      rows)))

(defn- loader-class
  [directory namespace-symbol]
  (io/file directory
           (str (-> (str namespace-symbol)
                    clojure.core/munge
                    (str/replace "." "/"))
                "__init.class")))

(defn- digest-bytes!
  [^MessageDigest digest value]
  (let [value-bytes (if (string? value)
                      (.getBytes ^String value
                                 java.nio.charset.StandardCharsets/UTF_8)
                      value)]
    (.update digest ^bytes value-bytes)
    (.update digest (byte-array [(byte 0)]))))

(defn- digest-file!
  [^MessageDigest digest file]
  (digest-bytes! digest (.getCanonicalPath ^java.io.File file))
  (with-open [input (io/input-stream file)]
    (let [buffer (byte-array 65536)]
      (loop []
        (let [read-count (.read input buffer)]
          (when (pos? read-count)
            (.update digest buffer 0 read-count)
            (recur))))))
  (.update digest (byte-array [(byte 0)])))

(defn- project-input-files
  []
  (let [source-root (io/file "src")]
    (->> (concat [(io/file "deps.edn")]
                 (file-seq source-root))
         (filter #(.isFile ^java.io.File %))
         (filter (fn [file]
                   (let [filename (.getName ^java.io.File file)]
                     (or (= "deps.edn" filename)
                         (str/ends-with? filename ".clj")
                         (str/ends-with? filename ".cljc")))))
         (map canonical-file)
         (sort-by #(.getCanonicalPath ^java.io.File %))
         vec)))

(defn- project-digest
  []
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [file (project-input-files)]
      (digest-file! digest file))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- sha-256
  [rows]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [row rows
            value [(str (:seon.dev-cache/namespace row))
                   (:seon.dev-cache/source-url row)
                   (slurp (:seon.dev-cache/source-url row))]]
      (digest-bytes! digest value))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- validate!
  [directory rows]
  (doseq [{namespace-symbol :seon.dev-cache/namespace
           source-url :seon.dev-cache/source-url} rows
          :let [class-file (loader-class directory namespace-symbol)
                source-connection (.openConnection (java.net.URL. source-url))
                source-mtime (.getLastModified source-connection)]]
    (when-not (.isFile class-file)
      (throw (ex-info "A selected namespace emitted no loader class."
                      {:seon.dev-cache/namespace namespace-symbol
                       :seon.dev-cache/class (.getPath class-file)})))
    (when-not (> (.lastModified class-file) source-mtime)
      (throw (ex-info "A loader class is not newer than its source."
                      {:seon.dev-cache/namespace namespace-symbol
                       :seon.dev-cache/class-mtime (.lastModified class-file)
                       :seon.dev-cache/source-mtime
                       source-mtime}))))
  (let [first-party-root (io/file directory "seon")]
    (when (and (.exists first-party-root)
               (some #(str/ends-with? (.getName ^java.io.File %)
                                      "__init.class")
                     (file-seq first-party-root)))
      (throw (ex-info "The dependency cache contains first-party classes."
                      {:seon.dev-cache/rejected directory})))))

(defn- write-manifest!
  [directory rows digest cache-digest project-source-digest duration-ms]
  (let [manifest (io/file directory manifest-file)]
    (.mkdirs (.getParentFile manifest))
    (spit manifest
          (str (pr-str {:seon.dev-cache/version cache-version
                        :seon.dev-cache/digest digest
                        :seon.dev-cache/cache-digest cache-digest
                        :seon.dev-cache/project-digest project-source-digest
                        :seon.dev-cache/namespaces
                        (mapv :seon.dev-cache/namespace rows)
                        :seon.dev-cache/sources rows
                        :seon.dev-cache/duration-ms duration-ms})
               "\n"))))

(defn- read-manifest
  [directory]
  (let [manifest (io/file directory manifest-file)]
    (when (.isFile manifest)
      (try
        (edn/read-string (slurp manifest))
        (catch Throwable _
          nil)))))

(defn- hex-digest
  [values]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [value values]
      (digest-bytes! digest (str value)))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- cache-identity
  [dependency-digest project-source-digest]
  (hex-digest [cache-version dependency-digest project-source-digest]))

(defn- cache-directory
  [cache-digest]
  (canonical-file (io/file cache-root cache-digest)))

(defn- valid-cache
  [directory expected-project-digest]
  (when-let [manifest (read-manifest directory)]
    (try
      (when (and (= cache-version (:seon.dev-cache/version manifest))
                 (= (.getName ^java.io.File (canonical-file directory))
                    (:seon.dev-cache/cache-digest manifest))
                 (= expected-project-digest
                    (:seon.dev-cache/project-digest manifest))
                 (= (sha-256 (:seon.dev-cache/sources manifest))
                    (:seon.dev-cache/digest manifest))
                 (every?
                  (fn [namespace-symbol]
                    (.isFile (loader-class directory namespace-symbol)))
                  (:seon.dev-cache/namespaces manifest)))
        manifest)
      (catch Throwable _
        nil))))

(defn- atomic-write-edn!
  [path value]
  (let [target (canonical-file path)
        parent (.getParentFile target)
        candidate (io/file parent (str (.getName target) "." (random-uuid)))]
    (.mkdirs parent)
    (try
      (spit candidate (str (pr-str value) "\n"))
      (try
        (Files/move (.toPath candidate) (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move (.toPath candidate) (.toPath target)
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      value
      (finally
        (Files/deleteIfExists (.toPath candidate))))))

(defn- selected-cache
  []
  (try
    (when-let [selection (when (.isFile (io/file selection-file))
                           (edn/read-string (slurp selection-file)))]
      (let [directory (canonical-file (:seon.dev-cache/path selection))]
        (when (= (.getCanonicalPath (canonical-file cache-root))
                 (.getCanonicalPath (.getParentFile directory)))
          selection)))
    (catch Throwable _
      nil)))

(defn- current-cache
  []
  (let [project-source-digest (project-digest)]
    (when-let [selection (selected-cache)]
      (let [directory (canonical-file (:seon.dev-cache/path selection))]
        (when-let [manifest (valid-cache directory project-source-digest)]
          {:seon.dev-cache/directory directory
           :seon.dev-cache/manifest manifest})))))

(defn- admit!
  [staging directory]
  (let [source (.toPath (canonical-file staging))
        target (.toPath (canonical-file directory))]
    (.mkdirs (.getParentFile (canonical-file directory)))
    (try
      (Files/move source target
                  (into-array StandardCopyOption
                              [StandardCopyOption/ATOMIC_MOVE]))
      (catch AtomicMoveNotSupportedException _
        (Files/move source target (make-array StandardCopyOption 0)))
      (catch FileAlreadyExistsException _
        nil))))

(defn- cache-result
  [directory manifest status]
  {:seon.dev-cache/digest (:seon.dev-cache/cache-digest manifest)
   :seon.dev-cache/source-digest (:seon.dev-cache/digest manifest)
   :seon.dev-cache/namespaces
   (count (:seon.dev-cache/namespaces manifest))
   :seon.dev-cache/status status
   :seon.dev-cache/path (.getCanonicalPath ^java.io.File directory)})

(defn- select-cache!
  [directory manifest]
  (atomic-write-edn!
   selection-file
   {:seon.dev-cache/digest (:seon.dev-cache/cache-digest manifest)
    :seon.dev-cache/path (.getCanonicalPath ^java.io.File directory)}))

(defn- with-cache-lock
  [transition]
  (let [lock-path (canonical-file lock-file)]
    (.mkdirs (.getParentFile lock-path))
    (with-open [file (RandomAccessFile. lock-path "rw")
                channel (.getChannel file)
                _ (.lock channel)]
      (transition))))

(defn- refresh!
  []
  (let [started (System/nanoTime)
        basis (b/create-basis {:project "deps.edn" :aliases [:dev]})
        project-source-digest (project-digest)
        staging (str staging-root "/" (random-uuid))]
    (b/delete {:path result-file})
    (.mkdirs (io/file staging))
    (println "seon cache: discovering the dependency closure")
    (flush)
    (try
      (let [rows (run-build! basis staging)
            dependency-digest (sha-256 rows)
            cache-digest (cache-identity dependency-digest
                                         project-source-digest)
            directory (cache-directory cache-digest)
            duration-ms (long (/ (- (System/nanoTime) started) 1000000))]
        (println "seon cache: compiled" (count rows) "dependency namespaces")
        (flush)
        (validate! staging rows)
        (write-manifest! staging rows dependency-digest cache-digest
                         project-source-digest duration-ms)
        (admit! staging directory)
        (let [manifest (or (valid-cache directory project-source-digest)
                           (throw
                            (ex-info
                             "The immutable dependency cache is invalid."
                             {:seon.dev-cache/path
                              (.getCanonicalPath directory)})))]
          (select-cache! directory manifest)
          (cache-result directory manifest :rebuilt)))
      (finally
        (b/delete {:path staging})))))

(defn refresh
  "Publish a new immutable cache for the dependency closure."
  [_]
  (let [result (with-cache-lock refresh!)]
    (prn result)
    result))

(defn ensure-cache
  "Reuse the current dependency cache, or rebuild it when inputs changed."
  [_]
  (let [result
        (with-cache-lock
          #(if-let [{:seon.dev-cache/keys [directory manifest]}
                    (current-cache)]
             (cache-result directory manifest :current)
             (do
               (println "seon cache: inputs changed; rebuilding")
               (flush)
               (refresh!))))]
    (prn result)
    result))

(defn- process-reference-files
  []
  (let [directory (io/file process-reference-root)]
    (if (.isDirectory directory)
      (->> (.listFiles directory)
           (filter #(.isFile ^java.io.File %))
           (sort-by #(.getName ^java.io.File %)))
      [])))

(defn- live-process-reference?
  [record]
  (try
    (let [pid (:seon.boot/pid record)
          start-instant (:seon.boot/start-instant record)
          cache-path (:seon.operator.process-record/cache-path record)
          optional (java.lang.ProcessHandle/of (long pid))]
      (when (and (pos-int? pid) (inst? start-instant)
                 (string? cache-path)
                 (= (.getCanonicalPath (canonical-file cache-root))
                    (.getCanonicalPath
                     (.getParentFile (canonical-file cache-path))))
                 (.isPresent optional))
        (let [handle (.get optional)
              observed (.startInstant (.info handle))]
          (and (.isAlive handle)
               (.isPresent observed)
               (= (.getTime ^java.util.Date start-instant)
                  (.toEpochMilli ^java.time.Instant (.get observed)))))))
    (catch Throwable _
      false)))

(defn- read-live-process-references!
  []
  (reduce
   (fn [result file]
     (let [record (try (edn/read-string (slurp file))
                       (catch Throwable _ nil))]
       (if (live-process-reference? record)
         (conj result record)
         (do
           (Files/deleteIfExists (.toPath ^java.io.File file))
           result))))
   []
   (process-reference-files)))

(defn- cache-directories
  []
  (let [root (io/file cache-root)]
    (if (.isDirectory root)
      (->> (.listFiles root)
           (filter #(.isDirectory ^java.io.File %))
           (remove #(Files/isSymbolicLink (.toPath ^java.io.File %)))
           (filter #(some? (read-manifest %)))
           (sort-by #(.getName ^java.io.File %)))
      [])))

(defn reap
  "Delete only immutable caches unreferenced by a recorded live process."
  [_]
  (let [result
        (with-cache-lock
          (fn []
            (let [references (read-live-process-references!)
                  live-paths
                  (into #{}
                        (map #(-> (:seon.operator.process-record/cache-path %)
                                  canonical-file
                                  .getCanonicalPath))
                        references)
                  selected-path (some-> (selected-cache)
                                        :seon.dev-cache/path
                                        canonical-file
                                        .getCanonicalPath)
                  protected-paths (cond-> live-paths
                                    selected-path (conj selected-path))
                  reaped
                  (into []
                        (comp
                         (remove #(contains? protected-paths
                                             (.getCanonicalPath
                                              ^java.io.File %)))
                         (map (fn [directory]
                                (let [path (.getCanonicalPath
                                            ^java.io.File directory)]
                                  (b/delete {:path path})
                                  path))))
                        (cache-directories))]
              {:seon.dev-cache/live-processes (count references)
               :seon.dev-cache/protected (count protected-paths)
               :seon.dev-cache/reaped reaped})))]
    (prn result)
    result))
