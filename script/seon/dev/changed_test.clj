(ns seon.dev.changed-test
  "Select and run affected pod, database-server, and operator tests."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [seon.dev.state :as state]
            [seon.dev.test-artifact :as artifact]
            [seon.dev.test-roots :as test-roots])
  (:import [java.io File FileInputStream]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))

(def manifest-wait-ms 3000)
(def test-timeout-ms 300000)
(def termination-wait-ms 2000)
(def hook-publication-timeout-ms 3000)

(declare normalize-paths)

(defn- hook-directory [configuration]
  (fs/path (:seon.dev.config/process-dir configuration) "changed-test-hook"))

(defn- hook-state-path [configuration name]
  (fs/path (hook-directory configuration) (str name ".edn")))

(defn- hook-pending [configuration]
  (state/read-edn (hook-state-path configuration "pending")))

(defn- delete-if-present! [path]
  (when (fs/exists? path) (fs/delete path))
  nil)

(defn- next-hook-generation! [configuration]
  (let [path (hook-state-path configuration "generation")
        generation (inc (long (or (:seon.dev.changed-test/generation
                                    (state/read-edn path))
                                  0)))]
    (state/write-edn! path {:seon.dev.changed-test/generation generation})
    generation))

(defn- current-process-identity []
  (let [pid (.pid (java.lang.ProcessHandle/current))]
    {:seon.dev.process/pid pid
     :seon.dev.process/start-instant (state/process-start-instant pid)}))

(defn- hook-worker-alive? [configuration]
  (some-> (state/read-edn (hook-state-path configuration "worker"))
          state/process-identity-alive?))

(defn- default-hook-worker-command [configuration]
  (let [root (:seon.dev.config/root configuration)]
    ["bb" "--config" (str (fs/path root "bb.edn"))
     "--deps-root" root "-m" "seon.dev.changed-test"
     "hook-worker" "--root" root]))

(defn- start-hook-worker! [configuration command]
  (let [root (:seon.dev.config/root configuration)
        log (fs/path (hook-directory configuration) "worker.log")
        _ (fs/create-dirs (hook-directory configuration))
        worker (process/process {:cmd command
                                 :dir root
                                 :out :append
                                 :out-file log
                                 :err :out})
        pid (.pid ^Process (:proc worker))
        start-instant
        (loop [attempt 0]
          (or (state/process-start-instant pid)
              (when (< attempt 100)
                (Thread/sleep 10)
                (recur (inc attempt)))))]
    (when-not start-instant
      (process/destroy-tree worker)
      (throw (ex-info "Changed-test worker identity was unavailable."
                      {:seon.dev.process/pid pid})))
    (state/write-edn!
     (hook-state-path configuration "worker")
     {:seon.dev.process/pid pid
      :seon.dev.process/start-instant start-instant})
    pid))

(defn enqueue-hook!
  "Union hook paths into one pending generation and ensure one worker."
  ([configuration paths]
   (enqueue-hook! configuration paths nil))
  ([configuration paths worker-command]
   (let [root (:seon.dev.config/root configuration)
         paths (normalize-paths root paths)]
     (state/with-lock
      configuration :changed-test-hook-publication
      hook-publication-timeout-ms
      (fn []
        (let [worker-alive? (hook-worker-alive? configuration)
              pending (hook-pending configuration)
              running (when-not worker-alive?
                        (state/read-edn
                         (hook-state-path configuration "running")))
              generation (next-hook-generation! configuration)
              requested-paths
              (->> (concat (:seon.dev.changed-test/paths pending)
                           (:seon.dev.changed-test/paths running)
                           paths)
                   distinct sort vec)
              request {:seon.dev.changed-test/generation generation
                       :seon.dev.changed-test/paths requested-paths
                       :seon.dev.changed-test/published-at
                       (str (Instant/now))}]
          (when running
            (delete-if-present! (hook-state-path configuration "running")))
          (state/write-edn! (hook-state-path configuration "pending") request)
          (when-not worker-alive?
            (start-hook-worker!
             configuration
             (or worker-command
                 (default-hook-worker-command configuration))))
          request))))))

(def host-analysis-config
  "{:output {:format :edn} :analysis {:var-usages false :var-definitions {:shallow true}}}")

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

(defn root-runtime-path?
  "True when a normalized path belongs to Seon's root runtime/test graph.

   `reference-code/` contains independent maintained dependency repositories.
   Their own tests prove edits there; the root graph becomes responsible only
   after `deps.edn` advances to a dependency commit. Treating those sources as
   unknown root CLJS resources needlessly widens every library edit to the
   complete pod gate."
  [path]
  (not (str/starts-with? path "reference-code/")))

(defn- resource-index [manifest]
  (into {} (map (juxt :seon.dev.test.resource/path identity))
        (:seon.dev.test.artifact/resources manifest)))

(defn- cljs-path? [path]
  (or (str/ends-with? path ".cljs")
      (str/ends-with? path ".cljc")))

(defn- host-path? [path]
  (or (str/ends-with? path ".clj")
      (str/ends-with? path ".cljc")))

(defn- broad-input? [path]
  (or (str/starts-with? path "config/")
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

(defn- host-source-file? [path]
  (and (fs/regular-file? path)
       (host-path? (str/lower-case (str path)))))

(defn- files-below [root relative]
  (let [directory (fs/path root relative)]
    (if (fs/directory? directory)
      (->> (fs/glob directory "**{.clj,.cljc}")
           (filter host-source-file?))
      [])))

(defn- host-corpus [root]
  (->> (concat (files-below root "src")
               (files-below root "script/seon/dev")
               (test-roots/operator-test-files root)
               (test-roots/writer-test-files root))
       (map #(str (fs/normalize (fs/absolutize %))))
       distinct
       sort
       vec))

(defn- host-analysis-row? [row]
  (not= :cljs (:lang row)))

(defn analysis->host-graph
  "Build host namespace dependencies and retained runner roots."
  [root analysis]
  (let [definitions (->> (get-in analysis [:analysis :namespace-definitions])
                         (filter host-analysis-row?))
        usages (->> (get-in analysis [:analysis :namespace-usages])
                    (filter host-analysis-row?))
        relative (fn [path]
                   (first (normalize-paths root [path])))
        ownership (->> definitions
                       (group-by :name)
                       (map (fn [[namespace rows]]
                              [namespace
                               (set (map (comp relative :filename) rows))]))
                       (into {}))
        ambiguous (->> ownership
                       (keep (fn [[namespace paths]]
                               (when (< 1 (count paths)) namespace)))
                       vec)
        path->namespace (into {}
                              (mapcat (fn [[namespace paths]]
                                        (map #(vector % namespace) paths)))
                              ownership)
        requires (reduce (fn [result {:keys [from to]}]
                           (if (and from to)
                             (update result from (fnil conj #{}) to)
                             result))
                         {}
                         usages)]
    (when (seq ambiguous)
      (throw (ex-info "Host namespaces must have one first-party source file."
                      {:seon.dev.changed-test/namespaces ambiguous})))
    {:seon.dev.changed-test/path->namespace path->namespace
     :seon.dev.changed-test/requires requires
     :seon.dev.changed-test/operator-tests
     (set (test-roots/operator-test-namespaces root))
     :seon.dev.changed-test/writer-tests
     (set (test-roots/writer-test-namespaces root))}))

(defn analyze-host
  "Return current CLJ namespace facts or an explicit unavailable reason."
  [root]
  (if-not (fs/which "clj-kondo")
    {:seon.dev.changed-test/host-status :unavailable
     :seon.dev.changed-test/reason "clj-kondo is unavailable"}
    (try
      (let [files (host-corpus root)
            result (process/sh {:cmd (into ["clj-kondo" "--lint"]
                                           (concat files
                                                   ["--skip-lint"
                                                    "--config"
                                                    host-analysis-config]))
                                :dir root
                                :out :string
                                :err :string
                                :continue true})
            parsed (edn/read-string (:out result))]
        (if (map? (:analysis parsed))
          {:seon.dev.changed-test/host-status :available
           :seon.dev.changed-test/host-graph
           (analysis->host-graph root parsed)}
          {:seon.dev.changed-test/host-status :unavailable
           :seon.dev.changed-test/reason
           "clj-kondo returned no namespace analysis"}))
      (catch Exception error
        {:seon.dev.changed-test/host-status :unavailable
         :seon.dev.changed-test/reason (.getMessage error)}))))

(defn- reverse-closure [requires seeds]
  (loop [known (set seeds)]
    (let [expanded (into known
                         (keep (fn [[namespace dependencies]]
                                 (when (seq (set/intersection
                                             known dependencies))
                                   namespace)))
                         requires)]
      (if (= known expanded) known (recur expanded)))))

(defn- operator-path? [path]
  (or (str/starts-with? path "script/seon/dev/")
      (str/starts-with? path "test/seon/dev/")
      (= path "bb.edn")))

(defn- writer-path? [path]
  (or (str/starts-with? path "src/seon/db/")
      (str/starts-with? path "src/seon/embed")
      (str/starts-with? path "test/seon/db/")
      (= path "test/seon/embed_writer_test.clj")
      (= path "bin/test-writer")))

(defn host-impact
  "Select retained operator and writer tests from host namespace facts."
  [host-result paths]
  (let [graph (:seon.dev.changed-test/host-graph host-result)
        host-paths (filterv host-path? paths)
        path->namespace (:seon.dev.changed-test/path->namespace graph)
        changed-namespaces (set (keep #(get path->namespace %) host-paths))
        unknown (filterv #(nil? (get path->namespace %)) host-paths)
        affected (when graph
                   (reverse-closure (:seon.dev.changed-test/requires graph)
                                    changed-namespaces))
        all-operator (:seon.dev.changed-test/operator-tests graph)
        all-writer (:seon.dev.changed-test/writer-tests graph)
        unknown-operator? (some operator-path? unknown)
        unknown-writer? (some writer-path? unknown)
        unknown-shared? (some #(and (str/starts-with? % "src/")
                                    (not (operator-path? %))
                                    (not (writer-path? %)))
                              unknown)
        unavailable? (nil? graph)
        shared-input? (some #(and (str/starts-with? % "src/")
                                  (str/ends-with? % ".cljc"))
                            paths)
        dependency-input? (some #{"deps.edn"} paths)
        force-operator? (some #{"bb.edn"} paths)
        force-writer? (some #{"bin/test-writer"} paths)
        relevant-operator? (or shared-input? dependency-input?
                               (some operator-path? paths))
        relevant-writer? (or shared-input? dependency-input?
                             (some writer-path? paths))
        operator-tests (cond
                         (or dependency-input? force-operator?)
                         (if unavailable? :all all-operator)
                         (and unavailable? relevant-operator?) :all
                         (or unknown-operator? unknown-shared?) all-operator
                         graph (set/intersection affected all-operator)
                         :else #{})
        writer-tests (cond
                       (or dependency-input? force-writer?)
                       (if unavailable? :all all-writer)
                       (and unavailable? relevant-writer?) :all
                       (or unknown-writer? unknown-shared?) all-writer
                       graph (set/intersection affected all-writer)
                       :else #{})]
    {:seon.dev.changed-test/host-namespaces changed-namespaces
     :seon.dev.changed-test/operator-tests
     (if (= :all operator-tests) :all (vec (sort-by str operator-tests)))
     :seon.dev.changed-test/writer-tests
     (if (= :all writer-tests) :all (vec (sort-by str writer-tests)))
     :seon.dev.changed-test/widening
     (cond-> []
       unavailable?
       (conj {:seon.dev.changed-test/reason :host-analysis-unavailable
              :seon.dev.changed-test/detail
              (:seon.dev.changed-test/reason host-result)})

       (seq unknown)
       (conj {:seon.dev.changed-test/reason :unknown-host-resource
              :seon.dev.changed-test/paths unknown}))}))

(defn impact
  "Derive the conservative reverse-transitive affected CLJS test namespaces."
  ([manifest paths] (impact manifest paths #{}))
  ([manifest paths seed-namespaces]
   (let [resources (:seon.dev.test.artifact/resources manifest)
         by-path (resource-index manifest)
         test-namespaces (set (:seon.dev.test.artifact/test-namespaces manifest))
         changed-rows (keep by-path paths)
         changed-namespaces
         (into (set seed-namespaces)
               (keep :seon.dev.test.resource/namespace changed-rows))
         unknown (->> paths (filter cljs-path?) (remove by-path) vec)
         broad (->> paths (filter broad-input?) vec)
         requires (into {}
                        (map (juxt :seon.dev.test.resource/namespace
                                   #(set (:seon.dev.test.resource/requires %))))
                        resources)
         affected (reverse-closure requires changed-namespaces)
         full? (boolean (or (seq unknown) (seq broad)))
         selected (if full?
                    test-namespaces
                    (set/intersection affected test-namespaces))]
     {:seon.dev.changed-test/paths (vec paths)
      :seon.dev.changed-test/full? full?
      :seon.dev.changed-test/test-namespaces (vec (sort-by str selected))
      :seon.dev.changed-test/widening
      (cond-> []
        (seq unknown)
        (conj {:seon.dev.changed-test/reason :unknown-cljs-resource
               :seon.dev.changed-test/paths unknown})

        (seq broad)
        (conj {:seon.dev.changed-test/reason :shared-or-build-input
               :seon.dev.changed-test/paths broad}))})))

(defn- prune-logs! [directory]
  (doseq [path (->> (fs/list-dir directory "changed-*.log")
                    (sort-by fs/last-modified-time)
                    reverse
                    (drop 20))]
    (fs/delete-if-exists path)))

(defn- process-descendants [^java.lang.ProcessHandle handle]
  (with-open [stream (.descendants handle)]
    (vec (iterator-seq (.iterator stream)))))

(defn- stable-process-handles
  "Expand known handles until two descendant observations are equal."
  [known]
  (loop [prior nil handles known attempts 0]
    (let [expanded
          (reduce
            (fn [result handle]
              (into result
                    (map (juxt #(.pid ^java.lang.ProcessHandle %) identity))
                    (when (.isAlive ^java.lang.ProcessHandle handle)
                      (process-descendants handle))))
            handles
            (vals handles))]
      (if (or (= (set (keys prior)) (set (keys expanded)))
              (= attempts 20))
        expanded
        (do (Thread/sleep 10)
            (recur handles expanded (inc attempts)))))))

(defn- stable-process-tree [^Process process]
  (let [root (.toHandle process)]
    (stable-process-handles {(.pid root) root})))

(defn- process-depth [handles ^java.lang.ProcessHandle handle]
  (loop [current handle depth 0]
    (let [parent (.parent current)]
      (if (and (.isPresent parent)
               (contains? handles (.pid ^java.lang.ProcessHandle (.get parent))))
        (recur (.get parent) (inc depth))
        depth))))

(defn- await-process-absence [handles timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (every? #(not (.isAlive ^java.lang.ProcessHandle %)) (vals handles))
        true
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 10) (recur))
          false)))))

(defn- signal-tree! [handles force?]
  (doseq [handle (sort-by #(process-depth handles %) > (vals handles))
          :when (.isAlive ^java.lang.ProcessHandle handle)]
    (if force?
      (.destroyForcibly ^java.lang.ProcessHandle handle)
      (.destroy ^java.lang.ProcessHandle handle))))

(defn- terminate!
  "Terminate one stable process tree descendants-first and await absence."
  [^Process process]
  (let [handles (stable-process-tree process)]
    (signal-tree! handles false)
    (or (await-process-absence handles termination-wait-ms)
        (let [expanded (stable-process-handles handles)]
          (signal-tree! expanded true)
          (await-process-absence expanded termination-wait-ms)))))

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

(defn test-process-environment
  "Return the canonical Node test environment, preserving explicit overrides."
  [configuration]
  (let [environment (:seon.dev.config/environment configuration)]
    {"SEON_CONFIG" (get environment "SEON_CONFIG" "config/test.edn")
     "SEON_RENDER_STRICT" (get environment "SEON_RENDER_STRICT" "1")}))

(defn- run-command! [root boundary argv environment]
  (let [log-dir (fs/path root "tmp/test-changed")
        log (fs/path log-dir
                     (str "changed-" (name boundary) "-"
                          (System/currentTimeMillis) "-"
                          (random-uuid) ".log"))
        _ (fs/create-dirs log-dir)
        builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.directory (.toFile (fs/path root)))
                  (.redirectErrorStream true)
                  (.redirectOutput (.toFile log)))
        _ (.putAll (.environment builder) environment)
        process (.start builder)
        shutdown-hook
        (Thread. #(terminate! process)
                 (str "seon-changed-test-cleanup-" (.pid process)))
        runtime (Runtime/getRuntime)
        _ (.addShutdownHook runtime shutdown-hook)
        [completed? terminated?]
        (try
          (let [completed? (.waitFor process test-timeout-ms
                                     TimeUnit/MILLISECONDS)]
            [completed? (if completed? true (terminate! process))])
          (catch InterruptedException error
            (terminate! process)
            (.interrupt (Thread/currentThread))
            (throw error))
          (catch Throwable error
            (terminate! process)
            (throw error))
          (finally
            (try
              (.removeShutdownHook runtime shutdown-hook)
              (catch IllegalStateException _))))
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
        {:seon.dev.changed-test/boundary boundary
         :seon.dev.changed-test/command argv
         :seon.dev.changed-test/status
         (if (and completed? terminated? (zero? exit) summary counts
                  (str/starts-with? counts "0 failures, 0 errors."))
           :passed
           (cond
             (not terminated?) :cleanup-failed
             completed? :failed
             :else :timed-out))
         :seon.dev.changed-test/exit exit
         :seon.dev.changed-test/summary summary
         :seon.dev.changed-test/counts counts
         :seon.dev.changed-test/failures failures
         :seon.dev.changed-test/log (str log)}]
    (prune-logs! log-dir)
    result))

(defn javascript-argv
  "Return an unfiltered full command or an explicitly focused JavaScript command."
  [configuration root manifest test-namespaces]
  (cond-> [(get-in configuration
                    [:seon.dev.config/environment "SEON_JS_RUNTIME"]
                    "bun")
           (str (fs/path root (:seon.dev.test.artifact/path manifest)))]
    (not= :all test-namespaces)
    (into (map #(str "--test=" %) test-namespaces))))

(defn- run-javascript! [configuration manifest test-namespaces]
  (let [root (:seon.dev.config/root configuration)]
    (assoc (run-command! root :pod
                         (javascript-argv configuration root manifest
                                          test-namespaces)
                         (test-process-environment configuration))
           :seon.dev.changed-test/test-namespaces test-namespaces)))

(defn- run-operator! [root test-namespaces]
  (let [argv (cond-> ["bb" "--config" (str (fs/path root "bb.edn"))
                      "--deps-root" root "-m" "seon.dev.test-runner"]
               (not= :all test-namespaces)
               (into (map str test-namespaces)))]
    (assoc (run-command! root :operator argv {})
           :seon.dev.changed-test/test-namespaces test-namespaces)))

(defn- run-writer! [root test-namespaces]
  (let [argv (cond-> [(str (fs/path root "bin/test-writer"))]
               (not= :all test-namespaces)
               (into (map str test-namespaces)))]
    (assoc (run-command! root :writer argv {})
           :seon.dev.changed-test/test-namespaces test-namespaces)))

(defn- run-pod-fallback! [configuration]
  (let [root (:seon.dev.config/root configuration)]
    (assoc (run-command! root :pod [(str (fs/path root "bin/test-cljs"))]
                         (test-process-environment configuration))
         :seon.dev.changed-test/test-namespaces :all
         :seon.dev.changed-test/reason
         "The managed Shadow manifest was unavailable; ran the full one-shot pod gate.")))

(defn- shadow-build-input? [path]
  (or (str/starts-with? path "config/")
      (#{"deps.edn" "shadow-cljs.edn" "package.json" "package-lock.json"}
       path)))

(defn shadow-plan
  "Select changed Shadow inputs and CLJ macro namespaces from current facts."
  [manifest host-selection paths]
  (let [resources (:seon.dev.test.artifact/resources manifest)
        dependencies (set (mapcat :seon.dev.test.resource/requires resources))
        host-namespaces (:seon.dev.changed-test/host-namespaces host-selection)
        macro-seeds (set/intersection dependencies host-namespaces)
        path->namespace
        (get-in host-selection
                [:seon.dev.changed-test/host-graph
                 :seon.dev.changed-test/path->namespace])
        relevant-paths (filterv #(or (cljs-path? %)
                                     (shadow-build-input? %)
                                     (contains? macro-seeds
                                                (path->namespace %)))
                                paths)]
    {:seon.dev.changed-test/shadow? (boolean (seq relevant-paths))
     :seon.dev.changed-test/shadow-paths relevant-paths
     :seon.dev.changed-test/shadow-seeds macro-seeds}))

(defn potential-shadow-input?
  "True when a path may affect the pod without a current Shadow graph."
  [path]
  (or (cljs-path? path)
      (shadow-build-input? path)
      (and (str/starts-with? path "src/")
           (str/ends-with? path ".clj"))))

(defn- aggregate-status [boundary-results]
  (let [statuses (set (map :seon.dev.changed-test/status boundary-results))]
    (cond
      (contains? statuses :cleanup-failed) :cleanup-failed
      (contains? statuses :timed-out) :timed-out
      (contains? statuses :failed) :failed
      (contains? statuses :build-unavailable) :build-unavailable
      (contains? statuses :passed) :passed
      :else :no-affected-tests)))

(defn- persist-report [root result]
  (let [directory (fs/path root "tmp/test-changed")
        report (fs/path directory "latest.report.edn")]
    (state/write-edn! report result)
    (assoc result :seon.dev.changed-test/report (str report))))

(defn- run-changed-unlocked!
  "Run the selected boundaries while the caller owns the changed-test lock."
  [configuration requested-paths]
  (let [root (:seon.dev.config/root configuration)
        paths (filterv root-runtime-path? requested-paths)
        dependency-source-paths
        (filterv (complement root-runtime-path?) requested-paths)
        host-result (analyze-host root)
        host-selection (assoc (host-impact host-result paths)
                              :seon.dev.changed-test/host-graph
                              (:seon.dev.changed-test/host-graph host-result))
        current-manifest (artifact/read-current root)
        shadow (if current-manifest
                 (shadow-plan current-manifest host-selection paths)
                 {:seon.dev.changed-test/shadow?
                  (boolean (some potential-shadow-input? paths))
                  :seon.dev.changed-test/shadow-paths
                  (filterv potential-shadow-input? paths)
                  :seon.dev.changed-test/shadow-seeds #{}})
        manifest (when (:seon.dev.changed-test/shadow? shadow)
                   (wait-current root
                                 (:seon.dev.changed-test/shadow-paths shadow)
                                 manifest-wait-ms))
        pod-selection (when manifest
                        (impact manifest paths
                                (:seon.dev.changed-test/shadow-seeds shadow)))
        pod-tests (when pod-selection
                    (if (:seon.dev.changed-test/full? pod-selection)
                      :all
                      (:seon.dev.changed-test/test-namespaces pod-selection)))
        operator-tests
        (:seon.dev.changed-test/operator-tests host-selection)
        writer-tests
        (:seon.dev.changed-test/writer-tests host-selection)
        boundary-results
        (cond-> []
          (or (= :all operator-tests) (seq operator-tests))
          (conj (run-operator! root operator-tests))

          (or (= :all writer-tests) (seq writer-tests))
          (conj (run-writer! root writer-tests))

          (and manifest (or (= :all pod-tests) (seq pod-tests)))
          (conj (run-javascript! configuration manifest pod-tests))

          (and (:seon.dev.changed-test/shadow? shadow) (nil? manifest))
          (conj (run-pod-fallback! configuration)))]
    {:seon.dev.changed-test/paths requested-paths
     :seon.dev.changed-test/status (aggregate-status boundary-results)
     :seon.dev.changed-test/boundaries boundary-results
     :seon.dev.changed-test/test-namespaces
     (vec (:seon.dev.changed-test/test-namespaces pod-selection))
     :seon.dev.changed-test/host-status
     (:seon.dev.changed-test/host-status host-result)
     :seon.dev.changed-test/widening
     (vec
      (concat
       (when (seq dependency-source-paths)
         [{:seon.dev.changed-test/reason
           :independent-reference-repository
           :seon.dev.changed-test/paths dependency-source-paths}])
       (:seon.dev.changed-test/widening host-selection)
       (:seon.dev.changed-test/widening pod-selection)))}))

(def changed-test-lock-timeout-ms
  (+ manifest-wait-ms (* 3 test-timeout-ms) 10000))

(defn run-changed!
  "Run affected pod, writer, and operator tests from current graph facts."
  [configuration paths]
  (let [root (:seon.dev.config/root configuration)
        requested-paths (normalize-paths root paths)]
    (state/with-lock
     configuration :changed-test changed-test-lock-timeout-ms
     #(persist-report root
                      (run-changed-unlocked! configuration requested-paths)))))

(defn- with-hook-publication [configuration transition]
  (state/with-lock configuration :changed-test-hook-publication
                   hook-publication-timeout-ms transition))

(defn- claim-hook-request! [configuration]
  (with-hook-publication
   configuration
   (fn []
     (let [path (hook-state-path configuration "pending")
           request (state/read-edn path)]
       (when request
         (state/write-edn! (hook-state-path configuration "running") request)
         (delete-if-present! path))
       request))))

(defn- complete-hook-request! [configuration request]
  (with-hook-publication
   configuration
   (fn []
     (when (= (:seon.dev.changed-test/generation request)
              (:seon.dev.changed-test/generation
               (state/read-edn (hook-state-path configuration "running"))))
       (delete-if-present! (hook-state-path configuration "running"))))))

(defn- continue-hook-worker? [configuration identity]
  (with-hook-publication
   configuration
   (fn []
     (if (hook-pending configuration)
       true
       (do
         (when (= identity
                  (state/read-edn (hook-state-path configuration "worker")))
           (delete-if-present! (hook-state-path configuration "worker")))
         false)))))

(defn- run-hook-request! [configuration request]
  (let [root (:seon.dev.config/root configuration)
        result
        (try
          (run-changed-unlocked!
           configuration (:seon.dev.changed-test/paths request))
          (catch Throwable error
            {:seon.dev.changed-test/paths
             (:seon.dev.changed-test/paths request)
             :seon.dev.changed-test/status :failed
             :seon.dev.changed-test/boundaries []
             :seon.dev.changed-test/test-namespaces []
             :seon.dev.changed-test/host-status :unavailable
             :seon.dev.changed-test/widening
             [{:seon.dev.changed-test/reason :worker-failure
               :seon.dev.changed-test/detail (.getMessage error)}]}))]
    (persist-report
     root
     (merge result
            (select-keys request
                         [:seon.dev.changed-test/generation
                          :seon.dev.changed-test/published-at])))))

(defn run-hook-worker!
  "Drain at most one coalesced hook generation at a time."
  ([configuration]
   (run-hook-worker! configuration #(run-hook-request! configuration %)))
  ([configuration execute!]
   (let [identity (current-process-identity)]
     (loop []
       (state/with-lock
        configuration :changed-test changed-test-lock-timeout-ms
        (fn []
          (when-let [request (claim-hook-request! configuration)]
            (execute! request)
            (complete-hook-request! configuration request))))
       (when (continue-hook-worker? configuration identity)
         (recur))))))

(defn -main [& arguments]
  (when-not (= ["hook-worker" "--root"] (vec (take 2 arguments)))
    (throw (ex-info "Choose `hook-worker --root ROOT`."
                    {:seon.dev.changed-test/arguments (vec arguments)})))
  (let [load-configuration (requiring-resolve 'seon.dev.config/load!)]
    (run-hook-worker! (load-configuration (nth arguments 2)))))

(defn format-result
  "Format one bounded advisory result for a human or edit hook."
  [result]
  (let [status (:seon.dev.changed-test/status result)
        boundaries (:seon.dev.changed-test/boundaries result)]
    (str "changed tests " (name status)
         (apply str
                (for [boundary boundaries
                      :let [tests (:seon.dev.changed-test/test-namespaces
                                   boundary)]]
                  (str "\n" (name (:seon.dev.changed-test/boundary boundary))
                       " " (name (:seon.dev.changed-test/status boundary))
                       (when (= :all tests) " — full retained gate")
                       (when (sequential? tests)
                         (str " — " (count tests) " namespace(s): "
                              (str/join ", " (take 6 tests))
                              (when (< 6 (count tests)) " …")))
                       (when-let [summary (:seon.dev.changed-test/summary
                                           boundary)]
                         (str "\n  " summary " "
                              (:seon.dev.changed-test/counts boundary)))
                       (when-let [reason (:seon.dev.changed-test/reason
                                          boundary)]
                         (str "\n  " reason))
                       (when-let [log (:seon.dev.changed-test/log boundary)]
                         (str "\n  log: " log))
                       (when-let [failures
                                  (seq (:seon.dev.changed-test/failures
                                        boundary))]
                         (str "\n" (str/join "\n" failures))))))
         (when-let [report (:seon.dev.changed-test/report result)]
           (str "\nreport: " report))
         (when-let [widening (seq (:seon.dev.changed-test/widening result))]
           (str "\nwidening: "
                (str/join ", "
                          (map (comp name :seon.dev.changed-test/reason)
                               widening)))))))
