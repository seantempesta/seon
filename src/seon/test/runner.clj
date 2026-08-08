(ns seon.test.runner
  "Run the JVM gate and optionally commit per-test result facts."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [seon.config :as config]
            [seon.db :as db]
            [seon.test.selection :as selection])
  (:import (java.lang ProcessHandle Runtime Thread)
           (java.lang.management ManagementFactory ThreadInfo)
           (java.time Instant)
           (java.util.concurrent Executors ThreadFactory TimeUnit))
  (:gen-class))

(defn- var-symbol
  [test-var]
  (when test-var
    (let [{:keys [name ns]} (meta test-var)]
      (when (and name ns)
        (symbol (str (ns-name ns)) (str name))))))

(defn- event-symbol
  [event]
  (var-symbol (or (:var event) (first test/*testing-vars*))))

(defn- bounded-text
  [options text]
  (let [text (str text)
        max-chars (:seon.config.eval.result/blob-threshold options)
        suffix "\n... additional failure output elided by bin/test"]
    (if (<= (count text) max-chars)
      text
      (if (<= max-chars (count suffix))
        (subs suffix 0 max-chars)
        (str (subs text 0 (- max-chars (count suffix))) suffix)))))

(defn- printable
  [options value]
  (bounded-text
   options
   (if (instance? Throwable value)
     (str (.getName (class value)) ": " (or (ex-message value) ""))
     (binding [*print-length* (:seon.print/length options)
               *print-level* (:seon.print/level options)]
       (pr-str value)))))

(defn- throwable-signature
  [^Throwable failure]
  (loop [current failure]
    (when current
      (or (:seon.error/signature (ex-data current))
          (recur (ex-cause current))))))

(defn- event-signature
  [event]
  (or (:seon.error/signature event)
      (when (instance? Throwable (:actual event))
        (throwable-signature (:actual event)))))

(defn- throwable-face
  [options ^Throwable failure signature]
  (with-out-str
    (println (str (.getName (class failure)) ": "
                  (or (ex-message failure) "")))
    (doseq [frame (take (:seon.print/length options)
                        (.getStackTrace failure))]
      (println "    at" frame))
    (when signature
      (println "  signature:" signature))))

(defn- failure-message
  [options event]
  (->> [(when (seq test/*testing-contexts*)
          (test/testing-contexts-str))
        (:message event)
        (when (contains? event :expected)
          (str "expected: " (printable options (:expected event))))
        (when (contains? event :actual)
          (str "actual: " (printable options (:actual event))))]
       (remove str/blank?)
       (str/join "\n")
       (bounded-text options)))

(defn- ensure-result
  [capture test-symbol]
  (if (contains? (::results capture) test-symbol)
    capture
    (-> capture
        (update ::order conj test-symbol)
        (assoc-in [::results test-symbol]
                  {:seon.test/sym (str test-symbol)
                   :seon.ns/name (symbol (namespace test-symbol))
                   :seon.test.result/outcome :pass
                   ::failure-messages []
                   ::failure-signatures #{}}))))

(defn- capture-event!
  [options capture selected-namespaces event]
  (when-let [test-symbol (event-symbol event)]
    (when (contains? selected-namespaces (symbol (namespace test-symbol)))
      (swap! capture
             (fn [current]
               (let [current (ensure-result current test-symbol)
                     signature (event-signature event)
                     seen? (and signature
                                (contains?
                                 (get-in current [::results test-symbol
                                                  ::failure-signatures])
                                 signature))]
                 (if (contains? #{:fail :error} (:type event))
                   (cond-> (assoc-in current
                                     [::results test-symbol
                                      :seon.test.result/outcome]
                                     :fail)
                     (and signature (not seen?))
                     (update-in [::results test-symbol ::failure-signatures]
                                conj signature)
                     (not seen?)
                     (update-in [::results test-symbol ::failure-messages]
                                conj (failure-message options event)))
                   current)))))))

(defn- report-error!
  {:seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
  [options event signature]
  (test/with-test-out
    (test/inc-report-counter :error)
    (print
     (bounded-text
      options
      (with-out-str
        (println "\nERROR in" (test/testing-vars-str event))
        (when (seq test/*testing-contexts*)
          (println (test/testing-contexts-str)))
        (when-let [message (:message event)]
          (println message))
        (println "expected:" (printable options (:expected event)))
        (print "  actual: ")
        (println (throwable-face options (:actual event) signature)))))))

(defn- report-event!
  [options default-report reported-signatures event]
  (if (and (= :error (:type event))
           (instance? Throwable (:actual event)))
    (let [signature (event-signature event)]
      (if (and signature (contains? @reported-signatures signature))
        (test/inc-report-counter :error)
        (do
          (when signature
            (swap! reported-signatures conj signature))
          (report-error! options event signature))))
    (default-report event)))

(defn- announce!
  {:seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
  [progress description]
  (let [at (Instant/now)]
    (reset! progress
            {::description description
             ::at-nanos (System/nanoTime)
             ::at at})
    (println "bin/test:" (str at) description)
    (flush)))

(defn- progress-event!
  [progress event]
  (case (:type event)
    :begin-test-ns
    (announce! progress (str "BEGIN namespace " (ns-name (:ns event))))

    :end-test-ns
    (announce! progress (str "END namespace " (ns-name (:ns event))))

    :begin-test-var
    (announce! progress (str "BEGIN test " (event-symbol event)))

    :end-test-var
    (announce! progress (str "END test " (event-symbol event)))

    nil))

(defn- process-description
  [^ProcessHandle process]
  (let [info (.info process)]
    (str "pid=" (.pid process)
         " alive=" (.isAlive process)
         " start=" (.orElse (.startInstant info) nil)
         " command=" (.orElse (.command info) nil))))

(defn- thread-info-text
  [^ThreadInfo info]
  (with-out-str
    (println (str "\"" (.getThreadName info) "\""
                  " id=" (.getThreadId info)
                  " state=" (.getThreadState info)
                  (when-let [lock (.getLockInfo info)]
                    (str " waiting-on=" lock))
                  (when (pos? (.getLockOwnerId info))
                    (str " owned-by=\"" (.getLockOwnerName info)
                         "\" id=" (.getLockOwnerId info)))))
    (doseq [frame (.getStackTrace info)]
      (println "\tat" frame))
    (doseq [monitor (.getLockedMonitors info)]
      (println "\tlocked monitor" monitor))
    (doseq [synchronizer (.getLockedSynchronizers info)]
      (println "\tlocked synchronizer" synchronizer))))

(defn- liveness-diagnostic
  [progress silence-seconds suite-start virtual-thread-dump]
  (let [process (ProcessHandle/current)
        child-processes (vec (.toList (.descendants process)))
        thread-bean (ManagementFactory/getThreadMXBean)
        deadlocked (some-> (.findDeadlockedThreads thread-bean) vec)]
    {::child-processes child-processes
     ::text
     (with-out-str
       (println "bin/test: SUITE LIVENESS BUG")
       (println "bin/test: no reporter progress for" silence-seconds "seconds")
       (println "bin/test: process" (process-description process))
       (println "bin/test: suite-start" suite-start)
       (println "bin/test: last-progress" (pr-str @progress))
       (println "bin/test: isolated-operator-root"
                (or (System/getProperty "seon.test.root")
                    (System/getProperty "seon.operator.root")))
       (println "bin/test: working-directory"
                (.getCanonicalPath (io/file ".")))
       (println "bin/test: run"
                (try
                  (str/trim (slurp (io/file "test-run.txt")))
                  (catch Throwable failure
                    (str "unavailable: " (ex-message failure)))))
       (println "bin/test: deadlocked-thread-ids" (pr-str deadlocked))
       (println "bin/test: descendants")
       (if (seq child-processes)
         (doseq [child-process child-processes]
           (println "bin/test:  " (process-description child-process)))
         (println "bin/test:   none"))
       (println "bin/test: virtual-thread-aware JVM dump" virtual-thread-dump)
       (println "bin/test: platform-thread MXBean supplement")
       (doseq [info (.dumpAllThreads thread-bean true true)]
         (print (thread-info-text info))))}))

(def ^:private jcmd-backstop-seconds
  "The foreign diagnostic process's loud last-resort bound."
  10)

(defn- persist-virtual-thread-dump!
  []
  (let [directory (io/file "tmp" "test-liveness")
        _ (.mkdirs directory)
        file (io/file directory
                      (str (.pid (ProcessHandle/current)) "-"
                           (System/currentTimeMillis) "-threads.json"))
        jcmd (io/file (System/getProperty "java.home") "bin" "jcmd")
        command [(str jcmd)
                 (str (.pid (ProcessHandle/current)))
                 "Thread.dump_to_file"
                 "-format=json"
                 (.getCanonicalPath file)]
        process (.start (ProcessBuilder. (into-array String command)))
        completed? (.waitFor process jcmd-backstop-seconds TimeUnit/SECONDS)]
    (when-not completed?
      (.destroyForcibly process)
      (throw
       (ex-info "jcmd did not complete its virtual-thread-aware dump."
                {::command command})))
    (let [output (str/trim (slurp (.getInputStream process)))]
      (when-not (zero? (.exitValue process))
        (throw
         (ex-info "jcmd refused the virtual-thread-aware dump."
                  {::command command
                   ::exit (.exitValue process)
                   ::output output}))))
    (.getCanonicalPath file)))

(defn- persist-diagnostic!
  {:seon.fn/external-sink :codec-storage
   :seon.fn/projection-boundary :none}
  [text]
  (let [directory (io/file "tmp" "test-liveness")
        _ (.mkdirs directory)
        file (io/file directory
                      (str (.pid (ProcessHandle/current)) "-"
                           (System/currentTimeMillis) ".log"))]
    (spit file text)
    (.getCanonicalPath file)))

(defn- stop-descendants!
  [child-processes]
  (doseq [^ProcessHandle child-process (reverse child-processes)]
    (when (.isAlive child-process)
      (.destroyForcibly child-process))))

(defn- fire-liveness-backstop!
  {:seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
  [progress silence-seconds suite-start]
  (let [virtual-thread-dump
        (try
          (persist-virtual-thread-dump!)
          (catch Throwable failure
            (str "unavailable: " (ex-message failure))))
        {::keys [child-processes text]}
        (liveness-diagnostic progress silence-seconds suite-start
                             virtual-thread-dump)
        log-path (try
                   (persist-diagnostic! text)
                   (catch Throwable failure
                     (str "unavailable: " (ex-message failure))))]
    (binding [*out* *err*]
      (print text)
      (println "bin/test: diagnostic-log" log-path)
      (println "bin/test: forcibly stopping suite descendants and exiting 124")
      (flush))
    (stop-descendants! child-processes)
    (.halt (Runtime/getRuntime) 124)))

(defn- silence-seconds
  []
  (let [configured (System/getenv "SEON_TEST_SILENCE_SECONDS")]
    (if (str/blank? configured)
      300
      (let [seconds (try
                      (Long/parseLong configured)
                      (catch NumberFormatException _
                        0))]
        (when-not (pos? seconds)
          (throw
           (ex-info
            "SEON_TEST_SILENCE_SECONDS must be a positive integer."
            {:seon.error/kind ::invalid-silence-seconds
             ::configured configured})))
        seconds))))

(defn- start-liveness-backstop!
  [progress silence-limit-seconds suite-start]
  (let [fired? (atom false)
        executor
        (Executors/newSingleThreadScheduledExecutor
         (reify ThreadFactory
           (newThread [_ runnable]
             (doto (Thread. runnable "seon-test-liveness-backstop")
               (.setDaemon true)))))
        check
        (reify Runnable
          (run [_]
            (let [silent-nanos (- (System/nanoTime) (::at-nanos @progress))]
              (when (and (>= silent-nanos
                             (.toNanos TimeUnit/SECONDS
                                       silence-limit-seconds))
                         (compare-and-set! fired? false true))
                (fire-liveness-backstop!
                 progress silence-limit-seconds suite-start)))))]
    (.scheduleAtFixedRate executor check 1 1 TimeUnit/SECONDS)
    executor))

(defn- captured-results
  [{::keys [order results]}]
  (mapv
   (fn [test-symbol]
     (let [result (get results test-symbol)
           messages (::failure-messages result)]
       (cond-> (dissoc result ::failure-messages ::failure-signatures)
         (seq messages)
         (assoc :seon.test.failure/message (str/join "\n\n" messages)))))
   order))

(defn- test-vars-in
  [namespaces]
  (into []
        (mapcat
         (fn [namespace-name]
           (->> (ns-interns namespace-name)
                vals
                (filter (comp :test meta))
                (sort-by var-symbol))))
        namespaces))

(defn- marker-reason
  "The declared non-blank reason for one test marker, from the var or its ns."
  [test-var marker-attribute]
  (let [var-metadata (meta test-var)
        namespace-metadata (meta (:ns var-metadata))
        marker (if (contains? var-metadata marker-attribute)
                 (get var-metadata marker-attribute)
                 (get namespace-metadata marker-attribute))]
    (when (some? marker)
      (when-not (and (string? marker) (not (str/blank? marker)))
        (throw
         (ex-info
          (str marker-attribute " must contain a non-blank reason.")
          {:seon.error/kind ::invalid-marker-reason
           :seon.test/sym (str (var-symbol test-var))
           ::marker marker-attribute
           ::value marker})))
      marker)))

(defn- long-reason
  [test-var]
  (marker-reason test-var :seon.test/long))

(defn- platform-reason
  [test-var]
  (marker-reason test-var :seon.test/platform))

(defn- test-selection
  "Partition every test var into the platform tier, the bulk tier, and skips.

  The platform tier is the declared `:seon.test/platform` moving-part
  regression set: it runs FIRST on every invocation so a broken platform
  fails in seconds instead of poisoning the bulk. `selected-symbols` bounds
  the bulk tier to the tests one change can reach; `:all` runs every
  eligible test."
  [namespaces {::keys [include-long? selected-symbols]}]
  (reduce
   (fn [selection test-var]
     (let [test-symbol (var-symbol test-var)
           long-marker (long-reason test-var)
           platform (platform-reason test-var)]
       (cond
         (and (not include-long?) long-marker)
         (update selection ::skipped conj
                 {::test-symbol test-symbol ::reason long-marker})

         platform
         (update selection ::platform conj test-var)

         (or (= :all selected-symbols)
             (contains? selected-symbols (str test-symbol)))
         (update selection ::selected conj test-var)

         :else
         (update selection ::unreached conj test-symbol))))
   {::platform [] ::selected [] ::skipped [] ::unreached []}
   (test-vars-in namespaces)))

(defn- run-selected-tests
  [namespaces selected-vars]
  (let [selected-by-namespace (group-by (comp :ns meta) selected-vars)]
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (doseq [namespace-name namespaces
              :let [namespace-object (the-ns namespace-name)
                    namespace-vars (get selected-by-namespace namespace-object)]
              :when (seq namespace-vars)]
        (test/do-report {:type :begin-test-ns :ns namespace-object})
        (if-let [hook (find-var
                       (symbol (str namespace-name) "test-ns-hook"))]
          (if (= (count namespace-vars)
                 (count (filter (comp :test meta)
                                (vals (ns-interns namespace-object)))))
            ((var-get hook))
            (throw
             (ex-info
              "A namespace test hook cannot select around long test vars."
              {:seon.error/kind ::long-test-ns-hook
               :seon.ns/name namespace-name})))
          (test/test-vars namespace-vars))
        (test/do-report {:type :end-test-ns :ns namespace-object}))
      @test/*report-counters*)))

(defn- red?
  [raw-summary]
  (pos? (+ (or (:fail raw-summary) 0) (or (:error raw-summary) 0))))

(defn- sum-summaries
  [raw-summaries]
  (reduce (fn [total summary]
            (merge-with + total (select-keys summary
                                             [:test :pass :fail :error])))
          {:test 0 :pass 0 :fail 0 :error 0}
          raw-summaries))

(defn- run-tiers!
  "Run ordered tiers, stopping after the first red fail-fast tier."
  [namespaces progress tiers]
  (loop [remaining tiers
         summaries []
         stopped nil]
    (if-let [{::keys [tier-name vars fail-fast?]} (first remaining)]
      (if (empty? vars)
        (recur (rest remaining) summaries stopped)
        (do
          (when progress
            (announce! progress
                       (str "TIER " (name tier-name) " " (count vars)
                            " tests")))
          (let [raw-summary (run-selected-tests namespaces vars)
                summaries (conj summaries raw-summary)]
            (if (and fail-fast? (red? raw-summary))
              (recur nil summaries tier-name)
              (recur (rest remaining) summaries stopped)))))
      {::raw-summary (sum-summaries summaries)
       ::stopped-after stopped})))

(defn- run-request!
  [request progress tiers]
  (let [selected-namespaces (set (:seon.test.runner/namespaces request))
        options (select-keys
                 (config/defaults)
                 [:seon.config.eval.result/blob-threshold
                  :seon.print/length
                  :seon.print/level])
        capture (atom {::order [] ::results {}})
        reported-signatures (atom #{})
        default-report test/report
        {::keys [raw-summary stopped-after]}
        (binding [test/report
                  (fn [event]
                    (capture-event! options capture selected-namespaces event)
                    (when progress
                      (progress-event! progress event))
                    (report-event! options default-report
                                   reported-signatures event))]
          (if tiers
            (let [outcome (run-tiers! (:seon.test.runner/namespaces request)
                                      progress tiers)]
              (test/do-report (assoc (::raw-summary outcome) :type :summary))
              outcome)
            {::raw-summary
             (apply test/run-tests (:seon.test.runner/namespaces request))}))
        summary
        {::test-count (:test raw-summary)
         ::pass-count (:pass raw-summary)
         ::fail-count (:fail raw-summary)
         ::error-count (:error raw-summary)}]
    (cond->
     {:seon.test.run/id (:seon.test.run/id request)
      :seon.test.run/at (:seon.test.run/at request)
      :seon.test.run/git-sha (:seon.test.run/git-sha request)
      :seon.test.runner/summary summary
      :seon.test.runner/results (captured-results @capture)}
      stopped-after (assoc ::stopped-after stopped-after))))

(defn run!
  "Run namespaces through `clojure.test` and return per-test values.

  Ordinary events use the default reporter. Throwable errors have one bounded
  face per existing error signature while every event remains counted."
  {:malli/schema [:=> [:cat :seon.test.runner/run-request]
                  :seon.test.runner/run-result]}
  [{namespaces :seon.test.runner/namespaces
    run-id :seon.test.run/id
    at :seon.test.run/at
    git-sha :seon.test.run/git-sha}]
  (run-request! {:seon.test.runner/namespaces namespaces
                 :seon.test.run/id run-id
                 :seon.test.run/at at
                 :seon.test.run/git-sha git-sha}
                nil
                nil))

(defn- namespace-tempid
  [namespace-name]
  (str "namespace:" namespace-name))

(defn- test-tempid
  [test-symbol]
  (str "test:" test-symbol))

(defn- result-id
  [run-id test-symbol]
  (str run-id ":" test-symbol))

(defn record-tx
  "Transaction data for one captured run and its exact test refs."
  {:malli/schema [:=> [:cat :seon.test.runner/run-result]
                  :seon.test.runner/record-tx]}
  [{run-id :seon.test.run/id
    at :seon.test.run/at
    git-sha :seon.test.run/git-sha
    results :seon.test.runner/results}]
  (let [namespace-names (distinct (map :seon.ns/name results))
        namespace-rows
        (mapv (fn [namespace-name]
                {:db/id (namespace-tempid namespace-name)
                 :seon.ns/name namespace-name})
              namespace-names)
        test-rows
        (mapv (fn [{test-symbol :seon.test/sym
                    namespace-name :seon.ns/name}]
                {:db/id (test-tempid test-symbol)
                 :seon.test/sym test-symbol
                 :seon.test/ns (namespace-tempid namespace-name)})
              results)
        run-tempid (str "run:" run-id)
        run-row {:db/id run-tempid
                 :seon.test.run/id run-id
                 :seon.test.run/at at
                 :seon.test.run/git-sha git-sha}
        failure-rows
        (into []
              (keep
               (fn [{test-symbol :seon.test/sym
                     message :seon.test.failure/message}]
                 (when message
                   {:db/id (str "failure:" (result-id run-id test-symbol))
                    :seon.test.failure/id
                    (str (result-id run-id test-symbol) ":failure")
                    :seon.test.failure/message message})))
              results)
        result-rows
        (mapv
         (fn [{test-symbol :seon.test/sym
               outcome :seon.test.result/outcome
               message :seon.test.failure/message}]
           (cond-> {:seon.test.result/id (result-id run-id test-symbol)
                    :seon.test.result/test (test-tempid test-symbol)
                    :seon.test.result/run run-tempid
                    :seon.test.result/outcome outcome}
             message
             (assoc :seon.test.result/failure
                    (str "failure:" (result-id run-id test-symbol)))))
         results)]
    (into namespace-rows
          (concat test-rows [run-row] failure-rows result-rows))))

(defn- start-cluster!
  [cluster-name root]
  (let [start! (requiring-resolve 'seon.cluster/start!)
        stop! (requiring-resolve 'seon.cluster/stop!)]
    (try
      (start! {:seon.boot/cluster-name cluster-name
               :seon.boot/root root})
      (catch Throwable failure
        (when-let [instance (:seon.boot/instance (ex-data failure))]
          (stop! instance))
        (throw failure)))))

(defn record!
  "Commit one run into an explicitly named, non-default cluster."
  {:malli/schema [:=> [:cat :seon.test.runner/record-request]
                  :seon.test.runner/recorded]}
  [{run-result :seon.test.runner/run-result
    cluster-name :seon.boot/cluster-name
    root :seon.boot/root}]
  (when (= "default" cluster-name)
    (throw
     (ex-info
      "Test results may not be written into the default cluster."
      {:seon.error/kind ::default-cluster-refused
       :seon.boot/cluster-name cluster-name})))
  (let [instance (start-cluster! cluster-name root)]
    (try
      (let [result
            (db/transact!
             (:seon.boot/cluster-connection instance)
             (record-tx run-result))]
        (when (:seon.error/kind result)
          (throw
           (ex-info "The test result transaction was refused."
                    {:seon.test.runner/refusal result}))))
      {:seon.boot/cluster-name cluster-name
       :seon.test.run/id (:seon.test.run/id run-result)
       :seon.test.runner/recorded-count
      (count (:seon.test.runner/results run-result))}
      (finally
        ((requiring-resolve 'seon.cluster/stop!) instance)))))

(defn- print-skipped!
  {:seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
  [skipped]
  (when (seq skipped)
    (println)
    (println "bin/test: skipped" (count skipped) "long tests:")
    (doseq [{::keys [test-symbol reason]} skipped]
      (println " -" test-symbol "-" reason))
    (println "bin/test: run skipped coverage with: bin/test --full")))

(def ^:private selection-modes
  #{"changed" "all" "full" "platform" "explicit"})

(defn- source-root
  []
  (or (System/getProperty "seon.test.source-root")
      (System/getProperty "seon.test.root")
      "."))

(defn- requested-changed-paths
  "Repository-relative paths the launcher named with `--changed`."
  []
  (let [file (some-> (System/getProperty "seon.test.changed-paths-file")
                     io/file)]
    (if (and file (.isFile file))
      (->> (str/split-lines (slurp file))
           (remove str/blank?)
           (mapv str/trim))
      [])))

(defn- reaching-selection
  "Bulk-tier test symbols for one set of changed repository-relative paths."
  [progress changed-paths]
  (let [build-manifest (requiring-resolve 'seon.fn/build-manifest)
        relative (requiring-resolve 'seon.test.selection/manifest-relative-artifacts)]
    (announce! progress
               (str "SELECT building the program graph for "
                    (count changed-paths) " changed path(s)"))
    (let [manifest (build-manifest {:seon.fn/roots selection/graph-roots})
          artifacts (relative "." manifest)
          tests (selection/reaching-tests artifacts changed-paths)]
      {::symbols (set tests)
       ::reason (str (count tests) " test(s) reach "
                     (count changed-paths) " changed path(s)")})))

(defn- bulk-selection
  "Resolve the bulk tier: every eligible test, a reaching subset, or none.

  Widening is loud and named. A missing basis, a removed file, or a change to
  a declared gate input no call edge can reach all widen to every eligible
  test rather than guessing a narrower answer."
  [selection-mode progress]
  (case selection-mode
    ("all" "full") {::symbols :all
                    ::reason (str "the " selection-mode " tier")
                    ::digests (selection/input-digests ".")}
    "platform" {::symbols #{} ::reason "platform tier only"}
    "changed"
    (let [explicit (requested-changed-paths)]
      (if (seq explicit)
        (if-let [widening (seq (filter selection/widening-path? explicit))]
          {::symbols :all
           ::reason (str "changed gate input outside the program graph: "
                         (str/join ", " (take 5 widening)))}
          (reaching-selection progress explicit))
        (if-let [basis (selection/read-basis (source-root))]
          (let [current (selection/input-digests ".")
                {changed :seon.test.selection/changed
                 removed :seon.test.selection/removed}
                (selection/changed-inputs
                 (:seon.test.basis/digests basis) current)]
            (cond
              (seq removed)
              {::symbols :all
               ::reason (str "input(s) removed since the green basis: "
                             (str/join ", " (take 5 removed)))
               ::digests current}

              (empty? changed)
              {::symbols #{}
               ::reason (str "no input changed since the green basis recorded "
                             (:seon.test.basis/at basis))
               ::digests current}

              :else
              (if-let [widening (seq (filter selection/widening-path? changed))]
                {::symbols :all
                 ::reason (str "changed gate input outside the program graph: "
                               (str/join ", " (take 5 widening)))
                 ::digests current}
                (assoc (reaching-selection progress changed)
                       ::digests current
                       ::changed changed))))
          {::symbols :all
           ::reason "no green basis is recorded yet"
           ::digests (selection/input-digests ".")})))))

(defn- record-green-basis!
  {:seon.fn/external-sink :codec-storage
   :seon.fn/projection-boundary :none}
  [selection-mode git-sha digests]
  (selection/write-basis!
   (source-root)
   {:seon.test.basis/at (str (Instant/now))
    :seon.test.basis/git-sha git-sha
    :seon.test.basis/mode selection-mode
    :seon.test.basis/digests digests})
  (println "bin/test: recorded a new green basis over"
           (count digests) "declared inputs"))

(defn -main
  "Run selected tests with progress and a liveness backstop.

  Every tiered invocation runs the declared `:seon.test/platform` moving-part
  regressions FIRST and stops there when they are red. The bulk tier follows:
  every eligible test under `all`/`full`, or only the tests reaching code
  changed since the last recorded GREEN basis under the bare `changed`
  default. Record results when the cluster argument names a non-default
  cluster, then exit zero exactly when no test failed or errored."
  {:malli/schema
   [:=> [:cat :seon.boot/cluster-name :seon.boot/root :string :string
         [:* :string]]
    :nil]}
  [cluster-name root git-sha selection-mode & namespace-names]
  (when-not (contains? selection-modes selection-mode)
    (throw
     (ex-info
      "The test selection mode is not one this runner knows."
      {:seon.error/kind ::invalid-selection-mode
       ::selection-mode selection-mode
       ::known selection-modes})))
  (let [namespaces (mapv symbol namespace-names)
        progress (atom {::description "JVM runner initialized"
                        ::at-nanos (System/nanoTime)
                        ::at (Instant/now)})
        suite-start (Instant/now)
        configured-silence-seconds (silence-seconds)
        backstop (start-liveness-backstop!
                  progress configured-silence-seconds suite-start)]
    (try
      (announce! progress
                 (str "START pid=" (.pid (ProcessHandle/current))
                      " git=" git-sha
                      " namespaces=" (count namespaces)
                      " silence-backstop=" configured-silence-seconds "s"))
      (doseq [[index test-namespace] (map-indexed vector namespaces)]
        (announce! progress
                   (str "LOAD " (inc index) "/" (count namespaces)
                        " " test-namespace))
        (require test-namespace)
        (announce! progress
                   (str "LOADED " (inc index) "/" (count namespaces)
                        " " test-namespace)))
      (let [explicit? (= "explicit" selection-mode)
            bulk (when-not explicit? (bulk-selection selection-mode progress))
            {::keys [platform selected skipped unreached]}
            (if explicit?
              {::platform [] ::selected (test-vars-in namespaces)
               ::skipped [] ::unreached []}
              (test-selection namespaces
                              {::include-long? (= "full" selection-mode)
                               ::selected-symbols (::symbols bulk)}))
            _ (when bulk
                (announce! progress
                           (str "SELECTION " selection-mode " — "
                                (::reason bulk)
                                "; platform " (count platform)
                                ", bulk " (count selected)
                                ", not reached " (count unreached))))
            run-result
            (run-request! {:seon.test.runner/namespaces namespaces
                           :seon.test.run/id (str (random-uuid))
                           :seon.test.run/at (java.util.Date.)
                           :seon.test.run/git-sha git-sha}
                          progress
                          [{::tier-name :platform ::vars platform
                            ::fail-fast? true}
                           {::tier-name :bulk ::vars selected}])
            _ (when-not (= "-" cluster-name)
                (record! {:seon.test.runner/run-result run-result
                          :seon.boot/cluster-name cluster-name
                          :seon.boot/root root}))
            summary (:seon.test.runner/summary run-result)
            green? (zero? (+ (::fail-count summary) (::error-count summary)))
            failures (->> (:seon.test.runner/results run-result)
                          (filter #(= :fail (:seon.test.result/outcome %)))
                          (map :seon.test/sym)
                          sort)]
        (when-let [stopped (::stopped-after run-result)]
          (println)
          (println "bin/test: PLATFORM TIER RED —" (name stopped)
                   "moving-part regressions failed; the bulk tier did not run.")
          (println "bin/test: fix the platform first; a broken platform"
                   "poisons every test that forks it."))
        (when (seq failures)
          (println "\nFailing tests:")
          (doseq [test-symbol failures]
            (println " -" test-symbol)))
        (print-skipped! skipped)
        (when (and green? (::digests bulk))
          (record-green-basis! selection-mode git-sha (::digests bulk)))
        (flush)
        (System/exit (if green? 0 1)))
      (finally
        (.shutdownNow backstop)))))
