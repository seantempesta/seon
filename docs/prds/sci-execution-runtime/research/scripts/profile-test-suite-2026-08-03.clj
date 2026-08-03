;; Profile `bin/test` without changing which tests it discovers or runs.
;; Run from the repository root:
;;
;;   clojure -M \
;;     docs/prds/sci-execution-runtime/research/scripts/profile-test-suite-2026-08-03.clj \
;;     --output tmp/test-profiles/full
;;
;; Parse a completed captured log without invoking another suite:
;;
;;   clojure -M \
;;     docs/prds/sci-execution-runtime/research/scripts/profile-test-suite-2026-08-03.clj \
;;     --input tmp/orchestrator/full-suite-2026-08-03.log \
;;     --output tmp/test-profiles/baseline
;;
;; Without `--input`, namespace arguments select a focused run. That mode
;; invokes `bin/test` without a result cluster, so the suite retains its private
;; operator root and cannot write to the live `default` cluster.

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(import '[java.io BufferedReader InputStreamReader]
        '[java.lang ProcessHandle]
        '[java.time Duration Instant])

(def ^:private output-option "--output")
(def ^:private input-option "--input")
(def ^:private progress-prefix "bin/test: ")
(def ^:private suite-start-prefix "START ")

(def ^:private event-prefixes
  [{:suite.profile/event :suite.profile/begin-namespace
    :suite.profile/prefix "BEGIN namespace "}
   {:suite.profile/event :suite.profile/end-namespace
    :suite.profile/prefix "END namespace "}
   {:suite.profile/event :suite.profile/begin-test
    :suite.profile/prefix "BEGIN test "}
   {:suite.profile/event :suite.profile/end-test
    :suite.profile/prefix "END test "}])

(def ^:private load-event-prefixes
  [{:suite.profile/event :suite.profile/end-load
    :suite.profile/prefix "LOADED "}
   {:suite.profile/event :suite.profile/begin-load
    :suite.profile/prefix "LOAD "}])

(defn- usage
  []
  (str "Usage: clojure -M " *file*
       " [--input LOG] [--output DIRECTORY] [test-namespaces...]"))

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

        (= input-option argument)
        (if-let [input-log (second remaining)]
          (recur (nnext remaining)
                 (assoc parsed :suite.profile/input-log input-log))
          (throw (ex-info (str input-option " requires a log path.")
                          {:suite.profile/arguments arguments})))

        (str/starts-with? argument "--")
        (throw (ex-info (str "Unknown option: " argument)
                        {:suite.profile/arguments arguments}))

        :else
        (recur (next remaining)
               (update parsed :suite.profile/namespaces conj argument)))
      parsed)))

(defn- validate-arguments
  [{input-log :suite.profile/input-log
    output-directory :suite.profile/output-directory
    namespaces :suite.profile/namespaces
    :as arguments}]
  (when (and input-log (seq namespaces))
    (throw (ex-info "--input cannot be combined with test namespaces."
                    {:suite.profile/arguments arguments})))
  (when (and input-log (nil? output-directory))
    (throw (ex-info "--input requires an explicit --output directory."
                    {:suite.profile/arguments arguments})))
  arguments)

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
  (or
   (some (fn [{event :suite.profile/event
               prefix :suite.profile/prefix}]
           (when (str/starts-with? description prefix)
             {:suite.profile/event event
              :suite.profile/subject (subs description (count prefix))}))
         event-prefixes)
   (some (fn [{event :suite.profile/event
               prefix :suite.profile/prefix}]
           (when (str/starts-with? description prefix)
             {:suite.profile/event event
              :suite.profile/subject
              (subs description (inc (.lastIndexOf description " ")))}))
         load-event-prefixes)))

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

(defn- parse-suite-start-line
  [line]
  (when (str/starts-with? line progress-prefix)
    (let [payload (subs line (count progress-prefix))]
      (if (str/starts-with? payload suite-start-prefix)
        {:suite.profile/event :suite.profile/start
         :suite.profile/error :suite.profile/missing-timestamp
         :suite.profile/line line}
        (let [separator (.indexOf payload " ")]
          (when (pos? separator)
            (let [instant-text (subs payload 0 separator)
                  description (subs payload (inc separator))]
              (when (str/starts-with? description suite-start-prefix)
                (try
                  {:suite.profile/event :suite.profile/start
                   :suite.profile/at (Instant/parse instant-text)}
                  (catch Throwable failure
                    {:suite.profile/event :suite.profile/start
                     :suite.profile/error :suite.profile/invalid-timestamp
                     :suite.profile/timestamp instant-text
                     :suite.profile/message (ex-message failure)
                     :suite.profile/line line}))))))))))

(defn- parse-count-between
  [line prefix suffix]
  (when (and (str/starts-with? line prefix)
             (str/ends-with? line suffix))
    (try
      (let [count-value
            (Long/parseLong
             (subs line (count prefix) (- (count line) (count suffix))))]
        (when-not (neg? count-value) count-value))
      (catch NumberFormatException _ nil))))

(defn- parse-run-counts
  [line]
  (when (str/starts-with? line "Ran ")
    (let [test-separator " tests containing "
          assertion-suffix " assertions."
          separator (.indexOf line test-separator)]
      (when (and (pos? separator) (str/ends-with? line assertion-suffix))
        (let [test-count (parse-count-between
                          (subs line 0 separator) "Ran " "")
              assertion-count
              (parse-count-between
               (subs line (+ separator (count test-separator)))
               "" assertion-suffix)]
          (when (and test-count assertion-count)
            {:suite.profile/test-count test-count
             :suite.profile/assertion-count assertion-count}))))))

(defn- parse-outcome-counts
  [line]
  (let [failure-suffix " failures"
        separator ", "
        error-suffix " errors."
        separator-index (.indexOf line separator)]
    (when (pos? separator-index)
      (let [failure-count
            (parse-count-between (subs line 0 separator-index) "" failure-suffix)
            error-count
            (parse-count-between
             (subs line (+ separator-index (count separator))) "" error-suffix)]
        (when (and failure-count error-count)
          {:suite.profile/failure-count failure-count
           :suite.profile/error-count error-count})))))

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
        (if (contains? #{:suite.profile/begin-load
                         :suite.profile/end-load
                         :suite.profile/begin-namespace
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

        (= :suite.profile/begin-load event-type)
        (if (:suite.profile/open-load state)
          (anomaly state event "Namespace load began before the prior load ended.")
          (assoc state :suite.profile/open-load
                 {:suite.profile/subject subject :suite.profile/at at}))

        (= :suite.profile/end-load event-type)
        (let [opened (:suite.profile/open-load state)]
          (cond
            (nil? opened)
            (anomaly state event "Namespace load ended without a matching begin.")

            (not= subject (:suite.profile/subject opened))
            (anomaly state event "Namespace load end did not match its begin.")

            :else
            (-> state
                (update :suite.profile/loads conj
                        {:suite.profile/namespace (symbol subject)
                         :suite.profile/load-ms
                         (elapsed-milliseconds (:suite.profile/at opened) at)})
                (dissoc :suite.profile/open-load))))

        (= :suite.profile/begin-namespace event-type)
        (cond
          (:suite.profile/open-namespace state)
          (anomaly state event "Namespace began before the prior namespace ended.")

          (:suite.profile/open-test state)
          (anomaly state event "Namespace began while a test remained open.")

          :else
          (assoc state
                 :suite.profile/open-namespace
                 {:suite.profile/subject subject :suite.profile/at at}
                 :suite.profile/preceding-boundary-at at))

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
                         :suite.profile/run-ms
                         (elapsed-milliseconds (:suite.profile/at opened) at)})
                (assoc :suite.profile/final-namespace-end-at at)
                (dissoc :suite.profile/open-namespace
                        :suite.profile/preceding-boundary-at))))

        (= :suite.profile/begin-test event-type)
        (cond
          (nil? (:suite.profile/open-namespace state))
          (anomaly state event "Test began outside a namespace.")

          (:suite.profile/open-test state)
          (anomaly state event "Test began before the prior test ended.")

          (nil? (:suite.profile/preceding-boundary-at state))
          (anomaly state event "Test began without a preceding timing boundary.")

          :else
          (assoc state :suite.profile/open-test
                 {:suite.profile/subject subject
                  :suite.profile/at at
                  :suite.profile/pre-begin-ms
                  (elapsed-milliseconds
                   (:suite.profile/preceding-boundary-at state) at)}))

        (= :suite.profile/end-test event-type)
        (let [opened (:suite.profile/open-test state)]
          (cond
            (nil? opened)
            (anomaly state event "Test ended without a matching begin.")

            (not= subject (:suite.profile/subject opened))
            (anomaly state event "Test end did not match its begin.")

            :else
            (let [body-ms (elapsed-milliseconds
                           (:suite.profile/at opened) at)
                  pre-begin-ms (:suite.profile/pre-begin-ms opened)]
              (-> state
                  (update :suite.profile/tests conj
                          {:suite.profile/test (symbol subject)
                           :suite.profile/namespace
                           (symbol (namespace (symbol subject)))
                           :suite.profile/body-ms body-ms
                           :suite.profile/pre-begin-ms pre-begin-ms
                           :suite.profile/effective-ms (+ pre-begin-ms body-ms)})
                  (assoc :suite.profile/preceding-boundary-at at)
                  (dissoc :suite.profile/open-test)))))

        :else state))))

(defn- capture-line
  [state line]
  (if-let [run-counts (parse-run-counts line)]
    (if (:suite.profile/run-counts state)
      (anomaly state
               {:suite.profile/event :suite.profile/duplicate-run-counts
                :suite.profile/line line}
               "More than one terminal test/assertion count was observed.")
      (assoc state :suite.profile/run-counts run-counts))
    (if-let [outcome-counts (parse-outcome-counts line)]
      (if (:suite.profile/outcome-counts state)
        (anomaly state
                 {:suite.profile/event :suite.profile/duplicate-outcome-counts
                  :suite.profile/line line}
                 "More than one terminal failure/error count was observed.")
        (assoc state :suite.profile/outcome-counts outcome-counts))
      (if-let [start-event (parse-suite-start-line line)]
        (cond
          (:suite.profile/error start-event)
          (anomaly state start-event
                   "Suite START event has no valid ISO timestamp.")

          (:suite.profile/suite-start-at state)
          (anomaly state start-event
                   "More than one timestamped suite START was observed.")

          :else
          (assoc state :suite.profile/suite-start-at
                 (:suite.profile/at start-event)))
        (if-let [event (parse-progress-line line)]
          (capture-event state event)
          state)))))

(defn- finish-capture
  [state]
  (cond-> state
    (:suite.profile/open-load state)
    (anomaly {:suite.profile/event :suite.profile/eof}
             "Namespace LOAD had no LOADED before process exit.")

    (:suite.profile/open-test state)
    (anomaly {:suite.profile/event :suite.profile/eof}
             "Test BEGIN had no END before process exit.")

    (:suite.profile/open-namespace state)
    (anomaly {:suite.profile/event :suite.profile/eof}
             "Namespace BEGIN had no END before process exit.")

    (empty? (:suite.profile/namespaces state))
    (anomaly {:suite.profile/event :suite.profile/eof}
             "No complete timestamped namespace was observed.")

    (nil? (:suite.profile/suite-start-at state))
    (anomaly {:suite.profile/event :suite.profile/eof}
             "No timestamped suite START was observed.")

    (and (:suite.profile/suite-start-at state)
         (:suite.profile/final-namespace-end-at state)
         (.isAfter ^Instant (:suite.profile/suite-start-at state)
                   ^Instant (:suite.profile/final-namespace-end-at state)))
    (anomaly {:suite.profile/event :suite.profile/eof}
             "Suite START occurred after the final namespace END.")

    (nil? (:suite.profile/run-counts state))
    (anomaly {:suite.profile/event :suite.profile/eof}
             "No terminal test/assertion count was observed.")

    (nil? (:suite.profile/outcome-counts state))
    (anomaly {:suite.profile/event :suite.profile/eof}
             "No terminal failure/error count was observed.")

    (and (:suite.profile/run-counts state)
         (not= (count (:suite.profile/tests state))
               (get-in state
                       [:suite.profile/run-counts :suite.profile/test-count])))
    (anomaly {:suite.profile/event :suite.profile/eof
              :suite.profile/observed-test-count
              (count (:suite.profile/tests state))
              :suite.profile/reported-test-count
              (get-in state
                      [:suite.profile/run-counts :suite.profile/test-count])}
             "Timestamped test count did not match the terminal summary.")))

(defn- summarize-namespaces
  [namespaces loads tests]
  (let [load-by-namespace (into {}
                                (map (juxt :suite.profile/namespace identity))
                                loads)
        tests-by-namespace (group-by :suite.profile/namespace tests)]
    (mapv
     (fn [namespace-profile]
       (let [namespace-name (:suite.profile/namespace namespace-profile)
             run-ms (:suite.profile/run-ms namespace-profile)
             load-ms (get-in load-by-namespace
                             [namespace-name :suite.profile/load-ms]
                             0.0)
             namespace-tests (get tests-by-namespace namespace-name [])
             body-ms (reduce + 0.0
                             (map :suite.profile/body-ms namespace-tests))
             pre-begin-ms (reduce + 0.0
                                  (map :suite.profile/pre-begin-ms
                                       namespace-tests))
             effective-ms (+ body-ms pre-begin-ms)]
         (assoc namespace-profile
                :suite.profile/load-ms load-ms
                :suite.profile/wall-ms (+ load-ms run-ms)
                :suite.profile/test-count (count namespace-tests)
                :suite.profile/test-body-ms body-ms
                :suite.profile/test-pre-begin-ms pre-begin-ms
                :suite.profile/test-effective-ms effective-ms
                :suite.profile/non-test-run-ms (- run-ms effective-ms))))
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

(defn- displayed-milliseconds
  [number]
  (if (some? number) (decimal number) "unavailable"))

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
             (str "wall_ms="
                  (displayed-milliseconds (:suite.profile/wall-ms profile)))
             (str "namespaces=" (count (:suite.profile/namespaces profile)))
             (str "tests=" (:suite.profile/test-count profile))
             (str "assertions=" (:suite.profile/assertion-count profile))
             (str "failures=" (:suite.profile/failure-count profile))
             (str "errors=" (:suite.profile/error-count profile)))
    (println "WORST NAMESPACES"
             (str "(total_ms load_ms run_ms test_effective_ms test_body_ms "
                  "test_pre_begin_ms terminal_non_test_run_ms tests namespace)"))
    (doseq [namespace-profile worst-namespaces]
      (println (decimal (:suite.profile/wall-ms namespace-profile))
               (decimal (:suite.profile/load-ms namespace-profile))
               (decimal (:suite.profile/run-ms namespace-profile))
               (decimal (:suite.profile/test-effective-ms namespace-profile))
               (decimal (:suite.profile/test-body-ms namespace-profile))
               (decimal (:suite.profile/test-pre-begin-ms namespace-profile))
               (decimal (:suite.profile/non-test-run-ms namespace-profile))
               (:suite.profile/test-count namespace-profile)
               (:suite.profile/namespace namespace-profile)))
    (println "WORST TESTS (effective_ms pre_begin_ms body_ms test)")
    (doseq [test-profile worst-tests]
      (println (decimal (:suite.profile/effective-ms test-profile))
               (decimal (:suite.profile/pre-begin-ms test-profile))
               (decimal (:suite.profile/body-ms test-profile))
               (:suite.profile/test test-profile)))
    (when-let [anomalies (seq (:suite.profile/anomalies profile))]
      (println "PROFILE ERRORS")
      (doseq [problem anomalies]
        (println (pr-str problem))))))

(defn- initial-capture
  [selected]
  {:suite.profile/selected-namespaces selected
   :suite.profile/loads []
   :suite.profile/namespaces []
   :suite.profile/tests []
   :suite.profile/anomalies []})

(defn- consume-reader
  [^BufferedReader reader selected live? log-writer]
  (loop [state (initial-capture selected)]
    (if-let [line (.readLine reader)]
      (do
        (when live?
          (println line)
          (flush))
        (when log-writer
          (.write ^java.io.Writer log-writer (str line "\n"))
          (.flush ^java.io.Writer log-writer))
        (recur (capture-line state line)))
      state)))

(defn- complete-profile!
  [output-directory captured base-profile]
  (let [captured (finish-capture captured)
        namespace-profiles
        (summarize-namespaces (:suite.profile/namespaces captured)
                              (:suite.profile/loads captured)
                              (:suite.profile/tests captured))
        tests-by-duration
        (vec (sort-by :suite.profile/effective-ms >
                      (:suite.profile/tests captured)))
        namespaces-by-duration
        (vec (sort-by :suite.profile/wall-ms > namespace-profiles))
        counts (merge (:suite.profile/run-counts captured)
                      (:suite.profile/outcome-counts captured))
        profile
        (merge base-profile
               counts
               {:suite.profile/output-directory
                (.getCanonicalPath ^java.io.File output-directory)
                :suite.profile/namespaces namespace-profiles
                :suite.profile/tests (:suite.profile/tests captured)
                :suite.profile/namespaces-by-duration namespaces-by-duration
                :suite.profile/tests-by-duration tests-by-duration
                :suite.profile/anomalies (:suite.profile/anomalies captured)})]
    (write-edn! (io/file output-directory "profile.edn") profile)
    (write-tsv! (io/file output-directory "namespaces.tsv")
                ["total_ms" "load_ms" "run_ms" "test_effective_ms"
                 "test_body_ms" "test_pre_begin_ms"
                 "terminal_non_test_run_ms" "test_count" "namespace"]
                (map (fn [namespace-profile]
                       [(decimal (:suite.profile/wall-ms namespace-profile))
                        (decimal (:suite.profile/load-ms namespace-profile))
                        (decimal (:suite.profile/run-ms namespace-profile))
                        (decimal
                         (:suite.profile/test-effective-ms namespace-profile))
                        (decimal (:suite.profile/test-body-ms namespace-profile))
                        (decimal
                         (:suite.profile/test-pre-begin-ms namespace-profile))
                        (decimal (:suite.profile/non-test-run-ms namespace-profile))
                        (:suite.profile/test-count namespace-profile)
                        (:suite.profile/namespace namespace-profile)])
                     namespaces-by-duration))
    (write-tsv! (io/file output-directory "tests.tsv")
                ["effective_ms" "pre_begin_ms" "body_ms" "namespace" "test"]
                (map (fn [test-profile]
                       [(decimal (:suite.profile/effective-ms test-profile))
                        (decimal (:suite.profile/pre-begin-ms test-profile))
                        (decimal (:suite.profile/body-ms test-profile))
                        (:suite.profile/namespace test-profile)
                        (:suite.profile/test test-profile)])
                     tests-by-duration))
    (report! profile)
    profile))

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
          (consume-reader reader selected true log-writer))
        exit (.waitFor process)
        wall-ms (/ (- (System/nanoTime) began-nanos) 1000000.0)
        profile
        (complete-profile!
         output-directory captured
         {:suite.profile/command command
          :suite.profile/exit exit
          :suite.profile/wall-ms wall-ms
          :suite.profile/wall-source :suite.profile/child-process})]
    (if (seq (:suite.profile/anomalies profile)) 65 exit)))

(defn- selected-namespaces-in-log
  [input-log]
  (with-open [reader (io/reader input-log)]
    (into #{}
          (keep (fn [line]
                  (let [event (parse-progress-line line)]
                    (when (and (= :suite.profile/begin-load
                                  (:suite.profile/event event))
                               (nil? (:suite.profile/error event)))
                      (symbol (:suite.profile/subject event))))))
          (line-seq reader))))

(defn- input-profile!
  [{input-path :suite.profile/input-log
    output-path :suite.profile/output-directory}]
  (let [input-log (.getCanonicalFile (io/file input-path))
        _ (when-not (and (.isFile input-log) (.canRead input-log))
            (throw (ex-info "--input must name a readable regular file."
                            {:suite.profile/input-log (.getPath input-log)})))
        output-directory (create-output-directory! output-path)
        selected (selected-namespaces-in-log input-log)
        captured (with-open [reader (BufferedReader. (io/reader input-log))]
                   (consume-reader reader selected false nil))
        wall-ms (when (and (:suite.profile/suite-start-at captured)
                           (:suite.profile/final-namespace-end-at captured))
                  (elapsed-milliseconds
                   (:suite.profile/suite-start-at captured)
                   (:suite.profile/final-namespace-end-at captured)))
        counts (merge (:suite.profile/run-counts captured)
                      (:suite.profile/outcome-counts captured))
        derived-exit
        (when (and (:suite.profile/failure-count counts)
                   (:suite.profile/error-count counts))
          (if (zero? (+ (:suite.profile/failure-count counts)
                        (:suite.profile/error-count counts)))
            0
            1))
        profile
        (complete-profile!
         output-directory captured
         {:suite.profile/input-log (.getCanonicalPath input-log)
          :suite.profile/exit derived-exit
          :suite.profile/wall-ms wall-ms
          :suite.profile/wall-source
          :suite.profile/timestamped-runner-progress-span})]
    (if (seq (:suite.profile/anomalies profile)) 65 derived-exit)))

(let [arguments (validate-arguments (parse-arguments *command-line-args*))]
  (if (:suite.profile/help? arguments)
    (println (usage))
    (System/exit
     (if (:suite.profile/input-log arguments)
       (input-profile! arguments)
       (run-profile! arguments)))))
