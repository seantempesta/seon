(ns seon.dev.test-artifact
  "Publish immutable CLJS test artifacts from Shadow's successful flush state."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File FileInputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.time Instant]))

(def retained-bundle-count 16)

(defn- hex-digest [^MessageDigest digest]
  (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest))))

(defn- file-sha256 [^File file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [input (FileInputStream. file)]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur)))))
    (hex-digest digest)))

(defn- update-text! [^MessageDigest digest value]
  (.update digest (.getBytes (str value) StandardCharsets/UTF_8))
  digest)

(defn- canonical-file [root value]
  (when value
    (.getCanonicalFile
      (let [file (io/file (str value))]
        (if (.isAbsolute file) file (io/file (str root) (str value)))))))

(defn- relative-project-path [^File root ^File file]
  (let [root-path (.toPath root)
        file-path (.toPath file)]
    (when (.startsWith file-path root-path)
      (str/replace (str (.relativize root-path file-path)) File/separator "/"))))

(defn- resource-namespace [resource]
  (or (:ns resource)
      (first (sort-by str (:provides resource)))))

(defn- resource-row [state root [resource-id resource]]
  (when-let [file (canonical-file root (:file resource))]
    (when-let [path (relative-project-path root file)]
      (let [output (get-in state [:output resource-id])]
        {:seon.dev.test.resource/path path
         :seon.dev.test.resource/name (:resource-name resource)
         :seon.dev.test.resource/namespace (resource-namespace resource)
         :seon.dev.test.resource/cache-key (mapv str (:cache-key resource))
         :seon.dev.test.resource/requires
         (->> (concat (:requires resource)
                      (:macro-requires resource)
                      (:used-var-namespaces output))
              (remove nil?)
              distinct
              (sort-by str)
              vec)}))))

(defn- atomic-copy! [^File source ^File target]
  (.mkdirs (.getParentFile target))
  (when-not (.isFile target)
    (let [temp (io/file (.getParentFile target)
                        (str (.getName target) "." (random-uuid) ".tmp"))]
      (Files/copy (.toPath source) (.toPath temp)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (Files/move (.toPath temp) (.toPath target)
                  (into-array CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))))
  target)

(defn- atomic-edn! [^File target value]
  (.mkdirs (.getParentFile target))
  (let [temp (io/file (.getParentFile target)
                      (str (.getName target) "." (random-uuid) ".tmp"))]
    (spit temp (str (pr-str value) "\n"))
    (Files/move (.toPath temp) (.toPath target)
                (into-array CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING])))
  value)

(defn- delete-tree! [^File root]
  (when (.exists root)
    (doseq [file (reverse (file-seq root))]
      (Files/deleteIfExists (.toPath file)))))

(defn- runner-runtime [^File output runner]
  (let [relative (second
                   (re-find
                     #"var SHADOW_IMPORT_PATH = __dirname \+ '([^']+)';"
                     runner))]
    (when-not relative
      (throw (ex-info "Shadow test runner does not declare its runtime path."
                      {:seon.dev.test.artifact/output (str output)})))
    (canonical-file (.getParentFile output)
                    (str/replace-first relative #"^/" ""))))

(defn- portable-runner [runner]
  (-> runner
      (str/replace
        #"var SHADOW_IMPORT_PATH = __dirname \+ '[^']+';"
        "var SHADOW_IMPORT_PATH = __dirname + '/cljs-runtime';")
      (str/replace
        #"if \(__dirname == '\.'\) \{ SHADOW_IMPORT_PATH = \"[^\"]+\"; \}"
        "if (__dirname == '.') { SHADOW_IMPORT_PATH = __dirname + '/cljs-runtime'; }")))

(defn- runtime-entries [^File runtime]
  (when-not (.isDirectory runtime)
    (throw (ex-info "Shadow completed without its compiled runtime directory."
                    {:seon.dev.test.artifact/runtime (str runtime)})))
  (->> (file-seq runtime)
       (filter #(.isFile ^File %))
       (map (fn [^File file]
              {:seon.dev.test.object/file file
               :seon.dev.test.object/path
               (str/replace
                 (str (.relativize (.toPath runtime) (.toPath file)))
                 File/separator "/")
               :seon.dev.test.object/digest (file-sha256 file)}))
       (sort-by :seon.dev.test.object/path)
       vec))

(defn- bundle-digest [runner entries]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (update-text! digest runner)
    (doseq [entry entries]
      (update-text! digest (:seon.dev.test.object/path entry))
      (update-text! digest "\u0000")
      (update-text! digest (:seon.dev.test.object/digest entry))
      (update-text! digest "\n"))
    (hex-digest digest)))

(defn- ensure-object! [^File objects entry]
  (atomic-copy! ^File (:seon.dev.test.object/file entry)
                (io/file objects (:seon.dev.test.object/digest entry))))

(defn- link-object! [^File bundle-runtime ^File object entry]
  (let [link (io/file bundle-runtime (:seon.dev.test.object/path entry))
        link-path (.toPath link)
        target (.relativize (.getParent link-path) (.toPath object))]
    (.mkdirs (.getParentFile link))
    (Files/createSymbolicLink link-path target (make-array FileAttribute 0))))

(defn- publish-bundle! [^File directory runner entries]
  (let [digest (bundle-digest runner entries)
        bundles (io/file directory "bundles")
        objects (io/file directory "objects")
        bundle (io/file bundles digest)
        target (io/file bundle "test.js")]
    (when-not (.isFile target)
      (let [temp (io/file bundles (str digest "." (random-uuid) ".tmp"))
            runtime (io/file temp "cljs-runtime")]
        (.mkdirs runtime)
        (try
          (doseq [entry entries]
            (link-object! runtime (ensure-object! objects entry) entry))
          (spit (io/file temp "test.js") runner)
          (.mkdirs bundles)
          (try
            (Files/move (.toPath temp) (.toPath bundle)
                        (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
            (catch java.nio.file.FileAlreadyExistsException _
              (delete-tree! temp)))
          (catch Throwable error
            (delete-tree! temp)
            (throw error)))))
    {:seon.dev.test.artifact/digest digest
     :seon.dev.test.artifact/file target}))

(defn- prune-bundles! [^File directory current-digest]
  (let [bundles (io/file directory "bundles")
        objects (io/file directory "objects")
        retained (->> (.listFiles bundles)
                      (filter #(.isDirectory ^File %))
                      (sort-by #(.lastModified ^File %) >)
                      (take retained-bundle-count)
                      vec)
        retained-names (conj (set (map #(.getName ^File %) retained))
                             current-digest)]
    (doseq [^File bundle (.listFiles bundles)
            :when (and (.isDirectory bundle)
                       (not (contains? retained-names (.getName bundle))))]
      (delete-tree! bundle))
    (let [used-objects
          (->> (.listFiles bundles)
               (filter #(.isDirectory ^File %))
               (mapcat file-seq)
               (filter #(.isFile ^File %))
               (map #(.getName (.getCanonicalFile ^File %)))
               set)]
      (doseq [^File object (.listFiles objects)
              :when (and (.isFile object)
                         (not (contains? used-objects (.getName object))))]
        (Files/deleteIfExists (.toPath object))))))

(defn ^{:shadow.build/stage :flush} publish!
  "Publish one immutable test bundle and atomically point at its manifest."
  [state]
  (if (false? (get-in state
                      [:shadow.build/config
                       :seon.dev.test-artifact/publish?]
                      true))
    state
    (let [root (canonical-file (io/file ".") (:project-dir state))
          output (canonical-file root
                                 (get-in state
                                         [:shadow.build/config :output-to]))]
      (when-not (and output (.isFile output))
        (throw (ex-info "Shadow completed without its configured test output."
                        {:seon.dev.test-artifact/output (some-> output str)})))
      (let [runner-source (slurp output)
            runner (portable-runner runner-source)
            runtime (runner-runtime output runner-source)
            directory (io/file root "out/test/artifacts")
            {:seon.dev.test.artifact/keys [digest file]}
            (publish-bundle! directory runner (runtime-entries runtime))
            resources (->> (:sources state)
                           (keep #(resource-row state root %))
                           (sort-by :seon.dev.test.resource/path)
                           vec)
            manifest
            {:seon.dev.test.artifact/digest digest
             :seon.dev.test.artifact/path (relative-project-path root file)
             :seon.dev.test.artifact/published-at (str (Instant/now))
             :seon.dev.test.artifact/test-namespaces
             (->> (:shadow.build.test-util/test-namespaces state)
                  (sort-by str)
                  vec)
             :seon.dev.test.artifact/resources resources}]
        (atomic-edn! (io/file directory "current.edn") manifest)
        (prune-bundles! directory digest)
        state))))

(defn read-current
  "Read the current published test manifest under `root`."
  [root]
  (let [path (io/file (str root) "out/test/artifacts/current.edn")]
    (when (.isFile path) (edn/read-string (slurp path)))))
