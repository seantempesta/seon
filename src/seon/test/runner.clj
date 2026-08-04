(ns seon.test.runner
  "Run the JVM gate and optionally commit per-test result facts."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [seon.config :as config]
            [seon.db :as db])
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

(defn- long-reason
  [test-var]
  (let [var-metadata (meta test-var)
        namespace-metadata (meta (:ns var-metadata))
        marker (if (contains? var-metadata :seon.test/long)
                 (:seon.test/long var-metadata)
                 (:seon.test/long namespace-metadata))]
    (when (some? marker)
      (when-not (and (string? marker) (not (str/blank? marker)))
        (throw
         (ex-info
          ":seon.test/long must contain a non-blank reason."
          {:seon.error/kind ::invalid-long-reason
           :seon.test/sym (str (var-symbol test-var))
           :seon.test/long marker})))
      marker)))

(defn- test-selection
  [namespaces include-long?]
  (reduce
   (fn [selection test-var]
     (let [reason (long-reason test-var)]
       (if (and (not include-long?) reason)
         (update selection ::skipped conj
                 {::test-symbol (var-symbol test-var)
                  ::reason reason})
         (update selection ::selected conj test-var))))
   {::selected [] ::skipped []}
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
      (let [summary (assoc @test/*report-counters* :type :summary)]
        (test/do-report summary)
        summary))))

(defn- run-request!
  [request progress selected-vars]
  (let [selected-namespaces (set (:seon.test.runner/namespaces request))
        options (select-keys
                 (config/defaults)
                 [:seon.config.eval.result/blob-threshold
                  :seon.print/length
                  :seon.print/level])
        capture (atom {::order [] ::results {}})
        reported-signatures (atom #{})
        default-report test/report
        raw-summary
        (binding [test/report
                  (fn [event]
                    (capture-event! options capture selected-namespaces event)
                    (when progress
                      (progress-event! progress event))
                    (report-event! options default-report
                                   reported-signatures event))]
          (if selected-vars
            (run-selected-tests (:seon.test.runner/namespaces request)
                                selected-vars)
            (apply test/run-tests (:seon.test.runner/namespaces request))))
        summary
        {::test-count (:test raw-summary)
         ::pass-count (:pass raw-summary)
         ::fail-count (:fail raw-summary)
         ::error-count (:error raw-summary)}]
    {:seon.test.run/id (:seon.test.run/id request)
     :seon.test.run/at (:seon.test.run/at request)
     :seon.test.run/git-sha (:seon.test.run/git-sha request)
     :seon.test.runner/summary summary
     :seon.test.runner/results (captured-results @capture)}))

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

(defn -main
  "Run selected tests with progress and a liveness backstop.

  Record results when the cluster argument names a non-default cluster, then
  exit zero exactly when no test failed or errored."
  {:malli/schema
   [:=> [:cat :seon.boot/cluster-name :seon.boot/root :string :string
         [:* :string]]
    :nil]}
  [cluster-name root git-sha selection-mode & namespace-names]
  (when-not (contains? #{"default" "full"} selection-mode)
    (throw
     (ex-info
      "The test selection mode must be default or full."
      {:seon.error/kind ::invalid-selection-mode
       ::selection-mode selection-mode})))
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
      (let [{::keys [selected skipped]}
            (test-selection namespaces (= "full" selection-mode))
            run-result
            (run-request! {:seon.test.runner/namespaces namespaces
                           :seon.test.run/id (str (random-uuid))
                           :seon.test.run/at (java.util.Date.)
                           :seon.test.run/git-sha git-sha}
                          progress
                          (when (= "default" selection-mode) selected))
            _ (when-not (= "-" cluster-name)
                (record! {:seon.test.runner/run-result run-result
                          :seon.boot/cluster-name cluster-name
                          :seon.boot/root root}))
            summary (:seon.test.runner/summary run-result)
            failures (->> (:seon.test.runner/results run-result)
                          (filter #(= :fail (:seon.test.result/outcome %)))
                          (map :seon.test/sym)
                          sort)]
        (when (seq failures)
          (println "\nFailing tests:")
          (doseq [test-symbol failures]
            (println " -" test-symbol)))
        (print-skipped! skipped)
        (flush)
        (System/exit
         (if (zero? (+ (::fail-count summary) (::error-count summary))) 0 1)))
      (finally
        (.shutdownNow backstop)))))
