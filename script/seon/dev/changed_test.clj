(ns seon.dev.changed-test
  "Select and run the CLJS tests affected by changed source paths."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.string :as str]
            [seon.dev.state :as state]
            [seon.dev.test-artifact :as artifact])
  (:import [java.io File FileInputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))

(def manifest-wait-ms 30000)
(def test-timeout-ms 300000)

(defn- hex-digest [^MessageDigest digest]
  (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest))))

(defn- file-sha1 [path]
  (let [digest (MessageDigest/getInstance "SHA-1")
        buffer (byte-array 65536)]
    (with-open [input (FileInputStream. (str path))]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur)))))
    (hex-digest digest)))

(defn normalize-paths
  "Return distinct repository-relative paths and reject paths outside root."
  [root paths]
  (let [root (fs/normalize (fs/absolutize root))]
    (->> paths
         (map (fn [path]
                (let [absolute (fs/normalize
                                 (fs/absolutize
                                   (if (fs/absolute? path)
                                     path
                                     (fs/path root path))))]
                  (when-not (fs/starts-with? absolute root)
                    (throw (ex-info "Changed test paths must stay inside the checkout."
                                    {:seon.dev.changed-test/path (str path)})))
                  (str/replace (str (fs/relativize root absolute))
                               File/separator "/"))))
         distinct
         sort
         vec)))

(defn- resource-index [manifest]
  (into {} (map (juxt :seon.dev.test.resource/path identity))
        (:seon.dev.test.artifact/resources manifest)))

(defn- cljs-path? [path]
  (or (str/ends-with? path ".cljs")
      (str/ends-with? path ".cljc")))

(defn- broad-input? [path]
  (or (str/ends-with? path ".cljc")
      (str/ends-with? path ".clj")
      (str/starts-with? path "config/")
      (#{"deps.edn" "shadow-cljs.edn" "package.json" "package-lock.json"
         "bb.edn"} path)))

(defn- manifest-published-after? [manifest path]
  (let [published (some-> (:seon.dev.test.artifact/published-at manifest)
                          Instant/parse .toEpochMilli)
        modified (when (fs/regular-file? path)
                   (.toMillis (fs/last-modified-time path)))]
    (or (nil? modified) (and published (<= modified published)))))

(defn- manifest-path-current? [root manifest index path]
  (let [file (fs/path root path)
        resource (get index path)]
    (cond
      (not (cljs-path? path)) (manifest-published-after? manifest file)
      (fs/regular-file? file)
      (and resource
           (= (file-sha1 file)
              (first (:seon.dev.test.resource/cache-key resource))))
      :else (nil? resource))))

(defn manifest-current?
  "True when the manifest's Shadow checksums match every changed CLJS path."
  [root manifest paths]
  (let [index (resource-index manifest)]
    (every? #(manifest-path-current? root manifest index %) paths)))

(defn wait-current
  "Wait for the managed Shadow watcher to publish facts for current files."
  [root paths timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [manifest (artifact/read-current root)]
        (cond
          (and manifest (manifest-current? root manifest paths)) manifest
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 100) (recur))
          :else nil)))))

(defn impact
  "Derive the conservative reverse-transitive affected CLJS test namespaces."
  [manifest paths]
  (let [resources (:seon.dev.test.artifact/resources manifest)
        by-path (resource-index manifest)
        test-namespaces (set (:seon.dev.test.artifact/test-namespaces manifest))
        changed-rows (keep by-path paths)
        changed-namespaces (set (keep :seon.dev.test.resource/namespace
                                      changed-rows))
        unknown (->> paths (filter cljs-path?) (remove by-path) vec)
        broad (->> paths (filter broad-input?) vec)
        affected
        (loop [known changed-namespaces]
          (let [next-known
                (into known
                      (keep (fn [resource]
                              (when (seq (set/intersection
                                          known
                                          (set (:seon.dev.test.resource/requires
                                                 resource))))
                                (:seon.dev.test.resource/namespace resource))))
                      resources)]
            (if (= known next-known) known (recur next-known))))
        selected (if (or (seq unknown) (seq broad))
                   test-namespaces
                   (set/intersection affected test-namespaces))]
    {:seon.dev.changed-test/paths (vec paths)
     :seon.dev.changed-test/test-namespaces (vec (sort-by str selected))
     :seon.dev.changed-test/widening
     (cond-> []
       (seq unknown)
       (conj {:seon.dev.changed-test/reason :unknown-cljs-resource
              :seon.dev.changed-test/paths unknown})

       (seq broad)
       (conj {:seon.dev.changed-test/reason :shared-or-build-input
              :seon.dev.changed-test/paths broad}))}))

(defn- prune-logs! [directory]
  (doseq [path (->> (fs/list-dir directory "changed-*.log")
                    (sort-by fs/last-modified-time)
                    reverse
                    (drop 20))]
    (fs/delete-if-exists path)))

(defn- terminate! [^Process process]
  (doseq [handle (reverse (vec (iterator-seq (.iterator (.descendants
                                                          (.toHandle process))))))]
    (.destroyForcibly ^java.lang.ProcessHandle handle))
  (.destroyForcibly process))

(defn failure-excerpts
  "Return bounded cljs.test failure blocks with expected and actual values."
  [output]
  (loop [lines (str/split-lines output)
         excerpts []]
    (if (or (empty? lines) (= 2 (count excerpts)))
      excerpts
      (if (re-find #"^(FAIL|ERROR) in \(" (first lines))
        (let [block (->> lines
                         (take 4)
                         (take-while (complement str/blank?))
                         (map #(subs % 0 (min 180 (count %))))
                         (str/join "\n"))]
          (recur (drop 4 lines) (conj excerpts block)))
        (recur (rest lines) excerpts)))))

(defn- run-node! [root manifest test-namespaces]
  (let [log-dir (fs/path root "tmp/test-changed")
        log (fs/path log-dir
                     (str "changed-" (System/currentTimeMillis) "-"
                          (random-uuid) ".log"))
        artifact-path (fs/path root (:seon.dev.test.artifact/path manifest))
        argv (into ["node" (str artifact-path)]
                   (map #(str "--test=" %) test-namespaces))
        _ (fs/create-dirs log-dir)
        process (-> (ProcessBuilder. ^java.util.List argv)
                    (.directory (.toFile (fs/path root)))
                    (.redirectErrorStream true)
                    (.redirectOutput (.toFile log))
                    (.start))
        completed? (.waitFor process test-timeout-ms TimeUnit/MILLISECONDS)
        _ (when-not completed? (terminate! process))
        exit (if completed? (.exitValue process) 124)
        output (slurp (str log))
        summary (some->> (str/split-lines output)
                         (filter #(re-find #"^Ran [0-9]+ tests containing" %))
                         last)
        counts (some->> (str/split-lines output)
                        (filter #(re-find #"^[0-9]+ failures, [0-9]+ errors\." %))
                        last)
        failures (failure-excerpts output)
        result
        {:seon.dev.changed-test/status
         (if (and completed? (zero? exit) summary counts
                  (str/starts-with? counts "0 failures, 0 errors."))
           :passed
           (if completed? :failed :timed-out))
         :seon.dev.changed-test/exit exit
         :seon.dev.changed-test/summary summary
         :seon.dev.changed-test/counts counts
         :seon.dev.changed-test/failures failures
         :seon.dev.changed-test/log (str log)}]
    (prune-logs! log-dir)
    result))

(defn run-changed!
  "Run affected CLJS tests from the exact immutable watcher artifact."
  [configuration paths]
  (let [root (:seon.dev.config/root configuration)
        paths (normalize-paths root paths)]
    (state/with-lock
      configuration :changed-test (+ manifest-wait-ms test-timeout-ms 10000)
      #(if-let [manifest (wait-current root paths manifest-wait-ms)]
         (let [selection (impact manifest paths)
               tests (:seon.dev.changed-test/test-namespaces selection)]
           (merge selection
                  {:seon.dev.changed-test/artifact
                   (:seon.dev.test.artifact/digest manifest)}
                  (if (seq tests)
                    (run-node! root manifest tests)
                    {:seon.dev.changed-test/status :no-affected-tests})))
         {:seon.dev.changed-test/paths paths
          :seon.dev.changed-test/status :build-unavailable
          :seon.dev.changed-test/reason
          "The managed Shadow test manifest did not match current source within 30 seconds."}))))

(defn format-result
  "Format one bounded advisory result for a human or edit hook."
  [result]
  (let [tests (:seon.dev.changed-test/test-namespaces result)
        status (:seon.dev.changed-test/status result)]
    (str "changed tests " (name status)
         (when (seq tests)
           (str " — " (count tests) " namespace(s): "
                (str/join ", " (take 8 tests))
                (when (< 8 (count tests)) " …")))
         (when-let [summary (:seon.dev.changed-test/summary result)]
           (str "\n" summary " " (:seon.dev.changed-test/counts result)))
         (when-let [reason (:seon.dev.changed-test/reason result)]
           (str "\n" reason))
         (when-let [log (:seon.dev.changed-test/log result)]
           (str "\nlog: " log))
         (when-let [failures (seq (:seon.dev.changed-test/failures result))]
           (str "\n" (str/join "\n" failures))))))
