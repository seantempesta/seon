(ns seon.test.runner
  "Run the JVM gate and optionally commit per-test result facts."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :as test]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [sci.impl.utils :as sci.utils]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
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

(defn- on-caller-loader
  "Wrap `task` so it runs under the submitting thread's classloader.

  A virtual thread's context classloader is not Clojure's dynamic
  loader, so a lazy require on an executor task compiles record classes
  onto a SIBLING loader chain — the main thread's concurrent loads then
  fail with ClassNotFoundException or 'namespace not found' for
  whichever source-compiled dependency loses the race (observed live:
  sci one run, clj-kondo's inlined tools.reader the next). Every
  executor submission in this runner pins the caller's loader first."
  ^java.util.concurrent.Callable [task]
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (fn []
      (.setContextClassLoader (Thread/currentThread) loader)
      (task))))

(defn- event-symbol
  [event]
  (var-symbol (or (:var event) (first test/*testing-vars*))))

(defn- printable
  [options value]
  (if (instance? Throwable value)
    (str (.getName (class value)) ": " (or (ex-message value) ""))
    (binding [*print-length* (:seon.print/length options)
              *print-level* (:seon.print/level options)]
      (pr-str value))))

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
       (str/join "\n")))

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
  [options event signature]
  (test/with-test-out
    (test/inc-report-counter :error)
    (print
     (with-out-str
       (println "\nERROR in" (test/testing-vars-str event))
       (when (seq test/*testing-contexts*)
         (println (test/testing-contexts-str)))
       (when-let [message (:message event)]
         (println message))
       (println "expected:" (printable options (:expected event)))
       (print "  actual: ")
       (println (throwable-face options (:actual event) signature))))))

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

(defn- assertionless-failure
  [capture event]
  (when (= :end-test-var (:type event))
    (let [test-symbol (event-symbol event)
          result (get-in @capture [::results test-symbol])
          assertion-count
          (+ (get result :seon.test/pass-count 0)
             (get result :seon.test/fail-count 0)
             (get result :seon.test/error-count 0))]
      (when (and result (zero? assertion-count))
        {:type :fail
         :var (:var event)
         :message (str "Test " test-symbol
                       " completed without assertion evidence.")
         :expected '(pos? assertion-count)
         :actual assertion-count}))))

(defn- capture-and-report-event!
  [options capture selected-namespaces default-report reported-signatures event]
  (capture-event! options capture selected-namespaces event)
  (when-let [failure (assertionless-failure capture event)]
    (capture-event! options capture selected-namespaces failure)
    (report-event! options default-report reported-signatures failure))
  (report-event! options default-report reported-signatures event))

(defn- announce!
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
            (on-caller-loader
             (fn []
               (try
                 {::dump-process process
                  ::dump-path (persist-virtual-thread-dump! process)}
                 (catch Throwable failure
                   {::dump-process process
                    ::dump-error (ex-message failure)}))))))
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

(defn- exchange-bound-seconds
  "The per-exchange bound: strictly inside the suite silence horizon.

  Both cannot sit at the same 300 s — when a task legitimately exceeds
  it, the suite watchdog raced the typed task bound and sometimes won,
  killing the run at exit 124 with the coordinator parked mid-exchange.
  The exchange fires first, converts to an attributed result, and that
  result IS reporter progress, so the watchdog never needs to."
  []
  (max 60 (- (silence-seconds) 30)))

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
                  (capture-and-report-event!
                   options capture selected-namespaces default-report
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
                    (when progress
                      (progress-event! progress event))
                    (capture-and-report-event!
                     options capture selected-namespaces default-report
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

(def ^:private ambient-drift-journal-limit
  "How many recent drifting tasks one worker keeps as attribution evidence."
  20)

(defn- resolve-loaded
  "The Var one qualified symbol names, or nothing when it is not loaded.

  `find-var` THROWS on an absent namespace, so a snapshot must not use it to
  ask whether something is loaded: a worker that has not loaded a namespace
  has nothing there to leak, which is an answer, not an error."
  [qualified-symbol]
  (when (find-ns (symbol (namespace qualified-symbol)))
    (find-var qualified-symbol)))

(defn- sci-base-namespace-sizes
  "Per-namespace var counts of the shared test SCI base ctx, when realized.

  `seon.test-support` acquires ONE cluster SCI ctx per worker JVM and every
  `fork-cluster-ctx` forks it, so a task that evaluates into the BASE rather
  than into its own fork changes what every later task inherits. The delay is
  never forced here: a worker that has not built the base has nothing to leak."
  []
  (when-let [base (some-> (resolve-loaded 'seon.test-support/database-base)
                          var-get)]
    (when (realized? base)
      (when-let [env (some-> @base :seon.sci.eval/ctx :env)]
        (into {}
              (map (fn [[namespace-name bindings]]
                     [namespace-name (count bindings)]))
              (:namespaces @env))))))

(defn- ambient-snapshot
  "Facts about this worker JVM's process-global state, DERIVED.

  AGENTS §5.7: a test owns nothing global. Nothing DECLARES which state that
  is, and a declaration would be the maintained list §2.2 bans, so the worker
  measures the shared state it can see and reports what a task changed.

  Every member is a fact about the running process, not a count somebody kept:
  the wrappers malli actually installed, the contracts it actually holds, the
  clusters actually running, and the shared SCI base each fork inherits."
  []
  (let [snapshot
        {::snapshot-instrumented
         (into #{}
               (map (fn [candidate]
                      (let [{namespace-object :ns var-name :name}
                            (meta candidate)]
                        (symbol (str (ns-name namespace-object))
                                (str var-name)))))
               ((requiring-resolve 'seon.instrument/instrumented)))
         ::snapshot-registered
         (into #{}
               (mapcat (fn [[namespace-symbol entries]]
                         (map (fn [[name-symbol _]]
                                (symbol (str namespace-symbol)
                                        (str name-symbol)))
                              entries)))
               (m/function-schemas))
         ::snapshot-live-clusters
         (or (some-> (resolve-loaded 'seon.cluster/running-instances)
                     var-get deref keys set)
             #{})}]
    (if-let [sizes (sci-base-namespace-sizes)]
      (assoc snapshot ::snapshot-sci-base sizes)
      snapshot)))

(defn- bounded-drift
  [before after]
  (let [added (set/difference after before)
        removed (set/difference before after)]
    (cond-> {}
      (seq added) (assoc ::drift-added
                         (vec (take 10 (sort (map str added))))
                         ::drift-added-count (count added))
      (seq removed) (assoc ::drift-removed
                           (vec (take 10 (sort (map str removed))))
                           ::drift-removed-count (count removed)))))

(def ^:private drift-directions
  "Which direction of change is a LEAK, per member — derived from what the
  member means, not from a preference.

  A wrapper that disappeared leaves every later task unarmed; a wrapper that
  appeared is a test that armed something extra and did not undo it: both
  matter. A malli function-schema REGISTRATION that disappeared is a real
  loss, but registrations that appeared are ordinary accretion — a test
  calling `apply!` collects every loaded namespace's contracts, including its
  own, and reporting those 47 rows as a defect is noise that buries the one
  line that matters. A cluster that appeared is one nobody stopped; a cluster
  that disappeared is a test stopping something it did not start."
  {::snapshot-instrumented #{::drift-added ::drift-removed}
   ::snapshot-registered #{::drift-removed}
   ::snapshot-live-clusters #{::drift-added ::drift-removed}})

(defn- ambient-drift
  "What one task changed in the worker's process-global state, or nothing.

  A member the worker could not see BEFORE and can see after is not drift: the
  shared SCI base is a delay, and the first database test in each worker is the
  one that realizes it. Only a change to a member present on both sides is a
  task changing something another task can already read."
  [before after]
  (let [set-drift
        (into {}
              (keep (fn [[member directions]]
                      (let [drift (select-keys
                                   (bounded-drift (get before member #{})
                                                  (get after member #{}))
                                   (into #{}
                                         (mapcat (fn [direction]
                                                   [direction
                                                    (keyword
                                                     (namespace direction)
                                                     (str (name direction)
                                                          "-count"))]))
                                         directions))]
                        (when (seq drift) [member drift]))))
              drift-directions)
        sci-drift
        (let [before-sizes (get before ::snapshot-sci-base)
              after-sizes (get after ::snapshot-sci-base)]
          (when (and before-sizes after-sizes (not= before-sizes after-sizes))
            {::drift-changed
             (vec (take 10
                        (sort (for [[namespace-name size] after-sizes
                                    :when (not= size
                                                (get before-sizes
                                                     namespace-name))]
                                (str namespace-name " "
                                     (get before-sizes namespace-name "absent")
                                     "->" size)))))}))]
    (cond-> set-drift
      sci-drift (assoc ::snapshot-sci-base sci-drift))))

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
                        (capture-and-report-event!
                         options capture #{namespace-name} default-report
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
               ::task-output (str output)))
      (catch Throwable failure
        (let [test-symbol (first (::task-symbols task))
              message
              (str "Worker task failed outside a test Var: "
                   (.getName (class failure)) ": "
                   (or (ex-message failure) ""))
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
                 (str output "\n" (throwable-face
                                    options failure
                                    (throwable-signature failure)))))))))

(def ^:private protocol-prefix
  "SEON_TEST_WORKER_EDN ")

(defn- load-declared-predicate-owners!
  "Load the namespace every packaged predicate symbol names.

  `seon.schema/register-core-predicate!` makes a predicate's owner loaded
  before anything can declare against it, and schema compilation therefore
  refuses to LOAD code while examining an authored form. A JVM that loaded
  only part of the tree breaks that invariant from the other side:
  `seon.schema/malli-form?` answers FALSE for four ordinary shipped
  contracts (`my.fs/write`, `my.shell/run`, and their two JVM owners)
  because `my.fs/content?`, `my.shell/stdin?` and `my.shell/output?` have
  no loaded Var — and under instrumentation that is a contract violation
  out of `seon.schema/canonical-definition` on any static analysis of the
  tree. The set is DERIVED from the population itself: every qualified
  symbol a Malli form carries is a predicate or generator owner. A symbol
  that cannot be loaded is left alone; the compile it feeds still refuses
  loudly and names it."
  [forms]
  (doseq [candidate (tree-seq coll? seq (seq forms))
          :when (qualified-symbol? candidate)]
    (try
      (requiring-resolve candidate)
      (catch Throwable _ nil))))

(defn- packaged-test-projection
  "Acquire the packaged projection once for one test-runner JVM."
  [role]
  (let [forms ((requiring-resolve 'seon.schema.edn/packaged-forms))
        _ (load-declared-predicate-owners! forms)
        projection (schema/declaration-projection forms)]
    (binding [*out* *err*]
      (println "bin/test: PACKAGED TEST PROJECTION ACQUIRED"
               "pid=" (.pid (ProcessHandle/current))
               "role=" role))
    projection))

(def ^:private program-source-root
  ;; The one root a live cluster's boot loads. `test` is deliberately not
  ;; here: a worker loads exactly the test namespaces its selection names.
  "src")

(defn- program-source-files
  [^java.io.File root]
  (->> (file-seq root)
       (filter (fn [^java.io.File file]
                 (and (.isFile file)
                      (or (.endsWith (.getName file) ".clj")
                          (.endsWith (.getName file) ".cljc")))))
       (sort-by (fn [^java.io.File file] (.getPath file)))))

(defn- declared-namespace
  "The namespace one first-party source file declares, or a typed refusal.

  A file whose first form is not an `ns` form used to drop out of the derived
  set with no report, so an unusual or malformed source file silently shrank
  the world the worker armed."
  [^java.io.File file]
  (let [form (try
               (with-open [source (java.io.PushbackReader. (io/reader file))]
                 (read {:read-cond :allow :eof ::eof} source))
               (catch Throwable failure
                 {::unreadable (or (ex-message failure)
                                   (.getName (class failure)))}))]
    (cond
      (and (map? form) (contains? form ::unreadable))
      {:seon.error/kind ::unreadable-program-source
       :seon.error/message
       (str "A first-party source file could not be read: " (.getPath file)
            " — " (::unreadable form) ".")
       ::source-file (.getPath file)}

      (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (second form)

      :else
      {:seon.error/kind ::program-source-declares-no-namespace
       :seon.error/message
       (str "A first-party source file declares no namespace: "
            (.getPath file)
            " — its first form must be an `ns` form, or the worker arms a"
            " smaller world than the cluster it claims to reproduce.")
       ::source-file (.getPath file)})))

(defn- declared-program-namespaces
  "Every namespace the first-party program declares, read from its own form.

  DERIVED, NOT LISTED. The reader answers what a file's namespace is; a
  path-to-symbol convention would be a naming rule, and a roster would be
  stale within a day. This is what makes the worker's armed set the set a
  cluster arms: a contract in a namespace no test happens to require —
  `seon.artifact/-main`, `seon.artifact/install-initialization-pages!`,
  `seon.test/run` were the three — is enforced on every live cluster and
  was checked by nothing here.

  TOTAL, AND LOUD ABOUT ABSENCE. This function's answer is the INPUT to the
  worker's arming check, and it used to answer `[]` in silence whenever the
  relative root did not resolve — a worker would then require nothing, its own
  test vars would keep the instrumented count positive, and the gate would be
  green about a question it never asked (the same disease one level up,
  `docs/seon/issues/declared-program-namespaces-returns-empty-in-silence.md`).
  An unresolvable root, an unreadable file, a file declaring no namespace, and
  an empty derivation are each a typed refusal naming what was missing."
  []
  (let [root (io/file program-source-root)]
    (when-not (.isDirectory root)
      (throw
       (ex-info
        (str "The first-party program source root does not resolve: "
             (.getPath root) " from working directory "
             (.getCanonicalPath (io/file ".")) ".")
        {:seon.error/kind ::program-source-root-unresolved
         ::program-source-root (.getPath root)
         ::working-directory (.getCanonicalPath (io/file "."))
         ::instrumentation-unavailable true})))
    (let [declared (mapv declared-namespace (program-source-files root))]
      (when-let [refusal (first (filter map? declared))]
        (throw
         (ex-info (:seon.error/message refusal)
                  (assoc refusal ::instrumentation-unavailable true))))
      (when (empty? declared)
        (throw
         (ex-info
          (str "The first-party program source root declares no namespaces: "
               (.getCanonicalPath root) ".")
          {:seon.error/kind ::program-declares-no-namespaces
           ::program-source-root (.getCanonicalPath root)
           ::instrumentation-unavailable true})))
      declared)))

(defn- arming-decision
  "The shipped decisions, admission caps and program namespaces one arm needs.

  DECIDED ONCE, BEFORE THE FIRST ARM, AND CARRIED. Deriving it again for a
  mid-run re-arm asks `seon.config/result-caps` its own question under the
  contracts the first arm installed, and the compiled shipped effective config
  does not satisfy `:seon.config/effective` — eighteen optional dials it
  legitimately leaves absent. On the first arm nothing is instrumented so the
  call answers; on a re-arm the worker died, and the namespaces it held were
  reported red as `confirmation parallel-only`, which mis-attributed a day of
  someone else's diagnosis (`docs/seon/issues/the-test-runners-re-arm-kills-
  the-worker-under-its-own-contract.md`).

  That the effective config fails its own key's schema is a separate defect,
  filed there; the arming path must not be the thing that discovers it."
  []
  (let [decisions (config/defaults)
        caps (config/result-caps decisions)]
    (when (:seon.error/kind caps)
      (throw
       (ex-info (:seon.error/message caps)
                (assoc caps ::instrumentation-unavailable true))))
    {::decisions decisions
     ::caps caps
     ::program (declared-program-namespaces)}))

(defn- arm-contracts!
  "Instrument this worker JVM's loaded contracts exactly as boot does.

  The gate must ask the question a live cluster asks. A contract
  violation that makes an agent's prompt unavailable on a running
  cluster was invisible here for as long as the worker never armed
  instrumentation — a check that reports health because its subject was
  never asked. The dial and the admission caps are the SHIPPED decisions
  the operator compiles (`seon.config/defaults`), so the gate
  cannot drift from boot by carrying constants of its own, and an absent
  cap refuses NAMING the key rather than instrumenting under a partial
  world."
  [decision projection worker-id namespaces]
  (let [{::keys [decisions caps program]} decision]
    ;; THE PROGRAM IS LOADED BEFORE IT IS ARMED. Instrumentation selects
    ;; loaded vars carrying `:malli/schema`, so a namespace nothing required
    ;; contributes nothing and the gate silently arms a smaller world than
    ;; the cluster it claims to reproduce.
    (doseq [namespace-name program]
      (require namespace-name))
    (let [applied ((requiring-resolve 'seon.instrument/apply!)
                   {:seon.config/on-core-error
                    (:seon.config/on-core-error decisions)
                    :seon.sci.admit/caps caps
                    :seon.schema/projection projection})]
      (when (:seon.error/kind applied)
        (throw
         (ex-info (:seon.error/message applied)
                  (assoc applied ::instrumentation-unavailable true))))
      ;; ABSENCE IS NEVER HEALTH, AND A COUNT IS NOT THE QUESTION. A floor of
      ;; zero was satisfied by the worker's OWN test vars, so a worker that
      ;; armed none of the program still passed. The question is SET COVERAGE
      ;; against the set a booted cluster arms, and both sides are derived the
      ;; same way: `seon.instrument/armable` asks malli's own two questions
      ;; (a declared function schema, a non-primitive value) over the program
      ;; namespaces this worker just loaded, and `seon.instrument/instrumented`
      ;; reads the wrappers actually installed. `seon.artifact/-main`,
      ;; `seon.artifact/install-initialization-pages!` and `seon.test/run`
      ;; were live on every cluster and armed by nothing here.
      (let [armable ((requiring-resolve 'seon.instrument/armable) program)
            installed ((requiring-resolve 'seon.instrument/instrumented))
            unarmed (into (sorted-set)
                          (map #(str (symbol %)))
                          (set/difference armable installed))]
        (when (seq unarmed)
          (throw
           (ex-info
            (str "bin/test armed a smaller world than a cluster in worker "
                 worker-id ": " (count unarmed) " of " (count armable)
                 " declared program contracts across " (count program)
                 " program namespaces carry no wrapper — "
                 (str/join ", " (take 10 unarmed))
                 (when (> (count unarmed) 10) ", ...") ".")
            (assoc applied
                   ::instrumentation-unavailable true
                   ::program-namespace-count (count program)
                   ::armable-count (count armable)
                   ::unarmed-program-contracts (vec unarmed)))))
        (binding [*out* *err*]
          (println "bin/test: CONTRACTS ARMED"
                   "worker=" worker-id
                   "mode=" (:seon.config/on-core-error decisions)
                   "namespaces=" (count namespaces)
                   "program-namespaces=" (count program)
                   "registered=" (:seon.instrument/registered applied)
                   "instrumented=" (:seon.instrument/instrumented applied)
                   "program-armable=" (count armable))))
      applied)))

(defn- reassert-contracts!
  "Re-arm this worker JVM when a task left its contracts stripped.

  A pooled worker runs many tests per JVM, and `seon.instrument/remove!` is
  total by design: one suite proving that instrumentation can be removed —
  or one suite arming a narrow filter and stripping everything in its
  `finally` — left every LATER task in that worker running unarmed. Those
  tasks then asserted the earlier suite's timing rather than their own
  subject: `seon.db-test/malformed-reads-return-flat-errors` and
  `the-gate-runs-under-the-contracts-a-cluster-runs-under` were both red in
  the pool and green in isolation, and the confirmation phase's
  `parallel-only` verdict was the only thing saying so.

  The armed state is therefore DERIVED at the seam that admits the work, not
  remembered from initialization: the worker counts the wrappers actually
  installed and re-arms when that disagrees with what it armed. Nothing has
  to be declared, so nothing can drift — a new suite that strips contracts
  costs one re-arm rather than a silently unarmed remainder."
  [arming worker-id]
  (when-let [{::keys [projection namespaces instrumented decision]} arming]
    (let [live (count ((requiring-resolve 'seon.instrument/instrumented)))]
      ;; LESS than the worker armed means a task STRIPPED wrappers, which is
      ;; the hazard. More means a test armed something extra of its own and is
      ;; expected to undo it; re-arming over that would fight its subject.
      (when (< live instrumented)
        (binding [*out* *err*]
          (println "bin/test: RE-ARMING CONTRACTS"
                   "worker=" worker-id
                   "installed=" live
                   "armed-at-initialization=" instrumented))
        ;; A RE-ARM THAT DIES IS A LOUD VERDICT ABOUT THE RE-ARM, never a
        ;; per-namespace red: a worker that exits here takes every namespace
        ;; it held down with it, and those were reported `parallel-only`
        ;; against their own owners for a day.
        (try
          (arm-contracts! decision projection worker-id namespaces)
          (catch Throwable failure
            (binding [*out* *err*]
              (println "bin/test: RE-ARM FAILED worker=" worker-id
                       "—" (ex-message failure))
              (flush))
            (throw
             (ex-info
              (str "bin/test could not re-arm worker " worker-id
                   " after a task stripped its contracts: "
                   (ex-message failure))
              {:seon.error/kind ::re-arm-failed
               ::worker-id worker-id
               ::installed live
               ::armed-at-initialization instrumented}
              failure))))))))

(defn- write-protocol!
  [^PrintWriter writer value]
  (.println writer (str protocol-prefix (pr-str value)))
  (.flush writer))

(defn- write-command!
  [^PrintWriter writer value]
  (.println writer (pr-str value))
  (.flush writer)
  (not (.checkError writer)))

(defn- serve-worker-commands!
  "Read and execute worker commands serially until explicitly stopped."
  [worker-id ^BufferedReader reader ^PrintWriter writer arming]
  (loop []
    (when-let [line (.readLine reader)]
      (let [command (edn/read-string line)]
        (case (::worker-command command)
          :initialize
          (let [namespaces (mapv symbol (::worker-namespaces command))]
            ;; LOADING IS AN OPERATION AND IT ASKS SCHEMA QUESTIONS, so it
            ;; runs under a projection like every other operation.
            (schema/call-with-projection
             (packaged-test-projection worker-id)
             (fn []
               (doseq [namespace-name namespaces]
                 (require namespace-name))))
            ;; THE PROJECTION THE LOOP HOLDS IS ACQUIRED AFTER THE REQUIRES,
            ;; exactly as a cluster acquires its own after loading: a
            ;; predicate schema's callable is admitted only once the
            ;; namespace declaring it is loaded, so a projection built
            ;; earlier refuses four shipped contracts (`my.fs/content?`,
            ;; `my.shell/stdin?`, `my.shell/output?`) that a live cluster
            ;; resolves.
            (let [projection (packaged-test-projection worker-id)
                  decision (arming-decision)
                  applied (arm-contracts!
                           decision projection worker-id namespaces)]
              (write-protocol! writer
                               {::worker-event :initialized
                                ::worker-id worker-id
                                ::exchange-id (::exchange-id command)
                                ::worker-namespace-count (count namespaces)
                                ::worker-instrumented
                                (:seon.instrument/instrumented applied)})
              (schema/call-with-projection
               projection
               #(serve-worker-commands!
                 worker-id reader writer
                 {::projection projection
                  ::namespaces namespaces
                  ::decision decision
                  ::instrumented (:seon.instrument/instrumented applied)}))))

          :run
          (do
            (reassert-contracts! arming worker-id)
            ;; THE WORKER MEASURES WHAT A TASK LEAVES BEHIND. A pooled worker
            ;; runs many tests per JVM, and the reds that only appear under
            ;; the whole gate are tests asserting an EARLIER task's leftovers.
            ;; Nothing declares which state is shared, so the seam that admits
            ;; the work derives it either side of the task and reports the
            ;; difference as that task's own fact.
            (let [before (ambient-snapshot)
                  result (run-task! (::worker-task command))
                  drift (ambient-drift before (ambient-snapshot))]
              (write-protocol! writer
                               (cond-> (assoc result
                                              ::worker-event :task-complete
                                              ::worker-id worker-id
                                              ::exchange-id
                                              (::exchange-id command))
                                 (seq drift)
                                 (assoc ::task-ambient-drift drift))))
            (recur))

          :stop
          (write-protocol! writer
                           {::worker-event :stopped
                            ::worker-id worker-id
                            ::exchange-id (::exchange-id command)})

          (throw
           (ex-info "A test worker received an unknown command."
                    {:seon.error/kind ::unknown-worker-command
                     ::command command :seon.test.runner/unknown-worker-command true})))))))

(defn- worker-command-loop!
  "Announce readiness, then serve commands until stopped."
  [worker-id ^BufferedReader reader ^PrintWriter writer]
  (write-protocol! writer {::worker-event :ready
                           ::worker-id worker-id
                           ::exchange-id (str worker-id "/readiness")})
  (serve-worker-commands! worker-id reader writer nil))

(defn- worker-main!
  [worker-id]
  (let [protocol-out (PrintWriter. System/out true)
        reader (io/reader System/in)]
    ;; Only the protocol uses stdout. Test and dependency output goes to the
    ;; worker's attributed stderr log even when a library writes System/out.
    (System/setOut System/err)
    (binding [*out* *err*]
      (worker-command-loop! worker-id reader protocol-out))))

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
  every stale failure identity and message in the same transaction. This
  runs as a `:db.fn/call` transaction function, so the presence decision
  reads the WRITER's own database value — a caller pre-read could strand
  a retract's lookup ref against a concurrently retracted row and reject
  the whole result transaction."
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
          (db/transact! connection
                        [[:db.fn/call #'record-tx completion]]))]
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

(def ^:private persistent-results-branch :test-results)
(def ^:private persistent-results-monitor (Object.))

(defn- commit-persistent-results!
  "Commit one bare-gate completion through the store-holding process."
  [held-store run-result]
  (locking persistent-results-monitor
    (registry/branch! {:seon.store/store held-store
                       :seon.cluster.registry/from source/current-branch
                       :seon.store/branch persistent-results-branch})
    (let [connection (store/open-branch! held-store persistent-results-branch)]
      (try
        (let [database (db/db connection)
              projection (schema/projection-from-database database)
              completion
              {:seon.test.runner/results
               (:seon.test.runner/results run-result)
               :seon.test/run-basis-t (db/basis-t database)
               :seon.test/run-at (:seon.test.run/at run-result)}]
          (schema/call-with-projection
           projection
           #(commit-results! connection completion)))
        (finally
          (store/release-branch! connection))))))

(defn- persistent-results-form
  [run-result]
  (pr-str
   `(do
      (require 'seon.cluster.registry
               'seon.cluster.source
               'seon.cluster.store
               'seon.db
               'seon.schema
               'seon.test.runner)
      (let [store#
            (some :seon.store/store
                  (vals @(var-get
                          (ns-resolve 'seon.cluster
                                      (symbol "running-instances")))))]
        (if store#
          (do
            (seon.cluster.registry/branch!
             {:seon.store/store store#
              :seon.cluster.registry/from seon.cluster.source/current-branch
              :seon.store/branch :test-results})
            (let [connection#
                  (seon.cluster.store/open-branch! store# :test-results)]
              (try
                (let [database# (seon.db/db connection#)
                      projection#
                      (seon.schema/projection-from-database database#)
                      completion#
                      {:seon.test.runner/results
                       (:seon.test.runner/results ~run-result)
                       :seon.test/run-basis-t (seon.db/basis-t database#)
                       :seon.test/run-at (:seon.test.run/at ~run-result)}]
                  (seon.schema/call-with-projection
                   projection#
                   #(seon.test.runner/commit-results!
                     connection# completion#)))
                (finally
                  (seon.cluster.store/release-branch! connection#)))))
          {:seon.error/kind
           :seon.test.runner/live-store-unavailable
           :seon.error/message
           "The live process has no held operator store."})))))

(defn- record-persistent-results!
  "Commit one bare-gate completion through the authoritative store holder."
  [operator-root run-result]
  (let [{live? :seon.fresh-operator/live-process?
         value :seon.fresh-operator/value}
        ((requiring-resolve 'seon.fresh-operator/live-root-value!)
         operator-root (persistent-results-form run-result))]
    (if live?
      value
      (let [held-store
            (store/open-store!
             {:seon.store/dir (str (io/file operator-root "data" "store"))})]
        (try
          (commit-persistent-results! held-store run-result)
          (finally
            (store/release-store! held-store)))))))

(defn- configured-persistent-results-root
  [launcher-root explicit-root]
  (or (not-empty explicit-root) launcher-root))

(defn- recording-failure
  [record-fn]
  (try
    (let [result (record-fn)]
      (when (:seon.error/kind result)
        result))
    (catch Throwable failure
      {:seon.error/kind
       (or (:seon.error/kind (ex-data failure))
           ::persistent-results-recording-failed)
       :seon.error/message
       (or (ex-message failure) (.getName (class failure)))})))

(defn- print-skipped!
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

(defn- append-worker-line!
  [worker line]
  (spit (::worker-error-log worker) (str line "\n") :append true))

(defn- matching-worker-reply?
  [expected reply]
  (and (= (::worker-id expected) (::worker-id reply))
       (= (::worker-event expected) (::worker-event reply))
       (= (::exchange-id expected) (::exchange-id reply))))

(defn- read-exchange-reply!
  [worker expected exit-future]
  (loop []
    (if-let [line (.readLine ^BufferedReader (::worker-reader worker))]
      (if (str/starts-with? line protocol-prefix)
        (let [reply
              (try
                (edn/read-string (subs line (count protocol-prefix)))
                (catch Throwable _ ::unparseable-worker-reply))]
          (if (and (map? reply) (matching-worker-reply? expected reply))
            {::exchange-terminal :reply ::exchange-reply reply}
            (do
              (append-worker-line! worker
                                   (str "UNMATCHED_WORKER_REPLY " line))
              (recur))))
        (do
          (append-worker-line! worker line)
          (recur)))
      ;; Pipe EOF is not a fourth terminal event. Await the already-registered
      ;; exact process exit; if the process stays live, the declared bound wins.
      (.join ^CompletableFuture exit-future))))

(defn- event-backstop-seconds
  []
  (long @(requiring-resolve 'seon.test-support/event-backstop-seconds)))

(defn- append-dispatch!
  [worker dispatch]
  (let [journal (io/file (::worker-root worker) "logs" "worker-dispatch.edn")]
    (spit journal (str (pr-str dispatch) "\n") :append true)
    (.getCanonicalPath journal)))

(defn- retire-worker!
  [worker destroy?]
  (reset! (::worker-retired? worker) true)
  (let [process ^Process (::worker-process worker)]
    (when (and destroy? (.isAlive process))
      (.destroyForcibly process)
      ;; retirement returns only when the worker can no longer act; the
      ;; wait is bounded — a process the OS will not reap is reported,
      ;; never awaited forever
      (when-not (.waitFor process 10 TimeUnit/SECONDS)
        (binding [*out* *err*]
          (println "bin/test: retired worker survived destroyForcibly"
                   "pid=" (.pid process)))))))

(defn- exchange-failure?
  [value]
  (true? (::worker-exchange-failure value)))

(defn- worker-exchange!
  [{worker ::worker
    command ::exchange-command
    expected-event ::expected-worker-event
    exchange-id ::exchange-id
    task-symbols ::task-symbols
    completion-bound-seconds ::completion-bound-seconds}]
  (let [process ^Process (::worker-process worker)
        worker-id (::worker-id worker)
        dispatch-at (Instant/now)
        expected {::worker-id worker-id
                  ::worker-event expected-event
                  ::exchange-id exchange-id}
        dispatch (cond-> {::worker-id worker-id
                          ::worker-pid (.pid process)
                          ::exchange-id exchange-id
                          ::expected-worker-event expected-event
                          ::completion-bound-seconds completion-bound-seconds
                          ::dispatch-at (str dispatch-at)}
                   (seq task-symbols) (assoc ::task-symbols task-symbols))]
    (if @(::worker-retired? worker)
      (assoc dispatch
             ::worker-exchange-failure true
             :seon.error/kind ::worker-retired
             ::missing-worker-event expected-event)
      (let [executor (Executors/newVirtualThreadPerTaskExecutor)
            exact-exit (.onExit process)
            exit-future
            (.thenApply
             exact-exit
             (reify java.util.function.Function
               (apply [_ exited]
                 {::exchange-terminal :exit
                  ::worker-exit (.exitValue ^Process exited)})))
            reply-future
            (CompletableFuture/supplyAsync
             (reify java.util.function.Supplier
               (get [_]
                 (read-exchange-reply! worker expected exit-future)))
             executor)
            bound-future
            (CompletableFuture/supplyAsync
             (reify java.util.function.Supplier
               (get [_] {::exchange-terminal :bound}))
             (CompletableFuture/delayedExecutor
              completion-bound-seconds TimeUnit/SECONDS))]
        (try
          (let [journal (append-dispatch! worker dispatch)
                command (when command (assoc command ::exchange-id exchange-id))]
            (if (and command
                     (not (write-command! (::worker-writer worker) command)))
              (do
                (retire-worker! worker true)
                (assoc dispatch
                       ::dispatch-journal journal
                       ::worker-exchange-failure true
                       :seon.error/kind ::worker-write-failure
                       ::missing-worker-event expected-event))
              (let [{terminal ::exchange-terminal :as outcome}
                    (.get
                     (CompletableFuture/anyOf
                      (into-array CompletableFuture
                                  [reply-future exit-future bound-future])))]
                (case terminal
                  :reply (::exchange-reply outcome)
                  :exit
                  (do
                    (retire-worker! worker false)
                    (assoc dispatch
                           ::dispatch-journal journal
                           ::worker-exchange-failure true
                           :seon.error/kind ::worker-exited
                           ::worker-exit (::worker-exit outcome)
                           ::worker-error-log (::worker-error-log worker)
                           ::missing-worker-event expected-event))
                  :bound
                  (do
                    (retire-worker! worker true)
                    (assoc dispatch
                           ::dispatch-journal journal
                           ::worker-exchange-failure true
                           :seon.error/kind ::worker-exchange-bound
                           ::worker-error-log (::worker-error-log worker)
                           ::missing-worker-event expected-event))))))
          (catch InterruptedException failure
            (.interrupt (Thread/currentThread))
            (retire-worker! worker true)
            (assoc dispatch
                   ::worker-exchange-failure true
                   :seon.error/kind ::worker-exchange-interrupted
                   ::missing-worker-event expected-event
                   ::failure-message (ex-message failure)))
          (catch Throwable failure
            (retire-worker! worker true)
            (assoc dispatch
                   ::worker-exchange-failure true
                   :seon.error/kind ::worker-exchange-failed
                   ::missing-worker-event expected-event
                   ::failure-class (.getName (class failure))
                   ::failure-message (or (ex-message failure) "")))
          (finally
            (.shutdownNow executor)))))))

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
                ::worker-journal (atom [])
                ::worker-process process
                ::worker-reader (io/reader (.getInputStream process))
                ::worker-writer (PrintWriter. (.getOutputStream process) true)
                ::worker-retired? (atom false)
                ::worker-checkout (.getCanonicalPath checkout-root)
                ::worker-root (.getCanonicalPath operator-root)
                ::worker-error-log (.getCanonicalPath error-log)}
        readiness-id (str worker-id "/readiness")
        ;; worker exchanges are bounded by the suite's declared silence
        ;; horizon: readiness and initialization cover a JVM boot plus
        ;; namespace loading, and a task legitimately runs minutes — the
        ;; fixture event backstop (seconds) mis-bounded all three and
        ;; killed real long tests as exchange failures
        ready (worker-exchange!
               {::worker worker
                ::exchange-id readiness-id
                ::expected-worker-event :ready
                ::completion-bound-seconds (exchange-bound-seconds)})]
    (when (exchange-failure? ready)
      (throw
       (ex-info "A test worker did not publish readiness."
                (assoc ready
                       :seon.error/kind ::worker-launch-failure
                       ::underlying-failure-kind (:seon.error/kind ready)
                       :seon.test.runner/worker-launch-failure true))))
    worker))

(defn- initialize-worker!
  [worker namespace-names]
  (let [exchange-id (str (::worker-id worker) "/initialize")
        result (worker-exchange!
                {::worker worker
                 ::exchange-command
                 {::worker-command :initialize
                  ::worker-namespaces (mapv str namespace-names)}
                 ::exchange-id exchange-id
                 ::expected-worker-event :initialized
                 ::completion-bound-seconds (exchange-bound-seconds)})]
    (when (exchange-failure? result)
      (throw
       (ex-info "A test worker refused namespace initialization."
                result)))
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
        (worker-exchange!
         {::worker worker
          ::exchange-command {::worker-command :stop}
          ::exchange-id (str (::worker-id worker) "/stop")
          ::expected-worker-event :stopped
          ::completion-bound-seconds (event-backstop-seconds)})
        (stop-owned-process-tree! ownership)
        (.waitFor process)))))

(defn- drain-worker-tasks!
  "Execute tasks one at a time until `next-task` returns nil."
  [next-task execute-task!]
  (loop [results []]
    (if-let [task (next-task)]
      (recur (conj results (execute-task! task)))
      results)))

(defn- task-red?
  [task-result]
  (pos? (+ (get-in task-result [::task-summary ::fail-count] 0)
           (get-in task-result [::task-summary ::error-count] 0))))

(defn- execute-worker-task!
  [progress worker task]
  (announce! progress
             (str "BEGIN worker=" (::worker-id worker)
                  " task=" (str/join "," (::task-symbols task))))
  (let [result
        (worker-exchange!
         {::worker worker
          ::exchange-command {::worker-command :run ::worker-task task}
          ::exchange-id (::task-id task)
          ::expected-worker-event :task-complete
          ::task-symbols (::task-symbols task)
          ::completion-bound-seconds (exchange-bound-seconds)})
        journal (::worker-journal worker)
        _ (when-let [drift (::task-ambient-drift result)]
            (when journal
              (swap! journal
                     (fn [entries]
                       (vec
                        (take-last
                         ambient-drift-journal-limit
                         (conj entries
                               {::task-symbols (::task-symbols task)
                                ::task-ambient-drift drift}))))))
            (println "bin/test: WORKER-GLOBAL STATE CHANGED by"
                     (str/join "," (::task-symbols task))
                     "worker=" (::worker-id worker)
                     (pr-str (into (sorted-map) drift))))
        result
        (if (exchange-failure? result)
          (let [test-symbols (mapv str (::task-symbols task))
                message (str "Worker exchange failed: " (pr-str result))]
            (assoc task
                   ::task-summary {::test-count (count test-symbols)
                                   ::pass-count 0
                                   ::fail-count 0
                                   ::error-count (count test-symbols)}
                   ::task-results
                   (mapv
                    (fn [test-symbol]
                      (let [failure-id
                            (schema/sha-256
                             [(.getBytes
                               (pr-str [test-symbol :worker-exchange message])
                               StandardCharsets/UTF_8)])]
                        #:seon.test{:sym test-symbol
                                    :pass-count 0
                                    :fail-count 0
                                    :error-count 1
                                    :failing-assertions [failure-id]
                                    :failure-message message}))
                    test-symbols)
                   ::task-output (str message "\n")
                   ::worker-exchange-result result))
          result)]
    (announce! progress
               (str "END worker=" (::worker-id worker)
                    " elapsed-ms=" (::task-elapsed-ms result)
                    " task=" (str/join "," (::task-symbols task))))
    ;; A RED CARRIES ITS SUSPECTS. The tasks that ran EARLIER in this same
    ;; worker and left process-global state behind are the only candidates
    ;; for "green alone, red in the pool", so the verdict names them instead
    ;; of leaving the reader with a victim and no leaker.
    (cond-> (assoc result ::executed-by (::worker-id worker))
      (and (task-red? result) journal (seq @journal))
      (assoc ::prior-ambient-drift
             (filterv #(not= (::task-symbols task) (::task-symbols %))
                      @journal)))))

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
                (on-caller-loader
                 (fn []
                   (drain-worker-tasks!
                    ;; a retired worker must stop TAKING: consuming the
                    ;; queue after retirement converted one bound firing
                    ;; into a cascade of attributed failures for every
                    ;; remaining task (observed: 1 bound -> 22 errors on
                    ;; a one-worker pool). Leftovers stay queued for the
                    ;; other workers or the serial fallback below.
                    (fn []
                      (when-not @(::worker-retired? worker)
                        (let [task (.take queue)]
                          (cond
                            (identical? finished task) nil
                            @(::worker-retired? worker)
                            (do (.put queue task) nil)
                            :else task))))
                    #(execute-worker-task! progress worker %))))))
             workers)
            ;; The serial remainder uses the same sequential worker loop,
            ;; without a second scheduler or concurrent command on its root.
            serial-future
            (when (seq unresolved-tasks)
              (.submit
               executor
               ^java.util.concurrent.Callable
               (on-caller-loader
                (fn []
                  (mapv #(execute-worker-task! progress serial-worker %)
                        unresolved-tasks)))))
            parallel-results (mapcat #(.get %) parallel-futures)
            serial-results (if serial-future (.get serial-future) [])
            ;; tasks left behind by retired workers: one bounded wave on
            ;; the serial worker when it is alive; otherwise a typed
            ;; pool-exhausted result per task — never a silent drop
            leftovers (loop [tasks []]
                        (let [entry (.poll queue)]
                          (cond
                            (nil? entry) tasks
                            (identical? finished entry) (recur tasks)
                            :else (recur (conj tasks entry)))))
            leftover-results
            (when (seq leftovers)
              (if (and serial-worker
                       (not @(::worker-retired? serial-worker)))
                (mapv #(execute-worker-task! progress serial-worker %)
                      leftovers)
                (mapv (fn [task]
                        (let [test-symbols (mapv str (::task-symbols task))
                              message
                              (str "Worker pool exhausted before this task"
                                   " could run; every pool worker retired.")]
                          (assoc task
                                 ::task-summary
                                 {::test-count (count test-symbols)
                                  ::pass-count 0
                                  ::fail-count 0
                                  ::error-count (count test-symbols)}
                                 ::task-results
                                 (mapv (fn [test-symbol]
                                         #:seon.test{:sym test-symbol
                                                     :pass-count 0
                                                     :fail-count 0
                                                     :error-count 1
                                                     :failure-message message})
                                       test-symbols)
                                 ::task-output (str message "\n")
                                 ::worker-pool-exhausted true)))
                      leftovers)))]
        (vec (concat parallel-results serial-results leftover-results)))
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
          (::injected? (ex-data failure)) (assoc ::injected? true)
          underlying-kind (assoc ::underlying-failure-kind underlying-kind)
          (ex-data failure) (assoc ::failure-data (ex-data failure)))]
    (println "bin/test: confirmation unconfirmed"
             (when (::injected? (ex-data failure)) "[INJECTED FIXTURE]")
             (str/join "," (::task-symbols task-result))
             "worker=" (::worker-id launch)
             "kind=" failure-kind)
    (assoc task-result
           ::parallel-failure :unconfirmed
           ::confirmation-failure failure-fact)))

(defn- parallel-failure-classification
  "How one pool red is classified once it has been re-run in isolation.

  A TASK WHOSE WORKER DIED IS NEVER `parallel-only`. Its pool \"result\" was
  manufactured by the exchange, not produced by a test, so classifying it by
  whether it passes in isolation attributes a dead worker to whichever
  namespaces it happened to hold — which is exactly how the re-arm class cost
  a day of someone else's diagnosis
  (`docs/seon/issues/the-test-runners-re-arm-kills-the-worker-under-its-own-contract.md`).
  It stays red, and it stays named as an exchange failure."
  [task-result confirmation]
  (cond
    (::worker-exchange-result task-result) :worker-exchange
    (task-red? confirmation) :reproducible
    :else :parallel-only))

(defn- confirm-parallel-failure!
  [namespaces progress task-result]
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
      ;; THE CONFIRMATION LOADS THE POOL WORKER'S WORLD. It used to load ONE
      ;; namespace, so a test whose subject depends on what is LOADED — the
      ;; program graph, the acquired SCI ctx's bindings, which capability
      ;; namespaces resolve — was answering a different question in the
      ;; confirmation than in the pool. `parallel-only` then meant "green in a
      ;; smaller world", which is not evidence about scheduling at all, and
      ;; thirteen `seon.sci.eval-test` verdicts read that way
      ;; (`docs/seon/issues/thirteen-sci-eval-reds-appear-only-under-the-whole-gate.md`).
      ;; Loading the same set leaves exactly ONE difference — the task runs
      ;; alone — so the verdict is about the thing it names.
      (initialize-worker! worker namespaces)
      (announce! progress
                 (str "CONFIRM isolated task="
                      (str/join "," (::task-symbols task))
                      " loaded-namespaces=" (count namespaces)))
      (let [confirmation (execute-worker-task! progress worker task)
            ;; A TASK WHOSE WORKER DIED IS NEVER `parallel-only`. Its pool
            ;; "result" was manufactured by the exchange, not produced by a
            ;; test, so classifying it by whether it passes in isolation
            ;; attributes a dead worker to whichever namespaces it held —
            ;; which is exactly how the re-arm class cost a day of someone
            ;; else's diagnosis. It stays red, and it stays named as an
            ;; exchange failure.
            classification (parallel-failure-classification
                            task-result confirmation)
            suspects (::prior-ambient-drift task-result)]
        (println "bin/test: confirmation" (name classification)
                 (str/join "," (::task-symbols task))
                 (if (= :parallel-only classification)
                   (str "worker=" (::executed-by task-result))
                   ""))
        ;; A `parallel-only` verdict that names only its victim sends the
        ;; reader to the wrong owner: the class is an EARLIER task in the same
        ;; worker leaving process-global state behind, so the verdict carries
        ;; the tasks that actually changed it.
        (when (and (= :parallel-only classification) (seq suspects))
          (println "bin/test:   suspected leakers, earlier in worker"
                   (::executed-by task-result) "—")
          (doseq [{symbols ::task-symbols drift ::task-ambient-drift}
                  suspects]
            (println "bin/test:    " (str/join "," symbols)
                     (pr-str (vec (sort (keys drift)))))))
        (when (and (= :parallel-only classification) (empty? suspects))
          (println "bin/test:   no worker-global state changed before this"
                   "task in worker" (::executed-by task-result)
                   "— the hazard is not ambient state this worker can see"))
        (cond-> (assoc task-result
                       ::parallel-failure classification
                       ::confirmation-result confirmation)
          (and (= :parallel-only classification) (seq suspects))
          (assoc ::parallel-only-suspects
                 (mapv ::task-symbols suspects))))
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
                           (on-caller-loader
                            (fn []
                              (try
                                (confirm! progress result)
                                (catch InterruptedException failure
                                  (.interrupt (Thread/currentThread))
                                  (throw failure))
                                (catch Throwable failure
                                  (unconfirmed-confirmation
                                   result failure))))))]))
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
  ;; EVERY TIER'S TALLY IS TOTAL. A worker that died, a bound that fired, a
  ;; pool that emptied and a task nobody could confirm are each their own
  ;; typed line naming the task — never a quiet per-namespace red that sends
  ;; the reader to the wrong owner.
  (let [worker-deaths
        (sort-by ::task-ordinal
                 (filter ::worker-exchange-result task-results))]
    (when (seq worker-deaths)
      (println)
      (println "Worker exchange failures —" (count worker-deaths)
               "task(s) whose worker died, was bounded, or refused;"
               "these reds belong to the exchange, not to the tests:")
      (doseq [task-result worker-deaths]
        (let [exchange (::worker-exchange-result task-result)]
          ;; ABSENT IS NO KEY, in the tally too: a retired worker never
          ;; published an exit code and never opened a log, and printing
          ;; `exit= log=` claims two facts the runner does not have.
          (println (str/join
                    " "
                    (into [" -" (str/join "," (::task-symbols task-result))
                           (str "worker=" (::worker-id exchange))
                           (str "kind=" (:seon.error/kind exchange))]
                          (remove nil?)
                          [(when-let [exit (::worker-exit exchange)]
                             (str "exit=" exit))
                           (when-let [log (::worker-error-log exchange)]
                             (str "log=" log))])))))))
  (let [exhausted (sort-by ::task-ordinal
                           (filter ::worker-pool-exhausted task-results))]
    (when (seq exhausted)
      (println)
      (println "Unlaunchable tasks —" (count exhausted)
               "task(s) never ran because every pool worker had retired:")
      (doseq [task-result exhausted]
        (println " -" (str/join "," (::task-symbols task-result))))))
  (let [unconfirmed
        (sort-by ::task-ordinal
                 (filter #(= :unconfirmed (::parallel-failure %))
                         task-results))]
    (when (seq unconfirmed)
      (println)
      (println "Unconfirmed tasks —" (count unconfirmed)
               "task(s) whose isolated confirmation could not run:")
      (doseq [task-result unconfirmed]
        (let [failure (::confirmation-failure task-result)]
          (println " -" (str/join "," (::task-symbols task-result))
                   "worker=" (::worker-id failure)
                   (when (::injected? failure) "[INJECTED FIXTURE]")
                   "kind=" (:seon.error/kind failure))))))
  (let [parallel-only
        (sort-by ::task-ordinal
                 (filter #(= :parallel-only (::parallel-failure %))
                         task-results))]
    (when (seq parallel-only)
      (println)
      (println "Parallel-only tasks —" (count parallel-only)
               "task(s) red in a pooled worker and green in isolation;"
               "each names the earlier tasks in its worker that changed"
               "process-global state:")
      (doseq [task-result parallel-only]
        (println " -" (str/join "," (::task-symbols task-result))
                 "worker=" (::executed-by task-result)
                 "suspected-leakers="
                 (if-let [suspects (::parallel-only-suspects task-result)]
                   (pr-str (mapv #(str/join "," %) suspects))
                   "none — no ambient state changed before it")))))
  (let [drifting (sort-by ::task-ordinal
                          (filter ::task-ambient-drift task-results))]
    (when (seq drifting)
      (println)
      (println "Tasks that changed worker-global state —" (count drifting)
               "(AGENTS §5.7: own nothing global):")
      (doseq [task-result drifting]
        (println " -" (str/join "," (::task-symbols task-result)))
        (doseq [[member drift] (sort-by key (::task-ambient-drift task-result))]
          ;; NAME WHAT CHANGED, not only which member. "3 wrappers removed"
          ;; sends the reader nowhere; "seon.db/pull removed" is the fix.
          (println "    " member
                   (str/join
                    " "
                    (into []
                          (remove nil?)
                          [(when-let [added (::drift-added drift)]
                             (str "added " (::drift-added-count drift) ": "
                                  (str/join ", " added)))
                           (when-let [removed (::drift-removed drift)]
                             (str "removed " (::drift-removed-count drift) ": "
                                  (str/join ", " removed)))
                           (when-let [changed (::drift-changed drift)]
                             (str "changed: " (str/join ", " changed)))]))))))))

(defn- finish-run!
  [{summary ::summary
    task-results ::task-results
    run-result ::run-result
    skipped ::skipped
    selection-mode ::selection-mode
    git-sha ::git-sha
    bulk ::bulk
    record-results! ::record-results!
    recording-label ::recording-label}]
  (let [green? (zero? (+ (::fail-count summary) (::error-count summary)))
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
    (when record-results!
      (when-let [failure (recording-failure record-results!)]
        (println (str "bin/test: " recording-label " NOT recorded:")
                 (:seon.error/kind failure)
                 (:seon.error/message failure))))
    (flush)
    (if green? 0 1)))

(defn- run-parallel-stage!
  [namespaces progress manifest workers serial-worker tasks]
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
                     (partial confirm-parallel-failure! namespaces))]
      (print-task-failures! confirmed)
      {::task-results confirmed
       ::task-summary (summarize-task-results confirmed)})))

(defn- run-coordinator!
  "Run selected tests with progress and a liveness backstop.

  Every tiered invocation runs the declared `:seon.test/platform` moving-part
  regressions FIRST and stops there when they are red. The bulk tier follows:
  every eligible test under `all`/`full`, or only the tests reaching code
  changed since the last recorded GREEN basis under the bare `changed`
  default. Record results in either the explicitly named non-default cluster
  or the persistent operator-owned branch selected by the launcher, then exit
  zero exactly when no test failed or errored."
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
                (on-caller-loader
                 (fn []
                   (let [checkout (worker-checkout worker-id)
                         worker (start-worker! worker-id checkout checkout)]
                     (swap! workers* conj worker)
                     (initialize-worker! worker namespaces))))))
             worker-ids)]
        ;; Coordinator namespace loading and manifest construction overlap the
        ;; workers' JVM startup and namespace loading.
        (doseq [[index test-namespace] (map-indexed vector namespaces)]
          (announce! progress
                     (str "LOAD " (inc index) "/" (count namespaces)
                          " " test-namespace))
          ;; The worker-launch virtual threads lazily load through
          ;; `requiring-resolve`, which serializes on REQUIRE_LOCK — a
          ;; bare `require` here raced them, interleaving `*loaded-libs*`
          ;; so whichever source-compiled dependency lost the race failed
          ;; with 'namespace not found' (observed live: sci, then
          ;; clj-kondo's inlined tools.reader). One lock, both entries.
          (locking clojure.lang.RT/REQUIRE_LOCK
            (require test-namespace))
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
              (run-parallel-stage! namespaces progress manifest pool-workers
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
                  (run-parallel-stage! namespaces progress manifest
                                       pool-workers serial-worker
                                       selected-tasks)))
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
              persistent-root
              (configured-persistent-results-root
               (System/getProperty "seon.test.persistent-results-root")
               (System/getenv "SEON_TEST_RESULT_ROOT"))
              record-results!
              (cond
                (not= "-" cluster-name)
                #(record! {:seon.test.runner/run-result run-result
                           :seon.boot/cluster-name cluster-name
                           :seon.boot/root root})

                persistent-root
                #(record-persistent-results! persistent-root run-result)

                :else nil)
              recording-label
              (if (not= "-" cluster-name)
                "result-cluster results"
                "persistent results")]
          (finish-run!
           {::summary summary
            ::task-results task-results
            ::run-result run-result
            ::skipped skipped
            ::selection-mode selection-mode
            ::git-sha git-sha
            ::bulk bulk
            ::record-results! record-results!
            ::recording-label recording-label})))
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
