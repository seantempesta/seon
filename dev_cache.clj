(ns dev-cache
  "Refreshes the source-preferred development class cache."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.nio.file AtomicMoveNotSupportedException Files
            StandardCopyOption]
           [java.security MessageDigest]))

(def cache-dir "target/dev-dependency-classes")
(def staging-dir "target/dev-dependency-classes.next")
(def result-file "target/dev-dependency-cache-result.edn")
(def manifest-file "META-INF/seon-dev-cache.edn")

(defn- canonical-file
  [path]
  (.getCanonicalFile (io/file path)))

(defn- cached-path?
  [path]
  (= (.getCanonicalPath (canonical-file cache-dir))
     (.getCanonicalPath (canonical-file path))))

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
  [basis form failure-message]
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
                 :seon.dev-cache/rejected staging-dir})))
    process))

(defn- run-build!
  [basis]
  (let [staging (.getCanonicalPath (canonical-file staging-dir))
        result (.getCanonicalPath (canonical-file result-file))
        discovery (discovery-form (dependency-containers basis) result)]
    (run-child! basis discovery
                "Development dependency-cache discovery failed.")
    (when-not (.isFile (io/file result))
      (throw
       (ex-info "Development dependency-cache discovery wrote no result."
                {:seon.dev-cache/result result})))
    (let [rows (edn/read-string (slurp result))]
      (run-child! basis (compile-form rows staging)
                  "Development dependency-cache compilation failed.")
      rows)))

(defn- loader-class
  [namespace-symbol]
  (io/file staging-dir
           (str (-> (str namespace-symbol)
                    clojure.core/munge
                    (str/replace "." "/"))
                "__init.class")))

(defn- sha-256
  [rows]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [row rows
            value [(str (:seon.dev-cache/namespace row))
                   (:seon.dev-cache/source-url row)
                   (slurp (:seon.dev-cache/source-url row))]]
      (.update digest (.getBytes value java.nio.charset.StandardCharsets/UTF_8))
      (.update digest (byte-array [(byte 0)])))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- validate!
  [rows]
  (doseq [{namespace-symbol :seon.dev-cache/namespace
           source-url :seon.dev-cache/source-url} rows
          :let [class-file (loader-class namespace-symbol)
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
  (let [first-party-root (io/file staging-dir "seon")]
    (when (and (.exists first-party-root)
               (some #(str/ends-with? (.getName ^java.io.File %)
                                      "__init.class")
                     (file-seq first-party-root)))
      (throw (ex-info "The dependency cache contains first-party classes."
                      {:seon.dev-cache/rejected staging-dir})))))

(defn- write-manifest!
  [rows digest duration-ms]
  (let [manifest (io/file staging-dir manifest-file)]
    (.mkdirs (.getParentFile manifest))
    (spit manifest
          (str (pr-str {:seon.dev-cache/version 1
                        :seon.dev-cache/digest digest
                        :seon.dev-cache/namespaces
                        (mapv :seon.dev-cache/namespace rows)
                        :seon.dev-cache/sources rows
                        :seon.dev-cache/duration-ms duration-ms})
               "\n"))))

(defn- admit!
  []
  (let [source (.toPath (canonical-file staging-dir))
        target (.toPath (canonical-file cache-dir))]
    (b/delete {:path cache-dir})
    (try
      (Files/move source target
                  (into-array StandardCopyOption
                              [StandardCopyOption/ATOMIC_MOVE]))
      (catch AtomicMoveNotSupportedException _
        (Files/move source target (make-array StandardCopyOption 0))))))

(defn refresh
  "Rebuild the directory-source dependency cache reached by seon.artifact."
  [_]
  (let [started (System/nanoTime)
        basis (b/create-basis {:project "deps.edn" :aliases [:dev]})]
    (b/delete {:path staging-dir})
    (b/delete {:path result-file})
    (.mkdirs (io/file staging-dir))
    (let [rows (run-build! basis)
          digest (sha-256 rows)
          duration-ms (long (/ (- (System/nanoTime) started) 1000000))]
      (validate! rows)
      (write-manifest! rows digest duration-ms)
      (admit!)
      (let [result {:seon.dev-cache/digest digest
                    :seon.dev-cache/namespaces (count rows)
                    :seon.dev-cache/duration-ms duration-ms
                    :seon.dev-cache/path cache-dir}]
        (prn result)
        result))))
