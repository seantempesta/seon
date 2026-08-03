;; Profile `bin/test` without changing which tests it discovers or runs.
;; Run from the repository root:
;;
;;   clojure -M \
;;     docs/prds/sci-execution-runtime/research/scripts/profile-test-suite-2026-08-03.clj \
;;     --output tmp/test-profiles/full
;;
;; Namespace arguments after the options select a focused run. The profiler
;; invokes `bin/test` without a result cluster, so the suite retains its private
;; operator root and cannot write to the live `default` cluster.

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(import '[java.io BufferedReader InputStreamReader]
        '[java.lang ProcessHandle]
        '[java.time Duration Instant])

(def ^:private output-option "--output")
(def ^:private progress-prefix "bin/test: ")

(def ^:private event-prefixes
  [{:suite.profile/event :suite.profile/begin-namespace
    :suite.profile/prefix "BEGIN namespace "}
   {:suite.profile/event :suite.profile/end-namespace
    :suite.profile/prefix "END namespace "}
   {:suite.profile/event :suite.profile/begin-test
    :suite.profile/prefix "BEGIN test "}
   {:suite.profile/event :suite.profile/end-test
    :suite.profile/prefix "END test "}])

(defn- usage
  []
  (str "Usage: clojure -M " *file*
       " [--output DIRECTORY] [test-namespaces...]"))

(defn- parse-arguments
  [arguments]
  (loop [remaining arguments
         parsed {:suite.profile/namespaces []}]
    (if-let [argument (first remaining)]
      (cond
        (#{"--help" "-h"} argument)
        (assoc parsed :suite.profile/help? true)

        (= output-option argument)
        (if-let [directory (second remaining)]
          (recur (nnext remaining)
                 (assoc parsed :suite.profile/output-directory directory))
          (throw (ex-info (str output-option " requires a directory.")
                          {:suite.profile/arguments arguments})))

        (str/starts-with? argument "--")
        (throw (ex-info (str "Unknown option: " argument)
                        {:suite.profile/arguments arguments}))

        :else
        (recur (next remaining)
               (update parsed :suite.profile/namespaces conj argument)))
      parsed)))

(defn- default-output-directory
  []
  (str "tmp/test-profiles/"
       (System/currentTimeMillis) "-" (.pid (ProcessHandle/current))))

(defn- test-file?
  [^java.io.File file]
  (let [file-name (.getName file)]
    (and (.isFile file)
         (or (str/ends-with? file-name "_test.clj")
             (str/ends-with? file-name "_test.cljc")))))

(defn- test-file-namespace
  [^java.io.File test-root ^java.io.File file]
  (let [relative (str (.relativize (.toPath test-root) (.toPath file)))
        without-extension
        (cond
          (str/ends-with? relative ".cljc") (subs relative 0 (- (count relative) 5))
          (str/ends-with? relative ".clj") (subs relative 0 (- (count relative) 4)))]
    (-> without-extension
        (str/replace java.io.File/separator ".")
        (str/replace "_" "-")
        symbol)))

(defn- selected-namespaces
  [repository-root namespace-names]
  (if (seq namespace-names)
    (set (map symbol namespace-names))
    (let [test-root (.getCanonicalFile (io/file repository-root "test"))]
      (into #{}
            (comp (filter test-file?)
                  (map #(test-file-namespace test-root %)))
            (file-seq test-root)))))

(defn- create-output-directory!
  [path]
  (let [directory (.getCanonicalFile (io/file path))]
    (when (.exists directory)
      (throw (ex-info "Refusing to overwrite an existing profile directory."
                      {:suite.profile/output-directory (.getPath directory)})))
    (when-not (.mkdirs directory)
      (throw (ex-info "Could not create the profile directory."
                      {:suite.profile/output-directory (.getPath directory)})))
    directory))

(defn- event-description
  [description]
  (some (fn [{event :suite.profile/event
              prefix :suite.profile/prefix}]
          (when (str/starts-with? description prefix)
            {:suite.profile/event event
             :suite.profile/subject (subs description (count prefix))}))
        event-prefixes))

(defn- parse-progress-line
  [line]
  (when (str/starts-with? line progress-prefix)
    (let [payload (subs line (count progress-prefix))]
      (if-let [event (event-description payload)]
        (assoc event
               :suite.profile/error :suite.profile/missing-timestamp
               :suite.profile/line line)
        (let [separator (.indexOf payload " ")]
          (when (pos? separator)
            (let [instant-text (subs payload 0 separator)
                  description (subs payload (inc separator))]
              (when-let [event (event-description description)]
                (try
                  (assoc event :suite.profile/at (Instant/parse instant-text))
                  (catch Throwable failure
                    (assoc event
                           :suite.profile/error :suite.profile/invalid-timestamp
                           :suite.profile/timestamp instant-text
                           :suite.profile/message (ex-message failure)
                           :suite.profile/line line)))))))))))

(defn- elapsed-milliseconds
  [^Instant began ^Instant ended]
  (/ (.toNanos (Duration/between began ended)) 1000000.0))

(defn- anomaly
  [state event message]
  (update state :suite.profile/anomalies conj
          {:suite.profile/event
           (cond-> event
             (:suite.profile/at event)
             (update :suite.profile/at str))
           :suite.profile/message message}))

(defn- selected-event?
  [state event]
  (let [event-type (:suite.profile/event event)
        subject (:suite.profile/subject event)
        namespace-name
        (if (contains? #{:suite.profile/begin-namespace
                         :suite.profile/end-namespace}
                       event-type)
          (symbol subject)
          (some-> subject symbol namespace symbol))]
    (contains? (:suite.profile/selected-namespaces state) namespace-name)))

(defn- capture-event
  [state event]
  (if-not (selected-event? state event)
    state
    (let [event-type (:suite.profile/event event)
          subject (:suite.profile/subject event)
          at (:suite.profile/at event)]
      (cond
        (:suite.profile/error event)
        (anomaly state event "Progress timing event has no valid ISO timestamp.")

        (= :suite.profile/begin-namespace event-type)
        (cond
          (:suite.profile/open-namespace state)
          (anomaly state event "Namespace began before the prior namespace ended.")

          (:suite.profile/open-test state)
          (anomaly state event "Namespace began while a test remained open.")

          :else
          (assoc state :suite.profile/open-namespace
                 {:suite.profile/subject subject :suite.profile/at at}))

        (= :suite.profile/end-namespace event-type)
        (let [opened (:suite.profile/open-namespace state)]
          (cond
            (nil? opened)
            (anomaly state event "Namespace ended without a matching begin.")

            (:suite.profile/open-test state)
            (anomaly state event "Namespace ended while a test remained open.")

            (not= subject (:suite.profile/subject opened))
            (anomaly state event "Namespace end did not match its begin.")

            :else
            (-> state
                (update :suite.profile/namespaces conj
                        {:suite.profile/namespace (symbol subject)
                         :suite.profile/wall-ms
                         (elapsed-milliseconds (:suite.profile/at opened) at)})
                (dissoc :suite.profile/open-namespace))))

        (= :suite.profile/begin-test event-type)
        (cond
          (nil? (:suite.profile/open-namespace state))
          (anomaly state event "Test began outside a namespace.")

          (:suite.profile/open-test state)
          (anomaly state event "Test began before the prior test ended.")

          :else
          (assoc state :suite.profile/open-test
                 {:suite.profile/subject subject :suite.profile/at at}))

        (= :suite.profile/end-test event-type)
        (let [opened (:suite.profile/open-test state)]
          (cond
            (nil? opened)
            (anomaly state event "Test ended without a matching begin.")

            (not= subject (:suite.profile/subject opened))
            (anomaly state event "Test end did not match its begin.")

            :else
            (-> state
                (update :suite.profile/tests conj
                        {:suite.profile/test (symbol subject)
                         :suite.profile/namespace
                         (symbol (namespace (symbol subject)))
                         :suite.profile/wall-ms
                         (elapsed-milliseconds (:suite.profile/at opened) at)})
                (dissoc :suite.profile/open-test))))

        :else state))))

(defn- finish-capture
  [state]
  (cond-> state
    (:suite.profile/open-test state)
    (anomaly {:suite.profile/event :suite.profile/eof}
             "Test BEGIN had no END before process exit.")

    (:suite.profile/open-namespace state)
    (anomaly {:suite.profile/event :suite.profile/eof}
             "Namespace BEGIN had no END before process exit.")

    (empty? (:suite.profile/namespaces state))
    (anomaly {:suite.profile/event :suite.profile/eof}
             "No complete timestamped namespace was observed.")))

(defn- summarize-namespaces
  [namespaces tests]
  (let [tests-by-namespace (group-by :suite.profile/namespace tests)]
    (mapv
     (fn [namespace-profile]
       (let [namespace-name (:suite.profile/namespace namespace-profile)
             namespace-tests (get tests-by-namespace namespace-name [])
             test-ms (reduce + 0.0 (map :suite.profile/wall-ms namespace-tests))]
         (assoc namespace-profile
                :suite.profile/test-count (count namespace-tests)
                :suite.profile/test-ms test-ms
                :suite.profile/non-test-ms
                (- (:suite.profile/wall-ms namespace-profile) test-ms))))
     namespaces)))

(defn- write-edn!
  [file value]
  (with-open [writer (io/writer file)]
    (binding [*out* writer
              *print-length* nil
              *print-level* nil]
      (prn value))))

(defn- decimal
  [number]
  (format "%.3f" (double number)))

(defn- write-tsv!
  [file header rows]
  (with-open [writer (io/writer file)]
    (.write writer (str (str/join "\t" header) "\n"))
    (doseq [row rows]
      (.write writer (str (str/join "\t" row) "\n")))))

(defn- report!
  [profile]
  (let [worst-namespaces (take 20 (:suite.profile/namespaces-by-duration profile))
        worst-tests (take 30 (:suite.profile/tests-by-duration profile))]
    (println)
    (println "PROFILE" (:suite.profile/output-directory profile))
    (println "SUITE"
             (str "exit=" (:suite.profile/exit profile))
             (str "wall_ms=" (decimal (:suite.profile/wall-ms profile)))
             (str "namespaces=" (count (:suite.profile/namespaces profile)))
             (str "tests=" (count (:suite.profile/tests profile))))
    (println "WORST NAMESPACES (wall_ms test_ms non_test_ms tests namespace)")
    (doseq [namespace-profile worst-namespaces]
      (println (decimal (:suite.profile/wall-ms namespace-profile))
               (decimal (:suite.profile/test-ms namespace-profile))
               (decimal (:suite.profile/non-test-ms namespace-profile))
               (:suite.profile/test-count namespace-profile)
               (:suite.profile/namespace namespace-profile)))
    (println "WORST TESTS (wall_ms test)")
    (doseq [test-profile worst-tests]
      (println (decimal (:suite.profile/wall-ms test-profile))
               (:suite.profile/test test-profile)))
    (when-let [anomalies (seq (:suite.profile/anomalies profile))]
      (println "PROFILE ERRORS")
      (doseq [problem anomalies]
        (println (pr-str problem))))))

(defn- run-profile!
  [{output-path :suite.profile/output-directory
    namespaces :suite.profile/namespaces}]
  (let [repository-root (.getCanonicalFile (io/file "."))
        test-command (.getCanonicalPath (io/file repository-root "bin/test"))
        output-directory (create-output-directory!
                          (or output-path (default-output-directory)))
        raw-log (io/file output-directory "runner.log")
        command (into [test-command] namespaces)
        selected (selected-namespaces repository-root namespaces)
        process-builder (doto (ProcessBuilder. ^java.util.List command)
                          (.directory repository-root)
                          (.redirectErrorStream true))
        began-nanos (System/nanoTime)
        process (.start process-builder)
        captured
        (with-open [reader (BufferedReader.
                           (InputStreamReader. (.getInputStream process)))
                    log-writer (io/writer raw-log)]
          (loop [state {:suite.profile/selected-namespaces selected
                        :suite.profile/namespaces []
                        :suite.profile/tests []
                        :suite.profile/anomalies []}]
            (if-let [line (.readLine reader)]
              (let [observed-at (Instant/now)]
                (println line)
                (flush)
                (.write log-writer (str observed-at "\t" line "\n"))
                (.flush log-writer)
                (recur (if-let [event (parse-progress-line line)]
                         (capture-event state event)
                         state)))
              state)))
        exit (.waitFor process)
        wall-ms (/ (- (System/nanoTime) began-nanos) 1000000.0)
        captured (finish-capture captured)
        namespace-profiles
        (summarize-namespaces (:suite.profile/namespaces captured)
                              (:suite.profile/tests captured))
        tests-by-duration
        (vec (sort-by :suite.profile/wall-ms > (:suite.profile/tests captured)))
        namespaces-by-duration
        (vec (sort-by :suite.profile/wall-ms > namespace-profiles))
        profile
        {:suite.profile/command command
         :suite.profile/output-directory (.getCanonicalPath output-directory)
         :suite.profile/exit exit
         :suite.profile/wall-ms wall-ms
         :suite.profile/namespaces namespace-profiles
         :suite.profile/tests (:suite.profile/tests captured)
         :suite.profile/namespaces-by-duration namespaces-by-duration
         :suite.profile/tests-by-duration tests-by-duration
         :suite.profile/anomalies (:suite.profile/anomalies captured)}]
    (write-edn! (io/file output-directory "profile.edn") profile)
    (write-tsv! (io/file output-directory "namespaces.tsv")
                ["wall_ms" "test_ms" "non_test_ms" "test_count" "namespace"]
                (map (fn [namespace-profile]
                       [(decimal (:suite.profile/wall-ms namespace-profile))
                        (decimal (:suite.profile/test-ms namespace-profile))
                        (decimal (:suite.profile/non-test-ms namespace-profile))
                        (:suite.profile/test-count namespace-profile)
                        (:suite.profile/namespace namespace-profile)])
                     namespaces-by-duration))
    (write-tsv! (io/file output-directory "tests.tsv")
                ["wall_ms" "namespace" "test"]
                (map (fn [test-profile]
                       [(decimal (:suite.profile/wall-ms test-profile))
                        (:suite.profile/namespace test-profile)
                        (:suite.profile/test test-profile)])
                     tests-by-duration))
    (report! profile)
    (if (seq (:suite.profile/anomalies profile)) 65 exit)))

(let [arguments (parse-arguments *command-line-args*)]
  (if (:suite.profile/help? arguments)
    (println (usage))
    (System/exit (run-profile! arguments))))
