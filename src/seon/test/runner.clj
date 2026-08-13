(ns seon.test.runner
  "Run the JVM gate and optionally commit per-test result facts."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [clojure.test.check.generators :as gen]
            [sci.impl.utils :as sci.utils]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test.selection :as selection])
  (:import (java.io BufferedReader PrintWriter StringWriter)
           (java.lang Process ProcessBuilder$Redirect ProcessHandle Runtime Thread)
           (java.nio.charset StandardCharsets)
           (java.lang.management ManagementFactory ThreadInfo)
           (java.time Instant)
           (java.util.concurrent CompletableFuture Executors
                                 LinkedBlockingQueue ThreadFactory TimeUnit
                                 TimeoutException))
  (:gen-class))

(defn var-reference?
  "True for a host or SCI Var reference."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (or (var? value) (sci.utils/var? value)))

(def var-generator
  "Finite representatives for the closed host/SCI Var representation sum."
  (gen/elements [#'var-reference? #'var-generator]))

(schema/register-core-predicate! 'seon.test.runner/var-reference?
                                 var-reference?)

(defn- var-symbol
  [test-var]
  (when test-var
    (let [{:keys [name ns]} (meta test-var)]
      (when (and name ns)
        (symbol (str ns) (str name))))))

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

(defn- failure-identity
  "Content identity for one normalized failing assertion report.

  SCI reports interpreted tests from generic JVM frames, so a source position
  would claim precision the reporter does not have. The normalized report is
  stable across reruns and distinguishes different failing claims."
  [options test-symbol event]
  (schema/sha-256
   [(.getBytes
     (pr-str [test-symbol
              (:type event)
              (:message event)
              (when (contains? event :expected)
                (printable options (:expected event)))
              (when (contains? event :actual)
                (printable options (:actual event)))
              (event-signature event)])
     StandardCharsets/UTF_8)]))

(defn- ensure-result
  [capture test-symbol]
  (if (contains? (::results capture) test-symbol)
    capture
    (-> capture
        (update ::order conj test-symbol)
        (assoc-in [::results test-symbol]
                  {:seon.test/sym (str test-symbol)
                   :seon.test/pass-count 0
                   :seon.test/fail-count 0
                   :seon.test/error-count 0
                   ::failure-messages []
                   ::failure-identities #{}}))))

(defn- capture-event!
  [options capture selected-namespaces event]
  (when-let [test-symbol (event-symbol event)]
    (when (contains? selected-namespaces (symbol (namespace test-symbol)))
      (swap! capture
             (fn [current]
               (let [current (ensure-result current test-symbol)
                     event-type (:type event)]
                 (case event-type
                   :pass
                   (update-in current [::results test-symbol
                                       :seon.test/pass-count] inc)

                   (:fail :error)
                   (let [failure-id
                         (failure-identity options test-symbol event)
                         seen? (contains?
                                (get-in current [::results test-symbol
                                                 ::failure-identities])
                                failure-id)]
                     (cond-> (update-in current
                                        [::results test-symbol
                                         (if (= :fail event-type)
                                           :seon.test/fail-count
                                           :seon.test/error-count)]
                                        inc)
                       (not seen?)
                       (update-in [::results test-symbol
                                   ::failure-identities] conj failure-id)
                       (not seen?)
                       (update-in [::results test-symbol ::failure-messages]
                                  conj (failure-message options event))))

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
    (locking progress
      (reset! progress
              {::description description
               ::at-nanos (System/nanoTime)
               ::at at})
      (println "bin/test:" (str at) description)
      (flush))))

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
  [progress silence-seconds suite-start child-processes virtual-thread-dumps]
  (let [process (ProcessHandle/current)
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
       (println "bin/test: virtual-thread-aware JVM dumps")
       (doseq [{::keys [dump-process dump-path dump-error]}
               virtual-thread-dumps]
         (println "bin/test:  "
                  (process-description dump-process)
                  (or dump-path (str "unavailable: " dump-error))))
       (println "bin/test: platform-thread MXBean supplement")
       (doseq [info (.dumpAllThreads thread-bean true true)]
         (print (thread-info-text info))))}))

(def ^:private jcmd-backstop-seconds
  "The foreign diagnostic process's loud last-resort bound."
  10)

(defn- persist-virtual-thread-dump!
  [^ProcessHandle target]
  (let [directory (io/file "tmp" "test-liveness")
        _ (.mkdirs directory)
        file (io/file directory
                      (str (.pid target) "-"
                           (System/currentTimeMillis) "-threads.json"))
        jcmd (io/file (System/getProperty "java.home") "bin" "jcmd")
        command [(str jcmd)
                 (str (.pid target))
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

(defn- persist-virtual-thread-dumps!
  "Persist concurrent virtual-thread-aware dumps for every supplied JVM."
  [processes]
  (let [executor (Executors/newVirtualThreadPerTaskExecutor)
        futures
        (mapv
         (fn [^ProcessHandle process]
           (.submit
            executor
            ^java.util.concurrent.Callable
            (fn []
              (try
                {::dump-process process
                 ::dump-path (persist-virtual-thread-dump! process)}
                (catch Throwable failure
                  {::dump-process process
                   ::dump-error (ex-message failure)})))))
         processes)]
    (try
      (mapv #(.get ^java.util.concurrent.Future %) futures)
      (finally
        (.shutdownNow executor)))))

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
  (let [process (ProcessHandle/current)
        child-processes (vec (.toList (.descendants process)))
        virtual-thread-dumps
        (persist-virtual-thread-dumps! (into [process] child-processes))
        {::keys [child-processes text]}
        (liveness-diagnostic progress silence-seconds suite-start
                             child-processes virtual-thread-dumps)
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
             ::invalid-silence-seconds seconds
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
           messages (::failure-messages result)
           identities (::failure-identities result)]
       (cond-> (dissoc result ::failure-messages ::failure-identities)
         (seq identities)
         (assoc :seon.test/failing-assertions (vec (sort identities)))
         (seq messages)
         (assoc :seon.test/failure-message (str/join "\n\n" messages)))))
   order))

(defn run-var!
  "Run one host or SCI test Var and return its captured assertion result.

  This is the same capture and reporter path used by `bin/test`; it performs
  no database write. `commit-results!` is the sole completion writer."
  {:malli/schema
   [:=> [:cat :seon.test/var]
    [:or :seon.test.runner/captured-result
     :seon.test/not-runnable-error]]}
  [test-var]
  (if-not (ifn? (:test (meta test-var)))
    {:seon.error/kind ::not-runnable
     :seon.test/not-runnable (str test-var)
     :seon.error/message "The supplied Var has no clojure.test function."}
    (let [test-symbol (var-symbol test-var)
          selected-namespaces #{(symbol (namespace test-symbol))}
          options (select-keys
                   (config/defaults)
                   [:seon.config.eval.result/blob-threshold
                    :seon.print/length
                    :seon.print/level])
          capture (atom {::order [] ::results {}})
          reported-signatures (atom #{})
          default-report test/report]
      (binding [test/report
                (fn [event]
                  (capture-event! options capture selected-namespaces event)
                  (report-event! options default-report
                                 reported-signatures event))]
        (test/test-vars [test-var]))
      (first (captured-results @capture)))))

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
           ::value marker :seon.test.runner/invalid-marker-reason true})))
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

(defn- atomic-namespace-task?
  [namespace-object]
  (or (seq (::test/once-fixtures (meta namespace-object)))
      (find-var (symbol (str (ns-name namespace-object)) "test-ns-hook"))))

(defn- test-tasks
  "Derived worker tasks preserving namespace-wide fixture boundaries."
  [all-vars selected-vars]
  (let [ordinal-by-symbol
        (into {} (map-indexed (fn [ordinal test-var]
                               [(var-symbol test-var) ordinal])) all-vars)
        selected-by-namespace (group-by (comp :ns meta) selected-vars)]
    (->> selected-by-namespace
         (mapcat
          (fn [[namespace-object namespace-vars]]
            (let [ordered (sort-by (comp ordinal-by-symbol var-symbol)
                                   namespace-vars)]
              (if (atomic-namespace-task? namespace-object)
                [ordered]
                (mapv vector ordered)))))
         (map (fn [task-vars]
                (let [symbols (mapv (comp str var-symbol) task-vars)]
                  {::task-id (str (random-uuid))
                   ::task-ordinal
                   (apply min (map #(ordinal-by-symbol (var-symbol %))
                                   task-vars))
                   ::task-namespace
                   (str (ns-name (:ns (meta (first task-vars)))))
                   ::task-symbols symbols
                   ::task-long? (boolean (some long-reason task-vars))})))
         (sort-by (juxt (comp not ::task-long?) ::task-ordinal))
         vec)))

(defn- indexed-test-symbols
  [manifest]
  (into #{}
        (keep :seon.test/sym)
        (mapcat :seon.fn.file/rows
                (:seon.fn.manifest/artifacts manifest))))

(defn- split-resolved-tasks
  [manifest tasks]
  (let [indexed (indexed-test-symbols manifest)]
    (group-by (fn [task]
                (if (every? indexed (::task-symbols task))
                  ::resolved
                  ::unresolved))
              tasks)))

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
               :seon.ns/name namespace-name :seon.test.runner/long-test-ns-hook namespace-name})))
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

(defn- task-summary
  [raw-summary]
  {::test-count (:test raw-summary)
   ::pass-count (:pass raw-summary)
   ::fail-count (:fail raw-summary)
   ::error-count (:error raw-summary)})

(defn- resolve-task-vars
  [task]
  (mapv (fn [test-symbol]
          (or (find-var (symbol test-symbol))
              (throw
               (ex-info "A worker could not resolve a selected test Var."
                        {:seon.error/kind ::unresolved-test-var
                         :seon.test/sym test-symbol :seon.test.runner/unresolved-test-var true}))))
        (::task-symbols task)))

(defn- run-task!
  "Run one worker task with all output captured as attributed data."
  [task]
  (let [test-vars (resolve-task-vars task)
        namespace-name (symbol (::task-namespace task))
        options (select-keys
                 (config/defaults)
                 [:seon.config.eval.result/blob-threshold
                  :seon.print/length
                  :seon.print/level])
        capture (atom {::order [] ::results {}})
        reported-signatures (atom #{})
        output (StringWriter.)
        started-at (Instant/now)
        started-nanos (System/nanoTime)
        default-report test/report]
    (try
      (let [raw-summary
            (binding [*out* output
                      *err* output
                      test/*test-out* output
                      test/report
                      (fn [event]
                        (capture-event! options capture #{namespace-name} event)
                        (report-event! options default-report
                                       reported-signatures event))]
              (let [summary (run-selected-tests [namespace-name] test-vars)]
                (test/do-report (assoc summary :type :summary))
                summary))]
        (assoc task
               ::task-started-at (str started-at)
               ::task-ended-at (str (Instant/now))
               ::task-elapsed-ms
               (quot (- (System/nanoTime) started-nanos) 1000000)
               ::task-summary (task-summary raw-summary)
               ::task-results (captured-results @capture)
               ::task-output (bounded-text options (str output))))
      (catch Throwable failure
        (let [test-symbol (first (::task-symbols task))
              message
              (bounded-text options
                            (str "Worker task failed outside a test Var: "
                                 (.getName (class failure)) ": "
                                 (or (ex-message failure) "")))
              failure-id
              (schema/sha-256
               [(.getBytes (pr-str [test-symbol :worker-task message])
                           StandardCharsets/UTF_8)])]
          (assoc task
                 ::task-started-at (str started-at)
                 ::task-ended-at (str (Instant/now))
                 ::task-elapsed-ms
                 (quot (- (System/nanoTime) started-nanos) 1000000)
                 ::task-summary {::test-count 0 ::pass-count 0
                                 ::fail-count 0 ::error-count 1}
                 ::task-results
                 [#:seon.test{:sym test-symbol
                              :pass-count 0
                              :fail-count 0
                              :error-count 1
                              :failing-assertions [failure-id]
                              :failure-message message}]
                 ::task-output
                 (bounded-text
                  options
                  (str output "\n" (throwable-face
                                     options failure
                                     (throwable-signature failure))))))))))

(def ^:private protocol-prefix
  "SEON_TEST_WORKER_EDN ")

(defn- packaged-test-projection
  "Acquire the packaged projection once for one test-runner JVM."
  [role]
  (let [projection
        (schema/declaration-projection
         ((requiring-resolve 'seon.schema.edn/packaged-forms)))]
    (binding [*out* *err*]
      (println "bin/test: PACKAGED TEST PROJECTION ACQUIRED"
               "pid=" (.pid (ProcessHandle/current))
               "role=" role))
    projection))

(defn- write-protocol!
  [^PrintWriter writer value]
  (.println writer (str protocol-prefix (pr-str value)))
  (.flush writer))

(defn- write-command!
  [^PrintWriter writer value]
  (.println writer (pr-str value))
  (.flush writer))

(defn- worker-command-loop!
  "Read and execute worker commands serially until explicitly stopped."
  [worker-id ^BufferedReader reader ^PrintWriter writer]
  (write-protocol! writer {::worker-event :ready ::worker-id worker-id})
  (loop []
    (when-let [line (.readLine reader)]
      (let [command (edn/read-string line)]
        (case (::worker-command command)
          :initialize
          (let [namespaces (mapv symbol (::worker-namespaces command))]
            (doseq [namespace-name namespaces]
              (require namespace-name))
            (write-protocol! writer
                             {::worker-event :initialized
                              ::worker-id worker-id
                              ::worker-namespace-count (count namespaces)})
            (recur))

          :run
          (do
            (write-protocol! writer
                             (assoc (run-task! (::worker-task command))
                                    ::worker-event :task-complete
                                    ::worker-id worker-id))
            (recur))

          :stop
          (write-protocol! writer
                           {::worker-event :stopped ::worker-id worker-id})

          (throw
           (ex-info "A test worker received an unknown command."
                    {:seon.error/kind ::unknown-worker-command
                     ::command command :seon.test.runner/unknown-worker-command true})))))))

(defn- worker-main!
  [worker-id]
  (let [projection (packaged-test-projection worker-id)
        protocol-out (PrintWriter. System/out true)
        reader (io/reader System/in)]
    ;; Only the protocol uses stdout. Test and dependency output goes to the
    ;; worker's attributed stderr log even when a library writes System/out.
    (System/setOut System/err)
    (binding [*out* *err*]
      (schema/call-with-projection
       projection
       #(worker-command-loop! worker-id reader protocol-out)))))

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

(defn record-tx
  "Transaction data replacing each test row's complete latest result.

  The attribute retractions make the update total: a later green run removes
  every stale failure identity and message in the same transaction."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.test.runner/completion]
                  :seon.test.runner/record-tx]}
  [database
   {results :seon.test.runner/results
    basis-t :seon.test/run-basis-t
    at :seon.test/run-at}]
  (let [namespace-names
        (distinct
         (map #(symbol (namespace (symbol (:seon.test/sym %)))) results))
        namespace-tempid #(str "test-result-namespace:" %)]
    (into
     (mapv (fn [namespace-name]
             {:db/id (namespace-tempid namespace-name)
              :seon.ns/name namespace-name})
           namespace-names)
     (mapcat
      (fn [{test-symbol :seon.test/sym :as result}]
        (let [namespace-name (symbol (namespace (symbol test-symbol)))
              test-ref [:seon.test/sym test-symbol]
              exists? (some? (db/pull database [:db/id] test-ref))
              result-row
              (cond-> (assoc result
                             :seon.test/run-basis-t basis-t
                             :seon.test/run-at at)
                (not exists?)
                (assoc :seon.test/ns (namespace-tempid namespace-name)))]
          (cond-> []
            exists?
            (conj [:db.fn/retractAttribute test-ref
                   :seon.test/failing-assertions]
                  [:db.fn/retractAttribute test-ref
                   :seon.test/failure-message])
            true (conj result-row))))
      results))))

(def ^:private result-selector
  [:seon.test/sym
   :seon.test/pass-count
   :seon.test/fail-count
   :seon.test/error-count
   :seon.test/run-basis-t
   :seon.test/run-at
   :seon.test/failing-assertions
   :seon.test/failure-message])

(defn commit-results!
  "Commit captured test results and return those exact committed facts."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.test.runner/completion]
    [:or :seon.test/results :seon.error/value]]}
  [connection {results :seon.test.runner/results :as completion}]
  (let [database (db/db connection)
        transaction-report
        (if (:seon.error/kind database)
          database
          (db/transact! connection (record-tx database completion)))]
    (if (:seon.error/kind transaction-report)
      transaction-report
      (mapv (fn [{test-symbol :seon.test/sym}]
              (dissoc
               (db/pull (:db-after transaction-report)
                        result-selector
                        [:seon.test/sym test-symbol])
               :db/id))
            results))))

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
  "Commit one runner completion into an explicitly named, non-default cluster."
  {:malli/schema [:=> [:cat :seon.test.runner/record-request]
                  [:or :seon.test/results :seon.error/value]]}
  [{run-result :seon.test.runner/run-result
    cluster-name :seon.boot/cluster-name
    root :seon.boot/root}]
  (when (= "default" cluster-name)
    (throw
     (ex-info
      "Test results may not be written into the default cluster."
      {:seon.error/kind ::default-cluster-refused
       ::default-cluster-refused cluster-name
       :seon.boot/cluster-name cluster-name})))
  (let [instance (start-cluster! cluster-name root)]
    (try
      (let [connection (:seon.boot/cluster-connection instance)
            completion
            {:seon.test.runner/results
             (:seon.test.runner/results run-result)
             :seon.test/run-basis-t (db/basis-t (db/db connection))
             :seon.test/run-at (:seon.test.run/at run-result)}]
        (commit-results! connection completion))
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
  [manifest changed-paths]
  (let [relative (requiring-resolve 'seon.test.selection/manifest-relative-artifacts)
        artifacts (relative "." manifest)
          tests (selection/reaching-tests artifacts changed-paths)]
    {::symbols (set tests)
     ::reason (str (count tests) " test(s) reach "
                   (count changed-paths) " changed path(s)" )}))

(defn- bulk-selection
  "Resolve the bulk tier: every eligible test, a reaching subset, or none.

  Widening is loud and named. A missing basis, a removed file, or a change to
  a declared gate input no call edge can reach all widen to every eligible
  test rather than guessing a narrower answer."
  [selection-mode manifest]
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
          (reaching-selection manifest explicit))
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
                (assoc (reaching-selection manifest changed)
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

(defn- worker-count
  []
  (max 1 (quot (.availableProcessors (Runtime/getRuntime)) 2)))

(defn- worker-parent
  []
  (io/file (or (System/getProperty "seon.test.worker-parent")
               (str (io/file (System/getProperty "seon.test.root")
                             "workers")))))

(defn- worker-checkout
  [worker-id]
  (io/file (worker-parent) worker-id))

(defn- read-worker-protocol!
  [worker]
  (loop []
    (if-let [line (.readLine ^BufferedReader (::worker-reader worker))]
      (if (str/starts-with? line protocol-prefix)
        (edn/read-string (subs line (count protocol-prefix)))
        (do
          (spit (::worker-error-log worker) (str line "\n") :append true)
          (recur)))
      nil)))

(defn- start-worker!
  [worker-id checkout-root operator-root]
  (.mkdirs (io/file operator-root "logs"))
  (let [error-log (io/file operator-root "logs" "worker-stderr.log")
        published-base (System/getProperty "seon.test.published-base")
        command (cond-> [(or (System/getenv "SEON_TEST_CLOJURE") "clojure")
                         "-Scp" (System/getProperty "java.class.path")
                         (str "-J-Dseon.operator.root="
                              (.getCanonicalPath operator-root))
                         (str "-J-Dseon.test.root="
                              (.getCanonicalPath operator-root))
                         (str "-J-Dseon.test.source-root=" (source-root))]
                  published-base
                  (conj (str "-J-Dseon.test.published-base=" published-base))
                  true
                  (into ["-M:test" "-m" "seon.test.runner"
                         "--worker" worker-id]))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory checkout-root)
                  (.redirectError
                   (ProcessBuilder$Redirect/appendTo error-log)))
        process
        (try
          (.start builder)
          (catch Exception failure
            (throw
             (ex-info "A test worker process could not launch."
                      {:seon.error/kind ::worker-launch-failure
                       ::worker-id worker-id
                       ::worker-error-log (.getCanonicalPath error-log) :seon.test.runner/worker-launch-failure true}
                      failure))))
        worker {::worker-id worker-id
                ::worker-process process
                ::worker-reader (io/reader (.getInputStream process))
                ::worker-writer (PrintWriter. (.getOutputStream process) true)
                ::worker-checkout (.getCanonicalPath checkout-root)
                ::worker-root (.getCanonicalPath operator-root)
                ::worker-error-log (.getCanonicalPath error-log)}
        ready (read-worker-protocol! worker)]
    (when-not ready
      (throw
       (ex-info "A test worker exited before publishing readiness."
                {:seon.error/kind ::worker-launch-failure
                 ::worker-id worker-id
                 ::worker-exit (when-not (.isAlive process)
                                 (.exitValue process))
                 ::worker-error-log (::worker-error-log worker) :seon.test.runner/worker-launch-failure true})))
    (when-not (= :ready (::worker-event ready))
      (throw
       (ex-info "A test worker published an invalid readiness value."
                {:seon.error/kind ::worker-launch-failure
                 ::worker-id worker-id
                 ::worker-ready ready
                 ::worker-error-log (::worker-error-log worker) :seon.test.runner/worker-launch-failure true})))
    worker))

(defn- worker-rpc!
  [worker command]
  (write-command! (::worker-writer worker) command)
  (if-let [result (read-worker-protocol! worker)]
    result
    (throw
     (ex-info "A test worker exited without returning its task result."
              {::worker-id (::worker-id worker)
               ::worker-exit
               (when-not (.isAlive ^Process (::worker-process worker))
                 (.exitValue ^Process (::worker-process worker)))
               ::worker-error-log (::worker-error-log worker)}))))

(defn- initialize-worker!
  [worker namespace-names]
  (let [result (worker-rpc!
                worker
                {::worker-command :initialize
                 ::worker-namespaces (mapv str namespace-names)})]
    (when-not (= :initialized (::worker-event result))
      (throw
       (ex-info "A test worker refused namespace initialization."
                {::worker-id (::worker-id worker)
                 ::worker-result result})))
    worker))

(def ^:private process-tree-exit-backstop-seconds
  "The loud last-resort bound after signaling a worker process tree."
  10)

(defn- process-tree-ownership
  "Capture one process tree and every exact exit publication before signaling."
  [^Process process]
  (let [root (.toHandle process)
        descendant-handles (vec (.toList (.descendants root)))
        handles (conj descendant-handles root)
        exits (mapv #(.onExit ^ProcessHandle %) handles)]
    {::process-root root
     ::process-descendants descendant-handles
     ::process-handles handles
     ::process-exits exits
     ::process-tree-exit
     (CompletableFuture/allOf
      (into-array CompletableFuture exits))}))

(defn- await-process-tree-exit
  [{::keys [process-tree-exit]}]
  (try
    (.get ^CompletableFuture process-tree-exit
          process-tree-exit-backstop-seconds TimeUnit/SECONDS)
    true
    (catch TimeoutException _
      false)))

(defn- signal-process-tree!
  [{::keys [process-root process-descendants]} forcibly?]
  (doseq [^ProcessHandle handle (concat (reverse process-descendants)
                                        [process-root])]
    (when (.isAlive handle)
      (if forcibly?
        (.destroyForcibly handle)
        (.destroy handle)))))

(defn- stop-owned-process-tree!
  [{::keys [process-handles process-root] :as ownership}]
  (signal-process-tree! ownership false)
  (when-not (await-process-tree-exit ownership)
    (let [stuck-processes
          (mapv (fn [^ProcessHandle handle]
                  {::process-id (.pid handle)
                   ::process-description (process-description handle)})
                (filter #(.isAlive ^ProcessHandle %) process-handles))]
      (binding [*out* *err*]
        (println "bin/test: WORKER PROCESS-TREE EXIT BACKSTOP fired; forcing"
                 (str/join "," (map ::process-id stuck-processes)))
        (flush))
      (signal-process-tree! ownership true)
      (let [forced-completion? (await-process-tree-exit ownership)]
        (throw
         (ex-info "A worker process tree exceeded its exit backstop."
                  {:seon.error/kind ::process-tree-exit-backstop
                   ::processes stuck-processes
                   ::forced-completion? forced-completion? :seon.test.runner/process-tree-exit-backstop true})))))
  ;; The exact root exit is already one of process-tree-exit's publications.
  (.get (.onExit ^ProcessHandle process-root)))

(defn- stop-worker!
  [worker]
  (let [process ^Process (::worker-process worker)]
    (when (.isAlive process)
      (let [ownership (process-tree-ownership process)]
        (try
          (write-command! (::worker-writer worker) {::worker-command :stop})
          (catch Throwable _))
        (stop-owned-process-tree! ownership)
        (.waitFor process)))))

(defn- drain-worker-tasks!
  "Execute tasks one at a time until `next-task` returns nil."
  [next-task execute-task!]
  (loop [results []]
    (if-let [task (next-task)]
      (recur (conj results (execute-task! task)))
      results)))

(defn- execute-worker-task!
  [progress worker task]
  (announce! progress
             (str "BEGIN worker=" (::worker-id worker)
                  " task=" (str/join "," (::task-symbols task))))
  (let [result (worker-rpc! worker
                            {::worker-command :run ::worker-task task})]
    (announce! progress
               (str "END worker=" (::worker-id worker)
                    " elapsed-ms=" (::task-elapsed-ms result)
                    " task=" (str/join "," (::task-symbols task))))
    result))

(defn- task-red?
  [task-result]
  (pos? (+ (get-in task-result [::task-summary ::fail-count] 0)
           (get-in task-result [::task-summary ::error-count] 0))))

(defn- run-task-pool!
  [progress workers serial-worker resolved-tasks unresolved-tasks]
  (let [queue (LinkedBlockingQueue.)
        finished (Object.)
        executor (Executors/newVirtualThreadPerTaskExecutor)]
    (doseq [task resolved-tasks]
      (.put queue task))
    (doseq [_ workers]
      (.put queue finished))
    (try
      (let [parallel-futures
            (mapv
             (fn [worker]
               (.submit
                executor
                ^java.util.concurrent.Callable
                (fn []
                  (drain-worker-tasks!
                   (fn []
                     (let [task (.take queue)]
                       (when-not (identical? finished task) task)))
                   #(execute-worker-task! progress worker %)))))
             workers)
            ;; The serial remainder uses the same sequential worker loop,
            ;; without a second scheduler or concurrent command on its root.
            serial-future
            (when (seq unresolved-tasks)
              (.submit
               executor
               ^java.util.concurrent.Callable
               (fn []
                 (mapv #(execute-worker-task! progress serial-worker %)
                       unresolved-tasks))))
            parallel-results (mapcat #(.get %) parallel-futures)
            serial-results (if serial-future (.get serial-future) [])]
        (vec (concat parallel-results serial-results)))
      (finally
        (.shutdownNow executor)))))

(defn- confirmation-root
  [task]
  (doto (io/file (worker-checkout "confirmation")
                 "operator-roots" (::task-id task))
    (.mkdirs)))

(defn- confirmation-launch
  [task]
  {::worker-id (str "confirmation-" (::task-ordinal task))
   ::task-id (::task-id task)
   ::task-ordinal (::task-ordinal task)
   ::task-symbols (::task-symbols task)})

(defn- publish-confirmation-launch!
  [root launch]
  (spit (io/file root "confirmation-launch.edn")
        (str (pr-str launch) "\n"))
  launch)

(defn- unconfirmed-confirmation
  [task-result failure]
  (let [launch (confirmation-launch task-result)
        underlying-kind (:seon.error/kind (ex-data failure))
        failure-kind
        (if (= ::worker-launch-failure underlying-kind)
          ::confirmation-worker-launch-failure
          ::confirmation-worker-failure)
        failure-fact
        (cond->
         (assoc launch
                :seon.error/kind failure-kind
                ::failure-class (.getName (class failure))
                ::failure-message (or (ex-message failure) ""))
          underlying-kind (assoc ::underlying-failure-kind underlying-kind)
          (ex-data failure) (assoc ::failure-data (ex-data failure)))]
    (println "bin/test: confirmation unconfirmed"
             (str/join "," (::task-symbols task-result))
             "worker=" (::worker-id launch)
             "kind=" failure-kind)
    (assoc task-result
           ::parallel-failure :unconfirmed
           ::confirmation-failure failure-fact)))

(defn- confirm-parallel-failure!
  [progress task-result]
  (let [task (select-keys task-result
                          [::task-id ::task-ordinal ::task-namespace
                           ::task-symbols ::task-long?])
        checkout (worker-checkout "confirmation")
        root (confirmation-root task)
        launch (publish-confirmation-launch!
                root (confirmation-launch task))
        _ (announce! progress
                     (str "CONFIRM launch worker=" (::worker-id launch)
                          " task=" (str/join "," (::task-symbols task))))
        worker (start-worker! (::worker-id launch) checkout root)]
    (try
      (initialize-worker! worker [(symbol (::task-namespace task))])
      (announce! progress
                 (str "CONFIRM isolated task="
                      (str/join "," (::task-symbols task))))
      (let [confirmation (execute-worker-task! progress worker task)
            classification (if (task-red? confirmation)
                             :reproducible
                             :parallel-only)]
        (println "bin/test: confirmation" (name classification)
                 (str/join "," (::task-symbols task)))
        (assoc task-result
               ::parallel-failure classification
               ::confirmation-result confirmation))
      (finally
        (stop-worker! worker)))))

(defn- confirm-task-results!
  "Confirm resolved pool failures concurrently while preserving result order."
  [parallelism progress resolved-task-ids task-results confirm!]
  (let [failures (filterv #(and (task-red? %)
                                (contains? resolved-task-ids (::task-id %)))
                          task-results)]
    (if (empty? failures)
      (vec task-results)
      (let [executor (Executors/newFixedThreadPool
                      (min parallelism (count failures)))
            futures
            (into {}
                  (map (fn [result]
                         [(::task-id result)
                          (.submit
                           executor
                           ^java.util.concurrent.Callable
                           (fn []
                             (try
                               (confirm! progress result)
                               (catch InterruptedException failure
                                 (.interrupt (Thread/currentThread))
                                 (throw failure))
                               (catch Throwable failure
                                 (unconfirmed-confirmation
                                  result failure)))))]))
                  failures)]
        (try
          (mapv (fn [result]
                  (if-let [confirmation (get futures (::task-id result))]
                    (.get ^java.util.concurrent.Future confirmation)
                    result))
                task-results)
          (finally
            (.shutdownNow executor)))))))

(defn- summarize-task-results
  [task-results]
  (reduce
   (fn [summary result]
     (merge-with + summary (::task-summary result)))
   {::test-count 0 ::pass-count 0 ::fail-count 0 ::error-count 0}
   task-results))

(defn- print-task-failures!
  [task-results]
  (doseq [result (sort-by ::task-ordinal (filter task-red? task-results))]
    (println)
    (println "bin/test: attributed output for"
             (str/join "," (::task-symbols result)))
    (print (::task-output result))))

(defn- print-final-tally!
  [summary task-results]
  (println)
  (println "Ran" (::test-count summary) "tests containing"
           (+ (::pass-count summary) (::fail-count summary))
           "assertions.")
  (println (::fail-count summary) "failures,"
           (::error-count summary) "errors.")
  (let [unconfirmed
        (sort-by ::task-ordinal
                 (filter #(= :unconfirmed (::parallel-failure %))
                         task-results))]
    (when (seq unconfirmed)
      (println)
      (println "Unconfirmed tasks:")
      (doseq [task-result unconfirmed]
        (let [failure (::confirmation-failure task-result)]
          (println " -" (str/join "," (::task-symbols task-result))
                   "worker=" (::worker-id failure)
                   "kind=" (:seon.error/kind failure)))))))

(defn- run-parallel-stage!
  [progress manifest workers serial-worker tasks]
  (let [{::keys [resolved unresolved]} (split-resolved-tasks manifest tasks)]
    (when (seq unresolved)
      (println "bin/test:" (count unresolved)
               "task(s) lack complete :seon.test rows; running serially:")
      (doseq [task unresolved]
        (println " -" (str/join "," (::task-symbols task)))))
    (let [initial (run-task-pool! progress workers serial-worker
                                  resolved unresolved)
          confirmed (confirm-task-results!
                     (worker-count)
                     progress
                     (into #{} (map ::task-id) resolved)
                     initial
                     confirm-parallel-failure!)]
      (print-task-failures! confirmed)
      {::task-results confirmed
       ::task-summary (summarize-task-results confirmed)})))

(defn- run-coordinator!
  "Run selected tests with progress and a liveness backstop.

  Every tiered invocation runs the declared `:seon.test/platform` moving-part
  regressions FIRST and stops there when they are red. The bulk tier follows:
  every eligible test under `all`/`full`, or only the tests reaching code
  changed since the last recorded GREEN basis under the bare `changed`
  default. Record results when the cluster argument names a non-default
  cluster, then exit zero exactly when no test failed or errored."
  {:malli/schema
   [:=> [:cat :seon.boot/cluster-name :seon.boot/root :string :string
         [:sequential :string]]
    :int]}
  [cluster-name root git-sha selection-mode namespace-names]
  (when-not (contains? selection-modes selection-mode)
    (throw
     (ex-info
      "The test selection mode is not one this runner knows."
      {:seon.error/kind ::invalid-selection-mode
       ::selection-mode selection-mode
       ::known selection-modes :seon.test.runner/invalid-selection-mode selection-mode})))
  (let [namespaces (mapv symbol namespace-names)
        progress (atom {::description "JVM runner initialized"
                        ::at-nanos (System/nanoTime)
                        ::at (Instant/now)})
        suite-start (Instant/now)
        configured-silence-seconds (silence-seconds)
        backstop (start-liveness-backstop!
                  progress configured-silence-seconds suite-start)
        pool-size (if (= "explicit" selection-mode) 1 (worker-count))
        worker-ids (conj (mapv #(str "pool-" %) (range 1 (inc pool-size)))
                         "serial")
        workers* (atom [])
        shutdown-hook
        (Thread. (fn [] (doseq [worker @workers*] (stop-worker! worker)))
                 "seon-test-worker-reaper")
        launch-executor (Executors/newVirtualThreadPerTaskExecutor)]
    (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
    (try
      (announce! progress
                 (str "START pid=" (.pid (ProcessHandle/current))
                      " git=" git-sha
                      " namespaces=" (count namespaces)
                      " workers=" pool-size
                      " silence-backstop=" configured-silence-seconds "s"))
      (let [worker-futures
            (mapv
             (fn [worker-id]
               (.submit
                launch-executor
                ^java.util.concurrent.Callable
                (fn []
                  (let [checkout (worker-checkout worker-id)
                        worker (start-worker! worker-id checkout checkout)]
                    (swap! workers* conj worker)
                    (initialize-worker! worker namespaces)))))
             worker-ids)]
        ;; Coordinator namespace loading and manifest construction overlap the
        ;; workers' JVM startup and namespace loading.
        (doseq [[index test-namespace] (map-indexed vector namespaces)]
          (announce! progress
                     (str "LOAD " (inc index) "/" (count namespaces)
                          " " test-namespace))
          (require test-namespace)
          (announce! progress
                     (str "LOADED " (inc index) "/" (count namespaces)
                          " " test-namespace)))
        (announce! progress "SELECT building the program graph")
        (let [build-manifest (requiring-resolve 'seon.fn/build-manifest)
              manifest (build-manifest
                        {:seon.fn/roots selection/graph-roots})
              workers (mapv #(.get %) worker-futures)
              pool-workers (filterv #(str/starts-with? (::worker-id %) "pool-")
                                    workers)
              serial-worker (first (filter #(= "serial" (::worker-id %))
                                           workers))
              explicit? (= "explicit" selection-mode)
              bulk (when-not explicit? (bulk-selection selection-mode manifest))
              all-vars (test-vars-in namespaces)
            {::keys [platform selected skipped unreached]}
            (if explicit?
              {::platform [] ::selected all-vars
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
              platform-tasks (test-tasks all-vars platform)
              selected-tasks (test-tasks all-vars selected)
              _ (announce! progress
                           (str "TIER platform " (count platform) " tests"))
              platform-outcome
              (run-parallel-stage! progress manifest pool-workers
                                   serial-worker platform-tasks)
              platform-red? (pos? (+ (get-in platform-outcome
                                              [::task-summary ::fail-count])
                                     (get-in platform-outcome
                                             [::task-summary ::error-count])))
              bulk-outcome
              (if platform-red?
                {::task-results []
                 ::task-summary {::test-count 0 ::pass-count 0
                                 ::fail-count 0 ::error-count 0}}
                (do
                  (announce! progress
                             (str "TIER bulk " (count selected) " tests"))
                  (run-parallel-stage! progress manifest pool-workers
                                       serial-worker selected-tasks)))
              task-results (->> (concat (::task-results platform-outcome)
                                        (::task-results bulk-outcome))
                                (sort-by ::task-ordinal)
                                vec)
              summary (merge-with + (::task-summary platform-outcome)
                                  (::task-summary bulk-outcome))
              run-result
              {:seon.test.run/id (str (random-uuid))
               :seon.test.run/at (java.util.Date.)
               :seon.test.run/git-sha git-sha
               :seon.test.runner/summary summary
               :seon.test.runner/results
               (into [] (mapcat ::task-results) task-results)
               ::stopped-after (when platform-red? :platform)}
            _ (when-not (= "-" cluster-name)
                (record! {:seon.test.runner/run-result run-result
                          :seon.boot/cluster-name cluster-name
                          :seon.boot/root root}))
            green? (zero? (+ (::fail-count summary) (::error-count summary)))
            failures (->> (:seon.test.runner/results run-result)
                          (filter #(pos? (+ (:seon.test/fail-count %)
                                           (:seon.test/error-count %))))
                          (map :seon.test/sym)
                          sort)]
          (print-final-tally! summary task-results)
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
          (if green? 0 1)))
      (finally
        (doseq [worker @workers*]
          (stop-worker! worker))
        (.shutdownNow launch-executor)
        (.shutdownNow backstop)
        (try
          (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
          (catch IllegalStateException _))))))

(defn- coordinator-main!
  [cluster-name root git-sha selection-mode namespace-names]
  (let [projection (packaged-test-projection "coordinator")]
    (schema/call-with-projection
     projection
     #(run-coordinator! cluster-name root git-sha selection-mode
                        namespace-names))))

(defn -main
  "Run the coordinator, prepare its immutable base, or run one worker."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& arguments]
  (case (first arguments)
    "--worker"
    (worker-main! (second arguments))

    "--prepare-base"
    (let [root (.getCanonicalPath (io/file (second arguments)))]
      (.mkdirs (io/file root))
      ((requiring-resolve 'seon.cluster/refresh-source!) root)
      (println "bin/test: shared published test base ready at" root))

    (let [[cluster-name root git-sha selection-mode & namespace-names]
          arguments]
      (System/exit
       (coordinator-main! cluster-name root git-sha selection-mode
                          namespace-names)))))
